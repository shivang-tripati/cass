package com.shivang.obd.voice.routing;

import com.shivang.obd.common.audit.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Routing policy/profile defining primary, overflow, and failover routes
 * for a tenant or reseller.
 * <p>
 * Platform Admin creates and manages these profiles. Campaigns reference
 * a profile to determine their routing behavior.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "voice_route_profiles", indexes = {
    @Index(name = "idx_voice_route_profiles_tenant_deleted", columnList = "tenant_id,deleted_at"),
    @Index(name = "idx_voice_route_profiles_reseller_deleted", columnList = "reseller_id,deleted_at"),
    @Index(name = "idx_voice_route_profiles_name_tenant", columnList = "name,tenant_id,deleted_at")
})
public class VoiceRouteProfile extends AuditableEntity {

    /** Owning tenant. */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Optional reseller for hierarchical ownership. */
    @Column(name = "reseller_id")
    private UUID resellerId;

    /** Profile name (unique within tenant). */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Description. */
    @Column(name = "description", length = 500)
    private String description;

    /** Whether automatic overflow from primary to overflow routes is enabled. */
    @Column(name = "auto_overflow_enabled", nullable = false)
    private Boolean autoOverflowEnabled = true;

    /** Whether automatic failover from primary to failover routes is enabled. */
    @Column(name = "auto_failover_enabled", nullable = false)
    private Boolean autoFailoverEnabled = true;

    /**
     * VB-6E: entries of one route type, in priority order.
     *
     * <h2>The defect this repairs</h2>
     *
     * <p>Pre-VB-6E the three collections below were unfiltered
     * {@code @OneToMany} associations, so {@code getPrimaryRoutes()},
     * {@code getOverflowRoutes()} and {@code getFailoverRoutes()} all returned
     * the <em>same</em> rows. The primary pass therefore iterated overflow and
     * failover entries and labelled its winner {@code ROUTE_SELECTED_PRIMARY},
     * and the {@code ROUTE_SELECTED_*} reason codes were not faithfully derived
     * from configuration.
     *
     * <h2>Why the union rather than a fourth association</h2>
     *
     * <p>Adding a fourth mapped collection over the same rows would give one
     * {@code VoiceRouteProfileEntry} four cascading associations with
     * {@code orphanRemoval}, which is a genuine hazard on flush. Taking the
     * union of the three existing collections and filtering by the entry's own
     * {@code routeType} fixes the bug with no change to the persisted model and
     * no new cascade to reason about. The filter is applied once, here, at the
     * point of use, which is the only place it can be provably correct.
     */
    public List<VoiceRouteProfileEntry> routesOfType(RouteType routeType) {
        List<VoiceRouteProfileEntry> union = new ArrayList<>(
                primaryRoutes.size() + overflowRoutes.size() + failoverRoutes.size());
        union.addAll(primaryRoutes);
        union.addAll(overflowRoutes);
        union.addAll(failoverRoutes);
        return union.stream()
                .filter(entry -> entry.getRouteType() == routeType)
                .sorted(Comparator.comparingInt(VoiceRouteProfileEntry::getPriority))
                .toList();
    }

    /** Primary route (required). */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, mappedBy = "profile")
    @OrderBy("priority ASC")
    private List<VoiceRouteProfileEntry> primaryRoutes = new ArrayList<>();

    /** Overflow routes (optional). */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, mappedBy = "profile")
    private List<VoiceRouteProfileEntry> overflowRoutes = new ArrayList<>();

    /** Failover routes (optional). */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, mappedBy = "profile")
    private List<VoiceRouteProfileEntry> failoverRoutes = new ArrayList<>();

    public void addPrimaryRoute(VoiceRouteProfileEntry entry) {
        entry.setProfile(this);
        entry.setRouteType(RouteType.PRIMARY);
        primaryRoutes.add(entry);
    }

    public void addOverflowRoute(VoiceRouteProfileEntry entry) {
        entry.setProfile(this);
        entry.setRouteType(RouteType.OVERFLOW);
        overflowRoutes.add(entry);
    }

    public void addFailoverRoute(VoiceRouteProfileEntry entry) {
        entry.setProfile(this);
        entry.setRouteType(RouteType.FAILOVER);
        failoverRoutes.add(entry);
    }
}