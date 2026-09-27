package com.shivang.obd.campaign.config;

/**
 * Deterministic failure for structurally invalid campaign type
 * configuration (VB-6A). Thrown by {@link CampaignTypeConfig} parsers and
 * surfaced through the existing validation error architecture — never
 * silently degraded.
 */
public class CampaignConfigInvalidException extends RuntimeException {

    public CampaignConfigInvalidException(String message) {
        super(message);
    }
}
