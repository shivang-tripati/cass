# VB-6E PLAYFILE + Common Calling Configuration Audit

> **AUDIT ONLY.** No production code, migration, entity, API or test was modified in producing this
> document. The only artifact is this file. Nothing was staged or committed. The user's uncommitted
> `docs/campaign-readiness.md` was read but left untouched.

---

## 1. Executive Summary

The audit's headline is not a missing feature. It is that **the Voice Blast campaign execution
pipeline cannot currently complete a single call end-to-end**, for two independent reasons that are
each sufficient on their own:

1. **The FreeSWITCH ESL client is protocol-nonconformant in four ways.** It cannot complete a
   handshake, it drops every inbound event, and it binds the wrong identifier to an attempt. Verified
   by reading the code (§17.5). The repository's own reports state repeatedly that live FreeSWITCH
   E2E was *"NOT EXECUTED (no FreeSWITCH instance available)"* (`docs/VB-4D-…:150`, `docs/VB-4E-…:182`,
   `docs/VB-4F-…:292`, `docs/voice-routing-and-capacity.md:1069`), and `infra/docker-compose.yml`
   contains no FreeSWITCH while `infrastructure/docker`, `infrastructure/kubernetes` and
   `infrastructure/monitoring` are **empty directories**. There is **no** test anywhere in the
   repository that opens a socket.

2. **The scheduler's execution-start step can never succeed.** `scheduledTick`'s first step calls
   `startExecution`, whose first statement is `requireUserId()`; that reads
   `SecurityContextHolder`, which **nothing in `src/main` ever populates** and which is empty on a
   scheduler thread. The resulting exception is caught by a single `try` that wraps **all five**
   steps of the tick, so on any cycle containing a `REQUESTED` execution, **retries, dialing, the ESL
   pump and reconciliation are all skipped** (§13.2). Verified by reading the code.

Consequently "add max call duration" — the obvious reading of *common calling configuration* — is
not the first thing VB-6E should do. It is the fourth or fifth. The first is making the pipeline
function at all, and proving it with a test that actually talks to something.

The platform's *design* work, by contrast, is in good shape. The immutable execution snapshot, the
snapshot-vs-resource validation rule, tenant isolation, the VB-6C/VB-6D safety pair, the canonical
failure taxonomy, the retry authority, PostgreSQL atomic admission, and campaign-resource governance
are all well-built, documented and genuinely well-tested (**1327 tests, 0 failures**). The gap is
almost entirely in the *telephony adapter* and in *three absent safety behaviours* (max call
duration, stuck-attempt recovery, honoured pause), not in the campaign domain.

**Scope note (must be resolved before implementation).** The repository contains **four different
definitions of VB-6E**:

