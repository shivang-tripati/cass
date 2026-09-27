package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AccessCheck;
import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.campaign.dto.UpdateCampaignRequest;
import com.shivang.obd.campaign.event.CampaignEventPublisher;
import com.shivang.obd.campaign.dto.CampaignResponse;
import com.shivang.obd.common.api.response.ApiResponse;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.common.exception.ConflictException;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.tenant.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * VB-6A correction — campaign editability rule: configuration mutation is
 * lifecycle-gated in one place ({@link CampaignLifecyclePolicy}); only
 * DRAFT is editable (SCHEDULED is the product's READY/executable state),
 * and unlock happens explicitly through the existing SCHEDULED → DRAFT
 * transition — no versioning, no cloning.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CampaignEditabilityTest {

    @Mock
    private CampaignRepository repository;
    @Mock
    private AuthorizationService authorizationService;
    @Mock
    private CampaignEventPublisher eventPublisher;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private com.shivang.obd.contact.ContactGroupRepository contactGroupRepository;
    @Mock
    private com.shivang.obd.audio.AudioAssetRepository audioAssetRepository;

    private CampaignService service;
    private CampaignLifecyclePolicy policy;

    private static final UUID USER_ID = UUID.fromString("b1000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_A = UUID.fromString("b1000000-0000-4000-8000-0000000000a1");
    private static final UUID CAMPAIGN_ID = UUID.fromString("b1000000-0000-4000-8000-0000000000c1");
    private static final UUID ASSET_ID = UUID.fromString("b1000000-0000-4000-8000-0000000000f1");

    @BeforeEach
    void setUp() {
        policy = new CampaignLifecyclePolicy();
        service = new CampaignService(
            repository, authorizationService, eventPublisher, currentUserProvider,
            new CampaignMapper(), tenantRepository, contactGroupRepository,
            new CampaignResourceValidationService(null, audioAssetRepository, null), policy);
        lenient().when(currentUserProvider.current()).thenReturn(Optional.of(
            new AuthenticatedUser(USER_ID, "edit@test.local", null)));
        com.shivang.obd.authz.context.OrganizationContextHolder
            .setAuthenticated(USER_ID, TENANT_A, null);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
    }

    private CampaignEntity campaignIn(CampaignStatus status) {
        CampaignEntity c = new CampaignEntity();
        c.setId(CAMPAIGN_ID);
        c.setTenantId(TENANT_A);
        c.setName("c-editability");
        c.setCampaignType(CampaignType.PLAYFILE);
        c.setStatus(status);
        c.setContentMode(ContentMode.AUDIO);
        c.setAudioAssetId(ASSET_ID);
        when(repository.findByIdAndTenantIdAndDeletedAtIsNull(CAMPAIGN_ID, TENANT_A))
            .thenReturn(Optional.of(c));
        lenient().when(repository.save(any(CampaignEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        // A usable AUDIO reference so content validation passes and the
        // lifecycle gate is what the assertions actually exercise.
        com.shivang.obd.audio.AudioAssetEntity asset =
            new com.shivang.obd.audio.AudioAssetEntity();
        asset.setId(ASSET_ID);
        asset.setTenantId(TENANT_A);
        asset.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
        asset.setStorageReference("s3://editability/promo.wav");
        lenient().when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(ASSET_ID, TENANT_A))
            .thenReturn(Optional.of(asset));
        return c;
    }

    private UpdateCampaignRequest updateRequest() {
        return new UpdateCampaignRequest(
            "renamed", null, CampaignRunMode.ONE_TIME,
            null, null, ContentMode.AUDIO, ASSET_ID, null,
            null, null, null, null, null);
    }

    @Test
    @DisplayName("Policy: only DRAFT is editable; every other state is rejected")
    void policyEditableMatrix() {
        for (CampaignStatus status : CampaignStatus.values()) {
            CampaignEntity campaign = new CampaignEntity();
            campaign.setStatus(status);
            if (status == CampaignStatus.DRAFT) {
                assertThat(policy.isEditable(campaign))
                    .as("DRAFT must be editable").isTrue();
                assertThatCode(() -> policy.assertEditable(campaign))
                    .as("DRAFT must pass assertEditable").doesNotThrowAnyException();
            } else {
                assertThat(policy.isEditable(campaign))
                    .as("%s must not be editable", status).isFalse();
                assertThatThrownBy(() -> policy.assertEditable(campaign))
                    .as("%s must be rejected by assertEditable", status)
                    .isInstanceOf(ConflictException.class);
            }
        }
    }

    @Test
    @DisplayName("Policy: assertEditable names the current state in the error")
    void policyErrorNamesState() {
        CampaignEntity scheduled = new CampaignEntity();
        scheduled.setStatus(CampaignStatus.SCHEDULED);

        assertThatThrownBy(() -> policy.assertEditable(scheduled))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("DRAFT")
            .hasMessageContaining("SCHEDULED");
    }

    @Test
    @DisplayName("DRAFT campaign update succeeds")
    void draftUpdateSucceeds() {
        campaignIn(CampaignStatus.DRAFT);

        ApiResponse<CampaignResponse> response =
            service.update(CAMPAIGN_ID, updateRequest());

        assertThat(response.data().name()).isEqualTo("renamed");
    }

    @Test
    @DisplayName("SCHEDULED (the executable/READY-equivalent state) rejects mutation")
    void scheduledUpdateRejected() {
        campaignIn(CampaignStatus.SCHEDULED);

        assertThatThrownBy(() -> service.update(CAMPAIGN_ID, updateRequest()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("DRAFT")
            .hasMessageContaining("SCHEDULED");
    }

    @Test
    @DisplayName("RUNNING, PAUSED, COMPLETED, FAILED and ARCHIVED all reject mutation")
    void otherNonEditableStatesRejected() {
        for (CampaignStatus status : CampaignStatus.values()) {
            if (status == CampaignStatus.DRAFT) {
                continue;
            }
            campaignIn(status);
            assertThatThrownBy(() -> service.update(CAMPAIGN_ID, updateRequest()))
                .as("mutation in %s must be rejected", status)
                .isInstanceOf(ConflictException.class);
        }
    }

    @Test
    @DisplayName("After an explicit SCHEDULED → DRAFT unlock, editing works again")
    void scheduledToDraftUnlocksEditing() {
        // The unlock edge is the existing legal transition
        // SCHEDULED → DRAFT (asserted in CampaignLifecycleServiceTest).
        // Simulate the campaign after that transition: once back in DRAFT,
        // the mutation path accepts the request that was rejected before.
        campaignIn(CampaignStatus.SCHEDULED);
        assertThatThrownBy(() -> service.update(CAMPAIGN_ID, updateRequest()))
            .isInstanceOf(ConflictException.class);

        campaignIn(CampaignStatus.DRAFT);
        ApiResponse<CampaignResponse> response =
            service.update(CAMPAIGN_ID, updateRequest());
        assertThat(response.data().name()).isEqualTo("renamed");
    }
}
