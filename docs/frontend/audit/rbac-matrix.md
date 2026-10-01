# F0 Artifact — RBAC Matrix

Canonical source: [`../F0-FRONTEND-AUDIT.md`](../F0-FRONTEND-AUDIT.md) §10, §18.1.

## 1. Mechanism

**No Spring method security.** `@EnableMethodSecurity` is present
(`security/config/SecurityConfig.java:31`) but **unused**: `@PreAuthorize`, `@Secured` and
`@RolesAllowed` appear **zero times** in `backend/src`.

`SecurityConfig.securityFilterChain` (L44-68) does authentication only:
`permitAll` on `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`,
`/api/v1/account/signup/**`, `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`,
`/actuator/health/**`; then `.anyRequest().authenticated()`.

Authorization is a **custom capability engine**:

```
AuthorizationService.hasCapability(userId, capabilityKey, AccessCheck)   L59-71
  1. findCapabilityId(key)          → capabilities table, active=true     L127-131
     (missing row ⇒ null ⇒ FALSE — fail-closed)
  2. collectAssignments(userId)     → Platform + Reseller + Tenant readers L133-137
  3. .filter(covers(assignment, target))                                 L140-148
  4. .anyMatch(roleHasCapability(roleId, capabilityId))                  L168-170

covers():  PLATFORM → true | RESELLER → coversReseller | TENANT → tenantId equals
           OWN / ASSIGNED → false (deferred to ResourceAuthorizationPolicy)
coversReseller(): resellerId equals, else resolve the target tenant's owning
           reseller through TenantHierarchyResolver
```

`AccessCheck` = `record AccessCheck(UUID resellerId, UUID tenantId)` with factories
`platformWide()` (null,null), `forReseller(id)`, `forTenant(id)`.
`TenantService` additionally builds a combined check `new AccessCheck(resellerId, tenantId)`.

`requireCapability` throws `ForbiddenException` (generic, non-leaking 403). Tenant isolation
is the strongest boundary: a resource in a different organizational context is denied
regardless of ownership.

## 2. Roles — `V1__create_multi_tenant_authorization_foundation.sql:155-160`

| Role key | Name | Scope | Note |
|---|---|---|---|
| `SUPER_ADMIN` | Super Admin | `PLATFORM` | holds **no** organizational home and no membership (V13:4-5); authority flows purely through `PLATFORM`-scoped grants |
| `RESELLER_ADMIN` | Reseller Admin | `RESELLER` | assigned at reseller creation (`ResellerProvisioningService.java:31,114`) |
| `TENANT_ADMIN` | Tenant Admin | `TENANT` | assigned at tenant creation (`TenantProvisioningService.java:38,142`) |
| `AGENT` | Agent | `ASSIGNED` | also assigned per tenant (`TenantProvisioningService.java:39,128`) |
| `REPORT_VIEWER` | Report Viewer | `TENANT` | seeded in V1; **no controller enforces `REPORT_VIEW`/`REPORT_EXPORT`** |

`Scope` enum (`authz/Scope.java:3-8`): `PLATFORM, RESELLER, TENANT, OWN, ASSIGNED`.

## 3. Capability catalog — 34 keys

| Migration | Keys added |
|---|---|
| V1 (27) | `TENANT_VIEW/MANAGE`, `RESELLER_VIEW/MANAGE`, `USER_VIEW/MANAGE`, `ROLE_VIEW/MANAGE`, `CAMPAIGN_VIEW/MANAGE/EXECUTE/ASSIGN/EXPORT`, `CONTACT_VIEW/MANAGE/IMPORT/EXPORT`, `AUDIO_VIEW/MANAGE/APPROVE`, `CALL_VIEW/DISPOSITION`, `AGENT_VIEW/MANAGE/ASSIGN`, `REPORT_VIEW/EXPORT` |
| V16 | `DID_VIEW`, `DID_MANAGE` |
| V20 | `TTS_VIEW`, `TTS_MANAGE`, `TTS_APPROVE` |
| V37 | `QUEUE_VIEW`, `QUEUE_MANAGE` |
| **never seeded** | **`IVR_VIEW`, `IVR_MANAGE`** — enforced in code, absent from every migration. See §5 |

