package com.shivang.obd.telephony;

import static com.shivang.obd.voice.VoiceTestSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shivang.obd.campaign.CallAttempt;
import com.shivang.obd.campaign.CallAttemptRepository;
import com.shivang.obd.campaign.CallAttemptStatus;
import com.shivang.obd.voice.call.CallLeg;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * VB-0 ESL event tests — the CHANNEL_HANGUP side of the reservation
 * lifecycle (RES3/RES5/RES6): hangup releases the capacity reservation,
 * duplicate hangup events are idempotent, and unknown call UUIDs are safe
 * no-ops.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EslEventServiceTest {

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
    static final String PROVIDER_CALL_ID = "fs-call-abc-123";

    CallAttempt attempt;
    CallSession session;

    @BeforeEach
    void setUp() {
        service = new EslEventService(attemptRepository, callSessionRepository, callLegRepository,
                voiceCapacity, mediaController, java.util.List.of(playbackTrigger),
                java.util.Optional.empty(), dtmfResultService, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());

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

    private EslEvent hangupEvent(String cause) {
        EslEvent e = new EslEvent("CHANNEL_HANGUP");
        e.addHeader("Call-UUID", PROVIDER_CALL_ID);
        e.addHeader("Hangup-Cause", cause);
        return e;
    }

    @Nested
    class ChannelHangup {

        @Test
        void RES3_channelHangup_releasesReservationWithGatewayAndTenant() {
            boolean processed = service.processEvent(hangupEvent("NORMAL_CLEARING"));

            assertThat(processed).isTrue();
            verify(voiceCapacity).release(GATEWAY, TENANT);
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.COMPLETED);
            assertThat(session.getEndedAt()).isNotNull();
        }

        @Test
        void failedCall_releasesReservationAndMarksSessionFailed() {
            boolean processed = service.processEvent(hangupEvent("USER_BUSY"));

            assertThat(processed).isTrue();
            verify(voiceCapacity).release(GATEWAY, TENANT);
            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
            assertThat(attempt.getFailureCode()).isEqualTo("BUSY");
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.FAILED);
        }

        @Test
        void RES5_duplicateHangupEvents_areIdempotent_releaseInvokedOnce() {
            service.processEvent(hangupEvent("NORMAL_CLEARING")); // first: attempt -> COMPLETED
            service.processEvent(hangupEvent("NORMAL_CLEARING")); // duplicate: attempt already terminal

            verify(voiceCapacity, org.mockito.Mockito.times(1)).release(any(UUID.class), any(UUID.class));
            verify(voiceCapacity).release(GATEWAY, TENANT); // exactly once
        }

        @Test
        void RES6_unknownProviderCallId_isSafeNoOp() {
            EslEvent e = new EslEvent("CHANNEL_HANGUP");
            e.addHeader("Call-UUID", "fs-unknown-uuid");

            boolean processed = service.processEvent(e);

            assertThat(processed).isFalse();
            verify(voiceCapacity, never()).release(any(UUID.class), any(UUID.class));
        }

        @Test
        void eventWithoutCallUuid_isIgnored() {
            EslEvent e = new EslEvent("CHANNEL_HANGUP");

            boolean processed = service.processEvent(e);

            assertThat(processed).isFalse();
            verify(voiceCapacity, never()).release(any(UUID.class), any(UUID.class));
        }

        @Test
        void sessionWithoutGateway_doesNotReleaseCapacity() {
            session.setGatewayId(null);

            service.processEvent(hangupEvent("NORMAL_CLEARING"));

            verify(voiceCapacity, never()).release(any(UUID.class), any(UUID.class));
        }
    }

    @Nested
    class ChannelAnswer {

        @Test
        void channelAnswer_marksSessionAnswered_withoutReleasingCapacity() {
            EslEvent e = new EslEvent("CHANNEL_ANSWER");
            e.addHeader("Call-UUID", PROVIDER_CALL_ID);

            boolean processed = service.processEvent(e);

            assertThat(processed).isTrue();
            assertThat(session.getStatus()).isEqualTo(CallSessionStatus.ANSWERED);
            assertThat(session.getAnsweredAt()).isNotNull();
            verify(voiceCapacity, never()).release(any(UUID.class), any(UUID.class));
        }
    }
}
