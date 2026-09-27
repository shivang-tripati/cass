# VB-6B.2 — ContactGroupMember Service + REST API — Audit

**VB-6B.2 AUDIT COMPLETE — IMPLEMENTATION NOT STARTED**

Date: 2026-09-26. Audit-only phase: no production code, tests, migrations, or architecture were modified.
Approved lineage: VB-6A (snapshots) → VB-6B.0 (model audit) → VB-6B.1 (identity refactor + V46) → **VB-6B.2 (this audit)**.

---

## 1. Current baseline (confirmed, no re-run needed)

- `./mvnw clean test` (VB-6B.1 final gate, same working tree — zero code changes since): **910 tests, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS**.
- `ArchitectureTest` (Spring Modulith `ApplicationModules.verify()`): **1 run / 0 failures / 0 errors / 0 skipped** — 0 cycles.
- Flyway head: **V46** — `V46__contact_identity_and_group_membership.sql`; 45 migrations apply cleanly to fresh postgres:16-alpine.
- Environment note carried forward: `MAVEN_OPTS="-Xmx640m -XX:MaxMetaspaceSize=320m"` required on this Windows host (forked-VM native OOM at 768m).

## 2. Existing membership model

`ContactGroupMemberEntity` (table `contact_group_members`, V46) is a **pure physical relationship row**:

| Field | Column | Notes |
|---|---|---|
| `id` (UUID) | `id` | PK, `gen_random_uuid()` default |
| `tenantId` | `tenant_id` NOT NULL | denormalized; DB-enforced equal to both parents |
| `contactGroupId` | `contact_group_id` NOT NULL | FK `(contact_group_id, tenant_id) → contact_groups (id, tenant_id)` (`fk_cgm_group`) |
| `contactId` | `contact_id` NOT NULL | FK `(contact_id, tenant_id) → contacts (id, tenant_id)` (`fk_cgm_contact`) |
| `createdAt` | `created_at` NOT NULL | `@CreationTimestamp` (entity must self-supply; Hibernate ignores DB defaults) |
| `createdBy` | `created_by` | **currently never stamped** — javadoc explicitly reserves this for VB-6B.2 |
| — | `updated_at`, `updated_by` | **columns exist in V46 but are deliberately NOT mapped** in the entity (no update semantics for an immutable relationship) |

- **No soft delete** (no `deleted_at`); entity deliberately does not extend `AuditableEntity` (would emit `deleted_at` SQL the table lacks).
- Uniqueness: `uq_cgm_group_contact UNIQUE (contact_group_id, contact_id)` — the concurrency authority.
- Tenant invariant is DB-enforced by the composite FKs; service code never needs to re-check it (and the backstop is proven by PG-C6).

## 3. Existing repository methods (`ContactGroupMemberRepository`)

- `existsByContactGroupIdAndContactId(groupId, contactId)` — idempotency pre-check for add.
- `findByContactGroupId(groupId)` — live-audience unit (used by orchestrator, eligibility, roster, export, delete cascade).
- `findByContactId(contactId)` — reverse lookup (contact delete cascade).
- `findByContactGroupIdAndContactId(groupId, contactId)` — single row.
- `existsByContactGroupId(groupId)` — delete-guard support.

**Gaps for VB-6B.2** (repository additions, no schema change): `countByContactGroupId`, `deleteByContactGroupIdAndContactId` (derived delete), a paged roster query joining the live contact payload, and a grouped-count query for list pages. No `@Modifying` bulk needs.

## 4. Existing service behavior (`ContactGroupService`)

- **Membership creation path exists**: private `upsertMembership(tenantId, groupId, contactId)` — exists-check, then `saveAndFlush`, with `DataIntegrityViolationException` caught and treated as the idempotent outcome ("the unique constraint wins"). Called by `createContact` and `importContacts`.
- **Membership deletion paths exist**: `deleteGroup` → `deleteAll(findByContactGroupId)`; `deleteContact` → `deleteAll(findByContactId)` (physical deletes, matching the no-soft-delete model).
- `createContact` = find-or-create identity (`ContactIdentityService` canonicalization + DIVE→typed-409 race contract) + `upsertMembership`.
- `importContacts` = batched find-or-create (`existingPhones` set, `existingIdentityIds` map, `new ArrayList<>(map.values())` for `saveAll`) + per-identity `upsertMembership`.
- Roster read: `listContacts` = all member ids in memory → `ContactSpecifications.liveContactInIds` + `search` → paged contact query. Works at MVP scale; the member roster should page memberships directly instead (one JPQL query) rather than materializing every membership id per page request.
- `findVisibleContact` is membership-scoped: contact must be a live member of the authorized group (404-cloaked).

