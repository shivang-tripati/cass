-- =====================================================================
-- Platform role assignments (Phase 2E)
--
-- SUPER_ADMIN holds NO organizational home and NO reseller/tenant
-- membership: its authority flows purely through the PLATFORM-scoped
-- role. This table is that explicit, auditable user-to-platform-role
-- link consumed by the authz RoleAssignmentReader port.
--
-- One active platform role per user (unique user_id), mirroring the
-- exactly-one-organizational-binding invariant.
--
-- Forward-only; V1-V12 remain untouched.
-- =====================================================================

CREATE TABLE platform_role_assignments (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id    UUID NOT NULL REFERENCES roles (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(255),
    CONSTRAINT uq_platform_role_assignments_user UNIQUE (user_id)
);

CREATE INDEX idx_platform_role_assignments_role ON platform_role_assignments (role_id);
