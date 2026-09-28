package com.shivang.obd.voice.agent;

import java.util.UUID;

/**
 * Resolves the ring window a specific call session is held to (VB-7A), as a
 * port.
 *
 * <h2>Why this is an interface</h2>
 *
 * <p>The ring budget is <em>frozen campaign configuration</em>: it lives in the
 * execution snapshot's {@code type_config}, which only the {@code campaign}
 * module can read. {@link AgentConnectTimeoutScheduler} lives in {@code voice},
 * and the module graph is a DAG with {@code voice} as a leaf — a direct
 * {@code voice → campaign} reference would invert that and close a cycle.
 *
 * <p>This is the codebase's established answer to exactly this situation, used
 * three times already: {@code DtmfCollectorTrigger} and {@code PlaybackTrigger}
 * are interfaces in {@code voice} implemented by {@code campaign}, and VB-6F
 * added {@code IvrPromptChecker} for the same reason. The port keeps the frozen
 * configuration in its owning module while the scheduler stays in the voice
 * domain, and it keeps the enforcement in one place:
 * {@link AgentRingWindow} holds the bounds and the default, so no second
 * authority can appear.
 *
 * <h2>What the implementation must do</h2>
 *
 * <p>Read the call session's <em>execution snapshot</em> and return its
 * configured ring window. It must not read mutable campaign configuration, and
 * it must not fall back to the live campaign — that is the VB-6A snapshot rule.
 * A session with no CONNECT_BY_AGENT budget (a DTMF/IVR campaign whose terminal
 * action is {@code CONNECT_BY_AGENT}, or any non-campaign call) must return the
 * supplied platform default, which keeps every pre-VB-7A path byte-for-byte
 * identical.
 */
public interface AgentRingBudgetResolver {

    /**
     * The ring window, in seconds, that applies to the given call session.
     *
     * @param callSessionId          the call whose agent leg is ringing
     * @param platformDefaultSeconds the caller's fallback; returned whenever no
     *                                configured budget applies
     * @return a positive number of seconds; never null
     */
    int ringSecondsFor(UUID callSessionId, int platformDefaultSeconds);
}
