# VB-8I — Campaign Lifecycle, Control Intent & Execution Semantics Audit

**STATUS: AUDIT ONLY. No production code, migration, API, column or test was modified in this phase.**

## 1. Executive Summary

The current architecture does **not** support the intended four-concept model. It has a
single `CampaignStatus` enum that is simultaneously used as a configuration lifecycle, an
operator control channel, and (nominally) an execution rollup — and the third of those is
pure fiction: **no rollup producer exists anywhere in `src/main`**.

Four findings dominate:

- **C-1 / C-2 (CRITICAL/HIGH) — the model's own documentation contradicts itself and the
  code.** `CampaignStatus` declares `RUNNING`/`COMPLETED`/`FAILED` to be *"aggregate
  rollups driven by the future execution engine"* and *"System-driven"*.
  `CampaignExecutionStatus` declares the same field to be *"the campaign's configuration
  lifecycle"*. Both cannot be true, and neither matches the code.
- **C-3 (HIGH) — `CampaignStatus.RUNNING` is unreachable.** Nothing writes it. A campaign
  actively dialling a million contacts reads `SCHEDULED`.
- **C-4 (CRITICAL) — the calling window is never enforced at dispatch.**
  `processDueAttempts` selects `QUEUED AND scheduled_at < now`. The window shapes only the
  *initial* `scheduledAt`. After 09:00 passes, attempts keep dialling at 20:00, at 03:00,
  at weekends. **`CALLING_WINDOW_CLOSED` does not exist as a concept in this system.**
- **C-7 (HIGH) — concurrent duplicate executions are allowed with no guard**, so every
  contact can be dialled once per execution.

**This audit also records that VB-8H — a previous phase of mine — deleted
`SYSTEM_DRIVEN_TRANSITIONS` on the reasoning that campaign status is control state. That
was a product decision taken without a product mandate, it contradicts the enum's own
documented intent, and it silently invalidated a frontend module that transcribes that
constant. See §20 (C-5) and §21.**

## 2. Current Architecture

```
CampaignService.changeStatus          operator -> CampaignStatus (the ONLY writer)
  └─ LEGAL_TRANSITIONS                (CampaignService L68-83 area)
  └─ DRAFT->SCHEDULED also validateActivation

CampaignExecutionService.execute      operator -> CampaignExecution(REQUESTED) + snapshot
  └─ idempotency key only; no "already running" guard

CampaignExecutionOrchestrator.scheduledTick      (30s, 8 steps)
  1 start-requested-executions  all REQUESTED -> RUNNING  (readiness gate; defers if not executable)
  2 process-retries             all RUNNING   -> creates retry attempts
  3 dial-due-attempts           QUEUED AND scheduled_at < now  -> claim -> dispatch
  4 pump-esl-events
  5 reconcile-executions        all-terminal? -> COMPLETED/FAILED
  6 reconcile-stale-calls       VB-8E orphan/session recovery
  7 terminate-missed-call-budgets

OutboundDialService.dispatchClaimedAttempt
  L328 campaign gate   !isExecutable(status)   -> requeue, return     (VB-8H)
  L422 resolveRoute                             -> fail if none
  L471 VB-6C admit (reserve daily dial slot)
  L485 voiceCapacity.reserve                   -> release VB-6C hold if full
  L520 VB-6D.3 admit (consume daily attempt)
  L540 dialer.dial                             (FreeSWITCH)
```

**There is no execution→campaign aggregation step anywhere.**

## 3. CampaignStatus Semantic Matrix

| State | Documented meaning (`CampaignStatus.java`) | Actual writer | Actual meaning in code | Coexists w/ RUNNING execution? | User or system? |
|---|---|---|---|---|---|
| `DRAFT` | "Being configured; not executable" | `clone`; `changeStatus` | The only editable state (`EDITABLE_STATUSES`) | yes (unlikely but legal) | user |
| `SCHEDULED` | "Valid and configured for execution" | `changeStatus` | **In practice, the de-facto "running" state** — it is what an actively-dialling campaign reads | **yes — this is the real running state** | user |
| `RUNNING` | "Execution has started. System-driven entry" | **NONE** | **Unreachable.** No producer exists | n/a | **neither** |
| `PAUSED` | "Intentionally suspended; reversible" | `changeStatus` | The dispatch gate (`!isExecutable`) | **yes — deliberately** | user |
| `COMPLETED` | "Finished successfully. System-driven" | **NONE** | **Unreachable.** No rollup | n/a | **neither** |
| `FAILED` | "Terminated unsuccessfully. System-driven" | **NONE** | **Unreachable.** No rollup | n/a | **neither** |
| `ARCHIVED` | "Retained for historical. Terminal" | `changeStatus` | Pure metadata — **does not stop a RUNNING execution from existing, and (post VB-8H) does stop new dispatch** | **yes — and strands it** | user |

