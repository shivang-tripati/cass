import { beforeEach, describe, expect, it, vi } from "vitest";

import type { ApiResponse } from "@/lib/api/types";
import type { CreateTtsTemplatePayload } from "@/lib/api/contracts";

/**
 * F3 — the `/api/v1/audio-assets` and `/api/v1/tts-templates` request contracts.
 *
 * Every assertion is about the SHAPE OF THE REQUEST or the handling of a
 * response body. It is not evidence that the backend behaves this way; that
 * comes from reading `AudioAssetController` / `AudioAssetService` and
 * `TtsTemplateController` / `TtsTemplateService`. See the F3 document §2.
 *
 * The API client is mocked, so these tests assert what the services SEND with no
 * network, no axios adapter and no jsdom.
 */

const get = vi.fn();
const post = vi.fn();
const put = vi.fn();
const patch = vi.fn();
const del = vi.fn();

vi.mock("@/lib/api/client", () => ({
  api: {
    get: (...args: unknown[]) => get(...args),
    post: (...args: unknown[]) => post(...args),
    put: (...args: unknown[]) => put(...args),
    patch: (...args: unknown[]) => patch(...args),
    delete: (...args: unknown[]) => del(...args),
  },
}));

const audio = await import("@/lib/api/audio-assets");
const tts = await import("@/lib/api/tts-templates");

const ASSET_ID = "a-1";
const TEMPLATE_ID = "t-1";

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
  totalElements: 3,
  totalPages: 1,
  hasNext: false,
  hasPrevious: false,
};

beforeEach(() => {
  get.mockReset();
  post.mockReset();
  put.mockReset();
  patch.mockReset();
  del.mockReset();
});

/* ------------------------------- audio: list ------------------------------ */

describe("getAudioAssets", () => {
  it("requests the collection with the whitelisted sort serialised", async () => {
    get.mockResolvedValue(envelope([], PAGE));
    await audio.getAudioAssets({
      page: 2,
      size: 50,
      sortField: "fileName",
      sortDirection: "asc",
    });
    // VERIFIED `AudioAssetService.SORTABLE_FIELDS` = name, fileName, createdAt,
    // updatedAt, status. `buildPageable` reads sort[0] as the field and honours
    // sort[1] only when it is "asc", so the direction is always explicit.
    expect(get).toHaveBeenCalledWith("/audio-assets", {
      params: { page: 2, size: 50, sort: "fileName,asc" },
    });
  });

  it("sends `status` and `search` when set, and neither when not", async () => {
    get.mockResolvedValue(envelope([], PAGE));
    const base = {
      page: 0,
      size: 20,
      sortField: "createdAt" as const,
      sortDirection: "desc" as const,
    };
    await audio.getAudioAssets({ ...base, status: "APPROVED", search: "welcome" });
    expect(get.mock.calls[0]?.[1]?.params?.status).toBe("APPROVED");
    expect(get.mock.calls[0]?.[1]?.params?.search).toBe("welcome");

    await audio.getAudioAssets(base);
    expect(get.mock.calls[1]?.[1]?.params?.status).toBeUndefined();
    expect(get.mock.calls[1]?.[1]?.params?.search).toBeUndefined();
  });

  it("passes the server pagination through instead of fabricating one", async () => {
    // F3 FIX: the pre-F3 code returned `totalElements: 0` when the envelope had
    // no pagination block, which made a populated list render as "0 recordings"
    // behind working pagination controls. The endpoint genuinely sends
    // PaginationMetadata, so it is passed straight through.
    const page = { ...PAGE, totalElements: 137, totalPages: 7, hasNext: true };
    get.mockResolvedValue(envelope([{ id: "x" }], page));
    const result = await audio.getAudioAssets({
      page: 0,
      size: 20,
      sortField: "createdAt",
      sortDirection: "desc",
    });
    expect(result.pagination).toEqual(page);
  });

  it("falls back to the rows it actually received when pagination is absent", async () => {
    get.mockResolvedValue(envelope([{ id: "a" }, { id: "b" }]));
    const result = await audio.getAudioAssets({
      page: 0,
      size: 20,
      sortField: "createdAt",
      sortDirection: "desc",
    });
    expect(result.pagination.totalElements).toBe(2);
    expect(result.pagination.totalPages).toBe(1);
  });

  it("returns an empty list for an envelope with no data", async () => {
    get.mockResolvedValue({ data: { success: true }, status: 200, headers: {} });
    const result = await audio.getAudioAssets({
      page: 0,
      size: 20,
      sortField: "createdAt",
      sortDirection: "desc",
    });
    expect(result.items).toEqual([]);
  });
});

