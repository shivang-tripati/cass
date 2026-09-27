package com.shivang.obd.telephony;

import com.shivang.obd.voice.media.PhoneNumberNormalizer;
import com.shivang.obd.did.DidEntity;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.telephony.PhoneListEntryRepository;
import com.shivang.obd.telephony.PhoneListType;
import com.shivang.obd.telephony.ScopeType;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.capacity.VoiceCapacityService;
import com.shivang.obd.voice.eligibility.VoiceEligibility;
import com.shivang.obd.voice.routing.VoiceRoute;
import com.shivang.obd.voice.routing.VoiceRouting;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Voice eligibility decision engine implementation.
 * <p>
 * Evaluates platform/tenant/reseller blocklists/whitelists and DID compatibility
 * before a call attempt is handed to the dialer.
 * <p>
 * This is the reusable VOICE-LAYER eligibility.
 * Campaign-specific targeting (contact group membership) is handled separately
 * by the campaign module via {@link CallEligibility}.
 * <p>
 * Eligibility precedence (highest to lowest):
 * 1. Platform Blocklist
 * 2. Platform Protected Numbers
 * 3. Reseller Blocklist
 * 4. Tenant Blocklist/DNC
 * 5. Whitelist allow (if enabled)
 * 6. DID validity
 * 7. DID/provider compatibility
 * 8. Gateway availability
 */
@Service
@RequiredArgsConstructor
public class VoiceEligibilityService implements VoiceEligibility {

    private final PhoneListEntryRepository phoneListRepository;
    private final VoiceRouting voiceRouting;
    private final VoiceCapacityService voiceCapacity;
    private final TenantRepository tenantRepository;
    private final DidRepository didRepository;

    /**
     * Evaluates voice-layer call eligibility for a destination number.
     * <p>
     * Reseller is resolved from tenant internally.
     *
     * @param tenantId the tenant owning the call
     * @param destinationNumber the destination phone number (E.164)
     * @param didId the DID being used (for DID/provider compatibility)
     * @return eligibility result with reason code
     */
    @Override
    @Transactional(readOnly = true)
    public EligibilityResult evaluate(UUID tenantId, String destinationNumber, UUID didId) {
        UUID resellerId = getResellerId(tenantId);
        return evaluate(tenantId, resellerId, destinationNumber, didId);
    }

    /**
     * Evaluates voice-layer call eligibility for a destination number with explicit reseller.
     *
     * @param tenantId the tenant owning the call
     * @param resellerId the reseller (if applicable)
     * @param destinationNumber the destination phone number (E.164)
     * @param didId the DID being used (for DID/provider compatibility)
     * @return eligibility result with reason code
     */
    public EligibilityResult evaluate(UUID tenantId, UUID resellerId, String destinationNumber, UUID didId) {
        String normalized = PhoneNumberNormalizer.normalize(destinationNumber);
        if (!PhoneNumberNormalizer.isValidE164(normalized)) {
            return EligibilityResult.blocked("INVALID_NUMBER", "Invalid phone number format");
        }

        // 1. Platform blocklist check
        if (isBlocked(tenantId, normalized, PhoneListType.PLATFORM_BLOCKLIST, null, null)) {
            return EligibilityResult.blocked("PLATFORM_BLOCKED", "Number blocked by platform");
        }

        // 2. Platform protected numbers
        if (isBlocked(tenantId, normalized, PhoneListType.PLATFORM_PROTECTED, null, null)) {
            return EligibilityResult.blocked("PLATFORM_PROTECTED", "Number protected by platform");
        }

        // 3. Reseller blocklist (if tenant has reseller)
        if (resellerId != null) {
            if (isBlocked(tenantId, normalized, PhoneListType.RESELLER_BLOCKLIST, resellerId, null)) {
                return EligibilityResult.blocked("RESELLER_BLOCKED", "Number blocked by reseller");
            }
        }

        // 4. Tenant DNC/Blocklist
        if (isBlocked(tenantId, normalized, PhoneListType.TENANT_BLOCKLIST, null, tenantId)) {
            return EligibilityResult.blocked("DNC_BLOCKED", "Number blocked by tenant DNC");
        }

        // 5. Whitelist check (only if explicitly enabled via parameter)
        // Note: whitelist is opt-in; the caller decides whether to enforce it
        // This method doesn't enforce whitelist by default - caller passes a flag if needed
        // For backward compatibility, we leave whitelist enforcement to the caller

        // 6. DID validity
        if (didId == null) {
            return EligibilityResult.blocked("INVALID_DID", "No DID assigned");
        }
        Optional<DidEntity> didOpt = didRepository.findByIdAndDeletedAtIsNull(didId);
        if (didOpt.isEmpty()) {
            return EligibilityResult.blocked("INVALID_DID", "DID not found");
        }
        DidEntity did = didOpt.get();
        if (did.getStatus() != com.shivang.obd.did.DidStatus.ACTIVE) {
            return EligibilityResult.blocked("INVALID_DID", "DID is not active");
        }
        if (did.getAllocationState() != com.shivang.obd.did.AllocationState.ASSIGNED) {
            return EligibilityResult.blocked("INVALID_DID", "DID is not assigned");
        }
        if (!tenantId.equals(did.getTenantId())) {
            return EligibilityResult.blocked("INVALID_DID", "DID does not belong to tenant");
        }

        // 7. DID/provider compatibility with available gateways
        String provider = did.getProvider();
        Optional<VoiceRoute> gatewayRoute = voiceRouting.resolve(tenantId, resellerId, provider);
        if (gatewayRoute.isEmpty()) {
            return EligibilityResult.blocked("NO_ELIGIBLE_GATEWAY", "No compatible gateway for DID provider");
        }

        // 8. Gateway availability check (delegates to VoiceCapacityService)
        if (!voiceCapacity.isAvailable(gatewayRoute.get().gatewayId(), tenantId)) {
            return EligibilityResult.blocked("TEMPORARILY_UNAVAILABLE", "No available gateway capacity");
        }

        return EligibilityResult.allowed();
    }

