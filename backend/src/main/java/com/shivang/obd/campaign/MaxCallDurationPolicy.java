package com.shivang.obd.campaign;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;

/**
 * The single authority for maximum call duration (VB-6E).
 *
 * <h2>What this bounds</h2>
 *
 * <p>The <b>maximum lifetime of an established outbound call session</b>,
 * measured from the moment the provider reports the channel answered. It is
 * explicitly <em>not</em>:
 *
 * <ul>
 *   <li>a ring timeout — the provider owns how long it rings, and the
 *       resulting NO_ANSWER already classifies correctly;</li>
 *   <li>a provider connection timeout — that is a socket concern, owned by
 *       {@code FreeSwitchProperties};</li>
 *   <li>a playback length — the audio asset's own duration is metadata and is
 *       deliberately not used to bound a call;</li>
 *   <li>a total-attempt or retry budget — those are VB-6C and VB-6D.</li>
 * </ul>
 *
 * <h2>Why an application-side deadline</h2>
 *
 * <p>FreeSWITCH can enforce this itself with an {@code absolute_timeout}
 * channel variable, and that was considered and deliberately rejected. A
 * provider-side timer terminates the channel without recording intent, and
 * FreeSWITCH reports such a termination with a normal-clearing cause — which
 * this platform classifies as a <em>successful</em> completion (see
 * {@code HangupCauseMapper.isNormalClearing}). A provider-side timeout would
 * therefore be indistinguishable from a callee who simply hung up after the
 * blast was delivered, and a timed-out call would be recorded COMPLETED.
 *
 * <p>Instead the deadline is computed and persisted on the session
 * ({@code call_sessions.deadline_at}) and enforced by the existing
 * reconciliation pattern: the sweeper records
 * {@link CallFailureCode#MAX_DURATION_EXCEEDED} on the session and then asks
 * the media boundary to terminate the channel. The resulting CHANNEL_HANGUP
 * then picks the recorded failure up through the same
 * {@code session.getFailureCode()} precedence that
 * {@code PLAYBACK_CONFIG_INVALID} already uses. That reuses the established
 * mechanism, is idempotent, and — critically — cannot be confused with a
 * legitimate normal release.
 *
 * <h2>Configuration</h2>
 *
 * <p>Null means "platform default", preserving the convention VB-6C.2 and
 * VB-6D.3 established for {@code dailyDialLimit} and
 * {@code maxDailyAttempts}: a campaign may narrow the ceiling, and the bound is
 * asserted in three places (DTO constraint, this guard, and the V52
 * {@code CHECK}) so an out-of-range value is unrepresentable.
 *
 * <h2>Not a Spring bean</h2>
 *
 * <p>This authority has no collaborators and holds no state, so it is a plain
 * final utility rather than a {@code @Service}. Making it a bean would force
 * CGLIB to subclass a type with no visible constructor for no benefit, and
 * would add a bean whose only purpose is static dispatch.
 */
public final class MaxCallDurationPolicy {

    /** Platform default: 300 seconds (5 minutes). */
    public static final int DEFAULT_MAX_CALL_DURATION_SECONDS = 300;

    /** Lowest configurable value: 1 second. */
    public static final int MIN_MAX_CALL_DURATION_SECONDS = 1;

    /** Highest configurable value: 3600 seconds (1 hour). */
    public static final int MAX_MAX_CALL_DURATION_SECONDS = 3600;

    private MaxCallDurationPolicy() {
        // Not instantiable: the constants and static methods are the API.
    }

    /**
     * The effective duration: the campaign's own value when configured,
     * otherwise the platform default.
     */
    public static int effectiveSeconds(Integer configuredSeconds) {
        if (configuredSeconds == null) {
            return DEFAULT_MAX_CALL_DURATION_SECONDS;
        }
        return clamp(configuredSeconds);
    }

    /**
     * Domain guard for the configured value. Null is valid (platform default);
     * 1..3600 is valid; anything else is a validation error.
     */
    public static void assertConfigurable(Integer configuredSeconds) {
        if (configuredSeconds == null) {
            return;
        }
        if (configuredSeconds < MIN_MAX_CALL_DURATION_SECONDS
                || configuredSeconds > MAX_MAX_CALL_DURATION_SECONDS) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR,
                    "maxCallDurationSeconds must be between " + MIN_MAX_CALL_DURATION_SECONDS
                            + " and " + MAX_MAX_CALL_DURATION_SECONDS
                            + ", or omitted for the platform default of "
                            + DEFAULT_MAX_CALL_DURATION_SECONDS);
        }
    }

    private static int clamp(int seconds) {
        return Math.max(MIN_MAX_CALL_DURATION_SECONDS,
                Math.min(MAX_MAX_CALL_DURATION_SECONDS, seconds));
    }
}