describe("the audio sort and status whitelists", () => {
  it("is exactly the five sortable fields", () => {
    // VERIFIED `AudioAssetService.SORTABLE_FIELDS`. Note there is no `scope`
    // (audio has none) and no `fileSize`.
    expect([...audio.AUDIO_ASSET_SORTABLE_FIELDS]).toEqual([
      "name",
      "fileName",
      "createdAt",
      "updatedAt",
      "status",
    ]);
  });

  it("offers exactly the three status names the endpoint accepts", () => {
    // An unrecognised value is a 400 "Unknown status filter: …", so the client
    // only ever sends these.
    expect([...audio.AUDIO_ASSET_STATUSES]).toEqual([
      "PENDING_APPROVAL",
      "APPROVED",
      "REJECTED",
    ]);
  });
});

/* ------------------------------ audio: writes ----------------------------- */

describe("uploadAudioAsset", () => {
  it("POSTs multipart with the exact @RequestParam names", async () => {
    post.mockResolvedValue(envelope({ id: ASSET_ID, status: "PENDING_APPROVAL" }));
    const file = new File(["RIFF....WAVEfmt "], "welcome.wav", {
      type: "audio/wav",
    });

    await audio.uploadAudioAsset({ name: "Welcome", file });

    const [url, body, config] = post.mock.calls[0] ?? [];
    expect(url).toBe("/audio-assets/upload");
    // VERIFIED AudioAssetController L88-90: name (required),
    // description (optional), file (required).
    const form = body as FormData;
    expect(form).toBeInstanceOf(FormData);
    expect(form.get("name")).toBe("Welcome");
    expect(form.get("file")).toBe(file);
    // The content type is set WITHOUT a boundary: the browser adapter must
    // append it, and a hand-written boundary produces an unparsable body.
    expect(config).toEqual({
      headers: { "Content-Type": "multipart/form-data" },
    });
  });

  it("omits `description` entirely when it is blank", async () => {
    post.mockResolvedValue(envelope({ id: ASSET_ID }));
    const file = new File(["x"], "a.mp3", { type: "audio/mpeg" });
    await audio.uploadAudioAsset({ name: "A", description: null, file });
    const form = post.mock.calls[0]?.[1] as FormData;
    expect(form.has("description")).toBe(false);
  });

  it("sends NO derived technical field", async () => {
    // The whole point of F3: contentType, fileSize, durationSeconds, checksum
    // and storageReference are all derived by AudioAssetService.upload L201-212.
    // A client that authored them would be inventing data.
    post.mockResolvedValue(envelope({ id: ASSET_ID }));
    const file = new File(["x"], "a.wav", { type: "audio/wav" });
    await audio.uploadAudioAsset({ name: "A", description: "d", file });
    const form = post.mock.calls[0]?.[1] as FormData;
    for (const derived of [
      "fileName",
      "contentType",
      "fileSize",
      "durationSeconds",
      "checksum",
      "storageReference",
      "tenantId",
      "status",
    ]) {
      expect(form.has(derived), `must not send ${derived}`).toBe(false);
    }
  });

  it("does not expose the metadata-registration endpoint at all", async () => {
    // F3 REMOVED `createAudioAsset`. `POST /api/v1/audio-assets` still exists
    // on the backend for externally provisioned assets, but its DTO makes the
    // client author a SHA-256 and a storage reference, so it gets no UI.
    const exports = audio as unknown as Record<string, unknown>;
    expect(exports.createAudioAsset).toBeUndefined();
  });
});

describe("updateAudioAsset", () => {
  it("uses PUT and forwards only the two updatable fields", async () => {
    put.mockResolvedValue(envelope({ id: ASSET_ID }));
    const payload = { name: "Renamed", description: "Notes" };
    await audio.updateAudioAsset(ASSET_ID, payload);
    expect(put).toHaveBeenCalledWith(`/audio-assets/${ASSET_ID}`, payload);
    expect(Object.keys(payload).sort()).toEqual(["description", "name"]);
  });
});

describe("deleteAudioAsset", () => {
  it("DELETEs and tolerates the bare 204", async () => {
    // VERIFIED: `ResponseEntity<Void>` — there is no body to unwrap.
    del.mockResolvedValue({ status: 204, data: undefined });
    await expect(audio.deleteAudioAsset(ASSET_ID)).resolves.toBeUndefined();
    expect(del).toHaveBeenCalledWith(`/audio-assets/${ASSET_ID}`);
  });
});