## 5. Existing controller/API behavior (`ContactGroupController`, `/api/v1/contact-groups`)

- Sub-resource convention: group-scoped paths addressed by **entity id of the child** — `/{id}/contacts/{contactId}` (contact id, not a wrapper-resource id). Any member API should follow this: `/{id}/members/{contactId}`.
- Envelope: `ApiResponse<T>` via `ResponseFactory` (`ok`/`created`/`page`); create → `201` + body; delete → `204` no body; paged → `ApiResponse<List<T>>` with `pagination`.
- Multipart bulk import is the **only** bulk endpoint in the codebase (5 MB / 5000 rows, per-row validation, `ContactImportError{rowNumber, field, code, message}` capped at 100 reported). No JSON batch-array endpoints exist anywhere yet.
- OpenAPI annotations (`@Operation`, per-response `@ApiResponse`) on every endpoint; no `@PreAuthorize`/controller-level security — authorization is entirely service-level.
- **Frontend contract (must not break)**: `frontend/src/lib/api/contact-groups.ts` and `contacts.ts` consume groups CRUD + `/{id}/contacts` CRUD/import/export. No member endpoints are referenced yet — the member API is purely additive.

## 6. Existing authorization behavior

- **Service-level only.** `ContactGroupService` gates every operation through `authorizedGroup(id, capability)` = `findVisibleGroup` (scoped lookup) + `authorizationService.requireCapability(userId, CAP, AccessCheck.forTenant(entity.getTenantId()))`.
- Capabilities: `CONTACT_VIEW` (reads), `CONTACT_MANAGE` (mutations). **No new capability is warranted** — memberships are part of the same aggregate surface (rule 19: do not duplicate authorization logic).
- `requireCapability` is fail-closed → `ForbiddenException` (403 ProblemDetail, generic non-leaking message).

## 7. Existing pagination conventions

- Request: `page` (default 0), `size` (default 20, `MAX_PAGE_SIZE = 100`), `sort` as `String[]` `"field,dir"`.
- Whitelisted sortable fields per resource (`GROUP_SORTABLE_FIELDS`, `CONTACT_SORTABLE_FIELDS`) + fixed default sort; invalid fields silently fall back to the default (`buildPageable`).
- Response: `ResponseFactory.page(items, PaginationMetadata.of(page, size, totalElements))`.

## 8. Existing batch-operation conventions

- Only one precedent: multipart import with **per-row outcomes** (`created` / `duplicate` / `error` counts + capped per-item error list) — never all-or-nothing 400 on first bad item.
- No JSON batch endpoints exist. A member batch add/remove should mirror the import philosophy: per-item result entries (`contactId`, `status`), not fail-fast.

## 9. Existing error semantics

- `GlobalExceptionHandler` → RFC 7807 `ProblemDetail` for `BusinessException` (carries `ApiErrorCode`), bean validation (400 with field errors), etc.
- `CommonErrorCode`: `VALIDATION_ERROR` 400, `RESOURCE_NOT_FOUND` 404, `CONFLICT` 409, `BUSINESS_RULE_VIOLATION` 422, `FORBIDDEN` 403.
- Typed helpers in use: `ResourceNotFoundException`, `ConflictException` (`ContactIdentityService.duplicateContactConflict` precedent), `BusinessException(VALIDATION_ERROR, …)`.
- **404-cloaking**: foreign/out-of-boundary and nonexistent resources are indistinguishable (both 404) — documented on every controller endpoint.

## 10. Existing tenant/reseller semantics

- `Scope(tenantId, resellerId)` from `OrganizationContextHolder`.
- TENANT → query-level scoping (`findByIdAndTenantIdAndDeletedAtIsNull`).
- RESELLER → unscoped live lookup, then `hierarchyTenantIds(resellerId)` (`tenantRepository.findAllByResellerIdAndStatus(ACTIVE)`) membership check; empty hierarchy → empty page.
- PLATFORM → unbounded (`AccessCheck.platformWide()` + capability only).
- This is exactly `findVisibleGroup` + the `listGroups` boundary switch; the member service must reuse it verbatim.

