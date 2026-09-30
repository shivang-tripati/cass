# VB-8D — Scheduler Transaction & Isolation Boundary

**Status: COMPLETE**
Full suite **2048 tests / 0 failures / 0 errors / 2 skipped**, `BUILD SUCCESS`.
Architecture **16 modules / 0 cycles**. Migration head **V55 — unchanged** (no migration added).
Closes VB-8C findings **F-8C-01**, **F-8C-02**, **F-8C-03**, **F-8C-04**, **F-8C-05**.

---

## 1. Baseline

| Item | Value |
|---|---|
| HEAD | `3a89e5c` (unchanged throughout) |
| `origin/main` | `3a89e5c` |
| Divergence | `0 / 0` |
| Working tree | Dirty by design — user's FreeSWITCH/telephony work, VB-7C.3, VB-8B, and a **large concurrent `frontend/` workstream that kept changing during this phase** |
| Pre-phase suite | 2038 / 0F / 0E / 2S |
| Post-phase suite | **2048 / 0F / 0E / 2S** (+10) |
| Architecture | 16 modules, 0 cycles — unchanged |
| Migration | V55, 54 files — unchanged |

The implementation remediated is the **working tree** (HEAD contains none of
VB-7C.3/VB-8B), which is the state that would ship.

---

## 2. Implementation

| Finding | Severity | What changed |
|---|---|---|
| **F-8C-01** | HIGH | `processDueAttempts` is **no longer `@Transactional`**. The batch loop runs with no transaction; each attempt gets two explicitly-committed units — an atomic claim, then a dispatch — so a later attempt's failure can never erase an earlier call's provider evidence. |
| **F-8C-02** | HIGH | The three scheduler steps that self-invoked their own `@Transactional` methods no longer do. `txTemplate.executeWithoutResult` opens a real transaction **per execution** in the start, retry and reconcile loops. `processRetries()` is now deliberately non-transactional (selection needs none; per-execution work does). The misleading Javadoc that claimed proxy invocation was corrected. |
| **F-8C-03** | MEDIUM | Per-**item** isolation, not just per-step. The dial loop, the start loop, the retry loop and the reconcile loop each catch `RuntimeException` per item, log with the item's identity, and continue. |
| **F-8C-04** | MEDIUM | `dispatchClaimedAttempt` verifies the execution's lifecycle state from persisted state **before any dispatch work**, and fails closed: no eligibility, routing, safety admission, capacity or telephony side effect. The attempt becomes `CANCELLED`, not `FAILED`, because `FAILED` is the only status the retry step reads — `FAILED` here would manufacture a retry for a finished execution. |
| **F-8C-05** | MEDIUM | `CallAttemptRepository.claimForDispatch(...)` — one conditional `QUEUED → IN_PROGRESS` UPDATE. The database arbitrates; the loser gets 0 rows and touches nothing. Tenant-scoped **in the predicate**. |

### 2.1 Why a `TransactionTemplate`, not annotations

The required boundary is **per item inside a loop that must itself hold none**.
No annotation can express that: one on the loop's method puts every item in one
transaction; one on the item's method is inert when the loop calls it on `this` —
which is precisely the F-8C-02 defect. So the boundary is stated explicitly.
`TransactionTemplate` is Spring Boot's own auto-configured bean, not an
abstraction invented here, and the brief's "explicit service boundaries" is
satisfied literally.

`@Transactional` was **kept** on `startExecution` (interactive), `startExecutionAsSystem`
and `reconcileExecution` because those are genuinely reachable **externally**
through the proxy. Both are now documented as applying to external callers only.

### 2.2 The atomic claim

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("UPDATE CallAttempt a SET a.status = :claimed, a.startedAt = :now "
     + "WHERE a.id = :id AND a.tenantId = :tenantId "
     + "AND a.status = :expected AND a.deletedAt IS NULL")
int claimForDispatch(id, tenantId, expected, claimed, now);
```

This is the **same guarded-update pattern the VB-6C daily-usage and VB-6D.3
daily-attempt services already use** — the platform's existing concurrency
mechanism applied where it was missing. No new mechanism was introduced.

### 2.3 Transaction model and ordering

```
processDueAttempts()                    NO TRANSACTION
  select due attempts
  for each attempt:
    try {
      [TX 1]  claim  = claimForDispatch(QUEUED → IN_PROGRESS)      COMMIT
               └ 0 rows → another worker owns it; skip
      [TX 2]  dispatch = reload, re-assert IN_PROGRESS, terminal check,
               eligibility → routing → daily dial limit → capacity →
               daily attempt ceiling → dialer.dial() → session/leg/providerCallId   COMMIT
    } catch (RuntimeException e) {
      [TX 3]  recover: IN_PROGRESS → CANCELLED / ATTEMPT_PROCESSING_FAILED   COMMIT
    }
