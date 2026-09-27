package com.shivang.obd.campaign;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single authority for Voice Blast campaign-attempt daily safety
 * (VB-6D.3) — "may this contact be dispatched to again today?".
 *
 * <p><b>What a campaign attempt IS.</b> A <em>dispatch</em>: the moment the
 * dial is issued to the provider. That boundary is chosen deliberately and
 * answers each case the phase had to settle:
 *
 * <table border="1">
 *   <caption>Attempt lifecycle semantics</caption>
 *   <tr><th>Event</th><th>Consumes an attempt?</th><th>Why</th></tr>
 *   <tr><td>Compliance / DND / whitelist rejection</td><td><b>No</b></td>
 *       <td>No dial is issued; evaluated strictly before this service</td></tr>
 *   <tr><td>Invalid contact / snapshot / timezone</td><td><b>No</b></td>
 *       <td>Pre-dispatch; never reaches this service</td></tr>
 *   <tr><td>VB-6C daily DNID limit reached</td><td><b>No</b></td>
 *       <td>VB-6C is evaluated first and refuses the dispatch</td></tr>
 *   <tr><td>No capacity / gateway unavailable</td><td><b>No</b></td>
 *       <td>Evaluated before this service; the attempt is requeued with its
 *           attempt number intact</td></tr>
 *   <tr><td>Routing failure</td><td><b>No</b></td>
 *       <td>Evaluated before this service</td></tr>
 *   <tr><td>Dial issued, provider rejects synchronously</td>
 *       <td><b>Yes</b></td><td>A dial was issued; the number may have
 *           rung. Not consuming it would let a broken provider be dialled
 *           forever</td></tr>
 *   <tr><td>Dial issued, provider accepts (+OK)</td>
 *       <td><b>Yes</b></td><td>As above</td></tr>
 *   <tr><td>Provider accepts then the call fails/NO_ANSWER</td>
 *       <td><b>Yes</b></td><td>The attempt already happened</td></tr>
 * </table>
 *
 * <p><b>Why dispatch and not acceptance.</b> This control exists to stop
 * unbounded redial of a contact. A dial that the provider rejected is still
 * a ring attempt from the platform's point of view, and if rejections were
 * free then a permanently-broken number would be retried without limit —
 * exactly the failure mode this ceiling is meant to prevent. Contrast
 * {@link DailyDialLimitService}, which counts provider <em>acceptance</em>
 * because it budgets real subscriber connections. The two are different
 * questions and are deliberately not merged.
 *
 * <p><b>Scope and key.</b> {@code (tenant_id, contact_id, usage_date)} —
 * no campaign, no DID. So the ceiling is shared by every Voice Blast
 * campaign of the tenant for that contact that day, which is what prevents
 * "campaign A three times, campaign B three times, campaign C three times"
 * from bypassing it. A campaign-specific override narrows the ceiling for
 * that campaign's own executions only; it can never widen it.
 *
 * <p><b>Day boundary.</b> The {@code usageDate} is resolved by
 * {@link DailyDialLimitService#resolveUsageDate} from the execution
 * snapshot's IANA timezone — deliberately the <em>same</em> seam VB-6C uses,
 * so both daily controls roll over at exactly the same instant and there is
 * only one authority for "what day is it".
 *
 * <p><b>Reservation lifecycle: none, by design.</b> The increment happens in
 * the same transaction that issues the dial, so consumption is immediate and
 * cannot be stranded by a crash — simpler and safer than VB-6C's
 * reserve/confirm pair, which must hold a slot across the dial.
 */
@Service
public class DailyAttemptSafetyService {

    private static final Logger log = LoggerFactory.getLogger(DailyAttemptSafetyService.class);

    /**
     * Platform ceiling on campaign attempts per contact per day, shared
     * across all Voice Blast campaigns of a tenant.
     *
     * <p>Chosen to match the repository's own existing per-contact attempt
     * allowance — {@code retry_max_attempts} is bounded 0..10 by the V14
     * CHECK and by the DTO — so a contact can never be dialled more often in
     * a day than the platform already considers a sane number of attempts
     * for a single execution. It is a code constant, deliberately not
     * tenant-configurable: this is a platform safety floor, not a feature.
     */
    public static final int MAX_DAILY_ATTEMPTS_PER_CONTACT = 10;

    /** Lowest campaign-configurable ceiling. */
    public static final int MIN_DAILY_ATTEMPTS_PER_CONTACT = 1;

    /** Counter: dispatches admitted. Tagged with the bounded effective limit. */
    static final String METRIC_ADMITTED =
            "obd.campaign.voiceblast.dailyattempt.admitted";
    /** Counter: dispatches refused because the contact-day ceiling was reached. */
    static final String METRIC_LIMIT_REACHED =
            "obd.campaign.voiceblast.dailyattempt.limit.reached";

    private final VoiceBlastDailyAttemptRepository attemptRepository;
    private final DailyDialLimitService dialLimitService;
    private final MeterRegistry meterRegistry;

    public DailyAttemptSafetyService(VoiceBlastDailyAttemptRepository attemptRepository,
                                     DailyDialLimitService dialLimitService,
                                     MeterRegistry meterRegistry) {
        this.attemptRepository = attemptRepository;
        this.dialLimitService = dialLimitService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * The effective ceiling for a campaign: the campaign's own stricter
     * value when configured, otherwise the platform maximum. A campaign can
     * only narrow this — {@link #assertConfigurable} rejects anything above
     * the platform maximum, so the {@code min} here is a belt-and-braces
     * guarantee rather than the primary control.
     */
    public int effectiveLimit(Integer campaignDailyAttempts) {
        if (campaignDailyAttempts == null) {
            return MAX_DAILY_ATTEMPTS_PER_CONTACT;
        }
        return Math.min(campaignDailyAttempts, MAX_DAILY_ATTEMPTS_PER_CONTACT);
    }

    /**
     * Domain guard for the campaign-configured value. Null means "platform
     * default"; {@code 1..MAX} is a valid stricter ceiling; anything else
     * is rejected so a policy looser than the platform can never be stored
     * or frozen into a snapshot.
     */
    public static void assertConfigurable(Integer campaignDailyAttempts) {
        if (campaignDailyAttempts == null) {
            return;
        }
        if (campaignDailyAttempts < MIN_DAILY_ATTEMPTS_PER_CONTACT
                || campaignDailyAttempts > MAX_DAILY_ATTEMPTS_PER_CONTACT) {
            throw new com.shivang.obd.common.exception.BusinessException(
                    com.shivang.obd.common.api.error.CommonErrorCode.VALIDATION_ERROR,
                    "maxDailyAttempts must be between " + MIN_DAILY_ATTEMPTS_PER_CONTACT
                            + " and " + MAX_DAILY_ATTEMPTS_PER_CONTACT
                            + ", or omitted for the platform default");
        }
    }

    /**
     * Atomically admits one campaign attempt for the contact on the given
     * day, or refuses it when the ceiling is reached.
     *
     * <p>Runs in the caller's transaction so the consumption commits or rolls
     * back together with the dispatch decision that caused it.
     *
     * @param campaignDailyAttempts the campaign's configured ceiling, or null
     * @return whether the dispatch may proceed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AdmissionResult admit(UUID tenantId,
                                 UUID contactId,
                                 String snapshotTimezone,
                                 Integer campaignDailyAttempts) {
        LocalDate usageDate = dialLimitService.resolveUsageDate(snapshotTimezone);
        int effectiveLimit = effectiveLimit(campaignDailyAttempts);

        attemptRepository.insertBucketRow(tenantId, contactId, usageDate);
        int granted = attemptRepository.reserve(
                tenantId, contactId, usageDate, effectiveLimit);

        if (granted == 1) {
            counter(METRIC_ADMITTED, effectiveLimit).increment();
            return AdmissionResult.ADMITTED;
        }

        counter(METRIC_LIMIT_REACHED, effectiveLimit).increment();
        log.info("Voice Blast daily attempt limit reached for tenant={} contact={} date={} "
                        + "(effectiveLimit={})",
                tenantId, contactId, usageDate, effectiveLimit);
        return AdmissionResult.LIMIT_REACHED;
    }

    /** Attempts consumed so far for a contact-day. Verification seam. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public int usedAttempts(UUID tenantId, UUID contactId, LocalDate usageDate) {
        Integer count = attemptRepository.currentCount(tenantId, contactId, usageDate);
        return count == null ? 0 : count;
    }

    private Counter counter(String name, int effectiveLimit) {
        return Counter.builder(name)
                .tag("effectiveLimit", String.valueOf(effectiveLimit))
                .register(meterRegistry);
    }

    /**
     * Outcome of a daily attempt admission.
     *
     * <p>Named in full rather than reusing {@link DailyDialLimitService.AdmissionResult},
     * because the two answer different questions and a reader must never have
     * to guess which one a given call site consulted.
     */
    public enum AdmissionResult { ADMITTED, LIMIT_REACHED }
}
