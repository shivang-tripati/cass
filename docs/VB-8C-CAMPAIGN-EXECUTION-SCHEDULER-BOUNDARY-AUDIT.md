# VB-8C — Campaign Execution Scheduler Boundary Audit

## Status
**AUDIT ONLY — FINDINGS**

1 HIGH × 2, MEDIUM × 3, LOW × 2, INFO × 2. **Nothing was fixed.**

---

## 1. Baseline

| Item | Value |
|---|---|
| HEAD | `3a89e5c` |
| `origin/main` | `3a89e5c` |
| Divergence | `0 / 0` |
| Staged / stashes | 0 / 0 |
| Working tree | **Dirty by design.** 127 entries: user's FreeSWITCH/telephony work, VB-7C.3 work, VB-8B work, a large concurrent `frontend/` workstream, and untracked docs/tools. Preserved byte-for-byte. |
| Maven baseline | `Tests run: 2038, Failures: 0, Errors: 0, Skipped: 2` — `BUILD SUCCESS` (10 min). Identical to VB-8B. |
| Architecture baseline | `ArchitectureTest` PASS — **16 modules, 0 dependency cycles** |
| Migration head | **V55**, 54 files |

**The implementation audited is the working tree, not `HEAD`.** `HEAD` contains
none of VB-7C.3 or VB-8B. `CampaignRuntimeConfig.integrationConfig`,
`ExecutionScheduleCalculator`, `CallAttemptService`'s hardened path and the
retry-ceiling accessor exist **only** as uncommitted changes. Every conclusion
below is against that state, which is the state that would ship.

The suite was re-run once for this audit and returned the same numbers, which
also proves the audit changed no code.

---

## 2. Scope

Audited: the scheduler tick, execution selection, attempt creation, due-attempt
selection, retry scheduling, daily safety interaction, compliance/audience,
calling windows, max duration and reconciliation, concurrency, tenant
isolation, transaction/side-effect boundaries, restart recovery, and unbounded
selection — plus the 15 architectural questions and the 26-scenario test matrix.

**Explicitly: no production changes. No test changes. No migrations. No API
changes. No scheduler changes. No telephony changes. No infrastructure changes.**
The only file this phase created is this report.

---

## 3. Scheduler Architecture

```
@Scheduled(fixedDelay = 30000)                        CampaignExecutionOrchestrator:516
  scheduledTick()                                       ← NOT @Transactional (by design, VB-6E)
   ├─ runStep("start-requested-executions")            L518
   │    executionRepository.findByStatusAndDeletedAtIsNull(REQUESTED)   [platform-wide, unordered]
   │    for each → startExecutionAsSystem(id)          [self-invocation → @Transactional INERT]
   │       └─ doStartExecution: live campaign + LIVE readiness gate
   │          → createInitialAttempts  → resolver.resolve()  [FROZEN]  → CallAttempt rows
   ├─ runStep("process-retries")                       L526
   │    processRetries()                               [self-invocation → @Transactional INERT]
   │      findByStatusAndDeletedAtIsNull(RUNNING) → per execution
   │        resolver.resolve() [FROZEN] → RetryPolicyService → new CallAttempt
   ├─ runStep("dial-due-attempts")                     L528
   │    dialService.processDueAttempts()               [PROXY call → @Transactional APPLIES]
   │      findByStatusAndScheduledAtBefore(QUEUED, now) [platform-wide, unbounded, unordered]
   │      ONE transaction for the whole batch, held across dialer.dial() I/O
   │      per attempt: campaign exists? PAUSED? → eligibility → routing
   │                    → daily dial limit → capacity → daily attempt ceiling → dial
   ├─ runStep("pump-esl-events")                       L530
   │    eslEventProcessor.ensureEventProcessing()       no campaign reads at all
   ├─ runStep("reconcile-executions")                  L532
   │    findByStatusAndDeletedAtIsNull(RUNNING) → reconcileExecution(id) [self-invocation → INERT]
   │      all attempts terminal? → COMPLETED / FAILED
   ├─ runStep("reconcile-stale-calls")                 L543
   │    StaleCallReconciler.reconcile()                 reads call_sessions.deadline_at only
   └─ runStep("terminate-missed-call-budgets")         L554  (optional bean)
        MissedCallExecutionService.terminateExpired()   resolver.resolve() [FROZEN]
```

Other `@Scheduled` components (`DtmfTimeoutScheduler`, `AgentConnectTimeoutScheduler`,
`VoiceCapacityServiceImpl`, `AcdMaintenanceScheduler`, `InboundAcdRetryScheduler`,
`AgentStaleReservationReconciler`, `EslEventScheduler`, `SecurityCleanupScheduler`)
reference **neither `CampaignEntity` nor `CampaignRepository`** — verified by
exhaustive grep. They operate on session/leg/attempt/token state only.

---

## 4. Execution Writer Inventory

Every production path that creates or mutates execution/attempt state.

### 4.1 `CallAttempt` **creation** — exactly three sites

| # | Class / method | Caller | Trigger | Snapshot? | CampaignEntity? | Client-controlled? | Lifecycle guard | Audience guard | Frozen/Derived/Validated | Tenant |
|---|---|---|---|---|---|---|---|---|---|---|
| W1 | `CampaignExecutionOrchestrator.createInitialAttempts:290` | `doStartExecution` | **scheduler** | Yes `resolve():245` | Yes (param, used for logging only) | No | execution must be `REQUESTED` (L186) | frozen `contactGroupId` → live membership (L266) | `didId` **FROZEN**; `scheduledAt` **DERIVED** via `ExecutionScheduleCalculator` | `execution.getTenantId()` |
| W2 | `CampaignExecutionOrchestrator.processRetriesForExecution:414` | `processRetries` | **scheduler** | Yes `resolve():341` | No | No | execution selected `RUNNING` only | `isContactStillValid` (identity, L373) | `attemptNumber` **DERIVED** from frozen `retryPolicy`; `scheduledAt` **DERIVED** via `adjustToScheduleWindow` | `execution.getTenantId()` |
| W3 | `CallAttemptService.createAttempt:208` | `CampaignController` L274 | **manual/API** | Yes `resolve()` | **No — dependency removed in VB-8B** | Request identifies operation only; `didId` validated, `scheduledAt` non-authoritative, `attemptNumber` bounded | `REQUESTED`/`RUNNING` only | frozen `contactGroupId` membership | all **FROZEN/VALIDATED** | `execution.getTenantId()` |

**No other production site constructs a `CallAttempt`.** `MissedCallExecutionService`
never creates attempts — it only *reads* them (`findByIdAndDeletedAtIsNull`) and
resolves frozen config at three sites.

### 4.2 `CallAttempt` **mutation** — dispatch and terminal state

| Class / method | Trigger | Snapshot? | CampaignEntity? | Notes |
|---|---|---|---|---|
| `OutboundDialService` — 14 `attemptRepository.save` sites | **scheduler** (dial step) | Yes `resolve():156` | Yes (existence + `PAUSED` only) | Owns QUEUED→IN_PROGRESS→{COMPLETED,FAILED,QUEUED} |
| `StaleCallReconciler:371` | **scheduler** (step 6) | No | No | Sweeps stranded `IN_PROGRESS` using `call_sessions.deadline_at`; **creates no retry** (SWEEP-14) |
| `EslEventService:262,585,590` | **ESL event** (telephony) | **No** | **No** | Finds attempt by `providerCallId`; reads **no** campaign configuration at all |
| `CallAttemptService` — mark/cancel sites | **manual/API** | n/a (status only) | No | Manual state transitions, capability-guarded |

