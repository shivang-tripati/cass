-- =====================================================================
-- Multi-tenancy, reseller, membership, role & capability foundation
-- Forward-only baseline for the OBD CPaaS platform.
-- Seed identifiers are fixed literals so every environment is identical.
-- =====================================================================

CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL,
    display_name VARCHAR(120),
    status      VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE'
                CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    deleted_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));
CREATE INDEX idx_users_status ON users (status);


CREATE TABLE resellers (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(150) NOT NULL,
    slug          VARCHAR(100) NOT NULL UNIQUE,
    display_name  VARCHAR(150),
    status        VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE'
                  CONSTRAINT ck_resellers_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    support_email VARCHAR(255),
    custom_domain VARCHAR(255),
    logo_url      VARCHAR(500),
    primary_color VARCHAR(20),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    created_by    VARCHAR(255),
    updated_by    VARCHAR(255),
    deleted_at    TIMESTAMPTZ,
    CONSTRAINT uq_resellers_custom_domain UNIQUE (custom_domain)
);


CREATE TABLE tenants (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(150) NOT NULL,
    slug        VARCHAR(100) NOT NULL UNIQUE,
    status      VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE'
                CONSTRAINT ck_tenants_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    reseller_id UUID REFERENCES resellers (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    deleted_at  TIMESTAMPTZ
);

CREATE INDEX idx_tenants_reseller ON tenants (reseller_id);


CREATE TABLE roles (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key            VARCHAR(80)  NOT NULL UNIQUE,
    name           VARCHAR(120) NOT NULL,
    description    VARCHAR(300),
    scope          VARCHAR(20)  NOT NULL
                   CONSTRAINT ck_roles_scope CHECK (scope IN ('PLATFORM', 'RESELLER', 'TENANT', 'OWN', 'ASSIGNED')),
    system_defined BOOLEAN      NOT NULL DEFAULT FALSE,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ,
    created_by     VARCHAR(255),
    updated_by     VARCHAR(255),
    deleted_at     TIMESTAMPTZ
);

CREATE INDEX idx_roles_scope ON roles (scope);


CREATE TABLE capabilities (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key         VARCHAR(100) NOT NULL UNIQUE,
    resource    VARCHAR(60)  NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    description VARCHAR(300),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT uq_capabilities_resource_action UNIQUE (resource, action)
);

CREATE INDEX idx_capabilities_resource ON capabilities (resource);


CREATE TABLE role_capabilities (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_id       UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    capability_id UUID NOT NULL REFERENCES capabilities (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(255),
    CONSTRAINT uq_role_capabilities_role_capability UNIQUE (role_id, capability_id)
);

CREATE INDEX idx_role_capabilities_role ON role_capabilities (role_id);
CREATE INDEX idx_role_capabilities_capability ON role_capabilities (capability_id);


CREATE TABLE reseller_memberships (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reseller_id UUID NOT NULL REFERENCES resellers (id) ON DELETE CASCADE,
    role_id     UUID NOT NULL REFERENCES roles (id),
    status      VARCHAR(30) NOT NULL DEFAULT 'ACTIVE'
                CONSTRAINT ck_reseller_memberships_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT uq_reseller_memberships_user_reseller UNIQUE (user_id, reseller_id)
);

CREATE INDEX idx_reseller_memberships_user ON reseller_memberships (user_id);
CREATE INDEX idx_reseller_memberships_reseller ON reseller_memberships (reseller_id);
CREATE INDEX idx_reseller_memberships_role ON reseller_memberships (role_id);


CREATE TABLE tenant_memberships (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tenant_id   UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    role_id     UUID NOT NULL REFERENCES roles (id),
    status      VARCHAR(30) NOT NULL DEFAULT 'ACTIVE'
                CONSTRAINT ck_tenant_memberships_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT uq_tenant_memberships_user_tenant UNIQUE (user_id, tenant_id)
);

CREATE INDEX idx_tenant_memberships_user ON tenant_memberships (user_id);
CREATE INDEX idx_tenant_memberships_tenant ON tenant_memberships (tenant_id);
CREATE INDEX idx_tenant_memberships_role ON tenant_memberships (role_id);


-- =====================================================================
-- SYSTEM ROLES (deterministic identifiers, never environment-dependent)
-- =====================================================================

INSERT INTO roles (id, key, name, description, scope, system_defined, active, created_at, created_by) VALUES
    ('501e0000-0000-4000-8000-000000000001', 'SUPER_ADMIN',    'Super Admin',    'Platform-wide administration across all resellers and tenants.', 'PLATFORM', TRUE, TRUE, now(), 'system'),
    ('501e0000-0000-4000-8000-000000000002', 'RESELLER_ADMIN', 'Reseller Admin', 'Administration of one reseller and its managed tenants.',         'RESELLER', TRUE, TRUE, now(), 'system'),
    ('501e0000-0000-4000-8000-000000000003', 'TENANT_ADMIN',   'Tenant Admin',   'Full standard administration of a single tenant.',                'TENANT',   TRUE, TRUE, now(), 'system'),
    ('501e0000-0000-4000-8000-000000000004', 'AGENT',          'Agent',          'Operates assigned calls and records dispositions.',               'ASSIGNED', TRUE, TRUE, now(), 'system'),
    ('501e0000-0000-4000-8000-000000000005', 'REPORT_VIEWER',  'Report Viewer',  'Read-only reporting access within a single tenant.',              'TENANT',   TRUE, TRUE, now(), 'system');


-- =====================================================================
-- INITIAL CAPABILITY CATALOG (resource + business action, stable keys)
-- =====================================================================

INSERT INTO capabilities (id, key, resource, action, description, active, created_at, created_by) VALUES
    ('cab1e100-0000-4000-8000-000000000001', 'TENANT_VIEW',       'TENANT',   'VIEW',       'View tenant profile and settings.',                 TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000002', 'TENANT_MANAGE',     'TENANT',   'MANAGE',     'Create and administer tenants.',                    TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000003', 'RESELLER_VIEW',     'RESELLER', 'VIEW',       'View reseller profile and configuration.',          TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000004', 'RESELLER_MANAGE',   'RESELLER', 'MANAGE',     'Create and administer resellers.',                  TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000005', 'USER_VIEW',         'USER',     'VIEW',       'View users and memberships.',                       TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000006', 'USER_MANAGE',       'USER',     'MANAGE',     'Invite users and manage memberships and roles.',    TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000007', 'ROLE_VIEW',         'ROLE',     'VIEW',       'View roles and their capabilities.',                TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000008', 'ROLE_MANAGE',       'ROLE',     'MANAGE',     'Define and edit custom roles.',                     TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000009', 'CAMPAIGN_VIEW',     'CAMPAIGN', 'VIEW',       'View campaigns and their progress.',                TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000010', 'CAMPAIGN_MANAGE',   'CAMPAIGN', 'MANAGE',     'Create, edit and configure campaigns.',             TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000011', 'CAMPAIGN_EXECUTE',  'CAMPAIGN', 'EXECUTE',    'Start, stop, pause and resume campaigns.',          TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000012', 'CAMPAIGN_ASSIGN',   'CAMPAIGN', 'ASSIGN',     'Assign agents or queues to campaigns.',             TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000013', 'CAMPAIGN_EXPORT',   'CAMPAIGN', 'EXPORT',     'Export campaign data.',                             TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000014', 'CONTACT_VIEW',      'CONTACT',  'VIEW',       'View contacts and contact groups.',                 TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000015', 'CONTACT_MANAGE',    'CONTACT',  'MANAGE',     'Create, edit and delete contacts and groups.',      TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000016', 'CONTACT_IMPORT',    'CONTACT',  'IMPORT',     'Import contacts from files.',                       TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000017', 'CONTACT_EXPORT',    'CONTACT',  'EXPORT',     'Export contacts.',                                  TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000018', 'AUDIO_VIEW',        'AUDIO',    'VIEW',       'View audio library entries.',                       TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000019', 'AUDIO_MANAGE',      'AUDIO',    'MANAGE',     'Upload and edit audio assets.',                     TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000020', 'AUDIO_APPROVE',     'AUDIO',    'APPROVE',    'Approve or reject uploaded audio.',                 TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000021', 'CALL_VIEW',         'CALL',     'VIEW',       'View call logs and live call state.',               TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000022', 'CALL_DISPOSITION',  'CALL',     'DISPOSITION','Record call outcomes and dispositions.',            TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000023', 'AGENT_VIEW',        'AGENT',    'VIEW',       'View agent profiles and states.',                   TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000024', 'AGENT_MANAGE',      'AGENT',    'MANAGE',     'Create and administer agent profiles.',             TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000025', 'AGENT_ASSIGN',      'AGENT',    'ASSIGN',     'Assign agents to campaigns or queues.',             TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000026', 'REPORT_VIEW',       'REPORT',   'VIEW',       'View reports and analytics.',                       TRUE, now(), 'system'),
    ('cab1e100-0000-4000-8000-000000000027', 'REPORT_EXPORT',     'REPORT',   'EXPORT',     'Export reports as CSV/Excel.',                      TRUE, now(), 'system');


-- =====================================================================
-- ROLE -> CAPABILITY MAPPINGS (joined by stable keys, rerun-safe)
-- =====================================================================

INSERT INTO role_capabilities (id, role_id, capability_id, created_at, created_by)
SELECT gen_random_uuid(), r.id, c.id, now(), 'system'
FROM roles r
JOIN capabilities c ON c.key = ANY (ARRAY [
    'TENANT_VIEW','TENANT_MANAGE',
    'RESELLER_VIEW','RESELLER_MANAGE',
    'USER_VIEW','USER_MANAGE',
    'ROLE_VIEW','ROLE_MANAGE',
    'CAMPAIGN_VIEW','CAMPAIGN_MANAGE','CAMPAIGN_EXECUTE','CAMPAIGN_ASSIGN','CAMPAIGN_EXPORT',
    'CONTACT_VIEW','CONTACT_MANAGE','CONTACT_IMPORT','CONTACT_EXPORT',
    'AUDIO_VIEW','AUDIO_MANAGE','AUDIO_APPROVE',
    'CALL_VIEW','CALL_DISPOSITION',
    'AGENT_VIEW','AGENT_MANAGE','AGENT_ASSIGN',
    'REPORT_VIEW','REPORT_EXPORT'
])
WHERE r.key = 'SUPER_ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM role_capabilities rc
      WHERE rc.role_id = r.id AND rc.capability_id = c.id
  );

INSERT INTO role_capabilities (id, role_id, capability_id, created_at, created_by)
SELECT gen_random_uuid(), r.id, c.id, now(), 'system'
FROM roles r
JOIN capabilities c ON c.key = ANY (ARRAY [
    'RESELLER_VIEW','RESELLER_MANAGE',
    'TENANT_VIEW','TENANT_MANAGE',
    'USER_VIEW','USER_MANAGE',
    'ROLE_VIEW',
    'CAMPAIGN_VIEW','CAMPAIGN_MANAGE','CAMPAIGN_EXECUTE','CAMPAIGN_EXPORT',
    'CONTACT_VIEW','CONTACT_MANAGE','CONTACT_IMPORT','CONTACT_EXPORT',
    'AUDIO_VIEW','AUDIO_APPROVE',
    'AGENT_VIEW','AGENT_MANAGE','AGENT_ASSIGN',
    'CALL_VIEW',
    'REPORT_VIEW','REPORT_EXPORT'
])
WHERE r.key = 'RESELLER_ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM role_capabilities rc
      WHERE rc.role_id = r.id AND rc.capability_id = c.id
  );

INSERT INTO role_capabilities (id, role_id, capability_id, created_at, created_by)
SELECT gen_random_uuid(), r.id, c.id, now(), 'system'
FROM roles r
JOIN capabilities c ON c.key = ANY (ARRAY [
    'TENANT_VIEW',
    'USER_VIEW','USER_MANAGE',
    'ROLE_VIEW','ROLE_MANAGE',
    'CAMPAIGN_VIEW','CAMPAIGN_MANAGE','CAMPAIGN_EXECUTE','CAMPAIGN_ASSIGN','CAMPAIGN_EXPORT',
    'CONTACT_VIEW','CONTACT_MANAGE','CONTACT_IMPORT','CONTACT_EXPORT',
    'AUDIO_VIEW','AUDIO_MANAGE','AUDIO_APPROVE',
    'CALL_VIEW','CALL_DISPOSITION',
    'AGENT_VIEW','AGENT_MANAGE','AGENT_ASSIGN',
    'REPORT_VIEW','REPORT_EXPORT'
])
WHERE r.key = 'TENANT_ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM role_capabilities rc
      WHERE rc.role_id = r.id AND rc.capability_id = c.id
  );

INSERT INTO role_capabilities (id, role_id, capability_id, created_at, created_by)
SELECT gen_random_uuid(), r.id, c.id, now(), 'system'
FROM roles r
JOIN capabilities c ON c.key = ANY (ARRAY [
    'CALL_VIEW','CALL_DISPOSITION'
])
WHERE r.key = 'AGENT'
  AND NOT EXISTS (
      SELECT 1 FROM role_capabilities rc
      WHERE rc.role_id = r.id AND rc.capability_id = c.id
  );

INSERT INTO role_capabilities (id, role_id, capability_id, created_at, created_by)
SELECT gen_random_uuid(), r.id, c.id, now(), 'system'
FROM roles r
JOIN capabilities c ON c.key = ANY (ARRAY [
    'REPORT_VIEW','REPORT_EXPORT'
])
WHERE r.key = 'REPORT_VIEWER'
  AND NOT EXISTS (
      SELECT 1 FROM role_capabilities rc
      WHERE rc.role_id = r.id AND rc.capability_id = c.id
  );
