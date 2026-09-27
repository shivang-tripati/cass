-- =====================================================================
-- Contact Group / Contact domain (Phase 2J)
-- Forward-only; V1-V16 remain untouched.
--
-- Contact groups are tenant-owned aggregates referenced by Campaign via
-- campaigns.contact_group_id. Contacts always inherit their group's
-- tenant so the Campaign.tenant == ContactGroup.tenant invariant holds
-- transitively.
-- =====================================================================

CREATE TABLE contact_groups (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants (id),
    name        VARCHAR(150) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    deleted_at  TIMESTAMPTZ,
    deleted_by  VARCHAR(255)
);

CREATE INDEX idx_contact_groups_tenant_deleted ON contact_groups (tenant_id, deleted_at);

CREATE TABLE contacts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenants (id),
    contact_group_id UUID NOT NULL REFERENCES contact_groups (id),
    first_name       VARCHAR(100) NOT NULL,
    last_name        VARCHAR(100),
    -- Canonical E.164 number used by the future dialer/TTS variable
    -- substitution; same format contract as dids.e164_number.
    phone_number     VARCHAR(20) NOT NULL
                     CONSTRAINT ck_contacts_phone_format
                     CHECK (phone_number ~ '^\+[1-9][0-9]{6,14}$'),
    email            VARCHAR(255),
    -- Genuinely dynamic per-contact context for future template-variable
    -- substitution (e.g. {"orderId": "ORD-12345"}); extension point only.
    attributes       JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ,
    created_by       VARCHAR(255),
    updated_by       VARCHAR(255),
    deleted_at       TIMESTAMPTZ,
    deleted_by       VARCHAR(255)
);

CREATE INDEX idx_contacts_group_deleted ON contacts (contact_group_id, deleted_at);
CREATE INDEX idx_contacts_tenant_deleted ON contacts (tenant_id, deleted_at);