```

**The ordering is honest about external telephony.** The claim commits *before*
the dial, so once committed the attempt is no longer selectable — no later
failure anywhere can make it dialable again. That single fact is what makes
duplicate dispatch impossible. PostgreSQL and FreeSWITCH are **not** pretended to
be atomic with each other; what is guaranteed is that a dispatch can never be
silently forgotten and re-attempted.

Scheduler steps 1, 2 and 5 use the same shape: selection outside, one
`txTemplate` transaction per execution inside, per-item `catch`.

### 2.4 Recovery state — no new taxonomy

An unexpected failure marks the attempt `CANCELLED` with the descriptive code
`ATTEMPT_PROCESSING_FAILED`, not `FAILED`. Two reasons: `FAILED` is the only
status `processRetries` selects, so `FAILED` here would silently consume campaign
retry budget or become a retry loop; and `CANCELLED` is already in
`TERMINAL_ATTEMPT_STATUSES`, so `reconcileExecution` still settles the execution.
The code is free text, matching how the dial path already persists eligibility
codes. **No `CallFailureCode` constant was added.**

### 2.5 Single lifecycle authority

`CampaignExecutionStatus` now owns `TERMINAL`, `DISPATCHABLE`,
`isTerminal()` and `acceptsDispatchWork()`. `CallAttemptService` (manual path) and
`OutboundDialService` (scheduler path) both ask that one enum, so the two cannot
drift. This is not a second lifecycle policy — it is the removal of a duplicate.

### 2.6 Corrected comments

* `CampaignExecutionOrchestrator.scheduledTick` Javadoc previously claimed steps
  "are invoked through the Spring proxy". **That was false** and is now replaced
  with what actually happens.
* `CallAttemptService`'s private status set now delegates to the enum.
* Five orphaned imports removed where this phase's changes orphaned them.

---

## 3. Tests — 10 new, all against real PostgreSQL

`SchedulerTransactionBoundaryPostgresIntegrationTest` — real repositories, real
transaction manager, real concurrent threads. These are the assertions no unit
test can make, because all four HIGH/MEDIUM findings are about what the database
**commits**.

| Test | Proves |
|---|---|
| **TX-1** | An attempt dispatched before another attempt's failure keeps its `providerCallId`, its `CallSession` and its `CallLeg`; is **not** `QUEUED` afterwards; and a second tick issues **no** dial. This is F-8C-01's exact failure mode, now inverted. |
| **TX-3** | A write inside a scheduler per-item boundary rolls back with that boundary (no partial commit) and does commit when the boundary completes — real rollback/commit behaviour, not annotation inspection. |
| **TX-4** | One poisoned attempt throws; the other attempt in the **same tick** is still dispatched; the poisoned one ends `CANCELLED`. F-8C-03. |
| **TX-5 / TX-5b** | A `COMPLETED` execution: no dial, **no** daily-attempt admission, **no** capacity reservation, no session, attempt `CANCELLED` with `EXECUTION_NOT_RUNNABLE`, and nothing left in `FAILED` so the retry step has nothing to pick up. F-8C-04. |
| **TX-6** | **Six concurrent workers** race the same due attempt: exactly **one** dispatch, exactly one committed `providerCallId`, exactly one session, one leg. F-8C-05, proven with real threads against real rows. |
| **TX-7 / TX-7b** | Tenant A's worker updating tenant B's attempt matches **0 rows** and leaves it `QUEUED`; the owning tenant then claims it. A second claim of an already-claimed attempt also matches **0 rows**. F-8C-05's tenant scoping. |
| **TX-8 / TX-8b** | A dispatched attempt still consumes the VB-6D.3 attempt ceiling exactly once, and the VB-6C authority is still asked about the campaign's **frozen** `dailyDialLimit` (3), with that value reaching `admit()`. Safety semantics unchanged. |

`TransactionTestSupport` is a tiny test-only helper giving the two pure-unit dial
tests a pass-through `TransactionTemplate` (they have no Spring context). Its
Javadoc states plainly that it is **not** a way to fake transactional semantics —
the boundaries are proved against real PostgreSQL in the class above.

### 3.1 Existing tests updated, not weakened

Seven existing test files needed the new constructor argument, and the two pure-unit
dial suites needed to follow the new claim contract. Their fixtures were changed
to mirror the database faithfully — the claim stub really moves the row to
`IN_PROGRESS` — and the routing/paused suites' executions were given
`CampaignExecutionStatus.RUNNING`, which the new terminal guard legitimately
requires. **59 tests across those suites remain green; no assertion was relaxed,
no test was disabled or skipped.**

---

## 4. Results

| Run | Result |
|---|---|
| Focused VB-8D PostgreSQL suite | **10 / 0F / 0E / 0S** |
| Restored dial/scheduler/retry/reconciler suites | **59 / 0F / 0E / 0S** |
| Campaign + per-type + architecture + boundary (74 classes) | **1092 / 0F / 0E / 0S** |
| **Full suite** | **2048 / 0F / 0E / 2S**, `BUILD SUCCESS`, 16:39 |

Both skips are pre-existing and unrelated: `ObdApplicationTests` (`@Disabled` in
committed source) and 1 of 4 in the user-owned untracked
`LiveFreeSwitchRuntimeContractTest`.

---

## 5. Architecture, database, API

* **Architecture:** `ArchitectureTest` PASS — **16 modules, 0 cycles**. No new
  module, no new dependency edge, no cycle. `TransactionTemplate` is a Spring bean.
* **Migrations: 0.** V55 remains head, 54 files. The claim needed no schema
  change — `status` and `tenant_id` already exist and the conditional UPDATE
  operates on existing columns.
* **API: 0 changes.** No controller, DTO, endpoint or schema touched. The
  OpenAPI contract test remains at **38**, unchanged from VB-8B, which is itself
  the proof nothing moved.
* **Scheduler:** 17 `@Scheduled` sites before and after — one scheduler, 30 s,
  same seven steps.

---

## 6. Scope verification

Verified on the 522 added production lines:

| Check | Result |
|---|---|
| Batch-wide transaction spanning external dial I/O | **None** — `processDueAttempts` has no `@Transactional` |
| Critical method relying on accidental self-invocation | **None** — all scheduler boundaries go through `txTemplate` |
| Duplicate scheduler | **None** |
| New distributed lock / Redis / Kafka / K8s / `synchronized` | **None** |
| `CampaignEntity` reintroduced as execution-time config source | **None** — no `campaign.get*` / `getTypeConfig` / `getIntegrationConfig` added |
| Campaign repository reintroduced into `CallAttemptService` | **No** — still absent (VB-8B) |
| Safety authority duplicated | **None** — no new `Daily*`/`RetryPolicy` class |
| Tenant filter weakened | **No** — the claim is tenant-scoped in its predicate |
| API change | **None** |
| Migration | **None** |

Not implemented, as instructed: **F-8C-06** (`CANCELLED` writer — product
decision), **F-8C-08** (pagination/unbounded selection — measurement first),
scheduler rewrite, worker queues, campaign versioning, webhook delivery, report
runtime, FreeSWITCH/telephony changes.

---

## 7. Working-tree safety

**No reset, clean, checkout, stash, stage, commit or push.** HEAD and `origin/main`
both remain `3a89e5c`, divergence `0/0`, 0 staged, 0 stashes.

**Changed by VB-8D (14 files):**

*Production (5):* `CallAttemptRepository`, `CampaignExecutionStatus`,
`OutboundDialService`, `CampaignExecutionOrchestrator`, `CallAttemptService`.

*Tests (9):* new `SchedulerTransactionBoundaryPostgresIntegrationTest`, new
`TransactionTestSupport`, plus constructor/stub updates in
`CampaignExecutionOrchestratorSchedulerTest`, `DialBatchContinuationPostgresIntegrationTest`,
`MissedCallPostgresIntegrationTest`, `OutboundDialServicePausedTest`,
`OutboundDialServiceRoutingTest`, `PlayfileRetrySemanticsTest`,
`VoiceBlastDailyDialLimitPostgresIntegrationTest`.

**Preserved untouched:** all user-owned FreeSWITCH/telephony sources and tests,
`infra/`, `tools/`, `.gitignore`, `future-hardening.md`, `campaign-readiness.md`,
`.env.example`, every VB-7C.3 and VB-8A artefact, and the whole `frontend/`
workstream.

**Concurrency note, reported honestly:** a large `frontend/` workstream was
actively landing new files, renames and modifications **while this phase ran**.
None of it was touched — this phase wrote only under `backend/`. It is flagged
because it means the working tree was moving underneath the audit trail, and any
future phase should re-verify before assuming a clean baseline.

---

## 8. Deferred

* **F-8C-06** — `CampaignExecutionStatus.CANCELLED` still has no production writer.
  VB-8D *consumes* it correctly (the terminal guard treats it as terminal) but does
  not create it. Remains a product decision; pausing is still the kill-switch.
* **F-8C-08** — the due-attempt selection is still platform-wide, unordered and
  unpaged. Explicitly out of scope here. Note that VB-8D substantially reduces its
  blast radius: the batch is no longer one transaction, so a single failure no
  longer rolls back an entire backlog of dispatched calls.
* The two VB-8C product decisions stand: cancellation, and whether attempt
  creation should require the prior attempt to have failed.

---

## 9. Final verdict

**READY.**

All five implementable findings are closed and proven against real PostgreSQL,
with the full suite green and architecture unchanged. The one behavioural change
worth calling out: a manual attempt and a scheduler attempt now share **one**
execution-lifecycle authority (`CampaignExecutionStatus`), so neither path can
drift from the other.

What is genuinely guaranteed now, and was not before:

1. A batch never contains a transaction whose rollback can erase an
   already-dispatched external telephony side effect.
2. Each attempt is processed in its own durable transaction boundary.
3. One poisoned row cannot stop the rest of the tick.
4. The scheduler's per-execution boundaries actually exist.
5. A terminal execution fails closed before dispatch.
6. Concurrent workers cannot both dispatch the same attempt — the database
   decides, tenant-scoped.

Nothing was faked to achieve this: the concurrency test uses six real threads
against real rows, and the transaction test proves real rollback and real commit.
