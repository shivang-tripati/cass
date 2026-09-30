package com.shivang.obd.campaign;

import static com.shivang.obd.campaign.CampaignTestSupport.TENANT_A;
import static com.shivang.obd.campaign.CampaignTestSupport.USER_ID;
import static com.shivang.obd.campaign.CampaignTestSupport.authenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.campaign.dto.CreateCampaignRequest;
import com.shivang.obd.campaign.dto.RetryPolicyConfig;
import com.shivang.obd.campaign.dto.ScheduleConfig;
import com.shivang.obd.campaign.event.CampaignEventPublisher;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Run-mode and schedule business rules enforced at the service boundary,
 * plus lineage defaults applied by the mapper.
 */
class CampaignValidationServiceTest {

    private CampaignRepository repository;
    private TenantRepository tenantRepository;
    private com.shivang.obd.contact.ContactGroupRepository contactGroupRepository;
    private com.shivang.obd.did.DidRepository didRepository;
    private CampaignService service;

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(CampaignRepository.class);
        tenantRepository = org.mockito.Mockito.mock(TenantRepository.class);
        contactGroupRepository = org.mockito.Mockito.mock(com.shivang.obd.contact.ContactGroupRepository.class);
        didRepository = org.mockito.Mockito.mock(com.shivang.obd.did.DidRepository.class);
        org.mockito.Mockito.when(contactGroupRepository.existsByIdAndTenantIdAndDeletedAtIsNull(
                any(UUID.class), any(UUID.class))).thenReturn(true);
        com.shivang.obd.audio.AudioAssetRepository audioAssetRepository =
            org.mockito.Mockito.mock(com.shivang.obd.audio.AudioAssetRepository.class);
        var defaultApprovedAsset = new com.shivang.obd.audio.AudioAssetEntity();
        defaultApprovedAsset.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
        defaultApprovedAsset.setStorageReference("tenants/tenant-a/promo.wav");
        org.mockito.Mockito.when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                any(UUID.class), any(UUID.class)))
            .thenReturn(java.util.Optional.of(defaultApprovedAsset));
        com.shivang.obd.tts.TtsTemplateRepository ttsTemplateRepository =
            org.mockito.Mockito.mock(com.shivang.obd.tts.TtsTemplateRepository.class);
        org.mockito.Mockito.when(ttsTemplateRepository.existsUsableForTenant(
                any(UUID.class), any(UUID.class)))
            .thenReturn(true);
        org.mockito.Mockito.when(didRepository.existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                any(UUID.class), any(UUID.class), any(com.shivang.obd.did.DidStatus.class), any(com.shivang.obd.did.AllocationState.class)))
            .thenReturn(true);
        var authorizationService = org.mockito.Mockito.mock(AuthorizationService.class);
        var eventPublisher = org.mockito.Mockito.mock(com.shivang.obd.campaign.event.CampaignEventPublisher.class);
        var currentUserProvider = org.mockito.Mockito.mock(CurrentUserProvider.class);
        when(currentUserProvider.current()).thenReturn(Optional.of(authenticatedUser()));
        when(tenantRepository.findByIdAndDeletedAtIsNull(any()))
            .thenReturn(Optional.of(new TenantEntity()));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new CampaignService(repository, authorizationService, eventPublisher,
            currentUserProvider, new CampaignMapper(), tenantRepository, contactGroupRepository,
            new com.shivang.obd.campaign.CampaignResourceValidationService(
                didRepository, audioAssetRepository, ttsTemplateRepository),
            new CampaignLifecyclePolicy());
        OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_A, null);
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clear();
    }

    @Test
    void createRejectsForeignMissingOrDeletedContactGroup() {
        CreateCampaignRequest request = new CreateCampaignRequest(
            "With group", null, CampaignType.PLAYFILE, null,
            UUID.randomUUID(), null, // foreign/missing/deleted group id -> single non-leaking 400
            ContentMode.AUDIO, UUID.randomUUID(), null,
            schedule("Asia/Kolkata"),
            new RetryPolicyConfig(0, null, null),
            null, null, false, null);
        when(contactGroupRepository.existsByIdAndTenantIdAndDeletedAtIsNull(
                request.contactGroupId(), TENANT_A)).thenReturn(false);

        assertThatThrownBy(() -> service.create(request, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Contact group");
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void recurringCampaignsRequireASchedule() {
        var request = createRequest(CampaignRunMode.RECURRING, null);

        assertThatThrownBy(() -> service.create(request, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("RECURRING");
    }

    @Test
    void omittedRunModeDefaultsToOneTimeWithVersionOne() {
        var request = createRequest(null, schedule("Asia/Kolkata"));

        service.create(request, null);

        var saved = savedCampaign();
        assertThat(saved.getRunMode()).isEqualTo(CampaignRunMode.ONE_TIME);
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(saved.getClonedFromCampaignId()).isNull();
    }

    @Test
    void configuredWindowRequiresAnExplicitTimezone() {
        var request = createRequest(CampaignRunMode.ONE_TIME, schedule(null));

        assertThatThrownBy(() -> service.create(request, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Timezone");
    }

    @Test
    void invalidTimezoneIsRejected() {
        var request = createRequest(CampaignRunMode.ONE_TIME, schedule("Mars/Olympus"));

        assertThatThrownBy(() -> service.create(request, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("IANA");
    }

    @Test
    void allowedDaysAreNormalizedAndHolidayReferencePassesThrough() {
        var calendarId = UUID.randomUUID();
        var request = new CreateCampaignRequest(
            "Weekday blast", null, CampaignType.PLAYFILE, CampaignRunMode.RECURRING,
            null, null,
            ContentMode.AUDIO, UUID.randomUUID(), null,
            new ScheduleConfig(LocalDate.of(2026, 9, 1), null, null,
                "Asia/Kolkata", Set.of(), calendarId),
            new RetryPolicyConfig(0, null, null),
            null, null, false, null);

        service.create(request, null);

        var saved = savedCampaign();
        assertThat(saved.getSchedule().getAllowedDaysOfWeek()).isNull();
        assertThat(saved.getSchedule().getHolidayCalendarId()).isEqualTo(calendarId);
        verify(tenantRepository).findByIdAndDeletedAtIsNull(TENANT_A);
    }

    @Test
    void validRecurringCampaignWithWorkingDaysIsAccepted() {
        var request = createRequest(CampaignRunMode.RECURRING,
            new ScheduleConfig(LocalDate.of(2026, 9, 1),
                null, null, "Asia/Kolkata",
                Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                null));

        service.create(request, null);

        var saved = savedCampaign();
        assertThat(saved.getRunMode()).isEqualTo(CampaignRunMode.RECURRING);
        assertThat(saved.getSchedule().getAllowedDaysOfWeek()).hasSize(5);
    }

    private CreateCampaignRequest createRequest(CampaignRunMode runMode, ScheduleConfig schedule) {
        return new CreateCampaignRequest(
            "Test campaign", null, CampaignType.PLAYFILE, runMode,
            null, null,
            ContentMode.AUDIO, UUID.randomUUID(), null,
            schedule,
            new RetryPolicyConfig(0, null, null),
            null, null, false, null);
    }

    private ScheduleConfig schedule(String timezone) {
        return new ScheduleConfig(LocalDate.of(2026, 9, 1),
            null, null, timezone, Set.of(DayOfWeek.MONDAY), null);
    }

    private CampaignEntity savedCampaign() {
        var captor = ArgumentCaptor.forClass(CampaignEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
