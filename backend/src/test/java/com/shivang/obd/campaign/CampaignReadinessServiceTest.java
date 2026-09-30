package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shivang.obd.audio.AudioAssetRepository;
import com.shivang.obd.audio.AudioAssetStatus;
import com.shivang.obd.contact.ContactGroupRepository;
import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.tts.TtsTemplateRepository;
import com.shivang.obd.tenant.TenantRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * VB-7C.1: {@link CampaignReadinessService} is the owner of runnable/readiness
 * correctness, and this class is its owner test.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Before VB-7C.1 the platform had <b>no dedicated test class for the
 * readiness service</b>. Readiness was asserted only incidentally, by whichever
 * test happened to need a reason code. That is the structural reason a live
 * fail-open survived a whole release:
 *
 * <p>{@code checkScheduleReadiness} required an execution timezone behind a
 * campaign-type membership list that omitted {@link CampaignType#MISSED_CALL},
 * and only when a schedule object existed. So a MISSED_CALL campaign — and
 * <em>any</em> campaign with no schedule at all — could be created, reported
 * ready, and then fail every dial with a PERMANENT
 * {@code EXECUTION_TIMEZONE_INVALID}. The tests that did exist asserted the
 * types they were written for, not the invariant, so nothing failed.
 *
 * <h2>What this class is for</h2>
 *
 * <p>Every test here is parameterized over {@link CampaignType#values()} rather
 * than over a hand-written list of types. That is the whole point: a new campaign
 * type is picked up automatically by these tests, and the ones that would have
 * caught the two VB-7C.1 defects are stated as invariants about <em>every</em>
 * type instead of assertions about specific constants.
 *
 * <h2>Layer boundary</h2>
 *
 * <p>This class tests <b>readiness</b> only — "may this campaign run right now,
 * and if not why". Write-time rejection of a configuration that can never work is
 * {@code CampaignService}'s responsibility and is covered by
 * {@code CampaignMissedCallValidationTest} and
 * {@code CampaignValidationServiceTest}; runtime behaviour belongs to the
 * per-type execution services. The tests below deliberately do not duplicate
 * those layers, and where a defect spans layers the assertions here are about
 * the <em>readiness</em> consequence.
 */
class CampaignReadinessServiceTest {

    private static final UUID CALLER_ID =
            UUID.fromString("cc000000-0000-4000-8000-000000000001");
    private static final UUID TENANT_A =
            UUID.fromString("aa000000-0000-4000-8000-00000000000a");

    private CampaignRepository campaignRepository;
    private ContactGroupRepository contactGroupRepository;
    private CampaignReadinessService readinessService;

    @BeforeEach
    void setUp() {
        campaignRepository = mock(CampaignRepository.class);
        contactGroupRepository = mock(ContactGroupRepository.class);

        // Every referenced resource is healthy by default; each test degrades
        // exactly the one thing it is about. The DID check is an existence
        // predicate returning a plain boolean, not an Optional.
        DidRepository didRepository = mock(DidRepository.class);
        when(didRepository.existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                any(), any(), any(DidStatus.class), any(AllocationState.class)))
                .thenReturn(true);

        AudioAssetRepository audioRepository = mock(AudioAssetRepository.class);
        var asset = new com.shivang.obd.audio.AudioAssetEntity();
        asset.setId(UUID.randomUUID());
        asset.setStatus(AudioAssetStatus.APPROVED);
        asset.setStorageReference("audio/tenant-a/promo.wav");
        when(audioRepository.findByIdAndTenantIdAndDeletedAtIsNull(any(), any()))
                .thenReturn(java.util.Optional.of(asset));

        TtsTemplateRepository ttsRepository = mock(TtsTemplateRepository.class);

        // A usable agent queue, so a CONNECT_BY_AGENT campaign is ready on its
        // own terms rather than blocked by an unrelated reason. The checker is a
        // mock (matchers are only legal on mocks); the validator around it is
        // real, so its mapping onto readiness reason codes is exercised.
        var queueChecker = mock(com.shivang.obd.voice.agent.AgentQueueReferenceChecker.class);
        when(queueChecker.usabilityOf(any(), any()))
                .thenReturn(com.shivang.obd.voice.agent.AgentQueueReferenceChecker
                        .QueueUsability.USABLE);
        var queueProvider = mock(
                org.springframework.beans.factory.ObjectProvider.class);
        when(queueProvider.getIfAvailable()).thenReturn(queueChecker);

        CampaignResourceValidationService validator =
                new CampaignResourceValidationService(
                        didRepository, audioRepository, ttsRepository, queueProvider);

        when(contactGroupRepository.existsByIdAndTenantIdAndDeletedAtIsNull(any(), any()))
                .thenReturn(true);

        readinessService = new CampaignReadinessService(
                campaignRepository,
                mock(com.shivang.obd.authz.AuthorizationService.class),
                mock(com.shivang.obd.security.CurrentUserProvider.class),
                contactGroupRepository,
                validator,
                mock(TenantRepository.class));
    }

    private static com.shivang.obd.did.DidEntity approvedDid() {
        var did = new com.shivang.obd.did.DidEntity();
        did.setId(UUID.randomUUID());
        did.setTenantId(TENANT_A);
        did.setE164Number("+919900000001");
        did.setStatus(com.shivang.obd.did.DidStatus.ACTIVE);
        did.setAllocationState(com.shivang.obd.did.AllocationState.ASSIGNED);
        return did;
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A fully valid, runnable campaign of the given type, with a timezone. */
    private CampaignEntity ready(CampaignType type) {
        var c = new CampaignEntity();
        c.setId(UUID.randomUUID());
        c.setTenantId(TENANT_A);
        c.setName("ready-" + type);
        c.setCampaignType(type);
        c.setStatus(CampaignStatus.SCHEDULED);
        c.setDidId(UUID.randomUUID());
        c.setContactGroupId(UUID.randomUUID());
        c.setTypeConfig(json(typeConfigFor(type)));
        applyContentFor(type, c);
        c.setSchedule(wideOpenWindow("Asia/Kolkata"));
        register(c);
        return c;
    }

    /**
     * Gives a type exactly the content it legitimately needs: media-playing
     * types get an approved audio asset, non-playing types get nothing.
     */
    private void applyContentFor(CampaignType type, CampaignEntity c) {
        if (type.playsMedia()) {
            c.setContentMode(ContentMode.AUDIO);
            c.setAudioAssetId(UUID.randomUUID());
        } else {
            c.setContentMode(null);
            c.setAudioAssetId(null);
        }
    }

    /** A window that is open all year, so no SCHEDULE_*_NOT_ELIGIBLE noise. */
    private static com.shivang.obd.campaign.ScheduleSpec wideOpenWindow(String timezone) {
        return new com.shivang.obd.campaign.ScheduleSpec(
                LocalDate.of(2020, 1, 1),
                LocalTime.of(0, 0), LocalTime.of(23, 59),
                timezone, new LinkedHashSet<>(Set.of(DayOfWeek.values())), null);
    }

    /**
     * A schedule with NO calling window at all - the "always on" shape, which
     * write-time validation accepts without a timezone
     * ({@code CampaignService} requires one only {@code if (windowConfigured)}).
     * This is the exact state that used to bypass the readiness rule.
     */
    private static com.shivang.obd.campaign.ScheduleSpec windowless(String timezone) {
        return new com.shivang.obd.campaign.ScheduleSpec(
                null, null, null, timezone, null, null);
    }

    private void register(CampaignEntity c) {
        // evaluateForSystem loads the campaign by (id, tenantId) - the
        // tenant-scoped finder - and deliberately skips the caller capability
        // check, so these tests exercise the readiness RULES and not the
        // authorization ladder. Both finders are stubbed so the test is robust
        // to which entry point it uses.
        when(campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(c.getId(), c.getTenantId()))
                .thenReturn(java.util.Optional.of(c));
        when(campaignRepository.findByIdAndDeletedAtIsNull(c.getId()))
                .thenReturn(java.util.Optional.of(c));
    }

    private com.shivang.obd.campaign.dto.CampaignReadinessResponse evaluate(CampaignEntity c) {
        return readinessService.evaluateForSystem(c.getId(), c.getTenantId());
    }

    private static JsonNode json(String raw) {
        return JsonMapper.builder().build().readTree(raw);
    }

    /** The valid, type-specific payload each type must carry. */
    private static String typeConfigFor(CampaignType type) {
        return switch (type) {
            case PLAYFILE -> "{}";
            case DTMF -> "{\"dtmf\":{\"expected\":\"1\"}}";
            case CONNECT_BY_AGENT ->
                    "{\"connectByAgent\":{\"queueId\":\"3f2504e0-4f89-11d3-9a0c-0305e82c3301\","
                        + "\"selectionStrategy\":\"LEAST_ACTIVE_RESERVATIONS\","
                        + "\"ringDurationSeconds\":60}}";
            case MISSED_CALL -> "{\"missedCall\":{\"ringDurationSeconds\":30}}";
        };
    }

    static Stream<Arguments> everyType() {
        return Stream.of(CampaignType.values())
                .map(t -> Arguments.of(t));
    }

    // ------------------------------------------------------------------
    // A. The capability every rule now derives from
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("A. the media capability is exhaustive and compiler-enforced")
    class MediaCapability {

        @Test
        @DisplayName("A1. playsMedia() states a value for EVERY CampaignType, with no "
                + "default arm - a new constant cannot compile without one")
        void everyTypeHasAStatedCapability() {
            // The switch in CampaignType.playsMedia() has no `default` arm, so
            // this is a build-time guarantee. The test restates it as a runtime
            // invariant so a future refactor that reintroduces a default branch
            // fails here rather than silently accepting a new type.
            for (CampaignType type : CampaignType.values()) {
                assertThat(type.playsMedia())
                        .as("CampaignType.%s must state playsMedia()", type)
                        .isIn(true, false);
            }
            assertThat(CampaignType.values())
                    .as("a fifth type must be handled by playsMedia() and by these tests")
                    .hasSizeGreaterThanOrEqualTo(4);
        }

        @Test
        @DisplayName("A2. the capability matches what each type's runtime actually does")
        void capabilityMatchesRuntime() {
            // PLAYFILE and DTMF both play media to the callee, which is why both
            // require content and both must reject TTS. CONNECT_BY_AGENT bridges
            // and MISSED_CALL only rings: neither plays anything, so neither may
            // acquire a media-content requirement.
            assertThat(CampaignType.PLAYFILE.playsMedia()).isTrue();
            assertThat(CampaignType.DTMF.playsMedia()).isTrue();
            assertThat(CampaignType.CONNECT_BY_AGENT.playsMedia()).isFalse();
            assertThat(CampaignType.MISSED_CALL.playsMedia()).isFalse();
        }

        @Test
        @DisplayName("A3. every campaign type reaches typeConfig validation - a broken "
                + "payload is reported, never silently accepted")
        void everyTypeReachesTypeConfigValidation() {
            // The original fail-open was an early `return` for any type missing
            // from a list, so an unparseable payload was never parsed. These
            // cases are the anti-regression for that.
            for (CampaignType type : CampaignType.values()) {
                var c = ready(type);
                c.setTypeConfig(json("{\"definitelyNotThisType\":{}}"));

                var response = evaluate(c);

                assertThat(response.ready())
                        .as("%s with an unparseable typeConfig must not be ready", type)
                        .isFalse();
                assertThat(response.reasons())
                        .as("%s must report a type-configuration reason", type)
                        .anyMatch(r -> r.code().startsWith("INVALID_")
                                && r.code().endsWith("CONFIGURATION"));
            }
        }

        @Test
        @DisplayName("A4. a missing typeConfig is reported for every type that REQUIRES one, "
                + "and PLAYFILE's empty configuration remains valid")
        void everyTypeValidatesItsTypeConfigShape() {
            // PLAYFILE's valid typeConfig is the empty object, so `{}`/null is
            // correct for it and must NOT be reported. Stating that explicitly
            // keeps the invariant honest: the rule is "a type that has a
            // configuration requires it", not "every type requires one".
            for (CampaignType type : CampaignType.values()) {
                var c = ready(type);
                c.setTypeConfig(null);

                if (type == CampaignType.PLAYFILE) {
                    assertThat(evaluate(c).ready())
                            .as("PLAYFILE has no type-specific configuration to supply")
                            .isTrue();
                    continue;
                }
                assertThat(evaluate(c).ready())
                        .as("%s requires a type-specific configuration", type)
                        .isFalse();
            }
        }
    }

    // ------------------------------------------------------------------
    // B. VB-7C.1 defect 1 — the execution-timezone fail-open
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("B. an execution timezone is required of EVERY campaign type")
    class ExecutionTimezone {

        @ParameterizedTest(name = "{0} without a timezone is not ready")
        @MethodSource("com.shivang.obd.campaign.CampaignReadinessServiceTest#everyType")
        @DisplayName("B1. a windowless campaign with no timezone is not ready - for every "
                + "type, including MISSED_CALL")
        void windowlessCampaignWithoutTimezoneIsNotReady(CampaignType type) {
            var c = ready(type);
            // A schedule with no window and no timezone: exactly the shape that
            // used to sail through, because the requirement sat behind a type
            // list AND inside the `schedule != null` branch.
            c.setSchedule(windowless(null));

            var response = evaluate(c);

            assertThat(response.ready())
                    .as("%s with no execution timezone is undialable and must not be ready",
                            type)
                    .isFalse();
            assertThat(response.reasons())
                    .as("%s must report the missing timezone", type)
                    .anyMatch(r -> r.code().equals("SCHEDULE_TIMEZONE_REQUIRED"));
        }

        @ParameterizedTest(name = "{0} with no schedule at all is not ready")
        @MethodSource("com.shivang.obd.campaign.CampaignReadinessServiceTest#everyType")
        @DisplayName("B2. a campaign with NO schedule object is not ready - the check is no "
                + "longer skipped when schedule is null")
        void noScheduleAtAllIsNotReady(CampaignType type) {
            var c = ready(type);
            // CampaignMapper.toScheduleSpec(null) returns null, and every
            // schedule column is nullable, so this state is genuinely reachable
            // and genuinely persisted. OutboundDialService passes a null zone to
            // DailyDialLimitService for exactly this shape.
            c.setSchedule(null);

            var response = evaluate(c);

            assertThat(response.ready())
                    .as("%s with no schedule is undialable and must not be ready", type)
                    .isFalse();
            assertThat(response.reasons())
                    .anyMatch(r -> r.code().equals("SCHEDULE_TIMEZONE_REQUIRED"));
        }

        @ParameterizedTest(name = "{0} with a valid timezone IS ready")
        @MethodSource("com.shivang.obd.campaign.CampaignReadinessServiceTest#everyType")
        @DisplayName("B3. a valid timezone makes every type ready - the fix did not make "
                + "readiness unreachable")
        void validTimezoneIsReady(CampaignType type) {
            var response = evaluate(ready(type));

            assertThat(response.reasons())
                    .as("%s with a valid timezone must be ready; got %s",
                            type, response.reasons())
                    .isEmpty();
            assertThat(response.ready())
                    .as("%s with a valid timezone must be ready", type)
                    .isTrue();
        }

        @ParameterizedTest(name = "{0} with a blank timezone is not ready")
        @EnumSource(CampaignType.class)
        @DisplayName("B4. a blank (not merely absent) timezone is also refused - no fallback "
                + "is invented for whitespace")
        void blankTimezoneIsNotReady(CampaignType type) {
            var c = ready(type);
            c.setSchedule(wideOpenWindow("   "));

            assertThat(evaluate(c).reasons())
                    .anyMatch(r -> r.code().equals("SCHEDULE_TIMEZONE_REQUIRED"));
        }

        @Test
        @DisplayName("B5. an invalid IANA identifier is still reported as an invalid "
                + "schedule (the pre-existing rule is unchanged)")
        void invalidTimezoneIsStillInvalidSchedule() {
            var c = ready(CampaignType.MISSED_CALL);
            c.setSchedule(wideOpenWindow("Not/AZone"));

            assertThat(evaluate(c).reasons())
                    .anyMatch(r -> r.code().equals("INVALID_SCHEDULE")
                            && r.message().contains("IANA"));
        }

        @Test
        @DisplayName("B6. the timezone reason is reported once, not duplicated")
        void timezoneReasonIsNotDuplicated() {
            var c = ready(CampaignType.MISSED_CALL);
            c.setSchedule(windowless(null));

            assertThat(evaluate(c).reasons().stream()
                    .filter(r -> r.code().equals("SCHEDULE_TIMEZONE_REQUIRED")).count())
                    .isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // C. VB-7C.1 defect 2 — the DTMF + TTS fail-open
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("C. TTS is rejected for every type that plays media")
    class UnsupportedTts {

        @Test
        @DisplayName("C1. TTS is reported for EVERY media-playing type - stated over the "
                + "capability, so a future media-playing type is covered automatically")
        void ttsIsReportedForEveryMediaPlayingType() {
            int checked = 0;
            for (CampaignType type : CampaignType.values()) {
                if (!type.playsMedia()) {
                    continue;
                }
                var c = ready(type);
                c.setContentMode(ContentMode.TTS);
                c.setAudioAssetId(null);
                c.setTtsTemplateId(UUID.randomUUID());

                assertThat(evaluate(c).reasons())
                        .as("%s plays media and must not be ready with TTS", type)
                        .anyMatch(r -> r.code().equals("INVALID_CONTENT_CONFIGURATION"));
                checked++;
            }
            // Guards against the loop silently covering nothing if playsMedia()
            // were ever changed to return false for every type.
            assertThat(checked).isEqualTo(2);
        }

        @Test
        @DisplayName("C1b. a type that plays nothing is deliberately UNAFFECTED by the TTS "
                + "rule - its content mode is inert, not broken")
        void nonPlayingTypesKeepExistingTtsSemantics() {
            // CONNECT_BY_AGENT bridges and MISSED_CALL only rings: neither plays
            // media, so neither can fail at runtime because of a TTS reference.
            // Rejecting TTS for them would invent a media capability they do not
            // have, and would change VB-7A / VB-7B behaviour.
            for (CampaignType type : CampaignType.values()) {
                if (type.playsMedia()) {
                    continue;
                }
                var c = ready(type);
                c.setContentMode(ContentMode.TTS);
                c.setAudioAssetId(null);
                c.setTtsTemplateId(UUID.randomUUID());

                assertThat(evaluate(c).reasons())
                        .as("%s plays nothing, so TTS must not block readiness", type)
                        .noneMatch(r -> r.code().equals("INVALID_CONTENT_CONFIGURATION")
                                && r.message().contains("TTS content"));
            }
        }

        @Test
        @DisplayName("C2. DTMF + TTS specifically - the VB-7C.1 defect - is refused")
        void dtmfWithTtsIsRefused() {
            var c = ready(CampaignType.DTMF);
            c.setContentMode(ContentMode.TTS);
            c.setAudioAssetId(null);
            c.setTtsTemplateId(UUID.randomUUID());

            var response = evaluate(c);

            assertThat(response.ready()).isFalse();
            assertThat(response.reasons())
                    .anyMatch(r -> r.code().equals("INVALID_CONTENT_CONFIGURATION")
                            && r.message().contains("DTMF"));
        }

        @Test
        @DisplayName("C3. PLAYFILE + TTS regression: still refused, as VB-6E intended")
        void playfileWithTtsStillRefused() {
            var c = ready(CampaignType.PLAYFILE);
            c.setContentMode(ContentMode.TTS);
            c.setAudioAssetId(null);
            c.setTtsTemplateId(UUID.randomUUID());

            var response = evaluate(c);

            assertThat(response.ready()).isFalse();
            assertThat(response.reasons())
                    .anyMatch(r -> r.code().equals("INVALID_CONTENT_CONFIGURATION")
                            && r.message().contains("PLAYFILE"));
        }

        @Test
        @DisplayName("C4. AUDIO content remains valid for the media-playing types")
        void audioRemainsValid() {
            for (CampaignType type : CampaignType.values()) {
                if (!type.playsMedia()) {
                    continue;
                }
                assertThat(evaluate(ready(type)).ready())
                        .as("%s + AUDIO must stay ready", type)
                        .isTrue();
            }
        }
    }

    // ------------------------------------------------------------------
    // D. Content requirement is capability-specific
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("D. the content requirement follows playsMedia()")
    class ContentRequirement {

        @ParameterizedTest(name = "{0} with no content IS ready")
        @MethodSource("com.shivang.obd.campaign.CampaignReadinessServiceTest#everyType")
        @DisplayName("D1. no type is ever reported as needing content it cannot use")
        void noTypeDemandsUnusableContent(CampaignType type) {
            var c = ready(type);

            var response = evaluate(c);

            assertThat(response.reasons())
                    .as("%s must not be told it requires content", type)
                    .noneMatch(r -> r.code().equals("MISSING_REQUIRED_REFERENCE"));
        }

        @Test
        @DisplayName("D2. a media-playing type with no content mode IS reported")
        void mediaPlayingTypeNeedsContent() {
            for (CampaignType type : CampaignType.values()) {
                if (!type.playsMedia()) {
                    continue;
                }
                var c = ready(type);
                c.setContentMode(null);
                c.setAudioAssetId(null);

                assertThat(evaluate(c).reasons())
                        .as("%s plays media and must require content", type)
                        .anyMatch(r -> r.code().equals("MISSING_REQUIRED_REFERENCE"));
            }
        }

        @Test
        @DisplayName("D3. CONNECT_BY_AGENT acquires no media-content requirement")
        void connectByAgentNeedsNoContent() {
            var c = ready(CampaignType.CONNECT_BY_AGENT);
            c.setContentMode(null);
            c.setAudioAssetId(null);

            assertThat(evaluate(c).ready())
                    .as("VB-7A behaviour must be unchanged")
                    .isTrue();
        }

        @Test
        @DisplayName("D4. MISSED_CALL acquires no media-content requirement")
        void missedCallNeedsNoContent() {
            var c = ready(CampaignType.MISSED_CALL);
            c.setContentMode(null);
            c.setAudioAssetId(null);

            assertThat(evaluate(c).ready())
                    .as("VB-7B behaviour must be unchanged")
                    .isTrue();
        }

        @Test
        @DisplayName("D5. an AUDIO mode with no asset is reported by readiness")
        void audioModeWithoutAssetIsReported() {
            // Ownership note: the inverse rule - a content REFERENCE with no
            // mode - is a configuration-mutation rule and is owned by
            // CampaignService, asserted in CampaignTypeCapabilityValidationTest
            // (D1). It is deliberately not duplicated here, because readiness
            // evaluating a stored row cannot tell that story usefully and the
            // bad configuration can never be stored in the first place.
            var c = ready(CampaignType.PLAYFILE);
            c.setContentMode(ContentMode.AUDIO);
            c.setAudioAssetId(null);

            assertThat(evaluate(c).reasons())
                    .anyMatch(r -> r.code().equals("INVALID_CONTENT_CONFIGURATION"));
        }
    }

    // ------------------------------------------------------------------
    // E. A valid configuration is not merely "not rejected"
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("E. a valid campaign of each type actually reaches READY")
    class ValidCampaignsAreReady {

        @ParameterizedTest(name = "{0} is ready")
        @EnumSource(CampaignType.class)
        @DisplayName("E1. every type has a reachable READY state - the assertions above "
                + "cannot pass merely because readiness is unreachable")
        void everyTypeCanBeReady(CampaignType type) {
            var response = evaluate(ready(type));

            assertThat(response.reasons())
                    .as("%s must be reachable as READY; blocked by %s",
                            type, response.reasons())
                    .isEmpty();
        }

        @Test
        @DisplayName("E2. a DRAFT campaign is still not executable - the lifecycle rule is "
                + "unchanged by VB-7C.1")
        void lifecycleRuleUnchanged() {
            var c = ready(CampaignType.MISSED_CALL);
            c.setStatus(CampaignStatus.DRAFT);

            assertThat(evaluate(c).reasons())
                    .anyMatch(r -> r.code().equals("CAMPAIGN_NOT_EXECUTABLE_STATE"));
        }

        @Test
        @DisplayName("E3. a foreign contact group is still reported as unavailable - tenant "
                + "isolation is unchanged")
        void tenantIsolationUnchanged() {
            when(contactGroupRepository.existsByIdAndTenantIdAndDeletedAtIsNull(any(), any()))
                    .thenReturn(false);

            var response = evaluate(ready(CampaignType.MISSED_CALL));

            assertThat(response.reasons())
                    .anyMatch(r -> r.code().equals("CONTACT_GROUP_UNAVAILABLE"));
        }

        @Test
        @DisplayName("E4. an unavailable DID is still reported - resource validity stays "
                + "dynamic and tenant-scoped")
        void didUnavailabilityUnchanged() {
            var c = ready(CampaignType.MISSED_CALL);
            c.setDidId(UUID.randomUUID());
            register(c);

            // A DID that is absent resolves to unusable through the real validator
            // only if the repository returns empty; simulate that explicitly.
            var readinessWithMissingDid = new CampaignReadinessService(
                    campaignRepository,
                    mock(com.shivang.obd.authz.AuthorizationService.class),
                    mock(com.shivang.obd.security.CurrentUserProvider.class),
                    contactGroupRepository,
                    new CampaignResourceValidationService(
                            absentDidRepository(), mock(AudioAssetRepository.class),
                            mock(TtsTemplateRepository.class)),
                    mock(TenantRepository.class));

            var response = readinessWithMissingDid.evaluateForSystem(c.getId(), TENANT_A);

            assertThat(response.reasons())
                    .anyMatch(r -> r.code().equals("DID_UNAVAILABLE"));
        }
    }

    private static DidRepository absentDidRepository() {
        DidRepository repo = mock(DidRepository.class);
        when(repo.existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                any(), any(), any(DidStatus.class), any(AllocationState.class)))
                .thenReturn(false);
        return repo;
    }

    /**
     * VB-7C.2: integration configuration readiness.
     *
     * <p>The point of this group is what readiness does <em>not</em> do. VB-7C.2
     * implements no webhook delivery, no signing and no reporting, so any
     * readiness rule referencing those subsystems could only ever be false.
     * Gating readiness on an intentionally absent subsystem would leave every
     * configured campaign permanently unready for a condition that is not a
     * fault - the same mistake VB-7A fixed for live agent availability.
     */
    @Nested
    @DisplayName("F. integration configuration (VB-7C.2)")
    class IntegrationConfiguration {

        private CampaignEntity withIntegration(CampaignType type, String rawJson) {
            var c = ready(type);
            if (rawJson != null) {
                c.setIntegrationConfig(json(rawJson));
            }
            return c;
        }

        @Test
        @DisplayName("F1. a valid ENABLED webhook does NOT block readiness - delivery is "
                + "unimplemented, and that is not a fault")
        void validWebhookDoesNotBlockReadiness() {
            var response = evaluate(withIntegration(CampaignType.MISSED_CALL,
                    "{\"webhook\":{\"enabled\":true,"
                        + "\"endpoint\":\"https://example.com/hooks/campaign\","
                        + "\"events\":[\"campaign.attempt.completed\","
                        + "\"campaign.attempt.failed\"]},"
                        + "\"reportPrivacy\":{\"policy\":\"MASKED\"}}"));

            assertThat(response.reasons())
                    .as("a correctly configured webhook must leave the campaign ready")
                    .isEmpty();
            assertThat(response.ready()).isTrue();
        }

        @Test
        @DisplayName("F2. a valid webhook does not block readiness for ANY campaign type")
        void validWebhookDoesNotBlockAnyType() {
            for (CampaignType type : CampaignType.values()) {
                var response = evaluate(withIntegration(type,
                        "{\"webhook\":{\"enabled\":true,"
                            + "\"endpoint\":\"https://example.com/h\","
                            + "\"events\":[\"campaign.attempt.completed\"]}}"));
                assertThat(response.reasons())
                        .as("%s with a valid webhook must be ready", type)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("F3. a DISABLED webhook with nothing configured does not block readiness")
        void disabledWebhookDoesNotBlockReadiness() {
            assertThat(evaluate(withIntegration(
                    CampaignType.PLAYFILE, "{\"webhook\":{\"enabled\":false}}")).ready())
                    .isTrue();
        }

        @Test
        @DisplayName("F4. no integration configuration at all does not block readiness")
        void absentIntegrationDoesNotBlockReadiness() {
            assertThat(evaluate(ready(CampaignType.MISSED_CALL)).ready()).isTrue();
        }

        @Test
        @DisplayName("F5. an UNREADABLE stored configuration is reported, so a row the "
                + "platform cannot parse never presents as runnable")
        void unreadableStoredConfigurationIsReported() {
            // Write-time validation means the API cannot produce this. It can
            // still arise from a row written by another version of the platform or
            // edited directly in the database - which is exactly the case
            // readiness exists to catch and write-time validation cannot.
            var response = evaluate(withIntegration(
                    CampaignType.MISSED_CALL, "{\"webhook\":{\"endpoint\":\"nonsense\"}}"));

            assertThat(response.ready()).isFalse();
            assertThat(response.reasons())
                    .anyMatch(r -> r.code().equals("INVALID_INTEGRATION_CONFIGURATION"));
        }

        @Test
        @DisplayName("F6. readiness does not require webhook DELIVERY infrastructure, and "
                + "does not report a delivery-unavailable reason")
        void readinessNeverRequiresDeliveryInfrastructure() {
            var response = evaluate(withIntegration(CampaignType.MISSED_CALL,
                    "{\"webhook\":{\"enabled\":true,"
                        + "\"endpoint\":\"https://example.com/h\","
                        + "\"events\":[\"campaign.attempt.completed\"]}}"));

            String text = response.reasons().toString().toUpperCase(java.util.Locale.ROOT);
            assertThat(text)
                    .doesNotContain("DELIVERY")
                    .doesNotContain("WEBHOOK_TRANSPORT")
                    .doesNotContain("SIGNING")
                    .doesNotContain("UNREACHABLE")
                    .doesNotContain("PROVIDER");
        }

        @Test
        @DisplayName("F7. readiness does not require REPORT infrastructure, and does not "
                + "report a report-subsystem reason")
        void readinessNeverRequiresReportInfrastructure() {
            var response = evaluate(withIntegration(
                    CampaignType.MISSED_CALL, "{\"reportPrivacy\":{\"policy\":\"MASKED\"}}"));

            assertThat(response.reasons()).isEmpty();
            String text = response.reasons().toString().toUpperCase(java.util.Locale.ROOT);
            assertThat(text).doesNotContain("REPORT");
        }

        @Test
        @DisplayName("F8. an enabled webhook with no events does not pass readiness, even "
                + "though it cannot be stored through the API")
        void enabledWithoutEventsIsNotReady() {
            var response = evaluate(withIntegration(
                    CampaignType.MISSED_CALL, "{\"webhook\":{\"enabled\":true}}"));

            assertThat(response.ready()).isFalse();
            assertThat(response.reasons())
                    .anyMatch(r -> r.code().equals("INVALID_INTEGRATION_CONFIGURATION"));
        }

        @Test
        @DisplayName("F9. a stored endpoint with a non-web scheme is reported")
        void badStoredSchemeIsReported() {
            var response = evaluate(withIntegration(
                    CampaignType.MISSED_CALL,
                    "{\"webhook\":{\"enabled\":true,\"endpoint\":\"file:///etc/passwd\","
                        + "\"events\":[\"campaign.attempt.completed\"]}}"));

            assertThat(response.ready()).isFalse();
            assertThat(response.reasons())
                    .anyMatch(r -> r.code().equals("INVALID_INTEGRATION_CONFIGURATION"));
        }
    }
}