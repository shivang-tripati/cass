# VB-8E — Scheduler Durability, Crash Recovery & Dispatch Consistency

Status: **COMPLETE WITH DEFERRED FINDINGS**

---

## 1. Baseline

| | |
|---|---|
| `HEAD` | `3a89e5c` |
| `origin/main` | `3a89e5c` |
| Divergence | `0 / 0` |
| Effective baseline | **current working tree** (VB-7C.3 + VB-8B + VB-8D uncommitted, plus user-owned FreeSWITCH and frontend work) |
| Test count entering VB-8E | 2048 (0F / 0E / 2S) |
| Migration head | `V55` (54 files) |
| Architecture | 16 modules / 0 cycles |
| Staged / stashed | 0 / 0 |

No reset, clean, stash, commit or push was performed. Every pre-existing dirty file is preserved.

---

## 2. Audit scope

Read in full, in addition to the three prior phase records
(`VB-8C-CAMPAIGN-EXECUTION-SCHEDULER-BOUNDARY-AUDIT.md`,
`VB-8D-SCHEDULER-TRANSACTION-BOUNDARY.md`,
`VB-8B-EXECUTION-CONFIGURATION-BOUNDARY.md`):

`CampaignExecutionOrchestrator`, `processDueAttempts`, `dispatchClaimedAttempt`,
`OutboundDialService`, `CallAttemptService`, `CallAttemptRepository`,
`StaleCallReconciler`, `FreeSwitchOutboundDialer`, `EslEventService`,
`CampaignExecutionStatus`, `RetryPolicyService` / `RetryPolicySpec`,
`DailyDialLimitService`, `DailyAttemptSafetyService`, `VoiceCapacityServiceImpl`,
`CallSession`, `CallLeg`, the caller's calling-window calculator, the scheduler
selection queries, and every scheduler / reconciler / retry test.

The audit question was: *after an attempt is atomically claimed, what happens if the
process, JVM, transaction, FreeSWITCH connection or application dies at each point
before and after external dispatch?*

---

## 3. Crash-state matrix

VB-8D's per-item transaction model is the baseline:

```
processDueAttempts()                    NO TRANSACTION
   TX-1  claim    QUEUED -> IN_PROGRESS      (commits BEFORE the dial)
   TX-2  dispatch (capacity, VB-6C, VB-6D.3, session, leg, providerCallId)
   TX-3  recovery (only if TX-2 threw)
```

| # | Crash point | Resulting state | Recovery today (post-VB-8E) | Verdict |
|---|---|---|---|---|
| A | before claim | `QUEUED` | untouched by every sweep; still selected by the next tick | **PASS** |
| B | after TX-1 commits, before TX-2 | `IN_PROGRESS`, `started_at` set, **no session**, no `providerCallId` | **was permanently stuck** — now settled to `CANCELLED` by the new orphan sweep | **FIXED (F-8E-01)** |
| C | during dial, before provider accepted | same visible state as B | indistinguishable from B; settled the same way | PASS (conservative) |
| D | dial rejected | `FAILED` by the dispatch path, capacity released, no session | already correct | **PASS** |
| E | dial accepted, DB write lost | `IN_PROGRESS`, no session, **live FreeSWITCH channel** | attempt settled (B); the real call is unaccounted | **PARTIAL — F-8E-03 deferred** |
| F | dispatch committed, telephony never happened | `IN_PROGRESS` + session + `providerCallId` | stranded-session sweep, once it actually ran | **FIXED (F-8E-02)** |
| G | after provider call, hangup event lost | session live, attempt `IN_PROGRESS` | stranded-session sweep → `FAILED` / `STALE_ATTEMPT_RECONCILED` → retry policy decides | **PASS (now live)** |
| H | repeated crash on the same attempt | `CANCELLED` is terminal and never re-selected | converges | PASS |

---

## 4. Correlation / origination audit

**The deterministic identity is real and is the strongest property in this design.**

