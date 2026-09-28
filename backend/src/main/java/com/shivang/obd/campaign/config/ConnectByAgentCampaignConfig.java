package com.shivang.obd.campaign.config;

import com.shivang.obd.campaign.CampaignType;
import com.shivang.obd.voice.agent.AgentRingWindow;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Typed configuration for {@link CampaignType#CONNECT_BY_AGENT} campaigns
 * (VB-7A).
 *
 * <h2>What replaced what</h2>
 *
 * <p>VB-6A gave this type a placeholder: a {@code JsonNode legacyPayload} that
 * accepted <em>any</em> non-empty object, on the documented reasoning that
 * agent routing was "service-driven" and no queue or agent configuration had
 * been decided. VB-7A decides it. The placeholder is gone — there is no
 * {@code JsonNode} escape hatch here, and a payload that is not exactly this
 * shape is a {@link CampaignConfigInvalidException}, which
 * {@code GlobalExceptionHandler} already maps to HTTP 400.
 *
 * <h2>What the campaign owns, and what it deliberately does not</h2>
 *
 * <p>The campaign owns only the three things that are genuinely campaign policy:
 *
 * <ul>
 *   <li><b>{@code queueId}</b> — which existing queue this campaign's calls are
 *       offered to. The queue (VB-4B) remains the single source of truth for
 *       membership, administrative lifecycle, and therefore agent eligibility.
 *       Campaign stores a reference, exactly as it stores a {@code didId} or an
 *       IVR {@code treeId}.</li>
 *   <li><b>{@code selectionStrategy}</b> — which existing selection rule to
 *       order candidates with. See {@link AgentSelectionStrategy} for why there
 *       is exactly one value.</li>
 *   <li><b>{@code ringDurationSeconds}</b> — how long an already-answered
 *       outbound call rings its reserved agent before {@code AGENT_NO_ANSWER}.
 *       Bounded by {@link AgentRingWindow}, the same authority the enforcing
 *       scheduler uses.</li>
 * </ul>
 *
 * <p>It stores <b>no</b> agent ids, no membership, no availability, no capacity
 * and no queue depth. Those are runtime facts owned by
 * {@code AgentAdminStatus}/{@code AgentAvailability} and the reservation
 * lifecycle; freezing them would create a second source of truth that is wrong
 * the moment an agent goes on a break. The execution snapshot freezes this
 * record verbatim, and the queue's <em>validity</em> is re-checked on every
 * execution, exactly as a DID's is.
 *
 * <h2>Campaign type safety</h2>
 *
 * <p>This record is a permitted case of the sealed
 * {@link CampaignTypeConfig}, reached only through its exhaustive
 * {@code fromTypeConfig} dispatch. A {@code PLAYFILE} or {@code DTMF} payload
 * therefore cannot carry a queue: it is parsed by its own strict type, which
 * has no such field, and the CONNECT_BY_AGENT-only invariant is structural
 * rather than a rule someone has to remember to enforce.
 *
 * <h2>On-disk shape</h2>
 *
 * <pre>{@code {"connectByAgent":{"queueId":"<uuid>",
 *                              "selectionStrategy":"LEAST_ACTIVE_RESERVATIONS",
 *                              "ringDurationSeconds":60}}}</pre>
 *
 * matching the existing {@code {"dtmf":{…}}} (VB-2) and {@code {"ivr":{…}}}
 * (VB-6F) root-key convention, in the existing {@code type_config} JSONB.
 */
public record ConnectByAgentCampaignConfig(
        UUID queueId,
        AgentSelectionStrategy selectionStrategy,
        Integer ringDurationSeconds,
        ConfigSchemaVersion schemaVersion) implements CampaignTypeConfig {

    /** Root JSON key, following the {@code dtmf} / {@code ivr} convention. */
    public static final String KEY = "connectByAgent";

    public ConnectByAgentCampaignConfig {
        if (queueId == null) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY + ".queueId is required for a CONNECT_BY_AGENT campaign");
        }
        if (selectionStrategy == null) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY + ".selectionStrategy is required; supported: "
                            + supportedStrategies());
        }
        if (!AgentRingWindow.isValid(ringDurationSeconds)) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY + ".ringDurationSeconds must be between "
                            + AgentRingWindow.MIN_RING_SECONDS + " and "
                            + AgentRingWindow.MAX_RING_SECONDS + " seconds");
        }
        if (schemaVersion == null) {
            throw new CampaignConfigInvalidException("schemaVersion is required");
        }
    }

    /** The request-side configuration, applying the platform ring default. */
    public static ConnectByAgentCampaignConfig of(
            UUID queueId, AgentSelectionStrategy selectionStrategy, Integer ringDurationSeconds) {
        return new ConnectByAgentCampaignConfig(queueId, selectionStrategy,
                AgentRingWindow.effectiveSeconds(ringDurationSeconds), ConfigSchemaVersion.V1);
    }

    @Override
    public CampaignType campaignType() {
        return CampaignType.CONNECT_BY_AGENT;
    }

    /** The effective ring window; the stored value is already validated. */
    public int effectiveRingSeconds() {
        return AgentRingWindow.effectiveSeconds(ringDurationSeconds);
    }

    @Override
    public JsonNode toJson() {
        ObjectNode inner = JsonNodeFactory.instance.objectNode();
        inner.put("queueId", queueId.toString());
        inner.put("selectionStrategy", selectionStrategy.name());
        inner.put("ringDurationSeconds", ringDurationSeconds.intValue());
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set(KEY, inner);
        return root;
    }

    /**
     * Parses a CONNECT_BY_AGENT payload. Strict and total: an absent, malformed
     * or differently-shaped payload throws rather than degrading, so a campaign
     * can never be activated or snapshotted against a configuration the runtime
     * cannot read.
     */
    public static ConnectByAgentCampaignConfig fromTypeConfig(JsonNode typeConfig) {
        if (typeConfig == null || typeConfig.isNull() || !typeConfig.isObject()) {
            throw new CampaignConfigInvalidException(
                    "CONNECT_BY_AGENT requires a type_config object with a \"" + KEY + "\" entry");
        }
        JsonNode inner = typeConfig.get(KEY);
        if (inner == null || inner.isNull()) {
            throw new CampaignConfigInvalidException(
                    "CONNECT_BY_AGENT requires type_config." + KEY
                            + " with a queueId, selectionStrategy and ringDurationSeconds");
        }
        if (!inner.isObject()) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY + " must be an object");
        }

        UUID queueId = readQueueId(inner);
        AgentSelectionStrategy strategy = readStrategy(inner);
        Integer ringSeconds = readRingSeconds(inner);
        rejectUnknownFields(inner);
        return new ConnectByAgentCampaignConfig(
                queueId, strategy, ringSeconds, ConfigSchemaVersion.V1);
    }

    private static UUID readQueueId(JsonNode inner) {
        JsonNode node = inner.get("queueId");
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY + ".queueId is required");
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY + ".queueId is not a valid identifier");
        }
    }

    private static AgentSelectionStrategy readStrategy(JsonNode inner) {
        JsonNode node = inner.get("selectionStrategy");
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY + ".selectionStrategy is required; supported: "
                            + supportedStrategies());
        }
        try {
            return AgentSelectionStrategy.valueOf(node.asText());
        } catch (IllegalArgumentException e) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY + ".selectionStrategy '" + node.asText()
                            + "' is not supported; supported: " + supportedStrategies());
        }
    }

    private static Integer readRingSeconds(JsonNode inner) {
        JsonNode node = inner.get("ringDurationSeconds");
        if (node == null || node.isNull() || !node.isIntegralNumber()) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY + ".ringDurationSeconds must be a whole number of seconds");
        }
        int seconds = node.asInt();
        if (!AgentRingWindow.isValid(seconds)) {
            throw new CampaignConfigInvalidException(
                    "type_config." + KEY + ".ringDurationSeconds must be between "
                            + AgentRingWindow.MIN_RING_SECONDS + " and "
                            + AgentRingWindow.MAX_RING_SECONDS + " seconds");
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
                case "queueId", "selectionStrategy", "ringDurationSeconds" -> {
                }
                default -> throw new CampaignConfigInvalidException(
                        "type_config." + KEY + "." + entry.getKey()
                                + " is not a supported field; supported: "
                                + "queueId, selectionStrategy, ringDurationSeconds");
            }
        });
    }

    private static String supportedStrategies() {
        StringBuilder names = new StringBuilder();
        for (AgentSelectionStrategy strategy : AgentSelectionStrategy.values()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(strategy.name());
        }
        return names.toString();
    }
}
