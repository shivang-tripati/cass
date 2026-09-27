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
import com.shivang.obd.telephony.SipGatewayOwnerType;
import com.shivang.obd.telephony.SipGatewayRepository;
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
 * VB-0 policy hierarchy tests (P1–P4).
 * <p>
 * Architecture §5: "A lower-level preference can never override a
 * higher-level restriction." The campaign (lowest level) may prefer any
 * gateway, but platform restrictions, reseller restrictions, and missing
 * allocations reject the route regardless of preference.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class VoicePolicyHierarchyTest {

    private VoiceRouteProfileRepository profileRepository;
    private SipGatewayRepository gatewayRepository;
    private com.shivang.obd.telephony.SipGatewayAllocationRepository allocationRepository;
    private VoiceCapacityService voiceCapacity;
    private VoiceEligibility voiceEligibility;
    private DidRepository didRepository;
    private GatewayRoutingPort gatewayRoutingPort;
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
        gatewayRepository = mock(SipGatewayRepository.class);
        allocationRepository = mock(com.shivang.obd.telephony.SipGatewayAllocationRepository.class);
        voiceCapacity = mock(VoiceCapacityService.class);
        voiceEligibility = mock(VoiceEligibility.class);
        didRepository = mock(DidRepository.class);
        gatewayRoutingPort = mock(GatewayRoutingPort.class);
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

    private void stubGateway(UUID id, String provider, SipGatewayOwnerType ownerType) {
        SipGateway g = gateway(id, provider, 100, 10, 0);
        g.setOwnerType(ownerType);
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

    private void stubEligibilityAllowed() {
        when(voiceEligibility.evaluate(TENANT, DEST, DID))
                .thenReturn(VoiceEligibility.EligibilityResult.allowed());
    }

    @Nested
    class PlatformRestrictions {

        @Test
        void P1_platformRestrictedGatewayB_rejectedEvenThoughCampaignPrefersIt() {
            // Profile prefers B (as primary!); platform policy blocks B for this tenant.
            VoiceRouteProfile p = profile(PROFILE, TENANT);
            p.addPrimaryRoute(entry(GW_B, RouteType.PRIMARY, 1)); // top preference
            p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10)); // allowed fallback
            stubProfile(p);

            stubGateway(GW_B, "TATA", SipGatewayOwnerType.PLATFORM);
            stubGateway(GW_A, "TATA", SipGatewayOwnerType.PLATFORM);
            stubDid();
            stubEligibilityAllowed();

            // Platform restriction: B blocked, A allowed
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), org.mockito.ArgumentMatchers.argThat(
                    g -> g != null && GW_B.equals(g.id())))).thenReturn(false);
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), org.mockito.ArgumentMatchers.argThat(
                    g -> g != null && GW_A.equals(g.id())))).thenReturn(true);
            when(voiceCapacity.checkCapacity(GW_A, TENANT))
                    .thenReturn(VoiceCapacityService.CapacityCheckResult.ok());

            VoiceRoutingDecision d = service.resolveRoute(TENANT, RESELLER, DEST, DID, CALL_TYPE, PROFILE);

            assertThat(d.selectedRoute()).isNotNull();
            assertThat(d.selectedRoute().gatewayId()).isEqualTo(GW_A);
            assertThat(d.routeType()).isEqualTo(RouteType.PRIMARY);
            assertThat(d.rejectedRoutes())
                    .anyMatch(r -> GW_B.equals(r.gatewayId())
                            && VoiceRoutingReason.ROUTE_REJECTED_TENANT_NOT_AUTHORIZED.getCode().equals(r.rejectionReason()));
        }
    }

    @Nested
    class ResellerRestrictions {

        @Test
        void P2_resellerRestrictedGatewayC_deniedToTenantTryingToUseIt() {
            // GW-C is reseller-owned; the tenant's reseller has no allocation.
            VoiceRouteProfile p = profile(PROFILE, TENANT);
            p.addPrimaryRoute(entry(GW_C, RouteType.PRIMARY, 1)); // campaign prefers C
            p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10));
            stubProfile(p);

            stubGateway(GW_C, "TATA", SipGatewayOwnerType.RESELLER);
            stubGateway(GW_A, "TATA", SipGatewayOwnerType.PLATFORM);
            stubDid();
            stubEligibilityAllowed();

            // Reseller restriction: C denied (reseller-owned, no allocation),
            // A allowed (platform-owned shared infrastructure).
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), org.mockito.ArgumentMatchers.argThat(
                    g -> g != null && GW_C.equals(g.id())))).thenReturn(false);
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), org.mockito.ArgumentMatchers.argThat(
                    g -> g != null && GW_A.equals(g.id())))).thenReturn(true);
            when(voiceCapacity.checkCapacity(GW_A, TENANT))
                    .thenReturn(VoiceCapacityService.CapacityCheckResult.ok());

            VoiceRoutingDecision d = service.resolveRoute(TENANT, RESELLER, DEST, DID, CALL_TYPE, PROFILE);

            assertThat(d.selectedRoute()).isNotNull();
            assertThat(d.selectedRoute().gatewayId()).isEqualTo(GW_A);
            assertThat(d.rejectedRoutes())
                    .anyMatch(r -> GW_C.equals(r.gatewayId())
                            && VoiceRoutingReason.ROUTE_REJECTED_TENANT_NOT_AUTHORIZED.getCode().equals(r.rejectionReason()));
        }
    }

    @Nested
    class AuthorizationService {

        @org.junit.jupiter.api.Test
        void platformOwnedGateway_isAuthorizedForAnyTenant() {
            com.shivang.obd.telephony.GatewayAuthorizationService real =
                    new com.shivang.obd.telephony.GatewayAuthorizationService(allocationRepository);
            SipGateway platformGateway = gateway(GW_A, "TATA", 100, 10, 0);
            platformGateway.setOwnerType(SipGatewayOwnerType.PLATFORM);

            org.assertj.core.api.Assertions.assertThat(real.isGatewayAuthorized(TENANT, RESELLER, platformGateway)).isTrue();
        }

        @org.junit.jupiter.api.Test
        void resellerOwnedGateway_requiresTenantOrResellerAllocation() {
            com.shivang.obd.telephony.GatewayAuthorizationService real =
                    new com.shivang.obd.telephony.GatewayAuthorizationService(allocationRepository);
            SipGateway resellerGateway = gateway(GW_C, "TATA", 100, 10, 0);
            resellerGateway.setOwnerType(SipGatewayOwnerType.RESELLER);
            resellerGateway.setOwnerResellerId(RESELLER);

            org.mockito.Mockito.when(allocationRepository.existsByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(GW_C, TENANT))
                    .thenReturn(false);
            org.mockito.Mockito.when(allocationRepository.existsByGatewayIdAndResellerIdAndEnabledTrueAndDeletedAtIsNull(GW_C, RESELLER))
                    .thenReturn(false);
            org.assertj.core.api.Assertions.assertThat(real.isGatewayAuthorized(TENANT, RESELLER, resellerGateway)).isFalse();

            org.mockito.Mockito.when(allocationRepository.existsByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(GW_C, TENANT))
                    .thenReturn(true);
            org.assertj.core.api.Assertions.assertThat(real.isGatewayAuthorized(TENANT, RESELLER, resellerGateway)).isTrue();
        }

        @org.junit.jupiter.api.Test
        void resellerOwnedGateway_resellerAllocationGrantsAccess_tenantAllocationNotRequired() {
            com.shivang.obd.telephony.GatewayAuthorizationService real =
                    new com.shivang.obd.telephony.GatewayAuthorizationService(allocationRepository);
            SipGateway resellerGateway = gateway(GW_C, "TATA", 100, 10, 0);
            resellerGateway.setOwnerType(SipGatewayOwnerType.RESELLER);

            org.mockito.Mockito.when(allocationRepository.existsByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(GW_C, TENANT))
                    .thenReturn(false);
            org.mockito.Mockito.when(allocationRepository.existsByGatewayIdAndResellerIdAndEnabledTrueAndDeletedAtIsNull(GW_C, RESELLER))
                    .thenReturn(true);
            org.assertj.core.api.Assertions.assertThat(real.isGatewayAuthorized(TENANT, RESELLER, resellerGateway)).isTrue();
        }

        @org.junit.jupiter.api.Test
        void nullTenantOrNullGateway_isNeverAuthorized() {
            com.shivang.obd.telephony.GatewayAuthorizationService real =
                    new com.shivang.obd.telephony.GatewayAuthorizationService(allocationRepository);

            org.assertj.core.api.Assertions.assertThat(real.isGatewayAuthorized(null, RESELLER, gateway(GW_A, "TATA", 100, 10, 0))).isFalse();
            org.assertj.core.api.Assertions.assertThat(real.isGatewayAuthorized(TENANT, RESELLER, null)).isFalse();
        }

        @org.junit.jupiter.api.Test
        void P3_noGatewayAllocation_capacityLayerRejectsAsUnauthorized() {
            // Capacity layer fails closed: no tenant/reseller allocation => not usable.
            SipGateway g = gateway(GW_A, "TATA", 100, 10, 0);
            org.mockito.Mockito.when(gatewayRepository.findByIdAndDeletedAtIsNull(GW_A)).thenReturn(Optional.of(g));
            org.mockito.Mockito.when(allocationRepository.existsByGatewayIdAndTenantIdAndEnabledTrueAndDeletedAtIsNull(GW_A, TENANT))
                    .thenReturn(false);

            com.shivang.obd.telephony.VoiceCapacityServiceImpl capacity =
                    new com.shivang.obd.telephony.VoiceCapacityServiceImpl(gatewayRepository, allocationRepository);
            jakarta.persistence.EntityManager em = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
            try {
                var f = com.shivang.obd.telephony.VoiceCapacityServiceImpl.class.getDeclaredField("entityManager");
                f.setAccessible(true);
                f.set(capacity, em);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }

            var result = capacity.checkCapacity(GW_A, TENANT);
            org.assertj.core.api.Assertions.assertThat(result.available()).isFalse();
            org.assertj.core.api.Assertions.assertThat(result.rejectionReason())
                    .isEqualTo(VoiceRoutingReason.ROUTE_REJECTED_TENANT_NOT_AUTHORIZED.getCode());
        }
    }

    @Nested
    class CampaignPreference {

        @Test
        void P4_campaignPreferenceCanNeverBypassAuthorization_restrictionWinsOverTopPriority() {
            // The campaign's top-preference gateway (priority 1) is restricted;
            // the allowed gateway has lower preference (priority 10). The
            // restricted one must be rejected despite being preferred.
            VoiceRouteProfile p = profile(PROFILE, TENANT);
            p.addPrimaryRoute(entry(GW_B, RouteType.PRIMARY, 1));  // restricted, preferred
            p.addPrimaryRoute(entry(GW_A, RouteType.PRIMARY, 10)); // allowed
            stubProfile(p);

            stubGateway(GW_B, "TATA", SipGatewayOwnerType.PLATFORM);
            stubGateway(GW_A, "TATA", SipGatewayOwnerType.PLATFORM);
            stubDid();
            stubEligibilityAllowed();

            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), org.mockito.ArgumentMatchers.argThat(
                    g -> g != null && GW_B.equals(g.id())))).thenReturn(false);
            when(gatewayRoutingPort.isGatewayAuthorized(eq(TENANT), eq(RESELLER), org.mockito.ArgumentMatchers.argThat(
                    g -> g != null && GW_A.equals(g.id())))).thenReturn(true);
            when(voiceCapacity.checkCapacity(GW_A, TENANT))
                    .thenReturn(VoiceCapacityService.CapacityCheckResult.ok());

            VoiceRoutingDecision d = service.resolveRoute(TENANT, RESELLER, DEST, DID, CALL_TYPE, PROFILE);

            // The restriction wins over the campaign preference.
            assertThat(d.selectedRoute().gatewayId()).isEqualTo(GW_A);
            assertThat(d.rejectedRoutes())
                    .anyMatch(r -> GW_B.equals(r.gatewayId())
                            && VoiceRoutingReason.ROUTE_REJECTED_TENANT_NOT_AUTHORIZED.getCode().equals(r.rejectionReason()));
        }
    }
}
