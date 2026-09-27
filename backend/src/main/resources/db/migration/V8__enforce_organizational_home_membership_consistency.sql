-- =====================================================================
-- Organizational Home ↔ Membership type-consistency enforcement.
--
-- Security invariant: a TENANT organizational home may only be referenced
-- by tenant_memberships; a RESELLER organizational home may only be
-- referenced by reseller_memberships. Additionally, organizational_homes
-- cannot change home_type while memberships exist (which would strand them).
--
-- These are enforced by PostgreSQL triggers so the invariant holds
-- regardless of whether the write originates from the application, SQL,
-- migration tooling, or any future service.
--
-- Forward-only; V1-V7 remain untouched.
-- =====================================================================

-- -------------------------------------------------------------------
-- Trigger function: tenant_memberships must reference a TENANT home
-- -------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_tenant_membership_home_type()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM organizational_homes
        WHERE id = NEW.organizational_home_id
          AND home_type = 'TENANT'
    ) THEN
        RAISE EXCEPTION
            'Tenant membership % references organizational_home % which is not of type TENANT',
            NEW.id, NEW.organizational_home_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenant_membership_home_type
    BEFORE INSERT OR UPDATE OF organizational_home_id ON tenant_memberships
    FOR EACH ROW
    EXECUTE FUNCTION check_tenant_membership_home_type();


-- -------------------------------------------------------------------
-- Trigger function: reseller_memberships must reference a RESELLER home
-- -------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_reseller_membership_home_type()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM organizational_homes
        WHERE id = NEW.organizational_home_id
          AND home_type = 'RESELLER'
    ) THEN
        RAISE EXCEPTION
            'Reseller membership % references organizational_home % which is not of type RESELLER',
            NEW.id, NEW.organizational_home_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reseller_membership_home_type
    BEFORE INSERT OR UPDATE OF organizational_home_id ON reseller_memberships
    FOR EACH ROW
    EXECUTE FUNCTION check_reseller_membership_home_type();


-- -------------------------------------------------------------------
-- Trigger function: prevent changing home_type when memberships exist
-- (would strand existing memberships under an incompatible home type)
-- -------------------------------------------------------------------
CREATE OR REPLACE FUNCTION prevent_incompatible_home_type_change()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.home_type != OLD.home_type THEN
        IF OLD.home_type = 'TENANT' AND EXISTS (
            SELECT 1 FROM tenant_memberships WHERE organizational_home_id = NEW.id
        ) THEN
            RAISE EXCEPTION
                'Cannot change organizational_home % from TENANT to RESELLER: tenant membership exists',
                NEW.id;
        END IF;
        IF OLD.home_type = 'RESELLER' AND EXISTS (
            SELECT 1 FROM reseller_memberships WHERE organizational_home_id = NEW.id
        ) THEN
            RAISE EXCEPTION
                'Cannot change organizational_home % from RESELLER to TENANT: reseller membership exists',
                NEW.id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_home_type_change
    BEFORE UPDATE OF home_type ON organizational_homes
    FOR EACH ROW
    EXECUTE FUNCTION prevent_incompatible_home_type_change();
