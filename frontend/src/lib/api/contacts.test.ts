import { beforeEach, describe, expect, it, vi } from "vitest";

import type { ApiResponse } from "@/lib/api/types";

/**
 * F2 — the `/api/v1/contact-groups/{groupId}/contacts` request contract.
 *
 * Every assertion here is about the SHAPE OF THE REQUEST or the handling of a
 * response body — the boundary a mock can legitimately pin. It is NOT evidence
 * that the backend behaves this way; that comes from reading
 * `ContactGroupController` and `ContactGroupService`. See the F2 document §3.
 *
 * The API client is mocked rather than intercepted, so these tests assert what
 * the service SENDS, with no network, no axios adapter and no jsdom.
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

const {
  CONTACT_SORTABLE_FIELDS,
  contactsKeys,
  createContact,
  deleteContact,
  exportContacts,
  getContact,
  getContacts,
  importContacts,
  updateContact,
} = await import("@/lib/api/contacts");

const GROUP_ID = "g-1";
const CONTACT_ID = "c-1";

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

describe("getContacts", () => {
  it("nests the contacts resource under its group and serialises page/size/sort", async () => {
    get.mockResolvedValue(envelope([{ id: CONTACT_ID }], PAGE));

    await getContacts({
      contactGroupId: GROUP_ID,
      page: 2,
      size: 50,
      sortField: "phoneNumber",
      sortDirection: "desc",
    });

    // VERIFIED: there is NO top-level /api/v1/contacts — a contact is a
    // tenant-level identity reachable only through a group route.
    expect(get).toHaveBeenCalledWith(`/contact-groups/${GROUP_ID}/contacts`, {
      params: { page: 2, size: 50, sort: "phoneNumber,desc" },
    });
  });

  it("sends no search value when the query is empty rather than an empty string", async () => {
    get.mockResolvedValue(envelope([], PAGE));
    await getContacts({
      contactGroupId: GROUP_ID,
      page: 0,
      size: 20,
      sortField: "firstName",
      sortDirection: "asc",
      search: "",
    });
    // `search: params.search || undefined` — the key is present but undefined, so
    // axios omits it from the query string. An empty `search=` would reach
    // `ContactSpecifications.search`, which already treats blank as no filter, so
    // either is safe — but omitting it is what keeps the URL clean.
    expect(get.mock.calls[0]?.[1]?.params?.search).toBeUndefined();
  });

  it("forwards a non-empty search verbatim", async () => {
    get.mockResolvedValue(envelope([], PAGE));
    await getContacts({
      contactGroupId: GROUP_ID,
      page: 0,
      size: 20,
      sortField: "firstName",
      sortDirection: "asc",
      search: "pooja",
    });
    expect(get.mock.calls[0]?.[1]?.params?.search).toBe("pooja");
  });

  it("returns the items and the server pagination untouched", async () => {
    const page = { ...PAGE, totalElements: 137, totalPages: 7, hasNext: true, page: 0 };
    get.mockResolvedValue(envelope([{ id: "a" }, { id: "b" }], page));

    const result = await getContacts({
      contactGroupId: GROUP_ID,
      page: 0,
      size: 20,
      sortField: "firstName",
      sortDirection: "asc",
    });

    // The F1 rule: never fabricate a page. A real PaginationMetadata is passed
    // through exactly as received.
    expect(result.pagination).toEqual(page);
    expect(result.items).toHaveLength(2);
  });

  it("defaults to an empty list when the envelope carries no data", async () => {
    get.mockResolvedValue({ data: { success: true }, status: 200, headers: {} });
    const result = await getContacts({
      contactGroupId: GROUP_ID,
      page: 0,
      size: 20,
      sortField: "firstName",
      sortDirection: "asc",
    });
    expect(result.items).toEqual([]);
  });
});

describe("sort whitelist", () => {
  it("is exactly the three fields ContactSpecifications accepts", () => {
    // VERIFIED: `ContactGroupService` builds its pageable against
    // CONTACT_SORTABLE_FIELDS; anything else is rejected or ignored server-side.
    // `lastName` and `email` are NOT sortable, and `email` is not searched.
    expect([...CONTACT_SORTABLE_FIELDS]).toEqual([
      "firstName",
      "phoneNumber",
      "createdAt",
    ]);
  });
});

describe("getContact", () => {
  it("requests the group-scoped contact path and unwraps the envelope", async () => {
    get.mockResolvedValue(envelope({ id: CONTACT_ID, phoneNumber: "+918012345678" }));

    const contact = await getContact(GROUP_ID, CONTACT_ID);

    expect(get).toHaveBeenCalledWith(
      `/contact-groups/${GROUP_ID}/contacts/${CONTACT_ID}`,
    );
    // The service returns the CONTACT, not the envelope — components never see
    // `ApiResponse`.
    expect(contact).toEqual({ id: CONTACT_ID, phoneNumber: "+918012345678" });
  });
});

describe("createContact", () => {
  it("POSTs to the group-scoped collection and returns the created contact", async () => {
    post.mockResolvedValue(envelope({ id: CONTACT_ID }, PAGE));
    const payload = { phoneNumber: "+918012345678", firstName: "John" };

    const created = await createContact(GROUP_ID, payload);

    expect(post).toHaveBeenCalledWith(
      `/contact-groups/${GROUP_ID}/contacts`,
      payload,
    );
    expect(created).toEqual({ id: CONTACT_ID });
  });
});

describe("updateContact", () => {
  it("uses PUT — the backend declares @PutMapping, not PATCH", async () => {
    put.mockResolvedValue(envelope({ id: CONTACT_ID }));
    const payload = { phoneNumber: "+918012345678", attributes: { city: "Pune" } };

    await updateContact(GROUP_ID, CONTACT_ID, payload);

    expect(put).toHaveBeenCalledWith(
      `/contact-groups/${GROUP_ID}/contacts/${CONTACT_ID}`,
      payload,
    );
    // The payload is forwarded verbatim: the schema has already decided what to
    // omit, and the service must not second-guess it.
    expect(put.mock.calls[0]?.[1]).toBe(payload);
  });
});

describe("deleteContact", () => {
  it("DELETEs the identity path and tolerates the bare 204", async () => {
    del.mockResolvedValue({ status: 204, data: undefined });
    // VERIFIED: `deleteGroup`/`deleteContact` return `ResponseEntity<Void>`, so
    // there is no body to unwrap. `sendVoid` is the correct helper.
    await expect(deleteContact(GROUP_ID, CONTACT_ID)).resolves.toBeUndefined();
    expect(del).toHaveBeenCalledWith(
      `/contact-groups/${GROUP_ID}/contacts/${CONTACT_ID}`,
    );
  });
});

describe("importContacts", () => {
  it("POSTs multipart with the field name the controller declares", async () => {
    post.mockResolvedValue(envelope({ totalRows: 0, created: 0 }));
    const file = new File(["phoneNumber\n+918012345678"], "contacts.csv", {
      type: "text/csv",
    });

    await importContacts(GROUP_ID, file);

    const [url, body, config] = post.mock.calls[0] ?? [];
    expect(url).toBe(`/contact-groups/${GROUP_ID}/contacts/import`);
    // VERIFIED: `@RequestParam("file") MultipartFile file`.
    expect(body).toBeInstanceOf(FormData);
    expect((body as FormData).get("file")).toBe(file);
    expect(config).toMatchObject({
      headers: { "Content-Type": "multipart/form-data" },
    });
  });
});

describe("exportContacts", () => {
  it("requests a raw download, not the JSON envelope", async () => {
    const blob = new Blob(["phoneNumber\n+918012345678"], {
      type: "text/csv",
    });
    get.mockResolvedValue({
      data: blob,
      status: 200,
      headers: { "content-disposition": 'attachment; filename="september.csv"' },
    });

    const download = await exportContacts(GROUP_ID, "csv");

    expect(get).toHaveBeenCalledWith(`/contact-groups/${GROUP_ID}/contacts/export`, {
      params: { format: "csv" },
      responseType: "blob",
    });
    expect(download.blob).toBe(blob);
    // The backend already chose a filename; the client does not invent one.
    expect(download.filename).toBe("september.csv");
  });

  it("reports a null filename when the header is absent, so the caller can fall back", async () => {
    get.mockResolvedValue({
      data: new Blob(["x"]),
      status: 200,
      headers: {},
    });
    const download = await exportContacts(GROUP_ID, "xlsx");
    expect(download.filename).toBeNull();
  });
});

describe("contactsKeys", () => {
  it("gives `forGroup` a prefix that matches every list key of that group", async () => {
    get.mockResolvedValue(envelope([], PAGE));
    const params = {
      contactGroupId: GROUP_ID,
      page: 0,
      size: 20,
      sortField: "firstName" as const,
      sortDirection: "asc" as const,
    };
    await getContacts(params);

    // F2: mutations invalidate `forGroup`, which must be a PREFIX of the list
    // key so a create performed on page 3 with a search active still refreshes
    // the visible list. Rebuilding one exact key (the F0 behaviour) did not.
    expect(contactsKeys.list(params).slice(0, 2)).toEqual(
      contactsKeys.forGroup(GROUP_ID).slice(0, 2),
    );
  });

  it("keeps a detail key per (group, contact) — the contact id alone is not unique to a group", () => {
    // VERIFIED: contacts are tenant-level identities, so the same contact can be
    // a member of several groups and must be addressable in each.
    expect(contactsKeys.detail(GROUP_ID, CONTACT_ID)).toEqual([
      "contacts",
      "detail",
      GROUP_ID,
      CONTACT_ID,
    ]);
  });
});
