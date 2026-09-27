-- =====================================================================
-- Align remaining audit-tracked tables with AuditableEntity.
--
-- V10 added deleted_by to users/tenants/resellers/campaigns only. The
-- provisioning phase performs real JPA INSERT/SELECTs against every
-- AuditableEntity-mapped table, so the schema must carry the full audit
-- column set everywhere the entity model expects it. This migration
-- completes that alignment; V1-V11 remain untouched.
-- =====================================================================

ALTER TABLE user_credentials    ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(255);
ALTER TABLE roles               ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(255);
ALTER TABLE capabilities        ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(255);
ALTER TABLE role_capabilities   ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(255);
ALTER TABLE reseller_memberships ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(255);
ALTER TABLE tenant_memberships  ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(255);
