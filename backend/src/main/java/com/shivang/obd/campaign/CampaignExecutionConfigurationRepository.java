package com.shivang.obd.campaign;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for the immutable configuration snapshots executions run on
 * (VB-6A correction). Read-only by consumers; rows are created only through
 * {@link CampaignConfigurationService} inside the execution-creation
 * transaction. Tenant-scoped lookups keep snapshot reads inside the
 * caller's isolation boundary — a foreign snapshot is indistinguishable
 * from a missing one.
 */
public interface CampaignExecutionConfigurationRepository
        extends JpaRepository<CampaignExecutionConfiguration, UUID> {

    /**
     * The snapshot an execution must use. Tenant-scoped: resolving another
     * tenant's snapshot is impossible through this lookup.
     */
    Optional<CampaignExecutionConfiguration> findByIdAndTenantId(UUID id, UUID tenantId);
}
