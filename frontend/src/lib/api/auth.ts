import { api } from "@/lib/api/client";
import type {
  AuthenticatedUserResponse,
  ChangePasswordRequest,
  LoginRequest,
  LoginResponse,
} from "@/lib/api/contracts";
import type { ApiResponse } from "@/lib/api/types";

/** Unwraps the ApiResponse envelope; throws ApiError on transport failures. */
export async function unwrap<T>(
  request: Promise<{ data: ApiResponse<T> }>,
): Promise<T> {
  const { data } = await request;
  return data.data as T;
}

export function login(payload: LoginRequest): Promise<LoginResponse> {
  return unwrap(api.post<ApiResponse<LoginResponse>>("/auth/login", payload));
}

export function fetchMe(): Promise<AuthenticatedUserResponse> {
  return unwrap(api.get<ApiResponse<AuthenticatedUserResponse>>("/auth/me"));
}

export function changePassword(
  payload: ChangePasswordRequest,
): Promise<void> {
  return unwrap(api.post<ApiResponse<void>>("/auth/change-password", payload));
}

export function logout(): Promise<void> {
  return unwrap(api.post<ApiResponse<void>>("/auth/logout"));
}

export function logoutAll(): Promise<void> {
  return unwrap(api.post<ApiResponse<void>>("/auth/logout-all"));
}
