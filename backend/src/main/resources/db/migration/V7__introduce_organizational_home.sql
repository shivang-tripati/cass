-- =====================================================================
-- Organizational Home (Phase 2C correction)
--
-- The authoritative user→organization relationship. Each user has at
-- most one home; memberships reference their home rather than existing
-- as independent user→org links.
--
-- Forward-only; V1-V6 remain untouched.
-- =====================================================================

CREATE TABLE organizational_homes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    home_type       VARCHAR(20) NOT NULL
                    CONSTRAINT ck_org_homes_type CHECK (home_type IN ('TENANT', 'RESELLER')),
    organization_id UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ
);

CREATE INDEX idx_org_homes_organization ON organizational_homes (organization_id);

ALTER TABLE tenant_memberships
    ADD COLUMN organizational_home_id UUID;

ALTER TABLE reseller_memberships
    ADD COLUMN organizational_home_id UUID;

ALTER TABLE tenant_memberships
    ALTER COLUMN organizational_home_id SET NOT NULL,
    ADD CONSTRAINT fk_tenant_memberships_org_home
        FOREIGN KEY (organizational_home_id)
        REFERENCES organizational_homes (id) ON DELETE CASCADE;

ALTER TABLE reseller_memberships
    ALTER COLUMN organizational_home_id SET NOT NULL,
    ADD CONSTRAINT fk_reseller_memberships_org_home
        FOREIGN KEY (organizational_home_id)
        REFERENCES organizational_homes (id) ON DELETE CASCADE;

CREATE UNIQUE INDEX uq_tenant_membership_one_per_home
    ON tenant_memberships (organizational_home_id);

CREATE UNIQUE INDEX uq_reseller_membership_one_per_home
    ON reseller_memberships (organizational_home_id);
