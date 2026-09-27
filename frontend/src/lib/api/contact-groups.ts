import { api } from "@/lib/api/client";
import type {
  ContactGroupResponse,
  CreateContactGroupPayload,
  UpdateContactGroupPayload,
  ContactImportResponse,
} from "@/lib/api/contracts";
import { unwrap } from "@/lib/api/auth";
import type {
  ApiResponse,
  PaginationMetadata,
} from "@/lib/api/types";

/**
 * /api/v1/contact-groups contract (verified against ContactGroupController/ContactGroupService):
 * - page zero-based; size server-clamped 1..100
 * - sort whitelist: name, createdAt, updatedAt; default createdAt,desc
 * - search: case-insensitive contains over name
 * - DELETE is a soft delete (deletedAt stamp) returning bare 204
 *   Rejected with 409 if group still contains contacts
 */
export const CONTACT_GROUP_SORTABLE_FIELDS = [
  "name",
  "createdAt",
  "updatedAt",
] as const;

export type ContactGroupSortField = (typeof CONTACT_GROUP_SORTABLE_FIELDS)[number];

export interface ContactGroupListParams {
  page: number;
  size: number;
  sortField: ContactGroupSortField;
  sortDirection: "asc" | "desc";
  search?: string;
}

/** Stable query-key factory for the Contact Groups module. */
export const contactGroupsKeys = {
  all: ["contact-groups"] as const,
  list: (params: ContactGroupListParams) => ["contact-groups", "list", params] as const,
  detail: (groupId: string) => ["contact-groups", "detail", groupId] as const,
};

export interface ContactGroupPage {
  items: ContactGroupResponse[];
  pagination: PaginationMetadata;
}

function buildSort(params: ContactGroupListParams): string {
  return `${params.sortField},${params.sortDirection}`;
}

export async function getContactGroups(params: ContactGroupListParams): Promise<ContactGroupPage> {
  const { data } = await api.get<ApiResponse<ContactGroupResponse[]>>("/contact-groups", {
    params: {
      page: params.page,
      size: params.size,
      sort: buildSort(params),
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

export function getContactGroup(groupId: string): Promise<ContactGroupResponse> {
  return unwrap(api.get<ApiResponse<ContactGroupResponse>>(`/contact-groups/${groupId}`));
}

export function createContactGroup(payload: CreateContactGroupPayload): Promise<ContactGroupResponse> {
  return unwrap(api.post<ApiResponse<ContactGroupResponse>>("/contact-groups", payload));
}

export function updateContactGroup(groupId: string, payload: UpdateContactGroupPayload): Promise<ContactGroupResponse> {
  return unwrap(api.put<ApiResponse<ContactGroupResponse>>(`/contact-groups/${groupId}`, payload));
}

/** Soft-deletes a contact group. Bare 204 — no envelope to unwrap. 
 * Returns 409 if group still contains contacts. */
export async function deleteContactGroup(groupId: string): Promise<void> {
  await api.delete(`/contact-groups/${groupId}`);
}

/** Bulk-import contacts into a group. Returns import result with per-row errors. */
export async function importContacts(groupId: string, file: File): Promise<ContactImportResponse> {
  const formData = new FormData();
  formData.append("file", file);
  return unwrap(
    api.post<ApiResponse<ContactImportResponse>>(
      `/contact-groups/${groupId}/contacts/import`,
      formData,
      { headers: { "Content-Type": "multipart/form-data" } },
    ),
  );
}

/** Export contacts from a group. Returns raw file blob. */
export async function exportContacts(groupId: string, format: "csv" | "xlsx" | "json" = "csv"): Promise<Blob> {
  const response = await api.get(`/contact-groups/${groupId}/contacts/export`, {
    params: { format },
    responseType: "blob",
  });
  return response.data;
}