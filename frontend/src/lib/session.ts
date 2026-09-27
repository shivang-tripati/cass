"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { toast } from "sonner";

import {
  changePassword as changePasswordRequest,
  fetchMe,
  login as loginRequest,
  logout as logoutRequest,
  logoutAll as logoutAllRequest,
} from "@/lib/api/auth";
import { toApiError } from "@/lib/api/error";
import { clearSession, setSession } from "@/lib/auth/token-store";
import { queryClient } from "@/lib/query-client";
import type { ChangePasswordValues, SignInValues } from "@/lib/schemas/auth";

/** Cached identity of the signed-in user (GET /api/v1/auth/me). */
export const SESSION_QUERY_KEY = ["auth", "me"] as const;

/**
 * Session state for gates and account UI. On first load after a reload the
 * in-memory access token is gone but the HttpOnly refresh cookie persists;
 * the axios interceptor transparently refreshes and retries the /me call.
 */
export function useSession() {
  return useQuery({
    queryKey: SESSION_QUERY_KEY,
    queryFn: fetchMe,
    retry: false,
    staleTime: 5 * 60_000,
  });
}

/** POST /login → stores the access token, seeds the session cache. */
export function useLogin() {
  return useMutation({
    mutationFn: (values: SignInValues) => loginRequest(values),
    onSuccess: (session) => {
      setSession(session);
      void queryClient.invalidateQueries({ queryKey: SESSION_QUERY_KEY });
    },
  });
}

/**
 * Drops all local authentication state. The server side of every flow that
 * ends a session has already been called by the time this runs.
 */
export function endLocalSession(): void {
  clearSession();
  // Remove every cached query so no authenticated data survives logout.
  queryClient.clear();
}

function useEndSessionMutation(
  request: () => Promise<void>,
  successToast: string,
) {
  const router = useRouter();
  return useMutation({
    mutationFn: request,
    onSuccess: () => {
      endLocalSession();
      toast.success(successToast);
      router.replace("/sign-in");
    },
    onError: (error) => {
      // The server may be unreachable, but the local session must still go.
      endLocalSession();
      toast.error(toApiError(error).message);
      router.replace("/sign-in");
    },
  });
}

export function useLogout() {
  return useEndSessionMutation(logoutRequest, "Signed out.");
}

export function useLogoutAll() {
  return useEndSessionMutation(logoutAllRequest, "Signed out of all devices.");
}

/**
 * POST /change-password. The backend revokes ALL refresh sessions when the
 * password changes, so success intentionally ends the local session and
 * returns the user to sign-in.
 */
export function useChangePassword() {
  return useMutation({
    mutationFn: (values: ChangePasswordValues) =>
      changePasswordRequest({
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      }),
  });
}
