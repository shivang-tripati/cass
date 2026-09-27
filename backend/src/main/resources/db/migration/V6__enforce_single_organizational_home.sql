-- =====================================================================
-- Enforce single organizational home per user (Phase 2C correction)
--
-- A user may have at most ONE active tenant membership and at most ONE
-- active reseller membership. Cross-table exclusion (tenant + reseller)
-- is enforced by application-level validation in RoleAssignmentPolicy.
--
-- Forward-only; V1-V5 remain untouched.
-- =====================================================================

CREATE UNIQUE INDEX uq_tenant_memberships_one_active_per_user
    ON tenant_memberships (user_id)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;

CREATE UNIQUE INDEX uq_reseller_memberships_one_active_per_user
    ON reseller_memberships (user_id)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;
