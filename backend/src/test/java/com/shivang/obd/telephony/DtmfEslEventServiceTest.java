package com.shivang.obd.telephony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shivang.obd.campaign.CallAttempt;
import com.shivang.obd.campaign.CallAttemptRepository;
import com.shivang.obd.campaign.CallAttemptStatus;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.media.DtmfCollectorTrigger;
import java.time.Instant;
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
 * VB-2 ESL boundary tests (spec §16.B): valid DTMF event, unknown channel
 * UUID, malformed event, digit forwarded exactly once per event. The
 * telephony layer must only correlate and forward — collection state and
 * idempotency live behind the {@link DtmfCollectorTrigger} boundary.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DtmfEslEventServiceTest {

    @Mock CallAttemptRepository attemptRepository;
    @Mock CallSessionRepository callSessionRepository;
    @Mock DtmfCollectorTrigger dtmfCollectorTrigger;

    EslEventService service;

    static final UUID TENANT = UUID.fromString("d2000000-0000-4000-8000-0000000000aa");
    static final String PROVIDER_CALL_ID = "fs-dtmf-uuid-1";

    CallAttempt attempt;
    CallSession session;

    @BeforeEach
    void setUp() {
        service = new EslEventService(attemptRepository, callSessionRepository,
                org.mockito.Mockito.mock(com.shivang.obd.voice.call.CallLegRepository.class),
                org.mockito.Mockito.mock(com.shivang.obd.voice.capacity.VoiceCapacityService.class),
                org.mockito.Mockito.mock(com.shivang.obd.voice.media.VoiceMediaController.class),
                java.util.List.of(),
                Optional.of(dtmfCollectorTrigger),
                org.mockito.Mockito.mock(com.shivang.obd.voice.dtmf.DtmfResultService.class),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());

        attempt = new CallAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setTenantId(TENANT);
        attempt.setStatus(CallAttemptStatus.IN_PROGRESS);
        attempt.setProviderCallId(PROVIDER_CALL_ID);

        session = new CallSession();
        session.setId(UUID.randomUUID());
        session.setTenantId(TENANT);
        session.setStatus(CallSessionStatus.WAITING_FOR_DTMF);
        session.setProviderCallId(PROVIDER_CALL_ID);
        session.setInitiatedAt(Instant.now());

        org.mockito.Mockito.when(attemptRepository.findByProviderCallIdAndDeletedAtIsNull(PROVIDER_CALL_ID))
                .thenReturn(Optional.of(attempt));
        org.mockito.Mockito.when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(PROVIDER_CALL_ID))
                .thenReturn(Optional.of(session));
    }

    private EslEvent dtmfEvent(String digit) {
        EslEvent event = new EslEvent("CHANNEL_DTMF");
        event.addHeader("Call-UUID", PROVIDER_CALL_ID);
        event.addHeader("DTMF-Digit", digit);
        return event;
    }

    @Test
    @DisplayName("valid DTMF event is forwarded to the collector boundary")
    void validDigitForwarded() {
        boolean processed = service.processEvent(dtmfEvent("5"));

        assertThat(processed).isTrue();
        verify(dtmfCollectorTrigger).onDtmfDigit(session.getId(), attempt.getId(), "5");
    }

    @Test
    @DisplayName("DTMF event for unknown channel UUID is safely ignored")
    void unknownChannelIgnored() {
        EslEvent event = new EslEvent("CHANNEL_DTMF");
        event.addHeader("Call-UUID", "no-such-channel");
        event.addHeader("DTMF-Digit", "5");

        boolean processed = service.processEvent(event);

        assertThat(processed).isFalse();
        verify(dtmfCollectorTrigger, never()).onDtmfDigit(any(), any(), any());
    }

    @Test
    @DisplayName("malformed DTMF event without digit is ignored")
    void malformedEventIgnored() {
        EslEvent event = new EslEvent("CHANNEL_DTMF");
        event.addHeader("Call-UUID", PROVIDER_CALL_ID);
        // No DTMF-Digit header.

        boolean processed = service.processEvent(event);

        assertThat(processed).isFalse();
        verify(dtmfCollectorTrigger, never()).onDtmfDigit(any(), any(), any());
    }

    @Test
    @DisplayName("duplicate digit events are each forwarded (collection layer owns idempotency)")
    void duplicatesForwardedToBoundary() {
        service.processEvent(dtmfEvent("1"));
        service.processEvent(dtmfEvent("1"));

        verify(dtmfCollectorTrigger, org.mockito.Mockito.times(2))
                .onDtmfDigit(session.getId(), attempt.getId(), "1");
    }
}
