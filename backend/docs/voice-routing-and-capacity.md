# Voice Routing and Capacity

**Status:** Architecture / Product Operations Specification  
**Scope:** Voice CPaaS — OBD / Voice Blast, Contact Center, Agent Calling, and future AI Voice

## 1. Purpose

This document defines how the platform decides:

1. Which gateways/trunks a tenant or campaign is allowed to use.
2. Which DID/CLI can be used with a selected route.
3. Which eligible gateway should receive a call.
4. When a route is considered full or unavailable.
5. When traffic may overflow or fail over to another gateway.
6. How capacity is reserved and released.
7. How multiple campaigns compete for shared capacity.
8. How Platform Admin controls and overrides routing decisions.
9. How every routing decision remains explainable and auditable.

**Core principle:** The platform may automate execution, but **Platform Admin controls routing policy and business/operations decisions**.

## 2. Three Separate Decisions

### A. Routing Eligibility — “Can this call use this route?”

Check tenant/reseller authorization, enterprise or reseller restrictions, campaign permissions, DID/CLI compatibility, provider/commercial restrictions, and gateway operational state.

If a gateway is not eligible, available capacity does not make it usable.

### B. Capacity Admission — “Can this route handle the call now?”

Check concurrent channels, CPS, active reservations, configured headroom, maintenance, and provider limits.

A gateway can be eligible but temporarily unavailable because it is full.

### C. Route Selection — “Which eligible available route should be used?”

Selection follows explicit routing policy. The system automates execution of the policy; it does not invent business decisions.

## 3. Gateways Are Independent Capacity Domains

Example:

| Gateway | Channels | CPS | Intended Use |
|---|---:|---:|---|
| A | 100 | 5 | Standard |
| B | 200 | 10 | High-volume / enterprise |
| C | 50 | 3 | Reseller-specific |

These are **not automatically one common pool** of 350 channels / 18 CPS. Each gateway has independent limits and access policy.

Aggregate capacity may be used for planning, but every call is admitted against its selected route.

## 4. When Gateway A Reaches Capacity

Suppose A is:

```text
100 channels / 100 used
5 CPS / 5 used
```

The system must **not** simply choose B because B has spare capacity.

It must evaluate:

1. Is the tenant/reseller authorized for B?
2. Is the campaign allowed to use B?
3. Is the DID/CLI compatible with B?
4. Does routing policy permit B as overflow/failover?
5. Is B healthy and enabled?
6. Does B have channel and CPS capacity?
7. If all pass, reserve B and execute the call.
8. Otherwise queue or use another explicitly approved route.

## 5. Policy Hierarchy

```text
Platform Policy
      ↓
Reseller Policy
      ↓
Tenant Policy
      ↓
Campaign Routing Policy
      ↓
DID / CLI Compatibility
      ↓
Gateway Eligibility
      ↓
Gateway Health
      ↓
Gateway Capacity
      ↓
Route Selection
      ↓
Capacity Reservation
      ↓
FreeSWITCH Originate
```

A lower-level preference can never override a higher-level restriction.

Example: if Gateway B is enterprise-only, a standard tenant cannot use it merely because its campaign prefers B.

## 6. DID / CLI Is Part of Routing Identity

A routing decision may depend on:

```text
Tenant + Reseller + Campaign + Call Type
+ DID/CLI + Destination + Gateway + Route Policy
```

The platform must not silently change caller identity merely because another gateway has capacity.

Example:

```text
DID-A → Gateway A
DID-B → Gateway B
```

If policy explicitly defines A → B failover, the system may switch both gateway and outbound identity:

```text
Gateway A + DID-A
        ↓ failover
Gateway B + DID-B
```

## 7. Primary, Overflow and Failover

### Primary

Normal preferred route.

```text
Primary: Gateway A + DID Pool A
```

### Overflow

Used when the primary route is healthy but temporarily lacks capacity. Overflow must be explicitly enabled by policy.

```text
A full → approved overflow B → B selected
```

### Failover

Used when the primary route is operationally unavailable, such as provider outage, gateway down, maintenance, or admin disablement.

```text
A down → approved failover B → new calls use B
```

Existing calls are not automatically moved.

## 8. Automatic Execution, Human-Controlled Policy

```text
                 PLATFORM ADMIN
                       |
                       v
              Routing Policy
                       |
                       v
              Automated Engine
                       |
       +---------------+---------------+
       |               |               |
   Eligibility      Capacity        Selection
       |               |               |
       +---------------+---------------+
                       |
                       v
                 Reservation
                       |
                       v
                  FreeSWITCH
```

