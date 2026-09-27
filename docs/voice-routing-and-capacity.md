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

## 31. Implemented Behavior (VB-0)

This section documents the behavior as actually implemented and demonstrated by the VB-0 executable test suite (see `backend/src/test/java/com/shivang/obd/voice/`). It is descriptive, not aspirational.

### 31.1 CPS Enforcement

- CPS is a per-gateway admission-rate limit counted from `voice_channel_reservations` where `reserved_at >= now() - interval '1 second' AND released_at IS NULL`.
- A gateway with `max_cps = NULL` has **no CPS limit**; a gateway with `max_cps = 5` admits at most 5 reservations in any rolling one-second window; the 6th is rejected with `ROUTE_REJECTED_CPS_CAPACITY`.
- Allocation-level CPS (`sip_gateway_allocations.max_cps`) further caps a tenant: with gateway CPS 10 and allocation CPS 2, the 3rd tenant admission inside the window is rejected even though the gateway is not saturated.
- Channels and CPS are distinct checks with distinct rejection codes (`ROUTE_REJECTED_CHANNEL_CAPACITY` vs `ROUTE_REJECTED_CPS_CAPACITY`). A gateway full on channels with CPS free rejects with the channel code; a gateway full on CPS with channels free rejects with the CPS code.
- CPS window counting is part of the reservation SQL; admission and the reservation INSERT happen under a transaction-scoped advisory lock (`pg_try_advisory_xact_lock`) per gateway.

### 31.2 DID Selection — No Silent Fallback

- A route entry may pin a DID (`voice_route_profile_entries.did_id`). When pinned, that DID MUST be provider-compatible with the candidate gateway; incompatibility rejects the route with `ROUTE_REJECTED_DID_INCOMPATIBLE`.
- There is **no fallback to the campaign DID** when a pinned DID is incompatible — the route is rejected outright.
- When no DID is pinned, the campaign DID is used and must itself be provider-compatible with the gateway; incompatibility again rejects with `ROUTE_REJECTED_DID_INCOMPATIBLE`.
- Approved failover may switch identity as a pair: `Gateway A + DID-A → failover → Gateway B + DID-B` (§6). The pinned-DID compatibility check is performed against the entry's own DID, so cross-provider failover with its own DID works; an incompatible pinned DID on the failover gateway rejects the failover route.
- When every candidate was rejected for the same reason, that reason becomes the decision's reason code (e.g. pure DID incompatibility surfaces `ROUTE_REJECTED_DID_INCOMPATIBLE`, not the generic `ROUTE_REJECTED_NO_ELIGIBLE_GATEWAY`). Mixed reasons surface the generic code.

### 31.3 Allocation Capacity

- A tenant can only use a gateway it is authorized for: a platform-owned gateway is shared; a reseller-owned gateway requires an enabled allocation for the tenant or its reseller (`ROUTE_REJECTED_TENANT_NOT_AUTHORIZED` otherwise).
- The tenant-level channel limit is `min(gateway effective channels, allocation effective channels)`.
- An allocation with `max_concurrent_channels = NULL` imposes **no tenant-level limit** (gateway limit still applies).
- Campaign preference never bypasses authorization: a top-priority entry on an unauthorized gateway is rejected with `ROUTE_REJECTED_TENANT_NOT_AUTHORIZED` and the next authorized candidate is evaluated (§5).

### 31.4 Gateway and Allocation Headroom

- Gateway headroom (`sip_gateways.capacity_headroom_pct`, V31): effective channels = `floor(max_concurrent_channels × (100 − headroom) / 100)`. Example: 100 channels with 10% headroom → effective 90; the 91st admission is rejected.
- Allocation headroom (`sip_gateway_allocations.capacity_headroom_pct`, V33): applies the same formula to the tenant limit. Example: 50-channel allocation with 20% headroom → effective 40.
- Effective tenant capacity is the minimum of the two effective values (§13). Values outside `[0, 100)` are rejected by CHECK constraints at the database level.

### 31.5 Reservation Reconciliation

- Reservations live in `voice_channel_reservations`; an active row has `released_at IS NULL`.
- Release is the idempotent UPDATE `SET released_at = now() WHERE ... AND released_at IS NULL` — duplicate hangup events and double releases touch only active rows and never produce negative usage.
- A scheduled reconciliation job releases rows where `released_at IS NULL AND reserved_at < now() − 5 minutes`, covering process crashes and lost ESL events (§14).
- Unknown gateway/tenant combinations are safe no-ops for release.

### 31.6 Deterministic Tie-Break

- Route entries are evaluated in ascending `priority` (lower value = higher precedence).
- Entries with equal priority are evaluated in the profile's stable entry order; repeated evaluation of the same logical input selects the same gateway every time (verified by repeated-execution test R3).
- The first eligible, authorized, healthy, capacity-available entry wins; later entries are recorded as rejected alternatives with machine-readable reasons (§19).
- A selected decision still reports the alternatives evaluated and rejected before selection — including capacity-exhausted primaries when overflow succeeds.

### 31.7 Temporary vs Permanent Failure

- Routing/capacity rejections for capacity, CPS, or headroom reasons requeue the campaign attempt without consuming a retry (§17). Provider unavailability and dialer failures requeue and release any reservation taken.
- Permanent eligibility failures (DNC/blocklist, campaign not found) fail the attempt permanently with the failure code recorded.

### 31.8 Test Suite Map

| Area | Tests |
|---|---|
| Routing R1–R9 | `VoiceRoutingServiceTest` |
| DID D1–D5 | `VoiceRoutingDIDTest` |
| Explainability (§19) | `VoiceExplainabilityTest` |
| Policy hierarchy P1–P4 | `VoicePolicyHierarchyTest` |
| Capacity C1–C8 | `VoiceCapacityServiceTest` |
| Reservation lifecycle RES1–RES6 | `VoiceReservationLifecycleTest` (unit), `VoiceReservationLifecycleIntegrationTest` (PostgreSQL) |
| Reconciliation REC1–REC3 | `VoiceReconciliationTest` (unit), `VoiceReservationLifecycleIntegrationTest` (PostgreSQL) |
| Tenant isolation | `VoiceTenantIsolationIntegrationTest` (PostgreSQL) |
| Concurrency (advisory lock, 20 workers vs 10 channels) | `VoiceCapacityConcurrencyIntegrationTest` (Testcontainers PostgreSQL) |
| Migrations V29–V33 schema | `VoiceSchemaMigrationIntegrationTest` (real Flyway chain) |
| Temporary retry / permanent failure | `OutboundDialServiceRoutingTest` |
| ESL hangup release/idempotency | `EslEventServiceTest` |

---

## 32. Implemented Behavior (VB-1) — PLAYFILE Voice Blast Execution

VB-1 adds PLAYFILE execution on top of the unchanged VB-0 foundation. Every
call still flows Campaign → OutboundDialService → VoiceEligibilityService →
VoiceRoutingService → VoiceCapacityService → reservation → FreeSWITCH; no
second routing path, reservation mechanism, or capacity calculation exists.

### 32.1 PLAYFILE Call Lifecycle

```
QUEUED attempt
  → eligibility (VB-0, unchanged)
  → routing (VB-0, unchanged)
  → capacity reservation (VB-0, unchanged)
  → originate (EslClient.bgapi originate; returns channel UUID stored as
    provider_call_id on both CallAttempt and CallSession)
  → CHANNEL_PROGRESS / CHANNEL_PROGRESS_MEDIA  → RINGING (idempotent)
  → CHANNEL_ANSWER                             → ANSWERED (idempotent)
  → playback requested by PlayfileExecutionService (PlaybackTrigger)
  → PLAYBACK_START   → PLAYING                 (idempotent)
  → PLAYBACK_STOP    → PLAYBACK_COMPLETED      → hangup requested
  → CHANNEL_HANGUP   → terminal attempt state + reservation released
```

- `CallSessionStatus` gained `PLAYING` and `PLAYBACK_COMPLETED` (migration
  V34). `PLAYBACK_COMPLETED` is distinct from `ANSWERED` so a stray
  `PLAYBACK_START` can never restart finished playback.
- RINGING: `CHANNEL_PROGRESS`/`CHANNEL_PROGRESS_MEDIA` map to RINGING on
  CallSession/CallLeg. Progress events never regress ANSWERED, PLAYING,
  PLAYBACK_COMPLETED, or terminal states. `EslClient` now subscribes to these
  events (VB-0 subscribed only CHANNEL_ANSWER/CHANNEL_HANGUP).
- Duplicate events of every type (ANSWER, RINGING, PLAYBACK_START,
  PLAYBACK_STOP, PLAYBACK_ERROR, HANGUP) are no-ops: state transitions are
  guarded by the current entity status, and hangup handling keeps the VB-0
  terminal-state guard.

### 32.2 Media Boundary

- `VoiceMediaController` (voice/media) is the only media surface campaign and
  event code may use; raw ESL commands never leave the telephony package.
- `FreeSwitchVoiceMediaController` (telephony, active when
  `telephony.freeswitch.enabled=true`) translates business operations:
  - `playAudio` → `uuid_broadcast <uuid> <path> aleg`
  - `terminateCall` → `uuid_kill <uuid> NORMAL_CLEARING`
  - `stopPlayback` → explicit no-op in VB-1 (a remote hangup or the
    post-completion hangup terminates playback natively)
  - DTMF/bridge/recording → `UnsupportedOperationException` (later phases)
- The FreeSWITCH channel UUID is `CallSession.providerCallId` (the
  originate-returned UUID). No duplicate identifiers were introduced.

### 32.3 Playback Trigger and Audio Validation

On the real DIALING/RINGING → ANSWERED transition, `EslEventService` fires the
`PlaybackTrigger` (`PlayfileExecutionService`, campaign package), which:

1. No-ops unless the session is still ANSWERED and the attempt's campaign
   resolves tenant-scoped and is `campaign_type = PLAYFILE` — non-PLAYFILE
   campaigns never invoke media playback.
2. Requires `content_mode = AUDIO` and a non-null `audio_asset_id`;
   otherwise records `PLAYBACK_CONFIG_INVALID` (permanent) and tears the
   call down via `terminateCall`.
