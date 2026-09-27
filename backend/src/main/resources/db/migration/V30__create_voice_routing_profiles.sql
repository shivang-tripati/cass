-- =====================================================================
-- Voice Routing Profiles (Phase VB-0A)
-- Forward-only; V1-V29 remain untouched.
-- =====================================================================

CREATE TYPE voice_route_type AS ENUM ('PRIMARY', 'OVERFLOW', 'FAILOVER');

-- Routing policy profiles
CREATE TABLE voice_route_profiles (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id),
    reseller_id             UUID REFERENCES resellers (id),
    name                    VARCHAR(100) NOT NULL,
    description             VARCHAR(500),
    auto_overflow_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    auto_failover_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    updated_by              VARCHAR(255),
    deleted_at              TIMESTAMPTZ,
    deleted_by              VARCHAR(255)
);

CREATE INDEX idx_voice_route_profiles_tenant_deleted ON voice_route_profiles (tenant_id, deleted_at);
CREATE INDEX idx_voice_route_profiles_reseller_deleted ON voice_route_profiles (reseller_id, deleted_at);
CREATE INDEX idx_voice_route_profiles_name_tenant ON voice_route_profiles (name, tenant_id, deleted_at);

-- Route entries within a profile
CREATE TABLE voice_route_profile_entries (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id              UUID NOT NULL REFERENCES voice_route_profiles (id) ON DELETE CASCADE,
    route_type              voice_route_type NOT NULL,
    priority                INTEGER NOT NULL DEFAULT 50,
    gateway_id              UUID NOT NULL REFERENCES sip_gateways (id),
    did_id                  UUID REFERENCES dids (id),
    enabled                 BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    updated_by              VARCHAR(255),
    deleted_at              TIMESTAMPTZ,
    deleted_by              VARCHAR(255)
);

CREATE INDEX idx_voice_route_entries_profile ON voice_route_profile_entries (profile_id);
CREATE INDEX idx_voice_route_entries_type_priority ON voice_route_profile_entries (route_type, priority);

-- Channel capacity reservations (for tracking active calls per gateway)
CREATE TABLE voice_channel_reservations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    gateway_id              UUID NOT NULL REFERENCES sip_gateways (id),
    tenant_id               UUID NOT NULL REFERENCES tenants (id),
    reserved_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_voice_channel_reservations_gateway_active ON voice_channel_reservations (gateway_id, released_at);
CREATE INDEX idx_voice_channel_reservations_tenant_active ON voice_channel_reservations (tenant_id, released_at);