describe("audio approve / reject", () => {
  it("uses PATCH, not POST or PUT", async () => {
    // VERIFIED AudioAssetController L162 and L178: `@PatchMapping`.
    patch.mockResolvedValue(envelope({ id: ASSET_ID, status: "APPROVED" }));
    await audio.approveAudioAsset(ASSET_ID);
    expect(patch).toHaveBeenCalledWith(`/audio-assets/${ASSET_ID}/approve`);
    expect(post).not.toHaveBeenCalled();
    expect(put).not.toHaveBeenCalled();

    patch.mockResolvedValue(envelope({ id: ASSET_ID, status: "REJECTED" }));
    await audio.rejectAudioAsset(ASSET_ID);
    expect(patch).toHaveBeenCalledWith(`/audio-assets/${ASSET_ID}/reject`);
  });

  it("returns the updated asset, so the caller can read the new status", async () => {
    patch.mockResolvedValue(
      envelope({ id: ASSET_ID, status: "APPROVED" as const }),
    );
    const result = await audio.approveAudioAsset(ASSET_ID);
    expect(result.status).toBe("APPROVED");
  });

  it("sends no request body", async () => {
    patch.mockResolvedValue(envelope({ id: ASSET_ID }));
    await audio.approveAudioAsset(ASSET_ID);
    expect(patch.mock.calls[0]).toHaveLength(1);
  });
});

describe("audio query keys", () => {
  it("roots every query under one prefix so one invalidation clears all", () => {
    expect(audio.audioAssetsKeys.all).toEqual(["audio-assets"]);
    expect(audio.audioAssetsKeys.detail(ASSET_ID)).toEqual([
      "audio-assets",
      "detail",
      ASSET_ID,
    ]);
    expect(
      audio.audioAssetsKeys.list({
        page: 0,
        size: 20,
        sortField: "createdAt",
        sortDirection: "desc",
      })[0],
    ).toBe("audio-assets");
  });
});

/* -------------------------------- tts: list ------------------------------- */

describe("getTtsTemplates", () => {
  it("requests the collection with the whitelisted sort", async () => {
    get.mockResolvedValue(envelope([], PAGE));
    await tts.getTtsTemplates({
      page: 0,
      size: 20,
      sortField: "status",
      sortDirection: "asc",
    });
    // VERIFIED `TtsTemplateService.SORTABLE_FIELDS` = name, createdAt,
    // updatedAt, status — there is NO `scope` and no `templateText`, which is
    // why the table marks those columns unsortable and offers no scope filter.
    expect(get).toHaveBeenCalledWith("/tts-templates", {
      params: { page: 0, size: 20, sort: "status,asc" },
    });
  });

  it("never sends a `scope` parameter, because none exists", async () => {
    get.mockResolvedValue(envelope([], PAGE));
    await tts.getTtsTemplates({
      page: 0,
      size: 20,
      sortField: "createdAt",
      sortDirection: "desc",
      status: "APPROVED",
      search: "hello",
    });
    const params = get.mock.calls[0]?.[1]?.params as Record<string, unknown>;
    expect(params.scope).toBeUndefined();
    expect(Object.keys(params).sort()).toEqual([
      "page",
      "search",
      "size",
      "sort",
      "status",
    ]);
  });

  it("passes the server pagination through", async () => {
    const page = { ...PAGE, totalElements: 88, totalPages: 5 };
    get.mockResolvedValue(envelope([{ id: TEMPLATE_ID }], page));
    const result = await tts.getTtsTemplates({
      page: 0,
      size: 20,
      sortField: "createdAt",
      sortDirection: "desc",
    });
    expect(result.pagination).toEqual(page);
  });
});

describe("the tts sort whitelist", () => {
  it("is exactly name/createdAt/updatedAt/status", () => {
    expect([...tts.TTS_TEMPLATE_SORTABLE_FIELDS]).toEqual([
      "name",
      "createdAt",
      "updatedAt",
      "status",
    ]);
  });
});

/* -------------------------------- tts: writes ----------------------------- */

describe("createTtsTemplate", () => {
  it("POSTs the payload to the collection and returns the created template", async () => {
    post.mockResolvedValue(
      envelope({ id: TEMPLATE_ID, scope: "TENANT", status: "PENDING_APPROVAL" }),
    );
    const payload: CreateTtsTemplatePayload = {
      name: "Greeting",
      templateText: "Hello {{firstName}}",
      variables: [{ name: "firstName", type: "STRING", required: true }],
      scope: "TENANT",
    };
    const created = await tts.createTtsTemplate(payload);
    expect(post).toHaveBeenCalledWith("/tts-templates", payload);
    expect(created.scope).toBe("TENANT");
  });

  it("forwards scope and tenantId verbatim, because they ARE the ownership model", async () => {
    post.mockResolvedValue(envelope({ id: TEMPLATE_ID }));
    const payload: CreateTtsTemplatePayload = {
      name: "System line",
      templateText: "Welcome",
      variables: [],
      scope: "TENANT",
      tenantId: "t-42",
    };
    await tts.createTtsTemplate(payload);
    expect(post.mock.calls[0]?.[1]).toBe(payload);
  });
});

