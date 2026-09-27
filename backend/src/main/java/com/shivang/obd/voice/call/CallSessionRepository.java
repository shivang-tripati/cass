package com.shivang.obd.voice.call;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CallSessionRepository
        extends JpaRepository<CallSession, UUID>, JpaSpecificationExecutor<CallSession> {

    Optional<CallSession> findByIdAndDeletedAtIsNull(UUID id);

    List<CallSession> findByTenantIdAndDeletedAtIsNull(UUID tenantId);

    Optional<CallSession> findByProviderCallIdAndDeletedAtIsNull(String providerCallId);

    Optional<CallSession> findByCallAttemptIdAndDeletedAtIsNull(UUID callAttemptId);

    @Query("select cs from CallSession cs " +
           "where cs.callAttemptId = :callAttemptId and cs.deletedAt is null")
    Optional<CallSession> findByCallAttemptId(UUID callAttemptId);

    List<CallSession> findByCampaignExecutionIdAndDeletedAtIsNull(UUID campaignExecutionId);
}