Three of seven states have no producer. Only `DRAFT` and `PAUSED` carry a *derived*
meaning that the engine actually honours.

## 4. Campaign Control Intent Audit

**There is no control-intent concept.** `CampaignStatus.PAUSED` is doing double duty as
both a lifecycle state and the dispatch control, which is why §20's C-1/C-2 contradiction
exists.

- No `pause`/`resume`/`start` intent field, command, or action model exists anywhere
  (`search for pause/resume intent` → only `CampaignService.changeStatus`).
- No platform-enforced pause exists. There is no kill-switch, no tenant-level or
  system-level stop, and no *reason* attached to a pause.
- `PAUSED` is the **only** runtime control the operator has, and it is encoded as a
  lifecycle state rather than a command.
- **Is a separate persisted ControlIntent required?** Not yet determinable — see §21. What
  is required is a *decision*, because Scenario 2 (platform pause) has nowhere to live.
- **If not introduced**, the following is lost: *who* paused (user vs platform) and *why*;
  whether a platform pause can be lifted by the user; any future "resume at 09:00"
  scheduling. None of these are currently representable at all.
- **If introduced**, `PAUSED` in `CampaignStatus` becomes redundant and would have to be
  removed or re-purposed, or the two will contradict — the exact failure the enum Javadoc
  already documents.

## 5. CampaignExecutionStatus Semantic Matrix

| State | Meaning | Producer | Consumer | Unfinished work possible? | Campaign status matters? |
|---|---|---|---|---|---|
| `REQUESTED` | Accepted, awaiting engine pickup | `CampaignExecutionService.execute` | `start-requested-executions` | yes | **yes** — non-executable ⇒ deferred, never failed |
| `RUNNING` | Engine started | `doStartExecution` L269 | dial path, retries, reconcile | **yes — the normal case across days** | only via the dial gate (PAUSED/archive) |
| `COMPLETED` | Finished successfully | `reconcileExecutionInternal` L539 | terminal | no — requires all attempts terminal | **no** — reconcile never reads the campaign |
| `FAILED` | Terminated with an error | L541 (all terminal, none completed) and L261 (start readiness failure) | terminal | no | no |
| `CANCELLED` | "Cancelled before or during processing" | **NONE** | `TERMINAL` set only | n/a | n/a |

`RUNNING` is the state that spans a multi-day campaign, and it is *not* a transient
state — it is the resting state for the entire life of a large campaign.

## 6. Campaign Transition Matrix

All operator-driven post-VB-8H. `?` = no producer; the edge is legal but unreachable.

| From → To | Legal | Producer | Caller | Documented? | Tested? |
|---|---|---|---|---|---|
| DRAFT → SCHEDULED | yes | `changeStatus` (+`validateActivation`) | user | yes | yes |
| SCHEDULED → DRAFT | yes | `changeStatus` | user | yes | yes |
| SCHEDULED → PAUSED | yes | `changeStatus` | user | yes | yes (VB-8H) |
| SCHEDULED → ARCHIVED | yes | `changeStatus` | user | yes | no execution-interaction test |
| SCHEDULED → RUNNING | yes **now** | `changeStatus` | user | **enum says system-driven** | VB-8H (operator) |
| RUNNING → PAUSED | yes | `changeStatus` | user | yes | yes |
| RUNNING → COMPLETED | yes **now** | `changeStatus` | user | **enum says system-driven** | VB-8H (operator) |
| RUNNING → FAILED | yes **now** | `changeStatus` | user | **enum says system-driven** | VB-8H (operator) |
| PAUSED → SCHEDULED | yes | `changeStatus` | user | yes | yes |
| PAUSED → RUNNING | yes | `changeStatus` | user | yes | no |
| PAUSED → ARCHIVED | yes | `changeStatus` | user | yes | no |
| COMPLETED → ARCHIVED | yes `?` | `changeStatus` | user | yes | no |
| FAILED → ARCHIVED | yes `?` | `changeStatus` | user | yes | no |
| ARCHIVED → (none) | terminal | — | — | yes | yes |

