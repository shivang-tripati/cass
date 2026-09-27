# VB-6B.1 — Contact Identity Refactor + V46 Migration — Implementation Report

Status: **COMPLETE** — full suite green, migration integrated, architecture clean.
Date: 2026-09-26. Scope brief: `docs/VB-6B.0-CONTACT-GROUP-MODEL-AUDIT.md` (approved target model), companion: `docs/VB-6B-CONTACT-AUDIENCE-AUDIT.md`.

---

## 1. Baseline (pre-refactor)

- `contacts` rows were **group-owned**: `contact_group_id UUID NOT NULL REFERENCES contact_groups(id)` (V17), with live identity enforced by the partial unique index `uq_contacts_group_phone_live ON contacts (contact_group_id, phone_number) WHERE deleted_at IS NULL` (V19) and lookup index `idx_contacts_group_deleted (contact_group_id, deleted_at)`.
- Consequence: the same phone number living in two groups of one tenant was **two physical Contact rows** with two ids — no tenant-level callable identity existed.
- Dial-time lookup resolved the attempt's `contact_id` without any tenant scoping or live-check contract at the dial boundary.
- `call_attempts.contact_id` pointed at whichever physical row had created the attempt; deleting/merging a contact orphaned attempt semantics.
- Intermediate in-flight state from the earlier VB-6B.1 phase: V45 (`idx_contacts_tenant_phone_live`, a **non-unique** tenant+phone query index) existed but modeled identity as a "query convention", not a constraint.

## 2. Objective

Flip Contact to a **tenant-level callable identity** `UNIQUE (tenant_id, phone_number) WHERE deleted_at IS NULL`, move the (group, contact) relationship into a physical **ContactGroupMember** table `UNIQUE (contact_group_id, contact_id)` with composite tenant FKs, backfill memberships and remap call attempts via a single deterministic V46 migration, and make dial-time lookup tenant-scoped with fail-closed `CONTACT_INVALID` per attempt — without touching Campaign `contactGroupId` targeting or VB-6A snapshot semantics.

## 3. Final Contact model

- **Contact** = tenant-level callable identity. DB-enforced by `uq_contacts_tenant_phone_live ON contacts (tenant_id, phone_number) WHERE deleted_at IS NULL` (V46). Soft-deleted numbers are re-creatable (live-partial, existing semantics preserved). `contacts.contact_group_id` is **dropped**.
- **ContactGroup** = tenant-owned audience. Schema unchanged (V17), including `uq_contact_groups_id_tenant (id, tenant_id)` used by the composite FKs below.
- **ContactGroupMember** = the physical (group, contact) relationship row. `UNIQUE (contact_group_id, contact_id)` (`uq_cgm_group_contact`), **no soft delete** — no `deleted_at` column, entity does not extend `AuditableEntity`; `created_at` NOT NULL with `@CreationTimestamp` + `nullable=false` (Hibernate does not apply DB defaults).
- Tenant invariant is **database-enforced** by composite foreign keys, no triggers:
  - `fk_cgm_contact FOREIGN KEY (contact_id, tenant_id) → contacts (id, tenant_id)`
  - `fk_cgm_group FOREIGN KEY (contact_group_id, tenant_id) → contact_groups (id, tenant_id)`
  - backed by `uq_contacts_id_tenant` / `uq_contact_groups_id_tenant` on the parents (V45-era unique constraints on contacts retained).

## 4. Changes

Production:
- `contact/ContactEntity` — no group column; tenant+phone identity.
- `contact/ContactRepository` — `findByIdAndTenantIdAndDeletedAtIsNull`, `existsByTenantIdAndPhoneNumberAndDeletedAtIsNull`, `findIdsByTenantIdAndPhoneNumberAndDeletedAtIsNull`; group-scoped finders removed.
- `contact/ContactIdentityService` — canonicalizes via `ContactValidation.canonicalizePhoneNumber` (single persistence canonicalizer), find-or-create identity, `duplicateContactConflict(tenantId, canonicalPhone)` typed 409 factory.
- `contact/ContactGroupMemberEntity` + `ContactGroupMemberRepository` — standalone entity, membership upsert/query API (`findByContactGroupId`, `findByContactGroupIdAndContactId`, `existsByContactGroupIdAndContactId`, `existsByContactGroupId`).
- `contact/ContactGroupService` — `createContact` is find-or-create on identity (race path: DB unique violation at flush → typed `ConflictException`); update/delete/import/export/list tenant-scoped; import builds `existingIdentityIds` LinkedHashMap with `computeIfAbsent` membership upserts and passes `new ArrayList<>(map.values())` to `saveAll` (Mockito List-cast gotcha).
- `contact/ContactMapper`, `contact/dto/ContactResponse` — no `contactGroupId` on contact payloads.
- `contact/ContactSpecifications.liveContactInIds` — membership-based audience selection support.
- `campaign/CampaignExecutionOrchestrator` — audience selection via `memberRepository.findByContactGroupId(config.contactGroupId())`; `isContactStillValid` is **identity-scoped** (tenant+live, no group predicate) for retry continuation.
- `campaign/OutboundDialService` — tenant-scoped contact lookup at dial time (§11).
- `campaign/CallAttemptService`, `campaign/CallFailureCode` — `CONTACT_INVALID (RetryClass.PERMANENT)`.
- `telephony/CallEligibilityService` — whitelist/group eligibility resolves group numbers through memberships.

