package com.shivang.obd.campaign;

import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.HangupCauseMapper;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * VB-8F — recovery of a real telephony outcome for a campaign dispatch whose
 * database write was lost.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>Since VB-8D the dispatch is two committed transactions: the claim
 * ({@code QUEUED -> IN_PROGRESS}) commits first, then the transaction that
 * writes the {@code CallSession}, the {@code CallLeg} and the
 * {@code providerCallId}. If the process dies between FreeSWITCH accepting the
 * originate and that second transaction committing, the telephony call
 * <em>really happened</em> while the platform has no row linking it to the
 * attempt. The eventual {@code CHANNEL_HANGUP} then correlates to nothing and
 * the attempt's true outcome is lost — until VB-8E's orphan sweep eventually
 * settles it as {@code CANCELLED} with the outcome explicitly unknown.
 *
 * <h2>What makes recovery safe</h2>
 *
 * <p>The campaign dialer pins {@code origination_uuid} to the attempt id, so the
 * channel's {@code Unique-ID} <em>is</em> the attempt id, from the very first
 * event — before any of our rows exist. The caller
 * ({@code EslEventService}) establishes that this event belongs to a channel we
 * originated; this class then requires the persisted attempt to look exactly
 * like a lost dispatch before it settles anything. Every check fails closed,
 * and all of them are re-evaluated here rather than trusted from the caller,
 * because the authoritative statement "this attempt is still claimed and has no
 * session" is a database fact, not something an event can assert.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <ul>
 *   <li><b>No session or leg is fabricated.</b> A {@code CallSession} is a
 *       telephony record the platform genuinely does not have; inventing one to
 *       satisfy a lookup would put a false call record into reporting. The
 *       attempt is settled directly, which is the smallest true state change.</li>
 *   <li><b>No capacity release.</b> The allocation was taken in the transaction
 *       that rolled back, so there is nothing held and nothing to release.</li>
 *   <li><b>No retry decision.</b> A non-clean hangup leaves the attempt
 *       {@code FAILED}, and the campaign's own {@link RetryPolicyService} then
 *       decides on the next scheduler tick exactly as it does for every other
 *       dispatched failure. This class never schedules anything.</li>
 *   <li><b>No terminal resurrection.</b> Only an {@code IN_PROGRESS} attempt is
 *       eligible, so a duplicate or late hangup cannot reopen a settled call.</li>
 * </ul>
 *
 * <p>Idempotent by construction: the first settlement moves the attempt out of
 * {@code IN_PROGRESS}, and every later event fails the eligibility check.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrphanedDispatchRecovery {

    private final CallAttemptRepository attemptRepository;
    private final CallSessionRepository callSessionRepository;
    private final CampaignExecutionRepository executionRepository;
    private final CampaignRuntimeConfigResolver runtimeConfigResolver;
    private final DailyDialLimitService dailyDialLimitService;

    /**
     * Settles one attempt from an authoritative FreeSWITCH hangup.
     *
     * <p>Called only after the primary {@code providerCallId} correlation has
     * definitively failed and the caller has proven the event describes a
     * channel this platform originated.
     *
     * @param attemptId     the attempt id, taken from the channel's pinned
     *                      {@code origination_uuid}
     * @param channelUuid   the same identity, as FreeSWITCH reported it on the
     *                      event
     * @param hangupCause   FreeSWITCH's {@code Hangup-Cause}, mapped through the
     *                      one canonical {@link HangupCauseMapper}
     * @return {@code true} when this call settled the attempt; {@code false} when
     *         the attempt was not an eligible lost dispatch and nothing was
     *         written
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean recover(UUID attemptId, String channelUuid, String hangupCause) {
        CallAttempt attempt = attemptRepository.findByIdAndDeletedAtIsNull(attemptId).orElse(null);
        if (attempt == null) {
            // The pinned UUID is not one of our attempts. For every channel this
            // platform originates without an attempt - the agent leg pins a
            // random UUID - this is the normal, expected outcome.
            log.debug("Recovery skipped: channel {} matches no call attempt", channelUuid);
            return false;
        }

        // Terminal or not yet claimed: nothing to recover. This is also the
        // idempotency guard for a duplicate hangup.
        if (attempt.getStatus() != CallAttemptStatus.IN_PROGRESS) {
            log.debug("Recovery skipped: attempt {} is {}, not an unsettled dispatch",
                    attemptId, attempt.getStatus());
            return false;
        }

        // A session exists, so the dispatch transaction did commit and the
        // primary correlation path owns this attempt. Settling it here would
        // race that path and double-count. Fail closed.
        if (callSessionRepository
                .findByCallAttemptIdAndDeletedAtIsNull(attemptId).isPresent()) {
            log.warn("Recovery skipped: attempt {} already has a call session; "
                    + "primary correlation owns it", attemptId);
            return false;
        }

        // The VB-6C ledger is keyed by tenant + contact + actual route DID +
        // calendar day. Without all three the acceptance cannot be recorded
        // against the right bucket, so do not settle on a guess.
        if (attempt.getTenantId() == null
                || attempt.getContactId() == null
                || attempt.getDidId() == null) {
            log.warn("Recovery skipped: attempt {} lacks tenant/contact/DID identity",
                    attemptId);
            return false;
        }

        // Canonical mapping, the same one the live hangup path uses. No second
        // hangup taxonomy is introduced here.
        boolean success = HangupCauseMapper.isNormalClearing(hangupCause);
        attempt.setStatus(success ? CallAttemptStatus.COMPLETED : CallAttemptStatus.FAILED);
        attempt.setFailureCode(success ? null : HangupCauseMapper.toFailureCode(hangupCause));
        attempt.setFailureReason(success ? null
                : "Recovered from FreeSWITCH CHANNEL_HANGUP (dispatch write was lost): "
                        + hangupCause);
        attempt.setCompletedAt(Instant.now());
        // Recovering the identity closes the correlation gap permanently: any
        // later event for this channel now resolves through the primary path,
        // where the terminal-state guard makes it a no-op.
        attempt.setProviderCallId(channelUuid);
        attemptRepository.saveAndFlush(attempt);

        log.info("Recovered lost campaign dispatch {} (channel={}, hangupCause={}, outcome={}) "
                        + "- the real telephony outcome is now recorded on the attempt",
                attemptId, channelUuid, hangupCause, attempt.getStatus());

        recordAcceptance(attempt);
        return true;
    }

    /**
     * Records the provider acceptance that the rolled-back transaction lost,
     * <b>and reconciles the VB-6C bucket for it</b>.
     *
     * <p>A {@code CHANNEL_HANGUP} on the channel this platform pinned is
     * stronger evidence of provider acceptance than the {@code +OK} the dial
     * path settles on, so the evidence is sufficient — this is not a guess.
     *
     * <p>Every component of the VB-6C key is read from persisted application
     * context and never from the event: the attempt supplies tenant, contact and
     * the actual route DID frozen into it at claim time, and the usage day is
     * computed from the same frozen snapshot the dial path used, so it cannot
     * drift.
     *
     * <p>VB-8G: this replaces the plain {@code confirmAccepted} call, which could
     * only record the ledger row. Because the pre-dial reservation was rolled
     * back with the crashed transaction, its hold-guarded increment matched no
     * row and the bucket stayed one behind reality — permitting one extra dial
     * per day against the provider-accepted limit. Reconciliation records the
     * usage regardless of the limit, and is exactly-once per attempt.
     *
     * <p><b>Deliberately non-fatal.</b> The observed telephony outcome is real
     * and must never be discarded because an auxiliary ledger write could not be
     * completed; anything unexpected is logged loudly rather than guessed at.
     */
    private void recordAcceptance(CallAttempt attempt) {
        try {
            var execution = executionRepository
                    .findByIdAndDeletedAtIsNull(attempt.getExecutionId()).orElse(null);
            if (execution == null) {
                log.warn("Recovered acceptance not reconciled for attempt {}: execution {} "
                        + "absent, so the usage day cannot be determined",
                        attempt.getId(), attempt.getExecutionId());
                return;
            }
            var config = runtimeConfigResolver.resolve(execution);
            var schedule = config.schedule();
            // Same computation as OutboundDialService, from the same frozen
            // snapshot, so the usage day cannot drift.
            var usageDate = dailyDialLimitService.resolveUsageDate(
                    schedule != null ? schedule.getTimezone() : null);
            dailyDialLimitService.reconcileRecoveredAcceptance(
                    attempt.getTenantId(), attempt.getId(), attempt.getContactId(),
                    attempt.getDidId(), usageDate, attempt.getProviderCallId());
        } catch (RuntimeException ledgerFailure) {
            log.error("Attempt {} was settled from a real hangup, but its provider-accepted "
                            + "usage could NOT be reconciled; the VB-6C bucket is now short by "
                            + "one real dial for today and must be corrected manually. "
                            + "Cause: {}", attempt.getId(), ledgerFailure.getMessage());
        }
    }
}
