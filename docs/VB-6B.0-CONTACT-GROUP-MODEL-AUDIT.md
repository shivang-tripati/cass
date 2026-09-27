# VB-6B.0 — Contact / Group Membership Model Audit (Design Reset)

## 1. Executive summary

    AUDIT + DESIGN COMPLETE
    IMPLEMENTATION NOT STARTED (old VB-6B.1 halted; new model pending approval)

The domain review concluded that the current contact model — **one Contact row per
(group, phone)** — conflates two concepts that the product roadmap (multi-group reuse,
inline numbers, daily limits, contact history, omnichannel) requires to be separate:

    CONTACT          = tenant-level callable identity      (tenant_id, phone_number)
    CONTACT GROUP    = tenant-owned audience/list
    GROUP MEMBERSHIP = the (group, contact) relationship

The target model (Contact / ContactGroup / ContactGroupMember) is **adopted as the
authoritative direction** by this audit; no concrete existing platform constraint makes it
unsafe. All findings below are evidence-tagged: **OBSERVED** (read from repo/DB),
**INFERRED** (derived), **RECOMMENDED** (audit judgment), **OPEN DECISION** (needs
explicit sign-off before implementation).

The already-completed portion of the old VB-6B.1 (canonicalization + typed 409 +
tenant-scoped dial lookup + fail-closed `CONTACT_INVALID`) is **largely KEEP/MODIFY** —
it is model-agnostic correctness work. The old Step-7b PG test files are broken
(harness wiring) and are classified **REPLACE**. One migration (V45) already exists on
disk from the old direction and is classified **REPLACE** (absorbed into the new-model
migration). **No production/test/migration changes were made in this phase.**

## 2. Current model

**OBSERVED** (all previously verified in `docs/VB-6B-CONTACT-AUDIENCE-AUDIT.md` §4–§6,
re-confirmed this session):

- `contacts.contact_group_id UUID NOT NULL REFERENCES contact_groups` (V17) — a Contact
  cannot exist without a group; identity is `(contact_group_id, phone_number)` for live
  rows, DB-enforced by `uq_contacts_group_phone_live` (V19, live-partial).
- `tenant_id` is denormalized onto the contact from the group; the E.164 format is
  CHECK-enforced (`ck_contacts_phone_format`).
- There is **no tenant-level uniqueness** of phone numbers and no tenant+phone lookup
  (until old-6B.1 added the non-unique `idx_contacts_tenant_phone_live`, V45).
- Consequences (all OBSERVED): the same number in N groups = N rows with N copies of
  name/email/attributes; no way to ask "which contacts share this number tenant-wide"
  without group enumeration; a person's call history is fragmented across per-group
  duplicate rows.

## 3. Problems with current model

**OBSERVED/INFERRED:**

| # | Problem | Evidence |
|---|---|---|
| P1 | Identity duplication: same tenant number = many rows with independent field copies; updating "the customer" means finding every group copy | schema; `ContactGroupService` CRUD |
| P2 | Cross-campaign counting impossible without group enumeration: VB-6C daily limits need "was this *number* called today" — today that is `tenant + phone`, but no row or index represents it as identity | audit §17; `CallAttempt` keyed by `contact_id` |
| P3 | History fragmentation: `CallAttempt.contactId` points at a per-group row; the same person called via two groups has two disconnected histories | `CallAttempt.contact_id` semantics |
| P4 | Inline numbers (VB-6B.5) would force "which group owns this pasted number?" — a meaningless question under group-owned identity | audit §13 |
| P5 | Import dedup is group-scoped only; re-importing the same file into another group duplicates the person entirely | `importContacts` |
| P6 | Suppression/DND would have to be applied per duplicate row instead of per identity | phone-list stack keys on number; contact row is not authoritative |
| P7 | The `attributes` JSONB (template variables) is per-group-copy, so the same customer has order data in one group and not another | `ContactEntity.attributes` |

The one genuine advantage of the current model — "group-specific contact data" — is not a
product requirement anywhere in the codebase or roadmap (RECOMMENDED finding); per-group
*audience* context is carried by the group/execution, not the contact.

## 4. Target model

```
Tenant
   |
   +--- Contact            (identity: tenant + canonical phone; no group column)
   |
   +--- ContactGroup       (audience/list; no contact ownership)
           |
           +--- ContactGroupMember ---> Contact
```

**OBSERVED** compatibility facts that make the refactor safe:

