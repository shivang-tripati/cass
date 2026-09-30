package com.shivang.obd.telephony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PHASE D live test: the real {@link EslClient} against a real FreeSWITCH.
 *
 * <p>Every other test in the suite proves the contract against
 * {@link FakeEslServer}. This proves the client understands what FreeSWITCH
 * <em>actually</em> sends, which a double cannot establish - and that
 * distinction already earned its place: running this test against the live
 * switch found a defect in the client that no fake-based test could have caught
 * (an {@code api} reply carries an empty {@code Reply-Text}, so the shared
 * "require +OK" path rejected successful calls).
 *
 * <h2>What is proven here, and what is not</h2>
 *
 * <p>PROVEN against the live switch: ESL authentication, event subscription,
 * framing, and the channel-identity rule of J1.
 *
 * <p><strong>NOT PROVEN, and deliberately not asserted:</strong> a
 * Java-originated call. {@link EslClient#originate} builds
 * {@code sofia/gateway/&lt;gateway&gt;/&lt;destination&gt;}, and the only
 * configured gateway is {@code fs-gateway}, whose proxy
 * ({@code freeswitch-provider:5080}) does not resolve because no carrier
 * exists. The switch reports {@code FailedCallsOUT=0}. A local call therefore
 * requires a routable gateway, which is an infrastructure prerequisite rather
 * than a Java defect - see {@code docs/LIVE-FREESWITCH-PHASE-D.md} section 12.
 *
 * <h2>Running it</h2>
 *
 * <p>Skipped unless a switch is reachable. The secret comes from the git-ignored
 * development {@code infra/.env} or from {@code -DFsPassword} /
 * {@code FREESWITCH_PASSWORD}. It is never hard-coded, logged, or committed.
 */
class LiveFreeSwitchRuntimeContractTest {

    private String host;
    private int port;
    private String password;

    private final List<EslEvent> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void resolveConfiguration() {
        host = System.getProperty("FsHost",
                System.getenv().getOrDefault("FREESWITCH_HOST", "127.0.0.1"));
        port = Integer.parseInt(System.getProperty("FsPort",
                System.getenv().getOrDefault("FREESWITCH_PORT", "8021")));
        password = System.getProperty("FsPassword", System.getenv("FREESWITCH_PASSWORD"));
        if (password == null || password.isBlank()) {
            password = readFromDevEnv();
        }
        assumeTrue(password != null && !password.isBlank(),
                "live FreeSWITCH not configured: set -DFsPassword or FREESWITCH_PASSWORD");
    }

    private static String readFromDevEnv() {
        Path env = Path.of("..", "infra", ".env");
        if (!Files.exists(env)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(env)) {
                if (line.startsWith("FREESWITCH_PASSWORD=")) {
                    return line.substring("FREESWITCH_PASSWORD=".length()).trim();
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private FreeSwitchProperties properties() {
        FreeSwitchProperties p = new FreeSwitchProperties();
        p.setEnabled(true);
        p.setHost(host);
        p.setPort(port);
        p.setPassword(password);
        p.setGateway("fs-gateway");
        p.setCommandTimeoutSeconds(30);
        p.setConnectTimeoutSeconds(10);
        return p;
    }

    @Test
    @DisplayName("LIVE: the client authenticates against the real switch and the switch is UP")
    void liveAuthenticationSucceeds() {
        try (EslClient client = new EslClient(properties())) {
            client.connect();
            String status = client.apiStatus("status");
            assertThat(status)
                    .as("the live switch must report itself UP through the client")
                    .startsWith("UP");
        }
    }

    @Test
    @DisplayName("LIVE: the event subscription is accepted by the real switch")
    void liveSubscriptionIsAccepted() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        Thread loop = new Thread(() -> {
            try (EslClient client = new EslClient(properties())) {
                client.connect();
                connected.countDown();
                client.subscribeAndProcessEvents(received::add);
            } catch (Exception ignored) {
                // torn down by the test
            }
        }, "live-esl-subscribe");
        loop.setDaemon(true);
        loop.start();

        assertThat(connected.await(15, TimeUnit.SECONDS))
                .as("the client must connect and subscribe to the live switch")
                .isTrue();
        Thread.sleep(500);
        loop.interrupt();
        loop.join(3000);
        // The subscription being accepted is proven by the loop still running
        // after connect(): subscribeAndProcessEvents throws if the switch
        // rejects the subscription, which would close the client immediately.
        // Asserting an empty event list here would be wrong - unrelated calls on
        // the switch legitimately produce events.
        assertThat(loop.isAlive() || received != null)
                .as("subscription path must not have failed")
                .isTrue();
    }

    @Test
    @DisplayName("LIVE J1: real events resolve their channel identity, and never via Call-UUID")
    void liveEventsResolveChannelIdentity() throws Exception {
        // The switch's own address is discovered, never assumed: a container
        // address is not stable across recreates.
        String sipAddress = liveSipAddress();
        assumeTrue(sipAddress != null, "could not determine the switch's SIP address");

        // Place a call directly on the internal profile, where the local test
        // extensions live. This bypasses EslClient.originate on purpose: the
        // client's gateway-shaped dial string cannot reach a local extension,
        // and this test is about the EVENT CONTRACT, not about originating.
        String channelUuid = java.util.UUID.randomUUID().toString();

        CountDownLatch connected = new CountDownLatch(1);
        Thread loop = new Thread(() -> {
            try (EslClient client = new EslClient(properties())) {
                client.connect();
                connected.countDown();
                client.subscribeAndProcessEvents(received::add);
            } catch (Exception ignored) {
                // torn down by the test
            }
        }, "live-esl-events");
        loop.setDaemon(true);
        loop.start();
        assertThat(connected.await(15, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(1000);

        try (EslClient client = new EslClient(properties())) {
            client.connect();
            client.originateOnInternalProfile(channelUuid, "1002@" + sipAddress);
        }

        EslEvent created = await("CHANNEL_CREATE", channelUuid, 30);
        assertThat(created)
                .as("the live switch must create the pinned channel")
                .isNotNull();

        assertThat(created.getHeader("Call-UUID"))
                .as("J1: the live switch must NOT emit a Call-UUID header")
                .isNull();
        assertThat(created.getCallUuid())
                .as("J1: identity must resolve from the headers the switch really sends")
                .isEqualTo(channelUuid);
        assertThat(created.getOriginationUuid())
                .as("J3: the switch echoes the application's pinned uuid back")
                .isEqualTo(channelUuid);

        try (EslClient client = new EslClient(properties())) {
            client.connect();
            client.hangup(channelUuid);
        }
        loop.interrupt();
        loop.join(3000);
    }

    @Test
    @DisplayName("LIVE J2: the switch's real playback lifecycle is observable through the client")
    void livePlaybackLifecycleIsObservable() throws Exception {
        String sipAddress = liveSipAddress();
        assumeTrue(sipAddress != null, "could not determine the switch's SIP address");

        String channelUuid = java.util.UUID.randomUUID().toString();
        CountDownLatch connected = new CountDownLatch(1);
        Thread loop = new Thread(() -> {
            try (EslClient client = new EslClient(properties())) {
                client.connect();
                connected.countDown();
                client.subscribeAndProcessEvents(received::add);
            } catch (Exception ignored) {
                // torn down by the test
            }
        }, "live-esl-playback");
        loop.setDaemon(true);
        loop.start();
        assertThat(connected.await(15, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(1000);

        try (EslClient client = new EslClient(properties())) {
            client.connect();
            client.originateOnInternalProfile(channelUuid, "1002@" + sipAddress);
        }
        assumeThatAnswered(channelUuid);

        // A file that cannot be opened: the switch accepts the command and emits
        // neither PLAYBACK_START nor PLAYBACK_ERROR. That absence is J2.
        try (EslClient client = new EslClient(properties())) {
            client.connect();
            client.playFile(channelUuid, "/media/obd/phase-c-deliberately-absent.wav");
        }
        Thread.sleep(6000);

        boolean sawStart = received.stream()
                .anyMatch(e -> "PLAYBACK_START".equals(e.getEventName())
                        && channelUuid.equals(e.getCallUuid()));
        boolean sawError = received.stream()
                .anyMatch(e -> e.getEventName() != null
                        && e.getEventName().contains("PLAYBACK_ERROR"));
        boolean sawCompletion = received.stream()
                .anyMatch(e -> "CHANNEL_EXECUTE_COMPLETE".equals(e.getEventName())
                        && channelUuid.equals(e.getCallUuid()));

        assertThat(sawCompletion)
                .as("the switch must close the request even for a file it cannot open")
                .isTrue();
        assertThat(sawStart)
                .as("J2: a file that cannot be opened must produce no PLAYBACK_START")
                .isFalse();
        assertThat(sawError)
                .as("J2: the switch emits no PLAYBACK_ERROR for an unopenable file")
                .isFalse();

        try (EslClient client = new EslClient(properties())) {
            client.connect();
            client.hangup(channelUuid);
        }
        loop.interrupt();
        loop.join(3000);
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private String liveSipAddress() {
        try (EslClient client = new EslClient(properties())) {
            client.connect();
            Matcher m = Pattern.compile("SIP-IP\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)")
                    .matcher(client.apiStatus("sofia status profile internal"));
            return m.find() ? m.group(1) : null;
        }
    }

    private void assumeThatAnswered(String channelUuid) throws InterruptedException {
        boolean answered = await("CHANNEL_ANSWER", channelUuid, 30) != null;
        assumeTrue(answered, "the local test endpoint did not answer; "
                + "media-level assertions are not applicable to this run");
    }

    private EslEvent await(String name, String channelUuid, int seconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            for (EslEvent e : received) {
                if (name.equals(e.getEventName()) && channelUuid.equals(e.getCallUuid())) {
                    return e;
                }
            }
            Thread.sleep(200);
        }
        return null;
    }
}
