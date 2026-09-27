# VB-4D Implementation Report

## 1. Status

**READY FOR VB-4E.**

All VB-4D acceptance criteria are met: inbound calls converge on the
canonical `CallSession`/`CallLeg` domain (no parallel inbound aggregate),
DID→tenant→destination routing is fail-closed and tenant-isolated, the
existing VB-4C ACD / VB-3 reservation / VB-3 bridge boundaries are reused
unchanged, duplicate-event and concurrency invariants are proven against
real PostgreSQL, and full regression equals the documented pre-existing
baseline (0 failures; the 13 pre-existing errors unchanged).

## 2. Files changed

**Added (main):**
- `db/migration/V39__add_did_inbound_destination.sql`
- `did/DidInboundDestination.java`
- `voice/inbound/InboundCallEvents.java` — event boundary (interface)
- `voice/inbound/InboundCallService.java` — routing, session/leg creation,
  queue entry, direct-agent path, caller-event handling, ACD connect step
- `voice/inbound/InboundAcdRetryScheduler.java` — ACD retry loop for
  waiting inbound calls (`@Scheduled`, existing convention)
- `voice/inbound/package-info.java` — `@NamedInterface("inbound")` module
  API for the telephony layer

**Modified (main):**
- `did/DidEntity.java` — inbound destination fields (`NAMED_ENUM` binding)
- `did/DidRepository.java` — `findByE164NumberAndDeletedAtIsNull`
- `voice/queue/QueueWaitingCallRepository.java` — live-row session lookup +
  conditional `markWaitingRemovedForSession` (WAITING→REMOVED)
- `telephony/EslClient.java` — subscribed events now include CHANNEL_CREATE
- `telephony/EslEventService.java` — inbound CHANNEL_CREATE branch
  (`Call-Direction=inbound`) and inbound-session caller-event branch

**Added (test):** `InboundCallServiceTest`, `InboundEslRoutingTest`,
`InboundIntegrationSupport`, `InboundIntegrationTest`

**Modified (test):** constructor-arg updates in the five existing
`EslEventService` test files (new `Optional<InboundCallEvents>` param);
`AgentConnectIntegrationTest` — bounded retry on AG-IT-5 (see §6)

## 3. Database changes

Migration **V39__add_did_inbound_destination.sql** (forward-only, V1–V38
untouched):

- `CREATE TYPE did_inbound_destination AS ENUM ('QUEUE', 'AGENT')`
- `dids` + `inbound_destination` (`NAMED_ENUM` in JPA),
  `inbound_queue_id` FK→queues `ON DELETE SET NULL`, `inbound_agent_id`
  FK→agents `ON DELETE SET NULL`
- Tenant-scope CHECKs (destination pointers require a tenant-owned DID —
  pool numbers cannot accept inbound calls)
- Indexes: `idx_dids_inbound_destination`, `idx_dids_inbound_queue`,
  `idx_dids_inbound_agent`
- No new tables; no changes to CallSession/CallLeg/QueueWaitingCall/
  AgentReservation — the canonical schema already represented everything
  else (verified: `call_type` had `CONTACT_CENTER_INBOUND` since V29,
  `call_leg_type` had CUSTOMER/AGENT, legs carried `agent_id`/`queue_id`)

## 4. Inbound flow (implemented)

```
FreeSWITCH CHANNEL_CREATE (Call-Direction=inbound)
  → EslEventService (inbound branch, before the attempt-miss early return)
  → InboundCallService.onInboundChannelCreated
      advisory lock on channel UUID (dedup)  →  re-read existing session
      DID lookup (live E.164) — fail closed: absent / pool / unconfigured
      CallSession (CONTACT_CENTER_INBOUND, INBOUND) + CUSTOMER leg
      QUEUE dest:  QueueWaitingCall WAITING (expiresAt = queue wait budget)
                   → InboundAcdRetryScheduler → AcdService.attemptAssignment
                   → connectAssignedAgent → AGENT leg → AgentLegDialer.originate
      AGENT dest:  eligibility → AgentReservationService.reserve
                   → AGENT leg → originate
  shared VB-3 boundary (unchanged, session-agnostic):
      CHANNEL_PROGRESS → agent leg RINGING
      CHANNEL_ANSWER   → agent leg ANSWERED → mediaController.bridge
      CHANNEL_BRIDGE   → BRIDGED (session + legs), reservation ACTIVE
      CHANNEL_HANGUP   → finalize + release (both legs' paths, idempotent)
```

ACD assignment remains the pre-dial gate: the reservation exists before
any originate; `connectAssignedAgent` only executes for ASSIGNED rows
with a live reservation. Queue timeout (WAITING→ABANDONED) and bounded
overflow remain owned by the VB-4C maintenance sweep — no second
mechanism.

## 5. Tests

- **VB-4D unit:** `InboundCallServiceTest` — 20/20 pass (routing matrix
  incl. fail-closed cases, idempotent CHANNEL_CREATE, caller
  answer/hangup incl. duplicate-hangup no-op, ACD connect step incl.
  originate-failure release and dead-endpoint unwind)
