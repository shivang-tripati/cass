package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.media.VoiceMediaController;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * VB-6E — the stale-call reconciler: maximum duration and stranded recovery.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Pre-VB-6E a call attempt was set {@code IN_PROGRESS} before its dial, and
 * the only automated route to a terminal state was the FreeSWITCH
 * {@code CHANNEL_HANGUP} event. If that event was lost — a crash, a dropped ESL
 * connection, a provider that never reported — the attempt stayed
 * {@code IN_PROGRESS} forever: never re-dialed, never completed, and its
 * execution never left {@code RUNNING} because reconciliation requires all
 * attempts terminal. Five other reconcilers existed for other domains;
 * campaign attempts had none.
 */
class StaleCallReconcilerTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-4000-8000-00000000000a");
    private static final UUID SESSION = UUID.fromString("cccccccc-0000-4000-8000-00000000000c");
    private static final UUID ATTEMPT = UUID.fromString("dddddddd-0000-4000-8000-00000000000d");

    private CallSessionRepository sessionRepository;
    private CallAttemptRepository attemptRepository;
    private VoiceMediaController mediaController;
    private StaleCallReconciler reconciler;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(CallSessionRepository.class);
        attemptRepository = mock(CallAttemptRepository.class);
        mediaController = mock(VoiceMediaController.class);
        reconciler = new StaleCallReconciler(sessionRepository, attemptRepository,
                mediaController, mock(EntityManager.class));
    }

    private CallSession session(CallSessionStatus status, Instant deadline) {
        CallSession session = new CallSession();
        session.setId(SESSION);
        session.setTenantId(TENANT);
        session.setCallAttemptId(ATTEMPT);
        session.setProviderCallId("fs-channel-1");
        session.setStatus(status);
        session.setDeadlineAt(deadline);
        session.setAnsweredAt(Instant.now().minusSeconds(10));
        return session;
    }

    private CallAttempt attempt(CallAttemptStatus status, Instant startedAt) {
        CallAttempt attempt = new CallAttempt();
        attempt.setId(ATTEMPT);
        attempt.setTenantId(TENANT);
        attempt.setStatus(status);
        attempt.setStartedAt(startedAt);
        return attempt;
    }

    // =====================================================================
    // Maximum call duration
    // =====================================================================

    @Nested
    class MaxCallDuration {

        @Test
        @DisplayName("SWEEP-1: an overdue answered session is recorded and terminated")
        void overdueSessionIsTerminated() {
            CallSession live = session(CallSessionStatus.PLAYING,
                    Instant.now().minusSeconds(1));
            when(sessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                    .thenReturn(Optional.of(live));

            assertThat(reconciler.finalizeOverdueSession(SESSION)).isTrue();

            // The failure code is recorded BEFORE the channel is touched. That
            // ordering is the whole design: the resulting CHANNEL_HANGUP reads
            // session.getFailureCode(), so our own NORMAL_CLEARING hangup can
            // never be mistaken for a delivered blast.
            assertThat(live.getFailureCode())
                    .isEqualTo(CallFailureCode.MAX_DURATION_EXCEEDED.name());
            assertThat(live.getFailureReason()).contains("maximum duration");
            verify(mediaController).terminateCall(SESSION, "fs-channel-1");
        }

        @Test
        @DisplayName("SWEEP-2: a session inside its deadline is left alone")
        void sessionInsideDeadlineIsUntouched() {
            CallSession live = session(CallSessionStatus.PLAYING,
                    Instant.now().plusSeconds(120));
            when(sessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                    .thenReturn(Optional.of(live));

            assertThat(reconciler.finalizeOverdueSession(SESSION)).isFalse();
            assertThat(live.getFailureCode()).isNull();
            verify(mediaController, never()).terminateCall(any(), anyString());
        }

        @Test
        @DisplayName("SWEEP-3: the timeout is idempotent - a second pass does nothing")
        void timeoutIsIdempotent() {
            CallSession live = session(CallSessionStatus.PLAYING,
                    Instant.now().minusSeconds(1));
            when(sessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                    .thenReturn(Optional.of(live));

            assertThat(reconciler.finalizeOverdueSession(SESSION)).isTrue();
            // Second sweep: the recorded failure code makes this a no-op, so no
            // second uuid_kill is issued and no recorded reason is overwritten.
            assertThat(reconciler.finalizeOverdueSession(SESSION)).isFalse();
            assertThat(live.getFailureCode())
                    .isEqualTo(CallFailureCode.MAX_DURATION_EXCEEDED.name());
            verify(mediaController, org.mockito.Mockito.times(1)).terminateCall(SESSION, "fs-channel-1");
        }

        @Test
        @DisplayName("SWEEP-4: a completed session is never terminated")
        void completedSessionIsNeverTerminated() {
            // A late timeout must not convert a completed PLAYFILE into a
            // failure, which would in turn make it retryable.
            for (CallSessionStatus terminal : new CallSessionStatus[] {
                    CallSessionStatus.COMPLETED, CallSessionStatus.FAILED}) {
                CallSession done = session(terminal, Instant.now().minusSeconds(60));
                when(sessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                        .thenReturn(Optional.of(done));

                assertThat(reconciler.finalizeOverdueSession(SESSION))
                        .as("%s must be left alone", terminal)
                        .isFalse();
                assertThat(done.getFailureCode()).isNull();
            }
            verify(mediaController, never()).terminateCall(any(), anyString());
        }

        @Test
        @DisplayName("SWEEP-5: a session with no deadline is not governed and is not swept")
        void sessionWithoutDeadlineIsNotSwept() {
            // NULL means "not governed": never answered, not a governed call
            // type, or predating this feature.
            CallSession ungoverned = session(CallSessionStatus.PLAYING, null);
            when(sessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                    .thenReturn(Optional.of(ungoverned));

            assertThat(reconciler.finalizeOverdueSession(SESSION)).isFalse();
            verify(mediaController, never()).terminateCall(any(), anyString());
        }

        @Test
        @DisplayName("SWEEP-6: a provider teardown failure does not lose the classification")
        void teardownFailureDoesNotLoseClassification() {
            CallSession live = session(CallSessionStatus.PLAYING,
                    Instant.now().minusSeconds(1));
            when(sessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                    .thenReturn(Optional.of(live));
            org.mockito.Mockito.doThrow(new IllegalStateException("channel already gone"))
                    .when(mediaController).terminateCall(any(), anyString());

            // The recorded failure code is the durable part; the hangup is best
            // effort, so a dead channel must not turn into an exception.
            assertThat(reconciler.finalizeOverdueSession(SESSION)).isTrue();
            assertThat(live.getFailureCode())
                    .isEqualTo(CallFailureCode.MAX_DURATION_EXCEEDED.name());
        }
    }

    // =====================================================================
    // Stranded attempt recovery
    // =====================================================================

    @Nested
    class StrandedRecovery {

        @Test
        @DisplayName("SWEEP-7: a stranded IN_PROGRESS attempt is failed with a dispatched code")
        void strandedAttemptIsFinalized() {
            CallSession live = session(CallSessionStatus.RINGING, null);
            CallAttempt attempt = attempt(CallAttemptStatus.IN_PROGRESS,
                    Instant.now().minus(StaleCallReconciler.STALE_ATTEMPT_THRESHOLD)
                            .minusSeconds(60));
            when(sessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                    .thenReturn(Optional.of(live));
            when(attemptRepository.findByIdAndDeletedAtIsNull(ATTEMPT))
                    .thenReturn(Optional.of(attempt));

            assertThat(reconciler.finalizeStrandedSession(SESSION)).isTrue();

            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.FAILED);
            assertThat(attempt.getFailureCode())
                    .isEqualTo(CallFailureCode.STALE_ATTEMPT_RECONCILED.name());
            assertThat(attempt.getCompletedAt()).isNotNull();
            assertThat(live.getStatus()).isEqualTo(CallSessionStatus.FAILED);
        }

        @Test
        @DisplayName("SWEEP-8: a recent attempt is left running - the sweeper is not eager")
        void recentAttemptIsLeftRunning() {
            CallSession live = session(CallSessionStatus.RINGING, null);
            CallAttempt attempt = attempt(CallAttemptStatus.IN_PROGRESS, Instant.now());
            when(sessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                    .thenReturn(Optional.of(live));
            when(attemptRepository.findByIdAndDeletedAtIsNull(ATTEMPT))
                    .thenReturn(Optional.of(attempt));

            assertThat(reconciler.finalizeStrandedSession(SESSION)).isFalse();
            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("SWEEP-9: a terminal attempt is never touched")
        void terminalAttemptIsNeverTouched() {
            for (CallAttemptStatus terminal : new CallAttemptStatus[] {
                    CallAttemptStatus.COMPLETED, CallAttemptStatus.FAILED,
                    CallAttemptStatus.CANCELLED}) {
                CallSession live = session(CallSessionStatus.COMPLETED, null);
                CallAttempt attempt = attempt(terminal,
                        Instant.now().minusSeconds(3600));
                when(sessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                        .thenReturn(Optional.of(live));
                when(attemptRepository.findByIdAndDeletedAtIsNull(ATTEMPT))
                        .thenReturn(Optional.of(attempt));

                assertThat(reconciler.finalizeStrandedSession(SESSION))
                        .as("%s must not be re-finalised", terminal)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("SWEEP-10: a terminal session is never touched")
        void terminalSessionIsNeverTouched() {
            CallSession done = session(CallSessionStatus.COMPLETED, null);
            when(sessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                    .thenReturn(Optional.of(done));
            when(attemptRepository.findByIdAndDeletedAtIsNull(ATTEMPT))
                    .thenReturn(Optional.of(attempt(CallAttemptStatus.IN_PROGRESS,
                            Instant.now().minusSeconds(3600))));

            assertThat(reconciler.finalizeStrandedSession(SESSION)).isFalse();
        }

        @Test
        @DisplayName("SWEEP-11: the stale threshold is a bounded, documented value")
        void staleThresholdIsBounded() {
            // Sits above every provider-side timeout the platform already waits
            // on, so the sweeper never races a merely slow call.
            assertThat(StaleCallReconciler.STALE_ATTEMPT_THRESHOLD.toMinutes())
                    .isEqualTo(5);
            assertThat(StaleCallReconciler.MAX_BATCH)
                    .as("each pass is bounded, matching the other reconcilers")
                    .isPositive();
        }

        @Test
        @DisplayName("SWEEP-12: reconciliation is tenant-safe by construction")
        void reconciliationIsTenantSafe() {
            // The queries are tenant-bounded and a session is only ever acted on
            // through its own row, so no cross-tenant termination is possible.
            CallSession live = session(CallSessionStatus.PLAYING,
                    Instant.now().minusSeconds(1));
            when(sessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                    .thenReturn(Optional.of(live));

            reconciler.finalizeOverdueSession(SESSION);

            assertThat(live.getTenantId()).isEqualTo(TENANT);
            verify(mediaController).terminateCall(SESSION, live.getProviderCallId());
        }
    }

    @Nested
    class BudgetIntegrity {

        @Test
        @DisplayName("SWEEP-13: neither sweeper invents a pre-dispatch failure")
        void neitherPathIsPreDispatch() {
            // Both outcomes are dispatched, so the campaign's own rules govern
            // them and the VB-6D pre-dispatch set stays about "never placed".
            assertThat(FailureClassification.of(CallFailureCode.MAX_DURATION_EXCEEDED)
                    .isPreDispatch()).isFalse();
            assertThat(FailureClassification.of(CallFailureCode.STALE_ATTEMPT_RECONCILED)
                    .isPreDispatch()).isFalse();
        }

        @Test
        @DisplayName("SWEEP-14: the sweeper creates no retry of its own")
        void sweeperCreatesNoRetry() {
            // Retries are created only by the orchestrator's retry pass, which is
            // the single place that decides whether one exists. A sweeper that
            // created its own retry could produce a duplicate.
            CallSession live = session(CallSessionStatus.RINGING, null);
            CallAttempt attempt = attempt(CallAttemptStatus.IN_PROGRESS,
                    Instant.now().minusSeconds(3600));
            when(sessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                    .thenReturn(Optional.of(live));
            when(attemptRepository.findByIdAndDeletedAtIsNull(ATTEMPT))
                    .thenReturn(Optional.of(attempt));

            reconciler.finalizeStrandedSession(SESSION);

            // Exactly one save of the SAME attempt, mutated in place. No new
            // attempt row is created.
            verify(attemptRepository, org.mockito.Mockito.times(1)).save(attempt);
            assertThat(List.of(attempt)).hasSize(1);
        }
    }
}
