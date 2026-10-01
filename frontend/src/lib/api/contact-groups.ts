import { api } from "@/lib/api/client";
import type {
  ContactGroupResponse,
  CreateContactGroupPayload,
  UpdateContactGroupPayload,
} from "@/lib/api/contracts";
import { sendVoid, unwrap } from "@/lib/api/transport";
import type { ApiResponse, PaginationMetadata } from "@/lib/api/types";

/**
 * /api/v1/contact-groups — the group (audience container) API.
 *
 * VERIFIED (F2) against ContactGroupController L51-134 and ContactGroupService.
 *
 * Import/export and membership live in sibling modules and are deliberately NOT
 * re-exported here: `contacts.ts` owns the identity API and
 * `contact-group-members.ts` owns participation. F0 found `importContacts` and
 * `exportContacts` duplicated byte-for-byte across this file and `contacts.ts`;
 * this file no longer declares either, so there is one implementation of each.
 *
 * ## DELETE has NO 409 — a corrected belief
 *
 * The controller's OpenAPI annotation claims `409 "Group still contains
 * contacts"`, and both F0 and the previous frontend comment repeated that. It is
 * wrong. `ContactGroupService.deleteGroup` (L171-181) calls
 * `memberService.removeAllForGroup(groupId)` and then soft-deletes the group;
 * a grep for `ConflictException` in the delete path finds only the contact
 * identity race at L194. The service Javadoc states the intended behaviour
 * explicitly: *"A group with live members can be deleted — the membership links
 * are what die, not the identities."*
 *
 * The practical consequence for the UI: deleting a group DOES remove its
 * memberships, but the CONTACTS THEMSELVES survive and stay live in the tenant,
 * and remain members of any other group. The confirmation dialog must say that.
 * The 409 branch is retained below for safety, but it is not an expected path.
 */
export const CONTACT_GROUP_SORTABLE_FIELDS = [
  "name",
  "createdAt",
  "updatedAt",
] as const;

export type ContactGroupSortField =
  (typeof CONTACT_GROUP_SORTABLE_FIELDS)[number];

export interface ContactGroupListParams {
  page: number;
  size: number;
  sortField: ContactGroupSortField;
  sortDirection: "asc" | "desc";
  search?: string;
}

export const contactGroupsKeys = {
  all: ["contact-groups"] as const,
  list: (params: ContactGroupListParams) =>
    ["contact-groups", "list", params] as const,
  detail: (groupId: string) => ["contact-groups", "detail", groupId] as const,
};

export interface ContactGroupPage {
  items: ContactGroupResponse[];
  pagination: PaginationMetadata;
}

function buildSort(params: ContactGroupListParams): string {
  return `${params.sortField},${params.sortDirection}`;
}

export async function getContactGroups(
  params: ContactGroupListParams,
): Promise<ContactGroupPage> {
  const { data } = await api.get<ApiResponse<ContactGroupResponse[]>>(
    "/contact-groups",
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
      // F1 contract: do not invent a page object.
      {
        page: params.page,
        size: params.size,
        totalElements: data.data?.length ?? 0,
        totalPages: data.data?.length ? 1 : 0,
        hasNext: false,
        hasPrevious: params.page > 0,
      },
  };
}

export function getContactGroup(
  groupId: string,
): Promise<ContactGroupResponse> {
  return unwrap(api.get<ApiResponse<ContactGroupResponse>>(`/contact-groups/${groupId}`));
}

/** POST /contact-groups — requires `CONTACT_MANAGE` for the CONTEXT tenant.
 *
 * VERIFIED: there is no `tenantId` field in the request. Ownership is always the
 * caller's own context tenant, and a caller with no tenant context is refused
 * ("A tenant must be specified for this operation."). The frontend therefore
 * must NOT offer a tenant picker on this form. */
export function createContactGroup(
  payload: CreateContactGroupPayload,
): Promise<ContactGroupResponse> {
  return unwrap(
    api.post<ApiResponse<ContactGroupResponse>>("/contact-groups", payload),
  );
}

/** PUT /contact-groups/{id} — name is `@NotBlank`; description is optional and
 * a blank value clears it. `tenantId` is immutable and absent from the DTO. */
export function updateContactGroup(
  groupId: string,
  payload: UpdateContactGroupPayload,
): Promise<ContactGroupResponse> {
  return unwrap(
    api.put<ApiResponse<ContactGroupResponse>>(`/contact-groups/${groupId}`, payload),
  );
}

/** DELETE /contact-groups/{id} — soft delete, bare 204.
 *
 * Cascades: the group's MEMBERSHIP ROWS are physically removed. The CONTACTS do
 * not exist independently of a group, but the contact IDENTITIES do — they are
 * tenant-scoped and survive, remaining members of any other group. See the
 * module comment for why there is no 409. */
export function deleteContactGroup(groupId: string): Promise<void> {
  return sendVoid(api.delete(`/contact-groups/${groupId}`));
}
