/**
 * Authentication token store — the ONLY place the access token lives.
 *
 * The refresh token is NEVER handled by this application. The backend sets
 * it as an HttpOnly cookie (`obd_rt`) that the browser attaches automatically
 * to /api/v1/auth/* requests and clears on logout; client script cannot read
 * or persist it by design. See src/lib/api/client.ts for the cookie-driven
 * silent refresh flow.
 *
 * The access token is kept in memory only (never persisted). After a page
 * reload the HttpOnly cookie silently mints a fresh access token via
 * POST /api/v1/auth/refresh.
 */

/** Pre-cookie interim builds persisted the refresh credential here; purge it. */
const LEGACY_REFRESH_TOKEN_STORAGE_KEY = "obd.refresh-token";

let accessToken: string | null = null;

if (typeof window !== "undefined") {
  try {
    window.localStorage.removeItem(LEGACY_REFRESH_TOKEN_STORAGE_KEY);
  } catch {
    // Storage unavailable — nothing to purge.
  }
}

export function getAccessToken(): string | null {
  return accessToken;
}

/** Stores the freshly issued access token (login or silent refresh). */
export function setSession(session: { accessToken: string }): void {
  accessToken = session.accessToken;
}

export function clearSession(): void {
  accessToken = null;
}