- `Campaign` references groups by UUID only (`campaigns.contact_group_id`, no FK) — the
  reference survives unchanged; the group's *meaning* changes from "contact container" to
  "membership container" with **no campaign-schema change**.
- The VB-6A execution snapshot freezes `contact_group_id` (the reference) — unchanged.
- `CallAttempt.contact_id` has **no FK** (V22) — attempts reference Contact identity
  directly, which is exactly the target semantics; no attempt-schema change is required.
- `CallSession` has no contact column — nothing to migrate there.
- Direct consumers of the group-scoped contact column (OBSERVED, complete inventory):
  `ContactMapper`, `ContactRepository` (6 methods + Specifications), `ContactGroupService`
  (contact CRUD + import + export + delete guard), `CampaignExecutionOrchestrator`
  (selection + `isContactStillValid`), `OutboundDialService` (old-6B.1 lookup),
  `CallEligibilityService` (membership-by-phone), `CallAttemptService.createAttempt`
  (group-scoped validation). All are rewirable inside the contact/campaign modules;
  none requires a new cross-module dependency direction.

**No Audience entity** (brief §28): the group remains the audience definition; campaigns
keep referencing it. Confirmed RECOMMENDED.

## 5. Contact identity semantics

- **Identity boundary**: `UNIQUE (tenant_id, phone_number) WHERE deleted_at IS NULL`
  (live-partial, matching the V19 convention). Within a tenant a live canonical phone
  resolves to exactly one Contact; across tenants the same number is legitimately
  different Contacts (tenant is inside the key). **No global phone uniqueness** (brief §5.1).
- **Canonical form**: `ContactValidation.canonicalizePhoneNumber` remains the single
  persistence canonicalizer (separators stripped, then E.164). Voice layer keeps
  `PhoneNumberNormalizer` for dial strings. (OBSERVED split; KEEP.)
- **Contact fields**: `first_name/last_name/email/attributes` belong to the identity (the
  person), not the group (brief confirms). One row = one authoritative copy.
- **No group column**: `contacts.contact_group_id` is REMOVED; Contact requires no group
  to exist.
- **OPEN DECISION D1 — soft-delete & uniqueness scope**: recommend live-partial unique
  (deleted numbers re-creatable), consistent with V19 and `softDeletedPhoneNumberIsReusable`.
  Alternative (full UNIQUE including deleted rows, preserving number-to-row immutability
  forever) rejected: it contradicts the existing reuse semantics.
- **OPEN DECISION D2 — phone changes**: updating a Contact's phone keeps the same row/UUID
  (identity evolves); history remains attached. Recommend allowing it (existing behavior),
  with the unique index as concurrency authority.

## 6. Group semantics

- A group is a **tenant-owned audience/list**: name/description + memberships. It owns
  nothing about the contact beyond the relationship.
- Groups remain reusable across campaigns and referenced by UUID; the VB-6A snapshot and
  Model A (live membership at execution start) are unchanged.
- Group-level counts (`existsByContactGroupIdAndDeletedAtIsNull` today) become
  membership-existence checks.
- **OPEN DECISION D3 — group deletion**: existing behavior is "409 while live contacts
  exist". Under the new model recommend: deleting a group **removes its membership rows
  (physical) and soft-deletes the group**; contacts are never touched. The 409-guard
  becomes unnecessary because memberships are cheap links. (Deliberate behavior change;
  documented, dev-stage.)

## 7. Membership semantics

- `ContactGroupMember` = (contact_group_id, contact_id), plus `tenant_id` (denormalized,
  see §8) and audit columns. **No extra attributes** (brief §5.3): no per-membership
  name/attributes — that would reinvent the duplication this refactor removes.
- Uniqueness: `UNIQUE (contact_group_id, contact_id)` — **full unique, not partial**.
  A membership is a pure relationship; re-adding = idempotent no-op, removal = physical
  delete. **OPEN DECISION D4**: physical removal vs soft-delete of membership rows.
  Recommend **physical** (membership is not historical record; call history lives on
  attempts; keeping soft-deleted memberships would force partial-unique complexity with
  zero query benefit). If audit trails of "who was in which list when" are ever needed,
  that is an event/reporting concern, not a membership column.
- Many-to-many both ways: one Contact ↔ many groups; one group ↔ many Contacts.

## 8. Tenant isolation

