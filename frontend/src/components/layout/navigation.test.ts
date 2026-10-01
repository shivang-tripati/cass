import { describe, expect, it } from "vitest";

import type { AuthenticatedUserResponse } from "@/lib/api/contracts";
import { Capability } from "@/lib/auth/capabilities";
import { getVisibleNavItems, scopeOf } from "@/components/layout/navigation";

/**
 * F1 — navigation visibility contract.
 *
 * Navigation hiding is UX, never authorization. These tests exist to pin that
 * it is CONSISTENT with the capability catalogue and with scope, so a user is
 * not shown a link whose page will immediately 403 — while remembering that
 * the backend is the only real boundary.
 */

/** `homeType` is `OrganizationalHomeType | null`, and the enum has no
 * PLATFORM member — null IS the platform representation. */
function user(
  capabilities: string[],
  homeType: AuthenticatedUserResponse["homeType"] = null,
): AuthenticatedUserResponse {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    email: "user@example.com",
    status: "ACTIVE",
    homeType,
    organizationId: homeType === null ? null : "22222222-2222-4222-8222-222222222222",
    capabilities,
  };
}

const urls = (u: AuthenticatedUserResponse) => getVisibleNavItems(u).map((i) => i.url);

describe("scopeOf", () => {
  it("maps homeType to a scope, with null meaning platform", () => {
    expect(scopeOf(user([], "TENANT"))).toBe("TENANT");
    expect(scopeOf(user([], "RESELLER"))).toBe("RESELLER");
    expect(scopeOf(user([], null))).toBe("PLATFORM");
    expect(scopeOf(null)).toBeNull();
  });
});

describe("getVisibleNavItems", () => {
  it("returns nothing for an unknown user", () => {
    expect(getVisibleNavItems(null)).toEqual([]);
    expect(getVisibleNavItems(undefined)).toEqual([]);
  });

  it("always offers Account, which is ungated", () => {
    expect(urls(user([]))).toContain("/account");
  });

  it("offers nothing but Account to a user with no capabilities", () => {
    // Matches the AGENT / REPORT_VIEWER roles: seeded capabilities that no
    // nav item requires, so the sidebar is empty apart from Account.
    expect(urls(user([]))).toEqual(["/account"]);
  });

  it("shows capability-gated items to a platform user", () => {
    const u = user([
      Capability.USER_VIEW,
      Capability.TENANT_VIEW,
      Capability.RESELLER_VIEW,
      Capability.DID_VIEW,
      Capability.CAMPAIGN_VIEW,
      Capability.CONTACT_VIEW,
      Capability.AUDIO_VIEW,
      Capability.TTS_VIEW,
    ]);
    expect(urls(u)).toEqual([
      "/users",
      "/tenants",
      "/resellers",
      "/dids",
      "/campaigns",
      "/contact-groups",
      "/audio-assets",
      "/tts-templates",
      "/account",
    ]);
  });

  it("hides Tenants and Resellers from a RESELLER user", () => {
    // requiredScope PLATFORM: a reseller holds TENANT_VIEW (it manages
    // tenants) but must not be offered the platform tenant/reseller admin.
    const u = user(
      [Capability.TENANT_VIEW, Capability.RESELLER_VIEW, Capability.CAMPAIGN_VIEW],
      "RESELLER",
    );
    const visible = urls(u);
    expect(visible).not.toContain("/tenants");
    expect(visible).not.toContain("/resellers");
    expect(visible).toContain("/campaigns");
  });

  it("hides Tenants and Resellers from a TENANT user", () => {
    const u = user([Capability.TENANT_VIEW, Capability.CONTACT_VIEW], "TENANT");
    const visible = urls(u);
    expect(visible).not.toContain("/tenants");
    expect(visible).not.toContain("/resellers");
    expect(visible).toContain("/contact-groups");
  });

  it("applies capability and scope as an AND", () => {
    // Has the capability but wrong scope -> hidden.
    expect(urls(user([Capability.RESELLER_VIEW], "TENANT"))).not.toContain("/resellers");
    // Right scope but no capability -> hidden.
    expect(urls(user([], null))).not.toContain("/tenants");
  });

  it("keeps the audit/role capabilities out of navigation", () => {
    // ROLE_VIEW/ROLE_MANAGE and REPORT_* are seeded and granted, but no
    // controller enforces them and no nav item requires them, so a user
    // holding only those still sees just Account.
    expect(
      urls(
        user([
          Capability.ROLE_VIEW,
          Capability.ROLE_MANAGE,
          Capability.REPORT_VIEW,
          Capability.REPORT_EXPORT,
        ]),
      ),
    ).toEqual(["/account"]);
  });
});
