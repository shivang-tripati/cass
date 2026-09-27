# VB-6A — Campaign Configuration Hardening — Implementation Report (Corrected)

## 1. Status

**VB-6A CORRECTION — COMPLETE.** The VB-6A implementation was corrected from its first-pass
design: campaign configuration **versioning was removed**, the **legacy live-campaign
fallback was removed**, executions now own a **mandatory immutable configuration snapshot**
(NOT NULL FK), and campaign **editability is lifecycle-gated** (DRAFT-only). Typed
configuration, the failure taxonomy, dynamic resource validation, tenant isolation, and
architecture cycle-freedom are preserved unchanged.

Final regression: **886 tests / 0 failures / 0 errors / 1 skipped — BUILD SUCCESS**
(baseline 855 → first pass 880 → corrected 886, +6 new editability tests). Flyway head
stays **V44** (one migration, rewritten in place — justification in §11). ArchitectureTest
remains cycle-free. No test was disabled, weakened, or removed.

## 2. Baseline vs final

| Metric | Baseline (pre-VB-6A) | First pass | **Corrected (final)** |
|---|---|---|---|
| Total tests | 855 | 880 | **886** |
| Failures / errors | 0 / 0 | 0 / 0 | **0 / 0** |
| Skipped | 1 (pre-existing) | 1 | **1 (same, pre-existing)** |
| Build result | BUILD SUCCESS | BUILD SUCCESS | **BUILD SUCCESS** (`clean test`, 3:56 min) |
| Flyway head | V42 | V44 (`campaign_configuration_versions`) | **V44 (`campaign_execution_configurations`)** |
| Snapshot ownership | none (live campaign reads) | version rows + nullable FK | **execution-owned snapshot, NOT NULL FK** |
| Editability | status checks absent from update | same as baseline | **DRAFT-only, single policy boundary** |

Evidence: baseline `/tmp/vb6a_baseline.log` (exit 0, reproduced exactly); final run this
session — `MAVEN_OPTS="-Xmx768m -XX:MaxMetaspaceSize=384m" ./mvnw clean test` from `backend/`,
`Tests run: 886, Failures: 0, Errors: 0, Skipped: 1`, exit 0.

## 3. Scope and strict rules applied

Implemented in the correction:

- Removal of campaign configuration versioning (entity, repository, MAX+1 allocation,
  per-campaign version sequences, unique `(campaign_id, configuration_version)` constraint).
- Removal of the legacy live-campaign execution fallback from the configuration resolution path.
- Execution-owned immutable snapshots: `campaign_execution_configurations` table,
  `campaign_executions.configuration_snapshot_id NOT NULL` FK, snapshot-first creation.
- Lifecycle-gated editability via a single `CampaignLifecyclePolicy` boundary wired into
  `CampaignService.update`.
- Preserved untouched: `CampaignTypeConfig` sealed hierarchy + validator +
  `ConfigSchemaVersion`; `CallFailureCode` taxonomy (behavior-identical retry classes,
  one additive code); dynamic resource validation (DID/audio/TTS stay runtime-checked);
  tenant isolation on every new read; Modulith cycle-freedom.

Strict rules honored: no test disabled/weakened/deleted; no ArchUnit/Modulith change; no
daily limits/IVR/MISSED_CALL/webhooks/privacy/max-duration/scheduler work; **VB-6B not
started**.

## 4. Why campaign configuration versioning was removed

The first pass modeled the snapshot as a *versioned campaign history*
(`CampaignConfigurationVersion`, `configuration_version INTEGER` allocated as
`MAX(version)+1` per campaign, `UNIQUE (campaign_id, configuration_version)`). That model
was rejected on correction for concrete reasons:

1. **The requirement is per-execution isolation, not campaign history.** Nothing reads a
   campaign's version sequence: no API, no UI, no report lists "configuration version 3 of
   campaign X". The version number is state with zero consumers — cost without function.
2. **MAX+1 allocation is a serialization hotspot.** Every `execute()` pays a
   `SELECT MAX(...) FOR UPDATE`-style race resolution against a unique constraint; concurrent
   executions of one campaign contend on a single counter row even though the snapshots
   themselves are independent. Removing the counter removes the entire allocation-race
   surface — snapshot uniqueness is now the row's own UUID, so concurrency is trivially safe
   by construction (the dedicated 8-thread CFG-D version-race test became unnecessary).
