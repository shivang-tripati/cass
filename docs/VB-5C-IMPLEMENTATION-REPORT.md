# VB-5C — DID/DNID Assignment & Allocation Lifecycle — Implementation Report

## 1. Status

**VB-5C — COMPLETE**

## 2. Baseline

Fresh `./mvnw clean test` immediately before implementation:

- **730 tests, 0 failures, 13 errors, 1 skipped**
- 13 errors in the 3 documented pre-existing classes (ArchitectureTest, ProvisioningSmokeIntegrationTest, SecuritySliceTest)
- 2 pre-existing modulith cycles (`campaign ↔ voice ↔ telephony`)
- Flyway head: **V40**

## 3. Scope

Implemented: platform→reseller, platform→tenant, reseller→tenant assignment; explicit revoke with provenance restoration; conditional-UPDATE concurrency; V41 provenance column; 2 REST endpoints; unit + real-PostgreSQL integration + concurrency tests.

Not implemented: billing, DID purchasing/provisioning, number porting/recycling/expiry, TTS, campaign execution, any new infrastructure.

## 4. Existing DID Architecture (reused, not replaced)

- `DidEntity` (V16): `tenantId` (nullable), `resellerId` (nullable), canonical E.164 with live-partial-unique index, `DidStatus{ACTIVE,INACTIVE}`, `AllocationState{AVAILABLE,ASSIGNED}`, JSONB capabilities, VB-4D inbound-destination triple, soft-delete audit columns.
- Pool representation unchanged: platform pool = both null; reseller pool = `resellerId` only; tenant-assigned = `tenantId` set (+ `ASSIGNED`).
- `DidService` boundary conventions reused verbatim: server-derived `OrganizationContextHolder` scope, scoped queries with 404-cloaking, `DID_VIEW`/`DID_MANAGE` capabilities, `ResponseFactory` envelopes, `ConflictException`/`BusinessException`/`ResourceNotFoundException`.
- Consumers verified and untouched: `CampaignReadinessService.checkDid` (ACTIVE+ASSIGNED+tenant), `CallAttemptService`, `VoiceEligibilityService` (`INVALID_DID` on not-assigned/foreign), `InboundCallService` (E.164 → tenant + inbound destination), `AgentOutboundCallService` (tenant CLI DID), `DidSpecifications.ownedByResellerOrTenants`.

## 5. Allocation / Provenance Design

The one thing `tenant_id`/`reseller_id` cannot express is *which pool a live assignment came from* — after a platform DID is stamped with a reseller id, revoking a reseller-pool hold and revoking a reseller-sourced tenant assignment look identical. Smallest fix: one nullable provenance column.

```text
AllocationSource (new enum, V41):
  PLATFORM : assigned directly out of the platform pool
  RESELLER : assigned out of the reseller pool stamped in reseller_id
  null     : pristine platform-pool number (also: platform-pool hold)

Transitions:
  Platform pool  --assign(reseller)--> Reseller pool   (source=RESELLER, state stays AVAILABLE)
  Reseller pool  --assign(tenant)  --> Tenant         (source=RESELLER, state=ASSIGNED)
  Platform pool  --assign(tenant)  --> Tenant         (source=PLATFORM, state=ASSIGNED)
  Tenant         --revoke--------->  source=RESELLER ? Reseller pool : Platform pool
  Reseller pool  --revoke--------->  Platform pool   (stamp + provenance cleared)
```

- Backfill (V41): live tenant-assigned DIDs with a reseller stamp → `RESELLER` (the only way `create()` produced them); everything else → `NULL`. Backward compatible: all existing readers ignore the new column.
- DB guards: `ck_dids_allocation_source` (enum CHECK), `ck_dids_allocation_source_requires_reseller` (RESELLER ⇒ reseller_id NOT NULL), `idx_dids_reseller_pool` partial index for pool queries. V16's `ck_dids_assigned_requires_tenant` continues to hold.
- Billing provenance: the (source pool, reseller stamp) pair answers "did this tenant's DID come from the platform or a reseller, and which reseller" without any billing ledger.

## 6. API

| Endpoint | Behavior |
|---|---|
| `POST /api/v1/dids/{id}/assign` | Body `{"targetId": "<uuid>"}`. Platform scope: target resolves as reseller first, then tenant (disjoint tables); reseller scope: own pool DID → own active tenant. Tenant scope: rejected. Returns `AssignDidResponse{didId, allocationState, allocationSource, tenantId, resellerId}`. |
| `POST /api/v1/dids/{id}/revoke` | Revokes the live assignment (tenant hold or reseller-pool hold), restoring provenance. Tenant scope: rejected. |

