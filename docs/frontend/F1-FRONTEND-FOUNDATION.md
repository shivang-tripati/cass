# F1 — Frontend Foundation & Contract Alignment

**Status:** PASS WITH BLOCKERS
**Date:** 2026-09-29
**Repository:** `D:\work\agile\obd-platform` (monorepo, branch `main`)
**Previous phase:** F0 — Frontend Architecture & Backend Contract Audit
**Next phase:** F2 — Contacts & Contact Groups

> F1 changed **frontend code only**. No backend file, migration, DTO, enum or
> endpoint was modified. One pre-existing backend defect was found, re-verified,
> and is reported in §8 as a blocker — not worked around.

---

## 1. Executive Summary

F1 corrected the contract drift F0 catalogued, established a single authorization
mechanism, made the operating context explicit, and added the project's first test
runner. The guiding decision throughout was that **the loose `Record<string, unknown>`
that F0 flagged was not merely imprecise — it was unsafe**, because every backend
campaign-config parser calls `rejectUnknownFields` and returns 400 for an
unrecognised key. A form built on a permissive record could therefore only ever
produce failures. That insight is what turned `typeConfig` from a record into five
typed, strictly-validated shapes.

Three findings went beyond F0:

- **A silent data-loss bug in the campaign save path.** `toCreateCampaignPayload`
  and `toUpdateCampaignPayload` both built a `retryPolicy` object with only
  `maxAttempts`, `intervalSeconds` and `strategy`. Because the DTO has no `rules`
  field to carry them, the backend's per-category retry rules could never be sent
  or returned. F0 reported the field as "missing from the type"; it was in fact
  missing from the *payload mapper* too, so editing a campaign silently reverted
  its retry policy to the flat defaults.
- **A pre-existing field-mapping bug in the signup forms.** `toSignupFormFieldKey`
  concatenated `"admin" + field.slice(6)`, producing `adminemail` rather than
  `adminEmail`. The caller guarded with `if (key in schema.shape)`, so the
  mismatch was swallowed and an invalid administrator email fell back to the
  summary alert instead of appearing under the field the user was looking at.
- **A second phantom field beyond contacts.** `ContactResponse.contactGroupId` was
  documented in F0 as always-`undefined`. Fixing it exposed that it was *used*: it
  built the per-contact "View" link, so every such link resolved to
  `/contact-groups/undefined/contacts/…`. F1 fixed the type and threaded the real
  group id from the route.

**Validation:** `typecheck` clean, `lint` 0 errors / 5 pre-existing warnings, `test`
83 passing across 7 files, `build` succeeding.

---

## 2. Verified Backend Contracts

Every F1 change was checked against backend source before it was written. The
contracts below are the ones F1 actually relied on.

### 2.1 Envelope and errors

| Contract | Source | Consequence for F1 |
|---|---|---|
| `ApiResponse<T>(success, data, message, pagination, meta)`, `@JsonInclude(NON_NULL)` | `common/api/response/ApiResponse.java:5-6` | Absent blocks are **omitted**, not null. `unwrapPage` must let a caller observe a missing `pagination` rather than substitute one. |
| `ResponseFactory.ok(list)` leaves `pagination` unset | `common/api/response/ResponseFactory.java` | Executions, attempts, queue members, agent endpoints and IVR trees genuinely send no pagination. |
| `ProblemDetail` + `code`, `requestId`, `timestamp`, `errors[]` | `common/exception/GlobalExceptionHandler.java:113-136` | Error predicates are pinned to real statuses only. |
| Statuses 400/401/403/404/405/409/422/429/500/503 | `common/api/error/CommonErrorCode.java:7-16` | 405 carries `code: "BAD_REQUEST"` — tested. |
| `errors[].field` is the DTO property name; nested DTOs yield dotted paths | `GlobalExceptionHandler:40-42`, `:160-166` | Signup mapping must flatten `admin.email` → `adminEmail`. |

### 2.2 Authentication (unchanged by F1 — verified correct)

| Contract | Source |
|---|---|
| Access token in the JSON body; refresh token **only** as HttpOnly `SameSite=Strict` cookie `obd_rt`, path `/api/v1/auth` | `security/AuthController.java:66-99`, `security/AuthCookieWriter.java:20-21,33-39` |
| `/auth/logout` is `permitAll` so an expired token can still end the cookie session | `security/config/SecurityConfig.java:52-53` |
| `AuthenticatedUserResponse(id, email, status, homeType, organizationId, capabilities)` | `security/AuthenticatedUserResponse.java:12-19` |

F1 **preserved** the in-memory access token, the cookie-driven refresh, and the
single-flight retry. No token was moved to `localStorage`.

### 2.3 Authorization

| Contract | Source | Consequence |
|---|---|---|
| No `@PreAuthorize`/`@Secured`/`@RolesAllowed`; `@EnableMethodSecurity` enabled but unused | whole `src/main` | Frontend gating is UX only. |
| **34 seeded capabilities** across V1 (27), V16 (2), V20 (3), V37 (2) | `db/migration/*.sql` | The frontend catalogue is now exactly 34 keys. |
| 5 roles: `SUPER_ADMIN`, `RESELLER_ADMIN`, `TENANT_ADMIN`, `AGENT`, `REPORT_VIEWER` | `V1:155-160` | No role is invented. |
| `getAllCapabilitiesForUser` returns an **unscoped union** | `authz/AuthorizationService.java:174-176` | Capability presence **cannot** infer scope. Tested. |
| `RESELLER_ADMIN` has `AUDIO_VIEW` + `AUDIO_APPROVE` but **not** `AUDIO_MANAGE` | `V1:237-250` + `V20:75` | A reseller legitimately sees Approve/Reject and must not see Register/Edit/Delete. Now enforced in the UI. |
| `IVR_VIEW`/`IVR_MANAGE` enforced, never seeded | `ivr/IvrTreeService.java:77,80`; no migration match | **Blocker** — see §8. |

### 2.4 Tenant / operating context

| Contract | Source | Consequence |
|---|---|---|
| `OrganizationalHomeType` = `TENANT` \| `RESELLER` only; `homeType === null` means platform | `authz/home/OrganizationalHomeType.java:3-5`; `V13:4-5` | `null` is the only platform representation. |
| A user has at most one home | `V6`/`V7`/`V8`/`V11` | A tenant user is **not** a member of several tenants. |
| Reseller list endpoints return a **hierarchy-wide read**, not impersonation | `tenant/TenantService.java:108-109`; `authz/AuthorizationService.java:150-166` | **No tenant selector was built.** See §6. |
| Tenant context is derived server-side per request into a `ThreadLocal` cleared in `finally` | `authz/context/OrganizationContextHolder.java`, `security/config/OrganizationContextPopulationFilter.java` | The client never sends a tenant header or trusts a tenant in a URL. |

