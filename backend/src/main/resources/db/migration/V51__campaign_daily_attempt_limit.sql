-- =====================================================================
-- VB-6D.3 — campaign-configurable daily attempt ceiling
--
-- Forward-only; V1-V50 remain untouched.
--
-- A campaign may narrow the platform-wide daily attempt ceiling for its own
-- executions, exactly as VB-6C.2 let a campaign narrow the platform daily
-- DIAL limit:
--
--   NULL  -> platform default (DailyAttemptSafetyService
--             .MAX_DAILY_ATTEMPTS_PER_CONTACT = 10)
--   1-10  -> campaign-specific stricter ceiling
--   other -> rejected by the CHECK below
--
-- The bound is duplicated from the code constant on purpose, in the same way
-- V48 duplicated the VB-6C constant into a CHECK: the database is the last
-- line of defense, and a value above the platform maximum must be
-- unrepresentable even if some future code path forgets to validate. The
-- code constant remains the single authority for the effective value; this
-- constraint only refuses to store something that could not be honoured.
--
-- The SAME nullable column is added to campaign_execution_configurations:
-- the VB-6A immutable snapshot freezes the campaign's configured ceiling at
-- execution-creation time, so editing a campaign later cannot loosen or
-- tighten an execution that already exists. No versioning, no history
-- table, no MAX+1 logic, no fallback read.
--
-- Backward compatibility: no backfill. Existing campaigns keep NULL, which
-- means "platform default", so no existing campaign changes behaviour.
-- =====================================================================

ALTER TABLE campaigns
    ADD COLUMN max_daily_attempts SMALLINT
    CONSTRAINT ck_campaigns_max_daily_attempts
        CHECK (max_daily_attempts IS NULL OR max_daily_attempts BETWEEN 1 AND 10);

ALTER TABLE campaign_execution_configurations
    ADD COLUMN max_daily_attempts SMALLINT
    CONSTRAINT ck_cec_max_daily_attempts
        CHECK (max_daily_attempts IS NULL OR max_daily_attempts BETWEEN 1 AND 10);
