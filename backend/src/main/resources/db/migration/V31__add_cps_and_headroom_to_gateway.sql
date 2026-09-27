-- =====================================================================
-- Add CPS and Capacity Headroom to SIP Gateway Domain (Phase VB-0B)
-- Forward-only; V1-V30 remain untouched.
-- =====================================================================

-- Add CPS and headroom to sip_gateways
ALTER TABLE sip_gateways
    ADD COLUMN max_cps INTEGER
        CONSTRAINT ck_sip_gateways_cps CHECK (max_cps IS NULL OR max_cps > 0),
    ADD COLUMN capacity_headroom_pct INTEGER
        CONSTRAINT ck_sip_gateways_headroom CHECK (capacity_headroom_pct IS NULL OR (capacity_headroom_pct >= 0 AND capacity_headroom_pct < 100));

-- Add CPS to sip_gateway_allocations
ALTER TABLE sip_gateway_allocations
    ADD COLUMN max_cps INTEGER
        CONSTRAINT ck_sip_alloc_cps CHECK (max_cps IS NULL OR max_cps > 0);

-- Index for CPS queries
CREATE INDEX idx_sip_gateways_cps ON sip_gateways (max_cps) WHERE max_cps IS NOT NULL;
CREATE INDEX idx_sip_alloc_cps ON sip_gateway_allocations (max_cps) WHERE max_cps IS NOT NULL;