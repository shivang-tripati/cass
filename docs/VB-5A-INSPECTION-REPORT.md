# VB-5A — Inspection & Resource Architecture Report

## 1. Status

**INSPECTION COMPLETE** — read-only. No production code, migrations, or tests were modified.

| Item | Value |
|---|---|
| Git branch | `master` |
| Git status | No tracked-file modifications; only untracked project artifacts (docs/, frontend/, infra/, logs) |
| Build system | Maven wrapper (`./mvnw`), Java **17.0.12**, Spring Boot **4.1.0** |
| Full baseline run | **701 tests, 0 failures, 13 errors, 1 skipped** (`./mvnw clean test`) |
| Baseline errors | The 13 documented pre-existing errors carried since VB-0-era (same classes as VB-4F final report; no drift) |
| Flyway version | **V1 … V40** (40 migrations + README) |
| Architecture cycles | **2** pre-existing (`campaign → voice → telephony`, reverse) — unchanged since VB-4F |

> Note: an earlier partial surefire snapshot showed 515 tests; that was a stale incremental-compile artifact, re-established cleanly as above.

---

## 2. Existing Architecture

Top-level packages under `com.shivang.obd`:

| Package | Purpose | Key classes |
|---|---|---|
| `authz` | Capability-based authorization | `AuthorizationService`, `AccessCheck(resellerId, tenantId)`, `Scope{PLATFORM, RESELLER, TENANT}`, `OrganizationContextHolder` |
| `tenant` / `reseller` | Org hierarchy | `TenantEntity.resellerId`, `ResellerEntity`, `ResellerMembership*`, provisioning/signup |
| `identity` / `security` | Auth foundation | `SuperAdminBootstrapper`, `CurrentUserProvider`, `TokenService` |
| `campaign` | Campaign domain + execution | `CampaignService`, `CampaignReadinessService`, `PlayfileExecutionService`, `DtmfExecutionService` |
| `audio` | Audio asset registry | `AudioAsset{Entity,Service,Controller,Repository,Mapper,Specifications,Status}` |
| `tts` | TTS template registry | `TtsTemplate{Entity,Service,Controller,Repository,Validation,Status}` |
| `did` | Managed numbers | `Did{Entity,Service,Controller,Repository}`, `AllocationState`, `DidStatus`, `DidCapability` |
| `voice` | Canonical call domain + media seam | `voice.call.{CallSession,CallLeg,*}`, `voice.media.{VoiceMediaController,PlaybackTrigger,OutboundDialer}` |
| `telephony` | FreeSWITCH/ESL boundary | `EslClient`, `EslEventService`, `FreeSwitchVoiceMediaController`, `FreeSwitchOutboundDialer` |
| `contact` | Contact lists | incl. the platform's only `MultipartFile` usage (CSV import) |

Dependency direction (actual): `campaign → audio/tts/did/contact` (resource consumers); `campaign/voice → voice.media` (seam); `telephony → voice.media` (implements seam). Resources never depend on campaign.

---

## 3. Audio

**Existing:** a complete tenant-owned **metadata registry** (`/api/v1/audio-assets`): create, get, list (paginated, sorted, filtered), update, soft-delete, **PATCH approve/reject**.

- `AudioAssetEntity`: `tenantId`, `name`, `description`, `fileName`, `contentType`, `fileSize` (>0 CHECK), optional `durationSeconds`/`checksum` ("when known"), **`storageReference`** (nullable, "logical storage location for the future object-store integration").
- `AudioAssetStatus`: `PENDING_APPROVAL → APPROVED | REJECTED` (DB CHECK enforced).
- Authorization: `AUDIO_VIEW` / `AUDIO_MANAGE` / `AUDIO_APPROVE` (V1-seeded) via `AuthorizationService.requireCapability`; tenant queries scoped, reseller hierarchy visibility, platform unbounded; **404-cloaking** for foreign IDs.
- **Upload capability: NOT PRESENT.** No `MultipartFile`, no binary download/streaming, nothing ever writes `storageReference`. Metadata is registered by hand today.
- Campaign integration: create/update shape validation + readiness gate + runtime re-validation (see §10).
- **Finding: the model was explicitly designed for storage to be added later; extending it is the intended path.**

## 4. PLAYFILE