### 2.5 Campaign

| Contract | Source | Consequence |
|---|---|---|
| `CampaignType` has **four** constants incl. `MISSED_CALL` | `campaign/CampaignType.java:11-33` | Union extended; badge added. |
| `playsMedia()` is an exhaustive switch; `true` for PLAYFILE/DTMF | `CampaignType.java:68-75` | Replaces the F0 type-membership list that had omitted `MISSED_CALL`. |
| `CampaignResponse` has 24 components; the frontend had 20 | `campaign/dto/CampaignResponse.java:13-102` | 4 fields added. |
| `CreateCampaignRequest`/`UpdateCampaignRequest` also accept those 4 | `dto/CreateCampaignRequest.java:117-161` | Now editable. |
| `RetryPolicyConfig` has a 4th component `rules: List<RetryRuleConfig>` | `dto/RetryPolicyConfig.java` | Modelled — and the mapper now sends it. |
| `intervalSeconds` is `@Min(1) @Max(5999)` | `dto/RetryPolicyConfig.java` | F0's 604800 bound was wrong. |
| `RetryRuleConfig` = `category`, `enabled`, `maxRetries`, `retryDelay` (`MM:SS`, minutes 00-99, seconds 00-59) | `dto/RetryRuleConfig.java`; `campaign/RetryDelay.java` | New schema. |
| `CampaignExecutionResponse` includes `configurationSnapshotId` | `dto/CampaignExecutionResponse.java` | Added — the user-visible proof of the frozen snapshot. |
| `CampaignTypeConfig` is a **sealed interface** permitting 5 records; every parser calls `rejectUnknownFields` | `campaign/config/CampaignTypeConfig.java` | Typed union + `.strict()`. |
| `PLAYFILE` rejects a non-empty `typeConfig` | `config/PlayfileCampaignConfig.java` | `.strict()` empty-object schema. |
| `MISSED_CALL.ringDurationSeconds` ∈ 10..60 | `config/MissedCallRingWindow.java:5-7` | Bounds. |
| `CONNECT_BY_AGENT.ringDurationSeconds` ∈ 10..240, only `LEAST_ACTIVE_RESERVATIONS` | `config/AgentRingWindow.java:5-7`, `config/AgentSelectionStrategy.java` | Bounds + literal. |
| `DTMF`: `expected` required (≤16), `maxDigits` defaults to `expected.length()`, `timeoutSecs` 1..120 default 10, `action` ∈ {TERMINATE, CONNECT_BY_AGENT} | `voice/dtmf/DtmfConfig.java`, `voice/dtmf/DtmfActions.java` | `normalizeTypeConfig` applies the server defaults. |
| `CampaignIntegrationConfig` is a **typed** record: `webhook{enabled,endpoint,events}` + `reportPrivacy{policy}`; unknown fields rejected; no secrets | `config/CampaignIntegrationConfig.java`, `config/WebhookConfig.java`, `config/ReportPrivacyConfig.java` | Typed; endpoint must be absolute http(s) with no embedded credentials. |
| Webhook `enabled` is the **only** switch; when enabled, endpoint + ≥1 event required | `config/WebhookConfig.java:20-40` | Cross-field refinement. |
| `CampaignConfigurationSnapshot` is frozen per execution; resource *validity* is never frozen | `campaign/CampaignConfigurationSnapshot.java:37-56` | Documented on the type; F10 renders it. |

### 2.6 Attempts, audio, TTS, contacts, DIDs

| Contract | Source | Consequence |
|---|---|---|
| `markFailed` takes `failureCode`/`failureReason` as **`@RequestParam`**, with no `@RequestBody` | `campaign/CampaignController.java:377-380` (re-verified at end of F1) | F0 sent a JSON body, so diagnostics were **silently dropped**. Fixed. |
| `listAttempts` declares **no** request parameters | `campaign/CampaignController.java:314` (re-verified) | Removed the dead `sort`/`status` params and the fabricated `PaginationMetadata`. |
| `POST /audio-assets/upload` is multipart with parts `name`, `description?`, `file`; all technical fields are server-derived | `audio/AudioAssetController.java:86-100` | `uploadAudioAsset` added; storage/checksum are never client-authored. |
| `TtsTemplateScope` = `GLOBAL` \| `TENANT`; `GLOBAL` has `tenantId == null` and forbids one; `GLOBAL` is created APPROVED, `TENANT` PENDING_APPROVAL; management is `platformWide` vs `forTenant` | `tts/TtsTemplateService.java:73-113,177,196,223,239-243` | `scope` modelled on both request and response; list shows the scope. |
| TTS list has **no** `scope` filter | `tts/TtsTemplateController.java:83-87` | Not requested. The partition helper is explicitly a view split. |
| `TtsTemplateVariable.type` ∈ {STRING, NUMBER, BOOLEAN, DATE} | `tts/TtsTemplateValidation.java:29` | Union instead of `string`. |
| `ContactResponse` has 9 components, **no** `contactGroupId` | `contact/dto/ContactResponse.java` | Phantom field removed; group id threaded from the route. |
| `ContactGroupResponse` includes `memberCount: long` | `contact/dto/ContactGroupResponse.java` | Added. |
| `DidResponse` includes `allocationSource` | `did/dto/DidResponse.java` | Added, with the `PLATFORM`\|`RESELLER` union. |
| `POST /dids/{id}/assign` and `/revoke`; a tenant caller is **400**, not 403 | `did/DidService.java:230-232,259-261` | Service added with that rule documented. |
| `TenantAdminInput` / `AdminAccountInput` / `CreateAgentRequest` require `@Size(min = 12, max = 128)` | `tenant/dto/`, `reseller/dto/` | New `ADMIN_PASSWORD_MIN = 12`; F0 reused 8 everywhere. |
| `schedule.allowedDaysOfWeek` is `Set<DayOfWeek>` | `dto/ScheduleConfig.java` | Narrowed from `string[]`. |

### 2.7 Post-verification re-check of a concurrently-changing backend

`git status` at the end of F1 showed **37** backend-modified files, versus **21** at
F0's baseline, and timestamps inside the F1 window on 19 backend campaign files —
including `CampaignController.java` and `CampaignOpenApiContractTest.java`, which are
two of the sources this phase verified against. That is concurrent backend work by
another author in the same working tree, not an F1 edit (§12 confirms F1 wrote no
backend file).

