package com.shivang.obd.campaign.config;

/**
 * Schema version of a typed {@link CampaignTypeConfig} payload (VB-6A).
 * <p>
 * Distinct from the campaign configuration version (per-campaign snapshot
 * counter): this versions the <em>shape</em> of the typeConfig JSON itself
 * so future evolution can be rejected or upgraded deterministically instead
 * of silently reinterpreted. Persisted configurations without an explicit
 * marker are version {@link #V1} — the shape the codebase has always used.
 */
public enum ConfigSchemaVersion {

    /** The initial (and current) typeConfig JSON shape. */
    V1
}
