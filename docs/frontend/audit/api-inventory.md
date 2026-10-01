# F0 Artifact — API Inventory

Canonical source: [`../F0-FRONTEND-AUDIT.md`](../F0-FRONTEND-AUDIT.md) §6, §8.
All paths are prefixed `/api/v1`. Source files are relative to
`backend/src/main/java/com/shivang/obd/`.

Legend for **FE**: `M` match · `P` partial · `X` mismatch · `—` no frontend caller.

## 1. Authentication — `security/AuthController.java`

| Method | Path | Request | Response | Cap | FE | Line |
|---|---|---|---|---|---|---|
| POST | `/auth/login` | `LoginRequest{email,password}` | `LoginResponse{accessToken,tokenType,expiresInSeconds}` | `permitAll` | M | 71 |
| POST | `/auth/refresh` | cookie `obd_rt` only | `LoginResponse` | `permitAll` | M | 87 |
| GET | `/auth/me` | — | `AuthenticatedUserResponse` | bearer | M | 50 |
| POST | `/auth/change-password` | `ChangePasswordRequest{currentPassword,newPassword}` | `null` | bearer | M | 57 |
| POST | `/auth/logout` | cookie | `null` | `permitAll` | M | 112 |
| POST | `/auth/logout-all` | — | `null` | bearer | M | 134 |
| POST | `/account/signup/tenant` | `TenantSignupRequest{name,slug,admin}` | `TenantResponse` | `permitAll` | M | `tenant/TenantSignupController.java:38` |
| POST | `/account/signup/reseller` | `CreateResellerRequest` | `ResellerResponse` | `permitAll` | M | `reseller/ResellerSignupController.java:40` |

`AuthenticatedUserResponse(id, email, status, homeType, organizationId, capabilities)`.
`homeType` is `TENANT|RESELLER` or **`null` for platform users**.

## 2. Campaigns — `campaign/CampaignController.java`

| Method | Path | Params | Response | Cap | FE | Line |
|---|---|---|---|---|---|---|
| POST | `/campaigns` | body `CreateCampaignRequest`; **`?tenantId`** | `CampaignResponse` 201 | `CAMPAIGN_MANAGE` | P | 60 |
| GET | `/campaigns` | `page=0, size=20, sort="createdAt,desc", status?, campaignType?, runMode?, search?` | `List<CampaignResponse>` + `pagination` | `CAMPAIGN_VIEW` | P | 96 |
| GET | `/campaigns/{id}` | — | `CampaignResponse` | `CAMPAIGN_VIEW` | P | 80 |
| PUT | `/campaigns/{id}` | `UpdateCampaignRequest` (PUT replace) | `CampaignResponse` | `CAMPAIGN_MANAGE` | P | 121 |
| DELETE | `/campaigns/{id}` | — | 204 (soft) | `CAMPAIGN_MANAGE` | M | 138 |
| PATCH | `/campaigns/{id}/status` | `{status: String}` | `CampaignResponse` | `CAMPAIGN_EXECUTE` | P | 160 |
| POST | `/campaigns/{id}/clone` | — | `CampaignResponse` 201 | `CAMPAIGN_MANAGE` | P | 180 |
| GET | `/campaigns/{id}/readiness` | — | `CampaignReadinessResponse{campaignId,ready,reasons[]}` | `CAMPAIGN_VIEW` | M | 199 |
| POST | `/campaigns/{id}/executions` | `{idempotencyKey?}` | `CampaignExecutionResponse` 201 | `CAMPAIGN_EXECUTE` | P | 218 |
| GET | `/campaigns/{campaignId}/executions` | **none, no pagination** | `List<CampaignExecutionResponse>` | `CAMPAIGN_EXECUTE` | M | 252 |
| GET | `/campaigns/{campaignId}/executions/{executionId}` | — | `CampaignExecutionResponse` | `CAMPAIGN_EXECUTE` | P | 236 |
| POST | `/campaigns/{id}/ivr-tree` | IVR source request | — | `IVR_MANAGE` | — | `campaign/CampaignIvrController.java:61` |

