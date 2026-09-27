package com.shivang.obd.tts;

/**
 * Ownership scope of a TTS template.
 *
 * <p>GLOBAL templates are platform-owned system/prebuilt templates:
 * {@code tenant_id} is NULL, they are managed (create/update/delete) and
 * gated (approve/reject) only with platform authorization (PLATFORM-scope
 * assignments), and an APPROVED, non-deleted GLOBAL template is usable by
 * every tenant.
 *
 * <p>TENANT templates are tenant-owned: {@code tenant_id} is NOT NULL,
 * tenant-created rows start PENDING_APPROVAL, and only the owning tenant
 * can use them. The mutual exclusivity of scope and tenancy is enforced
 * by database CHECK constraints (V42) — the application never mints a
 * cross-scope row and never copies GLOBAL templates into tenants.
 */
public enum TtsTemplateScope {
    GLOBAL,
    TENANT
}
