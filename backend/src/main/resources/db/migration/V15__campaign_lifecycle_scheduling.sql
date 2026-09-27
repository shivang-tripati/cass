-- =====================================================================
-- Campaign lifecycle, scheduling & versioning (Phase 2H)
-- Forward-only; V1-V14 remain untouched.
--
-- Expands the campaign lifecycle model, adds run-mode scheduling
-- eligibility, an external holiday-calendar reference, and simple
-- lineage versioning. No audit columns are introduced.
-- =====================================================================

-- Lifecycle expansion. ACTIVE is superseded by SCHEDULED (armed) and
-- RUNNING (executing); all pre-existing rows are DRAFT, so widening the
-- CHECK is data-safe.
ALTER TABLE campaigns DROP CONSTRAINT ck_campaigns_status;
ALTER TABLE campaigns
    ADD CONSTRAINT ck_campaigns_status CHECK (status IN (
        'DRAFT', 'SCHEDULED', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'ARCHIVED'));

-- First-class execution mode. Existing rows are one-time campaigns.
ALTER TABLE campaigns
    ADD COLUMN run_mode VARCHAR(20) NOT NULL DEFAULT 'ONE_TIME',
    ADD CONSTRAINT ck_campaigns_run_mode CHECK (run_mode IN ('ONE_TIME', 'RECURRING'));

-- Recurring eligibility window: type-safe day names serialized as a JSON
-- array of strings (e.g. ["MONDAY","FRIDAY"]); NULL means unrestricted.
ALTER TABLE campaigns ADD COLUMN allowed_days_of_week JSONB;

-- Holiday/blackout calendar reference. The owning Compliance/Holiday
-- Calendar module does not exist yet: plain UUID reference, no FK by
-- design (same convention as contact_group_id/audio_asset_id).
ALTER TABLE campaigns ADD COLUMN holiday_calendar_id UUID;

-- Simple lineage versioning: root campaigns start at 1; each clone
-- increments. Not an immutable-revision system.
ALTER TABLE campaigns
    ADD COLUMN version INTEGER NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_campaigns_version CHECK (version > 0);

ALTER TABLE campaigns
    ADD COLUMN cloned_from_campaign_id UUID,
    ADD CONSTRAINT ck_campaigns_no_self_clone
        CHECK (cloned_from_campaign_id IS NULL OR cloned_from_campaign_id <> id);

-- Reverse lineage lookup (descendants of a campaign).
CREATE INDEX idx_campaigns_cloned_from ON campaigns (cloned_from_campaign_id);
