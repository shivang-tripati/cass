-- =====================================================================
-- Campaign Execution foundation (Phase 2N)
-- Forward-only; V1-V20 remain untouched.
--
-- Minimal execution record representing one execution attempt of a campaign.
-- The future execution engine will own runtime transitions (RUNNING, COMPLETED, FAILED).
-- =====================================================================

CREATE TABLE campaign_executions (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id            UUID NOT NULL REFERENCES campaigns (id),
    tenant_id              UUID NOT NULL REFERENCES tenants (id),

    -- Execution lifecycle (distinct from campaign lifecycle).
    -- REQUESTED: execution accepted but not yet started by engine
    -- RUNNING: engine has started processing
    -- COMPLETED: engine finished successfully
    -- FAILED: engine terminated with error
    -- CANCELLED: execution cancelled before/during processing
    status                 VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
                            CONSTRAINT ck_campaign_executions_status
                            CHECK (status IN ('REQUESTED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),

    -- Optional client-supplied idempotency key for duplicate protection.
    -- Unique per (campaign_id, idempotency_key) to prevent duplicate executions.
    idempotency_key        VARCHAR(128),

    -- Request metadata
    requested_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    requested_by           VARCHAR(255) NOT NULL,

    -- Execution timestamps (populated by future engine)
    started_at             TIMESTAMPTZ,
    completed_at           TIMESTAMPTZ,

    -- Failure capture for FAILED status
    failure_reason         TEXT,

    -- Audit columns (AuditableEntity)
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by             VARCHAR(255),
    updated_at             TIMESTAMPTZ,
    updated_by             VARCHAR(255),

    -- Soft delete
    deleted_at             TIMESTAMPTZ,
    deleted_by             VARCHAR(255)
);

-- Scoped lookups: every path filters by tenant boundary and excludes soft-deleted.
CREATE INDEX idx_campaign_executions_tenant_deleted ON campaign_executions (tenant_id, deleted_at);

-- Campaign reference lookups (replacement, cancellation, status polling)
CREATE INDEX idx_campaign_executions_campaign ON campaign_executions (campaign_id);

-- Status filtering for execution engine consumers
CREATE INDEX idx_campaign_executions_status ON campaign_executions (status);

-- Idempotency: prevent duplicate execution requests for same campaign+key
CREATE UNIQUE INDEX uq_campaign_executions_campaign_idempotency
    ON campaign_executions (campaign_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;