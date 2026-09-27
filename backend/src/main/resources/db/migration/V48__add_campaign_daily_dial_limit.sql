-- =====================================================================
-- Campaign daily dial limit (VB-6C.2)
-- Forward-only; V1-V47 remain untouched. No backfill: existing campaigns
-- keep NULL, which means "use the platform maximum of 3" at runtime.
--
-- Voice Blast policy (VB-6C.1): at most 3 provider-accepted dials per
-- (tenant, contact, actual outbound DNID, calendar day). A campaign may
-- configure a stricter limit; the configured value is the campaign's
-- REQUEST, distinct from the runtime EFFECTIVE limit
-- (DailyDialLimitService.effectiveLimit(null) = 3).
--
--   NULL  -> platform maximum (3)
--   1-3   -> campaign-specific stricter limit
--   other -> rejected by the DB CHECK (defense in depth behind the
--            service/DTO validation)
--
-- The same nullable column is added to campaign_execution_configurations:
-- the VB-6A immutable snapshot freezes the campaign's configured value at
-- execution-creation time (NULL stays NULL — the configured/effective
-- distinction is preserved in the snapshot too). No new table: this is
-- campaign configuration and belongs on the campaign configuration model.
-- =====================================================================

ALTER TABLE campaigns
    ADD COLUMN daily_dial_limit SMALLINT
    CONSTRAINT ck_campaigns_daily_dial_limit
        CHECK (daily_dial_limit IS NULL OR daily_dial_limit BETWEEN 1 AND 3);

ALTER TABLE campaign_execution_configurations
    ADD COLUMN daily_dial_limit SMALLINT
    CONSTRAINT ck_cec_daily_dial_limit
        CHECK (daily_dial_limit IS NULL OR daily_dial_limit BETWEEN 1 AND 3);
