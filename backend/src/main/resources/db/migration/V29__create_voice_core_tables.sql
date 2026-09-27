-- =====================================================================
-- Voice Core: Call Session & Call Leg (Phase R)
-- Forward-only; V1-V28 remain untouched.
-- Universal voice lifecycle abstraction, not campaign-specific.
-- =====================================================================

CREATE TYPE call_direction AS ENUM ('INBOUND', 'OUTBOUND');
CREATE TYPE call_type AS ENUM ('VOICE_BLAST', 'CONTACT_CENTER_INBOUND', 'CONTACT_CENTER_OUTBOUND', 'AI', 'INTERNAL');
CREATE TYPE call_session_status AS ENUM ('INITIATED', 'DIALING', 'RINGING', 'ANSWERED', 'COMPLETED', 'FAILED', 'CANCELLED');
CREATE TYPE call_leg_type AS ENUM ('CUSTOMER', 'AGENT', 'EXTERNAL', 'AI', 'QUEUE');
CREATE TYPE endpoint_type AS ENUM ('SIP', 'WEBRTC', 'MOBILE_APP', 'EXTERNAL_FORWARD', 'AI');
CREATE TYPE call_leg_status AS ENUM ('INITIATED', 'DIALING', 'RINGING', 'ANSWERED', 'COMPLETED', 'FAILED', 'CANCELLED');

-- Call Session: one logical voice interaction
CREATE TABLE call_sessions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id),
    reseller_id             UUID REFERENCES resellers (id),
    direction               call_direction NOT NULL DEFAULT 'OUTBOUND',
    call_type               call_type NOT NULL DEFAULT 'VOICE_BLAST',
    status                  call_session_status NOT NULL DEFAULT 'INITIATED',
    did_id                  UUID REFERENCES dids (id),
    destination_number      VARCHAR(20),
    initiated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at             TIMESTAMPTZ,
    ended_at                TIMESTAMPTZ,
    failure_code            VARCHAR(50),
    failure_reason          TEXT,
    provider_call_id        VARCHAR(128),
    call_attempt_id         UUID,  -- optional link to campaign CallAttempt
    campaign_execution_id   UUID,  -- optional link to campaign CampaignExecution
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    updated_by              VARCHAR(255),
    deleted_at              TIMESTAMPTZ,
    deleted_by              VARCHAR(255)
);

CREATE INDEX idx_call_sessions_tenant_deleted ON call_sessions (tenant_id, deleted_at);
CREATE INDEX idx_call_sessions_status ON call_sessions (status);
CREATE INDEX idx_call_sessions_started ON call_sessions (initiated_at);
CREATE INDEX idx_call_sessions_direction ON call_sessions (direction);
CREATE INDEX idx_call_sessions_provider_call_id ON call_sessions (provider_call_id);
CREATE INDEX idx_call_sessions_call_attempt_id ON call_sessions (call_attempt_id);
CREATE INDEX idx_call_sessions_campaign_execution_id ON call_sessions (campaign_execution_id);

-- Call Leg: one leg within a call session
CREATE TABLE call_legs (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    call_session_id         UUID NOT NULL REFERENCES call_sessions (id),
    leg_type                call_leg_type NOT NULL,
    endpoint_type           endpoint_type,
    direction               call_direction NOT NULL DEFAULT 'OUTBOUND',
    status                  call_leg_status NOT NULL DEFAULT 'INITIATED',
    target                  VARCHAR(255),
    provider_call_id        VARCHAR(128),
    initiated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at             TIMESTAMPTZ,
    ended_at                TIMESTAMPTZ,
    failure_code            VARCHAR(50),
    failure_reason          TEXT,
    agent_id                UUID,
    queue_id                UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    updated_by              VARCHAR(255),
    deleted_at              TIMESTAMPTZ,
    deleted_by              VARCHAR(255)
);

CREATE INDEX idx_call_legs_session ON call_legs (call_session_id);
CREATE INDEX idx_call_legs_type ON call_legs (leg_type);
CREATE INDEX idx_call_legs_status ON call_legs (status);
CREATE INDEX idx_call_legs_provider_call_id ON call_legs (provider_call_id);