import type { ApiResponse, PaginationMetadata } from "@/lib/api/types";

/**
 * Envelope handling for the backend's uniform `ApiResponse<T>` success body.
 *
 * VERIFIED: `common/api/response/ApiResponse.java:5-6` declares
 *   record ApiResponse<T>(boolean success, T data, String message,
 *                         PaginationMetadata pagination, ResponseMetadata meta)
 * annotated `@JsonInclude(NON_NULL)`, so absent blocks are omitted entirely
 * rather than sent as null. `ResponseFactory.ok(list)` therefore leaves
 * `pagination` undefined for genuinely unpaginated collections.
 *
 * This module is the ONLY place that knows the envelope exists. Domain services
 * call it; components never see `ApiResponse<...>`.
 */

/** Extracts `data` from a success envelope.
 *
 * Does NOT throw on transport failure: an axios rejection propagates unchanged
 * so the response interceptor's refresh logic still sees it, and the caller
 * normalises it through `toApiError`. (The previous version of this helper
 * lived in `auth.ts` and its doc comment incorrectly claimed it threw
 * `ApiError`; it never did.)
 */
export async function unwrap<T>(
  request: Promise<{ data: ApiResponse<T> }>,
): Promise<T> {
  const { data } = await request;
  return data.data as T;
}

/** Extracts `data` plus `pagination` for a genuinely paginated list endpoint.
 *
 * Only use this where the backend actually attaches `PaginationMetadata` via
 * `ResponseFactory.page(...)`. For the endpoints that do not (executions, call
 * attempts, queue members, agent endpoints, waiting calls, IVR trees) the
 * backend sends no pagination at all and the service must return a plain array
 * rather than inventing a page object.
 */
export async function unwrapPage<T>(
  request: Promise<{ data: ApiResponse<T[]> }>,
): Promise<{ items: T[]; pagination: PaginationMetadata }> {
  const { data } = await request;
  return { items: data.data ?? [], pagination: data.pagination as PaginationMetadata };
}

/** Reads a binary download body (contact export), bypassing the envelope.
 *
 * VERIFIED: GET /api/v1/contact-groups/{id}/contacts/export returns
 * `ResponseEntity<byte[]>` with a `Content-Disposition` filename — it is not an
 * `ApiResponse` and must not be unwrapped.
 */
export function unwrapBlob(request: Promise<{ data: Blob }>): Promise<Blob> {
  return request.then((response) => response.data);
}

/** A raw file download plus the server's own filename, when it supplies one. */
export interface Download {
  blob: Blob;
  /** From `Content-Disposition`; `null` when the header is absent or unparsable. */
  filename: string | null;
}

/** Extracts a raw download body AND the server-chosen filename.
 *
 * VERIFIED: `GET /api/v1/contact-groups/{id}/contacts/export` sets
 * `Content-Disposition: attachment; filename="…"` (ContactGroupController
 * L400-409). The backend already picks a good name, so the client should use it
 * instead of inventing `contacts-<uuid>.csv` — a UUID in a downloaded filename
 * is noise the user has to look at.
 *
 * Only the filename is parsed, and only the last path segment is taken, so a
 * server-supplied name can never redirect the download or escape the intended
 * directory. Anything unexpected yields `null` and the caller falls back.
 */
export function unwrapDownload(
  request: Promise<{ data: Blob; headers: Record<string, string> }>,
): Promise<Download> {
  return request.then((response) => ({
    blob: response.data,
    filename: parseContentDispositionFilename(response.headers?.["content-disposition"]),
  }));
}

/** Extracts `filename="…"` from a Content-Disposition header. */
function parseContentDispositionFilename(
  header: string | undefined,
): string | null {
  if (!header) return null;
  const match = /filename="?([^";]+)"?/i.exec(header);
  if (!match) return null;
  const name = match[1].trim();
  if (name.length === 0) return null;
  // Take the basename only: a header is untrusted input even when it comes from
  // our own backend, and `..` or a path separator has no place in a download name.
  const base = name.split(/[\\/]/).pop();
  return base && base.length > 0 ? base : null;
}

/** Sends a request that returns a bare HTTP 204 with no body.
 *
 * Used by every DELETE in the API surface, which returns `ResponseEntity<Void>`.
 * There is nothing to unwrap, and calling `unwrap` on it would yield undefined.
 */
export async function sendVoid(request: Promise<unknown>): Promise<void> {
  await request;
}
