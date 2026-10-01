import { api } from "@/lib/api/client";
import type {
  AudioAssetResponse,
  AudioAssetStatus,
  AudioAssetUploadForm,
  UpdateAudioAssetPayload,
} from "@/lib/api/contracts";
import { sendVoid, unwrap } from "@/lib/api/transport";
import type { ApiResponse, PaginationMetadata } from "@/lib/api/types";

/**
 * /api/v1/audio-assets — VERIFIED (F3) against `AudioAssetController` and
 * `AudioAssetService`.
 *
 * Seven endpoints. There is **no download, preview, playback or media-URL
 * endpoint**, and `AudioAssetResponse` has no URL field. `MediaUriResolver`
 * exists but is server-side only: it turns the stored `storageReference` into a
 * FreeSWITCH filesystem path for the telephony engine. So a browser cannot play
 * an audio asset from this API, and F3 does not pretend otherwise — there is no
 * player, no `<audio>` element, and no "open in new tab". See the F3 document §8.
 *
 * ## There is only ONE creation path in the UI
 *
 * `POST /upload` (multipart). `POST /api/v1/audio-assets` (metadata
 * registration) also exists and F3 still calls nothing on it: its DTO makes the
 * client author `fileName`, `contentType`, `fileSize` and optionally a SHA-256
 * `checksum` and a `storageReference`, none of which a user can produce or
 * verify, and it registers an asset with no stored bytes. F3 removed the form
 * that used it and the API function that called it.
 *
 * ## List parameters, all verified
 *
 *  - `page` 0-based, `size` server-clamped to 1..100 (`MAX_PAGE_SIZE` L50).
 *  - `sort` whitelist `name, fileName, createdAt, updatedAt, status`
 *    (`SORTABLE_FIELDS` L47-48), default `createdAt` **DESC**. `buildPageable`
 *    (L348-356) reads `sort[0]` as the field and honours `sort[1]` only when it
 *    equals `asc` — anything else silently means DESC, so the client always
 *    sends an explicit direction.
 *  - `status` is an `AudioAssetStatus` name; an unknown value is a **400**
 *    `VALIDATION_ERROR` ("Unknown status filter: …", L327-336). `parseEnum`
 *    normalises case and `-`→`_`, but the client sends the exact enum name.
 *  - `search` is a case-insensitive contains over **`name` AND `fileName`**
 *    (`AudioAssetSpecifications.search`) — NOT `description`, which the previous
 *    comment in this file claimed.
 *  - The response DOES carry real `PaginationMetadata` (`ResponseFactory.page`,
 *    L117), so the page object is passed through and never fabricated.
 *
 * ## Soft delete
 *
 * `DELETE` stamps `deletedAt`/`deletedBy` and returns a bare **204**. The row
 * and its call history remain; campaign references to it fail activation
 * afterwards. A second delete of the same id is a 404, because the lookup is
 * `findByIdAndDeletedAtIsNull`.
 */
export const AUDIO_ASSET_SORTABLE_FIELDS = [
  "name",
  "fileName",
  "createdAt",
  "updatedAt",
  "status",
] as const;

export type AudioAssetSortField = (typeof AUDIO_ASSET_SORTABLE_FIELDS)[number];

export const AUDIO_ASSET_STATUSES = [
  "PENDING_APPROVAL",
  "APPROVED",
  "REJECTED",
] as const;

export interface AudioAssetListParams {
  page: number;
  size: number;
  sortField: AudioAssetSortField;
  sortDirection: "asc" | "desc";
  status?: AudioAssetStatus;
  search?: string;
}

/** Stable query-key factory. `all` is a prefix of every other key, so one
 *  invalidation clears the list and any open detail view. */
export const audioAssetsKeys = {
  all: ["audio-assets"] as const,
  list: (params: AudioAssetListParams) =>
    ["audio-assets", "list", params] as const,
  detail: (assetId: string) => ["audio-assets", "detail", assetId] as const,
};

export interface AudioAssetPage {
  items: AudioAssetResponse[];
  pagination: PaginationMetadata;
}

function buildSort(params: AudioAssetListParams): string {
  return `${params.sortField},${params.sortDirection}`;
}

