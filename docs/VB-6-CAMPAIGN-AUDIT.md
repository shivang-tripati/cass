# VB-6 Campaign Domain & Dependency Audit

## 1. Audit Objective

Read-only audit of the OBD/Voice platform (backend, Java 17 / Spring Boot / Spring Modulith / PostgreSQL-Flyway) performed **before** any Campaign-domain implementation. Everything below is derived from the actual repository at `D:/work/agile/obd-platform/backend`. No production code, test, migration, or configuration was created or modified in this phase.

Legend used throughout:
- **CONFIRMED FROM CODE** — read directly in source/migrations.
- **INFERRED FROM CODE** — derived from code behavior, not stated.
- **NOT PRESENT** — searched for, does not exist.
- **UNKNOWN / NEEDS PRODUCT DECISION** — code cannot answer.

## 2. Repository Baseline

| Item | State | Evidence |
|---|---|---|
| Git | Repo fully untracked (`git branch` → `master`, no commits). Diff-based evidence unavailable. | `git branch --show-current; git log` |
| Last clean baseline | **855 tests / 0 failures / 0 errors / 1 skipped — BUILD SUCCESS** (VB-5G final). Tree unchanged since; treated as reference baseline. | `/tmp/vb5g_final.log` |
| Flyway head | **V42** (`V42__add_tts_template_scope.sql`). 42 migrations V1–V42. | `backend/src/main/resources/db/migration/` |
| Maven | Spring Boot 4.1.0, Spring Modulith, Testcontainers. No changes. | `backend/pom.xml` |
| Test structure | Unit, MockMvc slices, PG integration (Testcontainers), Architecture (`ArchitectureTest`), security slices. | `backend/src/test/java/...` |

No deviation from the reference baseline was observed (tree identical to the VB-5G final state).

## 3. Existing Campaign Architecture

**Domain — CONFIRMED FROM CODE** (`campaign/CampaignEntity.java`):
- Fields: `tenantId` (NOT NULL), `name`, `description`, `campaignType` (`CampaignType`: **PLAYFILE, DTMF, CONNECT_BY_AGENT** — 3 types), `status` (`CampaignStatus`: DRAFT, ACTIVE, PAUSED, COMPLETED, ARCHIVED), `runMode` (`CampaignRunMode.ONE_TIME` default), `contactGroupId` (FK to contact group, nullable), `didId`, `contentMode` (`ContentMode.AUDIO`/TTS), `audioAssetId`, `ttsTemplateId`, `schedule` (`ScheduleSpec` embeddable: timezone, startDate/endDate, startTime/endTime, allowedDaysOfWeek, holidayCalendarId reference-unresolved), `retryPolicy` (`RetryPolicySpec`: `maxAttempts`, `intervalSeconds`, `RetryStrategy` — **FIXED only**), `typeConfig` JSONB, `integrationConfig` JSONB ("Optional API/webhook integration configuration; no secrets"), `version` Integer default 1, `clonedFromCampaignId`, `callOnWhitelistNumbers` boolean default false.
- `version` is a **lineage/clone marker, not optimistic locking** — no `@Version` exists in `AuditableEntity`/`BaseEntity` (grep confirmed).

**Execution model — CONFIRMED FROM CODE**:
- `CampaignExecution` (status REQUESTED→RUNNING→COMPLETED/FAILED, `idempotencyKey` unique-per-campaign when present, requestedAt/By, started/completedAt, failureReason).
- `CallAttempt` (executionId, **denormalized campaignId** — VB-5F fix, tenantId, contactId, didId, `attemptNumber`, status `QUEUED/IN_PROGRESS/COMPLETED/FAILED/CANCELLED`, scheduledAt/startedAt/completedAt, failureCode/failureReason). Uniqueness: `(executionId, contactId, attemptNumber)` via `existsByExecutionIdAndContactIdAndAttemptNumberAndDeletedAtIsNull`.
- `CampaignExecutionOrchestrator.scheduledTick()` `@Scheduled(fixedDelay = 30000)` (L370): starts REQUESTED executions → `processRetries()` → `dialService.processDueAttempts()` → `eslEventProcessor.ensureEventProcessing()` → reconciles RUNNING executions. **A working execution poller already exists.**

**Services**: `CampaignService` (CRUD/activation/clone), `CampaignReadinessService` (readiness reasons), `CampaignExecutionService` (start/request), `CampaignExecutionOrchestrator` (above), `CallAttemptService` (manual attempt CRUD + status transitions), `OutboundDialService` (dial pipeline), `CampaignResourceValidationService` (canonical DID/Audio/TTS validation, VB-5E), `PlayfileExecutionService`, `DtmfExecutionService`, `ConnectByAgentService` (playback triggers on the ESL lifecycle).

**API — CONFIRMED FROM CODE** (`CampaignController.java`): POST `/` , GET `/{id}`, GET list, PUT `/{id}`, DELETE `/{id}`, PATCH `/{id}/status`, POST `/{id}/clone`, GET `/{id}/readiness`, POST `/{id}/executions`, GET executions (+single), POST/GET attempt endpoints, PATCH attempt `in-progress|completed|failed|cancel` (18 endpoints total).

**Migrations — CONFIRMED FROM CODE**: `V9__create_campaign.sql` (original), `V14__rebuild_campaign_domain.sql` (current shape), `V15__campaign_lifecycle_scheduling.sql`, plus DID/contact migrations V16–V19. JSONB columns (`type_config`, `integration_config`); enum columns are Postgres named enums / varchar.

## 4. Campaign Configuration Model

Architecture: **shared entity + JSON typeConfig** — partially typed. Common columns are strongly typed; type-specific configuration lives in `typeConfig` JSONB parsed at runtime by strict parsers.

| Type | Common fields used | typeConfig keys | Parser | Validation |
|---|---|---|---|---|
| PLAYFILE | `contentMode=AUDIO`, `audioAssetId` | none parsed today (asset comes from entity columns) | — (`PlayfileExecutionService` L132 requires `ContentMode.AUDIO` + `audioAssetId != null`) | `CampaignResourceValidationService.validateAudio` + runtime re-validation |
| DTMF | `contentMode`, audio asset for prompt | `dtmf.{expected, maxDigits, terminator, timeoutSecs, action}` | `DtmfConfig.fromTypeConfig` — **strict, total**: unknown shapes throw `DtmfConfigInvalidException` | parse-time + snapshot into `DtmfInteraction` |
| CONNECT_BY_AGENT | agent selection via `ConnectByAgentService` | not parsed from typeConfig (agent resolved by service) | — | `AGENT_CONFIG_INVALID`, `AGENT_ENDPOINT_INVALID`, `AGENT_TENANT_MISMATCH` failure codes |

