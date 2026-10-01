import { Capability, hasAnyCapability } from "@/lib/auth/capabilities";
import type { AuthenticatedUserResponse } from "@/lib/api/contracts";

/**
 * Which ENFORCED capability gates each Contacts / Contact Groups action.
 *
 * ## Why this table exists
 *
 * The backend capability catalogue contains FOUR contact keys — `CONTACT_VIEW`,
 * `CONTACT_MANAGE`, `CONTACT_IMPORT`, `CONTACT_EXPORT` — but
 * `ContactGroupService` only ever checks the first two:
 *
 *   | Action                        | Service call site            | Enforced capability |
 *   |-------------------------------|------------------------------|---------------------|
 *   | list groups                   | `listGroups`  L128/131/138   | `CONTACT_VIEW`      |
 *   | get group                     | `getGroup`    L114           | `CONTACT_VIEW`      |
 *   | create group                  | `createGroup` L106           | `CONTACT_MANAGE`    |
 *   | update group                  | `updateGroup` L156           | `CONTACT_MANAGE`    |
 *   | delete group                  | `deleteGroup` L173           | `CONTACT_MANAGE`    |
 *   | list / get contacts           | `listContacts` L226, `getContact` L248 | `CONTACT_VIEW` |
 *   | create / update / delete contact | L198, L261, L296          | `CONTACT_MANAGE`    |
 *   | import contacts               | `importContacts`             | `CONTACT_MANAGE`    |
 *   | export contacts               | `exportContacts`             | `CONTACT_VIEW`      |
 *   | list / get members            | `listMembers` L70, `getMember` L83 | `CONTACT_VIEW` |
 *   | add / remove member (+batch)  | L100, L127, L135, L162       | `CONTACT_MANAGE`    |
 *
 * `CONTACT_IMPORT` and `CONTACT_EXPORT` are seeded (V1) and granted to the same
 * roles, but **no service checks them**. Gating the Import button on
 * `CONTACT_IMPORT` would therefore be a guess: it happens to produce the same
 * answer for the seeded roles, and would silently break the moment role
 * assignments diverged from the enforced set. This table records what the
 * service actually checks.
 *
 * ## It is UX protection, not access control
 *
 * The backend authorises every request independently and fail-closed. Hiding a
 * control prevents a guaranteed 403; it is never the security boundary. Nothing
 * here grants anything.
 *
 * ## Not a second permission system
 *
 * This is a lookup table over the F1 `Capability` catalogue plus a call to the
 * F1 `hasAnyCapability` predicate. It adds no new capability names, no new
 * predicate, and no new source of truth for what a user holds.
 */
export const CONTACT_ACTION_CAPABILITY = {
  /** Read anything: groups, contacts, members, and the contact export. */
  read: Capability.CONTACT_VIEW,
  /** Create, update and delete groups, contacts and memberships. */
  write: Capability.CONTACT_MANAGE,
  /**
   * Bulk import. NOTE this is `CONTACT_MANAGE`, not `CONTACT_IMPORT` — see the
   * table above. `importContacts` is gated on the same key as every other write.
   */
  import: Capability.CONTACT_MANAGE,
  /**
   * Bulk export. NOTE this is `CONTACT_VIEW`, not `CONTACT_EXPORT` — the service
   * authorises export with the read capability, so anyone who can see a group
   * can export it.
   */
  export: Capability.CONTACT_VIEW,
} as const;

export type ContactAction = keyof typeof CONTACT_ACTION_CAPABILITY;

/**
 * `true` when the user holds the capability the BACKEND enforces for `action`.
 *
 * This is the function the Contacts and Contact Groups UI calls, instead of
 * passing a capability name at each call site, so that "which key gates Import"
 * is answered in exactly one place.
 */
export function canPerformContactAction(
  user: AuthenticatedUserResponse | null | undefined,
  action: ContactAction,
): boolean {
  return hasAnyCapability(user, [CONTACT_ACTION_CAPABILITY[action]]);
}

/**
 * Can this user create a NEW contact group?
 *
 * ## This is not a capability check, and it is not invented
 *
 * `POST /api/v1/contact-groups` is the one Contacts write that is NOT authorised
 * against an existing resource. `ContactGroupService.createGroup` (L98-110) does:
 *
 * ```java
 * ContactGroupAccess.Scope scope = groupAccess.currentScope();
 * UUID tenantId = scope.tenantId();
 * if (tenantId == null) {
 *     throw business("A tenant must be specified for this operation.");
 * }
 * ```
 *
 * There is no `tenantId` field in `CreateContactGroupRequest` and no header or
 * query parameter that supplies one — `CreateContactGroupRequest` is
 * `record (String name, String description)`. A group can therefore only be
 * created by a caller whose server-derived organization context HAS a tenant.
 *
 * ## Why the client can know this without guessing
 *
 * `Scope.tenantId` and `/me`'s `homeType` are the SAME fact read from the SAME
 * row. `TenantMembershipResolverAdapter.resolvePrimaryTenantId` is
 * `homeRepository.findByUserId(userId).filter(h -> h.getHomeType() == TENANT)
 * .map(h -> h.getOrganizationId())`, and `UserService` populates
 * `homeType`/`organizationId` from that same `findByUserId` result
 * (`UserService.java:209`). So
 * `homeType === "TENANT"` ⟺ `context.tenantId != null` — an exact equivalence,
 * not a heuristic.
 *
 * ## Who this actually excludes
 *
 * A `RESELLER_ADMIN` is granted `CONTACT_MANAGE` by V1, so a
 * capability-only check would show them a Create button that can only ever
 * return `400 "A tenant must be specified for this operation."` A
 * `SUPER_ADMIN` has no organizational home at all, so the same. Both are real
 * accounts, not hypotheticals.
 *
 * Note the asymmetry, which is easy to get backwards: only CREATION needs a
 * tenant. `updateGroup`, `deleteGroup`, the contact mutations and every
 * membership mutation are authorised through `authorizedGroup(groupId, …)`,
 * which only requires the group to be VISIBLE — so a reseller can edit, delete,
 * import and manage memberships in the groups they can already see. Restricting
 * those would be wrong.
 */
export function canCreateContactGroup(
  user: AuthenticatedUserResponse | null | undefined,
): boolean {
  return (
    canPerformContactAction(user, "write") &&
    // `homeType` is the wire for the same row `Scope.tenantId` is read from.
    user?.homeType === "TENANT"
  );
}

/** Domain actions a component is expected to gate, for exhaustive tests. */
export const CONTACT_ACTIONS: readonly ContactAction[] = [
  "read",
  "write",
  "import",
  "export",
];