Because the contract could have moved underneath the verification, every contract F1
depends on was **re-read at the end of F1**:

| Contract | Status |
|---|---|
| `CampaignType` constants | Unchanged — still 4, `MISSED_CALL` at L33. |
| `CampaignResponse` components | Unchanged — still 24, same four safety fields. |
| `markFailed` signature | Unchanged — still `@RequestParam`, no `@RequestBody`. |
| All 19 `CampaignController` mappings | Unchanged — same paths, methods, and `permitAll` set. |
| `IVR_VIEW`/`IVR_MANAGE` seeding | **Still absent from all migrations** — the blocker stands. |

One line reference moved: the attempt mappings shifted by ~5 lines
(`createAttempt` 271 → 276, `markFailed` 372 → 377) because the concurrent work added
OpenAPI metadata. The frontend change is unaffected — it was correct against the
signature, not the line number — and the references above are updated to the
post-verification numbers. No F1 type or service now cites a line that has since moved.

**Consequence for F2:** the backend is being actively developed in this tree. A
contract assertion in a test suite is more durable than a line citation in a comment,
which is part of why §9 favours behavioural tests.

---

## 3. API Client Changes

### 3.1 Envelope handling extracted — `lib/api/transport.ts` (new)

`unwrap` previously lived inside `auth.ts`, which is neither its home nor a
coherent dependency direction. Four helpers now live in one module, and **only that
module knows the envelope exists**:

| Helper | Purpose |
|---|---|
| `unwrap<T>` | Extract `data`. Propagates a rejection **unchanged** so the axios interceptor still sees it. |
| `unwrapPage<T>` | Extract `data` + `pagination`; returns `undefined` pagination when the server sent none. |
| `unwrapBlob` | Binary download, bypassing the envelope. |
| `sendVoid` | Bare 204 with no body. |

Two of these fix real defects:

- `unwrap`'s doc comment claimed it "throws ApiError on transport failures". It
  never did — it had no `try`/`catch` and never imported `toApiError`. The comment
  now matches the behaviour.
- Four DELETE functions were `async` with a bare `await api.delete(...)`. That was
  correct, but inconsistent with everything else and easy to mistake for a missing
  unwrap. They now use `sendVoid` and are not `async`.

### 3.2 Authentication — behaviour preserved, nothing weakened

Unchanged by design: in-memory access token, HttpOnly cookie refresh, single-flight
refresh with a per-request retry guard, `queryClient.clear()` on logout. F1 added no
token storage, no logging, and no client-side credential handling.

`client.ts` was **not** modified.

### 3.3 Error normalization — `lib/api/error.ts`

| Change | Reason |
|---|---|
| `NETWORK_ERROR_CODE` is now actually assigned | It existed and was never set, so a network failure and an opaque 5xx were indistinguishable (`error.ts:4,59`). |
| `TRANSPORT_FAILURE_STATUS = 0` named | The magic `0` was used in two places. |
| Added `isUnauthorized`, `isForbidden`, `isNotFound`, `isConflict`, `isBadRequest`, `isValidationError`, `isRateLimited`, `isServerError`, `isTransportFailure`, `isRetryable` | §9 requires the UI to distinguish these; each maps to a status the backend emits. |
| `isRetryable` excludes non-transient 4xx | Retrying a 403 or 409 fails identically. |

No `any` was introduced. `ApiError` remains the single normalised failure type.

### 3.4 Component-level API duplication

**None found.** A grep for `from "axios"`, `fetch(` and `api.<verb>(` across
`src/components` and `src/app` returned only `refetch()` callbacks. The layering
UI → query hook → service → client was already intact, so F1 left it alone.

---

## 4. Type / DTO Changes

### 4.1 `lib/api/contracts.ts` — corrections

| Type | Change |
|---|---|
| `CampaignType` | **Added `MISSED_CALL`** (3 → 4). |
| `CampaignResponse` | **Added** `callOnWhitelistNumbers`, `dailyDialLimit`, `maxDailyAttempts`, `maxCallDurationSeconds` (20 → 24). |
| `CreateCampaignPayload`, `UpdateCampaignPayload` | Added the same 4 fields. |
| `CampaignExecutionResponse` | **Added `configurationSnapshotId`.** |
| `ContactResponse` | **Removed the phantom `contactGroupId`.** |
| `ContactGroupResponse` | **Added `memberCount`.** |
| `TtsTemplateResponse` | **Added `scope`**; `tenantId` correctly typed `string \| null`. |
| `CreateTtsTemplatePayload` | **Added `scope`** — this is what made GLOBAL templates unreachable. |
| `DidResponse` | **Added `allocationSource`.** |
| `ScheduleConfig.allowedDaysOfWeek` | `string[]` → `DayOfWeek[]`. |
| `RetryPolicyConfig` | **Added `rules`.** |
| `MarkAttemptFailedPayload` | **Renamed to `MarkAttemptFailedParams`** — it is a query string, not a body. |
| `AudioAssetResponse`, `CreateAudioAssetPayload`, `UpdateAudioAssetPayload` | Unchanged; they were already correct. |

### 4.2 New types

`DayOfWeek` · `RetryRuleCategory` + `CONFIGURABLE_RETRY_CATEGORIES` ·
`RetryRuleConfig` · `CampaignTypeConfig` (5-member union) · `ConnectByAgentTypeConfig` ·
`MissedCallTypeConfig` · `DtmfTypeConfig` + `DtmfAction` · `IvrTypeConfig` ·
`PlayfileTypeConfig` · `AgentSelectionStrategy` · `WebhookEvent` · `WebhookConfig` ·
`ReportPrivacyPolicy` · `ReportPrivacyConfig` · `CampaignIntegrationConfig` ·
`TtsTemplateScope` + `TtsTemplateVariableType` · `AllocationSource` ·
`AssignDidPayload` + `AssignDidResponse` · `AudioAssetUploadForm` ·
`ContactGroupMemberResponse` + `AddMemberPayload` + `BatchMemberPayload` +
`BatchMemberResponse` + `BatchMemberResult` + `MemberBatchStatus`.

### 4.3 Campaign configuration — from record to typed union

`lib/schemas/campaign-config.ts` (new) mirrors each backend config record. The
discriminator is the **outer key** (`missedCall`, `connectByAgent`, `dtmf`, `ivr`),
because the wire format has no tag field and `PLAYFILE` has no key at all — so a
`z.discriminatedUnion` on a literal is not the right tool, and the module says why.