`FreeSwitchOutboundDialer` pins the channel UUID itself:

```java
String channelUuid = request.callAttemptId() == null ? null : request.callAttemptId().toString();
// ... FreeSWITCH origination_uuid channel variable
```

So the channel's `Unique-ID` **is** the `CallAttempt` id, and the `providerCallId`
persisted for the attempt is that same string. Every campaign outbound path carries
the attempt identity: the manual attempt API and the scheduler both derive the attempt
before any telephony call, and no path originates a campaign call without one. Agent
legs and inbound legs are distinguished upstream, before attempt correlation, via the
existing agent-leg and call-type branches in `EslEventService`.

The gap is not the identity — it is **when** it becomes durable. `providerCallId` is
written inside TX-2. A crash before that commit leaves FreeSWITCH holding a channel
whose `Unique-ID` names a perfectly identifiable attempt, while the database has no
column that says so. `EslEventService` resolves by `findByProviderCallIdAndDeletedAtIsNull`
only; the `Unique-ID`-equals-attempt-id property is never exploited. This is
**F-8E-03**, deferred (see §14).

---

## 5. Stale IN_PROGRESS reconciliation

- **Stale definition**: `started_at <= now - 5 min` (`STALE_ATTEMPT_THRESHOLD`, unchanged — no evidence justified moving it).
- **Statuses examined**: attempt `IN_PROGRESS`; session in `INITIATED/DIALING/RINGING/ANSWERED/PLAYING/PLAYBACK_COMPLETED`.
- **Authoritative timestamp**: `call_attempts.started_at`, stamped by the claim itself.
- **Tenant safety**: the sweep is a system job and scans all tenants; the `strandedAttemptSessions` predicate asserts `a.tenant_id = s.tenant_id`, and the new orphan predicate is attempt-driven, so a session can never be finalised against another tenant's attempt.
- **Cadence**: step 6 of 8, every 30 s tick.
- **Transaction shape**: read pass in `SUPPORTS readOnly`, one transaction per item — correct, and a slow provider teardown cannot hold the sweep open.
- **Race with live dispatch**: safe, because the threshold is 5 minutes and the dispatch transaction commits in seconds. Proven by **E3**, where four reconciler threads run 40 full sweeps each *during* a real dispatch: the attempt is never settled and the session is never lost.
- **Population split (new)**: attempts *with* a session belong to the stranded sweep; attempts *without* one are now handled by the orphan sweep. The two are mutually exclusive by construction — the orphan query is a `NOT EXISTS`, not a join — so no attempt can be settled twice.

### F-8E-02 (CRITICAL) — the stranded-attempt sweep had never executed

`strandedAttemptSessions` was:

```sql
SELECT DISTINCT s.id FROM call_sessions s JOIN call_attempts a ON ...
ORDER BY a.started_at
```

PostgreSQL rejects this outright: *for `SELECT DISTINCT`, ORDER BY expressions must
appear in the select list*, and `a.started_at` did not. The sweep therefore threw on
its **first real match** — and because the exception escaped `reconcile()`, it also
skipped every later sweep in the same pass. No test had ever put a stranded session in
front of this query, so an entire branch of production recovery logic had never run.

Fixed with `GROUP BY s.id` + `ORDER BY min(a.started_at)`: a session joins to at most
one attempt, so grouping by the primary key yields exactly the rows `DISTINCT` was
asking for, and the aggregate preserves the oldest-dispatch-first ordering the batch
limit depends on. Test **D** now exercises the path for the first time.

---

## 6. ESL / external side-effect boundary

**PostgreSQL and FreeSWITCH are not one atomic transaction, and this phase does not
pretend otherwise.** The actual boundary:

- The dialer returns `accepted` on FreeSWITCH's `+OK`. The `CallSession`, `CallLeg`, `providerCallId`, the capacity allocation, the VB-6C reservation and the VB-6D.3 consumption are all written afterwards, in TX-2.
- If TX-2 commits, everything downstream correlates through `providerCallId` and ESL events converge normally.
- If TX-2 rolls back, the platform has **no record that a call was ever placed**, and cannot ask FreeSWITCH whether one exists without an explicit protocol probe.

