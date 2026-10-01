# F0 Artifact — Contract Matrix

Canonical source: [`../F0-FRONTEND-AUDIT.md`](../F0-FRONTEND-AUDIT.md) §7, §8, §10.
Every row is derived from backend controller/DTO source; frontend file:line given for each call.

Classes: **MATCH** · **PARTIAL** (works, under-represents the contract) · **MISMATCH** ·
**STALE** · **UNSUPPORTED** · **NO FRONTEND** · **UNKNOWN**.

## Summary counts

| Class | Count |
|---|---|
| MATCH | 24 |
| PARTIAL | 15 |
| MISMATCH | 8 |
| NO FRONTEND (endpoint exists) | 9 endpoint groups (6 roster + 2 DID + audio upload + tenant agents + 3 whole domains) |
| UNSUPPORTED | 0 — **the frontend invents no endpoint, DTO, role or permission** |
| STALE (removed contract) | 0 — every frontend call resolves to a live endpoint |

## Hard mismatches (must fix in F1)

| # | Frontend location | Backend location | Frontend assumes | Backend behaviour | Impact | Action |
|---|---|---|---|---|---|---|
| 1 | `lib/api/call-attempts.ts:151-162` (sends JSON body) | `campaign/CampaignController.java:377-378` (`@RequestParam failureCode, failureReason`) | body `{failureCode, failureReason}` | **query parameters** | failure diagnostics are **silently dropped**; every failed attempt looks diagnostic-free | move to query params |
| 2 | `lib/api/call-attempts.ts:90-94` (sends `sort`, `status`) | `CampaignController.java:309-313` (**no `@RequestParam` at all**) | filterable + sortable list | fixed order, no parameters | the status filter silently does nothing; UI implies a capability that does not exist | remove dead params + the fabricated `PaginationMetadata` (`call-attempts.ts:98-105`) |
| 3 | `lib/api/contracts.ts:281` (`ContactResponse.contactGroupId`) | `contact/dto/ContactResponse.java` — **9 components, no `contactGroupId`** | the contact carries its group id | the DTO has no such field | a permanently `undefined` field; any code branching on it is dead | remove the phantom field |
| 4 | `lib/api/contracts.ts:420` (`CampaignType` = 3 values) | `campaign/CampaignType.java:33` (**`MISSED_CALL` is the 4th**) | PLAYFILE, DTMF, CONNECT_BY_AGENT | PLAYFILE, DTMF, CONNECT_BY_AGENT, **MISSED_CALL** | `createCampaignSchema` rejects a valid type; the type filter cannot offer it; the type badge cannot render it; `edit-campaign-dialog.tsx:99` defaults to `"PLAYFILE"` | add `MISSED_CALL` end-to-end |
| 5 | `lib/api/contracts.ts:391-397` (`CreateTtsTemplatePayload` has `tenantId`, **no `scope`**) | `tts/dto/CreateTtsTemplateRequest.java:5-6` (**`scope` present**); `TtsTemplateService.java:78-86` | a template is always tenant-owned | `scope: GLOBAL` creates a **platform-owned** template and **rejects** a `tenantId` with 400 | platform/system templates are **unreachable from the UI** | add `scope`, or explicitly restrict the UI to tenant templates and document it |
| 6 | `lib/api/contracts.ts:378-388` (`TtsTemplateResponse` = 9 fields) | `tts/dto/TtsTemplateResponse.java` — **10, incl. `scope`** | — | `scope: GLOBAL\|TENANT` is returned | the UI cannot distinguish a platform catalog template from a tenant one | model `scope`, show it in list + detail |
| 7 | `lib/schemas/campaign-mutation.ts:81` (`intervalSeconds` max **604800**) | `dto/RetryPolicyConfig.java` (`@Max(5999)`) | 7-day intervals are valid | max 5999 s | the user submits a value the server rejects with 400 and no actionable message | tighten the zod bound |
| 8 | `lib/schemas/auth.ts:16` `PASSWORD_MIN = 8`, reused by `signup.ts:34-37` and `reseller-mutation.ts:88-91` | `TenantAdminInput` / `AdminAccountInput` (`@Size(min = 12, max = 128)`) | 8 characters is enough | **12** minimum for every admin/agent provisioning form | an 8–11 char password is accepted by the UI and rejected by the server | introduce `ADMIN_PASSWORD_MIN = 12` |

## Partial matches (under-modelled responses)

