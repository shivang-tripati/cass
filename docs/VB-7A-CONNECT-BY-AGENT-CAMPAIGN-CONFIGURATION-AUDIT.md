# VB-7A — CONNECT_BY_AGENT Campaign Configuration Audit

## 1. Audit Status

**AUDIT COMPLETE**

| Item | Value |
|---|---|
| Baseline commit | `100b19c` (VB-6F: reusable IVR trees and multi-level DTMF) |
| Branch | `main` |
| Migration head | **V53** (`V53__ivr_trees.sql`); 52 migration files total; next free is `V54` |
| Test baseline | **Tests run: 1614, Failures: 0, Errors: 0, Skipped: 1** |
| Pre-existing failures | **None.** `BUILD SUCCESS` |
| Skipped test | `ObdApplicationTests.contextLoads` (1) — pre-existing, needs a Testcontainers harness; skipped at VB-6F baseline too |
| Architecture baseline | `ArchitectureTest.modularBoundariesAreIntact` — **PASS** (`ApplicationModules.verify()`, 17.47 s) |
| Module count | **16** (15 sub-packages under `com.shivang.obd` + the root package) |
| Architecture cycles | **0** |
| Runtime | Spring Boot **4.1.0**, Java **17**, Jackson 3 (`tools.jackson`), Modulith **2.1.0** |
| Working tree | 2 user-owned modified files, preserved untouched, not staged, not committed |

Build command used: `cd backend; .\mvnw.cmd -o clean test` (full `clean`).

User-owned uncommitted work identified and **excluded** from this audit:

- `docs/campaign-readiness.md` (17 lines changed)
- `backend/docs/future-hardening.md` (28 lines added)

Neither file was read for audit content, modified, staged, or included in any change.

Recent phase commits for context:

```
100b19c  VB-6F: reusable IVR trees and multi-level DTMF
544b260  VB-6E: PLAYFILE production execution + common calling configuration
0dfe308  VB-6D.3: campaign daily-attempt safety and final retry integration
a0c6694  VB-6D: establish campaign retry safety policy
619deac  VB-6C.2 + VB-6C.3: complete the Voice Blast daily dial limit
2ade4bc  Initial commit: multi-tenant OBD (outbound dialer) platform
```

---

## 2. Scope

### Audited

- The existing VB-3/VB-4 agent, queue and ACD foundation in full (`voice.agent`, `voice.queue`, `voice.acd`)
- The current CONNECT_BY_AGENT campaign configuration: `ConnectByAgentCampaignConfig`, `CampaignTypeConfig` dispatch, `CampaignService` write-time rules
- The `CampaignConfigurationSnapshot` freeze model and `CampaignConfigurationService`
- `CampaignResourceValidationService` and `CampaignReadinessService` as the canonical validation/readiness authorities
- Campaign lifecycle and the DRAFT → SCHEDULED activation gate
- Campaign REST surface and the generated-OpenAPI contract tests
- Database schema for `campaigns`, `campaign_execution_configurations`, `agents`, `agent_endpoints`, `agent_reservations`, `queues`, `queue_memberships`, `queue_waiting_calls`
- Modulith module exposure (`@NamedInterface`) and dependency direction
- The three existing "input → agent action" paths (DTMF, IVR, campaign type)
- Existing test coverage for all of the above

### Explicitly not audited

- AI agents, AI providers, LLM/voice-AI integration (none exist; out of scope by brief)
- MISSED_CALL (not implemented; `campaigns.campaign_type` CHECK excludes it)
- Webhook / integration implementation (`integrationConfig` is persisted but read by no code)
- Media, PLAYFILE, DTMF, IVR configuration changes
- Telephony, FreeSWITCH, ESL behaviour
- Dialing strategies (predictive/progressive/preview)
- Any runtime implementation of agent connect or queue routing

---

## 3. Existing Architecture

### Module graph (actual, from `@NamedInterface` declarations)

The only `@NamedInterface` declarations in the application are:

```
authz\context   -> 'context'
authz\home      -> 'home'
ivr\dto         -> 'dto'
voice\agent     -> 'agent'
voice\call      -> 'call'
voice\capacity  -> 'capacity'
voice\dtmf      -> 'dtmf'
voice\eligibility -> 'eligibility'
voice\inbound   -> 'inbound'
voice\ivr       -> 'ivr'
voice\media     -> 'media'
voice\outbound  -> 'outbound'
voice\routing   -> 'routing'
```

Application modules: `account`, `audio`, `authz`, `campaign`, `common`, `contact`, `did`, `identity`, `ivr`, `reseller`, `security`, `telephony`, `tenant`, `tts`, `voice`, plus the root package — **16 modules, 0 cycles.**

### `voice` sub-package exposure

| Sub-package | Exposed | Note |
|---|---|---|
| `voice.agent` | **`@NamedInterface("agent")`** | canonical campaign-facing agent API |
| `voice.call`, `voice.capacity`, `voice.dtmf`, `voice.eligibility`, `voice.inbound`, `voice.ivr`, `voice.media`, `voice.outbound`, `voice.routing` | exposed | |
| `voice.acd` | **not exposed** | no `package-info.java` |
| `voice.queue` | **not exposed** | no `package-info.java` |
| `voice.endpoint` | **not exposed** | no `package-info.java` |

### Dependency direction relevant to VB-7A

`voice.agent/package-info.java` states its purpose explicitly:

> *Named interface: campaign agent-connect orchestration consumes
> `AgentReservationService`, agent repositories and the dialer SPI
> as the canonical voice agent API.*

Verified: `campaign` already imports ten `voice.agent` types —
`Agent`, `AgentEligibility`, `AgentLegDialer`, `AgentEndpointEntity`,
`AgentReasons`, `AgentReservation`, `AgentReservationService`,
`AgentRepository`, `AgentEndpointRepository`, `ReleaseReasons`.

**Consequence: `campaign → voice.agent` is an existing, sanctioned edge, and
`AgentRepository` is already reachable from `campaign` with zero architectural
change. Referencing `voice.queue.Queue` / `QueueRepository` is the only thing
that would require new exposure.**

### Direction checks (no reverse dependency)

| Package | imports `campaign`? |
|---|---|
| `voice.agent` | **NONE** |
| `voice.queue` | **NONE** |
| `voice.acd` | **NONE** |

So `campaign → voice.queue` would be a new edge in an already-legal direction,
**provided** `voice.queue` is exposed. It would not create a cycle.

### Where the agent runtime actually lives

`ConnectByAgentService` is in `com.shivang.obd.campaign` — **the outbound
agent-connect runtime is owned by the `campaign` module, not by `voice`.** This
is why VB-3 declared `AgentConnectTrigger` as a port in `voice.agent`: the
boundary type belongs to the voice domain, the implementation belongs to the
orchestrating module. `DtmfExecutionService` and `IvrExecutionService` both
consume it via `ObjectProvider<AgentConnectTrigger>`.

### The port-inversion precedent (VB-6F, and twice before it)

`ivr/IvrPromptChecker` is the codebase's established answer to "a consumer module
needs a provider module's authority": **the consumer declares the port, the
provider implements it.** Its own javadoc names the two prior instances
(`DtmfCollectorTrigger`, `PlaybackTrigger`). VB-7A must follow this, or must not
need it at all (see §20).

---

## 4. Existing Agent Foundation

This is not a stub. VB-3/VB-4 built a complete, deterministic, tenant-scoped
agent/queue/ACD foundation. It is the authoritative evidence for VB-7A.

### Agent representation

`voice/agent/Agent.java` — `@Entity`, table `agents`, extends `AuditableEntity`
(soft delete via `deleted_at`).

| Column | Type | Notes |
|---|---|---|
| `tenant_id` | UUID NOT NULL → `tenants(id)` | **tenant-scoped** |
| `display_name` | VARCHAR(120) NOT NULL | |
| `admin_status` | `agent_admin_status` NOT NULL DEFAULT `ACTIVE` | |
| `availability` | `agent_availability` NOT NULL DEFAULT `OFFLINE` | |
| `max_concurrent_calls` | INT NOT NULL, `CHECK (> 0)` | |
| `user_id` | UUID (nullable) | optional user linkage |

Indexes: `idx_agents_tenant (tenant_id, deleted_at)`,
`idx_agents_selection (tenant_id, admin_status, availability, deleted_at)`.

### Lifecycle — the two axes are deliberately separate

`AgentAdminStatus` — *"is this agent allowed to receive CONNECT_BY_AGENT calls at all?"*

| Value | Meaning |
|---|---|
| `ACTIVE` | allowed to receive calls |
| `SUSPENDED` | administratively paused — not selectable until re-activated |
| `DISABLED` | permanently barred |

`AgentAvailability` — *"can this agent take a call right now?"*

| Value | Meaning |
|---|---|
| `AVAILABLE` | endpoint registered/idle, eligible for selection |
| `BUSY` | engaged (active reservation) |
| `OFFLINE` | endpoint not reachable |

The source javadoc states the rationale: *"Conflating the two would make an
operational pause (BUSY) indistinguishable from a business decision
(SUSPENDED)."* **This is exactly the configuration-validity ≠ runtime-availability
distinction the brief asks about, and the codebase already has the right axis
for it.**

### Activation / deactivation invariants that already exist

From `AgentDirectoryService.updateStatus` (the `PUT /agents/{id}/status` handler):

1. Setting the current status is **idempotent** (returns unchanged).
2. `DISABLED` is **terminal** — any further status change throws
   `ConflictException`.
3. Legal transitions: `ACTIVE ↔ SUSPENDED`, and anything → `DISABLED`.
   Everything else throws a business error.
4. Deactivation cascades: setting a non-`ACTIVE` status while `availability ==
   AVAILABLE` forces `availability = OFFLINE`.

From `updatePresence`: only `AVAILABLE` and `OFFLINE` are agent-declarable.
`BUSY` is owned by the reservation lifecycle and **cannot** be set through the
API. A suspended/disabled agent **cannot declare itself available.**

### Answers to the Part 2 questions, grounded in code

| # | Question | Answer | Evidence |
|---|---|---|---|
| 1 | How is an agent represented? | `Agent` JPA entity, table `agents` | `Agent.java` |
| 2 | What identifies an agent? | `id` (UUID PK) + `tenantId` | `Agent.java` |
| 3 | Tenant-scoped? | **Yes** — `tenant_id` NOT NULL, FK to `tenants(id)` | `V36:30` |
| 4 | Activation representation? | `admin_status` ∈ {ACTIVE, SUSPENDED, DISABLED} | `AgentAdminStatus` |
| 5 | Full lifecycle? | Yes, the three above | `AgentAdminStatus` |
| 6 | Soft-deleted? | **Yes** — `deleted_at` via `AuditableEntity`; all repository lookups filter it | `AgentRepository` |
| 7 | Can an inactive agent remain assigned? | **Not currently defined by the codebase** — no campaign↔agent assignment exists at all | §5 |
| 8 | What happens when an assigned agent becomes inactive? | **Not currently defined** — same reason | §5 |
| 9 | Existing enforcement? | Yes, for the *agent's own* status transitions only. Nothing cross-resource. | `AgentDirectoryService:174-205` |
| 10 | Availability ≠ activation? | **Yes, explicitly** — two columns, two enums, documented rationale | `AgentAdminStatus` javadoc |
| 11 | Multiple queues per agent? | **Yes** — `queue_memberships(agent_id, queue_id)`, unique per pair, multiple rows per agent allowed | `V37:83` |
| 12 | Agent↔queue relationship? | **Yes** — `queue_memberships` with `status` ∈ {ACTIVE, INACTIVE} | `QueueMembership` |
| 13 | Queues tenant-scoped? | **Yes** — `tenant_id` NOT NULL | `V37:32` |
| 14 | Queue memberships mutable? | **Yes** — status is mutable; soft delete supported | `QueueMemberStatus` |
| 15 | Membership lifecycle? | `ACTIVE` / `INACTIVE`; no terminal state | `QueueMemberStatus` |
| 16 | Existing selection strategy? | **Yes** — one, documented, deterministic (below) | `AgentRepository:32-52` |
| 17 | active/available/busy/offline/suspended distinguished? | **Yes** — 3 admin + 3 availability values, fully separated | both enums |
| 18 | Which are config vs execution? | `admin_status` = **configuration**; `availability` = **execution/runtime** | §12 |

