import { api } from "@/lib/api/client";
import type {
  ResellerResponse,
  ResellerSignupPayload,
  TenantResponse,
  TenantSignupPayload,
} from "@/lib/api/contracts";
import { unwrap } from "@/lib/api/auth";
import type { ApiResponse } from "@/lib/api/types";

export function signupTenant(
  payload: TenantSignupPayload,
): Promise<TenantResponse> {
  return unwrap(
    api.post<ApiResponse<TenantResponse>>("/account/signup/tenant", payload),
  );
}

export function signupReseller(
  payload: ResellerSignupPayload,
): Promise<ResellerResponse> {
  return unwrap(
    api.post<ApiResponse<ResellerResponse>>(
      "/account/signup/reseller",
      payload,
    ),
  );
}