"No response ≠ no call" is respected. An unknown outcome is never converted into a
retryable failure, and never into a second dispatch.

---

## 7. Capacity recovery

`VoiceCapacityServiceImpl` is **DB-backed** (`SipGatewayAllocationRepository`), and the
reservation is taken inside TX-2. A crash between claim and dispatch rolls TX-2 back,
so the allocation is reverted with it — there is no capacity leak on this path, and the
new sweep therefore touches no capacity at all (test **B4**, `verifyNoInteractions`).

No second capacity authority was introduced.

---

## 8. Daily safety recovery

Business definitions are unchanged: VB-6C is tenant + contact + actual route DID +
calendar day and counts provider-accepted dials; VB-6D.3 is tenant + contact + calendar
day and counts dispatches.

Both `DailyDialLimitService.admit` and `DailyAttemptSafetyService.admit` are invoked
inside TX-2 (`admit` is `Propagation.MANDATORY`). A crash before TX-2 commits therefore
rolls back the reservation and the consumption together, which means recovery can
**never** double-consume, release something that must stay consumed, or consume
something that was never dispatched. The new orphan sweep deliberately performs no
ledger interaction whatsoever, because the crash left nothing to give back. Proven by
**B4**.

---

## 9. Retry safety

`processRetries` reads only `FAILED` attempts. The new recovery path writes
`CANCELLED`, which is terminal and outside the retry step's selection set.

This is the whole point, and it is asserted directly: **B2** proves an unknown external
outcome produces zero retriable attempts. Had it been written `FAILED`, the campaign's
own retry policy would have placed a **second real call** to a contact who may already be
receiving the first. Under-dialing is recoverable; double-dialing is not.

`STALE_ATTEMPT_RECONCILED` remains a dispatched outcome and therefore remains
retryable — that is pre-existing, intended behaviour, and unchanged.

---

## 10. Execution reconciliation

`reconcileExecutionInternal` is tenant-scoped, only acts on `RUNNING`, requires every
attempt to be in `TERMINAL_ATTEMPT_STATUSES` (`COMPLETED`, `FAILED`, `CANCELLED`), and
settles to `COMPLETED` if any attempt completed, else `FAILED`. It cannot move a
terminal execution backwards (**O**). `CANCELLED` being terminal is what lets a
recovered execution converge at all — proven by **B3**, where an all-`CANCELLED`
execution settles instead of hanging `RUNNING`.

**F-8E-05 (INFO)**: within a tick, `reconcile-executions` is step 5 and
`reconcile-stale-calls` is step 6, so an execution settles on the *following* 30 s tick
after its last attempt is recovered. A convergence latency of ≤30 s, not a correctness
issue; the steps were not reordered because doing so would change the established
scheduler ordering for no correctness gain.

---

## 11. Concurrency audit

| Race | Result |
|---|---|
| claim / claim | **F** — 6 workers, exactly 1 dial, 1 session |
| claim / reconcile | **E3** — 4 reconcilers × 40 sweeps during a live dispatch; no double-settle, no session loss |
| stale-reconcile / live dispatch | **E3** — the 5-minute threshold plus the in-transaction session re-check |
| session appears between read pass and finalise | **E2** — the finaliser re-checks and declines, leaving the attempt to the sweep that owns it |
| duplicate recovery | **H** — second pass declines; the attempt is no longer `IN_PROGRESS` |
| tenant cross-talk | **N** — only the targeted attempt settles; the other execution is untouched |
| terminal execution resurrection | **O** — settled attempt cannot move a `COMPLETED` execution back |

No `synchronized` block was used as a substitute for database correctness. Every
invariant above is enforced by a conditional SQL write and re-verified inside the
writing transaction.

