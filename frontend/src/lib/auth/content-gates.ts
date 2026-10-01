import { Capability, hasAnyCapability } from "@/lib/auth/capabilities";
import type { AuthenticatedUserResponse } from "@/lib/api/contracts";
import type { OperatingScope } from "@/lib/auth/operating-context";

/**
 * Which ENFORCED capability gates each Audio Assets / TTS Templates action, and
 * where the backend enforces it.
 *
 * ## Why a table per domain
 *
 * The capability catalogue seeds six keys across these two domains, and every
 * one of them is genuinely enforced — this is not the F2 Contacts situation
 * where two seeded keys were dead. What still needs pinning is *which* key each
 * action uses, and the fact that two of the actions have a **precondition beyond
 * a capability**. Writing each call site as `can(Capability.AUDIO_MANAGE)` puts
 * that knowledge in six places, and it is exactly the knowledge that is easy to
 * get wrong in a way tests will not catch.
 *
 * ## Audio Assets — verified call sites (`AudioAssetService`)
 *
 * | Action                | Capability      | Call site |
 * |-----------------------|-----------------|-----------|
 * | list / get            | `AUDIO_VIEW`    | L93, L101, L103, L79 |
 * | create (metadata)     | `AUDIO_MANAGE`  | L69 |
 * | update                | `AUDIO_MANAGE`  | L124 |
 * | delete                | `AUDIO_MANAGE`  | L137 |
 * | **upload**            | `AUDIO_MANAGE`  | L196 |
 * | approve / reject      | `AUDIO_APPROVE` | L269 (`transition`) |
 *
 * ## TTS Templates — verified call sites (`TtsTemplateService`)
 *
 * | Action                | Capability      | Call site |
 * |-----------------------|-----------------|-----------|
 * | list / get            | `TTS_VIEW`      | L134, L140, L150, L121 |
 * | create                | `TTS_MANAGE`    | L103 |
 * | update                | `TTS_MANAGE`    | L177 |
 * | delete                | `TTS_MANAGE`    | L196 |
 * | approve / reject      | `TTS_APPROVE`   | L223 (`transition`) |
 *
 * ## THE RESELLER DISTINCTION — preserved, not smoothed over
 *
 * VERIFIED against the seed migrations. V1 grants `RESELLER_ADMIN`
 * `AUDIO_VIEW` and `AUDIO_APPROVE` but **not** `AUDIO_MANAGE`; V20 grants
 * `RESELLER_ADMIN` all three `TTS_*` keys.
 *
 * So a reseller admin legitimately:
 *  - **can** see audio assets across their hierarchy, and approve/reject them;
 *  - **cannot** upload, edit or delete an audio asset.
 *
 * F3 must not "fix" this by giving `RESELLER_ADMIN` management actions, and must
 * not hide approval actions because the user lacks management capability. Both
 * directions are wrong, and the tests in `content-gates.test.ts` assert both.
 *
 * ## Two actions have a precondition beyond a capability
 *
 * `POST /audio-assets` and `POST /audio-assets/upload` both require
 * `Scope.tenantId != null` and throw `"A tenant must be specified for this
 * operation."` otherwise (`AudioAssetService` L64-67 and L191-195). There is no
 * `tenantId` field on either request DTO and no header or parameter that could
 * supply one, so **only a TENANT-scoped caller can create an audio asset.** A
 * `SUPER_ADMIN` holds `AUDIO_MANAGE` and still cannot. This is the same
 * situation as F2's `canCreateContactGroup`, for the same reason.
 *
 * `POST /tts-templates` is different: it has a `tenantId` field, but it is
 * honoured **only** for a platform caller and the capability is then checked
 * with `AccessCheck.platformWide()` (L90-105). A reseller is therefore refused.
 * The client can evaluate both the scope and the capability from `/me`, so the
 * create form can be gated correctly instead of offering an action guaranteed to
 * fail. See `canCreateTtsTemplate`.
 *
 * ## UX protection, not access control
 *
 * The backend authorises every request independently and fail-closed. Hiding a
 * control prevents a guaranteed 403; it grants nothing. Nothing in this file is
 * a security boundary, and nothing in it is a second permission system: it is a
 * lookup over the F1 `Capability` catalogue plus the F1 `hasAnyCapability`
 * predicate.
 */

