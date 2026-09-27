-- =====================================================================
-- DID (phone number) management domain (Phase 2I)
-- Forward-only; V1-V15 remain untouched.
--
-- Also aligns the Campaign DID reference naming introduced as a
-- placeholder in Phase 2G (number_inventory_id -> did_id); no data
-- changes, campaigns.did_id continues to point at the owning module.
-- =====================================================================

CREATE TABLE dids (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID REFERENCES tenants (id),
    reseller_id      UUID REFERENCES resellers (id),
    e164_number      VARCHAR(20) NOT NULL
                     CONSTRAINT ck_dids_e164_format
                     CHECK (e164_number ~ '^\+[1-9][0-9]{6,14}$'),
    country_code     VARCHAR(3) NOT NULL,
    area_code        VARCHAR(10),
    circle           VARCHAR(100),
    number_type      VARCHAR(30) NOT NULL
                     CONSTRAINT ck_dids_number_type
                     CHECK (number_type IN ('LANDLINE', 'MOBILE', 'PROMOTIONAL_140')),
    provider         VARCHAR(50) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                     CONSTRAINT ck_dids_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    -- Intentionally extensible typed set serialized as a JSON array of
    -- enum names (same convention as campaigns.allowed_days_of_week);
    -- starts with VOICE_OUTBOUND only.
    capabilities     JSONB,
    allocation_state VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
                     CONSTRAINT ck_dids_allocation_state
                     CHECK (allocation_state IN ('AVAILABLE', 'ASSIGNED')),
    -- An ASSIGNED DID must belong to exactly one tenant; pool numbers
    -- (AVAILABLE) may be unassigned or held under a reseller.
    CONSTRAINT ck_dids_assigned_requires_tenant
        CHECK (allocation_state <> 'ASSIGNED' OR tenant_id IS NOT NULL),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ,
    created_by       VARCHAR(255),
    updated_by       VARCHAR(255),
    deleted_at       TIMESTAMPTZ,
    deleted_by       VARCHAR(255)
);

-- The canonical number is unique among live records; soft deletion
-- releases the number for future reuse.
CREATE UNIQUE INDEX uq_dids_e164_live ON dids (e164_number) WHERE deleted_at IS NULL;

CREATE INDEX idx_dids_tenant_deleted ON dids (tenant_id, deleted_at);
CREATE INDEX idx_dids_reseller ON dids (reseller_id);
CREATE INDEX idx_dids_status ON dids (status);

ALTER TABLE campaigns RENAME COLUMN number_inventory_id TO did_id;
DROP INDEX IF EXISTS idx_campaigns_number_inventory;
CREATE INDEX idx_campaigns_did ON campaigns (did_id);

-- Capability catalog extension (stable keys continuing the V1 sequence).
INSERT INTO capabilities (id, key, resource, action, description, active, created_at, created_by) VALUES
    ('cab1e100-0000-4000-8000-000000000028', 'DID_VIEW',   'DID', 'VIEW',   'View DID numbers and their allocation state.', TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000029', 'DID_MANAGE', 'DID', 'MANAGE', 'Register and manage DID numbers.',             TRUE, now(), 'system');

-- Role grants: platform/reseller/tenant administrators manage DIDs;
-- agents intentionally receive none (fail-closed).
INSERT INTO role_capabilities (id, role_id, capability_id, created_at, created_by)
SELECT gen_random_uuid(), r.id, c.id, now(), 'system'
FROM roles r
JOIN capabilities c ON c.key = ANY (ARRAY ['DID_VIEW', 'DID_MANAGE'])
WHERE r.key IN ('SUPER_ADMIN', 'RESELLER_ADMIN', 'TENANT_ADMIN')
  AND NOT EXISTS (
      SELECT 1 FROM role_capabilities rc
      WHERE rc.role_id = r.id AND rc.capability_id = c.id);
