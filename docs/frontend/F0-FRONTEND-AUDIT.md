# F0 — Frontend Architecture & Backend Contract Audit

**Status:** Complete (read-only audit; no application source was modified)
**Date:** 2026-09-28
**Repository:** `D:\work\agile\obd-platform` (single monorepo, branch `main`, HEAD `3a89e5c`)
**Method:** Static repository inspection + execution of existing safe validation commands.

> **Scope note.** This document reports what the repositories contain. Where something was
> not found, the wording is "Not found in audited scope" — never "does not exist".
> Backend behaviour is treated as the source of truth. No backend or frontend application
> code was changed during F0.

---

## 1. Executive Summary

**Frontend architecture.** A single Next.js 16.2.10 App Router application using React 19.2.4
and TypeScript 5, with Tailwind CSS 4 and shadcn/ui (Radix) components. Server state is
managed entirely by TanStack Query; tables by TanStack Table; forms by React Hook Form + Zod 4;
the API layer is a hand-written axios client behind a single Next.js rewrite proxy. All
authenticated pages live in one `(platform)` route group behind a **client-side render gate**,
not a server-side guard. There are **no frontend tests and no test runner**.

**Backend architecture.** Spring Boot 4.1.0 / Java 17 monorepo module set (Spring Modulith),
REST under `/api/v1`, a uniform `ApiResponse<T>` envelope, RFC 9457 `ProblemDetail` errors,
Flyway + PostgreSQL, Redis, and springdoc OpenAPI served at runtime (no committed spec file).
Authorization is **not** Spring method security: `@EnableMethodSecurity` is present but
**entirely unused** — there is not a single `@PreAuthorize`/`@Secured`/`@RolesAllowed` in the
codebase. All authorization flows through a custom capability engine (`authz` module) that
combines DB-driven role/capability grants with `PLATFORM/RESELLER/TENANT/OWN/ASSIGNED` scope
resolution, backed by a server-side `ThreadLocal` organization context.

**Overall contract state.** The frontend is a **faithful but incomplete and partially stale
client**. Where it exists, it mostly hits the right endpoints with the right envelopes; the
failures are omission and drift rather than invented APIs. Concretely: 1 hard enum mismatch
(`MISSED_CALL` missing), 6 partially-modelled response DTOs, 2 genuinely wrong request
shapes, 1 major unexercised endpoint (audio upload), 3 entire backend domains with no frontend
at all (IVR, Queues, Agents), and 12 capability keys that the backend enforces but the
frontend never checks.

**Major risks.**

1. `IVR_VIEW` / `IVR_MANAGE` are enforced in `IvrTreeService` but are **never seeded into the
   `capabilities` table by any migration** — every IVR endpoint returns 403 for every role,
   including `SUPER_ADMIN`. Backend defect; documented, not fixed.
2. `/auth/me` returns a **flat, unscoped union** of all capabilities across all organizational
   scopes. Capability presence therefore cannot be used to infer scope in the UI.
3. The frontend has **no notion of acting as a tenant**. A reseller admin can list their
   hierarchy tenants but has no way to select one and operate in it; `toCreateTenantPayload`
   hardcodes `resellerId: null`.
4. Audio asset creation requires the user to hand-type a 64-char SHA-256 checksum and storage
   reference because the real multipart `POST /audio-assets/upload` is never called.
5. Zero frontend test coverage; no contract-drift detection on the client side.

**Major migration themes.** Contract alignment (enums, DTO fields, request shapes) →
RBAC gating of actions → tenant/context selection → Contacts & Groups → Audio upload → TTS
scope split → Campaign type-specific configuration → Execution & attempt visibility →
Queue/Agent surface required to unblock `CONNECT_BY_AGENT`.

---

## 2. Repository Map

Actual top-level layout of `D:\work\agile\obd-platform`:

| Path | Responsibility | Notes |
|---|---|---|
| `backend/` | Spring Boot 4.1.0 API, 549 main Java files, 55 Flyway migrations (`V1`–`V55`, **`V43` absent**), 179 test files | Wrapper `mvnw`/`mvnw.cmd`; `application.yaml` sets profile `dev` by default; `application-dev.yaml` present |
| `frontend/` | Next.js 16.2.10 App Router app, ~185 source files under `src/` | `node_modules/` and `.next/` present, so tooling is installed |
| `docs/` | Project context (`00_`–`06_`), per-sprint audit/implementation reports (`VB-*`, `LIVE-FREESWITCH-*`), `campaign-readiness.md`, `freeswitch/`, `voice-routing-and-capacity.md` | `docs/api/` exists but is **empty** — no committed OpenAPI document |
| `infra/` | Docker Compose: `docker-compose.yml`, `docker-compose.freeswitch.yml`, `docker-compose.freeswitch-endpoints.yml`, `.env.example` | FreeSWITCH compose files are untracked (`??` in `git status`) |
| `infrastructure/` | `docker/`, `kubernetes/`, `monitoring/` | Deployment shape only |
| `mock/` | `contacts.csv`, `contacts_test_india.json` | Test fixtures for backend import, **not** consumed by the frontend |
| `module/` | Design notes: `AUTH_`, `TENANT_`, `USER_`, `LEAD_`, `CAMPAIGN_`, `AUDIO_`, `DIALER_` | Documentation |
| `progress/` | `CURRENT_SPRINT.md`, `DECISIONS.md`, `DEVELOPMENT_LOG.md`, `KNOWN_ISSUES.md` | Planning records |
| `tools/` | `freeswitch-harness/` | Untracked |
| `.ai/` | `context/`, `skills/`, `prompts/`, `workflows/` referenced by `.cursorrules` | Agent guidance |
| `.github/` | Present but **empty** | No CI workflows found |

There are **no shared packages** between frontend and backend — no generated API client, no
OpenAPI-derived types, no shared schema repository. The TypeScript contracts in
`frontend/src/lib/api/contracts.ts` are hand-maintained and carry a header comment
(`types.ts:1-6`) asserting they are "mirrored 1:1 from the Spring Boot backend". This audit
shows that claim is currently **partially false** (§7, §8).

---

## 3. Frontend Architecture Map

```
Application
├── Entry points
│   ├── src/app/layout.tsx                 root layout; Geist/Geist_Mono; <Providers>
│   ├── src/app/page.tsx                   session-aware redirect: "/" → /account | /sign-in
│   └── src/components/providers.tsx       QueryClientProvider + TooltipProvider + Toaster
├── Routing
│   ├── Route groups: (auth)  3 pages  |  (platform)  16 pages
│   ├── No middleware.ts, no error.tsx, no not-found.tsx, no loading.tsx
│   └── All detail pages are async server components; `params` is a Promise (Next 16)
├── Layouts
│   ├── src/app/(auth)/layout.tsx
│   └── src/app/(platform)/layout.tsx      "use client"; render-phase auth gate
├── Authentication
│   ├── src/lib/auth/token-store.ts        access token in memory only; purges legacy localStorage key
│   ├── src/lib/session.ts                 useSession / useLogin / useLogout / useLogoutAll / useChangePassword
│   └── src/components/layout/user-menu.tsx
├── Authorization
│   ├── src/lib/auth/capabilities.ts       Capability const map + hasCapability helpers
│   ├── src/components/layout/navigation.ts  getVisibleNavItems (capability AND requiredScope)
│   └── NO per-action capability gating anywhere else
├── API layer
│   ├── src/lib/api/client.ts              axios instance, baseURL /api/v1, 401 single-flight refresh
│   ├── src/lib/api/*.ts                   14 domain modules, hand-written
│   ├── src/lib/api/types.ts               envelope + ProblemDetail + PaginationMetadata
│   ├── src/lib/api/contracts.ts           hand-written DTO mirror (576 lines)
│   └── src/lib/api/error.ts               ApiError + toApiError
├── State/query layer
│   ├── src/lib/query-client.ts            single shared QueryClient (retry 1, staleTime 30s)
│   ├── TanStack Query 5.101.2  — zustand declared in package.json but ZERO imports
├── Domain modules (components/<domain>/)
│   account, audio-assets, campaigns, contact-groups, contacts, dids, resellers,
│   tenants, tts-templates, users, common, forms, layout, ui
├── Shared components
│   ├── components/common/  17 items — table-pagination, table-skeleton, 5 filter toolbars, 10 status badges
│   ├── components/forms/   select-field, text-field, textarea-field
│   └── components/ui/      24 shadcn primitives
├── Forms
│   ├── src/lib/schemas/*.ts  11 Zod schema modules + payload mappers
│   └── react-hook-form 7.80 + @hookform/resolvers 5.4 + zod 4.4.3
├── Tables
│   └── TanStack Table 8.21 in every *-table.tsx
├── Navigation
│   └── components/layout/navigation.ts (definition) → app-sidebar.tsx, site-header.tsx
├── Error handling
│   └── per-view Alert blocks + toApiError; NO global boundary
├── Loading states
│   └── TableSkeleton + data-[pending=true] opacity shimmer (per view)
├── Notifications
│   └── sonner 2.0.7, 32 call sites, consistent toast.success / toast.error(toApiError(e).message)
├── Styling
│   ├── Tailwind CSS 4 (CSS-first, src/app/globals.css), CSS variables
│   └── components.json: style "radix-nova", baseColor neutral, cssVariables true
├── Utilities
│   ├── lib/utils.ts (cn), lib/format.ts (formatDateTime — the only export)
│   └── hooks/use-url-list-state.ts, use-debounced-value.ts, use-mobile.ts
├── Testing
│   └── NONE. No test script, no runner, no test files.
└── Configuration
    ├── next.config.ts   rewrite /api/:path* → ${API_ORIGIN}/api/:path* (default http://localhost:8081)
    ├── frontend/.env    API_ORIGIN=http://localhost:8081
    ├── tsconfig.json, eslint.config.mjs (eslint-config-next core-web-vitals + typescript)
    └── components.json
```

**Architectural pattern.** Feature/domain-oriented folder layout with a single shared
infrastructure tier (`lib/api`, `lib/schemas`, `components/common`, `components/ui`). This is
a reasonable and consistent structure. It is **not** a layered/clean-architecture layout, and
that is not a defect.

**API abstraction quality — mixed.**
*Good:* one axios instance, one place for refresh, uniform envelope unwrapping, uniform
`toApiError`.
*Weak:* the `unwrap` helper lives inside `auth.ts` rather than a transport module; three
different unwrap patterns coexist (helper, manual destructure for lists, raw for 204/blob);
`unwrap`'s doc comment (`auth.ts:10`) claims it throws `ApiError` but it does not.

**Separation between API DTOs and UI models — absent.** `contracts.ts` types are used
directly in components. There is no mapping layer, so backend DTO shape is the UI shape. This
is the root cause of several §8 findings: an absent backend field and an unmodelled backend
enum both become UI defects rather than being contained in a mapper.

**Duplication confirmed by grep.**
- `ContactImportResponse` and `ContactImportError` are each declared **twice in
  `contracts.ts`** (lines 256/266 and 308/318). `npx tsc --noEmit` passes, so this is legal
  TypeScript interface declaration merging — **not** a compile error. It is still drift risk.
- `importContacts` / `exportContacts` are byte-identical in `contact-groups.ts:97-116` and
  `contacts.ts:98-117`.
- Enum unions re-declared locally: `DidStatus`/`AllocationState`/`NumberType`/`DidCapability`
  in `dids.ts:124-134`, `AudioAssetStatus` in `audio-assets.ts:104`, `TtsTemplateStatus` in
  `tts-templates.ts:103` — shadowing the `contracts.ts` originals.
- Attempt transition tables duplicated in `api/call-attempts.ts:169-182` and
  `lib/schemas/call-attempt-mutation.ts:39-52`.
- `toCreatePayload`/`toUpdatePayload` exported from both `audio-asset-mutation.ts` and
  `tts-template-mutation.ts`.

**Hard-coded values found.**
- `next.config.ts:5` — dev origin fallback `http://localhost:8081`.
- `api/campaigns.ts:210-262` — reference-data fetchers hardcode `status:"ACTIVE"`,
  `allocationState:"ASSIGNED"`, `status:"APPROVED"` and **`size: 100`** (a silent truncation cap).
- `schemas/tenant-mutation.ts:70` — **`resellerId: null` hardcoded** in `toCreateTenantPayload`.
- `edit-campaign-dialog.tsx:99` — `campaign?.campaignType ?? "PLAYFILE"`.
- `components/campaigns/campaigns-view.tsx:213-221` — two live `TODO` stubs
  (`onClone`, `onStatusChange`) that call `window.location.reload()`.

**No mock data.** Grep for `mock|sample data|fake|placeholder data` across `frontend/src`
returns zero matches. All data flows from `src/lib/api/*` through TanStack Query. The
untracked `mock/` directory at repo root is backend import test data, not frontend fixtures.

**Security-relevant findings in the frontend.**
- Access token is in-memory only and never persisted; the refresh token is never readable by
  client script. This is correct and matches the backend design.
- `LEGACY_REFRESH_TOKEN_STORAGE_KEY = "obd.refresh-token"` (`token-store.ts:16`) is actively
  purged on module load. Good hygiene for a pre-cookie era.
- The `(platform)` subtree **server-renders for anonymous requests** and only redirects
  post-hydration (`(platform)/layout.tsx:1, 88-96`). This is a UX/defence-in-depth concern,
  not a data breach — every API call is independently authorized by the backend.
- `redirectToSignIn()` (`client.ts:104-111`) preserves `?next=`, but the layout guard
  (`layout.tsx:88-92`) redirects **without** `?next=`. Deep links are lost on that path.

---

## 4. Backend Architecture Map

```
com.shivang.obd
├── account/        UserController (3 endpoints) + UserService + UserSpecifications
├── audio/          AudioAssetController (8) + service, entity, storage abstraction, upload validator
├── authz/          AuthorizationService, AccessCheck, Scope, ResourceAuthorizationPolicy,
│                   RoleAssignmentReader(+platform/reseller/tenant adapters),
│                   context/OrganizationContextHolder, home/OrganizationalHome*
├── campaign/       CampaignController (15) + CampaignIvrController (1), config/ (13 typed configs),
│                   dto/ (10), services for execution/attempt/orchestration/scheduling/readiness
├── common/         api/response (ApiResponse, PaginationMetadata, ResponseFactory, RequestIdFilter),
│                   api/error (CommonErrorCode, FieldError), exception/GlobalExceptionHandler,
│                   lifecycle/LifecycleStatus, audit/ (AuditableEntity, BaseEntity)
├── contact/        ContactGroupController (18) + entities, identity, import/export readers
├── did/            DidController (7) + service, 6 enums
├── identity/       UserEntity, UserCredential, SuperAdminBootstrapper, EmailNormalizer
├── ivr/            IvrTreeController (6) + service (TENANT-ONLY)
├── reseller/       ResellerController (5) + ResellerSignupController (1) + provisioning
├── security/       AuthController (6), token/ (JWT + refresh rotation), ratelimit/ (Redis),
│                   detection/, event/, maintenance/, web/ClientIpResolver, config/SecurityConfig
├── telephony/      ESL client, FreeSWITCH dialer, event processing (infrastructure — no REST contract)
├── tenant/         TenantController (6) + TenantSignupController (1) + provisioning
├── tts/            TtsTemplateController (7) + service (GLOBAL vs TENANT scope)
└── voice/          acd, agent (17 endpoints), call, capacity, dtmf, eligibility, endpoint,
                    inbound, ivr, media, outbound, queue (12 endpoints), routing
```

