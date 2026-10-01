import { describe, expect, it } from "vitest";

import type { AuthenticatedUserResponse } from "@/lib/api/contracts";
import { Capability } from "@/lib/auth/capabilities";
import {
  CONTACT_ACTIONS,
  CONTACT_ACTION_CAPABILITY,
  canCreateContactGroup,
  canPerformContactAction,
} from "@/lib/auth/contact-gates";

/**
 * F2 — Contacts / Contact Groups capability gating.
 *
 * VERIFIED against `ContactGroupService` and `ContactGroupMemberService`. The
 * catalogue seeds FOUR contact capability keys; the services only ever check
 * two of them. These tests exist so that substituting the unenforced key can
 * never happen quietly again:
 *
 *   `CONTACT_IMPORT` is seeded (V1) and granted to the same roles as
 *   `CONTACT_MANAGE`, but `importContacts` checks `CONTACT_MANAGE`. Gating the
 *   Import button on `CONTACT_IMPORT` is a guess that currently happens to give
 *   the same answer, and breaks silently the day role assignments diverge.
 *
 *   `CONTACT_EXPORT` is the same story: `exportContacts` checks `CONTACT_VIEW`.
 *
 * This is UX protection only. The backend authorises every request independently
 * and fail-closed; a hidden button prevents a guaranteed 403 and grants nothing.
 */

function userWith(
  capabilities: Capability[],
  overrides: {
    homeType?: "TENANT" | "RESELLER" | null;
    organizationId?: string | null;
    role?: string;
  } = {},
): AuthenticatedUserResponse {
  const {
    homeType = "TENANT",
    organizationId = "t-1",
    role = "TENANT_ADMIN",
  } = overrides;
  return {
    id: "u-1",
    email: "user@example.com",
    role,
    homeType,
    organizationId,
    capabilities,
  } as unknown as AuthenticatedUserResponse;
}

describe("the gate table records the ENFORCED keys", () => {
  it("gates reads on CONTACT_VIEW", () => {
    expect(CONTACT_ACTION_CAPABILITY.read).toBe(Capability.CONTACT_VIEW);
  });

  it("gates writes on CONTACT_MANAGE", () => {
    expect(CONTACT_ACTION_CAPABILITY.write).toBe(Capability.CONTACT_MANAGE);
  });

  it("gates import on CONTACT_MANAGE, NOT the seeded-but-unenforced CONTACT_IMPORT", () => {
    expect(CONTACT_ACTION_CAPABILITY.import).toBe(Capability.CONTACT_MANAGE);
    expect(CONTACT_ACTION_CAPABILITY.import).not.toBe(Capability.CONTACT_IMPORT);
  });

  it("gates export on CONTACT_VIEW, NOT the seeded-but-unenforced CONTACT_EXPORT", () => {
    expect(CONTACT_ACTION_CAPABILITY.export).toBe(Capability.CONTACT_VIEW);
    expect(CONTACT_ACTION_CAPABILITY.export).not.toBe(Capability.CONTACT_EXPORT);
  });

  it("uses only real capability names", () => {
    for (const action of CONTACT_ACTIONS) {
      const value = CONTACT_ACTION_CAPABILITY[action];
      expect(Object.values(Capability)).toContain(value);
    }
  });
});

describe("a CONTACT_VIEW-only user", () => {
  const user = userWith([Capability.CONTACT_VIEW]);

  it("may view groups, contacts, members and the roster", () => {
    expect(canPerformContactAction(user, "read")).toBe(true);
  });

  it("may export — export is authorised with the read capability", () => {
    expect(canPerformContactAction(user, "export")).toBe(true);
  });

  it("may NOT create, edit or delete a group", () => {
    expect(canPerformContactAction(user, "write")).toBe(false);
  });

  it("may NOT create, edit or delete a contact", () => {
    // Same key as the group writes: `createContact`, `updateContact` and
    // `deleteContact` all call `authorizedGroup(groupId, CAP_MANAGE)`.
    expect(canPerformContactAction(user, "write")).toBe(false);
  });

  it("may NOT add or remove a membership", () => {
    // `addMember` L100, `removeMember` L127, `addMembers` L135,
    // `removeMembers` L162 — all `CONTACT_MANAGE`.
    expect(canPerformContactAction(user, "write")).toBe(false);
  });

  it("may NOT import contacts", () => {
    expect(canPerformContactAction(user, "import")).toBe(false);
  });
});

