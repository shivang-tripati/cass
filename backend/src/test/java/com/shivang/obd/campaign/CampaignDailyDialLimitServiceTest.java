package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.shivang.obd.campaign.dto.CreateCampaignRequest;
import com.shivang.obd.campaign.dto.RetryPolicyConfig;
import com.shivang.obd.campaign.dto.ScheduleConfig;
import com.shivang.obd.campaign.dto.UpdateCampaignRequest;
import com.shivang.obd.campaign.event.CampaignDomainEvent;
import com.shivang.obd.campaign.event.CampaignEventPublisher;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.contact.ContactGroupRepository;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * VB-6C.2 — campaign-level dailyDialLimit configuration:
 * service/domain validation matrix, null-default semantics, mapper
 * threading, and configuration editability through the existing
 * lifecycle rules. Snapshot immutability is proven against real
 * PostgreSQL in {@code DailyDialLimitSnapshotPostgresIntegrationTest};
 * REST/OpenAPI shapes are asserted by the generated-contract test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CampaignDailyDialLimitServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("cc000000-0000-4000-8000-000000000001");
    private static final java.util.UUID TENANT_A =
            UUID.fromString("aa000000-0000-4000-8000-00000000000a");

    @Mock
    private CampaignRepository repository;
    @Mock
    private com.shivang.obd.authz.AuthorizationService authorizationService;
    @Mock
    private CampaignEventPublisher eventPublisher;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private ContactGroupRepository contactGroupRepository;
    @Mock
    private com.shivang.obd.campaign.CampaignResourceValidationService resourceValidator;

    private CampaignService service;
    private CampaignEntity saved;

    @BeforeEach
    void setUp() {
        CampaignMapper mapper = new CampaignMapper();
        service = new CampaignService(
            repository, authorizationService, eventPublisher, currentUserProvider,
            mapper, tenantRepository, contactGroupRepository, resourceValidator,
            new CampaignLifecyclePolicy());

        saved = null;
        org.mockito.Mockito.when(repository.save(any(CampaignEntity.class)))
            .thenAnswer(inv -> {
                saved = inv.getArgument(0);
                return saved;
            });
        org.mockito.Mockito.when(currentUserProvider.current())
            .thenReturn(Optional.of(new com.shivang.obd.security.AuthenticatedUser(
                USER_ID, "admin@test.local", null)));
        org.mockito.Mockito.when(tenantRepository.findByIdAndDeletedAtIsNull(TENANT_A))
            .thenReturn(Optional.of(new com.shivang.obd.tenant.TenantEntity()));
        org.mockito.Mockito.doNothing().when(authorizationService)
            .requireCapability(any(), any(), any());
        // Audio validation usable by default (canonical validator mocked).
        org.mockito.Mockito.when(resourceValidator.validateAudio(any(), any()))
            .thenReturn(CampaignResourceValidationService.ResourceValidationResult.valid());

        com.shivang.obd.authz.context.OrganizationContextHolder
            .setAuthenticated(USER_ID, TENANT_A, null);
    }

    @AfterEach
    void tearDown() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
    }

    private CreateCampaignRequest createRequest(Integer dailyDialLimit) {
        return new CreateCampaignRequest(
            "Limited blast", null, CampaignType.PLAYFILE, null,
            null, null,
            ContentMode.AUDIO, UUID.randomUUID(), null,
            new ScheduleConfig(LocalDate.of(2026, 9, 1),
                LocalTime.of(10, 0), LocalTime.of(18, 0), "Asia/Kolkata", Set.of(), null),
            new RetryPolicyConfig(0, null, null),
            null, null, false,
            dailyDialLimit);
    }

    private UpdateCampaignRequest updateRequest(Integer dailyDialLimit) {
        return new UpdateCampaignRequest(
            "Limited blast", null, null,
            null, null,
            ContentMode.AUDIO, UUID.randomUUID(), null,
            new ScheduleConfig(LocalDate.of(2026, 9, 1),
                LocalTime.of(10, 0), LocalTime.of(18, 0), "Asia/Kolkata", Set.of(), null),
            new RetryPolicyConfig(0, null, null),
            null, null,
            dailyDialLimit);
    }

    private CampaignEntity draftCampaign(Integer configured) {
        CampaignEntity campaign = new CampaignEntity();
        campaign.setId(UUID.randomUUID());
        campaign.setTenantId(TENANT_A);
        campaign.setName("Existing");
        campaign.setCampaignType(CampaignType.PLAYFILE);
        campaign.setStatus(CampaignStatus.DRAFT);
        campaign.setContentMode(ContentMode.AUDIO);
        campaign.setAudioAssetId(UUID.randomUUID());
        campaign.setRetryPolicy(new RetryPolicySpec(0, null, RetryStrategy.FIXED));
        campaign.setDailyDialLimit(configured);
        return campaign;
    }

    @Nested
    class ValidationMatrix {

        @Test
        @DisplayName("null → valid (platform default); 1, 2, 3 → valid")
        void validValuesAccepted() {
            for (Integer valid : new Integer[] {null, 1, 2, 3}) {
                service.create(createRequest(valid), null);
                assertThat(saved.getDailyDialLimit()).isEqualTo(valid);
            }
        }

        @Test
        @DisplayName("0, -1, 4, 100 → rejected at the service/domain boundary")
        void invalidValuesRejected() {
            for (Integer invalid : new Integer[] {0, -1, 4, 100}) {
                assertThatThrownBy(() -> service.create(createRequest(invalid), null))
                        .as("dailyDialLimit=%s must be rejected", invalid)
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("dailyDialLimit");
                verify(repository, org.mockito.Mockito.never()).save(any());
            }
        }

        @Test
        @DisplayName("canonical constant is the platform maximum of 3")
        void platformMaximumConstant() {
            assertThat(DailyDialLimitService.MAX_VOICE_BLAST_DAILY_DIAL_LIMIT).isEqualTo(3);
            assertThat(DailyDialLimitService.PLATFORM_DAILY_DIAL_LIMIT).isEqualTo(3);
        }

        @Test
        @DisplayName("assertConfigurable: null passes; 1..3 pass; <=0 and >3 fail")
        void domainGuardMatrix() {
            DailyDialLimitService.assertConfigurable(null); // no throw
            DailyDialLimitService.assertConfigurable(1);
            DailyDialLimitService.assertConfigurable(2);
            DailyDialLimitService.assertConfigurable(3);
            for (Integer invalid : new Integer[] {0, -1, 4, 100}) {
                assertThatThrownBy(() -> DailyDialLimitService.assertConfigurable(invalid))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("dailyDialLimit");
            }
        }

        @Test
        @DisplayName("effective limit: null→3, 1→1, 2→2, 3→3, 5→min(5,3)=3")
        void effectiveLimitSemantics() {
            DailyDialLimitService limitService = new DailyDialLimitService(
                    org.mockito.Mockito.mock(VoiceBlastDailyUsageRepository.class),
                    org.mockito.Mockito.mock(VoiceBlastDailyUsageEntryRepository.class),
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
            assertThat(limitService.effectiveLimit(null)).isEqualTo(3);
            assertThat(limitService.effectiveLimit(1)).isEqualTo(1);
            assertThat(limitService.effectiveLimit(2)).isEqualTo(2);
            assertThat(limitService.effectiveLimit(3)).isEqualTo(3);
            assertThat(limitService.effectiveLimit(5)).isEqualTo(3);
        }

        @Test
        @DisplayName("UPDATE respects the same validation matrix")
        void updateValidatesSameMatrix() {
            CampaignEntity campaign = draftCampaign(3);
            org.mockito.Mockito.when(repository.findByIdAndTenantIdAndDeletedAtIsNull(
                    campaign.getId(), TENANT_A)).thenReturn(Optional.of(campaign));

            service.update(campaign.getId(), updateRequest(1));
            assertThat(saved.getDailyDialLimit()).isEqualTo(1);

            for (Integer invalid : new Integer[] {0, 4}) {
                assertThatThrownBy(() -> service.update(campaign.getId(), updateRequest(invalid)))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("dailyDialLimit");
            }
            // The entity was mutated only by the VALID update (save happened once).
            verify(repository).save(any(CampaignEntity.class));
        }
    }

    @Nested
    class NullDefaultSemantics {

        @Test
        @DisplayName("omitted value persists as NULL — never silently normalized to 3")
        void omittedValuePersistsAsNull() {
            service.create(createRequest(null), null);
            assertThat(saved.getDailyDialLimit()).isNull();
        }

        @Test
        @DisplayName("response preserves null (configured vs effective distinction)")
        void responsePreservesNull() {
            CampaignEntity entity = draftCampaign(null);
            entity.setId(UUID.randomUUID());
            var response = new CampaignMapper().toResponse(entity);
            assertThat(response.dailyDialLimit()).isNull();

            entity.setDailyDialLimit(2);
            assertThat(new CampaignMapper().toResponse(entity).dailyDialLimit()).isEqualTo(2);
        }
    }
}
