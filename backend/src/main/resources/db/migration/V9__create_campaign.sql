-- =====================================================================
-- Campaign module (Phase 2D)
-- Forward-only; V1-V8 remain untouched.
-- =====================================================================

CREATE TABLE campaigns (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants (id),
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    status      VARCHAR(30) NOT NULL DEFAULT 'DRAFT'
                CONSTRAINT ck_campaigns_status CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    deleted_at  TIMESTAMPTZ,
    deleted_by  VARCHAR(255)
);

CREATE INDEX idx_campaigns_tenant ON campaigns (tenant_id);
CREATE INDEX idx_campaigns_tenant_deleted ON campaigns (tenant_id, deleted_at);
CREATE INDEX idx_campaigns_status ON campaigns (status);
CREATE INDEX idx_campaigns_created_at ON campaigns (created_at);
