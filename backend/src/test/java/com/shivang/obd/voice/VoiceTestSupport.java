package com.shivang.obd.voice;

import com.shivang.obd.common.lifecycle.LifecycleStatus;
import com.shivang.obd.did.AllocationState;
import com.shivang.obd.did.DidEntity;
import com.shivang.obd.did.DidStatus;
import com.shivang.obd.did.NumberType;
import com.shivang.obd.reseller.ResellerEntity;
import com.shivang.obd.telephony.PhoneListEntry;
import com.shivang.obd.telephony.PhoneListType;
import com.shivang.obd.telephony.ScopeType;
import com.shivang.obd.telephony.SipGateway;
import com.shivang.obd.telephony.SipGatewayAllocation;
import com.shivang.obd.telephony.SipGatewayOwnerType;
import com.shivang.obd.telephony.SipGatewayStatus;
import com.shivang.obd.tenant.TenantEntity;
import com.shivang.obd.voice.call.CallDirection;
import com.shivang.obd.voice.call.CallLeg;
import com.shivang.obd.voice.call.CallLegStatus;
import com.shivang.obd.voice.call.CallLegType;
import com.shivang.obd.voice.call.CallSession;
import com.shivang.obd.voice.call.CallSessionStatus;
import com.shivang.obd.voice.call.CallType;
import com.shivang.obd.voice.routing.RouteType;
import com.shivang.obd.voice.routing.VoiceRouteProfile;
import com.shivang.obd.voice.routing.VoiceRouteProfileEntry;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared test fixtures for voice module tests.
 * <p>
 * Public so sub-packages (voice.routing, voice.capacity) can reuse the
 * builders; only factories and constants are exposed.
 */
public final class VoiceTestSupport {

    /** Stable, valid UUIDs so test output is easy to correlate across suites. */
    public static final UUID TENANT_A = UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    public static final UUID TENANT_B = UUID.fromString("bb000000-0000-4000-8000-00000000000b");
    public static final UUID RESELLER_X = UUID.fromString("a1000000-0000-4000-8000-00000000000x".replace("x", "1"));
    public static final UUID GATEWAY_A = UUID.fromString("aa000000-0000-4000-8000-00000000001a");
    public static final UUID GATEWAY_B = UUID.fromString("bb000000-0000-4000-8000-00000000001b");
    public static final UUID GATEWAY_C = UUID.fromString("cc000000-0000-4000-8000-00000000001c");
    public static final UUID DID_A = UUID.fromString("aa000000-0000-4000-8000-00000000002a");
    public static final UUID DID_B = UUID.fromString("bb000000-0000-4000-8000-00000000002b");
    public static final UUID PROFILE_ID = UUID.fromString("aa000000-0000-4000-8000-000000000031");
    public static final UUID ALLOCATION_ID = UUID.fromString("aa000000-0000-4000-8000-00000000004a");

    private VoiceTestSupport() {}

    static TenantEntity tenant(UUID id) {
        TenantEntity t = new TenantEntity();
        t.setId(id);
        t.setName("Test Tenant");
        t.setSlug("test-tenant");
        t.setStatus(LifecycleStatus.ACTIVE);
        return t;
    }

    static ResellerEntity reseller(UUID id) {
        ResellerEntity r = new ResellerEntity();
        r.setId(id);
        r.setName("Test Reseller");
        r.setSlug("test-reseller");
        r.setStatus(LifecycleStatus.ACTIVE);
        return r;
    }

    /** Standard healthy gateway: ACTIVE, enabled, platform-owned, no headroom. */
    public static SipGateway gateway(UUID id) {
        SipGateway g = new SipGateway();
        g.setId(id);
        g.setName("Test Gateway");
        g.setDisplayName("Test Gateway");
        g.setProvider("TATA");
        g.setFreeSwitchGatewayName("fs-gw-test");
        g.setFreeSwitchProfile("external");
        g.setStatus(SipGatewayStatus.ACTIVE);
        g.setOwnerType(SipGatewayOwnerType.PLATFORM);
        g.setMaxConcurrentChannels(100);
        g.setMaxCps(10);
        g.setCapacityHeadroomPct(0);
        g.setPriority(50);
        g.setEnabled(true);
        return g;
    }

    /** Gateway with explicit provider/capacity configuration. */
    public static SipGateway gateway(UUID id, String provider, int maxChannels, Integer maxCps, int headroomPct) {
        SipGateway g = gateway(UUID.randomUUID());
        g.setId(id);
        g.setProvider(provider);
        g.setMaxConcurrentChannels(maxChannels);
        g.setMaxCps(maxCps);
        g.setCapacityHeadroomPct(headroomPct);
        return g;
    }