3. Resolves the asset through
   `AudioAssetRepository.findByIdAndTenantIdAndDeletedAtIsNull(assetId,
   session.tenantId)` — a tenant can never play another tenant's asset. The
   asset must be APPROVED with a non-blank `storage_reference`; otherwise
   `PLAYBACK_CONFIG_INVALID` again.
4. Calls `mediaController.playAudio(...)`. A command-level failure (ESL
   error, channel gone) records `PLAYBACK_FAILED` (temporary).

Trigger failures are caught by the event service and never break event
processing; the PLAYBACK_ERROR/CHANNEL_HANGUP paths still finalize the call.

### 32.4 Playback Completion — Events, Not Command Acceptance

Command acceptance (`uuid_broadcast` OK) is NOT playback completion.
Completion is detected from FreeSWITCH events:

- `PLAYBACK_START` → ANSWERED → PLAYING (only from ANSWERED).
- `PLAYBACK_STOP` (carries `Other-Leg-Unique-ID`/channel UUID, correlated via
  `CallSession.providerCallId`) → PLAYING → PLAYBACK_COMPLETED, then the
  media boundary is asked to hang the channel up. The subsequent
  CHANNEL_HANGUP remains the single authoritative reservation release path.
- `PLAYBACK_ERROR` → records `PLAYBACK_FAILED` + reason on the session,
  requests teardown, and the attempt finalizes as FAILED with
  `PLAYBACK_FAILED` regardless of the eventual hangup cause.

### 32.5 Reservation Lifetime

- Reserved at capacity admission (VB-0); released exactly once by the
  CHANNEL_HANGUP handler (VB-0 idempotent `released_at IS NULL` UPDATE).
- Playback completion and playback failure request hangup through the media
  boundary but never release the reservation themselves — one release path.
- If the hangup command fails, the channel's own hangup event still
  finalizes state; the VB-0 stale-reservation reconciliation (5 min) covers
  lost events.

### 32.6 Failure Classification and Retry Semantics

| Code | Meaning | Retryable |
|---|---|---|
| `PLAYBACK_FAILED` | Transient FreeSWITCH media/resource failure during playback | Yes — existing retry policy |
| `PLAYBACK_CONFIG_INVALID` | Campaign/asset misconfiguration (missing audio, TTS mode, unapproved/cross-tenant/blank asset) | No — permanent |

- The CHANNEL_HANGUP finalizer preserves a session-recorded
  `PLAYBACK_FAILED`/`PLAYBACK_CONFIG_INVALID` classification even when the
  hangup cause itself is NORMAL_CLEARING — a teardown after absent/failed
  playback can never complete the attempt as success.
- `CampaignExecutionOrchestrator.processRetries` skips permanently-failed
  attempts (`PLAYBACK_CONFIG_INVALID`, `CAMPAIGN_NOT_FOUND`, `DIAL_FAILED`,
  `REJECTED`); temporary codes (incl. `PLAYBACK_FAILED`, `BUSY`,
  `NO_ANSWER`, `TEMPORARY_FAILURE`) continue through the unchanged retry
  policy (max attempts, interval, schedule window).
- Capacity/CPS rejections keep the VB-0 requeue-without-consuming-retry
  behavior; completed calls are never retried.

### 32.7 Tenant Isolation

- The playback trigger resolves the campaign with
  `findByIdAndTenantIdAndDeletedAtIsNull(attempt.campaignId,
  attempt.tenantId)` and the asset with the session's tenant — cross-tenant
  campaign/asset references fail closed as `PLAYBACK_CONFIG_INVALID` with no
  playback attempted.
- Event correlation (`findByProviderCallIdAndDeletedAtIsNull`) and all
  lifecycle writes remain inside the existing tenant-scoped repositories;
  reservation release is keyed by the session's gateway + attempt's tenant.

### 32.8 Observability

Structured logs (existing SLF4J pattern, identifiers only — no credentials/PII)
cover: call originated (dialer), ringing, answered, playback trigger fired,
playback requested (`uuid_broadcast`), playback started, playback completed,
playback failed (with reason), teardown/hangup requested, attempt finalized,
reservation released.

### 32.9 VB-1 Test Suite Map

| Area | Tests |
|---|---|
| ESL lifecycle P4–P8, P13–P20, P26–P29, state-machine safety | `PlayfileLifecycleEslTest` |
| Media adapter ESL contract (P9, P10) | `FreeSwitchVoiceMediaControllerTest` |
| Trigger/validation P1–P3, P24 | `PlayfileExecutionServiceTest` |
| Retry semantics P21–P23 | `PlayfileRetrySemanticsTest` |
| Full PostgreSQL lifecycle (attempt → session → leg → reservation → terminal → released) | `PlayfileLifecycleIntegrationTest` (real Flyway chain, real services, mocked ESL boundary) |

---

## 33. VB-2 — DTMF Interaction

VB-2 extends the voice execution lifecycle with DTMF collection after PLAYFILE
content playback, reusing the entire VB-0/VB-1 foundation (routing, capacity,
reservation, ESL boundary, media controller, retry model). No new telephony
engine, scheduler framework, or transport was introduced.

### 33.1 Lifecycle

```
ANSWERED → PLAYING → PLAYBACK_COMPLETED → WAITING_FOR_DTMF
        → (digits collected, persisted per interaction)
        → result: VALID | INVALID | TIMEOUT | (ABANDONED on remote hangup)
        → hangup request (VALID/INVALID/TIMEOUT)
        → CHANNEL_HANGUP (authoritative finalize + reservation release)
        → attempt finalized (COMPLETED; result audited on the interaction)
```

- `CallSessionStatus` gains one state: `WAITING_FOR_DTMF` (V35). The granular
  result lives on `dtmf_interactions.result` — single source of truth, no
  duplicate state concepts.
- A `DTMF` campaign plays its AUDIO content exactly like a `PLAYFILE` one
  (same `VoiceMediaController.playAudio` path, same tenant-scoped asset
  validation). On `PLAYBACK_STOP`, the event service dispatches
  `onPlaybackCompleted` to all registered `PlaybackTrigger` beans; each
  trigger self-guards by campaign type (PLAYFILE → teardown; DTMF → begin
  collection), so exactly one acts per call.

### 33.2 DTMF Configuration

Configuration lives in the existing `campaigns.type_config` JSONB under the
`"dtmf"` key — the surface V14/readiness already require for DTMF campaigns:

```json
{ "dtmf": { "expected": "123", "maxDigits": 8, "terminator": "#", "timeoutSecs": 10 } }
```

| Field | Required | Rules |
|---|---|---|
| `expected` | yes | digit sequence 0-9 (plus `*`/`#` when intentionally configured), ≤ 16 chars |
| `maxDigits` | no | ≥ expected length, ≤ 16; default = expected length |
| `terminator` | no | single key 0-9/`*`/`#`; ends collection and forces exact match |
| `timeoutSecs` | no | 1–120; default 10 |

Parsing (`DtmfConfig.fromTypeConfig`) is strict and total: missing/malformed/
out-of-bounds values throw and fail the interaction as permanent
`DTMF_CONFIG_INVALID`. Input is never silently normalized.

### 33.3 Event Source and Collection

- `EslClient` subscribes to `CHANNEL_DTMF`; the digit is read from the
  FreeSWITCH `DTMF-Digit` header and correlated by `Call-UUID` like every
  other event. Unknown channels and malformed events are safely ignored
  (`processEvent` reports them as ignored).
- The authoritative input is the real DTMF event — command acceptance is
  never treated as input. Digits arriving before collection starts (i.e.
  during playback, no interaction row) are ignored.
- `DtmfCollector` is a pure classifier: exact match → VALID; wrong digit or
  over-input beyond `maxDigits` → terminal INVALID; terminator forces exact
  match; a rejected digit never silently extends the window.

### 33.4 Timeout Semantics

- The deadline is persisted per interaction (`expires_at`, set once at
  creation from the config snapshot) — deterministic, correlated, and immune
  to stale timeouts from earlier interactions.
- Enforcement is a `@Scheduled(fixedDelay = 1s)` poller
  (`DtmfTimeoutScheduler`) following the project's existing poller convention
  (ESL reconnect, reservation reconciliation). It selects expired
  COLLECTING rows natively (`result = 'COLLECTING' AND expires_at < now()`)
  and delegates to the result service. No new scheduler framework.

### 33.5 Idempotency and Atomic Results

- Terminalization goes through `DtmfResultService.finalizeInteraction`, an
  atomic conditional UPDATE (`WHERE result = 'COLLECTING'`) that also records
  the final collected digits: exactly one of {digit event, timeout poller,
  hangup abandonment} wins the claim; losers observe zero rows and no-op.
- Duplicate digits after a terminal result, duplicate timeout scans, and
  duplicate hangups therefore cannot double-act, double-hangup, or mutate the
  result. A remote hangup during collection marks the interaction ABANDONED.
- Native SQL is used where PostgreSQL enum semantics matter (timeout scan,
  claim): Hibernate binds NAMED_ENUM *parameters* in JPQL as the
  class-derived type name (`dtmfresulttype`) and in native queries as
  ordinals — both fail against a real `dtmf_result_type` column. The entity
  mapping itself (`NAMED_ENUM` on the field) works and is proven by the
  integration tests.

### 33.6 Reservation Behavior

DTMF results never release capacity. Only VALID/INVALID/TIMEOUT results
request a hangup through the media boundary (`uuid_kill`); the resulting
`CHANNEL_HANGUP` remains the single authoritative finalize + reservation
release path (VB-0). Stale-reservation reconciliation continues to protect
against orphaned reservations.

### 33.7 Retry Classification

| Code | Meaning | Retryable |
|---|---|---|
| `DTMF_CONFIG_INVALID` | Invalid/missing `type_config.dtmf`, invalid audio config for DTMF campaigns | No — permanent |
| `DTMF_PLAYBACK_FAILED` | Transient media failure during DTMF-campaign playback | Yes — existing retry policy |
| (user input) | INVALID / TIMEOUT results | Not a failure: the call completes normally (attempt COMPLETED); the outcome is audited on the interaction and is never retried |

