-- =====================================================================
-- Agent domain + CONNECT_BY_AGENT (Phase VB-3)
-- Forward-only; V1-V35 remain untouched.
-- =====================================================================
-- VB-3 introduces the first contact-center primitive: deterministic agent
-- selection, atomic agent reservation (PostgreSQL-backed, advisory-lock
-- coordinated like VB-0 voice capacity), agent-leg originate and a
-- caller<->agent bridge.
--
-- Agent capacity is deliberately SEPARATE from voice gateway capacity:
-- agents.max_concurrent_calls is an agent-level concurrency budget; the
-- gateway channel/CPS budgets stay on voice_channel_reservations (VB-0).
-- Both reservations must succeed before a connection can proceed.
--
-- DTMF action: VB-2 persisted the interaction result; VB-3 persists the
-- requested action so DTMF_VALID results can dispatch to the action layer
-- (only CONNECT_BY_AGENT is supported in VB-3).

-- --- agent administrative status: "allowed to receive calls" ----------
CREATE TYPE agent_admin_status AS ENUM ('ACTIVE', 'SUSPENDED', 'DISABLED');

-- --- agent runtime availability: "can receive a call right now" -------
CREATE TYPE agent_availability AS ENUM ('AVAILABLE', 'BUSY', 'OFFLINE');

-- --- agent reservation lifecycle ---------------------------------------
CREATE TYPE agent_reservation_status AS ENUM ('RESERVED', 'ACTIVE', 'RELEASED');

CREATE TABLE agents (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id),
    display_name            VARCHAR(120) NOT NULL,
    admin_status            agent_admin_status NOT NULL DEFAULT 'ACTIVE',
    availability            agent_availability NOT NULL DEFAULT 'OFFLINE',
    max_concurrent_calls    INTEGER NOT NULL DEFAULT 1,
    user_id                 UUID REFERENCES users (id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    updated_by              VARCHAR(255),
    deleted_at              TIMESTAMPTZ,
    deleted_by              VARCHAR(255),
    CONSTRAINT ck_agents_max_concurrent_calls CHECK (max_concurrent_calls > 0)
);

CREATE INDEX idx_agents_tenant           ON agents (tenant_id, deleted_at);
CREATE INDEX idx_agents_selection        ON agents (tenant_id, admin_status, availability, deleted_at);

-- Endpoint: where FreeSWITCH originates the agent leg. VB-3 supports the
-- endpoint types the existing FreeSWITCH setup can dial: SIP (sofia
-- contact) and EXTERNAL_FORWARD (PSTN via gateway). WebRTC/MOBILE_APP are
-- modeled but NOT accepted by the VB-3 dialer (documented limitation).
CREATE TABLE agent_endpoints (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id                UUID NOT NULL REFERENCES agents (id),
    tenant_id               UUID NOT NULL REFERENCES tenants (id),
    endpoint_type           endpoint_type NOT NULL,
    -- SIP URI / E.164 dial string resolved by FreeSWITCH
    dial_target             VARCHAR(255) NOT NULL,
    enabled                 BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    updated_by              VARCHAR(255),
    deleted_at              TIMESTAMPTZ,
    deleted_by              VARCHAR(255)
);

CREATE INDEX idx_agent_endpoints_agent   ON agent_endpoints (agent_id, enabled, deleted_at);
CREATE INDEX idx_agent_endpoints_tenant  ON agent_endpoints (tenant_id, deleted_at);

CREATE TABLE agent_reservations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id                UUID NOT NULL REFERENCES agents (id),
    tenant_id               UUID NOT NULL REFERENCES tenants (id),
    call_session_id         UUID NOT NULL REFERENCES call_sessions (id),
    call_leg_id             UUID REFERENCES call_legs (id),
    attempt_id              UUID REFERENCES call_attempts (id),
    status                  agent_reservation_status NOT NULL DEFAULT 'RESERVED',
    reserved_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at             TIMESTAMPTZ,
    release_reason          VARCHAR(120),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    updated_by              VARCHAR(255),
    deleted_at              TIMESTAMPTZ,
    deleted_by              VARCHAR(255),
    CONSTRAINT ck_agent_reservations_release CHECK (
        (status = 'RELEASED' AND released_at IS NOT NULL)
        OR (status <> 'RELEASED' AND released_at IS NULL))
);

-- Active-reservation lookups (concurrency check + cleanup scans).
CREATE INDEX idx_agent_reservations_agent_active  ON agent_reservations (agent_id, status);
CREATE INDEX idx_agent_reservations_tenant        ON agent_reservations (tenant_id, deleted_at);
CREATE INDEX idx_agent_reservations_session       ON agent_reservations (call_session_id);
CREATE INDEX idx_agent_reservations_stale_scan    ON agent_reservations (status, reserved_at);

-- --- call lifecycle states (leg carries agent-leg granularity) --------
ALTER TYPE call_session_status ADD VALUE IF NOT EXISTS 'CONNECTING_AGENT';
ALTER TYPE call_session_status ADD VALUE IF NOT EXISTS 'BRIDGED';
ALTER TYPE call_leg_status   ADD VALUE IF NOT EXISTS 'BRIDGED';

-- --- VB-2 bridge: persist the requested DTMF action --------------------
ALTER TABLE dtmf_interactions
    ADD COLUMN action_type VARCHAR(40) NOT NULL DEFAULT 'TERMINATE';
