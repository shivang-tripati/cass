import { describe, expect, it } from "vitest";

import {
  CAMPAIGN_ACTION_CAPABILITY,
  CAMPAIGN_ACTIONS,
  CAMPAIGN_DEAD_CAPABILITIES,
  canCreateCampaign,
  canPerformCampaignAction,
  campaignCreateTargetFor,
  requiresTargetTenant,
} from "@/lib/auth/campaign-gates";
import { ALL_CAPABILITIES, Capability } from "@/lib/auth/capabilities";
import type { AuthenticatedUserResponse } from "@/lib/api/contracts";

/**
 * F4 — authorization tests for the Campaign domain.
 *
 * ## The finding that matters most
 *
 * `CAMPAIGN_ASSIGN` and `CAMPAIGN_EXPORT` are seeded by V1 and granted to
 * SUPER_ADMIN, RESELLER_ADMIN and TENANT_ADMIN, but VERIFIED by an exhaustive
 * search of `src/main/java` and `src/test/java` they appear in **no Java file
 * at all**. They gate nothing.
 *
 * `V1` even describes `CAMPAIGN_ASSIGN` as "Assign agents or queues to
 * campaigns" — but queue assignment lives behind `QUEUE_VIEW`/`QUEUE_MANAGE`
 * and no campaign endpoint assigns anything. So gating any control on
 * `CAMPAIGN_ASSIGN` would render a button for an operation that does not exist,
 * and *withholding* a real action because the user lacks it would invent a
 * restriction the server never applies. Both directions are wrong, and these
 * tests pin the correct behaviour from both sides.
 */

function userWith(...capabilities: Capability[]): AuthenticatedUserResponse {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    email: "user@example.test",
    firstName: "Test",
    lastName: "User",
    homeType: "TENANT",
    organizationId: "22222222-2222-4222-8222-222222222222",
    status: "ACTIVE",
    capabilities,
  } as unknown as AuthenticatedUserResponse;
}

const ALL_CAMPAIGN_KEYS: Capability[] = [
  Capability.CAMPAIGN_VIEW,
  Capability.CAMPAIGN_MANAGE,
  Capability.CAMPAIGN_EXECUTE,
  Capability.CAMPAIGN_ASSIGN,
  Capability.CAMPAIGN_EXPORT,
];

describe("action capability table", () => {
  it("covers exactly the three enforced keys", () => {
    expect([...CAMPAIGN_ACTIONS].sort()).toEqual(["execute", "read", "write"]);
    expect(CAMPAIGN_ACTION_CAPABILITY.read).toBe(Capability.CAMPAIGN_VIEW);
    expect(CAMPAIGN_ACTION_CAPABILITY.write).toBe(Capability.CAMPAIGN_MANAGE);
    expect(CAMPAIGN_ACTION_CAPABILITY.execute).toBe(Capability.CAMPAIGN_EXECUTE);
  });

  it("maps `execute` to a DIFFERENT key from `write`", () => {
    // VERIFIED `CampaignService.changeStatus` L277 checks CAMPAIGN_EXECUTE,
    // while update/delete/clone check CAMPAIGN_MANAGE. Conflating them is how
    // the F1 UI offered "Change Status" to a MANAGE-only holder.
    expect(CAMPAIGN_ACTION_CAPABILITY.execute).not.toBe(
      CAMPAIGN_ACTION_CAPABILITY.write,
    );
  });

  it("names no dead key", () => {
    for (const action of CAMPAIGN_ACTIONS) {
      expect(CAMPAIGN_DEAD_CAPABILITIES).not.toContain(
        CAMPAIGN_ACTION_CAPABILITY[action],
      );
    }
  });
});