3. **Versioning implies semantics the domain does not have.** A "version" suggests draft vs
   published revisioning of the campaign itself. The product rule is the opposite: the
   campaign has exactly one current configuration (editable while DRAFT), and each execution
   freezes *what it was created with*. That is an execution-owned snapshot, not a campaign
   revision — naming and schema now say so (`CampaignExecutionConfiguration`).

The separate-concept rule remains honored: the clone-lineage `CampaignEntity.version` column
was never touched by VB-6A code in either pass.

## 5. Why the legacy live-campaign fallback was removed

The first pass kept `CampaignRuntimeConfigResolver` resolving live-campaign configuration
when an execution had no snapshot reference ("legacy rows"). The correction removes the
fallback because a fallback defeats the guarantee it exists to protect:

- A running execution must **never silently adopt configuration it was not created with**.
  A fallback makes that exact failure possible and invisible: a corrupt or fabricated
  snapshot reference degrades into "use whatever the campaign says today", changing audience,
  DID, content, retry, and schedule semantics mid-flight with no error anywhere.
- Under the corrected model a missing snapshot is **impossible under normal operation**:
  the snapshot row is inserted in the same transaction as the execution, and the database
  enforces `configuration_snapshot_id NOT NULL` with a FK. Therefore a missing snapshot is
  not a runtime condition to tolerate — it is a **data-integrity violation**, and it fails
  closed deterministically.
- The deterministic failure is `ExecutionConfigurationMissingException`
  (INTERNAL_SERVER_ERROR, "Execution configuration snapshot is missing (data integrity
  violation)") at the resolver seam, and `CallFailureCode.EXECUTION_CONFIG_MISSING`
  (PERMANENT — no retry can succeed) on the dial path. Foreign snapshots are
  indistinguishable from missing ones (tenant-scoped lookup), so the fail-closed path also
  covers cross-tenant corruption.

Resolution is now single-arg and total: `resolve(CampaignExecution)` → snapshot or throw.
There is no second code path.

## 6. Execution-owned immutable configuration snapshot (design)

**Shape**: dedicated table `campaign_execution_configurations` with the payload as a typed
`@Embeddable` (`CampaignConfigurationSnapshot`); `campaign_executions` carries a plain
`configuration_snapshot_id UUID NOT NULL` FK + index
(`idx_campaign_executions_configuration_snapshot`), consistent with the codebase's
UUID-reference style (no JPA association).

- `CampaignExecutionConfiguration` — `@Entity`, JPA `@Immutable`, extends
  `AuditableEntity`; factory-only creation (`materialize(campaignId, tenantId,
  configuration, createdAt)`, package-private); no update API exists. Created **before** the
  execution row, **inside the same transaction** — execution and snapshot commit atomically
  (no execution without a snapshot; no orphan snapshot without an execution, §14 rule).
- `CampaignExecutionConfigurationRepository` — the single lookup
  `findByIdAndTenantId(id, tenantId)`; every read is tenant-scoped.
- `CampaignExecution.configurationSnapshotId` — `nullable = false, updatable = false`;
  the execution can never swap or lose its snapshot.
- `CampaignConfigurationService` — `createExecutionSnapshot(CampaignEntity)` (strict
  typeConfig validation, no allocation) and `requireExecutionSnapshot(CampaignExecution)`
  (tenant-scoped, throws the integrity exception). `toSnapshot(campaign, validatedTypeConfig)`
  maps the live campaign's configuration into the payload.
- `CampaignRuntimeConfigResolver` — takes `CampaignConfigurationService` (not the
  repository — one seam); returns the immutable `CampaignRuntimeConfig` record
  (12 fields unchanged: campaignId, campaignType, contactGroupId, didId, contentMode,
  audioAssetId, ttsTemplateId, callOnWhitelistNumbers, retryPolicy, schedule,
  typeConfigSchemaVersion, typeConfig; `asDtmf()` helper kept). Runtime services never
  decide snapshot-vs-live themselves — there is no live branch to decide.

DTO/API: `CampaignExecutionResponse.configurationSnapshotId` (additive field, NOT NULL for
every row created by the corrected model; the field is mandatory, not optional).