    /**
     * Evaluates voice-layer eligibility with optional whitelist enforcement.
     *
     * @param tenantId the tenant owning the call
     * @param destinationNumber the destination phone number (E.164)
     * @param didId the DID being used
     * @param enforceWhitelist whether to enforce whitelist (callOnWhitelistNumbers)
     * @return eligibility result with reason code
     */
    public EligibilityResult evaluate(UUID tenantId, String destinationNumber, UUID didId, boolean enforceWhitelist) {
        UUID resellerId = getResellerId(tenantId);
        return evaluate(tenantId, resellerId, destinationNumber, didId, enforceWhitelist);
    }

    /**
     * Evaluates voice-layer eligibility with optional whitelist enforcement and explicit reseller.
     *
     * @param tenantId the tenant owning the call
     * @param resellerId the reseller (if applicable)
     * @param destinationNumber the destination phone number (E.164)
     * @param didId the DID being used
     * @param enforceWhitelist whether to enforce whitelist (callOnWhitelistNumbers)
     * @return eligibility result with reason code
     */
    public EligibilityResult evaluate(UUID tenantId, UUID resellerId, String destinationNumber, UUID didId, boolean enforceWhitelist) {
        EligibilityResult result = evaluate(tenantId, resellerId, destinationNumber, didId);
        if (!result.isAllowed()) {
            return result;
        }
        if (enforceWhitelist) {
            if (!isWhitelisted(tenantId, normalized(destinationNumber), tenantId, resellerId)) {
                return EligibilityResult.blocked("NOT_WHITELISTED", "Number not on tenant whitelist");
            }
        }
        return EligibilityResult.allowed();
    }

    private UUID getResellerId(UUID tenantId) {
        return tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .map(TenantEntity::getResellerId)
                .orElse(null);
    }

    private String normalized(String number) {
        return PhoneNumberNormalizer.normalize(number);
    }

    private boolean isBlocked(UUID campaignTenantId, String normalized, PhoneListType type, UUID resellerId, UUID tenantId) {
        // Check platform lists (no scope)
        if (type == PhoneListType.PLATFORM_BLOCKLIST || type == PhoneListType.PLATFORM_PROTECTED) {
            return phoneListRepository.findByTypeAndScopeTypeAndActiveTrueAndDeletedAtIsNull(type, ScopeType.PLATFORM)
                    .stream()
                    .anyMatch(e -> e.getNormalizedNumber().equals(normalized));
        }

        // Tenant-scoped lists
        if (tenantId != null) {
            if (type == PhoneListType.TENANT_BLOCKLIST || type == PhoneListType.TENANT_WHITELIST) {
                return phoneListRepository
                        .findByTypeAndScopeTypeAndScopeTenantIdAndActiveTrueAndDeletedAtIsNull(
                                type, ScopeType.TENANT, tenantId)
                        .stream()
                        .anyMatch(e -> e.getNormalizedNumber().equals(normalized));
            }
        }

        // Reseller-scoped lists
        if (resellerId != null) {
            if (type == PhoneListType.RESELLER_BLOCKLIST || type == PhoneListType.RESELLER_WHITELIST) {
                return phoneListRepository
                        .findByTypeAndScopeTypeAndScopeResellerIdAndActiveTrueAndDeletedAtIsNull(
                                type, ScopeType.RESELLER, resellerId)
                        .stream()
                        .anyMatch(e -> e.getNormalizedNumber().equals(normalized));
            }
        }

        return false;
    }

    private boolean isWhitelisted(UUID campaignTenantId, String normalized, UUID tenantId, UUID resellerId) {
        // Check tenant whitelist
        if (tenantId != null) {
            if (phoneListRepository.findByTypeAndScopeTypeAndScopeTenantIdAndActiveTrueAndDeletedAtIsNull(
                    PhoneListType.TENANT_WHITELIST, ScopeType.TENANT, tenantId)
                    .stream()
                    .anyMatch(e -> e.getNormalizedNumber().equals(normalized))) {
                return true;
            }
        }
        // Check reseller whitelist if tenant has reseller
        if (resellerId != null) {
            if (phoneListRepository.findByTypeAndScopeTypeAndScopeResellerIdAndActiveTrueAndDeletedAtIsNull(
                    PhoneListType.RESELLER_WHITELIST, ScopeType.RESELLER, resellerId)
                    .stream()
                    .anyMatch(e -> e.getNormalizedNumber().equals(normalized))) {
                return true;
            }
        }
        return false;
    }
}