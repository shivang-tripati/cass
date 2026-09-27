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
import java.util.List;
import java.util.function.Consumer;

/**
 * Minimal FreeSWITCH Event Socket Library (ESL) client.
 * <p>
 * Implements the basic ESL protocol for outbound originate via bgapi,
 * in-call media playback via uuid_broadcast, call termination via
 * uuid_kill, and event subscription for call progress and lifecycle.
 * <p>
 * Protocol:
 * 1. Connect to host:port
 * 2. Send "auth <password>\n\n"
 * 3. Read response (expect "+OK accepted")
 * 4. For originate: Send command: "bgapi originate {...}sofia/gateway/<gateway>/<destination>\n\n"     * 4. For events: Send "event plain CHANNEL_PROGRESS CHANNEL_PROGRESS_MEDIA CHANNEL_ANSWER CHANNEL_DTMF CHANNEL_HANGUP PLAYBACK_START PLAYBACK_STOP PLAYBACK_ERROR\n\n"
 * 5. Read responses/events
 */
@Slf4j
public class EslClient implements AutoCloseable {

    private final FreeSwitchProperties properties;
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private boolean authenticated = false;
    private final List<Consumer<EslEvent>> eventHandlers = new ArrayList<>();

    public EslClient(FreeSwitchProperties properties) {
        this.properties = properties;
    }

    /**
     * Connects to FreeSWITCH Event Socket and authenticates.
     *
     * @throws EslException if connection or authentication fails
     */
    public void connect() {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return; // Already connected
        }

