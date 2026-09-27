package com.shivang.obd.voice.queue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Repository for {@link QueueMembership}. Uniqueness of live memberships
 * is enforced by the partial unique index
 * {@code uq_queue_memberships_queue_agent} in the database — concurrent
 * add-member requests collapse to one row at the DB level.
 */
public interface QueueMembershipRepository
        extends JpaRepository<QueueMembership, UUID>, JpaSpecificationExecutor<QueueMembership> {

    Optional<QueueMembership> findByQueueIdAndAgentIdAndDeletedAtIsNull(
            UUID queueId, UUID agentId);

    List<QueueMembership> findByQueueIdAndTenantIdAndDeletedAtIsNull(
            UUID queueId, UUID tenantId);

    List<QueueMembership> findByAgentIdAndTenantIdAndDeletedAtIsNull(
            UUID agentId, UUID tenantId);

    long countByQueueIdAndTenantIdAndStatusAndDeletedAtIsNull(
            UUID queueId, UUID tenantId, QueueMemberStatus status);

    Page<QueueMembership> findAll(Specification<QueueMembership> spec, Pageable pageable);
}