**Implemented but not documented:** all three edges VB-8H re-enabled (they are still
documented as system-driven). **Documented but not implemented:** the entire
"execution engine drives campaign status" design, which has no code.

## 7. Execution Transition Matrix

| From → To | Legal | Producer | Owner | Documented? | Tested? |
|---|---|---|---|---|---|
| (new) → REQUESTED | yes | `CampaignExecutionService.execute` | operator | yes | yes |
| REQUESTED → RUNNING | yes | `doStartExecution` L269 | engine | yes | VB-8H |
| REQUESTED → FAILED | yes | L261 (non-deferable readiness failure) | engine | no (undocumented) | yes |
| REQUESTED → CANCELLED | — | **NONE** | — | enum doc | no |
| RUNNING → COMPLETED | yes | L539 | engine | yes | VB-8H |
| RUNNING → FAILED | yes | L541 | engine | yes | VB-8H |
| RUNNING → CANCELLED | — | **NONE** | — | enum doc | no |

States with no producer: **`CANCELLED`**. Transitions with ambiguous ownership: none —
execution writes are cleanly confined to `CampaignExecutionOrchestrator`.

## 8. Ownership Matrix

| Concern | Owner | Evidence |
|---|---|---|
| Campaign configuration | `CampaignService` (editable only in DRAFT) | `CampaignLifecyclePolicy.EDITABLE_STATUSES` |
| Campaign status (all writers) | `CampaignService.changeStatus` | only writer; `CampaignMapper.clone` seeds DRAFT |
| Campaign dispatch control | `CampaignStatus` via `!isExecutable` | `OutboundDialService` L328 |
| Execution start | `CampaignExecutionOrchestrator` | L269 |
| Execution terminal state | `CampaignExecutionOrchestrator.reconcileExecutionInternal` | L539/L541 |
| Retry decision | `RetryPolicyService` (pure function) | L414 |
| Attempt lifecycle | `CallAttemptService` / dial path / ESL / reconcilers | — |
| **Execution→Campaign aggregation** | **NOBODY** | no code path exists |
| **Platform / kill-switch pause** | **NOBODY** | no code path exists |

## 9. Completion Semantics

**The 1,000,000-contact example, answered from source.**

`createInitialAttempts` (L286-350) materialises one `QUEUED` attempt per live contact in a
single pass at execution start, with `scheduledAt = calculateNextScheduledAt(...)`.
`reconcileExecutionInternal` requires **all** attempts to be in
`TERMINAL_ATTEMPT_STATUSES`.

| Question | Answer | Evidence |
|---|---|---|
| Is the execution COMPLETED at 19:00? | **No** — 750,000 attempts are still QUEUED, so `allTerminal` is false and it returns early | L529-533 |
| Is the campaign COMPLETED? | **No** — and it *cannot* be; no rollup exists | §8 |
| Campaign status at 19:00? | **`SCHEDULED`** (never `RUNNING`) | §3 |
| Execution status at 19:00? | `RUNNING` | L269 |
| What happens at 09:00 next day? | **Nothing special is required** — and nothing stops it dispatching at 19:00 tonight either | see below |
| Same execution or new? | **Same execution.** No new execution is created; nothing needs to wake it because the scheduler simply finds `scheduled_at < now` | L141-143 |
| What determines work is exhausted? | All attempts terminal. Retries are included automatically because a retry is a *new attempt* in the same execution | L529 |

**`CALLING_WINDOW_CLOSED` is NOT distinguished from `WORK_EXHAUSTED`.** The good news: the
architecture does **not** treat window closure as completion, which is correct. The bad
news is in §14: the window is not enforced at all.

**Authoritative source for "complete":** the attempt table, and nothing else. No contact
count, no campaign rollup, no window state.

## 10. Failure Semantics

`CampaignStatus.FAILED` has **no producer** and is unreachable, so it currently means
nothing. The only real failure semantics live on the execution:

- `FAILED` (L541) = every attempt terminal and **none** `COMPLETED`. A campaign where 5,000
  of 100,000 calls fail but 95,000 complete yields `COMPLETED`, not `FAILED` — the `FAILED`
  branch is all-or-nothing.
- `FAILED` (L261) = the execution could not start because readiness failed for a
  non-deferable reason (e.g. withdrawn DID, missing audio). Note this is the *opposite*
  polarity to a partial-call-failure campaign.
- Individual call failures are `CallAttemptStatus.FAILED` + `CallFailureCode`, owned by the
  attempt.

