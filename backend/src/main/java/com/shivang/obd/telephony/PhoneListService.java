package com.shivang.obd.telephony;

import com.shivang.obd.voice.media.PhoneNumberNormalizer;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;

/**
 * Internal service for managing phone list entries.
 * Platform-internal only. Validates domain invariants.
 */
@Service
@RequiredArgsConstructor
public class PhoneListService {

    private final PhoneListEntryRepository repository;

    @Transactional
    public PhoneListEntry createEntry(PhoneListType type, ScopeType scopeType, UUID scopeResellerId, UUID scopeTenantId,
                                      String number, String reason, Boolean active) {
        String normalized = PhoneNumberNormalizer.normalize(number);
        if (!PhoneNumberNormalizer.isValidE164(normalized)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Invalid phone number format: " + number);
        }
        validateScope(type, scopeType, scopeResellerId, scopeTenantId);

        // Check for duplicate active entry
        boolean exists = checkDuplicate(type, normalized, scopeResellerId, scopeTenantId);
        if (exists) {
            throw new BusinessException(CommonErrorCode.CONFLICT, "Number already exists in this list");
        }

        PhoneListEntry entry = new PhoneListEntry();
        entry.setType(type);
        entry.setScopeType(mapScopeType(type));
        entry.setScopeResellerId(type == PhoneListType.RESELLER_BLOCKLIST || type == PhoneListType.RESELLER_WHITELIST
                ? scopeResellerId : null);
        entry.setScopeTenantId(type == PhoneListType.TENANT_BLOCKLIST || type == PhoneListType.TENANT_WHITELIST
                ? scopeTenantId : null);
        entry.setNormalizedNumber(normalized);
        entry.setOriginalNumber(number);
        entry.setReason(reason);
        entry.setActive(true);
        return repository.save(entry);
    }

    private boolean checkDuplicate(PhoneListType type, String normalized, UUID scopeResellerId, UUID scopeTenantId) {
        ScopeType scopeType = mapScopeType(type);
        return switch (scopeType) {
            case PLATFORM -> repository.findByTypeAndScopeTypeAndActiveTrueAndDeletedAtIsNull(type, ScopeType.PLATFORM)
                    .stream().anyMatch(e -> e.getNormalizedNumber().equals(normalized));
            case RESELLER -> repository.findByTypeAndScopeTypeAndScopeResellerIdAndActiveTrueAndDeletedAtIsNull(
                    type, ScopeType.RESELLER, scopeResellerId)
                    .stream().anyMatch(e -> e.getNormalizedNumber().equals(normalized));
            case TENANT -> repository.findByTypeAndScopeTypeAndScopeTenantIdAndActiveTrueAndDeletedAtIsNull(
                    type, ScopeType.TENANT, scopeTenantId)
                    .stream().anyMatch(e -> e.getNormalizedNumber().equals(normalized));
        };
    }

    @Transactional
    public void softDelete(UUID id, String deletedBy) {
        PhoneListEntry entry = repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Phone list entry not found"));
        entry.setDeletedAt(java.time.Instant.now());
        entry.setDeletedBy(deletedBy);
        repository.save(entry);
    }

    @Transactional(readOnly = true)
    public List<PhoneListEntry> getActiveByType(PhoneListType type) {
        return repository.findByTypeAndActiveTrueAndDeletedAtIsNull(type);
    }

    // Internal validation helpers
    private void validateScope(PhoneListType type, ScopeType scopeType, UUID scopeResellerId, UUID scopeTenantId) {
        if (type == PhoneListType.PLATFORM_BLOCKLIST || type == PhoneListType.PLATFORM_PROTECTED) {
            if (scopeType != ScopeType.PLATFORM || scopeResellerId != null || scopeTenantId != null) {
                throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Platform lists must have PLATFORM scope");
            }
        } else if (type == PhoneListType.RESELLER_BLOCKLIST || type == PhoneListType.RESELLER_WHITELIST) {
            if (scopeType != ScopeType.RESELLER || scopeResellerId == null) {
                throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Reseller lists require RESELLER scope");
            }
        } else if (type == PhoneListType.TENANT_BLOCKLIST || type == PhoneListType.TENANT_WHITELIST) {
            if (scopeType != ScopeType.TENANT || scopeTenantId == null) {
                throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "Tenant lists require TENANT scope");
            }
        }
    }

    private ScopeType mapScopeType(PhoneListType type) {
        return switch (type) {
            case PLATFORM_BLOCKLIST, PLATFORM_PROTECTED -> ScopeType.PLATFORM;
            case RESELLER_BLOCKLIST, RESELLER_WHITELIST -> ScopeType.RESELLER;
            case TENANT_BLOCKLIST, TENANT_WHITELIST -> ScopeType.TENANT;
        };
    }

    private UUID getScopeId(PhoneListType type, UUID resellerId, UUID tenantId) {
        if (type == PhoneListType.RESELLER_BLOCKLIST || type == PhoneListType.RESELLER_WHITELIST) {
            return resellerId;
        }
        return tenantId;
    }
}