        try {
            socket = new Socket();
            socket.setSoTimeout(properties.getCommandTimeoutSeconds() * 1000);
            socket.connect(new java.net.InetSocketAddress(properties.getHost(), properties.getPort()),
                    properties.getConnectTimeoutSeconds() * 1000);

            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Authenticate
            sendCommand("auth " + properties.getPassword());
            String response = readResponse();

            if (!response.startsWith("+OK")) {
                throw new EslException("Authentication failed: " + response);
            }

            authenticated = true;
            log.debug("ESL connection established to {}:{}", properties.getHost(), properties.getPort());

        } catch (SocketTimeoutException e) {
            close();
            throw new EslException("Connection timeout to FreeSWITCH at " + properties.getHost() + ":" + properties.getPort(), e);
        } catch (IOException e) {
            close();
            throw new EslException("Failed to connect to FreeSWITCH at " + properties.getHost() + ":" + properties.getPort(), e);
        }
    }

    /**
     * ESL events the platform consumes: call progress (CHANNEL_PROGRESS and
     * its media variant = ringing/early media), lifecycle (answer/hangup),
     * and playback lifecycle (PLAYBACK_START/STOP/ERROR, fired by
     * uuid_broadcast on the channel executing the playback).
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
            "CHANNEL_BRIDGE"
    };

    /**
     * Subscribes to FreeSWITCH events and processes them.
     * This method blocks and processes events until the connection is closed.
     *
     * @param handler callback for each received event
     * @throws EslException if connection or event processing fails
     */
    public void subscribeAndProcessEvents(Consumer<EslEvent> handler) {
        if (!authenticated) {
            throw new EslException("Not authenticated - call connect() first");
        }

        try {
            // Subscribe to events
            sendCommand("event plain " + String.join(" ", SUBSCRIBED_EVENTS));
            String response = readResponse();

            if (!response.startsWith("+OK")) {
                throw new EslException("Event subscription failed: " + response);
            }

            log.info("Subscribed to FreeSWITCH events: {}", String.join(", ", SUBSCRIBED_EVENTS));

            // Process events loop
            String line;
            while (authenticated && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue; // Skip empty lines
                }

                // Parse event - first line is event name
                String eventName = line.trim();
                if (eventName.isEmpty()) {
                    continue;
                }

                // Read event headers until blank line
                EslEvent event = parseEvent(eventName);
                if (event != null) {
                    log.debug("Received ESL event: {} (call-id={})", event.getEventName(), event.getHeader("Call-UUID"));
                    handler.accept(event);
                }
            }

        } catch (SocketTimeoutException e) {
            throw new EslException("Event socket timeout", e);
        } catch (IOException e) {
            throw new EslException("I/O error during event processing", e);
        } finally {
            authenticated = false;
        }
    }

    /**
     * Parses a complete ESL event from the reader.
     */
    private EslEvent parseEvent(String eventName) throws IOException {
        EslEvent event = new EslEvent(eventName);
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                break; // End of headers
            }
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                event.addHeader(key, value);
            }
        }

        // Only return events we care about
        for (String name : SUBSCRIBED_EVENTS) {
            if (name.equals(event.getEventName())) {
                return event;
            }
        }
        return null;
    }

    /**
     * Sends a bgapi originate command to FreeSWITCH.
     *
     * @param callerId the caller ID (DID E.164 number)
     * @param destinationNumber the destination number (Contact E.164 number)
     * @param gatewayName resolved gateway name (or null to use properties default)
     * @param profile optional profile (or null for default)
     * @return the FreeSWITCH channel UUID if successful
     * @throws EslException if the command fails
     */
    public String originate(String callerId, String destinationNumber, String gatewayName, String profile) {
        if (!authenticated) {
            throw new EslException("Not authenticated - call connect() first");
        }

        String gateway = gatewayName != null && !gatewayName.isBlank() ? gatewayName : properties.getGateway();
        if (gateway == null || gateway.isBlank()) {
            throw new EslException("FreeSWITCH gateway not configured");
        }

        String effectiveProfile = profile != null && !profile.isBlank() ? profile : properties.getProfile();
        if (effectiveProfile == null || effectiveProfile.isBlank()) {
            effectiveProfile = "external";
        }

        // Build originate command
        // Format: bgapi originate {origination_caller_id_number=+1234567890}sofia/gateway/gateway_name/+19876543210
        String dialString = String.format("sofia/gateway/%s/%s", gateway, destinationNumber);
        String originateCmd = String.format("bgapi originate {origination_caller_id_number=%s}%s", callerId, dialString);

        log.debug("Sending originate command: bgapi originate {{origination_caller_id_number={}}}sofia/gateway/{}/{}",
                maskNumber(callerId), gateway, maskNumber(destinationNumber));

        try {
            sendCommand(originateCmd);
            String response = readResponse();

            if (response.startsWith("+OK")) {
                // Response format: "+OK <uuid>"
                String[] parts = response.split("\\s+", 2);
                if (parts.length >= 2) {
                    String uuid = parts[1].trim();
                    log.info("FreeSWITCH originate accepted for attempt (caller={}, destination={}, uuid={})",
                            maskNumber(callerId), maskNumber(destinationNumber), uuid);
                    return uuid;
                } else {
                    log.warn("FreeSWITCH originate accepted but no UUID in response: {}", response);
                    return "accepted";
                }
            } else if (response.startsWith("-ERR")) {
                // FreeSWITCH error response
                String errorMsg = response.substring(5).trim();
                log.warn("FreeSWITCH originate rejected: {}", errorMsg);
                throw new EslException("Originate rejected: " + errorMsg);
            } else {
                log.warn("Unexpected FreeSWITCH response: {}", response);
                throw new EslException("Unexpected response: " + response);
            }

        } catch (SocketTimeoutException e) {
            throw new EslException("Command timeout during originate", e);
        } catch (IOException e) {
            throw new EslException("I/O error during originate", e);
        }
    }

    public String originate(String callerId, String destinationNumber) {
        return originate(callerId, destinationNumber, null, null);
    }

    /**
     * Plays an audio file on an active channel (VB-1 PLAYFILE).
     * <p>
     * Uses {@code uuid_broadcast <uuid> <path> aleg} so the file is played to
     * the A-leg only (the customer channel), which is the correct behavior
     * for voice blast: the called party hears the message, there is no B-leg
     * in an originate A-leg scenario.
     * <p>
     * FreeSWITCH fires PLAYBACK_START when playback begins and
     * PLAYBACK_STOP when it completes (or PLAYBACK_ERROR on failure) on the
     * channel executing the playback — command acceptance is NOT completion.
     *
     * @param channelUuid the FreeSWITCH channel UUID (provider call id)
     * @param audioPath the audio file path as resolvable by FreeSWITCH
     * @throws EslException if the command fails or the channel is unknown
     */
    public void playFile(String channelUuid, String audioPath) {
        requireAuthenticated();
        requireUuid(channelUuid, "playFile");
        // aleg = play to the channel itself (the originating/customer leg).
        String command = String.format("uuid_broadcast %s %s aleg", channelUuid, audioPath);
        executeUuidCommand(command, "playFile");
    }

    /**
     * Terminates an active channel with NORMAL_CLEARING (VB-1 post-playback
     * hangup or playback-failure teardown).
     *
     * @param channelUuid the FreeSWITCH channel UUID (provider call id)
     * @throws EslException if the command fails or the channel is unknown
     */
    public void hangup(String channelUuid) {
        requireAuthenticated();
        requireUuid(channelUuid, "hangup");
        executeUuidCommand("uuid_kill " + channelUuid + " NORMAL_CLEARING", "hangup");
    }

    /**
     * Bridges two active channels (VB-3 CONNECT_BY_AGENT).
     * <p>
     * Uses {@code uuid_bridge <caller> <agent>}. Command acceptance means
     * FreeSWITCH accepted the bridge REQUEST — the authoritative confirmation
     * is the CHANNEL_BRIDGE event with the agent UUID in Bridge-B-Unique-ID
     * (see {@code EslEventService}). The otherUuid channel survives the
     * bridge; the primaryUuid channel acts as the bridge anchor.
     *
     * @param primaryUuid the anchor channel UUID (caller leg)
     * @param otherUuid   the channel UUID to bridge in (agent leg)
     * @throws EslException if the command fails or either UUID is blank
     */
    public void bridge(String primaryUuid, String otherUuid) {
        requireAuthenticated();
        requireUuid(primaryUuid, "bridge");
        requireUuid(otherUuid, "bridge");
        executeUuidCommand("uuid_bridge " + primaryUuid + " " + otherUuid, "bridge");
    }

    /**
     * Executes a uuid_* API command and validates the "+OK" response.
     */
    private void executeUuidCommand(String command, String operation) {
        try {
            sendCommand(command);
            String response = readResponse();
            if (response.startsWith("+OK")) {
                log.debug("FreeSWITCH {} accepted for channel", operation);
            } else if (response.startsWith("-ERR")) {
                String errorMsg = response.substring(4).trim();
                log.warn("FreeSWITCH {} rejected: {}", operation, errorMsg);
                throw new EslException(operation + " rejected: " + errorMsg);
            } else {
                log.warn("Unexpected FreeSWITCH response for {}: {}", operation, response);
                throw new EslException("Unexpected response for " + operation + ": " + response);
            }
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

    /**
     * Sends a raw ESL command.
     */
    private void sendCommand(String command) throws IOException {
        writer.print(command + "\n\n");
        writer.flush();
    }

    /**
     * Reads a complete ESL response (for command responses).
     */
    private String readResponse() throws IOException {
        StringBuilder response = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            response.append(line).append("\n");
            // ESL responses end with a blank line
            if (line.isEmpty()) {
                break;
            }
        }

        return response.toString().trim();
    }

    @Override
    public void close() {
        authenticated = false;
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
        writer = null;
        reader = null;
        log.debug("ESL connection closed");
    }

    /**
     * Masks phone number for logging (shows only last 4 digits).
     */
    private String maskNumber(String number) {
        if (number == null || number.length() <= 4) {
            return number;
        }
        return "***" + number.substring(number.length() - 4);
    }
}