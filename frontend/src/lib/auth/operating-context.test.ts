import { describe, expect, it } from "vitest";

import type { AuthenticatedUserResponse } from "@/lib/api/contracts";
import { deriveOperatingContext } from "@/lib/auth/operating-context";

/**
 * F1 — operating-context derivation.
 *
 * Scope comes from `homeType` and `organizationId` only. The backend returns a
 * scope-UNION of capabilities in /me
 * (`AuthorizationService.getAllCapabilitiesForUser`, "Does not apply scope
 * filtering"), so a test that inferred scope from capabilities would encode
 * exactly the wrong model. These tests pin scope to the home.
 */

function user(overrides: Partial<AuthenticatedUserResponse> = {}): AuthenticatedUserResponse {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    email: "user@example.com",
    status: "ACTIVE",
    homeType: "TENANT",
    organizationId: "22222222-2222-4222-8222-222222222222",
    capabilities: [],
    ...overrides,
  };
}

describe("deriveOperatingContext", () => {
  it("reports a resolving state with no scope before /me resolves", () => {
    const ctx = deriveOperatingContext(undefined, true);
    expect(ctx.isResolving).toBe(true);
    expect(ctx.scope).toBeNull();
    expect(ctx.tenantId).toBeNull();
    expect(ctx.resellerId).toBeNull();
    expect(ctx.scopeKey).toBe("unauthenticated");
  });

  it("maps a TENANT home to tenant scope and the tenant id", () => {
    const ctx = deriveOperatingContext(user({ homeType: "TENANT" }), false);
    expect(ctx.scope).toBe("TENANT");
    expect(ctx.tenantId).toBe("22222222-2222-4222-8222-222222222222");
    expect(ctx.resellerId).toBeNull();
    expect(ctx.label).toBe("Tenant");
  });

  it("maps a RESELLER home to reseller scope and the reseller id", () => {
    const ctx = deriveOperatingContext(
      user({ homeType: "RESELLER", organizationId: "33333333-3333-4333-8333-333333333333" }),
      false,
    );
    expect(ctx.scope).toBe("RESELLER");
    expect(ctx.resellerId).toBe("33333333-3333-4333-8333-333333333333");
    // A reseller does NOT get a tenant id: the backend gives it a
    // hierarchy-wide read, not an impersonation scope.
    expect(ctx.tenantId).toBeNull();
    expect(ctx.label).toBe("Reseller");
  });

  it("maps a null homeType to PLATFORM, because the enum has no PLATFORM member", () => {
    // VERIFIED: OrganizationalHomeType declares only TENANT and RESELLER, and
    // SUPER_ADMIN holds no home (V13). So null on the wire means platform.
    const ctx = deriveOperatingContext(
      user({ homeType: null, organizationId: null }),
      false,
    );
    expect(ctx.scope).toBe("PLATFORM");
    expect(ctx.tenantId).toBeNull();
    expect(ctx.resellerId).toBeNull();
    expect(ctx.label).toBe("Platform");
    expect(ctx.scopeKey).toBe("PLATFORM:none");
  });

  it("does NOT infer scope from capabilities", () => {
    // A tenant admin holds TENANT_VIEW. Inferring platform from that — which is
    // what the removed hasPlatformAccess() did — would be wrong.
    const ctx = deriveOperatingContext(
      user({ homeType: "TENANT", capabilities: ["TENANT_VIEW", "TENANT_MANAGE"] }),
      false,
    );
    expect(ctx.scope).toBe("TENANT");
  });

  it("produces distinct scope keys per identity", () => {
    const platform = deriveOperatingContext(user({ homeType: null, organizationId: null }), false);
    const resellerA = deriveOperatingContext(
      user({ homeType: "RESELLER", organizationId: "aaaaaaaa-1111-4111-8111-111111111111" }),
      false,
    );
    const resellerB = deriveOperatingContext(
      user({ homeType: "RESELLER", organizationId: "bbbbbbbb-1111-4111-8111-111111111111" }),
      false,
    );
    const tenantA = deriveOperatingContext(
      user({ homeType: "TENANT", organizationId: "aaaaaaaa-1111-4111-8111-111111111111" }),
      false,
    );

    const keys = new Set([platform.scopeKey, resellerA.scopeKey, resellerB.scopeKey, tenantA.scopeKey]);
    expect(keys.size).toBe(4);
  });

  it("is stable for the same identity", () => {
    const a = deriveOperatingContext(user(), false);
    const b = deriveOperatingContext(user(), false);
    expect(a.scopeKey).toBe(b.scopeKey);
  });

  it("keeps a TENANT user bound to exactly one tenant", () => {
    // There is no tenant selector, so a tenant context can never name a
    // different tenant than the one the backend bound in /me.
    const ctx = deriveOperatingContext(
      user({ homeType: "TENANT", organizationId: "44444444-4444-4444-8444-444444444444" }),
      false,
    );
    expect(ctx.tenantId).toBe("44444444-4444-4444-8444-444444444444");
  });
});
