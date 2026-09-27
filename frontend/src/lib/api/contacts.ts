import { api } from "@/lib/api/client";
import type {
  ContactResponse,
  CreateContactPayload,
  UpdateContactPayload,
  ContactImportResponse,
} from "@/lib/api/contracts";
import { unwrap } from "@/lib/api/auth";
import type { ApiResponse, PaginationMetadata } from "@/lib/api/types";

/**
 * /api/v1/contact-groups/{contactGroupId}/contacts contract (verified against ContactGroupController/ContactGroupService):
 * - page zero-based; size server-clamped 1..100
 * - sort whitelist: firstName, phoneNumber, createdAt; default firstName,asc
 * - search: case-insensitive contains over firstName, lastName, phoneNumber, email
 * - DELETE is a soft delete (deletedAt stamp) returning bare 204
 */
export const CONTACT_SORTABLE_FIELDS = [
  "firstName",
  "phoneNumber",
  "createdAt",
] as const;

export type ContactSortField = (typeof CONTACT_SORTABLE_FIELDS)[number];

export interface ContactListParams {
  contactGroupId: string;
  page: number;
  size: number;
  sortField: ContactSortField;
  sortDirection: "asc" | "desc";
  search?: string;
}

/** Stable query-key factory for the Contacts module. */
export const contactsKeys = {
  all: ["contacts"] as const,
  list: (params: ContactListParams) => ["contacts", "list", params] as const,
  detail: (contactGroupId: string, contactId: string) => ["contacts", "detail", contactGroupId, contactId] as const,
};

export interface ContactPage {
  items: ContactResponse[];
  pagination: PaginationMetadata;
}

function buildSort(params: ContactListParams): string {
  return `${params.sortField},${params.sortDirection}`;
}

export async function getContacts(params: ContactListParams): Promise<ContactPage> {
  const { data } = await api.get<ApiResponse<ContactResponse[]>>(
    `/contact-groups/${params.contactGroupId}/contacts`,
    {
      params: {
        page: params.page,
        size: params.size,
        sort: buildSort(params),
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

export function getContact(contactGroupId: string, contactId: string): Promise<ContactResponse> {
  return unwrap(api.get<ApiResponse<ContactResponse>>(`/contact-groups/${contactGroupId}/contacts/${contactId}`));
}

export function createContact(contactGroupId: string, payload: CreateContactPayload): Promise<ContactResponse> {
  return unwrap(api.post<ApiResponse<ContactResponse>>(`/contact-groups/${contactGroupId}/contacts`, payload));
}

export function updateContact(contactGroupId: string, contactId: string, payload: UpdateContactPayload): Promise<ContactResponse> {
  return unwrap(
    api.put<ApiResponse<ContactResponse>>(`/contact-groups/${contactGroupId}/contacts/${contactId}`, payload),
  );
}

/** Soft-deletes a contact. Bare 204 — no envelope to unwrap. */
export async function deleteContact(contactGroupId: string, contactId: string): Promise<void> {
  await api.delete(`/contact-groups/${contactGroupId}/contacts/${contactId}`);
}

/** Bulk-import contacts into a group. Returns import result with per-row errors. */
export async function importContacts(contactGroupId: string, file: File): Promise<ContactImportResponse> {
  const formData = new FormData();
  formData.append("file", file);
  return unwrap(
    api.post<ApiResponse<ContactImportResponse>>(
      `/contact-groups/${contactGroupId}/contacts/import`,
      formData,
      { headers: { "Content-Type": "multipart/form-data" } },
    ),
  );
}

/** Export contacts from a group. Returns raw file blob. */
export async function exportContacts(contactGroupId: string, format: "csv" | "xlsx" | "json" = "csv"): Promise<Blob> {
  const response = await api.get(`/contact-groups/${contactGroupId}/contacts/export`, {
    params: { format },
    responseType: "blob",
  });
  return response.data;
}