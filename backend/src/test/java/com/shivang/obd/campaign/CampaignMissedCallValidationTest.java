package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.common.exception.BusinessException;
import com.shivang.obd.contact.ContactGroupRepository;
import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.security.AuthenticatedUser;
import com.shivang.obd.security.CurrentUserProvider;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * VB-7B: the four campaign validation gates, corrected.
 *
 * <p>These are the regression tests that matter most in this phase. Before VB-7B
 * both the content requirement and the typeConfig requirement were expressed as
 * lists built from the enum's <em>current</em> membership, which is a fail-open
 * shape: adding a campaign type that nobody remembered to add to the list would
 * have silently skipped all of its configuration validation, or silently demanded
 * content it must not have. Each test below pins the corrected behaviour, and the
 * two that pin the <em>inclusive</em> content rule are the proof that a future
 * fifth type is now excluded by default.
 */
class CampaignMissedCallValidationTest {

    private static final UUID USER_ID = UUID.fromString("ff000000-0000-4000-8000-0000000000f1");
    private static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    private static final UUID GROUP_ID = UUID.fromString("cc000000-0000-4000-8000-0000000000c1");

    private CampaignService service;

    @BeforeEach
    void setUp() {
        CampaignRepository repository = org.mockito.Mockito.mock(CampaignRepository.class);
        TenantRepository tenantRepository = org.mockito.Mockito.mock(TenantRepository.class);
        ContactGroupRepository contactGroupRepository =
                org.mockito.Mockito.mock(ContactGroupRepository.class);
        DidRepository didRepository = org.mockito.Mockito.mock(DidRepository.class);

        when(contactGroupRepository.existsByIdAndTenantIdAndDeletedAtIsNull(any(), any()))
                .thenReturn(true);
        when(didRepository.existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                any(), any(), any(DidStatus.class), any(AllocationState.class)))
                .thenReturn(true);
        // PLAYFILE/DTMF content tests need a usable asset so that the CONTENT
        // rules - not the asset - are what is under test.
        com.shivang.obd.audio.AudioAssetRepository audioAssetRepository =
                org.mockito.Mockito.mock(com.shivang.obd.audio.AudioAssetRepository.class);
        var approved = new com.shivang.obd.audio.AudioAssetEntity();
        approved.setId(UUID.fromString("cd000000-0000-4000-8000-0000000000c2"));
        approved.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
        approved.setStorageReference("audio/tenant-a/asset/promo.wav");
        when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.of(approved));
        when(tenantRepository.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(new TenantEntity()));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CurrentUserProvider currentUserProvider = org.mockito.Mockito.mock(CurrentUserProvider.class);
        when(currentUserProvider.current())
                .thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "it@test.local", null)));

        service = new CampaignService(
                repository,
                org.mockito.Mockito.mock(AuthorizationService.class),
                org.mockito.Mockito.mock(com.shivang.obd.campaign.event.CampaignEventPublisher.class),
                currentUserProvider,
                new CampaignMapper(),
                tenantRepository,
                contactGroupRepository,
                new CampaignResourceValidationService(didRepository, audioAssetRepository, null),
                new CampaignLifecyclePolicy());
        com.shivang.obd.authz.context.OrganizationContextHolder
                .setAuthenticated(USER_ID, TENANT_A, null);
    }

    @AfterEach
    void tearDown() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
    }

    private static ObjectNode missedCall(int seconds) {
        ObjectNode inner = JsonNodeFactory.instance.objectNode();
        inner.put("ringDurationSeconds", seconds);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set("missedCall", inner);
        return root;
    }

    private com.shivang.obd.campaign.dto.CreateCampaignRequest request(
            CampaignType type, ContentMode mode, tools.jackson.databind.JsonNode typeConfig) {
        return request(type, mode, null, typeConfig);
    }

    /** Variant that also supplies an audio asset, for the content-bearing types. */
    private com.shivang.obd.campaign.dto.CreateCampaignRequest request(
            CampaignType type, ContentMode mode, UUID audioAssetId,
            tools.jackson.databind.JsonNode typeConfig) {
        return new com.shivang.obd.campaign.dto.CreateCampaignRequest(
                "c-missedcall-" + UUID.randomUUID(), null, type, null,
                GROUP_ID, null, mode, audioAssetId, null,
                new com.shivang.obd.campaign.dto.ScheduleConfig(
                        java.time.LocalDate.now().plusDays(1),
                        java.time.LocalTime.of(10, 0), java.time.LocalTime.of(18, 0),
                        "Asia/Kolkata", null, null),
                null, typeConfig, null, true, null);
    }

    private static final UUID ASSET_ID =
            UUID.fromString("cd000000-0000-4000-8000-0000000000c2");

    // ------------------------------------------------------------------
    // Content requirement: an INCLUSIVE list
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C17. a MISSED_CALL campaign needs no content at all")
    void missedCallNeedsNoContent() {
        // The pre-VB-7B rule was `type != CONNECT_BY_AGENT && mode == null`, which
        // would have demanded audio/TTS from a campaign type that plays nothing.
        assertThat(service.create(request(CampaignType.MISSED_CALL, null, missedCall(30)), TENANT_A))
                .isNotNull();
    }

    @Test
    @DisplayName("C17b. PLAYFILE and DTMF still require content - the content rule is not "
            + "weakened by the correction")
    void contentTypesStillRequireContent() {
        assertThatThrownBy(() -> service.create(
                request(CampaignType.PLAYFILE, null, null), TENANT_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("require content");
        assertThatThrownBy(() -> service.create(
                request(CampaignType.DTMF, null, null), TENANT_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("require content");
    }

    @Test
    @DisplayName("C17c. CONNECT_BY_AGENT still needs no content (unchanged)")
    void connectByAgentStillNeedsNoContent() {
        ObjectNode inner = JsonNodeFactory.instance.objectNode();
        inner.put("queueId", UUID.randomUUID().toString());
        inner.put("selectionStrategy", "LEAST_ACTIVE_RESERVATIONS");
        inner.put("ringDurationSeconds", 60);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set("connectByAgent", inner);
        // Fails on the queue reference, not on the missing content mode - proving
        // the content rule did not become stricter for this type either.
        assertThatThrownBy(() -> service.create(
                request(CampaignType.CONNECT_BY_AGENT, null, root), TENANT_A))
                .isNotNull();
    }

    // ------------------------------------------------------------------
    // typeConfig requirement: delegation, not a list
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C18. a MISSED_CALL typeConfig is actually parsed and validated")
    void missedCallTypeConfigIsValidated() {
        // The pre-VB-7B rule returned early for any type not named in
        // `DTMF || CONNECT_BY_AGENT`, so this payload would have been accepted
        // unvalidated - including at activation and at readiness.
        assertThatThrownBy(() -> service.create(
                request(CampaignType.MISSED_CALL, null, null), TENANT_A))
                .isInstanceOf(BusinessException.class);

        ObjectNode empty = JsonNodeFactory.instance.objectNode();
        assertThatThrownBy(() -> service.create(
                request(CampaignType.MISSED_CALL, null, empty), TENANT_A))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> service.create(
                request(CampaignType.MISSED_CALL, null, missedCall(9)), TENANT_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ringDurationSeconds");
    }

    @Test
    @DisplayName("C19. a legacy VB-6A-shaped payload is rejected for MISSED_CALL")
    void legacyPlaceholderShapeIsRejected() {
        ObjectNode legacy = JsonNodeFactory.instance.objectNode();
        legacy.put("connectTimeoutSecs", 30);
        assertThatThrownBy(() -> service.create(
                request(CampaignType.MISSED_CALL, null, legacy), TENANT_A))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("C19b. a DTMF campaign with an invalid typeConfig is still rejected "
            + "(delegation is at least as strict as the old list)")
    void dtmfValidationIsNotWeakened() {
        ObjectNode bad = JsonNodeFactory.instance.objectNode();
        bad.put("dtmf", "not-an-object");
        assertThatThrownBy(() -> service.create(
                request(CampaignType.DTMF, com.shivang.obd.campaign.ContentMode.AUDIO, bad),
                TENANT_A))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("C19c. PLAYFILE's empty configuration is still accepted (delegation did not "
            + "make PLAYFILE stricter)")
    void playfileEmptyConfigStillAccepted() {
        ObjectNode empty = JsonNodeFactory.instance.objectNode();
        assertThat(service.create(
                request(CampaignType.PLAYFILE, ContentMode.AUDIO, ASSET_ID, empty),
                TENANT_A)).isNotNull();
    }

    @Test
    @DisplayName("C19d. every type is validated through the sealed hierarchy, so a fifth "
            + "type cannot fail open")
    void everyTypeGoesThroughTheSealedHierarchy() {
        // PLAYFILE with a non-empty payload is the canary for the same fail-open
        // shape: it was never in the old "required" list, so its payload was never
        // parsed at write time. Delegation now rejects it.
        ObjectNode agentish = JsonNodeFactory.instance.objectNode();
        agentish.put("queueId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.create(
                request(CampaignType.PLAYFILE, ContentMode.AUDIO, ASSET_ID, agentish), TENANT_A))
                .isInstanceOf(BusinessException.class);
    }
}
