/**
 * The three-state approval lifecycle shared by Audio Assets and TTS Templates.
 *
 * ## Why this module exists
 *
 * Two independent services implement the same state machine, and the UI has to
 * decide whether to render Approve and Reject on every row and on every detail
 * page. Getting that decision from a source other than the server is how a UI
 * ends up hiding a transition the backend permits, or offering one that can only
 * return 409.
 *
 * ## What the backend actually does
 *
 * VERIFIED — `AudioAssetService.transition` (L265-277) and
 * `TtsTemplateService.transition` (L218-231) are structurally identical:
 *
 * ```java
 * if (entity.getStatus() == target) {
 *     throw new ConflictException("... is already " + target + ".");
 * }
 * entity.setStatus(target);
 * ```
 *
 * That is the whole rule. It means:
 *
 *  - **ANY state may transition to ANY other state.** PENDING → APPROVED,
 *    PENDING → REJECTED, REJECTED → APPROVED (re-submission after review), and
 *    APPROVED → REJECTED (withdrawal) are all valid.
 *  - **Only a no-op transition is a 409.**
 *  - There is **no** state from which a transition is forbidden, and **no**
 *    transition that requires a different capability.
 *
 * The F1/F2-era UI showed Approve/Reject only when the status was
 * `PENDING_APPROVAL`, which hid three of the four legal transitions — most
 * visibly, a REJECTED template could never be re-approved through the UI even
 * though the backend allows it. Restricting a client to a subset of what the
 * server permits is a frontend decision, and here it was a wrong one.
 *
 * ## Scope note
 *
 * This is a pure lifecycle model, not authorization. Whether the caller may
 * perform a given transition is a separate question, answered by
 * `lib/auth/content-gates.ts` against the capability the service actually
 * checks.
 */
export type ApprovalStatus = "PENDING_APPROVAL" | "APPROVED" | "REJECTED";

export const APPROVAL_STATUSES: readonly ApprovalStatus[] = [
  "PENDING_APPROVAL",
  "APPROVED",
  "REJECTED",
];

/** The two transitions the API exposes. There are no others. */
export type ApprovalTransition = "approve" | "reject";

/** The status a transition leads to. */
export const TRANSITION_TARGET: Record<
  ApprovalTransition,
  ApprovalStatus
> = {
  approve: "APPROVED",
  reject: "REJECTED",
};

/**
 * Would this transition be a no-op? A no-op is the ONLY case the backend
 * refuses, and it is refused with 409.
 */
export function isNoopTransition(
  from: ApprovalStatus,
  transition: ApprovalTransition,
): boolean {
  return from === TRANSITION_TARGET[transition];
}

/**
 * The 409 message the service produces, reproduced for the case the UI would
 * otherwise be able to trigger by racing two clicks. The backend's own text is
 * `"Audio asset is already " + target + "."` or
 * `"TTS template is already " + target + "."`.
 */
export function noopConflictMessage(
  subject: "Audio asset" | "TTS template",
  from: ApprovalStatus,
  transition: ApprovalTransition,
): string {
  return `${subject} is already ${TRANSITION_TARGET[transition]}.`;
}

/**
 * True when the transition would change the status. The UI uses this to decide
 * whether to render a control at all, so a row can never offer an action that
 * can only 409.
 *
 * Note this is a UI convenience, not a security or correctness boundary: the
 * status can change between the render and the click, which is exactly why the
 * mutation layer still handles 409 rather than assuming it cannot happen.
 */
export function canTransition(
  from: ApprovalStatus,
  transition: ApprovalTransition,
): boolean {
  return !isNoopTransition(from, transition);
}

/** Both transitions the UI should offer for a given current status. */
export function availableTransitions(
  from: ApprovalStatus,
): ApprovalTransition[] {
  return (["approve", "reject"] as const).filter((t) => canTransition(from, t));
}

/** Human label, so the two status badges can never disagree. */
export const APPROVAL_STATUS_LABEL: Record<ApprovalStatus, string> = {
  PENDING_APPROVAL: "Pending approval",
  APPROVED: "Approved",
  REJECTED: "Rejected",
};

/**
 * Plain-language consequence of approving, per domain.
 *
 * VERIFIED: campaigns may only reference APPROVED assets
 * (`AudioAssetStatus` Javadoc) and approved TTS templates
 * (`TtsTemplateService` L206-208). Existing references to a REJECTED item fail
 * activation, not immediately — the delete Javadocs say the same for soft delete.
 */
export const APPROVAL_CONSEQUENCE = {
  audio: {
    approve:
      "Once approved, campaigns in your organization can use this recording. A rejected or deleted recording cannot be used, and campaigns that still reference it will fail to start.",
    reject:
      "Rejecting removes this recording from the pool of recordings campaigns can use. Campaigns that still reference it will fail to start.",
  },
  tts: {
    approve:
      "Once approved, campaigns in your organization can use this template. Every tenant can also read it, because approved templates form the shared catalog.",
    reject:
      "Rejecting removes this template from the shared catalog. Campaigns that still reference it will fail to start.",
  },
} as const;