### The existing selection rule (`AgentRepository.findEligibleOrdered`)

```sql
select * from agents a
where a.tenant_id = :tenantId
and a.deleted_at is null
and a.admin_status = 'ACTIVE'
and a.availability = 'AVAILABLE'
order by
  (select count(*) from agent_reservations r
   where r.agent_id = a.id and r.status <> 'RELEASED'
   and r.deleted_at is null) asc,
  a.id asc
```

Contract, quoted from `ConnectByAgentService`:

> *eligible = tenant-scoped, ACTIVE admin status, AVAILABLE runtime
> availability, enabled dialable endpoint; ordered by least active reservations,
> then stable agent id — "same state always selects the same agent".*

Scan bound: `SELECTION_SCAN_LIMIT = 20`.

**This selection is tenant-wide. It has no campaign, queue, DID, or campaign-type
filter of any kind.**

### Reservations (the concurrency authority)

`AgentReservationStatus`: `RESERVED → ACTIVE → RELEASED`, terminal at
`RELEASED`, atomic conditional UPDATEs. `AgentReservationService` uses an
advisory lock + re-read + conditional capacity check. Uniqueness per session is
enforced by `V40__agent_reservation_session_uniqueness.sql`. This is the
**only** capacity system for agents and must not be duplicated.

### Queue and ACD

`queues` (V37): `tenant_id`, `name` (unique per tenant among live rows), `status`
∈ {ACTIVE, INACTIVE, DISABLED}, `max_waiting_calls`, `max_wait_seconds`,
`overflow_enabled`, `overflow_queue_id` → `queues(id)`.

`queue_memberships`: `(queue_id, agent_id, tenant_id, status)`, partial unique
index per queue+agent, four indexes including `idx_queue_memberships_agent
(agent_id, tenant_id, deleted_at)`.

`queue_waiting_calls`: `call_session_id` → `call_sessions(id)`, status enum,
`entered_at`, `expires_at`; one live `WAITING` row per session.

`AcdService` (VB-4C) is a **complete, deterministic, queue-based ACD engine**,
documented as: *"ACD determines and reserves an agent for a waiting call. It
performs NO telephony."* Flow: queue eligibility (exists / same tenant /
ACTIVE) → waiting-call eligibility → candidate scan over ACTIVE memberships →
deterministic order (**the same rule as VB-3**) → atomic reservation → assignment
claim. Plus `AcdOverflowService`, `AcdMaintenanceScheduler`, and
`AcdReasons`/`AcdResult`.

**AcdService's public API requires a `waitingCallId`:**

```java
public AcdResult attemptAssignment(UUID tenantId, UUID queueId,
                                   UUID waitingCallId,
                                   List<AcdResult.RejectedCandidate> rejectedSink)
```

An outbound campaign call has no `queue_waiting_calls` row. Reusing `AcdService`
for outbound would require enqueuing every outbound call as a waiting call.

### Agent reason vocabulary (already complete)

`AgentReasons`: `AGENT_SELECTED`, `AGENT_UNAVAILABLE`, `AGENT_NOT_AVAILABLE`,
`AGENT_BUSY`, `AGENT_ENDPOINT_INVALID`, `AGENT_TENANT_MISMATCH`,
`AGENT_CONFIG_INVALID`, `AGENT_RESERVATION_LOST`, `AGENT_ORIGINATE_FAILED`.

`AgentEligibility` is a record with `selected(agent, endpoint)` /
`rejected(reasonCode)` / `isSelected()`.

Agent endpoint statuses add `AGENT_OFFLINE`, `AGENT_AVAILABLE`,
`AGENT_SUSPENDED`, `AGENT_DISABLED`, `AGENT_ENDPOINT_UNSUPPORTED`,
`AGENT_AT_CAPACITY`, `AGENT_PRESENT_NOT_AVAILABLE`, `AGENT_NO_ANSWER`.

### REST surface (agent + queue, both complete)

`AgentDirectoryController` — 17 endpoints: create, get, list, update,
`PUT /{id}/status`, `PUT /{id}/presence`, `GET /{id}/availability`, endpoint
CRUD + enable/disable, `GET /{id}/calls/active`, `GET /{id}/calls`,
`POST /{id}/calls`.

`QueueDirectoryService`/`Controller` — capabilities `QUEUE_VIEW`,
`QUEUE_MANAGE`, same `AccessCheck.forTenant/forReseller/platformWide` pattern.

Capabilities in the platform: `CAMPAIGN_VIEW`, `CAMPAIGN_MANAGE`,
`CAMPAIGN_EXECUTE`, `AGENT_VIEW`, `AGENT_MANAGE`, `QUEUE_VIEW`, `QUEUE_MANAGE`,
`IVR_VIEW`, `IVR_MANAGE`.

**There is no `DELETE /agents/{id}`.** An agent cannot be deleted through the
API; the terminal administrative state is `DISABLED`, plus soft delete via
`deleted_at`. See §10.

---

## 5. Existing CONNECT_BY_AGENT Configuration

### The headline finding

`ConnectByAgentCampaignConfig` (VB-6A) carries **no type-specific configuration
at all.** Its entire body:

```java
public record ConnectByAgentCampaignConfig(
        JsonNode legacyPayload,
        ConfigSchemaVersion schemaVersion) implements CampaignTypeConfig {

    public ConnectByAgentCampaignConfig {
        Objects.requireNonNull(legacyPayload, "legacyPayload");
        if (legacyPayload.isNull() || !legacyPayload.isObject() || legacyPayload.isEmpty()) {
            throw new CampaignConfigInvalidException(
                    "CONNECT_BY_AGENT requires a non-empty typeConfig object");
        }
        ...
    }
```

Its own javadoc:

> *CONNECT_BY_AGENT currently carries no type-specific JSON configuration:
> agent selection, reservation and bridging are service-driven (deterministic
> eligibility scan), and the write-time contract only requires a **present**
> non-empty typeConfig object (historical decision — kept for compatibility).
> This typed representation therefore preserves that contract exactly: a
> non-empty object (**any shape**) is accepted as the legacy payload.*
> **No queue or agent-routing configuration is invented in this phase.**

`toJson()` round-trips the payload untouched.

**Any JSON object is a valid CONNECT_BY_AGENT configuration today.** There is no
agent reference, no queue reference, no strategy, no ring duration, no fallback,
no action mapping.

### Write-time rules in `CampaignService`

```java
if (type != CampaignType.CONNECT_BY_AGENT && mode == null) {
    throw business(type + " campaigns require content (audio or TTS).");
}
...
boolean required = type == CampaignType.DTMF || type == CampaignType.CONNECT_BY_AGENT;
```

So CONNECT_BY_AGENT: **does not require content mode**, and **does require a
non-empty typeConfig object**. The javadoc at L369-370 flags the requirement as
*"(product-defined later)"* — the payload was required as a placeholder, and the
shape was never specified.

### Existing coverage of this contract

`CampaignTypeConfigTest` asserts it deliberately:

- `connectAcceptsLegacyPayload` — `{"legacy": {"anything": true}}` is accepted
  **and round-trips untouched**.
- `connectRejectsAbsentPayload` — `null` is rejected.

These two tests encode the "any shape" contract. **VB-7A's typed configuration
will break them.** That is a genuine, documented compatibility contract, not an
accident — see Open Decision **OD-1**.

### Everything else about CONNECT_BY_AGENT

| Aspect | State today |
|---|---|
| `CampaignType.CONNECT_BY_AGENT` | exists; javadoc: *"Places the call and bridges it to an **agent/queue**."* |
| Campaign DTO fields | **none** — no campaign DTO carries an agent or queue reference |
| REST representation | none beyond the opaque `typeConfig` JSON |
| `CampaignResourceValidationService` | **DID / AUDIO / TTS only** — no agent, no queue |
| `CampaignReadinessService` | 14 reason codes, **none** agent/queue-related |
| Snapshot | `type_config` JSONB is frozen verbatim, but the payload means nothing |
| Database | `campaigns` has **no** `agent_id` / `queue_id` column; `V36`/`V37` tables have **no** `campaign_id` column. **No assignment relationship exists.** |
| Runtime | `ConnectByAgentService` selects tenant-wide, queue-blind |

### The gap matrix

| Capability | Existing | Location | Complete? | Notes |
|---|---|---|---|---|
| Agent ID / reference | **No** | — | ✗ | nothing carries it |
| Queue ID / reference | **No** | — | ✗ | nothing carries it |
| Agent selection config | **No** | — | ✗ | selection is service-driven only |
| Selection strategy | **Partial** | `AgentRepository.findEligibleOrdered` | ⚠ | one hard-coded strategy; not configurable, not campaign-scoped |
| Ring duration | **No** | `AgentConnectTimeoutScheduler.CONNECT_TIMEOUT_SECONDS = 60` | ✗ | hard-coded `static final int` |
| Fallback behaviour | **No** | — | ✗ | "one deterministic agent attempt per CONNECT request with a clean failure (no queue invented)" |
| Multiple agents | **No** | — | ✗ | single attempt, no retry-over-agents |
| Agent priority | **No** | — | ✗ | not defined by the codebase |
| Queue-based selection | **No** (outbound) | `AcdService` is inbound-only | ✗ | requires a `waitingCallId` |
| Explicit agent selection | **No** | — | ✗ | |
| Action after agent answers | **Partial** | `ConnectByAgentService` bridges on `CHANNEL_BRIDGE` | ⚠ | fixed, not configurable |
| Action when agent unavailable | **Partial** | `AgentReasons.*`, `recordConfigFailure` | ⚠ | fixed vocabulary, not configurable |
| Input-driven agent choice | **Yes** (runtime) | `DtmfActions.CONNECT_BY_AGENT`, `IvrTerminalAction.CONNECT_BY_AGENT` | ✓ | see §9 |
| Activation requirements | **No** | — | ✗ | no assignment to invalidate |
| Tenant ownership of the reference | **No** | — | ✗ | nothing to own |
| Snapshot behaviour | **Partial** | `type_config` JSONB frozen | ⚠ | freezes an empty contract |
| TypeConfig structural validation | **No** | `ConnectByAgentCampaignConfig` accepts any object | ✗ | |

