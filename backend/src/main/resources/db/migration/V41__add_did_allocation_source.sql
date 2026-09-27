-- =====================================================================
-- VB-5C: DID allocation provenance (forward-only; V1–V40 untouched).
-- =====================================================================
-- Revocation must return a DID to the pool it originally came from:
--
--   PLATFORM  → tenant   (revoke → platform pool,  reseller_id NULL)
--   PLATFORM  → reseller(revoke → reseller pool,  reseller_id kept)
--   RESELLER  → tenant   (revoke → reseller pool,  reseller_id kept)
--
-- The assignment side (tenant_id / reseller_id) alone cannot express
-- this: after a platform DID is stamped with a reseller id, revoking the
-- reseller assignment would look identical to revoking a reseller-pool
-- DID. allocation_source records the pool a live assignment came from
-- (RESELLER when the reseller pool was the source, PLATFORM for direct
-- platform assignments, NULL for pristine platform-pool numbers).
--
-- Backfill of pre-VB-5C live rows:
--   tenant-assigned + reseller stamp  → RESELLER (assigned out of the
--      reseller pool under the current create() ownership rules);
--   otherwise → NULL (platform pool). The nullable column means the
--      existing reader/check-consumer behavior is unchanged.
-- =====================================================================

ALTER TABLE dids
    ADD COLUMN allocation_source VARCHAR(20);

ALTER TABLE dids
    ADD CONSTRAINT ck_dids_allocation_source
    CHECK (allocation_source IN ('PLATFORM', 'RESELLER'));

-- A DID sourced from a reseller pool must carry that reseller's stamp.
ALTER TABLE dids
    ADD CONSTRAINT ck_dids_allocation_source_requires_reseller
    CHECK (allocation_source <> 'RESELLER' OR reseller_id IS NOT NULL);

UPDATE dids
SET allocation_source = 'RESELLER'
WHERE tenant_id IS NOT NULL
  AND reseller_id IS NOT NULL
  AND deleted_at IS NULL;

CREATE INDEX idx_dids_reseller_pool
    ON dids (reseller_id, allocation_state)
    WHERE deleted_at IS NULL;
