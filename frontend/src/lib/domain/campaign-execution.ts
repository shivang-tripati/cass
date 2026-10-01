import type { CallAttemptStatus, CampaignExecutionStatus } from "@/lib/api/contracts";

/**
 * The execution-observability model: how often the frontend polls, and when it
 * stops.
 *
 * ## Why the frontend polls at all
 *
 * Nothing a user does advances an execution. VERIFIED: the only writer of
 * `CampaignExecution.status` outside `CampaignService` is
 * `CampaignExecutionOrchestrator`, a `@Service` driven by
 * `@Scheduled(fixedDelay = 30000) scheduledTick()`. Its tick starts REQUESTED
 * executions, processes retries, dials due attempts, pumps ESL events and
 * settles RUNNING ones.
 *
 * So an execution genuinely changes on its own, and a view that does not poll
 * displays a permanently stale value. F4 originally removed the poll on the
 * (then correct) grounds that no engine existed; F4 restored it, and F4.1 exists
 * to check the restoration against the final backend rather than against that
 * recollection.
 *
 * ## The interval is derived, not chosen
 *
 * The engine ticks every 30 s, so 5 s is a sixth of that: a user sees a
 * transition within one poll of it happening, and an in-flight execution costs at
 * most 6 requests per engine cycle. Polling faster than the thing being polled
 * only adds load — which is why the F1 value of 3–4 s was not kept.
 *
 * ## The stop condition is the backend's own set
 *
 * `DISPATCHABLE = {REQUESTED, RUNNING}` is VERIFIED verbatim from
 * `CampaignExecutionStatus`. It is written as a positive list rather than
 * "everything not terminal" so that adding a status is a visible edit here
 * instead of a silent behaviour change in a negation.
 */

/** The engine's tick. VERIFIED `@Scheduled(fixedDelay = 30000)`. */
export const ENGINE_TICK_MS = 30_000;

/** The frontend poll interval — a sixth of `ENGINE_TICK_MS`. */
export const EXECUTION_POLL_MS = 5_000;

/**
 * VERIFIED `CampaignExecutionStatus.DISPATCHABLE = Set.of(REQUESTED, RUNNING)`.
 *
 * A status outside this set is settled and will never change again, so the poll
 * stops.
 */
export const DISPATCHABLE_EXECUTION_STATUSES: readonly CampaignExecutionStatus[] = [
  "REQUESTED",
  "RUNNING",
];

/** VERIFIED `CampaignExecutionStatus.TERMINAL = Set.of(COMPLETED, FAILED, CANCELLED)`. */
export const TERMINAL_EXECUTION_STATUSES: readonly CampaignExecutionStatus[] = [
  "COMPLETED",
  "FAILED",
  "CANCELLED",
];

/**
 * Attempt statuses the engine will never move again.
 *
 * VERIFIED in two independent places, which is why this is safe to rely on:
 * `CampaignExecutionOrchestrator.TERMINAL_ATTEMPT_STATUSES` (private), and
 * `reconcileExecutionInternal`, which settles an execution only once
 * `attempts.stream().allMatch(a -> TERMINAL_ATTEMPT_STATUSES.contains(...))`.
 */
export const TERMINAL_ATTEMPT_STATUSES: readonly CallAttemptStatus[] = [
  "COMPLETED",
  "FAILED",
  "CANCELLED",
];

/** Keep polling this execution? */
export function shouldPollExecution(
  status: CampaignExecutionStatus | undefined,
): boolean {
  if (!status) return false;
  return DISPATCHABLE_EXECUTION_STATUSES.includes(status);
}

/**
 * Keep polling this attempt list?
 *
 * An empty list never polls: there is nothing in flight, and the only way one
 * appears is the engine creating it, which is visible on the execution's own
 * poll. Polling an empty table would be a permanent request for a static answer.
 */
export function shouldPollAttempts(
  statuses: readonly CallAttemptStatus[],
): boolean {
  if (statuses.length === 0) return false;
  return statuses.some((status) => !TERMINAL_ATTEMPT_STATUSES.includes(status));
}

/**
 * Keep polling this execution LIST? Same rule, applied across rows.
 *
 * VERIFIED: `findByStatusAndDeletedAtIsNull` selects the rows the engine acts
 * on, so a list with no dispatchable row has nothing that can change.
 */
export function shouldPollExecutionList(
  statuses: readonly CampaignExecutionStatus[],
): boolean {
  return statuses.some((status) => DISPATCHABLE_EXECUTION_STATUSES.includes(status));
}

/**
 * Alias the attempt view uses, named for the list it guards.
 *
 * Kept as a distinct export rather than an inline import alias so the
 * relationship between the predicate and the decision it drives is visible at
 * the call site, and so a test can pin the name the component depends on.
 */
export const shouldPollAttemptList = shouldPollAttempts;

/* -------------------------------------------------------------------------- */
/* One in-flight execution per campaign (VB-8J)                                */
/* -------------------------------------------------------------------------- */