export async function getAudioAssets(
  params: AudioAssetListParams,
): Promise<AudioAssetPage> {
  const { data } = await api.get<ApiResponse<AudioAssetResponse[]>>(
    "/audio-assets",
    {
      params: {
        page: params.page,
        size: params.size,
        sort: buildSort(params),
        status: params.status || undefined,
        search: params.search || undefined,
      },
    },
  );
  return {
    items: data.data ?? [],
    pagination:
      data.pagination ??
      // F3: this endpoint genuinely sends PaginationMetadata, so a missing
      // block means a malformed 200. The F1/F2-era code returned
      // `totalElements: 0` here, which made a real list render as "0 assets"
      // with working pagination controls. F3 reports the page it asked for and
      // the number of rows actually received, which is honest and degrades
      // visibly rather than silently.
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

export function getAudioAsset(assetId: string): Promise<AudioAssetResponse> {
  return unwrap(
    api.get<ApiResponse<AudioAssetResponse>>(`/audio-assets/${assetId}`),
  );
}

/**
 * POST /api/v1/audio-assets/upload — the creation path.
 *
 * VERIFIED (`AudioAssetController.upload` L86-100): a `multipart/form-data`
 * request with `@RequestParam` parts `name` (required), `description` (optional)
 * and `file` (required). The client supplies those three parts and nothing else;
 * `fileName`, `contentType`, `fileSize`, `durationSeconds`, `checksum` and
 * `storageReference` are all derived server-side.
 *
 * The asset is always created in `PENDING_APPROVAL` (`AudioAssetService.upload`
 * L213) — **upload never approves**, so the user is told they will need approval
 * rather than being surprised by a pending row.
 *
 * `Content-Type` is set to `multipart/form-data` WITHOUT a boundary on purpose:
 * the shared axios instance defaults to JSON, and the browser adapter must be
 * the one that appends the boundary. Hand-writing a boundary here produces a
 * body the server cannot parse.
 */
export function uploadAudioAsset(
  form: AudioAssetUploadForm,
): Promise<AudioAssetResponse> {
  const body = new FormData();
  body.append("name", form.name);
  if (form.description) {
    body.append("description", form.description);
  }
  body.append("file", form.file);

  return unwrap(
    api.post<ApiResponse<AudioAssetResponse>>("/audio-assets/upload", body, {
      headers: { "Content-Type": "multipart/form-data" },
    }),
  );
}

/** PUT /api/v1/audio-assets/{id} — metadata only: name and description. */
export function updateAudioAsset(
  assetId: string,
  payload: UpdateAudioAssetPayload,
): Promise<AudioAssetResponse> {
  return unwrap(
    api.put<ApiResponse<AudioAssetResponse>>(
      `/audio-assets/${assetId}`,
      payload,
    ),
  );
}

/** DELETE /api/v1/audio-assets/{id} — soft delete, bare 204. */
export function deleteAudioAsset(assetId: string): Promise<void> {
  return sendVoid(api.delete(`/audio-assets/${assetId}`));
}

/** PATCH /api/v1/audio-assets/{id}/approve — `AUDIO_APPROVE`.
 *
 * VERIFIED transition rule (`AudioAssetService.transition` L265-277): the ONLY
 * refused case is a no-op, which is a **409** "Audio asset is already APPROVED."
 * Any other target state succeeds, including REJECTED → APPROVED. The shared
 * model in `lib/domain/approval.ts` records this. */
export function approveAudioAsset(
  assetId: string,
): Promise<AudioAssetResponse> {
  return unwrap(
    api.patch<ApiResponse<AudioAssetResponse>>(
      `/audio-assets/${assetId}/approve`,
    ),
  );
}

/** PATCH /api/v1/audio-assets/{id}/reject — `AUDIO_APPROVE`.
 *  409 only when already REJECTED. */
export function rejectAudioAsset(
  assetId: string,
): Promise<AudioAssetResponse> {
  return unwrap(
    api.patch<ApiResponse<AudioAssetResponse>>(
      `/audio-assets/${assetId}/reject`,
    ),
  );
}
