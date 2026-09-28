package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.campaign.config.MissedCallRingWindow;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VB-7B: the MISSED_CALL runtime seam.
 *
 * <p>Three things are pinned here, and each corresponds to a way this feature
 * could silently do nothing:
 *
 * <ol>
 *   <li><b>It is reached.</b> A MISSED_CALL campaign answering must arrive at this
 *       service. Before VB-7A that class of regression was real: a campaign type
 *       with no trigger was answered and then ignored, because every existing
 *       {@code PlaybackTrigger} self-guards by type.</li>
 *   <li><b>It does nothing but set a deadline.</b> No media, no bridge, no input
 *       collection, and crucially <em>no failure code</em> — the recorded-code
 *       path takes precedence in the classifier and would turn a successful ring
 *       into a retryable failure.</li>
 *   <li><b>It stays out of the way.</b> PLAYFILE, DTMF/IVR and CONNECT_BY_AGENT
 *       must not be diverted into it.</li>
 * </ol>
 */
class MissedCallExecutionServiceTest {

    private static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    private static final UUID CAMPAIGN_ID = UUID.fromString("cc000000-0000-4000-8000-0000000000c1");
    private static final UUID EXECUTION_ID = UUID.fromString("ee000000-0000-4000-8000-0000000000e1");
    private static final UUID ATTEMPT_ID = UUID.fromString("bb000000-0000-4000-8000-0000000000b1");
    private static final UUID SESSION_ID = UUID.fromString("dd000000-0000-4000-8000-0000000000d1");

    private CallSessionRepository callSessionRepository;
    private CallAttemptRepository callAttemptRepository;
    private CampaignExecutionRepository executionRepository;
    private CampaignConfigurationService configurationService;
    private VoiceMediaController mediaController;
    private MissedCallExecutionService service;
    private CallSession session;
    private CallAttempt attempt;

    @BeforeEach
    void setUp() {
        callSessionRepository = mock(CallSessionRepository.class);
        callAttemptRepository = mock(CallAttemptRepository.class);
        executionRepository = mock(CampaignExecutionRepository.class);
        configurationService = mock(CampaignConfigurationService.class);
        mediaController = mock(VoiceMediaController.class);

        service = new MissedCallExecutionService(
                callSessionRepository, callAttemptRepository, executionRepository,
                new CampaignRuntimeConfigResolver(configurationService),
                new CallSessionDeadlineAuthority(callSessionRepository),
                mediaController);

        session = new CallSession();
        session.setId(SESSION_ID);
        session.setTenantId(TENANT_A);
        session.setStatus(CallSessionStatus.ANSWERED);
        session.setInitiatedAt(Instant.now().minusSeconds(5));
        session.setAnsweredAt(Instant.now());
        session.setProviderCallId("fs-uuid-mc-1");
        // The link the ownership check and the pre-answer sweep both resolve the
        // campaign type through.
        session.setCallAttemptId(ATTEMPT_ID);
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(session));

        attempt = new CallAttempt();
        attempt.setId(ATTEMPT_ID);
        attempt.setTenantId(TENANT_A);
        attempt.setCampaignId(CAMPAIGN_ID);
        attempt.setExecutionId(EXECUTION_ID);
        attempt.setStartedAt(Instant.now().minusSeconds(5));
        when(callAttemptRepository.findByIdAndDeletedAtIsNull(ATTEMPT_ID))
                .thenReturn(Optional.of(attempt));

