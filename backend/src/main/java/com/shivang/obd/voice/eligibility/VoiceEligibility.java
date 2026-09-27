package com.shivang.obd.voice.eligibility;

import java.util.UUID;

/**
 * Voice eligibility decision boundary.
 * <p>
 * Determines if a number can legally/technically be called.
 * This is the reusable voice-layer eligibility, separate from campaign targeting.
 * <p>
 * Conceptual distinction:
 * <ul>
 *   <li>VOICE ELIGIBILITY: Can this number be called? (platform blocklist, DNC, DID validity, gateway availability)</li>
 *   <li>CAMPAIGN TARGETING: Should this campaign target this number? (contact group membership, campaign filters)</li>
 * </ul>
 * The campaign layer composes both: voice eligibility + campaign targeting = final dial decision.
 */
public interface VoiceEligibility {

    /**
     * Evaluates voice-layer call eligibility for a destination number.
     * Reseller is resolved from tenant internally.
     *
     * @param tenantId the tenant owning the call
     * @param destinationNumber the destination phone number (E.164)
     * @param didId the DID being used (for DID/provider compatibility)
     * @return eligibility result with reason code
     */
    EligibilityResult evaluate(UUID tenantId, String destinationNumber, UUID didId);

    /**
     * Evaluates voice-layer call eligibility with explicit reseller.
     *
     * @param tenantId the tenant owning the call
     * @param resellerId the reseller (if applicable)
     * @param destinationNumber the destination phone number (E.164)
     * @param didId the DID being used (for DID/provider compatibility)
     * @return eligibility result with reason code
     */
    EligibilityResult evaluate(UUID tenantId, UUID resellerId, String destinationNumber, UUID didId);

    /**
     * Evaluates voice-layer eligibility with optional whitelist enforcement.
     *
     * @param tenantId the tenant owning the call
     * @param destinationNumber the destination phone number (E.164)
     * @param didId the DID being used
     * @param enforceWhitelist whether to enforce whitelist (callOnWhitelistNumbers)
     * @return eligibility result with reason code
     */
    EligibilityResult evaluate(UUID tenantId, String destinationNumber, UUID didId, boolean enforceWhitelist);

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
    EligibilityResult evaluate(UUID tenantId, UUID resellerId, String destinationNumber, UUID didId, boolean enforceWhitelist);

    /**
     * Result of an eligibility evaluation.
     */
    class EligibilityResult {
        private final boolean allowed;
        private final String reasonCode;
        private final String reasonMessage;

        private EligibilityResult(boolean allowed, String reasonCode, String reasonMessage) {
            this.allowed = allowed;
            this.reasonCode = reasonCode;
            this.reasonMessage = reasonMessage;
        }

        public static EligibilityResult allowed() {
            return new EligibilityResult(true, null, null);
        }

        public static EligibilityResult blocked(String code, String message) {
            return new EligibilityResult(false, code, message);
        }

        public boolean isAllowed() { return allowed; }
        public String getReasonCode() { return reasonCode; }
        public String getReasonMessage() { return reasonMessage; }
    }
}