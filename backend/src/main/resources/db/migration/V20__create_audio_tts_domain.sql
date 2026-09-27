-- =====================================================================
-- Audio / TTS content domain (Phase 2K)
-- Forward-only; V1-V19 remain untouched.
--
-- Campaign references these rows by opaque UUID (audio_asset_id /
-- tts_template_id); ownership and approval are enforced at the
-- application boundary, so no cross-module foreign keys exist.
-- =====================================================================

CREATE TABLE audio_assets (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenants (id),
    name              VARCHAR(150) NOT NULL,
    description       TEXT,
    file_name         VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    file_size         BIGINT NOT NULL
                      CONSTRAINT ck_audio_assets_size CHECK (file_size > 0),
    duration_seconds  INTEGER
                      CONSTRAINT ck_audio_assets_duration
                      CHECK (duration_seconds IS NULL OR duration_seconds > 0),
    -- SHA-256 hex of the media payload when known; optional until real
    -- storage/upload wiring lands (deferred).
    checksum          VARCHAR(64),
    -- Logical storage location (future object-store key). The binary
    -- transfer pipeline is intentionally out of scope for this phase.
    storage_reference VARCHAR(500),
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING_APPROVAL'
                      CONSTRAINT ck_audio_assets_status
                      CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ,
    created_by        VARCHAR(255),
    updated_by        VARCHAR(255),
    deleted_at        TIMESTAMPTZ,
    deleted_by        VARCHAR(255)
);

CREATE INDEX idx_audio_assets_tenant_deleted ON audio_assets (tenant_id, deleted_at);

CREATE TABLE tts_templates (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenants (id),
    name          VARCHAR(150) NOT NULL,
    description   TEXT,
    template_text TEXT NOT NULL,
    -- Typed variable schema serialized as a JSONB array of
    -- {"name","type","required"} objects (same persistence convention as
    -- campaigns.allowed_days_of_week).
    variables     JSONB,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING_APPROVAL'
                  CONSTRAINT ck_tts_templates_status
                  CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    created_by    VARCHAR(255),
    updated_by    VARCHAR(255),
    deleted_at    TIMESTAMPTZ,
    deleted_by    VARCHAR(255)
);

CREATE INDEX idx_tts_templates_tenant_deleted ON tts_templates (tenant_id, deleted_at);

-- TTS capability catalog extension (stable keys continuing the sequence;
-- grant pattern mirrors the AUDIO capabilities from V1).
INSERT INTO capabilities (id, key, resource, action, description, active, created_at, created_by) VALUES
    ('cab1e100-0000-4000-8000-000000000030', 'TTS_VIEW',    'TTS', 'VIEW',    'View TTS templates.',                          TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000031', 'TTS_MANAGE',  'TTS', 'MANAGE',  'Create and manage TTS templates.',             TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000032', 'TTS_APPROVE', 'TTS', 'APPROVE', 'Approve or reject TTS templates for use.',     TRUE, now(), 'system');

INSERT INTO role_capabilities (id, role_id, capability_id, created_at, created_by)
SELECT gen_random_uuid(), r.id, c.id, now(), 'system'
FROM roles r
JOIN capabilities c ON c.key = ANY (ARRAY ['TTS_VIEW', 'TTS_MANAGE', 'TTS_APPROVE'])
WHERE r.key IN ('SUPER_ADMIN', 'RESELLER_ADMIN', 'TENANT_ADMIN')
  AND NOT EXISTS (
      SELECT 1 FROM role_capabilities rc
      WHERE rc.role_id = r.id AND rc.capability_id = c.id);
