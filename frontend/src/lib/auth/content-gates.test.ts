import { describe, expect, it } from "vitest";

import { Capability } from "@/lib/auth/capabilities";
import {
  AUDIO_ACTIONS,
  AUDIO_ACTION_CAPABILITY,
  TTS_ACTIONS,
  TTS_ACTION_CAPABILITY,
  canApproveTtsTemplate,
  canCreateAudioAsset,
  canManageTtsTemplate,
  canPerformAudioAction,
  canPerformTtsAction,
  ttsCreateOptionsFor,
} from "@/lib/auth/content-gates";
import type { AuthenticatedUserResponse } from "@/lib/api/contracts";
import type { OperatingScope } from "@/lib/auth/operating-context";

/**
 * F3 — Audio Assets and TTS Templates authorization.
 *
 * VERIFIED against `AudioAssetService`, `TtsTemplateService`,
 * `AuthorizationService.covers` / `.coversReseller`, and the seed migrations
 * V1 (audio) and V20 (TTS).
 *
 * ## The reseller split, in both directions
 *
 * The single most important thing this file pins, because both failure modes
 * are wrong and neither is caught by typecheck:
 *
 *  - V1 grants `RESELLER_ADMIN` `AUDIO_VIEW` and `AUDIO_APPROVE` but **not**
 *    `AUDIO_MANAGE`. So a reseller administrator CAN approve and reject audio
 *    and CANNOT upload, edit or delete it.
 *  - V20 grants `RESELLER_ADMIN` all three `TTS_*` keys, so a reseller
 *    administrator can fully manage its hierarchy's templates — but NOT the
 *    global catalog, which needs platform scope for every write.
 *
 * Giving a reseller management actions because it can approve is inventing a
 * permission. Hiding approval actions because the user lacks management is
 * removing a real one. Both are asserted below.
 */

/** `vi.mock`-free: the gate functions are pure, so a plain user factory is
 *  enough and there is no module boundary to stub. */
function userWith(
  capabilities: Capability[],
  homeType: "TENANT" | "RESELLER" | null,
  organizationId: string | null,
): AuthenticatedUserResponse {
  return {
    id: "u-1",
    email: "user@example.com",
    role: "TEST",
    homeType,
    organizationId,
    capabilities,
  } as unknown as AuthenticatedUserResponse;
}

const tenantAdmin = userWith(
  [
    Capability.AUDIO_VIEW,
    Capability.AUDIO_MANAGE,
    Capability.AUDIO_APPROVE,
    Capability.TTS_VIEW,
    Capability.TTS_MANAGE,
    Capability.TTS_APPROVE,
  ],
  "TENANT",
  "t-1",
);

/** Exactly the V1 grant for RESELLER_ADMIN. */
const resellerAdmin = userWith(
  [
    Capability.AUDIO_VIEW,
    Capability.AUDIO_APPROVE,
    Capability.TTS_VIEW,
    Capability.TTS_MANAGE,
    Capability.TTS_APPROVE,
  ],
  "RESELLER",
  "r-1",
);

/** Exactly the V1 grant for SUPER_ADMIN, which holds no organizational home. */
const superAdmin = userWith(
  [
    Capability.AUDIO_VIEW,
    Capability.AUDIO_MANAGE,
    Capability.AUDIO_APPROVE,
    Capability.TTS_VIEW,
    Capability.TTS_MANAGE,
    Capability.TTS_APPROVE,
  ],
  null,
  null,
);

describe("the gate tables record the ENFORCED keys", () => {
  it("audio reads are AUDIO_VIEW, verified at AudioAssetService L79/L93/L101/L103", () => {
    expect(AUDIO_ACTION_CAPABILITY.read).toBe(Capability.AUDIO_VIEW);
  });

  it("audio writes are AUDIO_MANAGE, verified at L69/L124/L137", () => {
    expect(AUDIO_ACTION_CAPABILITY.write).toBe(Capability.AUDIO_MANAGE);
  });

  it("audio upload is AUDIO_MANAGE — there is no AUDIO_UPLOAD capability", () => {
    expect(AUDIO_ACTION_CAPABILITY.upload).toBe(Capability.AUDIO_MANAGE);
    expect(Object.values(Capability)).not.toContain("AUDIO_UPLOAD");
  });

  it("audio approval is AUDIO_APPROVE, a genuinely different key (L269)", () => {
    expect(AUDIO_ACTION_CAPABILITY.approve).toBe(Capability.AUDIO_APPROVE);
    expect(AUDIO_ACTION_CAPABILITY.approve).not.toBe(
      AUDIO_ACTION_CAPABILITY.write,
    );
  });

  it("tts reads/writes/approval map to TTS_VIEW, TTS_MANAGE, TTS_APPROVE", () => {
    expect(TTS_ACTION_CAPABILITY.read).toBe(Capability.TTS_VIEW);
    expect(TTS_ACTION_CAPABILITY.write).toBe(Capability.TTS_MANAGE);
    expect(TTS_ACTION_CAPABILITY.approve).toBe(Capability.TTS_APPROVE);
  });

  it("invents no capability names beyond the seeded catalogue", () => {
    for (const key of [
      ...AUDIO_ACTIONS.map((a) => AUDIO_ACTION_CAPABILITY[a]),
      ...TTS_ACTIONS.map((a) => TTS_ACTION_CAPABILITY[a]),
    ]) {
      expect(Object.values(Capability)).toContain(key);
    }
  });

  it("does not define a capability called AUDIO_DELETE", () => {
    // Contacts have no CONTACT_DELETE and audio has no AUDIO_DELETE: deletion
    // runs on the manage key. F3 must not invent one.
    expect(Object.values(Capability)).not.toContain("AUDIO_DELETE");
  });
});

