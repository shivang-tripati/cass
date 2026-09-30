package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shivang.obd.campaign.config.CampaignConfigInvalidException;
import com.shivang.obd.campaign.config.CampaignTypeConfig;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * VB-6A snapshot payload mapping tests: the snapshot carries every
 * execution-affecting field and excludes administrative metadata;
 * invalid typeConfig fails snapshot materialization deterministically.
 */
class CampaignConfigurationSnapshotTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("Snapshot maps all execution-affecting fields from the live campaign")
    void mapsAllExecutionAffectingFields() {
        CampaignEntity campaign = new CampaignEntity();
        campaign.setId(UUID.randomUUID());
        campaign.setTenantId(UUID.randomUUID());
        campaign.setCampaignType(CampaignType.DTMF);
        campaign.setContactGroupId(UUID.randomUUID());
        campaign.setDidId(UUID.randomUUID());
        campaign.setContentMode(ContentMode.AUDIO);
        campaign.setAudioAssetId(UUID.randomUUID());
        campaign.setSchedule(new ScheduleSpec(
                LocalDate.of(2026, 10, 1),
                LocalTime.of(9, 0), LocalTime.of(17, 0),
                "Asia/Kolkata", Set.of(java.time.DayOfWeek.MONDAY), null));
        campaign.setRetryPolicy(new RetryPolicySpec(3, 120, RetryStrategy.FIXED));
        campaign.setTypeConfig(MAPPER.readTree("{\"dtmf\": {\"expected\": \"9\"}}"));
        campaign.setCallOnWhitelistNumbers(true);

        CampaignTypeConfig parsed = CampaignTypeConfig.fromTypeConfig(
                CampaignType.DTMF, campaign.getTypeConfig());
        CampaignConfigurationSnapshot snapshot =
                CampaignConfigurationService.toSnapshot(campaign, parsed);

        assertThat(snapshot.getCampaignType()).isEqualTo(CampaignType.DTMF);
        assertThat(snapshot.getContactGroupId()).isEqualTo(campaign.getContactGroupId());
        assertThat(snapshot.getDidId()).isEqualTo(campaign.getDidId());
        assertThat(snapshot.getContentMode()).isEqualTo(ContentMode.AUDIO);
        assertThat(snapshot.getAudioAssetId()).isEqualTo(campaign.getAudioAssetId());
        assertThat(snapshot.getScheduleStartDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(snapshot.getDailyEndTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(snapshot.getTimezone()).isEqualTo("Asia/Kolkata");
        assertThat(snapshot.getAllowedDaysOfWeek()).containsExactly(java.time.DayOfWeek.MONDAY);
        assertThat(snapshot.getRetryMaxAttempts()).isEqualTo(3);
        assertThat(snapshot.getRetryIntervalSeconds()).isEqualTo(120);
        assertThat(snapshot.getRetryStrategy()).isEqualTo(RetryStrategy.FIXED);
        assertThat(snapshot.getCallOnWhitelistNumbers()).isTrue();
        assertThat(snapshot.getTypeConfig().get("dtmf").get("expected").asText()).isEqualTo("9");

        // Spec views round-trip for the runtime consumers.
        assertThat(snapshot.scheduleSpec().getTimezone()).isEqualTo("Asia/Kolkata");
        assertThat(snapshot.retryPolicySpec().getMaxAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("Null schedule and null retry policy normalize like the live defaults")
    void normalizesAbsentPolicy() {
        CampaignEntity campaign = new CampaignEntity();
        campaign.setCampaignType(CampaignType.PLAYFILE);

        CampaignConfigurationSnapshot snapshot = CampaignConfigurationService.toSnapshot(
                campaign, CampaignTypeConfig.fromTypeConfig(CampaignType.PLAYFILE, null));

        assertThat(snapshot.getScheduleStartDate()).isNull();
        assertThat(snapshot.scheduleSpec()).isNull();
        assertThat(snapshot.getRetryMaxAttempts()).isZero();
        assertThat(snapshot.getRetryStrategy()).isEqualTo(RetryStrategy.FIXED);
        assertThat(snapshot.getCallOnWhitelistNumbers()).isFalse();
    }

    @Test
    @DisplayName("Invalid typeConfig fails snapshot materialization deterministically")
    void invalidTypeConfigFailsMaterialization() {
        CampaignEntity campaign = new CampaignEntity();
        campaign.setCampaignType(CampaignType.DTMF);
        campaign.setTypeConfig(MAPPER.readTree("{\"unexpected\": true}"));

        assertThatThrownBy(() -> CampaignTypeConfig.fromTypeConfig(
                CampaignType.DTMF, campaign.getTypeConfig()))
                .isInstanceOf(CampaignConfigInvalidException.class);
    }
}
