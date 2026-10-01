import { api } from "@/lib/api/client";
import type {
  AuthenticatedUserResponse,
  ChangePasswordRequest,
  LoginRequest,
  LoginResponse,
} from "@/lib/api/contracts";
import type { ApiResponse } from "@/lib/api/types";
import { sendVoid, unwrap } from "@/lib/api/transport";

/**
 * /api/v1/auth contract, verified against security/AuthController.java.
 *
 * Authentication architecture (PRESERVED by F1 — it already matched the
 * backend and is not changed here):
 *   - access token  → JSON body, held in memory only (lib/auth/token-store.ts)
 *   - refresh token → HttpOnly, SameSite=Strict, Secure cookie named `obd_rt`,
 *                     scoped to path /api/v1/auth. The raw value NEVER enters a
 *                     JSON response (AuthController L66-70, L87-98), so no
 *                     client code can read, store or forward it.
 *   - /auth/logout is permitAll so an expired access token can still end the
 *     cookie-carried refresh session (SecurityConfig L52-53).
 *
 * Envelope unwrapping lives in ./transport; this module only describes auth.
 */

export function login(payload: LoginRequest): Promise<LoginResponse> {
  return unwrap(api.post<ApiResponse<LoginResponse>>("/auth/login", payload));
}

export function fetchMe(): Promise<AuthenticatedUserResponse> {
  return unwrap(api.get<ApiResponse<AuthenticatedUserResponse>>("/auth/me"));
}

export function changePassword(
  payload: ChangePasswordRequest,
): Promise<void> {
  return sendVoid(
    api.post<ApiResponse<void>>("/auth/change-password", payload),
  );
}

export function logout(): Promise<void> {
  return sendVoid(api.post<ApiResponse<void>>("/auth/logout"));
}

export function logoutAll(): Promise<void> {
  return sendVoid(api.post<ApiResponse<void>>("/auth/logout-all"));
}
