package com.shivang.obd.voice.routing;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single route entry within a VoiceRouteProfile.
 * <p>
 * Each entry defines a gateway and optional DID to use for a specific route type.
 * The priority determines selection order within the same route type.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "voice_route_profile_entries", indexes = {
    @Index(name = "idx_voice_route_entries_profile", columnList = "profile_id"),
    @Index(name = "idx_voice_route_entries_type_priority", columnList = "route_type,priority")
})
public class VoiceRouteProfileEntry extends AuditableEntity {

    /** The profile this entry belongs to. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private VoiceRouteProfile profile;

    /** Type of this route entry (native PostgreSQL enum {@code voice_route_type}). */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "route_type", nullable = false)
    private RouteType routeType;

    /** Selection priority within the route type (lower = higher priority). */
    @Column(name = "priority", nullable = false)
    private Integer priority = 50;

    /** The gateway to use. */
    @Column(name = "gateway_id", nullable = false)
    private UUID gatewayId;

    /** Optional specific DID to use as caller ID when using this gateway.
     * If null, the campaign's DID is used (subject to compatibility). */
    @Column(name = "did_id")
    private UUID didId;

    /** Whether this specific entry is enabled. */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;
}