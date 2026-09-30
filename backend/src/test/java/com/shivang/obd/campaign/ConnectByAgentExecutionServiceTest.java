package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.campaign.config.AgentSelectionStrategy;
import com.shivang.obd.campaign.config.ConnectByAgentCampaignConfig;
import com.shivang.obd.voice.agent.AgentConnectRequest;
import com.shivang.obd.voice.agent.AgentConnectTrigger;
import com.shivang.obd.voice.agent.AgentEligibility;
import com.shivang.obd.voice.agent.AgentReasons;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * VB-7A: the CONNECT_BY_AGENT campaign execution path.
 *
 * <p>The regression this class exists to prevent: before VB-7A a
 * CONNECT_BY_AGENT <em>campaign</em> had no runtime. Every {@code PlaybackTrigger}
 * guards on campaign type, the dialer gates on nothing, so such a call was
 * answered and then silently ignored. These tests assert the opposite: the type
 * now reaches the agent runtime, and no other campaign type is diverted into it.
 */
class ConnectByAgentExecutionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    private static final UUID CAMPAIGN_ID =
            UUID.fromString("cc000000-0000-4000-8000-0000000000c1");
    private static final UUID EXECUTION_ID =
            UUID.fromString("ee000000-0000-4000-8000-0000000000e1");
    private static final UUID ATTEMPT_ID = UUID.fromString("bb000000-0000-4000-8000-0000000000b1");
    private static final UUID SESSION_ID = UUID.fromString("dd000000-0000-4000-8000-0000000000d1");
    private static final UUID QUEUE_ID = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301");

    private CallSessionRepository callSessionRepository;
    private CallAttemptRepository callAttemptRepository;
    private CampaignExecutionRepository executionRepository;
    private CampaignConfigurationService configurationService;
    private VoiceMediaController mediaController;
    private AgentConnectTrigger agentConnectTrigger;

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentConnectTrigger> triggerProvider =
            mock(ObjectProvider.class);

    private ConnectByAgentExecutionService service;
    private CallSession session;
    private CallAttempt attempt;

    @BeforeEach
    void setUp() {
        callSessionRepository = mock(CallSessionRepository.class);
        callAttemptRepository = mock(CallAttemptRepository.class);
        executionRepository = mock(CampaignExecutionRepository.class);
        configurationService = mock(CampaignConfigurationService.class);
        mediaController = mock(VoiceMediaController.class);
        agentConnectTrigger = mock(AgentConnectTrigger.class);

        when(triggerProvider.getIfAvailable()).thenReturn(agentConnectTrigger);
        when(agentConnectTrigger.connectByAgent(any(), any(), any()))
                .thenReturn(AgentEligibility.rejected(AgentReasons.AGENT_NOT_AVAILABLE));

        service = new ConnectByAgentExecutionService(
                callSessionRepository, callAttemptRepository, executionRepository,
                new CampaignRuntimeConfigResolver(configurationService),
                mediaController, triggerProvider);

        session = new CallSession();
        session.setId(SESSION_ID);
        session.setTenantId(TENANT_A);
        session.setStatus(CallSessionStatus.ANSWERED);
        session.setProviderCallId("fs-uuid-cba-1");
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(session));

        attempt = new CallAttempt();
        attempt.setId(ATTEMPT_ID);
        attempt.setTenantId(TENANT_A);
        attempt.setCampaignId(CAMPAIGN_ID);
        attempt.setExecutionId(EXECUTION_ID);
        when(callAttemptRepository.findByIdAndDeletedAtIsNull(ATTEMPT_ID))
                .thenReturn(Optional.of(attempt));

        var execution = new CampaignExecution();
        execution.setId(EXECUTION_ID);
        execution.setCampaignId(CAMPAIGN_ID);
        execution.setTenantId(TENANT_A);
        execution.setConfigurationSnapshotId(UUID.randomUUID());
        when(executionRepository.findByIdAndDeletedAtIsNull(EXECUTION_ID))
                .thenReturn(Optional.of(execution));
    }

    /** Freezes a CONNECT_BY_AGENT snapshot with the given configuration payload. */
    private void stubConnectByAgentSnapshot(JsonNode typeConfig) {
        when(configurationService.requireExecutionSnapshot(any(CampaignExecution.class)))
                .thenReturn(CampaignExecutionConfiguration.materialize(
                        CAMPAIGN_ID, TENANT_A,
                        new CampaignConfigurationSnapshot(
                                CampaignType.CONNECT_BY_AGENT, null, null, null, null, null,
                                null, null, null, null, null, null,
                                0, null, RetryStrategy.FIXED, null, null, null,
                                typeConfig, false, null),
                        java.time.Instant.now()));
    }

    private static String connectByAgentPayload(int ringSeconds) {
        return "{\"connectByAgent\": {\"queueId\": \"" + QUEUE_ID + "\", "
                + "\"selectionStrategy\": \"LEAST_ACTIVE_RESERVATIONS\", "
                + "\"ringDurationSeconds\": " + ringSeconds + "}}";
    }

    // ------------------------------------------------------------------
    // E. runtime — CONNECT_BY_AGENT actually reaches the agent runtime
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E17. an answered CONNECT_BY_AGENT campaign reaches the agent runtime "
            + "with the frozen queue and ring window")
    void connectByAgentCampaignReachesTheAgentRuntime() {
        stubConnectByAgentSnapshot(parse(connectByAgentPayload(45)));

        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        ArgumentCaptor<AgentConnectRequest> request =
                ArgumentCaptor.forClass(AgentConnectRequest.class);
        verify(agentConnectTrigger).connectByAgent(
                eq(SESSION_ID), eq(ATTEMPT_ID), request.capture());
        assertThat(request.getValue().queueId()).isEqualTo(QUEUE_ID);
        assertThat(request.getValue().ringDurationSeconds()).isEqualTo(45);
        assertThat(request.getValue().effectiveRingSeconds()).isEqualTo(45);
        assertThat(request.getValue().isQueueScoped()).isTrue();
    }

    @Test
    @DisplayName("E17b. the runtime guard: CONNECT_BY_AGENT is not the PLAYFILE path — "
            + "the call is connected, not left answered and silent")
    void connectByAgentIsNotSilentlyIgnored() {
        stubConnectByAgentSnapshot(parse(connectByAgentPayload(60)));

        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        // The defining assertion: the agent runtime was invoked at all. Before
        // VB-7A this call was answered and every trigger declined, so nothing
        // downstream ever ran.
        verify(agentConnectTrigger).connectByAgent(any(), any(), any());
        // And it was not handed off to the media layer instead.
        verify(mediaController, never()).terminateCall(eq(SESSION_ID), any());
        assertThat(session.getFailureCode()).isNull();
    }

    @Test
    @DisplayName("E17c. a duplicate CHANNEL_ANSWER does not connect twice")
    void duplicateAnswerIsIdempotent() {
        stubConnectByAgentSnapshot(parse(connectByAgentPayload(60)));
        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        session.setStatus(CallSessionStatus.CONNECTING_AGENT);
        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        verify(agentConnectTrigger, org.mockito.Mockito.times(1))
                .connectByAgent(any(), any(), any());
    }

    @Test
    @DisplayName("E18. a PLAYFILE campaign is not diverted into the agent runtime")
    void playfileCampaignIsNotDiverted() {
        when(configurationService.requireExecutionSnapshot(any(CampaignExecution.class)))
                .thenReturn(CampaignExecutionConfiguration.materialize(
                        CAMPAIGN_ID, TENANT_A,
                        new CampaignConfigurationSnapshot(
                                CampaignType.PLAYFILE, null, null, ContentMode.AUDIO,
                                UUID.randomUUID(), null,
                                null, null, null, null, null, null,
                                0, null, RetryStrategy.FIXED, null, null, null,
                                parse("{\"playfile\":{}}"), false, null),
                        java.time.Instant.now()));

        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        verify(agentConnectTrigger, never()).connectByAgent(any(), any(), any());
        verify(agentConnectTrigger, never()).connectByAgent(any(), any());
    }

    @Test
    @DisplayName("E18b. a DTMF campaign with a CONNECT_BY_AGENT terminal action stays on the "
            + "DTMF path (the input-driven action is unchanged)")
    void dtmfCampaignIsNotDiverted() {
        when(configurationService.requireExecutionSnapshot(any(CampaignExecution.class)))
                .thenReturn(CampaignExecutionConfiguration.materialize(
                        CAMPAIGN_ID, TENANT_A,
                        new CampaignConfigurationSnapshot(
                                CampaignType.DTMF, null, null, ContentMode.AUDIO,
                                UUID.randomUUID(), null,
                                null, null, null, null, null, null,
                                0, null, RetryStrategy.FIXED, null, null, null,
                                parse("{\"dtmf\": {\"expected\": \"1\", "
                                        + "\"action\": \"CONNECT_BY_AGENT\"}}"), false, null),
                        java.time.Instant.now()));

        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        // DtmfExecutionService owns this path; this trigger must not also fire.
        verify(agentConnectTrigger, never()).connectByAgent(any(), any(), any());
    }

    @Test
    @DisplayName("E18c. a campaign with no attempt, or no session, is a no-op")
    void missingLinksAreNoOps() {
        stubConnectByAgentSnapshot(parse(connectByAgentPayload(60)));

        service.onAnswered(SESSION_ID, null);
        service.onAnswered(null, ATTEMPT_ID);
        when(callAttemptRepository.findByIdAndDeletedAtIsNull(ATTEMPT_ID))
                .thenReturn(Optional.empty());
        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        verify(agentConnectTrigger, never()).connectByAgent(any(), any(), any());
    }

    @Test
    @DisplayName("E19. a dispatched-but-declined connect is left to the connect service to "
            + "finalize; this boundary neither retries nor crashes")
    void declinedConnectIsNotRetriedHere() {
        stubConnectByAgentSnapshot(parse(connectByAgentPayload(60)));

        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        verify(agentConnectTrigger, org.mockito.Mockito.times(1))
                .connectByAgent(any(), any(), any());
        // The connect service owns the failure; the trigger must not invent a
        // second teardown on top of it.
        verify(mediaController, never()).terminateCall(eq(SESSION_ID), any());
    }

    @Test
    @DisplayName("E19b. a trigger that throws fails the call deterministically instead of "
            + "leaving it answered")
    void throwingTriggerFailsTheCall() {
        stubConnectByAgentSnapshot(parse(connectByAgentPayload(60)));
        when(agentConnectTrigger.connectByAgent(any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> service.onAnswered(SESSION_ID, ATTEMPT_ID))
                .doesNotThrowAnyException();

        assertThat(session.getStatus()).isEqualTo(CallSessionStatus.ANSWERED);
        assertThat(session.getFailureCode())
                .isEqualTo(ConnectByAgentExecutionService.AGENT_CONNECT_CONFIG_INVALID_CODE);
        verify(mediaController).terminateCall(SESSION_ID, "fs-uuid-cba-1");
    }

    @Test
    @DisplayName("E19c. a deployment without the agent layer fails the call instead of crashing")
    void missingTriggerFailsTheCall() {
        stubConnectByAgentSnapshot(parse(connectByAgentPayload(60)));
        when(triggerProvider.getIfAvailable()).thenReturn(null);

        assertThatCode(() -> service.onAnswered(SESSION_ID, ATTEMPT_ID))
                .doesNotThrowAnyException();

        assertThat(session.getFailureCode())
                .isEqualTo(ConnectByAgentExecutionService.AGENT_CONNECT_CONFIG_INVALID_CODE);
    }

    @Test
    @DisplayName("E19d. a CONNECT_BY_AGENT snapshot with an unreadable configuration fails the "
            + "call rather than leaving the callee listening to silence")
    void unreadableSnapshotFailsTheCall() {
        stubConnectByAgentSnapshot(parse("{\"connectByAgent\": {}}"));

        service.onAnswered(SESSION_ID, ATTEMPT_ID);

        assertThat(session.getFailureCode())
                .isEqualTo(ConnectByAgentExecutionService.AGENT_CONNECT_CONFIG_INVALID_CODE);
        verify(agentConnectTrigger, never()).connectByAgent(any(), any(), any());
    }

    @Test
    @DisplayName("E22. the frozen request is built from the snapshot's typed configuration")
    void requestIsBuiltFromTheSnapshot() {
        var config = ConnectByAgentCampaignConfig.of(
                QUEUE_ID, AgentSelectionStrategy.LEAST_ACTIVE_RESERVATIONS, 90);
        assertThat(config.effectiveRingSeconds()).isEqualTo(90);
        assertThat(config.toJson().get("connectByAgent").get("ringDurationSeconds").asInt())
                .isEqualTo(90);
    }

    private static JsonNode parse(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