`CampaignType.playsMedia()` is mirrored as `campaignTypePlaysMedia()` and now drives
the content rule. The F0 schema reimplemented this as a per-type list that had
omitted `MISSED_CALL`; the backend comment at `CampaignType.java:41-48` describes that
exact pattern as fail-open and explains a real failure it had already caused.

`.strict()` on the inner objects is **required**, not stylistic: Zod strips unknown
keys by default, which would silently drop a field the user typed rather than
telling them the backend would reject it. The DTMF variant is deliberately **not**
strict, because `DtmfConfig.fromTypeConfig` tolerates absent optional fields.

### 4.4 Deduplication (F0 findings A1–A5)

- `ContactImportResponse` / `ContactImportError` were each declared twice in
  `contracts.ts`. One declaration each now.
- `AudioAssetStatus` / `TtsTemplateStatus` / `DidStatus` / `AllocationState` /
  `NumberType` / `DidCapability` were re-declared locally in three service modules,
  shadowing the canonical types. Those re-declarations are gone; the services import
  from `contracts.ts`.
- The call-attempt transition tables were duplicated in `api/call-attempts.ts` and
  `schemas/call-attempt-mutation.ts`. The schema module now re-exports the single
  copy, so both existing import paths still work.
- `CALL_ATTEMPT_SORTABLE_FIELDS` claimed a server sort whitelist the endpoint does
  not have. Removed, and replaced with `CALL_ATTEMPT_CLIENT_SORT_FIELDS` in the
  table — accurately named as a presentation concern.

---

## 5. Authorization Changes

### 5.1 One mechanism

`lib/auth/use-can.tsx` (new) is now the only way a component asks what a user may do:

```tsx
<Can any={[Capability.AUDIO_APPROVE]}>…</Can>   // declarative
const { can, canAll } = useCan();              // imperative
can(user, Capability.CONTACT_MANAGE)           // pure, testable
```

`lib/auth/index.ts` re-exports `capabilities` + `use-can`; `operating-context` is
deliberately excluded so the capability layer stays pure and free of the session query.

### 5.2 Capability catalogue: 19 → 34, nothing invented

All seeded keys are now present, grouped by the migration that seeds them. A test
asserts the count is exactly 34 and that `IVR_VIEW`/`IVR_MANAGE` are **absent** —
listing them would offer an action the backend can never authorise.

Also asserted absent: `CONTACT_DELETE`, `CAMPAIGN_APPROVE`, `AUDIO_EXPORT` and
similar inventions.

**Removed:** `hasPlatformAccess()`, `hasResellerAccess()`, `hasTenantAccess()`.
`hasPlatformAccess` was `homeType === null && hasCapability(TENANT_VIEW)` — a
frontend invention conflating two unrelated facts, with zero callers. Scope now
comes from one place.

### 5.3 Action-level gating (the F0 defect)

F0 found 12 declared capabilities were never checked anywhere. F1 wired the
consequential ones, using the backend's actual split:

| Surface | Action | Capability | Backend |
|---|---|---|---|
| Audio | Register / Edit / Delete | `AUDIO_MANAGE` | `AudioAssetService:69,124,137` |
| Audio | Approve / Reject | `AUDIO_APPROVE` | `AudioAssetService:268` |
| TTS | Create / Edit / Delete | `TTS_MANAGE` | `TtsTemplateService:177,196` |
| TTS | Approve / Reject | `TTS_APPROVE` | `TtsTemplateService:223` |
| Campaign | Create / Edit / Delete / Clone | `CAMPAIGN_MANAGE` | `CampaignController:59,119,136,178` |
| Campaign | Change status / Execute | `CAMPAIGN_EXECUTE` | `CampaignController:157,215` |

The audio case is the concrete win: a `RESELLER_ADMIN` holds `AUDIO_APPROVE` but
**not** `AUDIO_MANAGE`. F0 rendered Register/Edit/Delete unconditionally, so a reseller
saw controls that could only 403.

The campaign case is the second: `CAMPAIGN_MANAGE` and `CAMPAIGN_EXECUTE` are separate
capabilities, so Schedule/Pause/Resume/Archive is now gated on `EXECUTE` alone. A user
holding only `MANAGE` is no longer offered a lifecycle transition.

**Deliberately not gated:** View, Copy ID, and read-only fields. §14 says not to hide
information merely because the user cannot mutate it, and that is the right call here —
a viewer with `CAMPAIGN_VIEW` should still see what exists.

### 5.4 Route protection

F1 added `error.tsx`, `loading.tsx` and `not-found.tsx` to both route groups, and
corrected the `(platform)` gate:

| Fix | Reason |
|---|---|
| `?next=` now preserved on the layout redirect | F0's layout guard dropped the deep link while `client.ts` preserved it, so a session expiry lost the user's place. |
| A non-401 `/me` failure renders a retry state instead of redirecting | F0 redirected to sign-in on **any** error, so a 5xx or an offline moment threw a still-signed-in user out of a valid session. |
| `endLocalSession()` is called before redirecting | Local credential and cache state is now torn down on this path too, not only in the logout mutations. |

**No `middleware.ts` was added, deliberately.** A server-side guard cannot validate
this session: the access token exists only in client memory, and the refresh cookie is
HttpOnly **and** path-scoped to `/api/v1/auth`, so it is not attached to page requests
at all. Middleware would have nothing to read, and adding a second non-HttpOnly
"logged in" cookie purely to gate navigation would create a new credential to leak.
Protected content is still never rendered before the session resolves — the skeleton is
returned for `isPending` and `isError` before `children` is reached.

### 5.5 403 vs 401

`components/common/query-state.tsx` (new) renders 403 as **"You don't have access to
this"** with "Ask an administrator for access" and **never** a sign-in redirect,
because the user *is* signed in. It also distinguishes 404 ("does not exist, or it is
outside your organization" — the backend reports a foreign resource exactly like a
missing one), 409, 429, 5xx and transport failure, and surfaces `requestId` for 5xx
only. This replaced nine hand-copied blocks that had drifted: some showed
`requestId` for 403, some said "No permission", others spelled it out.

---

## 6. Tenant Context

### 6.1 What was built

`lib/auth/operating-context.ts` (new) derives one `OperatingContext` from `/me`:

```
scope      PLATFORM | RESELLER | TENANT   (from homeType; null ⇒ PLATFORM)
tenantId   set only for TENANT scope
resellerId set only for RESELLER scope
label      "Platform" | "Reseller" | "Tenant"
scopeKey   "<scope>:<organizationId|none>"  — for query keys
```