**Infrastructure failure is not represented in any execution or campaign state.** A trunk
down produces per-attempt failures, which feed the retry policy, which is correct — but
there is no aggregate "degraded" signal at any level.

## 11. Pause / Resume Semantics

**Scenario 1 — user pauses a running campaign.**

| Aspect | Result | Evidence |
|---|---|---|
| Campaign status | → `PAUSED` | `changeStatus` |
| Control intent | *none exists* | §4 |
| Execution status | stays `RUNNING` | no campaign coupling in reconcile/dial-gate-failure path |
| Queued attempts | retained, `QUEUED`, requeued every tick | `requeueAttempt` L319 |
| Active calls | **continue, never terminated** | gate is checked only for a *new* dispatch; no media/leg call on the pause path |
| Retries | **still created**; dispatch blocked by the gate | `processRetriesForExecution` never reads campaign status |
| Future dispatch | blocked until resume | L328 |

**Scenario 2 — platform pause (tenant generating too much traffic).** **This cannot be
expressed at all.** There is no platform pause, no reason field, no owner distinction, and
no auto-resume. It would have to be faked by an operator setting `PAUSED`, which loses
who-paused, why, and whether the user may lift it.

**Scenario 3 — `PAUSED` + `RUNNING` execution with unfinished work.** Intentionally valid
and extensively tested in VB-8H. Correct.

**Scenario 4 — `PAUSED` with active calls.** Calls continue (§ Scenario 1). No termination,
no reclassification. Correct and asserted in VB-8H.

## 12. Platform Pause Semantics

**Non-existent.** See §11 Scenario 2. This is the single largest functional gap between
the intended model and the code, and it is the strongest argument for a real control-intent
concept — but per §20 it is a product decision, not something this audit may invent.

## 13. Infrastructure / Capacity Blocking Semantics

| Condition | Campaign status | Execution status | Budget consumed? | Work retained? | Resumes? |
|---|---|---|---|---|---|
| Trunk/routing none (L422) | unchanged | `RUNNING` | **no** — before VB-6C admit | yes (marked failed, retried) | yes, via retry policy |
| No capacity (L485) | unchanged | `RUNNING` | VB-6C hold taken then **released**; VB-6D.3 **not** reached | yes | next tick |
| FreeSWITCH down (L540) | unchanged | `RUNNING` | VB-6C hold **released**; VB-6D.3 **consumed** | yes (CANCELLED, retried) | yes |
| **Calling window closed** | **unchanged** | **`RUNNING`** | **consumes everything — work proceeds** | n/a | n/a |
| Retry delay not elapsed | unchanged | `RUNNING` | no — `scheduled_at` gate | yes | when `scheduled_at` passes |

**No infrastructure condition changes `CampaignStatus` or `ExecutionStatus`, and that is
correct** — they are per-attempt outcomes feeding the retry policy. But note the asymmetry:
the *only* condition that changes campaign-level behaviour is `PAUSED` (and, since VB-8H,
any non-executable state), while **calling-window closure — the single most important
operational condition in a voice-blast product — is not modelled at any level.**

## 14. Calling-Window / Multi-Day Semantics

**The window is computed once and never enforced.** Evidence:

- `ExecutionScheduleCalculator.calculateNextScheduledAt` is called from exactly two
  places: `createInitialAttempts` (L326) and `adjustToScheduleWindow` for retries (L451).
- `processDueAttempts` selects `findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(QUEUED, now)`
  — **no window predicate anywhere**. A structural search for
  `isWithinWindow|callingWindow|isWithinSchedule|windowClosed` in `src/main` returns
  **zero results**.
- Consequence: once `scheduled_at` (09:00 on day 1) is in the past, the attempt is due
  **forever** — at 19:01, at 23:00, at 03:00, on a Saturday if `allowedDaysOfWeek`
  excludes it.
- `adjustToScheduleWindow` for retries has the same fate: it only picks a start-of-window
  timestamp, which then becomes permanently due.

**Multi-day (§H):** an execution *can* span days, and does so by construction — work is
materialised up front and the execution simply stays `RUNNING`. Nothing creates a new
execution per day, nothing wakes it, and the campaign sits at `SCHEDULED` overnight.
Retries whose computed time falls outside calling hours are clamped to the next window
start — and then, per the above, are not actually held there.

**This is a live correctness defect, not a modelling preference.** A campaign configured
09:00–19:00 will dial outside those hours.

## 15. Retry During Pause / Blocking Audit

1. **Does PAUSED prevent retries being created?** **No** — `processRetriesForExecution`
   (L390-465) never reads campaign status.
