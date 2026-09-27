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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * VB-0 routing behavior tests (R1–R9).
 * <p>
 * Tests the observable behavior of {@link VoiceRoutingService}: primary
 * selection, priority ordering, deterministic tie-break, overflow/failover
 * policy, and the "never invent a fallback" rule.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class VoiceRoutingServiceTest {

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
    }

    private void stubEligibilityAllowed() {
        when(voiceEligibility.evaluate(TENANT, DEST, DID))
                .thenReturn(VoiceEligibility.EligibilityResult.allowed());
    }

    private VoiceRoutingDecision resolve() {
        return service.resolveRoute(TENANT, RESELLER, DEST, DID, CALL_TYPE, PROFILE);
    }

    @Nested
    class PrimarySelection {

        @Test
        void R1_activeEligibleGatewayWithCapacity_isSelectedAsPrimary() {
            VoiceRouteProfile p = profile(PROFILE, TENANT);
            p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10));
            stubProfile(p);

            stubGateway(GW_A, "TATA", SipGatewayStatus.ACTIVE, true);
            stubDid();
            stubEligibilityAllowed();
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
            when(voiceCapacity.checkCapacity(GW_A, TENANT))
                    .thenReturn(VoiceCapacityService.CapacityCheckResult.ok());

            VoiceRoutingDecision d = resolve();

            assertThat(d.selectedRoute()).isNotNull();
            assertThat(d.routeType()).isEqualTo(RouteType.PRIMARY);
            assertThat(d.decisionReason()).isEqualTo(VoiceRoutingReason.ROUTE_SELECTED_PRIMARY.getCode());
            assertThat(d.selectedRoute().gatewayId()).isEqualTo(GW_A);
            assertThat(d.selectedRoute().didId()).isEqualTo(DID);
        }

        @Test
        void R2_lowerPriorityNumberWins_amongTwoEligiblePrimaries() {
            VoiceRouteProfile p = profile(PROFILE, TENANT);
            p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 20)); // higher number = lower priority
            p.addPrimaryRoute(entry(GW_B, RouteType.PRIMARY, 10)); // lower number = higher priority
            stubProfile(p);

            stubGateway(GW_A, "TATA", SipGatewayStatus.ACTIVE, true);
            stubGateway(GW_B, "TATA", SipGatewayStatus.ACTIVE, true);
            stubDid();
            stubEligibilityAllowed();
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
            when(voiceCapacity.checkCapacity(any(UUID.class), eq(TENANT)))
                    .thenReturn(VoiceCapacityService.CapacityCheckResult.ok());

            VoiceRoutingDecision d = resolve();

            assertThat(d.selectedRoute().gatewayId()).isEqualTo(GW_B);
            assertThat(d.routeType()).isEqualTo(RouteType.PRIMARY);
        }

        @Test
        void R3_samePriority_isDeterministicallyResolved() {
            VoiceRouteProfile p = profile(PROFILE, TENANT);
            p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10));
            p.addPrimaryRoute(entry(GW_B, RouteType.PRIMARY, 10));
            stubProfile(p);

            stubGateway(GW_A, "TATA", SipGatewayStatus.ACTIVE, true);
            stubGateway(GW_B, "TATA", SipGatewayStatus.ACTIVE, true);
            stubDid();
            stubEligibilityAllowed();
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
            when(voiceCapacity.checkCapacity(any(UUID.class), eq(TENANT)))
                    .thenReturn(VoiceCapacityService.CapacityCheckResult.ok());

            UUID first = resolve().selectedRoute().gatewayId();
            for (int i = 0; i < 9; i++) {
                assertThat(resolve().selectedRoute().gatewayId())
                        .as("tie-break must be stable across repeated identical inputs (run %d)", i + 1)
                        .isEqualTo(first);
            }
        }
    }

    @Nested
    class Overflow {

        @Test
        void R4_primaryFullWithOverflowEnabled_selectsOverflowWithCapacityReasonForPrimary() {
            VoiceRouteProfile p = profile(PROFILE, TENANT);
            p.setAutoOverflowEnabled(true);
            p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10));
            p.addOverflowRoute(entry(GW_B, RouteType.OVERFLOW, 10));
            stubProfile(p);

            stubGateway(GW_A, "TATA", SipGatewayStatus.ACTIVE, true);
            stubGateway(GW_B, "TATA", SipGatewayStatus.ACTIVE, true);
            stubDid();
            stubEligibilityAllowed();
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
            when(voiceCapacity.checkCapacity(GW_A, TENANT))
                    .thenReturn(VoiceCapacityService.CapacityCheckResult.rejected(
                            VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode()));
            when(voiceCapacity.checkCapacity(GW_B, TENANT))
                    .thenReturn(VoiceCapacityService.CapacityCheckResult.ok());

            VoiceRoutingDecision d = resolve();

            assertThat(d.selectedRoute()).isNotNull();
            assertThat(d.routeType()).isEqualTo(RouteType.OVERFLOW);
            assertThat(d.decisionReason()).isEqualTo(VoiceRoutingReason.ROUTE_SELECTED_OVERFLOW.getCode());
            assertThat(d.selectedRoute().gatewayId()).isEqualTo(GW_B);
            assertThat(d.rejectedRoutes())
                    .anyMatch(r -> GW_A.equals(r.gatewayId())
                            && VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode().equals(r.rejectionReason()));
        }

        @Test
        void R5_autoOverflowDisabled_overflowIsNotSelected() {
            VoiceRouteProfile p = profile(PROFILE, TENANT);
            p.setAutoOverflowEnabled(false);
            p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10));
            p.addOverflowRoute(entry(GW_B, RouteType.OVERFLOW, 10));
            stubProfile(p);

            stubGateway(GW_A, "TATA", SipGatewayStatus.ACTIVE, true);
            stubGateway(GW_B, "TATA", SipGatewayStatus.ACTIVE, true);
            stubDid();
            stubEligibilityAllowed();
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
            when(voiceCapacity.checkCapacity(GW_A, TENANT))
                    .thenReturn(VoiceCapacityService.CapacityCheckResult.rejected(
                            VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode()));

            VoiceRoutingDecision d = resolve();

            assertThat(d.selectedRoute()).isNull();
            assertThat(d.rejectedRoutes())
                    .anyMatch(r -> GW_B.equals(r.gatewayId())
                            && VoiceRoutingReason.ROUTE_REJECTED_AUTO_OVERFLOW_DISABLED.getCode().equals(r.rejectionReason()));
        }
    }

    @Nested
    class Failover {

        @Test
        void R6_primaryDownWithFailoverEnabled_selectsFailover() {
            VoiceRouteProfile p = profile(PROFILE, TENANT);
            p.setAutoFailoverEnabled(true);
            p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10));
            p.addFailoverRoute(entry(GW_B, RouteType.FAILOVER, 10));
            stubProfile(p);

            stubGateway(GW_A, "TATA", SipGatewayStatus.INACTIVE, true);
            stubGateway(GW_B, "TATA", SipGatewayStatus.ACTIVE, true);
            stubDid();
            stubEligibilityAllowed();
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
            when(voiceCapacity.checkCapacity(GW_B, TENANT))
                    .thenReturn(VoiceCapacityService.CapacityCheckResult.ok());

            VoiceRoutingDecision d = resolve();

            assertThat(d.selectedRoute()).isNotNull();
            assertThat(d.routeType()).isEqualTo(RouteType.FAILOVER);
            assertThat(d.decisionReason()).isEqualTo(VoiceRoutingReason.ROUTE_SELECTED_FAILOVER.getCode());
            assertThat(d.selectedRoute().gatewayId()).isEqualTo(GW_B);
        }

        @Test
        void R7_autoFailoverDisabled_failoverIsNotSelected() {
            VoiceRouteProfile p = profile(PROFILE, TENANT);
            p.setAutoFailoverEnabled(false);
            p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10));
            p.addFailoverRoute(entry(GW_B, RouteType.FAILOVER, 10));
            stubProfile(p);

            stubGateway(GW_A, "TATA", SipGatewayStatus.INACTIVE, true);
            stubGateway(GW_B, "TATA", SipGatewayStatus.ACTIVE, true);
            stubDid();
            stubEligibilityAllowed();
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);

            VoiceRoutingDecision d = resolve();

            assertThat(d.selectedRoute()).isNull();
            assertThat(d.rejectedRoutes())
                    .anyMatch(r -> GW_B.equals(r.gatewayId())
                            && VoiceRoutingReason.ROUTE_REJECTED_AUTO_FAILOVER_DISABLED.getCode().equals(r.rejectionReason()));
        }

        @Test
        void R8_healthyUnconfiguredGateway_isNeverSelectedAsArbitraryFallback() {
            VoiceRouteProfile p = profile(PROFILE, TENANT);
            p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10));
            // GW_C is healthy with capacity but NOT configured in the profile
            stubProfile(p);

            stubGateway(GW_A, "TATA", SipGatewayStatus.INACTIVE, true);
            stubGateway(GW_C, "TATA", SipGatewayStatus.ACTIVE, true);
            stubDid();
            stubEligibilityAllowed();
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);

            VoiceRoutingDecision d = resolve();

            assertThat(d.selectedRoute()).isNull();
            // Single common rejection reason is surfaced (more specific than
            // the generic NO_ELIGIBLE_GATEWAY code).
            assertThat(d.decisionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_GATEWAY_INACTIVE.getCode());
            // Not configured => never evaluated => must not even appear as a rejected candidate
            assertThat(d.rejectedRoutes()).noneMatch(r -> GW_C.equals(r.gatewayId()));
        }
    }

    @Nested
    class DegradedGateways {

        @Test
        void R9_degradedGatewayIsRejectedAsPrimary_butOverflowIsSelectedInstead() {
            VoiceRouteProfile p = profile(PROFILE, TENANT);
            p.setAutoOverflowEnabled(true);
            p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10));
            p.addOverflowRoute(entry(GW_B, RouteType.OVERFLOW, 10));
            stubProfile(p);

            stubGateway(GW_A, "TATA", SipGatewayStatus.DEGRADED, true);
            stubGateway(GW_B, "TATA", SipGatewayStatus.ACTIVE, true);
            stubDid();
            stubEligibilityAllowed();
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
            when(voiceCapacity.checkCapacity(GW_B, TENANT))
                    .thenReturn(VoiceCapacityService.CapacityCheckResult.ok());

            VoiceRoutingDecision d = resolve();

            assertThat(d.routeType()).isEqualTo(RouteType.OVERFLOW);
            assertThat(d.selectedRoute().gatewayId()).isEqualTo(GW_B);
            assertThat(d.rejectedRoutes())
                    .anyMatch(r -> GW_A.equals(r.gatewayId())
                            && VoiceRoutingReason.ROUTE_REJECTED_GATEWAY_DEGRADED.getCode().equals(r.rejectionReason())
                            && r.routeType() == RouteType.PRIMARY);
        }

        @Test
        void R9_degradedOverflowGateway_canStillBeSelectedForOverflow() {
            VoiceRouteProfile p = profile(PROFILE, TENANT);
            p.setAutoOverflowEnabled(true);
            p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10));
            p.addOverflowRoute(entry(GW_B, RouteType.OVERFLOW, 10));
            stubProfile(p);

            stubGateway(GW_A, "TATA", SipGatewayStatus.ACTIVE, true);
            stubGateway(GW_B, "TATA", SipGatewayStatus.DEGRADED, true);
            stubDid();
            stubEligibilityAllowed();
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
            when(voiceCapacity.checkCapacity(GW_A, TENANT))
                    .thenReturn(VoiceCapacityService.CapacityCheckResult.rejected(
                            VoiceRoutingReason.ROUTE_REJECTED_CHANNEL_CAPACITY.getCode()));
            when(voiceCapacity.checkCapacity(GW_B, TENANT))
                    .thenReturn(VoiceCapacityService.CapacityCheckResult.ok());

            VoiceRoutingDecision d = resolve();

            assertThat(d.routeType()).isEqualTo(RouteType.OVERFLOW);
            assertThat(d.selectedRoute().gatewayId()).isEqualTo(GW_B);
        }

        @Test
        void R9_degradedFailoverGateway_canStillBeSelectedForFailover() {
            VoiceRouteProfile p = profile(PROFILE, TENANT);
            p.setAutoFailoverEnabled(true);
            p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10));
            p.addFailoverRoute(entry(GW_B, RouteType.FAILOVER, 10));
            stubProfile(p);

            stubGateway(GW_A, "TATA", SipGatewayStatus.INACTIVE, true);
            stubGateway(GW_B, "TATA", SipGatewayStatus.DEGRADED, true);
            stubDid();
            stubEligibilityAllowed();
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), any())).thenReturn(true);
            when(voiceCapacity.checkCapacity(GW_B, TENANT))
                    .thenReturn(VoiceCapacityService.CapacityCheckResult.ok());

            VoiceRoutingDecision d = resolve();

            assertThat(d.routeType()).isEqualTo(RouteType.FAILOVER);
            assertThat(d.selectedRoute().gatewayId()).isEqualTo(GW_B);
        }
    }
}
