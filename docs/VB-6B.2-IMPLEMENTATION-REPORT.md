# VB-6B.2 — ContactGroupMember Service + REST API — Implementation Report

Status: **COMPLETE** — full suite green, API contract verified, architecture clean, Flyway head unchanged.
Date: 2026-09-27. Audit: `docs/VB-6B.2-CONTACT-GROUP-MEMBER-AUDIT.md`.

---

## 1. Baseline

- Entering state (VB-6B.1 final gate): 910 tests / 0 failures / 0 errors / 1 skipped / BUILD SUCCESS; ArchitectureTest 1/0/0/0 (0 cycles); Flyway V46.
- V46 already contained the full membership schema; the phase required **no migration**.

## 2. Implementation summary

Implemented the canonical `ContactGroupMemberService` plus a six-operation member REST API under `/api/v1/contact-groups/{groupId}/members`, extracted the shared `ContactGroupAccess` authorization gate, delegated all membership mutations from `ContactGroupService`, added `memberCount` to group responses, and delivered the test matrix (unit + first-ever API slice for this controller + PostgreSQL integration + generated-OpenAPI contract). Final gate: **950 tests / 0 failures / 0 errors / 1 skipped / BUILD SUCCESS** (+40 tests over baseline: 15 unit + 10 slice + 10 PG + 5 OpenAPI).

## 3. Repository changes (`ContactGroupMemberRepository`)

- `countLiveByContactGroupId(groupId)` — live member count (JPQL join to the contact, `deletedAt IS NULL`), so the direct count agrees with the roster and grouped counts (a plain `countByContactGroupId` was replaced after PG testing showed it counted memberships of soft-deleted contacts).
- `deleteByContactGroupIdAndContactId(groupId, contactId)` — returns the deleted-row count; idempotent by construction.
- `findRosterPage(groupId, search, Pageable)` — membership-led JPQL `SELECT m … JOIN m.contact c WHERE m.contactGroupId = :groupId AND c.deletedAt IS NULL` + optional case-insensitive contains `search` over firstName/lastName/phoneNumber; supports `Pageable` sorting directly on joined contact fields.
- `findCountsByGroupIds(groupIds)` — one grouped `SELECT m.contactGroupId, COUNT(m) … WHERE m.contactGroupId IN (:groupIds) AND c.deletedAt IS NULL GROUP BY m.contactGroupId`.
- No speculative queries; no schema change.

## 4. Shared authorization extraction (`ContactGroupAccess`)

New package-private `@Component` holding the gate previously private to `ContactGroupService`: `findVisibleGroup` (TENANT → `findByIdAndTenantIdAndDeletedAtIsNull`; RESELLER → live lookup + active-hierarchy containment via `hierarchyTenantIds`; PLATFORM → unbounded) + `authorizedGroup(id, capability)` = visibility + `requireCapability(userId, CONTACT_VIEW|CONTACT_MANAGE, AccessCheck.forTenant(group.tenantId))`, plus `requireUserId`, `currentScope`, and the shared 404 factories. **Behavior-identical refactor** — `ContactGroupService` was rewired to it, `ContactGroupMemberService` uses the same gate; no new capability; 404-cloaking preserved.

## 5. Service design (`ContactGroupMemberService`)

Canonical membership boundary. Reads: `listMembers` (paged roster, sortable whitelist `createdAt`/`firstName`/`phoneNumber`, default `createdAt,asc`, `MAX_PAGE_SIZE=100`), `getMember` (404-cloaked). Writes: `addMember` (single, idempotent, 201/200), `removeMember` (idempotent 204), `addMembers`/`removeMembers` (batch, per-item outcomes, duplicates deterministically replayed), cascades `removeAllForGroup`/`removeAllForContact`. Internal `insertMembership` is **THE** membership upsert (exists fast-path → `saveAndFlush` in the guarded block → DIVE = idempotent idempotent outcome; `createdBy` stamped). Exposes `memberCount`/`memberCounts` and `memberContactIds` for `ContactGroupService`. The service never creates/updates/deletes contacts — the member API resolves contacts strictly via `findByIdAndTenantIdAndDeletedAtIsNull(contactId, group.tenantId)`.

Dependency graph (acyclic, Modulith-verified): `ContactGroupService → ContactGroupMemberService → ContactGroupAccess`; `ContactGroupService → ContactGroupAccess`.

## 6. ContactGroupService delegation

