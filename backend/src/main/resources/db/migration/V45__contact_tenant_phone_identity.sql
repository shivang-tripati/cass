-- =====================================================================
-- Tenant-level logical phone identity support (VB-6B.1)
-- Forward-only; V1-V44 remain untouched.
--
-- The Contact row identity stays group-scoped: (contact_group_id,
-- phone_number) for live rows remains authoritative via the existing
-- partial unique index uq_contacts_group_phone_live (V19) — unchanged.
--
-- What VB-6B.1 adds is the TENANT-LEVEL LOGICAL phone identity
-- (tenant_id, canonical phone): the same number living in several groups
-- of one tenant is several Contact rows that future cross-campaign
-- consumers (contact history, VB-6C daily limits, suppression, reporting)
-- resolve as one logical identity. That is a QUERY convention, not a
-- uniqueness rule — this migration is deliberately a non-unique index.
-- No tenant+phone UNIQUE constraint is introduced.
-- =====================================================================

CREATE INDEX idx_contacts_tenant_phone_live
    ON contacts (tenant_id, phone_number)
    WHERE deleted_at IS NULL;
