-- =====================================================================
-- Organizational Home ↔ Membership organization-consistency enforcement
-- (Phase 2E)
--
-- V8 already guarantees that a membership references a home of the
-- correct TYPE. This migration closes the remaining gap: the home's
-- organization_id must be THE SAME organization as the membership's.
--
--   TENANT home:    organizational_homes.organization_id
--                     = tenant_memberships.tenant_id
--   RESELLER home:  organizational_homes.organization_id
--                     = reseller_memberships.reseller_id
--
-- Additionally, role scope is pinned to membership type so a tenant
-- membership can never carry a reseller-scoped role and vice versa.
--
-- Enforced by PostgreSQL triggers so the invariant holds regardless of
-- whether the write originates from the application, SQL, tooling, or
-- any future service.
--
-- Forward-only; V1-V10 remain untouched.
-- =====================================================================

-- -------------------------------------------------------------------
-- Trigger function: tenant_memberships.organization_home must point at
-- the SAME tenant as the membership itself
-- -------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_tenant_membership_home_organization()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM organizational_homes h
        WHERE h.id = NEW.organizational_home_id
          AND h.home_type = 'TENANT'
          AND h.organization_id <> NEW.tenant_id
    ) THEN
        RAISE EXCEPTION
            'Tenant membership % references organizational_home % whose organization does not match tenant %',
            NEW.id, NEW.organizational_home_id, NEW.tenant_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenant_membership_home_organization
    BEFORE INSERT OR UPDATE OF organizational_home_id, tenant_id ON tenant_memberships
    FOR EACH ROW
    EXECUTE FUNCTION check_tenant_membership_home_organization();


-- -------------------------------------------------------------------
-- Trigger function: reseller_memberships.organization_home must point
-- at the SAME reseller as the membership itself
-- -------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_reseller_membership_home_organization()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM organizational_homes h
        WHERE h.id = NEW.organizational_home_id
          AND h.home_type = 'RESELLER'
          AND h.organization_id <> NEW.reseller_id
    ) THEN
        RAISE EXCEPTION
            'Reseller membership % references organizational_home % whose organization does not match reseller %',
            NEW.id, NEW.organizational_home_id, NEW.reseller_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reseller_membership_home_organization
    BEFORE INSERT OR UPDATE OF organizational_home_id, reseller_id ON reseller_memberships
    FOR EACH ROW
    EXECUTE FUNCTION check_reseller_membership_home_organization();


-- -------------------------------------------------------------------
-- Trigger function: moving/retargeting a home's organization_id is
-- rejected while a matching membership still points at it with a
-- different organization (would strand the membership elsewhere)
-- -------------------------------------------------------------------
CREATE OR REPLACE FUNCTION prevent_home_organization_change_breaking_memberships()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.organization_id <> OLD.organization_id THEN
        IF NEW.home_type = 'TENANT' AND EXISTS (
            SELECT 1 FROM tenant_memberships m
            WHERE m.organizational_home_id = NEW.id
              AND m.tenant_id <> NEW.organization_id
        ) THEN
            RAISE EXCEPTION
                'Cannot retarget organizational_home % to %: tenant membership points elsewhere',
                NEW.id, NEW.organization_id;
        END IF;
        IF NEW.home_type = 'RESELLER' AND EXISTS (
            SELECT 1 FROM reseller_memberships m
            WHERE m.organizational_home_id = NEW.id
              AND m.reseller_id <> NEW.organization_id
        ) THEN
            RAISE EXCEPTION
                'Cannot retarget organizational_home % to %: reseller membership points elsewhere',
                NEW.id, NEW.organization_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_home_organization_change
    BEFORE UPDATE OF organization_id ON organizational_homes
    FOR EACH ROW
    EXECUTE FUNCTION prevent_home_organization_change_breaking_memberships();


-- -------------------------------------------------------------------
-- Trigger functions: role scope must match membership type
--   tenant_memberships  -> roles.scope IN ('TENANT', 'ASSIGNED')
--   reseller_memberships-> roles.scope = 'RESELLER'
-- -------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_tenant_membership_role_scope()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM roles r
        WHERE r.id = NEW.role_id
          AND r.scope NOT IN ('TENANT', 'ASSIGNED')
    ) THEN
        RAISE EXCEPTION
            'Tenant membership % references role % whose scope is not TENANT/ASSIGNED',
            NEW.id, NEW.role_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenant_membership_role_scope
    BEFORE INSERT OR UPDATE OF role_id ON tenant_memberships
    FOR EACH ROW
    EXECUTE FUNCTION check_tenant_membership_role_scope();


CREATE OR REPLACE FUNCTION check_reseller_membership_role_scope()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM roles r
        WHERE r.id = NEW.role_id
          AND r.scope <> 'RESELLER'
    ) THEN
        RAISE EXCEPTION
            'Reseller membership % references role % whose scope is not RESELLER',
            NEW.id, NEW.role_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reseller_membership_role_scope
    BEFORE INSERT OR UPDATE OF role_id ON reseller_memberships
    FOR EACH ROW
    EXECUTE FUNCTION check_reseller_membership_role_scope();