- Config: `CampaignEntity.audioAssetId` + `contentMode = AUDIO` (exclusive with TTS; enforced at create/update in `CampaignService.validateContent` — shape only, no existence check there).
- Activation: `CampaignReadinessService.checkAudioAsset` requires asset exists + same tenant + not deleted + **APPROVED** (`AUDIO_NOT_APPROVAL` reason `AUDIO_NOT_APPROVED`).
- Runtime: `PlayfileExecutionService.onAnswered` (VB-1) re-validates campaign type/mode, **tenant ownership**, **APPROVED**, and **non-blank `storageReference`**; failure → `PLAYBACK_CONFIG_INVALID` + teardown (the call is hung up and finalized via the authoritative hangup path).
- FreeSWITCH boundary: `VoiceMediaController.playAudio(sessionId, legId, storageReference)` → `FreeSwitchVoiceMediaController` → `EslClient.playFile` → `uuid_broadcast <channelUuid> <path> aleg`. **FreeSWITCH receives the raw `storageReference` string; FS knows nothing of tenants/approval.**
- Deleted/rejected audio **cannot** execute (activation + runtime gates). Metadata-only assets with blank `storageReference` fail fast per-call with config-invalid.
- **Finding: no local-filesystem coupling in Java** — the path/URI convention is deferred to the FreeSWITCH deployment. What is missing is *populating* `storageReference` with a real file.
- Minor gap: the readiness gate does **not** check `storageReference` presence — campaigns referencing never-uploaded assets activate, then fail per-dial. Small extension recommended.

## 5. TTS

- **Present as a definition/approval registry** (`/api/v1/tts-templates`): `templateText` + typed variable schema (JSONB, `{{var}}` placeholders validated against declarations), same `PENDING_APPROVAL/APPROVED/REJECTED` lifecycle, platform-created templates default APPROVED, tenant-created start PENDING_APPROVAL.
- **Synthesis: NOT PRESENT.** No provider abstraction (Google/Polly/ElevenLabs/etc.), no audio output, no campaign runtime TTS path. Rendering is explicitly deferred to a future execution layer.
- **GLOBAL scope: NOT representable.** `tts_templates.tenant_id` is `NOT NULL` — "global" today means platform seeds APPROVED templates into a chosen tenant. True cross-tenant global templates need a model decision (platform pool with nullable tenant + CHECK, or per-tenant replication).
- **Finding: governance (approval, validation, tenant scoping) already matches the VB-5 requirements; scope and synthesis are the open decisions.**

## 6. DID / DNID

- `DidEntity`: `tenantId` (nullable), `resellerId` (nullable), canonical `e164Number` (**partial unique** `uq_dids_e164_live WHERE deleted_at IS NULL`, format CHECK), `NumberType`, `provider`, `DidStatus{ACTIVE,INACTIVE}`, JSONB `capabilities`, `AllocationState{AVAILABLE,ASSIGNED}`, VB-4D inbound-destination triple.
- Ownership model (actual): **platform pool** (both null) / **reseller pool** (`resellerId` only) / **assigned to exactly one tenant** (`ASSIGNED` ⇒ `tenant_id NOT NULL`, DB CHECK). Tenants link back via `tenants.reseller_id`. This is exactly the SUPER_ADMIN → reseller → tenant hierarchy the phase asks about, with platform scope unbounded.
- "Can this tenant use this DID?" is decided by: scoped repository lookups (tenant → own DIDs; reseller → pool + managed tenants; platform → all) + `DID_VIEW`/`DID_MANAGE` capabilities + campaign readiness (`ACTIVE` + `ASSIGNED` + same tenant) + voice eligibility (CLI DID must be tenant-owned ACTIVE+ASSIGNED).
- Assignment lifecycle: ownership is **immutable after create** (`UpdateDidRequest` intentionally has no `tenantId`/`resellerId`), so silent reassignment is structurally impossible today — but there is also **no explicit assign/revoke/transfer API**. Registering an ASSIGNED DID directly is the only path.
- Concurrency: **no `@Version` anywhere**, no conditional-UPDATE guards on DID state. Safe today only because ownership is immutable. Any future assign/revoke flow **must** use a PostgreSQL conditional `UPDATE ... WHERE allocation_state = 'AVAILABLE'` (repo convention) rather than a read-check-write.
- Billing provenance: `resellerId` on DID + tenant hierarchy exist; no billing ledger tables (not required for MVP).

## 7. Tenant / Reseller Ownership

- Enforcement is **service-layer primary**: server-derived `OrganizationContextHolder` scope, scoped queries (404-cloaking), capability checks at the boundary; controllers are thin; repositories expose scoped finders. This pattern is **uniform** across `audio`, `tts`, `did`, `contact`, `campaign`.
- Reseller scope = own pool resources + resources of active tenants under the reseller (`findAllByResellerIdAndStatus`).
- No resource among Campaign/Audio/TTS/DID/Agent/Queue was found to cross tenant boundaries; VB-4F's isolation tests remain green in the baseline run.

## 8. Approval / Status Enums (inventory)