- **Mandatory invariant**: `member.tenant_id == contact.tenant_id == group.tenant_id`.
- **Recommended enforcement — composite foreign keys** (least-complex *robust* mechanism;
  brief §6 explicitly asks to evaluate beyond application validation):
  1. Add `UNIQUE (id, tenant_id)` on `contacts` and on `contact_groups` (cheap, additive;
     the PK already covers `id`).
  2. `contact_group_members.tenant_id NOT NULL`,
     `FK (contact_id, tenant_id) → contacts (id, tenant_id)`,
     `FK (contact_group_id, tenant_id) → contact_groups (id, tenant_id)`.
  A cross-tenant membership is then **impossible at the database** — no trigger, no
  app-level reliance, consistent with the platform's "PostgreSQL is the source of truth".
  (INFERRED as the best fit; triggers rejected as hidden magic; app-only rejected as
  weaker than what the schema can express.)
- All membership/identity queries remain tenant-scoped in the repository layer, matching
  the existing `findByIdAndTenantIdAndDeletedAtIsNull` conventions (OBSERVED pattern).
- Existing authorization (CONTACT_VIEW/CONTACT_MANAGE, reseller hierarchy, 404-cloaking)
  is reused unchanged; no second authorization system.

## 9. Soft-delete semantics

**OBSERVED conventions** (existing repo): contacts/groups soft-delete with
`deleted_at/deleted_by`; live-partial uniqueness makes soft-deleted numbers reusable;
group delete refuses while live contacts exist.

- **Contact delete**: soft-delete the identity row. Memberships: **physically remove**
  (a deleted contact is in no live audience); if the contact is re-created later (same
  number), memberships are re-added explicitly. Call history is untouched — attempts keep
  pointing at the (now soft-deleted) row; the identity query boundary is `contact_id`
  regardless of liveness. RECOMMENDED.
- **Group delete**: per §6/D3 — cascade-remove memberships, soft-delete group.
- **Can a deleted contact remain a member?** No (physically removed). **Can a deleted
  group retain memberships?** No (cascade).
- **Should memberships be soft-deleted?** No — D4.

## 10. CallAttempt impact

- `CallAttempt.contact_id` **stays a direct reference to Contact** (brief §15 expected
  direction — OBSERVED already true). No group column is added to attempts; the
  campaign/group context of an attempt is reachable via `execution_id →
  campaign_execution_configurations.contact_group_id` (OBSERVED chain), satisfying any
  "which audience produced this call" question without denormalization.
- **Data migration**: attempts referencing *duplicate* (merged-away) contact rows are
  **remapped to the surviving identity row** (`UPDATE call_attempts SET contact_id =
  <survivor>`). Rationale: identity merge semantics — the attempts were calls to that
  logical person; remapping unifies history (§18) and keeps retry/dial paths working for
  in-flight rows. The alternative (leave pointing at soft-deleted duplicates) would make
  every merged attempt `CONTACT_INVALID` at dial time. RECOMMENDED (dev DB currently has
  0 attempts, so this is design-for-correctness, not data salvage).
- `uq_call_attempts_execution_contact_attempt` semantics unchanged.

## 11. CallSession impact

**OBSERVED**: `call_sessions` has no contact column; the chain is
`call_session.call_attempt_id → call_attempt.contact_id`. Under the new model that chain
gains value (one identity per person) but requires **no schema change**. Adding
`call_sessions.contact_id` remains the previously documented optional denormalization
(audit §10) — still **not recommended now** (OPEN, deferred; measure first).

## 12. Campaign impact

**OBSERVED**: campaigns hold `contact_group_id` (nullable UUID, no FK); create/update
validation (`validateContactGroupReference`), readiness (`checkContactGroup`), the VB-6A
snapshot (`CampaignConfigurationSnapshot.contact_group_id`), and the mapper/clone paths
all operate on the *reference* — **zero campaign-schema or snapshot-model changes**.
Campaign-facing error surfaces ("Contact group does not exist or is not available") stay
verbatim.

## 13. Execution / audience semantics

- **Model A preserved** (brief §17): the execution freezes the group *reference*; the
  audience = **live membership at execution start**. `createInitialAttempts` switches from
  "contacts where group = X" to "memberships of X (live contacts only)", reading through
  `contact_group_members`. Later membership edits do not affect a started execution
  (unchanged).