- The CHANNEL_HANGUP finalizer now preserves *any* failure code recorded on
  the session (generalizing the VB-1 playback rule): a NORMAL_CLEARING
  teardown after a DTMF configuration error fails the attempt with
  `DTMF_CONFIG_INVALID` instead of completing it as success.
- `CampaignExecutionOrchestrator.isPermanentFailure` additionally skips
  `DTMF_CONFIG_INVALID`.

### 33.8 Action Boundary and Tenant Isolation

- VB-2 implements no downstream action (`CONNECT_BY_AGENT` is explicitly out
  of scope). The MVP action is: persist the result, classify it, hang up,
  finalize. The `DtmfCollectorTrigger` boundary (voice.media) is the seam a
  future action layer plugs into.
- The campaign is resolved tenant-scoped
  (`findByIdAndTenantIdAndDeletedAtIsNull(attempt.campaignId,
  attempt.tenantId)`), the audio asset with the call's tenant, and
  `dtmf_interactions` is tenant-keyed with repository lookups scoped by
  tenant; cross-tenant references fail closed (unresolved → config-invalid).
- The interaction row snapshots the configuration actually used
  (`expected_input`, `max_digits`, `terminator`, `timeout_secs`), so later
  campaign edits never rewrite a live call's rules.

### 33.9 Known Limitations

- Digit collection is event-driven only; no server-side IVR prompt loop
  (multi-prompt trees), no barge-in, no re-prompt on invalid input.
- `VoiceMediaController.collectDtmf` (FreeSWITCH-native digit collection with
  terminator) remains unsupported — VB-2 intentionally uses passive
  CHANNEL_DTMF events, which give deterministic server-side validation.
- No live FreeSWITCH E2E was executed for VB-2; the FreeSWITCH contract is
  covered at the ESL/media seam (`EslEvent` header parsing,
  `CHANNEL_DTMF` subscription) and the lifecycle is proven against real
  PostgreSQL with the ESL boundary mocked. A real FreeSWITCH container
  harness was not introduced solely for this phase.
- `dtmf_interactions` rows are not auto-deleted; retention follows the
  platform's general data policy (no dedicated TTL in VB-2).

### 33.10 VB-2 Test Suite Map

| Area | Tests |
|---|---|
| Config parsing/validation | `DtmfConfigTest` |
| Input rules (single digit, sequence, terminator, cap) | `DtmfCollectorTest` |
| ESL correlation (valid/unknown/malformed/duplicate) | `DtmfEslEventServiceTest` |
| Execution (interaction creation, digits, timeout, isolation, idempotency) | `DtmfExecutionServiceTest` |
| Full PostgreSQL lifecycle + V35 schema/enum | `DtmfLifecycleIntegrationTest`, `VoiceSchemaMigrationIntegrationTest` (real Flyway chain, real repositories, mocked ESL boundary) |

---

## 34. VB-3 — CONNECT_BY_AGENT

VB-3 adds the first contact-center primitive on top of the verified VB-0/VB-1/VB-2
foundation: after a DTMF interaction completes, the caller can be bridged to a
human agent over a second telephony leg.

### 34.1 Purpose and boundary

`CONNECT_BY_AGENT` is an **action consumer of the VB-2 DTMF boundary**. The DTMF
layer requests the action; the agent-connection service owns everything after
that. The VB-0 flow is never bypassed:

```
Campaign → OutboundDialService → VoiceEligibilityService → VoiceRoutingService
        → VoiceCapacityService → Reservation → FreeSWITCH (caller leg)
        → PLAYFILE → DTMF → CONNECT_BY_AGENT
        → agent eligibility → atomic agent reservation
        → agent leg originate → RINGING → ANSWERED
        → uuid_bridge(caller, agent) → BRIDGED (active conversation)
        → caller/agent hangup → cleanup
```

Not implemented in VB-3 (explicitly out of scope): predictive/progressive/preview
dialing, skill-based routing, queues, WebRTC/Android SIP agents, recording,
supervisor features, AI/STT/TTS.

### 34.2 Agent domain (V36 migration)

V36 (forward-only; V1–V35 untouched) adds:

- **`agents`** — `id`, `tenant_id` (FK → `tenants`, ON DELETE CASCADE),
  `display_name`, `admin_status agent_admin_status` (`ACTIVE`/`SUSPENDED`/`DISABLED`),
  `availability agent_availability` (`AVAILABLE`/`BUSY`/`OFFLINE`),
  `max_concurrent_calls` (default 1, CHECK ≥ 1), soft-delete columns
  (`deleted_at`, UNIQUE), timestamps.
- **`agent_endpoints`** — one or more dial destinations per agent:
  `endpoint_type endpoint_endpoint_type` (`SIP` only in VB-3), `dial_target`,
  `enabled`, `tenant_id` FK. Administrative status and runtime availability are
  deliberately separate concepts ("allowed to receive calls" vs "free right now").
- **`agent_reservations`** — the concurrency hold: `agent_id`, `tenant_id`,
  `call_session_id`, `attempt_id` (nullable), `call_leg_id` (nullable, set when
  the agent leg row exists), `status agent_reservation_status`
  (`RESERVED`/`ACTIVE`/`RELEASED`), `reserved_at`, `activated_at`, `released_at`,
  `release_reason`. CHECK `released_at` present iff `RELEASED`.
- `call_session_status += CONNECTING_AGENT, BRIDGED`; `call_leg_status += BRIDGED`;
  `dtmf_interactions += action_type, action_payload` columns.

### 34.3 Agent eligibility and selection

`AgentEligibility` evaluates, in order (fail-fast, explainable via `AgentReasons`):
tenant ownership → administrative `ACTIVE` → runtime `AVAILABLE`/`BUSY`
consideration → live concurrency (`max_concurrent_calls` minus non-RELEASED
reservations) → enabled endpoint with a non-blank `dial_target`.

**Selection rule (deterministic, documented):** candidates are ordered by
`active_hold_count ASC` (least-loaded first), then `id ASC` as a stable
tie-break — same state always yields the same agent. There is no randomization
and no scoring. `ConnectByAgentService.selectEligible` returns an explainable
`AgentEligibility` (selected agent + rejected candidates with reasons).

### 34.4 Atomic reservation (concurrency)

