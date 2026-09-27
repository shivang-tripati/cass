package com.shivang.obd.telephony;

import com.shivang.obd.voice.capacity.VoiceCapacityService;
import com.shivang.obd.voice.routing.VoiceRoutingReason;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Voice capacity service implementation using PostgreSQL advisory locks
 * for safe concurrent reservation.
 * <p>
 * Tracks both concurrent channels and CPS (calls per second) limits.
 * Uses gateway and allocation capacity configuration with headroom.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceCapacityServiceImpl implements VoiceCapacityService {

    private final SipGatewayRepository gatewayRepository;
    private final SipGatewayAllocationRepository allocationRepository;

    @PersistenceContext
    private EntityManager entityManager;

    // Advisory lock base for gateway channel reservations
    private static final long CHANNEL_LOCK_BASE = 0x100000000L;
    // Advisory lock base for CPS reservations
    private static final long CPS_LOCK_BASE = 0x200000000L;

    // CPS window in seconds
    private static final int CPS_WINDOW_SECONDS = 1;

    @Override
    @Transactional
    public boolean reserve(UUID gatewayId, UUID tenantId) {
        SipGateway gateway = gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId).orElse(null);
        if (gateway == null) {
            log.warn("Gateway not found for reservation: {}", gatewayId);
            return false;
        }

        // Check if tenant has allocation for this gateway
        if (!hasAllocation(gatewayId, tenantId)) {
            log.warn("No allocation for tenant {} on gateway {}", tenantId, gatewayId);
            return false;
        }

        // Try to acquire channel lock
        if (!acquireChannelLock(gatewayId)) {
            log.debug("Channel lock not available for gateway {}", gatewayId);
            return false;
        }

        try {
            // Check effective channel capacity (gateway + allocation limits)
            if (!checkChannelCapacity(gatewayId, tenantId)) {
                return false;
            }

            // Check CPS capacity
            if (!checkCpsCapacity(gatewayId, tenantId)) {
                return false;
            }

            // Record reservation
            recordReservation(gatewayId, tenantId);
            log.debug("Reserved channel on gateway {} for tenant {}", gatewayId, tenantId);
            return true;

        } finally {
            releaseChannelLock(gatewayId);
        }
    }

    @Override
    @Transactional
    public void release(UUID gatewayId, UUID tenantId) {
        // Release channel reservation (idempotent)
        releaseReservation(gatewayId, tenantId);
        log.debug("Released channel reservation on gateway {} for tenant {}", gatewayId, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAvailable(UUID gatewayId, UUID tenantId) {
        SipGateway gateway = gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId).orElse(null);
        if (gateway == null || !Boolean.TRUE.equals(gateway.getEnabled())
                || gateway.getStatus() != SipGatewayStatus.ACTIVE) {
            return false;
        }

        if (!hasAllocation(gatewayId, tenantId)) {
            return false;
        }

        // Check both channel and CPS capacity
        return checkChannelCapacity(gatewayId, tenantId) && checkCpsCapacity(gatewayId, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public CapacityCheckResult checkCapacity(UUID gatewayId, UUID tenantId) {
        SipGateway gateway = gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId).orElse(null);
        if (gateway == null) {
            return CapacityCheckResult.rejected(VoiceRoutingReason.ROUTE_REJECTED_GATEWAY_INACTIVE.getCode());
        }
        if (!Boolean.TRUE.equals(gateway.getEnabled())) {
            return CapacityCheckResult.rejected(VoiceRoutingReason.ROUTE_REJECTED_GATEWAY_DISABLED.getCode());
        }
        if (gateway.getStatus() != SipGatewayStatus.ACTIVE) {
            return CapacityCheckResult.rejected(
                    gateway.getStatus() == SipGatewayStatus.DEGRADED
                            ? VoiceRoutingReason.ROUTE_REJECTED_GATEWAY_DEGRADED.getCode()
                            : VoiceRoutingReason.ROUTE_REJECTED_GATEWAY_INACTIVE.getCode());
        }
        if (!hasAllocation(gatewayId, tenantId)) {
            return CapacityCheckResult.rejected(VoiceRoutingReason.ROUTE_REJECTED_TENANT_NOT_AUTHORIZED.getCode());
        }

        // Channel capacity (gateway level, then allocation level)
        int gatewayEffectiveChannels = getEffectiveChannels(gateway);
        if (getCurrentChannelUsage(gatewayId) >= gatewayEffectiveChannels) {
            return CapacityCheckResult.rejected(VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode());
        }
        int allocationLimit = getAllocationChannelLimit(gatewayId, tenantId);
        if (allocationLimit > 0
                && getCurrentTenantUsage(gatewayId, tenantId) >= allocationLimit) {
            return CapacityCheckResult.rejected(VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode());
        }

        // CPS capacity (gateway level, then allocation level)
        if (!checkCpsCapacity(gatewayId, tenantId)) {
            return CapacityCheckResult.rejected(VoiceRoutingReason.ROUTE_REJECTED_CPS_CAPACITY.getCode());
        }

        return CapacityCheckResult.ok();
    }

    @Override
    @Transactional(readOnly = true)
    public int getCurrentUsage(UUID gatewayId) {
        return getCurrentChannelUsage(gatewayId);
    }

    @Override
    @Transactional(readOnly = true)
    public int getMaxCapacity(UUID gatewayId) {
        SipGateway gateway = gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId).orElse(null);
        if (gateway == null) {
            return -1;
        }
        return getEffectiveChannels(gateway);
    }

    @Override
    @Transactional(readOnly = true)
    public int getCurrentCps(UUID gatewayId) {
        return getCurrentCpsUsage(gatewayId);
    }

    @Override
    @Transactional(readOnly = true)
    public int getMaxCps(UUID gatewayId) {
        SipGateway gateway = gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId).orElse(null);
        if (gateway == null) {
            return -1;
        }
        return getEffectiveCps(gateway);
    }

    // --- Internal methods ---

    private boolean hasAllocation(UUID gatewayId, UUID tenantId) {
        return allocationRepository.existsByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(gatewayId, tenantId)
                || allocationRepository.existsByGatewayIdAndResellerIdAndEnabledTrueAndDeletedAtIsNull(gatewayId,
                        gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId)
                                .map(SipGateway::getOwnerResellerId)
                                .orElse(null));
    }

    /**
     * Calculates effective channel capacity considering headroom.
     */
    private int getEffectiveChannels(SipGateway gateway) {
        int maxChannels = gateway.getMaxConcurrentChannels();
        Integer headroomPct = gateway.getCapacityHeadroomPct();
        if (headroomPct != null && headroomPct > 0 && headroomPct < 100) {
            return (int) Math.floor(maxChannels * (100 - headroomPct) / 100.0);
        }
        return maxChannels;
    }

    private int getEffectiveCps(SipGateway gateway) {
        Integer maxCps = gateway.getMaxCps();
        if (maxCps == null) {
            return Integer.MAX_VALUE; // No CPS limit
        }
        return maxCps;
    }

    /**
     * Checks channel capacity considering both gateway and allocation limits.
     * Effective capacity = min(gateway effective channels, allocation effective channels)
     */
    private boolean checkChannelCapacity(UUID gatewayId, UUID tenantId) {
        SipGateway gateway = gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId).orElse(null);
        if (gateway == null) {
            return false;
        }

        int gatewayEffectiveChannels = getEffectiveChannels(gateway);
        int currentUsage = getCurrentChannelUsage(gatewayId);
        
        // Check gateway-level capacity
        if (currentUsage >= gatewayEffectiveChannels) {
            log.debug("Gateway channel capacity exhausted for gateway {}: used={}, max={}", gatewayId, currentUsage, gatewayEffectiveChannels);
            return false;
        }

        // Check allocation-level capacity
        int allocationLimit = getAllocationChannelLimit(gatewayId, tenantId);
        if (allocationLimit > 0) {
            int tenantUsage = getCurrentTenantUsage(gatewayId, tenantId);
            if (tenantUsage >= allocationLimit) {
                log.debug("Allocation channel capacity exhausted for tenant {} on gateway {}: used={}, max={}", tenantId, gatewayId, tenantUsage, allocationLimit);
                return false;
            }
        }

        return true;
    }

    private int getAllocationChannelLimit(UUID gatewayId, UUID tenantId) {
        return allocationRepository.findByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(gatewayId, tenantId)
                .map(a -> {
                    // DDL allows NULL allocation channels = no tenant-level limit
                    int allocMax = a.getMaxConcurrentChannels() != null ? a.getMaxConcurrentChannels() : 0;
                    if (allocMax <= 0) {
                        return 0; // No limit
                    }
                    Integer headroomPct = a.getCapacityHeadroomPct();
                    if (headroomPct != null && headroomPct > 0 && headroomPct < 100) {
                        return (int) Math.floor(allocMax * (100 - headroomPct) / 100.0);
                    }
                    return allocMax;
                })
                .orElseGet(() -> {
                    // Check reseller-level allocation
                    return allocationRepository.findByGatewayIdAndResellerIdAndEnabledTrueAndDeletedAtIsNull(gatewayId,
                            gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId)
                                    .map(SipGateway::getOwnerResellerId)
                                    .orElse(null))
                            .map(a -> {
                                // DDL allows NULL allocation channels = no reseller-level limit
                                int allocMax = a.getMaxConcurrentChannels() != null ? a.getMaxConcurrentChannels() : 0;
                                if (allocMax <= 0) {
                                    return 0;
                                }
                                Integer headroomPct = a.getCapacityHeadroomPct();
                                if (headroomPct != null && headroomPct > 0 && headroomPct < 100) {
                                    return (int) Math.floor(allocMax * (100 - headroomPct) / 100.0);
                                }
                                return allocMax;
                            })
                            .orElse(0); // No allocation limit
                });
    }

    private int getCurrentTenantUsage(UUID gatewayId, UUID tenantId) {
        Query query = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM voice_channel_reservations WHERE gateway_id = :gatewayId AND tenant_id = :tenantId AND released_at IS NULL"
        );
        query.setParameter("gatewayId", gatewayId);
        query.setParameter("tenantId", tenantId);
        Number result = (Number) query.getSingleResult();
        return result.intValue();
    }

    private int getCurrentChannelUsage(UUID gatewayId) {
        Query query = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM voice_channel_reservations WHERE gateway_id = :gatewayId AND released_at IS NULL"
        );
        query.setParameter("gatewayId", gatewayId);
        Number result = (Number) query.getSingleResult();
        return result.intValue();
    }

    private boolean checkCpsCapacity(UUID gatewayId, UUID tenantId) {
        SipGateway gateway = gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId).orElse(null);
        if (gateway == null) {
            return false;
        }

        int gatewayEffectiveCps = getEffectiveCps(gateway);
        if (gatewayEffectiveCps == Integer.MAX_VALUE) {
            return true; // No CPS limit
        }

        int currentCps = getCurrentCpsUsage(gatewayId);
        if (currentCps >= gatewayEffectiveCps) {
            log.debug("Gateway CPS capacity exhausted for gateway {}: used={}, max={}", gatewayId, currentCps, gatewayEffectiveCps);
            return false;
        }

        // Check allocation-level CPS
        Integer allocationCpsLimit = getAllocationCpsLimit(gatewayId, tenantId);
        if (allocationCpsLimit != null && allocationCpsLimit > 0) {
            int tenantCps = getCurrentTenantCps(gatewayId, tenantId);
            if (tenantCps >= allocationCpsLimit) {
                log.debug("Allocation CPS capacity exhausted for tenant {} on gateway {}: used={}, max={}", tenantId, gatewayId, tenantCps, allocationCpsLimit);
                return false;
            }
        }

        return true;
    }

    private Integer getAllocationCpsLimit(UUID gatewayId, UUID tenantId) {
        return allocationRepository.findByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(gatewayId, tenantId)
                .map(SipGatewayAllocation::getMaxCps)
                .orElseGet(() -> allocationRepository.findByGatewayIdAndResellerIdAndEnabledTrueAndDeletedAtIsNull(gatewayId,
                        gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId)
                                .map(SipGateway::getOwnerResellerId)
                                .orElse(null))
                        .map(SipGatewayAllocation::getMaxCps)
                        .orElse(null));
    }

    private int getCurrentTenantCps(UUID gatewayId, UUID tenantId) {
        Query query = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM voice_channel_reservations WHERE gateway_id = :gatewayId AND tenant_id = :tenantId AND reserved_at >= now() - interval '1 second' AND released_at IS NULL"
        );
        query.setParameter("gatewayId", gatewayId);
        query.setParameter("tenantId", tenantId);
        Number result = (Number) query.getSingleResult();
        return result.intValue();
    }

    private int getCurrentCpsUsage(UUID gatewayId) {
        Query query = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM voice_channel_reservations WHERE gateway_id = :gatewayId AND reserved_at >= now() - interval '1 second' AND released_at IS NULL"
        );
        query.setParameter("gatewayId", gatewayId);
        Number result = (Number) query.getSingleResult();
        return result.intValue();
    }

    private boolean acquireChannelLock(UUID gatewayId) {
        long lockId = CHANNEL_LOCK_BASE + gatewayId.hashCode();
        // Explicit CAST: untyped JPA parameters fail overload resolution on
        // native advisory-lock calls (same typing issue documented in
        // AgentReservationService).
        Query query = entityManager.createNativeQuery(
                "SELECT pg_try_advisory_xact_lock(CAST(:lockId AS bigint))");
        query.setParameter("lockId", lockId);
        Boolean result = (Boolean) query.getSingleResult();
        return Boolean.TRUE.equals(result);
    }

    /**
     * No-op: transaction-scoped advisory locks release automatically at
     * commit/rollback ({@code pg_advisory_xact_unlock} does not exist in
     * PostgreSQL). Kept so the {@code try/finally} structure reads clearly.
     */
    private void releaseChannelLock(UUID gatewayId) {
        // Intentionally empty — see javadoc.
    }

    private void recordReservation(UUID gatewayId, UUID tenantId) {
        Query query = entityManager.createNativeQuery(
                "INSERT INTO voice_channel_reservations (gateway_id, tenant_id, reserved_at) VALUES (:gatewayId, :tenantId, now())"
        );
        query.setParameter("gatewayId", gatewayId);
        query.setParameter("tenantId", tenantId);
        query.executeUpdate();
    }

    private void releaseReservation(UUID gatewayId, UUID tenantId) {
        Query query = entityManager.createNativeQuery(
                "UPDATE voice_channel_reservations SET released_at = now() WHERE gateway_id = :gatewayId AND tenant_id = :tenantId AND released_at IS NULL"
        );
        query.setParameter("gatewayId", gatewayId);
        query.setParameter("tenantId", tenantId);
        query.executeUpdate();
    }

    /**
     * Reconciliation job - runs periodically to clean up stale reservations.
     * Reservations older than 5 minutes without release are considered stale
     * (covers process crashes, lost ESL events, etc.).
     */
    @Scheduled(fixedDelay = 60000) // Run every minute
    @Transactional
    public void reconcileStaleReservations() {
        Instant cutoff = Instant.now().minusSeconds(300); // 5 minutes
        Query query = entityManager.createNativeQuery(
                "UPDATE voice_channel_reservations SET released_at = now() WHERE released_at IS NULL AND reserved_at < :cutoff"
        );
        query.setParameter("cutoff", Instant.now().minusSeconds(300));
        int updated = query.executeUpdate();
        if (updated > 0) {
            log.info("Reconciled {} stale capacity reservations", updated);
        }
    }
}