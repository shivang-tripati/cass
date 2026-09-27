# VB-5E — Campaign Resource Validation (Canonical Contract) — Implementation Report

## 1. Status

**VB-5E — COMPLETE**

One canonical, side-effect-free, tenant-safe campaign-resource validation boundary
(`CampaignResourceValidationService`) now owns DID / Audio / TTS usability semantics
and is consumed at CREATE, UPDATE, activation/readiness, and all runtime paths.
Full regression: **830 tests, 0 failures, 13 errors, 1 skipped** — exactly the VB-5D
baseline (797/0/13/1) plus the 33 new VB-5E tests, with the identical pre-existing
error set and the identical 2 pre-existing modulith cycle groups. Flyway remains at
**V42**. No scheduler, execution, pacing, TTS-provider, or AI functionality was added.

## 2. Baseline

Established before any production change (log `/tmp/vb5e_baseline2.log`):

- **797 tests, 0 failures, 13 errors, 1 skipped** — exact match to the VB-5D report exit.
- 13 errors in the 3 documented pre-existing classes: `ArchitectureTest` (1),
  `ProvisioningSmokeIntegrationTest` (1), `SecuritySliceTest` (11).
- 2 pre-existing modulith cycle groups: `campaign ↔ voice ↔ telephony`
  (`Cycle detected: Slice campaign -> …`, `Cycle detected: Slice telephony -> …`).
- Flyway head: **V42** (`V42__add_tts_template_scope.sql`).

**Baseline anomaly, classified:** the first baseline attempt on this machine reported
678 tests / 45 errors because the Docker daemon was down — every Testcontainer static
`POSTGRES.start()` failed, tests fell back to the dev datasource, and Flyway failed
with "Connection to localhost:5432 refused" (environmental, documented in
`/tmp/vb5e_baseline.log`). After Docker was started, the clean re-run reproduced
**797/0/13/1 exactly**. The stable invocation for this machine remains
`MAVEN_OPTS="-Xmx768m -XX:MaxMetaspaceSize=384m" ./mvnw clean test -DargLine="-Xmx1g"`.

## 3. Scope

Implemented:

- `CampaignResourceValidationService` (new, `campaign` package): one authoritative,
  read-only validation boundary for campaign DID / AUDIO / TTS references, returning a
  small immutable result (`usable` + fine-grained `ValidationCode`).
- CREATE/UPDATE integration: `CampaignService.validateDidReference` and
  `validateContentReferences` now delegate; existing 400 `VALIDATION_ERROR` messages
  preserved verbatim.
- Activation/readiness integration: `CampaignReadinessService.checkDid` /
  `checkAudioAsset` / `checkTtsTemplate` now delegate; existing reason codes and
  messages preserved verbatim, including the `TTS_TEMPLATE_NOT_APPROVED` vs
  `TTS_TEMPLATE_NOT_AVAILABLE` tenant-safe split.
- Runtime integration: `PlayfileExecutionService` (PLAYFILE playback),
  `DtmfExecutionService` (DTMF playback — a sixth validation site discovered during
  implementation), `CampaignExecutionOrchestrator` (start-of-execution re-validation,
  per-retry DID check, initial attempts), and `CallAttemptService` (attempt creation)
  all validate through the canonical boundary; runtime failure codes/messages
  preserved verbatim (`PLAYBACK_CONFIG_INVALID`, `DTMF_CONFIG_INVALID`).
- Tests: 13-test unit classification matrix + 20-test real-PostgreSQL integration
  suite (Flyway V1→V42) covering CREATE, UPDATE, readiness, runtime invalidation,
  idempotency, and two-tenant isolation.

Not implemented (per contract): no scheduler/worker/pacing/CPS, no progressive/
preview/predictive dialing, no retry-engine or execution-state-machine changes, no
TTS synthesis/providers/runtime rendering, no storage/transcoding/FFmpeg/S3, no AI,
no new HTTP surfaces, **no database migration**.

## 4. Existing Validation Architecture (discovered)

Six scattered, individually-implemented validation sites existed before VB-5E, all
expressing the same resource semantics through the same authoritative repository
predicates:

| # | Site | Semantics |
|---|------|-----------|
| 1 | `CampaignService.create` → `validateDidReference` / `validateContentReferences` | DID: `existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(ACTIVE, ASSIGNED)` → 400 "DID does not exist or is not available."; AUDIO: `existsByIdAndTenantIdAndDeletedAtIsNullAndStatus(APPROVED)` → 400 "Audio asset does not exist or is not approved for use."; TTS: `existsUsableForTenant` → 400 "TTS template does not exist or is not approved for use." |
| 2 | `CampaignService.update` | same pair, PUT semantics |
| 3 | `CampaignService.changeStatus` → `validateActivation` (DRAFT→SCHEDULED) | re-runs both — the CREATE-then-invalidate gap was already acknowledged |
| 4 | `CampaignReadinessService.checkDid` / `checkAudioAsset` / `checkTtsTemplate` | reasons `DID_UNAVAILABLE`, `AUDIO_NOT_APPROVED`, `AUDIO_STORAGE_REFERENCE_MISSING` (two-phase EXISTS), `TTS_TEMPLATE_NOT_APPROVED` vs `TTS_TEMPLATE_NOT_AVAILABLE` (via `existsAccessibleForTenant`) |
| 5 | Runtime PLAYFILE: `PlayfileExecutionService.onAnswered` | inline tenant-owned + APPROVED + storage-reference checks → `PLAYBACK_CONFIG_INVALID` messages; and **the same block duplicated in `DtmfExecutionService.onAnswered`** → `DTMF_CONFIG_INVALID` |
| 6 | Runtime dial orchestration: `CampaignExecutionOrchestrator.isDidStillValid` + `createInitialAttempts` ("Campaign DID no longer available"), `CallAttemptService.createAttempt` ("DID does not exist or is not available for this tenant") | DID predicate re-checks |

Authoritative predicates (unchanged, still the single source of truth):
`DidRepository.existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState`,
`AudioAssetRepository.existsByIdAndTenantIdAndDeletedAtIsNullAndStatus` +
`…AndStorageReferenceIsNotNull` + `findByIdAndTenantIdAndDeletedAtIsNull`,
`TtsTemplateRepository.existsUsableForTenant` / `existsAccessibleForTenant` (V42 JPQL).

Result abstractions already present (`CallEligibility.EligibilityResult`,
`CampaignReadinessReason`) are HTTP/domain-facing; none expressed cross-resource
usability classification, so a minimal domain-focused result was introduced (§5).
No TTS runtime consumer exists (VB-5D seam) — none was invented.

## 5. Canonical Validation Design

**`CampaignResourceValidationService`** — plain `@Service`, constructor injection,
`campaign` package, zero dependencies beyond the three resource repositories.

```
Campaign (CREATE · UPDATE · ACTIVATION · RUNTIME)
    → CampaignResourceValidationService          ← one authoritative boundary
        → validateDid    → DidRepository          (VB-5C ownership/allocation)
        → validateAudio  → AudioAssetRepository   (VB-5B approval + logical storage)
        → validateTts    → TtsTemplateRepository  (VB-5D GLOBAL/TENANT predicate)
```

- **Result**: `record ResourceValidationResult(boolean usable, ValidationCode code)`,
  with `valid()` / `invalid(code)` factories. Codes are internal classifications:
  `DID_NOT_AVAILABLE`, `AUDIO_NOT_AVAILABLE`, `AUDIO_NOT_APPROVED`,
  `AUDIO_STORAGE_REFERENCE_MISSING`, `TTS_NOT_AVAILABLE`, `TTS_NOT_APPROVED`.
  **Callers map codes onto their existing externally observable error surfaces**;
  no existing code or message was renamed.
- **Contract guarantees** (stated on the class): side-effect free (read-only
  existence/lookup queries only — no calls, media, storage writes, reservations,
  locks, scheduling); tenant-safe (every query constrains the tenant boundary;
  foreign resources are indistinguishable from nonexistent ones); deterministic;
  not authoritative for null-reference *policy* (callers that permit absent
  references keep their own null-guards before delegating).
