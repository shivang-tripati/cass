-- =====================================================================
-- Add Capacity Headroom to SIP Gateway Allocations (Phase VB-0)
-- Forward-only; V1-V32 remain untouched.
-- =====================================================================
-- VoiceCapacityServiceImpl applies allocation-level headroom via
-- SipGatewayAllocation.getCapacityHeadroomPct() when computing the
-- effective tenant channel limit. This column backs that field; without
-- it the allocation headroom configuration silently has no persistence.

ALTER TABLE sip_gateway_allocations
    ADD COLUMN capacity_headroom_pct INTEGER
        CONSTRAINT ck_sip_alloc_headroom
        CHECK (capacity_headroom_pct IS NULL OR (capacity_headroom_pct >= 0 AND capacity_headroom_pct < 100));
