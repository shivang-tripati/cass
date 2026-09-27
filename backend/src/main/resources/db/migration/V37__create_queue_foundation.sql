-- =====================================================================
-- Queue Foundation (Phase VB-4B)
-- Forward-only; V1-V36 remain untouched.
-- =====================================================================
-- VB-4B establishes the queue domain required by the later contact-center
-- phases. It answers ONLY: "Which agents belong to this queue?"
--
-- It deliberately does NOT answer "Which agent should receive this call?"
-- — that is ACD (VB-4C). Consequently:
--   * max_waiting_calls / max_wait_seconds / overflow_* are persisted
--     CONFIGURATION; VB-4B executes neither timeout nor overflow.
--   * queue_waiting_calls persists that a canonical CallSession is
--     waiting in a queue; it duplicates no call attributes and creates
--     no agent legs. Entry/removal is owned by later inbound/ACD flows
--     (the VB-4B API surface is read-only for waiting calls).
--   * Ordering for a future dispatcher: ORDER BY entered_at, id
--     (documented; no dispatch is implemented here).

-- --- queue administrative lifecycle ------------------------------------
CREATE TYPE queue_status AS ENUM ('ACTIVE', 'INACTIVE', 'DISABLED');

-- --- membership lifecycle (independent of the agent itself) ------------
CREATE TYPE queue_member_status AS ENUM ('ACTIVE', 'INACTIVE');

-- --- waiting-call representation lifecycle ------------------------------
-- WAITING is a valid steady state in VB-4B (no assignment exists yet).
CREATE TYPE queue_waiting_call_status AS ENUM
    ('WAITING', 'REMOVED', 'COMPLETED', 'ABANDONED');

CREATE TABLE queues (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id),
    name                    VARCHAR(120) NOT NULL,
    description             VARCHAR(500),
    status                  queue_status NOT NULL DEFAULT 'ACTIVE',
    -- configuration only in VB-4B (no execution):
    max_waiting_calls       INTEGER NOT NULL DEFAULT 100
                            CONSTRAINT ck_queues_max_waiting_calls
                            CHECK (max_waiting_calls >= 0),
    max_wait_seconds        INTEGER NOT NULL DEFAULT 300
                            CONSTRAINT ck_queues_max_wait_seconds
                            CHECK (max_wait_seconds >= 0),
    overflow_enabled        BOOLEAN NOT NULL DEFAULT FALSE,
    -- overflow target must be a same-tenant queue and never the queue
    -- itself (self-overflow is meaningless); enforced below.
    overflow_queue_id       UUID REFERENCES queues (id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    updated_by              VARCHAR(255),
    deleted_at              TIMESTAMPTZ,
    deleted_by              VARCHAR(255),
    CONSTRAINT ck_queues_overflow_ref
        CHECK (overflow_queue_id IS NULL OR overflow_queue_id <> id)
);

-- Queue names are unique per tenant among live (non-deleted) queues.
CREATE UNIQUE INDEX uq_queues_tenant_name_live
    ON queues (tenant_id, lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX idx_queues_tenant    ON queues (tenant_id, deleted_at);
CREATE INDEX idx_queues_status    ON queues (tenant_id, status, deleted_at);

-- --- membership: Queue <-> Agent ---------------------------------------
-- The same agent may belong to many queues; one LIVE row per (queue,
-- agent). The partial unique index is the race-safety mechanism:
-- concurrent add-member requests collapse to one row at the database
-- level, while a soft-deleted membership can be re-established later
-- (history preserved).
CREATE TABLE queue_memberships (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    queue_id                UUID NOT NULL REFERENCES queues (id),
    agent_id                UUID NOT NULL REFERENCES agents (id),
    tenant_id               UUID NOT NULL REFERENCES tenants (id),
    status                  queue_member_status NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    updated_by              VARCHAR(255),
    deleted_at              TIMESTAMPTZ,
    deleted_by              VARCHAR(255)
);

CREATE UNIQUE INDEX uq_queue_memberships_queue_agent
    ON queue_memberships (queue_id, agent_id) WHERE deleted_at IS NULL;

CREATE INDEX idx_queue_memberships_queue   ON queue_memberships (queue_id, deleted_at);
CREATE INDEX idx_queue_memberships_agent   ON queue_memberships (agent_id, tenant_id, deleted_at);
CREATE INDEX idx_queue_memberships_tenant  ON queue_memberships (tenant_id, deleted_at);

-- --- waiting calls: canonical CallSession waiting in a queue -----------
-- References the canonical call; duplicates NO call attributes (no phone
-- number, direction, provider UUID, or call status snapshot). No agent
-- leg is created by a waiting-call row — assignment belongs to ACD.
CREATE TABLE queue_waiting_calls (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id),
    queue_id                UUID NOT NULL REFERENCES queues (id),
    call_session_id         UUID NOT NULL REFERENCES call_sessions (id),
    status                  queue_waiting_call_status NOT NULL DEFAULT 'WAITING',
    entered_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    updated_by              VARCHAR(255),
    deleted_at              TIMESTAMPTZ,
    deleted_by              VARCHAR(255)
);

-- One live WAITING row per call session (partial unique index — table-level
-- UNIQUE constraints cannot be partial in PostgreSQL).
CREATE UNIQUE INDEX uq_queue_waiting_calls_active_session
    ON queue_waiting_calls (call_session_id) WHERE (status = 'WAITING');

-- Deterministic future-dispatch ordering support (entered_at, id);
-- VB-4B performs no dispatch.
CREATE INDEX idx_queue_waiting_calls_dispatch
    ON queue_waiting_calls (queue_id, status, entered_at, id);
CREATE INDEX idx_queue_waiting_calls_tenant ON queue_waiting_calls (tenant_id, deleted_at);
CREATE INDEX idx_queue_waiting_calls_session ON queue_waiting_calls (call_session_id);

-- --- capability catalog extension (continues the V1/V16 sequence) ------
INSERT INTO capabilities (id, key, resource, action, description, active, created_at, created_by) VALUES
    ('cab1e100-0000-4000-8000-000000000033', 'QUEUE_VIEW',   'QUEUE', 'VIEW',   'View queues, memberships and waiting calls.', TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000034', 'QUEUE_MANAGE', 'QUEUE', 'MANAGE', 'Create and manage queues and their memberships.', TRUE, now(), 'system');

-- Role grants: platform/reseller/tenant administrators manage queues;
-- agents intentionally receive none (fail-closed), mirroring V16.
INSERT INTO role_capabilities (id, role_id, capability_id, created_at, created_by)
SELECT gen_random_uuid(), r.id, c.id, now(), 'system'
FROM roles r
JOIN capabilities c ON c.key = ANY (ARRAY ['QUEUE_VIEW', 'QUEUE_MANAGE'])
WHERE r.key IN ('SUPER_ADMIN', 'RESELLER_ADMIN', 'TENANT_ADMIN')
  AND NOT EXISTS (
      SELECT 1 FROM role_capabilities rc
      WHERE rc.role_id = r.id AND rc.capability_id = c.id);