- **Deliberately NOT**: no new validation framework, no parallel readiness system,
  no second TTS GLOBAL/TENant implementation, no HTTP concepts, no FreeSWITCH,
  no filesystem/provider coupling.

## 6. DID Validation

`validateDid(didId, tenantId)` delegates verbatim to the VB-5C predicate
`existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(didId, tenantId,
ACTIVE, ASSIGNED)`. A DID is usable only when it exists, is not soft-deleted, belongs
to the campaign tenant, is ACTIVE and ASSIGNED. Unassigned/revoked/INACTIVE/deleted/
foreign/missing all collapse to `DID_NOT_AVAILABLE` — one non-leaking classification,
exactly matching the pre-existing message per call site. No new DID lifecycle states;
no assignment/revocation logic (VB-5C owns that).

## 7. Audio Validation

`validateAudio(audioAssetId, tenantId)` performs the two-phase classification within
**one** tenant-bound row load (`findByIdAndTenantIdAndDeletedAtIsNull`): not resolvable
→ `AUDIO_NOT_AVAILABLE`; status ≠ APPROVED → `AUDIO_NOT_APPROVED`; approved but
`storageReference` null/blank → `AUDIO_STORAGE_REFERENCE_MISSING`; else valid.
Only the persisted **logical** `storageReference` is reasoned about — no filesystem,
no FreeSWITCH, no storage framework. This matches the VB-5B readiness semantics that
already closed the metadata-only-asset gap.

## 8. TTS Validation

`validateTts(ttsTemplateId, tenantId)` delegates to the authoritative V42 predicate
`existsUsableForTenant` (GLOBAL + APPROVED, or TENANT + matching tenantId + APPROVED,
both not deleted). `existsAccessibleForTenant` is used only for the tenant-safe
classification: accessible-but-unapproved → `TTS_NOT_APPROVED`; missing/deleted/
foreign → `TTS_NOT_AVAILABLE` (never leaks foreign-row existence). GLOBAL resources
are never treated as tenant-owned, no fake tenant IDs, no copying GLOBAL into tenants.
The VB-5D `TTS_TEMPLATE_NOT_APPROVED` vs `TTS_TEMPLATE_NOT_AVAILABLE` distinction is
preserved at the readiness boundary by mapping these two codes 1:1.

## 9. CREATE Integration

`CampaignService.create` → `validateDidReference` / `validateContentReferences` now
delegate to the validator and map outcomes onto the existing 400
`CommonErrorCode.VALIDATION_ERROR` messages verbatim:
"DID does not exist or is not available." / "Audio asset does not exist or is not
approved for use." / "TTS template does not exist or is not approved for use."
Content-mode gating (validate AUDIO only when `ContentMode.AUDIO`, TTS only when
`ContentMode.TTS`) and the existing exclusivity rules (`validateContent`:
AUDIO requires exactly one asset, TTS exactly one template, mutual exclusivity) are
preserved unchanged. CREATE validates *now*; activation re-validates (§11).

## 10. UPDATE Integration

`CampaignService.update` uses the same two delegation points with the request's
proposed references, so DID changes, audio changes, TTS changes, and content-mode
transitions (including AUDIO→TTS) are all validated with the target mode's semantics
(proved by PG-E3: an AUDIO→TTS transition referencing a foreign TENANT template is
rejected with the existing message; an approved GLOBAL template is accepted). PUT
semantics are unchanged. Rejected updates do not mutate the campaign (PG-E2 asserts
both references survive a rejected update). No resources irrelevant to the selected
content mode are validated, matching existing repository semantics.

## 11. Activation / Readiness Integration

`CampaignReadinessService` remains the orchestration layer; its three resource checks
now delegate and map codes onto the existing reason codes/messages verbatim:
- `DID_UNAVAILABLE` "DID does not exist or is not available."
- `AUDIO_NOT_APPROVED` "Audio asset does not exist or is not approved for use."
  (missing/foreign/unapproved all collapse here, as before — non-leaking)
- `AUDIO_STORAGE_REFERENCE_MISSING` "Audio asset is approved but has no stored audio file."
- `TTS_TEMPLATE_NOT_APPROVED` "TTS template is not approved for use."
- `TTS_TEMPLATE_NOT_AVAILABLE` "TTS template does not exist or is not available to this campaign."