2. **Does PAUSED prevent retry dispatch?** **Yes**, via the VB-8H dispatch gate.
3. **Does creating a retry consume budget?** **No** — `RetryPolicyService.evaluate` is
   documented as "a pure function… no counters, no reservations, no I/O" (L410-413).
   Allowance is bounded by `maxAttempts`, not consumed at creation.
4. **Does PAUSED cause repeated requeue every tick?** **Yes** — every 30 s, per queued
   attempt: select → `claimForDispatch` → gate → `requeueAttempt` → save. Two writes and
   one log line per attempt per tick.
5. **Hot loop?** Effectively yes, for large paused campaigns: a 1M-attempt paused campaign
   would perform ~2M writes every 30 s. The pause gate is checked *after* the atomic claim,
   so the claim/requeue cycle is unavoidable in the current design.
6. **Does a blocked condition consume daily-attempt budget?** For the **pause** gate, no
   (gate precedes L520; VB-8H asserts `verifyNoInteractions`). For **capacity**, no.
   For a **FreeSWITCH failure**, yes — a dispatch was genuinely attempted.
7. **VB-6C provider-accepted budget?** Only consumed on `+OK`, and released on every
   pre-acceptance failure. Consistent.
8. **Contradiction with VB-6C/VB-6D?** No semantic contradiction. The *operational* cost
   of the claim/requeue cycle under pause is new and is not accounted for anywhere.

## 16. Multiple Execution Semantics

**Multiple concurrent executions are allowed, with no guard.** `CampaignExecutionService.execute`
checks only an **opt-in** `idempotencyKey`; there is no "is this campaign already
executing?" check and no uniqueness constraint.

| Case | What happens | What `CampaignStatus` should mean |
|---|---|---|
| A=COMPLETED, B=RUNNING | both persist; B keeps dialling | **UNDEFINED** |
| A=FAILED, B=RUNNING | as above | **UNDEFINED** |
| A=COMPLETED, B=REQUESTED | as above | **UNDEFINED** |
| A=RUNNING, B=RUNNING | **every contact gets an attempt in BOTH** → double-dialled | **UNDEFINED** |
| A exhausted, B substantial | both scanned every tick by retries + reconcile | **UNDEFINED** |

**No rollup algorithm is defined anywhere in the codebase.** The enum Javadoc implies one
("aggregate rollups") but no code implements it, and the frontend has no rollup logic.
Per the audit rules I do **not** invent one: this is recorded as an unresolved product
decision (§21).

The daily contact limits (VB-6C, 3/day) and daily attempt limits (VB-6D.3) would *partly*
mask case 4, but they are safety caps, not a duplicate-execution guard, and they are
tenant-wide rather than per-execution.

## 17. Archived Campaign Semantics

| Question | Answer | Evidence |
|---|---|---|
| Is ARCHIVED terminal? | Yes | `LEGAL_TRANSITIONS[ARCHIVED] = Set.of()` |
| Can an ARCHIVED campaign have a RUNNING execution? | **Yes** | no check in `changeStatus`; `CampaignService` has **zero** references to `executionRepository`/`attemptRepository` |
| Does archive stop dispatch? | **Yes, since VB-8H** | the `!isExecutable` gate |
| Does archive cancel the execution? | **No** | no producer for `CANCELLED` |
| Is it merely metadata? | **It became a dispatch gate in VB-8H** — the enum doc still calls it "retained for historical/reference purposes" | contradiction |

**New finding (C-6, MEDIUM):** archiving a campaign that owns a `RUNNING` execution
**strands that execution in `RUNNING` forever** — the dispatch gate keeps requeueing its
attempts, none ever become terminal, `reconcileExecution` returns early forever, and the
scheduler keeps scanning it in `process-retries` and `reconcile-executions` every tick.
There is no path that ever settles it. VB-8H's gate change made archive *stop the calls*
but left the execution permanently open, which is a worse state than either extreme.

## 18. Frontend / API Semantics

**C-5 (HIGH) — the frontend is a transcription of a constant VB-8H deleted.**
`frontend/src/lib/domain/campaign-lifecycle.ts` states, as verified fact:

> `VERIFIED — CampaignService.java:68-83` … `SYSTEM_DRIVEN_TRANSITIONS = Set.of("SCHEDULED>RUNNING", …)`
> `availableTransitions` — steps 1 and 2 combined, i.e. what a user may actually click.

