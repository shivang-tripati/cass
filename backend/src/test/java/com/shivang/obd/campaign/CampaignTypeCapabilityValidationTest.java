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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * VB-7C.1: {@link CampaignService} owns <b>configuration mutation validation</b>
 * — "may this configuration be stored at all".
 *
 * <h2>Layer boundary</h2>
 *
 * <p>This class deliberately tests only the write path. Whether a stored
 * campaign is <em>runnable right now</em> belongs to
 * {@code CampaignReadinessService} and is owned by
 * {@code CampaignReadinessServiceTest}; runtime behaviour belongs to the
 * per-type execution services. The two layers are held to the same
 * campaign-type semantics — {@link CampaignType#playsMedia()} — so a rule can
 * never be enforced in one and forgotten in the other.
 *
 * <h2>What is pinned here</h2>
 *
 * <ol>
 *   <li><b>TTS has no runtime</b>, so no campaign that actually plays media may
 *       be stored with {@code contentMode = TTS}. VB-6E added that guard for
 *       PLAYFILE only; DTMF remained open and would have failed every call with
 *       a PERMANENT {@code PLAYBACK_CONFIG_INVALID}.</li>
 *   <li><b>Content is required</b> exactly for the types that play media, and
 *       is never demanded of the types that play nothing.</li>
 *   <li>Every test is parameterized over {@link CampaignType#values()}, so a new
 *       type is covered automatically rather than by remembering to add a case.
 *       </li>
 * </ol>
 */
class CampaignTypeCapabilityValidationTest {

    private static final UUID USER_ID =
            UUID.fromString("ff000000-0000-4000-8000-0000000000f1");
    private static final UUID TENANT_A =
            UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    private static final UUID GROUP_ID =
            UUID.fromString("cc000000-0000-4000-8000-0000000000c1");
    private static final UUID ASSET_ID =
            UUID.fromString("cd000000-0000-4000-8000-0000000000c2");
    private static final UUID TEMPLATE_ID =
            UUID.fromString("ce000000-0000-4000-8000-0000000000c3");

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
        when(tenantRepository.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.of(new TenantEntity()));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var approvedAsset = new com.shivang.obd.audio.AudioAssetEntity();
        approvedAsset.setId(ASSET_ID);
        approvedAsset.setStatus(com.shivang.obd.audio.AudioAssetStatus.APPROVED);
        approvedAsset.setStorageReference("audio/tenant-a/asset/promo.wav");
        var audioAssetRepository =
                org.mockito.Mockito.mock(com.shivang.obd.audio.AudioAssetRepository.class);
        when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.of(approvedAsset));

        var approvedTemplate = new com.shivang.obd.tts.TtsTemplateEntity();
        approvedTemplate.setId(TEMPLATE_ID);
        approvedTemplate.setStatus(com.shivang.obd.tts.TtsTemplateStatus.APPROVED);
        var ttsRepository = org.mockito.Mockito.mock(
                com.shivang.obd.tts.TtsTemplateRepository.class);
        when(ttsRepository.findByIdAndTenantIdAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.of(approvedTemplate));

        var queueChecker = org.mockito.Mockito.mock(
                com.shivang.obd.voice.agent.AgentQueueReferenceChecker.class);
        when(queueChecker.usabilityOf(any(), any()))
                .thenReturn(com.shivang.obd.voice.agent.AgentQueueReferenceChecker
                        .QueueUsability.USABLE);
        var queueProvider = org.mockito.Mockito.mock(
                org.springframework.beans.factory.ObjectProvider.class);
        when(queueProvider.getIfAvailable()).thenReturn(queueChecker);

        CurrentUserProvider currentUserProvider = org.mockito.Mockito.mock(
                CurrentUserProvider.class);
        when(currentUserProvider.current())
                .thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "it@test.local", null)));

        service = new CampaignService(
                repository,
                org.mockito.Mockito.mock(AuthorizationService.class),
                org.mockito.Mockito.mock(
                        com.shivang.obd.campaign.event.CampaignEventPublisher.class),
                currentUserProvider,
                new CampaignMapper(),
                tenantRepository,
                contactGroupRepository,
                new CampaignResourceValidationService(
                        didRepository, audioAssetRepository, ttsRepository, queueProvider),
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

    private static ObjectNode payload(String rootKey, String... keyValues) {
        ObjectNode inner = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            inner.put(keyValues[i], keyValues[i + 1]);
        }
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set(rootKey, inner);
        return root;
    }

    /** The valid, type-specific payload for a type, with correctly TYPED values. */
    private static ObjectNode typeConfigFor(CampaignType type) {
        return switch (type) {
            case PLAYFILE -> JsonNodeFactory.instance.objectNode();
            case DTMF -> payload("dtmf", "expected", "1");
            case CONNECT_BY_AGENT -> {
                // Numeric and boolean fields must be real JSON numbers/booleans:
                // the parsers reject a string where a number is required, which
                // would fail these tests on the payload rather than the rule.
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

    private static com.shivang.obd.campaign.dto.CreateCampaignRequest request(
            CampaignType type, ContentMode mode, UUID assetId, UUID templateId,
            ObjectNode typeConfig) {
        return new com.shivang.obd.campaign.dto.CreateCampaignRequest(
                "c-" + type + "-" + UUID.randomUUID(), null, type, null,
                GROUP_ID, null, mode, assetId, templateId,
                new com.shivang.obd.campaign.dto.ScheduleConfig(
                        java.time.LocalDate.now().minusDays(1),
                        java.time.LocalTime.of(0, 0), java.time.LocalTime.of(23, 59),
                        "Asia/Kolkata", null, null),
                null, typeConfig, null, true, null);
    }

    /** Exactly what a type is entitled to: media types get an asset, others none. */
    private com.shivang.obd.campaign.dto.CreateCampaignRequest entitled(
            CampaignType type) {
        return type.playsMedia()
                ? request(type, ContentMode.AUDIO, ASSET_ID, null, typeConfigFor(type))
                : request(type, null, null, null, typeConfigFor(type));
    }

    // ------------------------------------------------------------------
    // A. Every type has a storable, entitled configuration
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} is storable with exactly the content it needs")
    @EnumSource(CampaignType.class)
    @DisplayName("A1. every campaign type can be created with precisely the content its "
            + "capability entitles it to")
    void everyTypeIsStorableWithItsEntitledContent(CampaignType type) {
        assertThat(service.create(entitled(type), TENANT_A))
                .as("%s must be creatable", type)
                .isNotNull();
    }

    @Test
    @DisplayName("A2. the content requirement is exactly the set of types that play media")
    void contentRequirementTracksTheCapability() {
        // Stated as an invariant over the capability rather than as a hand-kept
        // list, so the rule cannot drift away from the runtime behaviour it
        // describes.
        for (CampaignType type : CampaignType.values()) {
            var noContent = request(type, null, null, null, typeConfigFor(type));
            if (type.playsMedia()) {
                assertThatThrownBy(() -> service.create(noContent, TENANT_A))
                        .as("%s plays media and must require content", type)
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("require content");
            } else {
                assertThat(service.create(noContent, TENANT_A))
                        .as("%s plays nothing and must not be required to configure content",
                                type)
                        .isNotNull();
            }
        }
    }

    // ------------------------------------------------------------------
    // B. VB-7C.1 defect 2 — TTS has no runtime
    // ------------------------------------------------------------------

    @Test
    @DisplayName("B1. no campaign type that PLAYS MEDIA may be stored with TTS content")
    void ttsIsRejectedForEveryMediaPlayingType() {
        int checked = 0;
        for (CampaignType type : CampaignType.values()) {
            if (!type.playsMedia()) {
                continue;
            }
            var withTts = request(type, ContentMode.TTS, null, TEMPLATE_ID,
                    typeConfigFor(type));

            assertThatThrownBy(() -> service.create(withTts, TENANT_A))
                    .as("%s plays media and must be rejected with TTS", type)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("do not support TTS content");
            checked++;
        }
        // Guards against the loop silently covering nothing.
        assertThat(checked).isEqualTo(2);
    }

    @Test
    @DisplayName("B1b. the TTS rejection does NOT apply to a type that plays nothing")
    void nonPlayingTypesKeepExistingTtsSemantics() {
        // CONNECT_BY_AGENT bridges and MISSED_CALL only rings. Neither plays
        // media, so a TTS reference cannot fail a call at runtime for them, and
        // rejecting it would invent a media capability they do not have - a
        // behaviour change to VB-7A / VB-7B rather than a correctness fix.
        //
        // Asserted as "the TTS rule did not fire" rather than "the campaign was
        // stored": a non-playing type still has to satisfy the ordinary
        // TTS-template governance rules, which are a different concern tested
        // elsewhere. What matters here is that the VB-7C.1 rule is scoped to
        // types that actually play media.
        for (CampaignType type : CampaignType.values()) {
            if (type.playsMedia()) {
                continue;
            }
            var withTts = request(type, ContentMode.TTS, null, TEMPLATE_ID,
                    typeConfigFor(type));

            try {
                assertThat(service.create(withTts, TENANT_A))
                        .as("%s plays nothing, so TTS must remain storable", type)
                        .isNotNull();
            } catch (BusinessException e) {
                assertThat(e.getMessage())
                        .as("%s must not be rejected by the VB-7C.1 TTS rule", type)
                        .doesNotContain("do not support TTS content");
            }
        }
    }

    @Test
    @DisplayName("B2. the DTMF + TTS fail-open is closed - DTMF is named in the error")
    void dtmfWithTtsIsRejectedByName() {
        // VB-7C.1 defect 2. Before this phase the guard read
        // `type == PLAYFILE && mode == TTS`, so DTMF was never covered and a
        // DTMF+TTS campaign could be stored, activated and would then fail every
        // call with a PERMANENT PLAYBACK_CONFIG_INVALID.
        var withTts = request(CampaignType.DTMF, ContentMode.TTS, null, TEMPLATE_ID,
                typeConfigFor(CampaignType.DTMF));

        assertThatThrownBy(() -> service.create(withTts, TENANT_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DTMF")
                .hasMessageContaining("do not support TTS content");
    }

    @Test
    @DisplayName("B3. the PLAYFILE + TTS rejection is unchanged from VB-6E")
    void playfileWithTtsRegression() {
        var withTts = request(CampaignType.PLAYFILE, ContentMode.TTS, null, TEMPLATE_ID,
                typeConfigFor(CampaignType.PLAYFILE));

        assertThatThrownBy(() -> service.create(withTts, TENANT_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PLAYFILE")
                .hasMessageContaining("do not support TTS content");
    }

    @Test
    @DisplayName("B4. TTS is refused even when the template is real and approved - the "
            + "problem is the absent runtime, not the reference")
    void ttsIsRejectedDespiteAValidTemplate() {
        // The fixture supplies an APPROVED, tenant-owned template, so a pass
        // could only mean the reference was validated while the unrenderable
        // mode was accepted. Uses a media-playing type so the rule applies.
        var withTts = request(CampaignType.DTMF, ContentMode.TTS, null, TEMPLATE_ID,
                typeConfigFor(CampaignType.DTMF));

        assertThatThrownBy(() -> service.create(withTts, TENANT_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("do not support TTS content");
    }

    // ------------------------------------------------------------------
    // C. Non-playing types did not acquire a media requirement
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C1. CONNECT_BY_AGENT and MISSED_CALL are still storable with no content "
            + "at all (VB-7A / VB-7B behaviour preserved)")
    void nonPlayingTypesStillNeedNoContent() {
        assertThat(service.create(
                request(CampaignType.CONNECT_BY_AGENT, null, null, null,
                        typeConfigFor(CampaignType.CONNECT_BY_AGENT)), TENANT_A))
                .isNotNull();
        assertThat(service.create(
                request(CampaignType.MISSED_CALL, null, null, null,
                        typeConfigFor(CampaignType.MISSED_CALL)), TENANT_A))
                .isNotNull();
    }

    // ------------------------------------------------------------------
    // D. Unrelated pre-existing rules are untouched
    // ------------------------------------------------------------------

    @Test
    @DisplayName("D1. content references without a content mode are still rejected")
    void referencesWithoutModeStillRejected() {
        for (CampaignType type : CampaignType.values()) {
            var dangling = request(type, null, ASSET_ID, null, typeConfigFor(type));
            assertThatThrownBy(() -> service.create(dangling, TENANT_A))
                    .as("%s with a dangling asset reference", type)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("explicit content mode");
        }
    }

    @Test
    @DisplayName("D2. every type's typeConfig is still strictly validated (VB-7B behaviour)")
    void typeConfigStillValidatedForEveryType() {
        ObjectNode foreign = JsonNodeFactory.instance.objectNode();
        foreign.put("someOtherType", "x");
        for (CampaignType type : CampaignType.values()) {
            var wrong = request(type,
                    type.playsMedia() ? ContentMode.AUDIO : null,
                    type.playsMedia() ? ASSET_ID : null, null, foreign);
            assertThatThrownBy(() -> service.create(wrong, TENANT_A))
                    .as("%s must reject a foreign typeConfig", type)
                    .isInstanceOf(BusinessException.class);
        }
    }
}
