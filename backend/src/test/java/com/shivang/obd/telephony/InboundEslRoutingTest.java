package com.shivang.obd.telephony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.campaign.CallAttemptRepository;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallType;
import com.shivang.obd.voice.capacity.VoiceCapacityService;
import com.shivang.obd.voice.dtmf.DtmfResultService;
import com.shivang.obd.voice.inbound.InboundCallEvents;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ESL contract tests for the VB-4D inbound boundary: verifies the exact
 * FreeSWITCH event fields the parser consumes (Call-UUID, Call-Direction,
 * Caller-Destination-Number, Caller-Caller-ID-Number, Hangup-Cause) and
 * that events route to the inbound boundary only for genuinely inbound
 * channels — outbound campaign channels are unaffected.
 */
class InboundEslRoutingTest {

    private static final String CALLER_UUID = "caller-channel-uuid";
    private static final UUID SESSION_ID = UUID.randomUUID();

    @org.mockito.Mock
    private CallAttemptRepository attemptRepository;
    @org.mockito.Mock
    private CallSessionRepository callSessionRepository;
    @org.mockito.Mock
    private CallLegRepository callLegRepository;
    @org.mockito.Mock
    private VoiceCapacityService voiceCapacity;
    @org.mockito.Mock
    private VoiceMediaController mediaController;
    @org.mockito.Mock
    private DtmfResultService dtmfResultService;
    @org.mockito.Mock
    private InboundCallEvents inboundCallEvents;

    private EslEventService service;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        service = new EslEventService(attemptRepository, callSessionRepository,
                callLegRepository, voiceCapacity, mediaController,
                List.of(), Optional.empty(), dtmfResultService,
                Optional.empty(), Optional.of(inboundCallEvents), java.util.Optional.empty());
    }

    private EslEvent event(String name, String callUuid) {
        EslEvent e = new EslEvent(name);
        e.addHeader("Call-UUID", callUuid);
        return e;
    }

    private CallSession inboundSession() {
        CallSession s = new CallSession();
        s.setId(SESSION_ID);
        s.setCallType(CallType.CONTACT_CENTER_INBOUND);
        return s;
    }

    @Test
    @DisplayName("I1: inbound CHANNEL_CREATE with direction header → onInboundChannelCreated with parsed fields")
    void inboundChannelCreateRouted() {
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CALLER_UUID))
                .thenReturn(Optional.empty());
        EslEvent e = event("CHANNEL_CREATE", CALLER_UUID);
        e.addHeader("Call-Direction", "inbound");
        e.addHeader("Caller-Destination-Number", "+911234567890");
        e.addHeader("Caller-Caller-ID-Number", "+919876543210");

        boolean processed = service.processEvent(e);

        assertThat(processed).isTrue();
        verify(inboundCallEvents).onInboundChannelCreated(
                CALLER_UUID, "+911234567890", "+919876543210");
        verify(attemptRepository, never()).findByProviderCallIdAndDeletedAtIsNull(anyString());
    }

    @Test
    @DisplayName("I2: outbound CHANNEL_CREATE (no inbound direction) never reaches the inbound boundary")
    void outboundChannelCreateIgnored() {
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CALLER_UUID))
                .thenReturn(Optional.empty());
        EslEvent e = event("CHANNEL_CREATE", CALLER_UUID);
        e.addHeader("Call-Direction", "outbound");

        service.processEvent(e);

        verify(inboundCallEvents, never()).onInboundChannelCreated(
                any(), any(), any());
    }

    @Test
    @DisplayName("I3: CHANNEL_CREATE without direction header is ignored by the inbound path")
    void directionlessChannelCreateIgnored() {
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CALLER_UUID))
                .thenReturn(Optional.empty());

        assertThat(service.processEvent(event("CHANNEL_CREATE", CALLER_UUID))).isFalse();
        verify(inboundCallEvents, never()).onInboundChannelCreated(
                any(), any(), any());
    }

    @Test
    @DisplayName("I4: ANSWER on an inbound session's caller channel → onInboundCallerAnswered")
    void inboundAnswerRouted() {
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CALLER_UUID))
                .thenReturn(Optional.of(inboundSession()));
        when(attemptRepository.findByProviderCallIdAndDeletedAtIsNull(CALLER_UUID))
                .thenReturn(Optional.empty());
        CallLeg callerLeg = new CallLeg();
        callerLeg.setLegType(CallLegType.CUSTOMER);
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(
                SESSION_ID, CallLegType.CUSTOMER))
                .thenReturn(List.of(callerLeg));

        service.processEvent(event("CHANNEL_ANSWER", CALLER_UUID));

        verify(inboundCallEvents).onInboundCallerAnswered(callerLeg);
    }

    @Test
    @DisplayName("I5: HANGUP on an inbound session's caller channel carries the cause → onInboundCallerHangup")
    void inboundHangupRouted() {
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CALLER_UUID))
                .thenReturn(Optional.of(inboundSession()));
        when(attemptRepository.findByProviderCallIdAndDeletedAtIsNull(CALLER_UUID))
                .thenReturn(Optional.empty());
        CallLeg callerLeg = new CallLeg();
        callerLeg.setLegType(CallLegType.CUSTOMER);
        when(callLegRepository.findByCallSessionIdAndLegTypeAndDeletedAtIsNull(
                SESSION_ID, CallLegType.CUSTOMER))
                .thenReturn(List.of(callerLeg));
        EslEvent e = event("CHANNEL_HANGUP", CALLER_UUID);
        e.addHeader("Hangup-Cause", "NORMAL_CLEARING");

        service.processEvent(e);

        verify(inboundCallEvents).onInboundCallerHangup(callerLeg, "NORMAL_CLEARING");
    }

    @Test
    @DisplayName("I6: ANSWER on an outbound (VOICE_BLAST) session with no attempt stays ignored")
    void outboundSessionAnswerIgnored() {
        CallSession outbound = new CallSession();
        outbound.setId(SESSION_ID);
        outbound.setCallType(CallType.VOICE_BLAST);
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CALLER_UUID))
                .thenReturn(Optional.of(outbound));
        when(attemptRepository.findByProviderCallIdAndDeletedAtIsNull(CALLER_UUID))
                .thenReturn(Optional.empty());

        assertThat(service.processEvent(event("CHANNEL_ANSWER", CALLER_UUID))).isFalse();
        verify(inboundCallEvents, never()).onInboundCallerAnswered(any());
    }

    @Test
    @DisplayName("I7: inbound boundary absent (Optional.empty) → inbound events fail closed")
    void missingBoundaryIgnored() {
        service = new EslEventService(attemptRepository, callSessionRepository,
                callLegRepository, voiceCapacity, mediaController,
                List.of(), Optional.empty(), dtmfResultService,
                Optional.empty(), Optional.empty(), Optional.empty());
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CALLER_UUID))
                .thenReturn(Optional.empty());
        EslEvent e = event("CHANNEL_CREATE", CALLER_UUID);
        e.addHeader("Call-Direction", "inbound");

        assertThat(service.processEvent(e)).isFalse();
    }

    @Test
    @DisplayName("I8: boundary exception is contained — the shared event loop never breaks")
    void boundaryExceptionContained() {
        when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(CALLER_UUID))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(inboundCallEvents).onInboundChannelCreated(any(), any(), any());
        EslEvent e = event("CHANNEL_CREATE", CALLER_UUID);
        e.addHeader("Call-Direction", "inbound");

        assertThat(service.processEvent(e)).isFalse(); // contained, not thrown
    }
}
