package com.shivang.obd.voice.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link AgentEndpointEntity}.
 */
public interface AgentEndpointRepository
        extends JpaRepository<AgentEndpointEntity, UUID>,
                JpaSpecificationExecutor<AgentEndpointEntity> {

    List<AgentEndpointEntity> findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(
            UUID agentId, UUID tenantId);

    /** VB-4A: all endpoints of an agent regardless of enablement (directory listing). */
    List<AgentEndpointEntity> findByAgentIdAndTenantIdAndDeletedAtIsNull(
            UUID agentId, UUID tenantId);

    /**
     * Tenant-scoped endpoint resolution — cross-tenant references fail
     * closed (empty).
     */
    Optional<AgentEndpointEntity> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    Optional<AgentEndpointEntity> findFirstByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNullOrderByIdAsc(
            UUID agentId, UUID tenantId);

    /**
     * Marks disabled/stale endpoints unenabled in bulk (not used by VB-3
     * hot path; available for administration).
     */
    @Modifying
    @Query("update AgentEndpointEntity e set e.enabled = false "
            + "where e.agentId = :agentId and e.enabled = true and e.deletedAt is null")
    int disableAllForAgent(@Param("agentId") UUID agentId);
}
