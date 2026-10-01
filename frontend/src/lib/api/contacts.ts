import { api } from "@/lib/api/client";
import type {
  ContactResponse,
  CreateContactPayload,
  UpdateContactPayload,
  ContactImportResponse,
} from "@/lib/api/contracts";
import { sendVoid, unwrap, unwrapDownload } from "@/lib/api/transport";
import type { Download } from "@/lib/api/transport";
import type { ApiResponse, PaginationMetadata } from "@/lib/api/types";

/**
 * /api/v1/contact-groups/{contactGroupId}/contacts — the CONTACT IDENTITY API.
 *
 * VERIFIED (F2) against ContactGroupController L138-224 and ContactGroupService.
 * There is **no** top-level `/api/v1/contacts` resource: contacts are only
 * reachable through their owning group, because a contact belongs to a tenant
 * and participation is a membership concern. F0 confirmed no `ContactController`
 * exists.
 *
 * ## Identity vs membership — do not conflate these
 *
 * `POST /{id}/contacts` is **find-or-create**. If the tenant already holds a
 * live contact for that phone number, the existing identity is reused and only
 * the membership is added (ContactGroupService L196-220). The identity key is
 * `(tenant_id, canonical_phone_number)`, DB-enforced by
 * `uq_contacts_tenant_phone_live`, so the same number in a DIFFERENT tenant is a
 * different contact and is allowed.
 *
 * `DELETE /{id}/contacts/{contactId}` soft-deletes the **identity** and
 * physically removes ALL of its memberships across every group
 * (ContactGroupService L294-307). To remove a contact from ONE group while
 * keeping it alive elsewhere, use `removeContactGroupMember` in
 * `lib/api/contact-group-members.ts` instead. Using the wrong one is data loss.
 *
 * ## There is no top-level /api/v1/contacts
 *
 * Confirmed: no `ContactController` exists anywhere in the backend. A contact
 * is a tenant-level identity that is only ever reached through a group route,
 * so the frontend must not construct `/contacts/{id}` URLs.
 *
 * ## Pagination
 *
 * 0-based `page`, `size` server-clamped 1..100, real `PaginationMetadata`.
 * Sort allowlist `firstName, phoneNumber, createdAt`, default `firstName,asc`.
 * Search matches firstName, lastName AND phoneNumber (ContactSpecifications) —
 * the previous frontend comment claimed email too, which the backend does not
 * search.
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

export const contactsKeys = {
  all: ["contacts"] as const,
  list: (params: ContactListParams) => ["contacts", "list", params] as const,
  detail: (contactGroupId: string, contactId: string) =>
    ["contacts", "detail", contactGroupId, contactId] as const,
  /** Every list of this group's contacts, regardless of paging/sort/search.
   * Used to invalidate after a mutation without reconstructing exact params. */
  forGroup: (contactGroupId: string) =>
    ["contacts", "list", { contactGroupId }] as const,
};

export interface ContactPage {
  items: ContactResponse[];
  pagination: PaginationMetadata;
}

function buildSort(params: ContactListParams): string {
  return `${params.sortField},${params.sortDirection}`;
}

