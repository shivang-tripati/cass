import type { CampaignStatus } from "@/lib/api/contracts";

/**
 * The Campaign lifecycle, transcribed from the backend.
 *
 * ## Why this module exists
 *
 * The F1/F3 UI showed lifecycle actions by asking "does this status look
 * terminal?" — `["COMPLETED","FAILED","ARCHIVED"].includes(status)`. That is
 * not the rule, and it produced controls that could only fail. The real rule is
 * two independent maps in `CampaignService`, and getting either wrong is
 * invisible to typecheck and to any mocked test.
 *
 * ## What the backend actually does
 *
 * VERIFIED — `CampaignService`:
 *
 * ```java
 * private static final Map<CampaignStatus, Set<CampaignStatus>> LEGAL_TRANSITIONS = Map.of(
 *     DRAFT,     Set.of(SCHEDULED),
 *     SCHEDULED, Set.of(PAUSED, DRAFT, ARCHIVED, RUNNING),
 *     RUNNING,   Set.of(PAUSED, ARCHIVED, COMPLETED, FAILED),
 *     PAUSED,    Set.of(SCHEDULED, ARCHIVED, RUNNING),
 *     COMPLETED, Set.of(ARCHIVED),
 *     FAILED,    Set.of(ARCHIVED),
 *     ARCHIVED,  Set.of());
 *
 * private static final Set<CampaignStatus> RESERVED_STATES = Set.of(
 *     RUNNING, COMPLETED, FAILED);
 * ```
 *
 * and `changeStatus` applies them in this order:
 *
 *  1. `LEGAL_TRANSITIONS` does not contain the edge -> `ConflictException` (409)
 *     "Illegal campaign lifecycle transition: FROM -> TO."
 *  2. the target is in `RESERVED_STATES` -> `ConflictException` (409)
 *     "Campaign status TO is reserved: ..."
 *  3. `DRAFT -> SCHEDULED` additionally runs `validateActivation(entity)`, which
 *     is the full configuration gate. It is the only transition that validates.
 *
 * So there are **THREE** outcomes for a proposed transition, not two: illegal,
 * reserved, and legal. The F1 UI collapsed the middle case into "legal", which
 * is why it offered "Pause" on a RUNNING campaign that then offered COMPLETED
 * and FAILED — all of which are permanently 409 through this API.
 *
 * ## The three rules this module encodes
 *
 *  - `legalTransitionsFrom` — step 1.
 *  - `availableTransitions` — steps 1 and 2 combined, i.e. what a user may
 *    actually click. This is what the UI must render.
 *
 *  - `isEditable` — VERIFIED `CampaignLifecyclePolicy.EDITABLE_STATUSES` is
 *    `{DRAFT}` and `assertEditable` throws `ConflictException` otherwise.
 *    Configuration is editable in DRAFT and nowhere else. To change a SCHEDULED
 *    campaign you must first return it to DRAFT, which is a legal manual edge.
 *
 * ## Scope note
 *
 * This is a pure state machine, not authorization. Whether the caller may
 * perform a given transition is answered by `lib/auth/campaign-gates.ts`
 * against `CAMPAIGN_EXECUTE`, which is the capability the service actually
 * checks (VERIFIED `CampaignService.changeStatus` L277-278).
 */
export const CAMPAIGN_STATUSES: readonly CampaignStatus[] = [
  "DRAFT",
  "SCHEDULED",
  "RUNNING",
  "PAUSED",
  "COMPLETED",
  "FAILED",
  "ARCHIVED",
];

/**
 * VERIFIED `CampaignService.LEGAL_TRANSITIONS`, verbatim.
 *
 * Note this is the LEGAL set, not the clickable set: it still contains edges
 * whose target is a reserved status. Use `availableTransitions` to decide what
 * to render.
 */
export const CAMPAIGN_LEGAL_TRANSITIONS: Readonly<
  Record<CampaignStatus, readonly CampaignStatus[]>
> = {
  DRAFT: ["SCHEDULED"],
  SCHEDULED: ["PAUSED", "DRAFT", "ARCHIVED", "RUNNING"],
  RUNNING: ["PAUSED", "ARCHIVED", "COMPLETED", "FAILED"],
  PAUSED: ["SCHEDULED", "ARCHIVED", "RUNNING"],
  COMPLETED: ["ARCHIVED"],
  FAILED: ["ARCHIVED"],
  ARCHIVED: [],
};

