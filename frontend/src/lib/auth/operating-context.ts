"use client";

import { useMemo } from "react";

import type { AuthenticatedUserResponse } from "@/lib/api/contracts";
import { SESSION_QUERY_KEY, useSession } from "@/lib/session";

/**
 * The operating context: WHICH organization this user is acting as.
 *
 * VERIFIED model, taken from the backend rather than invented:
 *
 *  - `AuthenticatedUserResponse.homeType` is `OrganizationalHomeType`, whose
 *    only constants are `TENANT` and `RESELLER`
 *    (authz/home/OrganizationalHomeType.java:3-5). A user with NO
 *    organizational home — a `SUPER_ADMIN` — therefore has `homeType === null`
 *    (V13: "SUPER_ADMIN holds NO organizational home and NO reseller/tenant
 *    membership"). `null` is how the wire says "platform scope".
 *  - `organizationId` is the id of that home: the tenant id for TENANT, the
 *    reseller id for RESELLER, and null for a platform user.
 *  - A user has at most ONE home (migrations V6/V7/V8/V11 enforce a single
 *    organizational home), so a tenant user is NOT a member of several tenants
 *    and the client must not model them as such.
 *
 * WHY THERE IS NO TENANT SELECTOR
 * ------------------------------
 * A reseller-scoped user does NOT get to pick a tenant and act inside it, and
 * the frontend does not pretend otherwise. The backend gives a reseller
 * HIERARCHY-WIDE READ: every domain list resolves the caller's own reseller and
 * returns the reseller's own record plus its managed tenants' records
 * (e.g. TenantService.list L108-109 restricts to `hasReseller(context.resellerId())`;
 * the reseller/tenant boundary itself is resolved in
 * AuthorizationService.coversReseller L150-166). There is NO request parameter,
 * header or token claim that narrows a request to one of those tenants — a
 * `tenantId` in a URL is never read by the backend, and `GET /dids?tenantId=`
 * is honoured only for platform callers.
 *
 * So a tenant selector in this UI would be a client-side filter pretending to
 * be a scope change. It is deliberately NOT built. When the backend gains a real
 * "act as tenant" contract, this module is the single place that grows a
 * `selectedTenantId`, and `clearOperatingContextQueries` below is the hook
 * that invalidates on change.
 *
 * The selected tenant is never a security boundary. The backend authorises
 * every request independently.
 */
export type OperatingScope = "PLATFORM" | "RESELLER" | "TENANT";

export interface OperatingContext {
  /** True only while `GET /auth/me` is still in flight. */
  readonly isResolving: boolean;
  /** Null until the session resolves; then always a usable context. */
  readonly scope: OperatingScope | null;
  /** The tenant this user is bound to. Null for RESELLER and PLATFORM scope. */
  readonly tenantId: string | null;
  /** The reseller this user is bound to. Null for TENANT and PLATFORM scope. */
  readonly resellerId: string | null;
  /** Human label for the header/scope banner. */
  readonly label: string;
  /**
   * A stable string identifying the authorization context, suitable for query
   * keys and cache scoping. Derived from backend-authorized data only.
   *
   * VERIFIED: it cannot change within a session, because there is no tenant
   * switching to perform. Cache isolation between identities is provided by
   * `endLocalSession()` calling `queryClient.clear()` on both logout success
   * and failure. F1 therefore does NOT thread this through all 50+ query-key
   * call sites — that would be churn with no correctness gain and a real risk
   * of breaking invalidation. It is exposed here so F2+ has one obvious place to
   * add it if a switching contract ever lands.
   */
  readonly scopeKey: string;
}

/** Derives the operating context from a `/me` payload. Pure and testable. */
export function deriveOperatingContext(
  user: AuthenticatedUserResponse | null | undefined,
  isResolving: boolean,
): OperatingContext {
  if (!user) {
    return {
      isResolving,
      scope: null,
      tenantId: null,
      resellerId: null,
      label: "Unknown",
      scopeKey: "unauthenticated",
    };
  }

  // homeType is the authoritative scope signal. Capabilities are NOT: /me
  // returns their scope-UNION, so they cannot distinguish platform from tenant.
  const scope: OperatingScope =
    user.homeType === "TENANT"
      ? "TENANT"
      : user.homeType === "RESELLER"
        ? "RESELLER"
        : "PLATFORM";

  const label =
    scope === "TENANT"
      ? "Tenant"
      : scope === "RESELLER"
        ? "Reseller"
        : "Platform";

  return {
    isResolving,
    scope,
    tenantId: scope === "TENANT" ? user.organizationId : null,
    resellerId: scope === "RESELLER" ? user.organizationId : null,
    label,
    scopeKey: `${scope}:${user.organizationId ?? "none"}`,
  };
}

/** Reads the current operating context from the session query.
 *
 * `useSession` is the single session source; this hook adds no fetching, no
 * store and no context provider, so there is exactly one place the UI learns
 * who the user is and what scope they act in. */
export function useOperatingContext(): OperatingContext {
  const session = useSession();
  return useMemo(
    () => deriveOperatingContext(session.data, session.isPending),
    [session.data, session.isPending],
  );
}

/** The authenticated user, or null. Re-exported for components that need both
 * the identity and the scope without a second hook call. */
export function useCurrentUser(): AuthenticatedUserResponse | null {
  return useSession().data ?? null;
}

export { SESSION_QUERY_KEY };
