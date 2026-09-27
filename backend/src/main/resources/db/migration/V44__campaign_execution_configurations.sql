-- VB-6A (corrected): execution-owned immutable configuration snapshot.
--
-- Model (VB-6A correction):
--   Campaign (editable while DRAFT)
--       | create execution
--       v
--   CampaignExecution ---- owns exactly one ----> campaign_execution_configurations row
--
-- There is NO campaign configuration versioning: the snapshot represents
-- what this particular execution was created to execute, not a historical
-- version of the campaign. The execution's FK is NOT NULL — the database
-- enforces "no execution without a snapshot"; the creation path inserts
-- the snapshot first inside the same transaction, so no orphan pair can
-- exist either.
--
-- Resource VALIDITY of the referenced DID/audio/TTS is never frozen here —
-- the payload records what was requested; runtime validation stays dynamic.
--
-- Audit columns (created_by/updated_by) mirror every other business table
-- (AuditableEntity requires them; see V10/V12 conventions).

CREATE TABLE campaign_execution_configurations (
    id                        UUID PRIMARY KEY,
    campaign_id               UUID NOT NULL REFERENCES campaigns (id),
    tenant_id                 UUID NOT NULL REFERENCES tenants (id),
    -- Immutable configuration payload (mirrors campaigns columns; see the
    -- CampaignConfigurationSnapshot embeddable for field semantics).
    campaign_type             VARCHAR(30) NOT NULL,
    contact_group_id          UUID,
    did_id                    UUID,
    content_mode              VARCHAR(10),
    audio_asset_id            UUID,
    tts_template_id           UUID,
    schedule_start_date       DATE,
    schedule_end_date         DATE,
    daily_start_time          TIME,
    daily_end_time            TIME,
    timezone                  VARCHAR(64),
    allowed_days_of_week      JSONB,
    holiday_calendar_id       UUID,
    retry_max_attempts        INTEGER NOT NULL DEFAULT 0,
    retry_interval_seconds    INTEGER,
    retry_strategy            VARCHAR(20) NOT NULL DEFAULT 'FIXED',
    type_config               JSONB,
    call_on_whitelist_numbers BOOLEAN NOT NULL DEFAULT FALSE,
    -- Audit columns (AuditableEntity).
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                VARCHAR(255),
    updated_at                TIMESTAMPTZ,
    updated_by                VARCHAR(255),
    deleted_at                TIMESTAMPTZ,
    deleted_by                VARCHAR(255)
);

CREATE INDEX idx_cec_campaign ON campaign_execution_configurations (campaign_id);
CREATE INDEX idx_cec_tenant ON campaign_execution_configurations (tenant_id);

-- Execution -> snapshot ownership (mandatory): every execution runs on an
-- immutable configuration snapshot. NOT NULL makes "execution without a
-- snapshot" impossible at the persistence layer.
ALTER TABLE campaign_executions
    ADD COLUMN configuration_snapshot_id UUID NOT NULL;

ALTER TABLE campaign_executions
    ADD CONSTRAINT fk_campaign_executions_configuration_snapshot
    FOREIGN KEY (configuration_snapshot_id)
    REFERENCES campaign_execution_configurations (id);

CREATE INDEX idx_campaign_executions_configuration_snapshot
    ON campaign_executions (configuration_snapshot_id);