### 4.3 `CampaignExecution` state — exactly four mutations, all in the orchestrator

| Site | Transition | Guard |
|---|---|---|
| L241 | → `FAILED` | readiness failed and not deferrable; **before** any attempt is created |
| L249 | → `RUNNING` | readiness passed |
| L481 | → `COMPLETED` | **all** attempts terminal and ≥1 COMPLETED |
| L483 | → `FAILED` | **all** attempts terminal, none COMPLETED |

`CampaignExecutionStatus.CANCELLED` is **declared but has no writer anywhere in
`src/main`** (F-8C-06).

---

## 5. Scheduler Step Inventory

| Step | Entry | Purpose | Reads | Writes | Transaction | Failure isolation | Snapshot boundary | External side effects | Concurrency protection |
|---|---|---|---|---|---|---|---|---|---|
| 1 start-requested | `scheduledTick:518` | start `REQUESTED` executions | executions (platform-wide), live campaign, live readiness | execution status, attempt rows | **NONE** (annotation inert) | step-level only — **not per item** | `createInitialAttempts` frozen ✓; readiness gate live (documented, F-03) | none | unique index on attempts |
| 2 process-retries | `scheduledTick:526` | create retry attempts | `RUNNING` executions, FAILED attempts | attempt rows | **NONE** (annotation inert) | step-level only | **fully frozen** ✓ | none | unique index + `existsBy` pre-check |
| 3 dial-due-attempts | `scheduledTick:528` | dispatch due attempts | QUEUED attempts due, campaign existence/PAUSED, contacts, routing | attempt status, `providerCallId`, sessions, legs, capacity, both safety buckets | **ONE for the entire batch**, spanning `dialer.dial()` I/O | step-level only | fully frozen ✓ | **`dialer.dial()`** | **none** — optimistic `IN_PROGRESS` claim |
| 4 pump-esl-events | `scheduledTick:530` | drain ESL queue | ESL events | attempt status, sessions | delegated | step-level | n/a — reads no campaign config | provider events | n/a |
| 5 reconcile-executions | `scheduledTick:532` | terminal-ise executions | `RUNNING` executions + their attempts | execution status | **NONE** (annotation inert) | step-level only — **not per item** | n/a | none | n/a |
| 6 reconcile-stale-calls | `scheduledTick:543` | expire overdue sessions | `call_sessions.deadline_at` | session/attempt terminal state | own | step-level | n/a — reads derived deadline only | **provider teardown** | idempotent by deadline presence |
| 7 terminate-missed-call | `scheduledTick:554` | expire MISSED_CALL ring budgets | attempts, frozen config | attempt status | own | step-level | **fully frozen** ✓ | provider teardown | idempotent |

`runStep` (L564) catches `RuntimeException` per **step**. That is the VB-6E
guarantee and it holds. It is **step**-level, not **item**-level (F-8C-03).

---

## 6. Snapshot Boundary Audit

| Field | Snapshot source | Scheduler consumer | Manual consumer | Live campaign read? | Dynamic resource read? | External read? | Correct? |
|---|---|---|---|---|---|---|---|
| `campaignType` | frozen | `Playfile:180`, `Dtmf:279`, dial routing `OutboundDialService:241` | attempt-creation guard | **No** | No | No | ✅ FROZEN |
| `didId` | frozen | `Orchestrator:247,378,393`; dial `:205,240,280` | derived + mismatch rejected | **No** | **Yes** — `validateDid` | No | ✅ FROZEN (validity dynamic, per VB-6A) |
| `contactGroupId` | frozen | `Orchestrator:246,373`; dial `:206` | membership enforced | **No** | **Yes** — live membership | No | ✅ FROZEN (membership dynamic, VB-6B.1) |
| `schedule` (window/dates/days) | frozen | `Orchestrator:280,386` via `ExecutionScheduleCalculator` | derived via same calculator | **No** | No | No | ✅ FROZEN |
| `timezone` | frozen | dial `:176,336` | via calculator | **No** | No | No | ✅ FROZEN — **no JVM/UTC fallback** |
| `retryPolicy` | frozen | `Orchestrator:350` | ceiling via `maxPermittedAttemptNumber()` | **No** | No | No | ✅ FROZEN |
| `dailyDialLimit` | frozen | dial `:287` | n/a (attempt has none) | **No** | No | No | ✅ FROZEN |
| `maxDailyAttempts` | frozen | dial `:338` | n/a | **No** | No | No | ✅ FROZEN |
| `maxCallDurationSeconds` | frozen | `Playfile:376` → `CallSessionDeadlineAuthority` | n/a | **No** | No | No | ✅ FROZEN, stamped once |
| `typeConfig` | frozen | resolver `parseTypeConfig:191` → PLAYFILE/DTMF/IVR/CONNECT_BY_AGENT/MISSED_CALL | not used for content | **No** | **Yes** — IVR tree validity, audio validity | No | ✅ FROZEN |
| **`integrationConfig`** | frozen (`integration_config` JSONB, V55) | **none** | **none** | **No** | No | No | ✅ **FROZEN + UNUSED** |
| `callOnWhitelistNumbers` | frozen | dial `:207` → eligibility | n/a | **No** | No | No | ✅ FROZEN |
| IVR tree | frozen in `typeConfig` at exec creation | `IvrCampaignConfig.snapshot()` | n/a | **No** | tree validity | No | ✅ FROZEN |
| CONNECT_BY_AGENT `queueId` | frozen | `ConnectByAgentExecutionService:115` | n/a | **No** | **Yes** — queue validity | No | ✅ FROZEN |
| MISSED_CALL ring budget | frozen | `MissedCallExecutionService:127,265,321` | n/a | **No** | No | No | ✅ FROZEN |
| campaign **status** (`PAUSED`) | — | dial `:149` | n/a | **Yes** | — | — | ✅ intentional control signal, fail-safe (requeue, no budget) |
| campaign **existence** | — | dial `:138`, PLAYFILE/DTMF | not loaded at all | **Yes** (existence only) | — | — | ✅ guard only, never config |
| **readiness gate** | — | `Orchestrator:201` | — | **Yes** (whole campaign) | resource validity | — | ⚠️ safe only via F-03 invariant |
| DND/DNC/blocklist | — | dial `:201` via `VoiceEligibilityService` | n/a | No | — | **Yes, live** | ✅ correct — must stay current |
| gateway / capacity | — | dial `:236,299` | n/a | No | — | **Yes, live** | ✅ correct |

**Verdict: every execution-affecting field the scheduler consumes is FROZEN. No
scheduler path reads mutable campaign configuration.** The only live campaign
reads are (a) an existence guard, (b) the `PAUSED` kill-switch, and (c) the
readiness gate — the last being safe by the F-03 disjointness invariant.

---

## 7. Lifecycle Audit