Its whole purpose is to hide the engine-owned edges. **VB-8H removed that constant, so the
frontend now hides three transitions the backend accepts, and its "VERIFIED" line
references are stale.** The two halves of the product now disagree about what a user may
do. I did not modify it (user-owned); it is reported.

**C-8 (MEDIUM) — the B10 fix is invisible.** `deferredReason` (added in VB-8H to explain
why an execution is `REQUESTED`) is **not referenced anywhere in the frontend**. The
backend-only visibility fix reaches no user.

**What the frontend currently treats `CampaignStatus` as:** primarily **control intent**
(it renders pause/resume/transition affordances from it), secondarily lifecycle. It does
not treat it as runtime state or an execution aggregate — consistent with there being no
rollup to display.

## 19. VB-8H Reconciliation

| # | VB-8H conclusion | Verdict | Evidence |
|---|---|---|---|
| 1 | PAUSED is a dispatch gate | **PASS** (and widened to all non-executable states) | `OutboundDialService` L328 |
| 2 | REQUESTED can remain deferred | **PASS** | `isDeferredRatherThanFailed` L706 |
| 3 | RUNNING execution stays RUNNING while paused | **PASS** | VB-8H PostgreSQL tests |
| 4 | Active calls not terminated by PAUSE | **PASS** | gate is dispatch-only; no media call on the path |
| 5 | Queued attempts retained/requeued | **PASS** (with the churn cost in §15) | `requeueAttempt` L319 |
| 6 | Resume continues the existing execution | **PASS** | VB-8H test |
| 7 | Campaign transitions controlled by `CampaignService` | **PASS** | only writer |
| 8 | Execution RUNNING/COMPLETED/FAILED owned by the engine | **PASS** | L269/L539/L541 |
| 9 | Campaign can have multiple executions | **PASS** (and unguarded) | §16 |
| 10 | Campaign status cannot mirror one execution | **PASS** — and this is the load-bearing argument for C-1 | §16 |
| — | *(implied)* VB-8H removed the system-driven edges legitimately | **PARTIAL / CONTRADICTED** | the enum documents those edges as the intended design, and the frontend depends on them (§20 C-5) |

## 20. Contradictions Found

| ID | Sev | Contradiction |
|---|---|---|
| **C-1** | **CRITICAL** | `CampaignStatus` documents `RUNNING`/`COMPLETED`/`FAILED` as *"aggregate rollups driven by the future execution engine"* / *"System-driven"*. **No rollup code exists**, and since VB-8H these edges are operator-driven. The enum's stated semantics, the code, and the product intent are three different things. |
| **C-2** | **HIGH** | `CampaignExecutionStatus` documents `CampaignStatus` as *"the campaign's configuration lifecycle"* — irreconcilable with C-1. Two enums' Javadocs define the same field incompatibly. |
| **C-3** | **HIGH** | `CampaignStatus.RUNNING` is unreachable. The de-facto running state is `SCHEDULED`, so "campaign is running" has no representation. |
| **C-4** | **CRITICAL** | The calling window is never enforced at dispatch. `CALLING_WINDOW_CLOSED` is unmodelled; configured calling hours are violated. |
| **C-5** | **HIGH** | Frontend `campaign-lifecycle.ts` transcribes the deleted `SYSTEM_DRIVEN_TRANSITIONS`; backend and frontend now disagree about legal user actions. |
| **C-6** | **MEDIUM** | ARCHIVED strands `RUNNING` executions permanently — dispatch stops but nothing ever settles the execution. |
| **C-7** | **HIGH** | Concurrent duplicate executions are unguarded; every contact can be dialled once per execution. |
| **C-8** | **MEDIUM** | `deferredReason` is unconsumed by the frontend, so the B10 fix reaches no user. |
| **C-9** | **MEDIUM** | A paused campaign re-claims and re-queues every queued attempt every 30 s, and keeps creating retries. Unbounded write churn. |
| **C-10** | **LOW** | `CampaignExecutionStatus.CANCELLED` still has no producer. |
| **C-11** | **MEDIUM** | `PAUSED` is both a lifecycle state and the dispatch control, with no third concept for "why". This is the direct cause of C-1/C-2. |

## 21. Unresolved Product Decisions

These **cannot** be resolved from the source and are not invented here.

1. **Is `CampaignStatus` a control surface or an execution rollup?** C-1 says rollup; the
   code (post-VB-8H) says operator control; C-2 says configuration lifecycle. The three
   cannot coexist. A decision is required, and it retroactively determines whether VB-8H's
   removal of `SYSTEM_DRIVEN_TRANSITIONS` was correct.