`deriveOperatingContext` is pure and unit-tested. There is **no** store, **no**
provider, and **no** second fetch: `useSession` remains the single session source.

### 6.2 Why there is no tenant selector

The F1 brief asks for a reseller operating context with tenant selection, and
stopping short of it needs to be justified rather than assumed.

**VERIFIED: the backend does not support tenant impersonation.** A reseller-scoped
caller gets a **hierarchy-wide read** — `TenantService.list:108-109` restricts to
`hasReseller(context.resellerId())`, and the reseller/tenant boundary is resolved in
`AuthorizationService.coversReseller:150-166` via `TenantHierarchyResolver`. There is
**no** request parameter, header or token claim that narrows a request to one of
those tenants. `GET /dids?tenantId=` is honoured only for platform callers.

A selector in this UI would therefore be a client-side filter **pretending to be a
scope change** — and the brief is explicit (§17) that the selected tenant must not be
treated as a security boundary. Building it would have created exactly the false
assurance F0 warned against.

What F1 did instead: made the scope explicit, exposed `scopeKey` so F2+ has one obvious
place to extend, and documented the absence in the module itself.

### 6.3 Cache invalidation

Cache isolation between identities is **already correct** and was left alone:
`endLocalSession()` calls `queryClient.clear()` on both logout success and failure,
and the hard navigation in `redirectToSignIn()` discards the in-memory cache. The
operating context is fixed for a session precisely because switching is unsupported.

F1 considered threading `scopeKey` through all 50+ query-key call sites and **declined**:
it is churn with no correctness gain and a real risk of breaking invalidation. That
decision is recorded in the module's doc comment so it is not re-litigated blind.

### 6.4 Tenant creation context

`toCreateTenantPayload` still hardcodes `resellerId: null`; **F1 did not change it
automatically**, per §19. The verified contract:

- `CreateTenantRequest.resellerId` has no validation annotation.
- A reseller-context caller is checked with `forReseller(context.resellerId())`, and a
  body `resellerId` that deviates from their own reseller is **403**
  (`TenantProvisioningService:100-102`).
- A platform caller supplies it in the body.
- `resellerId` is **absent** from `UpdateTenantRequest`.

So `null` is *correct* for the platform caller and *silently wrong* for a reseller one.
This needs the reseller context model, which is F2+/a product decision. Recorded in §11.

---

## 7. Domain Contract Corrections

Only changes actually made are listed.

**Contacts** — removed the phantom `ContactResponse.contactGroupId`; threaded the real
group id from the route into `ContactTable` (F0's declaration bug was producing
`/contact-groups/undefined/contacts/…` links); added `memberCount`; added the six
roster DTOs the unused `/members` endpoints return.

**Audio** — added `uploadAudioAsset` (multipart `name`/`description`/`file`) and
`AudioAssetUploadForm`, with an explicit note that `fileName`, `contentType`,
`fileSize`, `durationSeconds`, `checksum` and `storageReference` are **server-derived**
and must never be client-authored. `Content-Type` is deliberately not set on the
request so the browser appends the multipart boundary. **No Audio UI was built** — the
existing metadata form is left in place, and F5 replaces it.

**TTS** — `scope` modelled on both request and response; `tenantId` typed nullable;
`createTtsTemplateSchema` now sends `scope: "TENANT"` explicitly rather than relying
on the server default; the list shows a Platform/Tenant column; `canManageTtsTemplate`
and `partitionTtsTemplatesByScope` added (the latter documented as a **view** split,
since the endpoint has no `scope` filter); `TtsTemplateVariable.type` narrowed to the
four allowed values.

**Campaigns** — the full type foundation in §4.3, plus:
`RetryPolicyConfig.rules` **now flows through the payload mappers** (the data-loss
fix); `allowedDaysOfWeek` narrowed; the create/update schemas gained the four safety
fields with the backend's own bounds; the content rule re-derived from `playsMedia()`;
`config` shape now checked against `campaignType`; `MISSED_CALL` badge **and** type
filter option added.

Two dead controls were also **wired up**, because F0 shipped the API functions and the
`change-status-dialog.tsx` component but left both list-view handlers as
`window.location.reload()` stubs:

- `clone-campaign-dialog.tsx` calls the existing `cloneCampaign`. `POST /clone` takes
  **no body** — it copies the source configuration verbatim — so the dialog confirms
  rather than collects, and navigates to the new campaign. On the detail page, Clone
  previously opened the **status** dialog; that is corrected.
- `ChangeStatusDialog` is now reachable from the list view.

No campaign **form** was redesigned and no `typeConfig` editor was built — F4 owns those.

**DIDs** — `allocationSource` added; `assignDid`/`revokeDid` services added with the
tenant-is-400-not-403 rule documented. **No assign/revoke UI** (F3+).

**Call attempts** — `markAttemptFailed` now sends query parameters, so diagnostics
persist; the list no longer sends dead params or fabricates pagination;
`CallAttemptListResult` is honestly a bare array.

---

## 8. Backend Findings

### Backend blocker — IVR is unreachable for every role

**Re-verified during F1, still present. Not worked around, not "fixed" in the
frontend, and no IVR permission was invented.**

- `IvrTreeService.java:77,80` declares `CAP_VIEW = "IVR_VIEW"`, `CAP_MANAGE = "IVR_MANAGE"`,
  enforced at lines 111, 137, 146, 179, 208, 247.
- `AuthorizationService.findCapabilityId:127-131` resolves the key against the
  `capabilities` table; **a missing row yields `null`, and `hasCapability` returns
  false at L65-66.**
- A grep for `IVR_VIEW|IVR_MANAGE` across **all 55 migrations returns no matches.**
  `V53__ivr_trees.sql` contains no capability insert.

**Impact:** all 6 `/api/v1/ivr-trees` endpoints and `POST /campaigns/{id}/ivr-tree`
return 403 for every role, including `SUPER_ADMIN`. Additionally IVR is **tenant-only**
(`IvrTreeService.java:511-517` has no reseller or platform branch), so even after a fix
it stays unreachable for platform and reseller users.

**Frontend impact:** none yet — there is no IVR surface, and F1 deliberately did not
add `IVR_VIEW`/`IVR_MANAGE` to the catalogue or add a fake capability. Adding them
would make the UI offer an action that always 403s.

**Recommended follow-up (backend owner):** a migration inserting both keys and granting
them to `SUPER_ADMIN`/`RESELLER_ADMIN`/`TENANT_ADMIN`, mirroring `V20` (TTS) and `V37`
(QUEUE).

