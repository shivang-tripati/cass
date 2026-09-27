package com.shivang.obd.voice.routing;

import static com.shivang.obd.voice.VoiceTestSupport.RESELLER_X;
import static com.shivang.obd.voice.VoiceTestSupport.TENANT_A;
import static com.shivang.obd.voice.VoiceTestSupport.did;
import static com.shivang.obd.voice.VoiceTestSupport.entry;
import static com.shivang.obd.voice.VoiceTestSupport.gateway;
import static com.shivang.obd.voice.VoiceTestSupport.profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shivang.obd.did.DidEntity;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.telephony.SipGateway;
import com.shivang.obd.telephony.SipGatewayRoutingAdapter;
import com.shivang.obd.telephony.SipGatewayStatus;
import com.shivang.obd.voice.capacity.VoiceCapacityService;
import com.shivang.obd.voice.eligibility.VoiceEligibility;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * VB-0 explainability test (architecture doc §19): every decision exposes
 * the selected route, the route type, the reason, and the rejected
 * alternatives with machine-readable reasons.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class VoiceExplainabilityTest {

    private VoiceRouteProfileRepository profileRepository;
    private GatewayRoutingPort gatewayRoutingPort;
    private VoiceCapacityService voiceCapacity;
    private VoiceEligibility voiceEligibility;
    private DidRepository didRepository;
    private VoiceRoutingService service;

    private static final UUID TENANT = TENANT_A;
    private static final UUID RESELLER = RESELLER_X;
    private static final UUID PROFILE = UUID.fromString("aa000000-0000-4000-8000-000000000031");
    private static final UUID DID = UUID.fromString("aa000000-0000-4000-8000-00000000002a");
    private static final UUID GW_A = UUID.fromString("aa000000-0000-4000-8000-00000000001a");
    private static final UUID GW_B = UUID.fromString("bb000000-0000-4000-8000-00000000001b");
    private static final UUID GW_C = UUID.fromString("cc000000-0000-4000-8000-00000000001c");
    private static final String DEST = "+919876543210";
    private static final String CALL_TYPE = "VOICE_BLAST";

    @BeforeEach
    void setUp() {
        profileRepository = mock(VoiceRouteProfileRepository.class);
        gatewayRoutingPort = mock(GatewayRoutingPort.class);
        voiceCapacity = mock(VoiceCapacityService.class);
        voiceEligibility = mock(VoiceEligibility.class);
        didRepository = mock(DidRepository.class);
        service = new VoiceRoutingService(
                profileRepository, gatewayRoutingPort, voiceCapacity,
                voiceEligibility, didRepository);
    }

    private void stubProfile(VoiceRouteProfile p) {
        when(profileRepository.findByIdAndTenantIdAndDeletedAtIsNull(PROFILE, TENANT))
                .thenReturn(Optional.of(p));
        when(profileRepository.findFirstByTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(TENANT))
                .thenReturn(Optional.of(p));
    }

    private void stubGateway(UUID id, String provider, SipGatewayStatus status, boolean enabled) {
        SipGateway g = gateway(id, provider, 100, 10, 0);
        g.setStatus(status);
        g.setEnabled(enabled);
        when(gatewayRoutingPort.findGateway(id)).thenReturn(Optional.of(SipGatewayRoutingAdapter.toView(g)));
    }

    private void stubDid() {
        DidEntity d = did(DID, TENANT, "TATA");
        when(didRepository.findByIdAndDeletedAtIsNull(DID)).thenReturn(Optional.of(d));

        // VB-6E: a profile-pinned DID must pass the tenant-scoped ownership,
        // ACTIVE and ASSIGNED gate before it may be dialed as the caller ID.
        // The pre-VB-6E lookup was unscoped, which let a foreign or pool DID be
        // used. This stub expresses the new contract; the refusal cases are
        // covered by VoiceRoutingPinnedDidOwnershipTest.
        when(didRepository.findByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                org.mockito.ArgumentMatchers.eq(DID),
                org.mockito.ArgumentMatchers.eq(TENANT),
                org.mockito.ArgumentMatchers.eq(com.shivang.obd.did.DidStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(com.shivang.obd.did.AllocationState.ASSIGNED)))
                .thenReturn(Optional.of(d));    }

    @Test
    void decision_exposesSelectedRouteTypeReasonAndRejectedAlternativesWithGatewayIds() {
        // Architecture §19 scenario:
        //   Gateway A: authorized, healthy, channels FULL      -> REJECTED_CAPACITY
        //   Gateway B: authorized, healthy, overflow approved  -> SELECTED (overflow)
        //   Gateway C: not authorized (reseller-owned, no allocation) -> rejected
        VoiceRouteProfile p = profile(PROFILE, TENANT);
        p.setAutoOverflowEnabled(true);
        p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10));
        p.addOverflowRoute(entry(GW_B, RouteType.OVERFLOW, 10));
        // Gateway C is NOT in the profile: never evaluated, never reported.
        stubProfile(p);

        stubGateway(GW_A, "TATA", SipGatewayStatus.ACTIVE, true);
        stubGateway(GW_B, "TATA", SipGatewayStatus.ACTIVE, true);
        stubDid();
        when(voiceEligibility.evaluate(TENANT, DEST, DID))
                .thenReturn(VoiceEligibility.EligibilityResult.allowed());
        when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
        when(voiceCapacity.checkCapacity(GW_A, TENANT))
                .thenReturn(VoiceCapacityService.CapacityCheckResult.rejected(
                        VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode()));
        when(voiceCapacity.checkCapacity(GW_B, TENANT))
                .thenReturn(VoiceCapacityService.CapacityCheckResult.ok());

        VoiceRoutingDecision d = service.resolveRoute(TENANT, RESELLER, DEST, DID, CALL_TYPE, PROFILE);

        // Selected route
        assertThat(d.selectedRoute()).isNotNull();
        assertThat(d.selectedRoute().gatewayId()).isEqualTo(GW_B);

        // Route type + decision reason
        assertThat(d.routeType()).isEqualTo(RouteType.OVERFLOW);
        assertThat(d.decisionReason())
                .isEqualTo(VoiceRoutingReason.ROUTE_SELECTED_OVERFLOW.getCode());

        // Rejected alternatives: A with capacity reason
        assertThat(d.rejectedRoutes()).hasSize(1);
        RejectedRoute rejectedA = d.rejectedRoutes().get(0);
        assertThat(rejectedA.gatewayId()).isEqualTo(GW_A);
        assertThat(rejectedA.rejectionReason())
                .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode());
        assertThat(rejectedA.routeType()).isEqualTo(RouteType.PRIMARY);

        // Gateway C: not configured => not evaluated, not reported
        assertThat(d.rejectedRoutes()).noneMatch(r -> GW_C.equals(r.gatewayId()));
    }

    @Test
    void failoverDecision_reportsPrimaryUnavailabilityReason() {
        VoiceRouteProfile p = profile(PROFILE, TENANT);
        p.setAutoFailoverEnabled(true);
        p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10));
        p.addFailoverRoute(entry(GW_B, RouteType.FAILOVER, 10));
        stubProfile(p);

        stubGateway(GW_A, "TATA", SipGatewayStatus.INACTIVE, true);
        stubGateway(GW_B, "TATA", SipGatewayStatus.ACTIVE, true);
        stubDid();
        when(voiceEligibility.evaluate(TENANT, DEST, DID))
                .thenReturn(VoiceEligibility.EligibilityResult.allowed());
        when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
        when(voiceCapacity.checkCapacity(GW_B, TENANT))
                .thenReturn(VoiceCapacityService.CapacityCheckResult.ok());

        VoiceRoutingDecision d = service.resolveRoute(TENANT, RESELLER, DEST, DID, CALL_TYPE, PROFILE);

        assertThat(d.routeType()).isEqualTo(RouteType.FAILOVER);
        assertThat(d.decisionReason())
                .isEqualTo(VoiceRoutingReason.ROUTE_SELECTED_FAILOVER.getCode());
        assertThat(d.selectedRoute().gatewayId()).isEqualTo(GW_B);
        assertThat(d.rejectedRoutes())
                .anyMatch(r -> GW_A.equals(r.gatewayId())
                        && VoiceRoutingReason.ROUTE_REJECTED_GATEWAY_INACTIVE.getCode().equals(r.rejectionReason())
                        && r.routeType() == RouteType.PRIMARY);
    }
}
