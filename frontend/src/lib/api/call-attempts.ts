import { api } from "@/lib/api/client";
import type {
  CallAttemptResponse,
  CreateCallAttemptPayload,
  CallAttemptStatus,
} from "@/lib/api/contracts";
import { unwrap } from "@/lib/api/auth";
import type { ApiResponse } from "@/lib/api/types";

/**
 * /api/v1/campaigns/{campaignId}/executions/{executionId}/attempts contract
 * (verified against CallAttemptController/CallAttemptService):
 * - list: GET /campaigns/{campaignId}/executions/{executionId}/attempts
 *   - ordered by scheduledAt ASC (no pagination)
 * - filters: none
 * - auth: CAMPAIGN_EXECUTE
 * - GET /{campaignId}/executions/{executionId}/attempts/{attemptId}
 *   - auth: CAMPAIGN_EXECUTE
 * - POST /campaigns/{campaignId}/executions/{executionId}/attempts
 *   - auth: CAMPAIGN_MANAGE (create)
 * - PATCH /.../attempts/{id}/in-progress (QUEUED -> IN_PROGRESS)
 * - PATCH /.../attempts/{id}/completed (IN_PROGRESS -> COMPLETED)
 * - PATCH /.../attempts/{id}/failed (IN_PROGRESS|QUEUED -> FAILED)
 * - PATCH /.../attempts/{id}/cancel (QUEUED|IN_PROGRESS -> CANCELLED)
 * - auth: CAMPAIGN_EXECUTE
 */

export const CALL_ATTEMPT_SORTABLE_FIELDS = [
  "scheduledAt",
  "attemptNumber",
  "status",
  "createdAt",
  "startedAt",
  "completedAt",
] as const;

export type CallAttemptSortField = (typeof CALL_ATTEMPT_SORTABLE_FIELDS)[number];

export interface CallAttemptListParams {
  executionId: string;
  campaignId: string;
  // no pagination in backend - all attempts returned
  sortField?: CallAttemptSortField;
  sortDirection?: "asc" | "desc";
  status?: CallAttemptStatus;
}

export const callAttemptsKeys = {
  all: ["call-attempts"] as const,
  list: (params: CallAttemptListParams) => ["call-attempts", "list", params] as const,
  detail: (attemptId: string) => ["call-attempts", "detail", attemptId] as const,
  byExecution: (executionId: string, campaignId: string) =>
    ["call-attempts", "by-execution", executionId, campaignId] as const,
};

export interface CallAttemptPage {
  items: CallAttemptResponse[];
  // no pagination from backend
  pagination: {
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    hasNext: boolean;
    hasPrevious: boolean;
  };
}

function buildSort(params: Pick<CallAttemptListParams, "sortField" | "sortDirection">): string {
  const field = params.sortField ?? "scheduledAt";
  const dir = params.sortDirection ?? "asc";
  return `${field},${dir}`;
}

export async function getCallAttempts(params: CallAttemptListParams): Promise<{
  items: CallAttemptResponse[];
  pagination: {
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    hasNext: boolean;
    hasPrevious: boolean;
  };
}> {
  const { campaignId, executionId, ...restParams } = params;
  const { data } = await api.get<ApiResponse<CallAttemptResponse[]>>(
    `/campaigns/${campaignId}/executions/${executionId}/attempts`,
    {
      params: {
        sort: buildSort(restParams),
        status: restParams.status || undefined,
      },
    },
  );
  return {
    items: data.data ?? [],
    pagination: {
      page: 0,
      size: data.data?.length ?? 0,
      totalElements: data.data?.length ?? 0,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    },
  };
}

export function getCallAttempt(campaignId: string, executionId: string, attemptId: string): Promise<CallAttemptResponse> {
  return unwrap(
    api.get<ApiResponse<CallAttemptResponse>>(
      `/campaigns/${campaignId}/executions/${executionId}/attempts/${attemptId}`
    ),
  );
}

export async function createCallAttempt(
  campaignId: string,
  executionId: string,
  payload: CreateCallAttemptPayload
): Promise<CallAttemptResponse> {
  const { data } = await api.post<ApiResponse<CallAttemptResponse>>(
    `/campaigns/${campaignId}/executions/${executionId}/attempts`,
    payload
  );
  return data.data as CallAttemptResponse;
}

export async function updateCallAttempt(
  campaignId: string,
  executionId: string,
  attemptId: string,
  endpoint: "in-progress" | "completed" | "failed" | "cancel",
  payload?: Record<string, unknown>
): Promise<CallAttemptResponse> {
  const { data } = await api.patch<ApiResponse<CallAttemptResponse>>(
    `/campaigns/${campaignId}/executions/${executionId}/attempts/${attemptId}/${endpoint}`,
    payload ?? {}
  );
  return data.data as CallAttemptResponse;
}

export function markAttemptInProgress(campaignId: string, executionId: string, attemptId: string): Promise<CallAttemptResponse> {
  return updateCallAttempt(campaignId, executionId, attemptId, "in-progress", {});
}

export function markAttemptCompleted(campaignId: string, executionId: string, attemptId: string): Promise<CallAttemptResponse> {
  return updateCallAttempt(campaignId, executionId, attemptId, "completed", {});
}

export async function markAttemptFailed(
  campaignId: string,
  executionId: string,
  attemptId: string,
  failureCode?: string | null,
  failureReason?: string | null
): Promise<CallAttemptResponse> {
  return updateCallAttempt(campaignId, executionId, attemptId, "failed", {
    failureCode,
    failureReason,
  });
}

export function cancelCallAttempt(campaignId: string, executionId: string, attemptId: string): Promise<CallAttemptResponse> {
  return updateCallAttempt(campaignId, executionId, attemptId, "cancel", {});
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