import { beforeEach, describe, expect, it, vi } from "vitest";

import type { ApiResponse } from "@/lib/api/types";

/**
 * F2 — the `/api/v1/contact-groups/{groupId}/members` contract.
 *
 * This is the API F0 recorded as "entirely unused by the frontend". It is fully
 * supported, and F2 adopts it for membership management, so the request shapes
 * are pinned here.
 *
 * The two things most likely to be got wrong, and both are asserted below:
 *
 *  - **Add is idempotent and the STATUS carries the result.** 201 when the
 *    membership was created, 200 when the contact was already a member. The
 *    envelope's `success` flag is true either way, so the raw status is the
 *    only signal.
 *  - **Batch remove is a DELETE WITH A REQUEST BODY that returns 200**, not 204.
 */

const get = vi.fn();
const post = vi.fn();
const put = vi.fn();
const del = vi.fn();

vi.mock("@/lib/api/client", () => ({
  api: {
    get: (...args: unknown[]) => get(...args),
    post: (...args: unknown[]) => post(...args),
    // Present because the mocked client is a whole instance: a missing method
    // would throw at import time rather than at the call under test.
    put: (...args: unknown[]) => put(...args),
    delete: (...args: unknown[]) => del(...args),
  },
}));

const members = await import("@/lib/api/contact-group-members");

const GROUP_ID = "g-1";
const CONTACT_ID = "c-1";

function envelope<T>(data: T, pagination?: ApiResponse<T[]>["pagination"], status = 200) {
  return {
    data: { success: true, data, ...(pagination ? { pagination } : {}) },
    status,
    headers: {} as Record<string, string>,
  };
}

