import { beforeEach, describe, expect, it, vi } from "vitest";

import type { ApiResponse } from "@/lib/api/types";

/**
 * F2 — the `/api/v1/contact-groups` request contract.
 *
 * Two corrections are pinned here that the F0 code got wrong:
 *
 *  1. `importContacts` / `exportContacts` are NOT declared in this module. They
 *     were duplicated byte-for-byte across `contact-groups.ts` and `contacts.ts`;
 *     F2 leaves one owner each (the contacts identity API) so there is a single
 *     implementation to keep correct.
 *  2. DELETE carries NO 409 branch in the UI. The controller's OpenAPI annotation
 *     claims `409 "Group still contains contacts"`, but
 *     `ContactGroupService.deleteGroup` calls `removeAllForGroup` and then
 *     soft-deletes; the only `ConflictException` in the file is the contact
 *     identity race. The service Javadoc is explicit that a group with live
 *     members CAN be deleted.
 */

const get = vi.fn();
const post = vi.fn();
const put = vi.fn();
const del = vi.fn();

vi.mock("@/lib/api/client", () => ({
  api: {
    get: (...args: unknown[]) => get(...args),
    post: (...args: unknown[]) => post(...args),
    put: (...args: unknown[]) => put(...args),
    delete: (...args: unknown[]) => del(...args),
  },
}));

const contactGroups = await import("@/lib/api/contact-groups");

const GROUP_ID = "g-1";

function envelope<T>(data: T, pagination?: ApiResponse<T[]>["pagination"]) {
  return {
    data: { success: true, data, ...(pagination ? { pagination } : {}) },
    status: 200,
    headers: {} as Record<string, string>,
  };
}

const PAGE = {
  page: 0,
  size: 20,
  totalElements: 2,
  totalPages: 1,
  hasNext: false,
  hasPrevious: false,
};

beforeEach(() => {
  get.mockReset();
  post.mockReset();
  put.mockReset();
  del.mockReset();
});

describe("getContactGroups", () => {
  it("requests the top-level collection with a whitelisted sort", async () => {
    get.mockResolvedValue(envelope([{ id: GROUP_ID }], PAGE));

    await contactGroups.getContactGroups({
      page: 1,
      size: 10,
      sortField: "name",
      sortDirection: "asc",
      search: "leads",
    });

    expect(get).toHaveBeenCalledWith("/contact-groups", {
      params: { page: 1, size: 10, sort: "name,asc", search: "leads" },
    });
  });

  it("returns the server memberCount rather than counting anything itself", async () => {
    get.mockResolvedValue(
      envelope([{ id: GROUP_ID, name: "Leads", memberCount: 42 }], PAGE),
    );
    const page = await contactGroups.getContactGroups({
      page: 0,
      size: 20,
      sortField: "createdAt",
      sortDirection: "desc",
    });
    // VERIFIED: `ContactGroupService.withMemberCounts` fills memberCount from
    // ONE grouped query for the whole page.
    expect(page.items[0]?.memberCount).toBe(42);
  });
});

describe("getContactGroup", () => {
  it("requests the group by id and unwraps the envelope", async () => {
    get.mockResolvedValue(
      envelope({ id: GROUP_ID, name: "Leads", memberCount: 3 }),
    );

    const group = await contactGroups.getContactGroup(GROUP_ID);

    expect(get).toHaveBeenCalledWith(`/contact-groups/${GROUP_ID}`);
    expect(group).toEqual({ id: GROUP_ID, name: "Leads", memberCount: 3 });
  });
});

describe("createContactGroup", () => {
  it("POSTs name and description only — there is no tenantId in the DTO", async () => {
    post.mockResolvedValue(envelope({ id: GROUP_ID }, PAGE));
    const payload = { name: "Leads", description: "From the trade show" };

    await contactGroups.createContactGroup(payload);

    // VERIFIED: `CreateContactGroupRequest(name, description)`. Ownership is
    // always the caller's context tenant, derived server-side; a tenant picker
    // on this form would have nothing to bind to.
    expect(post).toHaveBeenCalledWith("/contact-groups", payload);
    expect(Object.keys(payload)).toEqual(["name", "description"]);
  });
});

describe("updateContactGroup", () => {
  it("uses PUT against the group path", async () => {
    put.mockResolvedValue(envelope({ id: GROUP_ID }));
    const payload = { name: "Leads v2" };

    await contactGroups.updateContactGroup(GROUP_ID, payload);

    expect(put).toHaveBeenCalledWith(`/contact-groups/${GROUP_ID}`, payload);
  });
});

describe("deleteContactGroup", () => {
  it("DELETEs the group and tolerates the bare 204", async () => {
    del.mockResolvedValue({ status: 204, data: undefined });
    await expect(contactGroups.deleteContactGroup(GROUP_ID)).resolves.toBeUndefined();
    expect(del).toHaveBeenCalledWith(`/contact-groups/${GROUP_ID}`);
  });
});

describe("module ownership", () => {
  it("does not re-export the contact import/export functions", async () => {
    // One implementation each. F0 had byte-identical copies in both files, so a
    // fix to one was invisible in the other.
    const exports = contactGroups as unknown as Record<string, unknown>;
    expect(exports.importContacts).toBeUndefined();
    expect(exports.exportContacts).toBeUndefined();
  });
});

describe("sort whitelist", () => {
  it("is exactly name/createdAt/updatedAt", async () => {
    // VERIFIED: `ContactGroupService.GROUP_SORTABLE_FIELDS`. `memberCount` is
    // NOT sortable, which is why the table marks that column unsortable.
    expect([...contactGroups.CONTACT_GROUP_SORTABLE_FIELDS]).toEqual([
      "name",
      "createdAt",
      "updatedAt",
    ]);
  });
});

describe("query keys", () => {
  it("prefixes every group query under one root so a mutation can clear them all", async () => {
    expect(contactGroups.contactGroupsKeys.all).toEqual(["contact-groups"]);
    expect(contactGroups.contactGroupsKeys.list({} as never)[0]).toBe(
      "contact-groups",
    );
    expect(contactGroups.contactGroupsKeys.detail(GROUP_ID)).toEqual([
      "contact-groups",
      "detail",
      GROUP_ID,
    ]);
  });
});