describe("AUDIO: a RESELLER_ADMIN", () => {
  it("may read audio assets", () => {
    expect(canPerformAudioAction(resellerAdmin, "read")).toBe(true);
  });

  it("MAY approve and reject — AUDIO_APPROVE is granted by V1", () => {
    expect(canPerformAudioAction(resellerAdmin, "approve")).toBe(true);
  });

  it("may NOT edit or delete — AUDIO_MANAGE is not granted by V1", () => {
    expect(canPerformAudioAction(resellerAdmin, "write")).toBe(false);
  });

  it("may NOT upload", () => {
    expect(canPerformAudioAction(resellerAdmin, "upload")).toBe(false);
    expect(canCreateAudioAsset(resellerAdmin)).toBe(false);
  });
});

describe("AUDIO: a TENANT_ADMIN", () => {
  it("may do every audio action", () => {
    for (const action of AUDIO_ACTIONS) {
      expect(canPerformAudioAction(tenantAdmin, action)).toBe(true);
    }
  });

  it("may create an audio asset, because it has a tenant context", () => {
    expect(canCreateAudioAsset(tenantAdmin)).toBe(true);
  });
});

describe("AUDIO: a SUPER_ADMIN", () => {
  it("holds AUDIO_MANAGE and AUDIO_APPROVE", () => {
    expect(canPerformAudioAction(superAdmin, "write")).toBe(true);
    expect(canPerformAudioAction(superAdmin, "approve")).toBe(true);
  });

  it("but can NOT create an audio asset", () => {
    // VERIFIED `AudioAssetService.create` L64-67 and `.upload` L191-195 both
    // throw "A tenant must be specified for this operation." when
    // `Scope.tenantId` is null, and neither request DTO carries a tenant. A
    // SUPER_ADMIN holds no organizational home, so it has no tenant context.
    // Offering the upload button here would produce a guaranteed failure.
    expect(canCreateAudioAsset(superAdmin)).toBe(false);
  });
});

describe("AUDIO: a user with no audio capabilities", () => {
  it("is denied everything rather than defaulting to allow", () => {
    const none = userWith([], "TENANT", "t-1");
    for (const action of AUDIO_ACTIONS) {
      expect(canPerformAudioAction(none, action)).toBe(false);
    }
    expect(canCreateAudioAsset(none)).toBe(false);
  });

  it("is denied when the session is absent", () => {
    for (const action of AUDIO_ACTIONS) {
      expect(canPerformAudioAction(null, action)).toBe(false);
      expect(canPerformAudioAction(undefined, action)).toBe(false);
    }
  });
});

describe("TTS: a TENANT_ADMIN on its own template", () => {
  const template = { scope: "TENANT" as const };

  it("may manage and approve it", () => {
    expect(canManageTtsTemplate(tenantAdmin, "TENANT", template)).toBe(true);
    expect(canApproveTtsTemplate(tenantAdmin, "TENANT", template)).toBe(true);
  });

  it("may NOT manage or approve a GLOBAL template", () => {
    // VERIFIED `manageCheckFor` L239-243: GLOBAL requires
    // `AccessCheck.platformWide()`, which a TENANT-scoped assignment never
    // covers (`AuthorizationService.covers` L145).
    const global = { scope: "GLOBAL" as const };
    expect(canManageTtsTemplate(tenantAdmin, "TENANT", global)).toBe(false);
    expect(canApproveTtsTemplate(tenantAdmin, "TENANT", global)).toBe(false);
  });
});

