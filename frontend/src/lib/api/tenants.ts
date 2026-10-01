import { api } from "@/lib/api/client";
import type {
  CreateTenantPayload,
  TenantResponse,
  UpdateTenantPayload,
} from "@/lib/api/contracts";
import { sendVoid, unwrap } from "@/lib/api/transport";
import type {
  ApiResponse,
  LifecycleStatus,
  PaginationMetadata,
} from "@/lib/api/types";

/**
 * /api/v1/tenants contract (verified against TenantController/TenantService):
 * - page zero-based; size server-clamped 1..100
 * - sort whitelist: name, slug, createdAt, updatedAt, status; default
 *   createdAt,desc; unknown fields silently fall back
 * - status: LifecycleStatus name; unknown → 400
 * - search: case-insensitive contains over name/slug
 * - DELETE is a soft delete (deletedAt stamp) returning bare 204
 */
export const TENANT_SORTABLE_FIELDS = [
  "name",
  "slug",
  "status",
  "createdAt",
] as const;

export type TenantSortField = (typeof TENANT_SORTABLE_FIELDS)[number];

export interface TenantListParams {
  page: number;
  size: number;
  sortField: TenantSortField;
  sortDirection: "asc" | "desc";
  status?: LifecycleStatus;
  search?: string;
}

/** Stable query-key factory for the tenants module. */
export const tenantsKeys = {
  all: ["tenants"] as const,
  list: (params: TenantListParams) => ["tenants", "list", params] as const,
  detail: (tenantId: string) => ["tenants", "detail", tenantId] as const,
};

export interface TenantPage {
  items: TenantResponse[];
  pagination: PaginationMetadata;
}

function buildSort(params: TenantListParams): string {
  return `${params.sortField},${params.sortDirection}`;
}

export async function getTenants(
  params: TenantListParams,
): Promise<TenantPage> {
  const { data } = await api.get<ApiResponse<TenantResponse[]>>("/tenants", {
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

export function getTenant(tenantId: string): Promise<TenantResponse> {
  return unwrap(api.get<ApiResponse<TenantResponse>>(`/tenants/${tenantId}`));
}

/** Name is the only backend-editable tenant field (blank ignored). */
export function updateTenant(
  tenantId: string,
  payload: UpdateTenantPayload,
): Promise<TenantResponse> {
  return unwrap(
    api.put<ApiResponse<TenantResponse>>(`/tenants/${tenantId}`, payload),
  );
}

export function createTenant(
  payload: CreateTenantPayload,
): Promise<TenantResponse> {
  // 201 with envelope; axios treats non-2xx as errors automatically.
  return unwrap(api.post<ApiResponse<TenantResponse>>("/tenants", payload));
}

/**
 * Soft-deletes a tenant. Returns bare 204 (no envelope), so the request is
 * awaited without unwrapping.
 */
export function deleteTenant(tenantId: string): Promise<void> {
  return sendVoid(api.delete(`/tenants/${tenantId}`));
}
