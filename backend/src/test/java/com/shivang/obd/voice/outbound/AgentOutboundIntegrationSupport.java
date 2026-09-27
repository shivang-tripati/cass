package com.shivang.obd.voice.outbound;

import com.shivang.obd.did.DidEntity;
import com.shivang.obd.did.DidRepository;
import com.shivang.obd.telephony.SipGateway;
import com.shivang.obd.telephony.SipGatewayAllocation;
import com.shivang.obd.telephony.SipGatewayAllocationRepository;
import com.shivang.obd.telephony.SipGatewayRepository;
import com.shivang.obd.telephony.SipGatewayRoutingAdapter;
import com.shivang.obd.telephony.SipGatewayRoutingService;
import com.shivang.obd.telephony.SipGatewayStatus;
import com.shivang.obd.telephony.VoiceCapacityServiceImpl;
import com.shivang.obd.telephony.VoiceEligibilityService;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.tenant.TenantRepository;
import com.shivang.obd.voice.acd.AcdIntegrationSupport;
import com.shivang.obd.voice.call.EndpointType;
import com.shivang.obd.voice.routing.GatewayRoutingPort;
import com.shivang.obd.voice.routing.VoiceRouteProfile;
import com.shivang.obd.voice.routing.VoiceRouteProfileEntry;
import com.shivang.obd.voice.routing.VoiceRouteProfileRepository;
import com.shivang.obd.voice.routing.VoiceRoutingService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * VB-4E integration support: extends the VB-4C harness (real PostgreSQL,
 * Flyway V1..V38, real advisory locks, real {@code AgentReservationService})
 * with the REAL outbound path collaborators —
 * {@link VoiceCapacityServiceImpl} (VB-0 channel/CPS reservations),
 * {@link VoiceRoutingService} + {@link VoiceEligibilityService} +
 * {@link SipGatewayRoutingAdapter} (VB-0 routing), and gateway/DID/
 * profile seeding. Only the provider-edge dialers are Mockito fakes.
 */
public abstract class AgentOutboundIntegrationSupport extends AcdIntegrationSupport {

    @Autowired
    protected SipGatewayRepository gatewayRepository;
    @Autowired
    protected SipGatewayAllocationRepository allocationRepository;
    @Autowired
    protected DidRepository didRepository;
    @Autowired
    protected VoiceRouteProfileRepository profileRepository;
    @Autowired
    protected TenantRepository tenantRepository;
    @Autowired
    protected com.shivang.obd.voice.call.CallLegRepository callLegRepository;
    @Autowired
    protected com.shivang.obd.reseller.ResellerRepository resellerRepository;
    @Autowired
    protected com.shivang.obd.telephony.PhoneListEntryRepository phoneListEntryRepository;

    protected VoiceCapacityServiceImpl capacityService;
    protected VoiceRoutingService routingService;

    /** Builds the real VB-0 capacity + routing stack over real repositories. */
    protected void initOutboundServices() {
        initAcdServices();
        capacityService = new VoiceCapacityServiceImpl(gatewayRepository, allocationRepository);
        // VoiceCapacityServiceImpl uses @PersistenceContext field injection;
        // mirror the established harness pattern (VoiceCapacityServiceTest)
        // so the real advisory-lock usage queries run against the container.
        try {
            var field = VoiceCapacityServiceImpl.class.getDeclaredField("entityManager");
            field.setAccessible(true);
            field.set(capacityService, entityManager);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot inject EntityManager into capacity service", e);
        }
        GatewayRoutingPort gatewayRoutingPort =
                new SipGatewayRoutingAdapter(gatewayRepository, new com.shivang.obd.telephony.GatewayAuthorizationService(allocationRepository));
        VoiceEligibilityService eligibilityService = new VoiceEligibilityService(
                phoneListEntryRepository, new SipGatewayRoutingService(
                        new com.shivang.obd.telephony.SipGatewayResolver(
                                gatewayRepository, allocationRepository)),
                capacityService, tenantRepository, didRepository);
        routingService = new VoiceRoutingService(
                profileRepository, gatewayRoutingPort, capacityService,
                eligibilityService, didRepository);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID seedGateway(String provider, int maxChannels) {
        SipGateway gw = new SipGateway();
        gw.setName("gw-" + UUID.randomUUID().toString().substring(0, 8));
        gw.setProvider(provider);
        gw.setFreeSwitchGatewayName("fs-" + gw.getName());
        gw.setFreeSwitchProfile("external");
        gw.setStatus(SipGatewayStatus.ACTIVE);
        gw.setEnabled(true);
        gw.setMaxConcurrentChannels(maxChannels);
        return gatewayRepository.save(gw).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID seedAllocation(UUID gatewayId, UUID tenantId) {
        SipGatewayAllocation a = new SipGatewayAllocation();
        a.setGatewayId(gatewayId);
        a.setTenantId(tenantId);
        a.setEnabled(true);
        return allocationRepository.save(a).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID seedRoutingDid(UUID tenantId, String provider, String e164) {
        TenantEntity tenant = tenantRepository.findByIdAndDeletedAtIsNull(tenantId).orElseThrow();
        // A real reseller row (tenants.reseller_id is an FK to resellers).
        com.shivang.obd.reseller.ResellerEntity reseller = new com.shivang.obd.reseller.ResellerEntity();
        reseller.setName("reseller-" + UUID.randomUUID().toString().substring(0, 8));
        reseller.setSlug("r-" + UUID.randomUUID().toString().substring(0, 8));
        reseller.setStatus(com.shivang.obd.common.lifecycle.LifecycleStatus.ACTIVE);
        reseller = resellerRepository.saveAndFlush(reseller);
        tenant.setResellerId(reseller.getId());
        tenantRepository.save(tenant);
        DidEntity did = new DidEntity();
        did.setTenantId(tenantId);
        did.setProvider(provider);
        did.setE164Number(e164);
        did.setCountryCode("+91");
        did.setNumberType(com.shivang.obd.did.NumberType.MOBILE);
        did.setStatus(com.shivang.obd.did.DidStatus.ACTIVE);
        did.setAllocationState(com.shivang.obd.did.AllocationState.ASSIGNED);
        did = didRepository.save(did);
        return did.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID seedRoutingProfile(UUID tenantId, UUID gatewayId, UUID didId) {
        VoiceRouteProfile profile = new VoiceRouteProfile();
        profile.setTenantId(tenantId);
        profile.setName("agent-outbound-" + UUID.randomUUID().toString().substring(0, 8));
        profile.setAutoOverflowEnabled(false);
        profile.setAutoFailoverEnabled(false);
        VoiceRouteProfileEntry entry = new VoiceRouteProfileEntry();
        entry.setGatewayId(gatewayId);
        entry.setRouteType(com.shivang.obd.voice.routing.RouteType.PRIMARY);
        entry.setPriority(1);
        entry.setEnabled(true);
        entry.setDidId(didId);
        profile.addPrimaryRoute(entry);
        return profileRepository.save(profile).getId();
    }

    /** Raw count of live channel reservations for a gateway. */
    public int channelReservations(UUID gatewayId) {
        return inTx(() -> {
            Number n = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM voice_channel_reservations "
                    + "WHERE gateway_id = :g AND released_at IS NULL")
                    .setParameter("g", gatewayId).getSingleResult();
            return n.intValue();
        });
    }
}
