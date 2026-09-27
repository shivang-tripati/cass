package com.shivang.obd.campaign.event;

import com.shivang.obd.campaign.event.CampaignDomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Lightweight domain-event seam. Publishes events via Spring's
 * ApplicationEventPublisher; a future Kafka/outbox implementation can
 * subscribe without changing the synchronous CRUD path.
 */
@Component
public class CampaignEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CampaignEventPublisher.class);

    private final ApplicationEventPublisher publisher;

    public CampaignEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(CampaignDomainEvent event) {
        log.debug("Publishing campaign event [type={} campaignId={}]", event.eventType(), event.campaignId());
        publisher.publishEvent(event);
    }
}
