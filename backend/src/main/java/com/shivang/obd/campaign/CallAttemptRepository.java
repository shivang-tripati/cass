package com.shivang.obd.campaign;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Repository with IDOR-safe scoped lookup variants.
 * The caller's tenant boundary is part of the query itself.
 */
public interface CallAttemptRepository
        extends JpaRepository<CallAttempt, UUID>, JpaSpecificationExecutor<CallAttempt> {

    Optional<CallAttempt> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    Optional<CallAttempt> findByIdAndDeletedAtIsNull(UUID id);

    List<CallAttempt> findByExecutionIdAndTenantIdAndDeletedAtIsNullOrderByScheduledAtAsc(
            UUID executionId, UUID tenantId);

    Optional<CallAttempt> findByExecutionIdAndContactIdAndAttemptNumberAndDeletedAtIsNull(
            UUID executionId, UUID contactId, Integer attemptNumber);

    boolean existsByExecutionIdAndContactIdAndAttemptNumberAndDeletedAtIsNull(
            UUID executionId, UUID contactId, Integer attemptNumber);

    List<CallAttempt> findByExecutionIdAndStatusInAndDeletedAtIsNull(
            UUID executionId, java.util.Collection<CallAttemptStatus> statuses);

    List<CallAttempt> findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(
            CallAttemptStatus status, Instant scheduledAt);

    Optional<CallAttempt> findByProviderCallIdAndDeletedAtIsNull(String providerCallId);
}