- Private `upsertMembership` **removed**; `createContact`/`importContacts` call `memberService.insertMembership(...)` — same exists-check + flush-in-guard + DIVE→idempotent semantics as before.
- `deleteGroup` → `memberService.removeAllForGroup(groupId)`; `deleteContact` → `memberService.removeAllForContact(contactId)`.
- Roster/export readers use `memberService.memberContactIds(groupId)` (identical queries as before).
- Authorization now goes through the shared `ContactGroupAccess` gate; `listGroups`/`getGroup`/`createGroup`/`updateGroup` additionally populate `memberCount` (§10).
- Externally visible behavior of existing group/contact APIs unchanged (proven by the untouched green ownership/identity/import suites).

## 7. DTOs (fully `@Schema`-documented)

- `ContactGroupMemberResponse(memberId, groupId, contactId, tenantId, contact: ContactResponse, createdAt, createdBy: UUID|null)` — `createdBy` is parsed to UUID when it is one; migration-era rows may be null (documented).
- `AddMemberRequest(contactId @NotNull)` — required, `format: uuid` in the schema.
- `BatchMemberRequest(contactIds @NotNull @NotEmpty @Size(max=500))` — `minItems/maxItems` are not expressible in this springdoc `@Schema` version, so the 1–500 bound is documented in the description and enforced by validation.
- `MemberBatchStatus` enum: `CREATED`, `EXISTS`, `NOT_FOUND`, `NOT_FOUND_CONTACT`, `ERROR` (enum meanings documented on the type).
- `BatchMemberResult(contactId, status, errorDetail nullable — coarse, non-leaking, only for ERROR)`.
- `BatchMemberResponse(results, processed, failed)` — one entry per requested item in request order.
- `ContactGroupResponse` gained `memberCount: long` (additive; documented as live-member count excluding soft-deleted contacts).

## 8. REST endpoints (all OpenAPI-documented on `ContactGroupController`)

| Method | Path | Auth | Success | Errors |
|--------|------|------|---------|--------|
| GET | `/api/v1/contact-groups/{id}/members` | CONTACT_VIEW | 200 + pagination | 400/403/404 |
| POST | `/api/v1/contact-groups/{id}/members` | CONTACT_MANAGE | 201 new / 200 existing | 400/403/404 |
| GET | `/api/v1/contact-groups/{id}/members/{contactId}` | CONTACT_VIEW | 200 | 403/404 |
| DELETE | `/api/v1/contact-groups/{id}/members/{contactId}` | CONTACT_MANAGE | 204 (idempotent) | 403/404 |
| POST | `/api/v1/contact-groups/{id}/members/batch` | CONTACT_MANAGE | 200 + per-item outcomes | 400/403/404 |
| DELETE | `/api/v1/contact-groups/{id}/members/batch` | CONTACT_MANAGE | 200 + per-item outcomes | 400/403/404 |

404-cloaking on every endpoint: nonexistent group, unauthorized group, foreign tenant, and (for reads/writes) missing membership/contact are indistinguishable. Batch item statuses: add → `CREATED`/`EXISTS`/`NOT_FOUND_CONTACT`/`ERROR`; remove → `CREATED` (row deleted)/`NOT_FOUND`/`ERROR`.

## 9. OpenAPI/Swagger documentation