## 3. Call Attempts — `campaign/CallAttemptService` via `CampaignController`

| Method | Path | Params | Cap | FE | Line |
|---|---|---|---|---|---|
| POST | `.../executions/{executionId}/attempts` | `CreateCallAttemptRequest{contactId,didId,attemptNumber,scheduledAt?}` | `CAMPAIGN_EXECUTE` | M | 271 |
| GET | `.../attempts/{attemptId}` | — | `CAMPAIGN_EXECUTE` | M | 291 |
| GET | `.../attempts` | **none, no pagination** | `CAMPAIGN_EXECUTE` | **X** | 309 |
| PATCH | `.../attempts/{attemptId}/in-progress` | — | `CAMPAIGN_EXECUTE` | M | 329 |
| PATCH | `.../attempts/{attemptId}/completed` | — | `CAMPAIGN_EXECUTE` | M | 350 |
| PATCH | `.../attempts/{attemptId}/failed` | **`?failureCode=&failureReason=` (RequestParam)** | `CAMPAIGN_EXECUTE` | **X** | 372 |
| PATCH | `.../attempts/{attemptId}/cancel` | — | `CAMPAIGN_EXECUTE` | M | 395 |

## 4. Contacts & Groups — `contact/ContactGroupController.java`

**No `ContactController` and no `/api/v1/contacts` resource exist** (confirmed by controller
enumeration). All 18 endpoints sit under `/contact-groups`.

| Method | Path | Params | Cap | FE | Line |
|---|---|---|---|---|---|
| POST | `/contact-groups` | `CreateContactGroupRequest` | `CONTACT_MANAGE` | M | 61 |
| GET | `/contact-groups/{id}` | — | `CONTACT_VIEW` | M | 79 |
| GET | `/contact-groups` | `page,size,sort="createdAt,desc",search?` | `CONTACT_VIEW` | P | 94 |
| PUT | `/contact-groups/{id}` | `UpdateContactGroupRequest` | `CONTACT_MANAGE` | M | 113 |
| DELETE | `/contact-groups/{id}` | — 204 | `CONTACT_MANAGE` | M | 130 |
| POST | `/contact-groups/{id}/contacts` | `CreateContactRequest` | `CONTACT_MANAGE` | X | 149 |
| GET | `/contact-groups/{id}/contacts` | `page,size,sort="firstName,asc",search?` | `CONTACT_VIEW` | M | 165 |
| GET | `/contact-groups/{id}/contacts/{contactId}` | — | `CONTACT_VIEW` | M | 184 |
| PUT | `/contact-groups/{id}/contacts/{contactId}` | `UpdateContactRequest` | `CONTACT_MANAGE` | M | 200 |
| DELETE | `/contact-groups/{id}/contacts/{contactId}` | — 204 | `CONTACT_MANAGE` | M | 218 |
| POST | `/contact-groups/{id}/contacts/import` | **multipart `@RequestParam("file")`** (csv/xlsx/json, ≤5000 rows) | `CONTACT_MANAGE` | M | 379 |
| GET | `/contact-groups/{id}/contacts/export` | `?format=csv\|xlsx\|json` → **raw `byte[]`** | `CONTACT_VIEW` | M | 400 |
| GET | `/contact-groups/{id}/members` | `page,size,sort="createdAt,asc",search?` | `CONTACT_VIEW` | — | 241 |
| POST | `/contact-groups/{id}/members` | `AddMemberRequest{contactId}` → **201 created / 200 existed** | `CONTACT_MANAGE` | — | 269 |
| GET | `/contact-groups/{id}/members/{contactId}` | — | `CONTACT_VIEW` | — | 293 |
| DELETE | `/contact-groups/{id}/members/{contactId}` | — 204 (idempotent) | `CONTACT_MANAGE` | — | 312 |
| POST | `/contact-groups/{id}/members/batch` | `BatchMemberRequest{contactIds[≤500]}` | `CONTACT_MANAGE` | — | 334 |
| DELETE | `/contact-groups/{id}/members/batch` | `BatchMemberRequest` (DELETE **with a body**) → 200 | `CONTACT_MANAGE` | — | 354 |