The service's broader readiness concerns (lifecycle state, schedule, content
configuration, contact group, 404-cloaked campaign lookup) are untouched.
`CampaignService.validateActivation` (DRAFT→SCHEDULED) keeps its own orchestration and
now consumes the same canonical semantics through the delegated write-time methods.

## 12. Runtime Integration

Runtime never trusts an earlier campaign validation result — every site re-reads
canonical state at the moment of use:

- **PLAYFILE** (`PlayfileExecutionService.onAnswered`): the inline tenant/approval/
  storage checks were replaced by `validateAudio` mapped back to the exact
  `PLAYBACK_CONFIG_INVALID` messages: "PLAYFILE campaign has no valid audio asset
  configured" (mode/reference pre-check retained), "Audio asset is not available for
  this tenant", "Audio asset is not approved for playback", "Audio asset has no
  storage reference". `PLAYBACK_FAILED` (transient command failures) is untouched.
  `EslEventService.PLAYBACK_CONFIG_INVALID_CODE` remains in sync (unchanged constant).
- **DTMF** (`DtmfExecutionService.onAnswered`): the identical duplicated inline block
  (discovered during integration — see §20) now delegates to the validator with the
  same message mapping under `DTMF_CONFIG_INVALID`. DTMF campaigns play the campaign
  audio through the same AUDIO content model, so they must honor the same contract.
- **Execution orchestration**: `CampaignExecutionOrchestrator.startExecution` still
  re-evaluates full readiness before RUNNING; `createInitialAttempts` re-validates the
  DID through the validator ("Campaign DID no longer available"); the per-retry
  `isDidStillValid` gate also delegates to the validator.
- **Attempt creation**: `CallAttemptService.createAttempt` validates the DID through
  the validator ("DID does not exist or is not available for this tenant").
- **DTMF/CONNECT_BY_AGENT dial-time DID checks** (`OutboundDialService`,
  `ConnectByAgentService`) remain destination-level and unchanged — see §22
  (VB-5F candidate).
- **No TTS runtime exists** (VB-5D seam) — none invented. The future TTS runtime
  consumes `validateTts`; the contract is already in place.

The validator performs no telephony commands, media playback, synthesis, scheduling,
locks, or storage operations (§13); tenant/approval/business validation stays out of
FreeSWITCH.

## 13. Tenant Isolation

- The validator takes the campaign/attempt/session tenant explicitly — always
  server-derived (OrganizationContextHolder scope, or the execution/session's stored
  `tenantId` at runtime) — never a client-supplied identifier.
- Every underlying query constrains the tenant boundary; a campaign tenant A
  referencing tenant B's DID/audio/TTS resolves to not-usable (PG-I1 proves all six
  cross-tenant directions plus own-tenant and approved-GLOBAL positives).
- Foreign-resource existence is not leaked: the write path returns one identical
  message for a foreign reference and a nonexistent one (PG-I2 compares the exact
  failure messages pairwise for DID, audio, and TTS); readiness reports
  `TTS_TEMPLATE_NOT_AVAILABLE` / `AUDIO_NOT_APPROVED` / `DID_UNAVAILABLE` for both
  classes; foreign campaigns 404-cloak (PG-I3).
- Approved GLOBAL TTS remains usable by every tenant (PG-I1, PG-F4, PG-D4).

## 14. Error Semantics

Zero externally visible error changes. Preserved verbatim: all three CampaignService
400 messages; all five readiness reason codes and messages; the runtime failure codes
`PLAYBACK_CONFIG_INVALID` / `PLAYBACK_FAILED` / `DTMF_CONFIG_INVALID` and their four
audio-related messages per service; "Campaign DID no longer available"; "DID does not
exist or is not available for this tenant". No new HTTP status codes, no new exception
hierarchy, no new error-code taxonomy. `LifecycleServiceTest`'s
`hasMessageContaining("not approved")` and the Playfile lifecycle message assertions
pass unchanged. Internally, fine-grained classification (`AUDIO_NOT_AVAILABLE` vs
`AUDIO_NOT_APPROVED`) is new but maps onto the *existing* collapsed external reasons —
e.g., readiness still reports `AUDIO_NOT_APPROVED` for a missing/foreign asset.