---

## 12. Findings table

| ID | Sev | Path | Summary | VB-8D already solved? | Action |
|---|---|---|---|---|---|
| F-8E-01 | **CRITICAL** | `StaleCallReconciler` | Attempt claimed `IN_PROGRESS` but crashed before TX-2 created its session is invisible to every reconciler (both sweeps are `call_sessions`-driven). It is not `QUEUED` so it is never re-dialled, and it is not terminal so the execution never settles — a permanent wedge, and the contact is never called. Introduced by VB-8D's claim-before-dispatch ordering. | No — introduced by it | **Fixed** |
| F-8E-02 | **CRITICAL** | `StaleCallReconciler.strandedAttemptSessions` | `SELECT DISTINCT s.id … ORDER BY a.started_at` is invalid PostgreSQL. The entire stale-attempt recovery sweep threw on its first real match and aborted the rest of `reconcile()`. Never exercised by any test. | No — pre-existing since VB-6E | **Fixed** |
| F-8E-03 | **HIGH** | `EslEventService` / `FreeSwitchOutboundDialer` | A channel that really was placed but whose TX-2 rolled back cannot be correlated: `providerCallId` was never persisted, and the `Unique-ID == attempt id` property is not exploited. The real call is unaccounted. | No | **Deferred** (§14) |
| F-8E-04 | **MEDIUM** | `StaleCallReconciler.reconcile` | One throwing sweep aborts the remaining sweeps in the same pass. | No | **Deferred** (§14) |
| F-8E-05 | **INFO** | scheduler step order | Execution settles ≤30 s after its last attempt is recovered. | — | Documented |
| F-8E-06 | **INFO** | `STALE_ATTEMPT_THRESHOLD` | 5 minutes is generous for a single-transaction dispatch; no evidence to change it. | — | Unchanged, per instruction |
| F-8E-07 | **INFO** | capacity + VB-6C + VB-6D.3 | All DB-backed and inside TX-2; a crash rolls them back atomically. No leak, no phantom consumption. | Already correct | **PASS** |
| F-8E-08 | **INFO** | `FreeSwitchOutboundDialer` | `origination_uuid` is deterministic and equals the attempt id; no random identity anywhere in the campaign path. | Already correct | **PASS** |

---

## 13. Implemented changes

**`StaleCallReconciler.java`** — two changes, both inside the existing reconciler; no new
class, no new dependency, no new module.

1. **`finalizeOrphanedClaims` / `orphanedClaimedAttempts` / `finalizeOrphanedClaim`**
   (F-8E-01). A bounded, oldest-first, `NOT EXISTS`-based sweep for attempts that are
   `IN_PROGRESS`, past the stale threshold, and have **no** session. It settles them to
   `CANCELLED` with `CLAIMED_NOT_DISPATCHED`, re-checking both staleness and the absence
   of a session *inside* the finalising transaction. It performs no capacity, no VB-6C and
   no VB-6D.3 interaction, and never makes an attempt retryable.

2. **`strandedAttemptSessions` SQL** (F-8E-02). `GROUP BY s.id` + `ORDER BY min(a.started_at)`.

Both follow the file's existing conventions exactly: read-only `SUPPORTS` candidate
pass, one transaction per finalisation, `MAX_BATCH` limit, tenant-consistent predicates.
`CANCELLED` + a free-text code mirrors the VB-8D precedent (`ATTEMPT_PROCESSING_FAILED`)
rather than adding a `CallFailureCode` constant for a non-telephony outcome.

**`SchedulerCrashRecoveryPostgresIntegrationTest.java`** — new, 15 tests, real
PostgreSQL via Testcontainers, covering the required A–P matrix.

Zero API changes. Zero migrations. Zero new modules. Zero new public methods on any
service.

---

## 14. Deferred findings

### F-8E-03 (HIGH) — orphan-channel correlation

