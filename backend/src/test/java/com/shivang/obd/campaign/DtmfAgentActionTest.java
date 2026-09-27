package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.audio.AudioAssetEntity;
import com.shivang.obd.audio.AudioAssetRepository;
import com.shivang.obd.audio.AudioAssetStatus;
import com.shivang.obd.voice.agent.AgentConnectTrigger;
import com.shivang.obd.voice.agent.AgentEligibility;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.dtmf.DtmfInteractionRepository;
import com.shivang.obd.voice.dtmf.DtmfResultService;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * VB-3 unit tests: the DTMF → CONNECT_BY_AGENT integration — a VALID result
 * with action=CONNECT_BY_AGENT dispatches through the AgentConnectTrigger
 * boundary; the DTMF layer never touches agent state, TERMINATE action and
 * invalid results still hang up (spec §13/§17).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DtmfAgentActionTest {

    private static final UUID TENANT = UUID.fromString("6a000000-0000-4000-8000-0000000000a1");
    private static final UUID SESSION = UUID.fromString("6e000000-0000-4000-8000-0000000000e1");
    private static final UUID ATTEMPT = UUID.fromString("6f000000-0000-4000-8000-0000000000f1");
    private static final UUID CAMPAIGN = UUID.fromString("6c000000-0000-4000-8000-0000000000c1");
    private static final UUID EXECUTION = UUID.fromString("6c000000-0000-4000-8000-0000000000e1");
    private static final UUID ASSET = UUID.fromString("6d000000-0000-4000-8000-0000000000d1");

    @Mock
    private CallSessionRepository callSessionRepository;

    @Mock
    private CallLegRepository callLegRepository;

    @Mock
    private CallAttemptRepository callAttemptRepository;

    @Mock
    private CampaignRepository campaignRepository;

    @Mock
    private CampaignExecutionRepository executionRepository;

    @Mock
    private CampaignConfigurationService configurationService;

    @Mock
    private AudioAssetRepository audioAssetRepository;

    @Mock
    private DtmfInteractionRepository interactionRepository;

    @Mock
    private DtmfResultService resultService;

    @Mock
    private VoiceMediaController mediaController;

    @Mock
    private ObjectProvider<AgentConnectTrigger> agentConnectTrigger;

    @Mock
    private AgentConnectTrigger connectTrigger;

    private DtmfExecutionService service;

    @SuppressWarnings("unchecked")
    private DtmfExecutionService serviceWithAction(String action) {
        var jsonMapper = new tools.jackson.databind.ObjectMapper();
        var typeConfig = jsonMapper.createObjectNode();
        var dtmf = typeConfig.putObject("dtmf");
        dtmf.put("expected", "1");
        dtmf.put("action", action);

        CampaignEntity campaign = new CampaignEntity();
        campaign.setId(CAMPAIGN);
        campaign.setTenantId(TENANT);
        campaign.setCampaignType(CampaignType.DTMF);
        campaign.setContentMode(ContentMode.AUDIO);
        campaign.setAudioAssetId(ASSET);
        campaign.setTypeConfig(typeConfig);

        when(campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(CAMPAIGN, TENANT))
                .thenReturn(Optional.of(campaign));
        // VB-6A correction: the execution resolves its mandatory immutable
        // snapshot — the snapshot carries the same DTMF config as the live
        // campaign fixture (no live fallback).
        var execution = new CampaignExecution();
        execution.setId(EXECUTION);
        execution.setCampaignId(CAMPAIGN);
        execution.setTenantId(TENANT);
        execution.setConfigurationSnapshotId(UUID.randomUUID());
        when(executionRepository.findByIdAndDeletedAtIsNull(EXECUTION))
                .thenReturn(Optional.of(execution));
        when(configurationService.requireExecutionSnapshot(execution))
                .thenReturn(DtmfExecutionServiceTest.snapshot(TENANT, CAMPAIGN, ASSET));

        return new DtmfExecutionService(callSessionRepository, callLegRepository,
                callAttemptRepository, campaignRepository, executionRepository,
                audioAssetRepository,
                // Real canonical validator (VB-5E) over the mocked audio repo.
                new CampaignResourceValidationService(null, audioAssetRepository, null),
                new CampaignRuntimeConfigResolver(configurationService),
                interactionRepository, resultService, mediaController, agentConnectTrigger);
    }

    private void stubAnswerPath(CallSession session) {
        when(callSessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                .thenReturn(Optional.of(session));
        when(callSessionRepository.findById(SESSION)).thenReturn(Optional.of(session));
        when(callAttemptRepository.findById(ATTEMPT)).thenReturn(Optional.of(attempt()));
        var asset = new AudioAssetEntity();
        asset.setId(ASSET);
        asset.setTenantId(TENANT);
        asset.setStatus(AudioAssetStatus.APPROVED);
        asset.setStorageReference("/srv/media/promo.wav");
        when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(ASSET, TENANT))
                .thenReturn(Optional.of(asset));
        when(interactionRepository.findByCallSessionIdAndDeletedAtIsNull(SESSION))
                .thenReturn(Optional.empty());
        when(interactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(callSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private com.shivang.obd.campaign.CallAttempt attempt() {
        var attempt = new com.shivang.obd.campaign.CallAttempt();
        attempt.setId(ATTEMPT);
        attempt.setTenantId(TENANT);
        attempt.setCampaignId(CAMPAIGN);
        attempt.setExecutionId(EXECUTION);
        return attempt;
    }

    @Test
    @DisplayName("DA1: VALID digit with CONNECT_BY_AGENT action dispatches the connect trigger")
    void validDigitDispatchesConnect() {
        DtmfExecutionService svc = serviceWithAction("CONNECT_BY_AGENT");

        CallSession session = new CallSession();
        session.setId(SESSION);
        session.setTenantId(TENANT);
        session.setStatus(CallSessionStatus.WAITING_FOR_DTMF);
        stubAnswerPath(session);

        var interaction = new com.shivang.obd.voice.dtmf.DtmfInteraction();
        interaction.setId(UUID.fromString("69000000-0000-4000-8000-000000000091"));
        interaction.setTenantId(TENANT);
        interaction.setCallSessionId(SESSION);
        interaction.setCallAttemptId(ATTEMPT);
        interaction.setCampaignId(CAMPAIGN);
        interaction.setExpectedInput("1");
        interaction.setMaxDigits(1);
        interaction.setTimeoutSecs(10);
        interaction.setCollectedDigits("");
        interaction.setResult(com.shivang.obd.voice.dtmf.DtmfResultType.COLLECTING);
        interaction.setExpiresAt(java.time.Instant.now().plusSeconds(10));
        interaction.setActionType("CONNECT_BY_AGENT");
        when(interactionRepository.findByCallSessionIdAndDeletedAtIsNull(SESSION))
                .thenReturn(Optional.of(interaction));
        when(resultService.finalizeInteraction(any(), any(), any(), any()))
                .thenReturn(Optional.of(interaction));
        when(agentConnectTrigger.getIfAvailable()).thenReturn(connectTrigger);
        when(connectTrigger.connectByAgent(any(), any()))
                .thenReturn(AgentEligibility.rejected(
                        com.shivang.obd.voice.agent.AgentReasons.AGENT_UNAVAILABLE));

        svc.onDtmfDigit(SESSION, ATTEMPT, "1");

        verify(connectTrigger).connectByAgent(SESSION, ATTEMPT);
        // The connect flow owns finalization — no immediate hangup by the DTMF layer.
        verify(mediaController, org.mockito.Mockito.never()).terminateCall(any(), any());
    }

    @Test
    @DisplayName("DA2: VALID digit with default TERMINATE action hangs up (VB-2 behavior preserved)")
    void validDigitTerminateActionHangsUp() {
        DtmfExecutionService svc = serviceWithAction("TERMINATE");

        CallSession session = new CallSession();
        session.setId(SESSION);
        session.setTenantId(TENANT);
        session.setStatus(CallSessionStatus.WAITING_FOR_DTMF);
        stubAnswerPath(session);

        var interaction = new com.shivang.obd.voice.dtmf.DtmfInteraction();
        interaction.setId(UUID.fromString("69000000-0000-4000-8000-000000000091"));
        interaction.setTenantId(TENANT);
        interaction.setCallSessionId(SESSION);
        interaction.setCallAttemptId(ATTEMPT);
        interaction.setExpectedInput("1");
        interaction.setMaxDigits(1);
        interaction.setTimeoutSecs(10);
        interaction.setCollectedDigits("");
        interaction.setResult(com.shivang.obd.voice.dtmf.DtmfResultType.COLLECTING);
        interaction.setExpiresAt(java.time.Instant.now().plusSeconds(10));
        interaction.setActionType("TERMINATE");
        when(interactionRepository.findByCallSessionIdAndDeletedAtIsNull(SESSION))
                .thenReturn(Optional.of(interaction));
        when(resultService.finalizeInteraction(any(), any(), any(), any()))
                .thenReturn(Optional.of(interaction));

        svc.onDtmfDigit(SESSION, ATTEMPT, "1");

        verify(mediaController).terminateCall(SESSION, null);
        verify(agentConnectTrigger, org.mockito.Mockito.never()).getIfAvailable();
    }

    @Test
    @DisplayName("DA3: INVALID digit never dispatches the connect action")
    void invalidDigitNeverConnects() {
        DtmfExecutionService svc = serviceWithAction("CONNECT_BY_AGENT");

        CallSession session = new CallSession();
        session.setId(SESSION);
        session.setTenantId(TENANT);
        session.setStatus(CallSessionStatus.WAITING_FOR_DTMF);
        stubAnswerPath(session);

        var interaction = new com.shivang.obd.voice.dtmf.DtmfInteraction();
        interaction.setId(UUID.fromString("69000000-0000-4000-8000-000000000092"));
        interaction.setTenantId(TENANT);
        interaction.setCallSessionId(SESSION);
        interaction.setCallAttemptId(ATTEMPT);
        interaction.setExpectedInput("1");
        interaction.setMaxDigits(1);
        interaction.setTimeoutSecs(10);
        interaction.setCollectedDigits("");
        interaction.setResult(com.shivang.obd.voice.dtmf.DtmfResultType.COLLECTING);
        interaction.setExpiresAt(java.time.Instant.now().plusSeconds(10));
        interaction.setActionType("CONNECT_BY_AGENT");
        when(interactionRepository.findByCallSessionIdAndDeletedAtIsNull(SESSION))
                .thenReturn(Optional.of(interaction));
        when(resultService.finalizeInteraction(any(), any(), any(), any()))
                .thenReturn(Optional.of(interaction));
        when(agentConnectTrigger.getIfAvailable()).thenReturn(connectTrigger);

        svc.onDtmfDigit(SESSION, ATTEMPT, "2");

        verify(connectTrigger, org.mockito.Mockito.never()).connectByAgent(any(), any());
        verify(mediaController).terminateCall(SESSION, null);
    }
}