describe("canPerformCampaignAction", () => {
  it("is false for a null user and for no capabilities", () => {
    for (const action of CAMPAIGN_ACTIONS) {
      expect(canPerformCampaignAction(null, action)).toBe(false);
      expect(canPerformCampaignAction(userWith(), action)).toBe(false);
    }
  });

  it("grants read on CAMPAIGN_VIEW alone", () => {
    const user = userWith(Capability.CAMPAIGN_VIEW);
    expect(canPerformCampaignAction(user, "read")).toBe(true);
    expect(canPerformCampaignAction(user, "write")).toBe(false);
    expect(canPerformCampaignAction(user, "execute")).toBe(false);
  });

  it("grants write on CAMPAIGN_MANAGE alone, but not execute", () => {
    // The exact asymmetry the F1 UI got wrong.
    const user = userWith(Capability.CAMPAIGN_MANAGE);
    expect(canPerformCampaignAction(user, "write")).toBe(true);
    expect(canPerformCampaignAction(user, "execute")).toBe(false);
  });

  it("grants execute on CAMPAIGN_EXECUTE alone, but not write", () => {
    const user = userWith(Capability.CAMPAIGN_EXECUTE);
    expect(canPerformCampaignAction(user, "execute")).toBe(true);
    expect(canPerformCampaignAction(user, "write")).toBe(false);
  });

  it("is unaffected by CAMPAIGN_ASSIGN and CAMPAIGN_EXPORT", () => {
    // A user holding ONLY the two dead keys must see no campaign actions at all.
    const user = userWith(Capability.CAMPAIGN_ASSIGN, Capability.CAMPAIGN_EXPORT);
    for (const action of CAMPAIGN_ACTIONS) {
      expect(canPerformCampaignAction(user, action), action).toBe(false);
    }
  });

  it("gives a full holder every action", () => {
    const user = userWith(...ALL_CAMPAIGN_KEYS);
    for (const action of CAMPAIGN_ACTIONS) {
      expect(canPerformCampaignAction(user, action), action).toBe(true);
    }
  });
});

describe("the seeded role grants, as F4 verified them", () => {
  it("gives SUPER_ADMIN, RESELLER_ADMIN and TENANT_ADMIN the same Campaign surface", () => {
    // V1 grants all three CAMPAIGN_VIEW/MANAGE/EXECUTE. CAMPAIGN_ASSIGN differs
    // (RESELLER_ADMIN lacks it) but it gates nothing, so the SURFACE is
    // identical. If this ever fails, a dead key has started being enforced.
    const superAdmin = userWith(
      Capability.CAMPAIGN_VIEW,
      Capability.CAMPAIGN_MANAGE,
      Capability.CAMPAIGN_EXECUTE,
      Capability.CAMPAIGN_ASSIGN,
      Capability.CAMPAIGN_EXPORT,
    );
    const resellerAdmin = userWith(
      Capability.CAMPAIGN_VIEW,
      Capability.CAMPAIGN_MANAGE,
      Capability.CAMPAIGN_EXECUTE,
      Capability.CAMPAIGN_EXPORT,
    );
    const tenantAdmin = userWith(
      Capability.CAMPAIGN_VIEW,
      Capability.CAMPAIGN_MANAGE,
      Capability.CAMPAIGN_EXECUTE,
      Capability.CAMPAIGN_ASSIGN,
      Capability.CAMPAIGN_EXPORT,
    );
    for (const action of CAMPAIGN_ACTIONS) {
      const expected = canPerformCampaignAction(superAdmin, action);
      expect(canPerformCampaignAction(resellerAdmin, action), action).toBe(expected);
      expect(canPerformCampaignAction(tenantAdmin, action), action).toBe(expected);
    }
  });

  it("gives AGENT and REPORT_VIEWER no Campaign access at all", () => {
    // VERIFIED V1: the AGENT grant list is CALL_VIEW/CALL_DISPOSITION only and
    // REPORT_VIEWER's is REPORT_VIEW/REPORT_EXPORT only.
    const agent = userWith(Capability.CALL_VIEW, Capability.CALL_DISPOSITION);
    const reporter = userWith(Capability.REPORT_VIEW, Capability.REPORT_EXPORT);
    for (const action of CAMPAIGN_ACTIONS) {
      expect(canPerformCampaignAction(agent, action), action).toBe(false);
      expect(canPerformCampaignAction(reporter, action), action).toBe(false);
    }
  });
});

