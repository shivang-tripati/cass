package com.shivang.obd.voice.call;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CallLegRepository
        extends JpaRepository<CallLeg, UUID>, JpaSpecificationExecutor<CallLeg> {

    Optional<CallLeg> findByIdAndDeletedAtIsNull(UUID id);

    List<CallLeg> findByCallSessionIdAndDeletedAtIsNull(UUID callSessionId);

    List<CallLeg> findByCallSessionIdAndLegTypeAndDeletedAtIsNull(UUID callSessionId, CallLegType legType);

    Optional<CallLeg> findByProviderCallIdAndDeletedAtIsNull(String providerCallId);

    /**
     * Agent connect timeout scan (VB-3): AGENT legs stuck in a pre-answer
     * state past the connect window. Status-based lookup — requires an
     * index on (leg_type, status) or the existing type index plus filter.
     */
    java.util.List<CallLeg> findByLegTypeAndStatusAndInitiatedAtBefore(
            CallLegType legType, CallLegStatus status, java.time.Instant cutoff);

    /** VB-4A: derived active-call count for one agent (canonical CallLeg data). */
    int countByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNull(
            UUID agentId, CallLegType legType,
            java.util.Collection<CallLegStatus> statuses);

    /** VB-4A: agent's live legs across all active statuses (active-calls query). */
    java.util.List<CallLeg> findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNullOrderByInitiatedAtDesc(
            UUID agentId, CallLegType legType, java.util.Collection<CallLegStatus> statuses);

    /** VB-4A: agent's terminal legs, newest first (call-history query). */
    org.springframework.data.domain.Page<CallLeg> findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNull(
            UUID agentId, CallLegType legType,
            java.util.Collection<CallLegStatus> statuses, Pageable pageable);

    /** VB-4A: agent's terminal legs within a date range (history filter). */
    org.springframework.data.domain.Page<CallLeg> findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNullAndInitiatedAtGreaterThanEqualAndInitiatedAtLessThanEqual(
            UUID agentId, CallLegType legType,
            java.util.Collection<CallLegStatus> statuses,
            java.time.Instant from, java.time.Instant to, Pageable pageable);

    /** VB-4A: agent's terminal legs initiated at/after a bound. */
    org.springframework.data.domain.Page<CallLeg> findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNullAndInitiatedAtGreaterThanEqual(
            UUID agentId, CallLegType legType,
            java.util.Collection<CallLegStatus> statuses,
            java.time.Instant from, Pageable pageable);

    /** VB-4A: agent's terminal legs initiated at/before a bound. */
    org.springframework.data.domain.Page<CallLeg> findByAgentIdAndLegTypeAndStatusInAndDeletedAtIsNullAndInitiatedAtLessThanEqual(
            UUID agentId, CallLegType legType,
            java.util.Collection<CallLegStatus> statuses,
            java.time.Instant to, Pageable pageable);

    /** VB-4A: session lookup with isolation kept at the service boundary. */
    List<CallLeg> findByCallSessionIdAndLegTypeAndStatusInAndDeletedAtIsNull(
            UUID callSessionId, CallLegType legType,
            java.util.Collection<CallLegStatus> statuses);
}