## 11. Existing tests (membership-relevant inventory)

| Suite | Coverage today |
|---|---|
| `ContactIdentityServiceTest` (12, unit) | canonicalization, find-or-create, DIVE→typed 409, tenant-scoped dedup, soft-deleted phone reuse (+memberships removed) |
| `ContactOwnershipServiceTest` (5, unit) | group-tenant inheritance, foreign group 404, reseller hierarchy visibility, group delete removes memberships, membership-scoped contact visibility |
| `ContactImportServiceTest` (7, unit) | per-row validation, canonical collapse, duplicate reporting, existing-identity membership upsert |
| `ContactIdentityPostgresIntegrationTest` (6, PG) | live uniqueness DIVE, cross-tenant identities, soft-delete reuse, **20-thread create race → 1 identity + 1 membership**, membership uniqueness, composite-FK cross-tenant rejection |
| `ContactIdentityMigrationPostgresIntegrationTest` (2, PG) | V46 merge semantics + schema shape |
| Campaign suites (snapshot/governance/resource-validation/dial-batch PG) | seed memberships (teardown deletes members first); audience selection via `findByContactGroupId` |
| **Gap** | **No MockMvc/API-slice tests exist for `ContactGroupController`** (contrast: `QueueDirectoryApiSliceTest`, `AgentDirectoryApiSliceTest` standalone-MockMvc pattern; full 401/403 wiring lives in `SecuritySliceTest`) |

## 12. Existing architecture boundaries

- Spring Modulith: `ApplicationModules.of(ObdApplication.class).verify()` — 0 cycles enforced.
- `contact` module exports entities/repositories consumed by `campaign` (`CampaignExecutionOrchestrator`, `CampaignReadinessService`) and `telephony` (`CallEligibilityService`). Membership consumers today:
  - `CampaignExecutionOrchestrator` — audience at execution start from snapshot `contactGroupId` (VB-6A semantics, untouchable).
  - `CallEligibilityService` — whitelist check resolves group numbers via memberships.
  - `ContactGroupService` — roster/export/cascades (internal).
- A new `ContactGroupMemberService` inside `com.shivang.obd.contact` keeps the dependency graph acyclic (see §14).

## 13. Required changes (audit conclusion)

**V46 schema is sufficient — no migration (rule 18).** Uniqueness, composite tenant FKs, and every needed index already exist: roster/count queries are `(contact_group_id)`-led → backed by `uq_cgm_group_contact`; reverse lookups → `idx_cgm_contact`; tenant queries → `idx_cgm_tenant`.

1. `ContactGroupMemberRepository`: add `countByContactGroupId`, `deleteByContactGroupIdAndContactId`, a paged JPQL roster (`membership JOIN live contact`, optional search), and a grouped-count for list pages.
2. DTOs: `ContactGroupMemberResponse` (memberId, groupId, contactId, tenantId, contact payload, createdAt, createdBy), `AddMemberRequest{contactId}`, batch result records.
3. `ContactGroupMemberService` — the canonical membership service: add (idempotent), remove (idempotent), batch add/remove, paged roster, member detail; stamps `createdBy` from `CurrentUserProvider` (closing the reserved audit surface).
4. Extract the group visibility/authorization gate (`findVisibleGroup` + `authorizedGroup`) into a package-private shared component so **both** services use one code path (real shared logic — not speculative abstraction; see A).
5. `ContactGroupService` delegates its internal `upsertMembership` / cascade deletes to `ContactGroupMemberService` (one canonical mutation path).
6. `ContactGroupController`: add `GET/POST/DELETE /{id}/members` endpoints + batch; surface `memberCount` on `ContactGroupResponse` (batch-aggregated on list, direct on get-by-id).
7. Tests: unit, API slice (first-ever slice tests for this controller), PG integration.

## 14. Reuse vs modify vs replace vs new

