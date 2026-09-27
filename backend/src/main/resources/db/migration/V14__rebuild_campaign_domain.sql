-- =====================================================================
-- Campaign domain rebuild (Phase 2G)
-- Forward-only; V1-V13 remain untouched.
--
-- The Phase 2D campaigns table was a CRUD-only shell (name/description/
-- status) that did not model the Campaign product domain: no campaign
-- type, no content selection, no contact-group/caller-id references,
-- no schedule, no retry policy. Its rows were scaffolding data; the
-- table is replaced wholesale rather than migrated.
-- =====================================================================

DROP TABLE IF EXISTS campaigns;

CREATE TABLE campaigns (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL REFERENCES tenants (id),
    name                   VARCHAR(200) NOT NULL,
    description            TEXT,

    -- First-class campaign type: drives configuration and validation.
    campaign_type          VARCHAR(30) NOT NULL
                           CONSTRAINT ck_campaigns_type
                           CHECK (campaign_type IN ('PLAYFILE', 'DTMF', 'CONNECT_BY_AGENT')),

    -- Lifecycle status (separate concept from type).
    status                 VARCHAR(30) NOT NULL DEFAULT 'DRAFT'
                           CONSTRAINT ck_campaigns_status
                           CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED')),

    -- External references. Modules owning these tables do not exist yet;
    -- plain UUID references by design (no cross-module FKs).
    contact_group_id       UUID,
    number_inventory_id    UUID,

    -- Content selection: static approved audio OR TTS template.
    content_mode           VARCHAR(10)
                           CONSTRAINT ck_campaigns_content_mode
                           CHECK (content_mode IN ('AUDIO', 'TTS')),
    audio_asset_id         UUID,
    tts_template_id        UUID,
    CONSTRAINT ck_campaigns_content_exclusive
        CHECK (NOT (audio_asset_id IS NOT NULL AND tts_template_id IS NOT NULL)),

    -- Schedule window configuration (timezone-explicit; extensible for
    -- future recurrence rules without breaking changes).
    schedule_start_date    DATE,
    schedule_end_date      DATE,
    daily_start_time       TIME,
    daily_end_time         TIME,
    timezone               VARCHAR(64),
    CONSTRAINT ck_campaigns_schedule_dates
        CHECK (schedule_start_date IS NULL OR schedule_end_date IS NULL
               OR schedule_end_date >= schedule_start_date),
    CONSTRAINT ck_campaigns_schedule_times
        CHECK (daily_start_time IS NULL OR daily_end_time IS NULL
               OR daily_end_time > daily_start_time),

    -- Fixed retry policy (the only strategy required today).
    retry_max_attempts     INTEGER NOT NULL DEFAULT 0
                           CONSTRAINT ck_campaigns_retry_attempts
                           CHECK (retry_max_attempts BETWEEN 0 AND 10),
    retry_interval_seconds INTEGER,
    retry_strategy         VARCHAR(20) NOT NULL DEFAULT 'FIXED'
                           CONSTRAINT ck_campaigns_retry_strategy
                           CHECK (retry_strategy IN ('FIXED')),
    CONSTRAINT ck_campaigns_retry_interval
        CHECK (retry_max_attempts = 0
               OR (retry_interval_seconds IS NOT NULL AND retry_interval_seconds > 0)),

    -- Type-specific configuration payload (DTMF keys/timeouts,
    -- agent/queue routing reference) and optional API/webhook
    -- integration configuration. Extensible; never stores secrets.
    type_config            JSONB,
    integration_config     JSONB,

    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ,
    created_by             VARCHAR(255),
    updated_by             VARCHAR(255),
    deleted_at             TIMESTAMPTZ,
    deleted_by             VARCHAR(255)
);

-- Scoped listing: every list/get path filters by tenant boundary and
-- excludes soft-deleted rows.
CREATE INDEX idx_campaigns_tenant_deleted ON campaigns (tenant_id, deleted_at);

-- Type filtering within a tenant (listing filter requirement).
CREATE INDEX idx_campaigns_tenant_type ON campaigns (tenant_id, campaign_type);

-- Status filtering across platform/reseller views.
CREATE INDEX idx_campaigns_status ON campaigns (status);

-- Default sort column.
CREATE INDEX idx_campaigns_created_at ON campaigns (created_at);

-- Reverse-reference lookups for the future owning modules (impact of
-- group replacement, asset approval/revocation, template rejection,
-- number release).
CREATE INDEX idx_campaigns_contact_group ON campaigns (contact_group_id);
CREATE INDEX idx_campaigns_audio_asset ON campaigns (audio_asset_id);
CREATE INDEX idx_campaigns_tts_template ON campaigns (tts_template_id);
CREATE INDEX idx_campaigns_number_inventory ON campaigns (number_inventory_id);
