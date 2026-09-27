-- =====================================================================
-- VB-5D: TTS template scope governance (forward-only; V1–V41 untouched).
-- =====================================================================
-- Introduces two ownership scopes for TTS templates:
--
--   GLOBAL → platform-owned system/prebuilt templates. tenant_id is
--            NULL; rows are managed (create/update/delete) and gated
--            (approve/reject) only with platform authorization
--            (PLATFORM-scope TTS_MANAGE / TTS_APPROVE). An APPROVED,
--            non-deleted GLOBAL template is usable by every tenant.
--   TENANT → tenant-owned templates. tenant_id is NOT NULL; rows are
--            created PENDING_APPROVAL and approved within the owning
--            tenant. Only the owning tenant can use them.
--
-- Scope is mutually exclusive with tenancy and enforced by the database
-- (GLOBAL ⇒ tenant_id IS NULL, TENANT ⇒ tenant_id IS NOT NULL), so no
-- application bug or manual write can mint a cross-scope row.
--
-- Backfill: every pre-VB-5D row is tenant-owned (tenant_id was NOT NULL
-- since V20), so all existing rows become scope='TENANT'. The campaign
-- usability predicate (APPROVED + not deleted + tenant match) is
-- preserved bit-for-bit for them — no campaign behavior changes until a
-- GLOBAL template is introduced.
--
-- No GLOBAL rows are fabricated here: platform operators mint them at
-- runtime through the normal create path (V42 only makes them
-- representable). No capability catalog changes: TTS_VIEW / TTS_MANAGE /
-- TTS_APPROVE (V20) already express read / manage / gate, and their
-- scope resolution (PLATFORM vs RESELLER vs TENANT) already maps onto
-- GLOBAL management.
-- =====================================================================

ALTER TABLE tts_templates
    ADD COLUMN scope VARCHAR(20);

-- Every pre-VB-5D row is tenant-owned.
UPDATE tts_templates
SET scope = 'TENANT';

-- GLOBAL rows are tenantless; V20 made tenant_id NOT NULL when every
-- row was tenant-owned.
ALTER TABLE tts_templates
    ALTER COLUMN tenant_id DROP NOT NULL;

ALTER TABLE tts_templates
    ALTER COLUMN scope SET NOT NULL;

ALTER TABLE tts_templates
    ALTER COLUMN scope SET DEFAULT 'TENANT';

ALTER TABLE tts_templates
    ADD CONSTRAINT ck_tts_templates_scope
    CHECK (scope IN ('GLOBAL', 'TENANT'));

-- GLOBAL rows are platform-owned: they must not carry a tenant stamp.
ALTER TABLE tts_templates
    ADD CONSTRAINT ck_tts_templates_scope_global_no_tenant
    CHECK (scope <> 'GLOBAL' OR tenant_id IS NULL);

-- TENANT rows keep the V20 ownership invariant.
ALTER TABLE tts_templates
    ADD CONSTRAINT ck_tts_templates_scope_tenant_requires_tenant
    CHECK (scope <> 'TENANT' OR tenant_id IS NOT NULL);

-- GLOBAL templates are the shared catalog every tenant resolves at
-- campaign readiness; keep that lookup index-shaped.
CREATE INDEX idx_tts_templates_global_status_deleted
    ON tts_templates (status, deleted_at)
    WHERE scope = 'GLOBAL';
