package com.shivang.obd.telephony;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-process FreeSWITCH ESL server that speaks the real wire protocol
 * (VB-6E).
 *
 * <h2>Why this exists</h2>
 *
 * <p>The VB-6E audit found four hard protocol defects in {@link EslClient} that
 * made the telephony integration unable to complete a single call against a
 * real FreeSWITCH â€” yet the entire test suite was green. The reason is that
 * every ESL test either mocked {@code EslClient} via
 * {@code mockConstruction} or hand-built {@link EslEvent} objects, so the
 * protocol layer had <b>zero</b> coverage. Even the one test that injected a
 * reader used the fabricated frame {@code "+OK accepted\n\n"}, which is not
 * anything FreeSWITCH sends.
 *
 * <p>This server closes that gap. It speaks genuine frames:
 *
 * <pre>
 * &lt;- Content-Type: auth/request
 *
 * &lt;- Content-Type: command/reply
 *    Reply-Text: +OK accepted
 *
 * &lt;- Content-Type: text/event-plain
 *    Content-Length: 214
 *
 *    Event-Name: CHANNEL_ANSWER
 *    ...
 * </pre>
 *
 * and it can deliver them fragmented, coalesced, or both, so the client's
 * framing is exercised rather than assumed.
 *
 * <h2>What it deliberately does NOT do</h2>
 *
 * <p>It is a protocol double, not a media server. It never opens a SIP channel
 * and never plays audio. Tests that need a live FreeSWITCH are a different
 * thing entirely and are reported separately in the VB-6E documentation.
 */
