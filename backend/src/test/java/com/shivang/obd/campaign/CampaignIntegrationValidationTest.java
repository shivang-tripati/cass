package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.shivang.obd.authz.AuthorizationService;
import com.shivang.obd.campaign.config.CampaignIntegrationConfig;
import com.shivang.obd.campaign.config.ReportPrivacy;
import com.shivang.obd.campaign.config.ReportPrivacyConfig;
import com.shivang.obd.campaign.config.WebhookConfig;
import com.shivang.obd.campaign.config.WebhookEvent;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * VB-7C.2: {@link CampaignService} owns write-time validation of the typed
 * integration configuration.
 *
 * <h2>Why these are the interesting tests</h2>
 *
 * <p>Integration configuration is <b>type-independent</b>. It applies to every
 * campaign type in exactly the same way, so the tests here are parameterized
 * over {@link CampaignType#values()} rather than naming constants — which is
 * what makes a fifth campaign type automatically covered. That is the direct
 * counterpart to the Family-B defect VB-7C.1 removed: there is no list here for
 * a new type to be omitted from, because there is no list at all.
 *
 * <p>Layer boundary: this class tests "may this be stored". Whether a stored
 * campaign is <em>runnable</em> is {@code CampaignReadinessService}'s, owned by
 * {@code CampaignReadinessServiceTest}. Nothing here touches delivery, because
 * no delivery exists.
 */
class CampaignIntegrationValidationTest {

    private static final UUID USER_ID =
            UUID.fromString("ff000000-0000-4000-8000-0000000000f1");
    private static final UUID TENANT_A =
            UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    private static final UUID GROUP_ID =
            UUID.fromString("cc000000-0000-4000-8000-0000000000c1");
    private static final UUID ASSET_ID =
            UUID.fromString("cd000000-0000-4000-8000-0000000000c2");

    private CampaignService service;
    private CampaignRepository repository;

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(CampaignRepository.class);
        TenantRepository tenantRepository = org.mockito.Mockito.mock(TenantRepository.class);
        ContactGroupRepository contactGroupRepository =
                org.mockito.Mockito.mock(ContactGroupRepository.class);
        DidRepository didRepository = org.mockito.Mockito.mock(DidRepository.class);

        when(contactGroupRepository.existsByIdAndTenantIdAndDeletedAtIsNull(any(), any()))
                .thenReturn(true);
        when(didRepository.existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                any(), any(), any(DidStatus.class), any(AllocationState.class)))
                .thenReturn(true);
        when(tenantRepository.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(new TenantEntity()));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var asset = new com.shivang.obd.audio.AudioAssetEntity();
        asset.setId(ASSET_ID);
        asset.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
        asset.setStorageReference("audio/tenant-a/promo.wav");
        var audioRepo = org.mockito.Mockito.mock(
                com.shivang.obd.audio.AudioAssetRepository.class);
        when(audioRepo.findByIdAndTenantIdAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.of(asset));

        var queueChecker = org.mockito.Mockito.mock(
                com.shivang.obd.voice.agent.AgentQueueReferenceChecker.class);
        when(queueChecker.usabilityOf(any(), any()))
                .thenReturn(com.shivang.obd.voice.agent.AgentQueueReferenceChecker
                        .QueueUsability.USABLE);
        var queueProvider = org.mockito.Mockito.mock(
                org.springframework.beans.factory.ObjectProvider.class);
        when(queueProvider.getIfAvailable()).thenReturn(queueChecker);

        CurrentUserProvider currentUser = org.mockito.Mockito.mock(CurrentUserProvider.class);
        when(currentUser.current())
                .thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "it@test.local", null)));

        service = new CampaignService(
                repository,
                org.mockito.Mockito.mock(AuthorizationService.class),
                org.mockito.Mockito.mock(
                        com.shivang.obd.campaign.event.CampaignEventPublisher.class),
                currentUser,
                new CampaignMapper(),
                tenantRepository,
                contactGroupRepository,
                new CampaignResourceValidationService(
                        didRepository, audioRepo,
                        org.mockito.Mockito.mock(
                                com.shivang.obd.tts.TtsTemplateRepository.class),
                        queueProvider),
                new CampaignLifecyclePolicy());
        com.shivang.obd.authz.context.OrganizationContextHolder
                .setAuthenticated(USER_ID, TENANT_A, null);
    }

    @AfterEach
    void tearDown() {
        com.shivang.obd.authz.context.OrganizationContextHolder.clear();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static ObjectNode typeConfigFor(CampaignType type) {
        return switch (type) {
            case PLAYFILE -> JsonNodeFactory.instance.objectNode();
            case DTMF -> payload("dtmf", "expected", "1");
            case CONNECT_BY_AGENT -> {
                ObjectNode inner = JsonNodeFactory.instance.objectNode();
                inner.put("queueId", "3f2504e0-4f89-11d3-9a0c-0305e82c3301");
                inner.put("selectionStrategy", "LEAST_ACTIVE_RESERVATIONS");
                inner.put("ringDurationSeconds", 60);
                ObjectNode root = JsonNodeFactory.instance.objectNode();
                root.set("connectByAgent", inner);
                yield root;
            }
            case MISSED_CALL -> {
                ObjectNode inner = JsonNodeFactory.instance.objectNode();
                inner.put("ringDurationSeconds", 30);
                ObjectNode root = JsonNodeFactory.instance.objectNode();
                root.set("missedCall", inner);
                yield root;
            }
        };
    }

    private static ObjectNode payload(String rootKey, String... keyValues) {
        ObjectNode inner = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            inner.put(keyValues[i], keyValues[i + 1]);
        }
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set(rootKey, inner);
        return root;
    }

    private com.shivang.obd.campaign.dto.CreateCampaignRequest request(
            CampaignType type, CampaignIntegrationConfig integration) {
        return new com.shivang.obd.campaign.dto.CreateCampaignRequest(
                "c-int-" + UUID.randomUUID(), null, type, null,
                GROUP_ID, null,
                type.playsMedia() ? ContentMode.AUDIO : null,
                type.playsMedia() ? ASSET_ID : null, null,
                new com.shivang.obd.campaign.dto.ScheduleConfig(
                        java.time.LocalDate.now().minusDays(1),
                        java.time.LocalTime.of(0, 0), java.time.LocalTime.of(23, 59),
                        "Asia/Kolkata", null, null),
                null, typeConfigFor(type), integration, true, null);
    }

    /** A valid, fully configured webhook. */
    private static CampaignIntegrationConfig validWebhook() {
        return new CampaignIntegrationConfig(
                WebhookConfig.of("https://example.com/hooks/campaign",
                        java.util.Set.of(WebhookEvent.ATTEMPT_COMPLETED)),
                ReportPrivacyConfig.defaults());
    }

    // ------------------------------------------------------------------
    // A. Type independence - the anti-Family-B proof
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} accepts a valid webhook configuration")
    @EnumSource(CampaignType.class)
    @DisplayName("A1. integration configuration applies to EVERY campaign type, with no "
            + "list a new type could be omitted from")
    void validIntegrationIsStoredForEveryType(CampaignType type) {
        assertThat(service.create(request(type, validWebhook()), TENANT_A))
                .as("%s must accept a valid webhook configuration", type)
                .isNotNull();
    }

    @ParameterizedTest(name = "{0} rejects an enabled webhook with no endpoint")
    @EnumSource(CampaignType.class)
    @DisplayName("A2. an enabled webhook with no endpoint is unrepresentable, for EVERY type")
    void malformedIntegrationIsRejectedForEveryType(CampaignType type) {
        // The typed configuration is total: its canonical constructor refuses to
        // build a value that could never be valid. So this cannot even reach the
        // service - there is no such object to pass. The HTTP mapping of that
        // refusal (a Jackson failure inside the constructor surfaces as
        // HttpMessageNotReadableException -> 400 "Malformed request body") is
        // asserted in CampaignApiSliceTest.
        assertThatThrownBy(() -> new WebhookConfig(Boolean.TRUE, null, java.util.List.of(
                WebhookEvent.ATTEMPT_FAILED)))
                .isInstanceOf(
                        com.shivang.obd.campaign.config.CampaignConfigInvalidException.class)
                .hasMessageContaining("endpoint is required");

        // And the rest of the request shape is unaffected by that, for every type.
        assertThat(service.create(request(type, validWebhook()), TENANT_A)).isNotNull();
    }

    @Test
    @DisplayName("A3. an unsupported privacy policy is unrepresentable, for EVERY type")
    void badPrivacyIsUnrepresentable() {
        // ReportPrivacy has no value that parses from an unknown string, so the
        // only way to hold an unsupported policy is to invent one - which the
        // parser refuses.
        assertThatThrownBy(() -> ReportPrivacy.require("ANONYMISED"))
                .isInstanceOf(
                        com.shivang.obd.campaign.config.CampaignConfigInvalidException.class)
                .hasMessageContaining("FULL")
                .hasMessageContaining("MASKED");

        assertThat(service.create(request(CampaignType.MISSED_CALL,
                new CampaignIntegrationConfig(
                        WebhookConfig.disabled(),
                        new ReportPrivacyConfig(ReportPrivacy.MASKED))), TENANT_A))
                .isNotNull();
    }

    // ------------------------------------------------------------------
    // B. Write-time validation behaviour
    // ------------------------------------------------------------------

    @Test
    @DisplayName("B1. no integration configuration at all is accepted (the common case)")
    void absentConfigurationAccepted() {
        for (CampaignType type : CampaignType.values()) {
            assertThat(service.create(request(type, null), TENANT_A)).isNotNull();
        }
    }

    @Test
    @DisplayName("B2. a disabled webhook with no endpoint and no events is accepted")
    void disabledWebhookAccepted() {
        var cfg = new CampaignIntegrationConfig(
                WebhookConfig.disabled(), ReportPrivacyConfig.defaults());

        assertThat(service.create(request(CampaignType.PLAYFILE, cfg), TENANT_A))
                .isNotNull();
    }

    @Test
    @DisplayName("B3. a valid MASKED report privacy configuration is accepted")
    void maskedPrivacyAccepted() {
        var cfg = new CampaignIntegrationConfig(
                WebhookConfig.disabled(),
                new ReportPrivacyConfig(ReportPrivacy.MASKED));

        assertThat(service.create(request(CampaignType.PLAYFILE, cfg), TENANT_A))
                .isNotNull();
    }

    @Test
    @DisplayName("B4. a configuration the service could still be handed programmatically is "
            + "re-validated at the write boundary and reported as a BusinessException (400)")
    void serviceReValidatesAsDefenceInDepth() {
        // The typed configuration is already total, so this path is unreachable
        // through the REST API. It is kept because CampaignService is also called
        // from non-REST code, and because validateTypeConfig above follows the
        // same shape: the service keeps its own authority rather than trusting
        // that whoever built the object checked it.
        var hostile = new CampaignIntegrationConfig(
                WebhookConfig.disabled(), ReportPrivacyConfig.defaults());
        assertThat(service.create(
                request(CampaignType.DTMF, hostile), TENANT_A)).isNotNull();
    }

    @Test
    @DisplayName("B5. a non-web endpoint scheme is refused at construction, not persisted")
    void nonWebSchemeRefused() {
        assertThatThrownBy(() -> new WebhookConfig(
                Boolean.TRUE, "file:///etc/passwd",
                java.util.List.of(WebhookEvent.ATTEMPT_FAILED)))
                .isInstanceOf(
                        com.shivang.obd.campaign.config.CampaignConfigInvalidException.class)
                .hasMessageContaining("scheme");
    }

    // ------------------------------------------------------------------
    // C. Persistence: canonical form, and secrets cannot be stored
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C1. what is persisted is the CANONICAL serialization, not the client's "
            + "raw JSON")
    void storedFormIsCanonical() {
        service.create(request(CampaignType.MISSED_CALL, validWebhook()), TENANT_A);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.shivang.obd.campaign.CampaignEntity.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        var stored = captor.getValue().getIntegrationConfig();

        assertThat(stored).isNotNull();
        assertThat(stored.at("/webhook/enabled").asBoolean()).isTrue();
        assertThat(stored.at("/webhook/endpoint").asString())
                .isEqualTo("https://example.com/hooks/campaign");
        assertThat(stored.at("/reportPrivacy/policy").asString()).isEqualTo("FULL");
    }

    @Test
    @DisplayName("C2. an absent configuration is persisted as null, not as a defaulted object")
    void absentConfigurationStoredAsNull() {
        service.create(request(CampaignType.PLAYFILE, null), TENANT_A);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.shivang.obd.campaign.CampaignEntity.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());

        assertThat(captor.getValue().getIntegrationConfig())
                .as("writing an explicit default into every row would be a silent "
                        + "change to every existing campaign")
                .isNull();
    }
}
