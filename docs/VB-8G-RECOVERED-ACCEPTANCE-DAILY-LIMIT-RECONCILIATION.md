# VB-8G — Recovered Acceptance & Daily-Limit Reconciliation

**F-8F-01 is CLOSED.** The acceptance/bucket invariant is demonstrated by
PostgreSQL-backed tests, not asserted. See §16 for the direct answer.

---

## 1. Baseline

| | |
|---|---|
| `HEAD` | `3a89e5c` |
| `origin/main` | `3a89e5c` |
| Divergence | `0 / 0` |
| Working tree | 217 dirty entries (VB-7C.3 → VB-8F uncommitted, plus user-owned FreeSWITCH and frontend work) |
| Test baseline | 2095 (0F / 0E / 2S) |
| Architecture baseline | 16 modules / 0 cycles |
| Migration baseline | `V55`, 54 files |

No reset, clean, stash, commit or push. All pre-existing entries preserved.
(The one staged entry is the user's `frontend/` `git mv`; untouched.)

---

## 2. Audit

### 2.1 Why `confirmUsed` cannot reconcile a recovered call

`confirmUsed` is:

```sql
UPDATE voice_blast_daily_usage
SET used_count = used_count + 1, reserved_count = reserved_count - 1
WHERE tenant_id = :t AND contact_id = :c AND did_id = :d AND usage_date = :day
  AND reserved_count > 0        -- <-- the hold guard
```

The guard exists for a good reason: `confirmUsed` *converts a hold*. It is only
safe because the dial path always holds a reservation when it confirms, so a
spurious confirm cannot inflate usage.

For a recovered acceptance the hold **does not exist** — it was granted inside
TX-2, which rolled back. So the statement matched zero rows, returned 0, and the
bucket never moved. VB-8F called `confirmAccepted`, which therefore wrote the
per-attempt ledger row while leaving `used_count` untouched. The bucket was
permanently one behind a real, provider-accepted call, and `reserve`'s
`reserved_count + used_count < limit` check then permitted one dial too many.

### 2.2 What persisted evidence proves the call was accepted

The recovery's own evidence chain (VB-8F, unchanged here): a `CHANNEL_HANGUP` whose
`variable_origination_uuid` equals the channel identity, naming an attempt that is
`IN_PROGRESS` with no `CallSession`. A channel that FreeSWITCH created and hung up
is *stronger* evidence of provider acceptance than the `+OK` the normal path settles
on, so this is not a guess. Tenant, contact and the actual route DID come from the
persisted attempt; the usage day comes from the same frozen snapshot the dial path
used.

### 2.3 How the bucket is keyed

`(tenant_id, contact_id, did_id, usage_date)` — unique via `uq_vbdu_bucket` (V47).
`used_count` is defined by V47 as *"Provider-ACCEPTED dials (+OK <uuid>) for this
bucket… ringing/busy/no-answer/failure AFTER acceptance still counts."* Platform
ceiling 3, campaign limit 1..3.

### 2.4 How a recovered acceptance maps to that same bucket

Through the **same key and the same two tables**. No new quota structure, no second
ledger, no new reservation lifecycle. The DID is the attempt's frozen `didId` — the
actual route DID, the same value the dial path passes to `admit`.

### 2.5 Idempotency key

`call_attempt_id`, enforced by the pre-existing
`CONSTRAINT uq_vbdue_call_attempt UNIQUE (call_attempt_id)` (V47). V47's own comment
calls this table the *"PHYSICAL PER-ATTEMPT LEDGER"* whose unique key makes
double-counting *"physically impossible at the database boundary, independent of any
application guard"*. That guarantee is reused, not reinvented.

### 2.6 Concurrency invariant

`used_count` must equal the number of distinct ledger rows for that bucket, and a
concurrent admission's `reserved_count + used_count < limit` check must observe any
reconciled usage. Both writes therefore run in one transaction, and the increment is
performed **only** by the worker that wins the unique insert.

---

## 3. Design

Three changes, all additive, all in the existing VB-6C domain:

| # | Change | Purpose |
|---|---|---|
| 1 | `VoiceBlastDailyUsageEntryRepository.insertIfAbsent(...)` | `INSERT … ON CONFLICT (call_attempt_id) DO NOTHING`, returning 1 iff this caller created the row. **The idempotency gate.** |
| 2 | `VoiceBlastDailyUsageRepository.countRecoveredUsed(...)` | `used_count = used_count + 1` for the bucket, with **no** hold guard and **no** limit guard. |
| 3 | `DailyDialLimitService.reconcileRecoveredAcceptance(...)` | Ensures the bucket row, takes the gate, increments only if it won. |
| 4 | `OrphanedDispatchRecovery.recordAcceptance` | Switches from `confirmAccepted` to the above. |

### 3.1 Why the increment has no guard — both absences are load-bearing

- **No `reserved_count > 0` guard.** That is the bug being fixed. It also cannot
  corrupt the hold count, because `reserved_count` is never written here, so
  `ck_vbdu_reserved_non_negative` cannot be violated.
- **No `< :effectiveLimit` guard.** Admission is the only operation allowed to
  refuse a slot. This is not an admission — the provider already accepted the call.
  Refusing would erase a factual acceptance *and* permit further dials. The bucket
  is therefore allowed to exceed the limit, and subsequent admissions observe the
  raised `used_count` and stop on their own (test 17).

### 3.2 Why the ledger row is inserted *before* the increment

The brief warns against `SELECT` → `INSERT` → `UPDATE`. The implementation does not
select at all. The unique insert **is** the gate, and it happens first:

```
ensureBucketRow()                      -- ON CONFLICT DO NOTHING, never raises
INSERT ledger ... ON CONFLICT (call_attempt_id) DO NOTHING
   └─ 0 rows → duplicate event, return false, count nothing
   └─ 1 row  → the only caller that may count
UPDATE bucket SET used_count = used_count + 1
```

`ON CONFLICT DO NOTHING` is used rather than save-then-catch for the reason
`insertBucketRow` already documents: a unique violation would mark the transaction
rollback-only and poison the follow-up increment, whereas this form never raises and
never aborts.

**Note the deliberate difference from `confirmAccepted`**, which increments *then*
inserts and catches `DataIntegrityViolationException`. That ordering is safe there
and is left untouched, because a hold is the scarce resource: the losing racer's
guarded `confirmUsed` matches no row, so it counted nothing to roll back. For an
*unguarded* increment that argument collapses — the loser would have counted — so
the gate must come first. Exactly-once is therefore a physical database property in
the recovery path, not exception-driven bookkeeping.

### 3.3 Transaction boundary

Both writes run in the caller's transaction (`OrphanedDispatchRecovery.recover` is
`@Transactional`, and `EslEventService.processEvent` is transactional and joins it).
The ledger row and the bucket movement therefore commit together or not at all — the
bucket can never disagree with the ledger it summarises. **No external I/O is
introduced**, and no `uuid_exists`-style probe is used.

