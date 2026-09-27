package com.shivang.obd.telephony;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal service for SIP gateway lifecycle and allocation.
 * <p>
 * No public API — platform-internal only. Validates domain invariants.
 */
@Service
@RequiredArgsConstructor
public class SipGatewayService {

    private final SipGatewayRepository gatewayRepository;
    private final SipGatewayAllocationRepository allocationRepository;

    @Transactional
    public SipGateway createGateway(SipGateway gateway) {
        validateGateway(gateway, null);
        return gatewayRepository.save(gateway);
    }

    @Transactional
    public SipGateway updateGateway(UUID gatewayId, SipGateway patch) {
        SipGateway existing = gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId)
                .orElseThrow(() -> new ResourceNotFoundException("Gateway not found"));

        existing.setDisplayName(patch.getDisplayName());
        existing.setProvider(patch.getProvider());
        existing.setFreeSwitchGatewayName(patch.getFreeSwitchGatewayName());
        existing.setFreeSwitchProfile(patch.getFreeSwitchProfile());
        existing.setStatus(patch.getStatus());
        existing.setOwnerType(patch.getOwnerType());
        existing.setOwnerResellerId(patch.getOwnerResellerId());
        existing.setOwnerTenantId(patch.getOwnerTenantId());
        existing.setMaxConcurrentChannels(patch.getMaxConcurrentChannels());
        existing.setPriority(patch.getPriority());
        existing.setEnabled(patch.getEnabled());

        validateGateway(existing, gatewayId);
        return gatewayRepository.save(existing);
    }

    @Transactional(readOnly = true)
    public SipGateway getGateway(UUID gatewayId) {
        return gatewayRepository.findByIdAndDeletedAtIsNull(gatewayId)
                .orElseThrow(() -> new ResourceNotFoundException("Gateway not found"));
    }

    @Transactional(readOnly = true)
    public List<SipGateway> listActiveGateways() {
        return gatewayRepository.findByStatusAndEnabledTrueAndDeletedAtIsNull(SipGatewayStatus.ACTIVE);
    }

    @Transactional
    public void softDeleteGateway(UUID gatewayId, String deletedBy) {
        SipGateway gateway = getGateway(gatewayId);
        gateway.setDeletedAt(java.time.Instant.now());
        gateway.setDeletedBy(deletedBy);
        gatewayRepository.save(gateway);
    }

    @Transactional
    public SipGatewayAllocation allocate(SipGatewayAllocation allocation) {
        validateAllocation(allocation, null);
        enforceCapacity(allocation);
        checkDuplicateAllocation(allocation, null);
        return allocationRepository.save(allocation);
    }

    @Transactional
    public SipGatewayAllocation updateAllocation(UUID allocationId, SipGatewayAllocation patch) {
        SipGatewayAllocation existing = allocationRepository.findById(allocationId)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation not found"));
        existing.setEnabled(patch.getEnabled());
        existing.setPriority(patch.getPriority());
        existing.setMaxConcurrentChannels(patch.getMaxConcurrentChannels());
        // gateway/reseller/tenant are immutable after creation
        validateAllocation(existing, allocationId);
        enforceCapacity(existing);
        checkDuplicateAllocation(existing, allocationId);
        return allocationRepository.save(existing);
    }

    @Transactional
    public void softDeleteAllocation(UUID allocationId, String deletedBy) {
        SipGatewayAllocation allocation = allocationRepository.findById(allocationId)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Allocation not found"));
        allocation.setDeletedAt(java.time.Instant.now());
        allocation.setDeletedBy(deletedBy);
        allocationRepository.save(allocation);
    }

    private void validateGateway(SipGateway gateway, UUID excludeId) {
        if (gateway.getFreeSwitchGatewayName() == null || gateway.getFreeSwitchGatewayName().isBlank()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "FreeSwitch gateway name cannot be blank");
        }
        if (gateway.getName() == null || gateway.getName().isBlank()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Gateway name cannot be blank");
        }
        if (gateway.getMaxConcurrentChannels() == null || gateway.getMaxConcurrentChannels() <= 0) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Gateway capacity must be positive");
        }
        if (gateway.getPriority() == null || gateway.getPriority() < 0 || gateway.getPriority() > 100) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Gateway priority must be between 0 and 100");
        }
        // owner consistency
        if (gateway.getOwnerType() == SipGatewayOwnerType.PLATFORM
                && (gateway.getOwnerResellerId() != null || gateway.getOwnerTenantId() != null)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Platform gateway must not have owner reseller/tenant");
        }
        if (gateway.getOwnerType() == SipGatewayOwnerType.RESELLER
                && gateway.getOwnerResellerId() == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Reseller gateway must have ownerResellerId");
        }
        if (gateway.getOwnerType() == SipGatewayOwnerType.TENANT
                && gateway.getOwnerTenantId() == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Tenant gateway must have ownerTenantId");
        }
        // uniqueness (active) — null-safe for new entities
        gatewayRepository.findByNameAndDeletedAtIsNull(gateway.getName())
                .filter(g -> excludeId == null || !g.getId().equals(excludeId))
                .ifPresent(g -> { throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Gateway name already exists"); });
        gatewayRepository.findByFreeSwitchGatewayNameAndDeletedAtIsNull(gateway.getFreeSwitchGatewayName())
                .filter(g -> excludeId == null || !g.getId().equals(excludeId))
                .ifPresent(g -> { throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "FreeSwitch gateway name already exists"); });
    }

    private void validateAllocation(SipGatewayAllocation allocation, UUID excludeId) {
        if (allocation.getGatewayId() == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Allocation gatewayId is required");
        }
        gatewayRepository.findByIdAndDeletedAtIsNull(allocation.getGatewayId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Gateway not found"));
        if (allocation.getResellerId() == null && allocation.getTenantId() == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Allocation must target reseller or tenant");
        }
        if (allocation.getPriority() == null || allocation.getPriority() < 0 || allocation.getPriority() > 100) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Allocation priority must be between 0 and 100");
        }
        if (allocation.getMaxConcurrentChannels() != null && allocation.getMaxConcurrentChannels() <= 0) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Per-tenant capacity must be positive");
        }
    }

    private void enforceCapacity(SipGatewayAllocation allocation) {
        if (allocation.getMaxConcurrentChannels() == null) {
            return;
        }
        SipGateway gateway = gatewayRepository.findByIdAndDeletedAtIsNull(allocation.getGatewayId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Gateway not found"));
        if (allocation.getMaxConcurrentChannels() > gateway.getMaxConcurrentChannels()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Per-tenant capacity must not exceed gateway capacity");
        }
    }

    private void checkDuplicateAllocation(SipGatewayAllocation allocation, UUID excludeId) {
        // Check duplicate active allocation for same gateway+target
        List<SipGatewayAllocation> existing = allocationRepository.findByGatewayIdAndDeletedAtIsNull(allocation.getGatewayId());
        for (SipGatewayAllocation e : existing) {
            if (excludeId != null && e.getId().equals(excludeId)) {
                continue;
            }
            if (!Boolean.TRUE.equals(e.getEnabled())) {
                continue;
            }
            if (!Boolean.TRUE.equals(allocation.getEnabled())) {
                continue;
            }
            boolean sameReseller = java.util.Objects.equals(e.getResellerId(), allocation.getResellerId());
            boolean sameTenant = java.util.Objects.equals(e.getTenantId(), allocation.getTenantId());
            if (sameReseller && sameTenant) {
                throw new BusinessException(CommonErrorCode.CONFLICT, "Duplicate active allocation for same gateway and target");
            }
        }
    }
}
