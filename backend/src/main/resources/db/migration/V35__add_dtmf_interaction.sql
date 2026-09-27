-- =====================================================================
-- DTMF Interaction (Phase VB-2)
-- Forward-only; V1-V34 remain untouched.
-- =====================================================================
-- VB-2 adds DTMF collection to the voice execution lifecycle:
-- ANSWERED -> PLAYING -> PLAYBACK_COMPLETED -> WAITING_FOR_DTMF ->
-- result (VALID / INVALID / TIMEOUT / ABANDONED) -> hangup.
--
-- CallSessionStatus: WAITING_FOR_DTMF is the only new session state —
-- it marks "playback finished, collecting DTMF input". The granular
-- result lives on dtmf_interactions (single source of truth); duplicating
-- VALID/INVALID/TIMEOUT on the session would create two sources of truth.
--
-- dtmf_interactions: one row per DTMF collection interaction. It is the
-- auditable record of configuration (snapshot), collected digits, result,
-- and timing. Tenant-scoped like every voice table.

ALTER TYPE call_session_status ADD VALUE IF NOT EXISTS 'WAITING_FOR_DTMF';

CREATE TYPE dtmf_result_type AS ENUM
    ('COLLECTING', 'VALID', 'INVALID', 'TIMEOUT', 'ABANDONED');

CREATE TABLE dtmf_interactions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id),
    call_session_id         UUID NOT NULL REFERENCES call_sessions (id),
    call_attempt_id         UUID REFERENCES call_attempts (id),
    campaign_id             UUID,
    -- Configuration snapshot (from campaigns.type_config -> "dtmf") kept
    -- for auditability: the campaign config may change between calls.
    expected_input          VARCHAR(16)  NOT NULL,
    max_digits              INTEGER      NOT NULL,
    terminator              VARCHAR(1),
    timeout_secs            INTEGER      NOT NULL,
    collected_digits        VARCHAR(32)  NOT NULL DEFAULT '',
    result                  dtmf_result_type NOT NULL DEFAULT 'COLLECTING',
    result_reason           VARCHAR(255),
    result_at               TIMESTAMPTZ,
    expires_at              TIMESTAMPTZ  NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    updated_by              VARCHAR(255),
    deleted_at              TIMESTAMPTZ,
    deleted_by              VARCHAR(255)
);

CREATE INDEX idx_dtmf_interactions_session      ON dtmf_interactions (call_session_id);
CREATE INDEX idx_dtmf_interactions_tenant       ON dtmf_interactions (tenant_id, deleted_at);
-- Timeout poller scan: COLLECTING interactions past their expiry.
CREATE INDEX idx_dtmf_interactions_timeout_scan ON dtmf_interactions (result, expires_at);
CREATE INDEX idx_dtmf_interactions_attempt      ON dtmf_interactions (call_attempt_id);