### 3.4 Not an admission

`reconcileRecoveredAcceptance` never calls `admit`, never grants or returns a hold,
and creates no reservation lifecycle. VB-6D.3 `DailyAttemptSafetyService` is **not
touched**: the rolled-back attempt consumption is a separate, already-correct fact
(it is never over-consumed, because consumption only ever happened inside the
transaction that rolled back). It is recorded here for completeness, not changed.

---

## 4. Database / Migration

**None. V47 is sufficient — no migration was added.** Head remains `V55`, 54 files.

| Requirement | Provided by V47 | Reused |
|---|---|---|
| Bucket keyed `(tenant, contact, did, day)` | `uq_vbdu_bucket` | ✅ |
| `used_count` counter with non-negative check | `ck_vbdu_used_non_negative` | ✅ |
| Exactly-once identity | `uq_vbdue_call_attempt UNIQUE (call_attempt_id)` | ✅ |
| Atomic bucket movement | single-statement conditional `UPDATE` | ✅ |

A migration would have added nothing except a duplicate of an existing guarantee.

---

## 5. API / OpenAPI

**None.** No controller, request, response, status code or validation change. No
OpenAPI regeneration required. `reconcileRecoveredAcceptance` is an internal
service method, not a REST surface.

---

## 6. Files Changed

