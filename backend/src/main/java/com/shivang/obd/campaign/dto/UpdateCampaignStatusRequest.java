package com.shivang.obd.campaign.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Target lifecycle state for the dedicated status transition operation.
 * Legality of the transition (and whether it may be performed manually
 * at all) is validated in the service layer.
 */
public record UpdateCampaignStatusRequest(
    @NotBlank String status
) {
}