Admin controls the rules; the engine executes them consistently.

## 9. Admin Controls

Platform Admin/authorized operations should be able to control, using the existing authorization model:

- Gateway enable/disable/maintenance state.
- Gateway channel and CPS limits.
- Tenant/reseller gateway access.
- Enterprise-only or reseller-specific routes.
- Primary routes.
- Overflow routes.
- Failover routes.
- Automatic overflow on/off.
- Automatic failover on/off.
- Route priority.
- DID/CLI compatibility mappings.
- Campaign routing preferences where permitted.
- Temporary emergency overrides.
- Override start/end time and reason.

These controls must not expose internal SIP/provider credentials to tenants or resellers.

## 10. Manual Emergency Override

Example:

```text
Campaign: CAM-123
Normal: A primary, B overflow
Override: Prefer B
Start: 10:30
End: 12:00
Reason: Gateway A provider instability
```

Overrides should be explicit, authorized, time-bound where possible, audited, visible to operations, and reversible.

An override may change route preference, but it must **not bypass hard eligibility rules** such as authorization, DID compatibility, provider restrictions, or capacity limits.

When it expires, normal policy resumes.

## 11. Gateway Failure

When A becomes unavailable:

1. Stop new admissions to A.
2. Leave existing calls on A unchanged.
3. Evaluate approved failover routes.
4. Validate tenant/reseller authorization.
5. Validate DID/CLI compatibility.
6. Validate route health.
7. Validate capacity.
8. Reserve the selected route.
9. Record the failover reason.
10. Continue new calls through the approved route.

Never do:

```text
A failed → pick any available gateway
```

## 12. Capacity Model

A route is available only when the required checks pass:

```text
eligible
AND healthy
AND channelsAvailable
AND cpsAvailable
```

Channels and CPS are different:

```text
100 channels = maximum concurrent occupancy
5 CPS       = maximum new-call admission rate
```

Actual sustainable throughput also depends on call occupancy duration.

Different call types have different occupancy characteristics:

- PLAYFILE: typically shorter.
- DTMF: interaction-dependent.
- CONNECT_BY_AGENT: potentially minutes.

Do not use one universal average occupancy as a hardcoded truth. Future capacity planning should use observed P50/P90/P95 occupancy by route and call type.

## 13. Capacity Headroom

Operational headroom should be configurable rather than hardcoded.

Example:

```text
Configured channels = 100
Operational utilization = 90%
Effective target = 90
```

Headroom protects against provider variability, burst behavior, measurement lag, signaling delays, and long-running calls.

## 14. Reservation Lifecycle

Conceptually:

```text
Select route
    ↓
Check capacity
    ↓
Reserve atomically
    ↓
Originate
    ↓
If originate fails → release
    ↓
Call ends → release
```

Reservations require expiry/reconciliation so application or signaling failures cannot leak capacity forever.

Concurrent workers must never be able to reserve more than the configured limit. PostgreSQL-backed transactional coordination is sufficient initially; do not add distributed locking infrastructure without demonstrated need.

## 15. CPS Admission

CPS is an admission-rate limit. A CPS admission is consumed when a call is admitted; channel capacity remains occupied until call release.

Both limits apply simultaneously:

```text
routeAvailable = eligible AND healthy AND channelCapacity AND cpsCapacity
```

## 16. Campaign Scheduling and Fairness

Campaigns should describe intent:

```text
volume
schedule
call type
targets
routing policy/reference
```

They should not own gateways directly as the primary execution model.

A shared scheduler determines which eligible route can accept the next call.

Multiple campaigns competing for the same route should use a deterministic fairness mechanism. Initial round-robin/fair-share is sufficient; later policy can add priority, weighted fairness, reserved capacity, or enterprise priority.

Campaign start means “eligible for scheduling,” not “dedicated gateway capacity.”

## 17. Capacity Exhaustion

If no eligible route currently has capacity, the normal behavior is to queue rather than immediately permanently fail the call, subject to campaign schedule and retry rules.

Temporary capacity exhaustion should not consume a permanent retry attempt unless explicitly defined by retry policy.

Operating windows must never be bypassed simply to clear a queue.

## 18. Campaign vs Route Capacity

Route capacity and campaign policy are different.

Example:

```text
Gateway B: 200 channels / 10 CPS
Campaign X: maximum 50 concurrent calls
```

