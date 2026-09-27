# VB-5D — TTS Governance / Scope & Approval Foundation — Implementation Report

## 1. Status

**VB-5D — COMPLETE**

## 2. Baseline

Fresh `./mvnw clean test` immediately before implementation:

- **759 tests, 0 failures, 13 errors, 1 skipped** (matches the VB-5C exit exactly)
- 13 errors in the 3 documented pre-existing classes (ArchitectureTest, ProvisioningSmokeIntegrationTest, SecuritySliceTest)
- 2 pre-existing modulith cycles (`campaign ↔ voice ↔ telephony`)
- Flyway head: **V41**

**Baseline anomaly, classified:** the first baseline attempt reported **415 tests** —
the surefire forked JVM was killed by a native-OOM crash mid-run
(`hs_err_pid4624.log`: `Native memory allocation (malloc) failed`), truncating
discovery during the voice module. 7 crash logs on disk confirm a recurring
environmental pattern on this machine. The pre-crash results (13 errors, 1
skipped, 2 cycles) already matched VB-5C. A re-run with a capped fork heap
(`-DargLine="-Xmx1g"`) reproduced the full **759/0/13/1** baseline. The anomaly
was environmental, not a code/test change, and the stable invocation for this
machine is `./mvnw clean test -DargLine="-Xmx1g"` with memory-capped `MAVEN_OPTS`.

## 3. Scope

Implemented: `TtsTemplateScope` (`GLOBAL|TENANT`); V42 migration (nullable
`tenant_id`, backfill `scope='TENANT'`, mutual-exclusion CHECKs, GLOBAL status
index); platform-only GLOBAL lifecycle (create APPROVED + tenantless, update /
delete / approve / reject via `AccessCheck.platformWide()`); preserved TENANT
rules (PENDING_APPROVAL start, approval reset on content edit, platform seeding
into a target tenant); GLOBAL visibility for tenants (own + APPROVED GLOBAL
catalog) and resellers (hierarchy + APPROVED GLOBAL, read-only); campaign
usability predicate `GLOBAL OR (TENANT AND tenant match) AND APPROVED AND not
deleted` in both `CampaignService` (write-time + activation) and
`CampaignReadinessService` (reason split); unit + real-PostgreSQL integration
tests.

Not implemented: synthesis/providers/execution (no runtime TTS consumer exists),
copying GLOBAL into tenants, fake tenant UUIDs, billing, new capabilities, any
campaign or audio changes beyond the two TTS usability call sites.

## 4. Existing TTS Architecture (reused, not replaced)

- `TtsTemplateEntity` (V20): soft-delete audit columns, `name`, `template_text`,
  JSONB `variables` (`List<TtsTemplateVariable>`), `status`
  `TtsTemplateStatus{PENDING_APPROVAL,APPROVED,REJECTED}` with DB CHECK.
- `TtsTemplateService` boundary conventions reused verbatim:
  server-derived `OrganizationContextHolder` scope, 404-cloaking,
  `TTS_VIEW`/`TTS_MANAGE`/`TTS_APPROVE` capabilities (V20; unchanged),
  `ResponseFactory` envelopes, `ConflictException`/`BusinessException`/
  `ResourceNotFoundException`, `TtsTemplateValidation` contract checks (400s).
- Consumers enumerated before implementation: `CampaignService`
  (shape-only `validateContent` + usability re-check at write/activation,
  ~line 529), `CampaignReadinessService.checkTtsTemplate` (~line 329). No
  runtime synthesis consumer exists — governance is validated at the readiness
  seam only, per contract.

## 5. Scope Design

Two ownership scopes, mutually exclusive with tenancy and enforced by the
database so no application bug or manual write can mint a cross-scope row:

```text
GLOBAL (platform-owned):
  tenant_id NULL, managed + gated only with platform authorization
  (PLATFORM-scope TTS_MANAGE / TTS_APPROVE ⇒ AccessCheck.platformWide()).
  APPROVED + not deleted ⇒ usable by EVERY tenant.

TENANT (tenant-owned):
  tenant_id NOT NULL, tenant-created rows start PENDING_APPROVAL,
  approved within the owning tenant, usable only by the owner.
  Pre-VB-5D behavior preserved bit-for-bit, including the platform
  seeding path (created APPROVED into a target tenant).
```

- Backfill (V42): every pre-VB-5D row becomes `scope='TENANT'` — the only
  representable state since V20 (`tenant_id NOT NULL`). The campaign predicate
  for backfilled rows is unchanged, so no campaign behavior changes until a
  GLOBAL row exists.
