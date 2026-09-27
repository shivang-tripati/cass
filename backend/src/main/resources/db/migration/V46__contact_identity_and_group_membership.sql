-- =====================================================================
-- Contact identity + group membership (VB-6B.1)
-- Forward-only; V1-V44 remain untouched.
--
-- Model (approved, VB-6B.0 audit):
--   Contact          = tenant-level callable identity
--                      UNIQUE (tenant_id, phone_number) WHERE deleted_at IS NULL
--   ContactGroup     = tenant-owned audience/list (unchanged schema)
--   ContactGroupMember = the (group, contact) relationship
--                      UNIQUE (contact_group_id, contact_id)
--                      physical rows (no soft delete)
--
-- Tenant invariant is DATABASE-ENFORCED with composite foreign keys:
--   member.tenant_id = contact.tenant_id = group.tenant_id
-- backed by UNIQUE (id, tenant_id) on both parent tables. No triggers.
--
-- Deterministic backfill (dev-stage data; survivor fields win):
--   For every live (tenant_id, phone_number): survivor = oldest
--   created_at, tie-break min(id). Non-survivor live rows are soft-deleted,
--   memberships are inserted for every source group, and call_attempts are
--   remapped to the survivor BEFORE non-survivors lose identity.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Backfill support structures: composite parent keys + member table
--    (memberships must exist while contact_group_id is still present so
--    the backfill can read source groups from contacts).
-- ---------------------------------------------------------------------

ALTER TABLE contacts
    ADD CONSTRAINT uq_contacts_id_tenant UNIQUE (id, tenant_id);

ALTER TABLE contact_groups
    ADD CONSTRAINT uq_contact_groups_id_tenant UNIQUE (id, tenant_id);

CREATE TABLE contact_group_members (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenants (id),
    contact_group_id UUID NOT NULL,
    contact_id       UUID NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       VARCHAR(255),
    updated_at       TIMESTAMPTZ,
    updated_by       VARCHAR(255),
    -- Tenant invariant: member.tenant = contact.tenant = group.tenant.
    CONSTRAINT fk_cgm_contact FOREIGN KEY (contact_id, tenant_id)
        REFERENCES contacts (id, tenant_id),
    CONSTRAINT fk_cgm_group FOREIGN KEY (contact_group_id, tenant_id)
        REFERENCES contact_groups (id, tenant_id),
    CONSTRAINT uq_cgm_group_contact UNIQUE (contact_group_id, contact_id)
);

CREATE INDEX idx_cgm_contact ON contact_group_members (contact_id);
CREATE INDEX idx_cgm_tenant ON contact_group_members (tenant_id);

-- ---------------------------------------------------------------------
-- 2. Deterministic survivor selection per live (tenant_id, phone_number):
--    oldest created_at, tie-break minimum id.
-- ---------------------------------------------------------------------

CREATE TEMP TABLE vb6b1_contact_survivors ON COMMIT DROP AS
SELECT DISTINCT ON (tenant_id, phone_number)
    tenant_id,
    phone_number,
    id AS survivor_id
FROM contacts
WHERE deleted_at IS NULL
ORDER BY tenant_id, phone_number, created_at ASC, id ASC;

-- ---------------------------------------------------------------------
-- 3. Memberships: every LIVE source contact contributes its group.
--    Deduped by uq_cgm_group_contact; cross-tenant rows are impossible
--    (source row's tenant is copied; composite FKs would reject drift).
-- ---------------------------------------------------------------------

INSERT INTO contact_group_members (tenant_id, contact_group_id, contact_id, created_by)
SELECT DISTINCT
    c.tenant_id,
    c.contact_group_id,
    s.survivor_id,
    'vb6b1-migration'
FROM contacts c
JOIN vb6b1_contact_survivors s
  ON s.tenant_id = c.tenant_id AND s.phone_number = c.phone_number
WHERE c.deleted_at IS NULL
ON CONFLICT (contact_group_id, contact_id) DO NOTHING;

-- ---------------------------------------------------------------------
-- 4. CallAttempt remap: merged-away contact ids point at the survivor,
--    preserving unified history and retryability. (call_attempts has no
--    FK on contact_id; the unique (execution_id, contact_id,
--    attempt_number) key cannot collide across distinct executions and
--    remaps within one execution cannot collide because the source rows
--    shared one identity.)
-- ---------------------------------------------------------------------

UPDATE call_attempts ca
SET contact_id = s.survivor_id
FROM vb6b1_contact_survivors s
JOIN contacts merged
  ON merged.tenant_id = s.tenant_id
 AND merged.phone_number = s.phone_number
WHERE ca.contact_id = merged.id
  AND merged.id <> s.survivor_id
  AND merged.deleted_at IS NULL;

-- ---------------------------------------------------------------------
-- 5. Soft-delete non-survivor live rows (fields intentionally discarded:
--    survivor fields win — dev-stage policy, documented in the report).
-- ---------------------------------------------------------------------

UPDATE contacts c
SET deleted_at = now(),
    deleted_by = 'vb6b1-migration'
FROM vb6b1_contact_survivors s
WHERE c.tenant_id = s.tenant_id
  AND c.phone_number = s.phone_number
  AND c.deleted_at IS NULL
  AND c.id <> s.survivor_id;

-- ---------------------------------------------------------------------
-- 6. Drop the group-owned identity model on contacts.
-- ---------------------------------------------------------------------

ALTER TABLE contacts DROP CONSTRAINT IF EXISTS contacts_contact_group_id_fkey;
ALTER TABLE contacts DROP COLUMN IF EXISTS contact_group_id;

DROP INDEX IF EXISTS uq_contacts_group_phone_live;
DROP INDEX IF EXISTS idx_contacts_group_deleted;

-- ---------------------------------------------------------------------
-- 7. Tenant-level live identity uniqueness (replaces group+phone).
--    Live-partial: a soft-deleted number is re-creatable (existing
--    semantics, preserved).
-- ---------------------------------------------------------------------

CREATE UNIQUE INDEX uq_contacts_tenant_phone_live
    ON contacts (tenant_id, phone_number)
    WHERE deleted_at IS NULL;

-- Old-V45-direction index name must not linger (it was non-unique).
DROP INDEX IF EXISTS idx_contacts_tenant_phone_live;
