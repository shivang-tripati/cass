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
 * VB-0 DID/CLI routing tests (D1–D5).
 * <p>
 * Verifies DID selection and the "no silent fallback" rule: a profile-pinned
 * DID incompatible with the candidate gateway rejects the route and never
 * falls back to the campaign DID; an approved failover may switch both
 * gateway and DID together.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class VoiceRoutingDIDTest {

    private VoiceRouteProfileRepository profileRepository;
    private GatewayRoutingPort gatewayRoutingPort;
    private VoiceCapacityService voiceCapacity;
    private VoiceEligibility voiceEligibility;
    private DidRepository didRepository;
    private VoiceRoutingService service;

    private static final UUID TENANT = TENANT_A;
    private static final UUID RESELLER = RESELLER_X;
    private static final UUID PROFILE = UUID.fromString("aa000000-0000-4000-8000-000000000031");
    private static final UUID DID_A = UUID.fromString("aa000000-0000-4000-8000-00000000002a"); // TATA
    private static final UUID DID_B = UUID.fromString("bb000000-0000-4000-8000-00000000002b"); // AIRTEL
    private static final UUID GW_A = UUID.fromString("aa000000-0000-4000-8000-00000000001a"); // TATA
    private static final UUID GW_B = UUID.fromString("bb000000-0000-4000-8000-00000000001b"); // AIRTEL
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

    private void stubGateway(UUID id, String provider) {
        SipGateway g = gateway(id, provider, 100, 10, 0);
        when(gatewayRoutingPort.findGateway(id)).thenReturn(Optional.of(SipGatewayRoutingAdapter.toView(g)));
    }

    private void stubGatewayStatus(UUID id, String provider, SipGatewayStatus status) {
        SipGateway g = gateway(id, provider, 100, 10, 0);
        g.setStatus(status);
        when(gatewayRoutingPort.findGateway(id)).thenReturn(Optional.of(SipGatewayRoutingAdapter.toView(g)));
    }

    private void stubDid(UUID id, String provider) {
        DidEntity d = did(id, TENANT, provider);
        when(didRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(d));

        // VB-6E: a profile-pinned DID must pass the tenant-scoped ownership,
        // ACTIVE and ASSIGNED gate before it may be dialed as the caller ID.
        // The pre-VB-6E lookup was unscoped, so a foreign or pool DID could be
        // used. Refusal cases live in VoiceRoutingPinnedDidOwnershipTest.
        when(didRepository
                .findByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                        org.mockito.ArgumentMatchers.eq(id),
                        org.mockito.ArgumentMatchers.eq(TENANT),
                        org.mockito.ArgumentMatchers.eq(com.shivang.obd.did.DidStatus.ACTIVE),
                        org.mockito.ArgumentMatchers.eq(
                                com.shivang.obd.did.AllocationState.ASSIGNED)))
                .thenReturn(Optional.of(d));
    }

    private void stubEligibilityAllowed(UUID didId) {
        when(voiceEligibility.evaluate(TENANT, DEST, didId))
                .thenReturn(VoiceEligibility.EligibilityResult.allowed());
    }

    private void stubCapacityOk(UUID gatewayId) {
        when(voiceCapacity.checkCapacity(gatewayId, TENANT))
                .thenReturn(VoiceCapacityService.CapacityCheckResult.ok());
    }

    @Test
    void D1_profilePinsCompatibleDIDA_didAIsUsedForTheCall() {
        VoiceRouteProfile p = profile(PROFILE, TENANT);
        p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10, DID_A)); // DID-A (TATA) + GW-A (TATA)
        stubProfile(p);

        stubGateway(GW_A, "TATA");
        stubDid(DID_A, "TATA");
        stubEligibilityAllowed(DID_A);
        when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
        stubCapacityOk(GW_A);

        VoiceRoutingDecision d = service.resolveRoute(TENANT, RESELLER, DEST, DID_A, CALL_TYPE, PROFILE);

        assertThat(d.selectedRoute()).isNotNull();
        assertThat(d.routeType()).isEqualTo(RouteType.PRIMARY);
        assertThat(d.selectedRoute().gatewayId()).isEqualTo(GW_A);
        assertThat(d.selectedRoute().didId()).isEqualTo(DID_A);
        assertThat(d.selectedRoute().didE164Number()).isEqualTo("+919876543210");
    }

    @Test
    void D2_profilePinnedDIDIncompatibleWithGateway_routeRejectedWithoutFallbackToCampaignDID() {
        // Profile pins DID-A (TATA) on GW-B (AIRTEL); campaign DID is also DID-A.
        VoiceRouteProfile p = profile(PROFILE, TENANT);
        p.addPrimaryRoute(entry(GW_B, RouteType.PRIMARY, 10, DID_A));
        stubProfile(p);

        stubGateway(GW_B, "AIRTEL");
        stubDid(DID_A, "TATA");
        stubEligibilityAllowed(DID_A);
        when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
        stubCapacityOk(GW_B);

        VoiceRoutingDecision d = service.resolveRoute(TENANT, RESELLER, DEST, DID_A, CALL_TYPE, PROFILE);

        assertThat(d.selectedRoute()).isNull();
        assertThat(d.decisionReason())
                .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_DID_INCOMPATIBLE.getCode());
        assertThat(d.rejectedRoutes())
                .anyMatch(r -> GW_B.equals(r.gatewayId())
                        && VoiceRoutingReason.ROUTE_REJECTED_DID_INCOMPATIBLE.getCode().equals(r.rejectionReason()));
    }

    @Test
    void D3_noProfileDid_campaignDidIsUsedWhenCompatible() {
        VoiceRouteProfile p = profile(PROFILE, TENANT);
        p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10)); // no pinned DID
        stubProfile(p);

        stubGateway(GW_A, "TATA");
        stubDid(DID_A, "TATA");
        stubEligibilityAllowed(DID_A);
        when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
        stubCapacityOk(GW_A);

        VoiceRoutingDecision d = service.resolveRoute(TENANT, RESELLER, DEST, DID_A, CALL_TYPE, PROFILE);

        assertThat(d.selectedRoute()).isNotNull();
        assertThat(d.selectedRoute().didId()).isEqualTo(DID_A);
        assertThat(d.routeType()).isEqualTo(RouteType.PRIMARY);
    }

    @Test
    void D3_noProfileDid_campaignDidIncompatibleWithGateway_rejected() {
        VoiceRouteProfile p = profile(PROFILE, TENANT);
        p.addPrimaryRoute(entry(GW_B, RouteType.PRIMARY, 10)); // no pinned DID
        stubProfile(p);

        stubGateway(GW_B, "AIRTEL");
        stubDid(DID_A, "TATA");
        stubEligibilityAllowed(DID_A);
        when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
        stubCapacityOk(GW_B);

        VoiceRoutingDecision d = service.resolveRoute(TENANT, RESELLER, DEST, DID_A, CALL_TYPE, PROFILE);

        assertThat(d.selectedRoute()).isNull();
        assertThat(d.decisionReason())
                .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_DID_INCOMPATIBLE.getCode());
    }

    @Test
    void D4_approvedFailoverSwitchesGatewayAndDidTogether() {
        // Primary: GW-A (TATA) + DID-A. Failover: GW-B (AIRTEL) + DID-B.
        // Campaign DID is DID-A; the failover route intentionally changes identity.
        VoiceRouteProfile p = profile(PROFILE, TENANT);
        p.setAutoFailoverEnabled(true);
        p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10, DID_A));
        p.addFailoverRoute(entry(GW_B, RouteType.FAILOVER, 10, DID_B));
        stubProfile(p);

        stubGatewayStatus(GW_A, "TATA", SipGatewayStatus.INACTIVE); // primary down
        stubGateway(GW_B, "AIRTEL");
        stubDid(DID_A, "TATA");
        stubDid(DID_B, "AIRTEL");
        stubEligibilityAllowed(DID_A);
        when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
        stubCapacityOk(GW_B);

        VoiceRoutingDecision d = service.resolveRoute(TENANT, RESELLER, DEST, DID_A, CALL_TYPE, PROFILE);

        assertThat(d.selectedRoute()).isNotNull();
        assertThat(d.routeType()).isEqualTo(RouteType.FAILOVER);
        assertThat(d.selectedRoute().gatewayId()).isEqualTo(GW_B);
        assertThat(d.selectedRoute().didId()).isEqualTo(DID_B);
        assertThat(d.decisionReason())
                .isEqualTo(VoiceRoutingReason.ROUTE_SELECTED_FAILOVER.getCode());
    }

    @Test
    void D5_failoverPinnedDidIncompatibleWithFailoverGateway_failoverRejected() {
        // Failover pins DID-B (AIRTEL) on GW-B (TATA): incompatible => failover rejected.
        VoiceRouteProfile p = profile(PROFILE, TENANT);
        p.setAutoFailoverEnabled(true);
        p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10, DID_A));
        p.addFailoverRoute(entry(GW_B, RouteType.FAILOVER, 10, DID_B));
        stubProfile(p);

        stubGatewayStatus(GW_A, "TATA", SipGatewayStatus.INACTIVE); // primary down
        stubGateway(GW_B, "TATA");
        stubDid(DID_A, "TATA");
        stubDid(DID_B, "AIRTEL");
        stubEligibilityAllowed(DID_A);
        when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
        stubCapacityOk(GW_B);

        VoiceRoutingDecision d = service.resolveRoute(TENANT, RESELLER, DEST, DID_A, CALL_TYPE, PROFILE);

        assertThat(d.selectedRoute()).isNull();
        assertThat(d.rejectedRoutes())
                .anyMatch(r -> GW_B.equals(r.gatewayId())
                        && VoiceRoutingReason.ROUTE_REJECTED_DID_INCOMPATIBLE.getCode().equals(r.rejectionReason()));
    }
}
