-- VB-7B: register MISSED_CALL as a fourth campaign type.
--
-- The configuration itself needs no schema: a MISSED_CALL campaign stores its
-- one type-specific value (ringDurationSeconds) in the EXISTING
-- campaigns.type_config JSONB (V14) and is frozen verbatim into the EXISTING
-- campaign_execution_configurations.type_config JSONB (V44), exactly as
-- CONNECT_BY_AGENT (VB-7A) and the IVR tree reference (VB-6F) do. DID,
-- audience, retry, schedule and safety are already campaign columns with their
-- own authorities and are already snapshotted.
--
-- This migration is therefore the ONLY change required, and it exists solely
-- because the column is constrained. campaign_type is VARCHAR(30) with a CHECK
-- (not a PostgreSQL enum), so widening the allowed set is a plain additive
-- constraint swap -- no ALTER TYPE, no new table, no new column.
--
-- Idempotence: DROP ... IF EXISTS before ADD, so this applies cleanly whether or
-- not the constraint carries its original name.

ALTER TABLE campaigns DROP CONSTRAINT IF EXISTS ck_campaigns_type;

ALTER TABLE campaigns
    ADD CONSTRAINT ck_campaigns_type
    CHECK (campaign_type IN (
        'PLAYFILE',
        'DTMF',
        'CONNECT_BY_AGENT',
        'MISSED_CALL'
    ));

COMMENT ON CONSTRAINT ck_campaigns_type ON campaigns IS
    'VB-7B: widened from three types to four so MISSED_CALL campaigns can be persisted. '
    'The type is additive only; no existing row is modified and no existing value is removed.';