springdoc-openapi-starter-webmvc-ui (already the project's library) generates the contract from the annotated controller/DTOs; `@Operation` (summary, description, `bearerAuth` security) + per-status `@ApiResponse` on all six operations, `@Parameter` on path/query params (page/size/sort/search semantics and defaults described), `@Schema` on every DTO field (purpose, required mode, nullability, examples). No manually maintained spec; no new documentation infrastructure.

## 10. memberCount implementation

- `GET /contact-groups/{id}` (+ create/update responses): `memberService.memberCount` → one `countLiveByContactGroupId` query.
- `GET /contact-groups` (list): `withMemberCounts(...)` → **one** `findCountsByGroupIds` grouped query for the page's ids, `getOrDefault(…, 0L)` filling groups with no members (the grouped query omits empty groups by design).
- Never a per-group count loop (asserted in unit test MEM-13).

## 11. Idempotency semantics

- **Add**: exists fast-path → nothing written (200, `created=false`). Raced duplicate → `DataIntegrityViolationException` at flush → caught, **no follow-up query** (the TX is aborted server-side, PostgreSQL `25P02`), returns the pre-insert entity with `created=false` → mapped to EXISTS/200. Never 500, never 409 (409 stays reserved for contact-identity conflicts).
- **Remove**: `deleteByContactGroupIdAndContactId` returns 1/0 → 204 either way; contact existence irrelevant.

## 12. Batch semantics

Never fail-fast. Distinct items processed independently; one bad item never aborts others. Duplicate ids within a request are deterministically replayed under idempotent semantics — add: a replayed `CREATED` becomes `EXISTS`, other first-outcomes repeat verbatim; remove: a replayed `CREATED` (row deleted) becomes `NOT_FOUND` (nothing left to remove). Constraint races inside batch add resolve per-item to `EXISTS`. `ERROR` items carry a coarse retry hint only (`"Add failed; retry this item."`); no SQL/exception detail leaks. `processed = results − ERROR`, `failed = ERROR count`.

## 13. createdBy behavior

`insertMembership` stamps `createdBy = CurrentUserProvider.current().userId` for every new membership (V46 reserved the column; PG-M9 proves DB persistence). Migration-era NULL rows are **not** backfilled (no migration). No `updated_at`/`updated_by` mapping was added — memberships remain immutable; `ContactGroupMemberEntity` still does not extend `AuditableEntity`.

## 14. Concurrency behavior

The `uq_cgm_group_contact` unique constraint remains the sole concurrency authority. PG-M1: 20 threads racing `addMember` on one pair → **exactly 1 physical row**, all outcomes classified CREATED/EXISTS, zero unexpected. The DIVE catch deliberately performs no recovery read (aborted-TX lesson from PG testing; the idempotent guarantee comes from the constraint itself). No locks, queues, or new infrastructure.

## 15. API error semantics

`GlobalExceptionHandler` (RFC 7807 ProblemDetail): bean validation (`@NotNull`/`@NotEmpty`/`@Size`) → 400 `VALIDATION_ERROR` with field errors; `ResourceNotFoundException` → 404; `ForbiddenException` from `requireCapability` → 403; no invented status codes — the documented errors are exactly what the handler produces (verified by slice tests hitting 400/404/200/201/204 paths).

## 16. API compatibility

Purely additive. No existing endpoint, DTO field, envelope, or status code changed. Frontend clients (`contact-groups.ts`, `contacts.ts`, campaign dialogs) untouched and unaffected; `memberCount` is a new field on `ContactGroupResponse`. `POST /{id}/contacts` still creates contacts + upserts membership (now via delegation). No compatibility shims for the pre-V46 group-owned model.

## 17. Tests

- `ContactGroupMemberServiceTest` (15, unit): add new (201 semantics + `createdBy` captor), add existing (no second save), missing contact 404, foreign-tenant contact 404-cloak, foreign group 404, constraint-race → EXISTS, remove existing, remove missing no-op, batch mixed + duplicate replay (exactly one `saveAndFlush`), roster pagination metadata, search passthrough, sort whitelist fallback (`phoneNumber,DESC` honored; unknown field → `createdAt,asc` default), member counts single+grouped with no id materialization, getMember + 404-cloak, batch remove + cascade delegation.
- `ContactGroupMemberApiSliceTest` (10, standalone MockMvc + real `GlobalExceptionHandler`): POST → 201 envelope; POST existing → 200; `{}` → 400 ProblemDetail; unknown contact → 404; roster envelope + `pagination.*`; GET member + missing → 404; DELETE → 204 (both cases); batch add response shape (`results[0].status`, `processed`, `failed`); batch validation 400 + unknown group 404; batch remove shape.
- `ContactGroupMemberOpenApiContractTest` (5, Spring Boot slice importing springdoc + real context): asserts the **generated** `/v3/api-docs` (§21).
- Updated in place: `ContactIdentityServiceTest`, `ContactOwnershipServiceTest`, `ContactImportServiceTest`, `ContactIdentityPostgresIntegrationTest` (constructor wiring only — assertions unchanged).

## 18. PostgreSQL integration (`ContactGroupMemberPostgresIntegrationTest`, 10 scenarios, postgres:16-alpine + full Flyway chain)

PG-M1 concurrent duplicate adds → 1 row; PG-M2 composite FK + 404-cloak; PG-M3 roster pagination (7 members, page size 3 → 3 pages) with embedded contact payload; PG-M4 direct + grouped counts vs physical rows; PG-M5 soft-deleted contact excluded from roster/counts/search; PG-M6 delete-by-pair idempotency (1 then 0); PG-M7 group delete removes memberships, contact untouched; PG-M8 contact delete removes memberships, identity soft-deleted; PG-M9 `createdBy` persisted to the DB column; PG-M10 batch add/re-add EXISTS/remove/re-remove NOT_FOUND against real PostgreSQL.

## 19. ArchitectureTest

`ApplicationModules.verify()`: **1 run / 0 failures / 0 errors — 0 cycles**. `contact` remains the sole owner of `ContactGroupMember`; no campaign/telephony membership logic added (their `findByContactGroupId` consumption unchanged).

## 20. Flyway verification

Head remains **V46**; no V47, no modification of V46. All 45 migrations applied cleanly in every PG container run of the new suite.

## 21. Generated OpenAPI verification

`ContactGroupMemberOpenApiContractTest` fetches the real generated spec from **`GET /v3/api-docs`** (springdoc default; Swagger UI at `/swagger-ui/index.html` — the project exposes these defaults, none invented) and asserts:

- All six operations present at `/api/v1/contact-groups/{id}/members`, `…/{contactId}`, `…/batch`.
- operationIds globally unique with stable descriptive stems (`listMembers_1`/`addMember_1`/`getMember`/`removeMember_1` — springdoc suffixes `_1` because `QueueDirectoryController` legitimately owns the same method names; `addMembersBatch`/`removeMembersBatch` unsuffixed).
- Schemas present: `AddMemberRequest` (`contactId` required, `format: uuid`), `BatchMemberRequest` (`contactIds` required), `BatchMemberResult` (with `status`), `BatchMemberResponse`, `ContactGroupMemberResponse`.
- `ContactGroupResponse.memberCount` present; **no** `contactGroupId` on `ContactGroupMemberResponse` or `ContactResponse` (no obsolete ownership field).
- Member list documents `page`, `size`, `sort`, `search`, `id` parameters.

## 22. Files changed

Production:
- New: `contact/ContactGroupAccess.java`, `contact/ContactGroupMemberService.java`, `contact/dto/{AddMemberRequest,BatchMemberRequest,BatchMemberResult,BatchMemberResponse,ContactGroupMemberResponse,MemberBatchStatus}.java`
- Modified: `contact/ContactGroupMemberEntity.java` (read-only `contact` join), `contact/ContactGroupMemberRepository.java`, `contact/ContactGroupService.java` (delegation + gate + memberCount), `contact/ContactGroupMapper.java` (memberCount slot), `contact/ContactGroupController.java` (six endpoints + docs), `contact/dto/ContactGroupResponse.java`

Tests:
- New: `contact/{ContactGroupMemberServiceTest,ContactGroupMemberApiSliceTest,ContactGroupMemberPostgresIntegrationTest,ContactGroupMemberOpenApiContractTest}.java`
- Updated (ctor wiring only): `contact/{ContactIdentityServiceTest,ContactOwnershipServiceTest,ContactImportServiceTest,ContactIdentityPostgresIntegrationTest}.java`

Docs: `docs/VB-6B.2-CONTACT-GROUP-MEMBER-AUDIT.md` (prior phase), this report.

## 23. Known limitations

- `errorDetail` on batch ERROR items is intentionally coarse (retry hint); no structured per-item error codes.
- Batch remove does not verify contact liveness (by design — relationship delete); a membership of a soft-deleted contact can still be explicitly removed.
- Roster `search`/`sort` operate on the joined contact payload only; no attribute-based search (out of scope, rule 15).
- springdoc operationId `_1` suffixes exist for `listMembers`/`addMember`/`removeMember` due to pre-existing QueueDirectory method names (globally unique; documented in §21).
- `minItems/maxItems` on `BatchMemberRequest.contactIds` are description-documented, not schema-attribute-expressed (annotation version lacks them); validation enforces 1–500.
- Windows host memory quirk persists: full suites need reduced JVM settings (`-Xmx512m -XX:MaxMetaspaceSize=288m` for Maven, `-DargLine="-Xmx384m -XX:MaxMetaspaceSize=224m"` for the surefire fork); two native-OOM hs_err crashes occurred and were cleaned up during the run.

## 24. Deferred VB-6B.3+ work

- Bulk **file** membership import (file-of-phones → memberships reusing import readers).
- Cross-group copy/move of memberships; "replace audience" operations.
- Reverse listing (groups of a contact) and membership-level analytics/history.
- VB-6C daily limits, contact history, suppression lists.
- Any membership update semantics (rows stay immutable).

## 25. Final status

- `./mvnw test` (full suite): **950 tests, 0 failures, 0 errors, 1 skipped — BUILD SUCCESS** (baseline 910 + 40 new).
- ArchitectureTest: 1/0/0/0 — 0 cycles.
- Flyway head: **V46** (unchanged; no migration).
- Generated OpenAPI contract inspected and asserted by test (§21).
- All FINAL GATE checklist items satisfied; no out-of-scope work implemented.

**VB-6B.2 — COMPLETE.**
