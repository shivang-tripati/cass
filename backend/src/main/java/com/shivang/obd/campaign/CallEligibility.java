package com.shivang.obd.campaign;

import java.util.UUID;

/**
 * Call eligibility decision boundary.
 * <p>
 * The campaign module defines the interface; telephony provides the implementation
 * to keep module boundaries clean (no campaign -> telephony dependency).
 * <p>
 * VB-6A: the primary evaluation input is {@link Context} — exactly the
 * execution-affecting values eligibility depends on, so callers can evaluate
 * against an execution's immutable configuration snapshot. The
 * {@link CampaignEntity} overload remains as a compatibility adapter that
 * derives the context from a live campaign (used by legacy paths and tests).
 */
public interface CallEligibility {

    /**
     * Eligibility inputs, decoupled from the mutable campaign entity
     * (VB-6A): tenant scope, requested DID, audience provenance, and the
     * campaign-level whitelist enforcement flag.
     */
    record Context(
            UUID tenantId,
            UUID resellerId,
            UUID didId,
            UUID contactGroupId,
            boolean enforceWhitelist) {
    }

    /**
     * Evaluates call eligibility for a destination number using an explicit
     * eligibility context (VB-6A primary entry point).
     *
     * @param context eligibility inputs (snapshot values for running executions)
     * @param destinationNumber the destination phone number
     * @return eligibility result with reason code
     */
    EligibilityResult evaluate(Context context, String destinationNumber);

    /**
     * Compatibility adapter: evaluates against a live campaign entity by
     * deriving its eligibility context. Reseller resolution stays with the
     * implementation (as before).
     */
    default EligibilityResult evaluate(CampaignEntity campaign, String destinationNumber) {
        return evaluate(new Context(
                campaign.getTenantId(),
                null,
                campaign.getDidId(),
                campaign.getContactGroupId(),
                Boolean.TRUE.equals(campaign.getCallOnWhitelistNumbers())),
                destinationNumber);
    }

    /**
     * Result of an eligibility evaluation.
     */
    static class EligibilityResult {
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
