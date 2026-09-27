import { api } from "@/lib/api/client";
import type {
  CampaignResponse,
  CreateCampaignPayload,
  UpdateCampaignPayload,
  UpdateCampaignStatusPayload,
  ExecuteCampaignPayload,
  CampaignReadinessResponse,
  CampaignExecutionResponse,
  CampaignStatus,
  CampaignType,
  CampaignRunMode,
  ContactGroupResponse,
  AudioAssetResponse,
  TtsTemplateResponse,
  DidResponse,
} from "@/lib/api/contracts";
import { unwrap } from "@/lib/api/auth";
import type { ApiResponse, PaginationMetadata } from "@/lib/api/types";

/**
 * /api/v1/campaigns contract (verified against CampaignController/CampaignService):
 * - page zero-based; size server-clamped 1..100
 * - sort whitelist: name, createdAt, updatedAt, status, campaignType; default createdAt,desc
 * - status: CampaignStatus name; unknown → 400
 * - campaignType: CampaignType name; unknown → 400
 * - runMode: CampaignRunMode name; unknown → 400
 * - search: case-insensitive contains over name and description
 * - DELETE is a soft delete (deletedAt stamp) returning bare 204
 */
export const CAMPAIGN_SORTABLE_FIELDS = [
  "name",
  "createdAt",
  "updatedAt",
  "status",
  "campaignType",
] as const;

export type CampaignSortField = (typeof CAMPAIGN_SORTABLE_FIELDS)[number];

export interface CampaignListParams {
  page: number;
  size: number;
  sortField: CampaignSortField;
  sortDirection: "asc" | "desc";
  status?: CampaignStatus;
  campaignType?: CampaignType;
  runMode?: CampaignRunMode;
  search?: string;
}

/** Stable query-key factory for the Campaigns module. */
export const campaignsKeys = {
  all: ["campaigns"] as const,
  list: (params: CampaignListParams) => ["campaigns", "list", params] as const,
  detail: (campaignId: string) => ["campaigns", "detail", campaignId] as const,
  readiness: (campaignId: string) => ["campaigns", "readiness", campaignId] as const,
  executions: (campaignId: string) => ["campaigns", "executions", campaignId] as const,
  execution: (campaignId: string, executionId: string) => ["campaigns", "executions", campaignId, executionId] as const,
};

export interface CampaignPage {
  items: CampaignResponse[];
  pagination: PaginationMetadata;
}

function buildSort(params: CampaignListParams): string {
  return `${params.sortField},${params.sortDirection}`;
}

export async function getCampaigns(params: CampaignListParams): Promise<CampaignPage> {
  const { data } = await api.get<ApiResponse<CampaignResponse[]>>("/campaigns", {
    params: {
      page: params.page,
      size: params.size,
      sort: buildSort(params),
      status: params.status || undefined,
      campaignType: params.campaignType || undefined,
      runMode: params.runMode || undefined,
      search: params.search || undefined,
    },
  });
  return {
    items: data.data ?? [],
    pagination:
      data.pagination ??
      {
        page: params.page,
        size: params.size,
        totalElements: 0,
        totalPages: 0,
        hasNext: false,
        hasPrevious: false,
      },
  };
}

export function getCampaign(campaignId: string): Promise<CampaignResponse> {
  return unwrap(api.get<ApiResponse<CampaignResponse>>(`/campaigns/${campaignId}`));
}

export function createCampaign(
  payload: CreateCampaignPayload,
  tenantId?: string,
): Promise<CampaignResponse> {
  const params = tenantId ? { tenantId } : undefined;
  return unwrap(api.post<ApiResponse<CampaignResponse>>("/campaigns", payload, { params }));
}

export function updateCampaign(
  campaignId: string,
  payload: UpdateCampaignPayload,
): Promise<CampaignResponse> {
  return unwrap(
    api.put<ApiResponse<CampaignResponse>>(`/campaigns/${campaignId}`, payload),
  );
}

/** Soft-deletes a campaign. Bare 204 — no envelope to unwrap. */
export async function deleteCampaign(campaignId: string): Promise<void> {
  await api.delete(`/campaigns/${campaignId}`);
}

/** Changes campaign lifecycle status via dedicated PATCH endpoint. */
export function changeCampaignStatus(
  campaignId: string,
  payload: UpdateCampaignStatusPayload,
): Promise<CampaignResponse> {
  return unwrap(
    api.patch<ApiResponse<CampaignResponse>>(`/campaigns/${campaignId}/status`, payload),
  );
}

/** Clones a campaign (POST /api/v1/campaigns/{id}/clone). */
export function cloneCampaign(campaignId: string): Promise<CampaignResponse> {
  return unwrap(
    api.post<ApiResponse<CampaignResponse>>(`/campaigns/${campaignId}/clone`),
  );
}