| Item | Classification |
|---|---|
| `contact_group_members` schema + indexes + constraints (V46) | **Reuse as-is** (no migration) |
| `ContactGroupMemberEntity` | **Reuse as-is** (add `createdBy` stamping only — no structural change) |
| `ContactGroupMemberRepository` | **Modify** (add derived + JPQL queries) |
| `ContactIdentityService` / identity find-or-create | **Reuse verbatim** (rule 20 — member API never creates identities) |
| Group visibility + capability gate | **Extract** from `ContactGroupService` into shared package-private component; behavior identical |
| `upsertMembership` (private in `ContactGroupService`) | **Move** into `ContactGroupMemberService` (canonical path); `ContactGroupService` delegates |
| Group/contact delete cascades | **Modify** to delegate to member service |
| `AuthorizationService` / `OrganizationContextHolder` / capabilities | **Reuse** (`CONTACT_VIEW` / `CONTACT_MANAGE`) |
| Pagination, `ApiResponse`/`ResponseFactory`, `PaginationMetadata`, error types | **Reuse** |
| Roster read pattern (materialize all member ids → `IN` query) | **Replace for the member endpoint** with a paged membership-led JPQL query; existing `listContacts` stays as-is (still satisfies its contract) |
| `ContactGroupMemberService`, member DTOs, member endpoints | **New** |
| `memberCount` on `ContactGroupResponse` | **Modify** `ContactGroupMapper`/response record (additive field) |
| Migration | **None** |

Dependency graph after changes (acyclic, Modulith-safe): `ContactGroupService → ContactGroupMemberService → ContactGroupAccess`; `ContactGroupService → ContactGroupAccess`; controller → both services.

## 15. Schema/index implications

- **None new.** `uq_cgm_group_contact` provides the B-tree for all `(contact_group_id)`-led queries (roster, count, exists, delete-by-pair). `idx_cgm_contact` covers reverse cascades. `idx_cgm_tenant` covers tenant-scope queries. Composite FKs keep tenant isolation DB-authoritative.
- `updated_at`/`updated_by` DB columns stay unmapped and NULL — memberships are immutable relationships (no update semantics in this phase; see O).
- `created_by` backfill for existing rows: **not** done (no migration; rows remain NULL, new rows stamped going forward).

## 16. Concurrency/idempotency risks

- **Add race** (two concurrent adds of the same pair): resolved by `uq_cgm_group_contact`. Pre-check + `saveAndFlush` + catch `DataIntegrityViolationException` → idempotent EXISTS outcome (exact existing `upsertMembership` pattern, PG-C4/PG-C5 proven). No locks, no new infrastructure (rule 6).
- **Remove race** (remove while add commits): derived delete is a no-op if the row vanished — idempotent.
- **Contact soft-delete concurrent with member add**: add validates the contact live within the group's tenant; a raced delete would violate no constraint (membership of a soft-deleted contact is not FK-forbidden). Risk accepted: roster query joins `deletedAt IS NULL` so soft-deleted members disappear from rosters; same exposure exists today for the campaign audience path. Mitigation is ordering (delete contact removes memberships in the same TX as the contact soft-delete), unchanged from VB-6B.1.
- **Batch + race**: per-item DIVE handling must not abort the batch — each item's constraint violation is caught and mapped to EXISTS (contrast with `importContacts`, which deliberately fails whole-import on identity races; memberships have no such identity semantics).

## 17. API compatibility risks

- **Additive only.** No existing endpoint, DTO field, or status code changes. Frontend (`contact-groups.ts`, `contacts.ts`, campaign dialogs) untouched.
- `ContactGroupResponse` gains `memberCount` — additive JSON field; `ApiResponse.NON_NULL` envelope unaffected.
- The existing `/contacts` sub-resource remains the contact-identity CRUD surface (create also upserts membership — that behavior is preserved, not duplicated, via delegation).
- No compatibility shims for the old group-owned Contact model (rule 16) — none exist to shim.

## 18. Exact implementation sequence