- The old-6B.1 fail-closed boundary is re-derived: attempts reference contacts directly,
  so a contact deleted/missing/foreign **after start** fails that attempt
  (`CONTACT_INVALID`, permanent) while the batch continues. The only behavioral change:
  **the group-mismatch clause disappears** — a contact's group membership is decided at
  attempt-creation time (membership read), not re-verified at dial time; the dial-time
  check becomes `findByIdAndTenantIdAndDeletedAtIsNull` (tenant + live). Compliance/
  eligibility re-checks at dial time are unchanged (§16 separation preserved).

## 14. Inline-number impact

Design impact only (brief §10; nothing implemented): inline flow becomes
normalize → find-or-create Contact (tenant+phone, race-safe via the unique index) →
upsert membership → campaign references group. **No temporary audience/contact concepts.**
The new model is what makes inline clean: "pasted numbers" become a system-created group
with memberships over canonical identities. Scheduled VB-6B.5.

## 15. Import impact

- Flow change: row → canonicalize → **find-or-create Contact** → upsert membership →
  continue. In-file duplicate number = same Contact, one membership (second row skipped /
  reported as DUPLICATE row but not an identity error). Same number in a *different
  group* = same Contact, *additional* membership — no duplicate Contact (brief §11).
- Contact-field conflicts across rows/groups (different names for one number): **OPEN
  DECISION D5** — recommend first-non-null-wins at Contact level, no per-row identity
  error (import remains audience-focused). 
- Sync/single-txn/5000-row/5 MB behavior is NOT redesigned here (brief §11); the
  find-or-create insert path must still be race-safe (§22). Large-import redesign stays
  VB-6B.6.

## 16. Daily-limit impact

- Counting identity becomes exactly the brief's requirement: **tenant + contact** — a
  single `call_attempts.contact_id` per tenant resolves "calls for this number today"
  across all groups/campaigns. No group dimension in the count (brief §14).
- Locked counting unit unchanged: `DIAL_REQUEST_ACCEPTED` (provider-accepted dial).
- The unique `(tenant_id, phone_number)` index *is* the future lookup; no counters,
  reservations, or limit logic in any implementation phase until VB-6C.

## 17. Retry impact

- Retry policy untouched: snapshot-driven retry policy/schedule (VB-6A), attempt
  numbering, permanent/temporary classification, requeue semantics all unchanged.
- Retry-time contact validation (`isContactStillValid`) changes from
  "contact in the snapshot's group" to "contact live within tenant" (group re-check is
  unnecessary — membership was decided at start; compliance/DID re-checks unchanged).
  Attempt remapping (§10) keeps merged rows retryable.

## 18. Reporting impact

No reporting module exists yet (OBSERVED — only readiness/governance services match
"report" today). Required future query boundaries, enabled by the model:

- **Contact history**: all attempts for identity X (`contact_id = X`) — unified across
  every group/campaign (fixes P3).
- **Group/campaign report**: attempts joined via `execution → snapshot.contact_group_id`
  — audience context without identity confusion.
- **Suppression**: one identity row to suppress; phone-list stack unchanged.
- Deleted-contact resolution for historical reports: read the soft-deleted identity row by
  id (no API today; OPEN DECISION D6 for reporting phase).

## 19. API impact

**OBSERVED current surface** (audit §21): everything contact-related is nested under
`/api/v1/contact-groups/{id}/contacts[...]`. Conceptual target boundary (brief §19):

- **Contact identity APIs**: `POST /api/v1/contacts` (create identity; no group), 
  `GET /contacts/{id}`, `PUT /contacts/{id}`, `GET /contacts` (tenant-scoped listing).
- **Membership APIs**: `POST /contact-groups/{id}/members` (`{contactId}` or
  `{phoneNumber}` find-or-create), `DELETE .../members/{contactId}`,
  `GET .../members` (paged listing incl. contact payload via join).
- **Backward compatibility**: dev-stage, zero commits — the nested group-contact CRUD
  endpoints can be **replaced deliberately** (the nested create becomes "create-or-get +
  add membership"). ContactResponse drops `contactGroupId` (breaking DTO change,
  documented); import/export semantics per §15. No compatibility shims (per §29 of the
  brief). **OPEN DECISION D7**: whether the old nested CRUD paths return 410/404 or are
  re-pointed to membership semantics during VB-6B.2 — recommend re-pointing (least client
  surprise in development).

## 20. Database schema proposal