## 7. Snapshot boundary

Snapshot = **what the campaign requested**. Resource validity = **whether that request is
currently allowed**. All runtime validations remain fully dynamic
(`CampaignResourceValidationService`, `VoiceEligibilityService`, DID governance, audio
approval/storage, TTS scope/approval, phone-list compliance, gateway capacity).

Field classification (unchanged from the audited §46 checklist, from `CampaignEntity`):

| Field | Classification | Rationale |
|---|---|---|
| `campaignType` | SNAPSHOT_REQUIRED | drives type-aware runtime behavior |
| `contactGroupId` | SNAPSHOT_REQUIRED | audience provenance + eligibility membership |
| `didId` | SNAPSHOT_REQUIRED (reference) | CLI/routing request; validity stays runtime-checked |
| `contentMode` | SNAPSHOT_REQUIRED | content semantics (AUDIO/TTS) |
| `audioAssetId` / `ttsTemplateId` | SNAPSHOT_REQUIRED (reference) | playback request; approval/storage stay runtime-checked |
| schedule window (6 fields + holiday ref) | SNAPSHOT_REQUIRED | retry scheduling reads it per retry |
| `retryPolicy` (maxAttempts/intervalSeconds/strategy) | SNAPSHOT_REQUIRED | retry loop consumes it per retry |
| `typeConfig` | SNAPSHOT_REQUIRED (canonical validated JSON) | DTMF parse + future type configs |
| `callOnWhitelistNumbers` | SNAPSHOT_REQUIRED | eligibility behavior flag |
| `integrationConfig` | excluded (ADMIN_ONLY) | zero consumers; snapshotting it would invent semantics |
| `name`, `description` | excluded | no execution effect |
| `status`, lifecycle fields | RUNTIME LIFECYCLE (live) | readiness gate reads live status at execution start, unchanged |
| `version`, `clonedFromCampaignId` | excluded | clone lineage |
| audit columns | excluded | — |

## 8. Typed configuration model (preserved)

Unchanged by the correction (validated strictly at snapshot creation instead of at
version-creation):

- `CampaignTypeConfig` sealed interface — `campaignType()`, `schemaVersion()`, `toJson()`
  (compatibility codec), static per-type dispatch `fromTypeConfig(type, payload)`.
- `PlayfileCampaignConfig` — absent/null/empty-object accepted, non-empty rejected
  deterministically.
- `DtmfCampaignConfig` — delegates to the existing strict `DtmfConfig` parser (rules and
  JSON shape unchanged); `toDtmfConfig()` keeps a single source of truth.
- `ConnectByAgentCampaignConfig` — legacy write contract preserved exactly.
- `ConfigSchemaVersion` — `V1` only; `CampaignTypeConfigValidator` remains the single
  structural/type validation ownership boundary; `CampaignConfigInvalidException` remains
  the deterministic typed failure.

Resolver detail: a corrupted snapshot payload cannot crash the runtime path earlier than it
always did — `parseTypeConfig` degrades to the raw-payload null view so DTMF parsing still
fails at call time (where the pre-VB-6A behavior lived), while a *missing* snapshot row
fails at the resolver (§5). Both are deterministic; neither invents configuration.

## 9. Lifecycle-gated editability rule

**Actual lifecycle** — `CampaignStatus` has exactly **7 states** and there is **no READY
status**:

```
DRAFT ──→ SCHEDULED ──→ RUNNING ──→ COMPLETED/FAILED ──→ ARCHIVED
   ↑           │  ↑        │
   └───────────┘  │        └──→ PAUSED ──→ SCHEDULED/RUNNING
   (unlock edge)  └───────────┘
```

(`LEGAL_TRANSITIONS` in `CampaignService`; SCHEDULED→RUNNING, RUNNING→COMPLETED/FAILED are
system-driven and rejected on the manual API.)

**Editability rule** (VB-6A correction):

- **DRAFT is the only editable state** — it is the configuration state.
- **SCHEDULED is the "READY" state**: it is the validated, execution-ready configuration
  ("READY is not editable" — since no READY status exists, SCHEDULED is that state).
  Editing a scheduled campaign's configuration would silently change what future executions
  run, bypassing the re-validation that the DRAFT→SCHEDULED edge performs.