Errors (existing taxonomy only): 404 foreign/missing DID (cloaking), 400 inactive DID / invalid target / tenants cannot assign-revoke, 409 already-assigned, not-available (lost race), not-assigned. Assignment requires `ACTIVE` status — an operationally INACTIVE number can never be activated by assignment; INACTIVE+ASSIGNED (deactivated after assignment) remains representable and revocable.

## 7. Authorization

- **Platform**: `DID_MANAGE` via `AccessCheck.platformWide()`; may assign platform-pool DIDs to any active reseller/tenant and revoke any live assignment.
- **Reseller**: `DID_MANAGE` via `AccessCheck.forReseller(own)`; visibility enforced by the existing `findVisible` boundary (foreign-pool and platform-pool DIDs are invisible → 404); target tenant must be `resellerId`-matched, active, and not deleted.
- **Tenant**: no assignment/revocation authority (explicit rejection), preserving the VB-5A rule that tenants *use* DIDs but do not manage platform inventory.
- Target IDs are never trusted for authorization — hierarchy is validated against server-side tenant/reseller state.

## 8. State Transitions (all DB-enforced by WHERE clauses)

| Operation | Precondition (in the UPDATE) | Post-state |
|---|---|---|
| platform→reseller | `AVAILABLE`, live, tenant null, reseller null | `AVAILABLE`, `resellerId=T`, `source=RESELLER` |
| platform→tenant | `AVAILABLE`, live, tenant null, reseller null | `ASSIGNED`, `tenantId=T`, `source=PLATFORM` |
| reseller→tenant | `AVAILABLE`, live, tenant null, `resellerId=R` | `ASSIGNED`, `tenantId=T`, `source=RESELLER` |
| revoke tenant hold | `ASSIGNED`, live, `tenantId=T` | `AVAILABLE`, tenant null, source = RESELLER?RESELLER:null |
| revoke reseller hold | `AVAILABLE`, live, tenant null, `resellerId=R`, `source=RESELLER` | `AVAILABLE`, reseller null, source null |

`rows == 1` = won; `rows == 0` = deterministic conflict (already assigned / not held / lost race). Deleted DIDs are excluded by `deleted_at IS NULL` in every transition.

## 9. Concurrency Strategy

Pure PostgreSQL conditional UPDATEs in a `@Transactional` service method. No `@Version`, no advisory locks, no Redis, no check-then-write window: the eligibility predicate and the ownership write are the same atomic statement. No distributed-lock justification exists, so none was introduced.

## 10. Database / Migration

**V41__add_did_allocation_source.sql** — one nullable column, two CHECK constraints, one partial index, one idempotent backfill. V1–V40 untouched. Verified on a fresh `postgres:16-alpine` container through the full chain by every integration test.

## 11. Campaign Readiness Integration

Real-PostgreSQL test proves: before assignment → `DID_UNAVAILABLE`; after platform→tenant assignment → DID reason gone; after revoke → `DID_UNAVAILABLE` again. `CampaignReadinessService` itself is untouched (zero diff in campaign code).

## 12. Voice Eligibility Integration

Real-PostgreSQL test with the real `VoiceEligibilityService`: unassigned/revoked DID → `INVALID_DID` for the tenant; assigned DID passes the DID dimension (reason code leaves `INVALID_DID`). `VoiceEligibilityService`/`VoiceRoutingService` untouched.

## 13. Tenant / Reseller Isolation

- 404-cloaked scoped lookups reused (`findByIdAndTenantIdAndDeletedAtIsNull` for tenants; `findVisible` for reseller/platform) — unit tests prove foreign-pool and platform-pool DIDs are invisible to reseller scope.
- Conditional UPDATEs are ownership-scoped (reseller variant requires `resellerId = R`), so even a concurrent caller cannot move a DID across reseller boundaries.
- Inbound routing cannot use revoked DIDs (tenant_id null + no destination) — proven by PG-B3.

## 14. Tests

| Suite | Count | Result |
|---|---|---|
| `DidAllocationServiceTest` (unit: flows, scopes, conflicts, provenance via mocks) | 17 | 17/17 ✓ |
| `DidAllocationPostgresIntegrationTest` (real PostgreSQL) | 12 | 12/12 ✓ |
| **VB-5C total** | **29** | **29/29 ✓** |

PG coverage: A1 platform→reseller round-trip, A2 platform→tenant round-trip, A3 reseller→tenant + provenance restoration, A4 deleted-DID rejection, A5 inactive-DID rejection, A6 no-silent-reassignment, A7 rollback leaves no partial state, B1 campaign readiness before/after assign/revoke, B2 voice eligibility before/after, B3 inbound resolution after revoke, C1 **20-thread assignment race → exactly 1 success**, C2 concurrent revoke-vs-assign ends in a valid lifecycle state.

## 15. PostgreSQL Concurrency Results