## 5. Audio — `audio/AudioAssetController.java`

| Method | Path | Params | Cap | FE | Line |
|---|---|---|---|---|---|
| POST | `/audio-assets` | `CreateAudioAssetRequest` (8 fields) | `AUDIO_MANAGE` | P | 50 |
| GET | `/audio-assets/{id}` | — | `AUDIO_VIEW` | M | 68 |
| **POST** | **`/audio-assets/upload`** | **multipart `@RequestParam` `name`, `description?`, `file`** (WAV/MP3, ≤5 MiB) | `AUDIO_MANAGE` | **—** | 86 |
| GET | `/audio-assets` | `page,size,sort="createdAt,desc",status?,search?` | `AUDIO_VIEW` | M | 110 |
| PUT | `/audio-assets/{id}` | `UpdateAudioAssetRequest{name,description}` | `AUDIO_MANAGE` | M | 130 |
| DELETE | `/audio-assets/{id}` | — 204 (soft) | `AUDIO_MANAGE` | M | 146 |
| PATCH | `/audio-assets/{id}/approve` | — (409 if already approved) | `AUDIO_APPROVE` | M | 162 |
| PATCH | `/audio-assets/{id}/reject` | — (409 if already rejected) | `AUDIO_APPROVE` | M | 177 |

## 6. TTS — `tts/TtsTemplateController.java`

| Method | Path | Params | Cap | FE | Line |
|---|---|---|---|---|---|
| POST | `/tts-templates` | `CreateTtsTemplateRequest{name,description?,templateText,variables,tenantId?,**scope**}` | `TTS_MANAGE` | **X** | 50 |
| GET | `/tts-templates/{id}` | — | `TTS_VIEW` | P | 68 |
| GET | `/tts-templates` | `page,size,sort="createdAt,desc",status?,search?` — **no `scope` filter exists** | `TTS_VIEW` | P | 81 |
| PUT | `/tts-templates/{id}` | `UpdateTtsTemplateRequest` — **no `tenantId`/`scope`/`status`** | `TTS_MANAGE` | M | 103 |
| DELETE | `/tts-templates/{id}` | — 204 | `TTS_MANAGE` | M | 119 |
| PATCH | `/tts-templates/{id}/approve` | — (409 if already approved) | `TTS_APPROVE` | M | 135 |
| PATCH | `/tts-templates/{id}/reject` | — (409 if already rejected) | `TTS_APPROVE` | M | 150 |

`TtsTemplateResponse` also carries **`scope`** — absent from the frontend type.

## 7. DIDs — `did/DidController.java`

| Method | Path | Params | Cap | FE | Line |
|---|---|---|---|---|---|
| POST | `/dids` | `CreateDidRequest` (11 fields, incl. `tenantId?`/`resellerId?`) | `DID_MANAGE` | M | 52 |
| GET | `/dids/{id}` | — | `DID_VIEW` | M | 70 |
| GET | `/dids` | `page,size,sort,status?,allocationState?,numberType?,provider?,circle?,tenantId?,resellerId?,**q**?` | `DID_VIEW` | M | 87 |
| PUT | `/dids/{id}` | `UpdateDidRequest` (replace; no `e164Number`/ownership) | `DID_MANAGE` | M | 117 |
| **POST** | **`/dids/{id}/assign`** | `AssignDidRequest{targetId}` (tenant or reseller id) → `AssignDidResponse` | `DID_MANAGE` | **—** | 138 |
| **POST** | **`/dids/{id}/revoke`** | — → `AssignDidResponse` | `DID_MANAGE` | **—** | 157 |
| DELETE | `/dids/{id}` | — 204 | `DID_MANAGE` | M | 172 |

