import { Capability, hasAnyCapability } from "@/lib/auth/capabilities";
import type { AuthenticatedUserResponse } from "@/lib/api/contracts";
import type { OperatingScope } from "@/lib/auth/operating-context";

/**
 * Which ENFORCED capability gates each Campaign action, and where the backend
 * enforces it.
 *
 * ## Why a table per domain
 *
 * Campaign is the one domain so far with a **three-way** capability split that
 * the UI must not flatten, and it is not the split the role names suggest.
 * Writing `can(Capability.CAMPAIGN_MANAGE)` at each call site is how the F1 UI
 * came to offer "Change Status" to a MANAGE-only holder.
 *
 * ## Verified call sites
 *
 * There is **no `@PreAuthorize` anywhere in the Campaign package** — every
 * check is a `requireCapability` call inside a service method, against the
 * caller's server-derived organizational context. The relevant constants are
 * `CampaignService.java:58-61`, `CampaignReadinessService.java:45`,
 * `CampaignExecutionService.java:37` and `CallAttemptService.java:38`.
 *
 * | Action                        | Capability         | Call site                        |
 * |-------------------------------|--------------------|----------------------------------|
 * | list campaigns                | `CAMPAIGN_VIEW`    | `CampaignService.list` L181/184/191 |
 * | get campaign                  | `CAMPAIGN_VIEW`    | `CampaignService.getById` L162    |
 * | create                        | `CAMPAIGN_MANAGE`  | `CampaignService.create` L119     |
 * | update                        | `CAMPAIGN_MANAGE`  | `CampaignService.update` L212     |
 * | delete                        | `CAMPAIGN_MANAGE`  | `CampaignService.delete` L253     |
 * | clone                         | `CAMPAIGN_MANAGE`  | `CampaignService.clone` L310      |
 * | change status                 | `CAMPAIGN_EXECUTE` | `CampaignService.changeStatus` L277 |
 * | readiness                     | `CAMPAIGN_VIEW`    | `CampaignReadinessService.evaluate` L45 |
 * | request execution             | `CAMPAIGN_EXECUTE` | `CampaignExecutionService.execute` L37 |
 * | list / get executions         | `CAMPAIGN_EXECUTE` | `CampaignExecutionService` L37    |
 * | create / read / transition attempt | `CAMPAIGN_EXECUTE` | `CallAttemptService` L38     |
 *
 * ## `CAMPAIGN_ASSIGN` and `CAMPAIGN_EXPORT` ARE DEAD KEYS
 *
 * This is a change from F1, which listed both in `Capability` without a
 * verdict. VERIFIED by an exhaustive search of `src/main/java` and
 * `src/test/java`: **`CAMPAIGN_ASSIGN` and `CAMPAIGN_EXPORT` appear in NO
 * Java file at all.** They exist only as rows in `V1__create_multi_tenant_
 * authorization_foundation.sql` (L179-180) and in the role grants
 * (L209, L230, L250).
 *
 * `V1` even describes `CAMPAIGN_ASSIGN` as "Assign agents or queues to
 * campaigns" — but queue assignment lives in `voice.queue` behind `QUEUE_VIEW`
 * / `QUEUE_MANAGE` (V37), and there is no agent-assignment endpoint on the
 * campaign resource at all.
 *
 * Consequence: **no Campaign action may be gated on either key.** Offering an
 * "Assign" control behind `CAMPAIGN_ASSIGN` would render a button whose
 * endpoint does not exist, and hiding a real action because the user lacks
 * `CAMPAIGN_ASSIGN` would be inventing a restriction the server never applies.
 * Both directions are wrong. `SUPER_ADMIN` and `TENANT_ADMIN` hold
 * `CAMPAIGN_ASSIGN` and must see exactly the same Campaign surface as
 * `RESELLER_ADMIN`, which does not.
 *
 * The keys stay in `Capability` because they are real rows in the catalog and
 * `/me` returns them; removing them would make the frontend disagree with the
 * server about what a user holds. This module is what records that they gate
 * nothing.
 *
 * ## Create has a precondition beyond a capability — and it is the
 *   FOURTH endpoint in this codebase gated on a tenant context
 *
 * VERIFIED `CampaignService.create` L110-119:
 *
 * ```java
 * UUID tenantId = scope.tenantId() != null ? scope.tenantId() : requestedTenantId;
 * if (tenantId == null) throw new BusinessException(VALIDATION_ERROR,
 *         "A tenant must be specified for this operation.");
 * requireTargetTenantUsable(tenantId);   // must exist and be ACTIVE
 * authorizationService.requireCapability(userId, CAP_MANAGE, AccessCheck.forTenant(tenantId));
 * ```
 *
 * So `POST /campaigns` is **not** platform-wide even though `SUPER_ADMIN` holds
 * `CAMPAIGN_MANAGE`. A caller with no tenant context must pass `?tenantId=`, and
 * the target must exist and be `ACTIVE`.
 *
 * Unlike `POST /audio-assets` (F3), the `tenantId` query parameter **is**
 * honoured here for platform and reseller callers — `DidService.create`
 * resolves the same way. This is the one place the backend genuinely offers a
 * target-tenant choice, so the create dialog is gated by
 * `canCreateCampaign` below, which is scope-aware. It is a real backend
 * parameter, not a frontend tenant selector, and it never widens the caller's
 * authority: `requireCapability(CAMPAIGN_MANAGE, forTenant(target))` is still
 * enforced, so a reseller can only target a tenant in its own hierarchy
 * (`AuthorizationService.coversReseller` L150-166).
 *
 * ## Other preconditions beyond a capability
 *
 *  - **Update requires DRAFT.** `CampaignLifecyclePolicy.assertEditable`
 *    throws 409 for any other status. See `lib/domain/campaign-lifecycle.ts`.
 *  - **Delete and clone have no lifecycle gate.** A campaign can be soft
 *    deleted or cloned in any state, including ARCHIVED. Do not hide those
 *    controls for terminal statuses.
 *  - **Every action on a campaign is additionally scoped by the caller's
 *    organizational boundary** through `findVisible`, which puts the tenant
 *    restriction inside the query so a foreign campaign is a 404, not a 403.
 *
 * ## UX protection, not access control
 *
 * The backend authorises every request independently and fails closed. Hiding a
 * control prevents a guaranteed 403; it grants nothing.
 */