Migration: `db/migration/V46__contact_identity_and_group_membership.sql` (§5). V45 retained (§18).

## 5. V46 migration (`V46__contact_identity_and_group_membership.sql`)

Single-transaction Flyway script (Flyway requirement is load-bearing: the survivor temp table uses `ON COMMIT DROP`):

1. Create `contact_group_members` (physical rows, unique `(contact_group_id, contact_id)`, composite tenant FKs `fk_cgm_contact`/`fk_cgm_group`, indexes `idx_cgm_contact (contact_id)`, `idx_cgm_tenant (tenant_id)`).
2. Build temp table `vb6b1_contact_survivors (tenant_id, phone_number, survivor_id)` — survivor = min(created_at), tie-break min(id), over **live** rows only.
3. Backfill memberships from **every** source contact row (survivors and non-survivors) so no group loses its audience: `INSERT ... ON CONFLICT (contact_group_id, contact_id) DO NOTHING`.
4. Remap `call_attempts.contact_id` from non-survivor rows to the survivor of the same (tenant, phone).
5. Soft-delete non-survivor live rows: `deleted_at = now(), deleted_by = 'vb6b1-migration'`.
6. Drop the group-owned identity model: `contacts_contact_group_id_fkey`, `contacts.contact_group_id`, `uq_contacts_group_phone_live`, `idx_contacts_group_deleted`, plus the superseded `idx_contacts_tenant_phone_live` (V45's non-unique index — name must not linger).
7. Create `uq_contacts_tenant_phone_live ON contacts (tenant_id, phone_number) WHERE deleted_at IS NULL`.

## 6. Duplicate survivor strategy

- Group key: `(tenant_id, phone_number)` over live rows.
- Survivor: **min(created_at)**; tie-break **min(id)** — deterministic and reproducible.
- Non-survivors: soft-deleted (never physically deleted), `deleted_by='vb6b1-migration'`, so the original row remains auditable and the number's history is traceable.
- Survivor **fields win** (dev-stage data); memberships are preserved for all source groups (step 3), so audiences do not shrink.

## 7. CallAttempt remapping

`call_attempts.contact_id` is UPDATEd to the survivor id where the attempt pointed at a non-survivor of the same `(tenant_id, phone_number)`. Attempts therefore keep dialing a live identity after the merge. Verified by migration test M-1 (attempt created against the older duplicate dials the survivor afterwards).

## 8. Tenant composite-FK enforcement

- `contact_group_members` carries `tenant_id` and both composite FKs; a membership whose contact and group are in different tenants cannot physically exist.
- Migration test asserts `pg_constraint.conname IN ('fk_cgm_contact','fk_cgm_group') = 2`.
- Runtime test PG-C6 proves a cross-tenant membership insert is rejected by the DB (not by service code).

## 9. Contact identity behavior

- All contact phone inputs canonicalized once (`ContactValidation.canonicalizePhoneNumber`); voice layer keeps `PhoneNumberNormalizer` for display/dial strings only.
- Same-tenant same-phone create → find-or-create returns the existing live identity; a genuine second insert collides on `uq_contacts_tenant_phone_live` (DIVE) → typed `ConflictException` 409. Same-transaction re-resolution after a flush-time DIVE is impossible (TX aborted), so the race path is intentionally simple: DIVE → typed conflict.
- Cross-tenant same phone → two independent identities (PG-C2).
- Soft-deleted phone → re-creatable as a new live identity (PG-C3).

## 10. Contact API changes

- `ContactResponse` no longer exposes `contactGroupId`; contact create/update DTOs have no group field — group association is expressed through membership endpoints on `ContactGroupController`.
- Contact list/export remains tenant-scoped; group-scoped listing/export resolves via `contact_group_members`.
- Out of scope (unchanged here): dedicated member service/REST API — **VB-6B.2**.

## 11. OutboundDialService changes

`buildDestinationNumber(attempt)` resolves the attempt's contact via `contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(attempt.getContactId(), attempt.getTenantId())` — tenant-scoped and live, **deliberately no group predicate**: membership determined the audience at attempt-creation time and is not a contact-identity constraint. `ResourceNotFoundException` from a missing/deleted/foreign contact is caught at the attempt boundary and converted to a per-attempt failure (§12).

## 12. CONTACT_INVALID behavior

- Failure code `CONTACT_INVALID`, `RetryClass.PERMANENT`.
- Per-attempt fail-closed: one invalid contact never aborts the due batch, never reaches the provider, and never falls back to live campaign configuration (contrasted with `EXECUTION_CONFIG_MISSING`, which is a batch-level integrity failure).
- Verified by `DialBatchContinuationPostgresIntegrationTest` (§14): deleted contact → only that attempt fails while the rest of the batch dials; missing contact; foreign-tenant contact; independence of a second execution.

## 13. Old VB-6B.1 work retained / replaced / removed

- **Retained**: V45 non-unique tenant+phone index migration (superseded but harmless, forward-only history intact); canonicalization strategy; import/export tenant scoping; Campaign `contactGroupId` + snapshot semantics (VB-6A untouched).
- **Replaced**: group-owned contact identity (`uq_contacts_group_phone_live` model) → tenant identity + membership rows; group-scoped contact finders → identity finders; orchestrator group-predicate validity check → identity-scoped check.
- **Removed**: `contacts.contact_group_id` (column, FK, indexes); `findByIdAndTenantIdAndContactGroupId`; group-scoped dedup logic in create/import paths; the earlier draft of a replacement V45 was consolidated into V46 (net: one new migration file).

## 14. Tests

Full bar met — `./mvnw clean test`: **910 tests, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS** (MAVEN_OPTS `-Xmx640m -XX:MaxMetaspaceSize=320m`; the Windows forked-VM native OOM at `-Xmx768m` did not recur).

New/rewritten suites:
- `ContactIdentityServiceTest` — 12 (find-or-create, canonicalization, duplicate → typed conflict incl. `databaseRaceBecomesTypedConflict`, tenant scoping).
- `ContactOwnershipServiceTest` — 5; `ContactImportServiceTest` — 7 (identity dedup, membership upserts via `existingIdentityIds` map).
- `ContactIdentityPostgresIntegrationTest` — 6 (§15, §8).
- `ContactIdentityMigrationPostgresIntegrationTest` — 2 (§16).
- `DialBatchContinuationPostgresIntegrationTest` — rewritten (was the old broken harness) with the standard PG pattern; 4 tests IT-B1..B4 (§12). Manual `OutboundDialService` wiring; destination-agnostic stubs (`evaluate(any(), anyString())`, `resolveRoute(any(), any(), anyString(), eq(didRowId), anyString(), any())`, `voiceCapacity.reserve(eq(gatewayId), any(UUID))`, dialer → accepted).

Harness notes baked into the suites: seed contacts each get their own group (pre-V46 `uq_contacts_group_phone_live`), randomized DID e164 (`+9198` + %08d of UUID hash), seeded `sip_gateways` row (unique `name`/`free_switch_gateway_name`), and `call_attempts.execution_id` requires seeded execution + snapshot rows. Migration test targets Flyway "44", seeds duplicates via raw SQL, applies the V46 resource text in ONE manual transaction (`ScriptUtils.executeSqlScript` with `setAutoCommit(false)`/`commit()`, `EncodedResource(ByteArrayResource(...), UTF_8)`, static `raw()` helper), then inserts the V46 `flyway_schema_history` row manually.

## 15. PG concurrency result

`ContactIdentityPostgresIntegrationTest` (postgres:16-alpine container, Flyway to head):
- PG-C1 live tenant uniqueness: second create → DIVE → typed conflict.
- PG-C2 cross-tenant same phone → 2 identities.
- PG-C3 soft-deleted phone re-creatable.
- PG-C4 **20 threads** racing `createContact` for the same (tenant, phone): exactly **1 identity + 1 membership**; per-thread outcome classified as typed conflict / root-cause DIVE / `UnexpectedRollbackException` → typedConflicts; final assertion `resolvedToWinner + typedConflicts <= threads` — rollback propagation blurs per-thread outcome by design, so the assertion is deliberately not tightened.
- PG-C5 membership uniqueness (second membership row for same pair rejected).
- PG-C6 cross-tenant membership rejected by composite FK.

## 16. Migration integration result

`ContactIdentityMigrationPostgresIntegrationTest` — both tests green:
- M-1: seed duplicate world at V44 (tenant T1 contacts C1 older + C2 newer same phone in G1/G2, T2 same phone separate; attempts against both) → after V46: survivor = C1 (min created_at), memberships for G1+G2 = 2, attempt remapped to C1, T2 identity untouched.
- M-2 (same TX execution path): `contacts.contact_group_id` dropped (`information_schema.columns` = 0), `uq_contacts_tenant_phone_live` present (`pg_indexes`), `uq_contacts_group_phone_live` absent, composite FKs `fk_cgm_contact`/`fk_cgm_group` = 2.

## 17. ArchitectureTest result

`ArchitectureTest`: **1 run, 0 failures, 0 errors, 0 skipped — 0 cycles**. No new package dependencies outside the established contact/campaign/telephony boundaries.

## 18. Flyway head

- Head: **V46 — `V46__contact_identity_and_group_membership.sql`**.
- Runtime: `Successfully applied 45 migrations to schema "public", now at version v46`.
- `V45__contact_tenant_phone_identity.sql` is retained: it created the non-unique `idx_contacts_tenant_phone_live`; V46 explicitly `DROP INDEX IF EXISTS idx_contacts_tenant_phone_live` so the old-direction name cannot linger, then creates the unique `uq_contacts_tenant_phone_live`.

## 19. Files changed

Production:
- `backend/src/main/resources/db/migration/V46__contact_identity_and_group_membership.sql` (new)
- `backend/src/main/java/com/shivang/obd/contact/ContactEntity.java`, `ContactRepository.java`, `ContactIdentityService.java`, `ContactMapper.java`, `ContactSpecifications.java`, `ContactGroupService.java`
- `backend/src/main/java/com/shivang/obd/contact/ContactGroupMemberEntity.java`, `ContactGroupMemberRepository.java` (new)
- `backend/src/main/java/com/shivang/obd/contact/dto/ContactResponse.java`
- `backend/src/main/java/com/shivang/obd/campaign/CampaignExecutionOrchestrator.java`, `OutboundDialService.java`, `CallAttemptService.java`, `CallFailureCode.java`
- `backend/src/main/java/com/shivang/obd/telephony/CallEligibilityService.java`

Tests:
- `backend/src/test/java/com/shivang/obd/contact/ContactIdentityServiceTest.java`, `ContactOwnershipServiceTest.java`, `ContactImportServiceTest.java`, `ContactIdentityPostgresIntegrationTest.java`, `ContactIdentityMigrationPostgresIntegrationTest.java`
- `backend/src/test/java/com/shivang/obd/campaign/DialBatchContinuationPostgresIntegrationTest.java` (rewritten)
- Teardown-order adjustments in snapshot/governance/resource-validation PG tests (members deleted before parents)

Docs:
- `docs/VB-6B.1-IMPLEMENTATION-REPORT.md` (this file)

## 20. Deferred VB-6B.2 work

- Member service + REST API (`/contact-groups/{id}/members` CRUD, batch add/remove) on `ContactGroupMemberRepository`.
- Membership-aware pagination/filtering for group rosters; member-count facets on group responses.
- Bulk membership import endpoint beyond the existing contact import path.

## 21. Known limitations

- Race-path conflict surfacing depends on caller classification: a thread that loses the DIVE race may observe `UnexpectedRollbackException` instead of the typed `ConflictException`; typed-conflict guarantees hold at the API boundary, not per-thread (PG-C4 assertion intentionally loose).
- Membership rows have no audit columns beyond `created_at`/`created_by` (no soft delete by design); removing a membership is a physical delete.
- V46 survivor strategy trusts `created_at` ordering of dev-stage data; production-grade merge (conflict resolution policy, notification, suppression-list interaction) is out of scope.
- Campaign `contactGroupId` remains a denormalized pointer validated only as "exists + same tenant" (`CampaignService.validateContactGroupReference`); referential enforcement stays application-level as before.
- Daily limits / VB-6C consumers unchanged and still to come.

## 22. Final status

- `./mvnw clean test`: **910 tests, 0 failures, 0 errors, 1 skipped — BUILD SUCCESS**.
- `ArchitectureTest`: 1/0/0/0, 0 cycles.
- Flyway head: V46; 45 migrations apply cleanly to a fresh postgres:16-alpine database.
- Regression grep (`contactGroupId|contact_group_id|uq_contacts_group_phone_live|idx_contacts_group_deleted|findByIdAndTenantIdAndContactGroupId|findIdsByTenantIdAndPhoneNumber`): remaining refs are all **Campaign `contactGroupId`** (entity/snapshot/DTOs/mapper/service validation/eligibility context — KEEP), **ContactGroupMember** own columns/queries (KEEP), **V14/V17/V19/V44 historical migrations** (KEEP — forward-only history), **V46 SQL** (expected), and **migration-seeding test SQL** (pre-V46 schema by design). Contact-ownership references to group identity are gone from production code.
- VB-6B.1 is complete. Stopping here per brief — no VB-6B.2 work started.
