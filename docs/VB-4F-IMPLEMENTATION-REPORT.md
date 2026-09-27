# VB-4F — Hardening & Full Verification Report

## 1. Status

**READY — VB-4 COMPLETE**

## 2. Baseline Before VB-4F

| Metric | Value |
|---|---|
| Tests | 689 |
| Failures | 0 |
| Errors | 13 — ArchitectureTest 1 (pre-existing campaign↔telephony↔voice cycles), ProvisioningSmokeIntegrationTest 1, SecuritySliceTest 11 |
| Skipped | 1 |
| Modulith cycles | 2 (VB-3-era, no VB-4 classes) |

(Note: one intermediate `clean test` run produced 114 errors /
656 tests from a stale incremental-compile classpath
(`NoClassDefFoundError: TokenService` cascading into context failures);
a subsequent `clean` run restored the exact 13-error baseline and the
run was repeated clean before any VB-4F change. Recorded for
completeness under baseline discipline.)

## 3. Verification Scope

Complete VB-4 surface inspected as built (not as documented): VB-4A
(Agent, AgentEndpoint, directory/call-query services, status/presence/
availability), VB-4B (Queue, QueueMembership, QueueWaitingCall,
lifecycle/configuration), VB-4C (AcdService, AcdOverflowService,
AcdMaintenanceScheduler, reservation lifecycle, assignment, timeout,
overflow, reconcilers), VB-4D (InboundCallService, InboundCallEvents,
InboundAcdRetryScheduler, DID→tenant→destination, queue/direct-agent,
bridge, cleanup), VB-4E (AgentOutboundCallService, API, routing,
capacity, ESL originate, bridge, cleanup), plus shared infrastructure
(CallSession/CallLeg, VoiceRouting, VoiceEligibility,
VoiceCapacityService, AgentReservationService, EslEventService,
AgentLegDialer, OutboundDialer seam).

## 4. Data Correctness

Ownership and relationship integrity verified across the canonical
entities: Agent, AgentEndpoint, AgentReservation, Queue,
QueueMembership, QueueWaitingCall, CallSession, CallLeg, SipGateway,
SipGatewayAllocation, channel reservations, DID. All tenant-owned
tables carry `tenant_id` with FK + tenant-scoped access paths;
session→legs, agent→endpoint, agent→reservation, queue→membership,
queue→waiting-call, gateway→reservation, DID→tenant relationships are
consistent (verified by the fresh-DB constraint checks and the
cross-tenant integration suites of VB-4A..E, all green).

## 5. Tenant / Organization Isolation

- Every audited lookup in VB-4 services uses
  `findByIdAndTenantIdAndDeletedAtIsNull` or the reseller-hierarchy
  visibility check (`findVisible`); plain `findById` remains only where
  the identifier was already tenant-verified upstream (internal
  resolution, scheduler-owned sweeps) — each case individually justified.
- ACD eligibility re-verifies queue.tenantId, waitingCall.tenantId, and
  membership.tenantId against the caller's scope (fail closed, 404
  semantics).
- VB-4D DID→destination routing is DB-enforced same-tenant via V39 CHECK
  constraints (`ck_dids_inbound_same_tenant_queue/agent`).
- Cross-tenant rejection is proven by the existing integration suites:
  AgentFoundation, QueueFoundation, AcdIntegration (IT-6), Inbound (IT-10),
  AgentOutbound (IT-2) — all green.
- The platform's identity model (organization/reseller/tenant via
  organizational home, VB-0-era) was not redefined; reseller-scoped
  visibility in the directory services was verified against it.

## 6. Ownership Verification

`findById` sweep across voice/campaign/telephony: no method accepting an
external `agentId/endpointId/queueId/waitingCallId/callSessionId/
callLegId/reservationId/gatewayId/didId` resolves tenant-owned state
without scope. The remaining unscoped `findById` sites are
(A) internal follow-up lookups of already-scoped identifiers
(AgentCallQueryService session resolution from scoped agent legs,
AcdService agentIdFromClaim), (B) scheduler-owned sweeps over
system-derived identifiers (AcdMaintenanceScheduler, reconciler), or
(C) execution services operating on provider-correlated sessions
(Dtmf/Playfile execution — campaign-plane, tenant-checked upstream at
dial time). No attacker-combinable cross-tenant ID resolution found.

## 7. State-Machine Verification

All major machines audited with their actual states:

- **Agent**: admin ACTIVE→SUSPENDED→ACTIVE allowed, DISABLED terminal
  (409), suspension flips presence OFFLINE, BUSY not manually settable
  (owned by reservation lifecycle) — existing L7–L10/P1–P4 suites.
- **AgentReservation**: RESERVED→ACTIVE via conditional UPDATE
  (`promoteToActive`), any→RELEASED terminal via
  `status <> 'RELEASED'` guards; expired-reservation→active impossible;
  V40 (below) forbids a second live hold per session.
- **QueueWaitingCall**: WAITING→ASSIGNED atomic claim (status-guarded
  UPDATE), ASSIGNED→WAITING unwind, WAITING→ABANDONED only from WAITING
  (ASSIGNED never abandoned by the timeout sweep), terminal states never
  reassigned — existing W-EL-1..5, IT-3/IT-4 suites.
- **CallSession/CallLeg**: terminal sessions never reconnect
  (C6), hangup→bridge and answer-after-hangup permutations contained
  (new EO tests), finalized legs never mutated (terminal-guard in all
  cleanup paths).

Invalid transitions either no-op idempotently or throw the domain error
— per the established lifecycle semantics; no new behavior invented.

## 8. Idempotency Verification

Duplicate-operation guarantees re-verified: duplicate CHANNEL_CREATE →
one session (VB-4D IT, 20-thread race), duplicate answer → one agent-leg
origination (VB-4E IT-7, 20 threads), duplicate bridge confirmation →
single state transition (C11), duplicate hangup → already-final
short-circuit with single release (C14, VB-4D/VB-4E cleanup tests),
duplicate connect → leg reuse (C2), repeated ACD attempts →
ALREADY_ASSIGNED (W-EL-4, IT-2). No duplicate sessions/legs/reservations/
assignments/capacity holds.

## 9. Concurrency Verification

Real-PostgreSQL race suites all green: agent reservation races
(AgentConnectIntegrationTest 20-thread try-lock matrix, VB-4E IT-3/IT-4
single-admission races), queue assignment races (AcdIntegrationTest
CC-1..CC-6: 20 callers → 5 agents at capacity 1 and 2, same-call claim
race, reservation+release race), simultaneous inbound calls (VB-4D
CC-1/CC-2: 20 calls → 5 agents ≤ capacity), gateway capacity races
(VoiceCapacityConcurrencyIntegrationTest), and the new V40 uniqueness
invariant (UQ-1). Availability races and stale-reservation
reconciliation are covered by the new SR suites (§15, DEFECT-002).

## 10. Telephony Failure Verification

Failure matrix verified by existing suites: customer originate failure
(unwind of legs + gateway hold + agent hold — VB-4E unit + IT),
agent originate failure (C5, VB-4D originate-failure release), agent
no-answer (AgentConnectTimeoutScheduler + reconciler — now with
availability restoration, SR-1), caller hangup (C15, A7, VB-4D/VB-4E
cleanup), agent hangup (C12/C13/C14), bridge failure (C10, BRIDGE_FAILED
release), duplicate events (§8). All cleanup paths release both the
agent hold and the gateway hold; no orphan resources.

## 11. FreeSWITCH Event Ordering

New permutation suite `OutboundEventOrderingTest` (7 tests) on the
outbound lifecycle: hangup-before-answer (finalized by the hangup path,
no originate), answer-after-termination (delivered but contained —
terminal state preserved, no resurrection), noisy
PROGRESS/ANSWER×2/HANGUP×2 sequence (leg mutated exactly once, both
hangups contained), bridge-without-answer (ignored safely, nothing
originates), progress-after-answer (ring state never regresses an
answered leg), unknown-UUID containment (later events still processed),
null hangup cause (normalized as success). The boundary delivers and
contains; idempotency guards downstream reject invalid orderings without
duplicating resources or stranding holds.

## 12. Database Verification

- Fresh Testcontainers PostgreSQL runs the real Flyway chain V1..V40 in
  every integration suite; `VoiceSchemaMigrationIntegrationTest` asserts
  complete, failure-free migration history.
- Constraints reviewed: V37 partial unique memberships
  (`uq_queue_memberships_queue_agent`) and one-WAITING-row-per-session
  (`uq_queue_waiting_calls_active_session`); V39 same-tenant CHECKs on
  DID destinations; **V40 (new)** one-live-reservation-per-session
  (`uq_agent_reservations_live_session`).