```sql
-- contacts: group column dropped; identity unique added
ALTER TABLE contacts DROP CONSTRAINT contacts_contact_group_id_fkey;  -- name per catalog
ALTER TABLE contacts DROP COLUMN contact_group_id;
CREATE UNIQUE INDEX uq_contacts_tenant_phone_live
    ON contacts (tenant_id, phone_number) WHERE deleted_at IS NULL;

-- groups: unchanged schema

-- membership bridge
CREATE TABLE contact_group_members (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenants (id),
    contact_group_id UUID NOT NULL,
    contact_id       UUID NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ,
    updated_by       VARCHAR(255),
    -- tenant invariant, DB-enforced (see §8):
    CONSTRAINT fk_cgm_contact      FOREIGN KEY (contact_id, tenant_id)
        REFERENCES contacts (id, tenant_id),
    CONSTRAINT fk_cgm_group        FOREIGN KEY (contact_group_id, tenant_id)
        REFERENCES contact_groups (id, tenant_id),
    CONSTRAINT uq_cgm_group_contact UNIQUE (contact_group_id, contact_id)
);
CREATE UNIQUE INDEX uq_contacts_id_tenant  ON contacts (id, tenant_id);
CREATE UNIQUE INDEX uq_contact_groups_id_tenant ON contact_groups (id, tenant_id);
CREATE INDEX idx_cgm_contact ON contact_group_members (contact_id);
CREATE INDEX idx_cgm_tenant  ON contact_group_members (tenant_id);
```

Notes: exact constraint/index names follow the V17/V19 conventions; the composite-FK
pattern requires the two `(id, tenant_id)` unique indexes shown. `deleted_at` is
deliberately **absent** from members (D4: physical membership rows). Final DDL is written
in the implementation phase against the actual catalog names — nothing is executed now.

## 21. Data migration strategy (deterministic; not executed)

One additive migration (V46, absorbing V45 — see §25) performs schema + backfill
atomically:

1. **Group survivors per tenant+phone**: for each `(tenant_id, phone_number)` group of
   **live** contacts: survivor = min(`created_at`), tie-break min(`id`). Deterministic.
2. **Merge**: non-survivor live rows are soft-deleted
   (`deleted_at = now(), deleted_by = 'vb6b-migration'`). Their field payloads are
   **discarded** in favor of the survivor's (dev-stage seed data only; no synthesis of
   "best" fields — deterministic and simple). Conflicting-field policy for future real
   migrations = D5.
3. **Memberships**: insert `(group_id, survivor_id, tenant_id)` for every live source row
   (each group keeps its audience), deduped by the unique constraint.
4. **Attempt remap**: `UPDATE call_attempts SET contact_id = survivor WHERE contact_id IN
   (merged ids)` — per tenant+phone scope; keeps history unified and retryable (§10).
5. **Schema change**: drop the group column/FK; add the unique identity index + member
   table (§20).
6. **Pre-existing soft-deleted contacts**: left deleted; no memberships created; their
   numbers become re-creatable (live-partial semantics).
7. **Cross-tenant**: nothing merges across tenants (tenant is in every key).
8. Campaigns/snapshots/executions: untouched.
9. Idempotency: re-running is prevented by Flyway; each step is deterministic so the
   script is auditable row-by-row. Dev DB currently holds 3 contacts / 16 groups of seed
   data (OBSERVED) — the backfill is small but the algorithm is written to be safe at any
   scale (set-based SQL, no row-by-row application loop).

## 22. Concurrency strategy

- **Identity creation**: the live-partial unique index is the final authority (same as
  V19 today — OBSERVED pattern). Single create: canonicalize → exists-check (typed 409
  fast path) → insert; the flush-time `DataIntegrityViolationException` is translated to
  the same typed 409 (old-6B.1 pattern, KEEP). **Find-or-create** (import/inline): rely on
  the unique index — attempt insert, on unique violation re-select the existing row
  (retry-once loop); no `SELECT-then-INSERT` trust. No application locks.
- **Membership creation**: `UNIQUE (contact_group_id, contact_id)` is the authority;
  re-add = no-op via the same translate-or-ignore pattern.
- **No new advisory locks** (brief §23): existing keyspaces remain
  `0x100000000L` (channel) / `0x200000000L` (CPS) in `VoiceCapacityServiceImpl`
  (OBSERVED); DB uniqueness is strictly preferable here.

## 23. Index strategy

Justified by actual access patterns only (brief §27):