/** The three-state approval lifecycle, identical for audio and TTS. */
export type ApprovalStatus = "PENDING_APPROVAL" | "APPROVED" | "REJECTED";

export const AUDIO_ACTION_CAPABILITY = {
  /** List and read. `AudioAssetService.list` / `.getById`. */
  read: Capability.AUDIO_VIEW,
  /** Metadata create, update, delete. */
  write: Capability.AUDIO_MANAGE,
  /** The multipart upload path. Separate from `write` only in intent — the
   *  enforced key is identical, which is why this is an alias and not a
   *  distinct capability. There is no `AUDIO_UPLOAD` key. */
  upload: Capability.AUDIO_MANAGE,
  /**
   * Approve / reject. VERIFIED distinct from `write`
   * (`AudioAssetService.transition` L269), and the distinction is real:
   * `RESELLER_ADMIN` holds this but not `AUDIO_MANAGE`.
   */
  approve: Capability.AUDIO_APPROVE,
} as const;

export type AudioAction = keyof typeof AUDIO_ACTION_CAPABILITY;

export const TTS_ACTION_CAPABILITY = {
  read: Capability.TTS_VIEW,
  write: Capability.TTS_MANAGE,
  approve: Capability.TTS_APPROVE,
} as const;

export type TtsAction = keyof typeof TTS_ACTION_CAPABILITY;

export function canPerformAudioAction(
  user: AuthenticatedUserResponse | null | undefined,
  action: AudioAction,
): boolean {
  return hasAnyCapability(user, [AUDIO_ACTION_CAPABILITY[action]]);
}

export function canPerformTtsAction(
  user: AuthenticatedUserResponse | null | undefined,
  action: TtsAction,
): boolean {
  return hasAnyCapability(user, [TTS_ACTION_CAPABILITY[action]]);
}

/**
 * Can this user create a new audio asset (upload or metadata registration)?
 *
 * Requires `AUDIO_MANAGE` **and** a TENANT operating context. See the class
 * comment: `AudioAssetService.create` L64-67 and `.upload` L191-195 both refuse
 * a caller whose `Scope.tenantId` is null, and neither DTO carries a tenant.
 *
 * A `SUPER_ADMIN` holds `AUDIO_MANAGE` and is still refused; a `RESELLER_ADMIN`
 * is refused for the opposite reason (no `AUDIO_MANAGE`).
 */
export function canCreateAudioAsset(
  user: AuthenticatedUserResponse | null | undefined,
): boolean {
  return (
    canPerformAudioAction(user, "write") && user?.homeType === "TENANT"
  );
}

/** Minimal shape of a TTS template that the scope rules depend on. */
export interface TtsScopeFacts {
  scope: "GLOBAL" | "TENANT";
}

/**
 * May this user mutate (edit / delete) this specific template?
 *
 * VERIFIED `TtsTemplateService.manageCheckFor` (L239-243):
 *
 * ```java
 * return entity.getScope() == GLOBAL ? AccessCheck.platformWide()
 *                                     : AccessCheck.forTenant(entity.getTenantId());
 * ```
 *
 * So a **GLOBAL** row requires PLATFORM scope — a reseller sees the shared
 * catalog and can read it, but every write against it is a 403. A **TENANT** row
 * requires `TTS_MANAGE`; a `RESELLER`-scoped assignment satisfies
 * `forTenant(T)` when T is in the caller's hierarchy
 * (`AuthorizationService.coversReseller` L150-166), so a reseller admin can
 * manage their own tenants' templates.
 *
 * This is why F1's `canManageTtsTemplate` in `lib/api/tts-templates.ts` was
 * wrong: it returned `true` for any TENANT row regardless of capability, and it
 * compared against an operating-context string that was passed in by hand rather
 * than read from the session.
 */
export function canManageTtsTemplate(
  user: AuthenticatedUserResponse | null | undefined,
  operatingScope: OperatingScope | null,
  template: TtsScopeFacts,
): boolean {
  if (!canPerformTtsAction(user, "write")) return false;
  if (template.scope === "GLOBAL") return operatingScope === "PLATFORM";
  return true;
}

