package com.shivang.obd.telephony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shivang.obd.campaign.CallAttempt;
import com.shivang.obd.campaign.CallAttemptRepository;
import com.shivang.obd.campaign.CallAttemptStatus;
import com.shivang.obd.campaign.OrphanedDispatchRecovery;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegRepository;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionRepository;
import com.shivang.obd.voice.capacity.VoiceCapacityService;
import com.shivang.obd.voice.dtmf.DtmfResultService;
import com.shivang.obd.voice.media.VoiceMediaController;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * VB-8F — the channel-correlation decision matrix, at the ESL boundary.
 *
 * <p>Every case here is about one question: <em>what evidence would have to be
 * present before this service is willing to attribute a FreeSWITCH event to a
 * campaign attempt?</em> The answer must be "an application-pinned origination
 * identity that names a live, unsettled, session-less attempt" — never "a UUID
 * that happens to look like an attempt id".
 *
 * <p>These tests pin the {@code providerCallId}-primary-first precedence and
 * every foreign-domain rejection. The database-backed half of the contract
 * (IN_PROGRESS, no session, tenant from the persisted row) is proven in
 * {@code OrphanedDispatchRecoveryPostgresIntegrationTest} against real
 * PostgreSQL; here the recovery collaborator is mocked so this class tests
 * correlation only.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrphanedDispatchRecoveryEslTest {

    @Mock CallAttemptRepository attemptRepository;
    @Mock CallSessionRepository callSessionRepository;
    @Mock CallLegRepository callLegRepository;
    @Mock VoiceCapacityService voiceCapacity;
    @Mock VoiceMediaController mediaController;
    @Mock DtmfResultService dtmfResultService;
    @Mock OrphanedDispatchRecovery recovery;

    EslEventService service;

    static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    static final UUID TENANT_B = UUID.fromString("bb000000-0000-4000-8000-00000000000b");
    static final UUID ATTEMPT_ID = UUID.fromString("cc000000-0000-4000-8000-0000000000c1");
    static final String ATTEMPT_CHANNEL = ATTEMPT_ID.toString();

    CallAttempt attempt;

    @BeforeEach
    void setUp() {
        service = new EslEventService(attemptRepository, callSessionRepository, callLegRepository,
                voiceCapacity, mediaController, List.of(),
                Optional.empty(), dtmfResultService, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(recovery));

        attempt = new CallAttempt();
        attempt.setId(ATTEMPT_ID);
        attempt.setTenantId(TENANT_A);
        attempt.setStatus(CallAttemptStatus.IN_PROGRESS);

        // Primary correlation finds nothing: this is the lost-dispatch world.
        lenient().when(attemptRepository.findByProviderCallIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.empty());
        lenient().when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.empty());
        lenient().when(callLegRepository.findByProviderCallIdAndDeletedAtIsNull(any()))
                .thenReturn(Optional.empty());
    }

    /**
     * A hangup shaped exactly like the ones the switch really sends, per the
     * runtime contract: the channel identity headers plus the pinned
     * origination variable the platform set when it originated the call.
     */
    private EslEvent pinnedCampaignHangup(String channelUuid, String cause) {
        return hangup(channelUuid, cause, Map.of("variable_origination_uuid", channelUuid));
    }

    private EslEvent hangup(String channelUuid, String cause, Map<String, String> extra) {
        EslEvent e = new EslEvent("CHANNEL_HANGUP");
        e.addHeader("Unique-ID", channelUuid);
        e.addHeader("Channel-Call-UUID", channelUuid);
        e.addHeader("Caller-Unique-ID", channelUuid);
        e.addHeader("variable_call_uuid", channelUuid);
        e.addHeader("Hangup-Cause", cause);
        if (extra != null) {
            extra.forEach(e::addHeader);
        }
        return e;
    }

    // =====================================================================
    // F8-A / F8-I: providerCallId stays primary
    // =====================================================================

    @Nested
    @DisplayName("F8-A/I - the primary providerCallId path is authoritative and never overridden")
    class PrimaryPrecedence {

        @Test
        @DisplayName("F8-A: a resolvable providerCallId is handled by the primary path alone")
        void primaryPathHandlesTheEvent() {
            CallSession session = new CallSession();
            session.setId(UUID.randomUUID());
            session.setTenantId(TENANT_A);
            session.setStatus(com.shivang.obd.voice.call.CallSessionStatus.DIALING);
            when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(ATTEMPT_CHANNEL))
                    .thenReturn(Optional.of(session));
            when(attemptRepository.findByProviderCallIdAndDeletedAtIsNull(ATTEMPT_CHANNEL))
                    .thenReturn(Optional.of(attempt));
            when(callLegRepository.findByCallSessionIdAndDeletedAtIsNull(session.getId()))
                    .thenReturn(List.of());

            assertThat(service.processEvent(pinnedCampaignHangup(ATTEMPT_CHANNEL, "NORMAL_CLEARING")))
                    .isTrue();

            assertThat(attempt.getStatus()).isEqualTo(CallAttemptStatus.COMPLETED);
            verify(recovery, never()).recover(any(), any(), any());
        }

        @Test
        @DisplayName("F8-I: even when the pinned identity names a different candidate, primary wins")
        void primaryWinsOverFallbackCandidate() {
            CallSession session = new CallSession();
            session.setId(UUID.randomUUID());
            session.setTenantId(TENANT_B);
            session.setStatus(com.shivang.obd.voice.call.CallSessionStatus.DIALING);
            // The event's channel resolves to a real, already-persisted session.
            when(callSessionRepository.findByProviderCallIdAndDeletedAtIsNull(ATTEMPT_CHANNEL))
                    .thenReturn(Optional.of(session));
            // ...while a *different* attempt id is reachable only via the fallback.
            when(attemptRepository.findByProviderCallIdAndDeletedAtIsNull(ATTEMPT_CHANNEL))
                    .thenReturn(Optional.of(attempt));
            when(callLegRepository.findByCallSessionIdAndDeletedAtIsNull(session.getId()))
                    .thenReturn(List.of());

            service.processEvent(pinnedCampaignHangup(ATTEMPT_CHANNEL, "NORMAL_CLEARING"));

            verify(recovery, never()).recover(any(), any(), any());
        }
    }

    // =====================================================================
    // F8-B: the happy path - a validated recovery
    // =====================================================================

    @Nested
    @DisplayName("F8-B - a pinned campaign hangup is handed to validated recovery")
    class ValidatedRecovery {

        @Test
        @DisplayName("F8-B: pinned identity equal to the channel identity triggers recovery")
        void pinnedIdentityTriggersRecovery() {
            when(recovery.recover(ATTEMPT_ID, ATTEMPT_CHANNEL, "USER_BUSY")).thenReturn(true);

            assertThat(service.processEvent(pinnedCampaignHangup(ATTEMPT_CHANNEL, "USER_BUSY")))
                    .isTrue();

            verify(recovery).recover(ATTEMPT_ID, ATTEMPT_CHANNEL, "USER_BUSY");
        }

        @Test
        @DisplayName("F8-B: recovery declining leaves the event unprocessed, not failed")
        void recoveryDecliningIsNotAnError() {
            when(recovery.recover(any(), any(), any())).thenReturn(false);

            assertThat(service.processEvent(pinnedCampaignHangup(ATTEMPT_CHANNEL, "USER_BUSY")))
                    .as("the attempt was not an eligible lost dispatch; nothing is attributed")
                    .isFalse();
        }

        @Test
        @DisplayName("F8-L: a non-terminal event never triggers recovery")
        void nonHangupEventNeverRecovers() {
            EslEvent answer = new EslEvent("CHANNEL_ANSWER");
            answer.addHeader("Unique-ID", ATTEMPT_CHANNEL);
            answer.addHeader("Channel-Call-UUID", ATTEMPT_CHANNEL);
            answer.addHeader("variable_origination_uuid", ATTEMPT_CHANNEL);

            assertThat(service.processEvent(answer)).isFalse();

            verify(recovery, never()).recover(any(), any(), any());
        }
    }

    // =====================================================================
    // Foreign domains: F8-C, D, E, F, and the deliberate-UUID case
    // =====================================================================

    @Nested
    @DisplayName("F8-C..F - no foreign channel is ever attributed to a campaign attempt")
    class ForeignChannels {

        @Test
        @DisplayName("F8-C: an inbound hangup carries no pinned origination variable")
        void inboundChannelIsRejected() {
            // Inbound channels are created by FreeSWITCH: the platform never
            // originated them, so there is no pinned variable to match on.
            EslEvent inbound = hangup(UUID.randomUUID().toString(), "NORMAL_CLEARING", null);
            inbound.addHeader("Call-Direction", "inbound");

            assertThat(service.processEvent(inbound)).isFalse();

            verify(recovery, never()).recover(any(), any(), any());
        }

        @Test
        @DisplayName("F8-D: an inbound hangup is still rejected when the header is absent")
        void inboundWithoutDirectionHeaderIsRejected() {
            assertThat(service.processEvent(
                    hangup(UUID.randomUUID().toString(), "NORMAL_CLEARING", null))).isFalse();

            verify(recovery, never()).recover(any(), any(), any());
        }

        @Test
        @DisplayName("F8-D/E: an agent leg pinned with a random uuid names no attempt")
        void agentLegRandomPinIsRejected() {
            // The agent leg IS originated with a pinned variable, but with a
            // random uuid. It is offered to recovery, which finds no attempt
            // and fails closed.
            String randomLegUuid = UUID.randomUUID().toString();
            when(recovery.recover(any(UUID.class), any(), any())).thenReturn(false);

            assertThat(service.processEvent(
                    hangup(randomLegUuid, "NORMAL_CLEARING",
                            Map.of("variable_origination_uuid", randomLegUuid)))).isFalse();
        }

        @Test
        @DisplayName("F8-D: a known agent leg is claimed by the agent path before recovery")
        void agentLegIsClaimedByAgentPath() {
            CallLeg leg = new CallLeg();
            leg.setId(UUID.randomUUID());
            leg.setLegType(CallLegType.AGENT);
            when(callLegRepository.findByProviderCallIdAndDeletedAtIsNull(ATTEMPT_CHANNEL))
                    .thenReturn(Optional.of(leg));

            service.processEvent(pinnedCampaignHangup(ATTEMPT_CHANNEL, "NORMAL_CLEARING"));

            verify(recovery, never()).recover(any(), any(), any());
        }

        @Test
        @DisplayName("F8-F: an unrelated channel with no pinned variable is rejected")
        void unrelatedChannelIsRejected() {
            assertThat(service.processEvent(
                    hangup(UUID.randomUUID().toString(), "NORMAL_CLEARING", null))).isFalse();

            verify(recovery, never()).recover(any(), any(), any());
        }

        @Test
        @DisplayName("F8-F: a foreign channel carrying an attempt-shaped id is still rejected")
        void foreignChannelWithAttemptShapedIdIsRejected() {
            // The nastiest realistic case: the Unique-ID is exactly a campaign
            // attempt id, but the channel was not originated by the platform so
            // it carries no pinned variable. UUID equality is not evidence.
            assertThat(service.processEvent(
                    hangup(ATTEMPT_CHANNEL, "NORMAL_CLEARING", null))).isFalse();

            verify(recovery, never()).recover(any(), any(), any());
        }

        @Test
        @DisplayName("F8-F: a foreign channel pinning someone else's attempt id is rejected")
        void foreignChannelPinningAttemptIdIsRejected() {
            // A channel that pins an attempt id that is NOT its own identity.
            EslEvent forged = hangup(UUID.randomUUID().toString(), "NORMAL_CLEARING",
                    Map.of("variable_origination_uuid", ATTEMPT_CHANNEL));

            assertThat(service.processEvent(forged)).isFalse();

            verify(recovery, never()).recover(any(), any(), any());
        }

        @Test
        @DisplayName("F8-H: a malformed pinned identity is rejected without throwing")
        void malformedPinnedIdentityIsRejected() {
            EslEvent malformed = hangup("not-a-uuid", "NORMAL_CLEARING",
                    Map.of("variable_origination_uuid", "not-a-uuid"));

            assertThat(service.processEvent(malformed)).isFalse();

            verify(recovery, never()).recover(any(), any(), any());
        }

        @Test
        @DisplayName("F8: an event with no channel identity at all is rejected")
        void eventWithoutChannelIdentityIsRejected() {
            EslEvent orphan = new EslEvent("CHANNEL_HANGUP");
            orphan.addHeader("Hangup-Cause", "NORMAL_CLEARING");
            orphan.addHeader("variable_origination_uuid", ATTEMPT_CHANNEL);

            assertThat(service.processEvent(orphan)).isFalse();

            verify(recovery, never()).recover(any(), any(), any());
        }
    }

    // =====================================================================
    // Isolation and ambiguity
    // =====================================================================

    @Nested
    @DisplayName("Isolation - no cross-tenant or cross-attempt attribution")
    class Isolation {

        @Test
        @DisplayName("F8-G: only the attempt named by the pinned identity is ever offered")
        void onlyTheNamedAttemptIsOffered() {
            when(recovery.recover(any(UUID.class), any(), any())).thenReturn(false);

            service.processEvent(pinnedCampaignHangup(ATTEMPT_CHANNEL, "NORMAL_CLEARING"));

            // Exactly one candidate, and it is the pinned identity - never a
            // search, never "the first row", never a tenant-scoped sweep.
            verify(recovery).recover(ATTEMPT_ID, ATTEMPT_CHANNEL, "NORMAL_CLEARING");
        }

        @Test
        @DisplayName("F8-G: the ESL layer never looks an attempt up by id itself")
        void eslLayerDoesNotResolveAttemptsItself() {
            service.processEvent(pinnedCampaignHangup(ATTEMPT_CHANNEL, "NORMAL_CLEARING"));

            // Correlation identifies the candidate; the campaign-side component
            // re-reads and re-validates it. Keeping the lookup out of the
            // telephony layer is what stops this becoming a second source of
            // truth about which attempt a channel belongs to.
            verify(attemptRepository, never()).findByIdAndDeletedAtIsNull(any());
            verify(attemptRepository, never())
                    .findByIdAndTenantIdAndDeletedAtIsNull(any(), any());
        }

        @Test
        @DisplayName("F8: recovery performs no capacity or media side effect of its own")
        void recoveryPathTouchesNoCapacityDirectly() {
            when(recovery.recover(any(UUID.class), any(), any())).thenReturn(true);

            service.processEvent(pinnedCampaignHangup(ATTEMPT_CHANNEL, "NORMAL_CLEARING"));

            // The rollback released everything with the transaction; releasing
            // again here would be a double release, so the ESL layer must not.
            verifyNoInteractions(voiceCapacity);
            verifyNoInteractions(mediaController);
        }
    }
}