**Cross-cutting contracts.**
- Envelope: `ApiResponse<T>(boolean success, T data, String message, PaginationMetadata pagination, ResponseMetadata meta)` with `@JsonInclude(NON_NULL)` (`common/api/response/ApiResponse.java:5-6`).
- Pagination: `PaginationMetadata(page, size, totalElements, totalPages, hasNext, hasPrevious)`; `size` clamped **1..100** in every service; `page` floored at 0.
- Sorting: explicit `String[] sort` request params, `"field,direction"`. Direction is ASC **only** for the literal `"asc"`; everything else is DESC.
- Soft delete: `AuditableEntity` supplies `deletedAt`/`deletedBy`; all list queries exclude soft-deleted rows.
- Tenant scoping: `OrganizationContextHolder` (ThreadLocal) → `Scope.of(context)`; every service derives visibility from it.
- Filtering: entity-specific `XxxSpecifications` (JPA Criteria). Search param is `search` everywhere **except DID, which uses `q`**.
- OpenAPI: springdoc 2.8.5, served at `/v3/api-docs` and `/swagger-ui` (both `permitAll`). **No committed spec file** — `docs/api/` is empty.

**Tenant model.** Three-level hierarchy: `PLATFORM` (no organizational home) → `RESELLER` →
`TENANT`. A `UserEntity` has at most one `OrganizationalHome` (enforced by V6/V7/V8/V11).
`SUPER_ADMIN` holds **no** home and no membership (V13). Tenants may be direct
(`reseller_id = NULL`) or reseller-managed. Scope resolution for a RESELLER-scoped caller
against a TENANT target goes through `TenantHierarchyResolver.resellerIdOf(tenantId)`.

**API versioning.** Path-based `/api/v1`. No header negotiation, no deprecation shims.

---

## 5. Domain Map

Domains actually supported by the repositories, and their frontend coverage:

| Domain | Backend | Frontend coverage | Verdict |
|---|---|---|---|
| **Auth** | `security/AuthController` — 6 endpoints | Complete | **Covered** |
| **Authorization** | `authz/` capability engine, 34 capabilities, 5 roles | Partial — nav only | **Under-built** |
| **Tenant** | `tenant/` — 6 + signup | List/detail/create/edit/delete | Covered; no agent creation, `resellerId` stuck at `null` |
| **Reseller** | `reseller/` — 5 + signup | List/detail/create/edit/delete | Covered |
| **Users** | `account/` — 3 (read + update only) | List/detail/update | Covered (backend has no create/delete either) |
| **Contacts** | `contact/` — 18 endpoints, **all under `/contact-groups`** | CRUD + import/export via group; `/members` roster **unused** | Covered, sub-optimal |
| **Contact Groups** | `contact/` — 8 endpoints | Full CRUD + members UI + import/export | Covered |
| **Audio** | `audio/` — 8 incl. multipart upload | Metadata CRUD + approve/reject; **upload not implemented** | **Partial** |
| **TTS** | `tts/` — 7, with GLOBAL/TENANT scope | Single undifferentiated list; **no `scope` in UI or payload** | **Partial** |
| **DIDs** | `did/` — 7 incl. assign/revoke | List/detail/create/edit/delete; **assign/revoke unused** | **Partial** |
| **Campaigns** | `campaign/` — 15 + 1 | List/detail/create/edit/delete; **clone & status change are TODO stubs**; **`MISSED_CALL` unmodelled** | **Partial / stale** |
| **Executions** | `campaign/` — nested under campaigns | Detail page only; no execution list page | **Partial** |
| **Call Attempts** | `campaign/` — 6 nested endpoints | Table + transitions; **failed-diagnostics request shape is wrong** | **Partial / wrong** |
| **IVR** | `ivr/` — 6, tenant-only | **Absent** | **Not built** — and backend-blocked (§18) |
| **Queues** | `voice/queue/` — 12 | **Absent** | **Not built** — blocks `CONNECT_BY_AGENT` |
| **Agents** | `voice/agent/` — 17 | **Absent** | **Not built** |
| **Scheduler** | `CampaignExecutionOrchestrator`, `CampaignExecutionOrchestratorSchedulerTest`, `AgentConnectTimeoutScheduler`, `StaleCallReconciler` | **Absent — and there is no REST surface** | **NOT EXPOSED BY BACKEND** |
| **FreeSWITCH/ESL** | `telephony/` | **Absent (correctly)** | Internal only |

**Scheduler finding.** Scheduling *is* implemented — `CampaignExecutionOrchestrator` drives
execution, and there is a dedicated scheduler test — but **no REST endpoint exposes scheduler
status, queue depth, or dispatch metrics.** A frontend "Scheduler/Run" page has no contract to
build on. Record as: **NOT EXPOSED BY BACKEND.** There is also no retry/cancel control surface
beyond the attempt transition PATCHes.

---

## 6. API Inventory

Complete inventory of endpoints relevant to frontend integration. All prefixed `/api/v1`.
Source files are relative to `backend/src/main/java/com/shivang/obd/`.

### 6.1 Authentication — `security/AuthController.java`

| Method | Path | Body | Returns | Cap | Line |
|---|---|---|---|---|---|
| POST | `/auth/login` | `LoginRequest{email,password}` | `LoginResponse{accessToken,tokenType,expiresInSeconds}` | none (`permitAll`) | 71 |
| POST | `/auth/refresh` | — (reads `obd_rt` cookie) | `LoginResponse` | none (`permitAll`) | 87 |
| GET | `/auth/me` | — | `AuthenticatedUserResponse` | authenticated | 50 |
| POST | `/auth/change-password` | `ChangePasswordRequest{currentPassword,newPassword}` | `null` | authenticated | 57 |
| POST | `/auth/logout` | — (cookie) | `null` | none (`permitAll`) | 112 |
| POST | `/auth/logout-all` | — | `null` | authenticated | 134 |
| POST | `/account/signup/tenant` | `TenantSignupRequest` | `TenantResponse` | none (`permitAll`) | `tenant/TenantSignupController.java:38` |
| POST | `/account/signup/reseller` | `CreateResellerRequest` | `ResellerResponse` | none (`permitAll`) | `reseller/ResellerSignupController.java:40` |

`AuthenticatedUserResponse(id, email, status, homeType, organizationId, capabilities)` —
`homeType` is `TENANT|RESELLER` or **`null` for platform users** (`authz/home/OrganizationalHomeType.java:3-5`).

### 6.2 Campaigns — `campaign/CampaignController.java`

| Method | Path | Params | Returns | Cap | Line |
|---|---|---|---|---|---|
| POST | `/campaigns` | body `CreateCampaignRequest`; `?tenantId` (optional UUID) | `CampaignResponse` 201 | `CAMPAIGN_MANAGE` | 60 |
| GET | `/campaigns` | `page=0,size=20,sort="createdAt,desc",status?,campaignType?,runMode?,search?` | `List<CampaignResponse>` + pagination | `CAMPAIGN_VIEW` | 96 |
| GET | `/campaigns/{id}` | — | `CampaignResponse` | `CAMPAIGN_VIEW` | 80 |
| PUT | `/campaigns/{id}` | `UpdateCampaignRequest` | `CampaignResponse` | `CAMPAIGN_MANAGE` | 121 |
| DELETE | `/campaigns/{id}` | — | 204 | `CAMPAIGN_MANAGE` | 138 |
| PATCH | `/campaigns/{id}/status` | `UpdateCampaignStatusRequest{status:String}` | `CampaignResponse` | `CAMPAIGN_EXECUTE` | 160 |
| POST | `/campaigns/{id}/clone` | — | `CampaignResponse` 201 | `CAMPAIGN_MANAGE` | 180 |
| GET | `/campaigns/{id}/readiness` | — | `CampaignReadinessResponse` | `CAMPAIGN_VIEW` | 199 |
| POST | `/campaigns/{id}/executions` | `ExecuteCampaignRequest{idempotencyKey?}` | `CampaignExecutionResponse` 201 | `CAMPAIGN_EXECUTE` | 218 |
| GET | `/campaigns/{campaignId}/executions` | **no params, no pagination** | `List<CampaignExecutionResponse>` | `CAMPAIGN_EXECUTE` | 252 |
| GET | `/campaigns/{campaignId}/executions/{executionId}` | — | `CampaignExecutionResponse` | `CAMPAIGN_EXECUTE` | 236 |
| POST | `/campaigns/{id}/ivr-tree` | `IvrFromCampaignRequest` | — | `IVR_MANAGE` | `campaign/CampaignIvrController.java:61` |

### 6.3 Call Attempts — `campaign/CallAttemptService.java` via `CampaignController`

| Method | Path | Params | Cap | Line |
|---|---|---|---|---|
| POST | `.../executions/{executionId}/attempts` | `CreateCallAttemptRequest{contactId,didId,attemptNumber,scheduledAt?}` | `CAMPAIGN_EXECUTE` | 271 |
| GET | `.../attempts/{attemptId}` | — | `CAMPAIGN_EXECUTE` | 291 |
| GET | `.../attempts` | **no params, no pagination** | `CAMPAIGN_EXECUTE` | 309 |
| PATCH | `.../attempts/{attemptId}/in-progress` | — | `CAMPAIGN_EXECUTE` | 329 |
| PATCH | `.../attempts/{attemptId}/completed` | — | `CAMPAIGN_EXECUTE` | 350 |
| PATCH | `.../attempts/{attemptId}/failed` | **`?failureCode=&failureReason=` (RequestParam, NOT body)** | `CAMPAIGN_EXECUTE` | 372 |
| PATCH | `.../attempts/{attemptId}/cancel` | — | `CAMPAIGN_EXECUTE` | 395 |

### 6.4 Contacts & Groups — `contact/ContactGroupController.java`

All 18 endpoints are under `/contact-groups`. **There is no `ContactController` and no
`/api/v1/contacts` resource** — confirmed by controller enumeration.

Group CRUD: POST `/contact-groups` (61), GET `/{id}` (79), GET list (94),
PUT `/{id}` (113), DELETE `/{id}` (130).
Contact child CRUD: POST `/{id}/contacts` (149), GET `/{id}/contacts` (165),
GET `/{id}/contacts/{contactId}` (184), PUT (200), DELETE (218).
Import/export: POST `/{id}/contacts/import` — multipart `@RequestParam("file")` (379);
GET `/{id}/contacts/export?format=csv|xlsx|json` → raw `byte[]` (400).
Member roster (**entirely unused by the frontend**): GET `/{id}/members` (241),
POST `/{id}/members` (269), GET `/{id}/members/{contactId}` (293),
DELETE `/{id}/members/{contactId}` (312), POST `/{id}/members/batch` (334),
DELETE `/{id}/members/batch` (354).
All: `CONTACT_VIEW` / `CONTACT_MANAGE` (or `CONTACT_IMPORT`/`CONTACT_EXPORT` vocabulary
exists in the catalog but is **not** used by the contact controller — it uses `CONTACT_VIEW`
for export and `CONTACT_MANAGE` for import).

### 6.5 Audio — `audio/AudioAssetController.java`

POST `/audio-assets` (50), GET `/{id}` (68), **POST `/audio-assets/upload` — multipart
`@RequestParam` `name`, `description?`, `file`** (86), GET list (110), PUT `/{id}` (130),
DELETE `/{id}` (146), PATCH `/{id}/approve` (162), PATCH `/{id}/reject` (177).
Caps: `AUDIO_VIEW` / `AUDIO_MANAGE` / `AUDIO_APPROVE`.
List params: `page,size,sort("createdAt,desc"),status?,search?`.
Upload limits: WAV/MP3 only, max `audio.storage.max-file-size-bytes` (default 5 MiB).

### 6.6 TTS — `tts/TtsTemplateController.java`

POST (50), GET `/{id}` (68), GET list (81), PUT `/{id}` (103), DELETE `/{id}` (119),
PATCH `/{id}/approve` (135), PATCH `/{id}/reject` (150).
Caps: `TTS_VIEW` / `TTS_MANAGE` / `TTS_APPROVE`.
`CreateTtsTemplateRequest` includes `scope: GLOBAL|TENANT` and `tenantId` — **there is no
`scope` query-param filter on the list endpoint** (confirmed: controller line 83-87).

### 6.7 DIDs — `did/DidController.java`

POST (52), GET `/{id}` (70), GET list (87), PUT `/{id}` (117),
**POST `/{id}/assign`** body `AssignDidRequest{targetId}` (138),
**POST `/{id}/revoke`** (157), DELETE `/{id}` (172).
List params: `page,size,sort,status?,allocationState?,numberType?,provider?,circle?,tenantId?,resellerId?,**q**?`.
`tenantId`/`resellerId` params are honoured **only** in the platform-scope branch
(`DidService.java:155-161`).

### 6.8 Tenants / Resellers / Users

`tenant/TenantController.java`: POST (50), GET `/{id}` (68), GET list (83), PUT (104),
DELETE (120), **POST `/{tenantId}/agents`** body `CreateAgentRequest{email,password,displayName?}`
→ returns `TenantResponse` (139), capability `USER_MANAGE` platformWide (`TenantService.java:82-83`).
`reseller/ResellerController.java`: POST (46), GET `/{id}` (63), GET list (78), PUT (100), DELETE (116).
`account/UserController.java`: GET `/{id}` (40), GET list (55), PUT `/{id}` (78).
**No user create, no user delete, no `GET /users/me`** — confirmed by full controller read.

### 6.9 Domains with no frontend at all

`ivr/IvrTreeController.java` — 6 endpoints, `IVR_VIEW`/`IVR_MANAGE`, **tenant-only** (no
reseller or platform branch exists in `IvrTreeService`).
`voice/queue/QueueDirectoryController.java` — 12 endpoints, `QUEUE_VIEW`/`QUEUE_MANAGE`.
`voice/agent/AgentDirectoryController.java` — 17 endpoints, `AGENT_VIEW`/`AGENT_MANAGE`, and
`CALL_VIEW` for call history/active calls (`AgentCallQueryService.java:54,73,97`).

---

## 7. Contract Matrix

Classification legend: **MATCH** / **PARTIAL** (works, under-represents the contract) /
**MISMATCH** (different field, enum, validation or shape) / **STALE** (based on a removed
contract) / **UNSUPPORTED** (frontend expects something the backend does not expose) /
**NO FRONTEND** (endpoint exists, no caller) / **UNKNOWN**.

