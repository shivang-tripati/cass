package com.shivang.obd.tts;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** IDOR-safe scoped access: the tenant boundary is part of every query. */
public interface TtsTemplateRepository
        extends JpaRepository<TtsTemplateEntity, UUID>, JpaSpecificationExecutor<TtsTemplateEntity> {

    Optional<TtsTemplateEntity> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    Optional<TtsTemplateEntity> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByIdAndTenantIdAndDeletedAtIsNullAndStatus(
        UUID id, UUID tenantId, TtsTemplateStatus status);

    /**
     * Authoritative campaign usability predicate (V42): APPROVED, not
     * deleted, and either GLOBAL (usable by every tenant) or TENANT-owned
     * by exactly the campaign tenant. Cross-tenant references resolve to
     * false without revealing foreign-row existence.
     */
    @Query("""
        SELECT COUNT(t) > 0 FROM TtsTemplateEntity t
        WHERE t.id = :id
          AND t.deletedAt IS NULL
          AND t.status = com.shivang.obd.tts.TtsTemplateStatus.APPROVED
          AND (t.scope = com.shivang.obd.tts.TtsTemplateScope.GLOBAL
               OR (t.scope = com.shivang.obd.tts.TtsTemplateScope.TENANT
                   AND t.tenantId = :tenantId))
        """)
    boolean existsUsableForTenant(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    /**
     * Tenant-safe accessibility superset of {@link #existsUsableForTenant}:
     * the row resolves for the campaign tenant (own TENANT row at any
     * approval status, or a GLOBAL row at any status) and is not deleted.
     * Used only to distinguish "accessible but not approved" from "missing
     * or cross-scope" without revealing foreign-row existence.
     */
    @Query("""
        SELECT COUNT(t) > 0 FROM TtsTemplateEntity t
        WHERE t.id = :id
          AND t.deletedAt IS NULL
          AND (t.scope = com.shivang.obd.tts.TtsTemplateScope.GLOBAL
               OR (t.scope = com.shivang.obd.tts.TtsTemplateScope.TENANT
                   AND t.tenantId = :tenantId))
        """)
    boolean existsAccessibleForTenant(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
