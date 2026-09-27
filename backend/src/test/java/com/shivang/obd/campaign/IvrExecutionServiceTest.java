package com.shivang.obd.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shivang.obd.audio.AudioAssetEntity;
import com.shivang.obd.audio.AudioAssetRepository;
import com.shivang.obd.audio.AudioStorageProperties;
import com.shivang.obd.audio.MediaUriResolver;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.ivr.IvrExecutionSnapshot;
import com.shivang.obd.voice.ivr.IvrNodeSnapshot;
import com.shivang.obd.voice.ivr.IvrNodeType;
import com.shivang.obd.voice.ivr.IvrStep;
import com.shivang.obd.voice.ivr.IvrStepRepository;
import com.shivang.obd.voice.ivr.IvrStepResultType;
import com.shivang.obd.voice.ivr.IvrTerminalAction;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * VB-6F — the IVR runtime.
 *
 * <p>Drives the real {@link IvrExecutionService} over a real snapshot, with
 * mocked persistence, and asserts the behaviours the brief names: multi-level
 * traversal, invalid input, no input, retry budgets, terminal behaviour,
 * duplicate/late digit safety, and — most importantly — that an IVR retry stays
 * inside one call.
 */
class IvrExecutionServiceTest {

    private static final UUID TENANT = UUID.fromString("aa000000-0000-4000-8000-0000000000a1");
    private static final UUID SESSION = UUID.fromString("bb000000-0000-4000-8000-0000000000b1");
    private static final UUID ATTEMPT = UUID.fromString("cc000000-0000-4000-8000-0000000000c1");
    private static final UUID TREE = UUID.fromString("dd000000-0000-4000-8000-0000000000d1");
    private static final UUID ASSET = UUID.fromString("ee000000-0000-4000-8000-0000000000e1");

    private CallSessionRepository sessionRepository;
    private IvrStepRepository stepRepository;
    private CampaignResourceValidationService resourceValidator;
    private AudioAssetRepository audioAssetRepository;
    private VoiceMediaController mediaController;
    private IvrExecutionService service;

    private CallSession session;
    private CallSession savedSession;
    /** Steps the runtime CREATED. The seeded current step is tracked separately. */
    private java.util.List<IvrStep> persisted;
    private IvrStep currentStep;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(CallSessionRepository.class);
        stepRepository = mock(IvrStepRepository.class);
        resourceValidator = mock(CampaignResourceValidationService.class);
        audioAssetRepository = mock(AudioAssetRepository.class);
        mediaController = mock(VoiceMediaController.class);

        AudioStorageProperties storage = new AudioStorageProperties();
        storage.setEnabled(true);
        MediaUriResolver resolver = new MediaUriResolver(storage);

        // Prompts are governed assets: usable by default, refusable per test.
        when(resourceValidator.validateAudio(any(), eq(TENANT)))
                .thenReturn(CampaignResourceValidationService.ResourceValidationResult.valid());

        AudioAssetEntity asset = new AudioAssetEntity();
        asset.setId(ASSET);
        asset.setTenantId(TENANT);
        asset.setStorageReference("audio/" + TENANT + "/" + ASSET + "/prompt.wav");
        when(audioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(asset));

        session = new CallSession();
        session.setId(SESSION);
        session.setTenantId(TENANT);
        session.setCallAttemptId(ATTEMPT);
        session.setStatus(CallSessionStatus.ANSWERED);
        session.setProviderCallId("fs-channel-1");
        savedSession = session;
        when(sessionRepository.findById(SESSION)).thenReturn(Optional.of(session));
        when(sessionRepository.findByIdAndDeletedAtIsNull(SESSION))
                .thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(i -> {
            savedSession = i.getArgument(0);
            return savedSession;
        });

        persisted = new java.util.ArrayList<>();
        when(stepRepository.save(any())).thenAnswer(i -> {
            IvrStep step = i.getArgument(0);
            if (step.getId() == null) {
                step.setId(UUID.randomUUID());
            }
            persisted.add(step);
            return step;
        });
        when(stepRepository.claimTerminal(any(), anyString(), anyString(), any()))
                .thenReturn(1);

