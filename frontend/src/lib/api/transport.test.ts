import { describe, expect, it } from "vitest";

import { sendVoid, unwrap, unwrapBlob, unwrapPage } from "@/lib/api/transport";
import type { ApiResponse } from "@/lib/api/types";

/**
 * F1 — envelope handling contract.
 *
 * `ApiResponse` is annotated `@JsonInclude(NON_NULL)`, so absent blocks are
 * omitted rather than sent as null. These tests pin that distinction, because
 * the F0 code conflated "no pagination block" with "an empty page".
 */

const pagination = {
  page: 0,
  size: 20,
  totalElements: 3,
  totalPages: 1,
  hasNext: false,
  hasPrevious: false,
};

describe("unwrap", () => {
  it("extracts data from a success envelope", async () => {
    const body: ApiResponse<{ id: string }> = {
      success: true,
      data: { id: "abc" },
    };
    await expect(unwrap(Promise.resolve({ data: body }))).resolves.toEqual({ id: "abc" });
  });

  it("propagates a rejection unchanged so the refresh interceptor still sees it", async () => {
    // The interceptor in client.ts inspects the axios error, so unwrap must not
    // swallow or re-wrap it.
    const boom = { response: { status: 401, data: { status: 401 } }, isAxiosError: true };
    await expect(unwrap(Promise.reject(boom))).rejects.toBe(boom);
  });
});

describe("unwrapPage", () => {
  it("returns items with the server pagination block", async () => {
    const body: ApiResponse<string[]> = {
      success: true,
      data: ["a", "b", "c"],
      pagination,
    };
    await expect(unwrapPage(Promise.resolve({ data: body }))).resolves.toEqual({
      items: ["a", "b", "c"],
      pagination,
    });
  });

  it("yields an empty list when data is omitted", async () => {
    const body: ApiResponse<string[]> = { success: true };
    const result = await unwrapPage(Promise.resolve({ data: body }));
    expect(result.items).toEqual([]);
  });

  it("does NOT invent pagination for an unpaginated endpoint", async () => {
    // Executions, call attempts, queue members, agent endpoints and IVR trees
    // all use ResponseFactory.ok(list), which leaves `pagination` undefined.
    // A caller must be able to observe that, not receive a fake page.
    const body: ApiResponse<string[]> = { success: true, data: ["a"] };
    const result = await unwrapPage(Promise.resolve({ data: body }));
    expect("pagination" in result && result.pagination).toBeUndefined();
  });
});

describe("unwrapBlob", () => {
  it("returns the raw body, bypassing the envelope", async () => {
    const blob = new Blob(["id,name\n"], { type: "text/csv" });
    await expect(unwrapBlob(Promise.resolve({ data: blob }))).resolves.toBe(blob);
  });
});

describe("sendVoid", () => {
  it("resolves for a 204 with no body", async () => {
    await expect(sendVoid(Promise.resolve())).resolves.toBeUndefined();
  });

  it("propagates a failure", async () => {
    await expect(sendVoid(Promise.reject(new Error("nope")))).rejects.toThrow("nope");
  });
});
