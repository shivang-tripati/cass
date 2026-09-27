package com.shivang.obd.campaign;

import com.shivang.obd.campaign.config.CampaignTypeConfig;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the configuration a running execution must use (VB-6A
 * correction): always the execution's own immutable snapshot. This is the
 * single seam between execution runtime code and configuration state —
 * runtime services never read mutable campaign configuration for
 * execution-affecting values, and there is deliberately NO fallback to the
 * live campaign.
 * <p>
 * An execution without its snapshot is an internal data-integrity error
 * (the database NOT NULL FK plus same-transaction creation make it
 * impossible under normal operation); {@link
 * ExecutionConfigurationMissingException} is the deterministic failure for
 * a corrupted row.
 */
@Service
public class CampaignRuntimeConfigResolver {

    private final CampaignConfigurationService configurationService;

    public CampaignRuntimeConfigResolver(CampaignConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    /**
     * The configuration of the campaign as the given execution must see it.
     *
     * @param execution the running execution (tenant-scoped)
     * @return the execution's immutable configuration snapshot view
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public CampaignRuntimeConfig resolve(CampaignExecution execution) {
        return CampaignRuntimeConfig.fromSnapshot(
                configurationService.requireExecutionSnapshot(execution));
    }

    /**
     * Immutable view of the execution's configuration. Values always come
     * from the snapshot. Resource <em>validity</em> of the referenced
     * DID/audio/TTS is never carried here — runtime validation stays
     * dynamic by design (VB-6A snapshot-vs-resource rule).
     */
    public record CampaignRuntimeConfig(
            UUID campaignId,
            CampaignType campaignType,
            UUID contactGroupId,
            UUID didId,
            ContentMode contentMode,
            UUID audioAssetId,
            UUID ttsTemplateId,
            Boolean callOnWhitelistNumbers,
            RetryPolicySpec retryPolicy,
            ScheduleSpec schedule,
            com.shivang.obd.campaign.config.ConfigSchemaVersion typeConfigSchemaVersion,
            CampaignTypeConfig typeConfig,
            /** Campaign-configured Voice Blast daily dial limit (VB-6C.2); null = platform max. */
            Integer dailyDialLimit,
            /**
             * Campaign-configured daily campaign-ATTEMPT ceiling (VB-6D.3);
             * null = platform default. Distinct from {@code dailyDialLimit}:
             * that one is DNID-scoped provider-accepted dials, this one is
             * DNID-agnostic dispatches shared across every Voice Blast
             * campaign of the tenant for that contact-day.
             */
            Integer maxDailyAttempts,
            /**
             * VB-6E: frozen maximum call duration in seconds; null = platform
             * default of 300s. Bounds the active call session, not a ring
             * timeout and not a playback length.
             */
            Integer maxCallDurationSeconds) {

        /**
         * The pre-VB-6D.3 shape: no daily-attempt override, so the platform
         * default applies. Retained so existing construction sites keep their
         * exact previous meaning.
         */
        public CampaignRuntimeConfig(
                UUID campaignId,
                CampaignType campaignType,
                UUID contactGroupId,
                UUID didId,
                ContentMode contentMode,
                UUID audioAssetId,
                UUID ttsTemplateId,
                Boolean callOnWhitelistNumbers,
                RetryPolicySpec retryPolicy,
                ScheduleSpec schedule,
                com.shivang.obd.campaign.config.ConfigSchemaVersion typeConfigSchemaVersion,
                CampaignTypeConfig typeConfig,
                Integer dailyDialLimit) {
            this(campaignId, campaignType, contactGroupId, didId, contentMode, audioAssetId,
                    ttsTemplateId, callOnWhitelistNumbers, retryPolicy, schedule,
                    typeConfigSchemaVersion, typeConfig, dailyDialLimit, null, null);
        }

        static CampaignRuntimeConfig fromSnapshot(
                CampaignExecutionConfiguration snapshot) {
            CampaignConfigurationSnapshot s = snapshot.getConfiguration();
            return new CampaignRuntimeConfig(
                    snapshot.getCampaignId(),
                    s.getCampaignType(),
                    s.getContactGroupId(),
                    s.getDidId(),
                    s.getContentMode(),
                    s.getAudioAssetId(),
                    s.getTtsTemplateId(),
                    s.getCallOnWhitelistNumbers(),
                    s.retryPolicySpec(),
                    s.scheduleSpec(),
                    com.shivang.obd.campaign.config.ConfigSchemaVersion.V1,
                    parseTypeConfig(s.getCampaignType(), s.getTypeConfig()),
                    s.getDailyDialLimit(),
                    s.getMaxDailyAttempts(),
                    s.getMaxCallDurationSeconds());
        }

        private static CampaignTypeConfig parseTypeConfig(
                CampaignType type, tools.jackson.databind.JsonNode typeConfig) {
            try {
                return com.shivang.obd.campaign.config.CampaignTypeConfig
                        .fromTypeConfig(type, typeConfig);
            } catch (com.shivang.obd.campaign.config.CampaignConfigInvalidException e) {
                // Snapshots persist canonical validated JSON, so this cannot
                // happen through normal flow. A corrupted snapshot must fail
                // the runtime path exactly where it always did (DTMF parsing
                // at call time), not earlier — keep the raw payload's null
                // view instead of fabricating a typed config.
                return null;
            }
        }

        /** Typed view of the snapshot's DTMF configuration, when present. */
        public Optional<com.shivang.obd.campaign.config.DtmfCampaignConfig> asDtmf() {
            return typeConfig instanceof com.shivang.obd.campaign.config.DtmfCampaignConfig dtmf
                    ? Optional.of(dtmf)
                    : Optional.empty();
        }
    }
}