describe("a CONTACT_MANAGE-only user", () => {
  // A role granted the manage key without the view key is not one the seeded
  // data produces, but the gate must still answer deterministically: the
  // backend would 403 the reads, and so must the UI.
  const user = userWith([Capability.CONTACT_MANAGE]);

  it("may write and import", () => {
    expect(canPerformContactAction(user, "write")).toBe(true);
    expect(canPerformContactAction(user, "import")).toBe(true);
  });

  it("may NOT read or export, matching the service's own checks", () => {
    expect(canPerformContactAction(user, "read")).toBe(false);
    expect(canPerformContactAction(user, "export")).toBe(false);
  });
});

describe("a user with both keys", () => {
  const user = userWith([Capability.CONTACT_VIEW, Capability.CONTACT_MANAGE]);

  it("may do every contacts action", () => {
    for (const action of CONTACT_ACTIONS) {
      expect(canPerformContactAction(user, action)).toBe(true);
    }
  });
});

describe("the unenforced keys grant nothing on their own", () => {
  it("CONTACT_IMPORT alone does not enable import", () => {
    const user = userWith([Capability.CONTACT_IMPORT]);
    expect(canPerformContactAction(user, "import")).toBe(false);
  });

  it("CONTACT_EXPORT alone does not enable export", () => {
    const user = userWith([Capability.CONTACT_EXPORT]);
    expect(canPerformContactAction(user, "export")).toBe(false);
  });
});

describe("an unauthenticated or capability-less user", () => {
  it("is denied every action rather than defaulting to allow", () => {
    for (const candidate of [null, undefined]) {
      for (const action of CONTACT_ACTIONS) {
        expect(canPerformContactAction(candidate, action)).toBe(false);
      }
      expect(canCreateContactGroup(candidate)).toBe(false);
    }
  });

  it("is denied when the user object carries no capabilities array", () => {
    const user = { id: "u-1" } as unknown as AuthenticatedUserResponse;
    for (const action of CONTACT_ACTIONS) {
      expect(canPerformContactAction(user, action)).toBe(false);
    }
  });
});

/**
 * Creating a group is the ONE Contacts write that is not authorised against an
 * existing resource, so it has an extra precondition beyond a capability.
 *
 * VERIFIED `ContactGroupService.createGroup` L98-110:
 *   `if (scope.tenantId() == null) throw business("A tenant must be specified for
 *   this operation.");`
 * and `CreateContactGroupRequest` is `record (name, description)` — there is no
 * tenantId field, header or query parameter that could supply one.
 */
describe("canCreateContactGroup", () => {
  it("allows a TENANT admin holding CONTACT_MANAGE", () => {
    const user = userWith([Capability.CONTACT_MANAGE], { homeType: "TENANT" });
    expect(canCreateContactGroup(user)).toBe(true);
  });

  it("refuses a TENANT admin WITHOUT CONTACT_MANAGE", () => {
    const user = userWith([Capability.CONTACT_VIEW], { homeType: "TENANT" });
    expect(canCreateContactGroup(user)).toBe(false);
  });

  it("refuses a RESELLER admin, even though V1 grants it CONTACT_MANAGE", () => {
    // This is the case a capability-only check gets wrong: the button is
    // offered and every submit returns 400 "A tenant must be specified".
    const user = userWith([Capability.CONTACT_MANAGE], {
      homeType: "RESELLER",
      organizationId: "r-1",
      role: "RESELLER_ADMIN",
    });
    expect(canPerformContactAction(user, "write")).toBe(true);
    expect(canCreateContactGroup(user)).toBe(false);
  });

  it("refuses a platform SUPER_ADMIN, who has no organizational home", () => {
    const user = userWith([Capability.CONTACT_MANAGE], {
      homeType: null,
      organizationId: null,
      role: "SUPER_ADMIN",
    });
    expect(canPerformContactAction(user, "write")).toBe(true);
    expect(canCreateContactGroup(user)).toBe(false);
  });

  it("does NOT restrict editing, deleting or membership for a reseller", () => {
    // Those go through `authorizedGroup(groupId, CONTACT_MANAGE)`, which only
    // requires the group to be VISIBLE. Restricting them would be wrong.
    const user = userWith([Capability.CONTACT_MANAGE], {
      homeType: "RESELLER",
      organizationId: "r-1",
    });
    expect(canPerformContactAction(user, "write")).toBe(true);
    expect(canPerformContactAction(user, "import")).toBe(true);
  });
});