**Risk.** A crash between FreeSWITCH accepting a call and TX-2 committing leaves a real,
live call to a contact that the platform never records. The attempt is settled
conservatively as `CANCELLED` (so it is never retried and never double-dialled), but the
platform does not learn the outcome. Compliance reporting on that call is incomplete.

**Why it does not fit VB-8E.** The correlation data exists — `Unique-ID` *is* the attempt
id — but exploiting it means changing the telephony event boundary: a fallback in
`EslEventService` from "resolve by `providerCallId`" to "also parse this channel UUID as
an attempt id", **plus a settle path** for a hangup on an attempt that has no session,
because the existing handler expects one. That is a behavioural change to inbound,
agent-leg and outbound correlation alike, where a mis-parse would attribute a foreign
channel to a campaign attempt. Doing it properly needs its own phase.

**Recommended future phase.** `VB-8F — channel correlation and outcome recovery`: the
`Unique-ID` fallback, a settle path for session-less attempts, and an ESL
`uuid_exists` probe for the case where the channel may not exist either.

**Regression test to add then.** Reuse the `G` shape: place a call, roll back TX-2,
deliver a `CHANNEL_HANGUP` carrying `Unique-ID == attemptId`, and assert the attempt
settles with a real telephony outcome.

### F-8E-04 (MEDIUM) — per-sweep failure isolation in `reconcile()`

**Risk.** If any single sweep throws, the later sweeps in the same pass are skipped.
Bounded and self-healing — the next 30 s tick retries — but it delays recovery.

**Why it does not fit VB-8E.** Wrapping each sweep in `try/catch` would swallow exactly
the class of defect this phase just uncovered: F-8E-02 was a query that threw loudly on
every execution in production, and a per-sweep catch is the most likely way it would have
been buried. The right fix is isolation *with* loud per-item failure reporting, which is
a design change to the reconciler's error contract, not a wrap. Deliberately not done.

---

## 15. Tests

New class `SchedulerCrashRecoveryPostgresIntegrationTest` — **15 tests**, all against
real PostgreSQL (Flyway V1..V55):

| Test | Scenario |
|---|---|
| A | `QUEUED` attempt untouched by recovery, still dispatchable |
| B1 | claimed-with-no-session is settled, not left stuck |
| B2 | unknown external outcome produces **zero** retriable attempts |
| B3 | execution converges once every attempt is settled |
| B4 | recovery touches no capacity / VB-6C / VB-6D.3 ledger |
| C | rejected dial → terminal attempt, no providerCallId, capacity released |
| D | stranded attempt **with** a session keeps `STALE_ATTEMPT_RECONCILED` |
| E1 | a freshly claimed attempt is never swept |
| E2 | a session appearing before finalising is not stolen |
| E3 | reconcile storm racing a real dispatch — no double-settle, no session loss |
| F | 6 concurrent workers → exactly 1 dispatch, 1 session |
| G | crash before the `providerCallId` write leaves the channel uncorrelatable |
| H | settling an orphaned claim twice changes nothing |
| N | tenant isolation / no cross-execution settlement |
| O | recovery never resurrects a terminal execution, and dials nothing for it |

No existing test was weakened, disabled, skipped or deleted. No `@Disabled`. No Surefire
exclusion.

### Verified results

| Scope | Result |
|---|---|
| Focused VB-8E class | `SchedulerCrashRecoveryPostgresIntegrationTest` — **15 run / 0 failures / 0 errors / 0 skipped** |
| PostgreSQL (VB-8E + VB-8D) | **25 run / 0 failures / 0 errors / 0 skipped** (15 + 10) |
| Scheduler / retry / reconciler / routing / capacity focused | **116 run / 0 failures / 0 errors / 0 skipped** |
| Full suite | **2063 run / 0 failures / 0 errors / 2 skipped** — `BUILD SUCCESS` |
| Architecture | `ArchitectureTest` green — **16 modules / 0 cycles** |
| Migration | head **V55**, 54 files, **0 added** |
| API | **unchanged** |
| Test delta | 2048 → 2063, exactly the 15 new tests |

