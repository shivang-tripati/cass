-- =====================================================================
-- Add deleted_by column to audit-tracked tables.
-- Aligns database schema with AuditableEntity soft-delete audit trail.
-- Forward-only; V1-V8 remain untouched.
-- =====================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(255);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(255);
ALTER TABLE resellers ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(255);
ALTER TABLE campaigns ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(255);