| Index | Serves |
|---|---|
| `uq_contacts_tenant_phone_live (tenant_id, phone_number) WHERE deleted_at IS NULL` (UNIQUE) | identity lookup, dedup, VB-6C counting joins, eligibility-by-phone |
| `uq_contacts_id_tenant (id, tenant_id)` | composite-FK invariant (§8) |
| `uq_contact_groups_id_tenant (id, tenant_id)` | composite-FK invariant |
| `uq_cgm_group_contact (contact_group_id, contact_id)` UNIQUE | membership idempotency + "members of group" (audience selection) |
| `idx_cgm_contact (contact_id)` | "groups of a contact", membership cascade on contact delete |
| existing `idx_contacts_tenant_deleted`, `idx_contact_groups_tenant_deleted` | unchanged listing paths |
| `idx_contacts_tenant_phone_live` (V45, non-unique) | **REPLACED** by the unique version above |

Not added: membership `(tenant_id, ...)` beyond the simple tenant index, phone-index on
attempts beyond existing `idx_call_attempts_contact`, any counter/limit indexes (VB-6C).

## 24. Performance / scaling considerations

- Audience selection ("members of group") becomes a join `members → contacts` over
  `uq_cgm_group_contact` + PK — equivalent cost to today's `idx_contacts_group_deleted`
  path; still a full in-memory list in `createInitialAttempts` (OBSERVED scale issue,
  unchanged by this refactor). **Future fix** (VB-6B.6/7, not now): keyset-paged
  membership scanning with batched attempt inserts; the membership table is the natural
  cursor unit (`(group_id, contact_id)` keyset).
- Import find-or-create adds one identity lookup per distinct number (index-served);
  large imports should chunk identity lookups exactly like today's
  `findLivePhoneNumbers` batch (future VB-6B.6).
- Member rows add one row per (contact, group) — storage cost is modest vs the removed
  field-duplication.
- 50-lakh scale: unchanged conclusions from audit §12 — schema/indexes support it;
  synchronous import and full-list attempt creation remain the bottlenecks (deferred).

## 25. Existing VB-6B.1 work classification

**OBSERVED state this session**: baseline run = **903 tests, 0 failures, 7 errors,
1 skipped**; the 7 errors are entirely the two old-Step-7b PG classes
(`DialBatchContinuationPostgresIntegrationTest` 4, `ContactIdentityPostgresIntegrationTest` 3)
which wire their own containers but **lack the `@DynamicPropertySource` datasource/Flyway
override and `@Import(JpaAuditConfig.class)`** used by every working PG harness — they
mis-routed to the dev DB (hence "relation tenants does not exist" / tenant FK errors).
ArchitectureTest: 1/0/0/0 (0 cycles). Flyway head on disk: V44 + V45. Nothing else broke.

| Item (old VB-6B.1) | Classification | Reason / action under new model |
|---|---|---|
| `ContactIdentityService` — canonicalization + typed conflict + DIVE translation | **KEEP (MODIFY)** | Model-agnostic; `assertPhoneAvailableInGroup` becomes tenant-level; `findLiveContactIdsByTenantPhone` collapses into the plain identity lookup |
| `ContactIdentityServiceTest` (10 unit, green) | **MODIFY** | Rewire dedup expectations from group-scope to tenant-scope; canonicalization/race tests stand |
| Contact create/update canonicalization + flush-in-guard + typed 409 (`ContactGroupService`, `ContactMapper` overloads) | **KEEP (MODIFY)** | Same mechanics; dedup boundary changes; nested-group API re-pointed to membership semantics (§19) |
| `V45__contact_tenant_phone_identity.sql` (non-unique index; uncommitted) | **REPLACE** | Absorbed into V46 as the UNIQUE identity index; delete V45 with the same zero-commit in-place precedent used for the V44 rewrite |
| `OutboundDialService` tenant-scoped contact lookup | **KEEP (MODIFY)** | Becomes `findByIdAndTenantIdAndDeletedAtIsNull` (drop the group predicate) |
| `CallFailureCode.CONTACT_INVALID` (PERMANENT) + fail-closed per-attempt handling | **KEEP** | Still required (§13); group-mismatch clause removed from the lookup but deleted/missing/foreign remain |
| `ContactRepository` new methods (`findByIdAndTenantIdAndContactGroupIdAndDeletedAtIsNull`, `findIdsByTenantIdAndPhoneNumberAndDeletedAtIsNull`) | **REPLACE** | First loses its group clause; second is superseded by identity uniqueness |
| `ContactIdentityPostgresIntegrationTest` (broken wiring) | **REPLACE** | Rewrite under the new model with the standard harness pattern (datasource override + JpaAuditConfig import) |
| `DialBatchContinuationPostgresIntegrationTest` (broken wiring) | **REPLACE** | Same; the four scenarios (deleted/missing/foreign/group-mismatch) become deleted/missing/foreign (group-mismatch dropped per §13) |
| `ContactOwnershipServiceTest` / `ContactImportServiceTest` ctor updates | **KEEP** | Mechanical; ownership semantics unchanged |
| `ContactEntity.contactGroupId`, `ContactResponse.contactGroupId`, `uq_contacts_group_phone_live`, `idx_contacts_group_deleted` | **REMOVE** | Replaced by identity + membership model (target state) |
| Old Step 7b continuation / old 6B.1 report | **DO NOT RESUME** | Superseded by this design reset |