---

## 6. Configuration Gap Matrix

Consolidated. **E** = existing, **P** = partial, **M** = missing.

| Requirement | E/P/M | Evidence |
|---|---|---|
| Typed CONNECT_BY_AGENT `typeConfig` | **M** | `ConnectByAgentCampaignConfig` holds `JsonNode legacyPayload`; accepts any non-empty object |
| Campaign → queue reference | **M** | no campaign column, no `campaign_id` in `queues` |
| Campaign → agent reference | **M** | no campaign column, no `campaign_id` in `agents` |
| Configurable selection strategy | **M** | strategy is a hard-coded SQL `ORDER BY` |
| Queue-scoped selection on the outbound path | **M** | `ConnectByAgentService` has zero `queue` references |
| Per-campaign ring duration | **M** | `CONNECT_TIMEOUT_SECONDS` is a platform constant |
| Fallback / retry-over-agents | **M** | one attempt, clean failure |
| Agent existence + tenant validation | **M** | `CampaignResourceValidationService` has no agent case |
| Queue existence + tenant validation | **M** | ditto |
| Readiness reason for agent/queue | **M** | 14 codes, none agent/queue |
| CONNECT_BY_AGENT-only invariant enforcement | **M** | no typed config exists to enforce it against |
| Agent lifecycle invariants across assignment | **M** | no assignment exists |
| OpenAPI for any of the above | **M** | `typeConfig` is opaque `JsonNode` |
| Deterministic selection | **E** | `findEligibleOrdered` + reservation service |
| Agent admin/availability separation | **E** | `AgentAdminStatus` / `AgentAvailability` |
| Input → agent action | **E** | DTMF + IVR terminal actions |
| Non-leaking cross-tenant error pattern | **E** | `validateDidReference` → one `BusinessException` |
| Snapshot freeze mechanism | **E** | `CampaignConfigurationService.createExecutionSnapshot` |
| `type_config` JSONB on both campaign and snapshot | **E** | `V14:73`, `V44:44` |
| DB restriction of `campaign_type` | **E** | `V14:23` CHECK (PLAYFILE, DTMF, CONNECT_BY_AGENT) |

---

## 7. Agent Assignment Model

Candidates A–F from the brief, scored against actual repository evidence.

### A. campaign → one agent

- Precedent: none.
- Runtime: `AgentConnectTrigger.connectByAgent(sessionId, attemptId)` has no
  agent parameter. Selection is a tenant-wide scan. Would require a runtime
  change to accept an id.
- Tenant: fine.
- Lifecycle: an assigned agent going `SUSPENDED`/`DISABLED` immediately breaks
  the campaign, with no configured behaviour.
- Concurrency: a single agent is a capacity bottleneck across a whole blast —
  `max_concurrent_calls` defaults to 1.
- Snapshot: trivially freezable.
- Readiness: trivially checkable.
- **Rejected by evidence:** contradicts the built foundation, which scans a pool
  precisely so one busy agent does not fail a call.

### B. campaign → multiple agents

- This is *not* expressible as "select among N" today. A scan with a candidate
  set exists; a *configured* candidate set does not.
- Concurrency/lifecycle: same concerns as A but mitigated by pool semantics.
- Would still need a runtime change (candidate set from config).
- **Partially supported**: the pool *concept* is built; the *configuration* is not.

### C. campaign → queue

- Precedent: **strong.** `Queue`, `QueueMembership`, `QueueStatus`,
  `AcdService`, `AcdOverflowService`, `AcdMaintenanceScheduler`,
  `QueueDirectoryService`, 20+ DDL hits in `V37`, `V38` — a complete queue
  domain exists.
- Runtime compatibility: **poor on the outbound path.** `AcdService.attemptAssignment`
  requires a `waitingCallId` (a `queue_waiting_calls` row). Outbound campaign
  calls have none. Reuse would require enqueuing every outbound call as a
  waiting call — a runtime and semantic change well beyond configuration.
- Tenant/lifecycle: excellent (`tenant_id` FK, `status` enum, membership status).
- Snapshot: freezable (a single UUID).
- **The domain exists; the outbound integration does not.**

### D. campaign → agent pool

- Precedent: implicit in the tenant-wide scan.
- Cannot be expressed as a *named, configured* pool — the only grouping concept
  in the codebase is the **queue**, and grouping agents is precisely
  `queue_memberships`.
- **Conceptually identical to C.** Any "pool" a campaign can select from is
  either a queue (the existing grouping primitive) or an ad-hoc agent list with
  no membership management, no capacity view, and no lifecycle.

### E. campaign → queue + optional explicit agents

- Precedent: none. No such composite exists anywhere in the codebase.
- Runtime: requires the most new logic (two candidate sources, precedence,
  fallback) and the most new config.
- **Rejected as over-engineering for a configuration phase**: it needs a runtime
  that does not exist, on top of a domain that already chose queues for grouping.

### F. Another existing model

- Explicit-agent-by-id: no precedent.
- DID-based routing: `agent_endpoints` binds agents to numbers, and
  `AgentEslRoutingTest` exists — but this is *routing*, not assignment, and
  `findEligibleOrdered` does not filter by it. Not an assignment model.

### What the repository naturally supports

**Option C — campaign → queue — is the model the repository already supports
for grouping, and it is the only candidate with a complete, tested, tenant-scoped
lifecycle behind it.** Two facts temper it, and both are decision-grade:

1. The outbound runtime cannot consume a queue today (§18). C is a
   *configuration* model whose *runtime* is unbuilt.
2. The tenant-wide scan is the only working outbound behaviour. C replaces it.

Therefore the honest statement is:

> The repository supports **campaign → queue** as the grouping model, and
> **tenant-wide pool** as the only currently-executing behaviour. VB-7A must
> choose which one a CONNECT_BY_AGENT campaign's configuration expresses, and
> that choice is only meaningful once §18's runtime gap is decided.

This is **Open Decision OD-2**. It cannot be resolved from the repository: both
models are representable, the existing code supports both readings, and the
brief forbids inventing product semantics.

---

## 8. Agent Selection Rules

### What exists

**Exactly one strategy, hard-coded, shared by both engines.**

- `AgentRepository.findEligibleOrdered` — least active reservations ASC, then
  agent id ASC.
- `AcdService` step 4 cites the *same* rule: *"deterministic order (VB-3 rule):
  least active reservations ASC, then agent id ASC — same state always picks the
  same agent."*
- `ConnectByAgentService.SELECTION_SCAN_LIMIT = 20`.
- `AgentReservationService` — advisory lock + re-read + conditional capacity
  check. This is the *authoritative* reservation, not the selection.

Supported today: availability-based selection ✔, deterministic selection ✔,
priority ✗, round-robin ✗, least-recently-used ✗, least-loaded (as a *derived*
count, yes; as a *configurable* strategy, no), explicit agent ✗, fallback ✗,
queue ✗ (outbound).

### Assessment for VB-7A

There is **no selection-strategy abstraction to expose.** The strategy is
inlined in a native SQL `ORDER BY` in `AgentRepository`. Making it configurable
would require one of:

| Option | What it means | Cost |
|---|---|---|
| **A** | Expose the existing rule as the *only* strategy; configuration records `null`/absent and the runtime keeps its current behaviour | Minimal, no runtime change — but then it is not configuration |
| **B** | Add a strategy enum + a strategy-aware repository query, wired to the existing reservation service | New selection code in the voice domain |
| **C** | New selection engine | **Forbidden** — duplicates `findEligibleOrdered` and `AcdService` |

Option C is explicitly ruled out by §21. Between A and B, **B is a runtime
change** and therefore outside a configuration-only phase.

**Recommendation:** VB-7A should NOT introduce a strategy algorithm. If the
product requires a per-campaign strategy, that is a separate runtime phase. The
configuration may record the *resolved* selection scope (queue) and leave the
strategy to the existing deterministic rule.

---

## 9. Input → Agent Action

### The behaviour already exists — three times over

| # | Model | Location | Scope |
|---|---|---|---|
| 1 | `DtmfActions.TERMINATE` \| `CONNECT_BY_AGENT` | `voice.dtmf` (exposed) | **campaign-level**, persisted on `dtmf_interactions.action_type` |
| 2 | `IvrTerminalAction.TERMINATE` \| `CONNECT_BY_AGENT` | `voice.ivr` (exposed) | **per-IVR-node** (VB-6F) |
| 3 | `CampaignType.CONNECT_BY_AGENT` | `campaign` | campaign *type* |

`DtmfActions`' javadoc is the key architectural statement:

> *DTMF terminal-result actions (VB-2/VB-3). Persisted on the interaction
> snapshot (`dtmf_interactions.action_type`) so the action dispatch is
> auditable and fixed at interaction creation — campaign config changes mid-call
> never alter the requested action.*

### The dispatch path (traced end to end)

```
CHANNEL_ANSWER (EslEventService:245, fires all PlaybackTriggers)
  → DtmfExecutionService.onAnswered          [guard: campaignType() == DTMF]
      → IvrExecutionService.onAnswered       (VB-6F, if IVR)
          → collect DTMF
              → terminal action == CONNECT_BY_AGENT
                  → dispatchConnectByAgent(sessionId, attemptId)   [L556]
                      → ObjectProvider<AgentConnectTrigger>.getIfAvailable()
                          → ConnectByAgentService.connectByAgent(...)
                              → AgentRepository.findEligibleOrdered(tenantId, limit 20)
                              → AgentReservationService (atomic)
                              → agent CallLeg + originate + ring + bridge
```

`dispatchConnectByAgent` is defensive: a missing trigger records a permanent
config failure (`"CONNECT_BY_AGENT action is not supported in this
deployment"`); a thrown `RuntimeException` is logged and swallowed.

### Verdict on Part 6

**"Input → agent action" is already fully implemented, already tested
(`DtmfAgentActionTest`, 7 CONNECT_BY_AGENT references), and already frozen
correctly.** It needs no new action framework. VB-7A must **not** introduce one.

The real gap is that the action is **unconfigurable**: it carries no agent, no
queue, no strategy, and passes only `(sessionId, attemptId)` to the trigger.

### The separate, larger finding: a CONNECT_BY_AGENT *campaign* has no runtime

This is a **runtime gap**, deliberately distinguished from the configuration gap.

Every `PlaybackTrigger` guards on campaign type:

- `PlayfileExecutionService:153` — `if (config.campaignType() != CampaignType.PLAYFILE) return;`
- `DtmfExecutionService:279` — `if (config.campaignType() != CampaignType.DTMF) return;`

`EslEventService` fires `onAnswered` on **all** `PlaybackTrigger`s, and the
outbound dialer gates on nothing at all (no `campaignType` reference exists in
`voice.outbound`).

Consequence: a `CONNECT_BY_AGENT` campaign
1. passes write-time validation (any non-empty `typeConfig`),
2. passes readiness (no agent/queue check exists),
3. passes the DRAFT → SCHEDULED activation gate,
4. passes the execution-creation readiness re-check,
5. is dialed and answered by a real callee,
6. and then **nothing happens** — no prompt, no DTMF collection, no agent
   connection.

The call would sit ANSWERED until VB-6E's `MaxCallDurationPolicy` /
`StaleCallReconciler` deadline (default 300 s) reaps it.