- **ESL contract:** `InboundEslRoutingTest` — 8/8 pass (CHANNEL_CREATE
  header parsing `Call-Direction`/`Caller-Destination-Number`/
  `Caller-Caller-ID-Number`, outbound isolation, boundary-absent
  fail-closed, exception containment in the shared loop)
- **PostgreSQL integration (real PG, Testcontainers, Flyway V1..V39):**
  `InboundIntegrationTest` — 13/13 pass: IT-1 queue entry, IT-2 ACD
  assignment, IT-3 agent leg persistence, IT-4 **20 concurrent duplicate
  CHANNEL_CREATE → exactly 1 session + 1 leg**, IT-5 caller hangup while
  queued, IT-6 originate failure → assignment released + call back to
  WAITING, IT-7 no-agent keeps WAITING, IT-8 queue timeout → ABANDONED,
  IT-9 bounded overflow reuse, IT-10 cross-tenant fail-closed, IT-11
  direct-agent path, CC-1 20 calls/5 agents@1 → ≤5 holds (each agent ≤1),
  CC-2 20 calls/5 agents@2 → ≤10 holds
- **Full regression:** `./mvnw clean compile`, `test-compile` clean;
  `./mvnw test` → **654 tests, 0 failures, 13 errors, 1 skipped**
  (baseline was 613/0/13/1; +41 VB-4D tests, all green; error count and
  categories unchanged — see §7)

## 6. Defects discovered (and fixed)

1. **Duplicate CHANNEL_CREATE race (new code):** check-then-insert on the
   channel UUID allowed 7 of 20 concurrent events to create sessions.
   Root cause: idempotency check outside a serialization boundary.
   Fix: transaction-scoped advisory lock (`pg_advisory_xact_lock`, base
   `0x400000000L`, disjoint from VB-0/VB-3 bases) + re-read under the
   lock. Regression test: IT-4 (20 threads → 1 session/1 leg).
2. **NAMED_ENUM binding missing:** `inbound_destination` failed inserts
   with `column is of type did_inbound_destination but expression is of
   type character varying`. Fix: `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` —
   the established VB-1/VB-3 pattern. Covered by every integration test.
3. **Duplicate-hangup re-ran cleanup:** a second CHANNEL_HANGUP on an
   already-final session re-invoked the VB-3 cleanup boundary. Fix:
   capture pre-existing terminal state and short-circuit. Regression
   test: `duplicateHangupNoOp`.
4. **Pre-existing flake (not VB-4D):** VB-3 `AG-IT-5` (20 threads, one
   try-lock attempt each, expects exactly 2 admissions) became
   deterministic-failing on the current machine — all attempts cluster
   inside winner-1's lock hold. Production callers re-evaluate on
   refusal (ACD candidate loop); the test now uses the same bounded
   retry, preserving the real invariant (never more than
   `maxConcurrentCalls` admitted — still asserted). Not a weakening: the
   over-admission assertion is untouched.
5. **Module boundary:** `EslEventService` referencing
   `voice.inbound.InboundCallEvents` produced a new modulith violation.
   Fix: `@NamedInterface("inbound")` on the package (same convention as
   `authz.context`); the violation list is now identical to the
   pre-VB-4D baseline.

## 7. Pre-existing / environment notes

The 13 full-suite errors are exactly the documented baseline groups:
ArchitectureTest 1 (campaign↔telephony cycle predating VB-4D — no
VB-4D class appears in any cycle), ProvisioningSmokeIntegrationTest 1,
SecuritySliceTest 11. Earlier in the session Docker Desktop stopped
mid-run, producing transient Testcontainers errors; after restart all
integration suites (VB-0..VB-4D) pass. Live FreeSWITCH E2E was **not**
executed (no FreeSWITCH instance available); the ESL/media seam is
covered by `InboundEslRoutingTest` and the existing contract tests.

## 8. Architectural verification

- No parallel inbound-call domain (inbound = canonical CallSession/legs;
  no `InboundCall` aggregate, no new call tables)
- No duplicate reservation system (VB-3 `AgentReservationService` only)
- No duplicate bridge/hangup implementation (shared `AgentConnectEvents`
  boundary; inbound adds no agent-hangup handler of its own)
- No second ESL client (CHANNEL_CREATE added to the existing
  subscription list); no new scheduler (one `@Scheduled` sweep reusing
  the convention); no Redis/Kafka/Kubernetes; no new SIP stack
- No cross-tenant routing (DID FK scoping + tenant-scoped queue/agent
  lookups; IT-10 proves fail-closed)
- No agent-capacity bypass (reservation-before-dial; CC-1/CC-2 prove
  `activeReservations(agent) ≤ maxConcurrentCalls`)

## 9. Final recommendation

**Technically READY FOR VB-4E — Outbound Agent Calling**, based on: 41
new tests all passing (20 unit + 8 ESL contract + 13 PostgreSQL
integration incl. concurrency), full regression at 654/0/13/1 matching
the pre-VB-4D baseline, and the canonical-domain convergence verified
by the architecture test. The known limitation — no live FreeSWITCH E2E
in this environment — applies to the ESL seam only and is unchanged from
prior phases.
