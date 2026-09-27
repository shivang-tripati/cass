package com.shivang.obd.telephony;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One correctly-framed FreeSWITCH Event Socket Library message.
 *
 * <p>VB-6E. Every ESL message is a header block terminated by a blank line,
 * optionally followed by a body of exactly {@code Content-Length} characters:
 *
 * <pre>
 * Content-Type: command/reply
 * Reply-Text: +OK accepted
 *
 * </pre>
 *
 * <p>or, for a plain event:
 *
 * <pre>
 * Content-Type: text/event-plain
 * Content-Length: 214
 *
 * Event-Name: CHANNEL_ANSWER
 * Channel-UUID: abc-123
 * </pre>
 *
 * <p>The pre-VB-6E client treated the first raw socket line as a verdict and
 * the first blank line as the end of an event, so it both mis-read every
 * command reply and dropped every event. This type exists so framing is done
 * in exactly one place and is correct by construction.
 *
 * <p>Header lookup is case-insensitive: FreeSWITCH is not consistent about
 * casing between the protocol spec and actual deployments.
 *
 * @param contentType the {@code Content-Type} header, never null
 * @param headers     all headers in wire order
 * @param body        the {@code Content-Length} body, or {@code ""} when absent
 */
public record EslMessage(String contentType, Map<String, String> headers, String body) {

    public EslMessage {
        if (contentType == null) {
            contentType = "";
        }
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        body = body == null ? "" : body;
    }

    /** Case-insensitive header lookup; {@code null} when absent. */
    public String header(String name) {
        if (name == null) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public String headerOrEmpty(String name) {
        String value = header(name);
        return value == null ? "" : value;
    }

    public int contentLength() {
        String raw = header("Content-Length");
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public boolean hasBody() {
        return !body.isEmpty();
    }

    /** True when this message is the server-initiated auth banner. */
    public boolean isAuthBanner() {
        return contentType.toLowerCase(Locale.ROOT).startsWith("auth/");
    }

    /** True when this message is a command reply. */
    public boolean isCommandReply() {
        return contentType.toLowerCase(Locale.ROOT).startsWith("command/");
    }

    /**
     * True when this message is a plain event, i.e. one carrying an
     * {@code Event-Name} in its body. Pre-VB-6E the client compared the
     * socket's first line against the subscribed event names, which never
     * matches because the first line is the content type.
     */
    public boolean isPlainEvent() {
        return contentType.toLowerCase(Locale.ROOT).startsWith("text/event");
    }

    /**
     * The command verdict, taken from the {@code Reply-Text} header as the
     * protocol specifies. {@code null} when the header is absent, so a caller
     * can never mistake raw socket text for a verdict.
     */
    public String replyText() {
        return header("Reply-Text");
    }

    /** True when {@code Reply-Text} begins with {@code +OK}. */
    public boolean isOk() {
        String reply = replyText();
        return reply != null && reply.trim().startsWith("+OK");
    }

    /** True when {@code Reply-Text} begins with {@code -ERR}. */
    public boolean isError() {
        String reply = replyText();
        return reply != null && reply.trim().startsWith("-ERR");
    }

    /**
     * The verdict text with the leading {@code +OK}/{@code -ERR} token
     * removed, for error messages. Never null.
     */
    public String verdict() {
        String reply = replyText();
        if (reply == null) {
            return "";
        }
        String trimmed = reply.trim();
        if (trimmed.startsWith("+OK")) {
            return trimmed.substring(3).trim();
        }
        if (trimmed.startsWith("-ERR")) {
            return trimmed.substring(4).trim();
        }
        return trimmed;
    }

    /**
     * The event name for a plain event, or {@code null}. The name lives in the
     * <em>body</em>, which is why the pre-VB-6E framing dropped every event.
     */
    public String eventName() {
        return header("Event-Name");
    }
}
