-- =====================================================================
-- Contact bulk-import support (Phase 2J.1)
-- Forward-only; V1-V17 remain untouched.
--
-- The import contract defines phoneNumber as the only mandatory field,
-- so first_name becomes optional. No other changes.
-- =====================================================================

ALTER TABLE contacts ALTER COLUMN first_name DROP NOT NULL;