- No GLOBAL rows are fabricated by the migration; platform operators mint them
  at runtime through the normal create path.
- GLOBAL is never copied into tenants and never carries a tenant stamp.
  Creation with `scope=GLOBAL` + `tenantId` is rejected (400).

## 6. API

- `CreateTtsTemplateRequest` gains optional `scope` (default `TENANT`).
  GLOBAL requires platform scope and a null `tenantId`; created APPROVED.
  TENANT follows pre-VB-5D rules (tenant context → own tenant, PENDING;
  platform caller + `tenantId` → seeded APPROVED into that tenant).
- `TtsTemplateResponse` gains `scope` (mapper is the sole constructor).
- No new endpoints; existing create/read/list/update/delete/approve/reject
  routes now enforce scope rules.

Errors (existing taxonomy only): 400 GLOBAL with tenant stamp / GLOBAL without
platform scope is 403 via `requireCapability`, contract violations; 404
foreign-tenant rows and not-yet-visible GLOBAL rows (cloaking, indistinguishable
from missing); 409 repeated same-target approve/reject transitions.

## 7. Authorization

- **GLOBAL management/gating**: `TTS_MANAGE`/`TTS_APPROVE` via
  `AccessCheck.platformWide()` — satisfied only by PLATFORM-scope assignments
  (verified in `AuthorizationService.covers`), so resellers and tenants cannot
  create, update, delete, approve, or reject GLOBAL rows even where they can
  see them. This also fixes a latent defect: `transition()` previously used
  `AccessCheck.forTenant(entity.getTenantId())`, which for tenantless rows
  would never be satisfiable.
- **GLOBAL visibility**: read path uses the caller's own `TTS_VIEW` boundary
  (tenant check for tenant callers, reseller check for resellers, platform-wide
  for platform) — GLOBAL is a shared catalog, not a platform secret.
- **TENANT**: unchanged (`forTenant(entity.tenantId)`); foreign rows 404-cloak
  in `findVisible` before any authorization decision.
- Scope itself is never accepted from a client as authorization: GLOBAL
  creation is gated by `platformWide()`, and the V42 CHECKs backstop the
  invariants at the storage layer.

## 8. Visibility & Usability Semantics (product decisions, signed off)

| Caller | list / getById | mutate / gate |
|---|---|---|
| Tenant | own TENANT rows (any status) + APPROVED non-deleted GLOBAL; never other tenants' rows | own TENANT rows only; GLOBAL → 403 (G4) |
| Reseller | hierarchy tenants' TENANT rows + APPROVED GLOBAL (read-only) | hierarchy TENANT rows only; GLOBAL → 403 |
| Platform | everything | everything |

List visibility is deliberately **not** campaign usability: tenants manage
their own unapproved rows, while only `APPROVED` GLOBAL rows enter the shared
catalog (`TtsTemplateSpecifications.visibleToTenant/visibleToTenants/
visibleGlobalOnly`). The authoritative campaign usability predicate remains
`existsUsableForTenant(id, tenantId)`.

## 9. Database / Migration

**V42__add_tts_template_scope.sql** (after V41; V1–V41 untouched):

- `ADD COLUMN scope VARCHAR(20)` → backfill `UPDATE ... SET scope='TENANT'` →
  `SET NOT NULL` → `SET DEFAULT 'TENANT'`
- `ALTER COLUMN tenant_id DROP NOT NULL` (GLOBAL rows are tenantless)
- `ck_tts_templates_scope` (`IN ('GLOBAL','TENANT')`)
- `ck_tts_templates_scope_global_no_tenant` (`GLOBAL ⇒ tenant_id IS NULL`)
- `ck_tts_templates_scope_tenant_requires_tenant` (`TENANT ⇒ tenant_id IS NOT NULL`)
- Partial index `idx_tts_templates_global_status_deleted (status, deleted_at)
  WHERE scope='GLOBAL'` — shape-matches the shared-catalog lookup

Verified on a fresh `postgres:16-alpine` container through the full chain by
every integration test. Constraint rejections proven at the DB level (PG-M3,
PG-M4, PG-M5: GLOBAL-with-tenant, TENANT-without-tenant, NULL-scope).

## 10. Campaign Readiness Integration

`CampaignReadinessService.checkTtsTemplate` now resolves usability via the
shared predicate, with a tenant-safe reason split:

- `TTS_TEMPLATE_NOT_APPROVED` — an accessible row exists (own TENANT row or a
  GLOBAL row at any status) but is `PENDING_APPROVAL`/`REJECTED`.