| State | Scheduler-startable | Attempt-creating | Terminal |
|---|---|---|---|
| `REQUESTED` | **Yes** (`doStartExecution:186`) | Yes (W1) | No |
| `RUNNING` | No | Yes (W2 retries; W3 manual) | No |
| `COMPLETED` | No | **No** (W3 blocked by VB-8B) | Yes |
| `FAILED` | No | **No** (W3 blocked) | Yes |
| `CANCELLED` | No | **No** (W3 blocked) | Yes — **but never assigned in production** (F-8C-06) |

**Answers to §E:**
1. Startable: `REQUESTED` only. 2. Attempt-creating: `REQUESTED`, `RUNNING`.
3. Terminal: `COMPLETED`, `FAILED`, `CANCELLED`.
4. **PAUSED is a campaign state, not an execution state** — there is no
   `PAUSED` execution. A paused campaign blocks dispatch at the dial step (L149)
   and consumes nothing (PAUSE-1/2/3 tested).
5. Transition after dispatch: an execution only becomes terminal once **all**
   attempts are terminal, so a live call always precedes terminality.
6. Terminal execution resurrected: **structurally impossible today** — but see
   F-8C-04 (the dial path never checks).
7. Tick racing cancellation: `CANCELLED` is never set, so there is no cancellation
   to race. Pause is checked per attempt at dial time.
8. Transitions persisted with the relevant operation: **no** — see F-8C-02.
9. Convention-only transitions: none found; `reconcileExecution` re-asserts status.

---

## 8. Due-Attempt Selection Audit

**Query** (`CallAttemptRepository:33`, derived, no `@Query`):
```sql
WHERE status = 'QUEUED' AND scheduled_at < ? AND deleted_at IS NULL
```

| Predicate considered | Present? |
|---|---|
| attempt status | ✅ |
| `scheduled_at` | ✅ |
| **execution status** | ❌ **absent** |
| **tenant** | ❌ absent (platform-wide by design) |
| **campaign / campaign status** | ❌ absent — but `PAUSED` is re-checked in service (L149) |
| **cancellation** | ❌ absent — `CANCELLED` never set |
| **retry validity** | ❌ absent — a retry attempt whose policy changed is still dialed (correct: policy is frozen) |
| **snapshot existence** | ❌ absent — but `resolve()` fails closed to `EXECUTION_CONFIG_MISSING` (L162) |
| **ORDER BY / LIMIT** | ❌ **absent** (F-8C-07) |

**Can an attempt be selected when…**

| Condition | Selectable? | Why it is / isn't a problem |
|---|---|---|
| execution terminal | **Yes** | Blocked upstream: `reconcileExecution` requires all attempts terminal; `doStartExecution` fails before creating any; VB-8B blocks manual creation. Invariant holds, but is **not checked at the dial point** (F-8C-04). |
| execution cancelled | N/A | `CANCELLED` has no writer. |
| campaign not executable | Yes, unless `PAUSED` | A `DRAFT`/`COMPLETED` campaign would still dispatch. Reachable only via edit-after-execution, and the dial path intentionally does not re-check executability. **INFO**, consistent with the frozen model. |
| attempt already processed | No | status filter. |
| retry no longer valid | Yes | Intentional — the policy is frozen, so validity cannot change. |
| snapshot missing | Yes, then failed | Fails closed with `EXECUTION_CONFIG_MISSING`. ✅ |
| tenant invalid | Yes | Attempt already carries its tenant; every downstream call is tenant-scoped. |

---

## 9. Retry Scheduling Audit

Verified against VB-6D. **No redesign suggested; all semantics intact.**

| VB-6D rule | Verified | Evidence |
|---|---|---|
| Policy is frozen | ✅ | `resolve():341`; comment L339 "live campaign is not consulted" |
| `maxRetries` counts retries | ✅ | `RetryRule.maxTotalAttempts()` = 1 + maxRetries; asserted B4-5 |
| Initial attempt is not a retry | ✅ | attempt 1 created by W1, not by the retry path |
| Pre-dispatch rejection consumes no retry | ✅ | `RetryPolicyService:75-80` returns not-retryable for pre-dispatch |
| Permanent failures never retry | ✅ | `RetryPolicyService:84-88` |
| Classification is canonical | ✅ | `CallFailureCode.canonicalize` first, L69-70 |
| Delay comes from frozen policy | ✅ | `rule.retryDelay()`, L112-118 |
| HANGUP_UNKNOWN semantics | ✅ | canonicalization, unchanged |

**Live campaign reads in the retry path: none.** **Client-controlled values:
none** (W2 takes no request input). **Off-by-one: none found.**

- Retry after terminal execution: prevented by `findByStatusAndDeletedAtIsNull(RUNNING)` — **structurally guarded, untested** (§19 SC-14).
- Retry after cancellation: N/A, no `CANCELLED` writer.
- Retry after pause: the retry attempt is created, then requeued at dial time without consuming budget. ✅
- Retry using a different policy than the original execution: **impossible** — the policy is read from the execution's own snapshot.

---

## 10. Daily Safety Interaction

| Authority | Enforced at | Key | Source | Atomic? |
|---|---|---|---|---|
| VB-6C daily dial limit | dial step, after routing (real DNID) | tenant/contact/DID/date, plus **`UNIQUE(call_attempt_id)`** (V47) | frozen `dailyDialLimit` | guarded conditional UPDATE, 0-row-safe |
| VB-6D.3 daily attempt ceiling | dial step, immediately before dial | tenant/contact/date, `UNIQUE(tenant_id,contact_id,usage_date)` (V50) | frozen `maxDailyAttempts` | guarded conditional UPDATE |

- Which step invokes which: **both in step 3 (dial-due-attempts)**, in that order.
- Retries consume the correct counters: **yes** — a retry is an ordinary attempt and passes the same two boundaries.
- Pre-dispatch rejection consumes nothing: **correct and tested** — `DailyAttemptSafetyService` Javadoc table; capacity requeue returns the dial reservation (`OutboundDialService:302`).
- Can a due retry bypass either? **No**, both run on every attempt regardless of origin.
- Does scheduler ordering create a bypass? **No.** Ordering is `start → retry → dial`; retries are created in step 2 and dispatched in step 3 of the *same* tick, so a retry still passes the full dial boundary.
- Failed reservation stranding: the dial-limit reservation is released on every failure branch (`:387,393,399,408,416,430`) and on capacity failure (`:302`). ✅
- Concurrent workers: see §14 and F-8C-05 — the **daily-attempt bucket is not per-attempt**, so a duplicated dispatch would consume the bucket twice but the **dial-limit confirm is per-attempt idempotent**, meaning **one real outbound call would go uncounted against VB-6C**.

---

## 11. Compliance / Audience Audit

- Compliance runs **at dial time for every attempt**, so retries re-run it. ✅ This satisfies "compliance must be rerun for retries" — not by a special retry hook, but because the dial path is shared.
- Ordering inside `processAttempt`: eligibility (DND/DNC/blocklist/whitelist) → destination → routing → dial limit → capacity → attempt ceiling. A compliance rejection consumes **nothing**.
- **Whitelist**: `if (!enforceWhitelist)` gates the contact-group check in `CallEligibilityService`. `enforceWhitelist` comes from the **frozen** `callOnWhitelistNumbers` (`:207`). Consequence, confirmed and intended by VB-6E: a whitelist-enforced campaign's *dial-time* audience check is skipped.
  - **Scheduler-created attempts are unaffected by this**: W1 always selects from the frozen `contactGroupId`. So a scheduler attempt is *never* more privileged than a manual one — VB-8B closed the manual side to match. ✅