NOT PRESENT: typed config classes for PLAYFILE/CONNECT_BY_AGENT, schema validation of `typeConfig` at write time (DTMF is validated only at call time), `integrationConfig` parsing (stored but never read — grep found no consumer).

## 5. Audience / Contact Architecture

**CONFIRMED FROM CODE** (`contact/`, V17/V19):
- `ContactGroupEntity` (tenant-scoped), `ContactEntity` — **group-scoped by design**: `contact_group_id UUID NOT NULL REFERENCES contact_groups (id)` (V17 L29). Phone: `phone_number VARCHAR(20) NOT NULL CHECK (phone_number ~ '^\+[1-9][0-9]{6,14}$')` — **E.164 enforced at DB level**. Dedup: partial unique index on `(contact_group_id, phone_number)` where deleted_at is null (V19). JSON `attributes`. Optional first/last name (V18).
- Import: `ContactGroupService.importContacts` (L79: `MAX_IMPORT_ROWS = 5000`) — **synchronous, single transaction, in-memory dedup**, CSV/JSON/XLSX readers, `MAX_REPORTED_ERRORS` capped error list, `ContactImportResponse`.
- Campaign → audience binding: `campaigns.contact_group_id`. A campaign selects **one group**. Individual numbers/pasted numbers have **no** entity — they must be materialized into a group (import endpoint) today.
- `CallEligibilityService.isNumberInCampaignContactGroup` enforces group membership at dial time via `existsByContactGroupIdAndPhoneNumberAndDeletedAtIsNull` (bypassed when `callOnWhitelistNumbers=true`).

**Future chain (Contact → callable identity → campaign membership → call history)**: partially supported. Contact exists; callable identity = the contact's single `phone_number`; campaign membership = group membership (indirect, no membership entity with per-campaign metadata); call history = `CallAttempt.contactId` (per execution) + `CallSession` (has **no** contactId — only `destinationNumber` + `callAttemptId`, so history joins through attempts). Historical calling info is attached to the **phone number via attempts**, not natively to the contact across executions. History across executions/campaigns requires a query over `call_attempts` by contact — possible but with no index on `contact_id` alone (CONFIRMED: `CallAttempt` indexes are execution/status-oriented).

## 6. DID / DNID

**CONFIRMED FROM CODE**: `DidEntity` (`did/`, V16, V39 inbound destination, V41 allocation source) with `DidStatus.ACTIVE` + `AllocationState.ASSIGNED`, tenant ownership, reseller hierarchy support.
- Campaign-time validation: `CampaignResourceValidationService.validateDid` (readiness + attempt creation + retry path).
- Dial-time validation: `VoiceEligibilityService.evaluate` step 6 — NOT_FOUND / NOT_ACTIVE / NOT_ASSIGNED / NOT_TENANT_OWNED each produce `INVALID_DID`.
- Selection: single campaign-level `didId`; no per-attempt DID pool. Caller-ID routing via `VoiceRoutingService` (primary/overflow/failover profiles).
- **Dynamic evaluation, no snapshot**: a DID revoked mid-execution fails the next dial (`INVALID_DID`) or the retry check (`isDidStillValid`); running attempts are not retroactively frozen. Stale DID → attempts marked FAILED with `INVALID_DID` (dial time) — CONFIRMED from `OutboundDialService`/`VoiceEligibilityDidSemanticsTest`.

## 7. Audio

**CONFIRMED FROM CODE** (`audio/`): `AudioAssetEntity` + repository, upload controller, `LocalAudioStorage` (storage abstraction), approval state, tenant ownership, `AudioUploadValidator` (advisory lock).
- PLAYFILE: `PlayfileExecutionService.onAnswered` validates exists / storage-ref resolvable / approved / tenant-owned → else terminal `PLAYBACK_CONFIG_INVALID` (permanent, never retried). Runtime support exists end-to-end (VB-1).
- DTMF: prompt reuses `contentMode=AUDIO` + `audioAssetId` (`DtmfExecutionService` L161 warns "missing AUDIO content/audioAssetId"). **NOT PRESENT**: dedicated invalid-response audio, no-response audio, multi-prompt sequences — `DtmfConfig` has no audio keys.
- Readiness: `CampaignResourceValidationService.validateAudio` codes `AUDIO_NOT_AVAILABLE / AUDIO_NOT_APPROVED / AUDIO_STORAGE_REFERENCE_MISSING`.

## 8. TTS

**CONFIRMED FROM CODE** (`tts/`, V42 scope): `TtsTemplate` with GLOBAL/TENANT scope, approval state, tenant visibility, readiness via `CampaignResourceValidationService` (`TTS_NOT_AVAILABLE / TTS_NOT_APPROVED`). **Configuration-only** — there is no synthesis provider, no runtime render boundary, no audio produced from TTS. A TTS campaign cannot actually speak today (contentMode TTS has no playback path — `PlayfileExecutionService` explicitly handles AUDIO only, L124 comment "only PLAYFILE campaigns play audio in VB-1").

## 9. Retry / Attempt Model

**CONFIRMED FROM CODE** (`CampaignExecutionOrchestrator.processRetriesForExecution`, `OutboundDialService`, `EslEventService`):

What constitutes an "attempt" today: one `call_attempts` row per `(execution, contact, attemptNumber)`; QUEUED → IN_PROGRESS (dial accept) → COMPLETED/FAILED/CANCELLED. Requeues (`TEMPORARILY_UNAVAILABLE`, capacity rejects, `PROVIDER_UNAVAILABLE`, dialer exceptions) reset to QUEUED **without consuming the attempt number**.

Existing failure/outcome taxonomy (all string-typed `failureCode`):

