"use client";

import { AlertTriangleIcon, CheckCircleIcon, RefreshCwIcon, XCircleIcon } from "lucide-react";

import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { QueryErrorState } from "@/components/common/query-state";
import type { CampaignReadinessReason } from "@/lib/api/contracts";

/**
 * The readiness panel: whether a campaign can execute right now, and why not.
 *
 * ## Readiness is the only honest answer to "can this campaign run?"
 *
 * VERIFIED `CampaignService.changeStatus` and `CampaignExecutionService.execute`
 * both call `CampaignReadinessService` (directly, and indirectly through
 * `execute`), so nothing can be scheduled or executed that readiness would
 * refuse. `POST /executions` raises
 * `CommonErrorCode.BUSINESS_RULE_VIOLATION` — a **422**, not a 400 and not a
 * 409 — listing every blocking reason. That is a distinct status from
 * everything else in this domain and the UI must not fold it into "conflict".
 *
 * `POST /status` does NOT call readiness: it calls `validateActivation`, which
 * re-checks configuration but not the time-dependent schedule window. So a
 * campaign can be SCHEDULED and still be unready, and the two are different
 * questions. This panel answers the readiness one and the status dialog
 * answers the other.
 *
 * ## The reason codes are a closed vocabulary
 *
 * VERIFIED: every code is a string literal in `CampaignReadinessService`, and
 * they are the only codes that endpoint can return. The F1 UI dumped
 * `code: message` into a raw list with no grouping, so a user could not tell a
 * fixable configuration problem from a purely time-based one — which is the
 * single most important distinction here, because `SCHEDULE_NOT_ELIGIBLE`
 * resolves itself and `AUDIO_NOT_APPROVED` never will.
 *
 * The mapping below groups them accordingly, and an unrecognised code falls
 * through to its own message rather than being dropped.
 */

/** Every code `CampaignReadinessService` can emit. */
export const READINESS_REASON_CODES = [
  "CAMPAIGN_NOT_EXECUTABLE_STATE",
  "SCHEDULE_NOT_ELIGIBLE",
  "SCHEDULE_TIMEZONE_REQUIRED",
  "INVALID_SCHEDULE",
  "INVALID_CONTENT_CONFIGURATION",
  "MISSING_REQUIRED_REFERENCE",
  "AUDIO_NOT_APPROVED",
  "AUDIO_STORAGE_REFERENCE_MISSING",
  "TTS_TEMPLATE_NOT_APPROVED",
  "TTS_TEMPLATE_NOT_AVAILABLE",
  "CONTACT_GROUP_UNAVAILABLE",
  "DID_UNAVAILABLE",
  "AGENT_QUEUE_NOT_AVAILABLE",
  "AGENT_QUEUE_NOT_ACTIVE",
  "INVALID_MISSED_CALL_CONFIGURATION",
  "INVALID_AGENT_CONFIGURATION",
  "INVALID_INTEGRATION_CONFIGURATION",
] as const;

export type ReadinessReasonCode = (typeof READINESS_REASON_CODES)[number];

/**
 * Why a code blocks execution, in terms the operator can act on.
 *
 * `RESOLVES_ITSELF` is the important distinction: a schedule-window or
 * lifecycle-state reason is temporary and no configuration change is needed,
 * whereas an approval or availability reason needs someone to do something.
 */
const REASON_GUIDANCE: Record<
  ReadinessReasonCode,
  { readonly category: "temporary" | "action" | "configuration"; readonly guidance: string }
> = {
  CAMPAIGN_NOT_EXECUTABLE_STATE: {
    category: "temporary",
    guidance:
      "Only a scheduled or running campaign can execute. Move it to the scheduled state first.",
  },
  SCHEDULE_NOT_ELIGIBLE: {
    category: "temporary",
    guidance:
      "The current date or time falls outside the campaign's calling window. This resolves itself — no change is needed.",
  },
  SCHEDULE_TIMEZONE_REQUIRED: {
    category: "action",
    guidance:
      "No schedule timezone is set, and the server applies no fallback zone for the daily dial limit. Set one in the schedule.",
  },
  INVALID_SCHEDULE: {
    category: "configuration",
    guidance:
      "The schedule is internally inconsistent: dates or times are out of order, or the timezone is not a valid IANA identifier.",
  },
  INVALID_CONTENT_CONFIGURATION: {
    category: "configuration",
    guidance:
      "The content selection is not coherent with the campaign type — for example a reference without a content mode, or both an audio asset and a template.",
  },
  MISSING_REQUIRED_REFERENCE: {
    category: "configuration",
    guidance:
      "This campaign type plays media and must have an audio recording selected.",
  },
  AUDIO_NOT_APPROVED: {
    category: "action",
    guidance:
      "The audio recording is missing, soft-deleted, belongs to another tenant, or is not approved. Approve it, or choose a different recording.",
  },
  AUDIO_STORAGE_REFERENCE_MISSING: {
    category: "action",
    guidance:
      "The recording is approved but has no stored audio file, so there is nothing to play. Upload the file to the asset.",
  },
  TTS_TEMPLATE_NOT_APPROVED: {
    category: "action",
    guidance: "The TTS template is not approved.",
  },
  TTS_TEMPLATE_NOT_AVAILABLE: {
    category: "action",
    guidance:
      "The TTS template does not exist, is deleted, or is not visible to this campaign's tenant.",
  },
  CONTACT_GROUP_UNAVAILABLE: {
    category: "action",
    guidance:
      "The contact group does not exist, has been deleted, or belongs to another tenant. Pick a group from this tenant.",
  },
  DID_UNAVAILABLE: {
    category: "action",
    guidance:
      "The number is missing, deleted, assigned to another tenant, not active, or not allocated. Choose an active, assigned number.",
  },
  AGENT_QUEUE_NOT_AVAILABLE: {
    category: "action",
    guidance:
      "The agent queue does not exist, has been deleted, or belongs to another tenant. Choose a queue from this tenant.",
  },
  AGENT_QUEUE_NOT_ACTIVE: {
    category: "action",
    guidance:
      "The agent queue exists but is inactive or disabled, so the campaign cannot be scheduled. Activate the queue.",
  },
  INVALID_MISSED_CALL_CONFIGURATION: {
    category: "configuration",
    guidance:
      "The missed-call ring duration is missing or outside the 10–60 second range.",
  },
  INVALID_AGENT_CONFIGURATION: {
    category: "configuration",
    guidance:
      "The agent configuration is incomplete: a queue, a selection strategy and a ring duration between 10 and 240 seconds are all required.",
  },
  INVALID_INTEGRATION_CONFIGURATION: {
    category: "configuration",
    guidance:
      "The stored integration configuration is not readable — for example a webhook with no endpoint, or an unrecognised field.",
  },
};

