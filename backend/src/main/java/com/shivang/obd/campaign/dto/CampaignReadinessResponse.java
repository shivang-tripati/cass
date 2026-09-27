package com.shivang.obd.campaign.dto;

import java.util.List;
import java.util.UUID;

/**
 * Campaign execution readiness response.
 */
public record CampaignReadinessResponse(
    UUID campaignId,
    boolean ready,
    List<CampaignReadinessReason> reasons
) {
}