- `TTS_TEMPLATE_NOT_AVAILABLE` — missing, deleted, or cross-tenant; foreign-row
  existence is never revealed.

Classification uses `existsAccessibleForTenant` (tenant-safe accessibility
superset), never the raw usability predicate alone — `false` cannot distinguish
"unapproved" from "missing/cross-scope" without leaking existence.

`CampaignService.validateContentReferences` (write-time + activation) switched
to the same `existsUsableForTenant`, so campaigns can reference APPROVED GLOBAL
templates at creation and activation re-checks still block when approval is
revoked (PG-C8, PG-C9). Audio/DID checks untouched.

## 11. Tenant / Reseller Isolation

- Tenant callers: foreign-tenant rows and unapproved GLOBAL rows 404-cloak in
  `findVisible`; the tenant list boundary can never emit another tenant's row.
- Reseller callers: hierarchy-filtered for TENANT rows; GLOBAL rows visible
  read-only; mutations demand platform scope regardless of visibility.
- Cross-tenant campaign references resolve to `TTS_TEMPLATE_NOT_AVAILABLE`,
  not `NOT_APPROVED` (no existence leak) — proven by PG-C4.

## 12. Tests

| Suite | Count | Result |
|---|---|---|
| `TtsTemplateServiceTest` (unit matrix; authz stub emulates real scope semantics) | 18 | 18/18 ✓ |
| `TtsGovernancePostgresIntegrationTest` (real PostgreSQL, V1..V42 chain) | 20 | 20/20 ✓ |
| **VB-5D total** | **38** | **38/38 ✓** |

Unit matrix: G1 platform creates GLOBAL (APPROVED, tenantless), G2 tenant
cannot create GLOBAL, G2b GLOBAL+tenantId rejected, G3 pending GLOBAL
404-cloaked from tenants, G3b approved GLOBAL readable by tenants, G4 tenant
cannot update/delete/reject GLOBAL, G4b platform update+delete GLOBAL, G4c
GLOBAL edit downgrades approval, A1 tenant approves own, A1b foreign approve
404-cloaked, A1c platform gates GLOBAL, A2 repeated approve conflicts, A2b
APPROVED→REJECTED is a legal revocation, A3 edit resets approval, B1/B1b
foreign read/delete 404-cloaked.

PG coverage: M1 V42 applied in chain, M2 TENANT default (backfill shape), M3
GLOBAL-with-tenant CHECK rejection, M4 TENANT-without-tenant CHECK rejection,
M5 NULL-scope rejection; G1 usability predicate matrix (own/foreign/global ×
approved/pending), G2 accessibility superset (tenant-safe classification), G3
list visibility; E1 platform→GLOBAL end-to-end (both tenants see + use), E2
pending GLOBAL cloaked, E3 GLOBAL+tenantId rejected, E4 tenant lifecycle with
approval reset, E5 idempotent-transition conflicts; C1 own approved TENANT
ready, C2 APPROVED GLOBAL ready, C3 own pending → NOT_APPROVED, C4 cross-tenant
→ NOT_AVAILABLE, C5 pending GLOBAL → NOT_APPROVED then deleted GLOBAL →
NOT_AVAILABLE, C6 missing id → NOT_AVAILABLE, C7 rejected GLOBAL →
NOT_APPROVED, C8 GLOBAL campaign create+ready+DRAFT→SCHEDULED, C9 approval
revoked after creation → activation blocked.

Harness notes: container started in a static initializer (before
`@DynamicPropertySource`); `Propagation.NOT_SUPPORTED` + `TransactionTemplate`
(CHECK-violation tests abort their transaction; services constructed with
`new` have inactive `@Transactional`); entity defaults exercised without
pre-setting ids (`@UuidGenerator` assigns). Two pre-existing campaign unit
tests stubbing the old repository method were updated to the new predicate
(test-only; the campaign services under test are production-touched anyway).

## 13. Full Regression

`./mvnw clean test -DargLine="-Xmx1g"`: **797 tests, 0 failures, 13 errors, 1 skipped**

- +38 new VB-5D tests, all green (baseline was 759).
- The 13 errors are the **same 3 pre-existing classes** (ArchitectureTest 1,
  ProvisioningSmokeIntegrationTest 1, SecuritySliceTest 11) — none introduced
  by VB-5D.
- Architecture: the **same 2 pre-existing cycle groups** (campaign ↔ voice ↔
  telephony), byte-identical grep output between baseline and regression logs;
  no `tts` involvement.

