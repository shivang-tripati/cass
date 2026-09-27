package com.shivang.obd.campaign.dto;

import java.util.UUID;

/**
 * A single readiness check result.
 */
public record CampaignReadinessReason(
    String code,
    String message
) {
}