| # | Domain | Op | Method | Endpoint | Request DTO (backend) | Response DTO (backend) | Auth | Cap | Tenant scope | Pagination | Frontend | Class |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Auth | login | POST | `/auth/login` | `LoginRequest` | `LoginResponse` | none | — | — | — | `auth.ts:18` | **MATCH** |
| 2 | Auth | refresh | POST | `/auth/refresh` | cookie only | `LoginResponse` | none | — | — | — | `client.ts:41` | **MATCH** |
| 3 | Auth | me | GET | `/auth/me` | — | `AuthenticatedUserResponse` | bearer | — | — | — | `auth.ts:22` | **MATCH** |
| 4 | Auth | change-password | POST | `/auth/change-password` | `ChangePasswordRequest` | `null` | bearer | — | principal | — | `auth.ts:26` | **MATCH** |
| 5 | Auth | logout | POST | `/auth/logout` | — | `null` | none | — | principal | — | `auth.ts:32` | **MATCH** |
| 6 | Auth | logout-all | POST | `/auth/logout-all` | — | `null` | bearer | — | principal | — | `auth.ts:36` | **MATCH** |
| 7 | Auth | signup tenant | POST | `/account/signup/tenant` | `TenantSignupRequest` | `TenantResponse` | none | — | n/a | — | `signup.ts:11` | **MATCH** |
| 8 | Auth | signup reseller | POST | `/account/signup/reseller` | `CreateResellerRequest` | `ResellerResponse` | none | — | n/a | — | `signup.ts:19` | **MATCH** |
| 9 | Campaign | create | POST | `/campaigns?tenantId=` | `CreateCampaignRequest` | `CampaignResponse` | bearer | `CAMPAIGN_MANAGE` | ctx or param | — | `campaigns.ts:102` | **PARTIAL** — `tenantId` param supported, but body missing 4 fields; `MISSED_CALL` rejected by zod |
| 10 | Campaign | list | GET | `/campaigns` | — | `List<CampaignResponse>` | bearer | `CAMPAIGN_VIEW` | ctx | page/size/sort | `campaigns.ts:71` | **PARTIAL** — `campaignType` filter cannot express `MISSED_CALL` |
| 11 | Campaign | get | GET | `/campaigns/{id}` | — | `CampaignResponse` | bearer | `CAMPAIGN_VIEW` | entity | — | `campaigns.ts:98` | **PARTIAL** |
| 12 | Campaign | update | PUT | `/campaigns/{id}` | `UpdateCampaignRequest` | `CampaignResponse` | bearer | `CAMPAIGN_MANAGE` | entity | — | `campaigns.ts:110` | **PARTIAL** — missing `dailyDialLimit`, `maxDailyAttempts`, `maxCallDurationSeconds`, `retryPolicy.rules` |
| 13 | Campaign | delete | DELETE | `/campaigns/{id}` | — | 204 | bearer | `CAMPAIGN_MANAGE` | entity | — | `campaigns.ts:120` | **MATCH** |
| 14 | Campaign | change status | PATCH | `/campaigns/{id}/status` | `UpdateCampaignStatusRequest{status:String}` | `CampaignResponse` | bearer | `CAMPAIGN_EXECUTE` | entity | — | `campaigns.ts:125` | **PARTIAL** — call exists but the UI handler is a TODO stub (`campaigns-view.tsx:215-218`) |
| 15 | Campaign | clone | POST | `/campaigns/{id}/clone` | — | `CampaignResponse` 201 | bearer | `CAMPAIGN_MANAGE` | entity | — | `campaigns.ts:135` | **PARTIAL** — call exists, UI handler is a TODO stub (`campaigns-view.tsx:211-214`) |
| 16 | Campaign | readiness | GET | `/campaigns/{id}/readiness` | — | `CampaignReadinessResponse` | bearer | `CAMPAIGN_VIEW` | entity | — | `campaigns.ts:142` | **MATCH** |
| 17 | Execution | create | POST | `/campaigns/{id}/executions` | `ExecuteCampaignRequest` | `CampaignExecutionResponse` | bearer | `CAMPAIGN_EXECUTE` | entity | — | `campaigns.ts:151` | **PARTIAL** — response missing `configurationSnapshotId` |
| 18 | Execution | list | GET | `/campaigns/{cid}/executions` | — | `List<...>` | bearer | `CAMPAIGN_EXECUTE` | entity | **none** | `campaigns.ts:164` | **MATCH** — correctly no pagination assumed |
| 19 | Execution | get | GET | `/campaigns/{cid}/executions/{eid}` | — | `CampaignExecutionResponse` | bearer | `CAMPAIGN_EXECUTE` | entity | — | `campaigns.ts:174` | **PARTIAL** |
| 20 | Attempt | list | GET | `.../attempts` | — | `List<CallAttemptResponse>` | bearer | `CAMPAIGN_EXECUTE` | entity | **none** | `call-attempts.ts:75` | **MISMATCH** — sends `sort`+`status` query params the endpoint does not accept (`CampaignController.java:309-313`) |
| 21 | Attempt | create | POST | `.../attempts` | `CreateCallAttemptRequest` | `CallAttemptResponse` | bearer | `CAMPAIGN_EXECUTE` | entity | — | `call-attempts.ts:117` | **MATCH** |
| 22 | Attempt | mark failed | PATCH | `.../failed` | **`?failureCode=&failureReason=`** | `CallAttemptResponse` | bearer | `CAMPAIGN_EXECUTE` | entity | — | `call-attempts.ts:151` | **MISMATCH** — sends a JSON body (`call-attempts.ts:158-161`); backend reads `@RequestParam` (`CampaignController.java:377-378`). Diagnostics are silently dropped. |
| 23 | Attempt | in-progress/completed/cancel | PATCH | `.../{in-progress,completed,cancel}` | — | `CallAttemptResponse` | bearer | `CAMPAIGN_EXECUTE` | entity | — | `call-attempts.ts:143-166` | **MATCH** |
| 24 | Contact Group | CRUD | * | `/contact-groups` | `Create/UpdateContactGroupRequest` | `ContactGroupResponse` | bearer | `CONTACT_MANAGE`/`VIEW` | entity | page/size/sort/search | `contact-groups.ts:54-94` | **PARTIAL** — `memberCount` (backend `long`) is not modelled |
| 25 | Contact | CRUD | * | `/contact-groups/{id}/contacts[/{contactId}]` | `Create/UpdateContactRequest` | `ContactResponse` | bearer | `CONTACT_MANAGE`/`VIEW` | group entity | page/size/sort/search | `contacts.ts:51-95` | **MISMATCH** — frontend `ContactResponse` declares `contactGroupId`; backend `ContactResponse` has **no such field** (`contact/dto/ContactResponse.java`, 9 components) |
| 26 | Contact | import | POST | `/contact-groups/{id}/contacts/import` | multipart `file` | `ContactImportResponse` | bearer | `CONTACT_MANAGE` | group entity | — | `contacts.ts:98` | **MATCH** |
| 27 | Contact | export | GET | `/contact-groups/{id}/contacts/export` | `?format` | raw `byte[]` | bearer | `CONTACT_VIEW` | group entity | — | `contacts.ts:111` | **MATCH** |
| 28 | Contact | **member roster** | * | `/contact-groups/{id}/members[/batch]` | `AddMemberRequest`, `BatchMemberRequest` | `ContactGroupMemberResponse`, `BatchMemberResponse` | bearer | `CONTACT_VIEW`/`MANAGE` | group entity | page/size/sort/search (list only) | — | **NO FRONTEND** — 6 endpoints, 0 types, 0 callers |
| 29 | Audio | create (metadata) | POST | `/audio-assets` | `CreateAudioAssetRequest` | `AudioAssetResponse` | bearer | `AUDIO_MANAGE` | ctx | — | `audio-assets.ts:83` | **PARTIAL** |
| 30 | Audio | **upload** | POST | `/audio-assets/upload` | multipart `name`,`description?`,`file` | `AudioAssetResponse` 201 | bearer | `AUDIO_MANAGE` | ctx | — | — | **NO FRONTEND** — the only real path to a usable asset |
| 31 | Audio | list/get/update/delete/approve/reject | * | `/audio-assets...` | `UpdateAudioAssetRequest` | `AudioAssetResponse` | bearer | `AUDIO_VIEW`/`MANAGE`/`APPROVE` | entity | page/size/sort/status/search | `audio-assets.ts:54-101` | **PARTIAL** — `storageReference`/`checksum` are user-invented values |
| 32 | TTS | create | POST | `/tts-templates` | `CreateTtsTemplateRequest` **+`scope`, +`tenantId`** | `TtsTemplateResponse` **+`scope`** | bearer | `TTS_MANAGE` | ctx / body tenantId | — | `tts-templates.ts:82` | **MISMATCH** — frontend payload has `tenantId` but **no `scope`** (`contracts.ts:391-397`); GLOBAL templates are unreachable from the UI |
| 33 | TTS | list | GET | `/tts-templates` | — | `List<TtsTemplateResponse>` | bearer | `TTS_VIEW` | scope-dependent (§16) | page/size/sort/status/search | `tts-templates.ts:53` | **PARTIAL** — response `scope` unmodelled; **no `scope` filter exists in the backend** |
| 34 | TTS | approve/reject/update/delete | * | `/tts-templates...` | `UpdateTtsTemplateRequest` | `TtsTemplateResponse` | bearer | `TTS_MANAGE`/`TTS_APPROVE` | entity | — | `tts-templates.ts:86-101` | **MATCH** (action buttons ungated — see §10) |
| 35 | DID | list | GET | `/dids` | — | `List<DidResponse>` | bearer | `DID_VIEW` | ctx (+params if platform) | page/size/sort/6 filters/**`q`** | `dids.ts:71` | **MATCH** — correctly uses `q` |
| 36 | DID | CRUD | * | `/dids` | `Create/UpdateDidRequest` | `DidResponse` | bearer | `DID_VIEW`/`MANAGE` | entity | — | `dids.ts:102-122` | **PARTIAL** — `allocationSource` (PLATFORM\|RESELLER) unmodelled |
| 37 | DID | **assign / revoke** | POST | `/dids/{id}/assign`, `/dids/{id}/revoke` | `AssignDidRequest{targetId}` | `AssignDidResponse` | bearer | `DID_MANAGE` | entity | — | — | **NO FRONTEND** |
| 38 | Tenant | CRUD | * | `/tenants` | `Create/UpdateTenantRequest` | `TenantResponse` | bearer | `TENANT_VIEW`/`MANAGE` | ctx/param | page/size/sort/status/search | `tenants.ts:57-111` | **PARTIAL** — `toCreateTenantPayload` hardcodes `resellerId: null` (`schemas/tenant-mutation.ts:70`) |
| 39 | Tenant | **create agent** | POST | `/tenants/{tenantId}/agents` | `CreateAgentRequest` | `TenantResponse` | bearer | `USER_MANAGE` platformWide | platform only | — | — | **NO FRONTEND** |
| 40 | Reseller | CRUD | * | `/resellers` | `Create/UpdateResellerRequest` | `ResellerResponse` | bearer | `RESELLER_VIEW`/`MANAGE` | ctx/param | page/size/sort/status/search | `resellers.ts:58-118` | **MATCH** — note backend `customDomain` is not in `ResellerResponse` |
| 41 | User | list/get/update | * | `/users` | `UpdateUserRequest` | `UserResponse` | bearer | `USER_VIEW`/`MANAGE` | target home | page/size/sort/status/search | `users.ts:50-86` | **MATCH** |
| 42 | IVR | CRUD + status | * | `/ivr-trees` | `Create/UpdateIvrTreeRequest` | `IvrTreeResponse` | bearer | `IVR_VIEW`/`IVR_MANAGE` | **tenant only** | page/size/`status?` only — **no sort, no search, no pagination metadata** | — | **NO FRONTEND** + **backend-blocked** (§18) |
| 43 | Queue | 12 endpoints | * | `/queues`, `/queues/endpoints...` | see §6.9 | `Queue*Response` | bearer | `QUEUE_VIEW`/`MANAGE` | tenant | mixed; members/waiting-calls unpaginated | — | **NO FRONTEND** |
| 44 | Agent | 17 endpoints | * | `/agents`, `/agents/endpoints/{id}` | see §6.9 | `Agent*Response` | bearer | `AGENT_VIEW`/`MANAGE`/`CALL_VIEW` | tenant | mixed | — | **NO FRONTEND** |
| 45 | Scheduler | — | — | — | — | — | — | — | — | — | — | **NOT EXPOSED BY BACKEND** |

---

## 8. DTO / Type Mapping

Pipeline reality: `Entity → Backend DTO → ApiResponse<T> → (hand-written) TS interface → UI`.
There is **no mapping layer** between the TS interface and the UI, so every DTO gap is
directly a UI gap.

### 8.1 Confirmed field-level mismatches

| Backend DTO (source) | Backend field | Frontend type (source) | Frontend status |
|---|---|---|---|
| `CampaignResponse` (`campaign/dto/CampaignResponse.java:66,75,88,102`) | `callOnWhitelistNumbers: Boolean` | `contracts.ts:450-471` | **ABSENT** |
| " | `dailyDialLimit: Integer` (1-3) | " | **ABSENT** |
| " | `maxDailyAttempts: Integer` (1-10) | " | **ABSENT** |
| " | `maxCallDurationSeconds: Integer` (1-3600) | " | **ABSENT** |
| `CampaignExecutionResponse` (`dto/CampaignExecutionResponse.java`) | `configurationSnapshotId: UUID` | `contracts.ts:529-540` | **ABSENT** |
| `RetryPolicyConfig` (`dto/RetryPolicyConfig.java`) | `rules: List<RetryRuleConfig>` (4th component) | `contracts.ts:443-447` (3 fields) | **ABSENT** |
| `RetryRuleConfig` (`dto/RetryRuleConfig.java`) | `category`, `enabled`, `maxRetries`, `retryDelay` (`MM:SS`) | — | **WHOLE TYPE UNMODELLED** |
| `ContactResponse` (`contact/dto/ContactResponse.java`) | *(has no `contactGroupId`)* | `contracts.ts:276-287` declares `contactGroupId: string` | **PHANTOM FIELD** |
| `ContactGroupResponse` | `memberCount: long` | `contracts.ts:234-241` | **ABSENT** |
| `ContactGroupMemberResponse` | `memberId`, `groupId`, `contactId`, `tenantId`, `contact`, `createdAt`, `createdBy` | — | **WHOLE TYPE UNMODELLED** |
| `BatchMemberResponse` / `BatchMemberResult` / `MemberBatchStatus` | 6 status values | — | **UNMODELLED** |
| `TtsTemplateResponse` (`tts/dto/TtsTemplateResponse.java`) | `scope: TtsTemplateScope` | `contracts.ts:378-388` | **ABSENT** |
| `CreateTtsTemplateRequest` | `scope: TtsTemplateScope` | `contracts.ts:391-397` | **ABSENT** |
| `DidResponse` (`did/dto/DidResponse.java`) | `allocationSource: AllocationSource` | `contracts.ts:184-199` | **ABSENT** |
| `AssignDidResponse` | `didId`, `allocationState`, `allocationSource`, `tenantId`, `resellerId` | — | **UNMODELLED** |
| `UserResponse` | 7 fields | `contracts.ts:107-115` 7 fields | **MATCH** |
| `TenantResponse` | 7 fields | `contracts.ts:65-73` 7 fields | **MATCH** |
| `ResellerResponse` | 10 fields (no `customDomain`) | `contracts.ts:88-99` 10 fields | **MATCH** |
| `CallAttemptResponse` | 15 fields | `contracts.ts:546-562` 15 fields | **MATCH** |
| `AudioAssetResponse` | 12 fields | `contracts.ts:331-345` 13 fields (adds `durationSeconds` correctly; all 12 present) | **MATCH** |
| `ApiResponse`/`PaginationMetadata`/`ProblemDetail`/`FieldErrorDto` | — | `types.ts:24-72` | **MATCH** |

### 8.2 Enum drift

| Enum | Backend values | Frontend `contracts.ts` | Class |
|---|---|---|---|
| `CampaignType` | `PLAYFILE, DTMF, CONNECT_BY_AGENT, MISSED_CALL` (4) | `PLAYFILE, DTMF, CONNECT_BY_AGENT` (3) | **MISMATCH — hard** |
| `CampaignStatus` | 7 | 7 | MATCH |
| `CampaignRunMode` | 2 | 2 | MATCH |
| `ContentMode` | 2 | 2 | MATCH |
| `RetryStrategy` | `FIXED` | `FIXED` | MATCH |
| `RetryRuleCategory` | `NO_ANSWER, BUSY, HANGUP, FAILED, SWITCHED_OFF, NOT_REACHABLE` | — | **UNMODELLED** |
| `CampaignExecutionStatus` | 5 | 5 | MATCH |
| `CallAttemptStatus` | 5 | 5 | MATCH |
| `LifecycleStatus` | `ACTIVE, SUSPENDED` | same | MATCH |
| `OrganizationalHomeType` | `TENANT, RESELLER` | same | MATCH |
| `AudioAssetStatus` / `TtsTemplateStatus` | 3 each | 3 each | MATCH |
| `DidStatus`/`AllocationState`/`NumberType`/`DidCapability` | 2/2/3/1 | same | MATCH |
| `TtsTemplateScope` | `GLOBAL, TENANT` | — | **UNMODELLED** |
| `AllocationSource` | `PLATFORM, RESELLER` | — | **UNMODELLED** |
| `CallFailureCode` | 53 values (see §15) | `failureCode: string \| null` | **LOSSY — no vocabulary** |
| `ScheduleConfig.allowedDaysOfWeek` | `Set<DayOfWeek>` (Java enum) | `string[] \| null` (`contracts.ts:439`); zod `z.array(z.string())` (`schemas/campaign-mutation.ts:57`) | **TOO LOOSE** — any non-`MONDAY`…`SUNDAY` string fails backend deserialization |

### 8.3 Validation-bound mismatches (client stricter/looser than server)

| Rule | Backend | Frontend zod | Class |
|---|---|---|---|
| `RetryPolicyConfig.intervalSeconds` | `@Min(1) @Max(5999)` | `.max(604800)` (`schemas/campaign-mutation.ts:81`) | **MISMATCH** — client allows 1000s of values the server rejects (400) |
| `CreateResellerRequest.admin` | `@Valid` only, **no `@NotNull`**, yet `ResellerProvisioningService:74` dereferences `request.admin().password()` | `admin?: TenantAdminInput \| null` | **Backend defect** — NPE/500 if omitted; client should treat as required |
| `TenantAdminInput.password` / `AdminAccountInput.password` | `@Size(min = 12, max = 128)` | `PASSWORD_MIN = 8` (`schemas/auth.ts:16`, reused by `signup.ts:34-37`, `reseller-mutation.ts:88-91`) | **MISMATCH** — client accepts 8-11 char passwords, server rejects them |
| `CreateContactRequest.phoneNumber` | `@Pattern(E164)` but **no `@NotBlank`/`@NotNull`** | `.min(1).regex(...)` | PARTIAL — client stricter; server would accept a null phone and fail later |
| `TtsTemplateVariable` | no bean annotations; validated in `TtsTemplateValidation` | `updateTtsTemplateSchema` **omits** the duplicate-name and stray-brace `superRefine` that create has (`schemas/tts-template-mutation.ts:20-26` vs `:33`) | **ASYMMETRY** — update under-validated client-side |
| `contact-mutation.ts:11` `CONTACT_E164_REGEX` | — | exported, **never used** (the same literal is inlined twice instead) | dead export |
| `call-attempt-mutation.ts:5` `ATTEMPT_NUMBER_MIN` | — | exported, **never used** | dead export |

### 8.4 `typeConfig` / `integrationConfig` — the largest structural gap

- **Backend**: `typeConfig` is a `JsonNode` on the wire, but validated server-side into typed
  configs by `CampaignTypeConfigValidator` — `PlayfileCampaignConfig`, `DtmfCampaignConfig`,
  `ConnectByAgentCampaignConfig`, `MissedCallCampaignConfig`, `IvrCampaignConfig`. **Unknown
  fields are rejected (400)**. Shapes are documented on the DTO:
  - `CONNECT_BY_AGENT` → `connectByAgent{queueId, selectionStrategy, ringDurationSeconds(10-240)}`
  - `MISSED_CALL` → `missedCall{ringDurationSeconds(10-60)}`
- **Frontend**: `typeConfig: Record<string, unknown> | null` (`contracts.ts:468,486,503`),
  zod `z.record(z.string(), z.unknown())` (`schemas/campaign-mutation.ts:116`).
  **There is no typed shape and no validation.** The UI cannot construct a valid payload
  and will not surface a clear field error.
- **Backend**: `integrationConfig` is a **typed** `CampaignIntegrationConfig`
  (`{ webhook{enabled,endpoint,events[]}, reportPrivacy{policy} }`), unknown fields rejected,
  no secrets storable.
- **Frontend**: `Record<string, unknown>`. Not renderable, not validatable.

---

## 9. Authentication Audit

**Traced flow (actual implementation):**

```
Login
  POST /api/v1/auth/login  {email,password}          (auth.ts:18)
  → 200 {accessToken, tokenType:"Bearer", expiresInSeconds}
  → Set-Cookie: obd_rt=<opaque>; HttpOnly; SameSite=Strict; Secure; Path=/api/v1/auth
        written by backend AuthCookieWriter:33-39 — the raw refresh token NEVER enters JSON
  → setSession({accessToken}) → module-level variable   (token-store.ts:33)
  → queryClient.invalidateQueries(["auth","me"])        (session.ts:42)
  ↓
Authenticated request
  axios request interceptor attaches `Authorization: Bearer <memory token>`  (client.ts:56-62)
  ↓
401
  response interceptor (client.ts:64-98):
    - skip if URL ∈ PUBLIC_ENDPOINTS {/auth/login,/auth/refresh,/account/signup/*}  (client.ts:23-28)
    - skip if this config object was already retried (WeakSet)                    (client.ts:54,74,94)
    - single-flight: concurrent 401s share one in-flight refresh promise          (client.ts:35,80-82)
    - raw axios POST /api/v1/auth/refresh (bypasses interceptors, no body)         (client.ts:41-45)
    - setSession(newAccessToken); replay original request with the new header      (client.ts:95-96)
    - on refresh failure: clearSession() + hard redirect to /sign-in?next=…        (client.ts:89-91,104-111)
  ↓
Page reload
  memory token gone, HttpOnly cookie persists
  (platform)/layout.tsx renders skeleton, useSession() → GET /auth/me → 401 → interceptor
  refreshes → /me succeeds → shell renders
  ↓
Logout
  useLogout → POST /auth/logout (cookie) → 200 → endLocalSession() = clearSession() +
  queryClient.clear() → toast → router.replace("/sign-in")   (session.ts:63-80,51-55)
Logout-all
  useLogoutAll → POST /auth/logout-all → same teardown        (session.ts:82-84)
```

**Assessment: the frontend authentication layer is a correct, faithful implementation of the
backend's cookie + bearer design.** Specific strengths worth preserving verbatim:

- Access token in memory only, never in `localStorage`/`sessionStorage` (`token-store.ts:18-26`).
- The refresh token is never read, stored or transmitted by client code — enforced by the
  backend cookie flags, and the frontend documents the invariant at `token-store.ts:1-13`.
- Active purge of the pre-cookie `obd.refresh-token` localStorage key.
- Single-flight refresh with a per-request retry guard, correctly preventing infinite 401 loops.
- `endLocalSession()` calls `queryClient.clear()`, so no authenticated cached data survives logout.
- `useSession()` sets `retry: false` so React Query does not fight the interceptor.

**Gaps / concerns.**

| # | Finding | Evidence | Severity |
|---|---|---|---|
| 1 | `(platform)` guard is client-side only; the subtree is server-rendered for anonymous requests, then redirects post-hydration. No `middleware.ts`, no `error.tsx`, no `not-found.tsx`, no `loading.tsx`. | `(platform)/layout.tsx:1,78-98` | Medium (defence-in-depth; not a breach) |
| 2 | The layout guard redirects **without** `?next=`, so deep links are lost. `client.ts` preserves it but the layout path does not. | `(platform)/layout.tsx:88-92` vs `client.ts:104-111` | Low |
| 3 | `session.data` is dereferenced without a null check after the `isPending \|\| isError` early return. | `(platform)/layout.tsx:88,109` | Low |
| 4 | `next-themes` is consumed (`components/ui/sonner.tsx:3` calls `useTheme()`) but **no `ThemeProvider` is mounted** — the Toaster always falls back to `theme = "system"`. | `components/providers.tsx` (16 lines, no ThemeProvider) | Low |
| 5 | `NETWORK_ERROR_CODE` is exported but `toApiError` never assigns it to network failures (`error.ts:59` omits `code`). | `error.ts:4,59,70` | Low (dead code) |
| 6 | `unwrap`'s doc comment claims it throws `ApiError`; it has no try/catch and never imports `toApiError`. | `auth.ts:10-16` | Low (misleading) |
| 7 | No rate-limit UX beyond a special-cased string in the sign-in form. | `sign-in-form.tsx:77-79` | Low |

**Security posture summary.** No token leakage, no storage persistence, no CSRF exposure
(SameSite=Strict + cookie scoped to `/api/v1/auth`), CORS is exact-match and
credential-enabled (`SecurityConfig.java:76-86`). **No authentication security concern was
found in the frontend.** Per F0 scope, authentication is **not** redesigned here.

---

## 10. RBAC Matrix

**Actual backend model.** Roles come from the DB (`roles` table); capabilities from
`capabilities`; grants from `role_capabilities`. Authorization is evaluated by
`AuthorizationService` (L59-125) as: capability granted to the role **AND** the assignment's
scope covering the target `AccessCheck`. `OWN`/`ASSIGNED` additionally require a registered
`ResourceAuthorizationPolicy`.

**Roles** (`V1__create_multi_tenant_authorization_foundation.sql:155-160`):

| Role key | Name | Scope | Notes |
|---|---|---|---|
| `SUPER_ADMIN` | Super Admin | `PLATFORM` | Holds **no** organizational home and no membership (V13:4-5) |
| `RESELLER_ADMIN` | Reseller Admin | `RESELLER` | Assigned at reseller creation (`ResellerProvisioningService.java:31`) |
| `TENANT_ADMIN` | Tenant Admin | `TENANT` | Assigned at tenant creation (`TenantProvisioningService.java:38,142`) |
| `AGENT` | Agent | `ASSIGNED` | Also assigned per tenant (`TenantProvisioningService.java:39,128`) |
| `REPORT_VIEWER` | Report Viewer | `TENANT` | Seeded in V1; no controller enforces a report capability |

**Capability catalog — 34 keys, fully enumerated by migration:**

| Migration | Capabilities added |
|---|---|
| V1 (27) | `TENANT_VIEW/MANAGE`, `RESELLER_VIEW/MANAGE`, `USER_VIEW/MANAGE`, `ROLE_VIEW/MANAGE`, `CAMPAIGN_VIEW/MANAGE/EXECUTE/ASSIGN/EXPORT`, `CONTACT_VIEW/MANAGE/IMPORT/EXPORT`, `AUDIO_VIEW/MANAGE/APPROVE`, `CALL_VIEW/DISPOSITION`, `AGENT_VIEW/MANAGE/ASSIGN`, `REPORT_VIEW/EXPORT` |
| V16 | `DID_VIEW`, `DID_MANAGE` |
| V20 | `TTS_VIEW`, `TTS_MANAGE`, `TTS_APPROVE` |
| V37 | `QUEUE_VIEW`, `QUEUE_MANAGE` |
| — | **`IVR_VIEW`, `IVR_MANAGE` — enforced in code, NEVER seeded. See §18.** |

**Evidence-based access matrix:**

| Role | Scope | Backend access (enforced) | Frontend routes | Frontend actions gated | Evidence |
|---|---|---|---|---|---|
| `SUPER_ADMIN` | `PLATFORM` — `covers()` returns `true` for every target (`AuthorizationService.java:143`) | Every capability endpoint. `RESELLER_ADMIN`/`TENANT_ADMIN` are *not* granted `RESELLER_MANAGE` for `PUT /resellers/{id}` (uses `forReseller(id)`, so a platform caller must still match the id) | all 9 nav items; `Tenants`/`Resellers` unblocked by the `homeType === null` check | **none** | `V1:216`; `navigation.ts:29-39,61-63` |
| `RESELLER_ADMIN` | `RESELLER` | `RESELLER_VIEW/MANAGE`, `TENANT_VIEW/MANAGE` (create tenants **under own reseller only** — a body `resellerId` mismatch is 403, `TenantProvisioningService:100-102`), `USER_VIEW/MANAGE`, `CAMPAIGN_VIEW/MANAGE/EXECUTE/EXPORT`, `CONTACT_*`, `AUDIO_VIEW/APPROVE` (**not** `AUDIO_MANAGE`), `AGENT_VIEW/MANAGE/ASSIGN`, `CALL_VIEW`, `DID_VIEW/MANAGE`, `TTS_VIEW/MANAGE/APPROVE`, `QUEUE_VIEW/MANAGE` | all 9 except `Resellers` (blocked by `requiredScope:"platform"`) | **none** | `V1:237-250`, `V16:68`, `V20:75`, `V37:133`; `navigation.ts:62-63` |
| `TENANT_ADMIN` | `TENANT` | `TENANT_VIEW`, `USER_VIEW/MANAGE`, `ROLE_VIEW/MANAGE`, `CAMPAIGN_VIEW/MANAGE/EXECUTE/ASSIGN/EXPORT`, `CONTACT_*`, `AUDIO_VIEW/MANAGE/APPROVE`, `CALL_VIEW/DISPOSITION`, `AGENT_VIEW/MANAGE/ASSIGN`, `REPORT_VIEW/EXPORT`, `DID_VIEW/MANAGE`, `TTS_VIEW/MANAGE/APPROVE`, `QUEUE_VIEW/MANAGE` | all 9 except `Tenants`/`Resellers` | **none** | `V1:257-266`, `V16:68`, `V20:75`, `V37:133` |
| `AGENT` | `ASSIGNED` | `CALL_VIEW`, `CALL_DISPOSITION` **only** (`V1:269-275`). `hasResourceAccess` would need a `ResourceAuthorizationPolicy`, and the only implementation is a test double (`test/.../TestResourcePolicy.java`) — so `OWN`/`ASSIGNED` grants are **not** resolvable in production | **none** — every nav item requires a capability this role lacks, so the sidebar is empty | n/a | `V1:269`; `AuthorizationService.java:98-103` |
| `REPORT_VIEWER` | `TENANT` | `REPORT_VIEW`, `REPORT_EXPORT` (`V1:281-287`). **No controller enforces either capability** — `REPORT_VIEW`/`REPORT_EXPORT` are seeded but unused in `src/main`. | **none** | n/a | `V1:281` |

**Frontend gating reality.**

`getVisibleNavItems` (`navigation.ts:50-75`) ANDs a capability check with an optional
`requiredScope` check. Only `"platform"` is ever used, implemented as
`user.homeType !== null` — correct, because `OrganizationalHomeType` has no `PLATFORM` member
and platform users have no home.

**Critical scoping caveat.** `GET /auth/me` returns
`getAllCapabilitiesForUser(userId)`, which is explicitly documented as
*"Does not apply scope filtering - returns the union of all capabilities"*
(`AuthorizationService.java:174-176`). A user with `TENANT_VIEW` therefore receives
`TENANT_VIEW` in `/me` **whether or not** they are a platform administrator. **Capability
presence in the frontend cannot be used to infer organizational scope** — the `homeType`
field is the only scope signal available, and it is `null` for any platform user.

**12 declared-but-never-checked capabilities.** `USER_MANAGE`, `TENANT_MANAGE`,
`RESELLER_MANAGE`, `DID_MANAGE`, `CAMPAIGN_MANAGE`, `CAMPAIGN_EXECUTE`, `CONTACT_MANAGE`,
`AUDIO_MANAGE`, `AUDIO_APPROVE`, `TTS_MANAGE`, `TTS_APPROVE` (plus `hasAnyCapability`,
`hasAllCapabilities`, `hasPlatformAccess`, `hasResellerAccess`, `hasTenantAccess`, all with
zero callers). Consequence, with evidence: `components/audio-assets/audio-assets-view.tsx:50,53`
and `components/tts-templates/tts-templates-view.tsx:40,41` call the approve/reject mutations
and render those actions with **no capability check** — a `RESELLER_ADMIN` (who lacks
`AUDIO_MANAGE`) will see approve/reject buttons that return 403.

**Also note:** `hasPlatformAccess()` (`capabilities.ts:75-78`) is a **frontend-invented
heuristic** — `homeType === null && hasCapability(TENANT_VIEW)`. It has zero callers and is
not derived from any backend rule. Treat as **UNKNOWN / not backend-authoritative**; do not
build on it without backend confirmation.

---

## 11. Tenant Boundary Map

**Actual end-to-end flow:**

```
Authenticated User
  ↓
JWT (stateless) — claims: sub (user UUID), email, sid (session family)
  SecurityConfig.java:88-98  —  NO roles, NO tenant, NO scope in the token
  ↓
OrganizationContextPopulationFilter
  reads SecurityContext principal → MembershipResolver/OrganizationalHomeResolver
  → OrganizationContextHolder.setAuthenticated(userId, tenantId, resellerId)
  (finally { OrganizationContextHolder.clear(); })                     ← leak-safe
  ↓
Scope.of(OrganizationContextHolder.current().orElse(null))
  used identically in 13 services: ContactGroupAccess:71, AudioAssetService:289,
  TtsTemplateService:299, DidService:385, TenantService:63, ResellerService:84,
  UserService:81, IvrTreeService:512, QueueDirectoryService:515,
  AgentDirectoryService:491, AgentCallQueryService:188, AgentOutboundApiService:56
  ↓
AuthorizationService.requireCapability(userId, capability, AccessCheck)
  ↓
Repository query constrained by the resolved tenant / hierarchy / entity
```

**How `tenantId` actually enters the system — the complete, verified list:**

| Entry point | Mechanism | Who may use it | Backend enforcement |
|---|---|---|---|
| `POST /campaigns?tenantId=` | **query param** | platform only | `CampaignService.create(request, tenantId)`; tenant callers derive it from context |
| `POST /dids` body `tenantId`/`resellerId` | body | reseller (own tenant only) / platform | `DidService.java:69-103`; a reseller targeting another reseller's tenant is 400 |
| `POST /tts-templates` body `tenantId` | body | platform only | `TtsTemplateService.java:90-98`; tenant must be `ACTIVE` |
| `POST /tenants` body `resellerId` | body | platform only | `TenantProvisioningService.java:100-102` → 403 on mismatch |
| `GET /dids?tenantId=&resellerId=` | query params | **platform only** (ignored for tenant/reseller callers) | `DidService.java:155-161` |
| `GET /tenants`, `/resellers`, `/users`, `/dids`, `/contact-groups`, `/audio-assets`, `/tts-templates`, `/campaigns`, `/queues`, `/agents` | **none** — context only | — | tenant / reseller-hierarchy / platform branch in each service |
| All `{id}` detail routes | **none** | — | `AccessCheck.forTenant(entity.getTenantId())` from the **loaded entity**, so a foreign id yields 403/404 rather than a leak |

**Confirmed absences.** There is **no** `tenantId` request header, **no** `X-Tenant-Id`,
**no** session-scoped selected-tenant state, and **no** `Pageable`/resolver that would let a
caller set the tenant implicitly. Tenant identity is derived server-side from the JWT subject
and DB membership on every request. `OrganizationContextHolder.clear()` runs in a `finally`
block, so no cross-request leakage.

**Frontend tenant state — what exists and what does not.**

- **No `tenantId` in any URL.**
- **No `tenantId` header** anywhere in the frontend.
- **No tenant-switching store, context or URL state.** The frontend is entirely single-tenant-in-context: it renders whatever `GET /me` returns.
- Only three places send a tenant identifier to the backend, and **all three are hardcoded or
  effectively unused**:
  1. `createCampaign(payload, tenantId?)` (`campaigns.ts:102-108`) → `?tenantId=`. **No caller
     supplies it** (grep: `createCampaign` is invoked from `create-campaign-dialog.tsx` without
     the second argument).
  2. `toCreateTenantPayload` → **`resellerId: null` hardcoded** (`schemas/tenant-mutation.ts:70`).
  3. `CreateDidPayload.tenantId` / `resellerId` exist in the type and zod schema
     (`schemas/did-mutation.ts:80-81`) but the create dialog supplies neither.

**Reseller tenant-selection — actual behaviour.**

- **Backend**: a `RESELLER`-scoped caller calling `GET /tenants` receives
  `hasReseller(context.resellerId())` — the reseller's own hierarchy tenants
  (`TenantService.java:108-109`). A reseller may create tenants under itself
  (`forReseller` + body-mismatch 403). For every other domain, a reseller gets a
  `forReseller(resellerId)` check, and `coversReseller` resolves a TENANT target through
  `TenantHierarchyResolver.resellerIdOf(tenantId)` (`AuthorizationService.java:150-166`).
- **Frontend**: a reseller admin sees a working `/tenants` list and detail pages, but has
  **no way to select a tenant and act within it**. There is no tenant picker, no
  `selectedTenantId` state, and the create-tenant dialog cannot place a tenant under a
  reseller. Every other domain list a reseller opens is served by the backend's
  hierarchy-wide read, so the reseller sees an aggregated view with no drill-down context.
- **No client-side filtering masquerades as a boundary.** The frontend does not filter by
  tenant and does not treat a `tenantId` it holds as an authorisation. This is correct.

**Flagged.** The frontend never treats an arbitrary `tenantId` as a security boundary, which
is the correct posture. The *functional* gap is the absence of tenant selection, and the
*hardcoded* `resellerId: null` which silently forces every UI-created tenant to be
platform-direct.

---

## 12. Staleness Matrix

| # | Area | Stale assumption | Current backend reality | Evidence | Severity | Migration action |
|---|---|---|---|---|---|---|
| S1 | Campaign | `CampaignType` has 3 values | **4 values** — `MISSED_CALL` exists with a full config (`MissedCallCampaignConfig`), execution service (`MissedCallExecutionService`), readiness rules, and migrations V54/V55 | `campaign/CampaignType.java:33` vs `contracts.ts:420`; `V54__campaign_type_missed_call.sql` | **High** | Add `MISSED_CALL` to the TS union, the zod enum, the type filter and the type badge before any campaign work |
| S2 | Campaign | `CampaignResponse` has 20 fields | **24 fields** — `callOnWhitelistNumbers`, `dailyDialLimit`, `maxDailyAttempts`, `maxCallDurationSeconds` all present and documented | `CampaignResponse.java:66,75,88,102` vs `contracts.ts:450-471` | **High** | Extend the type; decide whether these are editable in the campaign form (they are on both create and update DTOs) |
| S3 | Campaign | `RetryPolicyConfig` has 3 fields | **4** — plus `rules: List<RetryRuleConfig>` with `MM:SS` delays and per-category enable/disable | `dto/RetryPolicyConfig.java`, `dto/RetryRuleConfig.java` vs `contracts.ts:443-447` | **High** | Model `RetryRuleConfig`; the backend supports per-category rules the UI cannot express |
| S4 | Campaign | `retryPolicy.intervalSeconds` max 604800 | `@Max(5999)` | `dto/RetryPolicyConfig.java` vs `schemas/campaign-mutation.ts:81` | Medium | Tighten the zod bound; a mismatched value produces a 400 the user cannot act on |
| S5 | Campaign | `integrationConfig` is a free-form record | **Typed** `CampaignIntegrationConfig`; unknown fields **rejected** | `dto/CreateCampaignRequest.java:114` vs `contracts.ts:468` | Medium | Type it or remove it from the form until a consumer exists |
| S6 | Execution | `CampaignExecutionResponse` has 10 fields | **11** — `configurationSnapshotId` present | `dto/CampaignExecutionResponse.java` vs `contracts.ts:529-540` | Medium | Add it; it is the user-visible proof that an execution owns a frozen snapshot |
| S7 | Attempts | `markFailed` sends a JSON body | Backend reads **`@RequestParam`** `failureCode`/`failureReason` | `CampaignController.java:377-378` vs `call-attempts.ts:158-161` | **High** | Move to query params, or accept that failure diagnostics are silently lost |
| S8 | Attempts | List is filterable/sortable | Endpoint takes **no** parameters; ordering is fixed | `CampaignController.java:309-313` (no `@RequestParam`) vs `call-attempts.ts:90-94` | Medium | Remove the dead params and the fabricated `PaginationMetadata`; render the fixed order honestly |
| S9 | Contacts | `ContactResponse.contactGroupId` | Field **does not exist** in the backend DTO | `contracts.ts:281` vs `contact/dto/ContactResponse.java` | **High** | Remove the phantom field; the group is already in the route |
| S10 | Contacts | Group response has 6 fields | 7 — includes `memberCount: long` | `contact/dto/ContactGroupResponse.java` vs `contracts.ts:234-241` | Medium | Add it; it is the cheapest useful group summary |
| S11 | TTS | `TtsTemplateResponse` has 9 fields | 10 — includes `scope: GLOBAL\|TENANT` | `tts/dto/TtsTemplateResponse.java` vs `contracts.ts:378-388` | **High** | Model `scope`; the UI currently cannot tell a platform catalog template from a tenant one |
| S12 | TTS | Create payload is `{name,description,templateText,variables,tenantId?}` | Also accepts **`scope`**; `GLOBAL` **forbids** a `tenantId` | `dto/CreateTtsTemplateRequest.java:5-6`, `TtsTemplateService.java:78-86` | **High** | Add `scope` or explicitly restrict the UI to tenant templates and document the choice |
| S13 | TTS | — | **No `scope` filter on the list endpoint** | `TtsTemplateController.java:83-87` | Info | **NOT EXPOSED BY BACKEND** — do not design a global/tenant filter UI |
| S14 | Audio | Assets are created by entering metadata | A real multipart `POST /audio-assets/upload` exists and is the only sane path | `AudioAssetController.java:86-100` vs **absent** from `lib/api/audio-assets.ts` | **High** | Implement upload; the current dialog asks users to invent a 64-char SHA-256 checksum |
| S15 | DIDs | `DidResponse` has 14 fields | 15 — includes `allocationSource` | `did/dto/DidResponse.java` vs `contracts.ts:184-199` | Low | Add it |
| S16 | DIDs | — | `POST /dids/{id}/assign` and `/revoke` exist; frontend has **no** calls | `DidController.java:138,157` | Medium | Not required by the migration plan, but record as an unexercised capability |
| S17 | Tenants | New tenants can be placed under a reseller | `toCreateTenantPayload` **hardcodes `resellerId: null`** | `schemas/tenant-mutation.ts:70` | **High** | Wire the value from the form; the reseller flow is unreachable otherwise |
| S18 | Campaign | Clone and status change work | Both handlers are `TODO` stubs that call `window.location.reload()` — while the API functions, the zod schemas **and** `change-status-dialog.tsx` all exist | `campaigns-view.tsx:211-221` vs `campaigns.ts:125-139`, `schemas/campaign-mutation.ts:319-337` | **High** | Wire the existing dialogs; this is a wiring gap, not missing infrastructure |
| S19 | Auth | Password minimum is 8 | Server requires **12** for admin/agent provisioning (`@Size(min=12)`) | `schemas/auth.ts:16`, `schemas/signup.ts:34-37`, `schemas/reseller-mutation.ts:88-91` vs `TenantAdminInput`/`AdminAccountInput` | Medium | Introduce a separate `ADMIN_PASSWORD_MIN = 12`; the shared `PASSWORD_MIN = 8` is wrong for every provisioning form |
| S20 | Schedule | `allowedDaysOfWeek: string[]` | `Set<DayOfWeek>` — a Java enum; any other string fails deserialization | `dto/ScheduleConfig.java` vs `contracts.ts:439`, `schemas/campaign-mutation.ts:57` | Medium | Constrain the TS type to `DayOfWeek` literals and validate in zod |
| S21 | RBAC | 19 capability keys | 34 exist; the frontend catalog **omits** `IVR_VIEW`, `IVR_MANAGE`, `QUEUE_VIEW`, `QUEUE_MANAGE`, `AGENT_VIEW`, `AGENT_MANAGE`, `CALL_VIEW` | `capabilities.ts:9-44` vs the migration list in §10 | Medium | Extend the catalog when those domains are built |
| S22 | RBAC | 12 capabilities gate actions | **0 of them are checked anywhere** | grep over `frontend/src`; approve/reject ungated at `audio-assets-view.tsx:50,53` and `tts-templates-view.tsx:40,41` | **High** | Add per-action gating; today a `RESELLER_ADMIN` sees approve/reject buttons that 403 |
| S23 | Routing | Authenticated pages are guarded | Guard is a client render gate; no `middleware.ts`/`error.tsx`/`not-found.tsx`/`loading.tsx` | `(platform)/layout.tsx:1,78-98` | Medium | Add an error boundary at minimum |
| S24 | Campaign | Campaign edit requires a valid `typeConfig` for DTMF/CONNECT_BY_AGENT | `updateCampaignSchema` deliberately omits that rule ("we don't have original type here") | `schemas/campaign-mutation.ts:268-269` | Low | Acceptable — server validates; keep the comment |
| S25 | FreeSWITCH terminology | — | **Clean.** Zero occurrences of `freeswitch`, `ESL`, `sofia`, `uuid_broadcast/bridge/kill`, `SIP` or `RTP` in `frontend/src` (16 grep hits were all `eslint`) | grep over `frontend/src` | **None** | No action. See §15. |

**No evidence of removed/renamed endpoints.** Every frontend call resolves to a live backend
endpoint. This frontend is **drifted, not stale** — it was built against a slightly earlier
revision of a moving backend.

---

## 13. Reuse / Refactor / Rebuild Matrix

| Frontend area | Current state | Evidence | Class | Reason | Dependencies |
|---|---|---|---|---|---|
| App shell / root layout | Renders Geist fonts, `Providers`, skip-link pattern is in the platform layout | `app/layout.tsx` | **KEEP** | Sound | — |
| `(platform)/layout.tsx` shell | Client guard, skeleton, sidebar + header | `(platform)/layout.tsx:78-116` | **REUSE WITH MINOR CHANGES** | Structure is right; needs a real guard story + `error.tsx` | F1 foundation |
| Navbar / Sidebar / SiteHeader | Capability + `homeType` gated nav, skip link, `aria` wiring | `layout/navigation.ts`, `layout/app-sidebar.tsx`, `layout/site-header.tsx` | **KEEP** | Clean, correct, accessible | — |
| `navigation.ts` `requiredScope` model | Only `"platform"` ever used; `reseller`/`tenant` branches unreachable | `navigation.ts:66-71` | **REFACTOR** | Extend when tenant/reseller surfaces exist; do not delete | RBAC work |
| Auth: token-store | In-memory access token, legacy purge | `lib/auth/token-store.ts` | **KEEP** | Correct security model, well documented | — |
| Auth: axios client + 401 refresh | Single-flight refresh, retry guard, cookie-driven | `lib/api/client.ts:34-98` | **KEEP** | The strongest infrastructure in the repo | — |
| Auth: `unwrap` helper | Lives in `auth.ts`; wrong doc comment; 3 unwrap styles coexist | `auth.ts:10-16` | **REFACTOR** | Move to a transport module, fix the comment, converge on one style | — |
| `lib/api/error.ts` | `ApiError` + `toApiError`; maps ProblemDetail faithfully | `error.ts:11-74` | **KEEP** (drop `NETWORK_ERROR_CODE`) | Correct | — |
| `lib/api/contracts.ts` | Hand-written mirror with a "1:1" claim that is false | `contracts.ts` | **REFACTOR** | Add the 4 missing campaign types/fields, remove the phantom `contactGroupId`, de-duplicate the merged interfaces. **Do not rebuild** — the shape and the discipline are right; it is the contents that drifted | Campaign work |
| `lib/schemas/*` | 11 Zod modules with payload mappers; good `superRefine` intent | `lib/schemas/` | **REFACTOR** | Fix `PASSWORD_MIN` for admin forms, `intervalSeconds` bound, `allowedDaysOfWeek` type, TTS update asymmetry; delete 2 dead exports | — |
| Query layer (`lib/query-client.ts`, keys factories) | One shared client, per-domain key factories, `placeholderData` | `query-client.ts`, `*/api/*` key exports | **KEEP** | Well designed | — |
| `components/common/*` | 17 shared items: pagination, skeleton, 5 filter toolbars, 10 status badges | `components/common/` | **KEEP** — extend | The single highest-leverage reusable asset in the repo | — |
| `components/ui/*` (24 primitives) | shadcn/Radix | `components/ui/` | **KEEP** | Standard, no reason to rebuild | — |
| Forms (`react-hook-form` + Zod) | Consistent pattern incl. server `fieldErrors` → field mapping | `sign-in-form.tsx:62-81` and peers | **KEEP** | Good | — |
| Tables (TanStack Table) | Consistent 9-table pattern | `*/[a-z]*-table.tsx` | **KEEP** | Good | — |
| Notifications (`sonner`) | 32 call sites, uniform success/error | `components/ui/sonner.tsx` | **REFACTOR** | Mount a `ThemeProvider`; wire `position`/`closeButton` deliberately | — |
| Error boundaries | **None** | grep over `app/**` | **ADD** | Required for a production SPA | F1 foundation |
| Route guard | Client render gate only | `(platform)/layout.tsx:1` | **REFACTOR** | Add `middleware.ts` or an explicit server check | F1 foundation |
| `zustand` dependency | Declared, **zero imports** | `package.json:30`; grep over `src` | **REMOVE** | Dead dependency | — |
| `next-themes` | Consumed without a provider | `package.json:21`, `ui/sonner.tsx:3` | **REFACTOR** (or remove) | Either mount the provider or drop the call | — |
| Users pages | List/detail/edit; matches a 3-endpoint backend | `components/users/` | **KEEP** | Backend has no create/delete either | — |
| Tenants / Resellers pages | Full CRUD; `resellerId` hardcoded null | `components/tenants/`, `components/resellers/` | **REFACTOR** | Wire `resellerId`; add the missing agent-creation path | Tenant/RBAC work |
| DIDs pages | List/CRUD | `components/dids/` | **REUSE WITH MINOR CHANGES** | Add `allocationSource`; assign/revoke optional | — |
| Contact Groups pages | Full CRUD + members + import/export | `components/contact-groups/` | **KEEP** + **REUSE** the import/export helpers for Contacts | Working | — |
| Contacts pages | Nested under a group; correct URL shape; phantom `contactGroupId` | `components/contacts/` | **REFACTOR** | Fix the type; decide roster vs child-CRUD UX against the 6 unused `/members` endpoints | Contacts workstream |
| Audio pages | Metadata CRUD + approve/reject; **no upload** | `components/audio-assets/` | **REBUILD the create path** | The metadata-only dialog is not a usable product flow; upload must be implemented. List/detail/table/badge = **KEEP** | Audio workstream |
| TTS pages | Single flat list, no `scope` awareness | `components/tts-templates/` | **REBUILD** | The backend model is two scopes; the UI models one. List/table/dialogs are reusable **with** a scope-aware layer | TTS workstream |
| Campaigns pages | List/create/edit/delete real; clone + status change stubbed; no `MISSED_CALL` | `components/campaigns/` | **REFACTOR** (heavy) | Table/toolbar/badges **KEEP**; create+edit dialogs need typed `typeConfig`, the 4 new fields, retry rules, `MISSED_CALL`, and the safety/limit fields. **Not a rebuild** — the plumbing is sound | Campaign workstream |
| Campaign `typeConfig` form | `Record<string, unknown>` passthrough | `contracts.ts:468` | **REBUILD** | No typed shape exists; per-type editors are required | Requires Queue + Agent data for CONNECT_BY_AGENT |
| Execution detail page | Real | `campaigns/[campaignId]/executions/[executionId]` | **REFACTOR** | Add `configurationSnapshotId`; fix the attempt-failed call | — |
| Executions list page | **Absent** (only reachable via campaign detail) | `app/(platform)/` has no `executions` route | **ADD** | `GET /campaigns/{id}/executions` exists | Campaign workstream |
| IVR UI | **Absent** | no route, no component | **DO NOT BUILD YET** | Backend is 403-blocked (§18) | Backend fix first |
| Queues UI | **Absent** | no route | **BUILD (prerequisite)** | `CONNECT_BY_AGENT.typeConfig.queueId` is unusable without it | Campaign workstream |
| Agents UI | **Absent** | no route | **BUILD (prerequisite)** | Needed to understand agent state referenced by CONNECT_BY_AGENT | Campaign workstream |
| Scheduler UI | **Absent** | no route | **DO NOT BUILD** | **NOT EXPOSED BY BACKEND** | Backend work first |
| Dashboard | **Absent** — `/` redirects to `/account` | `app/page.tsx:19` | **UNKNOWN** | No backend metrics/aggregate endpoint was found. Needs product decision | — |

---

## 14. Error / Pagination / Validation Contract

### 14.1 Error contract

`GlobalExceptionHandler` (`common/exception/GlobalExceptionHandler.java`) produces RFC 9457
`ProblemDetail` extended with `code`, `requestId`, `timestamp` and optional `errors[]`.
Security-layer errors are produced separately by `RestAuthErrorHandling` with the same field
set, so 401/403 from the filter chain are **shape-identical** to those from the MVC layer.

| Code | HTTP | Raised by |
|---|---|---|
| `BAD_REQUEST` | 400 | `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException`, `MissingServletRequestParameterException`, 405 (title "Method not allowed", **code stays `BAD_REQUEST`**) |
| `VALIDATION_ERROR` | 400 | `MethodArgumentNotValidException`, `ConstraintViolationException`, `HandlerMethodValidationException` → `errors[]` |
| `UNAUTHORIZED` | 401 | `BusinessException`, `AuthenticationException`, entry point |
| `FORBIDDEN` | 403 | `ForbiddenException` (generic, non-leaking message), `AccessDeniedException` |
| `RESOURCE_NOT_FOUND` | 404 | `ResourceNotFoundException`, `NoResourceFoundException` |
| `CONFLICT` | 409 | `ConflictException` — duplicate E.164, duplicate attempt, illegal lifecycle transition, already-approved asset, duplicate queue name |
| `BUSINESS_RULE_VIOLATION` | **422** | `BusinessException` with this code (e.g. "A tenant must be specified") |
| `RATE_LIMITED` | 429 | `LoginProtectionService` / Redis limiter |
| `INTERNAL_SERVER_ERROR` | 500 | catch-all |
| `SERVICE_UNAVAILABLE` | 503 | `AudioStorageException` / storage disabled |

**Frontend compatibility: MATCH.** `toApiError` (`error.ts:51-74`) reads
`detail ?? title ?? FALLBACK_MESSAGE`, carries `code`, `requestId` and `fieldErrors`, and
degrades safely when the body is not RFC-shaped (`extractProblemDetail` requires a `status`
key). Views map `fieldErrors` onto form fields and show `requestId` for traceability
(`sign-in-form.tsx:62-81`; the 403 branch in every list view).

**Frontend gaps in error handling:**
- 405 returns HTTP 405 with `code: "BAD_REQUEST"` — the frontend has no 405 branch; it will
  fall through to `FALLBACK_MESSAGE`. Low impact.
- `ApiError.fieldMessage()` and `NETWORK_ERROR_CODE` are dead (`error.ts:4,37`).
- No global boundary, so an unhandled render throw produces a generic Next.js error page.

### 14.2 Pagination / filtering / sorting

| Aspect | Backend contract | Frontend behaviour | Verdict |
|---|---|---|---|
| Index base | `page` 0-based, floored at 0 | URL is **1-based**, converted at the hook boundary (`use-url-list-state.ts:49-51,82`) | **MATCH** — deliberate and correct |
| Page size | clamped **1..100** | UI offers a fixed whitelist; API passes `state.size` | MATCH |
| Total | `PaginationMetadata{page,size,totalElements,totalPages,hasNext,hasPrevious}` | `TablePagination` consumes it | MATCH |
| Sort | `sort=field,dir`; ASC only for literal `"asc"`; per-domain allowlist; invalid fields silently fall back | `*_SORTABLE_FIELDS` arrays mirror the allowlists; `buildSort` emits `field,dir` | MATCH |
| Search | `search` everywhere **except DID which uses `q`** | `dids.ts:84` correctly sends `q`; all others send `search` | MATCH (inconsistently applied, but correctly) |
| Filters | entity-specific (`status`, `allocationState`, `numberType`, `provider`, `circle`, `tenantId`, `resellerId`, `campaignType`, `runMode`); unknown enum value → **400** | toolbars send them; type unions are hand-maintained | PARTIAL — a union drift (e.g. `MISSED_CALL`) silently omits a filter option |
| Envelope | `pagination` is **null** for non-paginated lists (executions, attempts, IVR, queue members, agent endpoints, active calls, waiting calls) | attempts **fabricate** a single-page `PaginationMetadata` (`call-attempts.ts:98-105`) | **MISMATCH** — a real page object that does not exist server-side |
| `buildPageable` semantics | `length > 0` for most services; **`length >= 2`** for Queues and Agents (bare `sort=name` ignored) | not modelled (no UI) | Info — affects future Queues/Agents work |
| Default ordering | `createdAt,desc` almost everywhere; **`name,asc`** for queues; **`displayName,asc`** for agents; **`firstName,asc`** for contacts; **hardcoded `createdAt DESC`** for IVR | `defaultSort` mirrored per domain | MATCH where implemented |

**Do not unify this model.** The backend is genuinely inconsistent (three different `buildPageable`
behaviours, two different search parameter names, several unpaginated collections). A single
frontend abstraction must accommodate that, not hide it.

---

## 15. Campaign Architecture Audit

**Verified relationship (read from the source, not assumed):**

```
CampaignEntity                       ← mutable, soft-deleted, tenant-scoped, @Version optimistic lock
   │  campaign_type, contact_group_id, did_id, content_mode, audio_asset_id, tts_template_id,
   │  schedule_* + timezone + allowed_days_of_week + holiday_calendar_id,
   │  retry_max_attempts / interval / strategy / rules, type_config, integration_config,
   │  call_on_whitelist_numbers, daily_dial_limit, max_daily_attempts, max_call_duration_seconds
   │
   │  CampaignConfigurationService / CampaignMapper capture, at execution-creation time ONLY:
   ↓
CampaignConfigurationSnapshot        ← @Embeddable, the payload of the frozen row
   │  (columns listed in CampaignConfigurationSnapshot.java:83-226)
   ↓
CampaignExecutionConfiguration       ← immutable persisted row; has its own id
   │  (V44__campaign_execution_configurations.sql, V55__execution_snapshot_integration_config.sql)
   ↓
CampaignExecution                    ← references configurationSnapshotId; own status machine
   │  REQUESTED → RUNNING → COMPLETED | FAILED | CANCELLED
   ↓
CallAttempt                          ← per (execution, contact, did, attemptNumber)
      QUEUED → IN_PROGRESS → COMPLETED | FAILED | CANCELLED
```

**Confirmed answer to the central question: editing a campaign does NOT mutate an existing
execution.** The boundary is enforced structurally, not by convention:

- `CampaignRuntimeConfigResolver.CampaignRuntimeConfig` is built **solely** from a frozen
  snapshot row; no API on the resolver, the snapshot, or their records returns a
  `CampaignEntity`. Reading the mutable campaign from an execution-time path "requires
  reaching past the resolver to the repository" (`CampaignConfigurationSnapshot.java:50-56`).
- Both DTO schemas state it in OpenAPI text: *"Changing any of these does not alter an
  execution that already exists: each execution owns an immutable configuration snapshot"*
  (`dto/UpdateCampaignRequest.java` `typeConfig` description).
- `configurationSnapshotId` is returned on `CampaignExecutionResponse` precisely so a client
  can see which frozen configuration an execution runs against — **the frontend type omits
  this field**.

**Two deliberate exceptions (documented, not bugs):**
1. **Resource *validity* is never frozen.** A snapshot stores the *reference*; whether the DID
   is still assigned, the audio still approved, the queue still active is **always
   runtime-checked** (`CampaignConfigurationSnapshot.java:37-38`). A campaign can be ready at
   creation and unready at dial time.
2. **`integrationConfig` was frozen early, before any consumer existed** (VB-7C.3, migration
   V55). `null` is meaningful and preserved verbatim — "never configured" is deliberately
   distinct from "configured to defaults" (`CampaignConfigurationSnapshot.java:186-194`).
   **Note V55 is untracked in git** (`?? backend/.../V55__execution_snapshot_integration_config.sql`).

**Mutability classification:**

| Configuration | Mutable on the campaign? | Frozen per execution? |
|---|---|---|
| `campaignType` | **No — immutable for the campaign's lifetime** (`CampaignType.java:6`) | Yes |
| `contactGroupId`, `didId` | Yes | Yes |
| `contentMode`, `audioAssetId`, `ttsTemplateId` | Yes | Yes (reference only) |
| Schedule window + timezone + days + holiday calendar | Yes | Yes |
| Retry policy (flat + `rules`) | Yes | Yes |
| `typeConfig`, `integrationConfig` | Yes | Yes |
| `callOnWhitelistNumbers`, `dailyDialLimit`, `maxDailyAttempts`, `maxCallDurationSeconds` | Yes | Yes |
| `name`, `description` | Yes | **No — intentionally excluded** (administrative metadata) |
| `runMode` | Yes | **No — not in the snapshot** |
| `status` | Yes (lifecycle machine) | No |
| Referenced-resource validity (DID assigned? audio APPROVED? queue ACTIVE?) | n/a | **Never frozen — runtime only** |

**Frontend implication (the important one).** The frontend must not present campaign editing
as something that changes a running campaign. There is no "apply to running executions"
concept, and adding one would be a false affordance. The execution detail page should display
the frozen `configurationSnapshotId` so the distinction is legible.

**`typeConfig` shapes the frontend must model (all unknown fields rejected with 400):**

| `campaignType` | Required `typeConfig` | `playsMedia()` |
|---|---|---|
| `PLAYFILE` | none | `true` |
| `DTMF` | `dtmf{...}` (see `DtmfCampaignConfig`) | `true` |
| `CONNECT_BY_AGENT` | `connectByAgent{queueId, selectionStrategy:"LEAST_ACTIVE_RESERVATIONS", ringDurationSeconds:10-240}` | `false` |
| `MISSED_CALL` | `missedCall{ringDurationSeconds:10-60}` | `false` |

`playsMedia()` (`CampaignType.java:68-75`) is the **single** rule that derives both
"content is required" and "TTS is rejected" (no synthesis/playback runtime exists). It is
exhaustive by construction — the `switch` has no `default` arm, so adding a type fails the
build until its capability is stated. The frontend's `createCampaignSchema` reimplements this
as a type-membership list (`schemas/campaign-mutation.ts:153-170`), which is the exact
fail-open pattern the backend comment (`CampaignType.java:41-48`) says it replaced. The
frontend rule is currently correct only because `MISSED_CALL` is missing from its union.

**`integrationConfig` — configuration only, nothing is delivered.** No webhook transport,
signing, retry, queue or worker; no report generation or export. `webhook.enabled` is the only
switch; an endpoint alone never enables it. Events come from a fixed vocabulary
(`WebhookEvent`: `campaign.attempt.completed|failed|cancelled`) and `SWITCHED_OFF` /
`NOT_REACHABLE` in `RetryRuleCategory` are accepted for a future provider mapping that no
provider feeds today (`dto/RetryRuleConfig.java` description). **A UI must not imply delivery.**

**Failure taxonomy (53 codes) — partially infrastructure-flavoured.**
`CallFailureCode` covers carrier outcomes (`BUSY`, `NO_ANSWER`, `REJECTED`, `CONGESTION`,
`SWITCHED_OFF`-class), compliance blocks (`DNC_BLOCKED`, `NOT_WHITELISTED`, `RESELLER_BLOCKED`,
`PLATFORM_BLOCKED`, `PLATFORM_PROTECTED`, `NOT_IN_CAMPAIGN_TARGETS`), and platform internals
(`NO_ELIGIBLE_GATEWAY`, `GATEWAY_CAPACITY_EXHAUSTED`, `CALL_ORIGINATE_FAILED`,
`PROVIDER_UNAVAILABLE`, `ROUTE_REJECTED`, `EXECUTION_TIMEZONE_INVALID`, `EXECUTION_CONFIG_MISSING`,
`AGENT_ORIGINATE_FAILED`, `INBOUND_ROUTE_INVALID`). The frontend types `failureCode` as
`string | null` and ships **no vocabulary**, so it cannot render a meaningful label.
Recommended: a display-name map that groups codes into user-facing categories
(carrier outcome / blocked by policy / platform capacity / configuration error) rather than
exposing 53 raw codes.

**FreeSWITCH boundary audit — result: CLEAN on the frontend.**
Searching `frontend/src` for `freeswitch`, `ESL`, `sofia`, `uuid_broadcast`, `uuid_bridge`,
`uuid_kill`, `SIP`, `RTP`: **zero matches** (the 16 initial hits were all the substring
`eslint`). No FreeSWITCH concept reaches the UI, the API types, the routes, the components or
the labels. **This is a genuine strength and must be preserved.**

The infrastructure vocabulary does, however, cross the **contract** boundary and would reach
the UI if the frontend ever rendered raw codes:
- `CallAttemptResponse.failureCode` / `failureReason` — free-form strings, unconstrained.
- `AgentActiveCallResponse` / `AgentCallHistoryResponse` expose `sessionStatus`, `legStatus`,
  `legType`, `direction` as **plain `String`s**, not enums — no vocabulary is published.
- `AgentEndpointResponse.endpointType` (`SIP`, `WEBRTC`, `MOBILE_APP`, `EXTERNAL_FORWARD`, `AI`)
  and `.dialTarget` (SIP URIs) — only `SIP` and `EXTERNAL_FORWARD` are accepted by the service.

**Recommended follow-up (documentation, not a backend change):** when Agents/Queues are
built, publish enums for the `String`-typed agent call fields and a human-readable label map
for `CallFailureCode`, so `uuid_*`, gateway and provider terms stay server-side.

---

## 16. Audio / TTS Audit

**Audio — single, tenant-only model.**

`AudioAssetEntity` has `tenant_id NOT NULL`. **There is no global/platform audio tier.**
Lifecycle: `PENDING_APPROVAL` → `APPROVED` | `REJECTED`; approve/reject require `AUDIO_APPROVE`
and 409 if already in the target state. Two creation paths exist:

| Path | Body | Server-derived fields | Frontend |
|---|---|---|---|
| `POST /audio-assets/upload` (multipart) | `name`, `description?`, `file` | `fileName`, `contentType`, `fileSize`, `durationSeconds`, `checksum`, `storageReference` are **all computed server-side** after validating WAV/MP3 and a 5 MiB cap | **NOT USED** |
| `POST /audio-assets` (JSON) | all metadata supplied by the caller, validated only for shape | none | **USED** — the only path the UI offers |

This is the inversion to correct: the metadata endpoint is for externally-provisioned assets;
the upload endpoint is the product path. The current dialog requires a hand-typed 64-hex-digit
`checksum` (`schemas/audio-asset-mutation.ts:11`) and `storageReference` that nothing
verifies. Campaign consumption requires `AUDIO_APPROVED` (the frontend reference fetchers
correctly hardcode `status:"APPROVED"`, `campaigns.ts:242-244`).

**TTS — two genuinely distinct scopes. Do not merge them.**

`TtsTemplateScope` is `GLOBAL` | `TENANT`, and `tenant_id IS NULL` **exactly** when scope is
`GLOBAL` (migration V42 added the scope column). The two are different in ownership,
authorization, lifecycle and visibility:

| | `GLOBAL` | `TENANT` |
|---|---|---|
| `tenant_id` | `null` | owning tenant |
| Created by | platform caller only (`TtsTemplateService.java:78-86`; a `tenantId` in the body is **rejected 400**) | a tenant-context caller |
| Initial status | **`APPROVED` immediately** (`:110-111`) | `PENDING_APPROVAL` |
| Manage/update check | `AccessCheck.platformWide()` (`manageCheckFor`, `:239-243`) | `AccessCheck.forTenant(entity.getTenantId())` |
| Approve/reject | `TTS_APPROVE` platformWide | `TTS_APPROVE` for the owning tenant |
| Content edit on an APPROVED row | reverts to `PENDING_APPROVAL` — a platform edit **re-enters the platform gate** (`:181-186`) | same |
| Tenant list visibility | visible **only when `APPROVED`** | own rows, any status |
| Reseller list visibility | `visibleGlobalOnly()` when the hierarchy has no tenants; else `visibleToTenants(hierarchy)` | included via the hierarchy |
| Platform list visibility | all | all |

Two further verified facts:
- `UpdateTtsTemplateRequest` has **no** `tenantId`, **no** `scope`, **no** `status` — scope
  and ownership are immutable.
- The list endpoint has **no `scope` filter** (`TtsTemplateController.java:83-87`).
  **NOT EXPOSED BY BACKEND.**
- The frontend reference fetcher hardcodes `status:"APPROVED"` (`campaigns.ts:257-259`), which
  correctly surfaces only the usable catalog (tenant's approved rows + approved globals) —
  but the UI cannot explain *why* a template is or isn't in that list.

**Verdict.** The backend treats GLOBAL and TENANT TTS as two different resources with
different owners and different governance. The frontend collapses them into one
undifferentiated "TTS Templates" list with no scope column, no scope filter and no way to
create a global template. **Do not merge them further; the frontend needs to *split* them.**

**Consumption chain (verified):**
`Campaign.audioAssetId` / `Campaign.ttsTemplateId` ← must be `APPROVED` at
`CampaignResourceValidationService` / readiness time, and re-checked at runtime (never
frozen). The frontend's hardcoded `status:"APPROVED", size:100` reference fetchers are
contract-correct but **silently truncate at 100 records** with no user indication.

---

## 17. Testing & Validation Audit

### 17.1 Frontend

| Item | State |
|---|---|
| Test runner | **None installed** — no vitest/jest/playwright/cypress in `package.json` |
| Test script | **None** — scripts are `dev`, `build`, `start`, `lint`, `typecheck` |
| Test files | **0** |
| E2E | **None** |
| CI | `.github/` exists but is **empty** — no workflows |

### 17.2 Backend — 179 test files

The most useful **contract evidence for frontend work** (these tests encode the wire format
the frontend must match):

| Test | Value to the frontend |
|---|---|
| `CampaignOpenApiContractTest` | Campaign wire contract incl. the new snapshot fields |
| `ContactGroupMemberOpenApiContractTest` | Roster DTO shape (`memberId`/`groupId`/`contactId`) |
| `IvrOpenApiContractTest` | IVR DTO shape (nodes keyed by `nodeKey`, `input`/`targetNodeKey`) |
| `ApiResponseContractTest` | Envelope + pagination metadata |
| `ErrorResponseContractTest` | ProblemDetail shape and status mapping |
| `FieldErrorMappingTest` | `errors[]` field/code/message construction |
| `RequestIdPropagationTest` | `requestId` propagation (frontend surfaces it) |
| `SecuritySliceTest`, `AuthorizationEnforcementTest` | The real RBAC boundary |
| `Campaign*ApiSliceTest` (4) | Campaign/attempt/retry request-response binding |
| `AgentDirectoryApiSliceTest`, `QueueDirectoryApiSliceTest` | Agent/Queue contracts |
| `*PostgresIntegrationTest` (~25, Testcontainers 2.0.2) | Real schema/constraint behaviour |
| `ArchitectureTest` | Spring Modulith boundary enforcement |

### 17.3 Validation commands — verified by execution

| Project | Command | Result |
|---|---|---|
| Frontend | `npm run typecheck` (`tsc --noEmit`) | **PASS** (exit 0) |
| Frontend | `npm run lint` (`eslint`) | **PASS** (exit 0) — **0 errors, 5 warnings**, all `react-hooks/incompatible-library` (React Compiler skipping memoization of `form.watch`): `campaigns/edit-campaign-dialog.tsx:100`, `campaigns/campaign-table.tsx:206`, `audio-assets/audio-asset-table.tsx:49`, `tts-templates/tts-template-table.tsx:33`, `tts-templates/create-tts-template-dialog.tsx:49`, `tts-templates/edit-tts-template-dialog.tsx:51` |
| Frontend | `npm run build` | Not run in F0 (mutates `.next/`) |
| Frontend | tests | **Not available** |
| Backend | `./mvnw test` | Not run in F0 (requires PostgreSQL + Redis via Testcontainers/Docker Compose) |
| Backend | `./mvnw` wrapper present (`mvnw`, `mvnw.cmd`) | Confirmed |
| OpenAPI | `/v3/api-docs`, `/swagger-ui` (runtime, `permitAll`) | No committed spec to diff against |

**No project configuration was changed to make a command pass.**

**Recommended migration gates (not built during F0).**
1. `npm run typecheck` must stay at exit 0 — a cheap tripwire for DTO drift.
2. Extract the generated `/v3/api-docs` into `docs/api/openapi.json` and add a drift check
   against `contracts.ts`. This single step would have caught S1, S2, S3, S6, S9, S10, S11, S15.
3. Add a minimal test runner (Vitest + Testing Library) for: the `toApiError` mapping, the
   `unwrap` paths, and one contract test per migrated domain.
4. Treat the backend `*OpenApiContractTest` and `*ApiSliceTest` suites as the authority when
   a frontend type is ambiguous.

---

## 18. Risks & Unknowns

### 18.1 Backend finding (documented, not fixed — F0 rule)

**`IVR_VIEW` and `IVR_MANAGE` are enforced but never seeded → all IVR endpoints return 403 for
every role.**

- `IvrTreeService.java:77` `public static final String CAP_VIEW = "IVR_VIEW";`
- `IvrTreeService.java:80` `public static final String CAP_MANAGE = "IVR_MANAGE";`
- Enforced at lines 111, 137, 146, 179, 208, 247 via `requireCapability`.
- `AuthorizationService.findCapabilityId` (L127-131) looks the key up in the `capabilities`
  table; **a missing row yields `null`, and `hasCapability` returns `false` at L65-66.**
- A grep for `IVR_VIEW|IVR_MANAGE` across **all 55 migrations returns no matches.**
  `V53__ivr_trees.sql` contains no `capabilities` or `role_capabilities` insert.

**Impact.** Every `/api/v1/ivr-trees` endpoint (6) returns 403 `FORBIDDEN` for `SUPER_ADMIN`,
`RESELLER_ADMIN`, `TENANT_ADMIN` and everyone else. `POST /campaigns/{id}/ivr-tree` fails for
the same reason.

**Frontend impact.** None *yet* — the frontend has no IVR surface. But the backend migration
`docs/campaign-readiness.md`, `V53`, and `CampaignIvrController` all assume a working IVR
domain, so any frontend IVR work would be blocked on day one.

**Recommended follow-up (backend owner).** Add a migration inserting `IVR_VIEW`/`IVR_MANAGE`
into `capabilities` and granting them to `SUPER_ADMIN`/`RESELLER_ADMIN`/`TENANT_ADMIN`
(mirroring `V20` for TTS and `V37` for QUEUE). Until then: **do not build IVR UI.**

### 18.2 Backend findings (non-blocking, documentation only)

| # | Finding | Evidence | Frontend impact |
|---|---|---|---|
| B1 | `CreateResellerRequest.admin` is `@Valid` without `@NotNull`, yet `ResellerProvisioningService:74` dereferences `request.admin().password()` | `reseller/dto/CreateResellerRequest.java:8` | Client should always send `admin`; a nullable TS type invites a 500 |
| B2 | IVR list accepts `page`/`size` but returns `ResponseFactory.ok(list)` with **no** `PaginationMetadata` | `IvrTreeService.java:161` | A paginated UI would show false page controls |
| B3 | 405 responses carry `code: "BAD_REQUEST"` | `GlobalExceptionHandler.java:88-95` | No 405 branch in the frontend |
| B4 | `POST /tenants/{tenantId}/agents` returns a **`TenantResponse`**, not a user or agent | `tenant/TenantController.java:139-146` | A frontend action on this endpoint has no sensible result to render |
| B5 | `tenant/dto/CreateAgentRequest` and `voice/agent/dto/CreateAgentRequest` are different records with the same name and the same fields but different meanings (login credentials vs agent profile) | both files | Naming collision; must not be conflated in the frontend |
| B6 | V43 is absent from the migration sequence | `db/migration/` listing | Not a defect; noted so numbering gaps are not mistaken for loss |
| B7 | `IVR` is **tenant-only** — no reseller or platform branch exists | `IvrTreeService.java:511-517` | Even after the capability fix, IVR is unreachable for platform/reseller users |
| B8 | `CreateAgentEndpointRequest.agentId` is `@NotNull` but **ignored** by the service; the path `{id}` wins | `AgentDirectoryService.java:302-303` | A required-but-ignored field; easy source of bugs |
| B9 | `AgentAvailabilityResponse.presence` (response) vs `UpdateAgentPresenceRequest.availability` (request) — same concept, different field names | `voice/agent/dto/*` | Trap for the Agents workstream |
| B10 | `V55__execution_snapshot_integration_config.sql` and `ExecutionSnapshotIntegrationConfig*Test` are **untracked** in git | `git status` | Must be committed before the snapshot contract can be relied on |
| B11 | `Delete-` of the auth cookie is `Max-Age=0`; the backend sets `SameSite=Strict` with `secure` defaulting **true** — a non-HTTPS dev profile must opt out explicitly | `AuthCookieProperties.java:13-14` | Relevant to local dev setup only |
| B12 | 5 `hs_err_pid*.log` and 4 `replay_pid*.log` files are committed/present in `backend/` | directory listing | Repo hygiene; JVM crash artifacts |

### 18.3 Explicit unknowns — `UNKNOWN — requires confirmation`

| # | Question | Why it cannot be answered from the repository |
|---|---|---|
| U1 | Which capability keys should gate each frontend action by product intent? | The backend enforces them; **the frontend currently gates none of the 12 `_MANAGE`/`_APPROVE`/`_EXECUTE` keys**, so the intended mapping is not observable |
| U2 | Is `hasPlatformAccess()` (`homeType === null && TENANT_VIEW`) an approved rule? | It is a frontend invention with zero callers and no backend counterpart |
| U3 | Should a reseller be able to *act as* a selected tenant? | Backend supports hierarchy-wide **read**; nothing in either repo defines a tenant-switching product model |
| U4 | Is there a planned dashboard/landing route? | `/` redirects to `/account`; no aggregate/metrics endpoint was found. `REPORT_VIEW`/`REPORT_EXPORT` are seeded but enforced by **no** controller |
| U5 | Should the frontend ever expose `ROLE_VIEW`/`ROLE_MANAGE` or custom roles? | Roles are DB-driven and read-only through the API; no role CRUD endpoint exists |
| U6 | Is the `MISSED_CALL` campaign type in product scope for the frontend? | It is fully implemented backend-side (V54, config, execution service) but no UI decision is recorded anywhere |
| U7 | Should `integrationConfig` get a UI before any delivery runtime exists? | The backend is emphatic that it is configuration-only; no product requirement found |
| U8 | What is the intended UX for the 6 unused `/members` roster endpoints? | They exist with a richer DTO (`ContactGroupMemberResponse`) than `/contacts`; no frontend design decision recorded |
| U9 | Is the 5-minute `staleTime` on `/auth/me` acceptable? | No product or security requirement states a session-refresh cadence |
| U10 | Are the 5 untracked FreeSWITCH compose files / `tools/freeswitch-harness` in scope for the frontend? | They are infrastructure; no frontend relevance found |
| U11 | Does any consumer require the uncommitted V55 snapshot column to exist? | The file is untracked; the current HEAD schema lacks `integration_config` on the execution configuration |
| U12 | Are the `_FREE` "dead routes" (e.g. `/dids` has no detail page) intentional? | No design record; several detail routes exist for other domains and not for DIDs |

---

## 19. Recommended Migration Sequence

Derived from the dependency graph the repositories actually exhibit, not from the illustrative
order in the brief. **Two deviations from the brief's example order are evidence-based:**

1. **Queues must precede Campaigns**, not follow them. `CONNECT_BY_AGENT.typeConfig` requires
   `connectByAgent.queueId` referencing a queue owned by the campaign's tenant. A tenant with
   >0 queues still cannot configure one campaign type, because there is no queue API client,
   no queue route, and no queue picker.
2. **Audio upload must precede the TTS/Campaign content work**, because campaign content
   selection is gated on `APPROVED` assets and the only frontend creation path produces
   un-verifiable metadata.

```
F0 Audit (this document)
  ↓
F1  Foundation — contract alignment
      • Fix DTO/type drift: MISSED_CALL, 4 CampaignResponse fields, retryPolicy.rules,
        CampaignExecutionResponse.configurationSnapshotId, DidResponse.allocationSource,
        remove the phantom ContactResponse.contactGroupId, add ContactGroupResponse.memberCount
      • Fix the 2 wrong request shapes: markFailed → query params; drop dead attempt list params
      • Commit /v3/api-docs to docs/api/openapi.json and add a drift check
      • Add error.tsx / not-found.tsx; add a real route guard; fix the unwrap doc comment
      • Remove zustand; resolve the next-themes inconsistency
      Gate: typecheck exit 0, lint 0 errors, openapi drift check green
  ↓
F2  RBAC — action-level capability gating
      • Extend the Capability catalog to all 34 keys
      • Gate every mutating action (12 currently ungated), starting with AUDIO_APPROVE/TTS_APPROVE
      • Stop inferring scope from capabilities; document homeType as the only scope signal
      Gate: a RESELLER_ADMIN sees no approve/reject button; no ungated mutation remains
  ↓
F3  Tenant & context model
      • Decide and implement the reseller tenant-selection model (U3)
      • Wire the hardcoded resellerId: null
      • Document that /me capabilities are scope-unions, not grants
      Gate: a reseller can create a tenant under itself and scope its view to a selected tenant
  ↓
F4  Contacts & Groups
      • Align ContactResponse/ContactGroupResponse types
      • Decide roster (/members) vs child-CRUD (/contacts) UX — resolve U8
      Gate: create/edit/delete/import/export verified against the real endpoints
  ↓
F5  Audio — implement multipart upload
      • POST /audio-assets/upload with name/description/file
      • Remove the hand-typed checksum/storageReference fields from the create form
      Gate: an uploaded WAV/MP3 appears APPROVED-eligible and can be selected by a campaign
  ↓
F6  TTS — split the two scopes
      • Model TtsTemplateScope; show scope in list/detail
      • Add scope to the create payload (GLOBAL forbids tenantId)
      • Surface the APPROVED-only campaign picker truthfully
      Gate: platform-created GLOBAL templates and tenant TEMPLATE templates are distinguishable
         and each is only editable by its owner
  ↓
F7  Campaign foundation
      • Typed per-type typeConfig editors (PLAYFILE, DTMF, MISSED_CALL)
      • Expose the 4 new fields + retry rules
      • Wire the existing clone and status-change dialogs (they are already written)
      Gate: a campaign of each type can be created and round-trips its config exactly
  ↓
F8  Queue surface  ← prerequisite, must land before F9's CONNECT_BY_AGENT
      • Queue list/detail/members per /api/v1/queues (mind the 1-based sort requirement)
      Gate: a tenant can select a queue it owns
  ↓
F9  Campaign type-specific configuration — complete
      • CONNECT_BY_AGENT editor using real queue data
      • Display the snapshot boundary (configurationSnapshotId) on execution detail
      Gate: CONNECT_BY_AGENT round-trips; editing a campaign demonstrably does not alter an
         existing execution
  ↓
F10 Execution & attempt visibility
      • Executions list page; honest unpaginated rendering for executions and attempts
      • Human-readable failure-code labels grouped by disposition
      Gate: no fabricated PaginationMetadata; failure diagnostics actually persist (F1 fix verified)
  ↓
F11 Agent surface (if in product scope — U3-adjacent)
      • Agents list/detail/endpoints; mind B8 (ignored agentId) and B9 (presence vs availability)
  ↓
BLOCKED  IVR UI          → blocked on backend finding §18.1 (and tenant-only scope, B7)
BLOCKED  Scheduler UI    → NOT EXPOSED BY BACKEND. No endpoint exists.
BLOCKED  Dashboard       → no aggregate endpoint found (U4)
```

**Dependencies that constrain the order.**
- F1 → everything: every later domain builds on the corrected type layer.
- F2 → F5/F6/F7: the approve/reject and manage actions must be gated before the audio/TTS
  rebuilds them, or the new UI inherits the same ungated-action defect.
- F5 → F7: campaign content selection requires approved, genuinely-uploaded assets.
- F6 → F7: campaign content selection requires approved TTS templates.
- F8 → F9: `CONNECT_BY_AGENT` is unusable without queue data.
- F7 → F10: the snapshot boundary cannot be displayed until the field is modelled (F1) and
  executions are surfaced (F10).

---

## 20. F1 Readiness Checklist

F1 may begin when every box below is true. Items already satisfied are marked ✔.

**Audit baseline**
- [x] Actual frontend repository inspected and mapped
- [x] Actual backend repository inspected and mapped
- [x] Relevant API inventory built from controller source (not from docs)
- [x] DTOs, enums and validation annotations compared field-by-field
- [x] Auth flow traced end-to-end
- [x] RBAC derived from migrations + `AuthorizationService`, not assumed
- [x] Tenant boundary traced from JWT to repository
- [x] Contract, staleness and RBAC matrices produced
- [x] Reuse/refactor/rebuild classification completed per area
- [x] Campaign config → snapshot → execution → attempt relationship **verified in source**
- [x] Audio/TTS ownership model **verified in source**
- [x] Error and pagination contracts compared against the frontend
- [x] FreeSWITCH boundary confirmed clean on the frontend
- [x] Existing tests and validation commands inspected; `typecheck` and `lint` executed
- [x] Unknowns enumerated explicitly (U1–U12)
- [x] Migration sequence derived from observed dependencies

**Must be true before F1 code is written**

- [ ] **Owner decision on U3** — does a reseller select and act as a tenant? Blocks F3, and
      therefore the shape of every tenant-scoped screen.
- [ ] **Owner decision on U1** — the capability → action mapping for the 12 ungated keys.
      Without it F2 cannot be specified, let alone verified.
- [ ] **Owner decision on U6** — is `MISSED_CALL` in frontend scope? It is fully implemented
      backend-side; excluding it means the campaign type filter stays permanently wrong.
- [ ] **`/v3/api-docs` committed to `docs/api/openapi.json`** and treated as the contract
      source of truth. Without it, "1:1 mirror" remains an unverifiable claim.
- [ ] **Agreement that the backend is authoritative** for all of S1–S25 and that the migration
      changes the frontend, not the backend.
- [ ] **A test runner decision** (Vitest is the lowest-friction fit for the existing Vite-less
      Next 16 + Vitest-compatible stack; no runner exists today).
- [ ] **Confirmation that `docs/api/` is the right home** for the committed spec, or a
      decision to use another established convention.
- [ ] **Backend owner acknowledgement of §18.1 (IVR capability seeding)** — either it is
      scheduled, or IVR is explicitly out of frontend scope. **IVR UI must not be planned
      until this is closed.**
- [ ] **Backend owner acknowledgement of B1 (nullable `admin` → 500)** so the frontend
      contract for reseller creation can be pinned.
- [ ] **Confirmation that untracked `V55__execution_snapshot_integration_config.sql` will be
      committed** before the snapshot contract (and F10) is relied upon.

---

## 32. F0 Final Findings

### Frontend

**Current architecture.** Next.js 16.2.10 App Router, React 19.2.4, TypeScript 5, Tailwind 4 +
shadcn/Radix. Domain-foldered with a single shared infrastructure tier. TanStack Query for all
server state (no Zustand in use), TanStack Table, React Hook Form + Zod 4, hand-written axios
client behind a same-origin Next rewrite. All authenticated pages sit in one `(platform)`
route group behind a **client-side render gate**. Zero tests, no test runner, empty CI.

**Main reusable infrastructure.**
`lib/api/client.ts` (401 single-flight refresh with retry guard, cookie-driven) ·
`lib/auth/token-store.ts` (in-memory-only access token, legacy purge) ·
`lib/session.ts` (query-cache teardown on logout) ·
`lib/api/error.ts` (`toApiError` → faithful ProblemDetail mapping) ·
`components/common/*` (17 shared items — pagination, skeleton, 5 filter toolbars, 10 status
badges) · the 9 `*-table.tsx` and 24 `components/ui/*` primitives · `use-url-list-state.ts`
(1-based URL ↔ 0-based API, verified correct) · the `zod`-schema + payload-mapper pattern.

**Major stale areas.**
`CampaignType` missing `MISSED_CALL` · `CampaignResponse` missing 4 fields ·
`RetryPolicyConfig` missing `rules` · `CampaignExecutionResponse` missing
`configurationSnapshotId` · phantom `ContactResponse.contactGroupId` ·
`ContactGroupResponse` missing `memberCount` · `TtsTemplateResponse`/`CreateTtsTemplateRequest`
missing `scope` · `DidResponse` missing `allocationSource` · clone and status-change handlers
are `TODO` stubs · `PASSWORD_MIN=8` vs the server's 12 · `retryPolicy.intervalSeconds` 604800
vs 5999.

**Major refactor areas.**
Auth (`unwrap` placement, misleading doc comment) · error handling (no boundary) · route
guarding (client-only) · capability catalog (19 vs 34 keys) and action gating (0 of 12
`_MANAGE`/`_APPROVE`/`_EXECUTE` checks) · campaign create/edit dialogs (untyped `typeConfig`) ·
TTS pages (scope-blind) · contacts pages (phantom field) · duplicated code
(2 merged duplicate interfaces, 2 identical import/export pairs, 3 re-declared enum unions,
2 duplicated transition tables) · dead dependencies (`zustand`; `next-themes` without a provider).

**Major rebuild areas.**
Audio **create/upload path** (the only usable product flow is missing) · TTS **scope-aware
list/detail/create** (the backend models two resources; the UI models one) · per-`campaignType`
`typeConfig` editors (no typed shape exists anywhere in the frontend) · **Queue** and
**Agent** surfaces (absent entirely, and Queues block `CONNECT_BY_AGENT`).

### Backend

**Relevant API surface.** 18 controllers. Auth 6 (+2 signup) · Campaigns 15 (+1 IVR-bridge) ·
Call attempts 6 (nested) · Contact groups + contacts 18 · Audio 8 (incl. multipart upload) ·
TTS 7 · DIDs 7 (incl. assign/revoke) · Tenants 6 (+signup) · Resellers 5 (+signup) ·
Users 3 · IVR 6 · Queues 12 · Agents 17. Uniform `ApiResponse<T>` envelope; RFC 9457 errors;
`/v3/api-docs` at runtime with **no committed spec**.

**Auth model.** Stateless JWT (`sub`, `email`, `sid`) + rotating opaque refresh token in an
HttpOnly, SameSite=Strict cookie scoped to `/api/v1/auth`. `permitAll` on login, refresh,
logout, signup, OpenAPI and health; `anyRequest().authenticated()`. No roles or tenant claims in
the token.

**RBAC model.** **No Spring method security** — `@EnableMethodSecurity` is enabled and unused;
`@PreAuthorize`/`@Secured`/`@RolesAllowed` appear **zero** times. A custom capability engine
(`AuthorizationService`) evaluates `role_capabilities` × scope
(`PLATFORM|RESELLER|TENANT|OWN|ASSIGNED`) × `AccessCheck`, fail-closed to a non-leaking 403,
with tenant isolation as the strongest boundary. 5 seeded roles; **34 capability keys** seeded
across V1/V16/V20/V37; **`IVR_VIEW`/`IVR_MANAGE` enforced but never seeded**.

**Tenant model.** Three-level `PLATFORM → RESELLER → TENANT` with a single
`OrganizationalHome` per user (enforced V6–V13). Tenant identity is derived per-request from
the JWT subject + DB membership into a `ThreadLocal` cleared in a `finally` block. **No tenant
header, no tenant from session state, no trusted tenant in reads**; `tenantId` is accepted only
on 4 explicitly-authorized create/list entry points. Foreign resources are reported
indistinguishably from nonexistent ones (403/404).

**Major domain contracts.**
Uniform page/size/sort + entity filters, `size` 1..100, `search` (except DID's `q`), and
`PaginationMetadata` present **only** on genuinely paginated lists. Immutable per-execution
`CampaignConfigurationSnapshot` (structurally enforced via `CampaignRuntimeConfigResolver`),
with referenced-resource *validity* deliberately never frozen. Typed-but-unvalidated-on-the-wire
`typeConfig` (unknown fields rejected 400) and **typed** `integrationConfig` (configuration
only, nothing delivered). Two distinct TTS scopes (`GLOBAL` platform-owned, auto-approved vs
`TENANT`, approval-gated). Audio is tenant-only with a real multipart upload path. 53
`CallFailureCode` values, no published display vocabulary.

### Contract

**Matching areas.** Envelope, pagination metadata, ProblemDetail and field-error mapping ·
the entire auth/token/refresh/logout flow · query-parameter names and values on all 11
implemented list endpoints (including DID's `q` and the 1-based-URL↔0-based-API conversion) ·
every existing frontend endpoint path and HTTP method · `UserResponse`, `TenantResponse`,
`ResellerResponse`, `CallAttemptResponse`, `AudioAssetResponse` field-for-field · all shared
enums except `CampaignType` and `TtsTemplateScope` · import/export multipart and blob handling
· sort-field allowlists.

**Mismatches.**
1. `markAttemptFailed` sends a JSON body; the backend reads `@RequestParam` — failure diagnostics are silently lost.
2. `getCallAttempts` sends `sort`/`status` the endpoint does not accept, and fabricates a `PaginationMetadata` that does not exist.
3. `ContactResponse.contactGroupId` is declared in TypeScript but absent from the backend DTO.
4. `campaignType` filter cannot express `MISSED_CALL`.
5. `retryPolicy.intervalSeconds` allows 604800 client-side vs 5999 server-side.
6. Admin password minimum is 8 client-side vs 12 server-side.
7. `TtsTemplateScope` is entirely unmodelled, so GLOBAL templates are unreachable from the UI.
8. `allowedDaysOfWeek` is `string[]` client-side vs `Set<DayOfWeek>` server-side.

**Unsupported frontend assumptions.**
None found. The frontend invents **no** endpoint, DTO, role or permission. The only invented
concept is the unused heuristic `hasPlatformAccess()` (U2). All gaps are omissions —
under-modelled DTOs, unmodelled enums, unexercised endpoints, absent domains — rather than
fabrications.

**Unknowns.** U1–U12 in §18.3, most importantly: the capability→action mapping (U1), the
reseller tenant-selection model (U3), `MISSED_CALL` frontend scope (U6), the dashboard
question (U4), and the roster-vs-child-CRUD UX (U8).

### Migration

**First implementation target.** **F1 — foundation / contract alignment.** Fix the 2 wrong
request shapes, add the 7 missing DTO fields and 1 missing enum, remove the 1 phantom field,
commit the OpenAPI spec, add an error boundary and a real route guard, and clear the 2 dead
dependencies. This is low-risk, high-leverage, and every later domain depends on it.

**Dependencies.** F2 (RBAC) must precede F5/F6/F7 so the rebuilt surfaces do not inherit the
ungated-action defect. F5 (audio upload) and F6 (TTS scope) must precede F7 (campaign content
configuration). F8 (Queues) must precede F9 (`CONNECT_BY_AGENT`). F1's
`configurationSnapshotId` addition must precede F10 (execution visibility).

**Blocking issues.**
- **§18.1 IVR capability seeding** — a backend defect that makes the entire IVR API 403 for
  every role. **Blocks all IVR frontend work.**
- **U1** — the capability→action mapping is undefined, so RBAC work cannot be specified.
- **U3** — the reseller tenant-selection model is undefined, so F3 and every tenant-scoped
  screen are blocked.
- **B10** — the untracked `V55` migration means the snapshot contract is not yet reproducible
  from a clean checkout.
- **No test runner and no committed OpenAPI spec** — there is currently no automated
  tripwire for contract drift.

**Recommended sequence.**
F1 Foundation → F2 RBAC action gating → F3 Tenant/context model → F4 Contacts & Groups →
F5 Audio upload → F6 TTS scope split → F7 Campaign foundation → F8 Queues → F9 CONNECT_BY_AGENT
+ snapshot display → F10 Executions & attempts → F11 Agents (if in scope).
**IVR, Scheduler and Dashboard are blocked** — IVR on §18.1, Scheduler because
**NOT EXPOSED BY BACKEND**, Dashboard on U4.

---

## Supporting artifacts

Concise companion files, all derived from this document:

- `docs/frontend/audit/repository-map.md`
- `docs/frontend/audit/domain-map.md`
- `docs/frontend/audit/api-inventory.md`
- `docs/frontend/audit/contract-matrix.md`
- `docs/frontend/audit/staleness-matrix.md`
- `docs/frontend/audit/rbac-matrix.md`
- `docs/frontend/audit/tenant-boundary-map.md`
- `docs/frontend/audit/migration-plan.md`
