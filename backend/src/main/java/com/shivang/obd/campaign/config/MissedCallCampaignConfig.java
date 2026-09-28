package com.shivang.obd.campaign.config;

import com.shivang.obd.campaign.CampaignType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Typed configuration for {@link CampaignType#MISSED_CALL} campaigns (VB-7B).
 *
 * <h2>One field, on purpose</h2>
 *
 * <p>A MISSED_CALL campaign's other five concerns are <em>not</em> here, and
 * deliberately so — they already exist as campaign-level columns with their own
 * single authorities, and all of them are already frozen into the execution
 * snapshot by VB-6A:
 *
 * <ul>
 *   <li><b>DID</b> — {@code campaigns.did_id}, validated by
 *       {@code CampaignResourceValidationService.validateDid} and frozen as
 *       {@code did_id}.</li>
 *   <li><b>audience</b> — {@code campaigns.contact_group_id}, resolved as live
 *       group membership at execution creation (VB-6B.1 "Model A") and frozen as
 *       {@code contact_group_id}.</li>
 *   <li><b>retry policy</b> — the four retry columns plus {@code retry_rules},
 *       owned by {@code RetryPolicyService} and frozen verbatim.</li>
 *   <li><b>schedule</b> — the seven schedule columns, owned by the execution
 *       orchestrator and frozen as the schedule spec.</li>
 *   <li><b>safety policy</b> — {@code daily_dial_limit} (VB-6C.2) and
 *       {@code max_daily_attempts} (VB-6D.3), both campaign-type-neutral and
 *       both frozen.</li>
 * </ul>
 *
 * <p>Duplicating any of them here would create a second source of truth that the
 * snapshot, the validator and the runtime would all have to be kept in sync
 * with — the identical argument VB-7A used to justify storing only a
 * {@code queueId}. The one genuinely new value is the time budget.
 *
 * <h2>What it deliberately has none of</h2>
 *
 * <p>No {@code audioAssetId}, no content mode, no TTS template, no DTMF
 * configuration, no IVR tree, no queue, no agent selection, no agent ring
 * window. A MISSED_CALL campaign plays <b>no media at all</b>: the originate
 * command carries no media argument, so playback is simply never invoked.
 *
 * <h2>Campaign type safety</h2>
 *
 * <p>This record is a permitted case of the sealed {@link CampaignTypeConfig},
 * reached only through its exhaustive {@code fromTypeConfig} dispatch. A
 * PLAYFILE, DTMF, IVR or CONNECT_BY_AGENT payload therefore cannot carry a
 * ring window: it is parsed by its own strict type, which has no such field, and
 * conversely a {@code {"missedCall":…}} payload is rejected by every other type
 * (PLAYFILE refuses any non-empty payload; the others read only their own root
 * key). The invariant is structural, not a rule someone has to remember.
 *
 * <h2>On-disk shape</h2>
 *
 * <pre>{@code {"missedCall":{"ringDurationSeconds":30}}}</pre>
 *
 * matching the existing {@code {"dtmf":{…}}} (VB-2), {@code {"ivr":{…}}}
 * (VB-6F) and {@code {"connectByAgent":{…}}} (VB-7A) root-key convention, in the
 * existing {@code type_config} JSONB. No migration is required for it.
 */
public record MissedCallCampaignConfig(
        Integer ringDurationSeconds,
        ConfigSchemaVersion schemaVersion) implements CampaignTypeConfig {

    /** Root JSON key, following the {@code dtmf} / {@code ivr} convention. */
    public static final String KEY = "missedCall";

    public MissedCallCampaignConfig {
        if (ringDurationSeconds == null) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY + ".ringDurationSeconds is required for a "
                            + "MISSED_CALL campaign");
        }
        if (!MissedCallRingWindow.isValid(ringDurationSeconds)) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY + ".ringDurationSeconds must be between "
                            + MissedCallRingWindow.MIN_RING_SECONDS + " and "
                            + MissedCallRingWindow.MAX_RING_SECONDS + " seconds");
        }
        if (schemaVersion == null) {
            throw new CampaignConfigInvalidException("schemaVersion is required");
        }
    }

    /** The explicit configuration, with no defaulting applied. */
    public static MissedCallCampaignConfig of(Integer ringDurationSeconds) {
        return new MissedCallCampaignConfig(ringDurationSeconds, ConfigSchemaVersion.V1);
    }

    @Override
    public CampaignType campaignType() {
        return CampaignType.MISSED_CALL;
    }

    /** The effective time budget in seconds; the stored value is already validated. */
    public int effectiveRingSeconds() {
        return MissedCallRingWindow.effectiveSeconds(ringDurationSeconds);
    }

    @Override
    public JsonNode toJson() {
        ObjectNode inner = JsonNodeFactory.instance.objectNode();
        inner.put("ringDurationSeconds", ringDurationSeconds.intValue());
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set(KEY, inner);
        return root;
    }

    /**
     * Parses a MISSED_CALL payload. Strict and total: an absent, malformed or
     * differently-shaped payload throws rather than degrading, so a campaign can
     * never be activated or snapshotted against a configuration the runtime
     * cannot read.
     */
    public static MissedCallCampaignConfig fromTypeConfig(JsonNode typeConfig) {
        if (typeConfig == null || typeConfig.isNull() || !typeConfig.isObject()) {
            throw new CampaignConfigInvalidException(
                    "MISSED_CALL requires a type_config object with a \"" + KEY + "\" entry");
        }
        JsonNode inner = typeConfig.get(KEY);
        if (inner == null || inner.isNull()) {
            throw new CampaignConfigInvalidException(
                    "MISSED_CALL requires type_config." + KEY
                            + " with a ringDurationSeconds");
        }
        if (!inner.isObject()) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY + " must be an object");
        }
        rejectUnknownFields(inner);
        return new MissedCallCampaignConfig(
                readRingSeconds(inner), ConfigSchemaVersion.V1);
    }

    private static Integer readRingSeconds(JsonNode inner) {
        JsonNode node = inner.get("ringDurationSeconds");
        if (node == null || node.isNull()) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY + ".ringDurationSeconds is required");
        }
        if (!node.isIntegralNumber()) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY
                            + ".ringDurationSeconds must be a whole number of seconds");
        }
        int seconds = node.asInt();
        if (!MissedCallRingWindow.isValid(seconds)) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY + ".ringDurationSeconds must be between "
                            + MissedCallRingWindow.MIN_RING_SECONDS + " and "
                            + MissedCallRingWindow.MAX_RING_SECONDS + " seconds");
        }
        return seconds;
    }

    /**
     * Rejects anything the runtime would silently ignore. A stored field the
     * execution path does not read is configuration that looks real and does
     * nothing, so an unrecognised key is an error, not a shrug.
     */
    private static void rejectUnknownFields(JsonNode inner) {
        inner.properties().forEach(entry -> {
            switch (entry.getKey()) {
                case "ringDurationSeconds" -> {
                }
                default -> throw new CampaignConfigInvalidException(
                        "type_config." + KEY + "." + entry.getKey()
                                + " is not a supported field; supported: ringDurationSeconds");
            }
        });
    }
}
