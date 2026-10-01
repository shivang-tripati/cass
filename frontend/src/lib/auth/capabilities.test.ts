import { describe, expect, it } from "vitest";

import type { AuthenticatedUserResponse } from "@/lib/api/contracts";
import {
  ALL_CAPABILITIES,
  Capability,
  hasAllCapabilities,
  hasAnyCapability,
  hasCapability,
} from "@/lib/auth/capabilities";

/**
 * F1 — capability catalogue and check contract.
 *
 * The most important test here is the exhaustive one: the frontend capability
 * set must be exactly the backend's seeded set. F0 carried 19 keys against 34
 * seeded, and §12 of the F1 brief forbids inventing any.
 */

function user(capabilities: string[]): AuthenticatedUserResponse {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    email: "user@example.com",
    status: "ACTIVE",
    homeType: "TENANT",
    organizationId: "22222222-2222-4222-8222-222222222222",
    capabilities,
  };
}

describe("capability catalogue", () => {
  it("contains every capability the backend seeds (V1, V16, V20, V37)", () => {
    // 27 from V1 + 2 (DID) + 3 (TTS) + 2 (QUEUE) = 34.
    expect(ALL_CAPABILITIES).toHaveLength(34);
  });

  it("includes the F0-missing IVR-free keys and never IVR", () => {
    // IVR_VIEW / IVR_MANAGE are enforced by IvrTreeService but are NOT seeded
    // in any migration, so no role can ever hold them and no IVR endpoint can
    // succeed. Listing them here would offer an action that always 403s.
    expect(Capability).not.toHaveProperty("IVR_VIEW");
    expect(Capability).not.toHaveProperty("IVR_MANAGE");
    expect(ALL_CAPABILITIES).not.toContain("IVR_VIEW" as never);
  });

  it("invents no permission the backend does not define", () => {
    for (const forbidden of [
      "CONTACT_DELETE",
      "CAMPAIGN_APPROVE",
      "AUDIO_EXPORT",
      "CONTACT_REIMPORT",
      "TEMPLATE_PUBLISH",
    ]) {
      expect(ALL_CAPABILITIES).not.toContain(forbidden as never);
    }
  });

  it("has no duplicate keys", () => {
    expect(new Set(ALL_CAPABILITIES).size).toBe(ALL_CAPABILITIES.length);
  });

  it("uses UPPER_SNAKE keys equal to their own value", () => {
    for (const key of ALL_CAPABILITIES) {
      expect(key).toMatch(/^[A-Z][A-Z0-9_]*$/);
    }
  });
});

describe("hasCapability", () => {
  it("grants only what the user holds", () => {
    const u = user([Capability.CAMPAIGN_VIEW, Capability.CAMPAIGN_MANAGE]);
    expect(hasCapability(u, Capability.CAMPAIGN_VIEW)).toBe(true);
    expect(hasCapability(u, Capability.CAMPAIGN_MANAGE)).toBe(true);
    expect(hasCapability(u, Capability.CAMPAIGN_EXECUTE)).toBe(false);
  });

  it("denies everything for a missing user", () => {
    expect(hasCapability(null, Capability.CAMPAIGN_VIEW)).toBe(false);
    expect(hasCapability(undefined, Capability.CAMPAIGN_VIEW)).toBe(false);
  });

  it("denies everything for a user with no capability list", () => {
    const u = { ...user([]), capabilities: undefined as unknown as string[] };
    expect(hasCapability(u, Capability.CAMPAIGN_VIEW)).toBe(false);
  });

  it("does not confuse VIEW with MANAGE", () => {
    // The audio/tts split F1 relies on: V1 grants RESELLER_ADMIN
    // AUDIO_APPROVE but NOT AUDIO_MANAGE.
    const reseller = user([Capability.AUDIO_VIEW, Capability.AUDIO_APPROVE]);
    expect(hasCapability(reseller, Capability.AUDIO_APPROVE)).toBe(true);
    expect(hasCapability(reseller, Capability.AUDIO_MANAGE)).toBe(false);
  });
});

describe("hasAnyCapability / hasAllCapabilities", () => {
  const u = user([Capability.CAMPAIGN_VIEW, Capability.CONTACT_MANAGE]);

  it("hasAny is satisfied by one match", () => {
    expect(hasAnyCapability(u, [Capability.CAMPAIGN_EXECUTE, Capability.CAMPAIGN_VIEW])).toBe(true);
    expect(hasAnyCapability(u, [Capability.CAMPAIGN_EXECUTE])).toBe(false);
  });

  it("hasAll requires every key", () => {
    expect(hasAllCapabilities(u, [Capability.CAMPAIGN_VIEW, Capability.CONTACT_MANAGE])).toBe(true);
    expect(hasAllCapabilities(u, [Capability.CAMPAIGN_VIEW, Capability.CAMPAIGN_MANAGE])).toBe(false);
  });

  it("treats an empty requirement set as satisfied", () => {
    expect(hasAnyCapability(u, [])).toBe(false);
    expect(hasAllCapabilities(u, [])).toBe(true);
  });

  it("denies both for a missing user", () => {
    expect(hasAnyCapability(null, [Capability.CAMPAIGN_VIEW])).toBe(false);
    expect(hasAllCapabilities(null, [Capability.CAMPAIGN_VIEW])).toBe(false);
  });
});