## 15. Database / Migrations

**No migration created; Flyway head remains V42.** Inspection proved every VB-5E
semantic is already enforced by existing predicates over existing columns (V1/V9
campaigns, VB-5B audio columns, VB-5C DID allocation, V42 TTS scope). The integration
suite runs the full V1→V42 chain (PG-M-style check: `flyway_schema_history` contains
42; confirmed in the run log: `Migrating schema "public" to version "42 - add tts
template scope"`) and all existing constraints hold. Nothing in VB-5E required a new
invariant.

## 16. Architecture / Modulith

- `CampaignResourceValidationService` lives in the `campaign` module and depends only
  on `did`, `audio`, and `tts` repository interfaces — all already legal
  campaign→X references used by the code it replaced. **No new cycle, no new
  cross-module edge.**
- Final run architecture output shows exactly the two pre-existing cycle groups
  (`Slice campaign -> …`, `Slice telephony -> …`) and the same pre-existing
  cross-module constructor/implementation violations — all attributable to
  pre-existing classes (`ConnectByAgentService`, `DtmfExecutionService` triggers,
  `OutboundDialService`, etc.). The only new symbol appearing in the violation dump
  is the new constructor parameter type of the already-violating
  `DtmfExecutionService` constructor — it adds no new slice dependency pair.
- No classes were moved between modules; the smallest safe change was preferred.

## 17. Tests

**New (33):**

- `CampaignResourceValidationServiceTest` (13, unit/Mockito): DID classification
  (valid/unusable/null-ref), AUDIO classification (approved+storage usable, pending/
  rejected → NOT_APPROVED, missing/deleted/foreign → NOT_AVAILABLE, null/blank
  storage → STORAGE_REFERENCE_MISSING, null-ref), TTS classification (usable /
  accessible-unapproved / inaccessible, null-ref), and idempotency (3× repeated
  validation, deterministic results, exactly 3 read interactions per repo, zero
  write-capable interactions).
- `CampaignResourceValidationPostgresIntegrationTest` (20, real `postgres:16-alpine`,
  full Flyway V1→V42): D1–D4 CREATE matrix; E1–E3 UPDATE matrix incl. content-mode
  transition and no-mutation-on-reject; F1–F5 readiness reasons incl. revoked DID,
  audio reason split, TTS reason split tenant-safely, revoked GLOBAL approval;
  G1–G4 runtime invalidation after creation (DID revoked, audio approval revoked →
  deleted → storage lost, TTS approval revoked → deleted, foreign audio seeded
  directly); H1 idempotency/zero side effects (row counts and `updatedAt` unchanged
  across repeated validation); I1–I3 two-tenant isolation, message-pair non-leakage,
  404-cloaking.

**Updated (12 existing test classes)** — constructors only, plus two audio-stub
modernizations where the service previously consumed
`existsByIdAndTenantIdAndDeletedAtIsNullAndStatus` directly:

`CampaignValidationServiceTest`, `CampaignLifecycleServiceTest`,
`CampaignCloneServiceTest`, `PlayfileRetrySemanticsTest`,
`PlayfileExecutionServiceTest`, `DtmfExecutionServiceTest`, `DtmfAgentActionTest`,
`PlayfileLifecycleIntegrationTest`, `DtmfLifecycleIntegrationTest`,
`AudioUploadPostgresIntegrationTest`, `TtsGovernancePostgresIntegrationTest`,
`DidAllocationPostgresIntegrationTest`.

Runtime unit tests construct a **real validator over their existing mocked/autowired
audio repository** (the validator is a pure delegator), so per-test asset stubs drive
validation scenarios exactly as before; no scenario coverage was lost.

## 18. PostgreSQL Integration

All persistence-sensitive semantics are proven on real PostgreSQL via Testcontainers
(following the established VB-4/5 harness: static-init container start,
`@DynamicPropertySource`, `NOT_SUPPORTED` propagation, `TransactionTemplate`-wrapped
service calls, committed seeding, JPQL cleanup):