- RUNNING/PAUSED/COMPLETED/FAILED have execution history that must not be rewritten under
  them; ARCHIVED is terminal.
- A scheduled campaign that needs changes is **explicitly unlocked** via the existing legal
  **SCHEDULED → DRAFT** edge (which re-enters the editable state; DRAFT→SCHEDULED re-runs
  the full activation gate). No versioning, no cloning, no new statuses.

**Enforcement in one place**: `CampaignLifecyclePolicy` (`@Service`),
`EDITABLE_STATUSES = Set.of(DRAFT)`, `assertEditable(campaign)` throws `ConflictException`
(409) *"Campaign configuration can only be modified while the campaign is in DRAFT state
(current: X)."* — naming the current state so the client knows the unlock action. Wired into
`CampaignService.update` **after authorization, before validation** (new final constructor
arg). Status transitions remain governed by `LEGAL_TRANSITIONS` + `CAMPAIGN_EXECUTE`
exactly as before; the policy adds no second lifecycle model.

Locked by the new `CampaignEditabilityTest` (6 tests): the DRAFT-only matrix over all 7
statuses, the 409 message naming the state, a DRAFT update succeeding with AUDIO content
stubbed, SCHEDULED rejected, every non-DRAFT status rejected, and post-unlock editing
working after SCHEDULED→DRAFT.

## 10. Failure taxonomy (behavior-identical)

`CallFailureCode` consolidates every machine-readable failure code (inventory from all
`setFailureCode` producers). The correction adds exactly one constant:

- `EXECUTION_CONFIG_MISSING(RetryClass.PERMANENT)` — after `CAMPAIGN_NOT_FOUND`; the
  execution's snapshot could not be resolved (data-integrity violation; no retry can
  succeed). All other constants and their classes are untouched.

Totals: **43 constants**, **10 PERMANENT** — `REJECTED`, `DIAL_FAILED`,
`PLAYBACK_CONFIG_INVALID`, `DTMF_CONFIG_INVALID`, `AGENT_CONFIG_INVALID`,
`AGENT_ENDPOINT_INVALID`, `AGENT_TENANT_MISMATCH`, `CONNECT_BY_AGENT_UNSUPPORTED` (reserved
by the legacy gate), `CAMPAIGN_NOT_FOUND`, `EXECUTION_CONFIG_MISSING`. Everything else is
TEMPORARY; null/unknown codes classify TEMPORARY exactly like the legacy predicate.

Persistence stays string-based (no column/enum migration, historical values readable,
`fromCode` → `Optional.empty()` for unknown). `CampaignExecutionOrchestrator.isPermanentFailure`
delegates to `CallFailureCode.isPermanent` — retry behavior identical to baseline, locked by
`CallFailureCodeTest` (legacy-set parity, unknown-code determinism, producer inventory,
round-trip) and `PlayfileRetrySemanticsTest` (P21/P22/BUSY/max-exhausted).

## 11. Database changes

**Flyway head stays V44** with one migration file:
`V44__campaign_execution_configurations.sql` (the first pass's
`V44__campaign_configuration_versions.sql` was deleted; only one V44 exists on disk).

```sql
CREATE TABLE campaign_execution_configurations (
    id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL REFERENCES campaigns (id),
    tenant_id   UUID NOT NULL REFERENCES tenants (id),
    campaign_type VARCHAR(30) NOT NULL,
    contact_group_id UUID, did_id UUID,
    content_mode VARCHAR(10), audio_asset_id UUID, tts_template_id UUID,
    schedule_start_date DATE, schedule_end_date DATE,
    daily_start_time TIME, daily_end_time TIME, timezone VARCHAR(64),
    allowed_days_of_week JSONB, holiday_calendar_id UUID,
    retry_max_attempts INTEGER NOT NULL DEFAULT 0,
    retry_interval_seconds INTEGER,
    retry_strategy VARCHAR(20) NOT NULL DEFAULT 'FIXED',
    type_config JSONB,
    call_on_whitelist_numbers BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(255), updated_at TIMESTAMPTZ, updated_by VARCHAR(255),
    deleted_at TIMESTAMPTZ, deleted_by VARCHAR(255)
);
CREATE INDEX idx_cec_campaign ...; CREATE INDEX idx_cec_tenant ...;
ALTER TABLE campaign_executions ADD COLUMN configuration_snapshot_id UUID NOT NULL;
ALTER TABLE campaign_executions ADD CONSTRAINT fk_campaign_executions_configuration_snapshot
    FOREIGN KEY (configuration_snapshot_id) REFERENCES campaign_execution_configurations (id);
CREATE INDEX idx_campaign_executions_configuration_snapshot ...;
```