| Source | Says VB-6E is |
|---|---|
| This brief | PLAYFILE + Common Calling Configuration |
| `docs/VB-6D.3-IMPLEMENTATION-REPORT.md:351` | PLAYFILE + Common Calling Configuration |
| `docs/VB-6-CAMPAIGN-AUDIT.md:257,363` | `MISSED_CALL` campaign type |
| `docs/campaign-readiness.md:813` (user's uncommitted edit) | `CONNECT_BY_AGENT` campaign configuration |

This brief governs and is self-consistent: §26 forbids new campaign types and forbids a
`CONNECT_BY_AGENT` redesign, which excludes the other two readings outright. **The roadmap document
is stale on three counts** (it also still says VB-6D is IVR/DTMF, and VB-6F is `MISSED_CALL`) and
should be corrected. It is the user's file and was not edited.

---

## 2. Baseline

| Item | Value |
|---|---|
| Commit | `0dfe308` — "VB-6D.3: campaign daily-attempt safety and final retry integration" |
| Branch | `main`, **in sync with `origin/main`** (0 commits ahead/behind) |
| Working tree | **1 modified file**: `docs/campaign-readiness.md` (user's uncommitted IVR roadmap note) — **preserved untouched** |
| Migration head | **V51** (`V51__campaign_daily_attempt_limit.sql`); 50 migration files total |
| Full suite | **1327 tests / 0 failures / 0 errors / 1 skipped** — `BUILD SUCCESS` |
| Skipped test | `ObdApplicationTests.contextLoads` — pre-existing `@Disabled`, unrelated |
| Architecture | `ArchitectureTest` **1 / 0 / 0 / 0**, `ApplicationModules.verify()` → **0 cycles** |
| Modules | 15: account, audio, authz, campaign, common, contact, did, identity, reseller, security, telephony, tenant, tts, voice |
| Pre-existing failures | **none** |
| Build facts | `pom.xml`: Spring Boot **4.1.0**, **Java 17** (not 21), Spring Modulith 2.1.0, Jackson 3 |
| Active profile | `application.yaml:6` → `${SPRING_PROFILES_ACTIVE:dev}` — **`dev` is the shipped default** |
| Telephony default | `application-dev.yaml:46` → `telephony.freeswitch.enabled: false` → **NoOp dialer + NoOp media controller** |
| Audio storage default | `application-dev.yaml:60` → `audio.storage.enabled: ${AUDIO_STORAGE_ENABLED:true}` → `LocalAudioStorage` **is** active by default |

---

## 3. Existing PLAYFILE Architecture — the real runtime flow

Derived from the code, not assumed. File:line citations throughout.

```
POST /api/v1/campaigns/{id}/executions                    CampaignExecutionService.execute:57
  ├─ requireUserId()                                        :58   ← HTTP thread: OK
  ├─ requireCapability(CAMPAIGN_EXECUTE)                    :62
  ├─ readinessService.evaluate(campaignId)                  :65   ← also needs a user
  ├─ configurationService.createExecutionSnapshot(campaign)  :87   ← FREEZE happens here
  └─ executionRepository.save(execution) → status REQUESTED  :100

… every 30 s …
CampaignExecutionOrchestrator.scheduledTick()               :376  @Transactional, one transaction
  ├─ 1. startExecution(REQUESTED)                            :383-385
  │      └─ requireUserId()                                  :96   ✗✗ FAILS — no SecurityContext on a
  │                                                                    scheduler thread (§13.2)
  │            (and the throw skips steps 2-5 for the whole cycle)
  ├─ 2. processRetries()                                     :388
  │      └─ for FAILED attempts: RetryPolicyService.evaluate :252 → RetryDecision
  │         isContactStillValid / isDidStillValid            :276,:281
  │         create attempt N+1                               :291
  ├─ 3. dialService.processDueAttempts()                    :391  @Transactional → JOINS the tick tx
  │      └─ processAttempt(attempt)                          :116
  │          1. status must be QUEUED                         :117
  │          2. live campaign lookup (existence only)        :131
  │          3. resolveExecutionConfig → IMMUTABLE SNAPSHOT   :141
  │          4. resolveUsageDate(snapshot timezone)          :158  VB-6C  (no fallback → fails closed)
  │          5. buildDestinationNumber → contact phone (LIVE):171
  │          6. eligibility: blocklists/DNC/DID/gateway/capacity :184  CallEligibilityService
  │          7. status = IN_PROGRESS                         :205
  │          8. routing → VoiceRoute (may SUBSTITUTE the DID) :216-247
  │          9. VB-6C admit  (tenant, contact, actualDNID, day):262
  │         10. capacity reserve                             :276
  │         11. VB-6D.3 admit (tenant, contact, day)          :310  consume-at-dispatch
  │         12. dialer.dial(routedRequest)   ← BLOCKING NETWORK I/O INSIDE THE TX  :331
  │             └─ EslClient.originate → "bgapi originate …"  EslClient:211
  │                ✗✗ reply is "+OK Job-UUID: <job>", but the code reads parts[1] as the
  │                    channel UUID (EslClient:220-227) — a job UUID, not a channel UUID
  │                    ✗✗ and auth already failed at EslClient:67 (see §17.5)
  ├─ 4. eslEventProcessor.ensureEventProcessing()            :394
  └─ 5. reconcileExecution(RUNNING)                          :397-401

… inbound, event-driven …
EslEventScheduler.ensureEventProcessing() @Scheduled(60s)   EslEventScheduler:40
  └─ EslClient.connect() + subscribeAndProcessEvents(...)    :58-60   (one daemon thread)
       ✗✗ connect() reads the pending auth banner → fails    EslClient:64-69
       ✗✗ event framing drops every event                   EslClient:127-181

If it did connect (it does not):
  CHANNEL_ANSWER      → EslEventService:158  → session ANSWERED → PlaybackTrigger.onAnswered
  PlayfileExecutionService.onAnswered                        :92
    ├─ session must be ANSWERED                             :106
    ├─ campaign lookup, tenant-scoped                        :122
    ├─ config from the SNAPSHOT (never the live campaign)    :134
    ├─ must be PLAYFILE                                      :141
    ├─ must be contentMode == AUDIO and audioAssetId != null :148  ✗ TTS ⇒ PLAYBACK_CONFIG_INVALID
    ├─ resourceValidator.validateAudio(id, tenant)           :162  APPROVED + storageReference
    ├─ load asset (tenant-scoped)                            :188
    └─ mediaController.playAudio(session, leg, storageReference)  :196
         └─ EslClient.playFile → "uuid_broadcast <uuid> <storageReference> aleg"  EslClient:273
            ✗✗ storageReference is the LOGICAL string "audio/<tenant>/<asset>/<file>.wav"
                (LocalAudioStorage:76) — never translated to a FreeSWITCH-readable path
  PLAYBACK_START       → session ANSWERED→PLAYING            EslEventService:259
  PLAYBACK_STOP        → session PLAYING→PLAYBACK_COMPLETED   EslEventService:276
    └─ PlayfileExecutionService.onPlaybackCompleted           :215
         └─ mediaController.terminateCall → "uuid_kill <uuid> NORMAL_CLEARING"  EslClient:287
  PLAYBACK_ERROR       → session.failureCode=PLAYBACK_FAILED  EslEventService:371
  CHANNEL_HANGUP       → attempt COMPLETED | FAILED          EslEventService:489-499
```

### 3.1 Where the two hard stops are

| Stop | Code | Effect |
|---|---|---|
| `connect()` reads the unprompted `Content-Type: auth/request` banner, then tests `startsWith("+OK")` | `EslClient.java:64-69` | `EslException("Authentication failed: Content-Type: auth/request")` on the **first command** |
| `startExecution` → `requireUserId()` → empty `SecurityContextHolder` | `CampaignExecutionOrchestrator.java:96,569-574` | `BusinessException(UNAUTHORIZED)`; single `catch` at `:402` swallows it and **skips steps 2-5** |

---

## 4. Existing PLAYFILE Implementation — what already exists

VB-1 delivered a genuinely complete campaign-side PLAYFILE flow. Do not rebuild any of it.

| Component | State | Evidence |
|---|---|---|
| `PlayfileExecutionService implements PlaybackTrigger` | complete, 3 collaborators lazily injected | `PlayfileExecutionService.java:45` |
| PLAYFILE-only gating | enforced and pinned | `:141-145`; `PlayfileExecutionServiceTest.p2_nonPlayfileNeverPlays` |
| AUDIO-only gating | enforced and **pinned as intended** | `:148-154`; `PlayfileExecutionServiceTest.ttsContentModeRejected` |
| Asset validation via the canonical boundary | complete | `:162` → `CampaignResourceValidationService.validateAudio` |
| Tenant isolation on the asset | complete, 3 layers + 9 tests | `:163,:190`; `PlayfileExecutionServiceTest.p24_crossTenantAssetRejected` |
| Snapshot-only configuration read | complete, no live fallback | `:134-138`, `:266-275` |
| Idempotent answer trigger | complete | `:106-110`; `duplicateTriggerNoOp` |
| Idempotent completion trigger | complete | `:226-228` |
| `PLAYBACK_CONFIG_INVALID` (PERMANENT) vs `PLAYBACK_FAILED` (TEMPORARY) | canonical, distinct, tested | `CallFailureCode:89,92`; `PlayfileRetrySemanticsTest` (7) |
| Session→attempt failure propagation | correct and deliberate | `EslEventService:477-487` reads `session.getFailureCode()` before deciding success |
| Media boundary abstraction | `VoiceMediaController` with 7 methods, 2 impls | `voice/media/VoiceMediaController.java:14` |
| FreeSWITCH media adapter | real ESL commands, correctly built | `FreeSwitchVoiceMediaController.java:40` |

**Assumptions later phases changed underneath VB-1 — all beneficial, none broken:**

| VB-1 assumption | Now | Evidence |
|---|---|---|
| Config read from the live campaign | snapshot only | `CampaignRuntimeConfigResolver.resolve` `:39-43` |
| Retry reads `attempt.failureCode` only | unchanged, but now a *pure* authority | `RetryPolicyService` (VB-6D.2) |
| One dial per attempt | two daily ceilings gate dispatch | `DailyDialLimitService` (VB-6C), `DailyAttemptSafetyService` (VB-6D.3) |
| Asset approval checked at dial | unchanged, plus canonical codes | `CampaignResourceValidationService` (VB-5E) |

---

## 5. Audio Resource Integration

| Aspect | Reality | Evidence |
|---|---|---|
| Entity / table | `AudioAssetEntity` / `audio_assets` | `AudioAssetEntity.java:28,62`; `V20:10` |
| Fields | tenantId, name, description, fileName, contentType, fileSize, **durationSeconds (nullable)**, **checksum (nullable)**, **storageReference (nullable)**, status | `AudioAssetEntity.java:30-62` |
| Status | `PENDING_APPROVAL` / `APPROVED` / `REJECTED` only; no ARCHIVED, no separate readiness field | `AudioAssetStatus.java:8-12` |
| Transitions | approve / reject, self-transition → 409. `update` does **not** touch status | `AudioAssetService.java:265-277`, `:121-130` |
| Soft delete | `deleted_at` + `deleted_by`; **every** query filters explicitly (no Hibernate filter) | `AudioAssetService.java:132-144`; `AudioAssetSpecifications.notDeleted():16` |
| Storage | **local filesystem only** — `LocalAudioStorage` under `{base}/{tenant}/{asset}/{uuid}.{ext}` | `LocalAudioStorage.java:51-56` |
| Logical reference | `"audio/" + tenantId + "/" + audioAssetId + "/" + fileName` | `LocalAudioStorage.java:76` |
| **Storage reference format validation** | **NONE** | `CreateAudioAssetRequest.java:34` is `@Size(max=500)` only |
| Checksum | real SHA-256, **upload path only** | `AudioUploadValidator.sha256Hex:152`; `AudioAssetService.java:212` |
| Duration | hand-rolled RIFF parser, **WAV only**, null on parse failure and for sub-second; **MP3 never parsed** | `AudioUploadValidator.java:172-210`, `:96-98` |
| Content validation | MIME allow-list + **magic-byte signature** (WAV `RIFF…WAVE`, MP3 `ID3`/frame-sync) + size cap | `AudioUploadValidator.java:39-41,83-86,110-127` |
| Approval required to play | yes — enforced at write, activation, readiness **and** runtime | `CampaignService:123,475`; `CampaignReadinessService:320`; `PlayfileExecutionService:162` |
| TTS | governance only; **no synthesis, no runtime consumer** | `TtsTemplateEntity:25-26`; `tts/package-info.java:4` |

### 5.1 🔴 Finding A-1: the metadata-only registration path is an unvalidated path injection into FreeSWITCH

`POST /api/v1/audio-assets` (`AudioAssetController.java:50-56`) accepts a **client-supplied
`storageReference`** with no format validation (`CreateAudioAssetRequest.java:34` →
`AudioAssetMapper.java:22`). `AUDIO_MANAGE` is granted to `TENANT_ADMIN` (`V1:243-256`). After
`PATCH /{id}/approve`, that string passes `validateAudio` (which only checks non-blank,
`CampaignResourceValidationService.java:148`), passes readiness, and reaches
`uuid_broadcast <channel> <attacker-string> aleg` verbatim
(`PlayfileExecutionService.java:196` → `FreeSwitchVoiceMediaController.java:55` → `EslClient.java:273`).

The upload path cannot do this. **Untested.** Severity: high once the ESL layer works (§17).

### 5.2 🟠 Finding A-2: audio REST/OpenAPI have zero test coverage

No test for `create`, `getById`, `list`, `update`, `delete`, `approve`, `reject`. No slice test for
`/api/v1/audio-assets/**`. No OpenAPI contract test for audio or TTS — the only two in the repo are
`CampaignOpenApiContractTest` and `ContactGroupMemberOpenApiContractTest`. Severity: medium.

### 5.3 🟡 Finding A-3: `409` documented, `500` delivered for storage failure

`AudioAssetController.java:85` documents `409 "Audio storage is disabled"`, but
`AudioStorageException extends RuntimeException` (`AudioStorageException.java:4`) and
`GlobalExceptionHandler` has no handler for it → falls to
`@ExceptionHandler(Exception.class)` → **500** (`GlobalExceptionHandler.java:107-111`).
Severity: low (documented-wrong).

### 5.4 🟡 Finding A-4: no physical-file deletion on soft delete; MP3 duration never populated

Orphaned files accumulate; no GC. `durationSeconds` is permanently null for MP3, so any future
duration-based rule would silently not apply to MP3 assets. Severity: low.

---

## 6. Execution Snapshot Semantics

**The snapshot architecture is the strongest thing in the codebase and needs no work.**

| Property | Status | Evidence |
|---|---|---|
| Snapshot created **before** the execution row, same transaction | yes | `CampaignExecutionService.java:87-100` |
| Materialisation through a package-private factory only | yes | `CampaignExecutionConfiguration.materialize:64-75` |
| Hibernate `@Immutable` + every column `updatable = false` | yes | `CampaignExecutionConfiguration.java:43,48,52,60` |
| `configuration_snapshot_id` **NOT NULL** + FK | yes | `V44:61-67` |
| An execution without a snapshot is refused **by the database** | yes, tested | `CampaignConfigurationSnapshotPostgresIntegrationTest.executionWithoutSnapshotIsRefusedByDatabase` |
| Campaign edit cannot change a running execution | yes, tested on real PG | `…PostgresIntegrationTest.campaignEditAffectsOnlyFutureExecutions`, `runningExecutionKeepsSnapshot` |
| Foreign snapshot unresolvable, no live fallback | yes, tested | `…PostgresIntegrationTest.foreignSnapshotIsUnresolvable` |
| DID validity stays **dynamic** | by design, documented | `CampaignConfigurationSnapshot.java:38-39`; `CampaignRuntimeConfigResolver.java:45-50` |

### 6.1 Resource semantics (§8 of the brief) — the answer

| Moment | Behaviour | Enforced by |
|---|---|---|
| Campaign created | asset must exist, be tenant-owned, be APPROVED | `CampaignService.validateContentReferences:123,540-554` |
| Asset later edited | name/description only; **status untouched** | `AudioAssetService.update:121-130` |
| Asset later **rejected** | campaign fails readiness; **running execution unaffected** (only the id is frozen) | `CampaignReadinessService.checkAudioAsset:320` |
| Asset later **soft-deleted** | `validateAudio` → `AUDIO_NOT_AVAILABLE`; **runtime** rejects with `PLAYBACK_CONFIG_INVALID` (PERMANENT) | `CampaignResourceValidationService.java:139`; `PlayfileExecutionService.java:166-171` |
| Execution created | snapshot stores the **id only** | `CampaignConfigurationService.java:108` |
| Execution starts | readiness re-evaluated | `CampaignExecutionOrchestrator.java:115-122` |

So the rule *"the snapshot pins WHAT was requested; resources stay dynamically validated"* is
implemented exactly as intended, at four independent layers. **No gap. No versioning invented. No
gap to close.**

**Minor coverage gap:** the "asset soft-deleted after snapshot" case is proven at the validator level
(`CampaignResourceValidationServiceTest.missingAssetIsNotAvailable`) but not through
`PlayfileExecutionService`.

### 6.2 🟡 Finding S-1: `configuration_snapshot_id` has no UNIQUE constraint

`V44:62` adds a plain FK; `idx_campaign_executions_configuration_snapshot` (`V44:70`) is
**non-unique**. Nothing in code prevents two executions sharing one snapshot row. The invariant is a
convention, not a constraint. Severity: low (no current code path violates it), but it is the one
integrity rule in an otherwise tightly-constrained schema that the database does not enforce.

---

## 7. Common Calling Configuration Inventory

| Configuration | Current owner | Persisted where | Snapshot? | API? | Validation? | Campaign types |
|---|---|---|---|---|---|---|
| Contact group / audience | `CampaignEntity.contactGroupId` | `campaigns.contact_group_id` (V14) | ✅ `cec.contact_group_id` | ✅ create/update/response | ✅ tenant-scoped, 3 layers | all |
| DID / DNID (requested) | `CampaignEntity.didId` | `campaigns.did_id` (V14/V16) | ✅ `cec.did_id` | ✅ | ✅ create/update/activation/readiness/runtime | all |
| DNID (actual, routed) | `VoiceRoute.didId` | **not persisted** (method-local) | n/a | ❌ | via routing eligibility | all |
| Content mode | `ContentMode` (AUDIO/TTS) | `campaigns.content_mode` | ✅ `cec.content_mode` | ✅ | ✅ mutual exclusion enforced | all |
| Audio asset | `CampaignEntity.audioAssetId` | `campaigns.audio_asset_id` (no FK) | ✅ `cec.audio_asset_id` | ✅ | ✅ dynamic, 4 layers | all |
| TTS template | `CampaignEntity.ttsTemplateId` | `campaigns.tts_template_id` (no FK) | ✅ `cec.tts_template_id` | ✅ | ✅ validated, **never executed** | all |
| Schedule / timezone | `ScheduleSpec` (7 fields) | `campaigns.schedule` JSONB | ✅ **flattened** into 7 `cec` columns | ✅ | ✅ window+zone rules | all |
| Retry policy | `RetryPolicySpec` | `campaigns.retry_policy` JSONB + `retry_rules` JSONB | ✅ 4 `cec` columns | ✅ | ✅ `RetryPolicyValidator` (VB-6D.2) | all |
| Daily **dial** limit (VB-6C) | `DailyDialLimitService` | `campaigns.daily_dial_limit` (V48) | ✅ `cec.daily_dial_limit` | ✅ | ✅ `@DailyDialLimit` + CHECK 1-3 | Voice Blast |
| Daily **attempt** limit (VB-6D.3) | `DailyAttemptSafetyService` | `campaigns.max_daily_attempts` (V51) | ✅ `cec.max_daily_attempts` | ✅ | ✅ `@CampaignDailyAttempts` + CHECK 1-10 | Voice Blast |
| Whitelist flag | `callOnWhitelistNumbers` | `campaigns.call_on_whitelist_numbers` (V28) | ✅ | ✅ | ✅ | all |
| **Max call duration** | — | — | — | — | — | **ABSENT** |
| **Report privacy** | — | — | — | — | — | **ABSENT** |
| Webhooks / `integrationConfig` | `CampaignEntity.integrationConfig` | `campaigns.integration_config` JSONB (V14) | ❌ **deliberately excluded** | ✅ (unvalidated) | ❌ **none** | none |
| `typeConfig` | per-type sealed interface | `campaigns.type_config` JSONB | ✅ `cec.type_config` | ✅ | ✅ **strict per type** | DTMF, CONNECT_BY_AGENT required; PLAYFILE must be empty |

**Key structural finding:** the "common calling configuration" is **already** a single flat set of
columns on `campaigns`, mirrored one-for-one into `campaign_execution_configurations`. There is no
duplicated common configuration across campaign types to extract — **the extraction the brief
anticipates has effectively already happened** (in V44, `campaign_execution_configurations`). What
is missing is not a *structure*; it is three **absent items** and one **half-wired** item (TTS).

---

## 8. DID/DNID

| Concept | Reality |
|---|---|
| Requested vs allocated DID | **No schema distinction** — one `dids` table. "Requested" = `campaigns.did_id`; "actual" = `VoiceRoute.didId` |
| Actual routed DID | **may differ**: a `voice_route_profile_entries.did_id` substitutes for the campaign DID (`VoiceRoutingService.java:313-326`) |
| **VB-6C keys on the ACTUAL routed DID** | ✅ correct, and pinned by `OutboundDialServiceRoutingTest.routeDidSubstitution_usesRouteDidForBucket` (incl. a `never()` assertion on the requested DID) |
| VB-6D.3 attempt key | **no `did_id`** — deliberately DNID-agnostic so rotation cannot reset it (V50:72-74) |
| Fallback DID | **none** — an incompatible pinned DID is a route rejection, pinned by `VoiceRoutingDIDTest.D2`/`D5` |
| Null `didId` | accepted at create/update/readiness, then **hard-fails** at execution start (`CampaignExecutionOrchestrator.java:150-155`) |
| Tenant ownership | enforced at 5 boundaries incl. dial-time (`VoiceEligibilityService.java:110-127`) |
| Carrier | **no entity** — `dids.provider` is free-text `VARCHAR(50)` compared with `equalsIgnoreCase` |
| Reseller | `dids.reseller_id` + `allocation_source` provenance (V41) |

### 8.1 🔴 Finding D-1: a profile-pinned DID bypasses every DID validity and ownership check

`buildVoiceRoute` (`VoiceRoutingService.java:313-326`) filters a pinned DID by **only**
`findByIdAndDeletedAtIsNull` + provider-string equality (`:316-321`). The eligibility call at `:71`
sees only the **requested** DID. So a `voice_route_profile_entries.did_id` pointing at another
tenant's assigned DID, or at a pool DID with `tenant_id IS NULL`, that shares the gateway's provider
string would be **dialed as the CLI** and becomes `actualOutboundDidId` and the VB-6C bucket key.
No `status` / `allocationState` / `tenantId` check. **Untested** — every routing fixture uses
same-tenant DIDs. Severity: high (tenant isolation + compliance), but reachable only once routing
profiles are populated, and they currently have **no write path at all** (§8.2).

### 8.2 🟠 Finding D-2: routing profiles and SIP gateways have no production write path

`VoiceRouteProfile.addPrimaryRoute/addOverflowRoute/addFailoverRoute` (`VoiceRouteProfile.java:76-92`)
are called **only from test fixtures**. `SipGatewayService` has full CRUD but **no controller**
(none of the 17 `@RestController`s covers gateways or route profiles). Both must be seeded by hand
in SQL. This is why D-1 is currently unreachable — and also why routing cannot be operated as a
product. Severity: medium (blocks operability, not correctness).

### 8.3 🟠 Finding D-3: `route_type` is written but never read

`VoiceRouteProfile.primaryRoutes/overflowRoutes/failoverRoutes` are three unfiltered
`@OneToMany(mappedBy="profile")` collections (`VoiceRouteProfile.java:64-74`) — no `@Where`,
`@Filter` or `@SQLRestriction` exists anywhere in the codebase. All three return the **same rows**,
so the PRIMARY pass iterates overflow and failover entries too and the
`ROUTE_SELECTED_PRIMARY/_OVERFLOW/_FAILOVER` reason codes are not faithfully derived. Masked in
tests because all four routing suites hand-build in-memory lists. Severity: medium.

---

## 9. Welcome Audio / TTS

`TtsTemplateScope` **does** model tenant-owned vs global (`V42`: `GLOBAL` must have
`tenant_id IS NULL`, `TENANT` must have one, both `NOT NULL` + `CHECK`), with an authoritative
`existsUsableForTenant` predicate (`TtsTemplateRepository.java:27-36`) consumed by
`CampaignResourceValidationService.validateTts:165-177`. Unlike audio, a TTS **edit resets approval**
(`TtsTemplateService.java:181-186`).

PLAYFILE support: **direct audio only.** `VoiceMediaController` has no `speak`/synthesise method at
all. The `ttsTemplateId` in the runtime config record is **never read** by any execution service.

### 9.1 🔴 Finding T-1: PLAYFILE + TTS is fully configurable, fully valid, and always fails

`CampaignService.validateContent:396-399` accepts `contentMode=TTS` + `ttsTemplateId` for **any**
campaign type. The frontend offers it (`create-campaign-dialog.tsx:280`). It passes readiness
(`TtsGovernancePostgresIntegrationTest` seeds PLAYFILE+TTS campaigns and proves they are **ready**
and **activate**). Then at answer time `PlayfileExecutionService.java:148-154` records
`PLAYBACK_CONFIG_INVALID` — **PERMANENT** — for **every single call**.

This is a deliberate, test-pinned runtime boundary (`PlayfileExecutionServiceTest.ttsContentModeRejected`),
so it is *not* a bug in the runtime. The defect is the **configuration surface**: the platform lets
an operator build a campaign that is accepted, approved, scheduled, activated, and then fails 100%
of its calls with no way to find out why short of reading logs. Severity: **high, user-facing**.

---

## 10. Max Call Duration

**Completely absent.** Verified by exhaustive search:

| Searched | Result |
|---|---|
| `maxDuration`/`maxCallDuration`/`max_duration` in `src/main` | **0 matches** |
| `durationSeconds` in `src/main` | only `AudioAssetEntity` (the audio file's own length) |
| `max_duration`/`duration` in any migration | only `audio_assets.duration_seconds` (V20:19-21) |
| `ScheduledExecutorService` / `new Timer(` / `TimerTask` in `src/main` | **0 matches** |
| `absolute_timeout` / `schedule-hangup` / `execute_on_answer` in the originate command | **not sent** |

`EslClient.originate:210-211` builds
`bgapi originate {origination_caller_id_number=…}sofia/gateway/…/…` with **no**
`origination_ignore_early_media`, **no** `absolute_timeout`, **no** dialplan guard. Note also that
`effectiveProfile` is computed at `EslClient.java:203-206` and then **never used** — dead.

Consequence: the only things that can end a PLAYFILE call are the callee hanging up, the
post-playback `uuid_kill`, or the 5-minute capacity reconciler freeing the slot while the DB row
still says `IN_PROGRESS`. **There is no watchdog.** The repository's own audit agrees:
`docs/VB-6-CAMPAIGN-AUDIT.md:160` — *"**NOT PRESENT.** No campaign field … no `CallSession` timer,
no ESL schedule-hangup usage."* Severity: **high** — the only explicitly named "common calling
configuration" item in the brief is entirely missing.

---

## 11. Scheduler

`CampaignExecutionOrchestrator.scheduledTick` — `@Scheduled(fixedDelay = 30000)`, **one
transaction**, five steps (`CampaignExecutionOrchestrator.java:376-405`).

### 11.1 🔴 Finding SC-1: execution start can never succeed (VERIFIED)

```
:96   requireUserId()
:569  → currentUserProvider.current()
:12   SecurityCurrentUserProvider.current() → SecurityContextHolder.getContext().getAuthentication()
```

Searched `src/main` for `SecurityContextHolder.setAuthentication` / `createEmptyContext`:
**0 matches.** Only Spring Security's servlet filter populates the context, on the **request
thread**. A `@Scheduled` task runs on a scheduler thread with `MODE_THREADLOCAL` → empty context →
`Optional.empty()` → `BusinessException(UNAUTHORIZED)`.

`startExecution` has **no controller endpoint** (`grep startExecution` → only the declaration and
the internal call at `:384`). `CampaignExecutionService.execute:87-100` (the REST path) creates the
execution in `REQUESTED` and relies on the tick to start it. So **an execution requested over REST
never starts.** Severity: **critical**.

### 11.2 🔴 Finding SC-2: one failure aborts the other four steps

The single `try { …5 steps… } catch (Exception e) { log.error(...) }` (`:379`, `:402-404`) wraps
everything. Because step 1 always throws when a `REQUESTED` execution exists, **steps 2-5 —
retries, dialing, the ESL pump and reconciliation — are skipped for that entire cycle**, every 30
seconds, with only a log line. Severity: **critical**. The two findings compound: a single pending
execution disables the whole engine.

### 11.3 🟠 Finding SC-3: the whole tick is one transaction containing blocking network I/O

`:377 @Transactional` (REQUIRED) → `processDueAttempts` (also `@Transactional`, joins) →
`dialer.dial(...)` at `:331` — a TCP connect + auth + command round trip bounded by
`connectTimeoutSeconds=10` / `commandTimeoutSeconds=30`, **in a loop over every due attempt with no
`LIMIT`**. One transaction is held open across N × up-to-40s of socket I/O, holding row locks on
`call_attempts`, `call_sessions`, `call_legs`, `voice_channel_reservations` and both daily ledgers.
Symmetrically `EslEventService.processEvent` (`:89 @Transactional`) invokes
`PlayfileExecutionService.onAnswered/onPlaybackCompleted` → `playAudio`/`terminateCall` → **new
`EslClient` connect + auth** *inside* the event transaction. Self-invocations
(`startExecution`/`processRetries`/`reconcileExecution`) bypass the proxy, so their own
`@Transactional` annotations are inert. `docs/VB-6-CAMPAIGN-AUDIT.md:191` claims the opposite
(*"per-step transactions differ — retries and dialing are separately transactional, which is
correct"*) — that claim is **incorrect for the code as written**. Severity: high (lock contention
between the tick and the event thread, which contend for the same rows).

### 11.4 🟠 Finding SC-4: `PAUSED` is cosmetic

`CampaignStatus.PAUSED` exists (`CampaignStatus.java:21`) with legal transitions
(`CampaignService.java:66-76`) and a `PATCH /{id}/status` endpoint (`:247-277`) that touches **no
execution and no attempt**. The only runtime reader of campaign status is
`CampaignReadinessService.checkLifecycleState:100-108`, reached **only** from `startExecution` and
from the readiness/execute REST endpoints — never from `processRetries` or `processDueAttempts`.
So a paused campaign's `QUEUED` attempts **keep being dialled** and its `FAILED` attempts **keep
being retried**. Worse, if a `REQUESTED` execution's campaign is paused, `startExecution:116-123`
marks the execution **`FAILED`** — a pause *destroys* pending work instead of deferring it. No test
covers pause at all. Severity: high.

### 11.5 🟡 Finding SC-5: a single-threaded scheduler with no configured pool

No `spring.task.scheduling.*` anywhere, so Spring Boot's default pool size of **1** is used by all
nine pollers. A long `scheduledTick` (see SC-3) starves the 1-second DTMF timeout scanner and the
5-second agent/ACD sweeps — degrading live-call handling. Severity: medium.

### 11.6 Positive findings

- Timezone authority is single and explicit: `ScheduleSpec.timezone` (IANA), frozen per execution.
- Daily limits **fail closed** with no JVM/UTC fallback (`DailyDialLimitService.resolveUsageDate:178-196`).
- Readiness requires a timezone for all three Voice Blast types, with an explicit comment tying it
  to the daily-limit day boundary (`CampaignReadinessService.java:144-163`).
- Retry re-validates contact and DID before creating a retry attempt (`:276`, `:281`).
- `campaignExecutions.configuration_snapshot_id` NOT NULL + FK, and the snapshot is created first in
  the same transaction.

---

## 12. Webhooks

**Definitively absent as a capability.** The earlier audit's "dead JSONB" is accurate, with one
refinement: an HTTP client starter is on the classpath but **entirely unused**.

| Item | Verdict |
|---|---|
| `campaigns.integration_config JSONB` (V14:74) | **PERSISTED BUT DEAD** — nullable, **no CHECK**, never altered since V14 |
| Write sites | `CampaignMapper:32,45,141` (create/update) + `:76` (clone) |
| Read sites | `CampaignMapper:104` — **echoed to the client only** |
| Webhook tables/entities/DTOs | **ABSENT** — none in 50 migrations |
| Delivery mechanism | **ABSENT** — 0 uses of RestTemplate/WebClient/HttpClient/`java.net.http`; 0 HMAC/signature code; no delivery/queue/outbox entity; no idempotency key |
| `spring-boot-starter-restclient` | on the classpath (`pom.xml:52`), **0 source usages** |
| `CampaignDomainEvent` + `CampaignEventPublisher` | published (`CampaignService:141,225,239,275,295`) into Spring's `ApplicationEventPublisher`; **0 listeners** exist anywhere |
| `event_publication` table (V26) | Spring Modulith internal bookkeeping; no entity, no listener — **not** a webhook mechanism |
| Snapshot | **deliberately excluded**, with a stated rationale (`CampaignConfigurationSnapshot.java:35-36`) |
| Frontend | advertises a `{"webhookUrl": "https://..."}` placeholder (`create-campaign-dialog.tsx:312`) for a field nothing consumes |

### 12.1 🟡 Finding W-1: `integrationConfig` is accepted, echoed, unvalidated and untested

No validation annotation on any of the three DTOs; not a parameter of any validator; no size limit;
no round-trip test. A client can store arbitrary JSON that the API faithfully returns. Severity:
low functionally, but it is a **deception risk** — the UI presents it as webhook configuration.

**Recommendation: out of VB-6E.** Webhook delivery is a subsystem, not common calling configuration,
and §14 of the brief says not to build it unless scope demands. The one thing worth doing in VB-6E
is a **doc correction** so nobody builds against it.

---

## 13. Report Privacy

**Completely absent — not "thin", absent.** This corrects the direction of the earlier audit.

| Item | Verdict |
|---|---|
| Configuration field | **ABSENT** — no entity field, no column in any `ALTER TABLE campaigns`, no DTO field, no API |
| Reporting module | **ABSENT** — `REPORT_VIEW`/`REPORT_VIEWER` capabilities are seeded (`V1:160,193,…`) but guard **no endpoint** |
| Snapshot | N/A — the field does not exist (contrast V48/V51, which *did* add snapshot columns) |
| Masking | **5 mask functions, all `private`, all reachable only from SLF4J log statements**: `EslClient.maskNumber:402` (callerId/destination), `FreeSwitchVoiceMediaController.maskUuid:155`, `FreeSwitchAgentLegDialer.maskUuid:46`, `NoOpAgentLegDialer.mask:28`, `ConnectByAgentService.maskUuid:490` |
| Masking in any response/report | **NONE** — 0 applications outside logs |
| Masking tests | **NONE** — 0 matches for `mask*` under `src/test` |
| Raw PII exposure | `CallSession.destinationNumber` (VARCHAR(20), raw E.164); `ContactResponse.phoneNumber`; `AgentCallResponse.destinationNumber`; contact export CSV/XLSX/JSON (`ContactGroupService:565,588,606`) |

**Recommendation: out of VB-6E.** Report privacy is a reporting concern, §15 forbids expanding
reporting scope, and PLAYFILE execution does not read it (it cannot — the field does not exist).
Log-line masking is a real but separate hardening item.

---

## 14. Contact/Audience

**VB-6B's identity architecture is solid and PLAYFILE uses it correctly. No PLAYFILE-specific
lead/phone model exists or is needed.**

| Property | Reality | Evidence |
|---|---|---|
| Canonical identity | `(tenant_id, phone_number)` for live rows — **not** the UUID | `ContactEntity.java:16-28` |
| Uniqueness | partial unique index, DB-enforced | `V46:137-139` `uq_contacts_tenant_phone_live … WHERE deleted_at IS NULL` |
| Format | DB CHECK + service canonicalizer | `V17:34-36`; `ContactValidation.java:32-38` |
| Same number, two tenants | two different Contacts — proven on real PG | `ContactIdentityPostgresIntegrationTest.PG-C2` |
| 20-way concurrent create | exactly one identity | `…PG-C4` |
| Cross-tenant membership | refused by composite FK | `V46:45-49`; `…PG-C6` |
| **Audience frozen?** | **set yes, data no** — see below | `CampaignExecutionOrchestrator.java:165-208` |
| Tenant isolation | 4 layers: composite FK, write-time, readiness, runtime | `CampaignService:495-504`; `CampaignReadinessService:276` |

### 14.1 🟠 Finding CT-1: the "audience is frozen" javadoc is false for the default configuration

`CampaignExecutionOrchestrator.java:165-168` states *"Membership later changes cannot alter this
already-started execution (Model A)."* But `CallEligibilityService.java:61-65,99-110` re-reads
`memberRepository.findByContactGroupId(...)` **on every dial** when `enforceWhitelist == false` —
which is the default. So a contact removed from the group mid-execution still has its queued
attempts rejected with `NOT_IN_CAMPAIGN_TARGETS`; and a contact **added** mid-execution gets no
attempts. The set is frozen; membership is re-checked. Both are defensible, but the code and its
documentation disagree, and the retry path re-checks only contact *identity*
(`isContactStillValid:514-517`, whose `contactGroupId` parameter is **dead**). Severity: medium —
a correctness-of-documentation defect with a real behavioural ambiguity.

### 14.2 🟡 Finding CT-2: `CallEligibilityService.isNumberInCampaignContactGroup` is an N+1

`:104-109` loads all memberships, then calls `contactRepository.findById(contactId)` **per member**
on every dial, and its `findById` has **no tenant predicate** (safe today only because V46's
composite FKs guarantee group↔contact tenant equality). Severity: medium (performance), low
(correctness, given the FK).

### 14.3 🟡 Finding CT-3: `CampaignRunMode.RECURRING` is persisted and validated but never executed

`CampaignService:457-458` requires a schedule for `RECURRING`; **no code ever creates a subsequent
execution**. A recurring campaign runs once. Severity: medium — a configuration that promises
something the engine does not do.

---

## 15. Compliance

**Intact after VB-6D.3. No duplicate compliance service was introduced by VB-6D.**

Ordered check sequence — `VoiceEligibilityService` 4-arg core (`:77-142`) then whitelist last
(`:168-179`):

| # | Check | Code | Rejection |
|---|---|---|---|
| 1 | E.164 valid | `:78-81` | `INVALID_NUMBER` |
| 2 | Platform blocklist (no tenant filter) | `:84-86` | `PLATFORM_BLOCKED` |
| 3 | Platform protected | `:89-91` | `PLATFORM_PROTECTED` |
| 4 | Reseller blocklist | `:94-98` | `RESELLER_BLOCKED` |
| 5 | Tenant DNC/blocklist | `:101-103` | `DNC_BLOCKED` |
| 6-9 | DID null / missing / not ACTIVE / not ASSIGNED / foreign | `:111-127` | `INVALID_DID` |
| 10 | No gateway for the DID's provider | `:130-134` | `NO_ELIGIBLE_GATEWAY` |
| 11 | Capacity unavailable | `:137-139` | `TEMPORARILY_UNAVAILABLE` |
| 12 | Whitelist (**only if `enforceWhitelist`**) | `:173-177` | `NOT_WHITELISTED` |
| 13 | Campaign targeting (**only if not `enforceWhitelist`**) | `CallEligibilityService:61-65` | `NOT_IN_CAMPAIGN_TARGETS` |

**Blocklist/DNC strictly beats whitelist** — `:169-172` early-returns on any block, so the whitelist
branch is unreachable for a blocked number. Verified structurally, not just documented. Retry
re-enters compliance because retries create a **new attempt** that traverses the identical
`processAttempt` path — structurally guaranteed, not a convention. All 29 pre-dispatch codes are in
`FailureClassification.PRE_DISPATCH` and `RetryPolicyService:75-79` refuses them without consuming
budget. PLAYFILE adds no media-specific compliance edge cases.

### 15.1 🟠 Finding CM-1: three overlapping eligibility engines

1. `VoiceEligibilityService` — the real stack (4 overloads; only 2 used).
2. `CallEligibilityService` — a thin wrapper that **re-resolves the reseller the voice layer already
   resolved** (`CallEligibilityService.java:79-83`) and **returns the voice layer's result type** from
   a method whose declared return type is the campaign layer's duplicate
   (`CallEligibility.java:60-82` ≡ `VoiceEligibility.java:68-90`).
3. `AgentOutboundCallService.placeCall:122-293` — a hand-rolled 9-step sequence with its own reason
   vocabulary, **no whitelist enforcement**, and no daily limits.

Plus: **the entire blocklist/DNC/DID/gateway stack runs twice per dial** —
`OutboundDialService:184` and again inside `VoiceRoutingService:71`, eight lines apart, and the
second run is not index-backed (§15.2). `CallEligibilityService` has **no dedicated test class**;
it is always mocked. Severity: medium (performance + a genuine second implementation of a safety
gate).

### 15.2 🟡 Finding CM-2: `PhoneListService` is entirely dead and list checks do not use the index

`PhoneListService` has **zero callers** (main or test) and no controller. Three
`PhoneListEntryRepository` finders keyed on `normalized_number` — the column the leading index
`idx_phone_lists_number_type_scope` (`V25:49`) exists for — are **never used**. Consequently
`isBlocked`/`isWhitelisted` load the **entire** list for a `(type, scope)` and `.anyMatch()` in
memory on every dial. Two dead params: `isBlocked(campaignTenantId,…)` and
`isWhitelisted(campaignTenantId,…)` never reference their first argument. Severity: medium
(performance on the hot path), low (correctness).

### 15.3 🟡 Finding CM-3: five dead `VoiceRoutingReason` constants + one unreachable

`ROUTE_REJECTED_BLOCKLIST/_DNC/_WHITELIST/_CAMPAIGN_NOT_ALLOWED/_UNKNOWN` are never produced (12 of
24 constants total are dead). `ROUTE_REJECTED_INVALID_DID` is unreachable through the dial path
because eligibility short-circuits first. Severity: low (dead code).

---

## 16. CallAttempt / Outcome Mapping

`CallAttemptStatus` is deliberately coarse — **5 values**: `QUEUED`, `IN_PROGRESS`, `COMPLETED`,
`FAILED`, `CANCELLED`. Terminal set = `{COMPLETED, FAILED, CANCELLED}`
(`CampaignExecutionOrchestrator.java:79-83`). The fine-grained outcome lives on `CallSession`
(**12 values** incl. `PLAYING`, `PLAYBACK_COMPLETED`) and `CallLeg` (8 values).

| Real-world outcome | `CallAttempt.status` | `failure_code` | Retry classification |
|---|---|---|---|
| Queued for dispatch | `QUEUED` | — | — |
| Dialing | `IN_PROGRESS` | — | — |
| Ringing | `IN_PROGRESS` | — | — |
| Answered | `IN_PROGRESS` | — | — |
| Media started | `IN_PROGRESS` | — | — |
| **Media completed** | `COMPLETED` | `null` | — (via the `uuid_kill` hangup) |
| No answer (19) | `FAILED` | `NO_ANSWER` | `CONTACT_OUTCOME`/NO_ANSWER |
| Busy (17) | `FAILED` | `BUSY` | `CONTACT_OUTCOME`/BUSY |
| Rejected (21) | `FAILED` | `REJECTED` | `CONTACT_OUTCOME` |
| Congestion (34) | `FAILED` | `CONGESTION` | `CONTACT_OUTCOME`/FAILED |
| **Timeout** | — | — | **NOT REPRESENTED** (no max duration) |
| Provider failure | `FAILED` | `PROVIDER_UNAVAILABLE` / `DIAL_FAILED` | `PRE_DISPATCH` |
| **Media failure** | `FAILED` | `PLAYBACK_FAILED` | `CONTACT_OUTCOME`? **no** → TEMPORARY, retryable |
| **Media config error** | `FAILED` | `PLAYBACK_CONFIG_INVALID` | **`PRE_DISPATCH`** → never retried |
| **Caller hangup mid-playback** | **`COMPLETED`** | `null` | — ⚠️ see 16.1 |
| System hangup (post-playback `uuid_kill`) | `COMPLETED` | `null` | — |

**The attempt carries no notion of *how far* the call got.** "Answered but hung up after 2 seconds"
and "played the whole file" are both `COMPLETED` with a null failure code. If VB-6E needs
connection-level reporting (what fraction answered, how long they stayed), it must join to
`call_sessions` (`answered_at`, `ended_at`, `initiated_at`) — which is available and unused.

### 16.1 🔴 Finding CA-1: a callee who hangs up mid-playback is recorded as a COMPLETED blast

The success decision (`EslEventService.java:481`) reads exactly two things — the hangup cause, and
whether a failure was already recorded on the session. It **never reads `session.getStatus()`**,
which is precisely where `PLAYING` (still playing) and `PLAYBACK_COMPLETED` (finished) differ.
FreeSWITCH reports a normal far-end release as cause **16**, and `HangupCauseMapper.isNormalClearing:95-98`
treats 16 as success. So:

| Sequence | session at hangup | result |
|---|---|---|
| ANSWER → START → **STOP** → HANGUP(16) | `PLAYBACK_COMPLETED` | `COMPLETED` ✅ correct |
| ANSWER → START → **HANGUP(16)** mid-playback | `PLAYING` | `COMPLETED` ⚠️ **indistinguishable** |
| ANSWER → START → STOP → HANGUP(**no cause header**) | `PLAYBACK_COMPLETED` | `FAILED` + `HANGUP_UNKNOWN` ⚠️ **opposite error** |

Row 2 is **pinned by an existing test** — `PlayfileLifecycleEslTest.p13_remoteHangupDuringPlayback:200-216`
asserts `CallAttemptStatus.COMPLETED` after `CHANNEL_ANSWER → PLAYBACK_START → HANGUP(NORMAL_CLEARING)`.
Row 3 is the mirror hazard: a teardown hangup without a `Hangup-Cause` header turns a good call into
a `FAILED`/`HANGUP_UNKNOWN`, which is **retryable** — a re-dial of a successfully-played blast.

This is a **product decision, not a bug** (it is deliberately tested), so it must be asked, not
assumed. See §23 OD-A.

### 16.2 🟠 Finding CA-2: routing rejections bypass the canonical taxonomy and **consume retry budget** (VERIFIED)

`OutboundDialService.java:238`:
```java
markFailed(attempt, reason, "Routing failed: " + reason);   // reason = VoiceRoutingReason code
```
`reason` is e.g. `ROUTE_REJECTED_NO_ELIGIBLE_GATEWAY` or `ROUTE_REJECTED_DID_INCOMPATIBLE`.
`CallFailureCode` has only the bare `ROUTE_REJECTED` constant (`:203`) and **nothing ever writes it**.
`CallFailureCode.canonicalize:328-329` is `fromCode(code).orElse(HANGUP_UNKNOWN)`. Therefore:

```
ROUTE_REJECTED_GATEWAY_DISABLED
  → canonicalize → HANGUP_UNKNOWN
  → FailureClassification → CONTACT_OUTCOME, category HANGUP
  → RetryPolicyService → RETRYABLE, consumes campaign retry budget
```

This **directly contradicts** the VB-6D design: `CallFailureCode.ROUTE_REJECTED` sits in
`FailureClassification.PRE_DISPATCH` precisely so routing rejections consume no budget, and
`docs/VB-6-CAMPAIGN-AUDIT.md:191` states routing rejections "consume no retry budget". A gateway
being administratively disabled therefore burns a tenant's redial budget and can re-dial a contact
that was never reached. This is a **defect introduced by the interaction between routing (older)
and the VB-6D.1 taxonomy** — exactly the kind of cross-phase regression an audit should catch.
Severity: **high**.

---

## 17. FreeSWITCH / ESL

### 17.1 The abstractions that exist

| Port | Type | Real impl | NoOp impl |
|---|---|---|---|
| `voice.media.OutboundDialer` | interface | `FreeSwitchOutboundDialer` | `NoOpOutboundDialer` |
| `voice.media.VoiceMediaController` | interface, 7 methods | `FreeSwitchVoiceMediaController` | `NoOpVoiceMediaController` |
| `voice.agent.AgentLegDialer` | interface | `FreeSwitchAgentLegDialer` | `NoOpAgentLegDialer` |
| `campaign.EslEventProcessor` | 1 method (`ensureEventProcessing`) | `EslEventScheduler` | — |
| **ESL client itself** | **no interface** — concrete `EslClient implements AutoCloseable` | | |

**`EslClient` is not a stub.** It is a hand-written, genuinely real raw-socket ESL client
(`java.net.Socket`, `auth`, `event plain`, real command strings). **NoOp implementations fail loudly
rather than fake success** (`NoOpVoiceMediaController.playAudio` throws `EslException`;
`NoOpOutboundDialer.dial` returns `PROVIDER_UNAVAILABLE`) — the right choice, because it keeps the
lifecycle honest in provider-less environments.

**A new `EslClient` is opened per operation** — six sites: event loop, originate, playAudio,
terminateCall, bridge, agent-leg originate. No pooling, no shared command connection, no
reconnect-with-backoff. That is a performance and resilience concern, not a correctness one.

### 17.2 Event subscription and correlation

Subscribes to 10 events (`EslClient.java:89-100`) via `event plain` (`:116`). Correlation is by the
**`Call-UUID`** header (`EslEvent.getCallUuid:39-41`); bridge uses `Bridge-B-Unique-ID`. Dispatch
order: drop if no `Call-UUID` → resolve `CallSession` by `provider_call_id` → inbound-only
`CHANNEL_CREATE` → agent leg → `CHANNEL_BRIDGE` → resolve `CallAttempt` → inbound/outbound
boundaries → primary leg → switch.

**🟡 Finding E-1: `legs.get(0)` is unordered.** `EslEventService.java:151-152` takes
`callLegRepository.findByCallSessionIdAndDeletedAtIsNull(...)`'s first row with **no `ORDER BY`**.
With a CUSTOMER + AGENT leg the attempt-scoped hangup handler can latch onto the agent leg and
overwrite it. A type-filtered finder exists (`CallLegRepository.java:17`) and is used elsewhere, just
not here. Severity: medium (affects CONNECT_BY_AGENT).

**🟡 Finding E-2: non-unique `provider_call_id` with `Optional` finders.** `call_sessions`,
`call_legs` and `call_attempts` all have **non-unique** indexes on `provider_call_id`
(`V29:44,76`, `V23:12-13`), yet the finders return `Optional`. A duplicate UUID would throw
`IncorrectResultSizeDataAccessException`, unhandled. Severity: low.

**🟡 Finding E-3: `EslEventScheduler.shutdown()` is never invoked** — no `@PreDestroy` anywhere in
`src/main`, and the event-loop `EslClient` is never closed. Harmless (daemon thread) but dead.

### 17.3 Playback command

`EslClient.java:269-275`:
```java
String command = String.format("uuid_broadcast %s %s aleg", channelUuid, audioPath);
```
Not `playback`, not `execute_on_answer`. **Command acceptance is correctly not treated as completion** —
`EslClient.java:261-263` says so explicitly and the design is right: `PLAYBACK_START` → `PLAYING`,
and only `PLAYBACK_STOP` → `PLAYBACK_COMPLETED` → `uuid_kill`.

### 17.4 🟠 Finding E-4: `PLAYBACK_STOP` without `PLAYBACK_START` is silently discarded

`handlePlaybackStop:277-279` returns unless the session is `PLAYING`, and the session only reaches
`PLAYING` via `PLAYBACK_START` (`:260-261`). A dropped/coalesced start event therefore loses the
completion entirely: the call sits in `ANSWERED` forever, no hangup, **no reservation release**
(hangup-only, `:522-527`), and only the 5-minute capacity reconciler frees the slot. There is no
orphaned-call watchdog. Severity: high (and it becomes *more* likely once E-5 is fixed and events
actually flow).

### 17.5 🔴🔴 Finding E-5: the ESL client cannot complete a handshake — four hard defects (VERIFIED)

This is the single most important finding in the audit.

| # | Defect | Code | Protocol fact | Consequence |
|---|---|---|---|---|
| **D1** | **Auth banner never consumed.** FreeSWITCH speaks first with `Content-Type: auth/request\n\n`. | `EslClient.java:64-69` — sends `auth`, reads, tests `startsWith("+OK")` | The banner is unprompted | The first read returns the pending banner → `EslException("Authentication failed: Content-Type: auth/request")`. **`connect()` fails on the first command.** |
| **D2** | **`Reply-Text` never parsed.** | `:67`, `:119`, `:220`, `:232`, `:317` all test `startsWith("+OK")` on the *aggregate* string | Real reply: `Content-Type: command/reply\nReply-Text: +OK accepted\n\n` | Even with D1 fixed, every reply check fails. `originate` falls through to `EslException("Unexpected response: Content-Type: command/reply…")`. |
| **D3** | **Event framing wrong — every event dropped.** | `:127-133` takes the first line as the *event name*; `parseEvent:162-172` stops at the first blank line; filter `:175-179` matches against that name | Real frame: `Content-Type: text/event-plain` / `Content-Length: N` / **blank** / body containing `Event-Name: CHANNEL_ANSWER` | `eventName` becomes `"Content-Type: text/event-plain"`, which is in no `SUBSCRIBED_EVENTS` entry → `null` → dropped. The unread body then desynchronises the loop. **Zero events would ever be delivered.** |
| **D4** | **`bgapi` returns a Job-UUID, not a channel UUID.** | `:211` sends `bgapi originate`; `:220-227` splits and takes `parts[1]` as "the FreeSWITCH channel UUID"; falls back to the literal string `"accepted"` (`:229-231`) | `bgapi` replies `+OK Job-UUID: <job-uuid>`; the channel UUID arrives later in the `CHANNEL_CREATE` body | The value stamped into `provider_call_id` is a **job UUID**, so nothing would correlate. And `EslEventService:106-109` handles outbound `CHANNEL_CREATE` **only** when `Call-Direction=inbound` — `InboundEslRoutingTest:98-110` explicitly *asserts* outbound `CHANNEL_CREATE` is ignored. **No code path binds the real outbound channel UUID to the attempt.** |

**Consequence:** the entire telephony integration has never functioned against a real FreeSWITCH.
This is consistent with the repository's own repeated admission that live E2E was never executed, and
with `docs/VB-5A-INSPECTION-REPORT.md:58,100` deferring the audio path convention to "the FreeSWITCH
deployment".

**Why the tests never caught it:** `EslClient` appears in tests **only** via
`mockConstruction(EslClient.class)` (`FreeSwitchVoiceMediaControllerTest`, `AgentLegDialerContractTest`)
or via injected fake `reader`/`writer` fields carrying **fabricated** frames like `"+OK accepted\n\n"`
(`AgentLegDialerContractTest:199-211`) — which is not a real FreeSWITCH frame. Every `EslEvent` in the
60+ ESL tests is **hand-constructed** (`new EslEvent(name); addHeader(...)`), so `parseEvent` and
`SUBSCRIBED_EVENTS` filtering have **zero** coverage. The `uuid_broadcast … aleg` string is asserted
**nowhere**, because `playFile` is mocked. `FreeSwitchVoiceMediaControllerTest` even supplies
`AUDIO_PATH = "/usr/share/freeswitch/sounds/tenant-a/promo.wav"` — a realistic *absolute* FreeSWITCH
path — which masks finding E-6 entirely. Searches for `ServerSocket`, `MockWebServer`, `WireMock`,
`HttpServer` in `src/test`: **0 matches**.

### 17.6 🔴 Finding E-6: the audio storage reference is never translated to a FreeSWITCH-readable path (VERIFIED)

`PlayfileExecutionService.java:196` passes `asset.getStorageReference()` **verbatim**. That string is
`"audio/" + tenantId + "/" + audioAssetId + "/" + fileName` (`LocalAudioStorage.java:76`), written to
`{AUDIO_STORAGE_BASE_DIR:data/audio}/…` (`:51-56`).

`FreeSwitchVoiceMediaController.java:55` forwards it unchanged; `EslClient.java:273` interpolates it
into `uuid_broadcast <uuid> audio/<tenant>/<asset>/<file>.wav aleg`. FreeSWITCH resolves a relative
`playFile` argument **against its own sound directory**, and `FreeSwitchProperties` has **no**
sounds-directory field.

Searched for any translation: `sounds`, `getAbsolutePath`, `toRealPath`, `soundDir`, `absolute_codec_string`,
`file://` in `src/main` → **no translation exists**. `audio.storage.base-directory` is consulted **only**
inside `LocalAudioStorage` (`:51`, `:101`) and never on the playback path.

So even with E-5 fixed, **every playback would fail with a file-not-found**, surfacing as
`PLAYBACK_ERROR` → `PLAYBACK_FAILED` (TEMPORARY) → retries → exhaustion. The deployment assumes a
shared filesystem and a coincidental path convention that nothing configures or verifies. This is
the "production-readiness" gap in the most literal sense. Severity: **critical**.

---

## 18. Idempotency / Crash Recovery

### 18.1 What is genuinely good

`PlayfileLifecycleEslTest` (**29 tests**) is a strong suite. It pins state-machine safety
(`queuedToPlayingRejected`, `ringingToPlayingRejected`, `hangupToPlayingImpossible`,
`progressNeverRegressesState`), ordering (`p15_completionThenHangupFinalizesCompleted`,
`p13_remoteHangupDuringPlayback`), and **duplicate-delivery safety for six distinct events**
(`p14_duplicateHangupSafe`, `p26_duplicateAnswerSafe`, `p27_duplicatePlaybackStopSafe`,
`p28_duplicatePlaybackErrorSafe`, `p29_duplicateHangupInFullFlow`, `duplicatePlaybackStartSafe`,
`completedPlaybackDoesNotRestart`), plus `p19_playbackFailureEventuallyReleasesReservation` and
`noGatewayNoRelease`. Also `EslEventServiceFailureCodeTest` (**22**) pins the canonical-code invariant
on the persistence boundary. This is real, valuable work.

### 18.2 🔴 Finding R-1: no `@Version`, no compare-and-set, no locking anywhere

`@Version`, `PESSIMISTIC`, `OptimisticLock`, `SKIP LOCKED`, `@Lock`, `FOR UPDATE` in `src/main` →
**1 match, and it is a SQL comment** (`V50:39`). Idempotency rests entirely on **in-memory status
guards on a freshly-read JPA entity**, which work *only because the event loop is single-threaded*
(`EslEventScheduler.java:28-32`). The guards are not a general invariant either:
`OutboundDialService.requeueAttempt:465-470` writes `QUEUED` back **unconditionally**, which would
resurrect a terminal attempt if it interleaved with a hangup. Severity: high once multi-node or
concurrent writers exist; medium today.

### 18.3 🔴 Finding R-2: a lost event strands the attempt and the execution forever (VERIFIED)

An attempt is set `IN_PROGRESS` at `OutboundDialService.java:205`, **before** the dial at `:331`. The
**only** automated route to a terminal state is the `CHANNEL_HANGUP` handler. Then:

- `processDueAttempts:92-94` selects **only `QUEUED`** — never `IN_PROGRESS`.
- `processRetriesForExecution:233-235` selects **only `FAILED`**.
- `reconcileExecution:347-352` requires **all** attempts terminal.

⇒ A stranded `IN_PROGRESS` attempt is **never** reaped; its execution **never** leaves `RUNNING`; the
contact **never** gets a result. `CallAttemptRepository`'s complete query surface has **no**
"stale IN_PROGRESS" finder. Only capacity self-heals
(`VoiceCapacityServiceImpl.reconcileStaleReservations:401-413`, 5-minute cutoff).

**The asymmetry is stark:** the repository contains **six** reconcilers for other domains —
capacity (5 min), agent reservations (10 min), agent-connect timeout (5 s), DTMF timeout (1 s),
ACD maintenance (15 s), ACD retry (5 s) — and **none for campaign attempts or executions**.
`DtmfTimeoutScheduler` and `AgentConnectTimeoutScheduler` are exact, in-repo templates.
`DailyDialLimitService.java:119-127` even states the design gap: *"this phase deliberately adds NO
sweeper, scheduler, or reconciliation worker."* Manual recovery exists only via
`PATCH …/attempts/{id}/failed`. Severity: **high**.

### 18.4 🟡 Finding R-3: a requeued attempt keeps its original `scheduledAt`

`requeueAttempt:469` says *"scheduledAt could be recalculated here if needed"* — not implemented. A
capacity requeue therefore re-dials on the very next pass with **no backoff**, which is how the
`TEMPORARILY_UNAVAILABLE` loop at `:195-196` can spin. Severity: medium.

---

## 19. API / OpenAPI

`CampaignController` exposes **19 endpoints** under `/api/v1/campaigns`, all `@SecurityRequirement(bearerAuth)`
with documented 400/401/403/404/409 responses: CRUD, `POST /{id}/clone`, `GET /{id}/readiness`,
`POST /{id}/executions`, execution get/list, attempt get/list, and four attempt-lifecycle PATCHes
(`in-progress`, `completed`, `failed`, `cancel`).

**Already correct and complete:** every campaign DTO field carries `@Schema` with
`minimum`/`maximum`/null-semantics, verified by `CampaignOpenApiContractTest` (**11 tests**), which
asserts both daily ceilings coexist and are distinguished (3 and 10). VB-6C.2 and VB-6D.1/2/3 all
documented and pinned. **No OpenAPI work is needed for anything already built.**

### 19.1 🟡 Finding API-1: the attempt-lifecycle PATCHes accept a free-form `failureCode`

`CampaignController.java:372-380` takes `@RequestParam(required=false) String failureCode` and
`CallAttemptService.markFailed` accepts it unvalidated. This is defensible *because*
`canonicalize` exists to absorb it — that is precisely why VB-6D.1 built it — but it means the
capability-gated API is a second, unaudited writer of `call_attempts.failure_code`, and it is the
**only** caller of `failAttempt`. Severity: low (documented reasoning), worth an explicit note.

### 19.2 🟡 Finding API-2: audio and TTS have no OpenAPI contract test and no slice test

See §5.2. Generated OpenAPI is the source of truth per project rule, and neither module is covered
by a contract test. Severity: medium.

### 19.3 Anticipated VB-6E API surface

If VB-6E adds max call duration, it should follow the **exact VB-6D.3 pattern already proven**:
one nullable `Integer` field on `CreateCampaignRequest` / `UpdateCampaignRequest` /
`CampaignResponse`; one custom constraint annotation; one `assertConfigurable` domain guard; one DB
`CHECK` on `campaigns` **and** `campaign_execution_configurations`; `@Schema` with min/max/example
and explicit "null = platform default" wording; backward-compatible constructors so no existing
call site's meaning changes; and new `CampaignOpenApiContractTest` cases. No new endpoint.

---

## 20. Database

50 migrations, head **V51**, forward-only. Relevant tables:

| Table | Migration | Key constraints |
|---|---|---|
| `campaigns` | V9 → **V14 rebuild** (V10, V15, V16, V28, V48, V49, V51) | `retry_rules JSONB CHECK jsonb_typeof='array'` (V49:42-45); `daily_dial_limit 1..3` (V48); `max_daily_attempts 1..10` (V51). **`integration_config JSONB` has NO CHECK** |
| `campaign_execution_configurations` | V44 (+V48/49/51) | flattened schedule; `retry_rules JSONB`; **no unique on any column** |
| `campaign_executions` | V21 + V44 | `configuration_snapshot_id UUID NOT NULL` + FK (V44:61-67) |
| `call_attempts` | V22 | `status IN (…)` CHECK; `failure_code VARCHAR(50)`; partial unique `(execution_id, contact_id, attempt_number) WHERE deleted_at IS NULL`; **`contact_id` and `did_id` have no FK** |
| `call_sessions` / `call_legs` | V29 (+V34/35/36) | no `max_duration`, no deadline column; non-unique `provider_call_id` |
| `audio_assets` | V20 | `file_size > 0`, `duration_seconds IS NULL OR > 0`, `status IN (…)`. No migration since V20 |
| `tts_templates` | V20 + V42 | scope CHECKs; `tenant_id` nullable for GLOBAL |
| `dids` | V16 (+V39, V41) | `e164` E.164 CHECK, partial unique, `ASSIGNED requires tenant`, `RESELLER source requires reseller` |
| `phone_lists` | V25 | scope-consistency CHECK, partial unique; `normalized_number` index **unused by any query** |
| `voice_blast_daily_usage` (+`_entries`) | V47 | `UNIQUE (tenant_id, contact_id, did_id, usage_date)`; both counters ≥ 0 |
| `voice_blast_daily_attempts` | V50 | `UNIQUE (tenant_id, contact_id, usage_date)`, **no `did_id`**, `attempt_count >= 0` |
| `contact_group_members` | V46 | composite tenant FKs; `uq_cgm_group_contact`. **`updated_at`/`updated_by` exist in DDL but are unmapped by the entity** — permanently NULL |

**No migration is required for anything already built.** The one migration VB-6E would need is for
max call duration, following the V48/V51 pattern exactly.

---

## 21. Tests

| Suite | Tests | Kind | What it proves |
|---|---|---|---|
| **Total** | **1327** | — | 0 F / 0 E / 1 S |
| `PlayfileLifecycleEslTest` | **29** | pure mock | ESL **Java** state machine: ordering, 6× duplicate safety, reservation release. **Pins CA-1.** No socket. |
| `PlayfileExecutionServiceTest` | 12 | pure mock | snapshot/asset/tenant gating; `PLAYBACK_CONFIG_INVALID` vs `PLAYBACK_FAILED`; **pins the TTS rejection** |
| `PlayfileRetrySemanticsTest` | 7–8 | pure unit | `PLAYBACK_FAILED` retryable, `PLAYBACK_CONFIG_INVALID` never retried |
| `FreeSwitchVoiceMediaControllerTest` | 9 | `mockConstruction` | adapter calls `connect()` then `playFile`/`hangup`. **Proves nothing about the wire** — supplies a realistic absolute FS path, masking E-6 |
| `PlayfileLifecycleIntegrationTest` | 3 | **real PostgreSQL** (V1..V34) + mocked media | real DB + real `EslEventService` + real `PlayfileExecutionService`; reservation counts via native SQL. **ESL boundary mocked by design** — cannot detect E-5 |
| `VoiceBlastDailyDialLimitPostgresIntegrationTest` | 12 | real PG | 20 workers → exactly 3; cross-campaign sharing; per-DNID independence |
| `DailyAttemptConcurrencyPostgresIntegrationTest` | 11 | real PG | 20 workers → exactly 3; cross-campaign; restart; idempotency |
| `CampaignConfigurationSnapshotPostgresIntegrationTest` | 7 | real PG | snapshot immutability; DB refuses a snapshot-less execution |
| `CampaignResourceValidationPostgresIntegrationTest` | ~12 | real PG | audio/TTS/DID matrix; cross-tenant; non-leaking messages |
| `AudioUploadPostgresIntegrationTest` | 8 | real PG | upload→approve→ready; soft-delete invisibility |
| `ContactIdentityPostgresIntegrationTest` | 6+ | real PG | identity uniqueness incl. 20-way concurrency |
| `CampaignOpenApiContractTest` | 11 | OpenAPI | both daily ceilings; null semantics |
| `EslEventServiceFailureCodeTest` | 22 | pure mock | canonical-code invariant on persistence |

### 21.1 Coverage gaps

| Area | Gap |
|---|---|
| **ESL protocol** | **`EslClient` has no test of any kind.** No socket, no `ServerSocket`/`MockWebServer`/`WireMock` anywhere. `parseEvent` and `SUBSCRIBED_EVENTS` filtering: 0 coverage. D1-D4 are invisible to the suite. |
| **`CallEligibilityService`** | **no dedicated test class** — always mocked. The real blocklist/DNC/whitelist engine is only tested through `VoiceEligibilityDidSemanticsTest` (7, DID only) |
| **`scheduledTick` / orchestrator** | **0 tests** — no test file references it |
| **Pause** | **0 tests** — `PAUSED` never appears in a dial/retry test |
| **Recovery** | no test for stranded `IN_PROGRESS` |
| **Audio REST** | 0 slice tests for 7 of 8 endpoints; 0 OpenAPI contract tests |
| **TTS REST** | 0 OpenAPI contract tests |
| **Routing profiles** | all 4 suites hand-build in-memory lists → the unfiltered-collection bug (D-3) is invisible |
| **Resource deleted after snapshot** | proven at the validator level, not through `PlayfileExecutionService` |
| **`integrationConfig`** | 0 round-trip assertions |

**The dominant pattern: the campaign domain is tested against real PostgreSQL; the telephony
adapter is tested against mocks. Every critical defect in §17 lives in the untested half.**

---

## 22. Architecture

`ArchitectureTest` = `ApplicationModules.of(ObdApplication.class).verify()` + a PlantUML dump.
**1 test / 0 failures / 0 errors / 0 skipped → 0 cycles.** 15 modules, all verified.

| Module | Owns |
|---|---|
| `campaign` | campaign/execution/attempt lifecycle, retry policy, daily attempt safety, snapshot |
| `voice` | call/leg/session, routing, capacity, media port, DTMF collector, agent, ACD, queue, inbound |
| `telephony` | **ESL client, FreeSWITCH adapters, eligibility, gateway services** |
| `audio` / `tts` / `did` / `contact` | resource governance |
| `authz` / `security` / `tenant` / `reseller` / `account` / `identity` / `common` | platform |

**The dependency direction is correct and must be preserved:** `campaign → telephony → voice`, never
the reverse. Notably `campaign` already depends on `telephony` (`CallEligibilityService` lives there,
called from `OutboundDialService`), so **a protocol fix inside `telephony` adds no new module edge**
— the single most important architectural fact for scoping VB-6E. `voice.call.HangupCauseMapper` is
correctly placed in `voice`, shared by both consumers, with no `voice → campaign` edge.

**Two architecture observations:** `campaign.GatewayRouting` + `telephony.GatewayRoutingAdapter` are
**entirely dead** (zero injection sites). `AgentOutboundCallService` calls
`voiceRoutingService.resolveRoute` with **no eligibility call at all**, relying on routing step 2 as a
side effect — so the agent path gets blocklist/DNC *by accident of ordering*, and no whitelist.

---

## 23. Gaps / Risks

| ID | Gap | Current | Expected | Evidence | Severity | VB-6E? |
|---|---|---|---|---|---|---|
| **E-5** | ESL protocol: banner, `Reply-Text`, event framing, `bgapi` Job-UUID | cannot connect; drops all events | conformant client | `EslClient:64-69,119,127-181,211-227` | **CRITICAL** | ✅ **P0** |
| **SC-1** | Execution start requires a user on a scheduler thread | always `UNAUTHORIZED` | executions start | `Orchestrator:96,569-574`; `SecurityCurrentUserProvider:12` | **CRITICAL** | ✅ **P0** |
| **E-6** | Storage reference never mapped to a FreeSWITCH-readable path | `audio/<t>/<a>/f.wav` sent raw | resolvable path/URL | `LocalAudioStorage:76` → `EslClient:273` | **CRITICAL** | ✅ **P0** |
| **SC-2** | One `catch` wraps all 5 tick steps | 1 failure kills 4 steps | per-step isolation | `Orchestrator:379,402-404` | **CRITICAL** | ✅ **P0** |
| **T-1** | PLAYFILE+TTS configurable, valid, always fails | `PLAYBACK_CONFIG_INVALID` per call | rejected at write time, or TTS implemented | `CampaignService:396-399`; `PlayfileExecutionService:148-154`; `create-campaign-dialog.tsx:280` | HIGH | ✅ **P0** |
| **CA-2** | Routing rejections canonicalize to `HANGUP_UNKNOWN` → consume retry budget | budget burned on pre-dispatch | canonical pre-dispatch code | `OutboundDialService:238`; `CallFailureCode:203,328-329` | HIGH | ✅ **P1** |
| **§10** | Max call duration absent | no field, timer, or ESL limit | configurable, frozen, enforced | exhaustive search; `EslClient:210-211` | HIGH | ✅ **P1** |
| **R-2** | No attempt/execution sweeper | `IN_PROGRESS` stranded forever | 6 in-repo templates exist | `Orchestrator:347-352`; `CallAttemptRepository` surface | HIGH | ✅ **P1** |
| **SC-4** | `PAUSED` does not pause | attempts keep dialling | pause stops dispatch + retry | `CampaignReadinessService:100-108` never reached from dial/retry | HIGH | ✅ **P1** |
| **SC-3** | Blocking ESL I/O inside `@Transactional` both sides | up to 40 s × N per tx | I/O outside the tx | `Orchestrator:377,391,331`; `EslEventService:89` | HIGH | ✅ **P1** |
| **A-1** | Client-controlled `storageReference` reaches `uuid_broadcast` | unvalidated string | format-validated or upload-only | `CreateAudioAssetRequest:34` → `EslClient:273` | HIGH | ✅ **P1** |
| **D-1** | Profile-pinned DID bypasses ownership/status checks | only provider-string match | full `validateDid` | `VoiceRoutingService:313-326` | HIGH | ✅ **P1** |
| **CA-1** | Mid-playback hangup recorded as COMPLETED | `COMPLETED` | product decision | `EslEventService:481`; `PlayfileLifecycleEslTest:200-216` | HIGH | ⚠️ **OD-A** |
| **E-4** | `PLAYBACK_STOP` without `PLAYBACK_START` silently lost | session stuck `ANSWERED` | recovery path | `EslEventService:277-279,260-261` | MEDIUM-HIGH | ✅ **P1** |
| **D-3** | `route_type` written but never read | all 3 collections identical | filtered reads | `VoiceRouteProfile:64-74` | MEDIUM | ✅ **P1** |
| **D-2** | No write path for routing profiles / gateways | SQL seeding only | REST or explicit ops tool | `VoiceRouteProfile:76-92`; no controller | MEDIUM | ❌ defer (see §24) |
| **CT-3** | `RECURRING` never recurs | runs once | scheduler creates next execution | `CampaignService:457-458`; no code path | MEDIUM | ❌ defer |
| **CT-1** | "Audience frozen" javadoc false | membership re-checked per dial | docs match behaviour, or freeze it | `Orchestrator:165-168` vs `CallEligibilityService:61-65` | MEDIUM | ✅ **P1** (docs + dead param) |
| **CM-1** | Three eligibility engines; stack runs twice per dial | 2× cost, 2nd impl | one authority | `OutboundDialService:184` + `VoiceRoutingService:71` | MEDIUM | ⚠️ scope call (§24) |
| **CM-2** | `PhoneListService` dead; list checks unindexed | full-list scan per dial | indexed lookup | `PhoneListEntryRepository:30-43` unused | MEDIUM | ✅ **P1** (cheap) |
| **E-1** | `legs.get(0)` unordered | agent leg may be latched | type-filtered | `EslEventService:151-152` | MEDIUM | ✅ **P1** (cheap) |
| **R-1** | No `@Version`/CAS/locking | in-memory guards only | documented single-writer invariant, or CAS | 1 match repo-wide (a comment) | MEDIUM | ⚠️ scope call |
| **R-3** | Requeue keeps original `scheduledAt` | no backoff | recalculated | `OutboundDialService:469` | MEDIUM | ✅ **P1** (cheap) |
| **SC-5** | Single-threaded scheduler, no pool config | starvation risk | configured pool or documented | no `spring.task.scheduling.*` | MEDIUM | ✅ **P1** (cheap) |
| **API-2** | Audio/TTS: no OpenAPI contract or slice tests | uncovered | covered | 2 OpenAPI tests exist repo-wide | MEDIUM | ✅ **P1** |
| **S-1** | `configuration_snapshot_id` not UNIQUE | convention only | DB-enforced | `V44:62,70` | LOW | ✅ **P1** (cheap) |
| **CT-2** | N+1 + missing tenant predicate in group check | N queries per dial | one query | `CallEligibilityService:104-109` | LOW-MED | ✅ **P1** (cheap) |
| **A-2** | Audio REST untested | 0 slice tests | covered | `src/test` search | MEDIUM | ✅ **P1** |
| **A-3** | `409` documented, `500` delivered | wrong status | correct mapping | `AudioAssetController:85` | LOW | ✅ **P1** (cheap) |
| **A-4** | No file GC; MP3 duration always null | orphans accumulate | GC + MP3 probe | `AudioAssetService:132-144` | LOW | ❌ defer |
| **W-1** | `integrationConfig` unvalidated/untested | echoes arbitrary JSON | doc correction | `CampaignMapper:32,104` | LOW | ❌ defer (§12.1) |
| **E-2** | Non-unique `provider_call_id` + `Optional` | unhandled exception | unique, or list-returning | `V29:44,76`, `V23:12` | LOW | ❌ defer |
| **E-3** | `EslEventScheduler.shutdown()` never called | no `@PreDestroy` | lifecycle hook | `EslEventScheduler:81-92` | LOW | ✅ **P1** (trivial) |
| **CM-3** | 12 of 24 `VoiceRoutingReason` dead; 1 unreachable | dead code | remove or use | grep: declarations only | LOW | ❌ defer |
| §23 doc | 4 conflicting definitions of VB-6E | stale roadmap | one definition | `campaign-readiness.md:813` vs brief | MEDIUM | ✅ doc fix |
| §12 | Report privacy entirely absent | no config, log-only masking | config + response masking | exhaustive search | MEDIUM | ❌ defer (§13) |
| §18 | Per-operation `EslClient` (6 connections) | no pooling | pooled/shared | `EslClient` ×6 sites | LOW-MED | ⚠️ fold into P0 |
| Dead | `GatewayRouting`+adapter, 2 audio repo methods, 3 phone-list finders, 2 TTS repo methods, `holidayCalendarId`, `VoiceMediaController` recording/DTMF methods in both impls | unused | remove or implement | grep: declarations only | LOW | ❌ defer |

### 23.1 Open decisions requiring product input

| ID | Decision | Why engineering cannot infer it | Impact |
|---|---|---|---|
| **OD-A** | **Is a callee who hangs up mid-playback a delivered blast?** Currently `COMPLETED`, pinned by `p13`. `session.getStatus()` (`PLAYING` vs `PLAYBACK_COMPLETED`) is already available, so either answer is a ~5-line change — but *which* answer is correct is a product judgement. | "Delivered" vs "connected and cut off" is a business definition | reporting accuracy, retry behaviour, possibly campaign success metrics |
| **OD-B** | **PLAYFILE + TTS: reject at write time, or implement TTS synthesis?** The runtime already rejects it per call. Rejecting at configuration time is ~1 migration + 1 validation rule; implementing synthesis is a new subsystem. The brief forbids new campaign types but is silent on TTS runtime. | Scope | removes a silent 100 %-failure mode |
| **OD-C** | **Max call duration default and range.** The brief names the feature but not the value. Evidence available in-repo: audio is capped at 5 MiB and `durationSeconds` is parsed; VB-6D.3 set its platform ceiling to 10 by matching `retry_max_attempts`. No comparable precedent for seconds. | Product | needs a number before implementation |
| **OD-D** | **Should the eligibility stack run once or twice per dial?** (CM-1) De-duplicating means choosing which of the two gates is authoritative. | The two were added by different phases; neither is labelled canonical | performance + a duplicated safety gate |
| **OD-E** | **Multi-node deployment?** R-1 (no `@Version`/locking) and the single-threaded event loop are acceptable for one instance and unacceptable for several. | Deployment topology | whether CAS/locking is required now |

---

## 24. Proposed VB-6E Implementation Scope

Framed honestly: the brief asks for "PLAYFILE + common calling configuration to make PLAYFILE
production-ready". The audit's finding is that **PLAYFILE is not production-ready for a reason that
precedes every feature in the brief** — the pipeline cannot place or observe a call. So the scope
below leads with that, because shipping max call duration on top of a pipeline that cannot dial
would be building on sand.

### Must implement

**P0 — make the pipeline function (nothing else matters until these land)**
1. **Fix the ESL protocol layer** (E-5: D1 banner, D2 `Reply-Text`, D3 event framing/`Content-Length`,
   D4 `bgapi` → channel-UUID correlation incl. an outbound `CHANNEL_CREATE` path). Contained entirely
   inside `telephony` — **no new module edge**.
2. **Add a real protocol test**: a minimal in-process fake ESL **server** (`ServerSocket`) that
   replays genuine FreeSWITCH frames — banner, `command/reply` with `Reply-Text`, and
   `text/event-plain` with `Content-Length` bodies. This is the test whose absence let D1-D4 through,
   and it is the acceptance criterion for item 1.
3. **Fix execution start on a scheduler thread** (SC-1): stop deriving a user from
   `SecurityContextHolder` inside `startExecution`; use a system/execution identity.
4. **Isolate the tick's steps** (SC-2): per-step transaction boundaries and per-step exception
   containment so one failure cannot disable retries/dialing/ESL/reconciliation.
5. **Resolve the media URI** (E-6): map logical `storageReference` → a FreeSWITCH-readable path/URL
   via a single new boundary, with the sounds directory (or an object-storage base) as configuration;
   validate the reference shape so a client string can never reach `uuid_broadcast` (A-1).
6. **Reject PLAYFILE + TTS at configuration time** (T-1, subject to OD-B) so the silent 100 %-failure
   mode is impossible, rather than discoverable only in logs.

**P1 — the safety and correctness gaps**

7. **Max call duration** (§10, subject to OD-C): config → snapshot → enforcement, following the
   proven VB-6C.2/VB-6D.3 chain exactly, with the ESL `absolute_timeout`/scheduled hangup as the
   enforcement mechanism and a test-pinned terminal classification.
8. **Campaign attempt/execution sweeper** (R-2), mirroring `DtmfTimeoutScheduler` /
   `AgentConnectTimeoutScheduler`: fail stranded `IN_PROGRESS` attempts past a cutoff, release
   capacity, and let executions reconcile.
9. **Honour `PAUSED`** (SC-4): stop dispatch and retry for a paused campaign; defer rather than
   destroy pending executions.
10. **Canonical pre-dispatch code for routing rejections** (CA-2) so a routing failure stops consuming
    retry budget — this restores the VB-6D contract that the routing layer currently breaks.
11. **Move blocking ESL I/O out of the transaction** (SC-3) on both the tick and the event path.
12. **`PLAYBACK_STOP` recovery** (E-4): do not silently discard a completion whose start was missed.
13. **Ownership-check a profile-pinned DID** (D-1) with the canonical `validateDid` predicate.
14. **Filter routing collections by `route_type`** (D-3).
15. **Cheap correctness/perf batch:** type-filtered leg lookup (E-1); `scheduledAt` recalculation on
    requeue (R-3); scheduler pool configuration or a documented invariant (SC-5); index-backed phone
    list lookups (CM-2); remove the dead `contactGroupId` parameter (CT-1); UNIQUE on
    `configuration_snapshot_id` (S-1); `EslEventScheduler` lifecycle hook (E-3); correct the
    `AudioStorageException` → status mapping (A-3); one-query group membership check (CT-2).
16. **Tests:** the fake ESL server (item 2); audio/TTS slice + OpenAPI contract tests (API-2, A-2);
    first-ever tests for `CallEligibilityService`, `scheduledTick`, and pause-vs-dispatch; a sweeper
    recovery test.
17. **Documentation:** correct the four-way VB-6E scope conflict and the stale VB-6C/6D claims
    (`docs/VB-6-CAMPAIGN-AUDIT.md:191`), and the "audience is frozen" / "cron-configurable" javadocs.

### Already complete — do not touch

- Immutable execution snapshot (V44): `@Immutable`, `updatable=false`, NOT NULL FK, DB-refused
  snapshot-less execution, tenant-scoped resolution, no live fallback, **all tested on real PG**.
- Snapshot-vs-resource rule: pinned ids, dynamic approval/ownership/storage, enforced at 4 layers.
- VB-6C provider-accepted daily DNID limit — untouched, 12 real-PG tests, routing substitution pinned.
- VB-6D.3 daily attempt ceiling — 11 real-PG concurrency tests, cross-campaign, no `did_id`.
- Retry policy authority (`RetryPolicyService`, pure, 64+34+20 tests) and the failure taxonomy.
- Compliance precedence (blocklist → DNC → whitelist), 29 pre-dispatch codes, 404-cloaking,
  byte-identical non-leaking messages, composite tenant FKs on contact membership.
- Contact identity `(tenant_id, phone_number)` with a partial unique index and 20-way concurrency proof.
- Audio governance: magic-byte validation, SHA-256, tenant isolation, approval, 4-layer validation.
- Campaign CRUD/clone/readiness/execution/attempt API with full OpenAPI documentation.
- `PlayfileExecutionService` structure and the session→attempt failure propagation in
  `EslEventService:477-487`.
- 6 stale-reconciler patterns to imitate; 1327 green tests; 0 architecture cycles.

### Deferred — deliberately out of VB-6E

| Item | Why |
|---|---|
| **Webhook delivery** | A subsystem, not common calling configuration. §14 forbids building it absent explicit scope. Do the doc fix only. |
| **Report privacy config + response masking** | §15 forbids expanding reporting scope; PLAYFILE does not read it. Log-line masking is a separate hardening item. |
| **`integrationConfig` schema/validation** | No consumer exists; validating a field nothing reads adds no safety. |
| **`RECURRING` execution** | Real scheduler work; independent of PLAYFILE readiness. |
| **`holidayCalendarId`** | No HolidayCalendar module exists. |
| **Routing profile / SIP gateway REST** | Operability, not correctness. D-1 is mitigated by item 13 regardless. |
| **MP3 duration probe, file GC, checksum dedup** | Audio subsystem polish, not blocking PLAYFILE. |
| **Dead code removal** (`GatewayRouting`+adapter, 12 `VoiceRoutingReason` constants, unused repo methods) | Unrelated cleanup; do not bundle into a safety phase. |
| **Non-unique `provider_call_id`** | Low probability; would need a migration across three tables. |
| **Object storage for audio** | The `AudioStorage` seam already makes this a config change, not a code change. |

### VB-6F

- Reusable IVR / multi-level DTMF trees, nodes, transitions, campaign IVR selection.
- Any `ivrTreeId` concept. **Explicitly not started here**, and the stale `campaign-readiness.md`
  roadmap that places IVR in VB-6D should be corrected.
- Note `DtmfExecutionService` is a working single-level collector with `WAITING_FOR_DTMF`; it is the
  natural foundation, and its `typeConfig` is already strictly validated.

### Later

- TTS synthesis runtime (if OD-B chooses "implement" rather than "reject").
- Object storage / CDN-backed media.
- Carrier cause mappings (VB-6D's still-open OD-2) and a carrier entity.
- Multi-node hardening (`@Version`, leader election) if OD-E says multi-node.
- Contact-centre daily limits, omnichannel, AI voice, `MISSED_CALL`.

---

## 25. Proposed Implementation Sequence

**The entire remaining VB-6E scope is one coherent unit of work and should be delivered in a single
implementation phase.** Splitting it would be actively harmful here: the P0 items are only
*provable* together (a protocol fix without the fake-server test is unverifiable; a pipeline fix
without the media-URI fix still cannot play audio; a max-duration feature without the pipeline is
untestable).

Recommended internal order within that one phase — each step leaves the tree green:

| # | Step | Gate |
|---|---|---|
| 1 | **Fake ESL server test harness** (genuine FreeSWITCH frames) — written *first*, so it fails against today's code | D1-D4 each have a red test |
| 2 | **ESL protocol fix** | harness green; `connect`, `event plain`, `bgapi`→channel-UUID all proven |
| 3 | **Media URI resolution** + reference validation | `uuid_broadcast` proven to receive a resolvable path; A-1 closed |
| 4 | **Scheduler auth + per-step isolation** | execution starts from a `REQUESTED` row; a step-1 failure no longer skips steps 2-5 |
| 5 | **Move ESL I/O out of transactions** | no socket I/O inside a transaction (asserted) |
| 6 | **Reject PLAYFILE+TTS at write time** (OD-B) | API + readiness reject it; no test may configure it |
| 7 | **Attempt/execution sweeper** | stranded `IN_PROGRESS` reaped; capacity released; execution reconciles |
| 8 | **Honour `PAUSED`** | paused campaign: no dispatch, no retry, pending execution deferred not destroyed |
| 9 | **Max call duration** (OD-C) | config → snapshot → enforcement → classification, all pinned |
| 10 | **Routing pre-dispatch code + DID ownership + `route_type`** | CA-2, D-1, D-3 closed |
| 11 | **Cheap correctness/perf batch** (item 15) | each with a test |
| 12 | **New test coverage** (item 16) | first tests for eligibility, `scheduledTick`, pause; audio/TTS OpenAPI |
| 13 | **Full regression + architecture + OpenAPI verification + docs** | 1327+ green, 0 cycles, V52 chain clean |

**Decisions needed before step 1 can be scoped:** OD-B (TTS reject vs implement) and OD-C (duration
default). OD-A can be decided at step 9-10 and does not block. OD-D/OD-E are advisory.

---

## 26. Definition of Done

**P0 — the pipeline demonstrably works**

- [ ] A fake ESL **server** test replays genuine FreeSWITCH frames (auth banner, `command/reply` +
      `Reply-Text`, `text/event-plain` + `Content-Length` body) and the client passes it.
- [ ] `connect()` succeeds against a real handshake; the auth banner is consumed.
- [ ] Every command reply is read from `Reply-Text`, not from a string prefix.
- [ ] Events are framed by `Content-Length` and the event name is read from the **body**; a
      subscribed event is delivered to the handler with its headers intact.
- [ ] An outbound `originate` binds the **channel** UUID (not the job UUID) to
      `call_attempts.provider_call_id`, and a subsequent `CHANNEL_HANGUP` correlates to it.
- [ ] `uuid_broadcast` receives a path FreeSWITCH can resolve, produced by a single named boundary
      from the logical `storageReference`; the sounds/base location is configuration.
- [ ] A client-supplied `storageReference` cannot reach the ESL command (rejected or normalised).
- [ ] An execution created over REST reaches `RUNNING` and creates attempts **with no HTTP request
      in flight**.
- [ ] A failure in one tick step does not prevent the other four from running (tested).
- [ ] No network I/O occurs inside a `@Transactional` boundary (asserted by test or review).
- [ ] A PLAYFILE campaign cannot be created/activated with `contentMode=TTS` (or TTS synthesis
      exists end-to-end) — **no configuration can reach a guaranteed 100 % failure**.

**P1 — safety and correctness**

- [ ] Max call duration: configurable (null = platform default), validated, frozen into the snapshot,
      enforced, and exceeding it yields a pinned terminal classification. Rejected above the platform
      maximum at DTO, domain and DB layers.
- [ ] A stranded `IN_PROGRESS` attempt is detected past a cutoff, failed with a canonical code, and
      its capacity released; its execution reconciles instead of hanging in `RUNNING`.
- [ ] A `PAUSED` campaign dispatches nothing and retries nothing; a pending execution is deferred,
      not failed.
- [ ] A routing rejection persists a canonical **pre-dispatch** code and consumes **no** retry budget
      (with a test asserting a disabled-by-routing failure leaves the budget intact).
- [ ] `PLAYBACK_STOP` without a preceding `PLAYBACK_START` completes the call rather than stranding it.
- [ ] A profile-pinned DID that is foreign, `INACTIVE` or unassigned is rejected, not dialed.
- [ ] Routing honours `route_type` for the primary/overflow/failover passes.
- [ ] `legs.get(0)` is replaced by a type-filtered lookup; a requeued attempt gets a recalculated
      `scheduledAt`; scheduler threading is configured or its invariant documented; phone-list checks
      are index-backed; `configuration_snapshot_id` is UNIQUE; the dead `contactGroupId` parameter is
      gone; `EslEventScheduler` has a lifecycle hook; `AudioStorageException` maps to the documented
      status.
- [ ] OD-A is answered by product and the chosen behaviour is pinned by a test either way.

**Verification**

- [ ] Full Maven suite green with **exact** test/failure/error/skipped counts reported; no test
      deleted, `@Disabled`, excluded, or weakened; **all VB-6C (65), VB-6D.1 (107) and VB-6D.2 (68)
      tests still green**.
- [ ] `ArchitectureTest` 1/0/0/0 — **0 cycles**; no `campaign ↔ telephony` edge introduced.
- [ ] Flyway chain reaches the new head on a **clean** database.
- [ ] Every REST change documented in the **generated** OpenAPI and verified by a contract test;
      audio/TTS gain their first contract tests.
- [ ] New tests exist for: the ESL protocol (server-backed), `CallEligibilityService`,
      `scheduledTick`, pause-vs-dispatch, the sweeper, and max-duration classification.
- [ ] Documentation updated: this audit's findings closed out, the four-way VB-6E scope conflict
      resolved, and the stale claims in `docs/VB-6-CAMPAIGN-AUDIT.md:191` and the orchestrator
      javadocs corrected.
- [ ] Working tree reviewed; **no unrelated user change modified** (`docs/campaign-readiness.md`
      remains untouched and uncommitted).

---

*Audit produced read-only. The only file written is this document. `docs/campaign-readiness.md` was
read and left unmodified; nothing was staged or committed.*