- DID ownership/allocation (ASSIGNED vs AVAILABLE, ACTIVE vs INACTIVE, foreign,
  soft-delete-free existence) — real rows, real predicate.
- Audio approval + storageReference (incl. UPDATE of the same row through states) —
  real soft-delete `deleted_at` stamping.
- TTS GLOBAL/TENANT semantics — real V42 CHECK constraints and JPQL predicates,
  including cross-tenant rejection and GLOBAL shared usability.
- Campaign references and tenant isolation — two real tenants, both directions.
- Full Flyway V1→V42 chain applied per container; no H2 anywhere.

## 19. Final Regression

```
MAVEN_OPTS="-Xmx768m -XX:MaxMetaspaceSize=384m" ./mvnw clean test -DargLine="-Xmx1g"
(log: /tmp/vb5e_final.log)
Tests run: 830, Failures: 0, Errors: 13, Skipped: 1
BUILD FAILURE  ← expected: the 13 pre-existing errors fail the build, as at baseline
```

Comparison against the VB-5D baseline:

| Metric | VB-5D baseline | VB-5E final | Verdict |
|---|---|---|---|
| Total tests | 797 | **830** (+33 new) | ✓ as expected |
| Failures | 0 | **0** | ✓ invariant held |
| Errors | 13 | **13** — same 3 classes (ArchitectureTest 1, ProvisioningSmokeIntegrationTest 1, SecuritySliceTest 11) | ✓ no new errors |
| Skipped | 1 | 1 | ✓ |
| Modulith cycle groups | 2 (`campaign`, `telephony`) | 2 — identical groups | ✓ no new cycle |
| Flyway head | V42 | **V42** | ✓ no migration |

## 20. Defects Found

1. **Duplicated runtime audio validation in `DtmfExecutionService`** (not surfaced in
   the pre-implementation inspection, which mapped PLAYFILE only).
   *Symptom*: two ~30-line inline blocks with identical tenant/approval/storage
   semantics and message sets in `PlayfileExecutionService` and
   `DtmfExecutionService`, risking future divergence from the canonical contract.
   *Root cause*: VB-2 DTMF playback was implemented by copying the VB-1 PLAYFILE
   validation block rather than sharing it.
   *Fix*: consolidated onto `CampaignResourceValidationService` with the same
   message mapping under `DTMF_CONFIG_INVALID`.
   *Regression tests*: existing `DtmfExecutionServiceTest.crossTenantAssetRejected`
   plus the full `DtmfLifecycleIntegrationTest`; new PG-G2 covers the classification
   end-to-end on real PostgreSQL.
2. **Unit-matrix fixture defect (self-caught)**: a UUID constant in
   `CampaignResourceValidationServiceTest` contained a non-hex character
   (`…00t1`), throwing `NumberFormatException` in static init (13 errors on first
   run of the new suite). *Fix*: hex-safe constants. *Regression*: the unit matrix
   itself now runs and would fail on any regression of its classification logic.
3. **Vacuous assertion in a draft PG-H1** (`usingRecursiveComparison` ignoring all
   fields — tautologically true). *Fix*: replaced with explicit field assertions
   (didId, audioAssetId, contentMode, status, name) plus `updatedAt` invariance on
   DID/audio rows before the final regression run.

## 21. Defects Fixed

All three defects above were fixed within this phase. No pre-existing product defect
requiring external-behavior change was discovered; the two long-standing behavioral
simplifications found (readiness reseller-scope hierarchy expansion; dial-time DID
checks not reusing ACTIVE+ASSIGNED semantics) are documented as VB-5F candidates
rather than changed here, per the phase stop rule.

## 22. Known Limitations

- **No TTS runtime consumer exists** (VB-5D seam). `validateTts` is proven at
  CREATE/UPDATE/readiness; the future TTS runtime will call the same method.
- **Dial-time destination DID checks** (`OutboundDialService`, `ConnectByAgentService`
  use `findByIdAndDeletedAtIsNull` with their own eligibility logic) are
  destination-level and were not rerouted through the canonical campaign-resource
  boundary in this phase (VB-5F candidate).
- **`CampaignReadinessService` reseller scope** still resolves campaigns without
  hierarchy expansion (pre-existing simplification, noted in its source comments).
