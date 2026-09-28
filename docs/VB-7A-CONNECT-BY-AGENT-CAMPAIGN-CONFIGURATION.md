# VB-7A — CONNECT_BY_AGENT Campaign Configuration

Implementation record for VB-7A. The design this implements is the one audited in
`docs/VB-7A-CONNECT-BY-AGENT-CAMPAIGN-CONFIGURATION-AUDIT.md`; the audit's four
open decisions were resolved and locked before any code was written.

- Baseline: `100b19c` (VB-6F), migration head **V53**, suite **1614 / 0 F / 0 E / 1 S**, **16 modules, 0 cycles**
- Migration head after this phase: **V53 — unchanged. No migration was added.**
- Suite after this phase: **1683 / 0 F / 0 E / 1 S** — delta **+69**, reconciling exactly
  (63 in new test classes + 6 added OpenAPI cases; the 2 pre-existing
  `CampaignTypeConfigTest` cases were rewritten, not added). `ArchitectureTest`
  green, **16 modules, 0 cycles**.

---

## 1. What VB-7A is

A CONNECT_BY_AGENT **campaign** gets a real, typed, validated configuration, and
— the part the audit found missing — it gets an execution path.

Before this phase a CONNECT_BY_AGENT campaign had **no runtime at all**. Every
`PlaybackTrigger` guards on campaign type (`PlayfileExecutionService` returns
unless the type is `PLAYFILE`; `DtmfExecutionService` unless it is `DTMF`) and
the outbound dialer gates on nothing, so such a call was dialled, answered, and
then silently ignored until the VB-6E maximum-duration deadline reaped it. The
only agent connection that actually ran was the *input-driven* one: a DTMF or IVR
campaign whose **terminal action** happened to be `CONNECT_BY_AGENT`. Two
different things shared a name, and only one of them worked.

Both now work, and the distinction is explicit:

| | Campaign **type** = CONNECT_BY_AGENT | Terminal **action** = CONNECT_BY_AGENT on a DTMF/IVR campaign |
|---|---|---|
| Enters via | `ConnectByAgentExecutionService` (new, on answer) | `DtmfExecutionService` / `IvrExecutionService` (unchanged) |
| Configuration | typed `connectByAgent` object, frozen in the snapshot | none — a property of the interaction, not the campaign |
| Queue | the campaign's configured queue | none; tenant-wide VB-3 selection |
| Ring window | the campaign's configured window | platform default (60s), exactly as before |

The input-driven path is **byte-for-byte unchanged**. It has no
CONNECT_BY_AGENT type config, so it takes the unscoped branch of the same
connect boundary and behaves precisely as it did in VB-3.

---

## 2. Configuration shape