| Campaign retry case (intended) | Existing domain status/event | Existing? | Reusable? | Gap |
|---|---|---|---|---|
| NO_ANSWER | `NO_ANSWER` (dial result + hangup cause 19) | Yes | Yes | none |
| BUSY | `BUSY` (dial result + cause 17) | Yes | Yes | none |
| FAILED | `DIAL_FAILED`, `TEMPORARY_FAILURE` (cause 41), `CONGESTION` (34), `HANGUP_*` | Yes | Partially | no unified enum; string codes scattered |
| SWITCHED_OFF | — | **No** | — | no FreeSWITCH cause mapping (`HANGUP_<CAUSE>` fallback only) |

> **Remediated by VB-6D.1** (the two rows above are the audit's point-in-time record). The
> `HANGUP_<CAUSE>` fallback no longer exists: provider causes are normalized by a single total,
> closed boundary (`com.shivang.obd.voice.call.HangupCauseMapper`), so every persisted
> `failure_code` is a canonical `CallFailureCode` and an unmapped cause resolves to
> `HANGUP_UNKNOWN`. `SWITCHED_OFF` / `NOT_REACHABLE` remain deliberately unimplemented — see
> `docs/VB-6D.1-FAILURE-TAXONOMY-IMPLEMENTATION.md` §9.
| NOT_REACHABLE | `RESOURCE_UNAVAILABLE` (47), `TEMPORARILY_UNAVAILABLE` | Partial | Partial | semantics not product-defined |
| HANGUP (customer hangs up early) | `NORMAL_CLEARING` → COMPLETED | Yes | Yes | early-hangup counted as success today |
| Provider failure | `PROVIDER_UNAVAILABLE` (requeue) | Yes | Yes | — |
| Config failures | `PLAYBACK_CONFIG_INVALID`, `DTMF_CONFIG_INVALID`, `AGENT_*` | Yes | Yes | permanent set in `isPermanentFailure` |

Retry mechanics: `maxTotalAttempts = 1 + maxAttempts`; fixed `intervalSeconds` (default 60); schedule-window adjustment; idempotent via unique constraint; permanent failures skipped. **Retry scheduling is per-execution only — no cross-execution retry, no retry of a whole execution.**

## 10. Global Daily Call Limit

**NOT PRESENT — the critical gap.** No per-contact-per-day counting exists anywhere: no query counts attempts per contact per day (`CallAttemptRepository` has execution-scoped queries only — CONFIRMED by method list), no `dailyLimit` field on campaign or platform config, no reservation table for daily slots.

Attempt-history facts that shape the future policy:
- A "counted attempt" candidate boundaries that exist today: (a) attempt row created (QUEUED), (b) `IN_PROGRESS` + provider accepted (`DIAL_REQUEST_ACCEPTED`), (c) `CHANNEL_ANSWER`. Because requeues deliberately don't consume the attempt number, (b) — **provider-accepted dial** — is the only boundary where the platform has committed provider resources; recommendation: count at (b), do not count requeued/capacity-rejected attempts. This is a recommendation, not an implementation (prompt §8).
- The listed call states are distinguishable: queued (row exists QUEUED), routing started (IN_PROGRESS, pre-routing), capacity rejected (requeue path), originate failed (`DIAL_FAILED` etc.), ringing (`RINGING`), answered (`ANSWERED`), busy/no-answer/rejected (dial results + hangup causes), hangup (`COMPLETED`/`FAILED`). **Provider-side ringing-vs-answered and all intermediate states are persisted on CallSession, not CallAttempt.**

## 11. DTMF / IVR

**CONFIRMED FROM CODE** (`voice/dtmf/`): full single-level flow — `DtmfConfig` (strict parser), `DtmfInteraction` (persisted **snapshot** of config per call; `DtmfConfig.of(interaction)` rebuild guarantees mid-call type_config changes cannot affect a live interaction), `DtmfResultService` (+`abandonIfCollecting` on hangup), `DtmfResultType` (VALID/INVALID/timeout/abandoned), `DtmfTimeoutScheduler` (poll), `CHANNEL_DTMF` handling in `EslEventService`, idempotent transitions. Action: TERMINATE or CONNECT_BY_AGENT (VB-3 bridge). Invalid input is **terminal — no retry** ("No IVR framework: one expected sequence, one timeout, explicit invalid-input behavior").

Multi-level evolution (L1→L2→L3): would require a tree model. **Recommendation (from code, not implemented): a separate reusable IVR tree entity referenced by campaign (`ivrTreeId`) — embedding it in `typeConfig` would forfeit the snapshotting/reuse/idempotency properties `DtmfInteraction` already demonstrates, and `DtmfConfig`'s strict single-sequence parser cannot represent a tree.**

## 12. Agent / CONNECT_BY_AGENT

**CONFIRMED FROM CODE** (`voice/agent/`, VB-3/4A): `Agent` (adminStatus ACTIVE/SUSPENDED/DISABLED-terminal; availability AVAILABLE/OFFLINE + **BUSY owned by reservation lifecycle, not settable**), `AgentEndpointEntity` (SIP/EXTERNAL_FORWARD dialability validation), `AgentReservationService` (PostgreSQL advisory lock `0x3…` keyspace; RESERVED→ACTIVE conditional UPDATE; V40 partial unique index = one live reservation per session), `AgentConnectEvents` (ringing/answered/hangup/bridge-confirmed), `AgentConnectTimeoutScheduler` (5s), `AgentStaleReservationReconciler` (60s), `AgentCallQueryService`, bridge via `CHANNEL_BRIDGE`/`Bridge-B-Unique-ID`. Campaign side: `ConnectByAgentService` reserves then originates the agent leg.

**Invariant "agent with active call cannot be deactivated" — NOT enforced.** `AgentDirectoryService.updateStatus` (L174–204) performs pure admin-status transitions with **no active-call/reservation check**; `deactivateEndpoint` (L392) soft-deletes an endpoint with no check. Active-call state exists (`callLegRepository` active-leg counts are used for concurrency caps, L277/L467) but is not consulted on deactivation. Consequence: deactivating an agent mid-call succeeds; the in-flight reservation completes, future dials fail dialability. INFERRED: safe-but-surprising; the intended invariant needs a guard.

## 13. Queue / ACD

**CONFIRMED FROM CODE** (`voice/queue/`, `voice/acd/`): `Queue`, `QueueMembership` (unique live memberships), `QueueWaitingCall` (WAITING→ASSIGNED **atomic claim**, partial unique live-row index), `QueueDirectoryService/Controller`, `AcdService` (advisory lock assignment, V38), `AcdMaintenanceScheduler` (15s), `InboundAcdRetryScheduler` (5s). All inbound/contact-center oriented.

Campaign relationship: **Campaign references none of these.** CONNECT_BY_AGENT campaigns resolve an agent directly through `AgentReservationService`; there is no campaign→queue, no agent-pool reference, no ACD participation for outbound attempts. Queue-based campaigns would be a NEW integration (feasible: `QueueWaitingCall` is already session-linked via FK).

## 14. MISSED_CALL Readiness

**NOT PRESENT** — `CampaignType` has 3 values; zero MISSED_CALL references in main code (grep: only an unrelated javadoc word).

Feasibility from existing parts — CONFIRMED building blocks: `OutboundDialService` originate pipeline, `CallSessionStatus.RINGING`, ESL `CHANNEL_PROGRESS`, hangup cause 19 `NO_ANSWER`, `PlaybackTrigger` list pattern (a MISSED_CALL trigger would simply no-op; "exactly one trigger acts" per type is the established pattern). Gap: the dial path treats NO_ANSWER as FAILED (`markFailed(attempt, "NO_ANSWER", …)`); MISSED_CALL needs NO_ANSWER reclassified as the **success** outcome plus guaranteed no media/no bridge. Clean support requires a campaign-type branch in outcome mapping — no architectural obstruction found.

## 15. Compliance / Whitelist / DND

**CONFIRMED FROM CODE** (`telephony/PhoneList*`, `VoiceEligibilityService`):
- `PhoneListEntry` with 6 types: PLATFORM_BLOCKLIST, PLATFORM_PROTECTED, RESELLER_BLOCKLIST, RESELLER_WHITELIST, TENANT_BLOCKLIST, TENANT_WHITELIST; 3 scopes; documented precedence (enum javadoc); E.164-normalized; duplicate-active-entry rejected; soft delete.
- Enforcement: **dial-time, every attempt** — `VoiceEligibilityService.evaluate` precedence 1–4 (platform block → platform protected → reseller block → tenant DNC) before anything else. Every retry re-enters `processAttempt` → eligibility, so **retries cannot bypass**. Another campaign cannot bypass (checks are platform/reseller/tenant-scoped, not campaign-scoped). `callOnWhitelistNumbers=true` *restricts* to TENANT/RESELLER_WHITELIST (step 5) — an opt-in restriction, never a bypass. Campaign membership check is separate (`NOT_IN_CAMPAIGN_TARGETS`).
- DND == TENANT_BLOCKLIST (same list, `DNC_BLOCKED` code). No opt-out tracking per contact, no consent metadata.

## 16. Webhooks

**Configuration concept only — CONFIRMED**: `integrationConfig` JSONB exists on the entity and DTOs ("Optional API/webhook integration configuration; no secrets" — `CampaignEntity` L102, `CreateCampaignRequest` L39, `UpdateCampaignRequest` L37). **NOT PRESENT**: webhook entity, endpoint/secret/event-selection model, delivery client, delivery queue, retry mechanism, HMAC signing, idempotency keys, delivery logs. Zero consumers of `integrationConfig` (grep). Any event set (ALL/ANSWERED/NOT_ANSWERED/VALID_INPUT) is entirely future work.

## 17. Privacy

**Log-masking only — CONFIRMED**: `maskNumber`/`maskUuid` exist in `EslClient` (L214/226/402), `NoOpAgentLegDialer`, `FreeSwitchVoiceMediaController`, `FreeSwitchAgentLegDialer` — all **log lines only**. `CallSession.destinationNumber` stores the raw E.164; reports/queries expose it. **NOT PRESENT**: hide-dialed-number, hide-calling-number, role-based number visibility, presentation-layer masking, tenant/reseller report redaction. Masking today would be a presentation/report concern layered over raw storage — architecture supports it (raw vs. presentation separation possible) but nothing exists.

## 18. Maximum Call Duration

**NOT PRESENT.** No campaign field (`CampaignEntity` field list confirmed), no `CallSession` timer, no ESL schedule-hangup usage. `EslClient` has only socket-level connect/command timeouts (`FreeSwitchProperties.connectTimeoutSeconds/commandTimeoutSeconds`). The `PlaybackTrigger`/`mediaController.terminateCall(sessionId, providerCallId)` boundary is the natural future enforcement point (campaign config → execution timer → telephony terminate). Design doc `campaign-readiness.md` L574 describes the intent; code has none of it.

## 19. Configuration Freeze / Versioning

**CONFIRMED FROM CODE**: No freeze, no snapshot, no revision history. `version Integer = 1` is a manual clone-lineage marker (`clonedFromCampaignId`), **not** `@Version` optimistic locking (base entities have none). Executions re-read the live campaign every tick (`startExecution`, `processRetriesForExecution`, `OutboundDialService.processAttempt` all `findByIdAndTenantIdAndDeletedAtIsNull` at call time).

Behavior while running (INFERRED from those call sites):
- DID change → next attempt dials the new DID (retry path re-validates; dial path uses `attempt.getDidId()` snapshotted onto the attempt row at creation — attempts created *before* the change keep the old DID, new retries get the new one).
- Audio/TTS/retry-policy/schedule change → take effect at the next attempt/retry (no snapshot except DTMF: `DtmfInteraction` snapshots config per call — the one existing freeze mechanism).
- Audience change (group contents) → `createInitialAttempts` already ran; new contacts are **not** picked up mid-execution; deleted contacts fail retry validity check.
- Optimistic-locking collisions: none (no `@Version`); concurrent updates last-write-wins.

Intended Draft→Validated→Activated→frozen/versioned lifecycle has only the status skeleton (DRAFT/ACTIVE/PAUSED/…); the freeze/versioning substance is missing.

## 20. Scheduler Dependencies

**CONFIRMED FROM CODE** — 9 `@Scheduled` pollers (`@EnableScheduling` on `ObdApplication`; tests disable via `spring.scheduling.enabled=false`):

| Scheduler | Interval | Purpose |
|---|---|---|
| `CampaignExecutionOrchestrator.scheduledTick` | 30s | start executions, retries, dial due attempts, ESL pump, reconcile |
| `EslEventScheduler` | 60s | ESL event loop |
| `VoiceCapacityServiceImpl` | 60s | capacity reconciliation |
| `AcdMaintenanceScheduler` | 15s | ACD maintenance |
| `AgentConnectTimeoutScheduler` | 5s | connect timeouts |
| `AgentStaleReservationReconciler` | 60s | stale reservations |
| `DtmfTimeoutScheduler` | poll | DTMF timeouts |
| `InboundAcdRetryScheduler` | 5s | inbound retry |
| `SecurityCleanupScheduler` | cron 3am | token cleanup |

Reusable: poller convention, idempotent tick design, advisory locks (`VoiceCapacityServiceImpl`, `AgentReservationService`, `AcdService`, `AudioUploadValidator`), conditional-UPDATE claims, partial unique indexes (V40, queue live-row), execution `idempotencyKey`.
Not reusable for scale: the campaign tick is a **single-JVM poller with no leader election/distributed lock** — safe today, unsafe for a multi-instance scheduler; `scheduledTick` wraps everything in one try/catch with one `@Transactional` per method (per-step transactions differ — retries and dialing are separately transactional, which is correct). The future scheduler must add instance-coordination (DB-based, per the no-new-infra rule) before horizontal scale.

## 21. Tenant / Reseller Authorization

**CONFIRMED FROM CODE**: uniform pattern `authorizationService.requireCapability(userId, CAP_X, AccessCheck.forTenant(tenantId))` + scope resolution (`OrganizationContextHolder` → tenant/reseller/platform Scope records) in `CampaignService`, `CallAttemptService`, `CampaignExecutionOrchestrator`, `CampaignReadinessService`, agent/queue/contact services. Reseller visibility is **hierarchy-bounded** (`tenantRepository.findAllByResellerIdAndStatus(resellerId, ACTIVE)` → `findByIdAndTenantIdInAndDeletedAtIsNull` — VB-5F fix pattern) and empty→404. Platform scope sees all (`findByIdAndDeletedAtIsNull`).

Per-resource scoping: Campaign (tenant column + scoped finds), Contact/Group (tenant, group FK), DID (tenant-owned, checked at eligibility), Audio (tenant-owned, validated), TTS (tenant + GLOBAL scope, V42), Agent/Queue (tenant). Cross-tenant risks found: **none new** — `CallAttemptService` explicitly re-checks contact.tenantId == execution.tenantId; DID eligibility rejects non-owned DIDs. Note (documented, not fixed): `OutboundDialService.buildDestinationNumber` and `CallEligibilityService` load contact by id/group without tenant predicate — they operate on tenant-validated attempt/campaign context, so INFERRED safe, but a defense-in-depth tenant predicate would harden them.

## 22. Architecture / Modulith

**CONFIRMED FROM CODE**: `ArchitectureTest` passes (post-VB-5G: 0 cycles). `package-info.java` modules: account, audio, authz(context/home), campaign, common, contact, did, reseller, telephony, tenant, tts, voice (+ named interfaces `outbound`, `inbound`, and — VB-5G — `call`, `agent`, `media`, `routing`, `dtmf`, `capacity`, `eligibility`).

Actual dependency edges (from imports):
```
Campaign ──→ voice.{call, media, routing, capacity, eligibility, agent, dtmf, outbound}
        ──→ contact, audio, tts, did (via CampaignResourceValidationService)
        ──→ tenant, authz, common
telephony ──→ campaign (CallAttempt*, CallEligibility impl, EslEventService mutates attempts)
         ──→ voice.{media, call, routing, capacity, dtmf, agent, inbound, outbound, eligibility}
voice.agent/queue/acd/inbound ──→ voice.call, telephony (dialer interfaces via voice.media ports)
contact, did, audio, tts, tenant ──→ common/authz only (leaf domains)
```
Key port/adapter seams (all post-VB-5G): `CallEligibility` (campaign) ← `CallEligibilityService` (telephony); `OutboundDialer`/`VoiceMediaController`/`AgentLegDialer` (voice.media) ← FreeSWITCH/NoOp (telephony); `GatewayRoutingPort` (voice.routing) ← `SipGatewayRoutingAdapter` (telephony); `EslEventProcessor` boundary into campaign. Future-cycle risks: campaign↔telephony is the load-bearing pair (telephony writes campaign state) — any new campaign→telephony direct dependency would re-create the VB-5G cycle; use voice-owned ports.

## 23. Database / Migration Analysis

**CONFIRMED**: V42 head. Campaign-relevant: V9 (original), V14 (rebuild — current campaign shape), V15 (lifecycle/scheduling columns), V16 (dids), V17 (contact domain, E.164 CHECK), V18 (optional names), V19 (contact dedup partial unique), V34 (`PLAYING` enum value addition — precedent for enum extension), V35 (dtmf_interaction), V36 (agent connect), V37 (queue foundation), V38 (acd), V39 (inbound did destination), V40 (reservation uniqueness), V41 (did allocation source), V42 (tts scope).

Future-change safety (per §27 template):
- Additive columns/indexes (daily-limit counters, ivr_tree_id, webhook tables, privacy flags): **safe additive**, no backfill needed unless defaults must differ per row.
- New `CampaignType.MISSED_CALL`: Postgres named-enum or varchar addition — V34 precedent exists; safe additive; verify enum column type first (campaign uses varchar(30) per entity mapping — CONFIRMED `@Column(length=30)` with `EnumType.STRING` for campaign_type → plain ALTER-compatible).
- JSONB `type_config` shape changes: no migration, but requires parser versioning (DTMF's strict parser will reject old payloads — mid-flight risk).
- Backfill-requiring: attempt-history counters for daily limits on existing data; contact-level aggregate columns if introduced.
- Rollback risk: enum value removals (avoid); JSONB shape narrowing (avoid).

## 24. API Compatibility

**CONFIRMED** (18 endpoints, §3). Request/response DTOs in `campaign/dto` (`CreateCampaignRequest`, `UpdateCampaignRequest` carry `typeConfig`/`integrationConfig` as opaque JSON). Error contract: `ApiResponse` envelope + ProblemDetail (`RestAuthErrorHandling`), 404 on scope-miss, 409 on conflicts (`ConflictException` for duplicate attempt/terminal transitions). Backward-compatibility outlook: additive campaign types, additive typeConfig keys, new sub-resources (audience, webhooks) are non-breaking; breaking risks are (a) changing `typeConfig` semantics for existing types (DTMF parser is strict — new keys must be optional), (b) tightening validation on existing fields, (c) altering readiness codes consumers may branch on. New capabilities (daily limit, privacy, max duration) fit as additive request fields + response fields.

## 25. Test Coverage

**CONFIRMED** categories (855 tests): Unit (services, parsers — e.g. `OutboundDialServiceRoutingTest`, `PlayfileRetrySemanticsTest`, `VoiceRoutingServiceTest`), PG integration via Testcontainers (`AgentOutboundIntegrationTest`, `QueueFoundationIntegrationTest`, `AgentReservationUniquenessIntegrationTest`, `CampaignGovernanceHardeningPostgresIntegrationTest`, `VoiceEligibilityDidSemanticsTest`, `DidAllocationPostgresIntegrationTest`, `CampaignResourceValidationPostgresIntegrationTest`, `TtsGovernancePostgresIntegrationTest`, `AudioUploadPostgresIntegrationTest`), concurrency (`AgentReservationUniquenessIntegrationTest` — DB-level uniqueness under real PG), ESL (`AgentEslRoutingTest` + ESL service tests), Architecture (`ArchitectureTest`), Security (`SecuritySliceTest`), API slices.

Coverage per dependency: Campaign lifecycle/readiness/validation — covered. Tenant/reseller isolation — covered (VB-5F suite). Retry permanence — covered (`PlayfileRetrySemanticsTest`). Capacity/reservation atomicity — covered. **Not covered (feature absent, not test gap)**: daily limits, cross-execution attempt history, config-change-mid-execution, agent deactivation during active call, MISSED_CALL outcomes, webhook delivery, import above 5000 rows, privacy masking. **Partially covered**: DTMF (single-level only), queue (inbound flows only).

## 26. Capability Matrix

### 26.1 Campaign-type capability matrix

| Capability | PLAYFILE | DTMF | CONNECT_BY_AGENT | MISSED_CALL |
|---|---|---|---|---|
| Current support | Full runtime (VB-1): answer→play→teardown | Full runtime (VB-2/VB-3): prompt→collect→evaluate→terminate or bridge | Full runtime (VB-3): reserve→originate agent leg→bridge | **Missing** (type does not exist) |
| Existing entity fields | `contentMode=AUDIO`, `audioAssetId` | same + `typeConfig` JSONB | none type-specific (service-driven) | — |
| Existing typeConfig | none parsed (columns carry config) | `dtmf.{expected,maxDigits,terminator,timeoutSecs,action}` (strict parser) | none parsed | — |
| Validation | readiness `AUDIO_*` codes + runtime `PLAYBACK_CONFIG_INVALID` | readiness + parse-time strict + runtime `DTMF_CONFIG_INVALID` | runtime `AGENT_CONFIG_INVALID`/`AGENT_ENDPOINT_INVALID`/`AGENT_TENANT_MISMATCH` | — |
| Runtime support | `PlayfileExecutionService` + ESL playback events | `DtmfExecutionService` + `DtmfInteraction` snapshot + timeout scheduler + result service | `ConnectByAgentService` + `AgentReservationService` + bridge events | building blocks exist (originate, RINGING, NO_ANSWER cause) but NO_ANSWER is mapped to FAILED |
| Readiness support | DID+Audio validation | DID + content-mode asset validation | DID + agent config implied at dial | — |
| Missing pieces | none material | invalid/no-response audio, input retry, multi-level | active-call guard on deactivation; agent pool/queue targeting | type enum value, success-on-NO_ANSWER outcome mapping, readiness rules |

### 26.2 Platform capability matrix

| Capability | Exists | Partial | Missing | Existing Component | Future Phase |
|---|:---:|:---:|:---:|---|---|
| PLAYFILE | ✔ | | | `PlayfileExecutionService`, `AudioAssetEntity`, ESL playback events | — |
| DTMF | | ✔ | | `DtmfConfig`, `DtmfInteraction`, `DtmfResultService`, `DtmfTimeoutScheduler` | VB-6D (audio prompts, retry, multi-level) |
| CONNECT_BY_AGENT | ✔ | | | `ConnectByAgentService`, `AgentReservationService`, bridge events | deactivation guard |
| MISSED_CALL | | | ✔ | originate pipeline, `RINGING`, hangup cause 19 | VB-6E |
| DID/DNID | ✔ | | | `DidEntity`, `CampaignResourceValidationService.validateDid`, eligibility step 6 | — |
| Audio | ✔ | | | `AudioAssetEntity`, `LocalAudioStorage`, upload+approval | — |
| TTS | | ✔ | | `TtsTemplate` (V42 scope), readiness codes | synthesis runtime (out of scope until product decides) |
| Contact | | ✔ | | `ContactEntity` (E.164 CHECK, dedup V19) | VB-6B (identity, cross-campaign history) |
| Contact Group | ✔ | | | `ContactGroupEntity`, campaign `contact_group_id` | — |
| Inline numbers | | | ✔ | import endpoint is the only materialization path | VB-6B (direct/inline audience) |
| File import | | ✔ | | `ContactGroupService.importContacts` (CSV/JSON/XLSX, **sync, 5000-row cap, single tx**) | VB-6B (async, batching, >5M scale) |
| Daily call limit | | | ✔ | attempt history exists; **no counting, no enforcement, no atomicity for it** | VB-6C |
| Retry policy | | ✔ | | `RetryPolicySpec` (FIXED), permanent/temporary classification | VB-6C (taxonomy enum, per-case strategies) |
| IVR | | | ✔ | single-level DTMF only; snapshot pattern proven | VB-6D (IVR tree entity + `ivrTreeId`) |
| Agents | ✔ | | | `Agent`, endpoints, directory, reservation (advisory lock, V40) | deactivation invariant |
| Queues | | ✔ | | `Queue`, `QueueMembership`, `QueueWaitingCall`, `AcdService` (inbound-only) | outbound queue campaigns (post-6G) |
| Webhooks | | | ✔ | `integrationConfig` JSONB placeholder only | VB-6F |
| Privacy | | | ✔ | log-masking only | VB-6F |
| Max duration | | | ✔ | `terminateCall` boundary exists | VB-6F |
| Scheduler | | ✔ | | `scheduledTick` (30s) + 8 pollers; idempotent; **single-JVM** | VB-6G (coordination, scale) |
| Configuration freeze | | | ✔ | status skeleton; DTMF per-call snapshot is the only existing freeze | VB-6A (+ snapshot design) |

## 27. Reuse / Extend / Refactor / New

| Component | Classification | Reason |
|---|---|---|
| `CampaignEntity` / `CampaignController` / `CampaignService` | **EXTEND** | Owns lifecycle/API; needs freeze, new types, additive fields |
| `CampaignExecutionOrchestrator` + `scheduledTick` | **EXTEND** | Working poller; becomes scheduler core (needs coordination) |
| `CallAttempt` + repo | **EXTEND** | Needs daily-limit counting support (contact+day index) and richer outcome codes |
| `OutboundDialService` | **EXTEND** | Dial pipeline correct; needs outcome-mapping branch for MISSED_CALL, max-duration hook |
| `CampaignResourceValidationService` | **REUSE** | Canonical resource validation boundary (VB-5E), all 6 codes in use |
| `VoiceEligibilityService`, `PhoneList*` | **REUSE** | Compliance layer complete; enforced at every attempt incl. retries |
| `DidEntity` + DID stack | **REUSE** | Allocation/assignment/eligibility complete |
| `AudioAssetEntity` + storage | **REUSE** | Governed resource with approval + storage abstraction |
| `TtsTemplate` | **REUSE** (config), runtime **NEW** | Governance exists; synthesis runtime does not |
| `DtmfConfig`/`DtmfInteraction` | **REUSE** (single level) | Strict parser + snapshot proven; tree is beyond its model |
| IVR tree | **NEW** | No reusable structure (§11) |
| `ContactEntity`/`ContactGroupEntity` | **EXTEND** | Solid identity/dedup; needs cross-campaign history view + audience membership |
| Contact import | **REFACTOR** | Sync/single-tx/5000 cap incompatible with large audiences |
| Inline/pasted numbers | **NEW** | No entity or path exists |
| `Agent`/`AgentReservationService`/`AgentDirectoryService` | **REUSE** + small **EXTEND** | Reservation machinery complete; add deactivation guard |
| `Queue`/`AcdService` | **REUSE** (inbound) | Outbound queue campaigns would be NEW integration |
| Daily limit | **NEW** | Nothing counts or enforces it (§10) |
| Webhooks | **NEW** | Concept only (§16) |
| Privacy masking | **NEW** | Log-only masking today (§17) |
| Max call duration | **NEW** | Absent (§18) |
| Freeze/versioning | **NEW** | Only DTMF per-call snapshot exists (§19) |
| MISSED_CALL type | **NEW** | Type + outcome semantics (§14) |

## 28. Dependency Graph

```
                     ┌────────────────────────────────────────────┐
                     │                  campaign                  │
                     │  Service/Readiness/Orchestrator/Attempt    │
                     │  PlayfileExec / DtmfExec / ConnectByAgent  │
                     └──┬───────┬───────┬───────┬───────┬───────┬┘
              (ports)   │       │       │       │       │       │
        ┌───────────────┘       │       │       │       │       └──────────┐
        ▼                       ▼       │       ▼       ▼                  ▼
     contact                  audio    │      did      tts             authz/tenant
        ▲                               │       ▲                          ▲
        │ (CallEligibility impl)        │       │ (validation)             │
     telephony ──► voice.{media,call,routing,capacity,dtmf,agent,
                    inbound,outbound,eligibility,queue,acd}
        │                    │
        └──► FreeSWITCH ESL  └──► (all voice sub-packages expose @NamedInterface)
```
Leaf domains (contact, did, audio, tts, tenant, reseller) depend only on common/authz. telephony→campaign is the one "upward" edge (ESL state writers); it is legal and cycle-free — keep all new campaign⇄telephony traffic on voice-owned ports.

## 29. Risks

1. **Daily limit (HIGH)** — absent today; the moment scheduler-driven retries scale, per-contact frequency becomes a compliance exposure. Attempt-history shape supports it; needs counted-attempt definition + atomic reservation (advisory-lock/conditional-UPDATE patterns already proven in-repo) + contact-day index.
2. **No config freeze (HIGH)** — mid-execution config changes silently alter behavior (except DTMF). Readiness is checked at execution start only.
3. **Scheduler scale (HIGH)** — single-JVM 30s poller, no coordination; horizontal deployment would double-dial without instance locking (idempotency constraints bound the damage but dialing is provider-visible).
4. **Import ceiling (MEDIUM-HIGH)** — 5000 rows, one transaction, in-memory dedup: memory + tx-duration risk beyond small audiences; no async/progress tracking.
5. **Agent deactivation invariant (MEDIUM)** — unenforced (§12); product-visible surprise.
6. **`typeConfig` strictness vs. evolution (MEDIUM)** — DTMF parser rejects unknown shapes; future keys must be additive and versioned.
7. **String-typed failure taxonomy (MEDIUM)** — retry/permanence logic keyed on scattered string codes (`isPermanentFailure`); enum consolidation recommended before per-case retry strategies.
8. **TTS expectation gap (MEDIUM)** — TTS campaigns can be configured/readiness-approved but cannot speak (no runtime); product decision required.
9. **Cross-execution contact history (LOW-MEDIUM)** — no index on `call_attempts.contact_id` alone; daily-limit and history queries will need it.

## 30. Open Design Decisions

| Decision | What code supports | What needs a product decision |
|---|---|---|
| Attempt definition | Attempt row (QUEUED), provider-accepted dial (IN_PROGRESS + `DIAL_REQUEST_ACCEPTED`), `CHANNEL_ANSWER` are all persisted boundaries; requeues explicitly do not consume attempt numbers | Which boundary counts toward the global 3/day (recommendation: provider-accepted dial) |
| Retry vs daily limit | Retries create new attempt rows; nothing links retries to a daily budget | Does a retry consume another daily attempt? |
| Audience ownership | Contacts are group-scoped; groups are tenant-scoped and reusable across campaigns | Are contacts tenant-global (shared directory) vs campaign-specific members? |
| Contact dedup | Uniqueness is `(contact_group_id, phone_number)` per group, deleted_at-scoped | What uniquely identifies a contact tenant-wide? |
| Phone number identity | One contact = exactly one `phone_number` (NOT NULL) | Can one Contact hold multiple numbers? |
| Campaign freeze | Only DTMF snapshots per call; everything else dynamic | When does config become immutable (activation? first execution?) and what is snapshotted? |
| Resource changes mid-execution | DID snapshotted onto attempt rows; audio/TTS/group evaluated live; invalid→FAILED/permanent codes | What happens when an assigned DID/audio/TTS becomes unavailable after activation — fail fast, pause, or skip contacts? |
| IVR reuse | DTMF interaction snapshot pattern proven; no tree entity | Reusable IVR tree referenced by `ivrTreeId` (recommended) vs typeConfig embedding |
| Agent deactivation | Status machine has no active-call check; active-leg counts available | Exact check (live reservation? active leg?) and behavior (reject vs defer) |
| Webhook delivery | Nothing exists | Sync vs async, at-least-once semantics, signing, event set |
| Large imports | Sync, single-tx, 5000-row hard cap | Intended operational maximum (1M–5M implied by design doc) and acceptance UX |
| MISSED_CALL semantics | NO_ANSWER currently = FAILED everywhere | Success definition (ring duration threshold? carrier NO_ANSWER only?) |

## 31. Recommended Implementation Sequence

Derivation from the actual dependency graph (audience before limits, limits and freeze before scheduler, scheduler last):

| Phase | Scope | Why here | Dependencies | Migrations | API impact | Tests | Risks |
|---|---|---|---|---|---|---|---|
| **VB-6A** | Campaign config hardening: freeze/versioning snapshot, typed config model, failure-taxonomy enum consolidation | Every later phase consumes stable config semantics; cheapest to do before new types/audience | none (existing schema) | additive (snapshot table / version column) | additive response fields | snapshot immutability, mid-execution change tests | parser back-compat |
| **VB-6B** | Audience: contact identity/audience membership, inline numbers, async import scaling | Scheduler quality depends on audience scale; import refactor is isolated | 6A (freeze for audience snapshot) | additive (membership, import-job tables; contact-day index groundwork) | new audience endpoints + import async contract | import scale, dedup, tenant isolation | migration size on contacts |
| **VB-6C** | Daily limit + retry governance: counted-attempt boundary, atomic per-contact-day reservation, per-case retry taxonomy | Must exist **before** the scheduler multiplies call volume (compliance) | 6B (contact-day identity, index) | additive (counters/reservations) | additive campaign limit fields | concurrency (parallel dials), min() semantics, retry-vs-limit | contention design |
| **VB-6D** | IVR/DTMF expansion: reusable IVR tree (`ivrTreeId`), multi-level, prompt audio | Builds on 6A config model; independent of limits | 6A | additive (ivr trees/nodes) | new tree CRUD APIs | tree traversal, snapshot per level | typeConfig back-compat |
| **VB-6E** | MISSED_CALL type | Small, self-contained; needs outcome-mapping seam from 6A taxonomy work | 6A | enum/column additive | new campaign type | outcome mapping, no-media invariant | carrier NO_ANSWER variance |
| **VB-6F** | Webhooks + privacy + max duration | Reporting/integration surface; benefits from stable event/outcome model | 6A (outcomes), 6C (limits) | additive (webhook tables) | webhook config APIs, report masking params | delivery retry/idempotency, masking RBAC, duration enforcement | delivery volume |
| **VB-6G** | Scheduler: coordination (DB lock/leader), scale, tuning | **Last** — only after all safety controls (limits, compliance, freeze) are in place | all above | none/locks table | none | multi-instance, duplicate-dial prevention | lock design (no new infra) |

This reorders the design-doc sketch (VB-6A..6G) in one meaningful way: **daily limits/compliance (VB-6C) is pulled ahead of IVR and MISSED_CALL** because it is compliance-critical and depends only on audience identity, while IVR/MISSED_CALL are feature additive and safe to follow.

## 32. Final Audit Conclusion

The platform is substantially further along than "campaign skeleton": three campaign types run end-to-end against FreeSWITCH (playback, DTMF, agent bridging), an execution poller with retry semantics and permanent/temporary failure classification exists, compliance is dial-time enforced at every attempt, and reservation/capacity/queue concurrency primitives (advisory locks, conditional updates, partial unique indexes) are proven under real PostgreSQL tests (855/0/0/1 baseline).

The genuine gaps are concentrated, not diffuse: **no daily contact limit (nothing counts attempts per contact per day), no configuration freeze/versioning (only DTMF snapshots), no scheduler coordination beyond one JVM, a synchronous 5000-row import ceiling, and five fully-absent capabilities (MISSED_CALL, IVR trees, webhooks, report privacy, max call duration)**. Reuse dominates the classification (13 REUSE / 5 EXTEND / 4 REFACTOR-adjacent / 6 NEW); no existing component needs replacement.

**Recommended next phase: VB-6A — Campaign domain/configuration hardening** (freeze/versioning + typed config + failure-taxonomy consolidation), because every subsequent phase — audience scale, daily limits, IVR, MISSED_CALL, webhooks, scheduler — consumes stable configuration semantics, and it requires no new domain dependencies.

---

### Implementation-agent summary

- **Current state**: 3 runnable campaign types, execution poller + retries + ESL lifecycle, E.164-enforced contact groups, complete compliance/eligibility/DID/audio governance, proven concurrency primitives. Baseline 855/0/0/1, Flyway V42.
- **Reusable as-is**: `CampaignResourceValidationService`, `VoiceEligibilityService`/PhoneLists, DID stack, Audio stack, TTS governance, DTMF single-level runtime, Agent reservation machinery, Queue/ACD (inbound), port/adapter dial pipeline.
- **Extend**: Campaign entity/API (freeze, types, limits), `CallAttempt` (counting, outcomes), `OutboundDialService` (outcome seam), orchestrator (scheduler core), contacts (audience), agent deactivation guard.
- **Refactor**: contact import (async/batched), failure-taxonomy strings → enum, config parsing (versioned).
- **New**: daily-limit enforcement, IVR tree, MISSED_CALL type, webhooks, privacy masking, max-duration enforcement, freeze/snapshot store, scheduler coordination.
- **Highest-risk areas**: daily contact limits, config freeze, scheduler concurrency at scale, audience import scale, agent deactivation invariant, TTS expectation gap.
- **Recommended next phase**: **VB-6A — Campaign domain/configuration hardening** (per §31).

**Audit complete — stopping here. No implementation was performed; production code, tests, and migrations are untouched.**