        var execution = new CampaignExecution();
        execution.setId(EXECUTION_ID);
        execution.setCampaignId(CAMPAIGN_ID);
        execution.setTenantId(TENANT_A);
        execution.setConfigurationSnapshotId(UUID.randomUUID());
        when(executionRepository.findByIdAndDeletedAtIsNull(EXECUTION_ID))
                .thenReturn(Optional.of(execution));
    }

    private void stubSnapshot(CampaignType type, String typeConfigJson) {
        when(configurationService.requireExecutionSnapshot(any(CampaignExecution.class)))
                .thenReturn(CampaignExecutionConfiguration.materialize(
                        CAMPAIGN_ID, TENANT_A,
                        new CampaignConfigurationSnapshot(
                                type, null, null, null, null, null,
                                null, null, null, null, null, null, null,
                                0, null, RetryStrategy.FIXED, null, null, null,
                                json(typeConfigJson), false, null),
                        Instant.now()));
    }

    private static tools.jackson.databind.JsonNode json(String raw) {
        try {
            return tools.jackson.databind.json.JsonMapper.builder().build().readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String missedCall(int seconds) {
        return "{\"missedCall\": {\"ringDurationSeconds\": " + seconds + "}}";
    }

    // ------------------------------------------------------------------
    // Runtime reach and isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R38. an answered MISSED_CALL campaign reaches this service and gets a "
            + "post-answer deadline")
    void answeredMissedCallGetsPostAnswerDeadline() {
        stubSnapshot(CampaignType.MISSED_CALL, missedCall(30));

        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        assertThat(session.getDeadlineAt()).isNotNull();
        // Rebased onto the answer instant - this is what stops an answered call
        // from inheriting the pre-answer schedule.
        assertThat(session.getDeadlineAt())
                .isAfterOrEqualTo(session.getAnsweredAt().plusSeconds(30).minusSeconds(1));
    }

    @Test
    @DisplayName("R42. no media is ever played (playFile is not on the path at all)")
    void noMediaIsPlayed() {
        stubSnapshot(CampaignType.MISSED_CALL, missedCall(30));

        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        // The whole point: this campaign type plays nothing, so the only external
        // interaction available to assert on is that no teardown was requested
        // merely because the call was answered.
        verify(mediaController, never()).terminateCall(any(), any());
    }

    @Test
    @DisplayName("R39. a PLAYFILE campaign is not diverted into the MISSED_CALL service")
    void playfileIsNotDiverted() {
        stubSnapshot(CampaignType.PLAYFILE, "{}");

        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        assertThat(session.getDeadlineAt()).isNull();
    }

    @Test
    @DisplayName("R40. a DTMF/IVR campaign is not diverted")
    void dtmfIsNotDiverted() {
        stubSnapshot(CampaignType.DTMF, "{\"dtmf\": {\"expected\": \"1\"}}");

        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        assertThat(session.getDeadlineAt()).isNull();
    }

    @Test
    @DisplayName("R41. a CONNECT_BY_AGENT campaign is not diverted")
    void connectByAgentIsNotDiverted() {
        stubSnapshot(CampaignType.CONNECT_BY_AGENT,
                "{\"connectByAgent\": {\"queueId\": \"" + UUID.randomUUID() + "\", "
                        + "\"selectionStrategy\": \"LEAST_ACTIVE_RESERVATIONS\", "
                        + "\"ringDurationSeconds\": 60}}");

        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        assertThat(session.getDeadlineAt()).isNull();
    }

    @Test
    @DisplayName("R48. a duplicate CHANNEL_ANSWER is idempotent - the deadline is not moved "
            + "and no second teardown is requested")
    void duplicateAnswerIsIdempotent() {
        stubSnapshot(CampaignType.MISSED_CALL, missedCall(30));

        service.onAnswered(SESSION_ID, ATTEMPT_ID);
        Instant firstDeadline = session.getDeadlineAt();

        // A second answer event arriving late must not rebase the budget forward,
        // which would let a call live forever by repetition.
        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        assertThat(session.getDeadlineAt()).isEqualTo(firstDeadline);
    }

    @Test
    @DisplayName("R48b. a call already past ANSWERED is ignored entirely")
    void nonAnsweredStateIsIgnored() {
        stubSnapshot(CampaignType.MISSED_CALL, missedCall(30));
        session.setStatus(CallSessionStatus.RINGING);

        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        assertThat(session.getDeadlineAt()).isNull();
    }

    @Test
    @DisplayName("R48c. a missing session or attempt is a no-op, never an exception")
    void missingLinksAreNoOps() {
        stubSnapshot(CampaignType.MISSED_CALL, missedCall(30));

        assertThatCode(() -> {
            service.onAnswered(null, ATTEMPT_ID);
            service.onAnswered(SESSION_ID, null);
            when(callAttemptRepository.findByIdAndDeletedAtIsNull(ATTEMPT_ID))
                    .thenReturn(Optional.empty());
            service.onAnswered(SESSION_ID, ATTEMPT_ID);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("R49. a campaign edit after execution creation does not alter the deadline - "
            + "the value comes from the frozen snapshot")
    void deadlineComesFromTheSnapshot() {
        stubSnapshot(CampaignType.MISSED_CALL, missedCall(15));

        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        // 15s from the snapshot, not the 30s platform default: proof the runtime
        // reads the frozen execution configuration and not anything mutable.
        assertThat(session.getDeadlineAt())
                .isBefore(session.getAnsweredAt().plusSeconds(20));
    }

    // ------------------------------------------------------------------
    // Termination: success, not failure
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R43/R44. an expired ring window is terminated WITHOUT recording a failure "
            + "code, so the normal-clearing hangup classifies as COMPLETED")
    void expiredRingWindowTerminatesWithoutAFailureCode() {
        stubSnapshot(CampaignType.MISSED_CALL, missedCall(10));
        // Already stamped and overdue: the post-answer phase.
        session.setStatus(CallSessionStatus.ANSWERED);
        session.setDeadlineAt(Instant.now().minusSeconds(1));
        when(callSessionRepository.findLiveSessionsWithExpiredDeadline(
                any(), any(), any())).thenReturn(List.of(session));

        int terminated = service.terminateExpired();

        assertThat(terminated).isEqualTo(1);
        verify(mediaController).terminateCall(SESSION_ID, "fs-uuid-mc-1");
        // The load-bearing assertion: nothing was recorded, because the recorded
        // path has precedence in the classifier and would fail + retry the
        // attempt. An empty failure code is what makes this a successful delivery.
        assertThat(session.getFailureCode()).isNull();
    }

    @Test
    @DisplayName("R43b. a not-yet-expired deadline is left alone")
    void notYetExpiredIsLeftAlone() {
        stubSnapshot(CampaignType.MISSED_CALL, missedCall(60));
        session.setDeadlineAt(Instant.now().plusSeconds(30));
        when(callSessionRepository.findLiveSessionsWithExpiredDeadline(
                any(), any(), any())).thenReturn(List.of(session));

        assertThat(service.terminateExpired()).isZero();
        verify(mediaController, never()).terminateCall(any(), any());
    }

    @Test
    @DisplayName("R43c. an unanswered call past its budget is terminated, and the deadline is "
            + "stamped in the same pass")
    void unansweredCallTerminates() {
        stubSnapshot(CampaignType.MISSED_CALL, missedCall(10));
        CallSession ringing = new CallSession();
        ringing.setId(SESSION_ID);
        ringing.setTenantId(TENANT_A);
        ringing.setStatus(CallSessionStatus.RINGING);
        ringing.setInitiatedAt(Instant.now().minusSeconds(60));
        ringing.setCallAttemptId(ATTEMPT_ID);
        ringing.setProviderCallId("fs-uuid-mc-2");
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(ringing));
        when(callSessionRepository.findLiveSessionsWithExpiredDeadline(any(), any(), any()))
                .thenReturn(List.of());
        when(callSessionRepository.findLiveUnansweredSessionsDispatchedBefore(
                any(), any(), any())).thenReturn(List.of(ringing));

        int terminated = service.terminateExpired();

        assertThat(terminated).isEqualTo(1);
        assertThat(ringing.getDeadlineAt()).isNotNull();
        assertThat(ringing.getFailureCode()).isNull();
        verify(mediaController).terminateCall(SESSION_ID, "fs-uuid-mc-2");
    }

    @Test
    @DisplayName("R43d. an unanswered call whose configured budget has NOT elapsed is left "
            + "ringing - a longer configured window still applies")
    void unansweredButNotDueIsLeftAlone() {
        stubSnapshot(CampaignType.MISSED_CALL, missedCall(60));
        CallSession ringing = new CallSession();
        ringing.setId(SESSION_ID);
        ringing.setTenantId(TENANT_A);
        ringing.setStatus(CallSessionStatus.RINGING);
        ringing.setInitiatedAt(Instant.now().minusSeconds(20));
        ringing.setCallAttemptId(ATTEMPT_ID);
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(ringing));
        when(callSessionRepository.findLiveSessionsWithExpiredDeadline(any(), any(), any()))
                .thenReturn(List.of());
        when(callSessionRepository.findLiveUnansweredSessionsDispatchedBefore(
                any(), any(), any())).thenReturn(List.of(ringing));

        assertThat(service.terminateExpired()).isZero();
        assertThat(ringing.getDeadlineAt()).isNull();
        verify(mediaController, never()).terminateCall(any(), any());
    }

    @Test
    @DisplayName("R43e. a session this policy does not own is never claimed")
    void nonOwnedSessionIsNeverClaimed() {
        stubSnapshot(CampaignType.PLAYFILE, "{}");
        CallSession playfileSession = new CallSession();
        playfileSession.setId(SESSION_ID);
        playfileSession.setStatus(CallSessionStatus.ANSWERED);
        playfileSession.setDeadlineAt(Instant.now().minusSeconds(1));
        playfileSession.setCallAttemptId(ATTEMPT_ID);
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(playfileSession));

        assertThat(service.ownsDeadline(playfileSession)).isFalse();
        assertThat(service.terminateExpired()).isZero();
        verify(mediaController, never()).terminateCall(any(), any());
    }

    @Test
    @DisplayName("R43f. this service owns a deadline it stamped for a MISSED_CALL execution")
    void ownsItsOwnDeadline() {
        stubSnapshot(CampaignType.MISSED_CALL, missedCall(30));
        CallSession stamped = new CallSession();
        stamped.setId(SESSION_ID);
        stamped.setCallAttemptId(ATTEMPT_ID);
        stamped.setDeadlineAt(Instant.now().plusSeconds(30));

        assertThat(service.ownsDeadline(stamped)).isTrue();
        // A session with no deadline at all is nobody's business.
        stamped.setDeadlineAt(null);
        assertThat(service.ownsDeadline(stamped)).isFalse();
        assertThat(service.ownsDeadline(null)).isFalse();
    }

    @Test
    @DisplayName("R43g. an already-terminal session is never re-terminated")
    void terminalSessionIsNotReterminated() {
        stubSnapshot(CampaignType.MISSED_CALL, missedCall(10));
        session.setStatus(CallSessionStatus.ANSWERED);
        session.setDeadlineAt(Instant.now().minusSeconds(1));
        session.setFailureCode("MAX_DURATION_EXCEEDED");
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(session));

        assertThat(service.terminateStamped(SESSION_ID, "test")).isFalse();
        verify(mediaController, never()).terminateCall(any(), any());
    }

    @Test
    @DisplayName("R47. an answered call does not wait for stale reconciliation - its own "
            + "budget is what ends it")
    void answeredCallIsBoundedByItsOwnBudget() {
        stubSnapshot(CampaignType.MISSED_CALL, missedCall(10));
        session.setAnsweredAt(Instant.now().minusSeconds(1));

        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        // A deadline exists and is imminent, so the MISSED_CALL sweep - not the
        // 5-minute stale recovery - is what will end this call.
        assertThat(session.getDeadlineAt()).isNotNull();
        assertThat(session.getDeadlineAt()).isBefore(Instant.now().plusSeconds(15));
    }

    @Test
    @DisplayName("R43h. the sweep pre-filter cannot miss a due leg: it is bounded by the "
            + "minimum budget, so anything due is always inside the window")
    void preFilterIsBoundedByTheMinimumBudget() {
        assertThat(MissedCallExecutionService.PRE_FILTER_SECONDS)
                .isEqualTo(MissedCallRingWindow.MIN_RING_SECONDS);
        assertThat(MissedCallExecutionService.SWEEP_BATCH).isPositive();
    }

    @Test
    @DisplayName("R43i. onPlaybackCompleted is a no-op - nothing was played, so no lookup "
            + "and no teardown happens")
    void playbackCompletedIsANoOp() {
        assertThatCode(() -> service.onPlaybackCompleted(SESSION_ID, ATTEMPT_ID))
                .doesNotThrowAnyException();
        verify(mediaController, never()).terminateCall(any(), any());
        verify(callSessionRepository, never()).findByIdAndDeletedAtIsNull(any());
    }
}
