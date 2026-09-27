# VB-6B — Contact & Audience Audit

## 1. Status

    AUDIT COMPLETE
    IMPLEMENTATION NOT STARTED

This phase made **zero** changes to production code, tests, migrations, or configuration.
The only file created is this audit document. All claims below are evidence-tagged:
**OBSERVED** (read directly from the repository/database), **INFERRED** (derived from
observed code), **RECOMMENDED** (audit judgment), **OPEN DECISION** (must be resolved
before implementation).

## 2. Baseline

Measured this session, not assumed (**OBSERVED**):

| Metric | Value |
|---|---|
| Test run | `MAVEN_OPTS="-Xmx768m -XX:MaxMetaspaceSize=384m" ./mvnw -q clean test` (from `backend/`) |
| Total tests | **886** |
| Failures / Errors | **0 / 0** |
| Skipped | **1** (pre-existing environment-conditional skip) |
| Build result | **BUILD SUCCESS** (exit 0; counts summed from `target/surefire-reports/TEST-*.xml`) |
| Flyway head | **V44** — `V44__campaign_execution_configurations.sql`; dev DB `flyway_schema_history` confirms head=44. **Note: V43 does not exist on disk** (numbering gap left by the in-place V44 rewrite; OBSERVED in migration directory listing) |
| ArchitectureTest | **1 test, 0 failures, 0 errors, 0 skipped** — 0 Modulith cycles (`TEST-com.shivang.obd.architecture.ArchitectureTest.xml`) |
| Git state | Branch `master` has **zero commits**; 16 untracked top-level entries (`git log` → "does not have any commits yet") |
| Dev DB | `obd-postgres` container: `contacts`=129 rows, `contact_groups`=12 rows, `call_attempts`=0 rows (read-only SELECT inspection) |

The VB-6A-corrected baseline (886/0/0/1, V44) is still current; nothing regressed.

## 3. Repository State

