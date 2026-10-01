import { api } from "@/lib/api/client";
import type {
  UpdateUserRequest,
  UserResponse,
} from "@/lib/api/contracts";
import { unwrap } from "@/lib/api/transport";
import type { ApiResponse, LifecycleStatus, PaginationMetadata } from "@/lib/api/types";

/**
 * /api/v1/users contract (verified against UserController/UserService):
 * - page: zero-based; size: server-clamped to 1..100
 * - sort: "field" or "field,asc|desc"; only the first entry is honored;
 *   unknown fields silently fall back to createdAt,desc
 * - status: LifecycleStatus name (case-insensitive); unknown → 400
 * - search: case-insensitive contains over email/displayName
 */
export const USER_SORTABLE_FIELDS = [
  "email",
  "displayName",
  "status",
  "createdAt",
] as const;

export type UserSortField = (typeof USER_SORTABLE_FIELDS)[number];
export interface UserListParams {
  page: number;
  size: number;
  sortField: UserSortField;
  sortDirection: "asc" | "desc";
  status?: LifecycleStatus;
  search?: string;
}

/** Stable query-key factory for the users module. */
export const usersKeys = {
  all: ["users"] as const,
  list: (params: UserListParams) => ["users", "list", params] as const,
  detail: (userId: string) => ["users", "detail", userId] as const,
};

export interface UserPage {
  items: UserResponse[];
  pagination: PaginationMetadata;
}

function buildSort(params: UserListParams): string {
  return `${params.sortField},${params.sortDirection}`;
}

export async function getUsers(params: UserListParams): Promise<UserPage> {
  const { data } = await api.get<ApiResponse<UserResponse[]>>("/users", {
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

export function getUser(userId: string): Promise<UserResponse> {
  return unwrap(api.get<ApiResponse<UserResponse>>(`/users/${userId}`));
}

export function updateUser(
  userId: string,
  payload: UpdateUserRequest,
): Promise<UserResponse> {
  return unwrap(
    api.put<ApiResponse<UserResponse>>(`/users/${userId}`, payload),
  );
}