final class FakeEslServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final String password;
    private final Thread acceptor;
    private volatile boolean running = true;
    private volatile Socket clientSocket;
    private volatile OutputStream out;

    /** Commands the client issued, in order, for assertions. */
    private final List<String> receivedCommands = new CopyOnWriteArrayList<>();

    /** Replies to hand back, keyed by a substring of the command. */
    private final Map<String, List<String>> scriptedReplies = new LinkedHashMap<>();

    private final List<Runnable> onConnect = new ArrayList<>();

    private FakeEslServer(ServerSocket serverSocket, String password) {
        this.serverSocket = serverSocket;
        this.password = password;
        this.acceptor = new Thread(this::acceptLoop, "fake-esl-acceptor");
        this.acceptor.setDaemon(true);
    }

    /**
     * Starts a server on an ephemeral port.
     *
     * @param password the credential the client must present
     */
    static FakeEslServer start(String password) throws IOException {
        FakeEslServer server = new FakeEslServer(new ServerSocket(0), password);
        server.acceptor.start();
        return server;
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    String host() {
        return "127.0.0.1";
    }

    List<String> receivedCommands() {
        return List.copyOf(receivedCommands);
    }

    /**
     * Scripts the reply frames for the next command containing
     * {@code commandSubstring}. Frames are written verbatim, so a test can
     * inject exactly the bytes a real FreeSWITCH would send â€” including
     * deliberately malformed ones.
     */
    FakeEslServer replyWhen(String commandSubstring, String... frames) {
        scriptedReplies.computeIfAbsent(commandSubstring, k -> new ArrayList<>()).addAll(List.of(frames));
        return this;
    }

    /** Runs {@code action} once a client connects, for pushing events. */
    FakeEslServer onConnect(Runnable action) {
        onConnect.add(action);
        return this;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                clientSocket = socket;
                out = socket.getOutputStream();
                for (Runnable action : onConnect) {
                    action.run();
                }
                serve(socket);
            } catch (IOException e) {
                if (running) {
                    // A closed client socket is the normal path; anything else
                    // is a genuine test-harness problem and must surface.
                    continue;
                }
                return;
            }
        }
    }

    private void serve(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        Writer writer = new OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8);

        // FreeSWITCH speaks FIRST with an unprompted auth banner. A client that
        // sends its auth command before consuming this fails to authenticate,
        // which was audit defect 1.
        writeFrame(writer, "Content-Type: auth/request", null);

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) {
                continue; // end of a command; ESL commands are single-line here
            }
            receivedCommands.add(line);

            if (line.startsWith("auth ")) {
                boolean ok = line.equals("auth " + password);
                writeFrame(writer, "Content-Type: command/reply",
                        "Reply-Text: " + (ok ? "+OK accepted" : "-ERR invalid"));
                continue;
            }
            if (line.startsWith("event plain")) {
                writeFrame(writer, "Content-Type: command/reply",
                        "Reply-Text: +OK 0.0ms");
                continue; // subscription is done; events are pushed by tests
            }

            String scripted = takeScriptedReply(line);
            if (scripted != null) {
                writer.write(scripted);
                writer.flush();
            } else if (line.startsWith("bgapi originate")) {
                writeFrame(writer, "Content-Type: command/reply\nReply-Text: +OK Job-UUID: fake-job-1",
                        null);
            } else {
                writeFrame(writer, "Content-Type: command/reply", "Reply-Text: +OK accepted");
            }
        }
    }

    private String takeScriptedReply(String command) {
        for (Map.Entry<String, List<String>> entry : scriptedReplies.entrySet()) {
            if (command.contains(entry.getKey()) && !entry.getValue().isEmpty()) {
                return entry.getValue().remove(0);
            }
        }
        return null;
    }

    // =====================================================================
    // Event pushing
    // =====================================================================

    /**
     * Pushes one event as a genuine {@code text/event-plain} frame: headers,
     * {@code Content-Length}, blank line, then the body.
     */
    void pushEvent(String eventName, Map<String, String> headers) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("Event-Name: ").append(eventName).append('\n');
        for (Map.Entry<String, String> e : headers.entrySet()) {
            body.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        byte[] payload = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String head = "Content-Type: text/event-plain\nContent-Length: " + payload.length + "\n\n";
        out.write(head.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.write(payload);
        out.flush();
    }

    /** Convenience for the common Call-UUID-only event. */
    void pushEvent(String eventName, String callUuid) throws IOException {
        pushEvent(eventName, Map.of("Call-UUID", callUuid));
    }

    /**
     * Pushes one event split across several TCP writes at the given byte
     * boundaries, simulating fragmentation. The client must reassemble it.
     */
    void pushFragmentedEvent(String eventName, Map<String, String> headers,
                             int... chunkSizes) throws IOException {
        byte[] frame = frame(eventName, headers);
        int offset = 0;
        for (int size : chunkSizes) {
            if (offset >= frame.length) {
                break;
            }
            int end = Math.min(frame.length, offset + size);
            out.write(frame, offset, end - offset);
            out.flush();
            // A real network delivers a partial segment; sleeping briefly makes
            // the client's blocking read genuinely wait rather than finding the
            // whole frame already buffered.
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            offset = end;
        }
        if (offset < frame.length) {
            out.write(frame, offset, frame.length - offset);
            out.flush();
        }
    }

    /**
     * Pushes several events in a single TCP write, so the client must parse
     * them as independent messages rather than assuming one write is one event.
     */
    void pushCoalescedEvents(List<String[]> events) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        for (String[] event : events) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Call-UUID", event[1]);
            buffer.write(frame(event[0], headers));
        }
        out.write(buffer.toByteArray());
        out.flush();
    }

    private byte[] frame(String eventName, Map<String, String> headers) {
        StringBuilder body = new StringBuilder();
        body.append("Event-Name: ").append(eventName).append('\n');
        for (Map.Entry<String, String> e : headers.entrySet()) {
            body.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        byte[] payload = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String head = "Content-Type: text/event-plain\nContent-Length: " + payload.length + "\n\n";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            out.write(head.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write(payload);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** Waits for the client to issue a command containing {@code substring}. */
    boolean awaitCommand(String substring, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (String command : receivedCommands) {
                if (command.contains(substring)) {
                    return true;
                }
            }
            Thread.sleep(10);
        }
        return false;
    }

    /** Waits for a client connection, so a test never races the handshake. */
    boolean awaitClient(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (clientSocket != null) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static void writeFrame(Writer writer, String... headerLines) throws IOException {
        for (String header : headerLines) {
            if (header == null) {
                // A null entry means "no body follows this header block".
                break;
            }
            if (header.contains("\n")) {
                // A header block expressed as one multi-line string.
                for (String part : header.split("\n")) {
                    writer.write(part);
                    writer.write("\n");
                }
            } else {
                writer.write(header);
                writer.write("\n");
            }
        }
        writer.write("\n");
        writer.flush();
    }

    @Override
    public void close() {
        running = false;
        try {
            if (clientSocket != null) {
                clientSocket.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // best effort
        }
        acceptor.interrupt();
    }
}