1. **Repository**: add `countByContactGroupId`, `deleteByContactGroupIdAndContactId`, paged roster JPQL (`m JOIN c ON c.id = m.contactId AND c.deletedAt IS NULL WHERE m.contactGroupId = :groupId` + optional search on `c.phoneNumber/firstName/lastName`), grouped-count `findCountsByGroupIds`.
2. **Shared gate**: extract package-private `ContactGroupAccess` (visibility + capability) from `ContactGroupService`; rewire `ContactGroupService` to use it (behavior-identical refactor).
3. **DTOs**: `ContactGroupMemberResponse`, `AddMemberRequest`, `BatchMemberResult`, `BatchMemberResponse`.
4. **`ContactGroupMemberService`**: `addMember` (validate contact live in group's tenant via `ContactRepository.findByIdAndTenantIdAndDeletedAtIsNull` → idempotent insert with race catch → 201/200 + `createdBy` stamp), `removeMember` (idempotent 204), `addMembers`/`removeMembers` (per-item outcomes), `listMembers` (paged, whitelist sort), `getMember`, memberCount support.
5. **Delegate**: `ContactGroupService.upsertMembership` → `memberService.addMemberInternal(...)`; cascades → `memberService.removeAllForGroup/ForContact`; delete now-dead private code.
6. **Controller**: member endpoints under `/api/v1/contact-groups/{id}/members` + `memberCount` in `ContactGroupResponse` (mapper + batch aggregate in `listGroups`).
7. **Tests** (per §20), then full `./mvnw clean test` + `ArchitectureTest`.

## 19. Explicit out-of-scope items

- Bulk **file** membership import (attaching existing identities from csv/xlsx/json) — later phase.
- Cross-group copy/move of memberships; membership "replace audience" operations.
- Membership update semantics (none — rows immutable; `updated_*` columns stay unmapped).
- Membership history/audit-log architecture (rule 5: no existing convention requires it).
- Soft delete of memberships (rule 4: no product requirement).
- Per-tenant capability changes, new roles, or a `MEMBER_*` capability (reuse `CONTACT_*`).
- Daily limits / VB-6C consumers; Campaign `contactGroupId` semantics; VB-6A snapshot semantics; campaign scheduling/execution behavior.
- Redis/Kafka/locks/new infrastructure (rule 6).
- `created_by` backfill migration for existing membership rows.

## 20. Recommended test matrix

**Unit (Mockito, following `ContactOwnershipServiceTest` style):**
1. add → new membership 201, `createdBy` stamped, correct tenant/ids.
2. add → already-member → 200 EXISTS, no second save.
3. add → missing contact (tenant-scoped lookup) → 404; foreign-tenant contact → indistinguishable 404.
4. add → group unauthorized/missing → 404 (gate).
5. add race → `DataIntegrityViolationException` → idempotent EXISTS (not 500).
6. remove → present → 204 + delete invoked; absent → 204 (idempotent).
7. batch → mixed CREATED/EXISTS/errors; error items don't abort the batch.
8. roster → pagination metadata, sort whitelist fallback, search passthrough.

**API slice (standalone MockMvc, `QueueDirectoryApiSliceTest` pattern — first slice tests for this controller):**
9. POST member envelope/201; POST invalid body → 400 ProblemDetail; GET roster envelope + `pagination`; DELETE → 204; unknown path → 404 shape.

**PG integration (standard harness: `@DataJpaTest(showSql=false)`, `replace=NONE`, `JpaAuditConfig`, `NOT_SUPPORTED`, static postgres:16-alpine, container-start guard):**
10. DB uniqueness: concurrent duplicate adds (executor race) → exactly 1 row, idempotent outcome (mirror PG-C5).
11. Composite FK: member add for foreign-tenant contact id is impossible/404-cloaked; direct FK violation still rejected.
12. Roster pagination + memberCount correctness against seeded rows; soft-deleted contact excluded from roster.
13. `deleteByContactGroupIdAndContactId` idempotency; group-delete cascade still removes memberships (regression of existing behavior after delegation).

**Regression:** all 910 existing tests stay green; `ArchitectureTest` 0 cycles; no migration added (Flyway head stays V46).

---

## Audit decisions (A–O)

**A. ContactGroupService or dedicated ContactGroupMemberService?**
**Dedicated `ContactGroupMemberService`.** `ContactGroupService` is already ~700 lines spanning groups, contacts, import, and export; membership API semantics (idempotent add/remove, batch outcomes, roster paging, count) are a coherent separate responsibility. To keep **one canonical mutation path**, the existing private `upsertMembership` and the delete cascades move into the member service and `ContactGroupService` delegates. The shared group visibility/authorization gate is extracted into one package-private component used by both — no duplicated authz logic (rule 19), acyclic dependency graph.

**B. Canonical membership API?**
Sub-resource of contact groups, following the existing `/{id}/contacts/{contactId}` convention of addressing children by the underlying entity id (here the contact):
- `GET /api/v1/contact-groups/{groupId}/members` — paged roster (membership fields + embedded live-contact payload), `page/size/sort/search`.
- `POST /api/v1/contact-groups/{groupId}/members` — body `{ "contactId": "…" }`; 201 (created) / 200 (already member).
- `GET /api/v1/contact-groups/{groupId}/members/{contactId}` — membership detail (404-cloaked).
- `DELETE /api/v1/contact-groups/{groupId}/members/{contactId}` — 204, idempotent.
- `POST /api/v1/contact-groups/{groupId}/members/batch` — `{ contactIds: [...] }`, per-item outcomes.
- `DELETE /api/v1/contact-groups/{groupId}/members/batch` — same, removal.
- `memberCount` added to `ContactGroupResponse`.
Members are **relationships only**: the API never creates or modifies contacts (rule 20; contact creation remains `POST /{id}/contacts` and import).

**C. Add-member idempotent?** **Yes.** Established precedent (`upsertMembership` exists-check + constraint-backed race catch) and physical-relationship semantics make re-add a natural idempotent upsert. Return 200 with the existing membership when already present, 201 when newly created. The DB unique constraint remains the concurrency authority.

**D. Remove-member idempotent?** **Yes.** Physical rows: removing an absent membership is already the desired end state → 204. Distinguishing "was a member" would add statefulness the model doesn't have. Group 404 still applies (authorization path); contact existence is irrelevant to a relationship delete.

**E. Duplicate membership reporting?** **Not an error.** Single add → 200 with existing membership. Batch → per-item status `EXISTS` alongside `CREATED`/`ERROR`. A raced duplicate (DIVE on the unique index) resolves to the same idempotent outcome — never a 500, never a 409 (409 remains reserved for contact-identity conflicts, which are a different invariant).

**F. Missing Contact?** **404** (`ResourceNotFoundException`, "Contact not found") via tenant-scoped live lookup within the group's tenant. Foreign-tenant contact = nonexistent (404-cloak convention). Never auto-created through the member API — creation is the identity flow's job (rule 20).

**G. Missing ContactGroup?** **404** via the existing `findVisibleGroup` gate — identical to every current group endpoint (foreign and nonexistent indistinguishable).

**H. Cross-tenant Contact/Group combination?** **404-cloaked** at the API (contact lookup is scoped to the group's tenant, so a foreign contact is simply "missing"), with the **composite FK `fk_cgm_contact` as the DB backstop** — a cross-tenant row physically cannot exist even under a service bug (proven by PG-C6). No service-level tenant re-check beyond the existing lookup; DB constraints stay authoritative (rule 7/8).

**I. Reseller authorization?** **Reuse the platform/reseller/tenant hierarchy exactly as `ContactGroupService` implements it**: TENANT → query-scoped lookup + `AccessCheck.forTenant`; RESELLER → live lookup + `hierarchyTenantIds(resellerId)` containment (`findAllByResellerIdAndStatus(ACTIVE)`) + `AccessCheck.forTenant(group.tenantId)` capability check; PLATFORM → unbounded + `platformWide()`. Capabilities unchanged (`CONTACT_VIEW`/`CONTACT_MANAGE`). No weakening, no duplication — the gate is shared via the extracted component.

**J. Pagination model?** **The existing one, unchanged**: `page`/`size`/`sort` params, default sort, whitelisted sort fields with silent fallback, `MAX_PAGE_SIZE=100`, `ResponseFactory.page` + `PaginationMetadata`. Member roster defaults: `createdAt,asc`; sortable whitelist `createdAt`, `firstName`, `phoneNumber` (joined contact fields).

**K. Filtering/sorting — what exists vs necessary?** Existing: `search` (case-insensitive contains over names + phone) via `ContactSpecifications.search`; group list `search` on name. **MVP necessity**: roster `search` over contact name/phone (reuse the same semantics in the roster JPQL) + sort whitelist above. Explicitly **not** built (rule 15): attribute search, date-range filters, membership-status filters (there is no status), reverse listing (groups of a contact).

**L. Member count without N+1?** `countByContactGroupId` for single-group use (get-by-id, readiness-style checks) — index-backed by `uq_cgm_group_contact`. For group **list** pages: one grouped query `SELECT m.contactGroupId, COUNT(m) … WHERE m.contactGroupId IN (:pageIds) GROUP BY m.contactGroupId` mapped into the page's responses — never a per-row count query.

**M. Required DB constraints/indexes?** **None beyond V46** — uniqueness (`uq_cgm_group_contact`), composite tenant FKs (`fk_cgm_contact`, `fk_cgm_group`), and lookup indexes (`idx_cgm_contact`, `idx_cgm_tenant`) already cover every query this API issues. **No new migration** (rule 18 satisfied: V46 is sufficient for the approved API).

**N. Concurrency guarantees?** (1) At most one membership row per `(group, contact)` pair — DB-enforced, unaffected by races. (2) Raced adds resolve idempotently (constraint is the authority; DIVE caught → EXISTS). (3) Roster reads are consistent per-transaction; no cross-request locking. (4) No locks/queues/new infra (rule 6). (5) Authorization never replaces DB invariants (rule 8) — the composite FKs remain the tenant-isolation authority.

**O. VB-6B.2 vs later bulk work?**
- **VB-6B.2**: single + batch member add/remove by contact id, paged roster, member detail, `memberCount`, `createdBy` stamping, service delegation (one canonical path), tests (unit + slice + PG).
- **Later**: bulk *file* membership import (file-of-phones → memberships, reusing import readers), cross-group copy/move, membership-level analytics/history, any member update semantics.

---

## Dependency inventory (inspected)

**Production read**: `ContactGroupMemberEntity`, `ContactGroupMemberRepository`, `ContactGroupEntity`, `ContactEntity`, `ContactGroupService`, `ContactIdentityService`, `ContactGroupMapper`, `ContactMapper`, `ContactSpecifications`, `ContactRepository`, `ContactGroupRepository`, `ContactGroupController`, all `contact/dto/*` (6 records), `AuditableEntity`/`BaseEntity`, `AuthorizationService`, `AccessCheck`, `OrganizationContextHolder` (usage), `CurrentUserProvider` (usage), `ApiResponse`/`ResponseFactory`/`PaginationMetadata`, `CommonErrorCode`, `BusinessException`/`ConflictException`/`ResourceNotFoundException`/`ForbiddenException`, `GlobalExceptionHandler`, `CampaignExecutionOrchestrator` (membership usage), `CallEligibilityService` (membership usage), `CampaignReadinessService.checkContactGroup`, `V46__contact_identity_and_group_membership.sql`.

**Tests read**: `ContactIdentityServiceTest`, `ContactOwnershipServiceTest`, `ContactImportServiceTest`, `ContactIdentityPostgresIntegrationTest`, `ContactIdentityMigrationPostgresIntegrationTest`, `DialBatchContinuationPostgresIntegrationTest`, campaign PG suites (seed/teardown patterns), `QueueDirectoryApiSliceTest` (slice-test pattern), `SecuritySliceTest` (located), `ArchitectureTest` (Modulith verify).

**Frontend read**: `frontend/src/lib/api/contact-groups.ts`, `frontend/src/lib/api/contacts.ts` (contract documented against the controller), `frontend/src/lib/api/campaigns.ts` (group selector), contact-groups views (no member usage).

**Repo-wide searches**: all `ContactGroupMemberRepository` consumers; all `contactGroupId`/`contact_group_id` references (campaign-owned only, per VB-6B.1 regression grep); all `api/v1` route map; batch/bulk endpoint inventory; member-count usage (none exists today).

---

## Status

**VB-6B.2 AUDIT COMPLETE — IMPLEMENTATION NOT STARTED**

- Baseline: 910 tests / 0 failures / 0 errors / 1 skipped / BUILD SUCCESS (unchanged working tree).
- Flyway head: V46 — no migration required for this phase.
- ArchitectureTest: 1/0/0/0, 0 cycles.
- Decisions A–O recorded above; implementation sequence in §18; no production file was touched in this phase.

**STOP — implementation begins only on explicit instruction.**
