package com.shivang.obd.did;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A managed/provisioned telephone number. Ownership follows the platform
 * hierarchy: a DID may sit in the platform pool, under a reseller pool,
 * or be assigned to exactly one tenant. The E.164 number is the canonical
 * identity and is stored as VARCHAR — never numeric.
 *
 * <p>{@code capabilities} is a typed set serialized as a JSONB array of
 * enum names (same persistence convention as campaigns.allowed_days_of_week):
 * strongly constrained values with an intentionally extensible catalog.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dids", indexes = {
    @Index(name = "idx_dids_tenant_deleted", columnList = "tenant_id,deleted_at"),
    @Index(name = "idx_dids_reseller", columnList = "reseller_id"),
    @Index(name = "idx_dids_status", columnList = "status")
})
public class DidEntity extends AuditableEntity {

    /** Tenant currently using the DID; null while unassigned. */
    @Column(name = "tenant_id")
    private UUID tenantId;

    /** Reseller associated with the allocation; null for platform-pool numbers. */
    @Column(name = "reseller_id")
    private UUID resellerId;

    /** Canonical E.164 number, e.g. "+918012345678". Immutable after creation. */
    @Column(name = "e164_number", nullable = false, length = 20)
    private String e164Number;

    @Column(name = "country_code", nullable = false, length = 3)
    private String countryCode;

    @Column(name = "area_code", length = 10)
    private String areaCode;

    /** Telecom/circle grouping such as "Karnataka". */
    @Column(name = "circle", length = 100)
    private String circle;

    @Enumerated(EnumType.STRING)
    @Column(name = "number_type", nullable = false, length = 30)
    private NumberType numberType;

    /** Simple provider identifier (TATA, AIRTEL, TWILIO, ...). No provider framework yet. */
    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DidStatus status = DidStatus.ACTIVE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capabilities")
    private Set<DidCapability> capabilities;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_state", nullable = false, length = 20)
    private AllocationState allocationState = AllocationState.AVAILABLE;

    /**
     * Allocation provenance (VB-5C): the pool a live assignment came from.
     * {@code RESELLER} = assigned out of the reseller pool stamped in
     * {@code resellerId}; {@code PLATFORM} = assigned directly out of the
     * platform pool; {@code null} = unassigned platform-pool number.
     * Revocation restores this provenance: tenant assignments return to
     * the reseller pool when RESELLER, else to the platform pool, so the
     * original allocation chain is never lost.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_source", length = 20)
    private AllocationSource allocationSource;

    /**
     * Inbound destination for calls arriving on this DID (VB-4D).
     * {@code null} = the DID does not accept inbound calls — the default
     * for every outbound/pool number, so existing behavior is unchanged.
     * QUEUE → {@code inboundQueueId}; AGENT → direct-to-agent via
     * {@code inboundAgentId}. The V39 check constraints guarantee exactly
     * one pointer matches the destination kind, and tenant scoping is
     * enforced by the FK graph (destination must live in the DID's tenant).
     */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "inbound_destination")
    private DidInboundDestination inboundDestination;

    /** Inbound QUEUE destination (required when destination = QUEUE). */
    @Column(name = "inbound_queue_id")
    private UUID inboundQueueId;

    /** Inbound DIRECT-AGENT destination (required when destination = AGENT). */
    @Column(name = "inbound_agent_id")
    private UUID inboundAgentId;
}