const PAGE = {
  page: 0,
  size: 20,
  totalElements: 1,
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

describe("getContactGroupMembers", () => {
  it("requests the roster with page/size/sort and omits an empty search", async () => {
    get.mockResolvedValue(envelope([{ memberId: "m-1" }], PAGE));

    await members.getContactGroupMembers({
      groupId: GROUP_ID,
      page: 1,
      size: 50,
      sortField: "firstName",
      sortDirection: "desc",
      search: "",
    });

    expect(get).toHaveBeenCalledWith(`/contact-groups/${GROUP_ID}/members`, {
      params: { page: 1, size: 50, sort: "firstName,desc" },
    });
  });

  it("passes the server pagination through unchanged", async () => {
    get.mockResolvedValue(
      envelope([{ memberId: "m-1" }], { ...PAGE, totalElements: 88, totalPages: 5 }),
    );
    const page = await members.getContactGroupMembers({
      groupId: GROUP_ID,
      page: 0,
      size: 20,
      sortField: "createdAt",
      sortDirection: "asc",
    });
    expect(page.pagination.totalElements).toBe(88);
    expect(page.pagination.totalPages).toBe(5);
  });
});

describe("sort whitelist", () => {
  it("is createdAt/firstName/phoneNumber with an ASC default", () => {
    // VERIFIED: `MEMBER_SORTABLE_FIELDS` and
    // `DEFAULT_MEMBER_SORT = Sort.by(ASC, "createdAt")`. The ASC default is the
    // odd one out — the groups list defaults to createdAt DESC — so it is
    // asserted rather than assumed.
    expect([...members.CONTACT_GROUP_MEMBER_SORTABLE_FIELDS]).toEqual([
      "createdAt",
      "firstName",
      "phoneNumber",
    ]);
  });
});

describe("getContactGroupMember", () => {
  it("requests one membership by contact id", async () => {
    get.mockResolvedValue(envelope({ memberId: "m-1", contactId: CONTACT_ID }));
    const member = await members.getContactGroupMember(GROUP_ID, CONTACT_ID);
    expect(get).toHaveBeenCalledWith(
      `/contact-groups/${GROUP_ID}/members/${CONTACT_ID}`,
    );
    expect(member.memberId).toBe("m-1");
  });
});

describe("addContactGroupMember", () => {
  it("reports created=true on 201", async () => {
    // VERIFIED: `AddOutcome.created` is surfaced as HTTP 201, and the envelope's
    // `success` flag is identical in both cases — so the status is read.
    post.mockResolvedValue(
      envelope({ memberId: "m-1", contactId: CONTACT_ID }, undefined, 201),
    );

    const outcome = await members.addContactGroupMember(GROUP_ID, {
      contactId: CONTACT_ID,
    });

    expect(post).toHaveBeenCalledWith(`/contact-groups/${GROUP_ID}/members`, {
      contactId: CONTACT_ID,
    });
    expect(outcome.created).toBe(true);
    expect(outcome.member.contactId).toBe(CONTACT_ID);
  });

  it("reports created=false on 200 — already a member, not an error", async () => {
    post.mockResolvedValue(
      envelope({ memberId: "m-1", contactId: CONTACT_ID }, undefined, 200),
    );
    const outcome = await members.addContactGroupMember(GROUP_ID, {
      contactId: CONTACT_ID,
    });
    expect(outcome.created).toBe(false);
  });
});

describe("removeContactGroupMember", () => {
  it("DELETEs the membership and tolerates the bare 204", async () => {
    del.mockResolvedValue({ status: 204, data: undefined });
    // VERIFIED idempotent: a missing membership is a successful no-op, and
    // `ResponseEntity<Void>` means there is nothing to unwrap.
    await expect(
      members.removeContactGroupMember(GROUP_ID, CONTACT_ID),
    ).resolves.toBeUndefined();
    expect(del).toHaveBeenCalledWith(
      `/contact-groups/${GROUP_ID}/members/${CONTACT_ID}`,
    );
  });
});

describe("addContactGroupMembersBatch", () => {
  it("POSTs the id list and returns the per-item outcomes", async () => {
    const results = [
      { contactId: "c-1", status: "CREATED", errorDetail: null },
      { contactId: "c-2", status: "EXISTS", errorDetail: null },
      { contactId: "c-3", status: "NOT_FOUND_CONTACT", errorDetail: null },
    ];
    post.mockResolvedValue(envelope({ results, processed: 3, failed: 1 }));

    const batch = await members.addContactGroupMembersBatch(GROUP_ID, {
      contactIds: ["c-1", "c-2", "c-3"],
    });

    expect(post).toHaveBeenCalledWith(`/contact-groups/${GROUP_ID}/members/batch`, {
      contactIds: ["c-1", "c-2", "c-3"],
    });
    // VERIFIED: one outcome per distinct requested id, in request order, and the
    // operation never fail-fasts.
    expect(batch.results.map((r) => r.status)).toEqual([
      "CREATED",
      "EXISTS",
      "NOT_FOUND_CONTACT",
    ]);
    expect(batch.failed).toBe(1);
  });
});

describe("removeContactGroupMembersBatch", () => {
  it("sends a DELETE WITH A REQUEST BODY and returns 200 per-item outcomes", async () => {
    // VERIFIED: the controller declares
    // `@DeleteMapping("/{id}/members/batch")` with `@Valid @RequestBody`, and the
    // service returns a `BatchMemberResponse` — so this is 200 with a body, not
    // the 204 a plain DELETE returns. Getting this wrong loses the per-item
    // report and reports a successful removal for an id that was not removed.
    const results = [
      { contactId: "c-1", status: "NOT_FOUND", errorDetail: null },
      { contactId: "c-2", status: "ERROR", errorDetail: "Add failed; retry this item." },
    ];
    del.mockResolvedValue(envelope({ results, processed: 2, failed: 1 }));

    const batch = await members.removeContactGroupMembersBatch(GROUP_ID, {
      contactIds: ["c-1", "c-2"],
    });

    const [url, config] = del.mock.calls[0] ?? [];
    expect(url).toBe(`/contact-groups/${GROUP_ID}/members/batch`);
    expect(config).toEqual({ data: { contactIds: ["c-1", "c-2"] } });
    // `NOT_FOUND` is a SUCCESSFUL no-op, not a failure — the row was already
    // gone, which is the state the caller asked for.
    expect(batch.results[0]?.status).toBe("NOT_FOUND");
    expect(batch.failed).toBe(1);
  });
});

describe("query keys", () => {
  it("roots every membership query under one prefix for invalidation", () => {
    const params = {
      groupId: GROUP_ID,
      page: 0,
      size: 20,
      sortField: "createdAt" as const,
      sortDirection: "asc" as const,
    };
    expect(members.contactGroupMembersKeys.all).toEqual(["contact-group-members"]);
    // `all` is a PREFIX of every membership key, so invalidating it clears the
    // roster on any page, sort or search.
    expect(members.contactGroupMembersKeys.list(params)[0]).toEqual(
      members.contactGroupMembersKeys.all[0],
    );
  });

  it("keys a membership detail by (group, contact)", () => {
    expect(members.contactGroupMembersKeys.detail(GROUP_ID, CONTACT_ID)).toEqual([
      "contact-group-members",
      "detail",
      GROUP_ID,
      CONTACT_ID,
    ]);
  });
});