/**
 * The single execution that currently blocks a new one, or `null`.
 *
 * VERIFIED `CampaignExecutionService.execute`:
 *
 * ```java
 * if (executionRepository.existsActiveByCampaignId(campaignId)) {
 *     throw new BusinessException(BUSINESS_RULE_VIOLATION,
 *         "Campaign already has an execution in progress. Wait for it to reach a "
 *             + "terminal state, or pause it, before starting another.");
 * }
 * ```
 *
 * and `existsActiveByCampaignId` is a native query whose predicate is
 * `e.status IN (REQUESTED, RUNNING) AND e.deleted_at IS NULL` — that is,
 * exactly `DISPATCHABLE`. So "in flight" and "still pollable" are the same set,
 * and `findInFlightExecution` is expressed in terms of `shouldPollExecution`
 * rather than repeating the literals.
 *
 * ## Which execution is returned when several are dispatchable
 *
 * A list can only reach the backend if it was already inconsistent, because the
 * service refuses the second creation. So this is defensive, and it returns the
 * first match in the order the backend supplies: VERIFIED
 * `findByCampaignIdAndTenantIdAndDeletedAtIsNullOrderByRequestedAtDesc`, so index
 * 0 is the most recently requested — the one the user most likely just created,
 * and the one whose "wait for it" message is most useful.
 *
 * The check is UX protection, not enforcement: the backend re-checks under a row
 * lock (`lockCampaignRow`, native `SELECT ... FOR UPDATE` scoped to the one
 * campaign), so two racing creators serialise server-side regardless of what any
 * client believes. A client can neither rely on this to prevent a duplicate nor
 * assume it prevented one.
 */
export function findInFlightExecution<
  T extends { status: CampaignExecutionStatus },
>(executions: readonly T[] | undefined): T | null {
  for (const execution of executions ?? []) {
    if (shouldPollExecution(execution.status)) return execution;
  }
  return null;
}

/**
 * The backend's own 422 text, transcribed for display before the request.
 *
 * VERIFIED verbatim from `CampaignExecutionService.execute` above. It is exported
 * so the disabled control's wording and the message a rejected request produces
 * are the same sentence — a control that explains a rule differently from the
 * error it prevents is its own small honesty bug.
 *
 * Only used to describe the rule. If the server rejects anyway, the server's
 * message is what is shown; this never substitutes for it.
 */
export const IN_FLIGHT_EXECUTION_CONFLICT_MESSAGE =
  "Campaign already has an execution in progress. Wait for it to reach a " +
  "terminal state, or pause it, before starting another.";

/* -------------------------------------------------------------------------- */
/* Why an execution has not started (VB-8H)                                   */
/* -------------------------------------------------------------------------- */

/**
 * What the UI should say about a `REQUESTED` execution, decided from backend
 * fields only.
 *
 * F4.1 filed this as blocker B10: a `REQUESTED` execution gave the user no way
 * to tell "queued, will start shortly" from "held because the campaign is no
 * longer executable", and F4.1 explicitly declined to guess.
 *
 * The backend now answers it. VERIFIED `CampaignExecutionResponse.deferredReason`
 * is derived on read by `CampaignExecutionService.deriveDeferredReason` and is
 * non-null **only** while the status is `REQUESTED` — possible only because an
 * execution can be *created* while its campaign is ready, so the sole remaining
 * reason to still be `REQUESTED` is that the campaign has since left the
 * executable set. The text always comes from
 * `CampaignLifecyclePolicy.notExecutableReason`, whose code is always
 * `CAMPAIGN_NOT_EXECUTABLE_STATE` and whose message names the campaign status.
 *
 * So the frontend has exactly two honest branches and no taxonomy to invent:
 *
 *  - `DEFERRED_BY_CAMPAIGN_STATE` — show the backend's sentence verbatim and say
 *    the engine re-checks on its own cycle.
 *  - `AWAITING_ENGINE` — nothing is blocking it; it is queued.
 *
 * `null` for anything that is not `REQUESTED`, because "why has this not
 * started" is not a real question once an execution is `RUNNING` or terminal. A
 * `deferredReason` arriving on a non-`REQUESTED` execution is treated as
 * absent, which is what the backend guarantees — and if that ever changed, the
 * field still could not be mistaken for a `failureReason`.
 *
 * This deliberately does NOT re-derive the answer from the campaign's status.
 * The backend already decided it; a local re-derivation would be a second source
 * of truth that could disagree with the server's own reason.
 */
export type ExecutionStartState =
  | "DEFERRED_BY_CAMPAIGN_STATE"
  | "AWAITING_ENGINE";

export function executionStartState(
  execution: {
    status: CampaignExecutionStatus;
    deferredReason: string | null;
  } | null | undefined,
): ExecutionStartState | null {
  if (!execution || execution.status !== "REQUESTED") return null;
  return execution.deferredReason ? "DEFERRED_BY_CAMPAIGN_STATE" : "AWAITING_ENGINE";
}