## 4. Role → capability grants (as seeded)

| Role | Granted (V1 + V16 + V20 + V37) |
|---|---|
| `SUPER_ADMIN` | **all 33 seeded** |
| `RESELLER_ADMIN` | `RESELLER_VIEW/MANAGE`, `TENANT_VIEW/MANAGE`, `USER_VIEW/MANAGE`, `ROLE_VIEW`, `CAMPAIGN_VIEW/MANAGE/EXECUTE/EXPORT`, `CONTACT_*`, **`AUDIO_VIEW/AUDIO_APPROVE` (not `AUDIO_MANAGE`)**, `AGENT_VIEW/MANAGE/ASSIGN`, `CALL_VIEW`, `REPORT_VIEW/EXPORT`, `DID_VIEW/MANAGE`, `TTS_VIEW/MANAGE/APPROVE`, `QUEUE_VIEW/MANAGE` |
| `TENANT_ADMIN` | `TENANT_VIEW`, `USER_VIEW/MANAGE`, `ROLE_VIEW/MANAGE`, `CAMPAIGN_VIEW/MANAGE/EXECUTE/ASSIGN/EXPORT`, `CONTACT_*`, `AUDIO_VIEW/MANAGE/APPROVE`, `CALL_VIEW/DISPOSITION`, `AGENT_VIEW/MANAGE/ASSIGN`, `REPORT_VIEW/EXPORT`, `DID_VIEW/MANAGE`, `TTS_VIEW/MANAGE/APPROVE`, `QUEUE_VIEW/MANAGE` |
| `AGENT` | `CALL_VIEW`, `CALL_DISPOSITION` **only** |
| `REPORT_VIEWER` | `REPORT_VIEW`, `REPORT_EXPORT` only — and neither is enforced by any controller |

## 5. Backend finding — IVR is unreachable for every role

`IvrTreeService.java:77,80` declares `CAP_VIEW = "IVR_VIEW"` and `CAP_MANAGE = "IVR_MANAGE"`,
enforced at lines 111, 137, 146, 179, 208, 247.
`AuthorizationService.findCapabilityId` (L127-131) resolves the key against the `capabilities`
table; **a missing row yields `null`, and `hasCapability` returns `false` at L65-66**, so
`requireCapability` throws `ForbiddenException`.
A grep for `IVR_VIEW|IVR_MANAGE` across **all 55 migrations returns no matches**;
`V53__ivr_trees.sql` contains no `capabilities`/`role_capabilities` insert.

**Impact:** all 6 `/api/v1/ivr-trees` endpoints and `POST /campaigns/{id}/ivr-tree` return
**403 for `SUPER_ADMIN` and every other role.**

**Frontend impact:** none yet (no IVR surface), but it blocks any IVR work on day one.

**Recommended follow-up (backend owner, not F0):** a migration inserting `IVR_VIEW` /
`IVR_MANAGE` and granting them to `SUPER_ADMIN`/`RESELLER_ADMIN`/`TENANT_ADMIN`, mirroring
`V20` (TTS) and `V37` (QUEUE). Note that IVR is additionally **tenant-only** —
`IvrTreeService.java:511-517` has no reseller or platform branch — so even after the capability
fix it remains unreachable for platform/reseller users.

## 6. Evidence-based access matrix