/**
 * VERIFIED `CampaignService.RESERVED_STATES`.
 *
 * These are legal edges in the state machine that `changeStatus` still
 * refuses, because the target status describes an EXECUTION outcome rather
 * than campaign configuration.
 *
 * VB-8J corrected the previous contract here. It was transcribed as
 * SYSTEM_DRIVEN_TRANSITIONS - "owned by the execution engine" - which was
 * never true: no engine path writes campaign status, and none should, because
 * several executions may run for one campaign and no single one could
 * authoritatively set it. They are reserved execution-facts with no producer.
 */
export const CAMPAIGN_RESERVED_STATUSES: readonly CampaignStatus[] = [
  "RUNNING",
  "COMPLETED",
  "FAILED",
];

/** Is this target a reserved execution-fact? Such an edge is always 409. */
export function isReservedTarget(to: CampaignStatus): boolean {
  return CAMPAIGN_RESERVED_STATUSES.includes(to);
}

/** Step 1 only: is the edge in the legal set at all? */
export function isLegalTransition(
  from: CampaignStatus,
  to: CampaignStatus,
): boolean {
  return (CAMPAIGN_LEGAL_TRANSITIONS[from] ?? []).includes(to);
}

/**
 * The edges a user may actually invoke: legal AND targeting a real campaign
 * configuration state.
 *
 * This is the function the UI must use. `CAMPAIGN_LEGAL_TRANSITIONS` alone
 * would offer controls that can only return 409.
 */
export function availableTransitions(
  from: CampaignStatus,
): readonly ManuallyReachableStatus[] {
  return (CAMPAIGN_LEGAL_TRANSITIONS[from] ?? []).filter(
    (to): to is ManuallyReachableStatus => !isReservedTarget(to),
  );
}

/** Is there any action at all? False for ARCHIVED, the only dead end. */
export function hasAvailableTransition(from: CampaignStatus): boolean {
  return availableTransitions(from).length > 0;
}

/**
 * F4.1 — a campaign is NEVER moved by the execution engine.
 *
 * ## What F4.1 re-verified, and what it corrected
 *
 * F4 described the three engine-owned edges as reserved for "the future
 * execution engine", which was true of the code F4 read: the engine did not
 * exist yet. F4.1 re-audited the final backend, where a real engine now exists,
 * and established something more specific and more useful.
 *
 * VERIFIED, exhaustively, in `src/main`:
 *
 *  - The only production writer of `CampaignEntity.status` is
 *    `CampaignService.changeStatus` (L293, `entity.setStatus(target)`), which
 *    is reachable **only** from `PATCH /api/v1/campaigns/{id}/status`.
 *  - The only other writer is `CampaignMapper.cloneOf` (L81), which sets
 *    `DRAFT`.
 *  - `IvrFromCampaignService` is the sole other caller of
 *    `campaignRepository.save`, and it writes `typeConfig`, not status.
 *  - There is **no** `@Modifying`/bulk `UPDATE campaigns SET status` anywhere,
 *    and `CampaignRepository` exposes no mutating query at all — only three
 *    `findBy…` methods.
 *  - `CampaignExecutionOrchestrator` and `OutboundDialService` inject
 *    `CampaignRepository` but only ever **read** it: for tenant-scoped
 *    existence, and for the `PAUSED` dispatch gate.
 *
 * So the three edges are reserved but **unperformed**: `SCHEDULED` campaigns
 * stay `SCHEDULED` indefinitely, and `RUNNING`/`COMPLETED`/`FAILED` are
 * reachable only by a manual API call that the service itself refuses. The
 * engine's real work is on the *execution* and *attempt* records, not the
 * campaign.
 *
 * ## Why this matters to a user
 *
 * `EXECUTABLE_STATUSES = {SCHEDULED, RUNNING}` and
 * `CampaignLifecyclePolicy.isEditable` is `DRAFT` only, and the two sets are
 * asserted disjoint by `editableAndExecutableAreDisjoint()`. So a SCHEDULED
 * campaign is executable *and* permanently uneditable, and no automatic
 * transition will ever change that. Returning it to `DRAFT` to edit it is a
 * deliberate manual act, and it also removes its executability.
 *
 * The frontend does not invent a way around this and must not: the honest
 * position is that campaign status is operator-driven, and execution status is
 * engine-driven.
 */
export const CAMPAIGN_STATUS_IS_OPERATOR_DRIVEN = true;

/**
 * VERIFIED `CampaignLifecyclePolicy.EDITABLE_STATUSES = Set.of(DRAFT)`.
 *
 * `PUT /api/v1/campaigns/{id}` calls `assertEditable`, which throws
 * `ConflictException` (409) for anything else. So the Edit control is only ever
 * correct in DRAFT — and the honest way to reach DRAFT from SCHEDULED or PAUSED
 * is the legal `-> DRAFT` edge, which is why `availableTransitions` exposes it.
 */