- **PG-C1**: 20 threads, same reseller-pool DID, independent transactions → **exactly 1 success, 19 clean conflicts**; final row: single owner, `ASSIGNED`, provenance intact.
- **PG-C2**: concurrent `revokeFromTenant` (tenant A) vs `assignFromResellerPoolToTenant` (tenant B) → final state is always one valid lifecycle state (revoked-to-pool or single-owner assigned); no duplicate ownership, no corrupted provenance.

## 16. Full Regression

`./mvnw clean test`: **759 tests, 0 failures, 13 errors, 1 skipped**

- +29 new VB-5C tests, all green (baseline was 730).
- The 13 errors are the **same 3 pre-existing classes** (ArchitectureTest 1, ProvisioningSmokeIntegrationTest 1, SecuritySliceTest 11) — none introduced by VB-5C.
- Architecture: **2 pre-existing cycle groups** (campaign ↔ voice ↔ telephony), unchanged; no `did` involvement. `did/` imports nothing new outside `authz/common/security/tenant/reseller`.

## 17. Architecture / Modulith

No new module, no new package, no new dependency edge. All logic lives in the existing `did` package following its established service/controller/specifications/DTO layout.

## 18. Defects Found

1. **JPQL bulk UPDATE bypasses the persistence context** — after `assign*`/`revoke*`, `findById` returned stale cached entities, so service code read pre-transition state. Fixed with `@Modifying(clearAutomatically = true)` on all five transition queries (production fix; caught by integration tests).
2. **`revokeFromReseller` precondition mismatch** — initial design required `ASSIGNED`, but a reseller-pool hold is `AVAILABLE` by the platform's established pool representation; revoke would always have failed. Corrected to match `AVAILABLE` + `resellerId` + `source=RESELLER` (production fix).
3. Test-harness (documented, not production): `@DataJpaTest` default wrapping transaction hid committed state from worker threads → switched to `Propagation.NOT_SUPPORTED` per the VB-4 harness convention; `@DynamicPropertySource` ran before `@BeforeAll` → static-initializer container start; a self-recursive test helper caused a `StackOverflowError` during development (fixed; not a production issue).

## 19. Defects Fixed

All of the above, each protected by the PG integration suite (12/12 green on the final run).

## 20. Known Limitations

- No assignment history/audit trail (rows mutate in place; provenance is current-state only). Billing-grade history is a future concern.
- Target resolution in the platform flow tries reseller-then-tenant; UUID collisions across the two tables are the theoretical ambiguity, practically impossible.
- No dedicated DID lifecycle security events (no existing resource-change event convention to reuse; documented per §23).
- Reseller revoke requires the DID to be visible in the caller's hierarchy (404-cloaked otherwise) — consistent with all existing DID reads.

## 21. Out of Scope (not implemented)

Campaign scheduling/execution, dialing modes, TTS (all of it), audio changes, billing/invoicing, DID purchasing/provisioning, number porting/recycling/expiry automation, Kafka/Redis/K8s, generic allocation frameworks, frontend work.

## 22. Final DoD

- [x] Existing DID architecture inspected (all consumers enumerated)
- [x] Clean baseline established (730/0/13/1, 2 cycles, V40)
- [x] Platform → reseller assignment
- [x] Platform → tenant assignment
- [x] Reseller → tenant assignment
- [x] Explicit revoke (tenant holds and reseller-pool holds)
- [x] Correct provenance preserved & restored (V41 `allocation_source`)
- [x] Only AVAILABLE DIDs assignable (DB predicate)
- [x] No silent reassignment (409; proven by PG-A6)
- [x] Deleted DIDs unassignable (PG-A4)
- [x] Authorization enforced (unit tests, all three scopes)
- [x] Reseller hierarchy enforced (unit + scoped UPDATEs)
- [x] Tenant isolation enforced (404-cloaking; PG-B3)
- [x] PostgreSQL atomic allocation (conditional UPDATEs)
- [x] Concurrent assignment test passes (20→1, PG-C1)
- [x] Revoke/reassign race verified (PG-C2)
- [x] Campaign readiness verified (PG-B1)
- [x] Voice eligibility verified (PG-B2)
- [x] Existing DID behavior compatible (full regression green)
- [x] Migration only where justified (V41, minimal, backfilled)
- [x] Unit tests pass (17/17)
- [x] PostgreSQL integration tests pass (12/12)
- [x] Full regression executed (759/0/13/1)
- [x] Architecture tests executed (2 pre-existing cycles, unchanged)
- [x] No new Modulith cycle
- [x] No VB-5C scope leakage
- [x] Implementation report created
- [x] Final verdict documented

## 23. Final Verdict

**READY FOR VB-5D**

(Stopping here per the phase contract — no TTS, no campaign execution until explicit review.)
