package com.shivang.obd.telephony;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * FreeSWITCH Event Socket Library (ESL) client — protocol-conformant
 * implementation (VB-6E).
 *
 * <h2>Why this class was rewritten</h2>
 *
 * <p>Every VB-6E audit finding about this class was a protocol defect, and
 * every one of them was invisible to the test suite because the tests either
 * mocked this class or hand-built {@link EslEvent} objects. Four defects made
 * the integration unable to complete a single call against a real FreeSWITCH:
 *
 * <ol>
 *   <li><b>Auth banner never consumed.</b> FreeSWITCH speaks first with an
 *       unprompted {@code Content-Type: auth/request} block. The old code sent
 *       {@code auth} immediately, then read, then tested
 *       {@code startsWith("+OK")} — which read the <em>banner</em>, so
 *       {@code connect()} failed on its first command.</li>
 *   <li><b>{@code Reply-Text} never parsed.</b> The verdict lives in the
 *       {@code Reply-Text} <em>header</em>, not at the start of the raw text.
 *       Every {@code startsWith("+OK")} test therefore failed even after (1)
 *       was fixed.</li>
 *   <li><b>Event framing wrong.</b> A plain event's first line is its
 *       {@code Content-Type}; the {@code Event-Name} lives in the body after a
 *       blank line. The old code compared the first socket line against the
 *       subscribed names, so <b>every event was dropped</b>.</li>
 *   <li><b>{@code bgapi} Job-UUID treated as the channel UUID.</b>
 *       {@code bgapi originate} answers {@code +OK Job-UUID: <job-uuid>}; the
 *       channel UUID is a different identifier. The old code took
 *       {@code parts[1]} as "the channel UUID" and persisted a <b>job</b>
 *       UUID as the provider call id, so nothing could ever correlate.</li>
 * </ol>
 *
 * <h2>Corrected handshake and framing</h2>
 *
 * <pre>
 * TCP connect
 * &lt;- Content-Type: auth/request            (banner, unprompted)
 * -&gt; auth &lt;password&gt;
 * &lt;- Content-Type: command/reply
 *    Reply-Text: +OK accepted
 *    ...only now is the connection command-ready...
 * </pre>
 *
 * <p>All framing is delegated to {@link #readMessage()}, which reads a header
 * block to the terminating blank line and then exactly
 * {@code Content-Length} characters of body. That makes fragmented writes and
 * coalesced events correct by construction: a {@code BufferedReader} simply
 * blocks until the bytes it needs have arrived, and two events in one TCP
 * segment are read as two independent messages.
 *
 * <h2>Channel identity (defect 4)</h2>
 *
 * <p>Rather than correlating a late {@code CHANNEL_CREATE} to a job, the
 * channel UUID is <b>chosen by us</b> through FreeSWITCH's documented
 * {@code origination_uuid} channel variable. The caller passes the
 * {@code CallAttempt} id, so the channel UUID is known deterministically before
 * the call is placed and every subsequent ESL event correlates by
 * {@code Call-UUID} with no race and no extra table.
 * {@link #originate(String, String, String, String, String)} returns the
 * <em>job</em> UUID for logging and failure correlation only — it is never
 * persisted as a channel identity.
 *
 * <h2>Connection lifetime</h2>
 *
 * <p>This client is intentionally short-lived and used per operation, matching
 * the pre-existing VB-1..VB-4 design. The event subscription connection is the
 * one long-lived instance ({@link EslEventScheduler}).
 *
 * <h2>Secrets</h2>
 *
 * <p>The password is never logged. The auth command is logged with the
 * credential redacted, and protocol errors carry FreeSWITCH's own text, which
 * contains no credentials.
 */
@Slf4j
public class EslClient implements AutoCloseable {

    private final FreeSwitchProperties properties;
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private boolean authenticated = false;

    public EslClient(FreeSwitchProperties properties) {
        this.properties = properties;
    }

    // =====================================================================
    // Connection and authentication
    // =====================================================================

    /**
     * Connects to FreeSWITCH and completes the protocol handshake.
     *
     * <p>The order is load-bearing: the server's {@code auth/request} banner is
     * read <em>before</em> the {@code auth} command is sent, and the connection
     * is only marked command-ready once the reply's {@code Reply-Text} header
     * has been read and found to be {@code +OK}.
     */
    public void connect() {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        try {
            socket = new Socket();
            socket.setSoTimeout(properties.getCommandTimeoutSeconds() * 1000);
            socket.connect(new java.net.InetSocketAddress(properties.getHost(), properties.getPort()),
                    properties.getConnectTimeoutSeconds() * 1000);

            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Defect 1 fix: the banner is unprompted and arrives first. It must
            // be consumed before the auth reply can be read, or the reply read
            // returns the banner and authentication appears to fail.
            EslMessage banner = readMessage();
            if (!banner.isAuthBanner()) {
                throw new EslException("Expected FreeSWITCH auth banner but received: "
                        + describe(banner));
            }

            // Defect 2 fix: never log the credential.
            log.debug("ESL auth banner received from {}:{}; authenticating",
                    properties.getHost(), properties.getPort());
            sendCommand("auth " + properties.getPassword());
            EslMessage authReply = readMessage();

            if (!authReply.isCommandReply()) {
                throw new EslException("Malformed authentication reply: " + describe(authReply));
            }
            if (authReply.isError()) {
                throw new EslException("Authentication rejected by FreeSWITCH");
            }
            if (!authReply.isOk()) {
                throw new EslException("Unexpected authentication reply: " + describe(authReply));
            }

            authenticated = true;
            log.debug("ESL connection established to {}:{}", properties.getHost(), properties.getPort());

        } catch (SocketTimeoutException e) {
            close();
            throw new EslException("Connection timeout to FreeSWITCH at "
                    + properties.getHost() + ":" + properties.getPort(), e);
        } catch (IOException e) {
            close();
            throw new EslException("Failed to connect to FreeSWITCH at "
                    + properties.getHost() + ":" + properties.getPort(), e);
        }
    }

    // =====================================================================
    // Event subscription
    // =====================================================================

    /**
     * ESL events the platform consumes.
     *
     * <p>{@code CHANNEL_EXECUTE_COMPLETE} was added in Phase D. It is the only
     * event that distinguishes a <em>completed playback request</em> from a
     * <em>playback request that never started</em>, and that distinction is the
     * whole of J2. Measured on the live switch, for one {@code uuid_broadcast}:
     *
     * <pre>
     * valid file   -&gt; CHANNEL_EXECUTE, PLAYBACK_START, PLAYBACK_STOP, CHANNEL_EXECUTE_COMPLETE
     * missing file -&gt; CHANNEL_EXECUTE, CHANNEL_EXECUTE_COMPLETE
     * </pre>
     *
     * <p>Both cases answer the command identically ({@code +OK Message sent}) and
     * neither produces {@code PLAYBACK_ERROR}. See
     * {@link EslEventService#handlePlaybackLifecycle}.
     */
    static final String[] SUBSCRIBED_EVENTS = {
            "CHANNEL_CREATE",
            "CHANNEL_PROGRESS",
            "CHANNEL_PROGRESS_MEDIA",
            "CHANNEL_ANSWER",
            "CHANNEL_DTMF",
            "CHANNEL_HANGUP",
            "PLAYBACK_START",
            "PLAYBACK_STOP",
            "PLAYBACK_ERROR",
            "CHANNEL_BRIDGE",
            "CHANNEL_EXECUTE_COMPLETE"
    };

    /**
     * Subscribes to ESL events and processes them until the connection closes.
     *
     * <p>Defect 3 fix: each iteration reads one correctly-framed
     * {@link EslMessage}. A plain event's {@code Event-Name} is taken from the
     * message <em>body</em> headers, and the event is dispatched only when that
     * name is one this client subscribed to.
     */
    public void subscribeAndProcessEvents(Consumer<EslEvent> handler) {
        if (!authenticated) {
            throw new EslException("Not authenticated - call connect() first");
        }
        try {
            sendCommand("event plain " + String.join(" ", SUBSCRIBED_EVENTS));
            EslMessage subscribeReply = readMessage();
            if (!subscribeReply.isOk()) {
                throw new EslException("Event subscription rejected: " + describe(subscribeReply));
            }
            log.info("Subscribed to FreeSWITCH events: {}", String.join(", ", SUBSCRIBED_EVENTS));

            while (authenticated) {
                EslMessage message;
                try {
                    message = readMessage();
                } catch (SocketTimeoutException idleTimeout) {
                    // A quiet call produces no events. The read timeout is
                    // expected here, not a failure, so keep the loop alive.
                    continue;
                }
                EslEvent event = toSubscribedEvent(message);
                if (event != null) {
                    handler.accept(event);
                }
            }
        } catch (IOException e) {
            throw new EslException("I/O error during event processing", e);
        } finally {
            authenticated = false;
        }
    }

    /**
     * Converts a framed message into a dispatchable event, or {@code null} when
     * it is not a subscribed plain event.
     *
     * <p>Header precedence is deliberate: the <em>body</em> headers win,
     * because for {@code text/event-plain} that is where {@code Event-Name}
     * and every call identifier actually live.
     */
    private EslEvent toSubscribedEvent(EslMessage message) {
        if (!message.isPlainEvent() || !message.hasBody()) {
            return null;
        }
        Map<String, String> bodyHeaders = parseHeaderBlock(message.body());
        String eventName = firstNonBlank(bodyHeaders.get("Event-Name"), message.header("Event-Name"));
        if (eventName == null || !isSubscribed(eventName)) {
            return null;
        }
        EslEvent event = new EslEvent(eventName);
        message.headers().forEach(event::addHeader);
        bodyHeaders.forEach(event::addHeader);
        return event;
    }

    private boolean isSubscribed(String eventName) {
        for (String subscribed : SUBSCRIBED_EVENTS) {
            if (subscribed.equals(eventName)) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // Commands
    // =====================================================================

    /**
     * Issues a {@code bgapi originate} with a caller-chosen channel UUID.
     *
     * <p>Defect 4 fix: the channel UUID is <b>supplied by the caller</b> via the
     * documented {@code origination_uuid} channel variable, so the provider
     * call identity is deterministic <em>before</em> the call is placed and every
     * subsequent ESL event correlates by {@code Call-UUID} with no race, no
     * late-event window and no extra table. Campaign dials pass the
     * {@code CallAttempt} id.
     *
     * @return the CHANNEL UUID — the identity events correlate on. Never the
     *         job UUID, which is a background-task identifier.
     */
    public String originate(String callerId, String destinationNumber,
                            String gatewayName, String profile, String channelUuid) {
        if (channelUuid == null || channelUuid.isBlank()) {
            channelUuid = java.util.UUID.randomUUID().toString();
        }
        originateWithJob(callerId, destinationNumber, gatewayName, profile, channelUuid);
        return channelUuid;
    }

    /**
     * Issues a {@code bgapi originate} with a freshly generated channel UUID.
     *
     * <p>For channels that have no pre-existing identity to pin — the agent
     * leg, which is not attached to a {@code CallAttempt}. The UUID is
     * generated here and returned so the caller can persist it as the leg's
     * provider call id, which is what makes the leg's events correlate. This
     * repairs the same Job-UUID-vs-channel-UUID defect on the agent path.
     */
    public String originate(String callerId, String destinationNumber,
                            String gatewayName, String profile) {
        return originate(callerId, destinationNumber, gatewayName, profile, null);
    }

    public String originate(String callerId, String destinationNumber) {
        return originate(callerId, destinationNumber, null, null, null);
    }

    /**
     * The originate implementation, returning the background <b>job</b> UUID.
     *
     * <p>Private on purpose: the job UUID is useful for diagnosing a failed job
     * and is deliberately kept out of every public contract, so it can never be
     * persisted as a channel identity.
     */
    private String originateWithJob(String callerId, String destinationNumber,
                                    String gatewayName, String profile, String channelUuid) {
        requireAuthenticated();
        requireUuid(channelUuid, "originate");

        String gateway = gatewayName != null && !gatewayName.isBlank() ? gatewayName : properties.getGateway();
        if (gateway == null || gateway.isBlank()) {
            throw new EslException("FreeSWITCH gateway not configured");
        }

        // Multiple channel variables are comma-separated inside the braces.
        // origination_uuid pins the channel identity, which is what makes the
        // whole event-correlation chain deterministic.
        String dialString = String.format("sofia/gateway/%s/%s", gateway, destinationNumber);
        String originateCmd = String.format(
                "bgapi originate {origination_uuid=%s,origination_caller_id_number=%s}%s",
                channelUuid, callerId, dialString);

        log.debug("Sending originate command: bgapi originate "
                + "{{origination_uuid=...,origination_caller_id_number={}}}sofia/gateway/{}/{}",
                maskNumber(callerId), gateway, maskNumber(destinationNumber));

        EslMessage reply = execute(originateCmd, "originate");
        String jobUuid = reply.header("Job-UUID");
        if (jobUuid == null || jobUuid.isBlank()) {
            // FreeSWITCH accepted the command but did not report a job id. The
            // channel UUID is still known (we pinned it), so the call is
            // usable; this is logged rather than faked.
            log.warn("FreeSWITCH accepted originate without a Job-UUID header "
                    + "(channel {} pinned by origination_uuid)", channelUuid);
            return null;
        }
        log.info("FreeSWITCH originate accepted (channel={}, job={})",
                channelUuid, jobUuid);
        return jobUuid.trim();
    }

    /**
     * Plays an audio file on an active channel (VB-1 PLAYFILE).
     *
     * <p>Uses {@code uuid_broadcast <uuid> <path> aleg} so the file is played
     * to the A-leg only (the customer channel), which is correct for voice
     * blast.
     *
     * <h2>Acceptance is NOT playback (Phase D, J2)</h2>
     *
     * <p>FreeSWITCH answers {@code +OK Message sent} whether or not the file can
     * be opened, so this method's success says nothing about whether audio
     * played. Measured on the live switch: a missing file produced no
     * {@code PLAYBACK_ERROR} and no {@code Playback-Error} header at all.
     *
     * <p>Completion is therefore established from the event stream, not from
     * this call: {@code PLAYBACK_START} proves playback began,
     * {@code PLAYBACK_STOP} proves it completed, and a
     * {@code CHANNEL_EXECUTE_COMPLETE} that arrives with no preceding
     * {@code PLAYBACK_START} proves the request finished without ever starting.
     * See {@link EslEventService#handlePlaybackLifecycle}.
     *
     * @param audioPath a path FreeSWITCH can resolve (VB-6E maps the asset's
     *                  logical storage reference to one; the raw reference is
     *                  never sent)
     */
    public void playFile(String channelUuid, String audioPath) {
        requireAuthenticated();
        requireUuid(channelUuid, "playFile");
        if (audioPath == null || audioPath.isBlank()) {
            throw new IllegalArgumentException("Audio path is required for playback");
        }
        executeUuidCommand(String.format("uuid_broadcast %s %s aleg", channelUuid, audioPath), "playFile");
    }

    /** Terminates an active channel with NORMAL_CLEARING. */
    public void hangup(String channelUuid) {
        requireAuthenticated();
        requireUuid(channelUuid, "hangup");
        executeUuidCommand("uuid_kill " + channelUuid + " NORMAL_CLEARING", "hangup");
    }

    /** Bridges two active channels (VB-3 CONNECT_BY_AGENT). */
    public void bridge(String primaryUuid, String otherUuid) {
        requireAuthenticated();
        requireUuid(primaryUuid, "bridge");
        requireUuid(otherUuid, "bridge");
        executeUuidCommand("uuid_bridge " + primaryUuid + " " + otherUuid, "bridge");
    }

    /**
     * Places a call on the <em>internal</em> profile, bypassing the gateway.
     *
     * <p>Exists for integration verification against a switch with no carrier.
     * {@link #originate} is gateway-shaped by design - the production contract is
     * {@code sofia/gateway/&lt;gateway&gt;/&lt;destination&gt;} - and the only
     * configured gateway points at a provider that does not exist, so a
     * gateway-shaped originate cannot place a local call at all. This method
     * reaches a locally registered extension directly, which is what makes the
     * live event contract observable without inventing a carrier.
     *
     * <p>It is deliberately narrow: no gateway, no profile indirection, and the
     * destination is used verbatim after the {@code sofia/internal/} prefix. No
     * production code path calls it.
     *
     * @param channelUuid     the identity to pin, echoed back on every event
     * @param userAtHost      e.g. {@code 1002@172.25.0.4}
     */
    public void originateOnInternalProfile(String channelUuid, String userAtHost) {
        requireAuthenticated();
        requireUuid(channelUuid, "originateOnInternalProfile");
        if (userAtHost == null || userAtHost.isBlank()) {
            throw new IllegalArgumentException("Destination is required");
        }
        // bgapi is a genuine inbound command, so it is NOT api-prefixed.
        execute("bgapi originate {origination_uuid=" + channelUuid
                        + ",origination_caller_id_number=+15551230000}sofia/internal/"
                        + userAtHost + " &park()",
                "originateOnInternalProfile");
    }

    /**
     * Runs an {@code api} command and returns its response body.
     *
     * <p>Phase D, found by running against the live switch. An {@code api} reply
     * is <em>not</em> a command reply: it arrives as
     * {@code Content-Type: api/response} with an <strong>empty</strong>
     * {@code Reply-Text}, and the actual result is the body. The shared
     * {@link #execute} path requires {@code +OK} and therefore rejects a
     * perfectly successful {@code api} call - which is how this method first
     * failed against real FreeSWITCH.
     *
     * <p>So the verdict here is {@code -ERR} only. A successful {@code api} call
     * has no verdict to check, and treating "no verdict" as failure is exactly
     * the assumption that broke.
     *
     * @param command the API command, without the {@code api } prefix
     * @return the response body, or {@code ""} when there is none
     * @throws EslException if the switch reports {@code -ERR}, or on I/O failure
     */
    public String apiStatus(String command) {
        return executeApi(command, "api " + command).body();
    }

    /**
     * Sends an {@code api}-prefixed command and interprets its reply.
     *
     * <p>Shared by {@link #apiStatus} and the channel-addressed commands, because
     * both have the same reply shape and both were wrong in the same way.
     *
     * <p>An {@code api} reply is <em>not</em> a command reply. It arrives as
     * {@code Content-Type: api/response} with an <strong>empty</strong>
     * {@code Reply-Text}, and the result is the body. The shared
     * {@link #execute} path requires {@code +OK} and therefore rejects
     * perfectly successful {@code api} calls. Both failure modes below were
     * found by running against the live switch, not by reading a spec:
     *
     * <pre>
     * api status                    -&gt; Content-Type: api/response, Reply-Text: ''
     * api uuid_kill &lt;uuid&gt; CLEAR    -&gt; Content-Type: api/response, Reply-Text: ''
     * </pre>
     *
     * <p>So the only verdict that means failure here is {@code -ERR}. Treating
     * "no verdict" as failure is precisely the assumption that broke.
     */
    private EslMessage executeApi(String command, String operation) {
        try {
            sendCommand("api " + command);
            EslMessage reply = readMessage();
            if (reply.isError()) {
                String message = reply.verdict();
                log.warn("FreeSWITCH {} rejected: {}", operation, message);
                throw new EslException(operation + " rejected: " + message);
            }
            return reply;
        } catch (SocketTimeoutException e) {
            throw new EslException("Command timeout during " + operation, e);
        } catch (IOException e) {
            throw new EslException("I/O error during " + operation, e);
        }
    }

    /**
     * Executes a FreeSWITCH API that is addressed by channel UUID.
     *
     * <p><strong>Phase D, and the most severe defect this phase found.</strong>
     * These commands must be sent as {@code api <command>}, not as a bare
     * inbound command. ESL accepts a fixed set of inbound commands - {@code api},
     * {@code bgapi}, {@code event}, {@code filter}, {@code linger}, {@code exit},
     * {@code hup}, {@code log} - and anything else is rejected. Measured against
     * the live switch:
     *
     * <pre>
     * uuid_kill &lt;uuid&gt; NORMAL_CLEARING       -&gt; -ERR command not found
     * api uuid_kill &lt;uuid&gt; NORMAL_CLEARING   -&gt; accepted
     * uuid_broadcast &lt;uuid&gt; &lt;path&gt; aleg    -&gt; -ERR command not found
     * api uuid_broadcast &lt;uuid&gt; &lt;path&gt; aleg -&gt; accepted
     * </pre>
     *
     * <p>So playback, hangup and bridge were all being rejected by the provider.
     * The whole test suite passed regardless, because the protocol double
     * answered {@code +OK accepted} to every command it did not recognise - the
     * failure mode this whole phase exists to eliminate. {@code bgapi} is
     * genuinely a bare inbound command, which is why originate worked.
     */
    private void executeUuidCommand(String command, String operation) {
        executeApi(command, operation);
        log.debug("FreeSWITCH {} accepted for channel", operation);
    }

    /**
     * Sends a command and interprets its reply.
     *
     * <p>Defect 2 fix: the verdict comes from {@code Reply-Text}, and a reply
     * carrying no verdict at all is an error rather than a silent success.
     */
    private EslMessage execute(String command, String operation) {
        try {
            sendCommand(command);
            EslMessage reply = readMessage();

            if (reply.isError()) {
                String errorMsg = reply.verdict();
                log.warn("FreeSWITCH {} rejected: {}", operation, errorMsg);
                throw new EslException(operation + " rejected: " + errorMsg);
            }
            if (!reply.isOk()) {
                log.warn("Unexpected FreeSWITCH reply for {}: {}", operation, describe(reply));
                throw new EslException("Unexpected reply for " + operation + ": " + describe(reply));
            }
            return reply;
        } catch (SocketTimeoutException e) {
            throw new EslException("Command timeout during " + operation, e);
        } catch (IOException e) {
            throw new EslException("I/O error during " + operation, e);
        }
    }

    private void requireAuthenticated() {
        if (!authenticated) {
            throw new EslException("Not authenticated - call connect() first");
        }
    }

    private void requireUuid(String channelUuid, String operation) {
        if (channelUuid == null || channelUuid.isBlank()) {
            throw new EslException(operation + " requires a channel UUID");
        }
    }

    private void sendCommand(String command) throws IOException {
        writer.print(command + "\n\n");
        writer.flush();
    }

    // =====================================================================
    // Framing
    // =====================================================================

    /**
     * Reads exactly one ESL message: a header block terminated by a blank line,
     * then exactly {@code Content-Length} characters of body.
     *
     * <p>This is the single place framing happens, and it is what makes both
     * required robustness properties fall out for free:
     *
     * <ul>
     *   <li><b>Fragmented frames</b> — {@code readLine()} blocks until a
     *       newline arrives, so an event split across several TCP writes is
     *       reassembled rather than dropped.</li>
     *   <li><b>Coalesced frames</b> — one call consumes exactly one message, so
     *       two events in a single write are returned as two messages instead of
     *       being merged or losing the second's framing.</li>
     * </ul>
     */
    private EslMessage readMessage() throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                break; // end of the header block
            }
            addHeader(headers, line);
        }
        if (line == null && headers.isEmpty()) {
            throw new IOException("FreeSWITCH closed the ESL connection");
        }

        String contentType = headers.getOrDefault("Content-Type", "");
        int contentLength = parseContentLength(headers.get("Content-Length"));

        String body = "";
        if (contentLength > 0) {
            char[] buffer = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = reader.read(buffer, read, contentLength - read);
                if (n < 0) {
                    throw new IOException("Truncated ESL body: expected " + contentLength
                            + " characters, read " + read);
                }
                read += n;
            }
            body = new String(buffer);
        }
        return new EslMessage(contentType, headers, body);
    }

    private static void addHeader(Map<String, String> headers, String line) {
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return; // not a header; ignore rather than corrupt the map
        }
        headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
    }

    private static int parseContentLength(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Parses a body that consists of {@code Key: Value} lines. */
    private static Map<String, String> parseHeaderBlock(String block) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (block == null || block.isEmpty()) {
            return headers;
        }
        for (String line : block.split("\r?\n")) {
            if (line.isEmpty()) {
                continue;
            }
            addHeader(headers, line);
        }
        return headers;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    /**
     * A safe, single-line description of a message for error text and logs.
     * Never includes credentials, and never the whole body.
     */
    private static String describe(EslMessage message) {
        String reply = message.replyText();
        if (reply != null) {
            return "Reply-Text=" + reply.trim();
        }
        String type = message.contentType().isBlank() ? "<none>" : message.contentType();
        return "Content-Type=" + type.toLowerCase(Locale.ROOT);
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Override
    public void close() {
        authenticated = false;
        closeQuietly(reader);
        closeQuietly(writer);
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
        socket = null;
        writer = null;
        reader = null;
        log.debug("ESL connection closed");
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    /** Masks a phone number for logging (shows only the last 4 digits). */
    private static String maskNumber(String number) {
        if (number == null || number.length() <= 4) {
            return number;
        }
        return "***" + number.substring(number.length() - 4);
    }
}
