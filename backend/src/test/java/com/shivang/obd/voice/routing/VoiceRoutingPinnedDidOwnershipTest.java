package com.shivang.obd.voice.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidEntity;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.voice.capacity.VoiceCapacityService;
import com.shivang.obd.voice.eligibility.VoiceEligibility;
import com.shivang.obd.telephony.VoiceEligibilityService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * VB-6E — a routing profile's pinned DID must be validated, not trusted.
 *
 * <h2>The defect</h2>
 *
 * <p>Pre-VB-6E a pinned DID was filtered only by "row exists and is not
 * soft-deleted" plus a provider-string match. Eligibility only ever saw the
 * campaign's <em>requested</em> DID, so a routing profile could pin another
 * tenant's assigned DID, or a pool DID with {@code tenant_id IS NULL}, and that
 * value would be dialed as the caller ID — becoming both the CLI the subscriber
 * sees and the VB-6C daily-dial-limit bucket key. Tenant isolation and
 * compliance were effectively bypassed by a configuration row, and nothing
 * tested it because every existing fixture used a same-tenant DID.
 */
class VoiceRoutingPinnedDidOwnershipTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-4000-8000-00000000000a");
    private static final UUID CAMPAIGN_DID = UUID.fromString("dddddddd-0000-4000-8000-00000000000d");
    private static final UUID PINNED_DID = UUID.fromString("eeeeeeee-0000-4000-8000-00000000000e");
    private static final UUID PROFILE = UUID.fromString("ffffffff-0000-4000-8000-00000000000f");
    private static final UUID GATEWAY = UUID.fromString("99999999-0000-4000-8000-000000000009");
    private static final String PROVIDER = "TESTCARRIER";
    private static final String DESTINATION = "+919800000009";

    private DidRepository didRepository;
    private VoiceRouteProfileRepository profileRepository;
    private GatewayRoutingPort gatewayPort;
    private VoiceRoutingService service;

    @BeforeEach
    void setUp() {
        didRepository = mock(DidRepository.class);
        profileRepository = mock(VoiceRouteProfileRepository.class);
        gatewayPort = mock(GatewayRoutingPort.class);

        VoiceEligibilityService eligibility = mock(VoiceEligibilityService.class);
        // Routing consults the 3-arg overload (tenant, destination, did); the
        // 4-arg form is the campaign dial path and is stubbed separately there.
        when(eligibility.evaluate(any(), anyString(), any()))
                .thenReturn(VoiceEligibility.EligibilityResult.allowed());

        VoiceCapacityService capacity = mock(VoiceCapacityService.class);
        when(capacity.checkCapacity(any(), any()))
                .thenReturn(new VoiceCapacityService.CapacityCheckResult(true, null));

        when(gatewayPort.findGateway(GATEWAY)).thenReturn(Optional.of(gateway()));
        // The tenant must be authorized for the gateway, otherwise the
        // rejection happens earlier and the DID check is never reached.
        when(gatewayPort.isGatewayAuthorized(any(), any(), any())).thenReturn(true);

        service = new VoiceRoutingService(
                profileRepository, gatewayPort, capacity, eligibility, didRepository);

        // The campaign's own DID is valid and owned by the tenant, so any refusal
        // observed below is attributable to the PINNED did alone.
        when(didRepository.findByIdAndDeletedAtIsNull(CAMPAIGN_DID))
                .thenReturn(Optional.of(
                        did(CAMPAIGN_DID, TENANT, DidStatus.ACTIVE, AllocationState.ASSIGNED)));
    }

    private DidEntity did(UUID id, UUID tenantId, DidStatus status, AllocationState state) {
        DidEntity did = new DidEntity();
        did.setId(id);
        did.setTenantId(tenantId);
        did.setE164Number("+919800000001");
        did.setCountryCode("IN");
        did.setProvider(PROVIDER);
        did.setStatus(status);
        did.setAllocationState(state);
        return did;
    }

    private VoiceRouteProfileEntry entry(RouteType type, int priority) {
        VoiceRouteProfileEntry e = new VoiceRouteProfileEntry();
        e.setGatewayId(GATEWAY);
        e.setEnabled(true);
        e.setPriority(priority);
        e.setRouteType(type);
        return e;
    }

    /** A profile whose single primary entry pins {@link #PINNED_DID}. */
    private void stubProfile() {
        VoiceRouteProfile profile = new VoiceRouteProfile();
        profile.setId(PROFILE);
        profile.setTenantId(TENANT);
        profile.setAutoOverflowEnabled(false);
        profile.setAutoFailoverEnabled(false);

        VoiceRouteProfileEntry pinned = entry(RouteType.PRIMARY, 10);
        pinned.setDidId(PINNED_DID);
        profile.addPrimaryRoute(pinned);

        when(profileRepository.findByIdAndTenantIdAndDeletedAtIsNull(PROFILE, TENANT))
                .thenReturn(Optional.of(profile));
    }

    private VoiceRoutingDecision resolve() {
        return service.resolveRoute(TENANT, null, DESTINATION, CAMPAIGN_DID, "PLAYFILE", PROFILE);
    }

    private GatewayRouteView gateway() {
        return new GatewayRouteView(GATEWAY, "gw", PROVIDER, "fs-gw", "external",
                true, GatewayRouteStatus.ACTIVE);
    }

    @Nested
    class UsablePinnedDid {

        @Test
        @DisplayName("DID-1: a valid tenant-owned, ACTIVE, ASSIGNED pinned DID is used")
        void validPinnedDidIsUsed() {
            stubProfile();
            when(didRepository.findByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                    PINNED_DID, TENANT, DidStatus.ACTIVE, AllocationState.ASSIGNED))
                    .thenReturn(Optional.of(
                            did(PINNED_DID, TENANT, DidStatus.ACTIVE, AllocationState.ASSIGNED)));

            VoiceRoutingDecision decision = resolve();

            assertThat(decision.selectedRoute()).isNotNull();
            assertThat(decision.selectedRoute().didId())
                    .as("the pinned DID is the one actually dialed")
                    .isEqualTo(PINNED_DID);
        }
    }

    @Nested
    class RefusedPinnedDid {

        @Test
        @DisplayName("DID-2: a pinned DID that fails the ownership gate is refused, not dialed")
        void foreignPinnedDidIsRefused() {
            // The ownership lookup cannot resolve for a foreign tenant, an
            // INACTIVE DID, or a pool DID with tenant_id IS NULL - all three are
            // the same refusal, which is the point: one tenant-bounded predicate.
            stubProfile();
            when(didRepository.findByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                    PINNED_DID, TENANT, DidStatus.ACTIVE, AllocationState.ASSIGNED))
                    .thenReturn(Optional.empty());

            VoiceRoutingDecision decision = resolve();

            assertThat(decision.selectedRoute())
                    .as("a foreign or unusable DID must never become the caller ID")
                    .isNull();
            assertThat(decision.decisionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_DID_INCOMPATIBLE.getCode());
        }

        @Test
        @DisplayName("DID-3: the refusal never silently falls back to the campaign DID")
        void noSilentFallbackToCampaignDid() {
            stubProfile();
            when(didRepository.findByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
                    PINNED_DID, TENANT, DidStatus.ACTIVE, AllocationState.ASSIGNED))
                    .thenReturn(Optional.empty());

            // Falling back would dial the campaign's DID through a gateway the
            // operator deliberately did not pair with it. The pre-VB-6E
            // behaviour was a rejection, and it stays one.
            assertThat(resolve().selectedRoute()).isNull();
        }
    }

    @Nested
    class RouteTypeFiltering {

        @Test
        @DisplayName("TYPE-1: the primary pass considers only PRIMARY entries")
        void primaryPassSeesOnlyPrimaryEntries() {
            VoiceRouteProfile profile = new VoiceRouteProfile();
            profile.setId(PROFILE);
            profile.setTenantId(TENANT);
            profile.addPrimaryRoute(entry(RouteType.PRIMARY, 10));
            profile.addOverflowRoute(entry(RouteType.OVERFLOW, 1));

            // Pre-VB-6E all three collections returned the same rows, so the
            // primary pass would have picked the priority-1 OVERFLOW entry and
            // labelled its decision PRIMARY.
            assertThat(profile.routesOfType(RouteType.PRIMARY))
                    .extracting(VoiceRouteProfileEntry::getRouteType)
                    .containsExactly(RouteType.PRIMARY);
            assertThat(profile.routesOfType(RouteType.OVERFLOW))
                    .extracting(VoiceRouteProfileEntry::getRouteType)
                    .containsExactly(RouteType.OVERFLOW);
        }

        @Test
        @DisplayName("TYPE-2: each route type is disjoint and priority-ordered")
        void routeTypesAreDisjointAndOrdered() {
            VoiceRouteProfile profile = new VoiceRouteProfile();
            profile.setId(PROFILE);
            profile.setTenantId(TENANT);
            profile.addPrimaryRoute(entry(RouteType.PRIMARY, 30));
            profile.addPrimaryRoute(entry(RouteType.PRIMARY, 10));
            profile.addPrimaryRoute(entry(RouteType.PRIMARY, 20));
            profile.addOverflowRoute(entry(RouteType.OVERFLOW, 5));
            profile.addFailoverRoute(entry(RouteType.FAILOVER, 7));

            assertThat(profile.routesOfType(RouteType.PRIMARY))
                    .extracting(VoiceRouteProfileEntry::getPriority)
                    .containsExactly(10, 20, 30);
            assertThat(profile.routesOfType(RouteType.OVERFLOW)).hasSize(1);
            assertThat(profile.routesOfType(RouteType.FAILOVER)).hasSize(1);
        }
    }
}
