-- =====================================================================
-- SIP Gateway / Trunk internal resource domain (Phase 2V)
-- Forward-only; V1-V23 remain untouched.
-- Internal infrastructure — NOT exposed via tenant/reseller APIs.
-- =====================================================================

CREATE TABLE sip_gateways (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                        VARCHAR(100) NOT NULL,
    display_name                VARCHAR(200),
    provider                    VARCHAR(50) NOT NULL,
    free_switch_gateway_name    VARCHAR(100) NOT NULL,
    free_switch_profile         VARCHAR(50) NOT NULL DEFAULT 'external',
    status                      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                                CONSTRAINT ck_sip_gateways_status
                                CHECK (status IN ('ACTIVE', 'INACTIVE', 'DEGRADED')),
    owner_type                  VARCHAR(20) NOT NULL DEFAULT 'PLATFORM'
                                CONSTRAINT ck_sip_gateways_owner_type
                                CHECK (owner_type IN ('PLATFORM', 'RESELLER', 'TENANT')),
    owner_reseller_id           UUID,
    owner_tenant_id             UUID,
    max_concurrent_channels     INTEGER NOT NULL
                                CONSTRAINT ck_sip_gateways_capacity CHECK (max_concurrent_channels > 0),
    priority                    INTEGER NOT NULL DEFAULT 50
                                CONSTRAINT ck_sip_gateways_priority CHECK (priority BETWEEN 0 AND 100),
    enabled                     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(255),
    updated_by                  VARCHAR(255),
    deleted_at                  TIMESTAMPTZ,
    deleted_by                  VARCHAR(255),
    CONSTRAINT ck_sip_gateways_owner_consistency
        CHECK (
            (owner_type = 'PLATFORM' AND owner_reseller_id IS NULL AND owner_tenant_id IS NULL)
            OR (owner_type = 'RESELLER' AND owner_reseller_id IS NOT NULL AND owner_tenant_id IS NULL)
            OR (owner_type = 'TENANT' AND owner_tenant_id IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uq_sip_gateways_name_live
    ON sip_gateways (name) WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_sip_gateways_fs_name_live
    ON sip_gateways (free_switch_gateway_name) WHERE deleted_at IS NULL;

CREATE INDEX idx_sip_gateways_provider_status ON sip_gateways (provider, status);
CREATE INDEX idx_sip_gateways_owner ON sip_gateways (owner_type, owner_reseller_id, owner_tenant_id);
CREATE INDEX idx_sip_gateways_enabled_deleted ON sip_gateways (enabled, deleted_at);
CREATE INDEX idx_sip_gateways_fs_gateway ON sip_gateways (free_switch_gateway_name);
CREATE INDEX idx_sip_gateways_status ON sip_gateways (status);

-- Allocation / access assignments
CREATE TABLE sip_gateway_allocations (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    gateway_id                  UUID NOT NULL REFERENCES sip_gateways (id),
    reseller_id                 UUID,
    tenant_id                   UUID,
    enabled                     BOOLEAN NOT NULL DEFAULT TRUE,
    priority                    INTEGER NOT NULL DEFAULT 50
                                CONSTRAINT ck_sip_alloc_priority CHECK (priority BETWEEN 0 AND 100),
    max_concurrent_channels     INTEGER
                                CONSTRAINT ck_sip_alloc_capacity CHECK (max_concurrent_channels IS NULL OR max_concurrent_channels > 0),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(255),
    updated_by                  VARCHAR(255),
    deleted_at                  TIMESTAMPTZ,
    deleted_by                  VARCHAR(255),
    CONSTRAINT ck_sip_alloc_target CHECK (reseller_id IS NOT NULL OR tenant_id IS NOT NULL),
    CONSTRAINT ck_sip_alloc_capacity_limit CHECK (
        max_concurrent_channels IS NULL OR max_concurrent_channels > 0
    )
);

CREATE INDEX idx_sip_alloc_gateway ON sip_gateway_allocations (gateway_id);
CREATE INDEX idx_sip_alloc_reseller ON sip_gateway_allocations (reseller_id);
CREATE INDEX idx_sip_alloc_tenant ON sip_gateway_allocations (tenant_id);
CREATE INDEX idx_sip_alloc_enabled_deleted ON sip_gateway_allocations (enabled, deleted_at);
CREATE INDEX idx_sip_alloc_gateway_enabled ON sip_gateway_allocations (gateway_id, enabled);

-- Prevent duplicate active allocations: per (gateway, tenant) and per (gateway, reseller)
CREATE UNIQUE INDEX uq_sip_alloc_gateway_tenant_live
    ON sip_gateway_allocations (gateway_id, tenant_id)
    WHERE tenant_id IS NOT NULL AND reseller_id IS NULL AND deleted_at IS NULL AND enabled = TRUE;

CREATE UNIQUE INDEX uq_sip_alloc_gateway_reseller_live
    ON sip_gateway_allocations (gateway_id, reseller_id)
    WHERE reseller_id IS NOT NULL AND tenant_id IS NULL AND deleted_at IS NULL AND enabled = TRUE;

CREATE UNIQUE INDEX uq_sip_alloc_gateway_both_live
    ON sip_gateway_allocations (gateway_id, reseller_id, tenant_id)
    WHERE reseller_id IS NOT NULL AND tenant_id IS NOT NULL AND deleted_at IS NULL AND enabled = TRUE;
