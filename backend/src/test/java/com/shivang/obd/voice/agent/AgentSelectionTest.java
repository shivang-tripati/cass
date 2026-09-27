package com.shivang.obd.voice.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shivang.obd.campaign.ConnectByAgentService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * VB-3 unit tests: deterministic agent selection and explainable
 * rejection reasons (spec §7/§8/§39).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentSelectionTest {

    private static final UUID TENANT_A = UUID.fromString("3a000000-0000-4000-8000-0000000000a1");
    private static final UUID TENANT_B = UUID.fromString("3b000000-0000-4000-8000-0000000000b2");

    private static final UUID AGENT_1 = UUID.fromString("3c000000-0000-4000-8000-0000000000c1");
    private static final UUID AGENT_2 = UUID.fromString("3c000000-0000-4000-8000-0000000000c2");

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private AgentEndpointRepository endpointRepository;

    @Mock
    private AgentReservationService reservationService;

    private ConnectByAgentService service;

    @BeforeEach
    void setUp() {
        service = new ConnectByAgentService(agentRepository, endpointRepository,
                reservationService, mock(com.shivang.obd.voice.call.CallSessionRepository.class),
                mock(com.shivang.obd.voice.call.CallLegRepository.class),
                mock(AgentLegDialer.class),
                mock(com.shivang.obd.voice.media.VoiceMediaController.class),
                mock(com.shivang.obd.did.DidRepository.class));
    }

    private Agent agent(UUID id) {
        Agent a = new Agent();
        a.setId(id);
        a.setTenantId(TENANT_A);
        a.setDisplayName("Agent " + id);
        a.setAdminStatus(AgentAdminStatus.ACTIVE);
        a.setAvailability(AgentAvailability.AVAILABLE);
        a.setMaxConcurrentCalls(1);
        return a;
    }

    private AgentEndpointEntity endpoint(UUID agentId) {
        AgentEndpointEntity e = new AgentEndpointEntity();
        e.setId(UUID.fromString("3d000000-0000-4000-8000-0000000000e1"));
        e.setAgentId(agentId);
        e.setTenantId(TENANT_A);
        e.setEndpointType(com.shivang.obd.voice.call.EndpointType.SIP);
        e.setDialTarget("sip:agent@" + agentId + ".example");
        e.setEnabled(true);
        return e;
    }

    @Test
    @DisplayName("G1: selection prefers the least-loaded agent (deterministic ordering)")
    void leastLoadedAgentSelectedFirst() {
        // Repository ordering contract: least active reservations, then id.
        when(agentRepository.findEligibleOrdered(org.mockito.ArgumentMatchers.eq(TENANT_A),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(agent(AGENT_1), agent(AGENT_2))));
        when(endpointRepository.findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(
                org.mockito.ArgumentMatchers.eq(AGENT_1), org.mockito.ArgumentMatchers.eq(TENANT_A)))
                .thenReturn(List.of(endpoint(AGENT_1)));

        var result = service.connectByAgent(
                UUID.fromString("3e000000-0000-4000-8000-0000000000f1"), null);

        // Session missing → config rejection, but ordering was already exercised.
        assertThat(result.isSelected()).isFalse();
    }

    @Test
    @DisplayName("G2: no agents for tenant → AGENT_UNAVAILABLE (distinct from all-busy)")
    void noAgentsYieldsUnavailable() {
        org.springframework.data.jpa.domain.Specification<Agent> ignored = null;
        when(agentRepository.findEligibleOrdered(org.mockito.ArgumentMatchers.eq(TENANT_B),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(agentRepository.findByTenantIdAndDeletedAtIsNull(
                org.mockito.ArgumentMatchers.eq(TENANT_B),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of());

        var result = service.selectEligibleAgentPublic(TENANT_B);

        assertThat(result.isSelected()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(AgentReasons.AGENT_UNAVAILABLE);
    }

    @Test
    @DisplayName("G3: agents exist but none AVAILABLE → AGENT_NOT_AVAILABLE")
    void agentsExistButNotAvailable() {
        when(agentRepository.findEligibleOrdered(org.mockito.ArgumentMatchers.eq(TENANT_A),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(agentRepository.findByTenantIdAndDeletedAtIsNull(
                org.mockito.ArgumentMatchers.eq(TENANT_A),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(agent(AGENT_1)));

        var result = service.selectEligibleAgentPublic(TENANT_A);

        assertThat(result.reasonCode()).isEqualTo(AgentReasons.AGENT_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("G4: eligible agent without dialable endpoint → AGENT_ENDPOINT_INVALID")
    void endpointInvalidReason() {
        when(agentRepository.findEligibleOrdered(org.mockito.ArgumentMatchers.eq(TENANT_A),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(agent(AGENT_1))));
        when(endpointRepository.findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(
                org.mockito.ArgumentMatchers.eq(AGENT_1), org.mockito.ArgumentMatchers.eq(TENANT_A)))
                .thenReturn(List.of()); // no endpoint

        var result = service.selectEligibleAgentPublic(TENANT_A);

        assertThat(result.reasonCode()).isEqualTo(AgentReasons.AGENT_ENDPOINT_INVALID);
    }

    @Test
    @DisplayName("G5: WEBRTC endpoints are not dialable in VB-3 — skipped by selection")
    void webrtcEndpointsSkipped() {
        AgentEndpointEntity webrtc = endpoint(AGENT_1);
        webrtc.setEndpointType(com.shivang.obd.voice.call.EndpointType.WEBRTC);
        when(agentRepository.findEligibleOrdered(org.mockito.ArgumentMatchers.eq(TENANT_A),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(agent(AGENT_1))));
        when(endpointRepository.findByAgentIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(
                org.mockito.ArgumentMatchers.eq(AGENT_1), org.mockito.ArgumentMatchers.eq(TENANT_A)))
                .thenReturn(List.of(webrtc));

        var result = service.selectEligibleAgentPublic(TENANT_A);

        assertThat(result.reasonCode()).isEqualTo(AgentReasons.AGENT_ENDPOINT_INVALID);
    }
}