**This is not a configuration defect. It is an unbuilt runtime.** VB-7A cannot
close it and must not try — it is listed in §24 as an explicit non-goal and in
§18 as a runtime gap, and in §22 as **OD-3** (does VB-7A configuration ship
against a non-executing campaign type?).

---

## 10. Agent Lifecycle Invariants

### Traced scenarios

| # | Scenario | Current behaviour | Layer it belongs in |
|---|---|---|---|
| 1 | Campaign assigns an active agent | **Not representable** — no assignment exists | — |
| 2 | Agent becomes inactive (`SUSPENDED`/`DISABLED`) | `updateStatus` applies it unconditionally; cascades `AVAILABLE → OFFLINE`; `DISABLED` terminal. **No cross-resource check.** | — |
| 3 | Campaign remains DRAFT | Editable (`lifecyclePolicy.assertEditable` on update). Unaffected by agent state. | configuration |
| 4 | Campaign becomes SCHEDULED | `validateActivation` runs schedule/contact-group/DID/content/typeConfig/retry checks. **No agent/queue check.** This is the natural home for an assignment check. | **activation** |
| 5 | Snapshot already exists | Frozen and authoritative. Resource validity is *never* frozen (VB-6A rule, stated in `CampaignConfigurationSnapshot`'s own javadoc). | snapshot |
| 6 | Execution starts after deactivation | Nothing to check — no assignment in the snapshot, and readiness has no agent check. | **runtime** |
| 7 | Agent deleted after assignment | **No `DELETE /agents/{id}` exists.** Closest reachable states: `DISABLED` (terminal) and soft delete (`deleted_at`, via `AuditableEntity`). The brief's "deleted agent" case maps to `DISABLED` + `deleted_at`. | configuration |
| 8 | Queue deactivated | `QueueStatus` has `DISABLED` as terminal (`V37:20`). | readiness |
| 9 | Queue membership changes | `QueueMemberStatus` ACTIVE/INACTIVE, mutable, soft-deletable. | **runtime** (membership is a live capacity fact) |
| 10 | Agent temporarily unavailable | `availability` → `BUSY`/`OFFLINE`. Already excluded from selection by `findEligibleOrdered`. | **runtime** |

### The classification the brief demands

**CONFIGURATION VALIDITY ≠ RUNTIME AVAILABILITY** is not merely a principle here —
it is already a two-column schema with two enums and a written rationale. VB-7A
should map onto it exactly:

| Fact | Layer | Why |
|---|---|---|
| Agent exists, not soft-deleted, same tenant | **configuration validation** (write time) | Deterministic; the reference must be resolvable |
| Agent `admin_status == ACTIVE` | **configuration + activation/readiness** | A business decision that the agent may receive campaign calls at all |
| Agent `availability == AVAILABLE` | **runtime only — never readiness** | A momentary operational fact. Gating readiness on it would make almost every campaign permanently unready |
| Agent at capacity | **runtime only** | Owned by `AgentReservationService` |
| Queue exists, same tenant, `status == ACTIVE` | **configuration + activation/readiness** | A business decision |
| Queue membership set | **runtime only** | Membership is a live capacity fact; the queue is the frozen unit |
| Queue depth / overflow | **runtime only** | Live state |
| Agent suspended *after* the snapshot | **runtime** — call fails with an existing `AgentReasons` code | The snapshot freezes intent, not availability |

The existing `AgentReasons` vocabulary already provides correct runtime outcomes
(`AGENT_UNAVAILABLE`, `AGENT_NOT_AVAILABLE`, `AGENT_BUSY`, `AGENT_TENANT_MISMATCH`,
`AGENT_AT_CAPACITY`, `AGENT_NO_ANSWER`). **VB-7A must not add a second one.**

### Where the distinction belongs, concretely

- `CampaignResourceValidationService` — extend with `validateAgent(...)` /
  `validateQueue(...)`, checking existence + tenancy + `admin_status` /
  `status`. Called from `validateActivation` (and optionally at write time).
- `CampaignReadinessService` — add one reason code, evaluated at activation and
  re-evaluated at execution creation (which already re-runs readiness,
  `CampaignExecutionService:65`).
- **Not** in the snapshot. The snapshot freezes the *queue reference*; the
  queue's liveness is re-checked at every execution, exactly as the DID's is.

---

## 11. CONNECT_BY_AGENT-Only Invariant

> ONLY `CONNECT_BY_AGENT` may contain agent configuration.

### Where agent configuration could enter

| Entry point | Current state | Enforces type exclusivity? |
|---|---|---|
| `CreateCampaignRequest.typeConfig` / `UpdateCampaignRequest.typeConfig` | `JsonNode`, opaque | **No** |
| `CampaignTypeConfig.fromTypeConfig` (sealed interface) | dispatches on `CampaignType`; exhaustive `switch` | **Yes, structurally** — see below |
| `ConnectByAgentCampaignConfig` | accepts any non-empty object | **No** — accepts *anything* |
| `PlayfileCampaignConfig` / `DtmfCampaignConfig` / `IvrCampaignConfig` | strict typed parse | **Yes** — agent keys in a PLAYFILE/DTMF payload fail the parse |
| `CampaignTypeConfigValidator` | single delegation point | inherits the above |
| `CampaignService.validateTypeConfig` | delegates to the validator | inherits |
| `campaigns.campaign_type` CHECK | `IN ('PLAYFILE','DTMF','CONNECT_BY_AGENT')` | type-level only |
| `campaigns.type_config` JSONB | no JSON schema, no CHECK | **No** |
| `campaign_execution_configurations.type_config` JSONB | no JSON schema, no CHECK | **No** |
| REST DTOs | no agent/queue field | n/a |
| Snapshot resolver | copies `typeConfig` verbatim | **No** |

### The existing enforcement mechanism (already correct)

`CampaignTypeConfig` is a **sealed interface** with one `permits` entry per
campaign type, and `fromTypeConfig` is an **exhaustive switch**. Unknown types
have no representation by construction; the class javadoc states: *"Parsing is
strict and total — malformed or type-mismatched payloads throw
`CampaignConfigInvalidException` and never silently degrade."*

So once `ConnectByAgentCampaignConfig` is given a real typed shape, the
CONNECT_BY_AGENT-only invariant is **already enforced** by construction:

- PLAYFILE payload → `PlayfileCampaignConfig` strict parse → agent keys are
  unknown fields → `CampaignConfigInvalidException` → `BusinessException` → HTTP 400.
- DTMF payload → `DtmfCampaignConfig` / `IvrCampaignConfig` strict parse → same.
- CONNECT_BY_AGENT payload → the new typed config → agent keys required/allowed.

**No new validation service, no new discriminator check, and no new
`@PostConstruct`-style guard is needed.** This is the single strongest argument
for keeping the config inside `campaign.config` as a fourth `CampaignTypeConfig`
implementation.

### Why no database constraint

The brief asks not to recommend a DB constraint where the JSONB structure does
not make it clean. It does not:

- `campaigns.type_config` and `campaign_execution_configurations.type_config` are
  unconstrained `JSONB` (V14:73, V44:44). There is no JSON Schema, no
  `jsonb_typeof` CHECK, and no GIN index on it.
- Adding `CHECK (campaign_type = 'CONNECT_BY_AGENT')` when the payload is
  `{"dtmf":{...}}` would require reading into JSONB from a CHECK, duplicating
  the Java parse in SQL, and keeping the two in sync. It would also not compose
  with the fact that DTMF already has **two** valid shapes (VB-6F).
- The typed-parse boundary already rejects agent keys in a non-CONNECT_BY_AGENT
  payload at both write time and snapshot creation, which is where the invariant
  actually matters.

**Recommendation: enforce in `CampaignTypeConfig.fromTypeConfig` only. Do not
touch the schema.**

### Backstop worth naming

`CampaignType` is already DB-constrained to three values. A hypothetical
`MISSED_CALL` (VB-7B) will require a `V54` CHECK migration — and the sealed
interface's `permits` list will force every new campaign type to be handled
explicitly, so a new type cannot silently inherit a permissive parser. That is a
useful existing property, not something to add.

---

## 12. Snapshot Semantics

### What VB-6A already established

`CampaignConfigurationService.createExecutionSnapshot` validates the `typeConfig`
strictly and persists the immutable row **in the caller's transaction (REQUIRED)**,
so the snapshot and the execution commit atomically — *"an execution can never
exist without its snapshot, and no orphan snapshot exists either."*
`CampaignExecutionService:87` calls it before persisting the execution.

`CampaignConfigurationSnapshot` (an `@Embeddable` on the snapshot row) already
carries:

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "type_config")
private JsonNode typeConfig;
```

**VB-6F's IVR configuration already proved this exact route**: `IvrCampaignConfig`
and `IvrSnapshotCapture` freeze a tree reference through the *same* `type_config`
JSONB, with no new snapshot system.

### Field-by-field classification for VB-7A

| Field | Classification | Rationale |
|---|---|---|
| Queue reference (`queueId`) | **frozen configuration** | A business decision made at configuration time. Same class as `didId` — frozen, validity re-checked. |
| Selection scope discriminator (queue vs pool) | **frozen configuration** | Determines what the runtime must resolve; must not change mid-execution |
| Any "action after answer" toggle | **frozen configuration** *if* it exists | The current bridge-on-answer is fixed runtime behaviour |
| Ring duration | **runtime state, NOT frozen** | `CONNECT_TIMEOUT_SECONDS` is a platform constant owned by `AgentConnectTimeoutScheduler`. Freezing a value no runtime reads creates **dead configuration** |
| Agent availability (AVAILABLE/BUSY/OFFLINE) | **runtime state — never frozen** | Explicitly forbidden by the brief and by the existing two-column design |
| Queue depth / overflow | **runtime state — never frozen** | Live |
| Queue membership | **runtime state — never frozen** | Live capacity fact; the queue is the frozen unit |
| `maxConcurrentCalls` | **runtime state — never frozen** | Owned by the agent and reservation service |
| Agent `admin_status` | **live resource validation** | Like the DID: the *reference* is frozen, the *resource's validity* is re-checked. The snapshot javadoc states this rule explicitly. |

### Can the existing JSONB represent it?

**Yes, with no migration.** The CONNECT_BY_AGENT config rides
`campaigns.type_config` (V14:73) and is copied verbatim into
`campaign_execution_configurations.type_config` (V44:44) by the existing
snapshot code. This is precisely the VB-6F shape — a flat, keyed config in the
existing JSONB.

Nothing is created, versioned, or historised. No `MAX(version)+1`, no
compatibility shim, no second snapshot model. The snapshot is written once and
never updated.

### The one caveat

If VB-7A decides to freeze a queue reference, that reference is a **bare UUID**
by the same argument VB-6F used for `ivr_transitions.tree_id`. A bare UUID in a
frozen snapshot **cannot** express the composite tenant invariant that the IVR
tables enforce with composite FKs. The mitigation is the same as everywhere else
in this codebase: the reference is re-validated against the server-derived tenant
at execution creation, and again at dispatch. This is accepted, not overlooked.

---

## 13. Tenant / Authorization Analysis

### Does a campaign currently reference any agent/queue?

**No.** There is nothing to isolate. The audit confirms the *absence* of a
cross-tenant surface, and specifies the one that VB-7A would create.

### Existing ownership pattern to reuse

`CampaignService.validateDidReference` — the canonical precedent:

```java
private void validateDidReference(UUID campaignTenantId, UUID didId) {
    if (didId == null) { return; }
    if (resourceValidator.validateDid(didId, campaignTenantId).usable()) { return; }
    throw business("DID does not exist or is not available.");
}
```

and its documented invariant:

> *a supplied reference must point at a live resource owned by the SAME tenant as
> the campaign — caller visibility is irrelevant, and SUPER_ADMIN authority does
> not relax it. Foreign, nonexistent and soft-deleted resources all fail with one
> non-leaking validation error so no cross-tenant existence information escapes.*

`CampaignResourceValidationService` implements this with tenant-scoped derived
queries — e.g.
`existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(...)` —
and the `tenantId` parameter is documented as **"campaign tenant (server-derived)"**.

**Three rules VB-7A must inherit verbatim:**

1. **The tenant is server-derived, never client-supplied.** A client-provided
   tenant id in a CONNECT_BY_AGENT config is ignored or rejected.
2. **SUPER_ADMIN does not relax the ownership invariant.** A platform admin
   operating on a foreign campaign still cannot attach a foreign agent.
3. **One non-leaking error for foreign / nonexistent / soft-deleted alike.**
   Never "agent not found" vs "agent belongs to another tenant" — that is a
   tenant-enumeration oracle.

`AccessCheck` supports `forTenant`, `forReseller`, and `platformWide`, and
`AgentDirectoryService` / `QueueDirectoryService` already use all three for
their own CRUD. The campaign side uses `CAMPAIGN_VIEW` / `CAMPAIGN_MANAGE` /
`CAMPAIGN_EXECUTE`.

### Capability question

Should assigning an agent/queue require `AGENT_VIEW` or `QUEUE_VIEW` in
addition to `CAMPAIGN_MANAGE`?

**Recommendation: no.** The reference is validated for *existence and tenancy*,
exactly as the DID is — not for caller visibility. The DID is validated the same
way, and requiring `DID_VIEW` to attach a DID would be a new rule. Consistency
with the existing precedent is the correct answer. This is noted for
completeness rather than raised as an open decision.

### Reseller / platform admin

`AgentRepository.findByIdAndDeletedAtIsNull` exists specifically as an
*"Unscoped lookup for RESELLER/PLATFORM visibility resolution (VB-4A)"*.
Campaign-side validation must **not** use it — campaign validation is
tenant-scoped by construction. The unscoped lookup exists for the directory's
own visibility rules and must not become a campaign back door.

---

## 14. Resource Validation / Readiness

### The existing canonical authority

`CampaignResourceValidationService` — created through VB-5E/VB-5F. Its javadoc:

> *maps {@link ValidationCode}s onto their existing externally observable error*

`ValidationCode` today: `DID_NOT_AVAILABLE`, `AUDIO_NOT_AVAILABLE`,
`AUDIO_NOT_APPROVED`, `AUDIO_STORAGE_REFERENCE_MISSING`, `TTS_NOT_AVAILABLE`,
`TTS_NOT_APPROVED`.

API shape: `ResourceValidationResult(boolean usable, ValidationCode code)`.

It is consulted from three places:
1. `CampaignService.validateDidReference` / `validateContentReferences` (write + activation)
2. `CampaignReadinessService.checkDid` / `checkAudioAsset` / `checkTtsTemplate`
3. Nowhere else.

**VB-7A must extend this service, not create a parallel one.** A second validator
would be exactly the duplication the brief forbids.

### The type-config authority (a different concern, also existing)

`CampaignTypeConfigValidator` — javadoc: *"Single ownership boundary for campaign
type-configuration validation (VB-6A) ... deliberately distinct from resource
usability, which remains owned by `CampaignResourceValidationService` (DID/audio/
TTS state)."*

**The boundary is already drawn and is the right one:**

| Concern | Owner | VB-7A addition |
|---|---|---|
| Structural correctness of the payload (shapes, ranges, required keys) | `CampaignTypeConfig` / `CampaignTypeConfigValidator` | The typed CONNECT_BY_AGENT record |
| Does the referenced resource exist, belong to the tenant, and is it administratively usable | `CampaignResourceValidationService` | `validateAgent(...)`, `validateQueue(...)` |

### Which layer, when

| Check | Save-time | Activation (DRAFT→SCHEDULED) | Readiness re-check (execution creation) | Runtime |
|---|---|---|---|---|
| `typeConfig` structurally valid | ✔ (already) | ✔ (already, `validateTypeConfig` in `validateActivation`) | ✔ (already, via `createExecutionSnapshot`) | — |
| Agent exists / not deleted / same tenant | ✔ recommended | ✔ | ✔ | — |
| Agent `admin_status == ACTIVE` | ✔ recommended | ✔ | ✔ | re-checked per attempt |
| Queue exists / not deleted / same tenant | ✔ recommended | ✔ | ✔ | — |
| Queue `status == ACTIVE` | ✔ recommended | ✔ | ✔ | re-checked per attempt |
| Queue has ≥1 ACTIVE member | ✗ | ✗ | ✗ | runtime (a momentary fact) |
| Agent availability / capacity | ✗ | ✗ | ✗ | ✔ owned by `AgentReservationService` |

Save-time checking is a **UX improvement** (fail while the operator is looking at
the form), not a security control — the activation gate and the execution-time
re-check are the actual controls, and the runtime re-check is what protects a
campaign whose agent was suspended after SCHEDULED. This mirrors the DID exactly:
`validateDidReference`'s javadoc notes *"Activation re-checks approval because it
can change after creation."*

### Readiness reasons

`CampaignReadinessService` currently emits 14 codes:
`CAMPAIGN_NOT_EXECUTABLE_STATE`, `INVALID_SCHEDULE`,
`SCHEDULE_TIMEZONE_REQUIRED`, `SCHEDULE_NOT_ELIGIBLE`, `SCHEDULE_EXPIRED`,
`INVALID_CONTENT_CONFIGURATION`, `MISSING_REQUIRED_REFERENCE`,
`CONTACT_GROUP_UNAVAILABLE`, `DID_UNAVAILABLE`,
`AUDIO_STORAGE_REFERENCE_MISSING`, `AUDIO_NOT_APPROVED`,
`TTS_TEMPLATE_NOT_APPROVED`, `TTS_TEMPLATE_NOT_AVAILABLE`.

Capability: `CAMPAIGN_VIEW` (readiness is a read).

VB-7A would add at most two codes, in the established non-leaking style
(`AGENT_NOT_AVAILABLE` already exists as a *runtime* reason in `AgentReasons` —
a **readiness** code must not silently reuse a runtime constant, or the two
vocabularies would blur). Follow the `DID_UNAVAILABLE` precedent.

---

## 15. Campaign Lifecycle

### States (`CampaignStatus`, `V14:28`)

`DRAFT`, `ACTIVE`, `PAUSED` in the DB CHECK; the Java enum adds `SCHEDULED`,
`RUNNING`, `COMPLETED`, `FAILED`, `ARCHIVED`. Note the brief's assumed names
(`READY`) differ from the codebase (`SCHEDULED` is the activated state) — the
codebase name is authoritative.

### Editability

`CampaignService:204-207`:

> *only DRAFT campaigns are editable. A scheduled campaign is unlocked explicitly
> via the existing SCHEDULED → DRAFT transition*

`lifecyclePolicy.assertEditable(entity)` guards create/update/clone. A DRAFT
campaign must be re-validated after an edit — `validateTypeConfig` and the
resource checks run on every update, so an agent detached by an edit is caught
immediately.

### The activation gate — the natural home for assignment checks

`validateActivation` (called on DRAFT → SCHEDULED):

```java
validateContactGroupReference(tenantId, contactGroupId);
validateDidReference(tenantId, didId);
validateContentReferences(tenantId, contentMode, audioAssetId, ttsTemplateId);
validateContent(campaignType, contentMode, audioAssetId, ttsTemplateId);
validateTypeConfig(campaignType, typeConfig);
validateScheduleWindow(...);
validateRetrySpec(getRetryPolicy());
```

A queue/agent check slots in here, alongside `validateDidReference`.

### Execution creation — the second, authoritative gate

`CampaignExecutionService:64-70`:

```java
// Re-check readiness before creating execution
CampaignReadinessResponse readiness = readinessService.evaluate(campaignId);
if (!readiness.ready()) { throw new BusinessException(...); }
```

Then `configurationService.createExecutionSnapshot(campaign)` freezes the config.
**So a CONNECT_BY_AGENT campaign whose queue was deactivated after SCHEDULED is
blocked at execution creation** — provided readiness knows about the queue. This
is exactly the DID's behaviour and needs no new mechanism.

### Freeze boundary — unchanged and correct

| Phase | Agent/queue config |
|---|---|
| DRAFT | created, changed, removed freely (DRAFT is the only editable state) |
| SCHEDULED | frozen by the activation gate; unlock via SCHEDULED → DRAFT |
| Execution created | copied into the immutable snapshot, verbatim, in the same transaction |
| After that | never updated; resource validity re-checked at every attempt |

**No versioning. No `MAX(version)+1`. No legacy fallback. No compatibility shim
beyond OD-1's explicit question.**

---

## 16. API / OpenAPI

### Existing campaign surface

`CampaignController` — 20 endpoints: `POST` create, `GET /{id}`, `GET` list,
`PUT /{id}` update, `DELETE /{id}`, `PATCH /{id}/status`,
`POST /{id}/clone`, `GET /{id}/readiness`, `POST /{id}/executions`, plus
execution/attempt read + manual attempt transitions
(`in-progress` / `completed` / `failed` / `cancel`).

Request DTOs: `CreateCampaignRequest`, `UpdateCampaignRequest` — both carry
`typeConfig` as an opaque `JsonNode` with no `@Schema` describing its shape.
Response: `CampaignResponse.typeConfig` likewise opaque.

### Does CONNECT_BY_AGENT have REST representation?

**No, beyond an opaque `JsonNode`.** There is no agent field, no queue field, no
documented `typeConfig` schema for any campaign type.

### Generated OpenAPI is the source of truth

Three contract tests exist: `CampaignOpenApiContractTest` (9+ tests),
`IvrOpenApiContractTest`, `ContactGroupMemberOpenApiContractTest`. They build the
document and **inspect** it — e.g. `createSchemaDocumentsDailyDialLimit`,
`retryPolicySchemaDocumented`, `maxDailyAttemptsExposedOnAllCampaignSchemas`.
VB-7A must follow: a DTO field is not "documented" because of an annotation; the
generated document must contain it.

### What VB-7A would require

If VB-7A adds fields to the typed config, they arrive through the **existing**
`typeConfig` request/response properties. So:

- **No new endpoint** is required for the configuration itself.
- `@Schema` documentation of the CONNECT_BY_AGENT `typeConfig` shape is required
  on the campaign request/response schemas (currently undocumented for all types
  — VB-7A should at minimum document its own).
- `CampaignOpenApiContractTest` gains a test asserting the new property appears
  in the generated create/update/response schemas, mirroring
  `maxDailyAttemptsExposedOnAllCampaignSchemas`.

**No error-handling change is required.** `CampaignConfigInvalidException extends
BusinessException`, already mapped by `GlobalExceptionHandler:33` to a
`ProblemDetail` (HTTP 400). `CampaignService`'s ownership failures already throw
`business(...)` → same 400.

**No authorization change is required** — `CAMPAIGN_VIEW` / `CAMPAIGN_MANAGE` /
`CAMPAIGN_EXECUTE` already cover it.

---

## 17. Database / Migration Analysis

### Is a migration required?

**NO.**

| Question | Answer | Evidence |
|---|---|---|
| Do `campaigns` have a `type_config` JSONB column? | **Yes** | `V14:73` — `type_config JSONB` |
| Does the execution snapshot have one? | **Yes** | `V44:44` — `type_config JSONB` |
| Do `agents`/`queues` have tenant scoping, status, lifecycle? | **Yes** | `V36:28-46`, `V37:30-61` |
| Is there a `campaign_id` on any agent/queue table? | **No** | grep across all 52 migrations: `campaign_id` appears only in campaign-domain tables (`V15`, `V21`, `V22`, `V35`, `V44`) |
| Does `campaigns` have an `agent_id`/`queue_id` column? | **No** | `V14` `CREATE TABLE campaigns` |
| Is `campaign_type` DB-constrained? | **Yes** | `V14:23` `CHECK (campaign_type IN ('PLAYFILE','DTMF','CONNECT_BY_AGENT'))` |
| Is `type_config` JSON-schema-constrained? | **No** | no `jsonb_typeof` CHECK, no JSON Schema on either column |

**The CONNECT_BY_AGENT configuration is JSONB data, not a column.** It rides the
exact route VB-6F's IVR config used. **Zero DDL.**

### Why not a relational `campaign_agent_assignments` table

A normalized assignment table would be the reflex answer for "campaign → agents".
It is rejected here because:

1. It would duplicate the `queue_memberships` grouping primitive that already
   exists, is tenant-scoped, status-bearing, indexed, and tested.
2. It would require campaign↔agent lifecycle coupling (what happens when the
   agent is suspended?) — a large amount of new state, none of which VB-7A's
   configuration scope needs.
3. It would be **mutable relational state on a campaign**, conflicting with the
   established *"campaign configuration is editable; the execution snapshot is
   frozen"* model, and would invite a versioning discussion the brief forbids.
4. The brief's whole premise is that a **queue** is the grouping model (OD-2), and
   a queue reference is one UUID in existing JSONB.

**If OD-2 resolves to "explicit agent list", that decision would change this
conclusion** — an ordered list of agent UUIDs is still representable in JSONB and
still needs no DDL, so the answer holds either way. Only a requirement for
per-agent campaign-scoped metadata (priority, weight, per-campaign capacity)
would justify a table. Nothing in the brief or the repository asks for that.

### The one migration that IS eventually needed

`MISSED_CALL` (VB-7B) will require a `V54` to extend the `ck_campaigns` CHECK
constraint. Not VB-7A's business, but the audit should flag it so VB-7B is not
surprised.

---

## 18. Runtime Compatibility

### The traced path

```
Campaign (CONNECT_BY_AGENT)
  → CampaignExecutionConfiguration  [type_config frozen — currently empty contract]
  → CampaignExecutionOrchestrator
  → CallAttempt (dialed; no type gate anywhere in voice.outbound)
  → CHANNEL_ANSWER → EslEventService:245 fires all PlaybackTriggers
      → PlayfileExecutionService.onAnswered → campaignType() != PLAYFILE → RETURN
      → DtmfExecutionService.onAnswered    → campaignType() != DTMF    → RETURN
  → nothing. No prompt. No DTMF. No agent connection.
  → call sits ANSWERED until VB-6E's 300 s default deadline reaps it
