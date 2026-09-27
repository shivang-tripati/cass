package com.shivang.obd.campaign.config;

import com.shivang.obd.campaign.CampaignType;
import com.shivang.obd.voice.ivr.IvrExecutionSnapshot;
import com.shivang.obd.voice.ivr.IvrSnapshotCodec;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Typed configuration for a DTMF campaign that runs a reusable IVR tree
 * (VB-6F).
 *
 * <h2>Two shapes, one campaign type</h2>
 *
 * <p>{@code CampaignType.DTMF} now has two valid configurations:
 * <ul>
 *   <li><b>single-level</b> — {@code {"dtmf":{expected,…}}}, the VB-2 contract,
 *       unchanged and still fully supported;</li>
 *   <li><b>IVR</b> — {@code {"ivr":{treeId,…}}}, this type.</li>
 * </ul>
 *
 * <p>A campaign carries at most one. That is a deliberate choice over adding a
 * new {@code CampaignType}: a reusable IVR tree <em>is</em> a DTMF interaction,
 * the runtime is the same, and a new type would fork the dial, compliance,
 * routing, snapshot and retry paths for no behavioural difference. The brief
 * also asks the campaign to reference a tree rather than embed a flow, which is
 * exactly a new key in the existing type config.
 *
 * <h2>What a campaign stores, and what it does not</h2>
 *
 * <p>The campaign stores the tree <em>reference</em> and nothing else. The tree's
 * nodes, prompts, timing, retries and terminal actions live in
 * {@code ivr_nodes} / {@code ivr_transitions}, so two campaigns share one flow and
 * one edit updates both. Duplicating any of it here would create two sources of
 * truth.
 *
 * <p>The frozen {@code "ivr" execution} entry is written into the
 * <em>execution snapshot's</em> type config at execution-creation time, by
 * {@code IvrSnapshotCapture}. That is the only place the flow is materialised,
 * and it is what a running call reads.
 */
public record IvrCampaignConfig(UUID treeId,
                                ConfigSchemaVersion schemaVersion,
                                IvrExecutionSnapshot snapshot)
        implements CampaignTypeConfig {

    public IvrCampaignConfig {
        if (treeId == null) {
            throw new IllegalArgumentException("An IVR campaign configuration needs a treeId");
        }
        if (schemaVersion == null) {
            throw new IllegalArgumentException("schemaVersion is required");
        }
    }

    /**
     * The campaign-side shape: a reference to a tree, with nothing frozen yet.
     * <p>
     * This is what a create or update request stores. The flow itself is not
     * duplicated into the campaign — that is the entire point of a reusable tree.
     */
    public static IvrCampaignConfig referencing(UUID treeId) {
        return new IvrCampaignConfig(treeId, ConfigSchemaVersion.V1, null);
    }

    @Override
    public CampaignType campaignType() {
        return CampaignType.DTMF;
    }

    @Override
    public JsonNode toJson() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode ivr = JsonNodeFactory.instance.objectNode();
        ivr.put("treeId", treeId.toString());
        if (snapshot != null) {
            // The frozen flow. Written only into an execution snapshot, never
            // into a campaign row.
            ObjectNode frozen = IvrSnapshotCodec.toJson(snapshot);
            frozen.properties().forEach(entry -> ivr.set(entry.getKey(), entry.getValue()));
        }
        root.set(IvrSnapshotCodec.KEY, ivr);
        return root;
    }

    /**
     * Reads the campaign's IVR reference, if it has one.
     *
     * <p>Strict: a present but malformed {@code "ivr"} entry throws, so a
     * campaign can never be dialled against a tree reference nobody can parse.
     * An absent entry is simply not an IVR campaign.
     */
    public static Optional<IvrCampaignConfig> fromTypeConfig(JsonNode typeConfig) {
        if (typeConfig == null || !typeConfig.isObject()) {
            return Optional.empty();
        }
        JsonNode ivr = typeConfig.get(IvrSnapshotCodec.KEY);
        if (ivr == null || ivr.isNull()) {
            return Optional.empty();
        }
        JsonNode treeIdNode = ivr.get("treeId");
        if (treeIdNode == null || !treeIdNode.isTextual() || treeIdNode.asText().isBlank()) {
            throw new CampaignConfigInvalidException(
                    "type_config.ivr.treeId is required when a campaign references an IVR tree");
        }
        try {
            UUID treeId = UUID.fromString(treeIdNode.asText());
            // An entry that also carries nodes is a FROZEN execution snapshot;
            // a bare reference is the campaign-side shape. The same parser reads
            // both, so a campaign row and an execution snapshot can never be
            // confused for one another.
            IvrExecutionSnapshot frozen = ivr.has("nodes")
                    ? IvrSnapshotCodec.fromJson(ivr)
                    : null;
            return Optional.of(
                    new IvrCampaignConfig(treeId, ConfigSchemaVersion.V1, frozen));
        } catch (IllegalArgumentException e) {
            throw new CampaignConfigInvalidException(
                    "type_config.ivr.treeId is not a valid identifier: "
                            + treeIdNode.asText());
        } catch (IvrSnapshotCodec.IvrSnapshotInvalidException e) {
            throw new CampaignConfigInvalidException(
                    "type_config.ivr is not a readable execution snapshot: " + e.getMessage());
        }
    }

    /** Whether a type config selects the IVR path rather than single-level DTMF. */
    public static boolean selectsIvr(JsonNode typeConfig) {
        return fromTypeConfig(typeConfig).isPresent();
    }
}
