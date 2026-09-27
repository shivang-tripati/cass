package com.shivang.obd.telephony;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Access/allocation of a SIP gateway to a reseller or tenant.
 * <p>
 * Ownership and allocation are separate: a reseller-owned gateway may be
 * allocated to tenants outside that reseller, and platform-owned gateways
 * may be allocated to any reseller/tenant. At least one of resellerId or
 * tenantId must be set.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sip_gateway_allocations", indexes = {
        @Index(name = "idx_sip_alloc_gateway", columnList = "gateway_id"),
        @Index(name = "idx_sip_alloc_reseller", columnList = "reseller_id"),
        @Index(name = "idx_sip_alloc_tenant", columnList = "tenant_id"),
        @Index(name = "idx_sip_alloc_enabled_deleted", columnList = "enabled,deleted_at")
})
public class SipGatewayAllocation extends AuditableEntity {

    @Column(name = "gateway_id", nullable = false)
    private UUID gatewayId;

    @Column(name = "reseller_id")
    private UUID resellerId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "priority", nullable = false)
    private Integer priority = 50;

    @Column(name = "max_concurrent_channels")
    private Integer maxConcurrentChannels;

    /** Max calls per second (CPS) for this allocation. Null means no limit. */
    @Column(name = "max_cps")
    private Integer maxCps;

    /** Utilization headroom percentage (0-99) applied to the allocation's channel limit.
     * Effective allocation capacity = max_concurrent_channels * (100 - headroom_pct) / 100.
     * Mirrors {@link SipGateway#getCapacityHeadroomPct()}. */
    @Column(name = "capacity_headroom_pct")
    private Integer capacityHeadroomPct;
}