`AgentReservationService.reserve` runs in a single transaction guarded by a
**transaction-scoped PostgreSQL advisory lock** on the agent
(`pg_try_advisory_xact_lock(0x300000000L + agentId.hashCode())` — base disjoint
from VB-0's channel `0x1…` and CPS `0x2…` bases). Inside the lock it re-checks
`countActiveByAgentId < maxConcurrentCalls`, then inserts the RESERVED row.
Contended lock or exhausted capacity → refused (caller may try the next
candidate). No Redis, no second lock framework — same pattern proven in VB-0.

Release (`releaseForCallSession`) is an idempotent conditional UPDATE
(`RESERVED`/`ACTIVE` → `RELEASED` + timestamp + reason); double release and
release of unknown sessions are safe no-ops returning `false`. `markActive`
promotes RESERVED → ACTIVE when the agent answers.

### 34.5 Two-leg call model

No second call entity. The existing `CallSession` (business conversation) +
`CallLeg` (telephony leg) model is used with the **pre-existing** `CallLegType`
(`CUSTOMER`/`AGENT`) and `CallLeg.agentId` columns from V29:

- Caller leg: created by VB-0 originate, stays untouched.
- Agent leg: created by `ConnectByAgentService` with `type=AGENT`,
  `callSessionId`, `agentId`, own `providerCallId` (real FreeSWITCH UUID from
  the originate response), lifecycle `ORIGINATING → RINGING → ANSWERED →
  BRIDGED → HANGUP/FAILED`.

Agent-leg granularity lives on the leg; the session gains only
`CONNECTING_AGENT` and `BRIDGED`.

### 34.6 Agent-leg originate and bridge

- `AgentLegDialer` (voice boundary) with `FreeSwitchAgentLegDialer`
  (reuses the existing `EslClient.originate` — **no second originate
  mechanism**, agent leg gets its own provider UUID) and `NoOpAgentLegDialer`
  (safe-fail: returns empty when FreeSWITCH is disabled — never fakes a
  connected agent).
- `VoiceMediaController.bridge(callerUuid, agentUuid)` →
  `EslClient: uuid_bridge <caller> <agent>`; the NoOp controller refuses with
  `UnsupportedOperationException` (same semantics as its playback boundary).
- ESL: `CHANNEL_BRIDGE` is subscribed and parsed (`Bridge-B-Unique-Id` →
  `EslEvent.getBridgeBUuid()`); `EslEventService.handleAgentLegEvent` routes
  agent-UUID events by `CallLeg.providerCallId` **before** attempt correlation
  (an agent UUID never matches an attempt), handles
  PROGRESS/ANSWER/HANGUP/BRIDGE and is idempotent (duplicate events are
  conditional transitions).

### 34.7 DTMF → CONNECT_BY_AGENT integration

`DtmfConfig` gained an optional `action` (`{type: CONNECT_BY_AGENT, payload?…}`)
validated strictly; the executed action is snapshotted onto the
`dtmf_interactions` row (`action_type`). On a **valid, terminal** DTMF result the
existing `PlaybackTrigger`/trigger-list dispatch fires `AgentConnectTrigger`
(implemented by `ConnectByAgentService`), which:

1. re-verifies session state + tenant (idempotency guard: a second trigger for
   an already-connected/attempted session is a no-op);
2. selects + atomically reserves an agent (explainable rejection otherwise);
3. creates the agent leg, originates it via `AgentLegDialer`;
4. on originate failure releases the reservation immediately (no orphans);
5. hands event handling to `EslEventService` (RINGING/ANSWER/BRIDGE/HANGUP).

`DtmfExecutionService` never sees agent logic; `AgentReservationService` never
sees FreeSWITCH.

### 34.8 Hangup, cleanup order, resource lifecycle

- **Caller hangup** (any stage): agent leg terminated if active, agent
  reservation released, session finalized; the gateway reservation is released
  authoritatively by the **existing VB-0 CHANNEL_HANGUP flow** (unchanged).
- **Agent hangup** (during ringing/after answer/in conversation): agent leg
  finalized, agent reservation released, caller leg terminated, session
  finalized. Documented product behavior: either side hanging up ends the
  conversation for both.
- **Cleanup order:** mark connection terminal → terminate agent leg → release
  agent reservation → finalize session (state transition happens *before*
  resource release, protecting against duplicate cleanup). Gateway reservation
  always follows the authoritative CHANNEL_HANGUP path. Voice capacity and
  agent capacity remain separate: a call holds one gateway channel **and** one
  agent concurrency slot, released through their independent lifecycles.

### 34.9 Failure classification and retry

| Code | Class | Meaning |
|---|---|---|
| `AGENT_CONFIG_INVALID` | permanent | No eligible agent / invalid config |
| `AGENT_ORIGINATE_FAILED` | temporary | Provider/FS originate error |
| `AGENT_BRIDGE_FAILED` | temporary | Bridge command/error after answer |
| `AGENT_NO_ANSWER` | call-state | Agent leg rang out (timeout scheduler) |
| `AGENT_BUSY` | capacity | Reservation refused (at concurrency) |

Fallback policy: **one deterministic agent attempt** (no queues, no unlimited
retry) — on failure the reservation is released, the failure recorded through
the existing attempt/retry semantics, and the call finalizes cleanly. The
`AgentConnectTimeoutScheduler` (@Scheduled, existing convention)
 (@Scheduled, existing convention) rings out
agent legs whose RINGING state exceeded the configured no-answer window and
releases their holds; `AgentStaleReservationReconciler` reuses the VB-0 stale
pattern for orphaned RESERVED/ACTIVE holds (conservative 10-minute threshold —
shorter than a normal agent call, longer than any originate/bridge operation,
so an active call can never be falsely reclaimed).

### 34.10 Tenant isolation

Every agent/endpoint/reservation lookup is tenant-scoped
(`findByIdAndTenantIdAndDeletedAtIsNull`, tenant FK on all three tables).
Cross-tenant reservation and selection attempts fail closed — proven by
integration tests against real PostgreSQL.

### 34.11 Known limitations & verification status

- Deterministic single-agent attempt only; queueing/multi-agent fallback
  strategies belong to later phases.
- SIP endpoints only; endpoint credentials are not part of the VB-3 model.
- ESL/media seam is contract-tested (`AgentLegDialerContractTest`,
  `AgentEslRoutingTest`) and the lifecycle is proven against real PostgreSQL
  (Testcontainers, full Flyway chain V1..V36) with the ESL boundary mocked —
  **live FreeSWITCH E2E was not executed** (no FreeSWITCH instance in this
  environment); it remains an operational verification step.

### 34.12 VB-3 Test Suite Map

| Area | Tests |
|---|---|
| Eligibility + deterministic selection + explainability | `AgentSelectionTest` |
| Reservation, connection lifecycle, idempotency, hangups | `ConnectByAgentServiceTest` |
| Agent-leg ESL routing (PROGRESS/ANSWER/HANGUP/BRIDGE) | `AgentEslRoutingTest` |
| ESL contract (originate/bridge commands, NoOp safety) | `AgentLegDialerContractTest` |
| DTMF action config + dispatch + duplicate-trigger safety | `DtmfAgentActionTest` |
| Lifecycle + enum round-trip + isolation + 20-thread concurrency + V36 schema (real PostgreSQL) | `AgentConnectIntegrationTest` |

## 35. VB-4A — Agent Foundation (Directory, Presence, Endpoints, Call Queries)

VB-4A builds the tenant-scoped Agent foundation that later contact-center
phases (VB-4B Queue, VB-4C ACD, VB-4D Inbound, VB-4E Outbound Agent Calling)
consume. It adds **no new runtime execution path**: VB-3 CONNECT_BY_AGENT
behavior is untouched, and VB-4A only exposes management and read boundaries
over the existing domain.

### 35.1 Scope Boundary

**Implemented:** agent lifecycle (create/get/list/update/status), runtime
presence, derived availability, endpoint management, agent APIs, active-call
queries, call-history queries, tenant isolation, tests, documentation.

**Explicitly NOT implemented (deferred):** queues and queue membership
(VB-4B), ACD selection (VB-4C), inbound DID→queue/agent routing (VB-4D),
agent-originated outbound calling (VB-4E), WebRTC/Android SIP endpoints,
presence derived from FreeSWITCH registration events, call recording,
supervisor features.

### 35.2 Domain Model (reuse, no duplication)

- `Agent` (V36) is the single tenant-scoped agent object. `Agent.userId`
  remains an optional forward boundary to the existing `users`/`AGENT` role
  model; it is **not exposed via API in VB-4A** (agent-facing authentication
  is a later-phase concern).
- Administrative status: `AgentAdminStatus` = `ACTIVE | SUSPENDED | DISABLED`.
  `DISABLED` is terminal. Kept strictly separate from presence (per VB-3).
- Runtime presence: `AgentAvailability` = `AVAILABLE | BUSY | OFFLINE`.
  `BUSY` is **owned by the reservation lifecycle** (VB-3 flips it on
  reserve/release); the presence API accepts only `AVAILABLE | OFFLINE`
  from callers.
- Endpoints: `AgentEndpointEntity` (V36) reused. VB-4A management accepts
  the dialable types the telephony layer supports today — `SIP` and
  `EXTERNAL_FORWARD`. `WEBRTC | MOBILE_APP | AI` remain modeled but are
  rejected at creation time (they are not dialable yet).

No new migration was required: V36 already carries all VB-4A tables, enums,
constraints, and indexes (agents, agent_endpoints, agent_reservations,
call_legs.agent_id, CallSessionStatus.BRIDGED/CONNECTING_AGENT, etc.).

### 35.3 Lifecycle and Presence Transitions

Administrative status transitions (`PUT /api/v1/agents/{id}/status`):

```
ACTIVE  -> SUSPENDED, DISABLED
SUSPENDED -> ACTIVE, DISABLED
DISABLED -> (terminal, no transitions)
```

Invalid transitions fail with `AGENT_INVALID_STATUS` (HTTP 409 via
`ConflictException`). Same-state updates are idempotent no-ops.

Presence transitions (`PUT /api/v1/agents/{id}/presence`):

```
OFFLINE -> AVAILABLE
AVAILABLE -> OFFLINE
same state -> idempotent no-op
```

`BUSY` is **not accepted** from callers — it is derived state owned by the
agent reservation lifecycle (VB-3 flips availability on
reserve/release/reconcile). Creating an agent starts at `OFFLINE`.

### 35.4 Availability Semantics

`GET /api/v1/agents/{id}/availability` answers
"can this agent receive a contact-center call right now?" **deterministically
and explainably** — evaluated top-down, first failure wins:

1. `DISABLED` -> false, reason `AGENT_DISABLED`
2. `SUSPENDED` -> false, reason `AGENT_SUSPENDED`
3. presence `OFFLINE` -> false, reason `AGENT_OFFLINE`
4. presence `BUSY` -> false, reason `AGENT_BUSY` (reservation lifecycle owns)
5. no enabled dialable endpoint -> false, reason `AGENT_ENDPOINT_INVALID`
6. active agent-leg count >= maxConcurrentCalls -> false, reason
   `AGENT_AT_CAPACITY`
7. otherwise -> true, reason `AGENT_AVAILABLE`

This is a foundation query only: it makes **no queue/ACD assignment decision**
(VB-4B/4C will consume it). Active-call counting derives from the canonical
`CallLeg` table — there is no second call-count source of truth.

### 35.5 Endpoint Management

Operations under `/api/v1/agents`:

- `POST /{id}/endpoints` — create (`SIP` | `EXTERNAL_FORWARD`)
- `GET /{id}/endpoints` — list for agent
- `GET /endpoints/{endpointId}` — fetch one (tenant-scoped)
- `PUT /endpoints/{endpointId}` — update target/enablement
- `POST /endpoints/{endpointId}/enable` / `disable` — idempotent toggles
- `DELETE /endpoints/{endpointId}` — soft delete (`deleted_at` populated)

Validation: dialable type required, non-blank target, `AGENT_ALREADY_EXISTS`
on duplicate (agent, type, target), tenant fail-closed lookups via
`findBy*AndTenantId` with 404 on cross-tenant access. All endpoints of a
soft-deleted or disabled agent are unreachable through the same scoping.
### 35.6 Active Calls and Call History

`GET /api/v1/agents/{id}/calls/active` — agent legs in
`ACTIVE_LEG_STATUSES` (ORIGINATING, RINGING, ANSWERED, BRIDGED) joined to
their tenant-scoped `CallSession`. Terminated/failed legs never appear.
Empty list = no active calls.

`GET /api/v1/agents/{id}/calls` — paginated history over agent `CallLeg`
rows (Spring `Pageable`, sortable), filtered with
`HISTORY_LEG_STATUSES` (HANGUP, FAILED, NO_ANSWER). Each item exposes
session id, leg id, direction, remote number, start/end timestamps, final
leg status — fields that already exist on the canonical voice model. No
history table was created.

Tenant isolation: both queries are tenant-scoped end to end
(`CallLegRepository.findByAgentIdAndTenantId*`); an agent id belonging to
another tenant resolves to 404, never to another tenant's data.

### 35.7 APIs Added

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| POST | /api/v1/agents | create agent | AGENT_MANAGE |
| GET | /api/v1/agents/{id} | get agent | AGENT_VIEW |
| GET | /api/v1/agents | list/filter agents (status, availability, paging) | AGENT_VIEW |
| PUT | /api/v1/agents/{id} | update display name / max concurrent calls | AGENT_MANAGE |
| PUT | /api/v1/agents/{id}/status | administrative lifecycle transition | AGENT_MANAGE |
| PUT | /api/v1/agents/{id}/presence | presence update (AVAILABLE/OFFLINE) | AGENT_MANAGE |
| GET | /api/v1/agents/{id}/availability | deterministic availability + reason | AGENT_VIEW |
| POST | /api/v1/agents/{id}/endpoints | create endpoint | AGENT_MANAGE |
| GET | /api/v1/agents/{id}/endpoints | list endpoints | AGENT_VIEW |
| GET | /api/v1/agents/endpoints/{endpointId} | get endpoint | AGENT_VIEW |
| PUT | /api/v1/agents/endpoints/{endpointId} | update endpoint | AGENT_MANAGE |
| POST | /api/v1/agents/endpoints/{endpointId}/enable | enable endpoint | AGENT_MANAGE |
| POST | /api/v1/agents/endpoints/{endpointId}/disable | disable endpoint | AGENT_MANAGE |
| DELETE | /api/v1/agents/endpoints/{endpointId} | soft-delete endpoint | AGENT_MANAGE |
| GET | /api/v1/agents/{id}/calls/active | active agent legs | AGENT_VIEW |
| GET | /api/v1/agents/{id}/calls | paginated agent call history | AGENT_VIEW |

Conventions follow the existing platform: `ApiResponse`/`ResponseFactory`
envelope, `PaginationMetadata`, DTO records with validation, Swagger
annotations, server-derived `OrganizationContextHolder` tenant scope,
`ResourceNotFoundException` (404) / `ConflictException` (409). Capabilities
`AGENT_VIEW`/`AGENT_MANAGE` were already seeded in V1 — no RBAC changes.

### 35.8 VB-4A Test Suite Map

| Area | Tests |
|---|---|
| Lifecycle, presence, availability, endpoints, isolation (unit) | `AgentDirectoryServiceTest` |
| Active calls + history queries (unit) | `AgentCallQueryServiceTest` |
| DTO validation + authorization + error envelope (MockMvc slice) | `AgentDirectoryApiSliceTest` |
| Persistence, enum round-trip, queries, tenant isolation (real PostgreSQL, Flyway V1..V36) | `AgentFoundationIntegrationTest` |

Live behavior note: presence is an application-level domain state only —
FreeSWITCH registration/WebRTC connection state is intentionally **not**
integrated in VB-4A (documented for VB-4D+ runtime phases).

## 36. VB-4B — Queue Foundation

VB-4B introduces the tenant-owned queue domain consumed by later
contact-center phases. It answers **only**: "Which agents belong to this
queue?" It does **not** answer "Which agent should receive this call?" —
that is ACD (VB-4C). No agent selection, scoring, dispatch, timeout
execution or overflow execution exists in VB-4B.

### 36.1 Scope Boundary

**Implemented:** queue entity + lifecycle, configuration (capacity, wait
timeout, overflow), tenant ownership, membership + membership lifecycle,
waiting-call persistence/representation, queue CRUD APIs, membership APIs,
waiting-call read API, tenant isolation, V37 migration, tests, docs.

**Explicitly NOT implemented:** ACD/agent selection (VB-4C), queue
dispatch/polling workers, queue-entry orchestration (VB-4D inbound),
timeout execution, overflow execution, outbound agent calling (VB-4E),
WebRTC/Android SIP, recording, supervisor features, Redis/Kafka/new
scheduler.

### 36.2 Domain Model

```
Tenant
  └── Queue (V37: queues)
        ├── QueueMembership ──→ Agent (VB-4A, same tenant)
        │     (one LIVE row per (queue, agent); partial unique index)
        └── QueueWaitingCall ──→ CallSession (canonical call; FK only)
```

- `queues` — tenant_id, name (unique per tenant among live rows,
  case-insensitive), description, status, max_waiting_calls,
  max_wait_seconds, overflow_enabled, overflow_queue_id.
- `queue_memberships` — queue_id, agent_id, tenant_id, status. The same
  agent may belong to many queues. Membership is independent of the
  agent itself: an ACTIVE agent with an INACTIVE membership is not part
  of the queue for routing purposes. Removal is a **soft delete**
  (project pattern); the partial unique index on
  `(queue_id, agent_id) WHERE deleted_at IS NULL` keeps one live row per
  pair while allowing re-add after removal (history preserved).
- `queue_waiting_calls` — persists "this canonical CallSession is
  waiting in this queue". Duplicates **no** call attributes (no phone
  number, direction, provider UUID, call status snapshot). `entered_at`
  is authoritative for deterministic future dispatch ordering
  (`ORDER BY entered_at, id` — the index
  `idx_queue_waiting_calls_dispatch` supports exactly this). No agent
  leg is created by a waiting-call row.