```

### Does the current runtime understand the proposed configuration?

| Proposed field | Understood by `ConnectByAgentService`? | Evidence |
|---|---|---|
| queue reference | **No** | `ConnectByAgentService` contains **zero** references to `queue`/`Queue`; its javadoc states *"one deterministic agent attempt per CONNECT request with a clean failure (**no queue invented**)"* |
| selection strategy | **No** | selection is a hard-coded SQL `ORDER BY` in `AgentRepository` |
| per-campaign ring duration | **No** | `AgentConnectTimeoutScheduler.CONNECT_TIMEOUT_SECONDS = 60`, a `static final int` |
| fallback / retry-over-agents | **No** | one attempt, then a clean failure |
| dialed-DID-scoped agent filtering | **No** | `findEligibleOrdered` filters on `tenant_id` only |

### Configuration gap vs runtime gap — the separation the brief demands

**CONFIGURATION GAPS** (VB-7A's job, no runtime required):
- CONNECT_BY_AGENT has no typed `typeConfig`; any object is accepted
- No agent/queue reference can be expressed
- No resource validation or readiness for agent/queue
- No CONNECT_BY_AGENT-only invariant over real config
- No readiness reason, no OpenAPI documentation

**RUNTIME GAPS** (NOT VB-7A's job, and NOT closable by configuration):
1. **A CONNECT_BY_AGENT campaign type has no execution service at all.** It is
   dialed, answered, and ignored. (`§9`)
2. **The outbound agent path is queue-blind.** `AcdService` requires a
   `waitingCallId` from `queue_waiting_calls`; outbound calls have none. Routing a
   campaign call through a queue needs either a new outbound selection path or
   enqueuing outbound calls as waiting calls.
3. **`AgentConnectTrigger` accepts no configuration.** Its signature is
   `connectByAgent(UUID callSessionId, UUID attemptId)`. Nothing about a queue
   or strategy can reach the runtime without a signature change.
4. **Ring duration is a platform constant**, not per-campaign.
5. **Selection does not consider the dialed DID**, so an agent whose endpoint is
   bound to a different number can be bridged.

Gaps 2 and 3 are one coupled unit: *configuring a queue requires a runtime that
can be told which queue.* That is **OD-2 / OD-4**.

### The dead-configuration trap

This is the most important warning in the audit.

If VB-7A freezes a `queueId` into the snapshot and no runtime reads it, the
platform gains a field that looks functional, passes every test, and does
nothing. Every test in the implementation plan would pass; the product would
still not connect callers to the right agents.

**The audit does not recommend shipping a queue reference that no runtime
consumes.** Either VB-7A is accompanied by the minimal runtime change that reads
it, or it records the configuration as explicitly not-yet-executed and says so in
the API documentation. This is **OD-3**.

---

## 19. Test Coverage

### Existing coverage

| Area | Class | Status |
|---|---|---|
| Agent foundation (Postgres) | `AgentFoundationIntegrationTest` + Support | strong |
| Reservation uniqueness | `AgentReservationUniquenessIntegrationTest` | strong |
| Agent selection | `AgentSelectionTest` | strong |
| Agent connect service | `ConnectByAgentServiceTest` | strong |
| Agent connect (Postgres) | `AgentConnectIntegrationTest` + Support | strong |
| Agent outbound | `AgentOutboundCallServiceTest`, `AgentOutboundIntegrationTest` | strong |
| Agent stale reconciler | `AgentStaleReconcilerIntegrationTest` | strong |
| Agent directory | `AgentDirectoryServiceTest`, `AgentDirectoryApiSliceTest` | strong |
| Agent call queries | `AgentCallQueryServiceTest` | good |
| ESL agent routing | `AgentEslRoutingTest` | good |
| Dialer SPI contract | `AgentLegDialerContractTest` | good |
| Queue foundation (Postgres) | `QueueFoundationIntegrationTest` + Support | strong |
| ACD | `AcdServiceTest`, `AcdIntegrationTest` + Support | strong |
| Queue directory | `QueueDirectoryServiceTest`, `QueueDirectoryApiSliceTest` | strong |
| Input → agent action | `DtmfAgentActionTest` (7 CONNECT_BY_AGENT refs) | strong |
| Campaign type config | `CampaignTypeConfigTest` (11 refs) | strong — but encodes the "any shape" contract |
| IVR campaign config | `IvrFromCampaignServiceTest`, `IvrExecutionSnapshotTest` | strong (VB-6F precedent) |
| OpenAPI | `CampaignOpenApiContractTest`, `IvrOpenApiContractTest`, `ContactGroupMemberOpenApiContractTest` | strong |
| Campaign readiness | exists (in `campaign` suite) | covers DID/audio/TTS/schedule/contact-group only |

**The agent/queue/ACD foundation is very well tested. VB-7A does not need to
touch it.** The gap is entirely on the campaign-configuration side.

### Coverage matrix

| Requirement | Existing test | Missing test | Notes |
|---|---|---|---|
| CONNECT_BY_AGENT accepts valid agent config | — | **yes** | the phase's core positive path |
| PLAYFILE rejects agent config | partial (`CampaignTypeConfigTest` rejects malformed PLAYFILE) | **yes** | cross-type rejection is not asserted |
| DTMF rejects agent config | partial (same) | **yes** | two DTMF shapes → both must be checked |
| Tenant A cannot reference tenant B agent | — | **yes** | the security-critical case |
| Tenant A cannot reference tenant B queue | — | **yes** | ditto |
| Cross-tenant error is non-leaking | — | **yes** | must not distinguish foreign vs nonexistent |
| Inactive (`SUSPENDED`) agent behaviour | `AgentDirectoryServiceTest` (status transitions) | **yes** | campaign-config consequence is untested |
| `DISABLED` (terminal) agent behaviour | covered for the agent API | **yes** | campaign-config consequence untested |
| Agent soft-delete behaviour | covered for the agent API | **yes** | ditto |
| Queue ownership | `QueueDirectoryServiceTest` | **yes** | from the *campaign* side |
| Deactivated queue blocks activation | — | **yes** | the readiness case |
| Invalid selection rule | — | **yes** | if a strategy is configurable |
| Snapshot freezes the reference | `IvrExecutionSnapshotTest` (VB-6F precedent) | **yes** | same shape |
| Campaign edit after snapshot does not change it | `CampaignConfigurationServiceTest` (existing pattern) | **yes** | |
| Different executions get correct snapshots | existing pattern | **yes** | |
| Readiness failure on invalid agent/queue | — | **yes** | new readiness reason |
| Readiness **must not** fail on agent unavailability | — | **yes** | **the config-vs-runtime guard test** |
| SUPER_ADMIN cannot attach a foreign agent | — | **yes** | the ownership invariant does not relax |
| OpenAPI contract for the new schema | `CampaignOpenApiContractTest` pattern | **yes** | inspect the generated document |
| API validation error mapping | existing `BusinessException` → 400 tests | **yes** | new config cases only |
| Idempotency | `CampaignExecutionService` idempotency exists | — | not applicable to config; skip |

The last two rows reflect the brief's instruction to include only tests justified
by the audit. **Idempotency is not applicable** — VB-7A adds no endpoint.

### Total

**~19 new tests**, concentrated in 5 places: `CampaignTypeConfigTest` (config
shape + cross-type rejection), `CampaignResourceValidationServiceTest` (tenancy +
non-leaking), `CampaignReadinessServiceTest` (new reason + the
availability-must-not-matter test), `CampaignConfigurationServiceTest` (freeze +
edit-after-freeze), `CampaignOpenApiContractTest` (generated document).

---

## 20. Architecture / Dependency Analysis

### Cycle analysis

| Question | Answer | Evidence |
|---|---|---|
| Does `voice.agent` import `campaign`? | **No** | grep: 0 imports |
| Does `voice.queue` import `campaign`? | **No** | 0 imports |
| Does `voice.acd` import `campaign`? | **No** | 0 imports |
| Does `campaign` import `voice.agent`? | **Yes** | 10 types, via `@NamedInterface("agent")` |
| Does `campaign` import `voice.queue` or `voice.acd`? | **No** | grep: 0 imports |
| Would `campaign → voice.queue` create a cycle? | **No** | no reverse edge exists |

**VB-7A introduces zero new cycles** under any option. The module graph is a DAG
today and stays one.

### The three ways VB-7A could reach a queue

| Option | Mechanism | New module exposure | Cycle risk | Verdict |
|---|---|---|---|---|
| **1. Expose `voice.queue`** | add `package-info.java` with `@NamedInterface("queue")`; campaign injects `QueueRepository` | Widens the module surface to the **whole** package — entities, `QueueDirectoryService`, `QueueDirectoryController`, repositories | none | works, but over-exposes a CRUD package to a consumer that needs one read-only lookup |
| **2. Narrow port in `voice.agent`** | declare e.g. a queue-lookup/validation port in the already-exposed `voice.agent`, implement it inside `voice.queue`; campaign consumes the port | **none** | none | **preferred** — matches VB-3's own `AgentConnectTrigger` precedent and the VB-6F `IvrPromptChecker` pattern; keeps the module surface minimal |
| **3. Reuse `AcdService`** | expose `voice.acd` | widens ACD to campaign | none | **rejected** — `AcdService` requires a `waitingCallId` and is inbound-shaped; forcing outbound calls through it is a runtime redesign (§7, §18) |

For **agents**, no choice is needed: `AgentRepository` is already in the exposed
`voice.agent` package, whose own javadoc designates it *"the canonical voice agent
API"* for campaign orchestration. A tenant-scoped existence check is one derived
query, e.g.
`existsByIdAndTenantIdAndDeletedAtIsNullAndAdminStatus(...)`, on an already-public
interface.

### Recommended direction

```
campaign  ──(existing, sanctioned)──▶  voice.agent   [AgentRepository, AgentConnectTrigger]
campaign  ──(new narrow port)──────▶  voice.agent   [queue lookup port, impl inside voice.queue]
```

**No new module edge. No new `@NamedInterface`. No cycle.** Option 1 is acceptable
if preferred, but Option 2 is more consistent with the three port precedents
already in the codebase.

### Modulith detail carried forward

- `@ApplicationModule` in Modulith 2.1.0 has **no** `description` attribute —
  `campaign`'s root `package-info.java` correctly uses `displayName`.
- `campaign` exposes only its root package (its sub-packages have no
  `package-info.java`), so campaign→campaign-sub-package references are always
  intra-module and need no exposure.

---

## 21. Duplicate Infrastructure Risks

What a naive VB-7A could build, and what already exists instead.

| # | Risk | EXISTING AUTHORITY → SHOULD REUSE |
|---|---|---|
| 1 | **A second agent-selection service** | `AgentRepository.findEligibleOrdered` + `AgentReservationService` are the *only* selection and reservation authorities. `AcdService` already implements the identical order. A VB-7A "selector" would be a third copy of the same rule. |
| 2 | **A second readiness service** | `CampaignReadinessService.evaluate` is the sole readiness authority, re-invoked at execution creation. Extend it; never fork it. |
| 3 | **A second resource validator** | `CampaignResourceValidationService` is the declared single ownership boundary for DID/audio/TTS. Agent/queue belong there, with `ValidationCode`s. |
| 4 | **A second snapshot model** | `CampaignConfigurationSnapshot` + `CampaignConfigurationService` + `type_config` JSONB. VB-6F already proved the route. **No new table, no versioning.** |
| 5 | **A second assignment relationship** | `queue_memberships` already is the agent-grouping relationship, tenant-scoped and status-bearing. A `campaign_agent_assignments` table would duplicate it (§17). |
| 6 | **A second lifecycle model** | `AgentAdminStatus` (ACTIVE/SUSPENDED/DISABLED) and `QueueStatus` (ACTIVE/INACTIVE/DISABLED) already exist, with terminal states and transition guards. Do not invent a "campaign assignment status". |
| 7 | **A second action enum** | `DtmfActions` and `IvrTerminalAction` both already model input → agent action, and `IvrTerminalAction` is the newer, per-node one. **Do not create a third.** |
| 8 | **Campaign-specific ACD logic** | `AcdService` / `AcdOverflowService` / `AcdMaintenanceScheduler` are the ACD authorities. If VB-7A needs queue behaviour, extend them — do not write campaign-flavoured ACD. |
| 9 | **Campaign-specific agent-availability logic** | `findEligibleOrdered`'s `AVAILABLE` filter + `AgentReservationService`'s capacity check. Never re-derive availability in campaign code. |
| 10 | **A fourth `CampaignTypeConfig`** | ⚠ **Not a duplicate** — this is the *sanctioned* extension point (sealed `permits` + exhaustive switch). `PlayfileCampaignConfig`, `DtmfCampaignConfig`, `IvrCampaignConfig` are the precedents. VB-7A adding `ConnectByAgentCampaignConfig`'s real shape here is correct, not duplicative. |
| 11 | **A new reason-code vocabulary** | `AgentReasons` (runtime) and `CampaignReadinessService` codes (readiness) both exist. Add one readiness code; reuse runtime codes for runtime outcomes. Do not merge the two vocabularies. |
| 12 | **A new validation exception type** | `CampaignConfigInvalidException extends BusinessException` → 400 via the existing handler. Reuse. |

---

## 22. Open Decisions

Only four. Each is genuinely unresolvable from the repository: in every case the
code supports more than one reading and the brief forbids inventing product
semantics.

### OD-1 — Does VB-7A replace the "any non-empty object" CONNECT_BY_AGENT contract?

**Question.** `ConnectByAgentCampaignConfig` accepts any non-empty JSON object,
deliberately "kept for compatibility", and two tests assert it. Does VB-7A make
the payload strictly typed, breaking that contract?

**Evidence.**
- `ConnectByAgentCampaignConfig` javadoc: *"a non-empty object (any shape) is
  accepted as the legacy payload ... No queue or agent-routing configuration is
  invented in this phase."*
- `CampaignTypeConfigTest.connectAcceptsLegacyPayload` asserts
  `{"legacy":{"anything":true}}` round-trips untouched.
- `CampaignService` L369-370 calls the requirement *"(product-defined later)"* —
  the shape was required as a placeholder and never specified.
- `campaigns.campaign_type` is CHECK-constrained to the three types; a
  CONNECT_BY_AGENT campaign is creatable today, so the contract is reachable.

**Options.**
- **A. Strictly typed** — CONNECT_BY_AGENT requires the new shape; anything else
  is a 400. Breaks both existing tests. Consistent with the sealed-interface
  philosophy ("parsing is strict and total ... never silently degrade") and with
  PLAYFILE/DTMF/IVR.
- **B. Typed with a legacy escape** — accept the old shape as an alias. Matches
  the brief's warning against compatibility shims *unless an actual requirement
  is found*; one arguably exists here.
- **C. Typed but all-new fields optional** — the new fields may be absent, and
  absence means "tenant-wide pool" (today's behaviour). Non-breaking, but the
  config carries no information when defaults apply.

**Consequences.** A is cleanest and matches the codebase's stated philosophy; it
requires deleting/rewriting 2 tests. C is non-breaking but risks a
configuration that is silently inert. B is the shim the brief warns against.

**Decision needed.** Is the "any shape" contract a compatibility promise to
existing clients, or an acknowledged placeholder? Nothing in the repository
distinguishes a real client from a placeholder.

### OD-2 — One agent, an agent pool, or a queue?

**Question.** What does a CONNECT_BY_AGENT campaign's configuration reference?

**Evidence.** Candidate C (queue) has the strongest precedent: a complete
tenant-scoped, status-bearing, indexed, tested domain (`queues`,
`queue_memberships`, `AcdService`, `AcdOverflowService`, `QueueDirectoryService`).
Candidate D (pool) is what the code does *today* — a tenant-wide scan in
`findEligibleOrdered`, with no grouping concept at all. `CampaignType.CONNECT_BY_AGENT`'s
own javadoc says *"an **agent/queue**"*, deliberately ambiguous. The current
`ConnectByAgentService` javadoc says *"no queue invented"*. No `campaign_id`
exists in any agent/queue table, so no precedent for a campaign-side reference.

**Options.**
- **A. Queue** (recommended by repository evidence) — reuses `queue_memberships`
  for grouping. Cost: the outbound runtime is queue-blind (§18, gap 2).
- **B. Tenant-wide pool** — matches current behaviour; the config then expresses
  almost nothing beyond "connect to an agent", and O(1) new runtime work.
- **C. Ordered list of agent UUIDs** — no precedent; duplicates grouping; ignores
  capacity management.
- **D. Composite: queue + optional explicit agents** — no precedent; most new
  logic; over-engineering for a configuration phase.

**Consequences.** A and B are the only serious options. A is the model the
platform was built for; B is the only thing that currently executes.

**Decision needed.** Does the product want CONNECT_BY_AGENT to target a
*specific, managed group of agents* (a queue) or to keep today's tenant-wide
behaviour and merely make it explicit in configuration? Everything else in §7,
§12, §17 follows from this one answer.

### OD-3 — Is a queue reference without a runtime acceptable?

**Question.** `AgentConnectTrigger.connectByAgent(sessionId, attemptId)` cannot
receive a queue. If VB-7A freezes a `queueId` that nothing reads, the platform
gains a field that passes every test and does nothing (§18, "dead configuration").
Does VB-7A ship that?

**Evidence.** Every `PlaybackTrigger` guards on campaign type, so a
CONNECT_BY_AGENT campaign is dialed, answered, and ignored today (§9). The
brief scopes VB-7A as "configuration/readiness work only" and puts "new agent
runtime" and "new telephony execution" out of scope — yet the configuration it
asks for is meaningless without a runtime that reads it.

**Options.**
- **A. Config-only, documented as not-yet-executed** — ship the typed config
  with an explicit API description saying the campaign type has no execution
  service. Honest, in-scope, and leaves the product no better.
- **B. Config + the minimal runtime** — add a queue-scoped selection path to
  `ConnectByAgentService` (or make the outbound path enqueue waiting calls) and
  have a CONNECT_BY_AGENT campaign execute. Exceeds the stated scope but is the
  only way the configuration is real.
- **C. Defer VB-7A** until the runtime is agreed.

**Consequences.** A risks a shipped lie. B is a larger phase than "configuration"
but reuses `AgentReservationService` and the existing deterministic order — it is
plausibly small. C costs a phase.

**Decision needed.** Is it acceptable to ship a CONNECT_BY_AGENT configuration
for a campaign type that has no execution service, or must the runtime ship with
it? This determines whether VB-7A is genuinely configuration-only.

### OD-4 — Should an inactive agent/queue block activation, or only execution?

**Question.** A campaign references a queue whose `status` later becomes
`INACTIVE`, or an agent whose `admin_status` becomes `SUSPENDED`. Does that block
DRAFT → SCHEDULED, block execution creation, or neither (fail at runtime only)?

**Evidence.** Strong precedent points one way. The DID's `admin_status`/approval
is re-checked at activation with the explicit rationale: *"Activation re-checks
approval because it can change after creation."* `validateDidReference` and
`validateContentReferences` run in `validateActivation`, and readiness re-runs at
execution creation. Meanwhile `AgentAvailability` is documented as a *runtime*
fact, and `AgentAdminStatus` as a *configuration/business* fact. So
`admin_status`/`QueueStatus` follow the DID, and `availability` follows the
reservation lifecycle.

**Options.**
- **A. `admin_status`/`status` block activation + execution creation; `availability` never does.** Follows the DID precedent exactly. This is the audit's recommendation.
- **B. Neither blocks; fail at runtime only.** Maximally permissive; a campaign
  can sit SCHEDULED pointing at a suspended agent.
- **C. Block readiness only, never activation.** Inconsistent with the DID.

**Consequences.** A means a campaign becomes un-ready and un-executable when its
agent is suspended, and recovers automatically when reactivated. B means silent
runtime failures.

**Decision needed.** Confirm that administrative state is a *configuration*
gate (like DID approval) and that runtime availability is never a gate. The audit
believes A is clearly right and consistent — it is listed because the brief
explicitly asked for it, not because the repository is silent.

---

## 23. Proposed VB-7A Implementation Scope

Not implemented. Conditional on OD-1, OD-2 and OD-3.

### P0 — correctness and invariants

1. **Real typed `ConnectByAgentCampaignConfig`** — replace the `legacyPayload`
   record body with the actual configuration fields decided under OD-2. Keep the
   record, the `campaignType()`/`schemaVersion()`/`toJson()` contract, and its
   place in the sealed `CampaignTypeConfig.permits` list. Strict and total:
   unknown fields, wrong types, and out-of-range values throw
   `CampaignConfigInvalidException`, exactly as `DtmfCampaignConfig` does.
   *(Addresses OD-1; the only P0 item that can be built without OD-2/OD-3.)*
2. **CONNECT_BY_AGENT-only enforcement** — delivered *for free* by (1) via the
   sealed interface + exhaustive switch: agent keys in a PLAYFILE or DTMF payload
   already fail their own strict parse. Add explicit tests proving it for all
   four campaign types. **No new validator, no DB constraint.**
3. **Tenant-ownership invariant** — `validateQueue(...)` (and
   `validateAgent(...)` if OD-2 selects explicit agents) on
   `CampaignResourceValidationService`, using tenant-scoped derived queries
   against `AgentRepository` (already exposed) and a queue lookup behind a narrow
   port. One non-leaking `BusinessException` for foreign / nonexistent /
   soft-deleted, matching `validateDidReference`. Server-derived tenant; no
   relaxation for SUPER_ADMIN.
4. **Activation + readiness gate** — add the check to `validateActivation` and
   add at most **one** new readiness reason in the `DID_UNAVAILABLE` style.
   Must **not** consider `availability` or capacity (OD-4).
5. **Snapshot freeze** — the config rides the existing `type_config` JSONB via
   `CampaignConfigurationService`. **No new snapshot model, no versioning, no
   migration.** Add a test proving the reference freezes and that a later campaign
   edit does not alter an existing execution's snapshot.
6. **Resource-validity-is-never-frozen** — prove by test that a queue deactivated
   *after* the snapshot still blocks the *next* execution while leaving the
   existing snapshot intact.

### P1 — configuration, API, readiness

7. **`ValidationCode` additions** in `CampaignResourceValidationService`, in the
   established non-leaking style.
8. **Readiness reason** in `CampaignReadinessService` + the capability/response
   wiring already used by the other 14 codes.
9. **A narrow queue-lookup port** in the already-exposed `voice.agent` package,
   implemented inside `voice.queue` (VB-6F `IvrPromptChecker` pattern).
   **No new `@NamedInterface`; no new module edge; no cycle.**
10. **OpenAPI** — document the CONNECT_BY_AGENT `typeConfig` shape via `@Schema`
    on the campaign request/response schemas, and add a
    `CampaignOpenApiContractTest` case that **inspects the generated document**,
    mirroring `maxDailyAttemptsExposedOnAllCampaignSchemas`. No new endpoint.
11. **Explicit API documentation of execution status** (see OD-3). If VB-7A ships
    without a runtime, the generated OpenAPI description must say so — the
    alternative is a documented field that does nothing.

### P2 — tests, hardening, documentation

12. **~19 new tests** per §19, across `CampaignTypeConfigTest`,
    `CampaignResourceValidationServiceTest`, `CampaignReadinessServiceTest`,
    `CampaignConfigurationServiceTest`, `CampaignOpenApiContractTest`.
13. **The config-vs-runtime guard test** — a campaign referencing a valid, active,
    tenant-owned but currently *unavailable* agent/queue must remain **ready**.
    This is the test that protects the distinction the whole design rests on.
14. **Update the two existing tests** that encode the "any shape" contract, per
    OD-1.
15. **Documentation** — a
    `docs/VB-7A-CONNECT-BY-AGENT-CAMPAIGN-CONFIGURATION-IMPLEMENTATION.md`
    recording the model, the enforcement layer, the snapshot classification, the
    OD resolutions, and the known limitations.
16. **Full-suite regression** confirming VB-6C, VB-6D, VB-6E and VB-6F behaviour
    and test counts are unchanged.

### Explicitly NOT in VB-7A

- Any new selection algorithm (OD: strategy stays `findEligibleOrdered`)
- Any per-campaign ring duration (dead config until `AgentConnectTimeoutScheduler`
  is parameterised)
- Any change to `AcdService`, `AcdOverflowService`, `AcdMaintenanceScheduler`
- Any change to `AgentReservationService` or the reservation uniqueness model
- Any `campaign_agent_assignments` table
- Any use of the unscoped `AgentRepository.findByIdAndDeletedAtIsNull`
- Any new `AgentReasons` code

---

## 24. Explicit Non-Goals

Restated so nothing drifts in:

- **AI agents, AI providers, LLM/voice-AI integration** — none exist; the brief
  excludes them. `agent_endpoints` is SIP/endpoint data, not an AI runtime.
- **MISSED_CALL** — not implemented; `ck_campaigns` excludes it. VB-7B's problem.
- **Webhooks / integrations** — `integrationConfig` is persisted and read by no
  code. Not VB-7A.
- **A second agent-selection engine** — `findEligibleOrdered` is the authority.
- **A second ACD/queue engine** — `AcdService` is the authority, and it is
  inbound-only. Extending it is a separate, later decision.
- **A second scheduler** — `DtmfTimeoutScheduler` (1 s) and
  `AgentConnectTimeoutScheduler` (60 s) exist. VB-7A adds none.
- **A second DTMF/IVR action framework** — `DtmfActions` and
  `IvrTerminalAction` are the authorities; "input → agent action" already works.
- **A new agent runtime or agent lifecycle model** — `AgentAdminStatus` and
  `AgentReservationStatus` are the authorities.
- **A second readiness service or resource validator.**
- **A second snapshot model; campaign config versioning; `MAX(version)+1`;
  legacy execution fallback** — explicitly forbidden, and nothing found needs it.
- **FreeSWITCH / ESL / telephony changes** — VB-6E owns the protocol.
- **Predictive / progressive / preview dialing.**
- **Did-scoped agent filtering** — a real gap (§18) but a runtime change.
- **Campaign↔agent lifecycle coupling** (e.g. "cannot suspend an agent assigned
  to a live campaign") — a new invariant the brief does not ask for; runtime
  outcome codes already handle the case.
- **Unrelated cleanup or refactoring** — including the pre-existing
  `DtmfTimeoutScheduler` / `AgentConnectTimeoutScheduler` split.
- **`docs/campaign-readiness.md` and `backend/docs/future-hardening.md`** —
  user-owned; untouched, unstaged, uncommitted.

---

## 25. Final Verdict

# BLOCKED — DECISION REQUIRED

Not "blocked by an architecture defect" — the architecture is sound, the
foundation is excellent, and **VB-7A introduces zero cycles under every option**.
Blocked because the repository supports two incompatible readings of the central
question, and choosing one is a product decision the audit is forbidden to make.

### What is ready, with no decisions needed

- The agent/queue/ACD foundation is complete, deterministic, tenant-scoped and
  well tested (23 agent/queue/ACD test classes).
- `campaign → voice.agent` is an existing sanctioned edge, and `AgentRepository`
  is reachable today with no exposure change.
- `campaigns.type_config` and the snapshot's `type_config` JSONB already exist —
  **no migration is required**, the same route VB-6F used.
- `CampaignTypeConfig` is a sealed interface with an exhaustive switch, so
  CONNECT_BY_AGENT-only enforcement is **structural**, not a new validator.
- `CampaignResourceValidationService` and `CampaignReadinessService` are the
  declared single authorities and both already have a non-leaking tenant-ownership
  pattern to copy.
- `AgentAdminStatus` vs `AgentAvailability` is already a two-column, two-enum
  design with a written rationale for configuration-vs-runtime separation.

### Why it is blocked

**OD-2 is unresolvable from the code.** `CampaignType.CONNECT_BY_AGENT`'s own
javadoc says *"an agent/queue"*. `ConnectByAgentService`'s javadoc says *"no queue
invented"*. `findEligibleOrdered` filters on `tenant_id` and nothing else. Queues
are fully built; the outbound runtime is queue-blind and `AcdService` requires a
`waitingCallId` an outbound call does not have. Both "campaign → queue" and
"campaign → tenant-wide pool" are fully representable and fully consistent with
different parts of the codebase. There is no evidence that discriminates.

**OD-3 compounds it.** A CONNECT_BY_AGENT campaign has **no execution service at
all** — every `PlaybackTrigger` guards on campaign type, so the campaign is
dialed, answered, and then ignored. The configuration VB-7A is asked to build
would be read by nothing. Shipping it is a documented lie; not shipping it fails
the phase's purpose. The brief's scope ("configuration/readiness work only",
"no new agent runtime") and its goal (a working CONNECT_BY_AGENT configuration)
are in direct tension, and only the product owner can resolve it.

**OD-1 is a genuine compatibility conflict.** VB-6A deliberately kept
"any non-empty object" and two tests assert it. The brief forbids compatibility
shims "unless the audit finds an actual existing compatibility requirement" — the
audit found a *documented, tested* one, which is the interesting case, but whether
it represents real clients or a placeholder is unknowable from the repository.

**OD-4 is recommended-for-confirmation only.** The repository answers it (A:
`admin_status`/`QueueStatus` gate activation like DID approval; `availability`
never gates), but the brief explicitly asked for it to be raised.

### Concrete next step

Answer **OD-2** and **OD-3**. Those two answers determine whether VB-7A is a
small, clean configuration phase (P0 item 1 plus P1) or a configuration-plus-runtime
phase. OD-1 and OD-4 can be resolved in the same conversation but do not change
the shape of the work.

**The audit changed nothing.** No production source, test, migration, configuration
or documentation was modified. Only this report was created. Nothing was staged,
committed, or pushed.
