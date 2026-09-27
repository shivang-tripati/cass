package com.shivang.obd.campaign;

import static com.shivang.obd.campaign.CampaignTestSupport.SOURCE_ID;
import static com.shivang.obd.campaign.CampaignTestSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;

import com.shivang.obd.campaign.dto.CampaignResponse;
import java.time.DayOfWeek;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Mapper contract: lineage defaults, response exposure, and clone
 * configuration copying.
 */
class CampaignMapperTest {

    private final CampaignMapper mapper = new CampaignMapper();

    @Test
    void newEntitiesCarryDocumentedDefaults() {
        var entity = mapper.toEntity(new com.shivang.obd.campaign.dto.CreateCampaignRequest(
            "Root", null, CampaignType.PLAYFILE, null, null, null,
            ContentMode.AUDIO, UUID.randomUUID(), null,
            null,
            new com.shivang.obd.campaign.dto.RetryPolicyConfig(0, null, null),
            null, null, false, null), TENANT_A);

        assertThat(entity.getStatus()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(entity.getRunMode()).isEqualTo(CampaignRunMode.ONE_TIME);
        assertThat(entity.getVersion()).isEqualTo(1);
        assertThat(entity.getClonedFromCampaignId()).isNull();
        // VB-6C.2: omitted dailyDialLimit stays null (platform default).
        assertThat(entity.getDailyDialLimit()).isNull();
    }

    @Test
    void responsesExposeLineageAndSchedulingFields() {
        var entity = new CampaignEntity();
        entity.setId(SOURCE_ID);
        entity.setTenantId(TENANT_A);
        entity.setName("Parent");
        entity.setCampaignType(CampaignType.PLAYFILE);
        entity.setStatus(CampaignStatus.RUNNING);
        entity.setRunMode(CampaignRunMode.RECURRING);
        entity.setVersion(2);
        entity.setClonedFromCampaignId(UUID.randomUUID());
        entity.setSchedule(CampaignTestSupport.scheduleSpec(null, null, "Asia/Kolkata"));

        CampaignResponse response = mapper.toResponse(entity);

        assertThat(response.runMode()).isEqualTo(CampaignRunMode.RECURRING);
        assertThat(response.status()).isEqualTo(CampaignStatus.RUNNING);
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.clonedFromCampaignId()).isNotNull();
        assertThat(response.schedule().allowedDaysOfWeek()).containsExactly(DayOfWeek.WEDNESDAY);
        assertThat(response.schedule().timezone()).isEqualTo("Asia/Kolkata");
    }

    @Test
    void cloneOfProducesAnIndependentDraftSuccessor() {
        var source = new CampaignEntity();
        source.setId(SOURCE_ID);
        source.setTenantId(TENANT_A);
        source.setName("Source");
        source.setDescription("desc");
        source.setCampaignType(CampaignType.DTMF);
        source.setRunMode(CampaignRunMode.RECURRING);
        source.setStatus(CampaignStatus.FAILED);
        source.setVersion(7);
        source.setRetryPolicy(new RetryPolicySpec(3, 300, RetryStrategy.FIXED));
        source.setSchedule(CampaignTestSupport.scheduleSpec(null, null, "Asia/Kolkata"));
        source.setDailyDialLimit(2); // VB-6C.2: configuration must clone

        var clone = mapper.cloneOf(source);

        assertThat(clone.getStatus()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(clone.getVersion()).isEqualTo(8);
        assertThat(clone.getClonedFromCampaignId()).isEqualTo(SOURCE_ID);
        assertThat(clone.getTenantId()).isEqualTo(TENANT_A);
        assertThat(clone.getCampaignType()).isEqualTo(CampaignType.DTMF);
        assertThat(clone.getRunMode()).isEqualTo(CampaignRunMode.RECURRING);
        assertThat(clone.getRetryPolicy().getMaxAttempts()).isEqualTo(3);
        assertThat(clone.getDailyDialLimit()).isEqualTo(2);
        assertThat(clone.getSchedule()).isNotSameAs(source.getSchedule());
        // Mutating the clone must never leak into the source.
        clone.getSchedule().getAllowedDaysOfWeek().add(DayOfWeek.SUNDAY);
        assertThat(source.getSchedule().getAllowedDaysOfWeek()).hasSize(1);
    }
}