`DidResponse` also carries **`allocationSource`** — absent from the frontend type.
`tenantId`/`resellerId` list params are honoured **only** in the platform-scope branch
(`DidService.java:155-161`). A tenant caller attempting assign/revoke gets **400**
("Tenants cannot assign or transfer DID inventory.", `DidService.java:230-232`).

## 8. Tenants / Resellers / Users

`tenant/TenantController.java`:

| Method | Path | Cap | FE | Line |
|---|---|---|---|---|
| POST | `/tenants` | `TENANT_MANAGE` (reseller-scoped **or** platformWide) | P | 50 |
| GET | `/tenants/{id}` | `TENANT_VIEW` | M | 68 |
| GET | `/tenants` | `TENANT_VIEW` | M | 83 |
| PUT | `/tenants/{id}` | `TENANT_MANAGE` | M | 104 |
| DELETE | `/tenants/{id}` | `TENANT_MANAGE` | M | 120 |
| **POST** | **`/tenants/{tenantId}/agents`** — returns **`TenantResponse`** | **`USER_MANAGE` platformWide** (`TenantService.java:82-83`) | **—** | 139 |

`reseller/ResellerController.java`: POST 46 · GET `/{id}` 63 · GET list 78 · PUT 100 ·
DELETE 116. Caps `RESELLER_MANAGE`/`RESELLER_VIEW`; create and delete are **platformWide only**.

`account/UserController.java`: GET `/{id}` 40 · GET list 55 · PUT `/{id}` 78.
Caps `USER_VIEW`/`USER_MANAGE`. **No create, no delete, no `GET /users/me`.**
`UpdateUserRequest` is a **partial** update (`displayName` applied only if non-blank; `status`
only if non-null) despite being `PUT`.

## 9. Domains with no frontend (and their real contracts)

`ivr/IvrTreeController.java` — 6 endpoints, `IVR_VIEW`/`IVR_MANAGE`, **tenant-only**.
List takes `page`, `size`, `status?` **only**: no sort, no search, and the response carries
**no `PaginationMetadata`** (`IvrTreeService.java:161`). Nodes are keyed by `nodeKey`;
transitions are `{input, targetNodeKey}`; status change is `POST /{id}/status` (not PATCH);
`DRAFT` is accepted by the schema but rejected by the service.

`voice/queue/QueueDirectoryController.java` — 12 endpoints, `QUEUE_VIEW`/`QUEUE_MANAGE`.
Default sort is **`name,asc`** (not `createdAt,desc`); its `buildPageable` requires
`sort.length >= 2`, so a bare `sort=name` is **silently ignored**. Members and waiting-calls
are **unpaginated**. Status change is `PUT /{queueId}/status`. `maxWaitingCalls`/`maxWaitSeconds`
(not `maxCapacity`/`maxWaitTime`).

`voice/agent/AgentDirectoryController.java` — 17 endpoints. Caps `AGENT_VIEW`/`AGENT_MANAGE`,
and **`CALL_VIEW`** for `GET /{id}/calls` and `/calls/active`. Endpoint sub-resource lives at
`/agents/endpoints/{endpointId}` (top level, not nested). Default sort `displayName,asc`, same
`length >= 2` quirk. Call history is the only endpoint with date filters, named **`from`/`to`**
(`Instant`). `CreateAgentEndpointRequest.agentId` is `@NotNull` but **ignored**. Availability
request field is `availability`, response field is `presence`.

## 10. NOT EXPOSED BY BACKEND

No endpoint exists for: **scheduler status / dispatch queue depth / runtime metrics** ·
campaign progress rollups · campaign-level attempt counts · aggregate dashboard statistics ·
webhook delivery records or reports (`integrationConfig` is configuration only — "no webhook
is delivered and no report is generated") · contacts outside a contact group.