## 26. Test strategy (plan; not written now)

Matrix for the implementation phases (brief §26, mapped to new model):

- **Identity**: create; same tenant+phone → typed 409; different tenant+same phone → ok;
  canonicalization (separators/whitespace/invalid/leading-zero/stored-canonical);
  concurrent duplicate create (real PG, one row + loser typed 409); phone update keeps id.
- **Membership**: add; same contact in many groups; many contacts per group; duplicate
  membership no-op; **cross-tenant membership rejected at DB level** (composite-FK proof
  test); member listing paged.
- **Soft delete**: contact delete removes memberships + keeps attempts; group delete
  cascades memberships; deleted number re-creatable; deleted contact attempt →
  `CONTACT_INVALID` fail-closed.
- **History**: attempts remapped to survivor resolve; same contact called via two groups
  yields unified history.
- **Execution**: membership-based audience at start; membership edits after start have no
  effect; deleted/missing/foreign contact → per-attempt failure + **batch continuation
  (A invalid, B/C processed — the defect lock)**.
- **Tenant isolation**: contact read/write, membership add, group operations — all
  404-cloaked per existing conventions.
- **Daily-limit prep**: same contact across groups resolves to one identity count unit.
- **Regression**: full `clean test` bar ≥ previous green baseline; ArchitectureTest 0
  cycles; existing suites updated mechanically where the model changed (ownership/import
  fixtures, orchestrator harnesses, snapshot suite fixtures).

## 27. Architecture / module impact

- `contact` remains a **leaf** module owning identity + membership; `campaign` keeps
  consuming contact repositories/`CallEligibility` port (OBSERVED directions, unchanged);
  `telephony` keeps implementing the campaign-owned eligibility interface — **no new
  cycles, no new ports needed** (brief §25 satisfied). The membership concept lives
  entirely inside the contact module; campaign sees only "members of group X" through
  contact-owned queries.
- ArchitectureTest untouched; 0 cycles re-verified this session (OBSERVED).

## 28. Risks

| Risk | Severity | Direction |
|---|---|---|
| Cross-tenant membership if composite-FK pattern is skipped | HIGH | DB-enforced composite FKs (§8) — non-negotiable |
| Attempt remap touching live history rows | MEDIUM | Deterministic survivor rule; dev-stage data only; set-based SQL reviewed before run |
| Field-loss on merge (names/attributes of non-survivors) | LOW (dev data) | Deterministic discard; D5 policy documented for future real data |
| API breakage (nested CRUD → membership semantics) | MEDIUM | Deliberate, documented re-pointing (D7); no shims |
| Membership cascade complexity on deletes | LOW | Physical deletes; no soft-delete duality |
| Unique-index rebuild on contacts at migration time | LOW | Table is small today; set-based backfill |
| Find-or-create race storms during large imports | MEDIUM (future) | Retry-once on unique violation; chunking in VB-6B.6 |
| Old-6B.1 broken tests left red in tree until rewrite | LOW | Classified REPLACE; rewritten in first implementation phase |

## 29. Open decisions

| # | Decision | Recommendation |
|---|---|---|
| D1 | Live-partial vs full unique on (tenant, phone) | Live-partial (V19-consistent, reuse semantics) |
| D2 | Phone update keeps same Contact row | Allow (identity evolves; history attached) |
| D3 | Group delete: cascade memberships vs 409 | Cascade (memberships are links) |
| D4 | Membership removal: physical vs soft | Physical |
| D5 | Contact-field conflict policy on merge/import | First-non-null-wins; no identity error rows |
| D6 | May reporting resolve soft-deleted contacts? | Defer to reporting phase (read-by-id is possible) |
| D7 | Nested group-contact endpoints: re-point vs remove | Re-point to membership semantics in VB-6B.2 |
| D8 | Delete V45 vs keep non-unique index | Delete/absorb into V46 (uncommitted migration precedent) |
| D9 | `call_sessions.contact_id` denormalization | Still deferred (measure first) |