export function isReadinessReasonCode(
  code: string,
): code is ReadinessReasonCode {
  return (READINESS_REASON_CODES as readonly string[]).includes(code);
}

interface ReadinessPanelProps {
  readiness: { ready: boolean; reasons: CampaignReadinessReason[] } | undefined;
  isPending: boolean;
  error: unknown;
  onRefresh: () => void;
  isFetching: boolean;
}

export function CampaignReadinessPanel({
  readiness,
  isPending,
  error,
  onRefresh,
  isFetching,
}: ReadinessPanelProps) {
  const temporary = (readiness?.reasons ?? []).filter(
    (reason) =>
      isReadinessReasonCode(reason.code) &&
      REASON_GUIDANCE[reason.code].category === "temporary",
  );
  const actionable = (readiness?.reasons ?? []).filter(
    (reason) => !temporary.includes(reason),
  );

  return (
    <Card aria-live="polite">
      <CardHeader className="flex flex-row items-center justify-between">
        <div>
          <CardTitle className="flex items-center gap-2">
            {isPending ? (
              <RefreshCwIcon className="text-muted-foreground h-4 w-4 animate-spin" />
            ) : readiness?.ready ? (
              <CheckCircleIcon className="h-4 w-4 text-emerald-600" />
            ) : (
              <XCircleIcon className="h-4 w-4 text-amber-600" />
            )}
            Execution readiness
          </CardTitle>
          <CardDescription>
            {isPending
              ? "Checking…"
              : readiness?.ready
                ? "This campaign can be executed right now."
                : "The server refuses to execute this campaign while any reason below applies."}
          </CardDescription>
        </div>
        <Button
          variant="ghost"
          size="sm"
          onClick={onRefresh}
          disabled={isFetching}
          aria-label="Re-check readiness"
        >
          <RefreshCwIcon className={isFetching ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
        </Button>
      </CardHeader>
      <CardContent>
        {error ? (
          <QueryErrorState error={error} entityLabel="this campaign" onRetry={onRefresh} />
        ) : isPending ? (
          <p className="text-muted-foreground text-sm">Loading readiness…</p>
        ) : readiness?.ready ? (
          <p className="text-sm">
            Every reference resolves, the schedule is eligible, and the lifecycle
            state permits execution.
          </p>
        ) : (
          <div className="flex flex-col gap-4">
            {temporary.length > 0 ? (
              <ReasonGroup
                title="Waiting on time or state"
                tone="temporary"
                reasons={temporary}
              />
            ) : null}
            {actionable.length > 0 ? (
              <ReasonGroup title="Needs a change" tone="action" reasons={actionable} />
            ) : null}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function ReasonGroup({
  title,
  tone,
  reasons,
}: {
  title: string;
  tone: "temporary" | "action";
  reasons: readonly CampaignReadinessReason[];
}) {
  return (
    <div className="flex flex-col gap-2">
      <p className="flex items-center gap-1.5 text-sm font-medium">
        {tone === "temporary" ? (
          <RefreshCwIcon aria-hidden="true" className="text-muted-foreground h-4 w-4" />
        ) : (
          <AlertTriangleIcon aria-hidden="true" className="h-4 w-4 text-amber-600" />
        )}
        {title}
      </p>
      <ul className="ml-1 flex list-disc flex-col gap-2 pl-4">
        {reasons.map((reason, index) => (
          <li key={`${reason.code}-${index}`} className="text-sm">
            <span className="text-muted-foreground font-mono text-xs">{reason.code}</span>
            <span className="block">{reason.message}</span>
            {isReadinessReasonCode(reason.code) ? (
              <span className="text-muted-foreground block">
                {REASON_GUIDANCE[reason.code].guidance}
              </span>
            ) : null}
          </li>
        ))}
      </ul>
    </div>
  );
}