- NAMED_ENUM round-trips for all native enums verified by the schema and
  lifecycle ITs (including the VB-4D/VB-4E-mapped columns).
- Division of invariants preserved: DB enforces uniqueness/tenant-match
  invariants; services enforce lifecycle/eligibility invariants.

## 13. Transaction / Lock Verification

- All advisory-lock sites use `CAST(:lockId AS bigint)` parameters,
  transaction-scoped acquisition, and auto-release at commit/rollback.
- **No** `pg_advisory_xact_unlock` remains anywhere (the nonexistent
  function removed in VB-4E; the VB-3 comment that misattributed the
  failure to JPA parameter typing was corrected in VB-4F).
- Keyspace audit found and fixed a real collision (DEFECT-001); final
  keyspaces: `0x1` channel, `0x2` CPS, `0x3` agent reservation, `0x4`
  inbound dedup, `0x5` queue membership — disjoint.
- Transaction boundaries verified by the concurrency suites: partial
  failure cannot leave reservation-without-call, hold-without-call, or
  assignment-without-reservation (each unwind path exercised by C5/C15/
  REL-1/R-4 and the VB-4E/4D failure ITs).

## 14. Architecture / Modulith Verification

ArchitectureTest re-run: 2 cycle groups (both VB-3-era implementation
edges: campaign↔telephony↔voice, telephony↔voice) — identical to the
pre-VB-4F baseline; no VB-4F class appears in any violation or cycle;
no violation suppressed; canonical CallSession/CallLeg, single routing,
single capacity, single reservation model, single ESL boundary all
preserved.

## 15. Defects Found

### DEFECT-001
- **Area**: Concurrency / isolation (advisory locks)
- **Problem**: Queue-membership writes and inbound-call dedup hashed into
  the same advisory-lock keyspace (`0x400000000L`).
- **Root Cause**: VB-4B chose `0x4` for membership before VB-4D chose
  `0x4` for inbound dedup; neither audit cross-checked the global
  keyspace.
- **Fix**: Membership base moved to `0x5` (`QueueDirectoryService`);
  keyspaces documented as `0x1..0x5` disjoint.
- **Verification**: Advisory-lock audit (§13); existing membership and
  inbound concurrency suites green.
- **Regression Impact**: None behavioral — timing-only contention risk
  eliminated; all suites unchanged-green.

### DEFECT-002
- **Area**: State consistency (availability restoration)
- **Problem**: An agent whose stale reservation was reclaimed stayed
  `BUSY` indefinitely, permanently rejected by VB-4E outbound
  eligibility (and degrading ACD ordering).
- **Root Cause**: `AgentStaleReservationReconciler` used a bulk UPDATE
  with no availability restoration, unlike the `EXPIRED` path in
  `AcdMaintenanceScheduler`.
- **Fix**: Reconciler snapshots affected agents before the bulk release
  and restores `AVAILABLE` for agents left with zero active holds
  (repository gained `findAgentIdsWithStaleReservations`).
- **Verification**: New `AgentStaleReconcilerIntegrationTest` SR-1/2/3 on
  real PostgreSQL (restoration, idempotent double-run, mixed live+stale
  holds).
- **Regression Impact**: +3 tests; no existing test affected.

### DEFECT-003
- **Area**: Database integrity (reservation uniqueness)
- **Problem**: Nothing prevented two live reservations for the same call
  session; `releaseForCallSession` would release only one, leaking an
  agent slot forever.
- **Root Cause**: The one-hold-per-session invariant existed only
  implicitly in the single-row lookup, not as a constraint.
- **Fix**: `V40__agent_reservation_session_uniqueness.sql` — partial
  unique index `uq_agent_reservations_live_session ON
  agent_reservations (call_session_id) WHERE status <> 'RELEASED'`
  (replaces the plain session index; entity `@Index` list aligned).
- **Verification**: New `AgentReservationUniquenessIntegrationTest`
  UQ-1 (second live hold rejected, no partial state) and UQ-2 (terminal
  release permits legitimate re-reserve) on real PostgreSQL.
- **Regression Impact**: +1 migration, +2 tests; no existing test
  affected.

## 16. Defects Fixed

All three above. Production changes were limited to: keyspace constant
(+comment), reconciler restoration logic (+repository finder), V40
migration (+entity index list), and the corrected VB-3 comment. No
refactors, no new abstractions, no infrastructure, no product features.

