import { api } from "@/lib/api/client";
import type {
  CreateResellerPayload,
  ResellerResponse,
  UpdateResellerPayload,
} from "@/lib/api/contracts";
import { unwrap } from "@/lib/api/auth";
import type {
  ApiResponse,
  LifecycleStatus,
  PaginationMetadata,
} from "@/lib/api/types";

/**
 * /api/v1/resellers contract (verified against ResellerController/Service):
 * - page zero-based; size server-clamped 1..100
 * - sort whitelist: name, slug, createdAt, updatedAt, status; default
 *   createdAt,desc; unknown fields silently fall back
 * - status: LifecycleStatus name; unknown → 400
 * - search: case-insensitive contains over name/slug/displayName
 * - DELETE is a soft delete (deletedAt stamp) returning bare 204; no
 *   cascade — tenant rows are not modified.
 */
export const RESELLER_SORTABLE_FIELDS = [
  "name",
  "slug",
  "status",
  "createdAt",
] as const;

export type ResellerSortField = (typeof RESELLER_SORTABLE_FIELDS)[number];

export interface ResellerListParams {
  page: number;
  size: number;
  sortField: ResellerSortField;
  sortDirection: "asc" | "desc";
  status?: LifecycleStatus;
  search?: string;
}

/** Stable query-key factory for the resellers module. */
export const resellersKeys = {
  all: ["resellers"] as const,
  list: (params: ResellerListParams) => ["resellers", "list", params] as const,
  detail: (resellerId: string) => ["resellers", "detail", resellerId] as const,
};

export interface ResellerPage {
  items: ResellerResponse[];
  pagination: PaginationMetadata;
}

function buildSort(params: ResellerListParams): string {
  return `${params.sortField},${params.sortDirection}`;
}

export async function getResellers(
  params: ResellerListParams,
): Promise<ResellerPage> {
  const { data } = await api.get<ApiResponse<ResellerResponse[]>>(
    "/resellers",
    {
      params: {
        page: params.page,
        size: params.size,
        sort: buildSort(params),
        status: params.status || undefined,
        search: params.search || undefined,
      },
    },
  );
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

export function getReseller(resellerId: string): Promise<ResellerResponse> {
  return unwrap(
    api.get<ApiResponse<ResellerResponse>>(`/resellers/${resellerId}`),
  );
}

/**
 * Name is editable with blank-ignore semantics; the branding/contact fields
 * apply whenever sent, so cleared values are transmitted as "".
 */
export function updateReseller(
  resellerId: string,
  payload: UpdateResellerPayload,
): Promise<ResellerResponse> {
  return unwrap(
    api.put<ApiResponse<ResellerResponse>>(`/resellers/${resellerId}`, payload),
  );
}

export function createReseller(
  payload: CreateResellerPayload,
): Promise<ResellerResponse> {
  return unwrap(
    api.post<ApiResponse<ResellerResponse>>("/resellers", payload),
  );
}

/** Soft-deletes a reseller. Bare 204 — no envelope to unwrap. */
export async function deleteReseller(resellerId: string): Promise<void> {
  await api.delete(`/resellers/${resellerId}`);
}
