# VB-5F — Campaign Resource & Governance Hardening — Implementation Report

**Status:** COMPLETE — implemented, tested, full regression green (modulo pre-existing errors), architecture result unchanged.
**Date:** 2026-09-25
**Phase:** VB-5F (hardening of the VB-5E campaign-resource validation layer; VB-4F-style systematic audit)
**Baseline:** VB-5E final (830 tests / 0 failures / 13 errors / 1 skipped; Flyway head V42)

---

## 1. Status

**DONE.** All in-scope defects fixed, regression suites added, full run executed:
**847 tests / 0 failures / 13 errors / 1 skipped** (= 830 baseline + 17 new tests).
The 13 errors and 1 skipped are byte-identical to baseline (same 3 pre-existing classes, same 2 modulith cycle groups). **No migration was required** (Flyway head remains **V42**). Per the phase contract, this phase **stops here** — Campaign Execution Scheduler/pacing/CPS dialing is explicitly out of scope.

---

## 2. Baseline

Reproduced cleanly before any change (log `/tmp/vb5f_baseline.log`):

| Metric | Value |
|---|---|
| Tests | 830 (= VB-5E final, matches `/tmp/vb5e_final.log`) |
| Failures | 0 |
| Errors | 13 — `ArchitectureTest.modularBoundariesAreIntact` (1), `ProvisioningSmokeIntegrationTest.bootstrapSuperAdminHasNoOrganizationalHomeAndCanLogIn` (1), `SecuritySliceTest.*` (11) |
| Skipped | 1 (Docker-guarded Testcontainers suite) |
| Flyway head | V42 |
| Modulith cycles | 2 groups — "Slice campaign →" and "Slice telephony →" |
| Build result | exit=1 (the 13 pre-existing errors fail the build — expected and documented) |

---

## 3. Scope

Hardening **only** of the VB-5E campaign-resource layer and its adjacent campaign-runtime boundaries:

- Campaign/execution ID discipline in the dial path (correctness + tenant safety).
- Reseller-hierarchy scoping of every campaign-domain read boundary (IDOR / fail-open elimination).
- Stale-resource governance: resources revoked/reassigned/deleted **after** activation must be rejected at readiness **and** at dial time.
- Concurrency/idempotency guarantees: prove DB-enforced invariants (partial unique indexes, state machine, service-level idempotency).
- Soft-delete and message non-leakage regression coverage.

**Explicitly out of scope (unchanged, deferred):** scheduler/pacing/CPS, dialing types, retry engine changes, TTS synthesis, providers, AI, S3/Redis/Kafka, billing, frontend. No new migration; no error-contract changes.

---

## 4. Files inspected

Production:
`OutboundDialService`, `CampaignReadinessService`, `CampaignService`, `CampaignExecutionService`, `CallAttemptService`, `CampaignExecutionOrchestrator`, `CampaignRepository`, `CampaignExecutionRepository`, `CallAttemptRepository`, `DidRepository`, `TenantRepository`, `VoiceEligibilityService`, `CallEligibilityService`, `ConnectByAgentService`, `CampaignExecution`, `CallAttempt`, `CallSession`, `ContactEntity`, `CampaignStatus`, `LifecycleStatus`, `AuditableEntity`/`BaseEntity` (no `@Version`), DTOs (`UpdateCampaignRequest`, `UpdateCampaignStatusRequest`, `ExecuteCampaignRequest`, `CreateCallAttemptRequest`), migrations `V9`, `V14`, `V15`, `V21`, `V22`, `V29`.

Tests/harness:
`OutboundDialServiceRoutingTest`, `CampaignLifecycleServiceTest`, `PlayfileRetrySemanticsTest`, `DidAllocationPostgresIntegrationTest`, `TtsGovernancePostgresIntegrationTest`, `AudioUploadPostgresIntegrationTest`, `CampaignResourceValidationPostgresIntegrationTest`, `TtsTemplateService`, `docs/VB-4F-IMPLEMENTATION-REPORT.md` (style reference).