## 30. Recommended implementation sequence

Derived from the audit (each phase independently testable; brief §32 adjusted):

- **VB-6B.1 — Contact identity refactor**: entity change (drop group column), identity
  service/repository/API re-pointing, `CONTACT_INVALID` dial-path lookup to
  tenant+live, migration V46 (schema §20 + deterministic backfill §21, absorbing V45),
  identity tests incl. PG concurrency. *Foundation: everything else needs the identity.*
- **VB-6B.2 — Membership implementation**: `ContactGroupMember` entity/repo/service/API
  (add/remove/list), group-delete cascade, composite-FK invariant + DB proof test,
  membership tests (multi-group, duplicate no-op, cross-tenant rejection).
- **VB-6B.3 — Consumer rewiring**: eligibility membership-by-phone via join,
  orchestrator selection via memberships, `CallAttemptService` validation, import
  find-or-create + membership upsert, group/import test updates.
- **VB-6B.4 — Execution integration**: batch-continuation PG suite (rewritten harness),
  membership-edit-after-start semantics test, remap/history test.
- **VB-6B.5 — Inline numbers**: find-or-create + membership flow, system-created group.
- **VB-6B.6 — Large import / bounded processing**: import job record, chunked identity
  lookups, keyset-paged membership scanning for attempt creation.
- **VB-6B.7 — Daily call limit** (VB-6C-adjacent): counting on identity
  (`DIAL_REQUEST_ACCEPTED`), global/campaign min() — only when product green-lights.
- **VB-6B.8 — Hardening**: full regression, reseller-scope tests, normalizer edge suite,
  docs.

## 31. Explicit implementation boundary

**VB-6B.1 (next phase, upon approval)** implements exactly: the Contact identity model
change + V46 migration + identity/membership schema and backfill + canonicalization/
dedup rewiring + dial-path tenant-scoped lookup + `CONTACT_INVALID` fail-closed + the
identity test suite. It does **not** implement: membership APIs/flows (VB-6B.2), consumer
rewiring (VB-6B.3), inline (VB-6B.5), large import (VB-6B.6), daily limits (VB-6B.7/6C),
any new infrastructure, any VB-6A snapshot change, any scheduler redesign.

---

### Final report (phase summary)

- **Baseline**: pre-old-6B.1 = 886/0/0/1 green (V44). **Current tree** = 903 tests,
  0 failures, **7 errors** (all in the two broken old-Step-7b PG classes — harness
  wiring, documented above), 1 skipped; ArchitectureTest 0 cycles; Flyway head on disk
  V44+V45 (V45 uncommitted, classified REPLACE); dev DB intact (3 contacts/16 groups,
  read-only check).
- **Current state**: old VB-6B.1 halted mid-stream exactly as instructed; no old Step-7b
  work resumed; no old report written.
- **Major findings**: group-scoped identity is the root model defect (P1–P7); composite-FK
  tenant invariant is implementable with two cheap unique indexes; deterministic merge +
  attempt-remap migration designed; no architectural blocker exists.
- **Target model**: Contact = identity (tenant+phone), Group = audience, Member =
  relationship, Campaign = intent, Attempt = dialing event (brief's final principle,
  adopted).
- **Old VB-6B.1 work**: KEEP/MODIFY the correctness core (canonicalization, typed 409,
  race translation, tenant-scoped lookup, `CONTACT_INVALID`); REPLACE the two broken PG
  test classes, the group-scoped repo methods, and V45.
- **Migration strategy**: V46 = atomic schema + deterministic backfill (survivor rule,
  field discard, membership insert, attempt remap), absorbing V45.
- **Recommended next phase**: VB-6B.1 identity refactor after D1–D9 are approved.
- **Tests run this phase**: `./mvnw clean test` (full) → 903/0/7/1 (7 errors pre-exist
  in the old files; nothing modified to make them pass); ArchitectureTest 1/0/0/0;
  read-only dev-DB counts via psql.
- **Files changed in this phase**: **only** `docs/VB-6B.0-CONTACT-GROUP-MODEL-AUDIT.md`.
  No production code, tests, or migrations touched; VB-6A snapshot architecture
  untouched.
