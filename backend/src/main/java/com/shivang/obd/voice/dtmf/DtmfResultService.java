package com.shivang.obd.voice.dtmf;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies terminal DTMF results to interactions (VB-2).
 * <p>
 * Separated from the campaign execution service so the timeout poller, the
 * DTMF event path and the hangup path all reuse the exact same idempotent
 * finalize logic. Terminalization uses an atomic conditional claim
 * ({@link DtmfInteractionRepository#claimTerminal}): a terminal result is
 * written exactly once, and duplicate events (digit after timeout, repeated
 * timeout scans) never overwrite or re-act.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DtmfResultService {

    private final DtmfInteractionRepository interactionRepository;

    /**
     * Attempts to move a COLLECTING interaction to a terminal result,
     * recording the final collected digits.
     *
     * @return the finalized interaction if this call won the claim;
     *         empty when the interaction was already terminal (duplicate
     *         event) or does not exist
     */
    @Transactional
    public Optional<DtmfInteraction> finalizeInteraction(
            UUID interactionId, DtmfResultType result, String reason, String collectedDigits) {
        int claimed = interactionRepository.claimTerminal(
                interactionId, result.name(), reason, Instant.now(), collectedDigits);
        if (claimed == 0) {
            return Optional.empty();
        }
        log.info("DTMF interaction {} finalized: {} ({})", interactionId, result, reason);
        return interactionRepository.findById(interactionId);
    }

    /**
     * Marks the interaction for a call session ABANDONED if it is still
     * collecting (call ended before a result was reached — e.g. remote
     * hangup while waiting). No-op when there is no interaction or it is
     * already terminal. Safe against duplicate hangups via the atomic claim.
     */
    @Transactional
    public void abandonIfCollecting(UUID callSessionId) {
        interactionRepository.findByCallSessionIdAndDeletedAtIsNull(callSessionId)
                .filter(i -> i.getResult() == DtmfResultType.COLLECTING)
                .ifPresent(i -> finalizeInteraction(
                        i.getId(), DtmfResultType.ABANDONED,
                        "Call ended before DTMF completion", i.getCollectedDigits()));
    }
}