- **Double asset fetch at runtime playback** (validator classification + storage
  reference read) — negligible read cost, deliberately accepted to keep the validator
  result-only; a future refactor could return the entity without semantic change.
- `CampaignService.update` PUT semantics mean an omitted `didId` clears the
  reference; the "requested-but-missing DID" null-policy hardening is a VB-5F
  candidate.

## 23. Explicitly Out of Scope

Campaign scheduler/worker/pacing/CPS; progressive/preview/predictive dialing; retry
engine or execution state machine changes; batch processing; concurrency controllers;
TTS synthesis, providers (Google/ElevenLabs/Polly/Azure/FreeSWITCH), rendering,
caching, provider routing; S3/transcoding/FFmpeg/storage frameworks; LLMs/AI content
or voice generation; DID assignment/revocation flows (VB-5C owns); any migration;
any new REST surface; fixing the pre-existing ArchitectureTest / SecuritySliceTest /
ProvisioningSmokeIntegrationTest errors or the two pre-existing cycle groups.

## 24. Final DoD Checklist

- [x] Existing campaign/resource architecture inspected (all six validation sites,
      all runtime paths PLAYFILE/DTMF/CONNECT_BY_AGENT/dialing, message contracts,
      constructor shapes, harness conventions).
- [x] Clean baseline established and documented (797/0/13/1, Docker-down anomaly
      classified as environmental and reproduced away).
- [x] One canonical campaign-resource validation boundary exists
      (`CampaignResourceValidationService`).
- [x] DID validation uses existing VB-5C ownership/allocation semantics.
- [x] Audio validation uses existing VB-5B approval/storage semantics (logical only).
- [x] TTS validation uses existing VB-5D GLOBAL/TENANT semantics (`existsUsableForTenant` authoritative).
- [x] CREATE uses canonical validation.
- [x] UPDATE uses canonical validation (incl. content-mode transitions).
- [x] Activation/readiness uses canonical validation (`CampaignReadinessService` delegates; orchestration preserved).
- [x] Runtime validation reuses the same canonical semantics (PLAYFILE, DTMF, orchestrator, attempt creation).
- [x] Runtime does not trust stale campaign validation (re-reads canonical state; PG-G1–G4).
- [x] Tenant isolation preserved (server-derived context; PG-I1).
- [x] Foreign resource existence not leaked (PG-I2 message-pair equality; readiness split).
- [x] GLOBAL TTS remains usable by eligible tenants (PG-D4/F4/I1).
- [x] TENANT TTS remains tenant-isolated (PG-I1).
- [x] Audio not coupled to filesystem implementation (logical `storageReference` only).
- [x] Campaign validation not coupled to FreeSWITCH (no ESL/media dependency in validator).
- [x] Validation has no telephony/storage/scheduling side effects (unit H + PG-H1).
- [x] Existing error semantics remain compatible (zero external changes; §14).
- [x] Real PostgreSQL integration tests pass (20/20, Flyway V1→V42).
- [x] Unit tests pass (13/13 new; all updated suites green).
- [x] Full regression passes (830/0/13/1 — 0 failures, no new errors).
- [x] Architecture tests: no new cycle (same 2 pre-existing groups).
- [x] Flyway remains at V42 (no migration created).
- [x] No scheduler/execution/pacing/predictive/progressive work added.
- [x] No TTS provider/synthesis added.
- [x] No AI functionality added.
- [x] Implementation report created (this document).

## 25. Final Verdict

**VB-5E is COMPLETE and READY for review.** The platform now satisfies the target
architectural property — Campaign → canonical resource validation → DID / Audio / TTS
→ "currently usable for this tenant/campaign" — with one authoritative implementation,
reused unchanged at CREATE, UPDATE, activation/readiness, and runtime, independent of
FreeSWITCH, filesystem, TTS providers, scheduler, and AI. External behavior is
byte-for-byte compatible with the VB-5D baseline; the only test delta is +33 passing
VB-5E tests. Per the phase stop rule, VB-5F hardening candidates are documented
(§22) and **not** implemented. Stopping here for review before VB-5F.