Searches performed: all `getExecutionId()` call sites (F1 isolation confirmed), all `hierarchyTenantIds` implementations (canonical pattern), all `new CampaignReadinessService` / `new OutboundDialService` / `new CampaignExecutionOrchestrator` construction sites, `@Version` usage (none on campaign entities), `INVALID_DID` coverage, `uq_call_attempts` / `uq_campaign_executions` DDL.

---

## 5. Tenant isolation finding

**F2 family (defects, fixed).** `CampaignReadinessService.findVisible` reseller branch performed a **platform-wide** lookup (`findByIdAndDeletedAtIsNull`) with a comment admitting "simplified to platform-wide for now" — a reseller could reach any tenant's campaign. Inspection then found the **same fail-open pattern** in `CampaignExecutionService.findVisible`, plus a **broken fail-closed variant** in `CampaignExecutionService.findVisibleExecution`, `CallAttemptService.findVisibleExecution`, and `CampaignExecutionOrchestrator.findVisibleExecution` — those passed `scope.resellerId()` where a **tenantId** was expected (a reseller ID can never equal a tenant ID, so reseller-scope access always 404'd). All five were hardened to the canonical `CampaignService` pattern: `tenantRepository.findAllByResellerIdAndStatus(resellerId, LifecycleStatus.ACTIVE)` → `findByIdAndTenantIdInAndDeletedAtIsNull(id, hierarchyTenants)`, empty hierarchy → 404.

Everything else clean: all resource repositories expose `findByIdAndTenantIdAndDeletedAtIsNull` and existence predicates include `DeletedAtIsNull`; `VoiceEligibilityService` DID check filters deleted rows; `PlayfileExecutionService`/`DtmfExecutionService` campaign lookups use `findByIdAndTenantIdAndDeletedAtIsNull(attempt.getCampaignId(), attempt.getTenantId())`; no bypassing queries found in campaign runtime paths.

---

## 6. Ownership finding

Campaign ↔ resource ownership is delegated to the canonical `CampaignResourceValidationService` (VB-5E): `validateDid/validateAudio/validateTts(resourceId, campaignTenantId)` with same-tenant + status + approval semantics. Confirmed intact at all 6 call sites (`CampaignService`, `CampaignReadinessService`, `PlayfileExecutionService`, `DtmfExecutionService`, `CampaignExecutionOrchestrator`, `CallAttemptService`). The F1 defect (§7) was the one place ownership scoping was bypassed — by querying with the wrong key. `ConnectByAgentService` uses DID only for CLI display (`didE164`, null-safe → provider default), never eligibility — no change needed.

---

## 7. Campaign/execution ID-mixup finding (F1)

`OutboundDialService.processAttempt` L113–116 resolved the campaign via `campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(attempt.getExecutionId(), ...)`. `execution_id` references `campaign_executions`, **not** `campaigns`; the attempt carries both `campaignId` (denormalized FK) and `executionId` (correlation). The bug was masked in `OutboundDialServiceRoutingTest` because the fixture set `attempt.setCampaignId(executionId)` and `campaign.setId(executionId)`. Consequences pre-fix: with distinct IDs the dial path would mark every attempt `CAMPAIGN_NOT_FOUND`; conversely, if an execution's UUID were ever reused as a campaign id in another tenant, the wrong campaign's configuration could drive the call.

Audit of all other `getExecutionId()` uses: legitimate correlation fields only (`CallAttemptService` equality checks, dial-request correlation, `session.setCampaignExecutionId`, retry lookups) — F1 was the single misuse. Two dead private methods (`buildRequest`, `resolveRoute`) were also removed; they contained the remaining unscoped `findByIdAndDeletedAtIsNull` DID usage and two now-unused constructor dependencies.

---

## 8. Authorization finding

Capability checks (`CAMPAIGN_VIEW/MANAGE/EXECUTE`, `TTS_APPROVE`, etc.) verified present on every state-changing boundary and unchanged. The defects in this phase were **scope-of-read** defects (§5), not capability bypasses: authorization validated the caller's capability, but the data lookup did not constrain the rows to the caller's organizational boundary. `DidService.requireCapabilityForManagedDid` / `visibleToReseller` already implement hierarchy checks correctly (reference pattern).

