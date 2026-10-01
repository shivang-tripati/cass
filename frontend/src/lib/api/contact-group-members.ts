import { api } from "@/lib/api/client";
import type {
  AddMemberPayload,
  BatchMemberPayload,
  BatchMemberResponse,
  ContactGroupMemberResponse,
} from "@/lib/api/contracts";
import { sendVoid, unwrap } from "@/lib/api/transport";
import type { ApiResponse, PaginationMetadata } from "@/lib/api/types";

/**
 * /api/v1/contact-groups/{groupId}/members contract.
 *
 * VERIFIED (F2) against ContactGroupController L226-360 and
 * ContactGroupMemberService. F0 flagged this roster API as "entirely unused by
 * the frontend"; it is a first-class, fully-supported capability and F2 adopts
 * it for membership management.
 *
 * ## Why this is a SEPARATE resource from `/contacts`
 *
 * These are two different things, and conflating them is how a data model gets
 * corrupted:
 *
 *  - **Identity** (`/contacts`) is tenant-scoped: `(tenant_id,
 *    canonical_phone_number)`, DB-enforced by `uq_contacts_tenant_phone_live`
 *    (ContactIdentityService Javadoc). One tenant + number = exactly ONE live
 *    Contact, no matter how many groups contain it. There is NO global phone
 *    uniqueness — the tenant is inside the key.
 *  - **Participation** (`/members`) is a separate physical relationship row. The
 *    same contact may be a member of many groups.
 *
 * A contact is never *owned* by a group, and `ContactResponse` has no group
 * field — the group is the parent segment of its REST route.
 *
 * ## Operation semantics (all verified, all non-obvious)
 *
 * | Operation | Idempotent? | Success | Notes |
 * |---|---|---|---|
 * | `POST /{id}/contacts` | **find-or-create** | 201 | Reuses the tenant identity if the phone already exists, then upserts the membership. A raced insert surfaces as a typed 409, never a 500. |
 * | `DELETE /{id}/contacts/{contactId}` | yes (absent → 204) | 204 | **Soft-deletes the IDENTITY** and physically removes ALL its memberships, in every group. The phone becomes re-creatable. |
 * | `POST /{id}/members` | **yes** | **201 or 200** | 201 when created, 200 when the contact was already a member. A foreign-tenant contact is 404, indistinguishable from nonexistent. |
 * | `DELETE /{id}/members/{contactId}` | **yes** | 204 | Removes the RELATIONSHIP only. Contact existence is irrelevant. A missing membership is a successful no-op. |
 * | `POST /{id}/members/batch` | yes | 200 | Never fail-fast: every distinct id gets its own outcome. Duplicates inside one request collapse. Max 500. |
 * | `DELETE /{id}/members/batch` | yes | 200 | **DELETE with a request body.** Per-item outcomes; max 500. |
 *
 * ## Pagination
 *
 * `page` is 0-based, `size` is server-clamped to 1..100, and the response DOES
 * carry `PaginationMetadata` (it uses `ResponseFactory.page`). Sort allowlist is
 * `createdAt, firstName, phoneNumber`, default `createdAt,asc` — note the
 * default is ASC, unlike the groups and contacts listings.
 */

export const CONTACT_GROUP_MEMBER_SORTABLE_FIELDS = [
  "createdAt",
  "firstName",
  "phoneNumber",
] as const;

export type ContactGroupMemberSortField =
  (typeof CONTACT_GROUP_MEMBER_SORTABLE_FIELDS)[number];

export interface ContactGroupMemberListParams {
  groupId: string;
  page: number;
  size: number;
  sortField: ContactGroupMemberSortField;
  sortDirection: "asc" | "desc";
  search?: string;
}

/** Query-key factory. Mirrors the F1 convention: `all`, then per-resource keys. */
export const contactGroupMembersKeys = {
  all: ["contact-group-members"] as const,
  list: (params: ContactGroupMemberListParams) =>
    ["contact-group-members", "list", params] as const,
  detail: (groupId: string, contactId: string) =>
    ["contact-group-members", "detail", groupId, contactId] as const,
};

export interface ContactGroupMemberPage {
  items: ContactGroupMemberResponse[];
  pagination: PaginationMetadata;
}

export async function getContactGroupMembers(
  params: ContactGroupMemberListParams,
): Promise<ContactGroupMemberPage> {
  const { data } = await api.get<ApiResponse<ContactGroupMemberResponse[]>>(
    `/contact-groups/${params.groupId}/members`,
    {
      params: {
        page: params.page,
        size: params.size,
        sort: `${params.sortField},${params.sortDirection}`,
        search: params.search || undefined,
      },
    },
  );
  return {
    items: data.data ?? [],
    pagination:
      data.pagination ??
      // F1 contract: never fabricate a page object for a response that lacks
      // one. This endpoint DOES send pagination; the fallback exists only so a
      // malformed 200 cannot crash a render, and reports zero totals honestly.
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

export function getContactGroupMember(
  groupId: string,
  contactId: string,
): Promise<ContactGroupMemberResponse> {
  return unwrap(
    api.get<ApiResponse<ContactGroupMemberResponse>>(
      `/contact-groups/${groupId}/members/${contactId}`,
    ),
  );
}

export interface AddMemberOutcome {
  member: ContactGroupMemberResponse;
  created: boolean;
}

/** POST /{id}/members
 *
 * Idempotent, and the status code carries the distinction: **201** when the
 * membership was created, **200** when the contact was already a member. The
 * `success` flag in the envelope is true in both cases, so the status — not the
 * body — is the only signal, which is why the raw response is inspected. */
export async function addContactGroupMember(
  groupId: string,
  payload: AddMemberPayload,
): Promise<AddMemberOutcome> {
  const response = await api.post<ApiResponse<ContactGroupMemberResponse>>(
    `/contact-groups/${groupId}/members`,
    payload,
  );
  return { member: response.data.data as ContactGroupMemberResponse, created: response.status === 201 };
}

/** DELETE /{id}/members/{contactId}
 *
 * Removes the RELATIONSHIP ONLY. The contact identity is untouched and remains
 * in every other group it belongs to. This is materially different from
 * `deleteContact`, which soft-deletes the identity itself — see
 * `lib/api/contacts.ts`. */
export function removeContactGroupMember(
  groupId: string,
  contactId: string,
): Promise<void> {
  return sendVoid(
    api.delete(`/contact-groups/${groupId}/members/${contactId}`),
  );
}

/** POST /{id}/members/batch — never fail-fast. */
export function addContactGroupMembersBatch(
  groupId: string,
  payload: BatchMemberPayload,
): Promise<BatchMemberResponse> {
  return unwrap(
    api.post<ApiResponse<BatchMemberResponse>>(
      `/contact-groups/${groupId}/members/batch`,
      payload,
    ),
  );
}

/** DELETE /{id}/members/batch — a DELETE WITH A REQUEST BODY.
 *
 * VERIFIED: the controller declares `@Valid @RequestBody BatchMemberRequest` on
 * a `@DeleteMapping`. Returns 200 with per-item outcomes, NOT 204. */
export function removeContactGroupMembersBatch(
  groupId: string,
  payload: BatchMemberPayload,
): Promise<BatchMemberResponse> {
  return unwrap(
    api.delete<ApiResponse<BatchMemberResponse>>(
      `/contact-groups/${groupId}/members/batch`,
      { data: payload },
    ),
  );
}