Campaign X cannot consume all 200 channels unless explicitly configured to do so.

## 19. Deterministic Decision Trail

For every routing decision, operations should be able to reconstruct something like:

```text
Campaign: CAM-123
Tenant: TENANT-45
Requested DID: DID-A

Gateway A:
    Authorized: YES
    Health: UP
    Channels: FULL
    CPS: AVAILABLE
    Decision: REJECTED_CAPACITY

Gateway B:
    Authorized: YES
    DID Compatible: YES
    Health: UP
    Channels: 173/200
    CPS: 7.2/10
    Policy: OVERFLOW_ALLOWED
    Decision: SELECTED

Gateway C:
    Authorized: NO
    Decision: TENANT_NOT_AUTHORIZED
```

Final result:

```text
Gateway: B
Reason: PRIMARY_ROUTE_CAPACITY_EXHAUSTED + APPROVED_OVERFLOW
```

No AI scoring is required.

## 20. Recommended Reason Codes

Reuse existing conventions where available. Conceptually:

```text
ROUTE_SELECTED_PRIMARY
ROUTE_SELECTED_OVERFLOW
ROUTE_SELECTED_FAILOVER

ROUTE_REJECTED_TENANT_NOT_AUTHORIZED
ROUTE_REJECTED_RESELLER_NOT_AUTHORIZED
ROUTE_REJECTED_ENTERPRISE_ONLY
ROUTE_REJECTED_CAMPAIGN_NOT_ALLOWED
ROUTE_REJECTED_DID_INCOMPATIBLE
ROUTE_REJECTED_GATEWAY_DISABLED
ROUTE_REJECTED_GATEWAY_MAINTENANCE
ROUTE_REJECTED_GATEWAY_UNHEALTHY
ROUTE_REJECTED_CHANNEL_CAPACITY
ROUTE_REJECTED_CPS_CAPACITY
ROUTE_REJECTED_NO_APPROVED_FAILOVER
ROUTE_REJECTED_NO_APPROVED_OVERFLOW
```

## 21. Routing Flow

```text
Campaign ready
      |
      v
Resolve tenant/reseller context
      |
      v
Resolve routing policy/profile
      |
      v
Resolve DID/CLI identity
      |
      v
Find policy-authorized gateways
      |
      v
Validate DID/CLI compatibility
      |
      v
Remove disabled/maintenance/unhealthy routes
      |
      v
Check channel capacity
      |
      v
Check CPS capacity
      |
      v
Apply primary/overflow/failover policy
      |
      v
Apply deterministic selection rule
      |
      v
Reserve capacity atomically
      |
      v
Return gateway + DID + decision
      |
      v
FreeSWITCH originate
```

If reservation fails because another worker consumed the capacity, re-evaluate instead of trusting the old snapshot.

## 22. Route Profiles / Policies

The preferred conceptual model is that campaigns reference a routing policy/profile instead of hardcoding a gateway.

Example:

```text
ROUTE_PROFILE_STANDARD_INDIA

Primary:
    Gateway A + DID Pool A
Overflow:
    Gateway B + DID Pool B
Failover:
    Gateway B + DID Pool B
Automatic Overflow: enabled
Automatic Failover: enabled
```

Another example:

```text
ROUTE_PROFILE_RESELLER_ABC

Primary:
    Gateway C + DID Pool C
Overflow:
    none
Failover:
    Gateway A + approved DID Pool
```

Before creating a new `VoiceRouteProfile`, inspect the existing schema and routing abstractions and evolve an equivalent if one already exists.

## 23. Existing Architecture Boundaries

Preserve these responsibilities:

### Campaign

Campaign intent, targets, schedule, execution and retry.

### Voice

Call sessions, call legs, voice eligibility, routing, capacity and lifecycle integration.

### Telephony / FreeSWITCH adapter

Gateway execution, originate, media/control and ESL events.

### Platform/Admin Operations

Routing policy, access restrictions, operational state and emergency overrides.

Relevant existing boundaries include:

- `VoiceRouting`
- `VoiceRoute`
- `SipGatewayRoutingService`
- `GatewayRoutingAdapter`
- `SipGateway`
- `SipGatewayAllocation`
- existing DID/provider compatibility logic
- `OutboundDialer`
- `OutboundDialService`
- `CallSession`
- `CallLeg`
- existing authorization and audit infrastructure

Do not create duplicate routing abstractions.

