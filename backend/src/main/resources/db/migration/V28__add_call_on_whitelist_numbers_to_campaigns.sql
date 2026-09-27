-- =====================================================================
-- Add call_on_whitelist_numbers to campaigns (Phase: campaign whitelist)
-- Forward-only; V1-V27 remain untouched.
--
-- CampaignEntity maps callOnWhitelistNumbers as:
--   @Column(name = "call_on_whitelist_numbers", nullable = false)
--   private Boolean callOnWhitelistNumbers = false;
-- Hibernate expects BOOLEAN NOT NULL. Existing rows are backfilled to FALSE
-- (conservative default: do not dial whitelist numbers unless explicitly
-- enabled). Matches Java initializer and neighboring NOT NULL DEFAULT
-- conventions (e.g. version INTEGER NOT NULL DEFAULT 1).
-- =====================================================================

ALTER TABLE campaigns
    ADD COLUMN IF NOT EXISTS call_on_whitelist_numbers BOOLEAN NOT NULL DEFAULT FALSE;