describe("dead capability keys", () => {
  it("are exactly CAMPAIGN_ASSIGN and CAMPAIGN_EXPORT", () => {
    // VERIFIED: seeded in V1 (L179-180, grants L209/230/250) and referenced by
    // no Java file whatsoever.
    expect([...CAMPAIGN_DEAD_CAPABILITIES].sort()).toEqual([
      Capability.CAMPAIGN_ASSIGN,
      Capability.CAMPAIGN_EXPORT,
    ]);
  });

  it("remain in the capability catalogue, because /me really returns them", () => {
    // Removing them from `Capability` would make the frontend disagree with the
    // server about what a user holds. The catalogue stays; the GATES do not use
    // them.
    for (const key of CAMPAIGN_DEAD_CAPABILITIES) {
      expect(ALL_CAPABILITIES).toContain(key);
    }
  });
});

describe("canCreateCampaign", () => {
  it("requires CAMPAIGN_MANAGE", () => {
    expect(canCreateCampaign(userWith(Capability.CAMPAIGN_VIEW))).toBe(false);
    expect(canCreateCampaign(userWith(Capability.CAMPAIGN_EXECUTE))).toBe(false);
    expect(
      canCreateCampaign(userWith(Capability.CAMPAIGN_ASSIGN, Capability.CAMPAIGN_EXPORT)),
    ).toBe(false);
    expect(canCreateCampaign(userWith(Capability.CAMPAIGN_MANAGE))).toBe(true);
  });

  it("is false for a null user", () => {
    expect(canCreateCampaign(null)).toBe(false);
  });

  it("does not additionally require TENANT scope", () => {
    // VERIFIED `CampaignService.create` honours `?tenantId=` for platform and
    // reseller callers, unlike `POST /audio-assets` (F3) and
    // `POST /contact-groups` (F2), which refuse them outright. So the capability
    // IS sufficient to offer the action; the scope only decides whether a
    // target-tenant picker is shown.
    const platform = { ...userWith(Capability.CAMPAIGN_MANAGE), homeType: null };
    expect(canCreateCampaign(platform)).toBe(true);
  });
});

describe("requiresTargetTenant", () => {
  it("is true for PLATFORM and RESELLER, false for TENANT", () => {
    // VERIFIED `CampaignService.create` L112:
    //   `scope.tenantId() != null ? scope.tenantId() : requestedTenantId`
    // so the parameter is consulted ONLY when the caller has no tenant context.
    expect(requiresTargetTenant("PLATFORM")).toBe(true);
    expect(requiresTargetTenant("RESELLER")).toBe(true);
    expect(requiresTargetTenant("TENANT")).toBe(false);
  });

  it("is false while the scope is still resolving", () => {
    // The picker must not flash before `/me` resolves. A null scope renders no
    // picker, and the form is not submittable in that state anyway because the
    // Create button is gated on the resolved capability.
    expect(requiresTargetTenant(null)).toBe(false);
  });
});

describe("campaignCreateTargetFor", () => {
  it("sends no tenantId for TENANT scope, even if one was chosen", () => {
    // The caller's own tenant context WINS in the backend, so sending its own
    // id would be noise at best and misleading at worst.
    const target = campaignCreateTargetFor("TENANT", "some-tenant-id");
    expect(target.tenantId).toBeNull();
    expect(target.isTargetRequired).toBe(false);
  });

  it("requires and forwards a target for RESELLER", () => {
    const target = campaignCreateTargetFor("RESELLER", "tenant-1");
    expect(target.tenantId).toBe("tenant-1");
    expect(target.isTargetRequired).toBe(true);
  });

  it("requires and forwards a target for PLATFORM", () => {
    const target = campaignCreateTargetFor("PLATFORM", "tenant-1");
    expect(target.tenantId).toBe("tenant-1");
    expect(target.isTargetRequired).toBe(true);
  });

  it("is required but null before a target is chosen — so the form must block", () => {
    const target = campaignCreateTargetFor("PLATFORM", null);
    expect(target.tenantId).toBeNull();
    expect(target.isTargetRequired).toBe(true);
  });

  it("treats an empty string as no target", () => {
    // The select's "no selection" value arrives as "".
    expect(campaignCreateTargetFor("RESELLER", "").tenantId).toBeNull();
  });
});