- Group membership changes after snapshot: W1 reads membership **live** at attempt creation (VB-6B.1 Model A); the dial-time re-check also reads live membership of the **frozen** group. ✅ Correct by design.
- Deleted/soft-deleted contacts: `buildDestinationNumber` fails closed → `CONTACT_INVALID`, permanent. ✅
- Cross-tenant contacts: `findByIdAndTenantIdAndDeletedAtIsNull` cloaks foreign as missing. ✅

**No concrete discrepancy found.**

---

## 12. Calling Window / Timezone Audit

**One implementation.** Verified by search:

| Location | Role |
|---|---|
| `ExecutionScheduleCalculator` (`@Component`) | the **only** implementation of the window algorithm |
| `CampaignExecutionOrchestrator:575,588` | thin delegating wrappers |
| `OutboundDialService` | consumes the frozen timezone only (`:176`, `:336`) — **computes no window** |
| `CallAttemptService` (VB-8B) | delegates to the same calculator |

No other class touches `dailyStartTime`/`dailyEndTime`/`ZoneId` for dispatch
timing. `StaleCallReconciler` computes `deadline_at` but that is a call-duration
deadline anchored at ANSWERED, not a calling window.

- Execution timezone authoritative, no JVM/UTC fallback: ✅ `resolveUsageDate` throws `EXECUTION_TIMEZONE_INVALID` and the attempt fails deterministically before any admission or dialing (`:177-184`). Tested.
- DST-sensitive: `ZoneId.of` + `atZone`/`atStartOfDay` use true zone rules; no fixed offsets. No DST test found → coverage gap, not a defect.
- Schedule-less campaigns: calculator returns `baseTime` unchanged. ✅
- Retry scheduling: `adjustToScheduleWindow` on the frozen schedule. ✅

---

## 13. Max Duration / Reconciliation Audit

- `maxCallDurationSeconds` (frozen) → `MaxCallDurationPolicy.effectiveSeconds` → `CallSessionDeadlineAuthority.applyDeadline(Anchor.ANSWERED, seconds, rebase=false)`.
- **Stamped once**: `hasDeadline(session)` short-circuits, so a duplicate ANSWERED event cannot move the deadline. ✅ Tested (SWEEP-3 idempotency; VB-7B comment L378-382).
- MISSED_CALL shares the same authority but owns its ring budget via `CallSessionDeadlineOwner`, so the max-duration sweeper will not terminate a MISSED_CALL session early. ✅ Deliberate (`:285-294`).
- `deadline_at` is queried by a partial index (`deadline_at IS NOT NULL AND deadline_at <= :now ORDER BY deadline_at`, L218-222) — bounded and ordered. ✅
- Recorded as `MAX_DURATION_EXCEEDED`. ✅
- Stranded `IN_PROGRESS` attempts (no hangup event, provider died, restart): `StaleCallReconciler` fails them with a **dispatched** failure code (SWEEP-7), creating **no** retry (SWEEP-14) — so a stale sweep cannot inflate retry budget.
- Can an execution remain indefinitely active? **Yes, in one specific shape:** an execution stays `RUNNING` while any attempt is non-terminal. `StaleCallReconciler` bounds sessions with a deadline; a session **without** a deadline is explicitly not swept (SWEEP-5). Today every PLAYFILE/DTMF session gets a deadline, so the practical exposure is low. **INFO.**

---

## 14. Concurrency Audit

| Hazard | Protection present? | Evidence |
|---|---|---|
| Duplicate **attempt creation** | ✅ **Yes, physically** | `uq_call_attempts_execution_contact_attempt` on `(execution_id, contact_id, attempt_number)` (V22:75) |
| Duplicate **attempt creation** under race | ⚠️ the losing insert throws, aborting the batch (F-8C-01/03) | no `INSERT … ON CONFLICT` |
| Duplicate **dispatch** of one attempt | ❌ **No claim mechanism** | `attempt.setStatus(IN_PROGRESS); save();` at `:228-229`, explicitly commented "optimistic". **No `@Version`, no `PESSIMISTIC`, no `FOR UPDATE` anywhere in the codebase.** |
| Duplicate retry for one failed attempt | ✅ Yes | unique index + `existsBy` pre-check |
| Double-consume daily dial limit for one attempt | ✅ No | `UNIQUE(call_attempt_id)` (V47:77) |
| Double-consume daily attempt ceiling for one attempt | ❌ No | V50 keys on `(tenant_id, contact_id, usage_date)` — **no per-attempt key** |
| Execution double-transition | ✅ No | `reconcileExecution` re-reads and is idempotent |
| Overlapping ticks in one instance | ✅ No | default single-threaded `@Scheduled` + `fixedDelay` |
| Overlapping ticks across instances | ⚠️ **Unprotected** | see F-8C-05 |

**Proof of the race (F-8C-05).** Two workers, same QUEUED attempt: both read
`QUEUED` (`:121`), both pass every guard, both set `IN_PROGRESS` optimistically,
both consume the VB-6D.3 bucket (not per-attempt), and both call `dialer.dial`.
The VB-6C confirm is per-attempt idempotent, so **the second real outbound call
is never counted against the daily dial limit**. Reachability: not in the current
single-instance, single-threaded-scheduler deployment; reachable the moment the
platform runs two instances or a scheduler pool.

---

## 15. Tenant Isolation Audit

| Path | Predicate | Verdict |
|---|---|---|
| Scheduler selection (`findByStatusAndDeletedAtIsNull`) | none | Platform-wide **by design**; safe because every downstream step re-derives `tenantId` from the row. |
| `doStartExecution` campaign load | `findByIdAndTenantIdAndDeletedAtIsNull(campaignId, execution.getTenantId())` — "the execution row is the authority" | ✅ |
| `processAttempt` campaign load | `findByIdAndTenantIdAndDeletedAtIsNull(attempt.getCampaignId(), attempt.getTenantId())` | ✅ |
| Snapshot resolution | `snapshotRepository.findByIdAndTenantId(snapshotId, execution.getTenantId())`, fails closed | ✅ |
| `buildDestinationNumber` | `findByIdAndTenantIdAndDeletedAtIsNull` | ✅ |
| Eligibility / DND | keyed on `attempt.getTenantId()` | ✅ |
| Routing | `resolveRoute(attempt.getTenantId(), resellerId, …)` | ✅ |
| Capacity | `reserve(gatewayId, attempt.getTenantId())` | ✅ |
| `reconcileExecution` empty scope | falls back to `findByIdAndDeletedAtIsNull` | ✅ correct — **the method has no controller route**, so the unscoped branch is reachable only from the scheduler thread |
| Attempt ↔ execution tenant agreement | **never asserted** | ⚠️ **INFO** — `execution_id` and `tenant_id` are written together and never updated, so they cannot diverge; but nothing checks it (F-8C-07) |

**"ID is globally unique" was not accepted as isolation anywhere** — every lookup
above carries an explicit tenant predicate. No cross-tenant path found.

---

## 16. Transaction / External Side-Effect Audit