**OBSERVED.** The contact domain lives in `com.shivang.obd.contact` (16 files + `dto/`),
declared as a Modulith module in `package-info.java` ("Owns tenant-scoped callable
audiences referenced by Campaign through identifier references only"). The campaign module
imports `ContactEntity`/`ContactRepository` directly (orchestrator, dial service, telephony
eligibility adapter). There is **no** audience abstraction: `grep -rniE
"audienceSource|FILE_IMPORT|Audience"` matches only Javadoc/comments — `AudienceSource`,
`INLINE`, and `FILE_IMPORT` as code concepts **do not exist** (confirming §3 of the audit
brief). The previous audit's summary is accurate on every point re-verified below, with one
correction: the `call_attempts` contact index **does exist** (previous audit reported it
missing).

## 4. Contact Domain

**OBSERVED** (all from `backend/src/main/java/com/shivang/obd/contact/`):

- **Entity** — `ContactEntity`: `tenantId` (NOT NULL, denormalized from group),
  `contactGroupId` (NOT NULL), `firstName` (optional since V18), `lastName`, `phoneNumber`
  (NOT NULL, VARCHAR(20)), `email`, `attributes` (JSONB). Extends `AuditableEntity`
  (id, created_at/by, updated_at/by, deleted_at/by).
- **Repository** — `ContactRepository`: all lookups group-scoped or id-scoped;
  `existsByContactGroupIdAndPhoneNumberAndDeletedAtIsNull` (dial-time membership),
  `findLivePhoneNumbers(contactGroupId)` (import dedup batch lookup), and Specification
  executor for listing. **No tenant-scoped phone lookup exists** (no
  `findByTenantIdAndPhoneNumber...` method — INFERRED identity gap, §5).
- **Service** — no standalone ContactService; all contact logic lives in
  `ContactGroupService` (group-scoped by design).
- **Controller** — `ContactGroupController` (`/api/v1/contact-groups`) hosts contact CRUD
  nested under groups (§21).
- **DTOs** — `CreateContactRequest` (E.164 via `@Pattern(ContactValidation.E164_REGEX)`),
  `UpdateContactRequest` (same), `ContactResponse`, `ContactImportError`,
  `ContactImportResponse`.
- **Validators** — `ContactValidation`: E.164 regex `^\+[1-9][0-9]{6,14}$`, email regex,
  `canonicalizePhoneNumber` (separator-strip then E.164 match → null on failure).
- **Normalization** — contact-side canonicalization exists only in the **import** path
  (`canonicalizePhoneNumber`); single-create/API path does **not** canonicalize (DTO
  pattern requires the raw string to already be exact E.164 including `+`).
- **Status** — none. A contact is live (`deleted_at IS NULL`) or soft-deleted. No
  ACTIVE/INACTIVE/BLOCKED/DND states exist.
- **Soft delete** — `deleteContact` stamps `deleted_at`/`deleted_by`
  (`ContactGroupService.deleteContact`).
- **Ownership** — inherited from the group at creation
  (`contactMapper.toEntity(request, group.getTenantId(), groupId)`); the client can never
  set tenant (locked by `ContactOwnershipServiceTest.contactInheritsTheGroupsTenantNeverTheClients`).
- **Group relationship** — every contact belongs to exactly one group (`contact_group_id
  NOT NULL`); membership IS the row's group column, not a join table.
- **Phone representation** — canonical E.164 string, DB CHECK-enforced
  (`ck_contacts_phone_format`, V17).

## 5. Contact Identity

Answering §7 of the brief from the actual model (**OBSERVED** unless tagged):

1. **Reusable person/lead identity?** No. A Contact is "a callable person inside a contact
   group" (entity Javadoc) — i.e., a phone number within a group.
2. **Just a phone number inside a group?** Effectively yes: identity =
   `(contact_group_id, phone_number)`, enforced by the live partial unique index.
3. **Same number in multiple groups?** Yes — allowed and ordinary (uniqueness is
   group-scoped). The same number creates a separate Contact row per group.
4. **Same person, multiple numbers?** Not modeled: no person concept, no link between
   contacts; two numbers = two unrelated Contact rows.
5. **Stable UUID?** Yes — `id UUID PRIMARY KEY` (gen_random_uuid()), and
   `CallAttempt.contact_id` references it historically.
6. **Is phone number the identity?** Within a group, yes. Globally, no.
7. **Phone unique globally?** No.
8. **Unique per tenant?** No (no such constraint).
9. **Unique per group?** Yes — for **live** rows only
   (`uq_contacts_group_phone_live ON contacts(contact_group_id, phone_number) WHERE
   deleted_at IS NULL`, V19; verified in live DB index list).
10. **Can a contact move between groups?** No — no API or service method re-parents a
    contact (`updateContact` only updates names/phone/email/attributes).
11. **Can a contact belong to multiple groups?** No (single NOT NULL group column; no
    membership join table).
12. **Membership join entity?** None — membership is the `contacts.contact_group_id`
    column itself.
13. **Deleting a group delete contacts?** Never — `deleteGroup` refuses with 409 while
    live contacts exist (`ContactGroupService.deleteGroup` → ConflictException).
14. **Deleting a contact remove group membership?** Membership is the row; soft-delete
    hides the row from all live queries. The partial unique index makes the phone number
    immediately reusable in that group after deletion (locked by
    `ContactImportServiceTest.softDeletedPhoneNumberIsReusable`).
15. **Is contact history preserved?** Partially: `CallAttempt.contactId` survives a contact
    soft-delete (attempt rows are never cascaded), but there is no contact-centric history
    API or `CallSession.contactId` (§16).

**Classification: EXTEND** (**RECOMMENDED**). The row structure (tenant_id, group_id,
E.164 phone, attributes, audit columns) is sound; what's missing for reuse across
campaigns is tenant-level identity semantics, not a new table. A tenant-scoped identity
layer can be added by extending the existing entity/constraints without a redesign
(§33, §32).

## 6. Contact Group Domain

**OBSERVED:**

- **Entity** — `ContactGroupEntity`: `tenantId` NOT NULL, `name` VARCHAR(150) NOT NULL,
  `description`. No status, no dedup constraints on name.
- **Repository** — `ContactGroupRepository`: `findByIdAndTenantIdAndDeletedAtIsNull`,
  `findByIdAndDeletedAtIsNull`, two `exists...` variants; Specification executor.
- **Service** — `ContactGroupService`: group CRUD + nested contact CRUD + import/export
  (single boundary service for both aggregates).
- **Controller** — `ContactGroupController`.
- **Membership logic** — implicit via `contacts.contact_group_id`; no join entity, no
  membership operations beyond contact create/delete.
- **Lifecycle** — soft delete only, blocked at 409 while live contacts exist.
- **Ownership** — tenant-owned at creation from caller's context tenant; platform callers
  cannot create groups (`createGroup` requires a non-null context tenantId).
- **Tenant scoping** — every read path tenant/hierarchy-scoped (§7, §22).
- **Deduplication** — none at group level (same number can be imported into many groups by
  design).
- **Update** — name/description only (`UpdateContactGroupRequest`).
- **Import behavior** — §11.

**What a group IS today** (INFERRED from code): a tenant-owned **contact container +
import container + campaign-audience definition** — one object serving three roles. It is
reusable across campaigns (many campaigns may reference one group), mutable, and read
dynamically by executions (§9).

## 7. Tenant / Reseller Ownership

**OBSERVED:**

- **Contact owner** — the owning group's tenant, denormalized onto the row at creation and
  never client-writable (`ContactMapper.toEntity(request, group.getTenantId(), groupId)`;
  `CreateContactRequest` has no tenant field).
- **Group owner** — the caller's context tenant (`createGroup` rejects null tenant).
- **Reseller visibility** — `listGroups` resolves the active-tenant hierarchy
  (`hierarchyTenantIds` = `findAllByResellerIdAndStatus(ACTIVE)`) and lists across it;
  single-group reads for reseller scope use hierarchy membership check
  (`findVisibleGroup`) → foreign groups are 404-indistinguishable.
- **Contact reads** — always nested under an authorized group
  (`authorizedGroup(groupId, cap)` then `findVisibleContact(groupId, contactId)`), so a
  contact outside its group is 404 (`ContactOwnershipServiceTest.contactOutsideItsGroupIsIndistinguishableFromMissing`).
- **AccessCheck usage** — `CONTACT_VIEW` / `CONTACT_MANAGE` capabilities against
  `AccessCheck.forTenant(group.tenantId)` on every operation.
- **Cross-tenant leak checks** — group→contact nesting is tenant-safe; import derives
  ownership exclusively from the authorized group ("never from file contents" — locked by
  `ContactImportServiceTest.foreignTenantCannotImportIntoAnotherTenantsGroup`).

**Defense-in-depth weaknesses found** (documented, NOT fixed — **OBSERVED**):

1. **Dial-time contact lookup is not tenant-scoped** — `OutboundDialService.buildDestinationNumber`
   uses `contactRepository.findByIdAndDeletedAtIsNull(attempt.getContactId())` (no tenant
   predicate). Not exploitable today because `attempt.contactId` is server-derived and the
   attempt row carries the tenant, but it is the only contact read in the dial path without
   a tenant predicate. Severity: LOW (defense-in-depth, not IDOR).
2. **Single-contact creation has no application-level dedup** — `createContact` saves
   directly; a duplicate number collides on `uq_contacts_group_phone_live` and surfaces as
   an unhandled `DataIntegrityViolationException` (500) instead of a typed 409. Import has
   proper dedup; the single-create path does not. Severity: MEDIUM (API behavior).
3. **No tenant-scoped phone-number lookup method exists** in `ContactRepository` — if
   VB-6B introduces tenant-level identity, all phone lookups must gain tenant predicates;
   today group-scoping is the only isolation for phone lookups. Severity: LOW (currently
   safe; design constraint for VB-6B).

## 8. Phone Number Normalization

**OBSERVED** — two normalization utilities exist with slightly different contracts:

| | `ContactValidation.canonicalizePhoneNumber` (contact module) | `PhoneNumberNormalizer.normalize` (voice.media) |
|---|---|---|
| Location | `com.shivang.obd.contact` | `com.shivang.obd.voice.media` |
| Behavior | trim → strip `[space ( ) . -]` → E.164 regex match, **null on failure** | trim → strip `[space - ( ) .]` → **adds `+` if bare digits match** |
| Used by | bulk import only (dedup key + stored value) | `CallEligibilityService` membership check, `VoiceEligibilityService`, `OutboundDialService.buildDestinationNumber` |
| Error style | returns null → row error | returns best-effort string; caller validates with `isValidE164` |

- **E.164 contract** — single regex `^\+[1-9][0-9]{6,14}$` (`ContactValidation.E164_REGEX`),
  shared by DTO annotation, import pipeline, and DB CHECK constraint ("Do not duplicate
  these expressions elsewhere" per its Javadoc).
- **Layer where normalization happens** — split: DTO-level (`@Pattern`, create/update),
  service-level (import canonicalization), runtime-level (dial/eligibility re-normalize
  defensively). **No DB-level normalization** (CHECK validates only).
- **Canonical boundary** (**RECOMMENDED**): `ContactValidation` is the persistence-side
  canonicalizer and already declares itself the single source of the contract;
  `PhoneNumberNormalizer` remains the voice-layer dial-string normalizer. VB-6B should
  route **all** contact-input paths (single create, future inline) through
  `canonicalizePhoneNumber` rather than adding a third normalizer.
- **Whitespace/`+`/leading zeros** — separators stripped in both; `+` required for the
  stored form (CHECK); leading zero fails the regex (`[1-9]` first digit) — leading-zero
  handling is therefore **reject, not repair** (**OBSERVED**).
- **Malformed/blank** — create path: 400 via `@Pattern` (blank → pattern violation);
  import path: per-row `INVALID_E164` error (**OBSERVED**).
- **Max length** — 20 chars stored; regex caps at 15 digits + `+` (**OBSERVED**).

## 9. Campaign Audience Semantics

**OBSERVED** — exactly how Campaign chooses contacts today:

- `CampaignEntity.contactGroupId` — single, **nullable** UUID reference ("Reference into
  the future Contact module"; `campaigns.contact_group_id UUID` with plain index
  `idx_campaigns_contact_group`, **no FK** — verified: `campaigns` has only
  `campaigns_tenant_id_fkey` → tenants). One group per campaign; multiple groups not
  supported; no-group campaigns are creatable.
- **Selection timing** — contacts are selected **at execution start**, not at execution
  creation and not at dial time: `CampaignExecutionOrchestrator.createInitialAttempts`
  (called from `startExecution`) runs
  `contactRepository.findByContactGroupIdAndDeletedAtIsNull(config.contactGroupId())` —
  the **full live list of the group** loaded into memory.
- The group id comes from the execution's **VB-6A configuration snapshot**
  (`runtimeConfigResolver.resolve(execution).contactGroupId()`), but the **membership is
  read dynamically** — only the reference is frozen, never the contact set.
- **Group membership change after execution starts** — contacts added later: **not
  called** (list read once at start). Contacts soft-deleted later: already-created
  attempts still dial them — `OutboundDialService.buildDestinationNumber` uses
  `findByIdAndDeletedAtIsNull` (deleted contact → ResourceNotFoundException → requeue via
  OutboundDialException catch path... actually it propagates as a RuntimeException inside
  `processAttempt`'s try? No — **OBSERVED**: `buildDestinationNumber` is called before the
  try block that catches `OutboundDialException`; a deleted contact mid-execution would
  throw `ResourceNotFoundException` uncaught from `processAttempt`, aborting the whole
  `processDueAttempts` batch). This is a **defect** (documented, not fixed): a soft-deleted
  contact between attempt creation and dial crashes the dial batch loop for all tenants'
  due attempts in that tick. Retry path is protected —
  `isContactStillValid(contactId, tenantId, groupId)` re-checks group membership + tenant
  + liveness before creating a retry.
- **Contacts snapshotted?** No. **Dynamically evaluated?** Membership once at start;
  eligibility per dial.
- **Dial-time re-check** — `CallEligibilityService.isNumberInCampaignContactGroup`
  (`existsByContactGroupIdAndPhoneNumberAndDeletedAtIsNull`) re-validates membership per
  dial unless whitelist mode is on (whitelist replaces group-membership targeting).

**The three-layer distinction the brief demands** (**INFERRED**, matching VB-6A patterns):
- *Campaign audience configuration* = `campaigns.contact_group_id` (live, editable while
  DRAFT).
- *Execution audience snapshot* = **currently only the group reference** inside
  `campaign_execution_configurations.contact_group_id` (VB-6A) — membership NOT frozen.
- *Current contact eligibility* = dial-time stack (`VoiceEligibilityService` blocklists/DNC
  → whitelist-or-group-membership → DID/gateway checks).

## 10. Execution / Attempt Contact Semantics

**OBSERVED:**

- `CampaignExecution` — **no contact reference** (only the snapshot's group id via
  `configuration_snapshot_id`). Correct: an execution targets an audience, not a contact.
- `CallAttempt` — **`contact_id UUID NOT NULL` present** (V22), denormalized
  `campaign_id`, `tenant_id`, `did_id`; `attempt_number`; unique
  `(execution_id, contact_id, attempt_number) WHERE deleted_at IS NULL`
  (`uq_call_attempts_execution_contact_attempt`).
- **`CallSession` — NO `contactId`** (verified: entity has no field; live DB
  `information_schema` count=0 for `call_sessions.contact_id`). It carries
  `call_attempt_id`, `campaign_execution_id`, `destination_number`.
- `CallLeg` — no contact reference (customer leg carries `target` = E.164 string only).
- **`idx_call_attempts_contact` EXISTS** (V22 line 69; confirmed in live DB) — the
  previous audit's "no standalone contact_id index" finding is **stale/incorrect**.
- `call_attempts.contact_id` has **no FK** (V22: `contact_id UUID NOT NULL` with no
  REFERENCES; verified live DB FK list: only campaign/execution/tenant FKs) — deliberate
  loose reference, consistent with the codebase's UUID-reference style.

**Where contact identity should live going forward** (**RECOMMENDED**, from lifecycle
reasoning — not implemented):

- `CallAttempt` — already correct (per-attempt targeting).
- `CallSession` — **adding `contact_id` would be denormalization, not identity**: the
  canonical chain is already complete: `CallSession.call_attempt_id → CallAttempt.contact_id`.
  For non-campaign call types (inbound, agent-outbound) there is no Contact at all, so a
  nullable `contact_id` on CallSession would be a convenience join-avoidance column, not a
  correctness need. **OPEN DECISION** (defer; measure whether history queries justify it).
- `CallExecution`/`CampaignExecution` — no per-contact identity belongs here; if exact
  audience freezing is chosen (§18) it lands as a separate membership table, not a column.
- `CallLeg` — no; legs are channel-scoped.

**Future query chain** (how "Contact → history" resolves today — **INFERRED**):
`contacts.id → call_attempts (contact_id, indexed) → call_attempts.provider_call_id /
call_session_id? → call_sessions (via call_attempt_id, indexed idx_call_sessions_call_attempt_id)
→ legs`. All hops indexed; no missing index for this chain. **Gap**: contact history spans
per-execution only via attempt rows; "which executions touched this contact" requires
join through attempts (no execution_id+contact composite index — attempts are naturally
bounded per execution, so full scans are unlikely; classify LOW).

**Missing index for scale**: none identified on the current access paths except the
composite noted in §28 (`tenant_id, phone_number` would be needed **if** tenant-level
phone identity is introduced).

## 11. Current Import Pipeline

**OBSERVED** (`ContactGroupService.importContacts` + readers + controller docs):

| Aspect | Current behavior |
|---|---|
| Formats | `.csv` (Commons CSV), `.xlsx` (Apache POI), `.json` (array of row objects) — `CsvContactImportReader` / `XlsxContactImportReader` / `JsonContactImportReader` behind `ContactImportReader` SPI, selected by extension |
| Max rows | **5000** (`MAX_IMPORT_ROWS=5000`) — file with more rows → 400 |
| Max file size | **5 MB** (`spring.servlet.multipart.max-file-size: 5MB`, `max-request-size: 6MB` in `application-dev.yaml` — documented "bulk-contact import limits (Phase 2J.1)") |
| Sync/async | **Fully synchronous** inside the HTTP request |
| Transaction | **One `@Transactional` method** = one DB transaction for the whole import (valid rows persist together via `saveAll`) |
| Batch size | Single `saveAll(batch)` of up to 5000 entities (Hibernate batching not configured — INFERRED: N inserts through the persistence context) |
| Dedup | In-file (`seenPhones` set) + against group (`findLivePhoneNumbers` single batched lookup) → `DUPLICATE_PHONE` per-row errors, rows skipped |
| Invalid rows | Per-row errors: `INVALID_E164`, `INVALID_EMAIL`, `MALFORMED_ATTRIBUTES`; max **100** reported (`MAX_REPORTED_ERRORS`) |
| Partial success | Yes — valid rows import, invalid/duplicate rows reported in `ContactImportResponse{total, created, failed, duplicates, errors, errorList}` |
| Retry/idempotency | None — re-uploading the same file yields all-`DUPLICATE_PHONE` (rows against live contacts are skipped); no import record is persisted |
| Tenant/group ownership | From the **authorized group only**; file ownership columns ignored |
| File storage | None — streamed from multipart, never persisted; no temp files, no cleanup needed |
| Audit records | None (no import entity/log row; only contact audit columns) |
| Import status/progress | None — single synchronous response |
| Memory behavior | All rows parsed into a `List<ContactRowData>` **plus** all entities in memory (**INFERRED**: ~2× row payload; bounded by 5000-row cap) |
| Async jobs | **None exist** — only `@Scheduled` pollers (orchestrator tick, ESL, DTMF timeout, ACD, agent reconcile, security cleanup); no `@EnableAsync`, no job framework |

## 12. Large Audience / Scale

Theoretical capacity of the current model at 50-lakh (5M) numbers (**INFERRED** from
observed structures; nothing measured beyond schema/indexes):

| Area | Bottleneck | Severity |
|---|---|---|
| Import | Synchronous single-transaction 5000-row cap; multipart 5 MB | **HIGH** for >5k audiences (hard wall today), CRITICAL only for the millions goal |
| Import | One transaction for 5k rows holds locks + WAL for the request duration | MEDIUM |
| Attempt creation | `createInitialAttempts` loads **all** contacts into a `List<ContactEntity>` and saves one attempt per row in one transaction (`@Transactional startExecution`) — at 1M contacts this is OOM/timeout territory | **CRITICAL** for large audiences |
| Attempt creation | Per-row `existsByExecutionIdAndContactIdAndAttemptNumber...` check inside the loop (N queries; idempotency re-run is rare, so cost lands on first run too) | MEDIUM |
| Contact selection query | `findByContactGroupIdAndDeletedAtIsNull` — covered by `idx_contacts_group_deleted`; returns unbounded list (no pagination/cursor) | **HIGH** at scale |
| Dial loop | `processDueAttempts` loads ALL due QUEUED attempts unbounded and processes serially in one transaction | **HIGH** at scale (not contact-domain-specific but audience-driven) |
| Membership model | `contacts.contact_group_id` column — reads are index-covered; a join table would add no capability the product needs today | LOW |
| Keyset/cursor pagination | Not present anywhere in contact paths (offset pagination only for API listing) | MEDIUM (future) |
| Dedup at import | In-memory `HashSet` of live phones (5k) + `findLivePhoneNumbers` loads all live phone strings of the group into memory | MEDIUM (grows with group size) |
| Dial-time eligibility | `existsByContactGroupIdAndPhoneNumber...` per dial — index-covered (`idx_contacts_group_deleted` + unique index) | LOW |
| Hibernate fetch | No associations on ContactEntity (pure columns) — no N+1 from relations | LOW |
| Execution reconciliation | `reconcileExecution` loads all attempts of the execution | MEDIUM |

**Conclusion**: the *schema* can hold millions of contact rows (indexes are right);
the *import* and *attempt-creation* code paths cannot process them synchronously. VB-6B
should fix the model/identity layer and defer batch pipeline redesign to the sequence in
§33; do not build distributed infrastructure (§36).

## 13. Inline Numbers

**OBSERVED** — current support level:

- Campaign cannot contain numbers directly (no inline field, no audience JSONB —
  `CampaignEntity` has none; the only audience reference is `contactGroupId`).
- Reusable pieces that exist: `ContactValidation.canonicalizePhoneNumber` (dedup-quality
  canonicalization), `uq_contacts_group_phone_live` (DB dedup), the import pipeline
  (in-file dedup + reporting), `ContactGroupService.createContact` (single insert).
- Inline contacts *could* be converted into reusable Contacts today with zero new
  infrastructure (create-or-get into a group).

**Recommendation** (**RECOMMENDED** based on observed repo): inline/pasted numbers should
**create/reuse real Contact entities inside a real, reusable ContactGroup** — not a
temporary/campaign-scoped audience. Rationale from the repository: (a) groups are already
reusable audience containers with tenant-safe CRUD; (b) attempts/eligibility/history all
key off `contact_id`, which only exists for real Contact rows; (c) the VB-6A snapshot
model already snapshots `contactGroupId`, so an inline audience expressed as a group needs
**no execution-path change at all**; (d) a "temporary audience" concept would fork the
eligibility and snapshot semantics for zero product gain. Create-or-get semantics +
`AudienceSource`-style provenance labeling can be layered on the group later (§19).

## 14. Deduplication

Current boundary, answered from the domain (**OBSERVED** except where noted):

| Question | Current answer |
|---|---|
| Same tenant + same phone → same Contact? | **No** — only same **group** + phone collapses. Two groups = two rows. |
| Same tenant + different group → same Contact? | No (separate rows). |
| Different tenants + same phone | Separate rows, always (tenant is the hard boundary). |
| Same Contact in multiple groups | **Not supported** (single group column; no join entity). |
| Import same number twice | In-file second occurrence → `DUPLICATE_PHONE` skipped+reported; second *upload* → all rows `DUPLICATE_PHONE` against live contacts; after soft-delete, the number is reusable (partial unique index). |
| Inline number + existing Contact (future) | **OPEN DECISION** — create-or-get per group is the natural fit for today's model; tenant-level create-or-get requires the identity decision in §32. |

**No decision here can be made from generic best practice** — the tenant-vs-group identity
question is the pivotal **OPEN DESIGN DECISION** for VB-6B (§32, decision 1/2/9).

## 15. Contact Lifecycle

**OBSERVED**: the only lifecycle state is liveness (`deleted_at IS NULL`). There is **no**
ACTIVE/INACTIVE/BLOCKED/DND/invalid/unreachable state on Contact. `attributes` JSONB is
the only extension point.

DND/compliance placement (**OBSERVED**): compliance is **runtime, dial-time, phone-list
based** — `VoiceEligibilityService` checks PLATFORM_BLOCKLIST / PLATFORM_PROTECTED /
RESELLER_BLOCKLIST / TENANT_BLOCKLIST(DNC) / whitelist against `phone_list_entries`
(`PhoneListType`, `ScopeType`), per precedence documented in its Javadoc — **not** a
Contact state, not a campaign rule, not a provider result. This separation is correct and
must be preserved: the same number may be callable for one tenant and blocked for another;
a contact row cannot express that. **Do not move compliance into Contact** (brief §17
answered: the audit proves it belongs where it is).

## 16. Contact History

**OBSERVED** data available for future history queries:

| Future question | Answerable today? | Path |
|---|---|---|
| "How many times was this contact called today?" | Yes, in principle | `call_attempts WHERE contact_id=? AND started_at/completed_at >= today` — but "called" needs a definition (§17); `idx_call_attempts_contact` exists; no `(contact_id, completed_at)` composite → date-bounded queries scan the contact's full attempt set (fine per contact; LOW) |
| "Which campaigns called this contact?" | Yes | `call_attempts.contact_id → campaign_id` (denormalized column, indexed) |
| "What was the last provider result?" | Yes | attempts ordered by attempt_number/completed_at → `failure_code`, `provider_call_id` |
| "What happened on the previous attempt?" | Yes | `uq_call_attempts_execution_contact_attempt` ordering by attempt_number within execution |
| "Which execution produced this call?" | Yes | `call_attempts.execution_id`; `call_sessions.campaign_execution_id` |

**Gaps** (INFERRED): `CallSession.contactId` absent (chain requires the attempt hop —
acceptable, §10); attempts whose dial never happened (capacity requeue) still leave
QUEUED/FAILED rows, so "attempt rows" ≠ "calls made" — the VB-6C counting definition must
choose a status/provider marker (§17). No contact-history API exists. The model **can**
support history cleanly — `contact_id` on attempts is stable across contact soft-deletes
(no cascade), and reports can still resolve the attempt rows even after the contact is
deleted (contact name would require reading the soft-deleted row — **OPEN DECISION** on
whether history APIs may read soft-deleted contacts).

## 17. Future Daily-Limit Dependency

VB-6C will enforce "at most 3 calls/day per contact globally, campaign may be stricter
(effective = min)". The audit's job: identify the exact boundary. (**RECOMMENDED**,
consistent with the previous audit and verified against the current architecture:)

- **Count unit: provider-accepted dial** — i.e. an attempt that reached
  `OutboundDialService` case `DIAL_REQUEST_ACCEPTED` (attempt.status IN_PROGRESS with
  non-null `provider_call_id`, session row created). This excludes: attempt-row creation
  (QUEUED), capacity requeue (requeue path), compliance rejection (`markFailed` before
  dialing), scheduler requeue. The architecture supports this cleanly (**OBSERVED**): the
  accepted branch is a single well-defined point that already persists
  `providerCallId` on the attempt and creates the session.
- **Query shape VB-6C will need**: count of accepted dials for `contact_id` (via
  attempts) within a UTC/calendar-day window, tenant-scoped. The existing
  `idx_call_attempts_contact` supports it; a `(contact_id, status/created_at)` composite
  index is the likely future additive migration (documented, not created).
- **Enforcement point**: `OutboundDialService.processAttempt`, after eligibility, before
  routing/reservation — the same place compliance runs, so requeues and rejections never
  consume quota. Global-vs-campaign min() composition is VB-6C policy.
- **What VB-6B must establish** (and the current model already does): stable
  `CallAttempt.contactId` + liveness semantics + the attempt lifecycle that marks the
  accepted-dial point. Nothing else is needed from VB-6B for VB-6C's data model.

## 18. Execution Audience Snapshot Analysis

The VB-6A decision to freeze only `contactGroupId` (the reference) in
`campaign_execution_configurations` is **OBSERVED**. Comparison for VB-6B:

| Criterion | A: group ref, dynamic membership (current) | B: exact contact-set snapshot in config JSON | C: execution-audience membership table | D: copy contacts into per-execution rows |
|---|---|---|---|---|
| Correctness (reproducible audience) | Weak — membership edits mid-execution change nothing after start (list read once) but add/delete between `execute()` and `startExecution()` changes the audience silently | Strong | Strong | Strong |
| Scalability | **Best** — one UUID | Poor — millions of UUIDs in JSONB | Good if bounded; index-heavy at millions | Poor — row explosion |
| DB cost | None beyond existing | Huge payloads | 1 row/contact/execution | 1 full copy/contact/execution |
| Retry behavior | Retry resolves contact by id + live membership check (`isContactStillValid`) | Retry inside frozen set | Retry inside frozen set | Retry inside frozen set |
| Campaign/group edits | Safe (reference frozen; group edits affect only pre-start window) | Safe | Safe | Safe |
| Contact deleted mid-execution | Attempts still hold contact_id; dial path defect noted in §9 | Frozen set still references deleted contact — must still resolve deleted rows for dialing | Same issue | Copy keeps the number |
| Millions of contacts | Works (no copying) | **Fails** | Works but heavy | **Fails** |
| Scheduler behavior | Unchanged | Unchanged | Attempt creation joins membership | Attempt creation scans copies |
| Daily-limit integration (VB-6C) | Unaffected | Unaffected | Unaffected | Unaffected |
| Tenant isolation | Group-tenant check + attempt tenant | Set embedded in tenant-owned snapshot | Membership table needs tenant_id + scoping discipline (new leak surface) | Copies inherit tenant |
| Implementation complexity | Zero (today) | Medium (and a JSONB-size hazard) | High (new table, new lifecycle, reconciliation) | High |

**Recommendation** (**RECOMMENDED**): keep **Model A** (freeze the *reference* only;
VB-6A already does this), and treat the pre-start window as the defined semantics: the
audience = live membership **at execution start** (`startExecution` →
`createInitialAttempts`), which is already the de-facto behavior. Rationale from this
repository: attempts are created once from the group and never re-derived (adding contacts
mid-execution has no effect today — preserving that exactness requires no new machinery);
Model C's membership table adds a new tenant-isolation surface and reconciliation burden
for a product rule nobody has stated; Model B is unshippable at the stated scale. The one
genuine gap to fix in VB-6B is the deleted-contact dial defect (§9/§31), which is a
null-guard fix, not a model change. **What must stay dynamic at dial time**: eligibility
(compliance, DID validity, capacity) — unchanged from VB-6A's snapshot-vs-resource rule.

## 19. Audience Source Analysis

`CONTACT_GROUP / INLINE / FILE_IMPORT` (brief §3/§21): **none exist in code** (§3).

**Recommendation** (**RECOMMENDED** after inspecting how the project models similar
concepts): do **not** build an `Audience` entity. The project's established patterns are:
single enum columns (`CampaignType`, `ContentMode`), sealed typed configs
(`CampaignTypeConfig`), UUID references to owning modules (DID/audio/TTS/group), and
`@Embeddable` payload snapshots (VB-6A). The product's three sources are all expressible
as **provenance of a ContactGroup**: INLINE and FILE_IMPORT both materialize as contacts
inside a (possibly system-created) group; CONTACT_GROUP is a user-managed group. If
provenance matters (UI labeling, "imported" vs "manual" groups), the smallest evolution is
an additive `source` enum column on `contact_groups` (EXTEND, one migration) — not a
sealed type hierarchy or a new aggregate. An `AudienceSource` enum on the *campaign* is
not needed: the campaign keeps referencing a group either way. **OPEN DECISION**: whether
provenance is product-required at all (§32, decision 3/4).

## 20. File Import Architecture

**OBSERVED platform capabilities:**

- **File storage**: only audio asset storage (`audio_assets.storage_reference`, local/
  logical-path based, `AudioUploadValidator` 5 MB cap). No generic file/object-storage
  abstraction exists.
- **Upload metadata**: multipart handling exists (contact import, audio upload) but no
  persisted upload entity for contacts.
- **Async/jobs**: **none** — no `@EnableAsync`, no job framework, no queue. Only
  `@Scheduled` pollers (`CampaignExecutionOrchestrator.scheduledTick` 30s, ESL/DTMF/ACD/
  agent schedulers). These pollers are the only in-process batch pattern available.
- **Batch processing**: the 5000-row transactional import is the only batch precedent.
- **Progress tracking / failure reporting**: per-row error list in the import response;
  no persisted import record, no progress surface.

**Conclusion**: there is **no reusable file/import abstraction**. For future large
imports the required NEW capabilities (document only, §33 sequence): persisted import-job
record (status/progress/error report), chunked/asynchronous processing (the scheduled-
poller pattern is the in-house precedent, or Spring `@Async` + worker table), and a
file-storage reference for the uploaded file. **Do not** introduce S3/Redis/Kafka — the
poller+table pattern used by ESL/DTMF schedulers is sufficient and consistent with the
platform principles (§36).

## 21. API Inventory

**OBSERVED** — `ContactGroupController` (`/api/v1/contact-groups`), all JSON envelope
unless noted; capability in parentheses:

| Method & path | Auth | Scope | Request → Response | Notes |
|---|---|---|---|---|
| POST `` (CONTACT_MANAGE) | bearer | context tenant (required) | `CreateContactGroupRequest` → 201 `ContactGroupResponse` | platform callers rejected |
| GET `` (CONTACT_VIEW) | bearer | tenant / reseller hierarchy / platform | page+sort(allowlist)+search → page | soft-deleted excluded |
| GET `/{id}` | bearer | scoped 404-safe | → `ContactGroupResponse` | |
| PUT `/{id}` | bearer | scoped | `UpdateContactGroupRequest` → 200 | name/description only |
| DELETE `/{id}` | bearer | scoped | → 204; **409 if live contacts exist** | soft delete |
| POST `/{id}/contacts` | bearer | group-scoped | `CreateContactRequest` → 201 | **no dedup check** (DB unique → 500 on duplicate — §7 finding 2) |
| GET `/{id}/contacts` | bearer | group-scoped | page+sort+search → page | offset pagination |
| GET `/{id}/contacts/{contactId}` | bearer | group-scoped 404-safe | → `ContactResponse` | |
| PUT `/{id}/contacts/{contactId}` | bearer | group-scoped | `UpdateContactRequest` → 200 | phone editable (E.164 pattern); **no dedup check on update** (same 500 hazard) |
| DELETE `/{id}/contacts/{contactId}` | bearer | group-scoped | → 204 | soft delete |
| POST `/{id}/contacts/import` (multipart) | bearer | group-scoped | file → `ContactImportResponse` | 5 MB/5000 rows; per-row errors; sync single txn |
| GET `/{id}/contacts/export` | bearer | group-scoped | `format=csv|xlsx|json` → raw bytes (not envelope) | in-memory serialization of all contacts (MEDIUM scale risk) |

Campaign-side audience/execution endpoints (`CampaignController`, `/api/v1/campaigns`):
POST ``/GET ``/GET `/{id}`/PUT `/{id}`/DELETE `/{id}`/PATCH `/{id}/status`/POST
`/{id}/clone`/GET `/{id}/readiness`/POST `/{id}/executions`/GET `/{campaignId}/executions[/{executionId}]`/
POST+GET `.../executions/{executionId}/attempts[/{attemptId}]` — audience enters only via
`contactGroupId` in create/update DTOs; no audience-specific endpoint exists.

**Classifications** (**RECOMMENDED**): contact/group CRUD = **REUSE**; import = **EXTEND**
(add idempotency/async later); single contact create/update = **REFACTOR** (dedup +
canonicalization + typed 409); export = **EXTEND** (streaming later); campaign audience
field = **EXTEND** (if multi-group/inline ever needed); no NEW endpoints required by the
identity work.

## 22. Authorization / Security

**OBSERVED**: tenant isolation is consistent (§7). IDOR posture: scoped lookups make
foreign/nonexistent indistinguishable at group, contact (via group), campaign, and
execution levels. Findings (documented, not fixed):

| Finding | Severity | Evidence |
|---|---|---|
| Dial-path contact read lacks tenant predicate (`OutboundDialService.buildDestinationNumber` → `findByIdAndDeletedAtIsNull`) | LOW (defense-in-depth; input is server-derived) | §7.1 |
| Duplicate contact on single create/update → unhandled `DataIntegrityViolationException` (500, leaks nothing but is untyped) | MEDIUM (API quality) | §7.2; `createContact`/`updateContact` have no `existsBy...` guard |
| Import error list truncation (`MAX_REPORTED_ERRORS=100`) without a "truncated" flag | LOW (UX) | `ContactGroupService.addError` |
| `findLivePhoneNumbers` loads all live phones of a group into memory | LOW (perf at scale) | §12 |
| Campaign→group and group→contact tenant invariants hold transitively (group tenant = contact tenant by construction) | — | V17 header; mapper derives from group |

No reseller cross-tenant leakage found: reseller listing uses active-hierarchy tenants;
single reads verify hierarchy membership; capability checks run against the **resource's**
tenant, never the caller's claim.

## 23. Modulith / Architecture

**OBSERVED**: `ArchitectureTest` passes (0 cycles). Module directions involving contact:

- `campaign → contact` (orchestrator + dial service import `ContactEntity/ContactRepository`;
  `CampaignReadinessService` uses `ContactGroupRepository`).
- `telephony → campaign` (`CallEligibilityService implements campaign-owned `CallEligibility`)
  and `telephony → contact` (membership query) — the established, allowed direction.
- `contact` imports nothing from campaign/voice/telephony (checked `ContactGroupService`
  imports: authz, common, dto, security, tenant, POI/CSV only) — contact is a leaf.

**VB-6B dependency direction requirement** (**INFERRED**): keep `contact` a leaf; campaign
may keep consuming contact repositories, but audience *selection* logic (if it grows)
should enter as a campaign-owned interface implemented by contact or as a contact-owned
service consumed by campaign — matching the `CallEligibility` precedent. No campaign ↔
contact cycle; no contact ↔ telephony cycle exists or is needed. A dedicated port is
**not** currently needed (document only if multi-source audiences land, §19).

## 24. Database / Migration Analysis

**OBSERVED** (migrations + live dev DB):

- **V17__create_contact_domain.sql** — `contact_groups` (id PK default gen_random_uuid(),
  tenant_id FK→tenants, name, description, audit cols; `idx_contact_groups_tenant_deleted`);
  `contacts` (id PK, tenant_id FK→tenants, contact_group_id **FK→contact_groups**,
  first_name NOT NULL [dropped in V18], last_name, phone_number VARCHAR(20) NOT NULL
  **CHECK `^\+[1-9][0-9]{6,14}$`**, email, attributes JSONB, audit cols;
  `idx_contacts_group_deleted (contact_group_id, deleted_at)`,
  `idx_contacts_tenant_deleted (tenant_id, deleted_at)`).
- **V18__contact_optional_first_name.sql** — `ALTER COLUMN first_name DROP NOT NULL`.
- **V19__contact_dedup_identity.sql** — partial unique index
  `uq_contacts_group_phone_live ON contacts(contact_group_id, phone_number) WHERE deleted_at IS NULL`.
- **V14__rebuild_campaign_domain.sql** — `campaigns.contact_group_id UUID` (nullable, **no
  FK**), `idx_campaigns_contact_group`.
- **V22__create_call_attempt.sql** — `call_attempts.contact_id UUID NOT NULL` (**no FK**),
  `idx_call_attempts_contact`, `uq_call_attempts_execution_contact_attempt
  (execution_id, contact_id, attempt_number) WHERE deleted_at IS NULL` + other indexes
  (tenant_deleted, execution, campaign, status, scheduled, did).
- **V44__campaign_execution_configurations.sql** — snapshot embeddable includes
  `contact_group_id UUID` (nullable) — the audience reference frozen per execution.
- **call_sessions** — no contact column (any migration: none; live DB verified).

Relationship taxonomy: Campaign→Group = plain UUID reference + index (no FK);
Group→Contact = direct FK (`contacts.contact_group_id REFERENCES contact_groups`);
Attempt→Contact = plain UUID, no FK, indexed; Session→Contact = **absent** (chain via
`call_attempt_id`); membership = group-scoped column, not a join table; no duplication of
contact data outside `contacts` (destination_number on session/leg is a dial artifact,
not identity duplication).

**VB-6B migration needs** (prediction, none created): additive only — likely (a)
tenant-level identity support (e.g. `uq_contacts_tenant_phone_live` or a contact-identity
restructure per §32 decision 1), (b) optional `source` column on `contact_groups`, (c)
optional `(contact_id, created_at)` composite on attempts for VB-6C. All additive;
schema can evolve safely (dev DB holds 129 contacts/12 groups of seed data; repo has zero
commits so even rewrites are permissible per the VB-6A §18 precedent — but additive is
still the default).

## 25. Test Coverage

**OBSERVED** inventory (test methods listed in evidence pass):

| Area | Suites | Verdict |
|---|---|---|
| Import behavior (CSV/JSON/XLSX variants, canonicalization collapse, soft-delete reuse, file-level 400s, foreign-tenant import block) | `ContactImportServiceTest` (8 tests) | **STRONG** |
| Ownership/isolation (tenant inheritance, foreign group 404, reseller hierarchy visibility, non-empty-group delete 409, out-of-group contact 404) | `ContactOwnershipServiceTest` (5 tests) | **STRONG** |
| Campaign↔group wiring (readiness reason CONTACT_GROUP_UNAVAILABLE, create/update validation) | `CampaignValidationServiceTest`, `CampaignResourceValidationPostgresIntegrationTest` (group exists-check path) | **PARTIAL** (no PG test of group FK/tenant mismatch on campaigns — group_id has no FK) |
| Execution contact selection (start → attempts for group; membership change after start) | indirect via orchestrator usage in lifecycle tests | **PARTIAL** — no dedicated test asserts "contacts added after start are not dialed" or "deleted contact mid-execution" behavior (the §9 defect is untested) |
| Attempt uniqueness/dedup | `uq_call_attempts_execution_contact_attempt` exercised in VB-1/VB-2 PG suites | **STRONG** |
| Dial-time group membership eligibility | `OutboundDialServiceRoutingTest`, `CallFailureCodeTest` NOT_IN_CAMPAIGN_TARGETS paths | **PARTIAL** (no cross-tenant number-in-group leak test) |
| Normalization | `ContactImportServiceTest.formattedPhoneVariantsCollapse...`; `PhoneNumberNormalizer` covered via eligibility tests | **PARTIAL** (no direct unit suite for `PhoneNumberNormalizer` edge cases; single-create path canonicalization gap untested) |
| Deduplication constraints at DB level | V19 index exercised via import tests (H2? no — PG where run) | **PARTIAL** — no PG test asserting the raw unique-index violation on concurrent inserts |
| Concurrency (import vs import; contact create race) | none | **MISSING** |
| Soft delete semantics (contact) | `softDeletedPhoneNumberIsReusable` | **STRONG** |
| Contact history / attempt-by-contact queries | none | **MISSING** (future VB-6C dependency) |

Missing tests to list for VB-6B implementation (not written now): single-create duplicate
→ typed 409; concurrent same-phone imports (PG); campaign group tenant-mismatch PG;
post-start membership-change semantics; deleted-contact dial behavior (currently buggy);
`PhoneNumberNormalizer` edge suite; reseller-scoped contact listing.

## 26. Concurrency

**OBSERVED** mechanisms per operation:

| Operation | Protection |
|---|---|
| Contact create (single) | DB partial unique index only (`uq_contacts_group_phone_live`) — no app-level pre-check → 500 on race/duplicate (§7.2) |
| Import | In-memory `seenPhones` + batched live-phone set **inside one transaction**; two concurrent imports of the same number: both pass the set check, one wins the index, loser gets `DataIntegrityViolationException` → whole import txn rolls back (INFERRED from `@Transactional` + unique index; no test) |
| Group membership creation | Same as contact create |
| Attempt creation | App-level idempotency pre-check (`existsByExecutionIdAndContactIdAndAttemptNumber...`) + DB unique index — double protection (**STRONG**) |
| Retry creation | Same as attempts |
| Audience selection | No locking; single-threaded read inside `startExecution` txn |
| Future daily-limit boundary | **No counter exists**; VB-6C will need its own concurrency story (advisory lock or conditional insert) — documented for §33, not built |
| Existing advisory locks | `VoiceCapacityServiceImpl`: `CHANNEL_LOCK_BASE = 0x100000000L` (gateway channel reservations), `CPS_LOCK_BASE = 0x200000000L` (CPS reservations), key = base + `gatewayId.hashCode()` (**OBSERVED** — these keyspaces are taken; a future contact/limit lockspace must pick an unused base, e.g. a new constant, and not collide) |

No application synchronization beyond Spring single-writer transaction defaults; no
pessimistic locks on contact rows.

## 27. Soft Delete / Retention

**OBSERVED**: contacts soft-deleted (deleted_at/by stamped); groups soft-deleted with
409-guard while live contacts exist; memberships soft-deleted implicitly with the contact
row; imports not retained (no record); execution history (attempts/sessions) never
cascade-deleted, so **history references to deleted contacts persist** and remain
queryable by `contact_id`. Deleted contact rows remain resolvable by id
(`findByIdAndDeletedAtIsNull` is explicitly *not* used for re-parenting but
`buildDestinationNumber` uses it — see §9 defect). Reports can still resolve attempt rows
for deleted contacts; resolving the *contact attributes* requires reading the soft-deleted
row (no API exposes that today). Deleted contacts' numbers are immediately reusable in
the same group (partial index). No GC/retention exists anywhere (**consistent with
platform state; none required now**).

## 28. Query / Index / Scale Analysis

**OBSERVED** index coverage for the important current queries:

| Query | Index | Verdict |
|---|---|---|
| `findByContactGroupIdAndDeletedAtIsNull` (audience selection, import dedup, export) | `idx_contacts_group_deleted (contact_group_id, deleted_at)` | indexed; unbounded result (§12) |
| `existsByContactGroupIdAndPhoneNumber...` (dial-time membership) | same index (leading column) | indexed |
| `findLivePhoneNumbers` (import) | same index | indexed; full-group materialization (MEDIUM) |
| Contact lookup by id (dial path) | PK | fine; missing tenant predicate (§7.1) |
| Contact lookup by phone (tenant scope) | **no such query/method exists** | would need `(tenant_id, phone_number, deleted_at)` if tenant identity lands (additive) |
| Attempts by contact (history) | `idx_call_attempts_contact` | indexed; date-window variant would benefit from composite (VB-6C) |
| Attempts due for dial | `idx_call_attempts_scheduled` + status | indexed; unbounded list (§12, HIGH at scale) |
| Sessions by attempt | `idx_call_sessions_call_attempt_id` | indexed |
| Attempts by execution | `idx_call_attempts_execution` + unique composite | indexed |
| Group membership by execution+contact+number | `uq_call_attempts_execution_contact_attempt` | indexed (idempotency checks) |

No full-table scans on current hot paths; the unbounded `List` fetches (contacts for
group, due attempts) are the genuine scale bottlenecks, not missing indexes. Offset
pagination exists only on API listing; no keyset support anywhere (future need, MEDIUM).

## 29. Classification Matrix

| Component | Current Responsibility | Classification | Reason | VB-6B Impact |
|---|---|---|---|---|
| `ContactEntity` | Callable number inside a group; JSONB attributes | **EXTEND** | Sound columns/audit; lacks tenant-level identity & source provenance | Add identity semantics + optional provenance |
| `ContactRepository` | Group-scoped lookups + import batch read | **EXTEND** | Needs tenant-scoped phone lookup & create-or-get support for inline | New query methods |
| `ContactGroupService` (contact half) | Contact CRUD + import/export | **REFACTOR** | Single-create/update lack dedup/canonicalization → 500s; import logic embedded in service | Extract/normalize dedup boundary |
| `ContactGroupController` | Group+contact REST surface | **REUSE** | Complete, documented, capability-gated | Reuse as-is; maybe additive source field |
| `ContactGroupEntity` / `Repository` | Tenant-owned audience container | **EXTEND** | Reusable segment already; optional `source` provenance | Additive column only if product needs it |
| `ContactGroupService` (group half) | Group CRUD + deletion guard | **REUSE** | Correct isolation + 409 semantics | Reuse |
| `CampaignEntity.contactGroupId` | Audience reference | **REUSE** | Nullable single reference + snapshot integration (VB-6A) | No change for Model A |
| `CampaignService` | Campaign config validation incl. group reference | **REUSE** | Group ownership invariant already enforced | Reuse |
| `CampaignExecution` | Execution + VB-6A snapshot ref | **REUSE** | Snapshot already carries contactGroupId | No change |
| `CampaignExecutionService` | Execution creation (snapshot-first) | **REUSE** | Audience not its concern | Reuse |
| `CampaignExecutionOrchestrator` | Start → audience selection → attempts; retries | **EXTEND** | Full-list in-memory creation won't scale; deleted-contact guard missing on initial dial path; batch/bounded creation needed eventually | Bounded paging + defect fix |
| `CallAttempt` | Per-contact attempt with denormalized refs | **REUSE** | contact_id + unique constraint + index correct | Reuse; optional composite index later |
| `CallSession` | Universal voice session | **REUSE** (optionally EXTEND) | History chain works via attempt hop | Add contact_id only if measured need (OPEN) |
| `OutboundDialService` | Due-attempt dialing + eligibility + session creation | **EXTEND** | Tenant predicate fix; future VB-6C limit hook point | Small fixes; no model change |
| `VoiceEligibilityService` | Compliance/whitelist/DID/gateway stack | **REUSE** | Correct dial-time separation | Reuse untouched |
| `CallEligibilityService` | Campaign targeting (group membership) | **REUSE** | Context-based, snapshot-aligned (VB-6A) | Reuse |
| `PhoneNumberNormalizer` (voice) | Dial-string normalization | **REUSE** | Voice-layer concern | Reuse |
| `ContactValidation` (contact) | E.164 contract + import canonicalizer | **EXTEND** | Declared single source; route single-create through it | Reuse everywhere contact input exists |
| `ContactImportReader` SPI + CSV/XLSX/JSON readers | Format parsing | **REUSE** | Clean SPI, correct separation | Reuse |
| Import pipeline (`importContacts`) | Sync 5000-row single-txn import | **EXTEND** (later **REFACTOR**) | Works for small/medium; no idempotency/persistence/async | Keep; add job record when async lands |
| Contact DTOs | API contracts | **REUSE** | E.164 pattern correct; create lacks normalization | Reuse; maybe canonicalize at service |
| V17/V18/V19 migrations | Contact schema | **REUSE** | Correct constraints; additive evolution possible | Build on top |
| V22 attempts schema | Attempt+contact identity | **REUSE** | Index present (prior audit wrong) | Reuse |
| `ContactImportServiceTest` / `ContactOwnershipServiceTest` | Import+isolation locks | **REUSE** | Strong coverage | Extend with new cases |
| AudienceSource / INLINE / FILE_IMPORT concepts | — | **NEW** (only if provenance is required) | Do not exist; expressible as group `source` column | Decide in §32 |

## 30. Dependency Graph

**CURRENT** (**OBSERVED** — produced from actual imports, not assumed):

```
CampaignService / CampaignReadinessService ──▶ ContactGroupRepository (contact)
CampaignExecutionOrchestrator ──▶ ContactRepository (contact) ──▶ [selection at start]
CampaignExecutionOrchestrator ──▶ OutboundDialService
OutboundDialService ──▶ ContactRepository, CallEligibility (campaign iface),
                         VoiceRoutingService, VoiceCapacityService, CallSession/Leg repos
telephony.CallEligibilityService (implements campaign.CallEligibility) ──▶ ContactRepository,
                         VoiceEligibilityService ──▶ PhoneListEntryRepository, VoiceRouting,
                         VoiceCapacityService ──▶ [advisory locks] ──▶ FreeSWITCH (via dialer)
CampaignExecution ──▶ campaign_execution_configurations (snapshot: contact_group_id ref)
Contact module ──▶ tenants, authz (leaf; imports nothing from campaign/voice/telephony)
```

**FUTURE VB-6B edges** (**RECOMMENDED**, not implemented): `ContactGroupService` gains
create-or-get + canonicalization reuse for inline sources; `contact_groups` may gain a
`source` column; campaign keeps its single reference (no new edge).

**FUTURE VB-6C edges**: `OutboundDialService.processAttempt` gains a daily-limit check
port (campaign-owned interface implemented in telephony/contact, following the
`CallEligibility` precedent) — one new edge, direction preserved.

## 31. Risks

| Risk | Severity | Evidence | Impact | Recommended Direction |
|---|---|---|---|---|
| Contact identity ambiguity (group-scoped vs tenant-scoped) | **HIGH** | §5/§14; no tenant-phone lookup; same number = many rows | Reuse/history features (VB-6C limits, suppression) need a tenant-level "this number" concept; choosing wrong forces a painful migration | Resolve OPEN DECISION 1 first; additive tenant-level uniqueness or identity mapping |
| Deleted-contact dial crash aborts dial batch | **HIGH** (defect) | `buildDestinationNumber` throws `ResourceNotFoundException` outside the dial try/catch; no liveness guard on initial dial | One stale attempt blocks other tenants' due attempts that tick; retry path unaffected | VB-6B.1 fix: skip/fail that attempt only (fail-closed per attempt) |
| Full in-memory audience load at start | **HIGH** at scale | `createInitialAttempts` loads all contacts; one txn | OOM/timeout for large audiences | Bounded/batched attempt creation (paging) in VB-6B hardening |
| Synchronous import ceiling (5k rows / 5 MB / 1 txn) | **HIGH** for growth, not today | §11 | 10k+ audiences impossible | Defer async job design (§33 later step) |
| Single-create duplicate → 500 | MEDIUM | §7.2 | API quality; concurrency hazard on import-vs-create races | Typed 409 via `existsBy...` + index |
| No import idempotency/record | MEDIUM | §11 | Client retries re-report all-duplicates; no audit trail | Import-job record (when async lands) |
| Missing composite index for tenant-phone / contact-date queries | MEDIUM (future) | §28 | Slow once identity/history features land | Additive indexes with the identity work |
| Due-attempt loader unbounded | MEDIUM | `findByStatusAndScheduledAtBefore...` | Dial batch latency grows | Bounded fetch (scheduler hardening) |
| `findLivePhoneNumbers` memory growth | LOW–MEDIUM | §12 | Import memory scales with group size | Chunked existence checks later |
| Attempt-row ≠ dial semantics for future limits | LOW now / HIGH if ignored | §16/§17 | VB-6C miscounts if it counts QUEUED rows | Fix definition: DIAL_REQUEST_ACCEPTED marker (documented) |
| Group `contact_group_id` has no FK on campaigns | LOW | V14 | Orphan reference possible (readiness already treats null/missing group as unavailable) | Leave (consistent UUID-reference style) |
| Ad-hoc advisory-lock keyspace collisions in future | LOW | §26 keyspace values taken | New locks must not collide | New dedicated base constant when needed |

## 32. Open Design Decisions

Decisions that must be resolved **before** VB-6B implementation (not silently resolved
here):

1. **Contact identity** — (a) keep group-scope identity and add a separate tenant-level
   uniqueness (`tenant_id, phone_number` live-unique) making one contact per number per
   tenant with group membership join? (b) keep one-row-per-group and treat "tenant
   identity" as a query convention? (c) introduce a person/multi-number model?
   *Recommendation*: (a)-lite or (b) per product need; **decide** — everything else
   (inline, dedup, limits, suppression) inherits from it.
2. **Contact ↔ group relationship** — stay one-group-per-contact (move = delete+recreate)
   or introduce membership join for multi-group? Current model says single; product
   hasn't asked for multi-group. *Recommendation*: stay single-group now.
3. **Audience ownership** — campaign references group (current), or separate Audience
   aggregate? *Recommendation*: keep group-as-audience; add `source` provenance only if
   product requires the distinction.
4. **Inline numbers** — create/reuse Contacts in a real (possibly auto-created) group vs
   temporary audience. *Recommendation*: real group + create-or-get (§13).
5. **File import threshold** — at what size does import become async (rows? bytes?) and
   what is the UX (202 + job status)? Not needed for the identity phase; decide before
   the import-hardening step.
6. **Execution audience freezing** — Model A confirmed (§18)? If product later demands
   exact reproducibility for audits, Model C is the fallback; decide explicitly that A is
   the accepted semantic ("audience = live membership at start").
7. **Contact history reads** — may history APIs resolve soft-deleted contacts (show
   number/name after deletion)? Affects reporting privacy posture. Decide with reporting.
8. **Deleted contacts in active audiences** — a soft-deleted contact's pending attempts:
   skip (current effective behavior via eligibility for retries; **broken** for initial
   dial per §31) or complete? VB-6B must pick the behavior when fixing the defect.
9. **Deduplication boundary** — tenant-level, group-level, or both (unique per group +
   canonical tenant lookup)? Follows from decision 1.
10. **Multi-number contacts** — one Contact = one phone (current) vs person with numbers.
    No current pressure; default stay.
11. **Import idempotency** — client-supplied import key vs natural (group+file-hash)
    dedup? Decide when import record lands.
12. **Large-file processing** — poller-worker table pattern vs `@Async`; decide in the
    import step, not before.
13. **Audience changes while execution running** — confirm "no effect" (current behavior)
    as the documented contract.
14. **Group changes while campaign scheduled** — allowed today (groups are mutable;
    campaign holds a reference). Confirm no lock-down needed.
15. **Contact deletion while execution running** — same as decision 8; ties to the defect.
16. **VB-6C counting boundary** — confirm DIAL_REQUEST_ACCEPTED as the counted event
    (§17) so VB-6B adds nothing that contradicts it.

## 33. Recommended VB-6B Implementation Sequence

Dependency-driven, adjusted to what the repository actually needs (each step depends on
the previous; none started):

- **VB-6B.1 — Contact identity + dial-path correctness** (foundation; unblocks 2–5):
  resolve OPEN DECISION 1/9; additive migration (tenant-level uniqueness or identity
  convention); route single create/update through `canonicalizePhoneNumber` + typed 409
  dedup; **fix the deleted-contact dial defect** (per-attempt fail-closed); tenant
  predicate on the dial-path contact read. Rationale: every later feature keys off
  identity; the defect is a correctness bug in the live dial loop.
- **VB-6B.2 — Group/membership semantics made explicit** (depends on 1): document/lock
  the "audience = live membership at start" contract (test it), keep single-group model,
  confirm 409 deletion guard, add PG tests for concurrent same-phone inserts (V19 index)
  and campaign-group tenant mismatch.
- **VB-6B.3 — Inline audience (create-or-get)** (depends on 1+2): one service method
  (`createOrGetContact`) + campaign-facing convenience: paste-numbers → group
  create-or-get + contacts create-or-get; reuses canonicalization and the unique index;
  additive API only.
- **VB-6B.4 — Import hardening (small/medium)** (depends on 1; independent of 3):
  import-job record (idempotency + audit), typed partial-failure contract preserved,
  memory-lean dedup (chunked existence checks), keep synchronous 5000 cap.
- **VB-6B.5 — Bounded/batched attempt creation** (depends on 2; needed before >5k
  audiences are realistic): page the group for attempt creation, batch inserts, keep the
  unique index as the idempotency authority; optionally bound the due-attempt fetch.
- **VB-6B.6 — Async large import** (depends on 4+5, and OPEN DECISION 5/12): poller+table
  job pattern (in-house precedent), file reference storage, progress/status endpoint.
  **Only if** product confirms the scale requirement now.
- **VB-6B.7 — History/indexing groundwork** (depends on 1): composite
  `(contact_id, <time/status>)` index; read-model queries for contact history; no VB-6C
  counters.
- **VB-6B.8 — Hardening** (last): concurrency tests, reseller-scope contact listing
  tests, normalizer edge suite, classification-matrix updates, docs.

Not sequenced (correctly out of scope): daily limits (VB-6C), Redis/Kafka/S3, scheduler
distribution, MISSED_CALL, IVR, webhooks.

## 34. Explicit Non-Goals

Not implemented (and not to be "prepared" beyond documented dependencies): daily contact
limits (global 3/day, campaign overrides) — VB-6C; retry-policy redesign;
SWITCHED_OFF/NOT_REACHABLE detection; IVR; MISSED_CALL; webhooks; report privacy; max call
duration; scheduler distribution/coordination; Redis; Kafka; Kubernetes; new FreeSWITCH
functionality; TTS runtime; provider integrations; AI agents; predictive/progressive/
preview dialing; multi-group membership model; person/multi-number model — all untouched.

## 35. Final Conclusion

The current contact model is a **correct, well-isolated, group-scoped phone container**
with strong import and ownership semantics, an enforced E.164 contract, and a live
deduplication index — but it is **not yet a reusable tenant-level contact identity**, and
the execution path treats audience selection as a one-shot full-memory read with two
confirmed defects (deleted-contact dial crash; untyped duplicate-contact 500s). The
audience concepts from the product discussion (`AudienceSource`, INLINE, FILE_IMPORT) do
not exist in code. The smallest clean evolution is: extend identity at the tenant level
(one decision), reuse the group as the audience container (no new aggregate), keep VB-6A's
reference-only execution snapshot (Model A), fix the two dial-path defects, and grow the
import path only as far as the product's actual scale requirements demand. Prior-audit
corrections: the `call_attempts` contact index **exists** (V22), and the "~5000-row
single-transaction synchronous import" figure is **exact** (5000 rows, 5 MB, one
`@Transactional` method).

### Actual Current State

`contacts` are group-scoped E.164 rows (V17–V19: tenant FK, group FK, CHECK'd phone,
live-partial-unique `(group, phone)`); CRUD+import+export live in `ContactGroupService`
behind `CONTACT_VIEW/MANAGE`; campaigns hold one nullable `contactGroupId` (no FK);
executions freeze only the group reference (VB-6A snapshot) and materialize attempts once
at start from the full live group list; compliance is dial-time phone-list evaluation,
not contact state; imports are synchronous/single-txn/5000-row/5 MB with per-row error
reporting and no job record; `CallAttempt.contactId` (indexed, unique per
execution+contact+number) is the only contact-identity hop into call history;
`CallSession` has no contact column.

### Critical Findings

1. Deleted-contact attempt crashes the dial batch (`buildDestinationNumber` NFE outside
   try/catch) — HIGH defect.
2. Contact identity is group-scoped only; no tenant-level phone lookup/uniqueness —
   blocks reuse/history/limits design.
3. Single contact create/update has no dedup/canonicalization → untyped 500 on
   duplicates.
4. Audience selection loads the whole group into memory in one transaction — does not
   scale past small/medium audiences.
5. Import is hard-capped (5000 rows/5 MB/1 txn), synchronous, with no idempotency record.
6. Prior-audit correction: `idx_call_attempts_contact` exists; `call_sessions.contact_id`
   confirmed absent; `AudienceSource/INLINE/FILE_IMPORT` confirmed absent.

### Blocking Design Decisions

§32 decisions 1 (identity), 2 (membership model), 6 (audience-freezing semantics A), 8/15
(deleted-contact behavior), 9 (dedup boundary) — 1 and 9 gate everything else; 6/8/15
gate the VB-6B.1 defect fix semantics.

### Recommended Implementation Boundary

VB-6B.1 (identity + dial-path correctness) → VB-6B.2 (membership contract + PG tests) →
VB-6B.3 (inline create-or-get) → VB-6B.4 (import hardening) as the core VB-6B; VB-6B.5–.8
(scale/history/hardening) as the follow-on, sequenced in §33.

### Deferred

Daily limits/counters and their concurrency story (VB-6C); async large-import
infrastructure beyond the job-record decision; multi-group membership; person/multi-number
model; retention/GC; keyset pagination; any new infrastructure (Redis/Kafka/S3/K8s).

### Final Status

    VB-6B CONTACT & AUDIENCE AUDIT — COMPLETE
    IMPLEMENTATION — NOT STARTED

## 36. VB-6B Handoff

**The next implementation prompt should build** (in this order):

1. **VB-6B.1 Contact identity & correctness**: tenant-level phone identity per resolved
   OPEN DECISION 1 (additive migration only); canonicalization + typed-409 dedup on
   single create/update; tenant predicate on `OutboundDialService.buildDestinationNumber`;
   per-attempt fail-closed handling for missing/deleted contacts in the dial path.
2. **VB-6B.2 Membership contract**: tests locking "audience = live group membership at
   `startExecution`" (no effect from later membership edits), concurrent-insert PG test
   against `uq_contacts_group_phone_live`, campaign↔group tenant-mismatch PG test.
3. **VB-6B.3 Inline numbers**: create-or-get contacts into a real reusable group
   (auto-created named group allowed), reusing `ContactValidation.canonicalizePhoneNumber`
   and the existing unique index; no temporary audience concept.
4. **VB-6B.4 Import hardening**: persisted import record (idempotency + audit), chunked
   dedup lookups; keep the synchronous 5000/5 MB contract.

**It must NOT build**: any daily-limit logic or counters (VB-6C); an `Audience` entity or
`AudienceSource` hierarchy beyond (at most) an additive `contact_groups.source` column if
decision 3 demands provenance; execution-audience membership tables (Model C) or
contact-set snapshots (Model B); async job infrastructure (unless VB-6B.6 is explicitly
green-lit with decision 12 resolved); Redis/Kafka/S3/K8s; retry-policy changes; MISSED_CALL;
IVR; webhooks; any change to the VB-6A snapshot model (no versioning reintroduction, no
live-campaign fallback); any weakening of tenant isolation or the dial-time compliance
separation; any modification of `ArchitectureTest` or existing test assertions.

**Standing constraints for the implementer**: PostgreSQL is the source of truth; additive
migrations from head V44 (V43 gap exists — do not renumber); preserve `CallAttempt` as the
contact-identity anchor for history; preserve the `CallEligibility` port pattern for any
new cross-module boundary; run `MAVEN_OPTS="-Xmx768m -XX:MaxMetaspaceSize=384m"
./mvnw clean test` from `backend/` and hold the ≥886/0/0/1 bar before declaring done.

---

*VB-6B audit stops here. Implementation not started.*
