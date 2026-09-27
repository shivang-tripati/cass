package com.shivang.obd.telephony;

import com.shivang.obd.campaign.CallEligibility;
import com.shivang.obd.campaign.CampaignEntity;
import com.shivang.obd.contact.ContactRepository;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.eligibility.VoiceEligibility;
import com.shivang.obd.voice.eligibility.VoiceEligibility.EligibilityResult;
import com.shivang.obd.voice.media.PhoneNumberNormalizer;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Campaign-specific call eligibility adapter.
 * <p>
 * Delegates voice-layer eligibility (blocklists, DNC, DID validity, gateway availability)
 * to {@link VoiceEligibilityService}, and adds campaign-specific targeting logic:
 * - Whitelist enforcement (callOnWhitelistNumbers)
 * - Contact group membership validation
 * <p>
 * This keeps the campaign module's CallEligibility interface intact while
 * reusing the universal voice eligibility engine.
 * <p>
 * VB-6A: evaluation inputs arrive as a {@link Context} record so a running
 * execution can be evaluated against its immutable configuration snapshot;
 * the entity overload derives that context from a live campaign (legacy
 * paths and tests).
 */
@Service
@RequiredArgsConstructor
public class CallEligibilityService implements CallEligibility {

    private final VoiceEligibilityService voiceEligibility;
    private final ContactRepository contactRepository;
    private final com.shivang.obd.contact.ContactGroupMemberRepository memberRepository;
    private final TenantRepository tenantRepository;

    /**
     * Evaluates call eligibility for a contact using the given eligibility
     * context (VB-6A primary entry point).
     * <p>
     * Combines voice-layer eligibility with campaign targeting rules.
     */
    @Override
    @Transactional(readOnly = true)
    public EligibilityResult evaluate(CallEligibility.Context context, String destinationNumber) {
        UUID tenantId = context.tenantId();
        UUID didId = context.didId();
        boolean enforceWhitelist = context.enforceWhitelist();

        // Voice-layer eligibility (blocklists, DNC, DID validity, gateway availability)
        var voiceResult = voiceEligibility.evaluate(tenantId, destinationNumber, didId, enforceWhitelist);
        if (!voiceResult.isAllowed()) {
            return EligibilityResult.blocked(voiceResult.getReasonCode(), voiceResult.getReasonMessage());
        }

        // Campaign targeting: contact group membership (only when whitelist not enabled)
        if (!enforceWhitelist) {
            if (!isNumberInCampaignContactGroup(context.contactGroupId(), destinationNumber)) {
                return EligibilityResult.blocked("NOT_IN_CAMPAIGN_TARGETS", "Number not in campaign contact group");
            }
        }

        return EligibilityResult.allowed();
    }

    /**
     * Live-campaign adapter (VB-6A): resolves reseller from the tenant the
     * same way the voice layer does, then delegates to the context entry
     * point. Previously this adapter silently passed a null reseller; the
     * resolution here is the documented behavior of
     * {@code VoiceEligibilityService} itself, so results are unchanged.
     */
    @Override
    public EligibilityResult evaluate(CampaignEntity campaign, String destinationNumber) {
        UUID resellerId = campaign.getTenantId() != null
                ? tenantRepository.findByIdAndDeletedAtIsNull(campaign.getTenantId())
                        .map(TenantEntity::getResellerId)
                        .orElse(null)
                : null;
        return evaluate(new Context(
                campaign.getTenantId(),
                resellerId,
                campaign.getDidId(),
                campaign.getContactGroupId(),
                Boolean.TRUE.equals(campaign.getCallOnWhitelistNumbers())),
                destinationNumber);
    }

    /**
     * VB-6B.1: group targeting is evaluated through the membership bridge —
     * the number must be the canonical identity of a live member of the
     * group. The voice-layer normalizer prepares the dial string; identity
     * comparison uses the contact's canonical stored form.
     */
    private boolean isNumberInCampaignContactGroup(UUID contactGroupId, String destinationNumber) {
        if (contactGroupId == null) {
            return false;
        }
        String normalized = PhoneNumberNormalizer.normalize(destinationNumber);
        return memberRepository.findByContactGroupId(contactGroupId).stream()
                .map(com.shivang.obd.contact.ContactGroupMemberEntity::getContactId)
                .anyMatch(contactId -> contactRepository.findById(contactId)
                        .filter(c -> c.getDeletedAt() == null)
                        .map(c -> normalized.equals(c.getPhoneNumber()))
                        .orElse(false));
    }
}