## 17. Tests

### Unit / contract (VB-4F additions)
- `OutboundEventOrderingTest` — 7/7 (event-ordering permutations)

### PostgreSQL integration (VB-4F additions)
- `AgentStaleReconcilerIntegrationTest` — 3/3 (availability restoration,
  idempotency, mixed holds)
- `AgentReservationUniquenessIntegrationTest` — 2/2 (V40 invariant,
  re-reserve after release)

### Concurrency
- Covered by the combined race suites (§9): AcdIntegrationTest 12/12,
  AgentConnectIntegrationTest 6/6, AgentOutboundIntegrationTest 7/7,
  InboundIntegrationTest 13/13, VoiceCapacityConcurrencyIntegrationTest
  3/3, plus the new UQ-1 DB-level race guard.

### Full Regression
- `mvnw clean test`: **701 tests, 0 failures, 13 errors, 1 skipped**
- Phase suites: VB-0 (VoiceSchemaMigration 9, VoiceReservationLifecycle,
  VoiceReconciliation, VoiceTenantIsolation — green), VB-1
  (PlayfileLifecycle 3), VB-2 (DtmfLifecycle 4), VB-3
  (AgentConnect 6), VB-4A (AgentFoundation 8), VB-4B (QueueFoundation 8),
  VB-4C (AcdIntegration 12), VB-4D (Inbound 13), VB-4E
  (AgentOutbound 7) — all green.
- Errors identical to baseline (ArchitectureTest 1,
  ProvisioningSmoke 1, SecuritySlice 11); skip 1 (documented Docker
  conditional).

## 18. Baseline Comparison

| Metric | Before | After | Classification |
|---|---|---|---|
| Tests | 689 | 701 | +12 introduced by VB-4F (all green) |
| Failures | 0 | 0 | unchanged |
| Errors | 13 | 13 | existing baseline (same classes) |
| Skipped | 1 | 1 | existing baseline |
| Cycles | 2 | 2 | existing baseline |
| Migrations | V39 | V40 | introduced by VB-4F (invariant fix) |

No new failure category appeared; no defect was relabeled.

## 19. Live FreeSWITCH E2E

**NOT EXECUTED** — no FreeSWITCH instance available in the environment.
Coverage: ESL contract suites, the new event-ordering permutations, and
the real-PostgreSQL lifecycle/concurrency suites, consistent with every
prior phase.

## 20. Known Limitations

- Live FreeSWITCH E2E remains outstanding (§19).
- The 13 baseline errors (architecture cycle test, provisioning smoke,
  security slice) pre-date VB-4 and are documented in each phase report;
  they were neither caused nor fixed here (out of VB-4 scope).
- Presence remains application-level (no FreeSWITCH registration events)
  — deferred by design since VB-4A.
- `AgentAvailability` is a best-effort runtime signal owned by the
  reservation lifecycle; eligibility always re-derives from canonical
  state.

## 21. Architecture Impact

None adverse: no new package dependencies, no new cycles, no suppressed
violations, no new infrastructure. VB-4F added one migration and test
classes only, plus two narrowly-scoped production fixes (keyspace
constant, reconciler restoration).

## 22. Complete VB-4 Definition of Done

Data ✓ (isolation, ownership, cross-tenant rejection, canonical
relationships) · State ✓ (valid/invalid transitions, duplicate
answer/hangup/bridge/create idempotency) · Concurrency ✓ (agent, queue,
inbound, availability races, gateway capacity, stale reconciliation) ·
Telephony ✓ (originate failure, no-answer, caller/agent hangup, bridge
failure, event ordering, cleanup) · Database ✓ (fresh Flyway V1..V40,
constraints, FKs, indexes reviewed, transactions, advisory locking) ·
Architecture ✓ (no new cycle, no suppression, canonical single
implementations) · Regression ✓ (VB-0..VB-4E green, 701/0/13/1) ·
Scope ✓ (no dialers, no pacing, no new stacks, no feature expansion).

## 23. Final Verdict

**READY — VB-4 COMPLETE**

The VB-4 milestone (Agent + Queue + ACD + Inbound + Outbound) forms one
coherent, tenant-safe, concurrency-safe, event-driven system on the
shared voice core, verified under isolation, concurrency, duplicate
events, failure conditions, and full regression.