    public static SipGateway gatewayDown(UUID id) {
        SipGateway g = gateway(id);
        g.setStatus(SipGatewayStatus.INACTIVE);
        return g;
    }

    public static SipGateway gatewayDegraded(UUID id) {
        SipGateway g = gateway(id);
        g.setStatus(SipGatewayStatus.DEGRADED);
        return g;
    }

    public static SipGatewayAllocation allocation(UUID id, UUID gatewayId, UUID tenantId) {
        SipGatewayAllocation a = new SipGatewayAllocation();
        a.setId(id);
        a.setGatewayId(gatewayId);
        a.setTenantId(tenantId);
        a.setEnabled(true);
        a.setPriority(50);
        a.setMaxConcurrentChannels(100);
        a.setMaxCps(10);
        a.setCapacityHeadroomPct(0);
        return a;
    }

    public static SipGatewayAllocation allocation(UUID id, UUID gatewayId, UUID tenantId,
            int maxChannels, Integer maxCps, int headroomPct) {
        SipGatewayAllocation a = allocation(id, gatewayId, tenantId);
        a.setMaxConcurrentChannels(maxChannels);
        a.setMaxCps(maxCps);
        a.setCapacityHeadroomPct(headroomPct);
        return a;
    }

    public static DidEntity did(UUID id, UUID tenantId, String provider) {
        DidEntity d = new DidEntity();
        d.setId(id);
        d.setTenantId(tenantId);
        d.setE164Number("+919876543210");
        d.setCountryCode("91");
        d.setNumberType(NumberType.MOBILE);
        d.setProvider(provider);
        d.setStatus(DidStatus.ACTIVE);
        d.setAllocationState(AllocationState.ASSIGNED);
        return d;
    }

    public static DidEntity did(UUID id, UUID tenantId, String provider, String e164) {
        DidEntity d = did(id, tenantId, provider);
        d.setE164Number(e164);
        return d;
    }

    public static VoiceRouteProfile profile(UUID id, UUID tenantId) {
        VoiceRouteProfile p = new VoiceRouteProfile();
        p.setId(id);
        p.setTenantId(tenantId);
        p.setName("Default Profile");
        p.setAutoOverflowEnabled(true);
        p.setAutoFailoverEnabled(true);
        return p;
    }

    public static VoiceRouteProfileEntry entry(UUID gatewayId, RouteType type, int priority) {
        VoiceRouteProfileEntry e = new VoiceRouteProfileEntry();
        e.setGatewayId(gatewayId);
        e.setRouteType(type);
        e.setPriority(priority);
        e.setEnabled(true);
        return e;
    }

    public static VoiceRouteProfileEntry entry(UUID gatewayId, RouteType type, int priority, UUID didId) {
        VoiceRouteProfileEntry e = entry(gatewayId, type, priority);
        e.setDidId(didId);
        return e;
    }

    public static VoiceRouteProfileEntry disabledEntry(UUID gatewayId, RouteType type, int priority) {
        VoiceRouteProfileEntry e = entry(gatewayId, type, priority);
        e.setEnabled(false);
        return e;
    }

    public static CallSession session(UUID id, UUID tenantId, UUID gatewayId, UUID didId) {
        CallSession s = new CallSession();
        s.setId(id);
        s.setTenantId(tenantId);
        s.setGatewayId(gatewayId);
        s.setDidId(didId);
        s.setDestinationNumber("+919876543210");
        s.setDirection(CallDirection.OUTBOUND);
        s.setCallType(CallType.VOICE_BLAST);
        s.setStatus(CallSessionStatus.DIALING);
        s.setInitiatedAt(Instant.now());
        s.setProviderCallId("fs-call-" + UUID.randomUUID());
        return s;
    }

    public static CallLeg leg(UUID id, UUID sessionId, CallLegType type) {
        CallLeg l = new CallLeg();
        l.setId(id);
        l.setCallSessionId(sessionId);
        l.setLegType(type);
        l.setDirection(CallDirection.OUTBOUND);
        l.setStatus(CallLegStatus.DIALING);
        l.setTarget("+919876543210");
        l.setProviderCallId("fs-call-" + UUID.randomUUID());
        l.setInitiatedAt(Instant.now());
        return l;
    }

    public static PhoneListEntry phoneListEntry(UUID id, PhoneListType type, ScopeType scopeType, String normalizedNumber) {
        PhoneListEntry e = new PhoneListEntry();
        e.setId(id);
        e.setType(type);
        e.setScopeType(scopeType);
        e.setNormalizedNumber(normalizedNumber);
        e.setActive(true);
        return e;
    }
}
