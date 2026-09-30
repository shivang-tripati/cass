# VB-8J — Calling-Window Enforcement, Campaign Status, Duplicate-Execution Policy

Resolves the VB-8I findings C-1/C-2/C-3 (status model), C-4 (calling window never
enforced at dispatch), C-7 (unguarded concurrent executions) and C-5 (frontend
contract drift).

---

## 1. Decision gate — CampaignStatus, Control Intent, Execution Status

The four concepts are kept separate. No new `ControlIntent` type or column was
introduced, and no rollup producer was added.

**A. Authoritative meaning of `CampaignStatus`.** The operator-controlled
*configuration and operational-control* lifecycle of a campaign as a configured,
dispatchable business object. It is not a record of engine activity and is not
derived from executions. Single writer: `CampaignService.changeStatus`.

**B. Transition ownership.** All campaign transitions are operator-driven. None
are runtime-driven and none are derived.

**C. Representation.**

| Situation | Value |
|---|---|
| actively running | `SCHEDULED` |
| paused | `PAUSED` |
| unfinished, window closed | `SCHEDULED` (unfinished ≠ paused) |
| work exhausted | the **execution** is `COMPLETED`; campaign stays `SCHEDULED` |
| failed | the **execution** is `FAILED`; campaign stays `SCHEDULED` |
| archived | `ARCHIVED` |

**D. Multiple executions.** There is no rollup, and campaign status is
*orthogonal to executions by definition*. This dissolves the ambiguity rather
than papering over it: several executions may run for one campaign, so no single
execution could authoritatively set campaign status.

**E. Control intent.** Not required as a new field. `PAUSED` already expresses
operator suspend. Operational conditions (calling window, capacity, provider) are
**derived per tick** and never persisted, so they are not statuses and never
appear as one.

**F. Minimum change.** `RUNNING`/`COMPLETED`/`FAILED` are re-declared as
**reserved execution-facts with no producer** — a deliberate reversal of the
VB-8H position, which had made them operator-settable on the reasoning that
"campaign is control state". That reasoning was right but its consequence was
not carried through: those three describe execution facts. They are *legal* edges
that `changeStatus` still refuses, so the refusal can explain itself.

Both contradictory Javadocs were corrected (`CampaignStatus`, `CampaignExecutionStatus`).

The three values are **retained, not deleted**: `ck_campaigns_status` is a
database CHECK constraint (V15), so removing them is a schema change, not a code
cleanup. No migration was added.

> **Needs product ratification.** This reverses a VB-8H decision that was itself
> taken without a product mandate. It is internally consistent and enforced, but
> the underlying question — "is `CampaignStatus` a control surface or an execution
> rollup?" — was answered here on engineering grounds, not by the product owner.

---

## 2. Calling-window behaviour (C-4)

`ExecutionScheduleCalculator` gains `isWithinWindow(schedule, now)` and
`nextWindowOpen(schedule, now)`.

The gate sits in `OutboundDialService.processOneAttempt` **before** the claim,
using the execution's frozen snapshot — never the live campaign. Consequences:

- A closed window costs **one `scheduled_at` write**, not a claim/requeue cycle
  per attempt per tick. `scheduledAt` is pushed to the next window opening, which
  reuses the existing due-selection mechanism and adds no new state.
- **No budget is consumed**: no VB-6C daily hold, no VB-6D.3 attempt, no capacity.
- The attempt **stays `QUEUED`**, so a window closing can never complete an
  execution. A multi-day execution resumes when the window reopens.
- `IN_PROGRESS` attempts are untouched, so **active calls are never killed** by a
  window closing.
- The snapshot timezone is authoritative, with **no JVM/UTC fallback**; an
  unresolvable zone reads as closed rather than open.

Overnight windows (`endTime` before `startTime`) are handled as one wrapping
window rather than two. This required computing the next opening directly for
wrapping schedules, because the existing day-based adjustment cannot reach it.

### There is no schedule end date

A campaign becomes eligible at its start date and stays eligible across every
future calling window until its work is exhausted. The end-date concept has been
removed outright rather than repurposed — there is no `EXPIRED`, no
`FAILED`-on-end-date and no indefinite-expiry handling.