describe("updateTtsTemplate", () => {
  it("uses PUT and forwards only the four updatable fields", async () => {
    put.mockResolvedValue(envelope({ id: TEMPLATE_ID }));
    const payload = {
      name: "Greeting",
      templateText: "Hi {{firstName}}",
      variables: [],
    };
    await tts.updateTtsTemplate(TEMPLATE_ID, payload);
    // VERIFIED `UpdateTtsTemplateRequest` has no `scope` and no `tenantId`:
    // scope is immutable after creation.
    expect(put).toHaveBeenCalledWith(
      `/tts-templates/${TEMPLATE_ID}`,
      payload,
    );
    expect(Object.keys(payload).sort()).toEqual([
      "name",
      "templateText",
      "variables",
    ]);
  });
});

describe("deleteTtsTemplate", () => {
  it("DELETEs and tolerates the bare 204", async () => {
    del.mockResolvedValue({ status: 204, data: undefined });
    await expect(tts.deleteTtsTemplate(TEMPLATE_ID)).resolves.toBeUndefined();
    expect(del).toHaveBeenCalledWith(`/tts-templates/${TEMPLATE_ID}`);
  });
});

describe("tts approve / reject", () => {
  it("uses PATCH and returns the updated template", async () => {
    patch.mockResolvedValue(
      envelope({ id: TEMPLATE_ID, status: "APPROVED" as const }),
    );
    const result = await tts.approveTtsTemplate(TEMPLATE_ID);
    expect(patch).toHaveBeenCalledWith(`/tts-templates/${TEMPLATE_ID}/approve`);
    expect(result.status).toBe("APPROVED");
  });

  it("uses PATCH for reject too", async () => {
    patch.mockResolvedValue(
      envelope({ id: TEMPLATE_ID, status: "REJECTED" as const }),
    );
    const result = await tts.rejectTtsTemplate(TEMPLATE_ID);
    expect(patch).toHaveBeenCalledWith(`/tts-templates/${TEMPLATE_ID}/reject`);
    expect(result.status).toBe("REJECTED");
  });
});

describe("tts query keys", () => {
  it("roots every query under one prefix", () => {
    expect(tts.ttsTemplatesKeys.all).toEqual(["tts-templates"]);
    expect(tts.ttsTemplatesKeys.detail(TEMPLATE_ID)).toEqual([
      "tts-templates",
      "detail",
      TEMPLATE_ID,
    ]);
  });
});

describe("neither module exposes an endpoint the backend lacks", () => {
  it("no audio download, preview, playback or media-URL function", () => {
    // VERIFIED: `AudioAssetController` has seven mappings and none returns
    // bytes. `MediaUriResolver` is server-side only.
    const audioExports = audio as unknown as Record<string, unknown>;
    for (const name of [
      "downloadAudioAsset",
      "getAudioAssetUrl",
      "previewAudioAsset",
      "streamAudioAsset",
    ]) {
      expect(audioExports[name], `${name} must not exist`).toBeUndefined();
    }
  });

  it("no TTS generate, synthesise, render or preview function", () => {
    // VERIFIED: the controller's tag says "Rendering/synthesis belongs to the
    // future execution layer", and the `tts` package contains no provider.
    const ttsExports = tts as unknown as Record<string, unknown>;
    for (const name of [
      "generateTts",
      "synthesiseTts",
      "renderTtsTemplate",
      "previewTtsTemplate",
    ]) {
      expect(ttsExports[name], `${name} must not exist`).toBeUndefined();
    }
  });

  it("no binary download helper, so no ApiResponse unwrap can be applied to one", () => {
    // Both domains return JSON envelopes only. F1's `unwrapDownload` /
    // `unwrapBlob` exist for the contacts export and are deliberately not used
    // here, because there is nothing binary to fetch.
    for (const exports of [
      audio as unknown as Record<string, unknown>,
      tts as unknown as Record<string, unknown>,
    ]) {
      expect(exports.blob).toBeUndefined();
      expect(exports.download).toBeUndefined();
    }
  });
});