### 36.3 Lifecycles

Queue (`queue_status`): `ACTIVE → INACTIVE → ACTIVE`,
`ACTIVE|INACTIVE → DISABLED`. **DISABLED is terminal** (service 409 +
documented). Same-state updates are idempotent.

Membership (`queue_member_status`): `ACTIVE ↔ INACTIVE`.

Waiting call (`queue_waiting_call_status`): `WAITING`, `REMOVED`,
`COMPLETED`, `ABANDONED`. **WAITING is a valid steady state in VB-4B**
(no assignment exists yet); transitions to the terminal states are owned
by later inbound/ACD flows, not by VB-4B APIs.

### 36.4 Configuration (persisted, NOT executed)

| Field | Meaning in VB-4B |
|---|---|
| `max_waiting_calls` | configured waiting capacity (>= 0). Exposure only: `GET .../capacity` reports configuredCapacity / currentWaiting / remainingCapacity as a **read model** — it is not an admission decision. |
| `max_wait_seconds` | configured wait-time budget (>= 0). Persisted; also snapshotted as `expires_at` on waiting-call rows (informational). **No timeout execution.** |
| `overflow_enabled` + `overflow_queue_id` | overflow **configuration**. Validation on write: target must exist in the same tenant, must not be DISABLED, must not be the queue itself (service rule + DB check `ck_queues_overflow_ref`). **No overflow execution.** |

### 36.5 Membership Concurrency (PostgreSQL, proven)

Concurrent add-member requests for the same (queue, agent) are
serialized by a transaction-scoped advisory lock
(`pg_advisory_xact_lock`, lock base `0x400000000L` + hash(queue, agent) —
disjoint from the VB-0 voice-capacity and VB-3 agent-reservation lock
spaces). The **blocking** variant is deliberate: a duplicate add must
succeed idempotently, so the loser waits for the winner's transaction,
re-reads under the lock (READ_COMMITTED then sees the committed row) and
returns it unchanged. The partial unique index remains the last-resort
guarantee. Catching `DataIntegrityViolationException` and re-reading was
rejected: on PostgreSQL the transaction is already aborted at that point.
Proven by a 20-thread real-PostgreSQL test: 20 concurrent adds → exactly
one live membership, 20 successful responses.
### 36.6 APIs Added

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| POST | /api/v1/queues | create queue | QUEUE_MANAGE |
| GET | /api/v1/queues/{queueId} | get queue | QUEUE_VIEW |
| GET | /api/v1/queues | list/filter queues (paging, search) | QUEUE_VIEW |
| PUT | /api/v1/queues/{queueId} | update configuration | QUEUE_MANAGE |
| PUT | /api/v1/queues/{queueId}/status | lifecycle transition | QUEUE_MANAGE |
| GET | /api/v1/queues/{queueId}/capacity | capacity read model | QUEUE_VIEW |
| POST | /api/v1/queues/{queueId}/members | add member (idempotent) | QUEUE_MANAGE |
| GET | /api/v1/queues/{queueId}/members | list members | QUEUE_VIEW |
| PUT | /api/v1/queues/{queueId}/members/{agentId} | membership status | QUEUE_MANAGE |
| DELETE | /api/v1/queues/{queueId}/members/{agentId} | remove member (soft) | QUEUE_MANAGE |
| GET | /api/v1/queues/memberships/agents/{agentId} | an agent's memberships | QUEUE_VIEW |
| GET | /api/v1/queues/{queueId}/waiting-calls | waiting-call read model | QUEUE_VIEW |

Conventions identical to VB-4A: `ApiResponse`/`ResponseFactory` envelope,
DTO records with validation, Swagger, server-derived
`OrganizationContextHolder` scope, `ResourceNotFoundException` (404
fail-closed) / `ConflictException` (409) / `BusinessException` (422).
New capabilities `QUEUE_VIEW` / `QUEUE_MANAGE` seeded in V37 (UUID
sequence continuing V1/V16), granted to
SUPER_ADMIN / RESELLER_ADMIN / TENANT_ADMIN; agents receive none
(fail-closed). No queue-entry or call-transition APIs are exposed —
clients cannot fake telephony lifecycle transitions.

### 36.7 Tenant Isolation

Every queue/membership/waiting-call lookup is tenant-scoped
(`findByIdAndTenantIdAndDeletedAtIsNull` and siblings); cross-tenant
references resolve to 404, never another tenant's data. Membership adds
resolve the agent **through the queue's tenant** — a foreign agent id
fails closed. Overflow targets must resolve in the same tenant. Proven
at unit, API-slice and real-PostgreSQL levels.

### 36.8 VB-4B Test Suite Map

| Area | Tests |
|---|---|
| Lifecycle, configuration validation, membership (incl. advisory-lock race paths), waiting-call read model, isolation (unit) | `QueueDirectoryServiceTest` |
| HTTP contract: validation 400, conflict 409, business-rule 422, not-found 404, envelope shape (MockMvc slice) | `QueueDirectoryApiSliceTest` |
| Flyway V1..V37 chain, native enums, FKs/partial-index/Check constraints, lifecycle persistence, waiting-call persistence + ordering, tenant isolation, 20-thread concurrent add proof (real PostgreSQL) | `QueueFoundationIntegrationTest` |

## 37. VB-4C — ACD (Automatic Call Distribution)

VB-4C is a **control-plane decision and reservation layer**: given a
waiting call and a queue, it determines eligible agents, selects one
deterministically, and atomically reserves that agent. **It performs no
telephony** — no ESL, no legs, no originate, no bridge, no provider ids.
VB-4D (inbound calling) consumes the ACD assignment and owns FreeSWITCH
orchestration.

### 37.1 Scope

**Implemented:** queue eligibility, waiting-call eligibility, agent
eligibility (membership + admin status + presence + endpoint +
capacity), deterministic selection, atomic reservation (VB-3
infrastructure), assignment, assignment idempotency, reservation
release, reservation expiry + stale reconciliation, queue timeout
execution, bounded queue overflow execution, tenant isolation,
explainability, tests, docs.

