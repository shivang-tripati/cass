package com.shivang.obd.voice.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * VB-7A: the agent ring window, and the single authority that enforces it.
 *
 * <p>Before VB-7A the window was one platform constant, so a sweep applied one
 * cutoff to every leg. With a per-campaign budget that is no longer correct: a
 * leg with a 240s budget must survive a 60s-old sweep, and a leg with a 10s
 * budget must not. These tests pin both directions, plus the pre-VB-7A default
 * for every leg that has no configured budget.
 */
class AgentConnectTimeoutSchedulerTest {

    private static final UUID SESSION_ID = UUID.fromString("dd000000-0000-4000-8000-0000000000d1");

    private CallLegRepository callLegRepository;
    private AgentReservationService reservationService;
    private VoiceMediaController mediaController;
    private AgentRingBudgetResolver ringBudgetResolver;

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentRingBudgetResolver> resolverProvider =
            mock(ObjectProvider.class);

    @BeforeEach
    void setUp() {
        callLegRepository = mock(CallLegRepository.class);
        reservationService = mock(AgentReservationService.class);
        mediaController = mock(VoiceMediaController.class);
        ringBudgetResolver = mock(AgentRingBudgetResolver.class);

        when(resolverProvider.getIfAvailable()).thenReturn(ringBudgetResolver);
        when(ringBudgetResolver.ringSecondsFor(any(), any(Integer.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        when(callLegRepository.findByLegTypeAndStatusAndInitiatedAtBefore(
                eq(CallLegType.AGENT), eq(CallLegStatus.DIALING), any()))
                .thenReturn(List.of());
        when(callLegRepository.findByLegTypeAndStatusAndInitiatedAtBefore(
                eq(CallLegType.AGENT), eq(CallLegStatus.RINGING), any()))
                .thenReturn(List.of());
    }

    private AgentConnectTimeoutScheduler scheduler() {
        return new AgentConnectTimeoutScheduler(
                callLegRepository, mock(AgentReservationRepository.class),
                reservationService, mediaController, resolverProvider);
    }

    private CallLeg leg(String providerCallId, Instant initiatedAt) {
        CallLeg leg = new CallLeg();
        leg.setId(UUID.randomUUID());
        leg.setCallSessionId(SESSION_ID);
        leg.setLegType(CallLegType.AGENT);
        leg.setStatus(CallLegStatus.RINGING);
        leg.setProviderCallId(providerCallId);
        leg.setInitiatedAt(initiatedAt);
        return leg;
    }

    private void ringLeg(CallLeg leg) {
        when(callLegRepository.findByLegTypeAndStatusAndInitiatedAtBefore(
                eq(CallLegType.AGENT), eq(CallLegStatus.RINGING), any()))
                .thenReturn(List.of(leg));
    }

    private void dialLeg(CallLeg leg) {
        when(callLegRepository.findByLegTypeAndStatusAndInitiatedAtBefore(
                eq(CallLegType.AGENT), eq(CallLegStatus.DIALING), any()))
                .thenReturn(List.of(leg));
    }

    // === AgentRingWindow: the shared authority ===

    @Test
    @DisplayName("the pre-VB-7A platform default is retained unchanged")
    void platformDefaultIsUnchanged() {
        assertThat(AgentConnectTimeoutScheduler.CONNECT_TIMEOUT_SECONDS).isEqualTo(60);
        assertThat(AgentRingWindow.DEFAULT_RING_SECONDS).isEqualTo(60);
    }

    @Test
    @DisplayName("the ring window is bounded and the default sits inside that bound")
    void ringWindowBoundsAreCoherent() {
        assertThat(AgentRingWindow.MIN_RING_SECONDS)
                .isLessThan(AgentRingWindow.DEFAULT_RING_SECONDS);
        assertThat(AgentRingWindow.DEFAULT_RING_SECONDS)
                .isLessThan(AgentRingWindow.MAX_RING_SECONDS);
        // The maximum is deliberately below the ACD hold TTL (5 minutes) so the
        // ring deadline always arrives before the hold can expire and this
        // scheduler stays the only enforcer. Asserted against the TTL itself in
        // com.shivang.obd.voice.acd, where that constant is visible.
        assertThat(AgentRingWindow.MAX_RING_SECONDS).isLessThan(300);
    }

    @Test
    @DisplayName("isExpired compares the leg's own deadline")
    void isExpiredUsesTheLegsOwnDeadline() {
        Instant past = Instant.now().minusSeconds(100);
        assertThat(AgentRingWindow.isExpired(past, 60)).isTrue();
        assertThat(AgentRingWindow.isExpired(past, 240)).isFalse();
        assertThat(AgentRingWindow.isExpired(Instant.now(), 10)).isFalse();
        assertThat(AgentRingWindow.isExpired(null, 60)).isFalse();
    }

    // === per-leg budgets ===

    @Test
    @DisplayName("a leg with a long configured budget is NOT failed by an earlier sweep")
    void longBudgetLegSurvivesAnEarlySweep() {
        CallLeg leg = leg("uuid-long", Instant.now().minusSeconds(100));
        ringLeg(leg);
        when(ringBudgetResolver.ringSecondsFor(eq(SESSION_ID), any(Integer.class)))
                .thenReturn(AgentRingWindow.MAX_RING_SECONDS);

        scheduler().enforceAgentConnectTimeouts();

        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.RINGING);
        verify(reservationService, never()).releaseForCallSession(any(), anyString());
    }

    @Test
    @DisplayName("a leg with a short configured budget IS failed once its own deadline passes")
    void shortBudgetLegIsFailedOnTime() {
        CallLeg leg = leg("uuid-short", Instant.now().minusSeconds(100));
        ringLeg(leg);
        when(ringBudgetResolver.ringSecondsFor(eq(SESSION_ID), any(Integer.class)))
                .thenReturn(AgentRingWindow.MIN_RING_SECONDS);

        scheduler().enforceAgentConnectTimeouts();

        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.FAILED);
        assertThat(leg.getFailureCode()).isEqualTo("AGENT_NO_ANSWER");
        assertThat(leg.getFailureReason()).contains("10s");
        verify(reservationService).releaseForCallSession(SESSION_ID, ReleaseReasons.NO_ANSWER);
        verify(mediaController).terminateCall(SESSION_ID, "uuid-short");
    }

    @Test
    @DisplayName("a short-budget leg is left alone until its own deadline actually passes")
    void shortBudgetLegIsNotFailedEarly() {
        CallLeg leg = leg("uuid-early", Instant.now().minusSeconds(5));
        ringLeg(leg);
        when(ringBudgetResolver.ringSecondsFor(eq(SESSION_ID), any(Integer.class)))
                .thenReturn(AgentRingWindow.MIN_RING_SECONDS);

        scheduler().enforceAgentConnectTimeouts();

        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.RINGING);
    }