export async function getContacts(
  params: ContactListParams,
): Promise<ContactPage> {
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
      // F1 contract: do not invent a page object. This endpoint genuinely sends
      // pagination, so the fallback only guards a malformed 200.
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

export function getContact(
  contactGroupId: string,
  contactId: string,
): Promise<ContactResponse> {
  return unwrap(
    api.get<ApiResponse<ContactResponse>>(
      `/contact-groups/${contactGroupId}/contacts/${contactId}`,
    ),
  );
}

/** POST /{id}/contacts — find-or-create the identity, then upsert membership.
 *
 * VERIFIED conflict: a phone number that already exists as a live identity in
 * this tenant is REUSED, not rejected. A concurrent create of the same
 * tenant+phone surfaces as a typed 409
 * ("A contact with this phone number already exists for this tenant."), never a
 * 500. */
export function createContact(
  contactGroupId: string,
  payload: CreateContactPayload,
): Promise<ContactResponse> {
  return unwrap(
    api.post<ApiResponse<ContactResponse>>(
      `/contact-groups/${contactGroupId}/contacts`,
      payload,
    ),
  );
}

/** PUT /{id}/contacts/{contactId}
 *
 * VERIFIED replace semantics, and the three fields behave differently — this is
 * not a uniform "replace":
 *  - `phoneNumber` is required in practice: `canonicalPhoneNumber` throws
 *    VALIDATION_ERROR for anything that cannot reach canonical E.164 form, so an
 *    omitted or null phone is a 400.
 *  - `firstName` is stored as `trim()`-ed and NOT blank-to-null, so sending `""`
 *    would persist an empty string. Omit it instead.
 *  - `lastName` and `email` ARE blank-to-null, so sending `""` clears them.
 *  - `attributes` is assigned unconditionally (`entity.setAttributes(attributes)`),
 *    so omitting it CLEARS the stored object. This is the one field where a
 *    "leave unchanged" edit is impossible; the form therefore always sends the
 *    whole attributes object.
 *
 * A phone change keeps the same Contact UUID, so call history stays attached,
 * and collides with another live identity in the same tenant only as a typed
 * 409 (`assertPhoneAvailableForTenant`). */
export function updateContact(
  contactGroupId: string,
  contactId: string,
  payload: UpdateContactPayload,
): Promise<ContactResponse> {
  return unwrap(
    api.put<ApiResponse<ContactResponse>>(
      `/contact-groups/${contactGroupId}/contacts/${contactId}`,
      payload,
    ),
  );
}

/** DELETE /{id}/contacts/{contactId}
 *
 * DESTRUCTIVE BEYOND THIS GROUP: soft-deletes the contact IDENTITY and removes
 * all of its memberships in every group. The phone number becomes re-creatable.
 * To unlink from this group only, use `removeContactGroupMember`. */
export function deleteContact(
  contactGroupId: string,
  contactId: string,
): Promise<void> {
  return sendVoid(
    api.delete(`/contact-groups/${contactGroupId}/contacts/${contactId}`),
  );
}

/** POST /{id}/contacts/import — multipart, field name `file`.
 *
 * VERIFIED: max 5 MB / 5000 rows; `.csv`, `.xlsx` or `.json`. Required column
 * `phoneNumber` (E.164); optional `firstName`, `lastName`, `email`,
 * `attributes` (a JSON object STRING). Rows are validated individually —
 * duplicates within the file and against existing live contacts are SKIPPED and
 * reported, so a partial success is normal. Ownership comes exclusively from the
 * group; ownership columns in the file are ignored. At most 100 errors are
 * reported (MAX_REPORTED_ERRORS), so `errors.length` is not `errorCount`. */
export function importContacts(
  contactGroupId: string,
  file: File,
): Promise<ContactImportResponse> {
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

/** GET /{id}/contacts/export?format=csv|xlsx|json
 *
 * VERIFIED: a RAW FILE DOWNLOAD, not the JSON envelope — so it bypasses
 * `unwrap`. Columns are exactly `phoneNumber, firstName, lastName, email,
 * attributes`; ownership and audit fields are never included. Requires
 * `CONTACT_VIEW` (not CONTACT_MANAGE, and not the un-enforced CONTACT_EXPORT
 * key — see `ContactGroupService.exportContacts`).
 *
 * The backend supplies `Content-Disposition: attachment; filename="…"`, so the
 * filename comes from the server rather than being assembled client-side. */
export async function exportContacts(
  contactGroupId: string,
  format: "csv" | "xlsx" | "json" = "csv",
): Promise<Download> {
  return unwrapDownload(
    api.get(`/contact-groups/${contactGroupId}/contacts/export`, {
      params: { format },
      responseType: "blob",
    }),
  );
}