**Explicitly NOT implemented:** FreeSWITCH inbound calling, DID→queue
orchestration, inbound CallSession/CallLeg creation, agent leg dialing,
SIP originate, bridge, WebRTC/Android SIP, mobile wakeup, recording,
supervisor features, predictive/progressive/preview dialing, AI/skill
routing, Redis/Kafka/distributed locks, new scheduler infrastructure,
ACD transport integration.

### 37.2 ACD Domain (reuse over new concepts)

```
Queue ── QueueMembership ── Agent ── AgentReservation (VB-3 lifecycle)
  │                                        │ queue_id/waiting_call_id/expires_at (V38)
  └── QueueWaitingCall ── CallSession      ▲
        WAITING → ASSIGNED ────────────────┘ (assigned_agent_id/assigned_reservation_id)
```

- **No second reservation table.** `agent_reservations` (VB-3) gains
  `queue_id`, `waiting_call_id`, `expires_at` (V38) — ownership and a
  bounded hold window. NULL queue/waiting-call columns mean a VB-3
  CONNECT_BY_AGENT hold (stale reconciler owns those; no expiry).
- **No second waiting-call model.** `queue_waiting_calls` (VB-4B) gains
  the `ASSIGNED` enum value plus `assigned_agent_id`,
  `assigned_reservation_id`, `assigned_at`. Assignment ≠ telephony
  connection; no CallLeg is created by ACD.
- **BUSY remains lifecycle-owned.** ACD never sets availability
  directly; `AgentReservationService.reserve/release` flips it (VB-3
  rule), and ACD's expiry sweep restores AVAILABLE for expired holds.

### 37.3 Queue eligibility

Tenant-scoped lookup (foreign queue → `QUEUE_NOT_FOUND`, fail closed);
status must be ACTIVE (`QUEUE_NOT_ACTIVE` otherwise). ACD never
selects from an INACTIVE/DISABLED queue and never silently re-routes
the call to another queue during eligibility.

### 37.4 Waiting-call + agent eligibility

Waiting call: must resolve in the same tenant AND belong to the
requested queue (`WAITING_CALL_NOT_FOUND` otherwise); must be WAITING
(`WAITING_CALL_NOT_WAITING` for terminal rows). An ASSIGNED row with a
live reservation replays as `ALREADY_ASSIGNED`; if its reservation died
(released/expired), the stale marker is repaired back to WAITING so the
call becomes assignable again.

Agent (VB-4A semantics, composed with membership): ACTIVE membership in
the queue (membership is never inferred from admin status or presence);
agent row exists; `adminStatus=ACTIVE`; `availability=AVAILABLE`;
enabled dialable endpoint (SIP/EXTERNAL_FORWARD per VB-3/VB-4A
dialability rules); capacity from canonical live-hold counts
(`activeReservations(agent) < maxConcurrentCalls`). Rejections are
reason-coded (`AGENT_UNAVAILABLE`, `AGENT_OFFLINE`, `AGENT_BUSY`,
`AGENT_ENDPOINT_INVALID`) and surfaced per candidate in the result.

### 37.5 Selection policy (deterministic)

VB-3's proven rule, unchanged: eligible members ordered by
**least active reservations ASC, then agent id ASC** (stable
tie-break). Load is derived from the canonical reservation table — no
cached counters, no scoring, no randomness. Same database state always
yields the same candidate order.

### 37.6 Reservation/concurrency model

Selection is advisory; reservation is authoritative:

```
BEGIN
  advisory lock (VB-3 per-agent try-lock, base 0x3…)
  re-read state
  verify eligibility + capacity
  insert reservation (stamped queue_id/waiting_call_id/expires_at)
  claim assignment: UPDATE queue_waiting_calls
      SET status='ASSIGNED', assigned_* WHERE id=? AND status='WAITING'
  claim won  → COMMIT (ASSIGNED)
  claim lost → release fresh hold (ASSIGNMENT_LOST) → COMMIT
COMMIT
```

The conditional `claimAssignment` UPDATE is the duplicate-assignment
race boundary; the reservation lost-race path re-evaluates the NEXT
candidate (a caller never gets NO_AGENT while another member is free).
Retries are bounded by the candidate list — no loops.

### 37.7 Assignment

`ASSIGNED` means: waiting call + queue + agent + live reservation.
Exactly one live assignment per waiting call (partial-index +
conditional-claim invariant). Repeat ACD attempts for an assigned call
return the existing assignment (idempotent). VB-4C creates no CallLeg,
no provider call id, and touches no telephony boundary.

### 37.8 Reservation release

`releaseAssignment(waitingCallId, reason)`: conditional release of the
ACD hold (`waiting_call_id`-matched, idempotent) + conditional return
of the call to WAITING — the assignment becomes retryable by future
ACD invocations. VB-4C owns only ACD-layer release conditions (call
removed/abandoned, agent invalid before hand-off, expiry); hangup
paths remain VB-3's.

### 37.9 Reservation timeout (expiry)

ACD holds carry `expires_at = now + 5 min` (TTL constant on
`AcdService`). The `AcdMaintenanceScheduler` (@Scheduled 15s poll —
existing scheduler convention) releases expired RESERVED holds
(`ACD_EXPIRED`), returns their waiting calls to WAITING, and restores
agent availability. VB-3 holds (no expiry) are untouched. Idempotent:
conditional UPDATEs, repeated sweeps converge.

### 37.10 Queue timeout

Executes VB-4B's persisted `max_wait_seconds` via the waiting row's
`expires_at` snapshot (set at entry): WAITING past expiry → ABANDONED.
ASSIGNED calls are never abandoned by the sweep. Deterministic,
tenant-safe by construction (rows are tenant-owned), idempotent.

### 37.11 Overflow

