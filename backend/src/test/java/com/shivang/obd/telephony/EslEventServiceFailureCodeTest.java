package com.shivang.obd.telephony;

import static com.shivang.obd.voice.VoiceTestSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.shivang.obd.campaign.CallAttempt;
import com.shivang.obd.campaign.CallAttemptRepository;
import com.shivang.obd.campaign.CallAttemptStatus;
import com.shivang.obd.campaign.CallFailureCode;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.capacity.VoiceCapacityService;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * VB-6D.1 — persistence-boundary safety for the ESL hangup path.
 *
 * <p>Drives the real {@link EslEventService} with a provider hangup cause and
 * asserts on what actually lands in {@code CallAttempt.failureCode},
 * {@code CallSession.failureCode} and {@code CallLeg.failureCode}. This is the
 * end-to-end proof of the phase invariant, as opposed to
 * {@code HangupCauseMapperTest} which tests the mapper in isolation.
 *
 * <p>The critical property: for <em>any</em> provider cause — including causes
 * the integration has never seen — the persisted value is a canonical
 * {@link CallFailureCode}, never {@code "HANGUP_" + provider text}, and never
 * the non-canonical {@code "COMPLETED"}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EslEventServiceFailureCodeTest {

    @Mock CallAttemptRepository attemptRepository;
    @Mock CallSessionRepository callSessionRepository;
    @Mock CallLegRepository callLegRepository;
    @Mock VoiceCapacityService voiceCapacity;
    @Mock VoiceMediaController mediaController;
    @Mock com.shivang.obd.voice.media.PlaybackTrigger playbackTrigger;
    @Mock com.shivang.obd.voice.dtmf.DtmfResultService dtmfResultService;

    EslEventService service;

    static final UUID TENANT = TENANT_A;
    static final UUID GATEWAY = UUID.fromString("aa000000-0000-4000-8000-00000000001a");
    static final String PROVIDER_CALL_ID = "fs-call-canon-123";

    CallAttempt attempt;
    CallSession session;

    @BeforeEach
    void setUp() {
        service = new EslEventService(attemptRepository, callSessionRepository, callLegRepository,
                voiceCapacity, mediaController, List.of(playbackTrigger),
                Optional.empty(), dtmfResultService, Optional.empty(), Optional.empty(),
                Optional.empty());

        attempt = new CallAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setTenantId(TENANT);
        attempt.setStatus(CallAttemptStatus.IN_PROGRESS);
        attempt.setProviderCallId(PROVIDER_CALL_ID);

        session = new CallSession();
        session.setId(UUID.randomUUID());
        session.setTenantId(TENANT);
        session.setGatewayId(GATEWAY);
        session.setStatus(CallSessionStatus.DIALING);
        session.setProviderCallId(PROVIDER_CALL_ID);
        session.setInitiatedAt(Instant.now());

        lenient().when(attemptRepository.findByProviderCallIdAndDeletedAtIsNull(PROVIDER_CALL_ID))
                .thenReturn(Optional.of(attempt));
        lenient().when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(PROVIDER_CALL_ID))
                .thenReturn(Optional.of(session));
        lenient().when(callLegRepository.findByCallSessionIdAndDeletedAtIsNull(session.getId()))
                .thenReturn(List.of());
    }

    private void hangup(String cause) {
        EslEvent event = new EslEvent("CHANNEL_HANGUP");
        event.addHeader("Call-UUID", PROVIDER_CALL_ID);
        event.addHeader("Hangup-Cause", cause);
        service.processEvent(event);
    }

    @ParameterizedTest(name = "cause {0} -> attempt.failureCode={1}")
    @CsvSource({
            "17,            BUSY",
            "USER_BUSY,     BUSY",
            "19,            NO_ANSWER",
            "NO_ANSWER,     NO_ANSWER",
            "21,            REJECTED",
            "CALL_REJECTED, REJECTED",
            "34,            CONGESTION",
            "41,            TEMPORARY_FAILURE",
            "47,            RESOURCE_UNAVAILABLE"
    })
    @DisplayName("BOUND-1: supported causes persist their canonical code on the attempt and session")
    void supportedCausesPersistCanonicalCodes(String cause, String expected) {
        hangup(cause);

        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
        assertThat(attempt.getFailureCode()).isEqualTo(expected);
        assertThat(session.getStatus()).isEqualTo(CallSessionStatus.FAILED);
        assertThat(session.getFailureCode()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "unmapped cause {0} -> HANGUP_UNKNOWN")
    @ValueSource(strings = {"27", "15", "22", "31", "CARRIER_SPECIFIC", "0", "999999",
            "SOME_NEW_CARRIER_CODE"})
    @DisplayName("BOUND-2: unmapped causes persist the canonical unknown code, never provider text")
    void unmappedCausesPersistCanonicalUnknown(String cause) {
        hangup(cause);

        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
        assertThat(attempt.getFailureCode())
                .isEqualTo(CallFailureCode.HANGUP_UNKNOWN.getCode())
                .doesNotStartWith("HANGUP_" + cause);
        assertThat(CallFailureCode.fromCode(attempt.getFailureCode()))
                .as("persisted code must be canonical")
                .isPresent();
    }

    @Test
    @DisplayName("BOUND-3: a normal clearing persists NO failure code (it is an outcome, not a failure)")
    void normalClearingIsNotAFailure() {
        hangup("NORMAL_CLEARING");

        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
        assertThat(attempt.getFailureCode()).isNull();
        assertThat(session.getStatus()).isEqualTo(CallSessionStatus.COMPLETED);
        assertThat(session.getFailureCode()).isNull();
    }

    @Test
    @DisplayName("BOUND-4: numeric cause 16 is ALSO a normal clearing on this path")
    void numericNormalClearingIsSuccess() {
        hangup("16");

        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
        assertThat(attempt.getFailureCode()).isNull();
    }

    @Test
    @DisplayName("BOUND-5: an absent hangup cause persists the canonical unknown code")
    void absentCausePersistsCanonicalUnknown() {
        // No Hangup-Cause header at all.
        EslEvent event = new EslEvent("CHANNEL_HANGUP");
        event.addHeader("Call-UUID", PROVIDER_CALL_ID);
        service.processEvent(event);

        assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
        assertThat(attempt.getFailureCode())
                .isEqualTo(CallFailureCode.HANGUP_UNKNOWN.getCode());
    }

    /** Fresh attempt/session pair: a terminal attempt makes hangup a no-op. */
    private void resetLifecycle() {
        attempt = new CallAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setTenantId(TENANT);
        attempt.setStatus(CallAttemptStatus.IN_PROGRESS);
        attempt.setProviderCallId(PROVIDER_CALL_ID);

        session = new CallSession();
        session.setId(UUID.randomUUID());
        session.setTenantId(TENANT);
        session.setGatewayId(GATEWAY);
        session.setStatus(CallSessionStatus.DIALING);
        session.setProviderCallId(PROVIDER_CALL_ID);
        session.setInitiatedAt(Instant.now());

        lenient().when(attemptRepository.findByProviderCallIdAndDeletedAtIsNull(PROVIDER_CALL_ID))
                .thenReturn(Optional.of(attempt));
        lenient().when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(PROVIDER_CALL_ID))
                .thenReturn(Optional.of(session));
    }

    @Test
    @DisplayName("BOUND-6: the non-canonical code \"COMPLETED\" is never persisted as a failure")
    void completedIsNeverPersistedAsAFailureCode() {
        for (String cause : new String[] {"16", "NORMAL_CLEARING", "normal_clearing"}) {
            resetLifecycle();
            hangup(cause);

            assertThat(attempt.getFailureCode())
                    .as("cause %s must not persist COMPLETED as a failure code", cause)
                    .isNotEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("BOUND-7: every persisted failure code is classifiable by the retry gate")
    void everyPersistedCodeIsClassifiable() {
        for (String cause : new String[] {
                "17", "19", "21", "34", "41", "47", "27", "junk", "", "16", "NORMAL_CLEARING"}) {
            resetLifecycle();
            hangup(cause);

            String persisted = attempt.getFailureCode();
            if (persisted != null) {
                assertThat(CallFailureCode.fromCode(persisted))
                        .as("cause %s persisted %s, which must be canonical", cause, persisted)
                        .isPresent();
                assertThat(CallFailureCode.retryClassOf(persisted)).isNotNull();
            }
        }
    }
}