Removed from: `ScheduleSpec`, `dto/ScheduleConfig`, `CampaignConfigurationSnapshot`
(so existing snapshots stop freezing it), `CampaignConfigurationService`,
`CampaignMapper`, `CampaignService` validation, `CampaignReadinessService`
(the `SCHEDULE_EXPIRED` readiness reason is gone), `ExecutionScheduleCalculator`,
migration `V56` (drops `campaigns.schedule_end_date`, the `ck_campaigns_schedule_dates`
constraint that existed only to order the two dates, and
`campaign_execution_configurations.schedule_end_date`), and the frontend API
type, schedule form, config card, readiness panel and zod schema.

`startDate`, the daily window and `allowedDaysOfWeek` are unchanged. A closed
daily window is a dispatch-time deferral, never a readiness failure.

---

## 3. Duplicate / concurrent execution policy (C-7)

**One in-flight execution per campaign** (`REQUESTED` or `RUNNING`), enforced at
creation.

Rationale: two concurrent executions each materialise an attempt for *every*
contact in the audience, so the same contact is dialled once per execution. The
daily contact and attempt limits would cap that but not prevent it, and they are
safety ceilings, not intent.

Enforcement is a native `SELECT ... FOR UPDATE` on the **campaign row**, read
under that lock. This is not a global or advisory lock: creators of the same
campaign serialise, creators of different campaigns do not contend, and no new
infrastructure or migration was needed. Verified with a 6-thread race producing
exactly one execution.

Ordering in `CampaignExecutionService.execute` is deliberate — the guard runs
**last**:

1. campaign row lock,
2. readiness,
3. **idempotency key** lookup,
4. duplicate in-flight refusal.

After idempotency because retrying a request with the same key must return the
first execution rather than collide with it. After readiness because readiness
reports a fact about configuration while the guard reports a fact about execution
state.

---

## 4. Key implementation changes

| File | Change |
|---|---|
| `ExecutionScheduleCalculator` | `isWithinWindow`, `nextWindowOpen`, midnight-wrapping support |
| `OutboundDialService` | pre-claim window gate; `ExecutionScheduleCalculator` dependency |
| `CampaignService` | `RESERVED_STATES` guard; corrected `LEGAL_TRANSITIONS` |
| `CampaignStatus`, `CampaignExecutionStatus` | corrected Javadocs |
| `CampaignExecutionRepository` | `existsActiveByCampaignId`, `lockCampaignRow` |
| `CampaignExecutionService` | one-in-flight policy, ordered last |
| `CampaignController` | corrected OpenAPI: reserved states, not "engine-driven" |
| `ScheduleSpec`, `ScheduleConfig`, `CampaignConfigurationSnapshot`, `CampaignConfigurationService`, `CampaignMapper`, `CampaignReadinessService` | schedule end date removed |
| `V56__remove_campaign_schedule_end_date.sql` | drops both `schedule_end_date` columns and the date-ordering constraint |
| `frontend/.../campaign-lifecycle.ts` | `RESERVED_STATES` instead of `SYSTEM_DRIVEN_TRANSITIONS`; `availableTransitions` filters reserved targets; dead `systemDrivenTransitionMessage` removed; stale docblocks corrected |

10 `OutboundDialService` construction sites were updated for the new dependency.
`@RequiredArgsConstructor` meant no production constructor change.

---

## 5. Tests

New: `ExecutionScheduleCalculatorWindowTest` (14) — window arithmetic with an
explicitly supplied instant: inside/before/after, overnight, snapshot timezone,
disallowed weekday, exhausted end date, unresolvable zone, no-churn invariant.

New: `CallingWindowAndDuplicateExecutionPostgresIntegrationTest` (10) — closed
window blocks dispatch and keeps the attempt `QUEUED`; no budget consumed;
deferral pushes `scheduledAt` forward and is not reselected next tick; all-day
window still dispatches; window closure never completes the execution;
`IN_PROGRESS` untouched; second execution refused; new execution allowed after a
terminal one; 6-thread concurrent creation yields exactly one.

`CampaignLifecycleServiceTest` — three VB-8H-era tests rewritten to assert the
decided model instead of the reversed one.

Five pre-existing PostgreSQL tests adapted (see §7).

`CampaignOpenApiContractTest` 38 → 41: one previously-dead test restored and two
new assertions on the changed 409 contract.

