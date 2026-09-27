import { api } from "@/lib/api/client";
import type {
  TtsTemplateResponse,
} from "@/lib/api/contracts";
import { unwrap } from "@/lib/api/auth";
import type {
  ApiResponse,
  PaginationMetadata,
} from "@/lib/api/types";

/**
 * /api/v1/tts-templates contract (verified against TtsTemplateController/TtsTemplateService):
 * - page zero-based; size server-clamped 1..100
 * - sort whitelist: name, createdAt, updatedAt, status; default createdAt,desc
 * - status: TtsTemplateStatus name; unknown → 400
 * - search: case-insensitive contains over name
 * - DELETE is a soft delete (deletedAt stamp) returning bare 204
 */
export const TTS_TEMPLATE_SORTABLE_FIELDS = [
  "name",
  "createdAt",
  "updatedAt",
  "status",
] as const;

export type TtsTemplateSortField = (typeof TTS_TEMPLATE_SORTABLE_FIELDS)[number];

export interface TtsTemplateListParams {
  page: number;
  size: number;
  sortField: TtsTemplateSortField;
  sortDirection: "asc" | "desc";
  status?: TtsTemplateStatus;
  search?: string;
}

/** Stable query-key factory for the TTS Templates module. */
export const ttsTemplatesKeys = {
  all: ["tts-templates"] as const,
  list: (params: TtsTemplateListParams) => ["tts-templates", "list", params] as const,
  detail: (templateId: string) => ["tts-templates", "detail", templateId] as const,
};

export interface TtsTemplatePage {
  items: TtsTemplateResponse[];
  pagination: PaginationMetadata;
}

function buildSort(params: TtsTemplateListParams): string {
  return `${params.sortField},${params.sortDirection}`;
}

export async function getTtsTemplates(params: TtsTemplateListParams): Promise<TtsTemplatePage> {
  const { data } = await api.get<ApiResponse<TtsTemplateResponse[]>>("/tts-templates", {
    params: {
      page: params.page,
      size: params.size,
      sort: buildSort(params),
      status: params.status || undefined,
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

export function getTtsTemplate(templateId: string): Promise<TtsTemplateResponse> {
  return unwrap(api.get<ApiResponse<TtsTemplateResponse>>(`/tts-templates/${templateId}`));
}

export function createTtsTemplate(payload: import("@/lib/api/contracts").CreateTtsTemplatePayload): Promise<TtsTemplateResponse> {
  return unwrap(api.post<ApiResponse<TtsTemplateResponse>>("/tts-templates", payload));
}

export function updateTtsTemplate(templateId: string, payload: import("@/lib/api/contracts").UpdateTtsTemplatePayload): Promise<TtsTemplateResponse> {
  return unwrap(api.put<ApiResponse<TtsTemplateResponse>>(`/tts-templates/${templateId}`, payload));
}

export async function deleteTtsTemplate(templateId: string): Promise<void> {
  await api.delete(`/tts-templates/${templateId}`);
}

export function approveTtsTemplate(templateId: string): Promise<TtsTemplateResponse> {
  return unwrap(api.patch<ApiResponse<TtsTemplateResponse>>(`/tts-templates/${templateId}/approve`));
}

export function rejectTtsTemplate(templateId: string): Promise<TtsTemplateResponse> {
  return unwrap(api.patch<ApiResponse<TtsTemplateResponse>>(`/tts-templates/${templateId}/reject`));
}

/** com.shivang.obd.tts.TtsTemplateStatus */
export type TtsTemplateStatus = "PENDING_APPROVAL" | "APPROVED" | "REJECTED";