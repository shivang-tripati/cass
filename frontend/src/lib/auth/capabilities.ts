import type { AuthenticatedUserResponse } from "@/lib/api/contracts";

/**
 * Frontend capability constants, mirrored from the backend capability catalog.
 *
 * VERIFIED (F1) against the migrations that seed `capabilities`:
 *   V1  27 keys · V16 DID_VIEW/DID_MANAGE · V20 TTS_* · V37 QUEUE_*
 *   = 34 seeded keys, listed exhaustively below.
 *
 * NOTHING IS INVENTED HERE. In particular:
 *  - there is no CONTACT_DELETE. Contacts are removed with CONTACT_MANAGE.
 *  - there is no CAMPAIGN_APPROVE. Campaign lifecycle is CAMPAIGN_EXECUTE.
 *  - there is no AUDIO_EXPORT or CONTACT_DELETE. Contact import/export run on
 *    CONTACT_MANAGE / CONTACT_VIEW respectively, even though CONTACT_IMPORT and
 *    CONTACT_EXPORT also exist in the catalog and are granted to the same roles.
 *  - IVR_VIEW and IVR_MANAGE are NOT listed. IvrTreeService enforces them, but
 *    no migration seeds them, so `findCapabilityId` returns null and every IVR
 *    endpoint answers 403 for every role. Adding them here would make the UI
 *    offer an action the backend can never authorise. See F1 doc §8.
 *
 * Backend authorization remains the security boundary. Everything in this file
 * is UX protection that prevents offering an action guaranteed to fail.
 */
export const Capability = {
  // Tenant — V1
  TENANT_VIEW: "TENANT_VIEW",
  TENANT_MANAGE: "TENANT_MANAGE",

  // Reseller — V1
  RESELLER_VIEW: "RESELLER_VIEW",
  RESELLER_MANAGE: "RESELLER_MANAGE",

  // User — V1
  USER_VIEW: "USER_VIEW",
  USER_MANAGE: "USER_MANAGE",

  // Role — V1. Seeded and granted, but NO controller enforces these: there is
  // no role CRUD endpoint, so there is nothing to gate.
  ROLE_VIEW: "ROLE_VIEW",
  ROLE_MANAGE: "ROLE_MANAGE",

  // Campaign — V1
  CAMPAIGN_VIEW: "CAMPAIGN_VIEW",
  CAMPAIGN_MANAGE: "CAMPAIGN_MANAGE",
  CAMPAIGN_EXECUTE: "CAMPAIGN_EXECUTE",
  CAMPAIGN_ASSIGN: "CAMPAIGN_ASSIGN",
  CAMPAIGN_EXPORT: "CAMPAIGN_EXPORT",

  // Contact — V1. Only CONTACT_VIEW / CONTACT_MANAGE are enforced; the
  // import/export keys are granted to the same roles but the contact controller
  // does not check them.
  CONTACT_VIEW: "CONTACT_VIEW",
  CONTACT_MANAGE: "CONTACT_MANAGE",
  CONTACT_IMPORT: "CONTACT_IMPORT",
  CONTACT_EXPORT: "CONTACT_EXPORT",

  // Audio — V1
  AUDIO_VIEW: "AUDIO_VIEW",
  AUDIO_MANAGE: "AUDIO_MANAGE",
  AUDIO_APPROVE: "AUDIO_APPROVE",

  // Call — V1
  CALL_VIEW: "CALL_VIEW",
  CALL_DISPOSITION: "CALL_DISPOSITION",

  // Agent — V1
  AGENT_VIEW: "AGENT_VIEW",
  AGENT_MANAGE: "AGENT_MANAGE",
  AGENT_ASSIGN: "AGENT_ASSIGN",

  // Report — V1. Seeded but not enforced by any controller.
  REPORT_VIEW: "REPORT_VIEW",
  REPORT_EXPORT: "REPORT_EXPORT",

  // DID — V16
  DID_VIEW: "DID_VIEW",
  DID_MANAGE: "DID_MANAGE",

  // TTS — V20
  TTS_VIEW: "TTS_VIEW",
  TTS_MANAGE: "TTS_MANAGE",
  TTS_APPROVE: "TTS_APPROVE",

  // Queue — V37
  QUEUE_VIEW: "QUEUE_VIEW",
  QUEUE_MANAGE: "QUEUE_MANAGE",
} as const;

export type Capability = (typeof Capability)[keyof typeof Capability];

/** Every seeded backend capability, for exhaustive checks and tests. */
export const ALL_CAPABILITIES: readonly Capability[] = Object.values(Capability);

/**
 * True when the user's role holds `capability`.
 *
 * VERIFIED CAVEAT — read this before using capability presence for anything but
 * action gating. `GET /auth/me` returns
 * `AuthorizationService.getAllCapabilitiesForUser`, documented in-line as
 * "Does not apply scope filtering - returns the union of all capabilities"
 * (AuthorizationService.java:174-176). A capability here therefore means
 * "some role of this user, at some scope, holds it" — it does NOT mean the
 * user can exercise it against the tenant currently in view. Use
 * `useOperatingContext` for scope, never this set.
 */
export function hasCapability(
  user: AuthenticatedUserResponse | null | undefined,
  capability: Capability,
): boolean {
  if (!user?.capabilities) return false;
  return user.capabilities.includes(capability);
}

/** True when the user holds at least one of `capabilities`. */
export function hasAnyCapability(
  user: AuthenticatedUserResponse | null | undefined,
  capabilities: readonly Capability[],
): boolean {
  if (!user?.capabilities) return false;
  return capabilities.some((cap) => user.capabilities.includes(cap));
}

/** True when the user holds every one of `capabilities`. */
export function hasAllCapabilities(
  user: AuthenticatedUserResponse | null | undefined,
  capabilities: readonly Capability[],
): boolean {
  if (!user?.capabilities) return false;
  return capabilities.every((cap) => user.capabilities.includes(cap));
}

/**
 * F1 REMOVED: `hasPlatformAccess()`, `hasResellerAccess()`, `hasTenantAccess()`.
 *
 * `hasPlatformAccess` was `homeType === null && hasCapability(TENANT_VIEW)` — a
 * frontend invention with no backend counterpart, and it conflated two
 * unrelated facts (see the scope caveat above). Scope now comes from exactly one
 * place, `useOperatingContext`, which is derived from `homeType` and
 * `organizationId` as the backend defines them.
 */
