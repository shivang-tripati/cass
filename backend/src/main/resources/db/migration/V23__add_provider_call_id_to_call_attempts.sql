-- =====================================================================
-- Call Attempt provider correlation (Phase 2U)
-- Forward-only; V1-V22 remain untouched.
--
-- Adds provider_call_id for FreeSWITCH ESL event correlation.
-- =====================================================================

ALTER TABLE call_attempts
    ADD COLUMN provider_call_id VARCHAR(128);

-- Index for event correlation lookup
CREATE INDEX idx_call_attempts_provider_call_id ON call_attempts (provider_call_id)
    WHERE provider_call_id IS NOT NULL;