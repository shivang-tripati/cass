# VB-4E — Implementation Report (Outbound Agent Calling)

## 1. Status

**READY FOR VB-4F** — full evidence below.

## 2. What Was Implemented

Agent-originated outbound calling: an authorized, available agent requests
one external call; the platform validates agent + endpoint + destination,
resolves the outbound route (CLI DID from the tenant's assigned pool),
reserves VB-0 gateway capacity, takes a VB-3 atomic agent hold linked to
the session, creates the canonical `CallSession` with `AGENT` + `CUSTOMER`
legs, originates the customer leg through the shared dialer seam, and on
customer answer originates the agent leg and bridges via the existing
VB-3 path. Hangup (either side) releases both holds and finalizes the
session idempotently. API: `POST /api/v1/agents/{agentId}/calls`.

Intentionally **not** implemented: predictive/progressive/preview dialing,
campaign pacing, dialer schedulers, contact-list scheduling, occupancy
optimization, WebRTC/Android SIP, recording, AI routing.

## 3. Existing Components Reused

| Component | Role |
|---|---|
| `Agent` / `AgentEndpointEntity` + endpoint dialability rules | agent & endpoint validation (VB-4A) |
| `AgentReservationService` (advisory lock `0x3…`) | atomic agent hold, `releaseForCallSession`, `attachLeg` (VB-3) |
| `VoiceRoutingService.resolveRoute` + `VoiceEligibilityService` + `GatewayAuthorizationService` | gateway route selection for `CONTACT_CENTER_OUTBOUND` (VB-0) |
| `VoiceCapacityService` reserve/release + VB-0 reconcilers | channel/CPS capacity (VB-0) |
| `CallSession` / `CallLeg` (`CONTACT_CENTER_OUTBOUND`, `AGENT`, `CUSTOMER`) | canonical call model — no new aggregates |
| `OutboundDialer` seam + `FreeSwitchOutboundDialer` / `NoOpOutboundDialer` | customer-leg originate (shared with campaign) |
| `AgentLegDialer` (`FreeSwitchAgentLegDialer` / `NoOp`) | agent-leg originate |
| `ConnectByAgentService` (`AgentConnectEvents`) | agent-leg lifecycle, bridge, caller-hangup cleanup (VB-3/VB-4D shared boundary) |
| `EslEventService` event correlation | answer/progress/bridge/hangup routing |
| `ApiResponse`/`ResponseFactory`, `AuthorizationService.requireCapability`, `Scope` | API/auth conventions |
| `PhoneNumberNormalizer` | E.164 validation |

## 4. New Files

- `voice/outbound/AgentOutboundCallService.java` — core orchestration
- `voice/outbound/AgentOutboundReasons.java`, `AgentOutboundCallResult.java`, `AgentOutboundCallException.java`
- `voice/outbound/AgentOutboundApiService.java` — auth/scope binding
- `voice/outbound/package-info.java` — `@NamedInterface("outbound")`
- `voice/agent/dto/CreateAgentCallRequest.java`, `AgentCallResponse.java`
- `voice/media/` additions (relocated, see §5): `OutboundDialer`, `OutboundDialRequest`, `OutboundDialResponse`, `OutboundDialResult`, `OutboundDialException`, `GatewayRoute`, `NoOpOutboundDialer`, `PhoneNumberNormalizer`
- Tests: `AgentOutboundCallServiceTest` (20), `OutboundEslRoutingTest` (8), `AgentOutboundIntegrationSupport` + `AgentOutboundIntegrationTest` (7)

## 5. Modified Files

- `voice/agent/AgentDirectoryController.java` — added `POST /{agentId}/calls`
- `telephony/EslEventService.java` — attempt-less `CONTACT_CENTER_OUTBOUND` session branch (answer/progress/bridge request); bridge event delegates to the shared handler
- `telephony/VoiceCapacityServiceImpl.java` — defect fix (§15)
- `telephony/DidRepository.java` — CLI-DID finder
- `did/DidEntity.java` — `NAMED_ENUM` binding (VB-4D defect class)
- `voice/routing/VoiceRouteProfileEntry.java`, `VoiceRouteProfile.java` — `NAMED_ENUM` + bidirectional `@OneToMany` fixes
- `telephony/PhoneListEntry.java` — `NAMED_ENUM` fixes
- **Seam relocation** (no behavior change, import updates only): `campaign.OutboundDialer/Request/Response/Result/Exception/GatewayRoute/NoOpOutboundDialer` + `telephony.PhoneNumberNormalizer` → `voice.media.*`; `campaign.OutboundDialService`, `telephony.CallEligibilityService`, `telephony.PhoneListService`, `telephony.VoiceEligibilityService`, `telephony.package-info` updated accordingly
- Existing test constructor sites updated for the new `EslEventService` collaborator

## 6. Database Changes

**No migration required.** The existing schema (through V39) fully
represents agent outbound calls: `CallSession.call_type` already contains
`CONTACT_CENTER_OUTBOUND`, `CallLeg` supports `AGENT`/`CUSTOMER`, and
`agent_reservations` already carries the session linkage. Verified against
real PostgreSQL (Flyway V1..V39).

## 7. Outbound Call Flow

```
POST /agents/{id}/calls (AGENT_MANAGE, tenant scope)
  → agent ACTIVE + AVAILABLE + dialable endpoint (VB-4A rules)
  → destination E.164 normalized/validated
  → CLI DID = tenant's assigned DID (lowest id, deterministic)
  → VoiceRoutingService.resolveRoute(CONTACT_CENTER_OUTBOUND)
  → VoiceCapacityService.reserve(gateway, tenant)          [VB-0]
  → CallSession(DIALING) + AGENT leg(INITIATED)            [canonical]
  → AgentReservationService.reserve(agent, tenant, session)[VB-3, atomic]
  → CUSTOMER leg + OutboundDialer.dial → provider UUID stamped
  → CHANNEL_ANSWER(customer) → leg ANSWERED, session ANSWERED
      → agent leg origination (AgentLegDialer) → DIALING
  → agent answers → uuid_bridge (shared VB-3 path)
  → CHANNEL_BRIDGE → BRIDGED
  → hangup (either side) → agent hold released + agent leg torn down
      + gateway capacity released → session COMPLETED/FAILED
```

Failure semantics: originate failure (synchronous or ESL exception)
unwinds legs, gateway hold, and agent hold, and throws a deterministic
`AgentOutboundCallException`; capacity/race rejections never create
canonical state; duplicate events are idempotent by state checks
(pre-answer guard, already-originated guard, already-final guard).

## 8. CallSession / CallLeg Model

One `CallSession` per call: `tenantId`, `resellerId`, direction
`OUTBOUND`, `callType CONTACT_CENTER_OUTBOUND`, `gatewayId`, `didId`,
`destinationNumber`, `providerCallId` (customer channel). Two legs:
`AGENT` (target = endpoint dial target, provider UUID stamped at agent
origination) and `CUSTOMER` (target = destination, provider UUID from
dial response). No new tables, enums, or call aggregates.

## 9. Agent Reservation / Capacity

Reuses the VB-3 lifecycle unchanged: `RESERVED → ACTIVE → RELEASED`,
advisory-lock protected, `activeReservations(agent) ≤ maxConcurrentCalls`
proven under 20-thread concurrency (IT-4, IT-5). The hold is created
before any originate and released by every cleanup path
(hangup/originate-failure/release-for-session). BUSY is never set
directly on the agent row — the reservation is the source of truth.

## 10. Gateway Routing / Capacity

Same `VoiceRoutingService` hierarchy as campaign dialing (profile
entries, gateway health/authorization, failover). CLI DID resolution is
new but minimal: `findFirstByTenantId...StatusAndAllocationStateOrderByIdAsc`
(deterministic lowest-id pick from the tenant's assigned pool). Capacity
reserve precedes originate; release on hangup is authoritative for
outbound sessions (no `CallAttempt` → attempt-scoped release in the shared
hangup handler does not apply); stale holds covered by existing VB-0
reconciliation.

## 11. FreeSWITCH / ESL Integration

No second ESL client. `EslEventService` gained an outbound-session branch
for attempt-less `CONTACT_CENTER_OUTBOUND` sessions; agent-leg events,
bridge, and hangup cleanup flow through the shared VB-3 boundary.
`voice.outbound` is exposed as a `@NamedInterface("outbound")` (same
pattern as `voice.inbound`), keeping the modulith boundary explicit.

## 12. Idempotency

Duplicate `CHANNEL_ANSWER` (20 concurrent threads, IT-7): exactly one
agent-leg origination. Duplicate hangup: already-final short-circuit
(pre-existing-terminal guard, matching VB-4D). Duplicate bridge events:
delegated to the shared VB-3 idempotent handler. HTTP-layer retries
produce deterministic validation/routing results (state-derived, no
duplicate canonical state).

## 13. Tenant Isolation

Every lookup is tenant-scoped: agent, endpoint, CLI DID, route profile,
gateway allocation, session, legs, reservation. Cross-tenant fails closed
(not-found). Proven by IT-2 (Tenant A agent + Tenant B topology → no
session, no holds) and the unit tenant-isolation tests.

## 14. Tests

| Suite | Result |
|---|---|
| Unit (`AgentOutboundCallServiceTest`) | 20/20 ✓ |
| ESL contract (`OutboundEslRoutingTest`) | 8/8 ✓ |
| PostgreSQL integration (`AgentOutboundIntegrationTest`, real PG V1..V39) | 7/7 ✓ (incl. 20-call/4-agent matrix, gateway cap 8; 20-thread agent-capacity race; duplicate-answer race) |
| **Full suite (`mvnw clean test`)** | **689 tests, 0 failures, 13 errors, 1 skipped** |

The 13 errors / 1 skip are the exact pre-VB-4E baseline categories:
ArchitectureTest 1 (pre-existing campaign↔telephony/voice↔telephony
cycles — **now improved**, see §18), ProvisioningSmokeIntegrationTest 1,
SecuritySliceTest 11. No VB-4E class appears in any cycle or as a new
failure.

## 15. Defects Found and Fixed

1. **`pg_advisory_xact_unlock` does not exist** — `VoiceCapacityServiceImpl.releaseChannelLock` called a nonexistent function inside `reserve()`'s `finally`; every real `reserve()` failed. Unobserved until now because no test exercised the real reserve path end-to-end. Transaction-scoped advisory locks auto-release at commit/rollback, so the manual unlock was removed. Regression: IT-3/IT-4/IT-5.
   *(Note: the VB-3 comment blaming JPA parameter typing was a misdiagnosis; the CAST fix was correct but insufficient — the function itself doesn't exist.)*
2. **Latent `NAMED_ENUM` mapping gaps** on first real persistence: `VoiceRouteProfileEntry.routeType`, `PhoneListEntry.type`/`scopeType` (`@Enumerated(STRING)` over native PG enums). Same defect class as the VB-4D `DidEntity` fix. Regression: IT-1.
3. **`VoiceRouteProfile.entries` unidirectional `@OneToMany`** inserted children with NULL `profile_id` (NOT NULL violation) — made bidirectional. Regression: IT-1.
4. **Pre-existing modulith cycle** `campaign → voice → campaign` (via campaign-owned dialer seam) — resolved by relocating the seam to `voice.media` (§5/§18), not by disabling the test.

## 16. Existing Baseline Failures

Unchanged categories (13 errors, 1 skip): architecture cycle test
(campaign↔telephony/voice↔telephony implementation edges predating VB-4E),
Provisioning smoke, SecuritySlice. Counts identical to the pre-VB-4E
baseline (654+35=689, 0 failures).

## 17. Known Limitations

- No predictive/progressive/preview dialing, no pacing, no outbound
  scheduler (deliberate non-goals).
- Live FreeSWITCH E2E not executed (no instance available); ESL/media
  seam covered by contract tests, as in VB-3/VB-4D.
- Agent API is intentionally minimal; rate limiting/abuse hardening
  belongs to VB-4F.
- Agent answer → bridge depends on the shared VB-3 `CHANNEL_BRIDGE`
  path; no separate inbound/outbound bridge logic exists (by design).

## 18. Architecture Impact

- Dependency direction after the seam relocation: `campaign → voice`
  (orchestration), `voice → telephony` (ESL implementations), with the
  dialer seam voice-owned in `voice.media`. The **voice → campaign edge
  was eliminated**, removing a pre-existing cycle: modulith cycle groups
  went **3 → 2**; the 2 remaining cycles are VB-3-era implementation
  edges (`campaign↔telephony↔voice`, `telephony↔voice`) and contain no
  VB-4E classes.
- No new module cycles introduced; `voice.outbound` exposed only via
  `@NamedInterface`. No Redis/Kafka/new infrastructure. One dialer seam,
  one capacity system, one reservation system, one canonical call model.

## 19. Production Validation Still Required

Live FreeSWITCH originate/bridge/hangup against real SIP endpoints and
PSTN trunks; gateway CPS behavior under production traffic; observability
dashboards for the new session type.

## 20. Final Verdict

**READY FOR VB-4F** — all VB-4E acceptance criteria pass: one canonical
outbound path, canonical call model, reused routing/capacity/reservation,
proven concurrency and tenant isolation, idempotent lifecycle, exact
baseline regression, documentation updated (§39 of
`docs/voice-routing-and-capacity.md`).