    @Test
    @DisplayName("a leg with no configured budget keeps the pre-VB-7A 60s behaviour")
    void unconfiguredLegKeepsTheDefault() {
        CallLeg leg = leg("uuid-default", Instant.now().minusSeconds(100));
        dialLeg(leg);

        scheduler().enforceAgentConnectTimeouts();

        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.FAILED);
        assertThat(leg.getFailureReason()).contains("60s");
    }

    @Test
    @DisplayName("a deployment with no ring-budget resolver enforces the platform default")
    void missingResolverFallsBackToTheDefault() {
        when(resolverProvider.getIfAvailable()).thenReturn(null);
        CallLeg leg = leg("uuid-noresolver", Instant.now().minusSeconds(100));
        ringLeg(leg);

        new AgentConnectTimeoutScheduler(
                callLegRepository, mock(AgentReservationRepository.class),
                reservationService, mediaController, resolverProvider)
                .enforceAgentConnectTimeouts();

        assertThat(leg.getStatus()).isEqualTo(CallLegStatus.FAILED);
        assertThat(leg.getFailureReason()).contains("60s");
    }

    @Test
    @DisplayName("a resolver that throws or returns nonsense degrades to the default instead of "
            +"failing a call in progress")
    void resolverFailureDegradesToTheDefault() {
        CallLeg throwing = leg("uuid-throwing", Instant.now().minusSeconds(100));
        when(ringBudgetResolver.ringSecondsFor(eq(SESSION_ID), any(Integer.class)))
                .thenThrow(new IllegalStateException("snapshot unavailable"));
        ringLeg(throwing);
        scheduler().enforceAgentConnectTimeouts();
        assertThat(throwing.getStatus()).isEqualTo(CallLegStatus.FAILED);
        assertThat(throwing.getFailureReason()).contains("60s");

        CallLeg nonsense = leg("uuid-nonsense", Instant.now().minusSeconds(100));
        when(ringBudgetResolver.ringSecondsFor(eq(SESSION_ID), any(Integer.class)))
                .thenReturn(-5);
        ringLeg(nonsense);
        scheduler().enforceAgentConnectTimeouts();
        assertThat(nonsense.getStatus()).isEqualTo(CallLegStatus.FAILED);
        assertThat(nonsense.getFailureReason()).contains("60s");
    }

    @Test
    @DisplayName("the sweep pre-filter looks back the MAXIMUM ring window, not the old 60s "
            + "constant, so a long-budget leg is never missed")
    void preFilterIsBoundedByTheMaximum() {
        Instant before = Instant.now();
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);

        scheduler().enforceAgentConnectTimeouts();

        verify(callLegRepository).findByLegTypeAndStatusAndInitiatedAtBefore(
                eq(CallLegType.AGENT), eq(CallLegStatus.DIALING), cutoff.capture());
        // The pre-VB-7A sweep used a 60s cutoff, which would have made a
        // 240s-budget leg invisible on every early pass. The cutoff must now
        // reach at least the maximum window back.
        assertThat(cutoff.getValue()).isBefore(
                before.minusSeconds(AgentRingWindow.MAX_RING_SECONDS - 30));
    }
}
