package com.shivang.obd.telephony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VB-6E — the ESL protocol contract, proven against a real socket.
 *
 * <p>These are the tests whose absence let four hard protocol defects ship in
 * a green build. Every one of them exercises the actual {@link EslClient}
 * against {@link FakeEslServer} over a real TCP connection, with genuine
 * FreeSWITCH wire frames. Nothing here is mocked, and the client's internals
 * are not stubbed — if the framing or the handshake is wrong, these fail.
 *
 * <p>The audit's four defects, and the test that now pins each:
 * <ol>
 *   <li>auth banner never consumed → {@code SEQ-1}</li>
 *   <li>{@code Reply-Text} never parsed → {@code SEQ-1}, {@code SEQ-5}</li>
 *   <li>event framing dropped every event → {@code SEQ-3}, {@code SEQ-4}</li>
 *   <li>bgapi Job-UUID treated as the channel UUID → {@code SEQ-2}</li>
 * </ol>
 */
class EslProtocolTest {

    private static final String PASSWORD = "test-esl-password";

    private FakeEslServer server;
    private EslClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = FakeEslServer.start(PASSWORD);
    }

    @AfterEach
    void stopServer() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private FreeSwitchProperties properties() {
        FreeSwitchProperties properties = new FreeSwitchProperties();
        properties.setEnabled(true);
        properties.setHost(server.host());
        properties.setPort(server.port());
        properties.setPassword(PASSWORD);
        properties.setGateway("test-gateway");
        properties.setConnectTimeoutSeconds(5);
        // A short read timeout keeps the event loop test quick; the loop treats
        // a read timeout as "no events right now" and keeps waiting.
        properties.setCommandTimeoutSeconds(2);
        return properties;
    }

    private EslClient connected() {
        client = new EslClient(properties());
        client.connect();
        return client;
    }

    // =====================================================================
    // Sequence A - connect / auth
    // =====================================================================

    @Test
    @DisplayName("SEQ-1: connect consumes the auth banner, then authenticates, then commands work")
    void connectConsumesAuthBanner() {
        // The server writes an unprompted auth/request banner the instant the
        // socket opens. A client that sent `auth` without reading it first would
        // read the banner as the auth reply and fail — the pre-VB-6E defect.
        EslClient connected = connected();

        // A command after a correct handshake proves the connection is
        // genuinely command-ready, not merely un-thrown.
        org.assertj.core.api.Assertions.assertThatCode(
                () -> connected.hangup("11111111-2222-3333-4444-555555555555"))
                .doesNotThrowAnyException();

        assertThat(server.receivedCommands())
                .anySatisfy(c -> assertThat(c).isEqualTo("auth " + PASSWORD))
                .anySatisfy(c -> assertThat(c).startsWith("uuid_kill "));
    }

    @Test
    @DisplayName("SEQ-1a: a wrong password is rejected as an authentication failure")
    void wrongPasswordIsRejected() {
        FreeSwitchProperties properties = properties();
        properties.setPassword("not-the-password");
        client = new EslClient(properties);

        assertThatThrownBy(client::connect)
                .isInstanceOf(EslException.class)
                .hasMessageContaining("Authentication rejected");
    }

    // =====================================================================
    // Sequence E - failure
    // =====================================================================

    @Test
    @DisplayName("SEQ-5: a -ERR reply becomes a domain failure, not a silent success")
    void errorReplyBecomesFailure() {
        EslClient connected = connected();
        server.replyWhen("uuid_broadcast",
                "Content-Type: command/reply\nReply-Text: -ERR no such file\n\n");

        // Pre-VB-6E this returned silently, because the verdict was read from
        // the raw socket text which began with "Content-Type:".
        assertThatThrownBy(() -> connected.playFile(
                "11111111-2222-3333-4444-555555555555", "/sounds/promo.wav"))
                .isInstanceOf(EslException.class)
                .hasMessageContaining("rejected")
                .hasMessageContaining("no such file");
    }

    @Test
    @DisplayName("SEQ-5a: a reply with no verdict at all is a failure, never a false success")
    void verdictlessReplyIsNotSuccess() {
        EslClient connected = connected();
        server.replyWhen("uuid_kill", "Content-Type: command/reply\n\n");

        assertThatThrownBy(() -> connected.hangup("11111111-2222-3333-4444-555555555555"))
                .isInstanceOf(EslException.class)
                .hasMessageContaining("Unexpected reply");
    }

    // =====================================================================
    // Sequence B - bgapi originate / Job-UUID vs channel UUID
    // =====================================================================

    @Test
    @DisplayName("SEQ-2: originate returns the CHANNEL uuid we pinned, not the bgapi Job-UUID")
    void originateReturnsPinnedChannelUuid() {
        EslClient connected = connected();
        String channelUuid = UUID.randomUUID().toString();

        String returned = connected.originate("+918000000001", "+919800000002",
                "test-gateway", "external", channelUuid);

        // The server replies "+OK Job-UUID: fake-job-1". The pre-VB-6E client
        // took parts[1] of that as "the channel UUID", so it would have
        // returned "Job-UUID:" and persisted a job id as the provider call id.
        assertThat(returned)
                .as("must be the pinned channel uuid, never the job uuid")
                .isEqualTo(channelUuid)
                .isNotEqualTo("fake-job-1")
                .isNotEqualTo("Job-UUID:");

        // And the command must actually pin it, which is what makes the whole
        // event-correlation chain deterministic.
        assertThat(server.receivedCommands())
                .anySatisfy(command -> {
                    assertThat(command).startsWith("bgapi originate");
                    assertThat(command).contains("origination_uuid=" + channelUuid);
                });
    }

    @Test
    @DisplayName("SEQ-2a: an unpinned originate still yields a real, usable channel uuid")
    void unpinnedOriginateGeneratesChannelUuid() {
        EslClient connected = connected();

        String returned = connected.originate("+918000000001", "+919800000002",
                "test-gateway", "external");

        assertThat(returned).isNotBlank().isNotEqualTo("fake-job-1");
        // Must be parseable as the UUID it claims to be.
        assertThat(UUID.fromString(returned)).isNotNull();
    }

    // =====================================================================
    // Sequence C / D - event framing
    // =====================================================================

    @Test
    @DisplayName("SEQ-3: a plain event is delivered, with Event-Name read from the BODY")
    void plainEventIsDelivered() throws Exception {
        List<EslEvent> received = new CopyOnWriteArrayList<>();
        CountDownLatch subscribed = new CountDownLatch(1);
        server.onConnect(subscribed::countDown);

        startEventLoop(received);

        assertThat(subscribed.await(5, TimeUnit.SECONDS))
                .as("client must connect to the fake server")
                .isTrue();
        assertThat(server.awaitCommand("event plain", 5000))
                .as("client must issue the event subscription before events are pushed")
                .isTrue();

        String callUuid = UUID.randomUUID().toString();
        server.pushEvent("CHANNEL_ANSWER", callUuid);

        assertThat(awaitCount(received, 1, 5000))
                .as("a subscribed event must actually be delivered")
                .isTrue();
        EslEvent event = received.get(0);
        assertThat(event.getEventName()).isEqualTo("CHANNEL_ANSWER");
        assertThat(event.getCallUuid()).isEqualTo(callUuid);
    }

    @Test
    @DisplayName("SEQ-4: an event fragmented across TCP writes is reassembled, not dropped")
    void fragmentedEventIsReassembled() throws Exception {
        List<EslEvent> received = new CopyOnWriteArrayList<>();
        CountDownLatch subscribed = new CountDownLatch(1);
        server.onConnect(subscribed::countDown);

        startEventLoop(received);

        assertThat(subscribed.await(5, TimeUnit.SECONDS))
                .as("client must connect to the fake server")
                .isTrue();
        assertThat(server.awaitCommand("event plain", 5000))
                .as("client must issue the event subscription before events are pushed")
                .isTrue();

        String callUuid = UUID.randomUUID().toString();
        // Split the frame into small chunks, so the header block and the body
        // arrive in separate reads.
        server.pushFragmentedEvent("CHANNEL_HANGUP",
                Map.of("Call-UUID", callUuid, "Hangup-Cause", "NORMAL_CLEARING"),
                7, 3, 11, 5, 2);

        assertThat(awaitCount(received, 1, 8000))
                .as("a fragmented event must be reassembled, not silently dropped")
                .isTrue();
        assertThat(received.get(0).getEventName()).isEqualTo("CHANNEL_HANGUP");
        assertThat(received.get(0).getCallUuid()).isEqualTo(callUuid);
        assertThat(received.get(0).getHangupCause()).isEqualTo("NORMAL_CLEARING");
    }

    @Test
    @DisplayName("SEQ-4a: two events in one TCP write are parsed as two independent events")
    void coalescedEventsAreParsedIndependently() throws Exception {
        List<EslEvent> received = new CopyOnWriteArrayList<>();
        CountDownLatch subscribed = new CountDownLatch(1);
        server.onConnect(subscribed::countDown);

        startEventLoop(received);

        assertThat(subscribed.await(5, TimeUnit.SECONDS))
                .as("client must connect to the fake server")
                .isTrue();
        assertThat(server.awaitCommand("event plain", 5000))
                .as("client must issue the event subscription before events are pushed")
                .isTrue();

        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        server.pushCoalescedEvents(List.of(
                new String[] {"CHANNEL_ANSWER", first},
                new String[] {"CHANNEL_HANGUP", second}));

        assertThat(awaitCount(received, 2, 8000))
                .as("both coalesced events must be delivered")
                .isTrue();
        assertThat(received.stream().map(EslEvent::getEventName))
                .containsExactly("CHANNEL_ANSWER", "CHANNEL_HANGUP");
        assertThat(received.get(0).getCallUuid()).isEqualTo(first);
        assertThat(received.get(1).getCallUuid()).isEqualTo(second);
    }

    @Test
    @DisplayName("SEQ-4b: an unsubscribed event is ignored rather than mis-delivered")
    void unsubscribedEventIsIgnored() throws Exception {
        List<EslEvent> received = new CopyOnWriteArrayList<>();
        CountDownLatch subscribed = new CountDownLatch(1);
        server.onConnect(subscribed::countDown);

        startEventLoop(received);

        assertThat(subscribed.await(5, TimeUnit.SECONDS))
                .as("client must connect to the fake server")
                .isTrue();
        assertThat(server.awaitCommand("event plain", 5000))
                .as("client must issue the event subscription before events are pushed")
                .isTrue();

        // CHANNEL_DESTROY is a real FreeSWITCH event but is NOT subscribed.
        server.pushEvent("CHANNEL_DESTROY", UUID.randomUUID().toString());
        // ...and a subscribed one immediately after, to prove the loop did not
        // die or desynchronise on the unrecognised frame.
        server.pushEvent("PLAYBACK_STOP", UUID.randomUUID().toString());

        assertThat(awaitCount(received, 1, 8000)).isTrue();
        assertThat(received.get(0).getEventName()).isEqualTo("PLAYBACK_STOP");
    }

    // =====================================================================
    // Message-level unit coverage of the framing primitives
    // =====================================================================

    @Test
    @DisplayName("MSG-1: the verdict comes from Reply-Text, not from raw socket text")
    void verdictComesFromReplyText() {
        EslMessage ok = new EslMessage("command/reply",
                Map.of("Reply-Text", "+OK accepted"), "");
        EslMessage err = new EslMessage("command/reply",
                Map.of("Reply-Text", "-ERR no such channel"), "");

        assertThat(ok.isOk()).isTrue();
        assertThat(ok.isError()).isFalse();
        assertThat(ok.verdict()).isEqualTo("accepted");

        assertThat(err.isError()).isTrue();
        assertThat(err.isOk()).isFalse();
        assertThat(err.verdict()).isEqualTo("no such channel");
    }

    @Test
    @DisplayName("MSG-2: a message with no Reply-Text is neither ok nor error")
    void verdictlessMessageIsUndecided() {
        EslMessage verdictless = new EslMessage("command/reply", Map.of(), "");

        assertThat(verdictless.isOk()).isFalse();
        assertThat(verdictless.isError()).isFalse();
        assertThat(verdictless.replyText()).isNull();
    }

    @Test
    @DisplayName("MSG-3: header lookup is case-insensitive")
    void headerLookupIsCaseInsensitive() {
        EslMessage message = new EslMessage("command/reply",
                Map.of("job-uuid", "job-1", "Reply-Text", "+OK"), "");

        assertThat(message.header("Job-UUID")).isEqualTo("job-1");
        assertThat(message.header("JOB-UUID")).isEqualTo("job-1");
        assertThat(message.header("Absent")).isNull();
    }

    @Test
    @DisplayName("MSG-4: the job uuid is readable as a header and is distinct from the channel uuid")
    void jobUuidIsAHeader() {
        // Pins the protocol fact the old client got wrong: Job-UUID is its own
        // header, and it is not the channel identity.
        EslMessage reply = new EslMessage("command/reply",
                Map.of("Reply-Text", "+OK Job-UUID: 7f0c...", "Job-UUID", "7f0c-job"), "");

        assertThat(reply.header("Job-UUID")).isEqualTo("7f0c-job");
        assertThat(reply.isOk()).isTrue();
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private void startEventLoop(List<EslEvent> received) {
        Thread thread = new Thread(() -> {
            // The handshake must complete before subscribing: subscribeAndProcessEvents
            // deliberately refuses to run on an unauthenticated connection, which is
            // itself part of the contract under test.
            try (EslClient loopClient = new EslClient(properties())) {
                loopClient.connect();
                loopClient.subscribeAndProcessEvents(received::add);
            } catch (RuntimeException e) {
                // The loop exits when the test closes the server; that is not a
                // failure of the test itself.
            }
        }, "esl-protocol-test-loop");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean awaitCount(List<EslEvent> received, int count, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (received.size() >= count) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

}