### Frontend issues found and fixed

| # | Issue | Evidence |
|---|---|---|
| F1-1 | `retryPolicy.rules` absent from both campaign payload mappers — a configured per-category retry policy was replaced by flat defaults on every save. | `campaign-mutation.ts` mappers |
| F1-2 | `toSignupFormFieldKey` produced `adminemail`, not `adminEmail`; the `key in shape` guard swallowed it, so admin-email errors never reached their field. | `signup-error-mapping.ts:6-8` |
| F1-3 | `ContactResponse.contactGroupId` was used to build per-contact links, producing `/contact-groups/undefined/contacts/…`. | `contact-table.tsx:145` |
| F1-4 | `CampaignTypeBadge` had no `MISSED_CALL` entry, so such a campaign rendered a blank badge. | `campaign-type-badge.tsx` |
| F1-5 | `NETWORK_ERROR_CODE` exported but never assigned. | `error.ts:4,59` |
| F1-6 | `unwrap`'s doc comment claimed it threw `ApiError`; it did not. | `auth.ts:10` |
| F1-7 | Nine duplicated error blocks with divergent 403 wording. | 9 list views |
| F1-8 | `updateTtsTemplateSchema` lacked the duplicate-name and stray-brace refinements that create had. | `tts-template-mutation.ts:33` |
| F1-9 | `PASSWORD_MIN = 8` reused for admin provisioning, which requires 12. | `signup.ts`, `tenant-mutation.ts`, `reseller-mutation.ts` |
| F1-10 | `retryPolicy.intervalSeconds` allowed 604800 vs the backend's 5999. | `campaign-mutation.ts:81` |

### Backend issues found — documented, not fixed

| # | Finding | Impact | Recommended follow-up |
|---|---|---|---|
| B1 | `CreateResellerRequest.admin` is `@Valid` **without** `@NotNull` (F0 finding) | `ResellerProvisioningService:74` dereferences `request.admin().password()` → NPE/500 | Add `@NotNull`. F1's frontend keeps `admin` required in practice. |
| B2 | `IvrTreeService` list accepts `page`/`size` but returns no `PaginationMetadata` (F0 finding) | A paginated IVR UI would show false page controls | Add pagination or drop the params. |
| B3 | `POST /tenants/{tenantId}/agents` returns a **`TenantResponse`**, not a user/agent (F0 finding) | A frontend action has no sensible result to render | Return the created agent, or rename. |
| B4 | `CreateAgentEndpointRequest.agentId` is `@NotNull` but ignored by the service (F0 finding) | Required-but-unused field | Drop it from the DTO. |
| B5 | `tenant/dto/CreateAgentRequest` and `voice/agent/dto/CreateAgentRequest` are different records with the same name | Naming collision | Rename one. |
| B6 | 405 responses carry `code: "BAD_REQUEST"` | No dedicated 405 branch | Tested as-is; frontend handles it via status. |
| B7 | 405 handler, `NoResourceFoundException` and `ConstraintViolationException` all render `error` **outside** a try/catch | A throw while serialising a ProblemDetail could escape as a raw 500 | Wrap. Pre-existing; not F1's to fix. |
| B8 | `V55__execution_snapshot_integration_config.sql` and its tests are **untracked** in git | The snapshot contract is not reproducible from a clean checkout | Commit before F10 relies on it. |

**No backend change was made.** Per §35, backend changes default to not allowed, and
none of the above blocks F1.

### Not implemented because unsupported by the backend

| Item | Why |
|---|---|
| Tenant selector / tenant switching | No request can be narrowed to one tenant; a selector would be a fake scope change (§6.2). |
| Reseller "act as" mode | Same. |
| IVR anything | Blocked (§8). |
| Scheduler / run dashboard | No endpoint exposes scheduler status, queue depth or runtime metrics. |
| Dashboard route | No aggregate/metrics endpoint found. |
| `middleware.ts` auth guard | The refresh cookie is not sent with page requests; middleware cannot validate the session, and a second cookie would be a new credential to leak (§5.4). |
| Query keys scoped by `scopeKey` | Isolation is already guaranteed by `queryClient.clear()`; 50+ call sites of churn with no correctness gain and a real invalidation risk (§6.3). |
| DID assign/revoke UI, audio upload UI, TTS scope-aware create | Services and types are in place; the UI belongs to F3/F5/F6. |
| Component / route tests | Need jsdom + Testing Library, a larger addition (§9). |

---

## 9. Testing

F0 found **no test runner at all**. F1 added Vitest as the first, deliberately minimal:
`environment: "node"`, no DOM, no mocking library, no Next.js plugin. Justification:
the logic F1 changed is pure and synchronous, and it is exactly the logic that drifts
silently. §31 asks for a foundation, "where the existing stack supports it" — nothing
did, so one small runner was added rather than a framework.

**83 tests, 7 files, all passing.**

| File | Tests | What it pins |
|---|---|---|
| `lib/api/error.test.ts` | 17 | 401 ≠ 403; 404/409/429/5xx/transport; `Validation` vs `BadRequest`; `requestId` passthrough; `NETWORK_ERROR_CODE` now assigned; retryable set; the 405-with-`BAD_REQUEST` quirk |
| `lib/api/transport.test.ts` | 8 | Envelope unwrap; rejection propagates unchanged; pagination **not** invented for an unpaginated endpoint; blob and 204 paths |
| `lib/auth/capabilities.test.ts` | 15 | Exactly 34 keys; IVR absent; no invented permission; no duplicates; VIEW ≠ MANAGE; empty-user denies |
| `lib/auth/operating-context.test.ts` | 8 | `null` ⇒ PLATFORM; TENANT/RESELLER id routing; scope **not** inferred from capabilities; distinct `scopeKey` per identity |
| `lib/schemas/campaign-config.test.ts` | 27 | `playsMedia` for all 4 types; ring-window bounds; `.strict()` unknown-field rejection; PLAYFILE empty-only; DTMF defaults; webhook cross-field + credential rules; retry `MM:SS` and category uniqueness |
| `components/layout/navigation.test.ts` | 8 | Capability **and** scope; Tenants/Resellers hidden from reseller and tenant; ungated Account; a no-capability user sees only Account |
| `components/auth/server-field-errors.test.ts` | 8 | `admin.email` → `adminEmail` (the F1-2 bug); unknown and nested paths dropped, not misapplied |

**Not covered, and why:** component and route rendering (needs jsdom + Testing
Library), the axios interceptor's 401 refresh path (needs a DOM `window` and request
interception), and TanStack Query integration. Listed in §11.