| File | Change |
|---|---|
| `campaign/VoiceBlastDailyUsageRepository.java` | + `countRecoveredUsed` |
| `campaign/VoiceBlastDailyUsageEntryRepository.java` | + `insertIfAbsent` |
| `campaign/DailyDialLimitService.java` | + `reconcileRecoveredAcceptance` |
| `campaign/OrphanedDispatchRecovery.java` | `recordAcceptance` now reconciles instead of confirming |
| `test/…/RecoveredAcceptanceReconciliationPostgresIntegrationTest.java` | **new**, 17 tests |
| `test/…/OrphanedDispatchRecoveryPostgresIntegrationTest.java` | one assertion reversed (see §7) |

No refactor of unrelated code. VB-6C admission, VB-6D retry policy, VB-6D.3, the
scheduler, ESL, routing, capacity, DND/whitelist and campaign configuration are all
untouched.

---

## 7. One test assertion was reversed, deliberately

`OrphanedDispatchRecoveryPostgresIntegrationTest.acceptanceDoesNotInflateTheBucket`
asserted `usedCount == 0` and its name documented it as *"no hold survives, so usage
is not fabricated"*. That test **pinned the F-8F-01 defect on purpose**, so that the
boundary could not drift silently.

It is replaced by `recoveredAcceptanceCountsAgainstTheBucket`, asserting
`usedCount == 1`, with a comment explaining that the reversal is the point of this
phase. No other existing test was changed, weakened, disabled or skipped. No
`@Disabled`, no Surefire exclusion.

---

## 8. Test Matrix

`RecoveredAcceptanceReconciliationPostgresIntegrationTest` — **17 tests**, real
PostgreSQL (Flyway V1..V55), pinned clock so "today" is deterministic.

| # | Test | Asserts |
|---|---|---|
| 1 | `firstReconciliationCountsOnce` | ledger written, `used_count == 1` |
| 2 | `duplicateReconciliationDoesNotDoubleCount` | 3 identical events → still 1 |
| 3 | `directDoubleReconcileIsIdempotent` | second call returns `false` |
| 4 | `concurrentDuplicateReconciliationCountsOnce` | **8 threads**, one attempt → exactly 1 winner, `used_count == 1` |
| 5 | `concurrentDistinctRecoveriesOnOneBucketAllCount` | 6 threads × 6 attempts on one bucket → 6, no lost update |
| 6 | `admissionObservesReconciledUsage` | limit 2, one reconciled + 2 admissions → exactly 1 admitted |
| 7 | `admissionCannotSlipPastConcurrentRecovery` | 4-thread recovery then admission at limit 1 → refused |
| 8 | `recoveryAtLimitStillRecords` | bucket at 3/3 → reconciliation still counts → **4** |
| 9 | `admissionStopsAfterOverLimitBucket` | over-limit bucket → `reserve` 0 rows and `admit` returns `DAILY_LIMIT_REACHED` |
| 10 | `keyIsReconstructedFromPersistedContext` | ledger row's tenant/contact/DID/date/providerCallId all match the attempt |
| 11–15 | `nullTenant/Contact/Did/UsageDate/AttemptIdFailsClosed` | each incomplete key → `false`, `used_count == 0`, no ledger row |
| 16 | `crossTenantReconciliationCannotLeak` | tenant A's bucket stays empty; only B credited |
| 17 | `terminalAttemptIsNotReconciled` | `CANCELLED` attempt → not resurrected, no usage invented |

---

## 9. Concurrency Guarantees

| Scenario | Mechanism | Test |
|---|---|---|
| Two workers, same attempt | `UNIQUE (call_attempt_id)` — one insert wins, only it increments | 4 |
| Two workers, different attempts, same bucket | bucket row lock serialises the increments; no lost update | 5 |
| Recovery + normal admission | both write the same row; `reserve` re-reads under that lock | 6, 7 |
| Redelivered event | gate returns 0; nothing counted | 2, 3 |

