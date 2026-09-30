package com.shivang.obd.campaign;

import static com.shivang.obd.campaign.CampaignTestSupport.TENANT_A;
import static com.shivang.obd.campaign.CampaignTestSupport.USER_ID;
import static com.shivang.obd.campaign.CampaignTestSupport.authenticatedUser;
import static com.shivang.obd.campaign.CampaignTestSupport.campaign;
import static com.shivang.obd.campaign.CampaignTestSupport.withSchedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.authz.context.OrganizationContextHolder;
import com.shivang.obd.campaign.dto.UpdateCampaignStatusRequest;
import com.shivang.obd.campaign.ContentMode;
import com.shivang.obd.campaign.event.CampaignDomainEvent;
import com.shivang.obd.campaign.event.CampaignEventPublisher;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.common.exception.ForbiddenException;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Lifecycle transition rules: legal edges succeed, illegal edges and edges
 * targeting a reserved execution-fact are rejected, activation gates on
 * configuration.
 */
class CampaignLifecycleServiceTest {

    private CampaignRepository repository;
    private AuthorizationService authorizationService;
    private CampaignEventPublisher eventPublisher;
    private com.shivang.obd.contact.ContactGroupRepository contactGroupRepository;
    private com.shivang.obd.audio.AudioAssetRepository audioAssetRepository;
    private com.shivang.obd.tts.TtsTemplateRepository ttsTemplateRepository;
    private com.shivang.obd.did.DidRepository didRepository;
    private CampaignService service;

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(CampaignRepository.class);
        authorizationService = org.mockito.Mockito.mock(AuthorizationService.class);
        eventPublisher = org.mockito.Mockito.mock(CampaignEventPublisher.class);
        contactGroupRepository = org.mockito.Mockito.mock(com.shivang.obd.contact.ContactGroupRepository.class);
        audioAssetRepository = org.mockito.Mockito.mock(com.shivang.obd.audio.AudioAssetRepository.class);
        ttsTemplateRepository = org.mockito.Mockito.mock(com.shivang.obd.tts.TtsTemplateRepository.class);
        didRepository = org.mockito.Mockito.mock(com.shivang.obd.did.DidRepository.class);
        org.mockito.Mockito.when(contactGroupRepository.existsByIdAndTenantIdAndDeletedAtIsNull(
                any(UUID.class), any(UUID.class))).thenReturn(true);
        org.mockito.Mockito.when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                any(UUID.class), any(UUID.class)))
            .thenReturn(java.util.Optional.of(approvedAssetWithStorage()));
        org.mockito.Mockito.when(ttsTemplateRepository.existsUsableForTenant(
                any(UUID.class), any(UUID.class)))
            .thenReturn(true);
        org.mockito.Mockito.when(didRepository.existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                any(UUID.class), any(UUID.class), any(com.shivang.obd.did.DidStatus.class), any(com.shivang.obd.did.AllocationState.class)))
            .thenReturn(true);
        var currentUserProvider = org.mockito.Mockito.mock(CurrentUserProvider.class);
        when(currentUserProvider.current()).thenReturn(Optional.of(authenticatedUser()));
        service = new CampaignService(repository, authorizationService, eventPublisher,
            currentUserProvider, new CampaignMapper(),
            org.mockito.Mockito.mock(TenantRepository.class),
            contactGroupRepository,
            new com.shivang.obd.campaign.CampaignResourceValidationService(
                didRepository, audioAssetRepository, ttsTemplateRepository),
            new CampaignLifecyclePolicy());
        OrganizationContextHolder.setAuthenticated(USER_ID, TENANT_A, null);
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clear();
    }

    /** Default healthy asset used by the canonical validator stub. */
    private static com.shivang.obd.audio.AudioAssetEntity approvedAssetWithStorage() {
        var asset = new com.shivang.obd.audio.AudioAssetEntity();
        asset.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
        asset.setStorageReference("tenants/tenant-a/promo.wav");
        return asset;
    }

    @Nested
    class LegalTransitions {

        @Test
        void draftToScheduledSucceedsAndGatesOnConfiguration() {
            var draft = campaign(CampaignStatus.DRAFT);
            withSchedule(draft);
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(draft.getId(), TENANT_A))
                .thenReturn(Optional.of(draft));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.changeStatus(
                draft.getId(), new UpdateCampaignStatusRequest("SCHEDULED"));

            assertThat(response.data().status()).isEqualTo(CampaignStatus.SCHEDULED);
            verify(authorizationService).requireCapability(
                eq(USER_ID), eq("CAMPAIGN_EXECUTE"), any(AccessCheck.class));
            var event = capturedEvent();
            assertThat(event.eventType()).isEqualTo(CampaignDomainEvent.STATUS_CHANGED);
            assertThat(event.status()).isEqualTo(CampaignStatus.SCHEDULED);
        }

        @Test
        void pausedToRunningIsRefusedBecauseRunningIsReserved() {
            // VB-8J: this asserted PAUSED -> RUNNING was a legal manual edge, which
            // was the VB-8H position. The decided model makes RUNNING a reserved
            // execution-fact with no producer, so the edge is legal in the table
            // but refused with the reason that matters.
            var paused = campaign(CampaignStatus.PAUSED);
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(paused.getId(), TENANT_A))
                .thenReturn(Optional.of(paused));

            assertThatThrownBy(() -> service.changeStatus(
                paused.getId(), new UpdateCampaignStatusRequest("RUNNING")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("reserved");

            assertThat(paused.getStatus()).isEqualTo(CampaignStatus.PAUSED);
            verify(repository, never()).save(any());
        }

        @Test
        void scheduledCanRevertToDraft() {
            var scheduled = campaign(CampaignStatus.SCHEDULED);
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(scheduled.getId(), TENANT_A))
                .thenReturn(Optional.of(scheduled));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.changeStatus(scheduled.getId(), new UpdateCampaignStatusRequest("DRAFT"));

            assertThat(scheduled.getStatus()).isEqualTo(CampaignStatus.DRAFT);
        }
    }

    @Nested
    class IllegalTransitions {

        @Test
        void draftCannotJumpToCompleted() {
            var draft = campaign(CampaignStatus.DRAFT);
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(draft.getId(), TENANT_A))
                .thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> service.changeStatus(
                draft.getId(), new UpdateCampaignStatusRequest("COMPLETED")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("DRAFT");
            verify(repository, never()).save(any());
        }

        @Test
        void archivedIsTerminal() {
            var archived = campaign(CampaignStatus.ARCHIVED);
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(archived.getId(), TENANT_A))
                .thenReturn(Optional.of(archived));

            assertThatThrownBy(() -> service.changeStatus(
                archived.getId(), new UpdateCampaignStatusRequest("SCHEDULED")))
                .isInstanceOf(ConflictException.class);
            verify(repository, never()).save(any());
        }

        @Test
        void engineDrivenEdgesAreRejectedOnTheManualApi() {
            // VB-8J supersedes the VB-8H position in both directions. VB-8H
            // removed the rejection and made RUNNING operator-settable, on the
            // reasoning that campaign status is control state. VB-8J keeps that
            // reasoning but draws the consequence properly: RUNNING describes
            // execution progress, so it is reserved rather than clickable. The
            // old message ("performed by the execution engine") was false and no
            // engine path exists; the new one says what is actually true.
            var scheduled = campaign(CampaignStatus.SCHEDULED);
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(scheduled.getId(), TENANT_A))
                .thenReturn(Optional.of(scheduled));

            assertThatThrownBy(() -> service.changeStatus(
                scheduled.getId(), new UpdateCampaignStatusRequest("RUNNING")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("reserved")
                .hasMessageContaining("executions");

            assertThat(scheduled.getStatus()).isEqualTo(CampaignStatus.SCHEDULED);
            verify(repository, never()).save(any());
        }

        @Test
        void runningTerminalEdgesAreAlsoReserved() {
            // RUNNING -> COMPLETED / FAILED are legal in the table but refused,
            // for the same reason. Nothing produces them, and a campaign must not
            // be able to declare itself finished or failed by hand.
            for (var target : new CampaignStatus[] {
                    CampaignStatus.COMPLETED, CampaignStatus.FAILED}) {
                var running = campaign(CampaignStatus.RUNNING);
                when(repository.findByIdAndTenantIdAndDeletedAtIsNull(running.getId(), TENANT_A))
                    .thenReturn(Optional.of(running));

                assertThatThrownBy(() -> service.changeStatus(
                    running.getId(), new UpdateCampaignStatusRequest(target.name())))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("reserved");

                assertThat(running.getStatus()).isEqualTo(CampaignStatus.RUNNING);
            }
        }

        @Test
        void runningCannotBeResurrectedFromCompleted() {
            var completed = campaign(CampaignStatus.COMPLETED);
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(completed.getId(), TENANT_A))
                .thenReturn(Optional.of(completed));

            assertThatThrownBy(() -> service.changeStatus(
                completed.getId(), new UpdateCampaignStatusRequest("RUNNING")))
                .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    class Guards {

        @Test
        void activationRequiresConfiguredSchedule() {
            CampaignEntity draft = campaign(CampaignStatus.DRAFT);
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(draft.getId(), TENANT_A))
                .thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> service.changeStatus(
                draft.getId(), new UpdateCampaignStatusRequest("SCHEDULED")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("schedule");
            verify(repository, never()).save(any());
        }

        @Test
        void activationRejectsForeignMissingOrDeletedContactGroupWithoutMutatingState() {
            CampaignEntity draft = campaign(CampaignStatus.DRAFT);
            withSchedule(draft);
            draft.setContactGroupId(UUID.randomUUID());
            when(contactGroupRepository.existsByIdAndTenantIdAndDeletedAtIsNull(
                    draft.getContactGroupId(), TENANT_A)).thenReturn(false);
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(draft.getId(), TENANT_A))
                .thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> service.changeStatus(
                draft.getId(), new UpdateCampaignStatusRequest("SCHEDULED")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Contact group");
            assertThat(draft.getStatus()).isEqualTo(CampaignStatus.DRAFT);
            verify(repository, never()).save(any());
        }

        @Test
        void activationAcceptsSameTenantLiveContactGroup() {
            CampaignEntity draft = campaign(CampaignStatus.DRAFT);
            withSchedule(draft);
            UUID groupId = UUID.randomUUID();
            draft.setContactGroupId(groupId);
            when(contactGroupRepository.existsByIdAndTenantIdAndDeletedAtIsNull(groupId, TENANT_A))
                .thenReturn(true);
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(draft.getId(), TENANT_A))
                .thenReturn(Optional.of(draft));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.changeStatus(
                draft.getId(), new UpdateCampaignStatusRequest("SCHEDULED"));

            assertThat(response.data().status()).isEqualTo(CampaignStatus.SCHEDULED);
        }

        @Test
        void activationBlocksUnapprovedAudioAsset() {
            CampaignEntity draft = campaign(CampaignStatus.DRAFT);
            withSchedule(draft);
            draft.setContentMode(ContentMode.AUDIO);
            draft.setAudioAssetId(UUID.randomUUID());
            when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                    draft.getAudioAssetId(), TENANT_A))
                .thenReturn(Optional.empty());
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(draft.getId(), TENANT_A))
                .thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> service.changeStatus(
                draft.getId(), new UpdateCampaignStatusRequest("SCHEDULED")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not approved");
            assertThat(draft.getStatus()).isEqualTo(CampaignStatus.DRAFT);
            verify(repository, never()).save(any());
        }

        @Test
        void activationAllowsApprovedAudioAsset() {
            CampaignEntity draft = campaign(CampaignStatus.DRAFT);
            withSchedule(draft);
            draft.setContentMode(ContentMode.AUDIO);
            draft.setAudioAssetId(UUID.randomUUID());
            var approvedAsset = new com.shivang.obd.audio.AudioAssetEntity();
            approvedAsset.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
            approvedAsset.setStorageReference("tenants/tenant-a/promo.wav");
            when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                    draft.getAudioAssetId(), TENANT_A))
                .thenReturn(Optional.of(approvedAsset));
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(draft.getId(), TENANT_A))
                .thenReturn(Optional.of(draft));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.changeStatus(
                draft.getId(), new UpdateCampaignStatusRequest("SCHEDULED"));

            assertThat(response.data().status()).isEqualTo(CampaignStatus.SCHEDULED);
        }

        @Test
        void unknownStatusValueIsAValidationError() {
            var draft = campaign(CampaignStatus.DRAFT);

            assertThatThrownBy(() -> service.changeStatus(
                draft.getId(), new UpdateCampaignStatusRequest("BROKEN")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown status");
        }

        @Test
        void missingCapabilityFailsClosed() {
            var draft = campaign(CampaignStatus.DRAFT);
            when(repository.findByIdAndTenantIdAndDeletedAtIsNull(draft.getId(), TENANT_A))
                .thenReturn(Optional.of(draft));
            doThrow(new ForbiddenException())
                .when(authorizationService)
                .requireCapability(eq(USER_ID), eq("CAMPAIGN_EXECUTE"), any(AccessCheck.class));

            assertThatThrownBy(() -> service.changeStatus(
                draft.getId(), new UpdateCampaignStatusRequest("SCHEDULED")))
                .isInstanceOf(ForbiddenException.class);
            verify(repository, never()).save(any());
        }
    }

    private CampaignDomainEvent capturedEvent() {
        var captor = ArgumentCaptor.forClass(CampaignDomainEvent.class);
        verify(eventPublisher).publish(captor.capture());
        return captor.getValue();
    }
}