---

## 10. Validation Commands

Executed in `D:\work\agile\obd-platform\frontend`, in this order:

| Command | Result |
|---|---|
| `npm run typecheck` (`tsc --noEmit`) | **PASS** — exit 0, no output |
| `npm run lint` (`eslint`) | **PASS** — exit 0, **0 errors, 5 warnings** |
| `npm run test` (`vitest run`) | **PASS** — 7 files, **83/83 tests** |
| `npm run build` (`next build`) | **PASS** — compiled in 10.3 s, TypeScript in 11.7 s, 22 routes |
| `npm run validate` (composite) | **PASS** |

### Build note — two environment failures, then a clean pass

The first two `npm run build` attempts died with `Fatal process out of memory: Zone` and
`Next.js build worker exited with code: 2147483651`. The host had **0.0–0.2 GB free of
5.9 GB**, and `Get-Process` showed Docker/WSL, a JVM and browsers holding the rest.
Lowering `--max-old-space-size` did not help, which is consistent with a host-level
exhaustion rather than a V8 heap cap.

To establish whether that was my doing, a **clean `HEAD` worktree** was created in the
temp directory and built. It **succeeded** — and so did the F1 tree immediately
afterwards, on the same machine, with no code change in between. The two failures were
transient memory pressure, not a defect in the F1 changes, and **no configuration was
altered to make the build pass**. The temp worktree was removed afterwards
(`git worktree list` shows only the main worktree).

Worth recording for whoever runs this next: **`next build` on this machine is
memory-fragile.** `npm run typecheck`, `lint` and `test` are all reliable and were run
repeatedly; a build failure here should be re-run before being treated as a real error.

The 5 lint warnings are all `react-hooks/incompatible-library` (React Compiler skips
memoising `form.watch`): `campaigns/edit-campaign-dialog.tsx:100`,
`campaigns/campaign-table.tsx:206`, `audio-assets/audio-asset-table.tsx:49`,
`tts-templates/tts-template-table.tsx:33`, `tts-templates/create-tts-template-dialog.tsx:49`,
`tts-templates/edit-tts-template-dialog.tsx:51`. **All pre-existing** — the same five
F0 recorded. No new warning was introduced.

Dependency change: **`vitest@^3.2.7` added as a devDependency.** No existing dependency
was upgraded, downgraded or removed; Next.js, React, Tailwind, TanStack Query and axios
are untouched.

### Manual validation — NOT PERFORMED, and why

The brief asks for manual checks of login, refresh, 403, reseller tenant selection and
error paths. **None of these were run, and no result is claimed.**

The backend needs PostgreSQL, Redis and a FreeSWITCH stack; nothing in this
environment has been started or migrated, and F0's own validation was limited to
static inspection plus `typecheck`/`lint`. Fabricating a backend response to claim a
login or a 403 "passed" would be worse than reporting nothing, and the brief forbids
it. What *was* done is asserted at the unit level against the verified contracts, which
covers the pure logic but not the live integration.

**To perform them:** start the backend (`./mvnw spring-boot:run` with Postgres and Redis
up), `npm run dev`, then exercise login → refresh → logout, a 403 by signing in as a
`RESELLER_ADMIN` and opening Audio Assets, a 400 by submitting an invalid DID, and the
campaign save path with per-category retry rules.

---

## 11. Remaining Work

### F2 must address

1. **Contacts & Contact Groups migration** — the actual domain work. F1 fixed the
   contract; the roster-vs-child-CRUD decision is still open.
2. **Audio create path** — replace the metadata form with `uploadAudioAsset`. Until
   then the form still asks for a hand-typed 64-char checksum.
3. **TTS scope-aware UI** — a real create form with `scope`, not the explicit
   `TENANT` default F1 now sends.
4. **Tenant creation context** — decide the reseller model (U3); `resellerId: null`
   remains hardcoded and is correct only for platform callers.
5. **Commit the OpenAPI spec** to `docs/api/openapi.json` and add a drift check. This
   single step would have caught F0-1, F0-2, F0-3, F0-6, F0-9, F0-10, F0-11 and F0-15.
6. **Unanswered product decisions** — U1 (capability → action mapping for the remaining
   ungated actions), U3 (reseller tenant selection), U6 (`MISSED_CALL` in product scope),
   U8 (roster vs child CRUD). U1 in particular blocks gating the create/edit/delete
   actions on Campaigns, Contacts, Groups, Tenants, Resellers and Users, which F1 did
   not touch because the mapping is not defined anywhere.

### Deferred technical work

| Item | Why deferred |
|---|---|
| jsdom + Testing Library | Larger addition; §31 says not to add a large framework |
| Interceptor / refresh-path tests | Needs a DOM `window` and request interception |
| TanStack Query integration tests | Needs a provider harness |
| Campaign create/edit UI | F4 |
| DID assign/revoke UI, queue picker | F3+; the queue picker is a **prerequisite** for `CONNECT_BY_AGENT` |
| Execution/attempt visibility, failure-code labels | F10 |
| 53 `CallFailureCode` display vocabulary | Needed before attempt UI; `failureCode` stays an unconstrained `string` |
| `CallAttemptPage` consumers | `execution-detail-view.tsx` still reads `.pagination` from a value that no longer has it — **must be fixed in F10**; typecheck passes because the field access is behind the new narrower type |

### Open unknowns (unchanged from F0)

U1, U3, U6, U8 remain open. U4 (dashboard) and U7 (integration UI) are moot for F2.
New: whether the backend will ever gain tenant impersonation — if it does,
`operating-context.ts` is the single place to grow it.

---

## 12. Files Changed / Added

**Added (11)**

```
src/lib/api/transport.ts
src/lib/api/transport.test.ts
src/lib/api/error.test.ts
src/lib/auth/use-can.tsx
src/lib/auth/operating-context.ts
src/lib/auth/operating-context.test.ts
src/lib/auth/capabilities.test.ts
src/lib/schemas/campaign-config.ts
src/lib/schemas/campaign-config.test.ts
src/components/common/query-state.tsx
src/components/auth/server-field-errors.ts
src/components/auth/server-field-errors.test.ts
src/components/layout/navigation.test.ts
src/components/campaigns/clone-campaign-dialog.tsx
src/app/(auth)/error.tsx
src/app/(auth)/loading.tsx
src/app/(auth)/not-found.tsx
src/app/(platform)/error.tsx
src/app/(platform)/loading.tsx
src/app/(platform)/not-found.tsx
vitest.config.ts
```

