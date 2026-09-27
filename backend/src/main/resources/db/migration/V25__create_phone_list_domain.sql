-- =====================================================================
-- Phone List / Call Eligibility domain (Phase 2Y)
-- Forward-only; V1-V24 remain untouched.
-- =====================================================================

CREATE TYPE phone_list_type AS ENUM (
    'PLATFORM_BLOCKLIST',
    'PLATFORM_PROTECTED',
    'RESELLER_BLOCKLIST',
    'RESELLER_WHITELIST',
    'TENANT_BLOCKLIST',
    'TENANT_WHITELIST'
);

CREATE TYPE scope_type AS ENUM (
    'PLATFORM',
    'RESELLER',
    'TENANT'
);

CREATE TABLE phone_lists (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type                phone_list_type NOT NULL,
    scope_type          scope_type NOT NULL,
    scope_reseller_id   UUID,
    scope_tenant_id     UUID,
    normalized_number   VARCHAR(20) NOT NULL,
    original_number     VARCHAR(30),
    reason              TEXT,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    created_by          VARCHAR(255),
    updated_by          VARCHAR(255),
    deleted_at          TIMESTAMPTZ,
    deleted_by          VARCHAR(255),
    CONSTRAINT ck_phone_lists_scope_consistency CHECK (
        (scope_type = 'PLATFORM' AND scope_reseller_id IS NULL AND scope_tenant_id IS NULL)
        OR (scope_type = 'RESELLER' AND scope_reseller_id IS NOT NULL AND scope_tenant_id IS NULL)
        OR (scope_type = 'TENANT' AND scope_tenant_id IS NOT NULL)
    )
);

-- Active block/allow lookups per number
CREATE UNIQUE INDEX uq_phone_lists_number_type_scope_active
    ON phone_lists (normalized_number, type, scope_type, scope_reseller_id, scope_tenant_id, active, deleted_at)
    WHERE active = TRUE AND deleted_at IS NULL;

CREATE INDEX idx_phone_lists_number_type_scope ON phone_lists (normalized_number, type, scope_type, scope_reseller_id, scope_tenant_id, active, deleted_at);
CREATE INDEX idx_phone_lists_scope ON phone_lists (scope_type, scope_reseller_id, scope_tenant_id);
CREATE INDEX idx_phone_lists_type_active ON phone_lists (type, active, deleted_at);