2. **Does the product need a platform/kill-switch pause, and does it need a *reason* and
   an *owner*?** (§11 Scenario 2, §12)
3. **What is the rollup rule when several executions exist?** No algorithm exists (§16).
4. **What does `CampaignStatus.COMPLETED` mean, and who sets it?** No candidate condition
   is defined anywhere.
5. **Are concurrent executions per campaign legitimate?** (§16, C-7)
6. **Must the calling window be enforced at dispatch, and what is the intended behaviour
   for a retry computed outside calling hours?** (C-4)
7. **Should `ARCHIVED` settle, cancel, or leave running executions?** (C-6)
8. **Is a persisted `ControlIntent` required, or is intent a command?** (§4)

## 22. Proposed Canonical State Model

**Conceptual — four distinct concepts, never collapsed into one enum:**

```
┌─────────────────────────────────────────────────────────────┐
│ CAMPAIGN STATUS          configuration lifecycle            │
│   DRAFT → SCHEDULED → ARCHIVED                             │
│   "is this campaign configured and permitted to be offered?"│
└─────────────────────────────────────────────────────────────┘
              ▲                          │  operational gate (per tick)
              │ control                  ▼
┌──────────────────────────┐   ┌────────────────────────────────┐
│ CONTROL INTENT           │   │ OPERATIONAL CONDITION         │
│   RUN | PAUSE            │   │  WINDOW_OPEN | WINDOW_CLOSED  │
│   owner: user|platform   │   │  CAPACITY_OK | NO_CAPACITY    │
│   reason (for platform)  │   │  PROVIDER_OK | NO_PROVIDER    │
│   NOT a lifecycle state  │   │  transient, never persisted   │
└──────────────────────────┘   └────────────────────────────────┘
                                    │ may block dispatch; must NEVER
                                    │ change a lifecycle state
                                    ▼
┌─────────────────────────────────────────────────────────────┐
│ EXECUTION STATUS         runtime lifecycle of one run       │
│   REQUESTED → RUNNING → COMPLETED | FAILED | CANCELLED      │
│   derived from ATTEMPT work-exhaustion only                 │
└─────────────────────────────────────────────────────────────┘
```

**Recommended representation (to be decided, not implemented here):**

| Concept | Representation | Rationale |
|---|---|---|
| Campaign Status | keep as configuration lifecycle; `RUNNING`/`COMPLETED`/`FAILED` either gain a real rollup producer or are **removed** from the enum | C-1/C-3 |
| Control Intent | **most likely a command, not new state** — `POST /campaigns/{id}/pause`, `POST .../resume` — with the *effect* being recorded as an audit event. A persisted field is only required if a **platform** pause must survive restarts and be user-liftable, which is decision §21.2 | avoids C-11 |
| Operational Condition | **derived, never persisted** — computed per tick from the snapshot schedule + capacity + provider health. It is a *predicate*, not a state | C-4; persisting it would create stale truth |
| Execution Status | unchanged, already correct and attempt-derived | §5 |

**If the product wants campaign-level progress reporting** (the F5 Queues question), that
should be a **read-only rollup projection** over executions — never a lifecycle
transition, and never a dispatch gate.

## 23. Required Changes for Next Phase

Ordered by severity. **None implemented here.**

| # | Change | Sev | Notes |
|---|---|---|---|
| 1 | **Enforce the calling window at dispatch.** Add a window predicate to due-attempt selection (or a re-computed gate) so attempts cannot dispatch outside the snapshot's window. | **CRITICAL** | C-4. Must be snapshot-derived, never live-campaign. |
| 2 | **Resolve the campaign-status contradiction** with the product, then either implement a real rollup producer or remove the three unreachable states. | **CRITICAL** | C-1/C-2/C-3. Blocked on §21.1. |
| 3 | **Guard concurrent executions** per campaign (at minimum reject creation when an active execution exists). | **HIGH** | C-7 |
| 4 | **Reconcile the frontend** `campaign-lifecycle.ts` with whichever backend model wins. | **HIGH** | C-5. Frontend is user-owned; needs coordination. |
| 5 | **Decide archive semantics** and settle stranded executions. | **MEDIUM** | C-6 |
| 6 | **Move the pause check before the atomic claim** (or add a cheap pre-filter) to stop the claim/requeue churn. | **MEDIUM** | C-9 |
| 7 | **Decide whether retries may be created while paused.** | **MEDIUM** | §15.1 |
| 8 | **Consume `deferredReason` in the frontend.** | **MEDIUM** | C-8 |
| 9 | **Decide `CANCELLED` producer** or remove the state. | **LOW** | C-10 |
| 10 | Correct the two contradictory enum Javadocs regardless of the model decision. | **LOW** | cheap, prevents future confusion |