Differences vs the first pass: no `configuration_version` column, no
`UNIQUE (campaign_id, configuration_version)` constraint, and the execution FK is
**NOT NULL** (was nullable for the legacy fallback).

**Rewrite-in-place justification (process deviation, deliberate):** the repository has
**zero commits** (nothing from VB-6A was ever committed), and the only environment the first
V44 had touched was the dev database (`obd-postgres`), which held 0 executions/snapshot rows.
Rewriting an uncommitted migration avoids polluting history with a migration that only ever
described a rejected design (§18 rule: uncommitted migrations may be corrected in place).
The dev DB was manually reset to the pre-V44 state (`ALTER TABLE campaign_executions DROP
COLUMN IF EXISTS configuration_version_id; DROP TABLE IF EXISTS
campaign_configuration_versions; DELETE FROM flyway_schema_history WHERE version='44';`)
and the corrected V44 was applied through the normal Flyway chain. No other environment,
no data, and no historical migration (V1..V43) was touched.

## 12. Execution flow changes

```
execute() [same transaction]:
  authz → readiness (live campaign, unchanged)
  → createExecutionSnapshot(campaign)      [validate typeConfig strictly, insert immutable row]
  → save execution(configurationSnapshotId = snapshot.id)   [NOT NULL FK]
scheduledTick / startExecution:
  re-readiness (unchanged) → createInitialAttempts resolves execution's snapshot
processRetries:
  retryPolicy, schedule window, contact/DID validity, new attempt's didId ← snapshot
  (calculateNextScheduledAt(ScheduleSpec, Instant); retry references execution.getCampaignId())
processDueAttempts (OutboundDialService):
  resolve(execution); resolve failure → attempt FAILED, EXECUTION_CONFIG_MISSING (no dial,
  no fallback); campaign existence check (CAMPAIGN_NOT_FOUND) unchanged;
  eligibility Context, routing DID, campaign type ← snapshot
PLAYFILE/DTMF triggers (onAnswered/onPlaybackCompleted):
  type, contentMode, audioAssetId, typeConfig ← execution snapshot
  audio approval/tenant/storage validation unchanged (dynamic);
  DtmfExecutionService: missing snapshot row → DtmfConfigInvalidException
    ("Execution configuration snapshot has no valid DTMF configuration") — the leftover
    live-campaign read was REMOVED
EslEventService: trigger invocations stay try/caught — integrity exceptions fail the
  attempt deterministically without breaking event processing
```

## 13. Retry behavior — explicitly unchanged

- Attempt numbering unchanged (`nextAttemptNumber = failed.attemptNumber + 1`,
  `maxTotalAttempts = 1 + maxAttempts`).
- Retry delays unchanged (`completedAt + intervalSeconds`, default 60).
- Schedule-window adjustment unchanged in logic, now reading the snapshot's schedule.
- Permanent/temporary classification unchanged (§10; one new permanent code covers a
  condition that previously had no dial-time representation).
- Requeue semantics (TEMPORARILY_UNAVAILABLE/capacity/provider-unavailable don't consume
  attempt numbers) unchanged.
- **Retries stay on the original snapshot**: the retry loop resolves through
  `resolve(execution)` — an edit after attempt 1 can never change attempt 2 (CFG-B/CFG-C).

## 14. Tenant/Reseller authorization

- Snapshots carry `tenant_id` NOT NULL (FK); the only read is `findByIdAndTenantId` — a
  foreign snapshot is indistinguishable from a missing one (no existence leak), proven by
  CFG-D.
- Snapshot creation happens only on the campaign's own tenant, inside `execute(...)`,
  which is already authorization-gated (`CAMPAIGN_EXECUTE` + boundary-scoped lookup).
