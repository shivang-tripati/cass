-- =====================================================================
-- Add gateway_id to Call Sessions for Capacity Tracking (Phase VB-0B)
-- Forward-only; V1-V31 remain untouched.
-- =====================================================================

ALTER TABLE call_sessions
    ADD COLUMN gateway_id UUID REFERENCES sip_gateways (id);

CREATE INDEX idx_call_sessions_gateway ON call_sessions (gateway_id);