The 2 skips are the pre-existing baseline skips, unchanged by this phase.

---

## 16. PostgreSQL / concurrency evidence

- **F** — 6 threads, `CountDownLatch`-synchronised, one claimed dial and one session.
- **E3** — 4 reconciler threads × 40 full `reconcile()` passes concurrent with a real
  `processDueAttempts()` dispatch, asserting the attempt keeps its `providerCallId`,
  stays `IN_PROGRESS`, and its session survives.
- **E2 / H** — the in-transaction re-checks that make those two idempotent.
- **D** — the first successful execution of the stranded-session query in the project's
  history, which is how F-8E-02 was found rather than assumed.

---

## 17. Architecture

Unchanged: **16 modules / 0 cycles**, verified by `ArchitectureTest` in the full run.
No new module, no new package, no new cross-module dependency. The change is confined to
one existing class in `campaign`.

---

## 18. Database

**No migration.** Head remains `V55`, 54 files. The recovery uses only columns that
already exist (`status`, `started_at`, `failed_at`-equivalent, `completed_at`,
`failure_code`, `failure_reason`, `deleted_at`) and adds no new persistent state and no
new status value — `CANCELLED` and the free-text code are both pre-existing.

---

## 19. API / OpenAPI

**Unchanged.** No controller, request, response, status code or validation change; no
schema, so no OpenAPI regeneration is required and none was performed.

---

## 20. Working-tree safety

- No `git reset`, `checkout`, `clean`, `stash`, `revert`, commit or push.
- Files changed by VB-8E: `StaleCallReconciler.java` and the new
  `SchedulerCrashRecoveryPostgresIntegrationTest.java`. Nothing else.
- Verified against the pre-phase baseline: **190 / 190 pre-existing dirty entries still
  present, 0 disappeared.**
- The user-owned `frontend/` workstream continued landing files during this phase (16
  further entries). None were read-modified, staged or reverted by VB-8E.
- No user-owned FreeSWITCH/telephony source was touched.
- `HEAD` remains `3a89e5c`; 0 staged, 0 stashes, 0 commits ahead of `origin/main`.

---

## 21. Final invariants

1. A crash after the claim and before the dispatch transaction can no longer wedge an
   attempt or an execution: the attempt is settled within one stale threshold and the
   execution converges on the following tick.
2. An unknown external outcome is **never** converted into a retryable failure, and
   **never** into a second dispatch.
3. Recovery cannot double-consume, under-release or phantom-consume the VB-6C daily dial
   limit or the VB-6D.3 daily attempt safety ledger — all of it is inside the transaction
   that a crash rolls back.
4. Recovery is idempotent, tenant-safe, bounded, and cannot steal an attempt from a
   dispatch that is still in flight or resurrect a terminal execution.
5. The concurrent claim race is unaffected: N workers still yield exactly one dispatch.
6. PostgreSQL and FreeSWITCH are **not** one transaction, and no code or claim in this
   phase asserts otherwise.

---

## 22. Final verdict

**COMPLETE WITH DEFERRED FINDINGS.**

Two CRITICAL defects fixed, both with PostgreSQL evidence. The more serious of the two
(F-8E-02) was pre-existing and entirely invisible: a production recovery branch that
had never executed because its SQL was invalid, guarded by a test suite that never put a
row in front of it. F-8E-01 was a genuine regression introduced by VB-8D's own
claim-before-dispatch ordering — the correct fix for duplicate dispatch opened a window
that no reconciler could see.

The system now provably converges after a crash at every point before and after the
dial, with one honest exception, stated plainly rather than papered over: a call that
FreeSWITCH really placed but whose database write was lost is settled conservatively and
**not retried**, and the platform never learns its outcome. That gap is F-8E-03, deferred
to VB-8F with its exact rationale, its risk, and the test that should accompany the fix.
