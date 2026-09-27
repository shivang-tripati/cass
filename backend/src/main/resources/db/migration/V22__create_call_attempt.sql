-- =====================================================================
-- Call Attempt foundation (Phase 2O)
-- Forward-only; V1-V21 remain untouched.
--
-- Individual call attempt within a campaign execution.
-- The future execution engine owns runtime state transitions.
-- =====================================================================

CREATE TABLE call_attempts (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id           UUID NOT NULL REFERENCES campaign_executions (id),
    campaign_id            UUID NOT NULL REFERENCES campaigns (id),
    tenant_id              UUID NOT NULL REFERENCES tenants (id),
    contact_id             UUID NOT NULL,
    did_id                 UUID NOT NULL,

    -- Attempt number for this contact within this execution (1-based).
    -- Enables retry policy evaluation: attemptNumber <= retryMaxAttempts
    attempt_number         INTEGER NOT NULL DEFAULT 1
                            CONSTRAINT ck_call_attempts_attempt_number
                            CHECK (attempt_number > 0),

    -- Execution-oriented status (distinct from campaign/execution lifecycle).
    -- QUEUED: awaiting pickup by execution worker
    -- IN_PROGRESS: worker has started dialing/processing
    -- COMPLETED: call finished successfully
    -- FAILED: call terminated with error (retry may apply)
    -- CANCELLED: attempt cancelled before/during processing
    status                 VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
                            CONSTRAINT ck_call_attempts_status
                            CHECK (status IN ('QUEUED', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED')),

    -- When the attempt was scheduled for execution
    scheduled_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- When the execution worker started processing this attempt
    started_at             TIMESTAMPTZ,

    -- When the attempt finished (success or failure)
    completed_at           TIMESTAMPTZ,

    -- Standardized failure code for programmatic handling (e.g. DIAL_FAILED, NO_ANSWER, BUSY, CONGESTION)
    failure_code           VARCHAR(50),

    -- Human-readable failure reason
    failure_reason         TEXT,

    -- Soft delete / audit
    deleted_at             TIMESTAMPTZ,
    deleted_by             VARCHAR(255)
);

-- Scoped lookups: every path filters by tenant boundary and excludes soft-deleted.
CREATE INDEX idx_call_attempts_tenant_deleted ON call_attempts (tenant_id, deleted_at);

-- Execution reference lookups (replacement, cancellation, status polling)
CREATE INDEX idx_call_attempts_execution ON call_attempts (execution_id);

-- Campaign reference lookups
CREATE INDEX idx_call_attempts_campaign ON call_attempts (campaign_id);

-- Status filtering for execution engine consumers
CREATE INDEX idx_call_attempts_status ON call_attempts (status);

-- Scheduled-at ordering for worker pickup
CREATE INDEX idx_call_attempts_scheduled ON call_attempts (scheduled_at);

-- Contact reference lookups
CREATE INDEX idx_call_attempts_contact ON call_attempts (contact_id);

-- DID reference lookups
CREATE INDEX idx_call_attempts_did ON call_attempts (did_id);

-- Prevent duplicate attempts for same execution+contact+attemptNumber
CREATE UNIQUE INDEX uq_call_attempts_execution_contact_attempt
    ON call_attempts (execution_id, contact_id, attempt_number)
    WHERE deleted_at IS NULL;