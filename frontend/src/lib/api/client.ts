import axios, { AxiosError, type AxiosInstance } from "axios";

import { clearSession, getAccessToken, setSession } from "@/lib/auth/token-store";
import type { LoginResponse } from "@/lib/api/contracts";
import type { ApiResponse } from "@/lib/api/types";

/**
 * Same-origin API client. All calls go through the Next.js rewrite proxy
 * (see next.config.ts) so the browser never talks cross-origin.
 *
 * `withCredentials` keeps cookie handling explicit: the backend owns the
 * HttpOnly refresh cookie (obd_rt, SameSite=Strict), which the browser
 * attaches to /api/v1/auth/* automatically and which JS can never read.
 */
export const api: AxiosInstance = axios.create({
  baseURL: "/api/v1",
  headers: { "Content-Type": "application/json" },
  timeout: 20_000,
  withCredentials: true,
});

/** Endpoints that must never trigger the refresh flow on 401. */
const PUBLIC_ENDPOINTS = new Set([
  "/auth/login",
  "/auth/refresh",
  "/account/signup/tenant",
  "/account/signup/reseller",
]);

function isPublicEndpoint(url: string | undefined): boolean {
  return url !== undefined && PUBLIC_ENDPOINTS.has(url);
}

/* Single-flight refresh: concurrent 401s share one in-flight request. */
let refreshInFlight: Promise<string> | null = null;

async function refreshAccessToken(): Promise<string> {
  // Cookie-driven: the browser supplies the HttpOnly obd_rt credential, so
  // the request carries no body. Raw axios call: must not recurse through
  // this client's interceptors.
  const { data } = await axios.post<ApiResponse<LoginResponse>>(
    "/api/v1/auth/refresh",
    null,
    { timeout: 20_000, withCredentials: true },
  );
  if (!data.data?.accessToken) {
    throw new Error("Refresh response contained no access token.");
  }
  setSession(data.data);
  return data.data.accessToken;
}

/** Requests already retried once after a refresh — never retried again. */
const retriedRequests = new WeakSet<object>();

api.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config;
    const status = error.response?.status;

    const canRefresh =
      original !== undefined &&
      status === 401 &&
      !isPublicEndpoint(original.url) &&
      !retriedRequests.has(original);

    if (!canRefresh) {
      throw error;
    }

    refreshInFlight ??= refreshAccessToken().finally(() => {
      refreshInFlight = null;
    });

    try {
      await refreshInFlight;
    } catch {
      // Refresh was attempted and failed (missing/expired/revoked cookie):
      // the session is dead.
      clearSession();
      redirectToSignIn();
      throw error;
    }

    retriedRequests.add(original);
    original.headers.Authorization = `Bearer ${getAccessToken()}`;
    return api.request(original);
  },
);

/**
 * Hard redirect used only when an authenticated session has expired
 * server-side. A full navigation also resets all in-memory state.
 */
function redirectToSignIn(): void {
  if (typeof window === "undefined") return;
  const { pathname, search } = window.location;
  if (pathname === "/sign-in") return;
  const next =
    pathname === "/" ? "" : `?next=${encodeURIComponent(pathname + search)}`;
  window.location.assign(`/sign-in${next}`);
}