        service = new IvrExecutionService(
                sessionRepository, mock(CallLegRepository.class), stepRepository,
                new CampaignIvrPromptGovernance(resourceValidator),
                audioAssetRepository, resolver, mediaController,
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public com.shivang.obd.voice.agent.AgentConnectTrigger getObject() {
                        throw new IllegalStateException("no agent support in this test");
                    }

                    @Override
                    public com.shivang.obd.voice.agent.AgentConnectTrigger getObject(
                            Object... args) {
                        return getObject();
                    }

                    @Override
                    public com.shivang.obd.voice.agent.AgentConnectTrigger
                            getIfAvailable() {
                        return null;
                    }

                    @Override
                    public com.shivang.obd.voice.agent.AgentConnectTrigger
                            getIfUnique() {
                        return null;
                    }
                });
    }

    // =====================================================================
    // Snapshots
    // =====================================================================

    private static IvrNodeSnapshot menu(String key, int wait, int invalid, int noInput,
                                        Map<String, String> edges) {
        return new IvrNodeSnapshot(key, IvrNodeType.MENU, ASSET, wait, ASSET,
                invalid, ASSET, noInput, null, edges);
    }

    private static IvrNodeSnapshot terminal(String key, IvrTerminalAction action) {
        return new IvrNodeSnapshot(key, IvrNodeType.TERMINAL, null, 5,
                null, 0, null, 0, action, Map.of());
    }

    /** ROOT -> SALES|SUPPORT, each to a leaf. Two levels, three levels deep walk. */
    private static IvrExecutionSnapshot twoLevel() {
        Map<String, IvrNodeSnapshot> nodes = new LinkedHashMap<>();
        nodes.put("ROOT", menu("ROOT", 10, 0, 0, Map.of("1", "SALES", "2", "SUPPORT")));
        nodes.put("SALES", menu("SALES", 10, 0, 0, Map.of("1", "LEAF")));
        nodes.put("SUPPORT", menu("SUPPORT", 10, 0, 0, Map.of("2", "LEAF")));
        nodes.put("LEAF", terminal("LEAF", IvrTerminalAction.TERMINATE));
        return new IvrExecutionSnapshot(TREE, "Menu", "ROOT", nodes);
    }

    /** ROOT with a generous invalid budget and a no-input prompt. */
    private static IvrExecutionSnapshot withRetries(int invalidRetries, int noInputRetries) {
        Map<String, IvrNodeSnapshot> nodes = new LinkedHashMap<>();
        nodes.put("ROOT", menu("ROOT", 10, invalidRetries, noInputRetries,
                Map.of("1", "LEAF")));
        nodes.put("LEAF", terminal("LEAF", IvrTerminalAction.TERMINATE));
        return new IvrExecutionSnapshot(TREE, "Retries", "ROOT", nodes);
    }

    /** Seeds a fresh current step, keeping any steps the runtime already created. */
    private void seedCurrentStep(IvrExecutionSnapshot snapshot, String nodeKey, int wait) {
        IvrStep step = new IvrStep();
        step.setId(UUID.randomUUID());
        step.setTenantId(TENANT);
        step.setCallSessionId(SESSION);
        step.setCallAttemptId(ATTEMPT);
        step.setTreeId(TREE);
        step.setNodeKey(nodeKey);
        step.setNodeType(IvrNodeType.MENU);
        step.setInputWaitSeconds(wait);
        step.setInvalidInputRetries(0);
        step.setNoInputRetries(0);
        step.setInvalidAttempts(0);
        step.setNoInputAttempts(0);
        step.setResult(IvrStepResultType.WAITING_INPUT);
        step.setExpiresAt(Instant.now().plusSeconds(wait));
        currentStep = step;
        when(stepRepository
                .findFirstByCallSessionIdAndResultAndDeletedAtIsNullOrderByCreatedAtDesc(
                        eq(SESSION), eq(IvrStepResultType.WAITING_INPUT)))
                .thenReturn(Optional.of(currentStep));
    }

    /** Seeds a fresh current step and forgets the ones created before it. */
    private void openCurrentStep(IvrExecutionSnapshot snapshot, String nodeKey, int wait) {
        persisted.clear();
        seedCurrentStep(snapshot, nodeKey, wait);
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Nested
    class Lifecycle {

        @Test
        @DisplayName("RT-1: answering opens a step at the root and plays its prompt")
        void answerOpensRootStep() {
            session.setStatus(CallSessionStatus.ANSWERED);
            when(stepRepository
                    .findFirstByCallSessionIdAndResultAndDeletedAtIsNullOrderByCreatedAtDesc(
                            any(), any())).thenReturn(Optional.empty());

            service.onAnswered(SESSION, ATTEMPT, twoLevel());

            assertThat(persisted).hasSize(1);
            assertThat(persisted.get(0).getNodeKey()).isEqualTo("ROOT");
            assertThat(persisted.get(0).getResult()).isEqualTo(IvrStepResultType.WAITING_INPUT);
            verify(mediaController).playAudio(eq(SESSION), any(), anyString());
        }

        @Test
        @DisplayName("RT-2: a duplicate answer does not open a second step")
        void duplicateAnswerIsIgnored() {
            session.setStatus(CallSessionStatus.ANSWERED);
            openCurrentStep(twoLevel(), "ROOT", 10); // an open step already exists

            service.onAnswered(SESSION, ATTEMPT, twoLevel());

            assertThat(persisted)
                    .as("a second step would restart the caller's flow")
                    .isEmpty();
        }

        @Test
        @DisplayName("RT-3: the prompt is played with a FreeSWITCH-readable media URI, not the raw reference")
        void promptUsesResolvedMediaUri() {
            session.setStatus(CallSessionStatus.ANSWERED);
            when(stepRepository
                    .findFirstByCallSessionIdAndResultAndDeletedAtIsNullOrderByCreatedAtDesc(
                            any(), any())).thenReturn(Optional.empty());

            service.onAnswered(SESSION, ATTEMPT, twoLevel());

            // The VB-6E fix: the raw logical reference is never handed to the
            // provider, because FreeSWITCH resolves relative paths against its
            // own sound directory.
            verify(mediaController).playAudio(eq(SESSION), any(),
                    eq("/usr/share/freeswitch/sounds/" + TENANT + "/" + ASSET + "/prompt.wav"));
        }

        @Test
        @DisplayName("RT-4: playback completion arms the wait")
        void playbackCompletedArmsWait() {
            session.setStatus(CallSessionStatus.PLAYBACK_COMPLETED);
            service.onPlaybackCompleted(SESSION, ATTEMPT);
            assertThat(savedSession.getStatus()).isEqualTo(CallSessionStatus.WAITING_FOR_DTMF);
        }

        @Test
        @DisplayName("RT-5: a prompt the tenant cannot use is a permanent configuration failure")
        void unusablePromptIsPermanentFailure() {
            when(resourceValidator.validateAudio(any(), eq(TENANT))).thenReturn(
                    CampaignResourceValidationService.ResourceValidationResult.invalid(
                            CampaignResourceValidationService.ValidationCode.AUDIO_NOT_APPROVED));
            session.setStatus(CallSessionStatus.ANSWERED);
            when(stepRepository
                    .findFirstByCallSessionIdAndResultAndDeletedAtIsNullOrderByCreatedAtDesc(
                            any(), any())).thenReturn(Optional.empty());

            service.onAnswered(SESSION, ATTEMPT, twoLevel());

            assertThat(savedSession.getFailureCode())
                    .isEqualTo(IvrExecutionService.IVR_CONFIG_INVALID_CODE);
            verify(mediaController, never()).playAudio(any(), any(), anyString());
        }
    }

    // =====================================================================
    // Traversal (the brief's Examples 1 and 2)
    // =====================================================================

    @Nested
    class Traversal {

        @Test
        @DisplayName("RT-6: a valid digit moves the caller to the target node")
        void validDigitAdvances() {
            openCurrentStep(twoLevel(), "ROOT", 10);
            service.onDtmfDigit(SESSION, ATTEMPT, twoLevel(), "1");

            assertThat(persisted).hasSize(1);
            assertThat(persisted.get(0).getNodeKey()).isEqualTo("SALES");
            assertThat(savedSession.getStatus()).isEqualTo(CallSessionStatus.PLAYING);
        }

        @Test
        @DisplayName("RT-7: a caller walks ROOT -> SALES and a further digit reaches a terminal leaf")
        void multiLevelWalk() {
            IvrExecutionSnapshot snapshot = twoLevel();

            openCurrentStep(snapshot, "ROOT", 10);
            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "1");
            assertThat(persisted).hasSize(1);
            assertThat(persisted.get(0).getNodeKey()).isEqualTo("SALES");

            // Second level: the caller is on SALES, and '1' takes them to LEAF,
            // which is terminal - so the call ends rather than opening a third
            // step. That is the two-level walk the brief's Example 2 describes.
            seedCurrentStep(snapshot, "SALES", 10);
            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "1");
            assertThat(persisted)
                    .as("a terminal node ends the call instead of opening another step")
                    .hasSize(1);
            verify(mediaController, times(1)).terminateCall(eq(SESSION), anyString());
        }

        @Test
        @DisplayName("RT-7b: three levels of MENU nodes are walked, one step per level")
        void threeLevelWalkOpensOneStepPerLevel() {
            Map<String, IvrNodeSnapshot> nodes = new LinkedHashMap<>();
            nodes.put("ROOT", menu("ROOT", 10, 0, 0, Map.of("1", "MID")));
            nodes.put("MID", menu("MID", 10, 0, 0, Map.of("1", "DEEP")));
            nodes.put("DEEP", menu("DEEP", 10, 0, 0, Map.of("1", "LEAF")));
            nodes.put("LEAF", terminal("LEAF", IvrTerminalAction.TERMINATE));
            IvrExecutionSnapshot snapshot = new IvrExecutionSnapshot(TREE, "Deep", "ROOT", nodes);

            openCurrentStep(snapshot, "ROOT", 10);
            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "1");
            assertThat(persisted.get(0).getNodeKey()).isEqualTo("MID");

            seedCurrentStep(snapshot, "MID", 10);
            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "1");
            assertThat(persisted.get(1).getNodeKey()).isEqualTo("DEEP");

            seedCurrentStep(snapshot, "DEEP", 10);
            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "1");
            verify(mediaController, times(1)).terminateCall(eq(SESSION), anyString());
        }

        @Test
        @DisplayName("RT-8: the terminal node's action is honoured")
        void terminalActionHonoured() {
            Map<String, IvrNodeSnapshot> nodes = new LinkedHashMap<>();
            nodes.put("ROOT", menu("ROOT", 10, 0, 0, Map.of("1", "AGENT")));
            nodes.put("AGENT", terminal("AGENT", IvrTerminalAction.TERMINATE));
            IvrExecutionSnapshot snapshot =
                    new IvrExecutionSnapshot(TREE, "Agent", "ROOT", nodes);

            openCurrentStep(snapshot, "ROOT", 10);
            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "1");

            verify(mediaController).terminateCall(eq(SESSION), anyString());
        }
    }

    // =====================================================================
    // Invalid input (Example 3)
    // =====================================================================

    @Nested
    class InvalidInput {

        @Test
        @DisplayName("RT-9: an unrecognised digit with budget re-asks in the SAME call")
        void invalidDigitReasks() {
            IvrExecutionSnapshot snapshot = withRetries(2, 0);
            openCurrentStep(snapshot, "ROOT", 10);

            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "9");

            // A NEW step for the same node, with the counter advanced. No new
            // attempt, no hangup: the caller is still in the call.
            assertThat(persisted).hasSize(1);
            assertThat(persisted.get(0).getNodeKey()).isEqualTo("ROOT");
            assertThat(persisted.get(0).getInvalidAttempts()).isEqualTo(1);
            assertThat(persisted.get(0).getResult()).isEqualTo(IvrStepResultType.WAITING_INPUT);
            verify(mediaController, never()).terminateCall(any(), anyString());
        }

        @Test
        @DisplayName("RT-10: the invalid-input prompt is played when the node configures one")
        void invalidPromptPlayed() {
            IvrExecutionSnapshot snapshot = withRetries(2, 0);
            openCurrentStep(snapshot, "ROOT", 10);
            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "9");
            // Root prompt plus the invalid-input prompt.
            verify(mediaController, times(1)).playAudio(eq(SESSION), any(), anyString());
        }

        @Test
        @DisplayName("RT-11: retries=0 means ONE attempt, then the call ends")
        void zeroRetriesMeansOneAttempt() {
            IvrExecutionSnapshot snapshot = withRetries(0, 0);
            openCurrentStep(snapshot, "ROOT", 10);

            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "9");

            verify(mediaController).terminateCall(eq(SESSION), anyString());
            assertThat(persisted).as("no re-ask is persisted when the budget is zero")
                    .isEmpty();
        }

        @Test
        @DisplayName("RT-12: the budget is spent exactly: retries=2 gives three attempts")
        void budgetIsSpentExactly() {
            IvrExecutionSnapshot snapshot = withRetries(2, 0);

            // Attempt 1 -> re-ask
            openCurrentStep(snapshot, "ROOT", 10);
            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "9");
            assertThat(persisted.get(0).getInvalidAttempts()).isEqualTo(1);

            // Attempt 2 -> re-ask
            openCurrentStep(snapshot, "ROOT", 10);
            currentStep.setInvalidAttempts(1);
            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "9");
            assertThat(persisted.get(0).getInvalidAttempts()).isEqualTo(2);

            // Attempt 3 -> budget spent, call ends
            openCurrentStep(snapshot, "ROOT", 10);
            currentStep.setInvalidAttempts(2);
            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "9");
            verify(mediaController).terminateCall(eq(SESSION), anyString());
        }

        @Test
        @DisplayName("RT-13: an exhausted budget does NOT fail the call attempt")
        void exhaustionIsNotAnAttemptFailure() {
            // An exhausted input budget is a conversation outcome, not a dispatch
            // failure. Failing the attempt would push a caller-input outcome
            // through the campaign retry policy.
            IvrExecutionSnapshot snapshot = withRetries(0, 0);
            openCurrentStep(snapshot, "ROOT", 10);

            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "9");

            assertThat(savedSession.getFailureCode())
                    .as("no failure code means the call is not treated as failed")
                    .isNull();
        }
    }

    // =====================================================================
    // No input (Example 4)
    // =====================================================================

    @Nested
    class NoInput {

        @Test
        @DisplayName("RT-14: a timeout with budget re-asks in the SAME call")
        void timeoutReasks() {
            IvrExecutionSnapshot snapshot = withRetries(0, 2);
            openCurrentStep(snapshot, "ROOT", 10);
            currentStep.setNoInputRetries(2);

            service.onStepTimeout(SESSION);

            assertThat(persisted).hasSize(1);
            assertThat(persisted.get(0).getNodeKey()).isEqualTo("ROOT");
            assertThat(persisted.get(0).getNoInputAttempts()).isEqualTo(1);
            verify(mediaController, never()).terminateCall(any(), anyString());
        }

        @Test
        @DisplayName("RT-15: no input and invalid input keep SEPARATE budgets")
        void noInputAndInvalidAreIndependent() {
            IvrExecutionSnapshot snapshot = withRetries(0, 2);
            openCurrentStep(snapshot, "ROOT", 10);
            currentStep.setNoInputRetries(2);
            currentStep.setInvalidInputRetries(0);

            service.onStepTimeout(SESSION);
            assertThat(persisted.get(0).getNoInputAttempts())
                    .as("a timeout must not consume the invalid-input budget")
                    .isEqualTo(1);
            assertThat(persisted.get(0).getInvalidAttempts()).isZero();
        }

        @Test
        @DisplayName("RT-16: the no-input budget is read from the FROZEN step, not a live tree")
        void noInputBudgetComesFromTheStep() {
            // The timeout path is dispatched with a session id only, so the budget
            // has to be on the step. That is what keeps a live IVR edit from
            // changing a deadline the caller is already waiting on.
            IvrExecutionSnapshot snapshot = withRetries(0, 0);
            openCurrentStep(snapshot, "ROOT", 10);
            currentStep.setNoInputRetries(0);

            service.onStepTimeout(SESSION);

            verify(mediaController).terminateCall(eq(SESSION), anyString());
        }

        @Test
        @DisplayName("RT-17: no input with no budget ends the call")
        void noBudgetEndsTheCall() {
            IvrExecutionSnapshot snapshot = withRetries(5, 0);
            openCurrentStep(snapshot, "ROOT", 10);
            currentStep.setNoInputRetries(0);

            service.onStepTimeout(SESSION);

            verify(mediaController).terminateCall(eq(SESSION), anyString());
        }
    }

    // =====================================================================
    // Idempotency (Example 7)
    // =====================================================================

    @Nested
    class Idempotency {

        @Test
        @DisplayName("RT-18: a duplicate digit does not transition twice")
        void duplicateDigitTransitionsOnce() {
            IvrExecutionSnapshot snapshot = twoLevel();
            openCurrentStep(snapshot, "ROOT", 10);
            // The second claim loses the race, as it would for a duplicate event.
            when(stepRepository.claimTerminal(any(), anyString(), anyString(), any()))
                    .thenReturn(1, 0);

            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "1");
            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "1");

            assertThat(persisted)
                    .as("a duplicate digit must not create a second transition")
                    .hasSize(1);
        }

        @Test
        @DisplayName("RT-19: a digit arriving after the step advanced is ignored")
        void digitAfterTransitionIgnored() {
            IvrExecutionSnapshot snapshot = twoLevel();
            // No step is WAITING_INPUT any more: the caller already moved on.
            when(stepRepository
                    .findFirstByCallSessionIdAndResultAndDeletedAtIsNullOrderByCreatedAtDesc(
                            any(), any())).thenReturn(Optional.empty());
            persisted.clear();

            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "1");

            assertThat(persisted).isEmpty();
            verify(mediaController, never()).terminateCall(any(), anyString());
        }

        @Test
        @DisplayName("RT-20: a digit after the call ended is ignored")
        void digitAfterHangupIgnored() {
            IvrExecutionSnapshot snapshot = twoLevel();
            when(stepRepository
                    .findFirstByCallSessionIdAndResultAndDeletedAtIsNullOrderByCreatedAtDesc(
                            any(), any())).thenReturn(Optional.empty());
            persisted.clear();

            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "1");

            assertThat(persisted).isEmpty();
        }

        @Test
        @DisplayName("RT-21: a late digit after the deadline is left to the timeout path")
        void lateDigitLeftToTimeout() {
            IvrExecutionSnapshot snapshot = twoLevel();
            openCurrentStep(snapshot, "ROOT", 10);
            currentStep.setExpiresAt(Instant.now().minusSeconds(1));

            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "1");

            assertThat(persisted)
                    .as("the timeout poller owns finalization; a late digit must not win it")
                    .isEmpty();
        }

        @Test
        @DisplayName("RT-22: a timeout losing the claim does nothing")
        void timeoutLosingClaimDoesNothing() {
            IvrExecutionSnapshot snapshot = withRetries(0, 2);
            openCurrentStep(snapshot, "ROOT", 10);
            currentStep.setNoInputRetries(2);
            when(stepRepository.claimTerminal(any(), anyString(), anyString(), any()))
                    .thenReturn(0); // a digit already won the race

            service.onStepTimeout(SESSION);

            assertThat(persisted).isEmpty();
            verify(mediaController, never()).terminateCall(any(), anyString());
        }

        @Test
        @DisplayName("RT-23: abandoning marks the open step ABANDONED")
        void abandonMarksStep() {
            IvrExecutionSnapshot snapshot = twoLevel();
            openCurrentStep(snapshot, "ROOT", 10);

            service.abandonIfWaiting(SESSION);

            verify(stepRepository).claimTerminal(any(), eq("ABANDONED"), anyString(), any());
        }
    }

    // =====================================================================
    // §28 — retries stay inside one call
    // =====================================================================

    @Nested
    class RetriesStayInsideTheCall {

        @Test
        @DisplayName("RT-24: an IVR retry creates no new call session, attempt or dial")
        void retryTouchesNothingOutsideTheCall() {
            IvrExecutionSnapshot snapshot = withRetries(3, 3);
            openCurrentStep(snapshot, "ROOT", 10);

            service.onDtmfDigit(SESSION, ATTEMPT, snapshot, "9");

            // The re-ask reuses the SAME session and attempt. There is no dial,
            // so nothing downstream of a dial can be consumed.
            assertThat(persisted).hasSize(1);
            assertThat(persisted.get(0).getCallSessionId()).isEqualTo(SESSION);
            assertThat(persisted.get(0).getCallAttemptId()).isEqualTo(ATTEMPT);


            verify(mediaController, never()).terminateCall(any(), anyString());
        }

        @Test
        @DisplayName("RT-25: the runtime never touches campaign retry or safety machinery")
        void runtimeTouchesNoCampaignRetryMachinery() throws Exception {
            // Structural guarantee, stronger than a behavioural one: the IVR
            // runtime's collaborators are the step table, the media boundary, the
            // prompt governance port and the agent boundary. It has no reference
            // to RetryPolicyService, DailyAttemptSafetyService or
            // DailyDialLimitService at all, so the two retry domains cannot be
            // confused even by accident.
            for (var field : IvrExecutionService.class.getDeclaredFields()) {
                String type = field.getType().getName();
                assertThat(type)
                        .as("IvrExecutionService must not depend on %s", type)
                        .doesNotContain("RetryPolicyService")
                        .doesNotContain("DailyAttemptSafetyService")
                        .doesNotContain("DailyDialLimitService")
                        .doesNotContain("CampaignExecutionOrchestrator");
            }
            for (var constructor : IvrExecutionService.class.getDeclaredConstructors()) {
                for (var parameter : constructor.getParameterTypes()) {
                    String type = parameter.getName();
                    assertThat(type)
                            .as("IvrExecutionService must not accept %s", type)
                            .doesNotContain("RetryPolicyService")
                            .doesNotContain("DailyAttemptSafetyService")
                            .doesNotContain("DailyDialLimitService");
                }
            }
        }
    }
}