Frontend: `campaign-lifecycle.test.ts` rebuilt from source (35 tests). Full
frontend suite 24 files / 498 tests, typecheck clean, lint 0 errors.

**Full backend regression: 2163 tests, 0 failures, 0 errors, 2 skipped,
`BUILD SUCCESS`**. The 2 skips are pre-existing and environment-gated
(`ObdApplicationTests` `@Disabled`, and the live-FreeSWITCH contract test).
`ArchitectureTest` ran clean — no new module cycles. No `@Disabled` was added and
there are no Surefire test exclusions.

Removing the end date removed the two tests that asserted an end-date ordering
rule and added two that assert the opposite, so the count is unchanged at 2163
relative to the pre-cleanup baseline.

The frontend suite is 497 tests, typecheck clean, lint 0 errors.

No live FreeSWITCH validation was performed **for the campaign phases**: no
claim here rests on one. Unrelated ESL tests in the telephony unit do read
`infra/.env` and run against a local gateway when it exists, which is why a
working-tree run reports 2 skips and a clean-clone run reports 5 — the four
live-gateway tests assume a password that is deliberately not committed. No
campaign assertion depends on them.

---

## 6. OpenAPI

The generated document is the source of truth and was inspected. My own C-1/C-2/C-3
decision had made the controller **factually wrong**: it advertised
`PAUSED -> RUNNING` as a legal transition and described reserved edges as
"engine-driven". Both corrected, and the new 409 cause on execution creation
documented. Assertions added so the spec enforces this rather than merely stating it.

---

## 7. What this commit is, and what it carries

This is **not** a file-isolated VB-8J diff, and the commit message says so. Two
things make isolation impossible rather than merely inconvenient:

- **VB-8D/VB-8H symbol coupling.** VB-8J's files call
  `CallAttemptRepository.claimForDispatch`, `CampaignLifecyclePolicy.isExecutable`,
  `CampaignLifecyclePolicy.notExecutableReason` and read
  `CampaignExecutionResponse.deferredReason` — none of which exist at `3a89e5c`.
  A VB-8J-only commit does not compile. Verified by building a staged VB-8J-only
  tree in isolation: 4 compilation errors.
- **FreeSWITCH coupling.** VB-8F's `EslEventService` resolves channel identity
  through `EslEvent.CHANNEL_IDENTITY_HEADERS`, which does not exist at `3a89e5c`.
  Verified the same way: the committed tree fails to compile without
  `EslEvent.java` and compiles with it. The whole `com.shivang.obd.telephony`
  unit is therefore included, deliberately, to keep that change whole.

Both were confirmed by building candidate trees in isolated worktrees rather than
assumed, after an earlier VB-8J-only attempt was caught this way before pushing.

## 8. Cost of the C-7 decision — stated plainly

Five pre-existing tests, spanning four subsystems (snapshot immutability ×2,
daily-limit snapshot, missed-call snapshot, retry-policy snapshot), created a
second execution while the first was still `REQUESTED`. Each now settles the
first before creating the second.

**This narrows what they prove.** They establish snapshot immutability across a
*sequential* pair, not a *concurrent* one. Coverage is not lost outright —
`runningExecutionKeepsSnapshot` separately covers a running execution holding its
snapshot — but the concurrent-pair case is no longer tested anywhere. Recorded
here rather than buried.

---

## 9. Deferred / reported, not fixed

- **`retryPolicySchemaDocumented` is dead** (`@DisplayName`, no `@Test`), as was
  `campaignStatusEnumUnchanged`. The latter was restored because it guards the
  contract this phase changes; the former guards `RetryPolicyConfig` and was left
  as found.
- **Per-attempt snapshot resolution on the window-close tick.** Each due attempt
  resolves its execution snapshot. After a window closes this is a one-time cost
  per window rather than per tick, so it was not optimised. Measure before
  changing, as with F-8C-08.
- **No live FreeSWITCH validation was performed.** No report in this phase claims any.

Carried forward unresolved: whether report privacy should constrain attempt-listing
APIs; whether aggregation/pseudonymisation belong in the privacy model; whether
execution `CANCELLED` should be implemented (still no producer); whether a
manual/scheduler attempt should require the prior attempt to have failed.