| Backend DTO | Missing frontend field(s) | Consequence |
|---|---|---|
| `CampaignResponse` (`CampaignResponse.java:66,75,88,102`) | `callOnWhitelistNumbers`, `dailyDialLimit`, `maxDailyAttempts`, `maxCallDurationSeconds` | four safety/limit controls present in both create **and** update DTOs are invisible and uneditable |
| `CampaignExecutionResponse` | `configurationSnapshotId` | the user-visible proof that an execution owns a frozen configuration cannot be shown |
| `RetryPolicyConfig` | `rules: List<RetryRuleConfig>` | per-category retry rules (category / enabled / maxRetries / `MM:SS` delay) cannot be configured |
| `ContactGroupResponse` | `memberCount: long` | the cheapest useful group summary is unused |
| `DidResponse` | `allocationSource` (`PLATFORM`\|`RESELLER`) | DID ownership provenance is hidden |

## Entirely unmodelled backend types

`TtsTemplateScope` · `RetryRuleCategory` · `RetryRuleConfig` · `ContactGroupMemberResponse` ·
`BatchMemberResponse` · `BatchMemberResult` · `MemberBatchStatus` · `AssignDidResponse` ·
`AllocationSource` · `CallFailureCode` (53 values) · all IVR DTOs · all Queue DTOs ·
all Agent DTOs.

## Endpoint groups with no frontend caller

| Endpoints | Why it matters |
|---|---|
| `POST /audio-assets/upload` (multipart) | the **only** real path to a usable, verified audio asset; the UI instead hand-types a 64-hex `checksum` and `storageReference` |
| `GET/POST/DELETE /contact-groups/{id}/members…` + both batch variants (6) | a richer roster API (`ContactGroupMemberResponse` with `memberId`/`groupId`/`contactId`/embedded `contact`) exists alongside the child-CRUD API the UI uses |
| `POST /dids/{id}/assign`, `POST /dids/{id}/revoke` | DID allocation transfer |
| `POST /tenants/{tenantId}/agents` | agent account provisioning (returns a `TenantResponse`, oddly) |
| `POST /campaigns/{id}/ivr-tree` | campaign → IVR promotion |
| `/api/v1/ivr-trees` (6) | **and blocked by the backend defect** — see audit §18.1 |
| `/api/v1/queues` (12) | **prerequisite** for `CONNECT_BY_AGENT.typeConfig.queueId` |
| `/api/v1/agents` (17) | agent state referenced by `CONNECT_BY_AGENT` |

## Enum drift

| Enum | Backend | Frontend | Class |
|---|---|---|---|
| `CampaignType` | 4 values | 3 | **MISMATCH** |
| `TtsTemplateScope` | 2 values | — | **UNMODELLED** |
| `RetryRuleCategory` | 6 values | — | **UNMODELLED** |
| `AllocationSource` | 2 values | — | **UNMODELLED** |
| `ScheduleConfig.allowedDaysOfWeek` | `Set<DayOfWeek>` (Java enum) | `string[]` / `z.array(z.string())` | **TOO LOOSE** — non-`MONDAY`…`SUNDAY` fails backend deserialization |
| `UpdateCampaignStatusRequest.status` | `String` `@NotBlank` | `CampaignStatus` union | Client stricter — acceptable |
| `CallFailureCode` | 53 values | `string \| null` | **LOSSY** — no vocabulary, no display labels |
| All others (CampaignStatus, CampaignRunMode, ContentMode, RetryStrategy, CampaignExecutionStatus, CallAttemptStatus, LifecycleStatus, OrganizationalHomeType, AudioAssetStatus, TtsTemplateStatus, DidStatus, AllocationState, NumberType, DidCapability) | — | identical | **MATCH** |

## DTOs verified as an exact match

`UserResponse` (7) · `TenantResponse` (7) · `ResellerResponse` (10) · `CallAttemptResponse` (15) ·
`AudioAssetResponse` · `ApiResponse` · `PaginationMetadata` · `ResponseMetadata` ·
`ProblemDetail` · `FieldErrorDto` · `LoginRequest`/`LoginResponse` ·
`AuthenticatedUserResponse` · `ChangePasswordRequest` · `ScheduleConfig` (7 nullable fields) ·
`ExecuteCampaignRequest` · `CampaignReadinessResponse`/`Reason` · `CreateCallAttemptRequest` ·
`Create/UpdateContactGroupRequest` · `Create/UpdateContactRequest` · `Create/UpdateAudioAssetRequest` ·
`Create/UpdateDidRequest` · `Create/UpdateTenantRequest` · `Create/UpdateResellerRequest` ·
`UpdateUserRequest`.

Note: `ResellerResponse` correctly omits `customDomain` because the **backend DTO** omits it,
even though `ResellerEntity` and `CreateResellerRequest` both have it.
`ContactImportResponse` matches (6 fields).