| Enum | Values | Kind |
|---|---|---|
| `AudioAssetStatus`, `TtsTemplateStatus` | PENDING_APPROVAL / APPROVED / REJECTED | **approval-only** |
| `DidStatus` | ACTIVE / INACTIVE | operational |
| `AllocationState` (did) | AVAILABLE / ASSIGNED | allocation |
| `LifecycleStatus` (common) | ACTIVE / INACTIVE / (DISABLED) | org lifecycle |
| `AgentAdminStatus` / `AgentAvailability`, `QueueStatus`, `AgentReservationStatus`, `CallSessionStatus`, `CallLegStatus`, campaign lifecycle (V14/V15) | … | VB-4 domains |

**Conclusion: approval and operational lifecycle are already separate concepts per resource.** Audio/TTS approval must reuse `PENDING_APPROVAL/APPROVED/REJECTED` — do not invent a new enum. DID lifecycle should extend/keep `DidStatus` + `AllocationState`; adding values (e.g. SUSPENDED) is a VB-5B decision.

## 9. Storage

- **No storage abstraction exists.** No `StorageService`/object-storage client; no `java.nio.file` usage in main code. The only multipart handling is the contact **CSV import** (in-memory stream), which proves Spring multipart is configured.
- `audio_assets.storage_reference` is the single intended seam: a logical string handed verbatim to FreeSWITCH.
- **Key architectural answer: yes — local storage today, S3 later, with zero campaign/business-logic change**, because Java never touches files; whatever gets written into `storage_reference` (filesystem path, then later `http(s)://` URL) is what FS receives. The coupling point is *the future upload code path only*.
- Do **not** build a generic storage framework. If VB-5B adds upload, add one narrow `AudioStorage` seam inside `audio/` with a local-filesystem implementation — nothing more.

## 10. Campaign Validation

| Stage | What happens |
|---|---|
| Create/Update | `CampaignService.validateConfiguration`: content exclusivity, type-config JSON shape, schedule coherence, retry policy. **Syntactic only** — does not verify referenced resources exist. |
| Activation | `CampaignReadinessService`: semantic + tenant-aware + lifecycle-aware checks (contact group, DID ACTIVE+ASSIGNED, audio APPROVED, TTS APPROVED, schedule window) |
| Runtime | `PlayfileExecutionService` / `DtmfExecutionService` / VB-3 connect path re-validate ownership + status; failures are classified config-invalid (permanent) vs transient |

A campaign can reference a resource that later becomes deleted/rejected — activation blocks it, and runtime fails safely with teardown. Runtime revalidation **already exists**; the only addition worth making is the `storageReference` presence check at readiness (§4).

## 11. FreeSWITCH Boundary

- Inbound media: `uuid_broadcast <uuid> <path> aleg`; playback completion/failure event-driven (`PLAYBACK_START/STOP` → `EslEventService` → `PlaybackTrigger`); teardown via `uuid_kill`.
- FS is **not** aware of tenants, ownership, or approval — it receives a bare path/URI. Java resolves nothing binary. **This is the preferred boundary; preserve it.**

## 12. Database / Migrations

Relevant migrations: **V1** (authz foundation incl. capability seeds, e.g. `AUDIO_APPROVE`), **V9/V14/V15** (campaign + lifecycle/scheduling), **V16** (dids + checks + partial-unique e164), **V20** (`audio_assets`, `tts_templates` + status CHECKs + tenant FKs + `(tenant_id, deleted_at)` indexes), **V24** (sip gateways), **V25** (phone lists), **V29–V40** (voice core → ACD → inbound DID destination → reservation uniqueness).

Current schema version: **V40**. All three resource tables already carry tenant FKs, soft-delete audit columns, status CHECK constraints, and operational indexes.

## 13. Authorization

Capability-based (`AuthorizationService.requireCapability(userId, capability, AccessCheck)`), with `AccessCheck{platformWide, forReseller, forTenant}` and scope derived server-side. Resource capabilities follow the `<RESOURCE>_VIEW/_MANAGE` (+ `_APPROVE` for governance) convention. Roles map to capabilities seeded in V1 (`SUPER_ADMIN` bootstrapped via `SuperAdminBootstrapper`). No `@PreAuthorize` sprawl — the service boundary is the enforcement point.

## 14. Architecture Cycles

2 pre-existing cycle groups (`campaign ↔ voice ↔ telephony`), documented since VB-4E/VB-4F; no audio/tts/did classes are involved. Future `campaign → audio/tts/did` edges already exist and point the correct direction. **Rule for VB-5B: new FS-facing implementations must implement `voice.media` interfaces from `telephony`; new storage code must live in `audio/` (or a leaf package) so no new cycle can form.**

