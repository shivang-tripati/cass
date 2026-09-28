package com.shivang.obd.voice.acd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.voice.queue.QueueWaitingCall;
import com.shivang.obd.voice.queue.QueueWaitingCallRepository;
import com.shivang.obd.voice.queue.QueueWaitingCallStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * VB-7A: the outbound queue-assignment adapter.
 *
 * <p>This is the seam that lets an already-answered outbound call use the
 * existing ACD authority instead of a second selection path. The tests pin the
 * two properties that make that safe: every decision is <em>delegated</em> to
 * {@link AcdService} (never reimplemented), and the enrolled waiting-call row is
 * never left {@code WAITING}, so the inbound retry sweep can never pick up an
 * outbound call and dial it through the inbound path.
 */
class OutboundQueueAssignmentServiceTest {

    private static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    private static final UUID QUEUE_ID = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301");
    private static final UUID SESSION_ID = UUID.fromString("dd000000-0000-4000-8000-0000000000d1");
    private static final UUID AGENT_ID = UUID.fromString("dd000000-0000-4000-8000-0000000000a9");
    private static final UUID WAITING_CALL_ID =
            UUID.fromString("dd000000-0000-4000-8000-0000000000c9");

    private AcdService acdService;
    private QueueWaitingCallRepository waitingCallRepository;
    private OutboundQueueAssignmentService service;

    @BeforeEach
    void setUp() {
        acdService = mock(AcdService.class);
        waitingCallRepository = mock(QueueWaitingCallRepository.class);
        service = new OutboundQueueAssignmentService(acdService, waitingCallRepository);
        when(waitingCallRepository.findByCallSessionIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.empty());
        when(waitingCallRepository.save(any(QueueWaitingCall.class)))
                .thenAnswer(invocation -> {
                    QueueWaitingCall saved = invocation.getArgument(0);
                    saved.setId(WAITING_CALL_ID);
                    return saved;
                });
    }

    @Test
    @DisplayName("E18a. the whole decision is delegated to AcdService — no second selection path")
    void decisionIsDelegatedToAcd() {
        when(acdService.attemptAssignment(eq(TENANT_A), eq(QUEUE_ID), eq(WAITING_CALL_ID)))
                .thenReturn(AcdResult.assigned(QUEUE_ID, WAITING_CALL_ID, AGENT_ID,
                        UUID.randomUUID(), List.of()));

        var result = service.assignToQueue(TENANT_A, QUEUE_ID, SESSION_ID);

        assertThat(result.assigned()).isTrue();
        assertThat(result.agentId()).isEqualTo(AGENT_ID);
        verify(acdService, times(1)).attemptAssignment(TENANT_A, QUEUE_ID, WAITING_CALL_ID);
        // An assignment is left alone: it is not "removed" out from under ACD.
        verify(waitingCallRepository, never()).markWaitingRemovedForSession(any());
    }

    @Test
    @DisplayName("E18b. the call is enrolled in the canonical waiting-call model")
    void callIsEnrolledAsAWaitingCall() {
        when(acdService.attemptAssignment(eq(TENANT_A), eq(QUEUE_ID), eq(WAITING_CALL_ID)))
                .thenReturn(AcdResult.assigned(QUEUE_ID, WAITING_CALL_ID, AGENT_ID,
                        UUID.randomUUID(), List.of()));

        service.assignToQueue(TENANT_A, QUEUE_ID, SESSION_ID);

        ArgumentCaptor<QueueWaitingCall> enrolled =
                ArgumentCaptor.forClass(QueueWaitingCall.class);
        verify(waitingCallRepository).save(enrolled.capture());
        assertThat(enrolled.getValue().getTenantId()).isEqualTo(TENANT_A);
        assertThat(enrolled.getValue().getQueueId()).isEqualTo(QUEUE_ID);
        assertThat(enrolled.getValue().getCallSessionId()).isEqualTo(SESSION_ID);
        assertThat(enrolled.getValue().getStatus()).isEqualTo(QueueWaitingCallStatus.WAITING);
    }