describe("TTS: a RESELLER_ADMIN", () => {
  it("may manage and approve a TENANT template in its hierarchy", () => {
    // V20 grants all three TTS_* keys, and a RESELLER-scoped assignment covers
    // `forTenant(T)` for T in the caller's hierarchy
    // (`AuthorizationService.coversReseller` L150-166).
    const template = { scope: "TENANT" as const };
    expect(canManageTtsTemplate(resellerAdmin, "RESELLER", template)).toBe(true);
    expect(canApproveTtsTemplate(resellerAdmin, "RESELLER", template)).toBe(true);
  });

  it("may NOT manage or approve a GLOBAL template", () => {
    // The shared catalog is platform-owned. A reseller can READ it, and every
    // write against it is refused.
    const global = { scope: "GLOBAL" as const };
    expect(canManageTtsTemplate(resellerAdmin, "RESELLER", global)).toBe(false);
    expect(canApproveTtsTemplate(resellerAdmin, "RESELLER", global)).toBe(false);
  });
});

describe("TTS: a SUPER_ADMIN", () => {
  it("may manage and approve BOTH scopes", () => {
    expect(canManageTtsTemplate(superAdmin, "PLATFORM", { scope: "TENANT" })).toBe(
      true,
    );
    expect(canManageTtsTemplate(superAdmin, "PLATFORM", { scope: "GLOBAL" })).toBe(
      true,
    );
    expect(
      canApproveTtsTemplate(superAdmin, "PLATFORM", { scope: "GLOBAL" }),
    ).toBe(true);
  });
});

describe("TTS: create options", () => {
  it("a tenant caller may create only TENANT templates, with no target picker", () => {
    // VERIFIED `TtsTemplateService.create` case 2: a caller with a tenant
    // context uses its own tenant, so there is nothing to choose.
    const options = ttsCreateOptionsFor(tenantAdmin, "TENANT");
    expect(options.scopes).toEqual(["TENANT"]);
    expect(options.requiresTargetTenant).toBe(false);
    expect(options.canCreateGlobal).toBe(false);
  });

  it("a platform caller may create GLOBAL, and must pick a target for TENANT", () => {
    // Cases 1 and 3.
    const options = ttsCreateOptionsFor(superAdmin, "PLATFORM");
    expect(options.scopes).toEqual(["TENANT", "GLOBAL"]);
    expect(options.canCreateGlobal).toBe(true);
    // A platform caller seeding a TENANT template has no context tenant, so
    // `tenantId` is the only way to say which organization it belongs to.
    expect(options.requiresTargetTenant).toBe(true);
  });

  it("a reseller caller may create NOTHING, despite holding TTS_MANAGE", () => {
    // VERIFIED: `create` has no reseller branch. A RESELLER-scoped assignment
    // does not cover `platformWide()` (AuthorizationService.covers L144), so the
    // platform seeding path is refused, and with no context tenant and no
    // `tenantId` the call falls through to "A tenant must be specified for this
    // operation." Offering the form would 400 on every submit.
    const options = ttsCreateOptionsFor(resellerAdmin, "RESELLER");
    expect(options.scopes).toEqual([]);
    expect(options.canCreateGlobal).toBe(false);
  });

  it("a user without TTS_MANAGE may create nothing in any scope", () => {
    const viewOnly = userWith([Capability.TTS_VIEW], "TENANT", "t-1");
    expect(ttsCreateOptionsFor(viewOnly, "TENANT").scopes).toEqual([]);
    expect(ttsCreateOptionsFor(viewOnly, "PLATFORM").scopes).toEqual([]);
  });

  it("treats an unresolved operating scope as offering nothing", () => {
    // `/me` is still loading, so the scope is null. Offering a create button on
    // a half-resolved session is how a user gets a 403 they cannot explain.
    const options = ttsCreateOptionsFor(tenantAdmin, null as OperatingScope | null);
    // A TENANT-scoped user is still known from `homeType`, so they keep the
    // tenant option; the GLOBAL option needs PLATFORM and is withheld.
    expect(options.scopes).toEqual(["TENANT"]);
    expect(options.canCreateGlobal).toBe(false);
  });
});

describe("TTS: a user without TTS capabilities", () => {
  it("is denied every action and every scope", () => {
    const none = userWith([], "TENANT", "t-1");
    for (const action of TTS_ACTIONS) {
      expect(canPerformTtsAction(none, action)).toBe(false);
    }
    expect(canManageTtsTemplate(none, "TENANT", { scope: "TENANT" })).toBe(false);
    expect(canApproveTtsTemplate(none, "TENANT", { scope: "TENANT" })).toBe(false);
    expect(ttsCreateOptionsFor(none, "TENANT").scopes).toEqual([]);
  });
});