- No new authorization model; `AccessCheck.forTenant`/reseller-hierarchy patterns untouched.
  Runtime resource validations remain the existing dynamic checks.

## 15. Architecture result

- `ArchitectureTest` passes — **0 Modulith cycles**. All new types live in `campaign`
  (`CampaignExecutionConfiguration`, `CampaignLifecyclePolicy`,
  `ExecutionConfigurationMissingException`, resolver/service) or the campaign-owned
  `campaign.config` package; no campaign→telephony dependency was added.
- The resolver depends on `CampaignConfigurationService` (service seam), not on the
  repository — one indirection boundary, no layered bypass.
- Discipline check (zero-commit repo, so a full inventory instead of a diff): no lingering
  references to the removed machinery (`CampaignConfigurationVersion`,
  `configurationVersionId`, `materializeForCampaign` — grep over `src/` is clean); exactly
  one V44 migration file in `src/main/resources/db/migration/`; the only stray untracked
  artifacts at the repo root are JVM crash logs (`hs_err_pid*.log`, `replay_pid*.log`) from
  an earlier forked-VM flake — build outputs, not project files.

## 16. Files changed

**Removed**
- `campaign/CampaignConfigurationVersion.java`
- `campaign/CampaignConfigurationVersionRepository.java`
- `src/main/resources/db/migration/V44__campaign_configuration_versions.sql`

**Added**
- `src/main/resources/db/migration/V44__campaign_execution_configurations.sql`
- `campaign/CampaignExecutionConfiguration.java` (+ `...Repository.java`)
- `campaign/ExecutionConfigurationMissingException.java`
- `campaign/CampaignLifecyclePolicy.java`
- `campaign/config/*` — `CampaignTypeConfig`, `PlayfileCampaignConfig`,
  `DtmfCampaignConfig`, `ConnectByAgentCampaignConfig`, `ConfigSchemaVersion`,
  `CampaignTypeConfigValidator`, `CampaignConfigInvalidException` (from the first pass,
  preserved)
- `campaign/CallFailureCode.java` (first pass, + `EXECUTION_CONFIG_MISSING`)
- Tests: `CampaignEditabilityTest` (new in correction),
  `CampaignTypeConfigTest`, `CallFailureCodeTest`, `CampaignConfigurationSnapshotTest`,
  `CampaignConfigurationSnapshotPostgresIntegrationTest` (rewritten in correction)

**Modified**
- `campaign/CampaignExecution.java` — `configurationSnapshotId` (NOT NULL, updatable=false)
- `campaign/CampaignExecutionService.java` — snapshot-first creation + response mapping
- `campaign/dto/CampaignExecutionResponse.java` — `configurationSnapshotId`
- `campaign/CampaignConfigurationService.java` — rewritten
  (`createExecutionSnapshot` / `requireExecutionSnapshot`)
- `campaign/CampaignRuntimeConfigResolver.java` — `resolve(execution)` single-arg,
  `CampaignRuntimeConfig.fromSnapshot`
- `campaign/CampaignService.java` — `lifecyclePolicy` constructor arg + gate in `update`
- `campaign/CampaignExecutionOrchestrator.java` — snapshot resolution for initial attempts
  and retries; `calculateNextScheduledAt(ScheduleSpec, Instant)`
- `campaign/OutboundDialService.java` — snapshot resolution + `EXECUTION_CONFIG_MISSING`
  dial handling
- `campaign/PlayfileExecutionService.java`, `campaign/DtmfExecutionService.java` —
  snapshot-only content/type/config resolution (DTMF live-campaign read removed)
- `campaign/CampaignConfigurationSnapshot.java` — payload embeddable (mapping fix, below)

## 17. Tests

**New / rewritten in the correction**

| Suite | Result | Covers |
|---|---|---|
| `CampaignEditabilityTest` (new) | 6/0/0/0 | DRAFT-only matrix over all 7 statuses, 409 message names state, DRAFT update ok, SCHEDULED rejected, post-unlock editing |
| `CampaignConfigurationSnapshotPostgresIntegrationTest` (rewritten, real PG) | 7/0/0/0 | CFG-A1 snapshot content, CFG-A2 DTMF typeConfig, CFG-B per-execution snapshots across edits, CFG-C execution keeps snapshot after edit, CFG-D foreign snapshot → integrity exception, CFG-E1 missing snapshot no-fallback, CFG-E2 DB NOT NULL FK rejects execution without snapshot |
| Focused unit suites (config/failure-code/editability) | 18/0/0/0 | typed config, taxonomy, snapshot mapping |
| Snapshot + governance PG suites | 17/0/0/0 | governance hardening stays green with snapshot-seeded executions |

