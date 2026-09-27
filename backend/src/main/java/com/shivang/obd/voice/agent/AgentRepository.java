package com.shivang.obd.voice.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Tenant-scoped repository for {@link Agent}.
 * <p>
 * The eligibility+selection query is NATIVE SQL: it orders by a correlated
 * reservation subquery and filters on the native {@code agent_admin_status}
 * / {@code agent_availability} enums — JPQL enum literals are unreliable
 * with Hibernate 7 native-enum columns (they bind as
 * {@code 'X'::JavaClassName}, not the PG type).
 */
public interface AgentRepository
        extends JpaRepository<Agent, UUID>, JpaSpecificationExecutor<Agent> {

    Optional<Agent> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    /**
     * Deterministic selection source query (VB-3 rule): eligible agents for
     * a tenant ordered by least active reservations, then stable agent id —
     * same state always selects the same agent.
     */
    @Query(value = """
            select * from agents a
            where a.tenant_id = :tenantId
              and a.deleted_at is null
              and a.admin_status = 'ACTIVE'
              and a.availability = 'AVAILABLE'
            order by
              (select count(*) from agent_reservations r
                 where r.agent_id = a.id and r.status <> 'RELEASED'
                   and r.deleted_at is null) asc,
              a.id asc
            """,
            countQuery = """
            select count(*) from agents a
            where a.tenant_id = :tenantId
              and a.deleted_at is null
              and a.admin_status = 'ACTIVE'
              and a.availability = 'AVAILABLE'
            """,
            nativeQuery = true)
    Page<Agent> findEligibleOrdered(@Param("tenantId") UUID tenantId, Pageable pageable);

    /** Existence probe for the AGENT_UNAVAILABLE vs AGENT_NOT_AVAILABLE distinction. */
    List<Agent> findByTenantIdAndDeletedAtIsNull(UUID tenantId, Pageable pageable);

    /** Unscoped lookup for RESELLER/PLATFORM visibility resolution (VB-4A). */
    Optional<Agent> findByIdAndDeletedAtIsNull(UUID id);
}