## 24. No Speculative Infrastructure

The initial architecture does not require:

- Kafka
- Kubernetes
- Microservices
- Redis distributed locks
- Kamailio
- OpenSIPS
- Asterisk
- additional SIP engines

Use PostgreSQL + Spring Boot + FreeSWITCH unless actual requirements or tests demonstrate a need for additional infrastructure.

## 25. Required Scenario Tests

At minimum:

1. Tenant allowed only A; A full; B available → queue.
2. Tenant allowed A+B; A full; overflow enabled → B selected.
3. B has capacity but tenant unauthorized → B rejected.
4. B has capacity but DID incompatible → B rejected.
5. A + DID-A primary; B + DID-B approved failover; A down → B + DID-B selected.
6. Active calls on A remain on A after A becomes unavailable.
7. Enterprise-only B cannot be used by standard tenant.
8. Reseller-specific C cannot be used by another reseller.
9. CPS full while channels remain → no new admission on route.
10. Channels full while CPS remains → no new admission on route.
11. Originate failure releases reservation.
12. Hangup releases reservation.
13. Stale reservation is reconciled.
14. Concurrent admission cannot exceed capacity.
15. Manual override selects B only for its configured scope/time.
16. Expired override returns to normal policy.
17. Every decision exposes selected route, source, reason and rejected alternatives.

## 26. Operational Visibility

The eventual operations UI should expose:

### Gateway

```text
Status
Channels used / max
CPS current / max
Active reservations
Health
Maintenance
```

### Routing

```text
Primary routes
Overflow routes
Failover routes
Policy state
Active overrides
```

### Campaign

```text
Queued
Admitted
In progress
Completed
Failed
Current route distribution
```

### Diagnostics

```text
Why selected?
Why rejected?
Why queued?
Why failed over?
```

## 27. Audit

Routing policy changes and temporary overrides should be auditable using existing audit infrastructure where available:

```text
who changed it
what changed
when
why
scope
effective start/end
```

Do not expose sensitive provider credentials in routing diagnostics or tenant-facing APIs.

## 28. What the Platform Must Never Do

1. Select a gateway solely because it has capacity.
2. Bypass tenant/reseller authorization.
3. Use enterprise-only infrastructure for a standard tenant.
4. Switch DID/CLI without an approved relationship.
5. Move active calls simply because policy changed.
6. Treat all gateways as one common capacity pool.
7. Ignore CPS because channels are available.
8. Ignore channels because CPS is available.
9. Invent failover relationships automatically.
10. Keep temporary overrides indefinitely.
11. Hide the reason for a routing decision.
12. Expose internal SIP/provider credentials to tenants/resellers.
13. Use opaque AI scoring for route selection.

## 29. Implementation Guidance

When implementing this specification:

1. Inspect the actual Phase R code, schema and migrations first.
2. Reuse existing routing, gateway, DID, authorization, call-session and audit abstractions.
3. Introduce a route-profile/policy abstraction only if the current model lacks an equivalent.
4. Keep eligibility, capacity, selection and operator override as separate responsibilities.
5. Return selected gateway and outbound identity together when the route requires it.
6. Keep hard eligibility restrictions active during overrides.
7. Make routing deterministic and explainable.
8. Add scenario-driven tests before live FreeSWITCH integration.
9. Do not assume live SIP/provider infrastructure is available in unit tests.
10. Do not add distributed infrastructure without demonstrated need.

## 30. Final Decision Model

```text
                  CALL READY
                      |
                      v
             ROUTING POLICY
                      |
                      v
             ACCESS / ELIGIBILITY
                      |
          +-----------+-----------+
          |                       |
      DID / CLI              Tenant / Reseller
      compatibility            authorization
          |                       |
          +-----------+-----------+
                      |
                      v
                GATEWAY HEALTH
                      |
                      v
                 CAPACITY
              /             \\
        Channels             CPS
              \\             /
                      |
                      v
             POLICY SELECTION
          /          |          \\
       PRIMARY    OVERFLOW    FAILOVER
          |          |          |
          +----------+----------+
                      |
                      v
              ATOMIC RESERVATION
                      |
                      v
                  ORIGINATE
                      |
                      v
              CALL SESSION / LEG
                      |
                      v
              RELEASE CAPACITY
```

> **Eligibility determines which routes may be used. Capacity determines which eligible routes can accept traffic now. Routing policy determines which eligible available route is selected. Platform Admin controls that policy; automation executes it.**
