-- =====================================================================
-- Fix call_attempts audit columns (Phase 2O patch)
-- V22 created the table without AuditableEntity fields but the JPA
-- entity extends AuditableEntity (created_at/created_by/updated_at/
-- updated_by). Add them forward-only so Hibernate queries succeed.
-- =====================================================================

ALTER TABLE call_attempts
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Backfill nulls where default not populated for existing rows
UPDATE call_attempts SET created_at = COALESCE(created_at, scheduled_at, now())
WHERE created_at IS NULL;
