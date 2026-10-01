# F0 Artifact — Tenant Boundary Map

Canonical source: [`../F0-FRONTEND-AUDIT.md`](../F0-FRONTEND-AUDIT.md) §11.

## End-to-end flow (verified from source)

```
Authenticated User
  ↓
JWT — stateless. Claims: sub (user UUID), email, sid (session family)
  SecurityConfig.java:88-98 → NO roles, NO tenant, NO scope in the token
  ↓
OrganizationContextPopulationFilter
  reads SecurityContext principal → MembershipResolver / OrganizationalHomeResolver
  → OrganizationContextHolder.setAuthenticated(userId, tenantId, resellerId)
  finally { OrganizationContextHolder.clear(); }        ← leak-safe, per request
  ↓
Scope.of(OrganizationContextHolder.current().orElse(null))
  Identical usage in 13 services:
    contact/ContactGroupAccess.java:71          audio/AudioAssetService.java:289
    tts/TtsTemplateService.java:299             did/DidService.java:385
    tenant/TenantService.java:63                reseller/ResellerService.java:84
    account/UserService.java:81                 ivr/IvrTreeService.java:512,520
    voice/queue/QueueDirectoryService.java:515  voice/agent/AgentDirectoryService.java:491
    voice/agent/AgentCallQueryService.java:188  voice/outbound/AgentOutboundApiService.java:56
  ↓
AuthorizationService.requireCapability(userId, capability, AccessCheck)
  PLATFORM → true | RESELLER → coversReseller | TENANT → tenantId equality
  ↓
Repository query constrained by the resolved tenant / hierarchy / loaded entity
```

## Model

Three levels: `PLATFORM` (no organizational home) → `RESELLER` → `TENANT`.
A user has **at most one** `OrganizationalHome`, enforced by migrations V6/V7/V8/V11.
`SUPER_ADMIN` holds no home and no membership (V13:4-5).
Tenants are either direct (`reseller_id = NULL`) or reseller-managed.

`OrganizationalHomeType` = `TENANT | RESELLER` only (`authz/home/OrganizationalHomeType.java`).
Platform users have `homeType === null` in both `/auth/me` and `UserResponse`.

## How `tenantId` actually enters the system — the complete list

| Entry point | Mechanism | Who may use it | Backend enforcement |
|---|---|---|---|
| `POST /campaigns?tenantId=` | query param | platform only | tenant callers derive it from context |
| `POST /dids` body `tenantId` / `resellerId` | body | reseller (own tenants only) / platform | `DidService.java:69-103`; a reseller targeting another reseller's tenant is 400 |
| `POST /tts-templates` body `tenantId` | body | platform only | `TtsTemplateService.java:90-98`; target tenant must be `ACTIVE` |
| `POST /tenants` body `resellerId` | body | platform only | `TenantProvisioningService.java:100-102` → 403 on mismatch |
| `GET /dids?tenantId=&resellerId=` | query params | **platform only** — ignored for tenant/reseller callers | `DidService.java:155-161` |
| all list endpoints (`/tenants`, `/resellers`, `/users`, `/dids`, `/contact-groups`, `/audio-assets`, `/tts-templates`, `/campaigns`, `/queues`, `/agents`) | **none — context only** | — | tenant / reseller-hierarchy / platform branch inside each service |
| all `{id}` detail routes | **none** | — | `AccessCheck.forTenant(entity.getTenantId())` from the **loaded entity** → a foreign id yields 403/404, never a leak |

## Confirmed absences

- **No `tenantId` request header.** No `X-Tenant-Id` or equivalent anywhere.
- **No session-scoped selected-tenant state** on the backend.
- **No `Pageable`/argument resolver** that could let a caller set the tenant implicitly.
- **No `@RequestHeader` usage on any endpoint** in the audited controllers.
- Tenant identity is derived **server-side per request** from the JWT subject + DB membership.
- `OrganizationContextHolder.clear()` runs in a `finally` block, so there is no
  cross-request thread-local leakage.

## Frontend tenant state

### Present

| Mechanism | Where | Verdict |
|---|---|---|
| `tenantId` in a URL path | **nowhere** | correct |
| `tenantId` in a request header | **nowhere** | correct |
| Tenant-switching store / context / URL state | **nowhere** | **gap** (see below) |
| `createCampaign(payload, tenantId?)` → `?tenantId=` | `lib/api/campaigns.ts:102-108` | exists but **no caller supplies it** |
| `CreateTenantPayload.resellerId` | `lib/schemas/tenant-mutation.ts:70` | **hardcoded `null`** |
| `CreateDidPayload.tenantId` / `resellerId` | `lib/schemas/did-mutation.ts:80-81` | in the schema; the create dialog supplies neither |
| Client-side tenant filtering | **nowhere** | correct — the frontend does not filter by tenant |

### Correctly absent

The frontend **never** treats an arbitrary `tenantId` as a security boundary, and never
performs client-side filtering that could be mistaken for one. There is no code path where a
user-supplied tenant id is assumed to grant access. **This is the correct posture and must be
preserved.**

## Reseller tenant selection — actual behaviour

### Backend

A `RESELLER`-scoped caller:

- `GET /tenants` → `forReseller(context.resellerId())` + `hasReseller(context.resellerId())`
  — returns **the reseller's own hierarchy tenants** (`TenantService.java:108-109`).
- `POST /tenants` → `forReseller(context.resellerId())`; a body `resellerId` that deviates
  from the caller's own reseller is **403** (`TenantProvisioningService.java:100-102`).
- `GET /resellers` → restricted to `hasId(context.resellerId())` (own row only).
- Every other domain list → `forReseller(resellerId)`, and `coversReseller` resolves a TENANT
  target through `TenantHierarchyResolver.resellerIdOf(tenantId)`
  (`AuthorizationService.java:150-166`), i.e. **hierarchy-wide read access**.
- `POST /dids` with a body `tenantId` → permitted, but only for a tenant owned by the caller's
  own reseller.

So the backend provides **hierarchy-wide aggregation**, not tenant impersonation.

### Frontend

A reseller admin gets a working `/tenants` list and detail pages, but:

- **There is no tenant picker** and no `selectedTenantId` state.
- **There is no way to act within a selected tenant.** Every other domain list is served by
  the backend's hierarchy-wide read, so the reseller sees an aggregated view with no
  drill-down context and no "now acting as tenant X" affordance.
- The create-tenant dialog **cannot** place a tenant under a reseller
  (`schemas/tenant-mutation.ts:70` hardcodes `resellerId: null`), so the reseller flow is
  unreachable from the UI.

### Open question

> **UNKNOWN — requires confirmation (U3).** Should a reseller be able to *select and act as* a
> tenant? Nothing in either repository defines a tenant-switching product model. This decision
> blocks F3 and therefore the shape of every tenant-scoped screen.

## Guidance for the frontend migration

1. **Never** derive authority from a `tenantId` the user picked. The backend authorizes the
   selected tenant; the UI may only reflect the outcome.
2. **Do not add a `tenantId` header or query param to reads.** The backend does not accept
   them, and adding them would create a false impression of client-side scoping.
3. `homeType` from `/auth/me` is the **only** trustworthy scope signal; capabilities are a
   scope-**union** (`AuthorizationService.java:174-176`) and cannot be used to infer scope.
4. The `resellerId: null` hardcode must be removed before the reseller tenant flow can work.
5. A tenant-switching feature, if approved (U3), must be backed by a backend change or an
   explicit, documented "aggregated view" UX — not by a client-side filter.