---

## 9. State-machine finding

`CampaignService.LEGAL_TRANSITIONS` covers all 7 `CampaignStatus` values exactly; system-driven edges (`SCHEDULED→RUNNING`, `RUNNING→COMPLETED`, `RUNNING→FAILED`) are rejected on the manual API; illegal edges throw `ConflictException` (409). DB CHECK `ck_campaigns_status` (V15) matches the enum exactly. `campaign_executions` CHECK covers `REQUESTED/RUNNING/COMPLETED/FAILED/CANCELLED`; `call_attempts` CHECK covers `QUEUED/IN_PROGRESS/COMPLETED/FAILED/CANCELLED`. Verified by PG-G7: **double activation is 409**, row unchanged, and illegal-edge/unit coverage pre-exists in `CampaignLifecycleServiceTest`.

---

## 10. Concurrency finding

**No `@Version` optimistic locking on campaign-domain entities** (`BaseEntity` = id only; `AuditableEntity` adds audit columns). This is acceptable for the current write patterns **because** the invariants that matter are DB-enforced:

- `uq_campaign_executions_campaign_idempotency (campaign_id, idempotency_key) WHERE idempotency_key IS NOT NULL` (V21) — concurrent duplicate executes collapse to one row (PG-G9: 8 threads → 1 winner, 7 rejected).
- `uq_call_attempts_execution_contact_attempt (execution_id, contact_id, attempt_number) WHERE deleted_at IS NULL` (V22) — concurrent duplicate attempt inserts collapse to one row (PG-G10: 6 threads → 1 winner, 5 constraint violations, count = 1).
- Lifecycle edges are guarded by the in-transaction state machine (PG-G7 proves no double activation under the manual API).
- Precedent for conditional-UPDATE allocation transitions (no read-check-write window): VB-5C `@Modifying(clearAutomatically=true)` JPQL in `DidRepository` (`assignFromPlatformPoolToTenant`, `assignFromResellerPoolToTenant`, `revokeFromTenant`, …) — reused unmodified in PG-G4/G5.
- Advisory locks remain in `VoiceCapacityServiceImpl`, `AgentReservationService`, `AcdService`, `AudioUploadValidator` (unchanged, still effective).

Campaign CRUD itself remains last-writer-wins on whole-row PUT updates; no partial-mutation vector exists because updates are single-row, single-transaction, whole-entity writes.

---

## 11. PostgreSQL constraint finding

Inventory verified against live PG via Testcontainers (Flyway V1..V42 chain):
`ck_campaigns_status` (7 values), `ck_campaign_executions_status` (5), `ck_call_attempts_status` (5), `ck_call_attempts_attempt_number > 0`, partial unique indexes from §10, `uq_dids_e164_live` (V16). FKs: `campaign_executions → campaigns/tenants`, `call_attempts → campaign_executions/campaigns/tenants`, `call_sessions.gateway_id` has **no** FK (plain UUID — safe for harness simulation). All confirmed; nothing missing for the in-scope invariants.

---

## 12. Idempotency finding

Two layers, both verified:

1. **Service level** — `CampaignExecutionService.execute` returns the existing execution when `findByCampaignIdAndIdempotencyKeyAndDeletedAtIsNull` matches (PG-G8: duplicate execute returns the first execution's ID; row count unchanged).
2. **DB level** — the V21 partial unique index is the race-proof backstop (PG-G9).

Attempt-creation idempotency likewise: `existsByExecutionIdAndContactIdAndAttemptNumberAndDeletedAtIsNull` guards plus the V22 partial unique index (PG-G10).

---

## 13. Stale-resource finding

The phase's core governance question: *what happens when a validated resource changes after activation?* Answer, now regression-proven at both boundaries:

- **Readiness** (`CampaignReadinessService` → canonical validator): revoked/reassigned/deleted DID ⇒ `DID_UNAVAILABLE` (PG-G4, PG-G6).
- **Dial time** (`VoiceEligibilityService` step 6): deleted/missing ⇒ `INVALID_DID "DID not found"`; INACTIVE ⇒ `"DID is not active"`; not-ASSIGNED (revoked) ⇒ `"DID is not assigned"`; foreign tenant (reassigned) ⇒ `"DID does not belong to tenant"` (PG-G4, PG-G5; unit matrix in `VoiceEligibilityDidSemanticsTest`).
- **Retry path** (`CampaignExecutionOrchestrator.isDidStillValid` → validator) and **dial dispatch** (eligibility gate) both re-validate per use — no trust caching.

**Design decision (kept):** dial-time DID enforcement deliberately stays in `VoiceEligibilityService` rather than rerouting through `CampaignResourceValidationService`. The voice-layer check is a superset (existence + not-deleted + ACTIVE + ASSIGNED + tenant match), it lives on the per-dial hot path, and rerouting would change the telephony error contract (`INVALID_DID` is asserted in `DidAllocationPostgresIntegrationTest` L414/L419) and add a cross-module dependency. Documented equivalence + tests instead.

---

## 14. Runtime-validation finding

Per-dial chain re-verified end-to-end: `OutboundDialService.processAttempt` → campaign lookup (now by `campaignId`, §7) → `CallEligibility.evaluate` (blocklists/DNC/DID/gateway + campaign targeting) → `VoiceRoutingService.resolveRoute` (primary/overflow/failover) → capacity reserve → dial → session/leg creation. Failure codes preserved verbatim: `TEMPORARILY_UNAVAILABLE` requeues (retry not consumed); capacity/CPS/headroom routing rejections requeue; `BUSY/NO_ANSWER/REJECTED/DIAL_FAILED` fail with reason; `PROVIDER_UNAVAILABLE`/`OutboundDialException` requeue; `CAMPAIGN_NOT_FOUND` remains the failure code for a vanished campaign. `OutboundDialServiceRoutingTest` (12 tests) still passes unchanged in assertions — only the fixture was corrected (§16).

---

## 15. Readiness finding

`CampaignReadinessService.evaluate` remains pure-read with deterministic reasons: lifecycle (`CAMPAIGN_NOT_EXECUTABLE_STATE` for anything but SCHEDULED/RUNNING), schedule coherence (IANA timezone, windows, days), content mode exclusivity, contact-group/DID/audio/TTS availability via the canonical validator, with reason codes and non-leaking messages preserved verbatim (`AUDIO_NOT_APPROVED` collapse, `AUDIO_STORAGE_REFERENCE_MISSING`, `TTS_TEMPLATE_NOT_APPROVED`/`_NOT_AVAILABLE` tenant-safe classification). The only change: the reseller-scoped campaign lookup is now hierarchy-bounded (§5), making `evaluate` 404-cloak foreign campaigns instead of evaluating them.

---

## 16. CREATE-UPDATE finding (no change required; one test-fixture correction)

`CampaignService.create/update` already validate every supplied reference through the canonical validator; PUT semantics replace config blocks wholesale and null references clear them (documented on `UpdateCampaignRequest`). `validateContent` exclusivity (AUDIO xor TTS) preserved. No CREATE/UPDATE defects found in scope. The only related fix was in a **test fixture**: `OutboundDialServiceRoutingTest` had collapsed campaign and execution into one UUID — corrected to distinct IDs (F1) with matching stubs.

---

## 17. Soft-delete finding

All scoped lookups filter `deleted_at IS NULL` on every resource repo, including the reseller-hierarchy lookups added this phase (`findByIdAndTenantIdInAndDeletedAtIsNull`). Suspended tenants are excluded from hierarchy resolution via `LifecycleStatus.ACTIVE` filtering (comment contract carried over from `CampaignService.hierarchyTenantIds`). PG-G6 proves: soft-deleted campaign disappears from tenant-scoped **and** platform lookups, readiness turns 404 (not "not ready"), and a soft-deleted DID is unusable at both validator and eligibility boundaries.

---

## 18. Approval-usability finding

Canonical validator semantics confirmed and pinned by existing VB-5E suites: audio requires APPROVED **and** a storage reference (`AUDIO_STORAGE_REFERENCE_MISSING` closed the VB-5B gap), TTS requires APPROVED + GLOBAL-or-own-TENANT scope with tenant-safe classification (V42), DID requires ACTIVE + ASSIGNED + same tenant. No approval-state changes in VB-5F; the new stale-resource tests exercise the *loss* of usability (revoke/unassign/delete) after it was granted.

---

## 19. Transaction-boundary finding

Harness-relevant and production-relevant facts: services constructed directly (no Spring proxy) have inactive `@Transactional`, so all service invocations in the new suite are wrapped in `TransactionTemplate`; concurrency tests use committed seeds (NOT_SUPPORTED propagation) so worker threads observe fixtures; conditional UPDATEs (`DidRepository`) execute inside those transactions. `processDueAttempts` remains a single transaction per batch with per-attempt save points via `attemptRepository.save` — unchanged semantics, verified by the corrected routing tests. `JpaAuditConfig` is imported by all PG harnesses so `AuditingEntityListener` populates audit columns.

---

## 20. Event-race finding

`CampaignExecutionOrchestrator.scheduledTick` (fixedDelay 30s) sequences: start REQUESTED executions → process retries → dial due attempts → ensure ESL processing → reconcile RUNNING executions. Idempotency of each step is what makes tick overlap safe: start is REQUESTED-gated; retry creation is existence-guarded + unique-indexed; dial is QUEUED-gated; reconcile is RUNNING-gated and terminal-only. ESL duplicate-event handling (no-op on non-live sessions) is covered by pre-existing tests (`PlayfileExecutionServiceTest`, `EslEventServiceTest`, `OutboundEventOrderingTest`) — no event-race defects found in scope; no changes made.

---

## 21. Defects discovered

| ID | Severity | Location | Description |
|---|---|---|---|
| **F1** | High (correctness) | `OutboundDialService.processAttempt` L115 | Campaign looked up by `attempt.getExecutionId()` instead of `attempt.getCampaignId()`; masked by a collapsed test fixture. |
| **F2** | High (IDOR, fail-open) | `CampaignReadinessService.findVisible` L376–382 | Reseller branch = platform-wide lookup ("simplified to platform-wide for now"). |
| **F3** | High (IDOR, fail-open) | `CampaignExecutionService.findVisible` (reseller branch) | Same platform-wide fallback for campaign visibility. |
| **F4** | Medium (broken scoping, fail-closed) | `CampaignExecutionService.findVisibleExecution` (reseller branch) | Passed `resellerId` as a tenantId — reseller-scope reads always 404. |
| **F5** | Medium (broken scoping, fail-closed) | `CallAttemptService.findVisibleExecution` (reseller branch) | Same resellerId-as-tenantId bug. |
| **F6** | Medium (broken scoping, fail-closed) | `CampaignExecutionOrchestrator.findVisibleExecution` (reseller branch) | Same resellerId-as-tenantId bug. |
| F7 | Low (dead code) | `OutboundDialService.buildRequest`/`resolveRoute` | Unused private methods; `resolveRoute` contained the last unscoped `findByIdAndDeletedAtIsNull` DID usage. |
| — | Gap (test) | `VoiceEligibilityService` | No dedicated unit tests for the dial-time DID semantics matrix. |

---

## 22. Defects fixed

All of the above, in-scope:

- **F1** — `OutboundDialService.processAttempt` now queries `findByIdAndTenantIdAndDeletedAtIsNull(attempt.getCampaignId(), attempt.getTenantId())` with an explanatory comment; dead methods removed (F7) together with the now-unused `DidRepository`/`GatewayRouting` constructor dependencies (constructor 12 → 10 args); `OutboundDialServiceRoutingTest` fixture rebuilt with distinct `campaignId`/`executionId` and a corrected stub — plus a new assertion path proving campaign resolution uses the campaign key (PG-G3).
- **F2** — `CampaignReadinessService` gained `TenantRepository` (final field, `@RequiredArgsConstructor`) and a hierarchy-bounded reseller branch (empty hierarchy → 404), mirroring `CampaignService`.
- **F3/F4/F5/F6** — `CampaignExecutionService`, `CallAttemptService`, `CampaignExecutionOrchestrator` each gained `TenantRepository` and the same hierarchy-bounded branches; `CampaignExecutionRepository` gained `findByIdAndTenantIdInAndDeletedAtIsNull(UUID, Collection<UUID>)`.
- **Test-constructor ripple** — `CampaignResourceValidationPostgresIntegrationTest`, `TtsGovernancePostgresIntegrationTest`, `AudioUploadPostgresIntegrationTest`, `DidAllocationPostgresIntegrationTest`, `PlayfileRetrySemanticsTest` updated for the new constructor args.
- **Gap** — `VoiceEligibilityDidSemanticsTest` (7 tests) added for the dial-time DID matrix.

**Error-semantics preservation:** no public message, reason code, or status code changed; every pre-existing assertion in touched test files passes without modification to its expectations. The F2-family fixes change behavior **only for reseller-scoped callers looking at rows outside their hierarchy** — previously either leaking (fail-open) or impossible (fail-closed-broken); both now behave as 404-cloaked, consistent with `CampaignService`.

---

## 23. Migrations

**None.** Flyway head remains **V42**. Every invariant exercised this phase is already DB-enforced (CHECKs, two partial unique indexes, conditional-UPDATE transitions) or service-enforced (state machine, idempotency pre-check, canonical validator). No new invariant was discovered that PostgreSQL does not already enforce. (Candidate noted but not needed: a composite FK from `call_attempts.campaign_id/execution_id` consistency — rejected as over-constraint for this phase; see §28.)

---

## 24. Tests added

**New files (17 tests):**

1. `backend/src/test/java/com/shivang/obd/campaign/CampaignGovernanceHardeningPostgresIntegrationTest.java` — 10 tests, real `postgres:16-alpine`, full Flyway V1..V42, harness per VB-4/5 conventions (static container, `NOT_SUPPORTED`, `TransactionTemplate`, committed seeds, JPQL cleanup):
   - PG-G1 readiness in reseller scope: in-hierarchy visible, foreign campaign 404-cloaked, empty-hierarchy 404.
   - PG-G2 reseller-bound executions/attempts: get/list/execute/getAttempt all 404 outside hierarchy (F3–F6 regression).
   - PG-G3 distinct campaign/execution IDs: correct-key lookup resolves, execution-key lookup empty (F1 regression at the DB boundary).
   - PG-G4 DID revoked after activation: readiness `DID_UNAVAILABLE` **and** dial-time `INVALID_DID` (`"DID is not assigned"`).
   - PG-G5 DID reassigned to a foreign tenant: dial-time `INVALID_DID` (`"DID does not belong to tenant"`).
   - PG-G6 soft-deleted campaign/resources: excluded from all scoped lookups; readiness 404; validator/eligibility unusable.
   - PG-G7 double activation: second `DRAFT→SCHEDULED` is `ConflictException` (409), row unchanged.
   - PG-G8 duplicate execute with same idempotency key: first execution returned, no new row.
   - PG-G9 8 concurrent executes, same key: exactly 1 success, 7 index rejections, 1 row (V21 partial unique index).
   - PG-G10 6 concurrent attempt inserts, same (execution, contact, number): exactly 1 winner, count = 1 (V22 partial unique index).
2. `backend/src/test/java/com/shivang/obd/telephony/VoiceEligibilityDidSemanticsTest.java` — 7 unit tests pinning the step-6 DID matrix: missing/deleted, null, INACTIVE, revoked (AVAILABLE), reassigned (foreign tenant), usable-passes, and usable→revoked transition blocks on the next dial.

**Modified test files:** `OutboundDialServiceRoutingTest` (F1 fixture, constructor), `CampaignResourceValidationPostgresIntegrationTest`, `TtsGovernancePostgresIntegrationTest`, `AudioUploadPostgresIntegrationTest`, `DidAllocationPostgresIntegrationTest` (readiness/validator constructor args), `PlayfileRetrySemanticsTest` (orchestrator constructor arg).

**Test count:** 830 → **847** (+17: 10 PG + 7 unit).

---

## 25. PostgreSQL/concurrency evidence

- Full-log totals: `[ERROR] Tests run: 847, Failures: 0, Errors: 13, Skipped: 1` (`/tmp/vb5f_final.log`; diff of `<<< ERROR` sets against `/tmp/vb5f_baseline.log` = **identical**).
- PG suite result: `Tests run: 10, Failures: 0, Errors: 0` in `CampaignGovernanceHardeningPostgresIntegrationTest` (7.5 s, container-start excluded).
- Race evidence in the log: Hibernate logs `ERROR: duplicate key value violates unique constraint "uq_call_attempts_execution_contact_attempt"` during PG-G10 — the DB rejecting 5 of 6 concurrent inserts; the test then asserts exactly 1 live row. PG-G9 likewise collapses 8 concurrent executes to 1 row via `uq_campaign_executions_campaign_idempotency`.
- Error-set diff command evidence: `diff /tmp/vb5f_base_errs.txt /tmp/vb5f_final_errs.txt` → `IDENTICAL ERROR SETS`.
- Both new suites run as part of `clean test` (no `-Dtest` filtering) — they are part of the standing regression, not one-off runs.

---

## 26. Full regression

Command (machine quirk honored: `MAVEN_OPTS` heap cap, bash/POSIX):

```
cd backend
MAVEN_OPTS="-Xmx768m -XX:MaxMetaspaceSize=384m" ./mvnw clean test -DargLine="-Xmx1g"
```

| Metric | Baseline (`/tmp/vb5f_baseline.log`) | Final (`/tmp/vb5f_final.log`) | Δ |
|---|---|---|---|
| Tests | 830 | **847** | +17 |
| Failures | 0 | **0** | — |
| Errors | 13 | **13** | identical set (diff-verified) |
| Skipped | 1 | **1** | — |
| Flyway head | V42 | **V42** | — |
| Modulith cycle groups | 2 | **2** | same ("Slice campaign →", "Slice telephony →") |
| Build exit | 1 (pre-existing errors) | **1** | same cause |

The 13 errors, decomposed: `ArchitectureTest.modularBoundariesAreIntact` ×1, `ProvisioningSmokeIntegrationTest.bootstrapSuperAdminHasNoOrganizationalHomeAndCanLogIn` ×1, `SecuritySliceTest.*` ×11 — all pre-existing and documented in the VB-5E report; none touched by this phase.

---

## 27. Architecture result

**Unchanged.** `ArchitectureTest.modularBoundariesAreIntact` fails exactly as at baseline with the same 2 cycle groups (`- Cycle detected: Slice campaign ->`, `- Cycle detected: Slice telephony ->` at the same log positions relative to suite start). No new violations, no new package cycles introduced by the added imports (`tenant.TenantRepository` into campaign services — already a campaign→tenant dependency used by `CampaignService`; `common.lifecycle.LifecycleStatus` likewise pre-existing usage). The phase adds no cross-module new coupling: the dial-time DID tests live in the existing telephony test package and the governance suite in the campaign package.

---

## 28. Remaining limitations

1. **Two modulith cycle groups persist** (pre-existing, out of scope): `Slice campaign ↔ …` and `Slice telephony ↔ …`; documented as the standing `ArchitectureTest` error.
2. **No `@Version` optimistic locking** on campaign-domain aggregates — safe under current whole-row writes + DB-enforced invariants, but a future editor with per-field concurrent writes should add versioning.
3. **`CallEligibilityService` has no dedicated unit test class** (gap noted during inspection, unchanged this phase); its blocklist/DNC/DID/gateway composition is exercised indirectly via `VoiceEligibilityDidSemanticsTest` + routing tests + PG suites.
4. **CampaignService PUT null-DID policy** — a requested-but-invalid DID supplied as an absent/`null` reference clears silently; documented PUT contract, unchanged (deferred candidate in VB-5E, still open as a product decision).
5. **Execution-engine edge rejection** — `SYSTEM_DRIVEN_TRANSITIONS` still rejects manual `SCHEDULED→RUNNING` etc. until the scheduler phase owns them (intended).
6. **Orchestrator tick is a single-JVM `@Scheduled`** — horizontal deployments need the future scheduler/shedlock-style coordination (next milestone).

---

## 29. Deferred items

- **Campaign Execution Scheduler / pacing / CPS** — the explicit next milestone; deliberately untouched here (contract STOP line).
- **Optimistic locking (`@Version`)** for campaign/execution/attempt aggregates if the scheduler phase introduces multi-writer contention on single rows.
- **Composite consistency constraint** linking `call_attempts.campaign_id` to its `execution_id`'s campaign — candidate for a V43 FK/trigger if the scheduler phase cannot guarantee the pairing in code.
- **`CallEligibilityService` unit suite** — recommended alongside the scheduler work (it will own retry/timing semantics next).
- **Requested-but-invalid DID null-policy on PUT** — needs product input.
- **VB-5E-noted candidate**: unify the three `hierarchyTenantIds` copies behind a shared hierarchy service — cosmetic now that all five boundary implementations share one pattern; not worth the churn mid-series.

---

## 30. Definition-of-Done checklist

| # | Item | Status |
|---|---|---|
| 1 | Inspection of all VB-5E-layer + adjacent runtime boundaries completed | ✅ §4–§20 |
| 2 | Baseline reproduced exactly before changes | ✅ §2 (830/0/13/1) |
| 3 | All in-scope defects identified and classified | ✅ §21 (F1–F7 + gap) |
| 4 | F1 campaign/execution ID mixup fixed | ✅ §22 |
| 5 | F2 family reseller-scope defects fixed (fail-open ×2, fail-closed-broken ×3) | ✅ §22 |
| 6 | Dead code removed; unused dependencies dropped | ✅ §22 (F7) |
| 7 | Error semantics / messages / reason codes preserved verbatim | ✅ §22 |
| 8 | Dial-time DID semantics regression-tested (unit matrix) | ✅ §24 (7 tests) |
| 9 | Stale-resource rejection proven at readiness **and** dial time on real PG | ✅ §24 (PG-G4/G5/G6) |
| 10 | Reseller-hierarchy scoping proven on real PG | ✅ §24 (PG-G1/G2) |
| 11 | Concurrency invariants proven with multi-threaded PG tests | ✅ §24 (PG-G7/G9/G10) |
| 12 | Idempotency (service + DB layers) proven | ✅ §12, §24 (PG-G8/G9) |
| 13 | Soft-delete governance proven | ✅ §24 (PG-G6) |
| 14 | Message non-leakage preserved and tested | ✅ §15, §17 (404-cloaked, §13 codes) |
| 15 | Focused suites green before full run | ✅ 38/0/0 unit; 10/0/0 PG |
| 16 | Full regression run with machine-quirk command | ✅ §26 |
| 17 | Final = baseline + new tests, zero new failures/errors | ✅ 847/0/13/1, identical error sets |
| 18 | Flyway head unchanged (no migration needed) | ✅ V42 |
| 19 | Architecture result unchanged (2 cycle groups) | ✅ §27 |
| 20 | Report written (this document) and phase **STOPPED** before next milestone | ✅ — Campaign Execution Scheduler **not** started |

---

## Final verdict

**VB-5F COMPLETE AND VERIFIED.** 847 tests / 0 failures / 13 pre-existing errors / 1 skipped; Flyway head V42; architecture output unchanged. Five reseller-scope defects (two leaking, three broken) and one campaign/execution ID-mixup in the dial path were fixed with zero change to the public error contract; dial-time stale-DID rejection, soft-delete governance, reseller-hierarchy scoping, and DB-enforced concurrency invariants are now regression-proven on real PostgreSQL. The platform remains safe to harden further in the Campaign Execution Scheduler milestone.