| Step | Writes | External side effect | In one transaction? |
|---|---|---|---|
| 1 start | execution status, attempt rows | none | **No** — annotation inert |
| 2 retry | attempt rows | none | **No** — annotation inert |
| 3 dial | attempt status, `providerCallId`, sessions, legs, capacity, both safety buckets | **`dialer.dial()` per attempt** | **Yes — one transaction for the WHOLE batch, spanning all the I/O** |
| 4 ESL | attempt/session status | provider teardown | delegated |
| 6 stale | session/attempt status | provider teardown | own |

**Divergences the platform can currently produce (F-8C-01):**
- *DB says dispatched, provider never received* — not produced: acceptance is the last durable write before the call is counted.
- *Provider received, DB says not dispatched* — **producible.** If attempt *K+1* in a batch throws a non-`OutboundDialException` runtime error, the whole batch rolls back, erasing `providerCallId` (`:373`), the session and leg rows (`:364-365`), the capacity reservation and both safety consumptions — while FreeSWITCH keeps the live channels for attempts 1..K. The ESL hangup for attempt 1 then cannot correlate (`findByProviderCallId` misses), and on the next tick attempt 1 is `QUEUED` and due again → **re-dialled.**
- *Duplicate dispatch after restart* — same mechanism; `StaleCallReconciler` cannot help, because no session row survived to be swept.

Classification: **correctness defect** (data/provider divergence), with an
**existing-accepted-limitation** component (no distributed transaction across the
provider boundary — that is inherent and must not be over-engineered away).

---

## 17. Restart / Crash Recovery Audit

| Crash point | Persisted state | Recovery |
|---|---|---|
| before attempt creation | execution `RUNNING`, no attempts | `reconcileExecution` returns early (no attempts ⇒ "cannot complete") → **execution stays RUNNING forever** |
| after attempt creation, before dispatch | attempts `QUEUED`, due in the future/past | next tick selects and dispatches ✅ |
| after dispatch, before provider accept | attempt `IN_PROGRESS`, session `DIALING` | `StaleCallReconciler` fails the stranded attempt with a dispatched code (SWEEP-7) ✅ |
| after provider accept | `providerCallId`, session, capacity, both buckets | ESL events correlate by `providerCallId` ✅ |
| before hangup | as above | deadline sweeper terminates; ESL completes it ✅ |
| during retry scheduling | partial retry attempts | next tick re-runs; `existsBy` makes it idempotent ✅ |
| **during a dial batch (post-rollback)** | **nothing** — see F-8C-01 | **no recovery**; attempt returns to `QUEUED` and is **re-dialled** |

**What is currently guaranteed:** attempt-level idempotence (unique index),
session-level reconciliation for anything with a persisted `deadline_at`,
per-attempt idempotence of the VB-6C dial bucket.

**The one execution that can never complete** is a `RUNNING` execution with zero
attempts. `reconcileExecution` explicitly comments "No attempts — execution cannot
complete" and returns. This is reachable if `createInitialAttempts` throws
between the `RUNNING` transition (L249) and the first save — and, because of
F-8C-02, `createInitialAttempts` can never re-run. **F-8C-02 is the enabler.**

---

## 18. Unbounded Selection / Scalability Audit

| Aspect | Current state |
|---|---|
| Query | `findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(QUEUED, now)` |
| Ordering | **none** |
| Limit / page | **none** |
| Rows loaded | all due attempts, materialised into one `List` |
| Transaction scope | **the entire batch**, held across provider I/O |
| Index support | single-column `idx_call_attempts_status` (V22:63) and `idx_call_attempts_scheduled` (V22:66). **No composite `(status, scheduled_at)`** → the two-column predicate likely resolves to a bitmap intersection or a status-index scan plus filter. |
| Tenant distribution | unconstrained; one large tenant can fill the batch |
| Starvation | with no `ORDER BY`, ordering is physical/undefined — a large tenant's rows can consistently precede others within a tick |
| Overlapping ticks | prevented in one instance; **not** across instances (F-8C-05) |

**Evidence-based classification: LOW.**
Not a correctness problem at any demonstrated scale, and I have no repository
evidence of a production volume that makes it one. Its real significance is
**amplification of F-8C-01**: the larger the batch, the more provider calls are
rolled back by one stray exception, and the longer the transaction. Severity was
not inflated beyond what the evidence supports.

---

## 19. Test Coverage Matrix

| # | Scenario | Verdict | Evidence / gap |
|---|---|---|---|
| 1 | Scheduler uses frozen DID after campaign mutation | **NOT TESTED** | Snapshot-level freeze proven (`CampaignConfigurationSnapshotPostgresIntegrationTest` CFG-B/C). The only tests combining `processDueAttempts` with a "mutation" mutate the **attempt object**, not the campaign (`OutboundDialServiceRoutingTest:135`). No test mutates a campaign then runs the dial path. |
| 2 | Scheduler uses frozen schedule after campaign mutation | **NOT TESTED** | Same gap. `calculateNextScheduledAt` is unit-proven; the end-to-end mutated-campaign scenario is not. |
| 3 | Scheduler uses frozen retry policy after mutation | **PARTIALLY** | `RetryPolicySnapshotPostgresIntegrationTest` (7) proves the policy is frozen and applied; no test drives a campaign edit into `processRetries`. |
| 4 | Scheduler uses frozen `dailyDialLimit` | **PARTIALLY** | `DailyDialLimitSnapshotPostgresIntegrationTest` (5) + `VoiceBlastDailyDialLimitPostgresIntegrationTest` (12) prove freezing and use; the mutated-campaign path is not driven. |
| 5 | Scheduler uses frozen `maxDailyAttempts` | **PARTIALLY** | `DailyAttemptSafetyServiceTest` + `DailyAttemptConcurrencyPostgresIntegrationTest` prove the ceiling; dial-path wiring asserted only in mocks. |
| 6 | Scheduler uses frozen `maxCallDurationSeconds` | **TESTED** | `MaxCallDurationConfigurationTest$SnapshotFreeze`. |
| 7 | Scheduler uses frozen `typeConfig` | **TESTED** | `CampaignConfigurationSnapshotPostgresIntegrationTest` CFG-A2; `PlayfileExecutionServiceTest`, `DtmfExecutionServiceTest`. |
| 8 | Scheduler uses frozen IVR config | **TESTED** | `IvrExecutionSnapshotTest$Immutability`, `IvrFromCampaignServiceTest$CampaignRepointing`. |
| 9 | Scheduler uses frozen CONNECT_BY_AGENT config | **TESTED** | `ConnectByAgentConfigPostgresIntegrationTest` (8). |
| 10 | Scheduler uses frozen MISSED_CALL config | **TESTED** | `MissedCallPostgresIntegrationTest` (15). |
| 11 | Scheduler does not use mutable campaign config after snapshot | **PARTIALLY** | Proven by source trace + absence; no test asserts it. |
| 12 | Retry uses frozen retry policy | **TESTED** | `PlayfileRetrySemanticsTest` (7 scenarios), `RetryPolicySnapshotPostgresIntegrationTest`. |
| 13 | Retry reruns compliance | **PARTIALLY** | Structurally true (shared dial path); no test explicitly runs a *retry* attempt through eligibility/DND. |
| 14 | Retry cannot resurrect a terminal execution | **NOT TESTED** | Structurally guarded by `findByStatusAndDeletedAtIsNull(RUNNING)`. **No test asserts it** — `PlayfileRetrySemanticsTest` covers *attempt* states, not *execution* states. |
| 15 | Due-attempt cannot dispatch a paused execution | **TESTED** | `OutboundDialServicePausedTest` PAUSE-1/2/3: `verify(dialer, never()).dial(any())`, no safety budget consumed, attempt number preserved. |
| 16 | Due-attempt cannot dispatch a cancelled execution | **NOT APPLICABLE** | `CANCELLED` has no production writer (F-8C-06). |
| 17 | Scheduler tenant isolation | **PARTIALLY** | Proven for every downstream lookup; no test exercises the **unscoped selection** path with two tenants in one tick. |
| 18 | Concurrent scheduler safety | **NOT TESTED** | Concurrency harnesses exist for counters and capacity (`DailyAttemptConcurrencyPostgresIntegrationTest`, `VoiceCapacityConcurrencyIntegrationTest`) but **none** runs two `processDueAttempts` or two ticks concurrently. F-8C-05 is untested. |
| 19 | Restart / recovery | **PARTIALLY** | `StaleCallReconcilerTest` SWEEP-7 (stranded attempt) and SWEEP-3 (idempotency) cover the session/attempt layer. **No test covers a crash mid-batch** — the F-8C-01 divergence is untested. |
| 20 | Calling-window boundary | **PARTIALLY** | `ExecutionScheduleCalculator` boundary behaviour is asserted in VB-8B's `B3-*`; no pre-VB-8B test drives the orchestrator's window. |
| 21 | Invalid timezone | **TESTED** | `EXECUTION_TIMEZONE_INVALID` paths across `DailyDialLimitServiceTest`, `VoiceBlastDailyDialLimitPostgresIntegrationTest`, `OutboundDialServiceRoutingTest`, `PlayfileRetrySemanticsTest`. |
| 22 | Daily dial limit interaction | **TESTED** | `DailyDialLimitServiceTest`, `VoiceBlastDailyDialLimitPostgresIntegrationTest`, `DailyDialLimitSnapshotPostgresIntegrationTest`. |
| 23 | Daily attempt ceiling interaction | **TESTED** | `DailyAttemptSafetyServiceTest`, `DailyAttemptConcurrencyPostgresIntegrationTest`. |
| 24 | Max duration reconciliation | **TESTED** | `StaleCallReconcilerTest` SWEEP-1/2/3/5/6/10/11. |
| 25 | Scheduler step failure isolation | **TESTED** | `CampaignExecutionOrchestratorSchedulerTest` TICK-1..TICK-8, incl. each step's failure not blocking later steps. |
| 26 | Manual and scheduler converge on one frozen boundary | **TESTED** | VB-8B `ManualAttemptBoundaryPostgresIntegrationTest` PGB-4 drives a real campaign edit and shows the frozen DID on both paths. |