`ConnectByAgentCampaignConfig` — a permitted case of the sealed
`CampaignTypeConfig`, reached only through its exhaustive `fromTypeConfig`
dispatch. **The VB-6A placeholder (`JsonNode legacyPayload`, "any non-empty
object is accepted") is gone.** There is no `JsonNode` escape hatch.

```json
{
  "connectByAgent": {
    "queueId": "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
    "selectionStrategy": "LEAST_ACTIVE_RESERVATIONS",
    "ringDurationSeconds": 60
  }
}
```

| Field | Type | Required | Rules |
|---|---|---|---|
| `queueId` | UUID string | yes | must resolve; ownership + `ACTIVE` checked by the canonical validator |
| `selectionStrategy` | enum | yes | exactly one implemented value (§4) |
| `ringDurationSeconds` | integer | yes | `AgentRingWindow.MIN..MAX` = 10..240 |

Parsing is **strict and total**: absent, malformed, wrong-typed, out-of-range,
unsupported, or **unrecognised fields** all raise `CampaignConfigInvalidException`
→ HTTP 400 via the existing `GlobalExceptionHandler`. A stored field the runtime
does not read is configuration that looks real and does nothing, so an unknown
key is an error rather than a shrug.

### Campaign type safety

The CONNECT_BY_AGENT-only invariant is **structural, not a rule anyone has to
remember**: a `PLAYFILE` or `DTMF` payload is parsed by its own strict type,
which has no `connectByAgent` field, so agent configuration cannot leak across.
`MISSED_CALL` has no representation at all (the sealed `permits` list and the
`V14` CHECK constraint both exclude it).

No JSON Schema or `jsonb_typeof` CHECK was added to the two `type_config` JSONB
columns. The Java parse boundary already rejects cross-type payloads at write
time *and* at snapshot creation, which is where the invariant matters; a SQL
re-implementation of the same parse would be a second rule set free to drift.

---

## 3. Queue ownership — the existing domain is authoritative

The campaign stores a **reference**, exactly as it stores a `didId` or an IVR
`treeId`. It stores no agent ids, no membership, no availability, no capacity, no
queue depth. The queue (VB-4B) remains the single source of truth for all of
those.

`campaign → voice.queue` was not made reachable by widening a package. Instead a
narrow, domain-specific read seam was added — the codebase's own port-inversion
pattern, used three times already (`DtmfCollectorTrigger`, `PlaybackTrigger`,
`IvrPromptChecker`):

- **Port** `voice.agent.AgentQueueReferenceChecker` — declared in
  `voice.agent`, whose package-info already designates it *"the canonical voice
  agent API"* for campaign orchestration. **No new `@NamedInterface`, no new
  module edge, no cycle.**
- **Implementation** `voice.queue.QueueReferenceService` — a thin
  tenant-scoped read over `QueueRepository`, answering only
  `USABLE` / `NOT_ACCESSIBLE` / `NOT_ACTIVE`.

### Tenant isolation

`campaign.tenantId == queue.tenantId`, enforced server-side, and a foreign queue
is reported **exactly** like a nonexistent one — `NOT_ACCESSIBLE`, with a single
non-leaking message. The lookup is constrained by tenant, so a campaign
configuration cannot be used to probe another tenant's queue inventory. This
mirrors `validateDidReference` and the `existsByIdAndTenantId...` pattern, and
SUPER_ADMIN authority does not relax it.

Validated consistently at **create, update, activation, readiness and (via
readiness) execution creation** — never at only one endpoint.

---

## 4. Selection strategy — one rule, stated honestly

`AgentSelectionStrategy` has **exactly one** constant,
`LEAST_ACTIVE_RESERVATIONS`: the existing deterministic VB-3/VB-4C order (fewest
active reservations, then ascending agent id).

No round-robin, weighted, skills, predictive or AI routing was added — none of
it exists in the repository. Adding a placeholder constant would put a value in
the persisted contract and the REST schema that the runtime cannot honour, which
is exactly the failure mode §2 exists to eliminate. When a second genuinely
implemented rule exists it is a one-line change here; the ACD engine remains the
single interpreter either way.

**No selection algorithm was written.** The queue-scoped path delegates the whole
decision — membership, order, reservation — to `AcdService`.

---

## 5. Ring window — one authority, a per-leg budget

`AgentRingWindow` is the single authority for the bounds and the default, so the
configuration validator and the enforcing scheduler can never disagree about
what is a legal window.

| Constant | Value | Derivation |
|---|---|---|
| `DEFAULT_RING_SECONDS` | 60 | the pre-VB-7A `AgentConnectTimeoutScheduler.CONNECT_TIMEOUT_SECONDS`, unchanged |
| `MIN_RING_SECONDS` | 10 | a floor: below this a window cannot express "ring the agent" and only converts a slow pickup into `AGENT_NO_ANSWER` |
| `MAX_RING_SECONDS` | 240 | **strictly below `AcdService.RESERVATION_TTL` (5 min)** |

That last bound is the important one. The ACD hold carries its own TTL, swept by
`AcdMaintenanceScheduler`. If a campaign's ring deadline could land *after* the
hold expired, the ACD sweep would release an agent that is still legitimately
ringing — a second timeout authority for one call. Keeping the maximum under the
TTL guarantees `AgentConnectTimeoutScheduler` always fires first and stays the
**sole** enforcer of the ring window. A test asserts this against the TTL itself.

### Enforcement

`AgentConnectTimeoutScheduler` remains the only enforcer; what changed is the
source of the number. With per-campaign budgets, one global cutoff can no longer
be correct, so the sweep over-fetches legs initiated before
`now - MAX_RING_SECONDS` (safe: no budget exceeds the maximum, so nothing due is
missed) and then applies **each leg's own deadline**. A leg with no configured
budget — including every DTMF/IVR `CONNECT_BY_AGENT` action — is enforced exactly
as it was before, at 60s.

`AgentRingBudgetResolver` is the port that carries the frozen value across the
module boundary, implemented by `CampaignAgentRingBudget`, which reads the
**execution snapshot** and never the live campaign. Every unresolved case — a
non-campaign call, a missing execution, a corrupted snapshot, a throwing
resolver — degrades to the platform default rather than failing a call in
progress.

`voice → campaign` is **not** created: `voice.agent` declares the port,
`campaign` implements it.

---

## 6. Readiness vs runtime availability

The distinction the agent side already draws (`AgentAdminStatus` vs
`AgentAvailability`) is applied on the campaign side:

| Fact | Layer | Ready? |
|---|---|---|
| queue exists, not deleted, same tenant | configuration validation | **required** |
| queue `status == ACTIVE` | configuration + activation + readiness | **required** |
| queue has ≥1 active member | runtime | never a gate |
| agent `admin_status == ACTIVE` | runtime (ACD) | never a gate |
| agent `availability`, capacity, reservations | runtime (ACD/reservation) | never a gate |
| queue depth | runtime | never a gate |

A correctly configured campaign stays **ready** whenever the contact centre is
closed. Gating readiness on momentary availability would make it impossible to
save or schedule.

Three reasons are reported separately, because the operator fixes them
differently, all in the existing non-leaking style:

- `INVALID_AGENT_CONFIGURATION` — the type config does not parse
- `AGENT_QUEUE_NOT_AVAILABLE` — missing, soft-deleted, or foreign
- `AGENT_QUEUE_NOT_ACTIVE` — the tenant's own queue, administratively not active

Two new `ValidationCode`s (`QUEUE_NOT_AVAILABLE`, `QUEUE_NOT_ACTIVE`) were added
to the canonical `CampaignResourceValidationService`, which is the declared single
ownership boundary. No parallel validator was created. The method is
side-effect free and never throws.

---

## 7. Snapshot semantics

Only configuration is frozen, through the **existing** VB-6A/VB-6F mechanism:
`campaigns.type_config` (V14) → `campaign_execution_configurations.type_config`
(V44), copied verbatim by `CampaignConfigurationService` in the same transaction
as the execution row.

Frozen: `queueId`, `selectionStrategy`, `ringDurationSeconds`.

Never frozen: agent availability, admin state, busy/reservation state, queue
depth, ACD capacity, queue membership. At runtime the snapshot supplies the
queue, and the existing Queue/ACD layer supplies the current agent state.

No versioning, no `MAX(version)+1`, no history table, no legacy fallback, no
execution-time read of mutable campaign configuration. A campaign edit affects
only executions created afterwards; the PostgreSQL tests prove both snapshots
survive intact.

One accepted limitation, stated because it was a deliberate choice: the frozen
`queueId` is a bare UUID in JSONB and cannot carry a composite tenant FK the way
`V53`'s IVR tables do. The mitigation is the one used everywhere in this
codebase — re-validate the reference against the server-derived tenant at every
execution and at every dispatch.

---

## 8. Runtime flow

```
CampaignExecution
  → immutable snapshot (queueId + strategy + ring)      [frozen]
  → CallAttempt → outbound dial                         [unchanged]
  → CHANNEL_ANSWER
  → EslEventService fires every PlaybackTrigger         [unchanged]
  → PlayfileExecutionService  → type != PLAYFILE  → no-op
  → DtmfExecutionService       → type != DTMF     → no-op
  → ConnectByAgentExecutionService → type == CONNECT_BY_AGENT
      → reads the FROZEN snapshot
      → AgentConnectTrigger.connectByAgent(session, attempt, request)
          → ConnectByAgentService
              → AgentQueueAssignmentTrigger.assignToQueue(tenant, queue, session)
                  → OutboundQueueAssignmentService
                      → enrols the call in queue_waiting_calls (idempotent)
                      → AcdService.attemptAssignment(...)   ← the real authority
                          queue ACTIVE → membership-gated candidates → deterministic
                          order → atomic reservation → single-winner assignment claim
              → resolves the assigned agent + dialable endpoint
              → agent CallLeg (queue_id recorded) → originate → ring
  → agent answers → bridge                              [unchanged, VB-3 events]
  → ring window elapses → AGENT_NO_ANSWER, reservation released
```

### Why the outbound call uses a `queue_waiting_calls` row

ACD's contract is queue-centric: it assigns a **waiting call** to an agent and
enforces that the call belongs to the same queue and tenant. The alternative — a
parallel "outbound" assignment path — would need its own candidate scan and its
own reservation call, i.e. a second ACD. Instead the outbound call is enrolled in
the canonical waiting-call model, whose `call_session_id` already references
`call_sessions` and which duplicates no call attributes. The enrolment is a
reference, not a copy, and every ACD invariant then applies verbatim.

`OutboundQueueAssignmentService` is an **adapter, not a second ACD**: it
delegates every decision and adds only the enrolment.

### Single-shot, and never left WAITING

An outbound connect is a decision *now* — the callee is already on the line, so
waiting is not an option, and the documented VB-3 policy is one deterministic
attempt. The enrolled row is therefore **never left `WAITING`**: on any
non-assignment it moves straight to `REMOVED` in the same transaction. That also
keeps it out of `InboundAcdRetryScheduler`, the inbound loop that processes
`WAITING` rows and would otherwise dial an already-connected outbound call
through the inbound path.

An `ASSIGNED` row is moved to `COMPLETED`/`REMOVED` when the call finishes, for
bookkeeping accuracy; every sweep already ignores `ASSIGNED`, so nothing about
correctness depends on it.

### Backward compatibility at the seam

`AgentConnectTrigger` gained a `default` overload
`connectByAgent(sessionId, attemptId, AgentConnectRequest)`. The existing
two-argument method is untouched and remains the primary contract, so the
meaning of `CONNECT_BY_AGENT` as an **action** is unchanged. An unscoped request
takes the original tenant-wide scan byte-for-byte.

---

## 9. Tests

New tests, by area:

| Area | Class | Covers |
|---|---|---|
| A. typed configuration | `ConnectByAgentCampaignConfigTest` | valid/round-trip, missing & malformed queue, bad strategy, out-of-range & non-integer ring, unknown field, cross-type rejection, DTMF/IVR paths unaffected |
| A. placeholder replacement | `CampaignTypeConfigTest` (updated) | the two tests that encoded "any object accepted" now assert the typed contract |
| B. tenant isolation | `CampaignQueueResourceValidationTest` | same-tenant accepted, foreign ≡ nonexistent, nulls, no queue layer |
| C. lifecycle / readiness | `CampaignQueueResourceValidationTest` + `ConnectByAgentConfigPostgresIntegrationTest` | INACTIVE and DISABLED block, availability never blocks, side-effect free |
| C. readiness reasons | `ConnectByAgentConfigPostgresIntegrationTest` | ACTIVE ready, INACTIVE → `AGENT_QUEUE_NOT_ACTIVE`, foreign → `AGENT_QUEUE_NOT_AVAILABLE`, invalid config → `INVALID_AGENT_CONFIGURATION`, disabled queue blocks execution creation |
| D. snapshot | `ConnectByAgentConfigPostgresIntegrationTest` | JSONB round trip, freeze, edit-does-not-mutate, runtime reads the snapshot |
| E. runtime | `ConnectByAgentExecutionServiceTest` | reaches the agent runtime, **not silently ignored**, other types not diverted, idempotent, deterministic failures |
| E. ACD delegation | `OutboundQueueAssignmentServiceTest` | decision delegated, enrolment, reuse, never left WAITING, ring bound vs ACD TTL |
| Ring window | `AgentConnectTimeoutSchedulerTest` | default unchanged, per-leg deadlines both directions, resolver degradation, bounded pre-filter |
| OpenAPI | `CampaignOpenApiContractTest` (extended) | queue/strategy/ring documented, availability-vs-administrative distinction, example parses, no new endpoint, 400 + bearer security unchanged |

PostgreSQL-backed: `ConnectByAgentConfigPostgresIntegrationTest` runs against a
real Testcontainers PostgreSQL with Flyway V1..V53 and proves the JSONB
round trip, the snapshot freeze, the tenant-scoped SQL, and the queue
administrative lifecycle. It is not a mock-only test.

**No existing test was weakened, disabled, skipped or deleted.** The only edits to
existing tests are: the two `CampaignTypeConfigTest` cases that asserted the
placeholder contract (which OD-1 explicitly replaces); additive OpenAPI cases;
and one fixture adaptation in `TtsGovernancePostgresIntegrationTest`, whose
campaign is a CONNECT_BY_AGENT one and therefore had to be given a real, valid
configuration plus a queue for the tenant to own. That suite's assertions — the
TTS governance rules — are untouched.

Suite: **1614 → 1683**, `failures = 0`, `errors = 0`, `skipped = 1` (the
pre-existing `ObdApplicationTests.contextLoads`).

---

## 10. Architecture

- **16 modules — unchanged. 0 cycles.** `ArchitectureTest` passes.
- **No new `@NamedInterface`.** Both new ports are declared in the already-exposed
  `voice.agent`.
- **New module edges: none.** `campaign → voice.agent` already existed.
- **`voice` still does not depend on `campaign`.** The two ports invert that
  dependency without creating it.
- Constructor arity changes on `CampaignResourceValidationService` and
  `ConnectByAgentService` were absorbed by **backward-compatible secondary
  constructors**, so ~20 unrelated existing tests compile and behave unchanged —
  the same approach VB-6F used for `CampaignConfigurationService`.

---

## 11. Database

**No migration. Migration head is still V53.**

The configuration is JSONB data, not a column. `campaigns.type_config` (V14:73)
and `campaign_execution_configurations.type_config` (V44:44) already exist, and
this is the exact route VB-6F's IVR configuration used.

No `campaign_agent_assignments` table: `queue_memberships` (V37) already *is* the
agent-grouping relationship — tenant-scoped, status-bearing, indexed and tested —
and a bare `queueId` in JSONB needs no DDL. Adding a relational assignment table
would duplicate that primitive and invite a campaign-configuration versioning
discussion the phase rules forbid.

One conditional `UPDATE` was added to `QueueWaitingCallRepository`
(`markAssignedTerminalForSession`) to close out the enrolled row. No DDL.

> Flagged for the next phase, not actioned here: **VB-7B (MISSED_CALL) will
> require a `V54` to extend the `ck_campaigns` CHECK constraint.**

---

## 12. Known limitations

All repository-supported, none speculative:

1. **One selection strategy exists.** `selectionStrategy` is required and
   validated, but only `LEAST_ACTIVE_RESERVATIONS` is implemented. Adding an
   unsupported value would be configuration that lies.
2. **No dialed-DID-scoped agent filtering.** `findEligibleOrdered` and ACD filter
   on tenant (and, for ACD, queue membership) but not on the campaign's DID, so
   an agent whose endpoint is bound to a different number can still be bridged.
   This is a pre-existing VB-3/VB-4 property and is out of scope.
3. **One deterministic attempt, no fallback to another agent.** The documented
   VB-3 policy, preserved deliberately. A campaign that gets `AGENT_BUSY` fails
   that call rather than trying the next member.
4. **The frozen `queueId` is a bare UUID**, with the snapshot-time re-validation
   described in §7 rather than a composite tenant FK.
5. **An `ASSIGNED` waiting-call row is only cleaned on a known terminal event.**
   It is inert for all three sweeps regardless, so this is accuracy, not
   correctness.
6. **The ring window and `Queue.maxWaitSeconds` are deliberately independent.**
   The latter bounds how long a call waits in a queue (inbound, VB-4B); the
   former bounds how long an already-answered outbound call rings one reserved
   agent.
7. **No AI agent, no predictive/progressive/preview dialing, no webhooks, no
   report privacy, no MISSED_CALL, no TTS synthesis, no reusable-IVR changes** —
   all explicitly out of scope.

---

## 13. Regression

Verified unchanged and green: PLAYFILE (media URI, playback, teardown), DTMF
(single-level and IVR), and every VB-6C / VB-6D / VB-6E / VB-6F suite — daily
dial limit, retry and safety policy, PLAYFILE + common calling configuration,
and the IVR trees with multi-level DTMF. The DTMF/IVR `CONNECT_BY_AGENT`
terminal action takes the unscoped branch of the same connect boundary and is
covered by the pre-existing `DtmfAgentActionTest`.

Full-suite result: see §9 of the implementation report.