Executes VB-4B configuration (`overflow_enabled` + `overflow_queue_id`)
under the V37 validation rules: target same-tenant, operational (not
DISABLED), never self. The move UPDATE matches the SOURCE queue and
WAITING rows only: bounded single hop per invocation, structurally
loop-free (A→B and B→A cannot recurse within one operation),
idempotent (already-moved rows don't match), expiry reset on arrival.

### 37.12 Capacity handling

Queue capacity remains a read model (`QueueCapacityResponse`); ACD adds
no admission logic (VB-4D inbound entry owns admission). Waiting calls
are never deleted on a failed assignment attempt — NO_ELIGIBLE_AGENT
leaves the call WAITING (§32 of the phase contract).

### 37.13 Tenant isolation

Every ACD operation verifies `waitingCall.tenantId == queue.tenantId ==
agent.tenantId == reservation.tenantId` through tenant-scoped lookups
(fail closed, 404/NOT_FOUND semantics). Cross-tenant queue references,
cross-tenant waiting-call injection and foreign overflow targets are
all proven rejected at unit and real-PostgreSQL levels.

### 37.14 Failure/result codes

`AcdResult(status, queueId, waitingCallId, agentId, reservationId,
reason, rejectedCandidates[])`. Statuses: ASSIGNED,
ALREADY_ASSIGNED, QUEUE_NOT_ELIGIBLE, WAITING_CALL_NOT_ELIGIBLE,
NO_ELIGIBLE_AGENT, OVERFLOWED (overflow execution reports via the
service return value; the status exists for future orchestration
results). Reasons reuse `AgentReasons` plus `AcdReasons`
(QUEUE_NOT_FOUND, QUEUE_NOT_ACTIVE, WAITING_CALL_NOT_FOUND,
WAITING_CALL_NOT_WAITING, NO_ACTIVE_MEMBERS, NO_ELIGIBLE_AGENT,
ACD_EXPIRED, ASSIGNMENT_LOST). Classification: queue/call-eligibility
failures are configuration/normal-lifecycle (not retryable system
errors); AGENT_BUSY/NO_ELIGIBLE_AGENT are capacity outcomes — the call
stays WAITING for a future attempt (invoked by the future inbound
orchestration, never a loop inside ACD).

### 37.15 Explicit non-goals

VB-4C determines and reserves an agent. It does not perform telephony.
No inbound calling, no DID→queue orchestration, no agent dialing, no
bridge, no recording, no supervisor features, no AI. Assignment
consumption (originate agent leg, bridge, hangup semantics) is
VB-4D's contract.

### 37.16 VB-4C test suite map

| Area | Tests |
|---|---|
| Eligibility matrix, deterministic selection/tie-break, race re-evaluation, idempotency, release, overflow (unit) | `AcdServiceTest` |
| Assignment persistence, enum round-trip, expiry/timeout sweeps (idempotent, real PostgreSQL), bounded overflow, tenant isolation, concurrency matrix CC-1..CC-6 (1→1, 2→1, 20→5, 20→5@2, same-call race, concurrent release) | `AcdIntegrationTest` |

## 38 — VB-4D: Inbound Calling

### 38.1 Scope

VB-4D connects the contact-center domain (VB-4A/4B/4C) to real inbound
telephony. Inbound calls converge on the canonical voice domain: an
inbound call IS a `CallSession` (`call_type = CONTACT_CENTER_INBOUND`,
`direction = INBOUND`) with a CUSTOMER leg (the FreeSWITCH caller
channel) and — after connection — an AGENT leg. No inbound-specific
call aggregate exists.

Implemented:

- CHANNEL_CREATE detection on the existing ESL boundary (no second client)
- DID → tenant → destination routing (QUEUE / direct AGENT)
- Canonical CallSession + CUSTOMER leg creation from the live channel
- Queue entry via the existing VB-4B `QueueWaitingCall` model
- ACD consumption: existing VB-4C assignment + existing VB-3 reservation
- Agent leg originate through the existing `AgentLegDialer` boundary
- Bridge confirmation through the existing VB-3 CHANNEL_BRIDGE handler
  (one bridge implementation for inbound and outbound)
- Caller-hangup cleanup at any stage (queued / reserved / ringing /
  bridged): queue row REMOVED, reservation released, agent leg torn down
- Direct-agent path: reservation + agent leg without a queue row
- V39 migration: inbound destination configuration on the existing
  `dids` table

Explicit non-goals (unchanged from the phase contract): IVR trees,
ring/hunt groups, skill routing, WebRTC/Android SIP endpoints, FCM
mobile wakeup, recording, supervisor features, AI agents, predictive /
progressive / preview dialing, Kafka, Redis, new schedulers, a second
ESL client, or any new telephony engine.

### 38.2 Inbound flow

```
FreeSWITCH CHANNEL_CREATE (Call-Direction=inbound)
  → EslEventService.handleInboundChannelCreate
  → InboundCallService.onInboundChannelCreated
      → DID lookup (live E.164) — fail closed when absent/foreign/unconfigured
      → CallSession (CONTACT_CENTER_INBOUND, INBOUND) + CUSTOMER leg
      → QUEUE:  QueueWaitingCall (WAITING, expiresAt = wait budget)
                → InboundAcdRetryScheduler → AcdService.attemptAssignment
                → InboundCallService.connectAssignedAgent → AGENT leg + originate
      → AGENT:  eligibility → AgentReservationService.reserve → AGENT leg + originate
  → FreeSWITCH events (session-agnostic, shared VB-3 boundary):
      CHANNEL_PROGRESS → agent leg RINGING
      CHANNEL_ANSWER   → agent leg ANSWERED → mediaController.bridge
      CHANNEL_BRIDGE   → BRIDGED (session + both legs), reservation ACTIVE
      CHANNEL_HANGUP   → finalize + release (agent leg path and
                         caller leg path, both idempotent)
```

### 38.3 DID configuration (V39)

```
CREATE TYPE did_inbound_destination AS ENUM ('QUEUE', 'AGENT');
ALTER TABLE dids ADD COLUMN inbound_destination did_inbound_destination,
                   ADD COLUMN inbound_queue_id UUID REFERENCES queues (id) ON DELETE SET NULL,
                   ADD COLUMN inbound_agent_id UUID REFERENCES agents (id) ON DELETE SET NULL;
```

- `inbound_destination IS NULL` → DID does not accept inbound calls
  (default for every existing/outbound/pool number — behavior unchanged)
- Kind/pointer coherence is enforced by the routing service (fail
  closed); the DB keeps `ON DELETE SET NULL` FKs so referential cleanup
  never wedges
- Tenant isolation: FKs scope the destination to the DID's tenant; pool
  numbers (`tenant_id IS NULL`) cannot carry an inbound destination
- Indexes: `idx_dids_inbound_destination`, `idx_dids_inbound_queue`,
  `idx_dids_inbound_agent`

### 38.4 Event handling and idempotency

- `EslClient.SUBSCRIBED_EVENTS` now includes `CHANNEL_CREATE`
- CHANNEL_CREATE is only routed to the inbound boundary when
  `Call-Direction=inbound` — outbound campaign channels are unaffected
- Dedup key: the caller channel UUID. A transaction-scoped advisory
  lock (`pg_advisory_xact_lock`, base `0x400000000L` — disjoint from
  VB-0 gateway/CPS and VB-3 agent bases) serializes concurrent
  CHANNEL_CREATE events for the same channel; duplicates block, re-read
  under the lock, and return the existing session (proven: 20
  concurrent duplicates → exactly 1 session + 1 leg)
- Caller ANSWER/HANGUP on inbound sessions (which have no CallAttempt)
  are handled by the inbound boundary; agent-leg events and bridge
  confirmations continue through the session-agnostic VB-3 handlers
- All transitions are state-guarded; duplicate/late events never
  regress state or double-act

### 38.5 Hangup and cleanup semantics

| Stage at caller hangup | Queue row | Reservation | Agent leg | Session |
|---|---|---|---|---|
| WAITING (queued) | REMOVED | none | none | FAILED (CALLER_HANGUP) |
| CONNECTING_AGENT (ringing) | untouched | released (VB-3 `onCallerHangup`) | CANCELLED + terminated | FAILED (CALLER_HANGUP) |
| BRIDGED (NORMAL_CLEARING) | untouched | released (VB-3) | COMPLETED | COMPLETED |

Agent hangup pre-bridge releases the reservation and fails the session
(`AGENT_CONNECT_FAILED` policy inherited from VB-3 — one deterministic
attempt, no automatic fallback). Queue timeout (WAITING → ABANDONED)
and bounded overflow remain owned by the VB-4C sweeps — VB-4D adds no
second timeout/overflow mechanism.

### 38.6 Concurrency and capacity invariants

- `activeReservations(agent) ≤ agent.maxConcurrentCalls` — proven with
  real PostgreSQL: 20 inbound calls → 5 agents (capacity 1) yields ≤ 5
  reservations, each agent ≤ 1; capacity 2 yields ≤ 10
- One session / one caller leg per channel UUID — proven with 20
  concurrent duplicate CHANNEL_CREATE events
- Reservation-before-dial holds on the direct-agent path; originate
  failure releases the assignment (no orphan holds)

### 38.7 VB-4D test suite map

| Area | Tests |
|---|---|
| Routing (queue/direct/fail-closed), idempotency, caller events, ACD connect step, originate-failure release (unit) | `InboundCallServiceTest` |
| CHANNEL_CREATE parsing (`Call-Direction`, `Caller-Destination-Number`, `Caller-Caller-ID-Number`), outbound isolation, boundary-absent fail-closed, exception containment (ESL contract) | `InboundEslRoutingTest` |
| Lifecycle IT-1..IT-11 + duplicate-event race + concurrency CC-1/CC-2 + timeout/overflow reuse + tenant isolation (real PostgreSQL, Flyway V1..V39) | `InboundIntegrationTest` |

Live FreeSWITCH E2E was not executed (no FreeSWITCH instance in the
test environment); the ESL/media seam is covered by the contract tests
above.

---

## 39 — VB-4E: Outbound Agent Calling

### 39.1 Scope

VB-4E adds **agent-originated outbound calling**: an authorized agent
requests one external call now, and the platform places it through the
exact same telephony path the campaign dialer uses. It intentionally
does **not** implement predictive/progressive/preview dialing, campaign
pacing, dialer schedulers, contact-list scheduling, or any agent-occupancy
logic. There is no new outbound telephony implementation — one canonical
path, two callers (campaign and agent).

```
Agent → endpoint validation → destination validation
      → VoiceRouting (CONTACT_CENTER_OUTBOUND, CLI DID from tenant pool)
      → VoiceCapacity (VB-0 channel/CPS reservation)
      → canonical CallSession + AGENT leg + CUSTOMER leg
      → AgentReservation (VB-3 advisory-lock hold, session-linked)
      → OutboundDialer.originate (customer leg)
      → customer answers → agent leg originate → uuid_bridge
      → conversation → hangup → releases
```

### 39.2 One outbound telephony path

The dialer seam moved out of the campaign slice so both callers share it:

- `voice.media.OutboundDialer` (+ `OutboundDialRequest`/`Response`/
  `Result`/`Exception`, `GatewayRoute`, `NoOpOutboundDialer`,
  `PhoneNumberNormalizer`) — provider-agnostic dialing boundary, now
  voice-owned because both the campaign path and the agent path depend on
  it (dependency direction: campaign → voice, voice → telephony/ESL; no
  voice → campaign edge exists anymore)
- `campaign.OutboundDialService` — campaign orchestration calling the
  shared seam (unchanged behavior)
- `voice.outbound.AgentOutboundCallService` — agent orchestration calling
  the same seam

This relocation removed the pre-existing `campaign → voice → campaign`
cycle from the modulith report (3 cycle groups → 2, both pre-dating
VB-3-era implementation edges, none containing VB-4E classes).

### 39.3 Agent eligibility (reused, not duplicated)

The request passes the existing VB-4A gates before any telephony work:
tenant-scoped agent lookup (fail closed), `adminStatus = ACTIVE`,
`availability = AVAILABLE` (BUSY remains owned by the reservation
lifecycle), and a dialable endpoint via the shared endpoint rules
(`SIP` dialable today; `EXTERNAL_FORWARD`/`WEBRTC`/`MOBILE_APP`/`AI`
follow the existing dialability rules as they are implemented — no new
endpoint types). The agent reservation reuses the VB-3 atomic
`AgentReservationService` (advisory lock `0x3…` keyspace,
`activeReservations(agent) ≤ maxConcurrentCalls` preserved; the hold is
linked to the `CallSession` so every cleanup path can find it).

### 39.4 Routing, capacity, CLI DID

- Routing: the existing `VoiceRoutingService.resolveRoute` hierarchy with
  `callType = CONTACT_CENTER_OUTBOUND` — same gateway selection, health,
  authorization, and failover rules as campaign dialing.
- CLI DID: agent-originated requests carry no DID, so the service
  deterministically picks the tenant's default CLI DID
  (`findFirstByTenantId...OrderByIdAsc` — lowest id wins; profile entries
  may still pin their own DID via `buildVoiceRoute`).
- Capacity: `VoiceCapacityService.reserve` before originate; release on
  hangup (authoritative — outbound sessions have no `CallAttempt`, so the
  attempt-scoped release in the shared hangup handler does not apply) and
  on synchronous originate failure. Stale holds remain covered by the
  VB-0 reconciliation.

### 39.5 Session/leg model and lifecycle

Canonical reuse: one `CallSession` (`CONTACT_CENTER_OUTBOUND`, direction
`OUTBOUND`, gateway + DID + reseller stamped) with an `AGENT` leg
(target = endpoint dial target, `INITIATED` until dialed) and a
`CUSTOMER` leg (target = validated E.164 destination). Sequencing:

1. `placeCall` — validate → route → reserve gateway capacity → create
   session + AGENT leg → agent hold (session-linked) → CUSTOMER leg →
   originate via `OutboundDialer`; synchronous failure unwinds
   everything (legs failed, gateway + agent holds released, deterministic
   `AgentOutboundCallException`).
2. Customer answer (`CHANNEL_ANSWER` on the customer leg) — customer leg
   `ANSWERED`, session `ANSWERED`, then the **agent leg is originated**
   (the agent endpoint has no channel until the bridge; the agent hold
   already exists — same reservation-before-dial order as VB-4D).
3. Agent answer → existing VB-3 bridge path (`CHANNEL_BRIDGE` anchored on
   the customer channel routes through the shared `ConnectByAgentService`
   bridge handling).
4. Hangup (either side) — shared cleanup: agent hold release +
   agent-leg teardown via the shared boundary, gateway capacity release,
   session finalized. Duplicate hangups no-op (already-final guard,
   matching VB-4D).

Outbound sessions carry no `CallAttempt`, so `EslEventService` gained an
attempt-less `CONTACT_CENTER_OUTBOUND` branch that routes
answer/progress/bridge events to `AgentOutboundCallService` (exposed via
the `@NamedInterface("outbound")` `voice.outbound` package — the same
boundary pattern as `voice.inbound`).

### 39.6 API

`POST /api/v1/agents/{agentId}/calls` with `{ "destination": "<E.164>" }`
(existing URL/DTO/auth/response conventions: `AGENT_MANAGE` capability,
`Scope`-checked principal, `ApiResponse` wrapper, canonical call identity
returned — no second call-id model). Request-scoped idempotency follows
existing service semantics; duplicate ESL events are idempotent by state.

### 39.7 Result codes

`AgentOutboundReasons`: `INVALID_DESTINATION`, `NO_ELIGIBLE_GATEWAY`,
`GATEWAY_CAPACITY_EXHAUSTED`, `CALL_ORIGINATE_FAILED`; existing
`AgentReasons`/`AgentFoundationReasons` reused for agent-side rejections.
Failure taxonomy: configuration errors are permanent; capacity/race
rejections are temporary; originate failures unwind deterministically.

### 39.8 Tenant isolation

Agent, endpoint, DID (CLI), route profile, gateway allocation, session,
and legs are all tenant-scoped lookups; cross-tenant access fails closed
(404/not-found semantics). Proven at unit and real-PostgreSQL level
(`AgentOutboundIntegrationTest` IT-2: Tenant A agent + Tenant B
topology → no session, no holds).

### 39.9 VB-4E test suite map

| Area | Tests |
|---|---|
| Eligibility, destination, routing, capacity, reservation unwind, lifecycle, CLI-DID fallback (unit) | `AgentOutboundCallServiceTest` |
| Outbound session-event correlation, bridge delegation, hangup containment, duplicate-answer idempotency, outbound isolation (ESL contract) | `OutboundEslRoutingTest` |
| IT-1 basic flow, IT-2 tenant isolation, IT-3 gateway capacity race, IT-4 agent capacity race, IT-5 20×4-agent matrix (gateway cap 8), IT-6 hangup cleanup (both holds released), IT-7 duplicate-answer race → one agent leg (real PostgreSQL, Flyway V1..V39) | `AgentOutboundIntegrationTest` |

### 39.10 Defects found and fixed during VB-4E

- `VoiceCapacityServiceImpl.releaseChannelLock` called
  `pg_advisory_xact_unlock`, a function that **does not exist** in
  PostgreSQL (transaction-scoped advisory locks release automatically at
  commit/rollback). Every real `reserve()` therefore failed at the
  `finally` block — previously unobserved because no test exercised the
  real reserve path end-to-end. The manual unlock was removed (VB-3
  documented the same auto-release contract). Regression: IT-3/IT-5.
- Latent NAMED_ENUM mapping gaps on first real persistence:
  `VoiceRouteProfileEntry.routeType`, `PhoneListEntry.type`/`scopeType`
  (same defect class as the VB-4D `DidEntity` fix), plus the
  `VoiceRouteProfile.entries` unidirectional `@OneToMany` inserting
  children with a NULL `profile_id` (made bidirectional, mirroring the
  existing `CallSession`/`CallLeg` style).

Live FreeSWITCH E2E was not executed (no FreeSWITCH instance in the
test environment); the ESL/media seam is covered by the contract tests
above, as in prior phases.

### 39.11 Explicit non-goals (deferred)

Predictive/progressive/preview dialing, campaign pacing, outbound
schedulers, contact-list scheduling, agent-occupancy optimization,
WebRTC/Android SIP, recording, AI routing — none are implemented or
stubbed beyond the documented extension points. The agent call API is
deliberately minimal; VB-4F (hardening) owns rate limiting, abuse
controls, and operational hardening of this surface.

---

## 40 — VB-4F: Hardening & Full Verification

VB-4F is the consolidation phase for the VB-4 contact-center milestone:
no new product scope, verification of the complete Agent + Queue + ACD +
Inbound + Outbound system against real PostgreSQL, plus hardening fixes
for the latent defects the verification exposed.

### 40.1 Verification scope

- **Ownership/tenant isolation audit** — every `findById`-style lookup in
  the VB-4 services was inspected: agent/endpoint/queue/membership/
  waiting-call/reservation/session/leg lookups are tenant-scoped
  (`findByIdAndTenantIdAndDeletedAtIsNull`) or reseller-hierarchy checked;
  `releaseForCallSession`/`releaseAssignment` operate on internal,
  already-tenant-verified session/waiting-call identifiers. ACD eligibility
  re-verifies queue, waiting call, and membership tenant match (fail
  closed). V39 already enforces DID→destination same-tenant CHECK
  constraints at the DB layer.
- **Advisory-lock audit** — all `pg_*_advisory_xact_lock` call sites use
  CAST-typed parameters, transaction-scoped acquisition, and auto-release
  (no manual unlock; the VB-3 comment misdiagnosing the removed
  `pg_advisory_xact_unlock` call was corrected).
- **State-machine audit** — conditional-UPDATE guards verified:
  `RESERVED→ACTIVE` (promoteToActive), terminal release (status <>
  'RELEASED'), `WAITING→ASSIGNED` claim, WAITING-only timeout sweep
  (ASSIGNED rows never abandoned), terminal-session connect guard, and
  duplicate-answer/bridge/hangup idempotency (existing C*/REL*/W-EL*
  suites).
- **Fresh-database migration verification** — the integration suites run
  the real Flyway V1..V40 chain on fresh Testcontainers PostgreSQL;
  `VoiceSchemaMigrationIntegrationTest` additionally asserts migration
  history integrity.
- **Event-ordering verification** — new permutation tests (below).

### 40.2 Defects found and fixed

1. **DEFECT-001 (isolation/concurrency) — advisory-lock keyspace
   collision.** `QueueDirectoryService.membershipLockId` used base
   `0x400000000L`, identical to `InboundCallService.INBOUND_LOCK_BASE`
   (VB-4D): concurrent queue-membership and inbound-dedup operations
   hashed into the same keyspace, so an unrelated inbound creation could
   serialize membership writes (and vice versa). Membership moved to base
   `0x5` — the keyspace is now `0x1..0x5`, disjoint by construction.
2. **DEFECT-002 (availability restoration).** The VB-3 stale-reservation
   reconciler released orphaned holds with a bulk UPDATE but never
   restored the agents' runtime availability: an agent whose hold was
   reclaimed stayed `BUSY` indefinitely and was rejected by VB-4E
   outbound eligibility. The reconciler now snapshots affected agents
   before the bulk release and restores `AVAILABLE` for those left with
   no remaining active hold (matching the `EXPIRED` path's behavior in
   the ACD maintenance sweep).
3. **DEFECT-003 (database invariant).** The reservation lifecycle
   resolves/releases holds by `call_session_id` (single-row lookup), so
   two live holds for one session would permanently leak an agent slot.
   V40 adds the missing uniqueness guarantee
   (`uq_agent_reservations_live_session`, partial unique index on
   `call_session_id WHERE status <> 'RELEASED'`, replacing the plain
   session index — same pattern as V37's
   `uq_queue_waiting_calls_active_session`).

### 40.3 VB-4F hardening test additions

| Area | Tests |
|---|---|
| FreeSWITCH event-ordering permutations: hangup-before-answer, late answer after termination, noisy PROGRESS/ANSWER×2/HANGUP×2 sequence (leg mutated exactly once), bridge without answer, out-of-order progress, unknown-UUID containment, null hangup cause | `OutboundEventOrderingTest` (7) |
| Stale-reclaim reconciliation: hold RELEASED + `BUSY→AVAILABLE` restored, idempotent double-run, agent with a second live hold keeps BUSY | `AgentStaleReconcilerIntegrationTest` (3, real PostgreSQL) |
| Reservation-uniqueness DB invariant: second live hold for the same session rejected by V40, terminal release permits legitimate re-reserve | `AgentReservationUniquenessIntegrationTest` (2, real PostgreSQL) |

### 40.4 Baseline comparison

| Metric | Before VB-4F | After VB-4F |
|---|---|---|
| Tests | 689 | 701 (+12, all green) |
| Failures | 0 | 0 |
| Errors | 13 (3 documented baseline classes) | 13 (identical classes) |
| Skipped | 1 | 1 |
| Modulith cycles | 2 (VB-3-era, no VB-4 classes) | 2 (identical) |

The 13 baseline errors: ArchitectureTest 1 (pre-existing
campaign↔telephony↔voice implementation edges), ProvisioningSmoke 1,
SecuritySlice 11. No VB-4F class appears in any violation.

### 40.5 Final verification matrix

| Area | Verification | Result |
|---|---|---|
| Tenant isolation | Cross-tenant ITs across VB-4A..E + ownership audit | PASS |
| Ownership | Scoped lookup audit (`findById` sweep) | PASS |
| State machines | Conditional-UPDATE guards + existing transition suites | PASS |
| Idempotency | Duplicate answer/bridge/hangup/create suites | PASS |
| Agent concurrency | VB-3/VB-4E 20-thread races + V40 uniqueness | PASS |
| Queue/ACD concurrency | VB-4C CC-1..CC-6 | PASS |
| Inbound concurrency | VB-4D IT-4 race, CC-1/CC-2 | PASS |
| Stale reservations | SR-1..SR-3 (now with availability restoration) | PASS |
| Telephony failure matrix | Originate failure, no-answer, caller/agent hangup, bridge failure suites | PASS |
| Event ordering | EO1..EO7 permutations | PASS |
| Database | Fresh-container Flyway V1..V40, constraints/FKs/indexes reviewed | PASS |
| Advisory locks | Keyspace 0x1..0x5 disjoint, tx-scoped, auto-release | PASS |
| Architecture | 2 pre-existing cycles, no new/suppressed violations | PASS |
| Regression VB-0..VB-4E | Full suite 701/0/13/1 | PASS |

Live FreeSWITCH E2E: NOT EXECUTED (no FreeSWITCH instance available);
coverage is the ESL contract + event-ordering + real-PostgreSQL suites,
consistent with every prior phase.