    @Test
    @DisplayName("E18c. an existing row is reused, never duplicated (idempotent per call)")
    void existingWaitingCallIsReused() {
        QueueWaitingCall existing = new QueueWaitingCall();
        existing.setId(WAITING_CALL_ID);
        existing.setTenantId(TENANT_A);
        existing.setQueueId(QUEUE_ID);
        existing.setCallSessionId(SESSION_ID);
        existing.setStatus(QueueWaitingCallStatus.ASSIGNED);
        when(waitingCallRepository.findByCallSessionIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(existing));
        when(acdService.attemptAssignment(eq(TENANT_A), eq(QUEUE_ID), eq(WAITING_CALL_ID)))
                .thenReturn(AcdResult.alreadyAssigned(QUEUE_ID, WAITING_CALL_ID, AGENT_ID,
                        UUID.randomUUID()));

        var result = service.assignToQueue(TENANT_A, QUEUE_ID, SESSION_ID);

        assertThat(result.assigned()).isTrue();
        verify(waitingCallRepository, never()).save(any());
    }

    @Test
    @DisplayName("E18d. a declined assignment takes the row out of the waiting set, so the "
            + "inbound retry sweep never sees an outbound call")
    void declinedAssignmentIsNeverLeftWaiting() {
        when(acdService.attemptAssignment(eq(TENANT_A), eq(QUEUE_ID), eq(WAITING_CALL_ID)))
                .thenReturn(AcdResult.failed(AcdResult.AcdStatus.NO_ELIGIBLE_AGENT,
                        QUEUE_ID, WAITING_CALL_ID, AcdReasons.NO_ACTIVE_MEMBERS, List.of()));

        var result = service.assignToQueue(TENANT_A, QUEUE_ID, SESSION_ID);

        assertThat(result.assigned()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(AcdReasons.NO_ACTIVE_MEMBERS);
        verify(waitingCallRepository, times(1)).markWaitingRemovedForSession(SESSION_ID);
    }

    @Test
    @DisplayName("E18e. a queue that is not ACTIVE is declined by ACD and also takes the row out")
    void ineligibleQueueIsNeverLeftWaiting() {
        when(acdService.attemptAssignment(eq(TENANT_A), eq(QUEUE_ID), eq(WAITING_CALL_ID)))
                .thenReturn(AcdResult.failed(AcdResult.AcdStatus.QUEUE_NOT_ELIGIBLE,
                        QUEUE_ID, WAITING_CALL_ID, AcdReasons.QUEUE_NOT_ACTIVE, List.of()));

        var result = service.assignToQueue(TENANT_A, QUEUE_ID, SESSION_ID);

        assertThat(result.assigned()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(AcdReasons.QUEUE_NOT_ACTIVE);
        verify(waitingCallRepository).markWaitingRemovedForSession(SESSION_ID);
    }

    @Test
    @DisplayName("E18f. null arguments are declined without touching ACD or the repository")
    void nullArgumentsAreDeclined() {
        assertThat(service.assignToQueue(null, QUEUE_ID, SESSION_ID).assigned()).isFalse();
        assertThat(service.assignToQueue(TENANT_A, null, SESSION_ID).assigned()).isFalse();
        assertThat(service.assignToQueue(TENANT_A, QUEUE_ID, null).assigned()).isFalse();

        verify(acdService, never()).attemptAssignment(any(), any(), any());
        verify(waitingCallRepository, never()).save(any());
    }

    @Test
    @DisplayName("E18g. closing a finished call moves the row out of ASSIGNED")
    void closingQueuePresenceIsTerminal() {
        service.closeQueuePresence(SESSION_ID, true);
        verify(waitingCallRepository, times(1))
                .markAssignedTerminalForSession(SESSION_ID, "COMPLETED");

        service.closeQueuePresence(SESSION_ID, false);
        verify(waitingCallRepository, times(1))
                .markAssignedTerminalForSession(SESSION_ID, "REMOVED");

        service.closeQueuePresence(null, true);
        verify(waitingCallRepository, times(2)).markAssignedTerminalForSession(any(), any());
    }

    @Test
    @DisplayName("the maximum campaign ring window stays strictly below the ACD hold TTL, so "
            + "AgentConnectTimeoutScheduler is always the first to fire")
    void ringWindowIsBoundedBelowTheHoldTtl() {
        // Visible here because this test lives in the ACD package. If this ever
        // fails, an outbound ring deadline could land AFTER the hold expires and
        // the ACD sweep would release an agent that is still legitimately
        // ringing — two timeout authorities for one call.
        assertThat(AcdService.RESERVATION_TTL.getSeconds())
                .isGreaterThan(
                        com.shivang.obd.voice.agent.AgentRingWindow.MAX_RING_SECONDS);
    }
}
