import { api } from "@/lib/api/client";
import type {
  CallAttemptResponse,
  CreateCallAttemptPayload,
  CallAttemptStatus,
  MarkAttemptFailedParams,
} from "@/lib/api/contracts";
import { unwrap } from "@/lib/api/transport";
import type { ApiResponse } from "@/lib/api/types";

/**
 * /api/v1/campaigns/{campaignId}/executions/{executionId}/attempts
 *
 * VERIFIED against CampaignController (all endpoints live in that controller;
 * there is no separate CallAttemptController) and CallAttemptService:
 * - list: GET .../attempts — takes NO request parameters whatsoever, returns
 *   `ResponseFactory.ok(list)`, so there is NO `pagination` block, and is
 *   ordered by scheduled time oldest-first.
 * - POST .../attempts           → 201; capability CAMPAIGN_EXECUTE
 * - PATCH .../{id}/in-progress  QUEUED -> IN_PROGRESS
 * - PATCH .../{id}/completed    IN_PROGRESS -> COMPLETED
 * - PATCH .../{id}/failed       IN_PROGRESS|QUEUED -> FAILED; takes
 *                               failureCode/failureReason as QUERY PARAMS
 * - PATCH .../{id}/cancel       QUEUED|IN_PROGRESS -> CANCELLED
 * An illegal transition is 409, not 400.
 *
 * F1 corrections applied here:
 *  1. the list no longer sends the `sort`/`status` query params the endpoint
 *     does not declare, and no longer fabricates a single-page
 *     `PaginationMetadata` for a response that carries none;
 *  2. `markAttemptFailed` sends its diagnostics as query params, because the
 *     backend declares no `@RequestBody` on that method and was silently
 *     discarding the JSON body the frontend used to send.
 */

/** F1: REMOVED. The endpoint declares no sorting, so a "sortable fields"
 * whitelist advertised a capability that does not exist. F10 replaces sorting
 * with a genuine client-side sort if one is ever wanted. */

export interface CallAttemptListParams {
  campaignId: string;
  executionId: string;
}

export const callAttemptsKeys = {
  all: ["call-attempts"] as const,
  byExecution: (campaignId: string, executionId: string) =>
    ["call-attempts", "by-execution", campaignId, executionId] as const,
};

/** A complete, unpaginated attempt list. The backend sends no pagination
 * metadata for this endpoint, so this type deliberately has none. */
export interface CallAttemptListResult {
  items: CallAttemptResponse[];
}


/** F1: the previous shape declared a `pagination` block the backend never
 * sends, and `getCallAttempts` filled it in locally. Replaced by
 * `CallAttemptListResult`, which is honestly a bare array. */

export function getCallAttempts(
  params: CallAttemptListParams,
): Promise<CallAttemptListResult> {
  const { campaignId, executionId } = params;
  // No params object: CampaignController.listAttempts (L309-313) declares
  // neither @RequestParam nor Pageable. Sending sort/status was a no-op.
  return unwrap(
    api.get<ApiResponse<CallAttemptResponse[]>>(
      `/campaigns/${campaignId}/executions/${executionId}/attempts`,
    ),
  ).then((items) => ({ items }));
}

export function getCallAttempt(campaignId: string, executionId: string, attemptId: string): Promise<CallAttemptResponse> {
  return unwrap(
    api.get<ApiResponse<CallAttemptResponse>>(
      `/campaigns/${campaignId}/executions/${executionId}/attempts/${attemptId}`
    ),
  );
}

export function createCallAttempt(
  campaignId: string,
  executionId: string,
  payload: CreateCallAttemptPayload
): Promise<CallAttemptResponse> {
  return unwrap(
    api.post<ApiResponse<CallAttemptResponse>>(
      `/campaigns/${campaignId}/executions/${executionId}/attempts`,
      payload,
    ),
  );
}

/** Advances an attempt through its lifecycle.
 *
 * Only the `failed` transition carries data, and it is carried in the QUERY
 * STRING: CampaignController.markFailed (L372-380) declares
 * `@RequestParam failureCode` / `failureReason` and no `@RequestBody`. Every
 * other transition takes no input at all. */
export function transitionCallAttempt(
  campaignId: string,
  executionId: string,
  attemptId: string,
  transition: "in-progress" | "completed" | "cancel",
): Promise<CallAttemptResponse> {
  return unwrap(
    api.patch<ApiResponse<CallAttemptResponse>>(
      `/campaigns/${campaignId}/executions/${executionId}/attempts/${attemptId}/${transition}`,
      null,
    ),
  );
}

export function markAttemptFailed(
  campaignId: string,
  executionId: string,
  attemptId: string,
  params?: MarkAttemptFailedParams,
): Promise<CallAttemptResponse> {
  return unwrap(
    api.patch<ApiResponse<CallAttemptResponse>>(
      `/campaigns/${campaignId}/executions/${executionId}/attempts/${attemptId}/failed`,
      null,
      {
        params: {
          failureCode: params?.failureCode || undefined,
          failureReason: params?.failureReason || undefined,
        },
      },
    ),
  );
}

export function markAttemptInProgress(campaignId: string, executionId: string, attemptId: string): Promise<CallAttemptResponse> {
  return transitionCallAttempt(campaignId, executionId, attemptId, "in-progress");
}

export function markAttemptCompleted(campaignId: string, executionId: string, attemptId: string): Promise<CallAttemptResponse> {
  return transitionCallAttempt(campaignId, executionId, attemptId, "completed");
}

export function cancelCallAttempt(campaignId: string, executionId: string, attemptId: string): Promise<CallAttemptResponse> {
  return transitionCallAttempt(campaignId, executionId, attemptId, "cancel");
}

/** Legal lifecycle transitions (client-side mirror of backend transitions). */
export const CALL_ATTEMPT_LEGAL_TRANSITIONS: Record<CallAttemptStatus, CallAttemptStatus[]> = {
  QUEUED: ["IN_PROGRESS", "FAILED", "CANCELLED"],
  IN_PROGRESS: ["COMPLETED", "FAILED", "CANCELLED"],
  COMPLETED: [],
  FAILED: [],
  CANCELLED: [],
};

/** System-driven transitions that cannot be invoked manually. */
export const CALL_ATTEMPT_SYSTEM_TRANSITIONS: Array<{ from: string; to: string }> = [
  { from: "QUEUED", to: "IN_PROGRESS" },
  { from: "IN_PROGRESS", to: "COMPLETED" },
  { from: "IN_PROGRESS", to: "FAILED" },
];