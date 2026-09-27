package com.shivang.obd.campaign;

import com.shivang.obd.audio.AudioAssetEntity;
import com.shivang.obd.audio.AudioAssetRepository;
import com.shivang.obd.audio.AudioAssetStatus;
import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.tts.TtsTemplateRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Canonical campaign-resource validation boundary (VB-5E).
 * <p>
 * One authoritative implementation of the question <em>"is this campaign
 * resource currently usable by this tenant?"</em> for the three resource
 * kinds a campaign depends on:
 * <ul>
 *   <li><b>DID</b> — VB-5C ownership/allocation semantics: live, not
 *       soft-deleted, owned by the campaign tenant, {@code ACTIVE} and
 *       {@code ASSIGNED}.</li>
 *   <li><b>Audio</b> — VB-5B governance semantics: live, not soft-deleted,
 *       tenant-owned, {@code APPROVED}, and carrying a usable (non-blank)
 *       logical {@code storageReference}. Physical storage resolution is
 *       deliberately out of scope — only persisted logical validity is
 *       reasoned about here.</li>
 *   <li><b>TTS</b> — VB-5D scope governance semantics: the authoritative
 *       {@code existsUsableForTenant} predicate (GLOBAL + APPROVED, or
 *       TENANT-owned + APPROVED, both not deleted), with the tenant-safe
 *       {@code existsAccessibleForTenant} superset used only to classify
 *       "accessible but not approved" without leaking foreign-row
 *       existence.</li>
 * </ul>
 * <p>
 * The same semantics are consumed at CREATE, UPDATE,
 * activation/readiness, and runtime execution. Callers map the fine-grained
 * {@link ValidationCode}s onto their existing externally observable error
 * surfaces (400 validation errors, readiness reasons, runtime failure
 * codes); this service never throws and never maps to HTTP concepts.
 * <p>
 * Contract guarantees:
 * <ul>
 *   <li><b>Side-effect free</b> — read-only repository existence/lookup
 *       queries only. No calls, media, storage writes, reservations,
 *       locks, or state transitions. Safe to invoke repeatedly.</li>
 *   <li><b>Tenant-safe</b> — every query constrains the tenant boundary;
 *       foreign resources are indistinguishable from nonexistent ones
 *       ({@code *_NOT_AVAILABLE} codes only ever mean "not usable from
 *       here").</li>
 *   <li><b>Not authoritative for policy</b> — a {@code null} reference is
 *       reported as not usable; callers that permit absent references
 *       (e.g. no existing product rule makes a DID mandatory) keep their
 *       own null-guards before delegating.</li>
 * </ul>
 * <p>
 * No FreeSWITCH, storage, provider, scheduler, or AI dependencies —
 * future TTS runtime execution is expected to consume this same contract.
 */
@Service
@RequiredArgsConstructor
public class CampaignResourceValidationService {

    private final DidRepository didRepository;
    private final AudioAssetRepository audioAssetRepository;
    private final TtsTemplateRepository ttsTemplateRepository;

    /**
     * Fine-grained, resource-scoped validation outcomes. These are internal
     * classification codes — each caller maps them onto its existing
     * externally visible error semantics (never renamed here).
     */
    public enum ValidationCode {
        /** DID missing, soft-deleted, foreign, INACTIVE, or not ASSIGNED. */
        DID_NOT_AVAILABLE,
        /** Audio asset missing, soft-deleted, or foreign. */
        AUDIO_NOT_AVAILABLE,
        /** Audio asset resolvable for the tenant but not APPROVED. */
        AUDIO_NOT_APPROVED,
        /** Audio asset approved but without a usable storage reference. */
        AUDIO_STORAGE_REFERENCE_MISSING,
        /** TTS template missing, soft-deleted, or not visible to the tenant. */
        TTS_NOT_AVAILABLE,
        /** TTS template visible to the tenant but not APPROVED. */
        TTS_NOT_APPROVED
    }

    /**
     * Immutable validation outcome: {@code usable} answers the canonical
     * question; {@code code} classifies the failure ({@code null} when
     * usable).
     */
    public record ResourceValidationResult(boolean usable, ValidationCode code) {

        public static ResourceValidationResult valid() {
            return new ResourceValidationResult(true, null);
        }

        public static ResourceValidationResult invalid(ValidationCode code) {
            return new ResourceValidationResult(false, code);
        }
    }

    /**
     * Validates a campaign DID reference against the VB-5C ownership and
     * allocation model.
     *
     * @param didId    DID reference (may be {@code null} → not usable)
     * @param tenantId campaign tenant (server-derived)
     * @return valid only when the DID is live, tenant-owned, ACTIVE and ASSIGNED
     */
    public ResourceValidationResult validateDid(UUID didId, UUID tenantId) {
        if (didId == null || tenantId == null) {
            return ResourceValidationResult.invalid(ValidationCode.DID_NOT_AVAILABLE);
        }
        boolean usable = didRepository.existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
            didId, tenantId, DidStatus.ACTIVE, AllocationState.ASSIGNED);
        return usable
            ? ResourceValidationResult.valid()
            : ResourceValidationResult.invalid(ValidationCode.DID_NOT_AVAILABLE);
    }

    /**
     * Validates a campaign audio asset reference against the VB-5B
     * governance model. Two-phase within one lookup: approval first, then
     * storage reference, so the common healthy path and each failure class
     * are classified from the single tenant-bound row.
     *
     * @param audioAssetId audio asset reference (may be {@code null} → not usable)
     * @param tenantId     campaign tenant (server-derived)
     * @return valid only when the asset is live, tenant-owned, APPROVED and
     *         carries a non-blank storage reference
     */
    public ResourceValidationResult validateAudio(UUID audioAssetId, UUID tenantId) {
        if (audioAssetId == null || tenantId == null) {
            return ResourceValidationResult.invalid(ValidationCode.AUDIO_NOT_AVAILABLE);
        }
        return audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(audioAssetId, tenantId)
            .map(this::classifyAudio)
            .orElseGet(() -> ResourceValidationResult.invalid(ValidationCode.AUDIO_NOT_AVAILABLE));
    }

    private ResourceValidationResult classifyAudio(AudioAssetEntity asset) {
        if (asset.getStatus() != AudioAssetStatus.APPROVED) {
            return ResourceValidationResult.invalid(ValidationCode.AUDIO_NOT_APPROVED);
        }
        if (asset.getStorageReference() == null || asset.getStorageReference().isBlank()) {
            return ResourceValidationResult.invalid(ValidationCode.AUDIO_STORAGE_REFERENCE_MISSING);
        }
        return ResourceValidationResult.valid();
    }

    /**
     * Validates a campaign TTS template reference against the VB-5D GLOBAL/
     * TENANT governance model. Delegates to the authoritative
     * {@code existsUsableForTenant} predicate; {@code existsAccessibleForTenant}
     * is used only for the tenant-safe not-approved classification.
     *
     * @param ttsTemplateId TTS template reference (may be {@code null} → not usable)
     * @param tenantId      campaign tenant (server-derived)
     * @return valid only when the template is usable by the tenant (approved
     *         GLOBAL, or approved TENANT owned by exactly this tenant)
     */
    public ResourceValidationResult validateTts(UUID ttsTemplateId, UUID tenantId) {
        if (ttsTemplateId == null || tenantId == null) {
            return ResourceValidationResult.invalid(ValidationCode.TTS_NOT_AVAILABLE);
        }
        if (ttsTemplateRepository.existsUsableForTenant(ttsTemplateId, tenantId)) {
            return ResourceValidationResult.valid();
        }
        boolean accessibleButUnapproved = ttsTemplateRepository.existsAccessibleForTenant(
            ttsTemplateId, tenantId);
        return ResourceValidationResult.invalid(accessibleButUnapproved
            ? ValidationCode.TTS_NOT_APPROVED
            : ValidationCode.TTS_NOT_AVAILABLE);
    }
}