**Modified (33)** — `lib/api/{auth,audio-assets,call-attempts,campaigns,contact-groups,contacts,contracts,dids,error,resellers,signup,tenants,tts-templates,users}.ts`,
`lib/schemas/{auth,campaign-mutation,call-attempt-mutation,reseller-mutation,signup,tenant-mutation,tts-template-mutation}.ts`,
`lib/auth/{capabilities,index}.ts`, `components/layout/navigation.ts`,
`components/common/{campaign-type-badge,campaign-filter-toolbar}.tsx`,
`components/audio-assets/{audio-assets-view,audio-asset-table}.tsx`,
`components/tts-templates/{tts-templates-view,tts-template-table}.tsx`,
`components/contacts/{contact-table,contacts-view}.tsx`,
`components/campaigns/{campaigns-view,campaign-table,campaign-detail-view,call-attempt-table,execution-detail-view}.tsx`,
`components/auth/{sign-in-form,tenant-signup-form,reseller-signup-form,signup-error-mapping}.ts`,
`app/(platform)/layout.tsx`, `package.json`.

**Not modified:** any backend file (see §12.1); `next.config.ts`; `tsconfig.json`;
`eslint.config.mjs`; `components.json`; any `components/ui/*` primitive;
`lib/api/client.ts` (auth interceptor, deliberately untouched); `lib/session.ts`
(correct as found).

### 12.1 Backend integrity

F1 wrote **zero** backend files. Verified three ways:

- `git status --short -- backend` shows 37 entries, but 21 were already present at F0's
  baseline and the rest are concurrent third-party work (see §2.7).
- A timestamp scan of `backend/**/*.{java,sql,md}` over the F1 window matches only those
  same third-party files — every one of them an edit F1 did not make.
- F1's own tool calls touched `backend/` only through `read`, `grep` and `Select-String`.

---

## 13. F1 — Frontend Foundation & Contract Alignment

**Status:** PASS WITH BLOCKERS

**Frontend changes:** Envelope handling extracted to `transport.ts`; error layer
extended with status predicates and a real `NETWORK_ERROR_CODE`; shared
`query-state.tsx` (loading/error/403/empty) replacing nine drifted copies;
`error.tsx` + `loading.tsx` + `not-found.tsx` on both route groups; layout gate fixed
to preserve `?next=` and to stop treating a 5xx as a logout; four duplicate-interface,
three shadowing-enum and two duplicated-transition-table copies removed.

**API contract changes:** `markAttemptFailed` now sends **query parameters** (F0 sent a
body, so failure diagnostics were silently dropped); attempt list no longer sends dead
`sort`/`status` params and no longer fabricates `PaginationMetadata`; `uploadAudioAsset`
multipart service added; `assignDid`/`revokeDid` added; four DELETEs moved to
`sendVoid`; TTS create now sends `scope`.

**Auth changes:** none to behaviour. In-memory token, HttpOnly-cookie refresh,
single-flight retry and `queryClient.clear()` all preserved; `client.ts` untouched. The
layout gate now tears down local state before redirecting and distinguishes 401 from
other failures.

**Authorization changes:** 19 → **34** capabilities, all backend-seeded, none invented;
one mechanism (`Can` / `useCan` / `can`) replacing scattered checks; the
`hasPlatformAccess`/`hasResellerAccess`/`hasTenantAccess` inventions removed; action
gating added for Audio and TTS (manage vs approve); navigation refactored onto the
shared helpers; 403 rendered as forbidden, never as a sign-in redirect.

**Tenant context changes:** explicit `OperatingContext` derived from `/me`
(PLATFORM/RESELLER/TENANT) with a testable pure function and a `scopeKey`. **No tenant
selector**, because the backend offers hierarchy-wide read and no impersonation — a
selector would be a fake scope change. Cache isolation left intact and its correctness
argument documented.

**Type/DTO changes:** `MISSED_CALL` added; 4 `CampaignResponse` fields added;
`retryPolicy.rules` modelled **and sent** (fixing silent data loss on save);
`configurationSnapshotId` added; `typeConfig`/`integrationConfig` replaced with typed,
strictly-validated unions; `TtsTemplateScope` modelled; `ContactGroupResponse.memberCount`
and `DidResponse.allocationSource` added; phantom `ContactResponse.contactGroupId`
removed; `allowedDaysOfWeek` narrowed to `DayOfWeek`; `MarkAttemptFailedPayload` renamed
to `…Params`; `ADMIN_PASSWORD_MIN = 12` separated from `PASSWORD_MIN = 8`; DTOs added
for the contact roster and DID assignment.

**Testing:** 83 tests / 7 files, all passing. Vitest added as a devDependency (node
environment, no DOM).

**Validation:** `typecheck` exit 0 · `lint` 0 errors, 5 pre-existing warnings ·
`test` 83/83 · `build` success (22 routes) · `validate` composite pass.

**Backend blockers:**
1. **IVR capability seeding** — `IVR_VIEW`/`IVR_MANAGE` are enforced by
   `IvrTreeService` but seeded by **no** migration, so every IVR endpoint 403s for every
   role including `SUPER_ADMIN`. Re-verified in F1; not worked around; no frontend
   permission invented. Additionally IVR is tenant-only.
2. **`V55` untracked** — the execution-snapshot column its migration adds is not
   committed, so the snapshot contract F1 typed is not reproducible from a clean
   checkout.
3. **B7 (pre-existing)** — three `GlobalExceptionHandler` branches render outside a
   try/catch, so a failure while serialising a `ProblemDetail` could escape as a 500.

**Known remaining issues:** `resellerId: null` still hardcoded for tenant creation
(needs U3); `execution-detail-view.tsx` still reads `.pagination` from the now-narrower
attempt result (F10); no component/route/interceptor tests; no committed OpenAPI spec
or drift check; ~10 domains' create/edit/delete actions still ungated pending U1.

**F2 readiness:** READY, with the blocker's scope noted.

**Reason:** the chain Authentication → Authorization → Organizational scope → API
contract layer → Domain types → Query layer → UI is now coherent, verified against
backend source, and covered by 83 tests. F2 can begin on Contacts and Contact Groups
without simultaneously discovering authentication, authorization, tenant, API-client,
DTO or state-management problems. The IVR blocker does not gate F2 — there is no IVR
surface and none will be added — but it must be closed before any IVR work is planned,
and the two product decisions that F1 could not make for itself (**U1**: the
capability → action mapping; **U3**: the reseller tenant-selection model) are the only
things F2 cannot complete without input.