/** Checks campaign execution readiness (GET /api/v1/campaigns/{id}/readiness). */
export function getCampaignReadiness(
  campaignId: string,
): Promise<CampaignReadinessResponse> {
  return unwrap(
    api.get<ApiResponse<CampaignReadinessResponse>>(`/campaigns/${campaignId}/readiness`),
  );
}

/** Requests campaign execution (POST /api/v1/campaigns/{id}/executions) — returns REQUESTED; idempotent on key. */
export function executeCampaign(
  campaignId: string,
  payload: ExecuteCampaignPayload,
): Promise<CampaignExecutionResponse> {
  return unwrap(
    api.post<ApiResponse<CampaignExecutionResponse>>(
      `/campaigns/${campaignId}/executions`,
      payload,
    ),
  );
}

/** Lists executions for a campaign (GET /api/v1/campaigns/{campaignId}/executions) — no pagination. */
export async function listCampaignExecutions(
  campaignId: string,
): Promise<CampaignExecutionResponse[]> {
  const { data } = await api.get<ApiResponse<CampaignExecutionResponse[]>>(
    `/campaigns/${campaignId}/executions`,
  );
  return data.data ?? [];
}

/** Gets a specific execution (GET /api/v1/campaigns/{campaignId}/executions/{executionId}). */
export function getCampaignExecution(
  campaignId: string,
  executionId: string,
): Promise<CampaignExecutionResponse> {
  return unwrap(
    api.get<ApiResponse<CampaignExecutionResponse>>(
      `/campaigns/${campaignId}/executions/${executionId}`,
    ),
  );
}

/** Legal lifecycle transitions (client-side mirror of backend LEGAL_TRANSITIONS). */
export const CAMPAIGN_LEGAL_TRANSITIONS: Record<CampaignStatus, CampaignStatus[]> = {
  DRAFT: ["SCHEDULED"],
  SCHEDULED: ["RUNNING", "PAUSED", "DRAFT", "ARCHIVED"],
  RUNNING: ["PAUSED", "COMPLETED", "FAILED"],
  PAUSED: ["SCHEDULED", "RUNNING", "ARCHIVED"],
  COMPLETED: ["ARCHIVED"],
  FAILED: ["ARCHIVED"],
  ARCHIVED: [],
};

/** System-driven transitions that cannot be invoked manually. */
export const CAMPAIGN_SYSTEM_TRANSITIONS: Array<{ from: CampaignStatus; to: CampaignStatus }> = [
  { from: "SCHEDULED", to: "RUNNING" },
  { from: "RUNNING", to: "COMPLETED" },
  { from: "RUNNING", to: "FAILED" },
];

// ==== Reference data fetching for create/edit dialogs ====

/**
 * Fetches DIDs suitable for campaign assignment.
 * Filters: ACTIVE status, ASSIGNED allocation state (server-enforced for campaign use).
 * Returns minimal fields needed for selection.
 */
export async function getDidsForCampaign(): Promise<DidResponse[]> {
  const { data } = await api.get<ApiResponse<DidResponse[]>>("/dids", {
    params: {
      status: "ACTIVE",
      allocationState: "ASSIGNED",
      size: 100, // reasonable limit for selection
    },
  });
  return data.data ?? [];
}

/**
 * Fetches Contact Groups for campaign assignment.
 * Returns minimal fields needed for selection.
 */
export async function getContactGroupsForCampaign(): Promise<ContactGroupResponse[]> {
  const { data } = await api.get<ApiResponse<ContactGroupResponse[]>>("/contact-groups", {
    params: {
      size: 100, // reasonable limit for selection
    },
  });
  return data.data ?? [];
}

/**
 * Fetches Audio Assets suitable for campaign use.
 * Filters: APPROVED status (server-enforced for campaign use).
 * Returns minimal fields needed for selection.
 */
export async function getAudioAssetsForCampaign(): Promise<AudioAssetResponse[]> {
  const { data } = await api.get<ApiResponse<AudioAssetResponse[]>>("/audio-assets", {
    params: {
      status: "APPROVED",
      size: 100, // reasonable limit for selection
    },
  });
  return data.data ?? [];
}

/**
 * Fetches TTS Templates suitable for campaign use.
 * Filters: APPROVED status (server-enforced for campaign use).
 * Returns minimal fields needed for selection.
 */
export async function getTtsTemplatesForCampaign(): Promise<TtsTemplateResponse[]> {
  const { data } = await api.get<ApiResponse<TtsTemplateResponse[]>>("/tts-templates", {
    params: {
      status: "APPROVED",
      size: 100, // reasonable limit for selection
    },
  });
  return data.data ?? [];
}