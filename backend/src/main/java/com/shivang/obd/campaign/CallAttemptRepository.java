package com.shivang.obd.campaign;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * VB-8D: atomically claims one due attempt for dispatch, moving it
     * {@code expected -> claimed} in a single conditional UPDATE.
     *
     * <p><b>Why a claim rather than the previous read-then-write.</b> The old
     * path read the row, checked {@code QUEUED} in Java, then wrote
     * {@code IN_PROGRESS}. Two workers doing that on the same row both pass the
     * check and both place the call. This query makes the database the arbiter:
     * exactly one worker can move the row out of {@code expected}, so exactly one
     * {@code UPDATE} reports 1 and every other reports 0.
     *
     * <p>This is the same guarded-update pattern the VB-6C daily-usage and
     * VB-6D.3 daily-attempt services already use, so no new concurrency
     * mechanism is introduced - only the platform's existing one, applied where
     * it was missing.
     *
     * <p><b>Tenant scoping is part of the predicate, not an afterthought.</b>
     * A worker holding an attempt id from another tenant updates 0 rows and
     * cannot claim it.
     *
     * @return 1 when this caller won the claim, 0 when the row was already
     *         claimed, already past {@code expected}, soft-deleted, or belongs
     *         to another tenant
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CallAttempt a SET a.status = :claimed, a.startedAt = :now "
            + "WHERE a.id = :id AND a.tenantId = :tenantId "
            + "AND a.status = :expected AND a.deletedAt IS NULL")
    int claimForDispatch(@Param("id") UUID id,
                         @Param("tenantId") UUID tenantId,
                         @Param("expected") CallAttemptStatus expected,
                         @Param("claimed") CallAttemptStatus claimed,
                         @Param("now") Instant now);
}