**Coverage summary: 12 TESTED · 9 PARTIALLY TESTED · 4 NOT TESTED · 1 N/A.**
The gaps cluster on exactly the two HIGH findings — which is why they survived.

---

## 20. Findings

### F-8C-01 — **HIGH** — The whole due-attempt batch is one transaction spanning provider I/O; one stray exception rolls back calls already placed

- **Location** `OutboundDialService.processDueAttempts:94` (`@Transactional`), loop `:105-109`, per-attempt dial `:361`, `providerCallId` write `:373`, `confirmAccepted` `:378`, session/leg creation `:364-365`.
- **Evidence.** `processDueAttempts` is `@Transactional` and invoked through the injected proxy (`Orchestrator:528` → `dialService.processDueAttempts()`), so the advice **applies**. Every due attempt is processed inside that single transaction, including `dialer.dial(...)`. The only caught exception is `OutboundDialException` (`:424`); the eligibility block (`:201-219`) and both safety admissions (`:285`, `:333`) are **outside** any try. Any other `RuntimeException` — `DataIntegrityViolationException`, a DB error, `IllegalStateException` from `isNumberInCampaignContactGroup`'s N+1 queries — propagates out of the loop and rolls the **entire batch** back.
- **Why it matters.** Rollback erases, for attempts 1..K: `IN_PROGRESS` status, `providerCallId`, the `CallSession`/`CallLeg` rows, the capacity reservation, and both VB-6C/VB-6D.3 consumptions — while FreeSWITCH retains the live channels. The ESL hangup for attempt 1 then cannot correlate (`EslEventService:151` looks up by `providerCallId`), and on the next tick the attempt is `QUEUED` and due again → **the contact is dialled a second time**. This is exactly §M's "provider received but DB says not dispatched".
- **Existing protection.** The unique index prevents duplicate *attempt rows*; `StaleCallReconciler` cleans up stranded sessions **that have a row** — here there is no row, so it cannot help.
- **Reproduction/proof.** Source-level, deterministic. Provable at test level with one attempt stubbed to throw a non-`OutboundDialException` runtime error after a first attempt is accepted; **not written** (audit-only).
- **Recommended remediation (NOT implemented).** Narrow the transaction to one attempt: make the per-attempt work transactional (`REQUIRES_NEW` via a self-injected proxy, or move `processAttempt` to a separate bean) and keep the batch loop outside any transaction. This also converts today's whole-batch rollback into per-attempt isolation.
- **Scope impact** `OutboundDialService` only. No migration, no API change.
- **Suggested next phase** **VB-8D**.

---

### F-8C-02 — **HIGH** — Three `@Transactional` scheduler steps are self-invoked; the annotations are inert

- **Location** `CampaignExecutionOrchestrator.startExecutionAsSystem:166`, `processRetries:340`, `reconcileExecution:451` — all annotated `@Transactional`, all invoked only from `scheduledTick` on `this` (`:522`, `:526`, `:536`).
- **Evidence.** No production caller exists outside the class (verified by exhaustive search: only tests call them, and only `MissedCallPostgresIntegrationTest:609` wraps the call in `transactionTemplate.execute(...)`). Transaction management is in default proxy mode — no `@EnableTransactionManagement(mode = ASPECTJ)` anywhere. Self-invocation therefore bypasses the proxy and the advice never runs. The Javadoc at `:507-511` asserts *"they are invoked through the Spring proxy"* — **that statement is false.**
- **Why it matters.** `doStartExecution` commits `RUNNING` (`:249`), then `createInitialAttempts` saves attempts one-by-one in independent transactions. A failure partway leaves a `RUNNING` execution with a **partial** attempt set — and because the `REQUESTED` guard (`:186`) is passed, `createInitialAttempts` can never re-run. Every remaining contact is silently never called, and `reconcileExecution` never completes the execution because it has non-terminal attempts. A permanent, silent audience loss.
- **Existing protection.** None for atomicity. Attempt creation is idempotent (`existsBy` + unique index) — but that only helps if the method is re-entered, which it cannot be.
- **Reproduction/proof.** Deterministic from source. A test could make the *N*-th `attemptRepository.save` throw; not written (audit-only).
- **Recommended remediation (NOT implemented).** Restore the intended per-step transaction — either inject `CampaignExecutionOrchestrator` lazily into itself and call through the proxy, or extract each step into its own `@Service`. Then decide deliberately whether step 1 *should* be one transaction per execution (it probably should, so a partial audience set never commits).
- **Scope impact** `CampaignExecutionOrchestrator` only.
- **Suggested next phase** **VB-8D**.