## 14. Architecture / Modulith

No new module, no new package, no new dependency edge. Changes live in the
existing `tts` package (enum, entity, repository, specifications, mapper, DTOs,
service) plus two call-site edits in `campaign` (the two enumerated TTS
consumers) — both edges already existed. V42 touches only `tts_templates`.

## 15. Defects Found

1. **`transition()` GLOBAL authorization was unsatisfiable** — approve/reject
   used `AccessCheck.forTenant(entity.getTenantId())`, which can never pass for
   a tenantless row; GLOBAL gating would have been impossible (403 for
   everyone). Fixed: GLOBAL rows gate via `AccessCheck.platformWide()`
   (production fix; caught during design walkthrough).
2. **V42 initially missing `ALTER COLUMN tenant_id DROP NOT NULL`** — GLOBAL
   inserts failed on the V20 NOT NULL constraint. Caught immediately by the PG
   integration suite (M-section + every GLOBAL seeding test) and fixed in the
   migration.
3. **Mapper defensive fallback** — `TtsTemplateResponse` mapping tolerates a
   null `scope` marker by deriving from `tenantId` (only reachable for
   pre-migration entities in tests; the DB default makes it unreachable in
   production writes).
4. Environmental (not production): recurring JVM native-OOM crashes on this
   machine (7 `hs_err_pid*.log` files); documented mitigation is the
   memory-capped Maven/surefire invocation recorded in §2.

## 16. Defects Fixed

All of the above, each protected by the test suites (38/38 green on the final
run). No open defects.

## 17. Known Limitations

- GLOBAL templates are platform-curated only; there is no tenant-facing request
  workflow ("request a GLOBAL template") — out of contract scope.
- No GLOBAL seeding/migration data: the platform mints GLOBAL rows at runtime
  (contract: V42 makes them representable, nothing else).
- The readiness reason split requires two queries when unusable (usability +
  accessibility); both are indexed point lookups.
- List visibility exposes APPROVED GLOBAL rows to resellers' catalog views;
  per the signed-off decision this is read-only and enforced by authorization,
  not by hiding.

## 18. Out of Scope (not implemented)

TTS synthesis, providers, runtime rendering, audio pipeline changes, campaign
execution/scheduling, billing, new capabilities or role seeds, GLOBAL-to-tenant
copying, fake tenant UUIDs, Kafka/Redis/K8s, frontend work.

## 19. Final DoD

- [x] Existing TTS architecture inspected (all consumers enumerated)
- [x] Clean baseline established (759/0/13/1, 2 cycles, V41) — anomaly classified
- [x] `TtsTemplateScope` enum `GLOBAL|TENANT`
- [x] GLOBAL = platform-owned, `tenant_id NULL`, platform authorization only
- [x] TENANT = `tenant_id NOT NULL`, tenant-created start PENDING_APPROVAL
- [x] V42 after V41; backfill `scope='TENANT'`; no fabricated GLOBAL rows
- [x] DB CHECKs enforce GLOBAL⇒no-tenant, TENANT⇒tenant, enum domain, NOT NULL
- [x] Approved GLOBAL usable by every tenant (PG-G1, PG-C2)
- [x] No GLOBAL copying, no fake tenant UUIDs (PG-E3 rejects the shape)
- [x] Existing capabilities TTS_VIEW/TTS_MANAGE/TTS_APPROVE reused (no new seeds)
- [x] Tenant list = own + approved GLOBAL; never other tenants' rows (PG-G3)
- [x] Reseller visibility read-only for GLOBAL (unit G4; §8 matrix)
- [x] Campaign readiness accepts GLOBAL+APPROVED or TENANT+APPROVED+tenant match (C1–C9)
- [x] Reason split tenant-safe (C3/C4/C5/C7)
- [x] Runtime governance validation seam only (no synthesis; none exists)
- [x] Approval reset on GLOBAL edit (G4c); idempotent transitions (A2, E5)
- [x] Security matrix across Tenant A/B/Platform (unit + PG)
- [x] Unit tests pass (18/18)
- [x] PostgreSQL integration tests pass (20/20)
- [x] Full regression executed (797/0/13/1)
- [x] Architecture tests executed (2 pre-existing cycles, unchanged)
- [x] No new Modulith cycle
- [x] No VB-5D scope leakage
- [x] Implementation report created
- [x] Final verdict documented

## 20. Final Verdict

**READY — TTS scope governance foundation complete**

(Stopping here per the phase contract — no synthesis, no providers, no campaign
execution until explicit review.)