**Updated (construction only — assertions untouched)**: `CampaignService` ctor sites
(`CampaignCloneServiceTest`, `CampaignLifecycleServiceTest`,
`CampaignValidationServiceTest`, `CampaignResourceValidationPostgresIntegrationTest`,
`TtsGovernancePostgresIntegrationTest`, governance test) gain `+CampaignLifecyclePolicy()`;
unit tests re-stub snapshots instead of live campaigns (`DtmfExecutionServiceTest` with
`snapshot(...)`/`stubSnapshot`, `DtmfAgentActionTest`, `PlayfileExecutionServiceTest` with
`stubSnapshot(type, mode, audioAssetId)`, `OutboundDialServiceRoutingTest`,
`PlayfileRetrySemanticsTest` single-arg resolver stub); raw-SQL PG harnesses
(`PlayfileLifecycleIntegrationTest`, `DtmfLifecycleIntegrationTest`,
`CampaignGovernanceHardeningPostgresIntegrationTest`) seed
`campaign_execution_configurations` rows and set `configuration_snapshot_id`.

**Defect found and fixed during correction verification** (in this phase's new code, caught
by the raw-SQL lifecycle suites): `CampaignConfigurationSnapshot.campaignType` and
`contentMode` were missing `@Enumerated(EnumType.STRING)`, so Hibernate read the VARCHAR
columns as **ordinals** (`Bad value for type byte : DTMF`). JPA-only round-trips
(CFG-A1) passed self-consistently while raw-SQL-seeded rows exploded during snapshot
hydration inside the `CHANNEL_ANSWER` trigger — silently swallowed by
`EslEventService`'s trigger guard (playAudio/terminateCall never invoked). Fixed by adding
`@Enumerated(EnumType.STRING)` to both fields; all lifecycle suites green. Lesson recorded:
enum mappings must be STRING when raw-SQL fixtures and JPA share a table.

## 18. Full regression (final)

```
cd backend && MAVEN_OPTS="-Xmx768m -XX:MaxMetaspaceSize=384m" ./mvnw clean test
Tests run: 886, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS   (3:56 min)
```

(855 baseline + 6 new `CampaignEditabilityTest` + 25 first-pass tests = 886; the 1 skipped
is the pre-existing environment-conditional skip, unchanged. The earlier forked-VM crash in
`CampaignCloneServiceTest` is environmental — the test passes in isolation and did not recur
in the final clean run.)

## 19. Known limitations / deferred / next phase

**Known limitations**
- Executions created by the *first-pass* code (if any had survived in some environment)
  would have a null snapshot reference; under the corrected model they fail closed with
  `EXECUTION_CONFIG_MISSING`. The dev DB was reset (§11) and nothing was ever committed, so
  no such rows exist in practice.
- `integrationConfig` is snapshotted nowhere and remains write-only storage until a future
  phase defines its semantics.
- Concurrent same-campaign executions remain permitted by design (as before); duplicate
  executions are governed only by the existing idempotency-key mechanism.
- Snapshots are retained as immutable history rows after their execution completes
  (no GC — deliberate; volume is bounded by executions, and rows are never updated).

**Deferred / out of scope (unchanged)**: daily contact limits; retry-policy redesign
(per-case strategies, SWITCHED_OFF detection, NOT_REACHABLE semantics); IVR trees; MISSED_CALL;
webhooks; report privacy; max call duration; audience import scaling; contact model/history
redesign; scheduler distribution/coordination; TTS runtime; FreeSWITCH/ESL changes.

**Recommended next phase**: **VB-6B — Contact + audience** (per the VB-6 audit sequence),
which the scheduler-quality phases depend on. The snapshot seam
(`CampaignRuntimeConfigResolver`) is where audience snapshots (contact-group reference) will
naturally extend.

---

*VB-6A correction stops here. VB-6B not started. No future-phase feature was implemented.*