---

### F-8C-03 — **MEDIUM** — Failure isolation is per step, never per item; one poisoned item can stall a whole batch every tick

- **Location** `runStep:564` (step-level only); unisolated loops at `:521-523` (start) and `:535-537` (reconcile); `:105-109` (dial).
- **Evidence.** `runStep` catches `RuntimeException` around the whole lambda. A `REQUESTED` execution whose campaign was hard-deleted makes `doStartExecution:198` `orElseThrow` on **every** tick; that exception aborts the remaining iterations of the loop. Selection is `findByStatusAndDeletedAtIsNull(REQUESTED)` with **no `ORDER BY`**, so row order is physical — if the throwing row lands early, the same executions are skipped every 30 s indefinitely.
- **Why it matters.** One permanently-poisoned row can deny service to unrelated, healthy executions — a liveness/fairness defect, not a data-integrity one.
- **Existing protection.** Step-level isolation (VB-6E) prevents cross-step damage, and the throwing row itself is retried forever (which is correct for transient errors but wrong for permanent ones).
- **Reproduction/proof.** Source-level. A deterministic test needs a stubbed campaign repository returning empty for one execution; not written (audit-only).
- **Recommended remediation (NOT implemented).** Per-item `try/catch` inside the two loops, logging the execution id and continuing — the same pattern `runStep` already uses one level up.
- **Scope impact** `CampaignExecutionOrchestrator`.
- **Suggested next phase** VB-8D (same file as F-8C-02; do them together).

---

### F-8C-04 — **MEDIUM** — The dial path never checks execution status; it relies entirely on upstream invariants

- **Location** `OutboundDialService.processAttempt:120-232` — checks attempt status, campaign existence, campaign `PAUSED`; **never** `execution.getStatus()`.
- **Evidence.** The "no `QUEUED` attempt on a terminal execution" invariant is maintained by three upstream facts: `reconcileExecution:470-475` requires all attempts terminal before `COMPLETED`/`FAILED`; `doStartExecution:241` sets `FAILED` *before* creating any attempt; and VB-8B blocks manual creation on terminal executions. None of these is enforced at the point of dispatch.
- **Why it matters.** Defence in depth is absent at the one place that actually places calls. Any future change — a new execution-closing path, a bulk operation, an admin tool, a replay — could produce a `QUEUED` attempt on a terminal execution and it would be dialled.
- **Existing protection.** The upstream invariant (real, but indirect).
- **Why that is insufficient.** It couples dispatch safety to three unrelated code paths in one file. Nothing asserts it, so a regression anywhere is silent.
- **Recommended remediation (NOT implemented).** In `processAttempt`, fail closed on a terminal execution exactly as VB-8B does for manual creation (`ExecutionConfigurationMissingException`-style, or a canonical `EXECUTION_NOT_RUNNABLE` failure). Cheap, local, and makes the invariant enforced rather than emergent.
- **Scope impact** `OutboundDialService` + one `CallFailureCode`/mapper entry.
- **Suggested next phase** VB-8D.

---

### F-8C-05 — **MEDIUM** — No atomic attempt claim; a duplicated dispatch would also escape the VB-6C dial-limit count

- **Location** `OutboundDialService:228-229` — `attempt.setStatus(IN_PROGRESS); attemptRepository.save(attempt);`, explicitly commented *"optimistic"*.
- **Evidence.** **No** `@Version`, `LockModeType`, `@Lock`, or `FOR UPDATE` exists anywhere in `src/main`. Two workers on the same `QUEUED` attempt both pass `:121`, both claim optimistically, and both reach `dialer.dial`. The VB-6D.3 bucket (`voice_blast_daily_attempts`) is keyed on `(tenant_id, contact_id, usage_date)` — **no `call_attempt_id`**, unlike V47's `UNIQUE(call_attempt_id)` — so both consumptions succeed. The VB-6C confirm **is** per-attempt idempotent, so the second real outbound call is **never counted against the daily dial limit**.
- **Why it matters.** Both a duplicate call to a contact and an under-counted daily dial limit are safety-relevant, and the under-count is silent.
- **Existing protection.** Single-threaded default `@Scheduled` + `fixedDelay` means ticks do not overlap **in one instance**. That is the only protection, and it is a deployment accident rather than a design guarantee.
- **Reachability.** Not reachable in the current single-instance default. Reachable with two instances or a scheduler pool — both ordinary for this platform's direction. Classified MEDIUM, not HIGH, precisely because current deployment cannot trigger it.
- **Recommended remediation (NOT implemented).** A single conditional claim — `UPDATE call_attempts SET status='IN_PROGRESS' WHERE id=? AND status='QUEUED'` — and dispatch only if it affected a row. This is the same pattern the two daily-safety services already use, so it introduces no new mechanism.
- **Scope impact** `OutboundDialService` + `CallAttemptRepository`.
- **Suggested next phase** VB-8D.

---

### F-8C-06 — **LOW** — `CampaignExecutionStatus.CANCELLED` has no production writer

- **Location** `CampaignExecutionStatus:25`.
- **Evidence.** Exhaustive search of `src/main`: no assignment, and no `@Scheduled` or service path that cancels an execution. The four `setStatus` sites are `FAILED`, `RUNNING`, `COMPLETED`, `FAILED`.
- **Why it matters.** VB-8B blocks manual attempts on `CANCELLED`, and this audit's lifecycle matrix lists it as terminal — a state nothing can produce. Any operator expectation of "cancel this running campaign" is unmet, and a test written against `CANCELLED` would pass vacuously.
- **Recommended remediation (NOT implemented).** Either implement cancellation as its own product phase, or document `CANCELLED` as reserved. **Do not** wire it into the scheduler in this boundary work — pausing is the current kill-switch and it is well tested.
- **Suggested next phase** Documentation now; a cancellation phase only if the product wants it.

---

### F-8C-07 — **LOW** — Attempt-to-execution tenant agreement is never asserted

- **Location** `OutboundDialService.resolveExecutionConfig:503-509` loads the execution with `findByIdAndDeletedAtIsNull` (unscoped) and trusts `execution.getTenantId()` for the snapshot lookup; the attempt's own `tenantId` is never compared to it.
- **Evidence.** Both IDs are written together at creation and never updated, so they cannot diverge through normal flow. There is no assertion.
- **Why it matters.** A cross-tenant pairing would be silently accepted by this method. Unreachable today; a latent invariant with no guard.
- **Recommended remediation (NOT implemented).** One comparison, failing closed, or a DB-level guarantee that `(execution_id, tenant_id)` is consistent.
- **Suggested next phase** Fold into VB-8D (one line).

---

### F-8C-08 — **INFO** — Unbounded, unordered due-attempt selection; no composite index

- **Location** `CallAttemptRepository:33`; indexes V22:63 (`status`) and V22:66 (`scheduled_at`).
- **Evidence.** No `LIMIT`, `ORDER BY`, or composite `(status, scheduled_at)` index. Whole batch materialised into one list inside one transaction (which is what makes F-8C-01 expensive).
- **Why it matters.** Not a correctness problem at any scale the repository evidences. Its significance is amplification of F-8C-01 and an absence of fairness between tenants within a tick.
- **Recommended remediation (NOT implemented).** Only if measured load warrants: a `LIMIT`-ed, `ORDER BY scheduled_at` batch. Pagination is explicitly **not** recommended as part of VB-8D, since narrowing the transaction (F-8C-01) is the higher-value change and does not require it.
- **Suggested next phase** Deferred; measure first.

