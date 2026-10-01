import { Badge } from "@/components/ui/badge";
import type { CampaignExecutionStatus } from "@/lib/api/contracts";
import { TERMINAL_EXECUTION_STATUSES } from "@/lib/domain/campaign-execution";

/**
 * `CampaignExecutionStatus` badge.
 *
 * ## Why this exists
 *
 * The F1 detail view and the execution detail view each declared their own
 * private `Record<string, string>` of Tailwind class strings for the same five
 * statuses. F3 already found and fixed exactly this duplication for audio and
 * TTS by introducing `approval-status-badge.tsx`; campaigns were left with two
 * copies, so a status could render amber in one place and grey in another.
 *
 * ## The five statuses, and what they mean
 *
 * VERIFIED `CampaignExecutionStatus`: REQUESTED, RUNNING, COMPLETED, FAILED,
 * CANCELLED, plus a `TERMINAL` set used by the execution layer.
 *
 * ### F5 CORRECTED THIS — all five are reachable, not just REQUESTED
 *
 * The previous doc said: "Only REQUESTED is reachable by a user today. VERIFIED:
 * nothing in the REST surface advances an execution — the service writes
 * REQUESTED and returns, and the controller says 'Actual execution is performed
 * by the future execution engine'. The remaining four are here because the wire
 * type includes them and the backend can set them from its own internals, not
 * because this UI can."
 *
 * That was true of the F1-era backend and is now false in a way that matters: it
 * implied the user would only ever see "Requested", which is precisely the
 * misunderstanding F4.1 was filed to correct. VERIFIED against the backend as it
 * stands, `CampaignExecutionOrchestrator` is a `@Service` with
 * `@Scheduled(fixedDelay = 30000) scheduledTick()` and it writes
 * `REQUESTED -> RUNNING` (with `startedAt`) and `RUNNING -> COMPLETED | FAILED`
 * (with `completedAt`). So a user watches an execution reach RUNNING and then
 * settle, and these four badges are load-bearing.
 *
 * `CANCELLED` is the one genuine exception, and it is an engine fact rather than
 * a UI choice: `CampaignExecutionStatus.CANCELLED` has **zero** producers in
 * `src/main` — no writer, and no REST operation that cancels an execution (only
 * a single *call attempt* has a cancel endpoint). It is part of the enum and the
 * wire type, so it must render, but this UI must never offer a control that
 * produces it. See the F5 contract audit.
 *
 * The important distinction this module must not blur: these are EXECUTION
 * statuses. They are never derived from, and never imply, a campaign status.
 */
const EXECUTION_STATUS_STYLE: Record<CampaignExecutionStatus, string> = {
  REQUESTED: "bg-amber-500/10 text-amber-700 border-amber-500/30",
  RUNNING: "bg-blue-500/10 text-blue-700 border-blue-700/30",
  COMPLETED: "bg-emerald-500/10 text-emerald-700 border-emerald-500/30",
  FAILED: "bg-red-500/10 text-red-700 border-red-500/30",
  CANCELLED: "bg-gray-500/10 text-gray-700 border-gray-500/30",
};

export const CAMPAIGN_EXECUTION_STATUS_LABEL: Record<
  CampaignExecutionStatus,
  string
> = {
  REQUESTED: "Requested",
  RUNNING: "Running",
  COMPLETED: "Completed",
  FAILED: "Failed",
  CANCELLED: "Cancelled",
};

/**
 * True when the execution can no longer change.
 *
 * F5: this used to restate the set inline, which was a fourth copy of the
 * backend's `TERMINAL` after `lib/domain/campaign-execution.ts` was introduced
 * as the single home for that contract. It now delegates, so the badge and the
 * poll guard cannot disagree about what "finished" means.
 */
export function isTerminalExecutionStatus(
  status: CampaignExecutionStatus,
): boolean {
  return TERMINAL_EXECUTION_STATUSES.includes(status);
}

export function CampaignExecutionStatusBadge({
  status,
}: {
  status: CampaignExecutionStatus;
}) {
  return (
    <Badge variant="outline" className={EXECUTION_STATUS_STYLE[status]}>
      {CAMPAIGN_EXECUTION_STATUS_LABEL[status]}
    </Badge>
  );
}