/**
 * May this user approve / reject this specific template?
 *
 * VERIFIED `TtsTemplateService.transition` L223: the capability is
 * `TTS_APPROVE`, but the SCOPE target is the same `manageCheckFor` as a write —
 * so a GLOBAL row still needs PLATFORM scope. A reseller admin holds
 * `TTS_APPROVE` and can approve their own tenants' templates, and is refused on
 * the shared catalog.
 */
export function canApproveTtsTemplate(
  user: AuthenticatedUserResponse | null | undefined,
  operatingScope: OperatingScope | null,
  template: TtsScopeFacts,
): boolean {
  if (!canPerformTtsAction(user, "approve")) return false;
  if (template.scope === "GLOBAL") return operatingScope === "PLATFORM";
  return true;
}

/** The scope a caller may choose when creating a template, or `null` for none. */
export type CreatableTtsScope = "TENANT" | "GLOBAL";

/**
 * Which `scope` values this caller may send on `POST /tts-templates`, and
 * whether a target tenant must also be supplied.
 *
 * VERIFIED `TtsTemplateService.create` L68-114, which resolves in this order:
 *
 *  1. `scope == GLOBAL` → `tenantId` MUST be absent (else 400 "GLOBAL templates
 *     are platform-owned and must not reference a tenant."); capability is
 *     checked with `AccessCheck.platformWide()`. **Platform only.**
 *  2. `scope == TENANT` and the caller has a tenant context → that tenant;
 *     capability checked `forTenant(thatTenant)`. No `tenantId` sent.
 *  3. `scope == TENANT`, no tenant context, and `tenantId` present → the target
 *     must exist and be ACTIVE, and the capability is checked
 *     `AccessCheck.platformWide()`. **Platform only** — a reseller is refused,
 *     because a RESELLER-scoped assignment does not cover `platformWide()`
 *     (`AuthorizationService.covers` L144).
 *  4. otherwise → 400 "A tenant must be specified for this operation."
 *
 * So the honest answer is two separate questions, and conflating them is how a
 * tenant picker ends up in front of a reseller who will be 403'd on submit.
 */
export interface TtsCreateOptions {
  /** Scopes this caller may create in. Never empty for a `TTS_MANAGE` holder. */
  readonly scopes: readonly CreatableTtsScope[];
  /** True when creating a TENANT template also requires choosing a target tenant. */
  readonly requiresTargetTenant: boolean;
  /** True when a GLOBAL template may be created at all (platform scope). */
  readonly canCreateGlobal: boolean;
}

export function ttsCreateOptionsFor(
  user: AuthenticatedUserResponse | null | undefined,
  operatingScope: OperatingScope | null,
): TtsCreateOptions {
  if (!canPerformTtsAction(user, "write")) {
    return { scopes: [], requiresTargetTenant: false, canCreateGlobal: false };
  }
  const isPlatform = operatingScope === "PLATFORM";
  if (isPlatform) {
    // Platform callers may create a shared GLOBAL row, or seed a system
    // template into a specific ACTIVE tenant (case 3 above).
    return {
      scopes: ["TENANT", "GLOBAL"],
      requiresTargetTenant: true,
      canCreateGlobal: true,
    };
  }
  // A tenant-scoped caller creates in its own tenant only (case 2).
  // A reseller-scoped caller reaches the `else` branch and is refused with
  // "A tenant must be specified for this operation." — so the honest answer is
  // that a reseller cannot create a template at all, even though V20 grants it
  // TTS_MANAGE. Reporting that is better than offering a form that always 400s.
  return {
    scopes: user?.homeType === "TENANT" ? ["TENANT"] : [],
    requiresTargetTenant: false,
    canCreateGlobal: false,
  };
}

/** Domain actions covered by these tables, for exhaustive tests. */
export const AUDIO_ACTIONS: readonly AudioAction[] = [
  "read",
  "write",
  "upload",
  "approve",
];

export const TTS_ACTIONS: readonly TtsAction[] = ["read", "write", "approve"];