## 24. Test Matrix Required for Next Phase

- Window enforcement: attempt due at 19:01 with a 09:00–19:00 window must **not** dispatch; must dispatch at 09:00 next day; must hold across `allowedDaysOfWeek` and `endDate`.
- Window + retry: a retry computed at 20:00 must be held to the next window open.
- Window + capacity/failure: a blocked attempt inside the window must not leak a hold across the boundary.
- 1M-contact multi-day: attempt set materialised once; execution stays `RUNNING` across days; completes only when the last attempt is terminal.
- Duplicate execution: creating a second execution while one is active must be rejected (or explicitly permitted and tested).
- Archive with a `RUNNING` execution: must not strand it — assert a defined terminal state.
- Pause churn: assert bounded writes per tick for a paused campaign with many queued attempts.
- Rollup (if implemented): every combination in §16 must have a defined, tested outcome.
- Invariants P1–P15 from the brief must each have an existing or new test; today **P12 (window closure ≠ completion)** holds only incidentally, and **P7 (calling-window semantics authoritative)** does **not** hold.

## 25. Final Verdict

### Decision gate

| # | Question | Answer |
|---|---|---|
| 1 | What does `CampaignStatus` mean? | **Ambiguous — three conflicting definitions.** As built (post-VB-8H) it is an *operator control + configuration lifecycle*; its own Javadoc says *system-driven execution rollup*; `CampaignExecutionStatus` says *configuration lifecycle*. |
| 2 | What does Control Intent mean? | **It does not exist.** `PAUSED` is overloaded to serve it. |
| 3 | What does `CampaignExecutionStatus` mean? | **Clear and correct**: the runtime lifecycle of one run, derived from attempt work-exhaustion. |
| 4 | What is an Operational Condition? | **Does not exist.** Calling-window, capacity and provider health are per-attempt outcomes, except the window, which is **not represented at all**. |
| 5 | Can `CampaignStatus` be changed by both control and runtime? | **Not today** — only an operator writes it. It *should* be, per the intended model, which is exactly the unresolved contradiction. |
| 6 | When does a campaign become `RUNNING`? | **Never, automatically.** Only an operator can, post-VB-8H. |
| 7 | When does a campaign become `PAUSED`? | Operator `changeStatus`; no reason or owner recorded. |
| 8 | When does a campaign become `COMPLETED`? | **Never, automatically.** No definition exists. |
| 9 | When does a campaign become `FAILED`? | **Never, automatically.** No definition exists. |
| 10 | Can a campaign remain `RUNNING` overnight? | The state is unreachable, so the question is moot; a campaign dialling overnight reads **`SCHEDULED`**, and **would keep dialling** (C-4). |
| 11 | Can a campaign be `PAUSED` while an execution is `RUNNING`? | **Yes** — intentionally valid and tested. |
| 12 | Can multiple executions exist simultaneously? | **Yes**, unguarded. |
| 13 | If yes, how is `CampaignStatus` determined? | **Undefined.** No rollup exists. |
| 14 | Is a new `ControlIntent` model required? | **Not decidable from source.** It is required *if* platform pause / pause reasons / user-liftable platform holds are wanted; it is **not** required merely to make `PAUSED` readable, which is already clear. |
| 15 | What must change before F5 Queues? | C-4 (window enforcement) and C-7 (duplicate executions) are correctness defects that a queue layer would inherit and amplify. C-1/C-2 need a product decision. |

**NOT READY — PRODUCT DECISION REQUIRED**

Not because the audit is incomplete, but because the two CRITICAL findings cannot be
resolved from source:

- **C-4** is a defect with an unambiguous correct fix (enforce the window) and needs no
  decision — only authorisation to implement, which this phase is forbidden to give.
- **C-1/C-2** is a genuine product question: *is `CampaignStatus` a control surface or an
  execution rollup?* The enum Javadoc says rollup; the code says control; VB-8H changed it
  to control and invalidated a frontend module that encoded the rollup reading. Choosing
  silently here would be inventing a product model, which this audit is explicitly
  forbidden to do.

The next phase should be **VB-8J — calling-window enforcement + campaign-status model
decision**, with C-7 folded in, and C-5 requiring frontend coordination.