---

## 21. Positive Guarantees Verified

Inspected and found correct. Recorded so the next phase does not re-litigate them.

1. **Every execution-affecting field the scheduler consumes is FROZEN** — 16 fields traced individually (§6). No scheduler path reads mutable campaign configuration.
2. **`CampaignRuntimeConfig` is the sole runtime configuration source for both writers** — W1, W2 and W3 all resolve through `CampaignRuntimeConfigResolver`; W3 additionally no longer holds a `CampaignRepository`.
3. **Retry semantics are intact and fully policy-frozen** — every VB-6D rule verified against source and 7 tests.
4. **Exactly one calling-window implementation** exists post-VB-8B; the orchestrator holds only delegating wrappers.
5. **Compliance is re-run on every attempt, including retries**, because retries reuse the shared dial path — the design's intent, verified.
6. **Daily safety is enforced on every attempt** and correctly ordered after compliance and routing; pre-dispatch rejection provably consumes nothing.
7. **Duplicate attempt creation is physically impossible** — `uq_call_attempts_execution_contact_attempt`.
8. **VB-6C dial-limit consumption is per-attempt idempotent** — `UNIQUE(call_attempt_id)` (V47).
9. **Tenant isolation is predicate-based everywhere**, not ID-based. The unscoped scheduler selections are platform-wide **by design**, with per-row tenant derivation downstream. The one unscoped fallback (`findVisibleExecution`) is unreachable from any API.
10. **The execution's FK to its snapshot is `NOT NULL`**, so "execution without snapshot" is a database impossibility; a missing snapshot fails closed to `EXECUTION_CONFIG_MISSING`.
11. **Per-step failure isolation works** (VB-6E), verified by TICK-1..TICK-8.
12. **`StaleCallReconciler` never invents a retry** (SWEEP-14) and never classifies as pre-dispatch (SWEEP-13) — so reconciliation cannot inflate retry budget.
13. **Max-duration deadlines are stamped once** and cannot be moved by a duplicate ANSWERED event; MISSED_CALL's own ring budget is protected from the max-duration sweeper.
14. **`MISSED_CALL` never creates attempts** — it only reads them, so there is no second writer to audit.
15. **`integrationConfig` is FROZEN + UNUSED, and the scheduler does not accidentally read mutable integration configuration** — verified by exhaustive search.
16. **Architecture is cycle-free** at 16 modules; the scheduler's boundary did not change in VB-8B or VB-8C.

---

## 22. Open Product Decisions

1. **Should a running campaign be cancellable?** `CANCELLED` exists as a state with no writer (F-8C-06). Pausing is the current, well-tested kill-switch. This is a product question, not a defect.
2. **Should a scheduler attempt be front-run?** VB-8B left open whether a manual attempt may be created for attempt *N* before *N−1* has failed. **Audited for the scheduler equivalent: it cannot happen** — W2 only creates attempt *N* from an existing *FAILED* attempt *N−1*, under the frozen policy. So the manual path is strictly more permissive than the scheduler path. Whether to align them is a product decision.
3. **Should whitelist mode's audience be the tenant whitelist rather than the frozen group?** Confirmed: the dial-time group check is skipped for whitelist-enforced campaigns (`CallEligibilityService`), while scheduler creation always selects from the frozen group. VB-8B made manual creation *match* the scheduler. Unchanged by this audit and not a defect — but the asymmetry between "audience at creation" and "audience at dial" is real and intentional.
4. Carried forward untouched: whether report privacy should constrain attempt-listing APIs (they expose `contactId`), and whether aggregation/pseudonymisation belong in the privacy model.

---

## 23. Recommended Next Phase

**VB-8D — SCHEDULER TRANSACTION AND ISOLATION BOUNDARY.**
Smallest phase that closes both HIGH findings and the two MEDIUMs that share
their files. Strictly no new architecture:

1. **F-8C-01** — one attempt per transaction in the dial step; per-attempt failure isolation.
2. **F-8C-02** — restore the intended per-step transaction (proxy self-injection or step extraction), and make step 1 atomic per execution so a partial audience never commits.
3. **F-8C-03** — per-item `try/catch` in the start and reconcile loops.
4. **F-8C-04** — fail closed on a terminal execution in `processAttempt`.
5. **F-8C-05** — atomic conditional `QUEUED → IN_PROGRESS` claim.
6. **F-8C-07** — one tenant-agreement assertion.
7. Add the four missing tests: campaign-mutation→dial, retry-vs-terminal-execution, concurrent dial, crash-mid-batch.

`F-8C-06` (cancellation) is a **product decision** and should not be bundled.
`F-8C-08` (pagination) should wait for measurement.

**Deliberately not recommended:** a scheduler rewrite, a worker/queue architecture,
Redis or Kafka locking, sharding, or a distributed scheduler. Nothing in this
audit requires any of them, and introducing them would be exactly the
over-engineering this programme has avoided so far.

---

## 24. Explicitly Out of Scope

Not audited, not started, not recommended here: webhook delivery/signing/retry;
report runtime, export, aggregation, pseudonymisation, privacy enforcement;
campaign versioning or configuration history; the telephony mutable-campaign
adapter (`CallEligibility.evaluate(CampaignEntity, …)`, dead, telephony track);
FreeSWITCH/ESL/SIP/RTP behaviour; DND/DNC product semantics; routing and capacity
design; frontend; infrastructure; deployment topology.

`integrationConfig` consumption was **not** audited beyond confirming the scheduler
does not read mutable integration configuration. Its being frozen-but-unused is
recorded as a fact, not a finding.

---

## 25. Final Verdict

### **READY WITH FINDINGS**

**FACT.** The scheduler respects the execution configuration boundary. All 16
execution-affecting fields it consumes are frozen; the sole runtime source is
`CampaignRuntimeConfig` for both scheduler and manual writers; retry, calling
windows, daily safety, compliance and max duration all trace to the snapshot or
to deliberately live resource state.

**FACT.** Two HIGH findings are about **transaction boundaries, not configuration**:
the dial step wraps an entire platform-wide batch in one transaction spanning
provider I/O, and three scheduler steps carry `@Transactional` annotations that
never take effect because they are self-invoked. Neither lets a campaign mutate
an execution. Both can silently lose calls.

**FACT.** These findings survived because the tests that would have caught them do
not exist: of 26 required scenarios, 4 are NOT TESTED and 9 are only partially
tested, and the gaps cluster precisely on the two HIGH findings.

**INFERENCE.** The boundary is sound; the durability of the scheduler around it
is not. That is a narrower and more tractable problem than the audits before it,
and it is confined to two files.

**RECOMMENDATION.** VB-8D as scoped in §23. It needs no migration, no API change,
no new infrastructure and no scheduler redesign — the tick stays one scheduler at
30 s with the same seven steps.

> This audit changed nothing. Measure → trace → classify → document → recommend.
> Implement only after these findings are reviewed.