No `synchronized`, no advisory lock, no Redis, no sweeper, no new scheduler. The
existing single-statement conditional `UPDATE` pattern is preserved exactly.

---

## 10. Failure Behaviour

- **Incomplete bucket key** (any of tenant/attempt/contact/DID/date null) → warn and
  return `false`. No bucket row, no ledger row, no usage. Fail closed; never fabricate.
- **Execution or snapshot missing** → warn, skip reconciliation, keep the settled
  attempt. The real telephony outcome is never discarded because of an accounting
  write.
- **Any other failure** → logged at `ERROR` with the explicit text *"the VB-6C bucket
  is now short by one real dial for today and must be corrected manually"*, then
  swallowed so the recovered attempt stays settled. A real accepted call is never
  silently dropped.

Recovery still never attributes a foreign channel, crosses tenants, invents a DID or
contact, counts without acceptance evidence, double-counts, resurrects a terminal
attempt, creates a second call, invokes normal admission, or alters retry
classification or count — all inherited unchanged from VB-8F and covered by its tests.

---

## 11. Verification

| Scope | Result |
|---|---|
| VB-8G focused (PostgreSQL) | `RecoveredAcceptanceReconciliationPostgresIntegrationTest` — **17 run / 0F / 0E / 0S** |
| VB-8F regression | `OrphanedDispatchRecoveryPostgresIntegrationTest` 15 + `OrphanedDispatchRecoveryEslTest` 17 — all green |
| **VB-6C regression** (9 classes: daily dial limit, snapshot, observability, validation, daily attempt concurrency/safety) | **115 run / 0F / 0E / 0S** |
| Full suite | **see §13** |
| Architecture | **16 modules / 0 cycles** |
| Migration | `V55`, 54 files, **0 added** |
| API / OpenAPI | **unchanged** |
| Git | `HEAD` `3a89e5c`; **no commit, no push**; no reset/clean/stash |

The 2 pre-existing skips are the unchanged baseline.

---

## 12. Deferred

Nothing in F-8F-01's scope remains. F-8F-02 and F-8F-03 (from VB-8F) remain
deferred and unchanged: operator-level forgery via raw ESL socket access, and
non-terminal events on a lost dispatch being ignored. Neither is a quota-accounting
concern, and neither is addressed here.

---

## 13. Direct Answer

> **Can a recovered provider-accepted call now cause VB-6C `used_count` to remain
> permanently one behind reality?**

**No.**

Demonstrated, not asserted:

- A recovered acceptance is counted — `firstReconciliationCountsOnce` observes
  `used_count == 1` from a real pinned `CHANNEL_HANGUP` on a claimed, session-less
  attempt.
- It is counted **once** — three redelivered events, and eight concurrent workers on
  the same attempt, both leave `used_count == 1`, with exactly one winner reported.
- It is counted for **every** recovered call — six concurrent distinct recoveries on
  one bucket yield `used_count == 6`, no lost update.
- It is counted **even at the limit** — a bucket at 3/3 becomes 4, because a factual
  acceptance cannot be refused; and the *next* admission is then correctly refused
  (`admit` → `DAILY_LIMIT_REACHED`), which is the enforcement that was previously
  leaking.
- It is counted only with **complete, persisted** evidence — any missing key element
  produces no ledger row and no usage.
- It is counted only for the **owning tenant** — tenant A's bucket is never credited
  for tenant B's call.

The invariant, in one line: **every provider-accepted call for a
(tenant, contact, actual route DID, calendar day) bucket appears in `used_count`
exactly once, whether observed normally or recovered after a lost write.**

**F-8F-01: CLOSED.**

---

## 14. Verdict

**READY.** F-8F-01 is closed with PostgreSQL-backed evidence, no migration, no API
change, no architectural change, and no regression to VB-6C, VB-6D.3 or VB-8F.
