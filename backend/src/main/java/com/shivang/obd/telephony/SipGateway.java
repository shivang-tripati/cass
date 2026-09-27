package com.shivang.obd.telephony;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Logical SIP gateway/trunk for FreeSWITCH.
 * <p>
 * Represents infrastructure owned by platform/reseller/tenant. Does NOT
 * store SIP credentials. Routing, capacity and failover are decided by
 * allocation and resolver, not by this entity alone.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sip_gateways", indexes = {
        @Index(name = "idx_sip_gateways_provider_status", columnList = "provider,status"),
        @Index(name = "idx_sip_gateways_owner", columnList = "owner_type,owner_reseller_id,owner_tenant_id"),
        @Index(name = "idx_sip_gateways_enabled_deleted", columnList = "enabled,deleted_at"),
        @Index(name = "idx_sip_gateways_fs_gateway", columnList = "free_switch_gateway_name")
})
public class SipGateway extends AuditableEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Column(name = "free_switch_gateway_name", nullable = false, length = 100)
    private String freeSwitchGatewayName;

    @Column(name = "free_switch_profile", nullable = false, length = 50)
    private String freeSwitchProfile = "external";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SipGatewayStatus status = SipGatewayStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    private SipGatewayOwnerType ownerType = SipGatewayOwnerType.PLATFORM;

    @Column(name = "owner_reseller_id")
    private UUID ownerResellerId;

    @Column(name = "owner_tenant_id")
    private UUID ownerTenantId;

    @Column(name = "max_concurrent_channels", nullable = false)
    private Integer maxConcurrentChannels;

    /** Max calls per second (CPS) for this gateway. */
    @Column(name = "max_cps")
    private Integer maxCps;

    /** Utilization headroom percentage (0-100) for capacity planning.
     * Effective capacity = max_concurrent_channels * (100 - headroom_pct) / 100 */
    @Column(name = "capacity_headroom_pct")
    private Integer capacityHeadroomPct;

    @Column(name = "priority", nullable = false)
    private Integer priority = 50;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;
}
