package com.shivang.obd.campaign;

import static com.shivang.obd.campaign.CampaignTestSupport.SOURCE_ID;
import static com.shivang.obd.campaign.CampaignTestSupport.TENANT_A;
import static com.shivang.obd.campaign.CampaignTestSupport.USER_ID;
import static com.shivang.obd.campaign.CampaignTestSupport.authenticatedUser;
import static com.shivang.obd.campaign.CampaignTestSupport.campaign;
import static com.shivang.obd.campaign.CampaignTestSupport.withSchedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.campaign.event.CampaignDomainEvent;
import com.shivang.obd.campaign.event.CampaignEventPublisher;
import com.shivang.obd.common.exception.ResourceNotFoundException;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantRepository;
import java.time.DayOfWeek;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Clone semantics: fresh DRAFT successor, version+1 lineage pointer,
 * deep-copied configuration, tenant preservation, IDOR-safe scoping.
 */
class CampaignCloneServiceTest {

    private CampaignRepository repository;
    private AuthorizationService authorizationService;
    private CampaignEventPublisher eventPublisher;
    private com.shivang.obd.contact.ContactGroupRepository contactGroupRepository;
    private com.shivang.obd.did.DidRepository didRepository;
    private CampaignService service;

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(CampaignRepository.class);
        authorizationService = org.mockito.Mockito.mock(AuthorizationService.class);
        eventPublisher = org.mockito.Mockito.mock(CampaignEventPublisher.class);
        contactGroupRepository = org.mockito.Mockito.mock(com.shivang.obd.contact.ContactGroupRepository.class);
        didRepository = org.mockito.Mockito.mock(com.shivang.obd.did.DidRepository.class);
        org.mockito.Mockito.when(contactGroupRepository.existsByIdAndTenantIdAndDeletedAtIsNull(
                any(UUID.class), any(UUID.class))).thenReturn(true);
        org.mockito.Mockito.when(didRepository.existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                any(UUID.class), any(UUID.class), any(com.shivang.obd.did.DidStatus.class), any(com.shivang.obd.did.AllocationState.class)))
            .thenReturn(true);
        var currentUserProvider = org.mockito.Mockito.mock(CurrentUserProvider.class);
        when(currentUserProvider.current()).thenReturn(Optional.of(authenticatedUser()));
        service = new CampaignService(repository, authorizationService, eventPublisher,
            currentUserProvider, new CampaignMapper(),
            org.mockito.Mockito.mock(TenantRepository.class), contactGroupRepository,
            new com.shivang.obd.campaign.CampaignResourceValidationService(
                didRepository,
                org.mockito.Mockito.mock(com.shivang.obd.audio.AudioAssetRepository.class),
                org.mockito.Mockito.mock(com.shivang.obd.tts.TtsTemplateRepository.class)),
            new CampaignLifecyclePolicy());
        OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_A, null);
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clear();
    }

    @Test
    void cloneResetsLifecycleAdvancesVersionAndCopiesConfiguration() {
        var source = campaign(CampaignStatus.RUNNING);
        source.setId(SOURCE_ID);
        source.setVersion(3);
        withSchedule(source);

        when(repository.findByIdAndTenantIdAndDeletedAtIsNull(SOURCE_ID, TENANT_A))
            .thenReturn(Optional.of(source));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.clone(SOURCE_ID);

        assertThat(response.data().status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(response.data().version()).isEqualTo(4);
        assertThat(response.data().clonedFromCampaignId()).isEqualTo(SOURCE_ID);

        var saved = capturedClone();
        assertThat(saved.getTenantId()).isEqualTo(TENANT_A);
        assertThat(saved.getCampaignType()).isEqualTo(CampaignType.PLAYFILE);
        assertThat(saved.getRunMode()).isEqualTo(CampaignRunMode.ONE_TIME);
        assertThat(saved.getSchedule()).isNotSameAs(source.getSchedule());
        assertThat(saved.getSchedule().getAllowedDaysOfWeek())
            .containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        verify(authorizationService).requireCapability(
            eq(USER_ID), eq("CAMPAIGN_MANAGE"), any(AccessCheck.class));

        var captor = ArgumentCaptor.forClass(CampaignDomainEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(CampaignDomainEvent.CLONED);
    }

    @Test
    void cloneDeepCopiesTheEligibilityWindow() {
        var source = campaign(CampaignStatus.DRAFT);
        source.setId(SOURCE_ID);
        source.setVersion(1);
        withSchedule(source);
        when(repository.findByIdAndTenantIdAndDeletedAtIsNull(SOURCE_ID, TENANT_A))
            .thenReturn(Optional.of(source));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.clone(SOURCE_ID);
        var saved = capturedClone();
        saved.getSchedule().getAllowedDaysOfWeek().add(DayOfWeek.SUNDAY);

        assertThat(source.getSchedule().getAllowedDaysOfWeek())
            .containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
    }

    @Test
    void foreignSourceIsIndistinguishableFromMissing() {
        when(repository.findByIdAndTenantIdAndDeletedAtIsNull(SOURCE_ID, TENANT_A))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.clone(SOURCE_ID))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    private CampaignEntity capturedClone() {
        var captor = ArgumentCaptor.forClass(CampaignEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
