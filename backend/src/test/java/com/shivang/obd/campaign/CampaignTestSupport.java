package com.shivang.obd.campaign;

import com.shivang.obd.security.AuthenticatedUser;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Shared fixtures for campaign service/mapper unit tests. Follows the
 * plain JUnit + Mockito house style used by the authz suite.
 */
final class CampaignTestSupport {

    static final UUID USER_ID = UUID.fromString("cc000000-0000-4000-8000-000000000001");
    static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    static final UUID SOURCE_ID = UUID.fromString("51000000-0000-4000-8000-000000000001");

    private CampaignTestSupport() {
    }

    static AuthenticatedUser authenticatedUser() {
        return new AuthenticatedUser(USER_ID, "admin@test.local", null);
    }

    /** Minimal valid PLAYFILE/AUDIO campaign in the given lifecycle state. */
    static CampaignEntity campaign(CampaignStatus status) {
        var campaign = new CampaignEntity();
        campaign.setId(UUID.randomUUID());
        campaign.setTenantId(TENANT_A);
        campaign.setName("Test campaign");
        campaign.setCampaignType(CampaignType.PLAYFILE);
        campaign.setStatus(status);
        campaign.setContentMode(ContentMode.AUDIO);
        campaign.setAudioAssetId(UUID.randomUUID());
        return campaign;
    }

    static void withSchedule(CampaignEntity campaign) {
        campaign.setSchedule(new ScheduleSpec(
            LocalDate.of(2026, 9, 1),
            LocalTime.of(10, 0), LocalTime.of(18, 0),
            "Asia/Kolkata", Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), null));
    }

    static ScheduleSpec scheduleSpec(LocalDate start, String timezone) {
        return new ScheduleSpec(start, LocalTime.of(10, 0), LocalTime.of(18, 0),
            timezone, new LinkedHashSet<>(Set.of(DayOfWeek.WEDNESDAY)), null);
    }
}