| Role | Scope | Backend access (enforced) | Frontend routes | Frontend actions gated | Evidence |
|---|---|---|---|---|---|
| `SUPER_ADMIN` | `PLATFORM` — `covers()` true for every target (`AuthorizationService.java:143`) | every capability endpoint. `PUT`/`GET /resellers/{id}` use `forReseller(id)`, so a platform caller must still match the id | all 9 nav items; `Tenants`/`Resellers` unblocked by the `homeType === null` check (`navigation.ts:62-63`) | **none** | `V1:216` |
| `RESELLER_ADMIN` | `RESELLER` | can create tenants **under itself only** (a body `resellerId` mismatch is 403, `TenantProvisioningService:100-102`); **cannot** create resellers (`ResellerService.java:64-65` is `platformWide`); **cannot** delete resellers (`:123` `platformWide`); lacks `AUDIO_MANAGE`; **cannot** assign/revoke DIDs for another reseller; cannot create a user (`UserController` has no create) | all 9 except `Resellers` | **none** | `V1:237-250`, `V16:68`, `V20:75`, `V37:133` |
| `TENANT_ADMIN` | `TENANT` | full tenant-scoped campaign/contact/audio/tts/did/queue/agent/agent-call surface. **Cannot** assign or transfer DIDs — 400 "Tenants cannot assign or transfer DID inventory." (`DidService.java:230-232,259-261`). **Cannot** create tenants or agents (both `platformWide`) | all 9 except `Tenants`/`Resellers` | **none** | `V1:257-266` |
| `AGENT` | `ASSIGNED` | `CALL_VIEW` + `CALL_DISPOSITION` only. `hasResourceAccess` (L98-103) requires a registered `ResourceAuthorizationPolicy`; the only implementation is the test double `test/.../TestResourcePolicy.java` — so **`OWN`/`ASSIGNED` grants are not resolvable in production** | **none** — the sidebar is empty because no nav item requires a capability this role has | n/a | `V1:269-275`; `AuthorizationService.java:98-103` |
| `REPORT_VIEWER` | `TENANT` | nothing enforced — `REPORT_VIEW`/`REPORT_EXPORT` are seeded but used by **no** controller in `src/main` | **none** | n/a | `V1:281-287` |

## 7. Frontend gating reality

`getVisibleNavItems` (`components/layout/navigation.ts:50-75`) ANDs:
1. a capability check using `user.capabilities.some(...)` (OR semantics across
   `requiredCapabilities`; moot today because every item supplies exactly one), then
2. an optional `requiredScope` check. Only `"platform"` is ever used, implemented as
   `user.homeType !== null` — correct, because `OrganizationalHomeType` has no `PLATFORM`
   member and platform users have no home. The `"reseller"` and `"tenant"` branches are
   unreachable (no item declares them).

### Critical scoping caveat

`GET /auth/me` returns `AuthorizationService.getAllCapabilitiesForUser(userId)`, documented
in-line as *"Does not apply scope filtering - returns the union of all capabilities"*
(`AuthorizationService.java:174-176`).

**Consequence:** a user with `TENANT_VIEW` receives it in `/me` whether or not they are a
platform administrator. **Capability presence cannot be used to infer organizational scope.**
`homeType` is the only scope signal available, and it is `null` for every platform user.

### 12 declared-but-never-checked capabilities

`USER_MANAGE`, `TENANT_MANAGE`, `RESELLER_MANAGE`, `DID_MANAGE`, `CAMPAIGN_MANAGE`,
`CAMPAIGN_EXECUTE`, `CONTACT_MANAGE`, `AUDIO_MANAGE`, `AUDIO_APPROVE`, `TTS_MANAGE`,
`TTS_APPROVE` — plus `hasAnyCapability`, `hasAllCapabilities`, `hasPlatformAccess`,
`hasResellerAccess`, `hasTenantAccess` (all zero callers).

**Observable consequence:** `components/audio-assets/audio-assets-view.tsx:50,53` and
`components/tts-templates/tts-templates-view.tsx:40,41` invoke the approve/reject mutations and
render those actions with **no capability check**. A `RESELLER_ADMIN` — who is granted
`AUDIO_APPROVE` but **not** `AUDIO_MANAGE` — will see approve/reject controls whose surrounding
create/edit controls they cannot use, and any user lacking `AUDIO_APPROVE` will see buttons
that return 403.

### Invented concept

`hasPlatformAccess()` (`lib/auth/capabilities.ts:75-78`) is
`homeType === null && hasCapability(TENANT_VIEW)`. It has **no backend counterpart** and zero
callers. **UNKNOWN — requires confirmation (U2).** Do not build on it.

## 8. Rule for the frontend

> **Frontend visibility is not the security boundary. Backend authorization is
> authoritative.**

Capability gating in the UI exists only to avoid presenting actions that will 403. It must
never be treated as an access control, and the 403 branch in every list view
("You don't have permission to view …" / "Ask a platform administrator for access.") is the
correct backstop.
