package com.shivang.obd.campaign.event;

import com.shivang.obd.campaign.CampaignEntity;
import com.shivang.obd.campaign.CampaignStatus;
import com.shivang.obd.campaign.CampaignType;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable campaign lifecycle event. Published through the synchronous
 * ApplicationEventPublisher seam; a future outbox/Kafka transport can
 * subscribe without changing the CRUD contract.
 */
public record CampaignDomainEvent(
    UUID campaignId,
    UUID tenantId,
    CampaignType campaignType,
    CampaignStatus status,
    String eventType,
    Instant occurredAt
) {

    public static final String CREATED = "CAMPAIGN_CREATED";
    public static final String UPDATED = "CAMPAIGN_UPDATED";
    public static final String DELETED = "CAMPAIGN_DELETED";
    public static final String STATUS_CHANGED = "CAMPAIGN_STATUS_CHANGED";
    public static final String CLONED = "CAMPAIGN_CLONED";

    public static CampaignDomainEvent created(CampaignEntity campaign) {
        return of(campaign, CREATED);
    }

    public static CampaignDomainEvent updated(CampaignEntity campaign) {
        return of(campaign, UPDATED);
    }

    public static CampaignDomainEvent deleted(CampaignEntity campaign) {
        return of(campaign, DELETED);
    }

    /** Emitted after a successful manual lifecycle transition; status carries the new state. */
    public static CampaignDomainEvent statusChanged(CampaignEntity campaign) {
        return of(campaign, STATUS_CHANGED);
    }

    /** Emitted for the fresh DRAFT lineage successor; carries source lineage via its fields. */
    public static CampaignDomainEvent cloned(CampaignEntity clone) {
        return of(clone, CLONED);
    }

    private static CampaignDomainEvent of(CampaignEntity campaign, String eventType) {
        return new CampaignDomainEvent(
            campaign.getId(),
            campaign.getTenantId(),
            campaign.getCampaignType(),
            campaign.getStatus(),
            eventType,
            Instant.now());
    }
}