## 15. Resource Dependency Map (actual)

```text
Campaign ──┬── AudioAsset   (campaigns.audio_asset_id → audio_assets)
           ├── TtsTemplate  (campaigns.tts_template_id → tts_templates)
           ├── DID          (campaigns.did_id → dids)
           └── ContactGroup
AudioAsset ── (no binary storage today; storage_reference nullable string)
DID ── Inbound destination (VB-4D) / CLI routing (VB-0/VB-4E)
Runtime: PlayfileExecutionService → VoiceMediaController(voice.media)
         → FreeSwitchVoiceMediaController(telephony) → EslClient → uuid_broadcast
```

## 16. Gap Analysis

| # | Finding | Category |
|---|---|---|
| 1 | Tenant scoping + 404-cloaking + capability model across audio/tts/did | **A — correct** |
| 2 | Audio/TTS approval lifecycle (status enum, CHECK constraints, APPROVE capability, platform-default-approve for TTS) | **A — correct** |
| 3 | Runtime re-validation of audio (tenant + APPROVED + storage ref) | **A — correct** |
| 4 | FS media boundary (FS receives bare path; no business leak) | **A — correct** |
| 5 | Audio binary upload / download / storage write / `storageReference` population | **C — missing, required** |
| 6 | Duration/checksum/MIME extraction from real media (fields exist, optional) | **C — partially missing** |
| 7 | DID explicit assign/revoke/transfer lifecycle (AVAILABLE-guarded, concurrency-safe) | **C — missing, required by VB-5 goals** |
| 8 | Audio/TTS CRUD + registry model + campaigns referencing by ID | **D — reuse as-is** |
| 9 | `audio_assets.storage_reference` as the storage seam; DID scoped finders | **E — extend** |
| 10 | `AudioAssetService/Controller` → add multipart upload; `DidService` → add lifecycle ops; readiness → storage-ref check | **E — extend** |
| 11 | No optimistic locking on DID (safe only while ownership immutable) — assign/revoke must use conditional UPDATE | **F — risk to address in design** |
| 12 | Readiness gate misses blank `storageReference` (wasted dials, per-call failure) | **F — small fix** |
| 13 | TTS synthesis, TTS GLOBAL scope, generic storage framework, provider frameworks, workflow engines | **G — not MVP / defer decision** |

## 17. Minimal Recommended Architecture (for VB-5B review — NOT implemented)

1. **Audio upload (VB-5B core):** extend `AudioAssetController/Service` with multipart upload; one narrow `AudioStorage` interface in `audio/` (local-filesystem impl, `@ConditionalOnProperty`); validate content-type/size; compute checksum (+ duration only if a lightweight extraction is already available — otherwise leave optional); write `storageReference`; keep approval flow unchanged (upload never auto-approves). **Likely no migration needed** — columns already exist.
2. **DID assignment lifecycle (VB-5C candidate):** add explicit assign/revoke endpoints scoped to platform/reseller; enforce `AVAILABLE`-only assignment via PostgreSQL conditional UPDATE; keep ownership immutability otherwise; no new locking tech.
3. **Campaign readiness extension:** add `AUDIO_STORAGE_REFERENCE_MISSING` check (one method in `CampaignReadinessService`).
4. **TTS:** defer synthesis; defer GLOBAL-scope decision to product review — document rather than build.

Every proposed abstraction: extends an existing class, adds no module, no new dependency edge beyond existing directions, no cycle, and is PostgreSQL-testable with the existing harness.

## 18. Proposed Future Phase Breakdown (derived from inspection)

- **VB-5B — Audio upload & storage wiring** (§17.1 + §17.3): smallest, highest-value; makes PLAYFILE actually executable end-to-end.
- **VB-5C — DID assignment lifecycle** (§17.2): conditional-UPDATE design first, endpoints second.
- **VB-5D — TTS decisions** (global scope, synthesis) — only if a product requirement lands.

## 19. Explicit Non-Goals (this phase and near-term)

No microservices, Kafka/Redis/K8s, event sourcing/CQRS, generic resource/workflow/policy frameworks, plugin systems, AI resource selection, generic multi-provider storage/cloud SDK abstraction, TTS synthesis, IVR/ring groups, recording, WebRTC/Android SIP.

## 20. Final Verdict

**READY FOR VB-5B**

The platform already contains the canonical resource layer (audio/TTS/DID registries with tenant ownership, approval lifecycle, capability authorization, and campaign validation at every stage). VB-5B should **extend** this layer — upload/storage wiring for audio plus the readiness storage-check — rather than create any second abstraction.