export function isEditable(status: CampaignStatus): boolean {
  return status === "DRAFT";
}

/**
 * VERIFIED `CampaignLifecyclePolicy.EXECUTABLE_STATUSES = {SCHEDULED, RUNNING}`.
 *
 * This is what `CampaignReadinessService.checkLifecycleState` tests, producing
 * `CAMPAIGN_NOT_EXECUTABLE_STATE`. A DRAFT campaign is therefore NEVER ready,
 * which is correct: activation is what moves it into a schedulable state.
 */
export function isExecutable(status: CampaignStatus): boolean {
  return status === "SCHEDULED" || status === "RUNNING";
}

/** The 409 text the service produces for an illegal edge. */
export function illegalTransitionMessage(
  from: CampaignStatus,
  to: CampaignStatus,
): string {
  return `Illegal campaign lifecycle transition: ${from} -> ${to}.`;
}

/** The 409 text `assertEditable` produces for a non-DRAFT campaign. */
export function notEditableMessage(status: CampaignStatus): string {
  return `Campaign configuration can only be modified while the campaign is in DRAFT state (current: ${status}).`;
}

/**
 * Human labels, so a badge and a dialog can never disagree about a state.
 *
 * The backend ships enum constants, not labels, so these are presentation
 * strings and are deliberately kept in one place rather than inline in a badge
 * and a select.
 */
export const CAMPAIGN_STATUS_LABEL: Readonly<Record<CampaignStatus, string>> = {
  DRAFT: "Draft",
  SCHEDULED: "Scheduled",
  RUNNING: "Running",
  PAUSED: "Paused",
  COMPLETED: "Completed",
  FAILED: "Failed",
  ARCHIVED: "Archived",
};

/** One-line explanation of what a state means operationally. */
export const CAMPAIGN_STATUS_DESCRIPTION: Readonly<
  Record<CampaignStatus, string>
> = {
  DRAFT: "Editable. Not yet scheduled, and never executable in this state.",
  SCHEDULED:
    "Scheduled and eligible to run. Configuration is locked — return it to draft to make changes, which also stops it being executable. Nothing moves it forward automatically.",
  RUNNING: "Marked as running. Configuration is locked.",
  PAUSED:
    "Temporarily stopped. Pausing really does hold new calls: the engine re-queues queued attempts instead of dialling them. Configuration is locked, and this state cannot return to draft.",
  COMPLETED: "Marked as finished. Only archiving remains.",
  FAILED: "Marked as failed. Only archiving remains.",
  ARCHIVED: "Final. No further transitions exist.",
};

/**
 * F4.1 — true when the campaign is in a state the execution engine will accept.
 *
 * VERIFIED `CampaignLifecyclePolicy.isExecutable` /
 * `EXECUTABLE_STATUSES = {SCHEDULED, RUNNING}`, and this is exactly the check
 * `CampaignReadinessService.checkLifecycleState` makes, so it is the same
 * predicate the engine's start gate effectively relies on.
 */
export function isInExecutableState(status: CampaignStatus): boolean {
  return isExecutable(status);
}

/**
 * The union of every status a user can actually move a campaign INTO.
 *
 * It excludes RUNNING, COMPLETED and FAILED because those are reserved
 * execution-facts, so `availableTransitions` can never return them. Typing the
 * consequence map over this union rather than over all seven statuses means a
 * new reserved state cannot be added here without also being reachable, and a
 * state that IS reachable cannot lack its explanation.
 */
export type ManuallyReachableStatus = "SCHEDULED" | "PAUSED" | "DRAFT" | "ARCHIVED";

/**
 * Plain-language consequence of each transition the user can invoke.
 *
 * The DRAFT -> SCHEDULED edge is the only one that runs `validateActivation`,
 * so it is the only one that can fail for reasons unrelated to the edge itself.
 * The UI must say so BEFORE the user clicks, because the failure names a
 * missing schedule, an unapproved asset or an inactive queue rather than
 * anything about the transition.
 */
export const CAMPAIGN_TRANSITION_CONSEQUENCE: Readonly<
  Record<ManuallyReachableStatus, string>
> = {
  SCHEDULED:
    "Activates the campaign. The server re-checks the whole configuration first: a schedule with a timezone, a contact group that still exists, a DID that is active and assigned, content that is approved, and a valid type configuration. If any of those fail, activation is refused.",
  PAUSED:
    "Pauses the campaign. Pausing does not run the activation checks, so a campaign that became unready while running stays paused rather than being rejected.",
  DRAFT:
    "Returns the campaign to draft, which re-enables editing. No configuration check runs.",
  ARCHIVED: "Archives the campaign. This is final — an archived campaign has no outgoing transitions.",
};