export const CAMPAIGN_ACTION_CAPABILITY = {
  /** list / get / readiness. */
  read: Capability.CAMPAIGN_VIEW,
  /** create / update / delete / clone. */
  write: Capability.CAMPAIGN_MANAGE,
  /**
   * Status transitions, execution requests, and every call-attempt operation.
   * VERIFIED distinct from `write` — a MANAGE-only holder must not be offered
   * Schedule, Pause, Resume, Archive or Execute.
   */
  execute: Capability.CAMPAIGN_EXECUTE,
} as const;

export type CampaignAction = keyof typeof CAMPAIGN_ACTION_CAPABILITY;

export function canPerformCampaignAction(
  user: AuthenticatedUserResponse | null | undefined,
  action: CampaignAction,
): boolean {
  return hasAnyCapability(user, [CAMPAIGN_ACTION_CAPABILITY[action]]);
}

/**
 * Can this user create a campaign at all?
 *
 * Requires `CAMPAIGN_MANAGE` **and** a way to resolve a target tenant. A
 * TENANT-scoped caller creates in its own tenant and needs nothing else. A
 * PLATFORM or RESELLER caller must additionally choose an ACTIVE target
 * tenant, which the create dialog supplies via `?tenantId=`.
 *
 * The capability is necessary but not sufficient, so this is deliberately more
 * than `canPerformCampaignAction(user, "write")` — the same shape as F3's
 * `canCreateAudioAsset` and F2's `canCreateContactGroup`.
 */
export function canCreateCampaign(
  user: AuthenticatedUserResponse | null | undefined,
): boolean {
  return canPerformCampaignAction(user, "write");
}

/**
 * Does creating a campaign require the caller to choose a target tenant?
 *
 * VERIFIED `CampaignService.create` L112: the scope's own tenant wins when
 * present, and `requestedTenantId` is only consulted when it is absent. So the
 * picker is REQUIRED for platform and reseller callers and must NOT be shown to
 * a tenant caller, who would be silently ignored.
 */
export function requiresTargetTenant(operatingScope: OperatingScope | null): boolean {
  return operatingScope === "PLATFORM" || operatingScope === "RESELLER";
}

/** Where a campaign created by this caller will live. */
export interface CampaignCreateTarget {
  /**
   * The tenant to send as `?tenantId=`, or `null` when the caller's own tenant
   * context decides. `null` is correct — and required — for TENANT scope.
   */
  readonly tenantId: string | null;
  /** True when the form must not submit until a target tenant is chosen. */
  readonly isTargetRequired: boolean;
}

export function campaignCreateTargetFor(
  operatingScope: OperatingScope | null,
  chosenTenantId: string | null,
): CampaignCreateTarget {
  const isTargetRequired = requiresTargetTenant(operatingScope);
  // A select with no selection yields "", not null. Normalising here keeps the
  // return type honest — a `""` would be a tenantId that is syntactically a
  // string but semantically absent.
  const chosen = chosenTenantId && chosenTenantId.length > 0 ? chosenTenantId : null;
  return {
    tenantId: isTargetRequired ? chosen : null,
    isTargetRequired,
  };
}

/** Domain actions covered by this table, for exhaustive tests. */
export const CAMPAIGN_ACTIONS: readonly CampaignAction[] = [
  "read",
  "write",
  "execute",
];

/**
 * Capability keys that exist in the catalog but gate nothing in Campaign.
 *
 * Kept as an exported constant so a test can assert the frontend never gates on
 * them, and so the F1 capability catalogue and this module cannot drift apart
 * without a failing test.
 */
export const CAMPAIGN_DEAD_CAPABILITIES: readonly Capability[] = [
  Capability.CAMPAIGN_ASSIGN,
  Capability.CAMPAIGN_EXPORT,
];
