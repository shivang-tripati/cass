import { api } from "@/lib/api/client";
import type {
  CreateDidPayload,
  DidResponse,
  UpdateDidPayload,
} from "@/lib/api/contracts";
import { unwrap } from "@/lib/api/auth";
import type {
  ApiResponse,
  PaginationMetadata,
} from "@/lib/api/types";

/**
 * /api/v1/dids contract (verified against DidController/DidService):
 * - page zero-based; size server-clamped 1..100
 * - sort whitelist: e164Number, createdAt, updatedAt, status, allocationState,
 *   numberType, provider; default createdAt,desc; unknown fields silently fall back
 * - status: DidStatus name; unknown → 400
 * - allocationState: AllocationState name; unknown → 400
 * - numberType: NumberType name; unknown → 400
 * - provider: exact match (case-insensitive)
 * - circle: exact match (case-insensitive)
 * - search (q): case-insensitive contains over e164Number, circle, provider
 * - tenantId: honored only for platform-scope callers
 * - resellerId: honored only for platform-scope callers
 * - DELETE is a soft delete (deletedAt stamp) returning bare 204
 */
export const DID_SORTABLE_FIELDS = [
  "e164Number",
  "createdAt",
  "updatedAt",
  "status",
  "allocationState",
  "numberType",
  "provider",
] as const;

export type DidSortField = (typeof DID_SORTABLE_FIELDS)[number];

export interface DidListParams {
  page: number;
  size: number;
  sortField: DidSortField;
  sortDirection: "asc" | "desc";
  status?: DidStatus;
  allocationState?: AllocationState;
  numberType?: NumberType;
  provider?: string;
  circle?: string;
  tenantId?: string;
  resellerId?: string;
  search?: string;
}

/** Stable query-key factory for the DIDs module. */
export const didsKeys = {
  all: ["dids"] as const,
  list: (params: DidListParams) => ["dids", "list", params] as const,
  detail: (didId: string) => ["dids", "detail", didId] as const,
};

export interface DidPage {
  items: DidResponse[];
  pagination: PaginationMetadata;
}

function buildSort(params: DidListParams): string {
  return `${params.sortField},${params.sortDirection}`;
}

export async function getDids(params: DidListParams): Promise<DidPage> {
  const { data } = await api.get<ApiResponse<DidResponse[]>>("/dids", {
    params: {
      page: params.page,
      size: params.size,
      sort: buildSort(params),
      status: params.status || undefined,
      allocationState: params.allocationState || undefined,
      numberType: params.numberType || undefined,
      provider: params.provider || undefined,
      circle: params.circle || undefined,
      tenantId: params.tenantId || undefined,
      resellerId: params.resellerId || undefined,
      q: params.search || undefined,
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

export function getDid(didId: string): Promise<DidResponse> {
  return unwrap(api.get<ApiResponse<DidResponse>>(`/dids/${didId}`));
}

export function createDid(payload: CreateDidPayload): Promise<DidResponse> {
  return unwrap(api.post<ApiResponse<DidResponse>>("/dids", payload));
}

export function updateDid(
  didId: string,
  payload: UpdateDidPayload,
): Promise<DidResponse> {
  return unwrap(
    api.put<ApiResponse<DidResponse>>(`/dids/${didId}`, payload),
  );
}

/** Soft-deletes a DID. Bare 204 — no envelope to unwrap. */
export async function deleteDid(didId: string): Promise<void> {
  await api.delete(`/dids/${didId}`);
}

/** com.shivang.obd.did.DidStatus */
export type DidStatus = "ACTIVE" | "INACTIVE";

/** com.shivang.obd.did.AllocationState */
export type AllocationState = "AVAILABLE" | "ASSIGNED";

/** com.shivang.obd.did.NumberType */
export type NumberType = "LANDLINE" | "MOBILE" | "PROMOTIONAL_140";

/** com.shivang.obd.did.DidCapability */
export type DidCapability = "VOICE_OUTBOUND";