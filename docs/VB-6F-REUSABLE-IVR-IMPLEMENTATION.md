# VB-6F — Reusable IVR Trees + Multi-Level DTMF — Implementation

> **Status: COMPLETE.** Implements the full scope of
> `docs/VB-6F-REUSABLE-IVR-AUDIT.md`. Reusable finite IVR trees, multi-level DTMF,
> integrated into the *existing* DTMF runtime. No telephony was redesigned.
>
> **Baseline:** commit `544b260`, Flyway **V52**, suite **1478 / 0 F / 0 E / 1 S**,
> 0 Modulith cycles, 15 modules.

---

## 1. What VB-6F actually is

VB-2 already ships a **working single-level DTMF runtime**. Its own javadoc says
where it stops: *"No IVR framework: one expected sequence, one timeout, explicit
invalid-input behavior (terminal INVALID, no retry)."* VB-6F is exactly the work
that sentence defers, and nothing else.

So this phase adds three things and reuses everything else:

| # | Added | Why it could not be reused |
|---|---|---|
| 1 | A **reusable tree resource** | the flow lived in each campaign's `type_config`, so an identical menu was authored once per campaign |
| 2 | A **per-step state table** (`ivr_steps`) | `dtmf_interactions` is one row per call *session*, so it structurally cannot record which node of a multi-level tree the caller is on, per-node retry counters, or a per-node deadline |
| 3 | **Invalid-input and no-input retry** | the single-level runtime terminalises a wrong digit immediately |

Everything else is reuse: the `CHANNEL_DTMF` boundary, the `DtmfCollectorTrigger`
interface, the 1-second `DtmfTimeoutScheduler`, the atomic-claim idempotency
idiom, the execution snapshot mechanism, `CampaignResourceValidationService` for
prompt governance, and VB-6E's `MediaUriResolver` for playback.

**No second DTMF consumer. No second scheduler. No new telephony. No new
infrastructure.**

---

## 2. A VB-6E cross-phase regression this phase had to fix

The audit found, and implementation confirmed, that **VB-6E's media-URI fix was
applied to `PlayfileExecutionService` but never to `DtmfExecutionService`**:

```java
// DtmfExecutionService:216 — the defect VB-6E was chartered to remove
mediaController.playAudio(session.getId(), legIdOf(session), asset.getStorageReference());

// PlayfileExecutionService:214,232 — the VB-6E fix
mediaUri = mediaUriResolver.resolveMediaUri(...);
mediaController.playAudio(session.getId(), legIdOf(session), mediaUri);
```

So every DTMF campaign still handed `audio/<tenant>/<asset>/<file>.wav` to
`uuid_broadcast`, which FreeSWITCH resolves against its **own** sound directory.
DTMF campaign playback could not work in a real deployment — the same defect
VB-6E repaired for PLAYFILE, in the one campaign type VB-6F builds on.

Fixed in this phase, because IVR prompts traverse exactly this path and the
acceptance criteria require prompt audio governance to be *reused and working*.
`DtmfExecutionServiceTest.dtmfCampaignPlaysAudio` and the three other DTMF
fixtures were moved to the canonical reference shape and now assert the resolved
path. This is a regression **fix**, not a regression risk: all 57 pre-existing
DTMF tests remain green and unmodified in intent.

---

## 3. Domain model

### 3.1 Placement — the decisive architectural choice

```
authz ─▶ (nothing new)
audio ─▶ (nothing new)                     MUST NOT import campaign or ivr
voice ─▶ (nothing new)                     LEAF
  └── voice.ivr                            new sub-package, @NamedInterface("ivr")
telephony ─▶ campaign                      pre-existing, unchanged
ivr ─▶ voice.ivr, authz, common            NEW resource module
campaign ─▶ ivr, voice.ivr, voice.dtmf, audio, telephony
```

Two facts, both verified by reading the code before designing, forced this:

1. `com.shivang.obd.voice` imports **nothing** from `campaign` or `audio`. It is a
   leaf, and `DtmfInteraction` references the campaign only as a **bare UUID
   column** — deliberately, to keep it one.
2. `DtmfExecutionService` lives in `campaign`, not `voice`, because it needs
   `CallAttempt`, `CampaignExecution` and `CampaignRuntimeConfigResolver`.

VB-6F follows that split exactly: **pure domain in the leaf, orchestration in
`campaign`.** Putting the IVR domain in `ivr` and the runtime in `campaign` gives
one new legal edge and zero cycles.

One thing `ivr` genuinely cannot do alone is check prompt resources, because the
authority (`CampaignResourceValidationService`) lives in `campaign` — and
`campaign` already depends on `ivr`. That is reached through
`IvrPromptChecker`, a port implemented by `CampaignIvrPromptGovernance`. This is
the codebase's own pattern, used twice already (`DtmfCollectorTrigger`,
`PlaybackTrigger`, and `AgentConnectTrigger` as an `ObjectProvider`).

### 3.2 Entities

| Type | Purpose |
|---|---|
| `IvrTree` | the reusable resource: tenant, name, `status`, `rootNodeId`, `sourceCampaignId` |
| `IvrNode` | one step: key, `nodeType`, prompt + invalid + no-input asset ids, wait, both retry budgets, `terminalAction` |
| `IvrTransition` | one edge: `treeId`, `nodeId`, `dtmfInput`, `targetNodeId` |
| `IvrStep` | per-node live state for one call (the IVR analogue of `dtmf_interactions`) |

Two deliberate modelling choices:

- **`IvrTransition` carries `treeId` although its node already has one.** The
  redundancy *is* the constraint: V53 declares two composite foreign keys against
  `ivr_nodes(id, tree_id)`, pinning both endpoints to the same tree. A
  cross-tree transition cannot be written at all.
- **Prompts are bare audio-asset UUIDs.** The `voice` module must not depend on
  `audio` or `campaign`. The same discipline `DtmfInteraction` uses for
  `campaignId`.

Nodes and transitions are **relational, not JSONB**. A tree is a navigable,
inspectable, constraint-bearing resource, and the repository's convention for
resources (`audio_assets`, `did`, `tts_templates`) is typed relational. JSONB is
used for the frozen execution snapshot, which is the one place a bounded
validated value is the better representation.

---

## 4. Retry semantics — the ambiguity the brief asked to resolve

> **`invalidInputRetries` / `noInputRetries` = the number of ADDITIONAL attempts
> granted after the initial one.** `0` means one attempt in total. `2` means up to
> three.

Chosen to match the existing **retry** reading (`RetryPolicySpec.maxAttempts` is
VB-6D.2's *retries*, not total attempts) rather than the *total* reading
(`DtmfConfig.maxDigits`). Stated in the OpenAPI description, asserted by
`RT-11` (`retries=0` ⇒ one attempt) and `RT-12` (`retries=2` ⇒ three attempts),
and documented on the DTOs — so it cannot be misread later.

**IVR retries are not campaign retries.** This is the single most dangerous
confusion the phase could introduce, so it is pinned two ways:

- behaviourally — `RT-24` shows a re-ask reuses the same session and attempt and
  issues no dial;
- **structurally** — `RT-25` reflects over `IvrExecutionService`'s fields and
  constructor parameters and asserts it has **no** reference at all to
  `RetryPolicyService`, `DailyAttemptSafetyService` or `DailyDialLimitService`.
  The two domains cannot be confused even by accident.

Exhausting a node's budget ends the call and **does not fail the
`CallAttempt`** (`RT-13`): an exhausted input budget is a conversation outcome,
not a dispatch failure, and failing it would push a caller-input outcome through
the campaign retry policy.

---

## 5. DTMF runtime integration

`DtmfExecutionService` remains the **only** `CHANNEL_DTMF` consumer. Each of its
four entry points gained one branch, taken *first*, so a campaign without an IVR
snapshot reaches the single-level code unchanged:

| Event | IVR path | Single-level path |
|---|---|---|
| `onAnswered` | open the root step, play its prompt | validate asset, play campaign audio |
| `onPlaybackCompleted` | arm the wait → `WAITING_FOR_DTMF` | create `dtmf_interactions` |
| `onDtmfDigit` | resolve against the frozen snapshot | `DtmfCollector.feed` |
| `onDtmfTimeout` | per-node no-input handling | `TIMEOUT` terminalisation |

The snapshot is read from the **frozen execution configuration** only
(`config.asIvr()`), never the live campaign — the VB-6A rule, applied unchanged.

### 5.1 Per-node state, and why a new table

The current node of a call is **the newest `WAITING_INPUT` row for its session** —
one lookup, mirroring how the single-level runtime finds its one interaction.
Nothing IVR-related is written to `call_sessions`, so there is never a second
source of truth.

`ivr_steps` is **self-describing**: it freezes the node's wait *and* both retry
budgets. The per-node timeout path is dispatched with a session id only, so the
budget has to be on the step; resolving the execution there would be slower and
would put a live-tree read on the timeout path. `dtmf_interactions` freezes its
whole configuration for the same reason.

### 5.2 Timeout — no second scheduler

`ivr_steps.expires_at` is scanned by the **existing** `DtmfTimeoutScheduler` at
its existing 1-second cadence, which gained one extra query after the
single-level one. The brief forbids a new scheduler, a new timer framework and a
second thread; folding the scan into the poller that already bounds how late a
DTMF timeout can fire is what makes that true.

### 5.3 Idempotency — no second framework

The atomic claim idiom is reused verbatim on `ivr_steps`
(`UPDATE … WHERE id = :id AND result = 'WAITING_INPUT'`). Duplicate digits, late
digits, digits after a transition, digits after a terminal action and digits
after hangup are therefore all no-ops, and a digit racing the timeout poller has
exactly one winner (`RT-18`, `RT-19`, `RT-20`, `RT-21`, `RT-22`).

### 5.4 Maximum call duration

`IvrExecutionService` **never writes** `call_sessions.deadline_at`. VB-6E's single
authoritative deadline stands, so entering another node can neither extend nor
reset a call's total time. A call that runs long is still terminated by the VB-6E
reconciler, exactly as for PLAYFILE.

---

## 6. Snapshot design

`IvrExecutionSnapshot` is a **flat, keyed, immutable** value: nodes in a map by
`nodeKey`, transitions frozen as digit → target **key** (not UUID).

That shape buys three properties at once:

- traversal is a **map lookup** — no live IVR read, no UUID resolution, no
  ordering ambiguity;
- it is **self-contained**, so an archived or soft-deleted tree leaves historical
  executions fully runnable and auditable;
- it is **immutable** — every collection is copied at construction, and
  `SNAP-8`/`SNAP-9` prove neither the node map nor a transition map can be
  mutated.

It is serialised into the **existing** `campaign_execution_configurations.type_config`
JSONB under an `"ivr"` key, written once at execution creation, on a column that
is already `updatable = false`. So VB-6F needs **no snapshot table, no IVR
versioning, no `MAX(version)+1`, no history table, no compatibility fallback and
no legacy branching** — the VB-6A immutability guarantee applies to the IVR for
free.

Nodes are written in `nodeKey` order, so the same tree always produces
byte-identical JSON (`SNAP-16`), and the round trip is lossless for every prompt,
budget, timing, terminal action and edge (`SNAP-14`).

### 6.1 What is deliberately *not* frozen

Prompt assets are frozen as **identities**; ownership, approval and storage
availability are still checked at playback time through
`CampaignResourceValidationService`. That is the VB-6A §9 split — the snapshot
pins *what was requested*, governance decides whether it may still be used — and
inventing a different split would be a new snapshot philosophy.

`CIVR-12` is the brief's Example 6: an execution keeps the tree it captured after
the live tree is edited, and a **new** execution picks the edit up.

---

## 7. Prompt / audio / TTS governance

One authorization system, not two. `CampaignIvrPromptGovernance` delegates every
question to `CampaignResourceValidationService.validateAudio(id, tenantId)` and
holds **no** ownership or approval rule of its own. It is asked:

1. on **activation**, so a tree whose prompts are unusable cannot become
   selectable;
2. on **snapshot capture**, so a call never starts against a revoked prompt;
3. again at **playback**, so a resource revoked mid-call is refused rather than
   dialled (`RT-5`).

Playback goes through `VoiceMediaController` with a URI from VB-6E's
`MediaUriResolver` — the raw storage reference is never sent (`RT-3`).

**TTS prompts are out of scope.** VB-6E established (OD-B) that TTS synthesis does
not exist, so a TTS prompt would create a configuration that fails 100% of calls.
Prompts are AUDIO only. Recorded as a decision, not an omission.

---

## 8. Tree validation

`IvrTreeValidator` is **pure** — no Spring, no repositories, no I/O — so every rule
is directly unit-testable and the same instance serves all four places the brief
requires validation: on edit, on activation, on attach-to-campaign and on snapshot
capture. It detects missing/multiple roots, duplicate `(node, digit)`
transitions, dangling targets, cross-tree targets, the invalid DTMF alphabet,
**cycles**, **unreachable nodes**, MENU-without-transitions,
TERMINAL-with-transitions, TERMINAL-without-action, MENU-with-action, and
out-of-range waits and retries. `VAL-26` proves a validator whose answer drifted
between calls would be a defect, by asserting determinism.

**Cycles are rejected.** The brief's default recommendation is adopted.

> **Correction to the audit.** The audit originally claimed "return to a previous
> menu" was already expressible, because a transition may target any node in the
> same tree including an ancestor. **That was wrong** — a back-edge *is* a cycle by
> graph definition and permits an unbounded walk. `IvrTreeValidatorTest
> .backwardTransitionIsAcycle` pins the rejection. A "go back" affordance needs a
> bounded structure (a parent pointer applied on retry, or a re-prompt node per
> level), which is a future phase.

The cycle detector is **iterative**, not recursive, so a pathological tree cannot
overflow the stack — the validator must survive the input it exists to reject.

---

## 9. Database — V53

Forward-only; V1–V52 untouched.

| Object | Note |
|---|---|
| `ivr_tree_status`, `ivr_node_type`, `ivr_terminal_action`, `ivr_step_result_type` | PostgreSQL enums, following `V35`'s convention |
| `ivr_trees`, `ivr_nodes`, `ivr_transitions`, `ivr_steps` | 4 tables |
| `UNIQUE (tree_id, node_key)` | one key per tree; the same key in two trees is fine |
| `UNIQUE (node_id, dtmf_input)` | one digit, one target |
| `CHECK dtmf_input ~ '^[0-9*#]$'` | the existing runtime's alphabet, no new semantics |
| `CHECK (node_id, tree_id) → ivr_nodes(id, tree_id)` | the source node is in this tree |
| `CHECK (target_node_id, tree_id) → ivr_nodes(id, tree_id)` | **a cross-tree target is unrepresentable** |
| `CHECK (root_node_id, id) → ivr_nodes(id, tree_id)` | a tree's root is in that tree, `DEFERRABLE` so root+root-row can be inserted together |
| `CHECK node_id <> target_node_id` | no self-loops |
| wait / retry bounds, `deadline_at >= answered_at` analogues | bounds duplicated from the code constants, as V48/V50/V51/V52 did |
| `idx_ivr_steps_timeout (result, expires_at)` | the existing poller's access path |

**Nothing existing was modified**: no change to `dtmf_interactions`,
`call_sessions`, `call_attempts`, `campaigns` or
`campaign_execution_configurations`.

### 9.1 The constraints are proven, not asserted

`IvrPostgresConstraintTest` runs against **real PostgreSQL** and attempts each
illegal write directly, asserting the **database** refuses it — cross-tree
transition, duplicate digit, duplicate node key, invalid digit, self-loop,
out-of-range wait, negative retries, and a root belonging to another tree. A
validation-only guarantee would still permit the row through any other writer.

The test is deliberately **not** `@Transactional`: a constraint violation is only
raised at commit, so a nested transaction would join the outer one and the
violation would surface outside the assertion. Fixtures commit and are torn down
in `@AfterEach`.

---

## 10. Campaign integration

A DTMF campaign now has two valid shapes, and `CampaignTypeConfig` dispatches on
which key is present:

```json
{"dtmf": {"expected": "1", "timeoutSecs": 10, "action": "TERMINATE"}}   // unchanged
{"ivr":  {"treeId": "…"}}                                               // VB-6F
```

No new `CampaignType`. A reusable IVR tree **is** a DTMF interaction with the
same runtime; a new type would fork the dial, compliance, routing, snapshot and
retry paths for no behavioural difference. The campaign stores a **reference**
only — `CIVR-6` asserts the tree's nodes are *not* embedded, since duplicating
them would create two sources of truth.

### 10.1 Create-IVR-from-campaign (§16)

```
DTMF campaign {"dtmf":{expected, timeoutSecs, action}}
  → IvrTree (created DRAFT, then activated — it is known-valid)
  → root MENU node   prompt = the campaign's own audio asset
                     inputWaitSeconds = timeoutSecs
  → one transition   expected → a TERMINAL node
  → that node        action = the campaign's action
  → campaign typeConfig rewritten to {"ivr":{treeId}}
```

**Behaviour is preserved exactly**: the caller hears the same prompt, presses the
same digit, gets the same action, and times out at the same moment
(`CIVR-1`…`CIVR-4`). What changes is that the flow is now editable and
shareable.

- **Idempotent** — a campaign that already references a tree returns that tree
  unchanged (`CIVR-8`), so a retried request cannot create a second one.
- **Transactional** — tree, nodes, transitions and the campaign's new config are
  written together, so a failure leaves the campaign on its original
  configuration.
- **Validated** — `CIVR-5` re-runs the real validator over what the conversion
  produced, because a converted tree that could not be activated would be
  useless.

`POST /api/v1/campaigns/{id}/ivr-tree` follows the existing campaign REST
conventions.

---

## 11. Lifecycle

`DRAFT → ACTIVE → ARCHIVED`, mirroring `TtsTemplateStatus` / `CampaignStatus`.

- **Activation** re-validates structure *and* prompt resources. The re-check is
  not redundant: a tree written while its assets were approved can be invalid by
  activation time.
- **Archiving is not a delete.** A captured snapshot is self-contained, so calls
  created while the tree was active keep working and stay auditable.
- **No return to DRAFT**, because existing snapshots must keep the flow they were
  created with; create a new tree instead.
- Only `ACTIVE` trees are selectable (`TEN-9`, `TEN-10`).

---

## 12. API

Following `TtsTemplateController` exactly — `ApiResponse<T>`, `page/size/status`,
`@Tag`/`@Operation`/`@ApiResponse` per operation, `@SecurityRequirement`.

| Method | Path |
|---|---|
| `POST` | `/api/v1/ivr-trees` |
| `GET` | `/api/v1/ivr-trees` |
| `GET` | `/api/v1/ivr-trees/{id}` |
| `PUT` | `/api/v1/ivr-trees/{id}` |
| `DELETE` | `/api/v1/ivr-trees/{id}` |
| `POST` | `/api/v1/ivr-trees/{id}/status` |
| `POST` | `/api/v1/campaigns/{id}/ivr-tree` |

**Why the whole tree is one request.** A tree is only meaningful as a complete
structure: one root, no cycles, every node reachable. Submitting it in one call is
what lets it be validated atomically, so an invalid tree can never be persisted
and then activated. Per-node endpoints would deliberately allow that state, so
there are none.

`GET` returns the structure **inline**, so the menu a caller experiences is
directly readable from one response.

**OpenAPI verified against the running document**, not against source annotations
(`IvrOpenApiContractTest`, 9 tests): all four paths, the `1..120` wait bounds,
`0..10` retry bounds, the `ADDITIONAL`-attempts wording, the statement that an
IVR retry never creates a call attempt or invokes the campaign retry policy, the
`[0-9*#]` pattern, the same-tree rule, the lifecycle enum, the inline menu, prompt
governance wording, and bearer security on every operation.

---

## 13. Tenant isolation

Every repository lookup is bounded by `(id, tenantId)`, so a foreign tree **does
not resolve** rather than failing an authorization check. That is why a foreign
tree and a nonexistent one are indistinguishable — 11 negative tests
(`IvrTenantIsolationTest`): read, list, update, activate, delete, create-ownership,
select-for-execution, and prompt checks.

The create request carries **no tenant field at all**, so ownership can only come
from the caller's organizational boundary (`TEN-7`).

---

## 14. Tests

| Suite | Tests | Proves |
|---|---|---|
| `IvrTreeValidatorTest` *(new)* | 48 | one root, duplicate digits, cross-tree targets, invalid DTMF, self-loops, **cycles**, **unreachable nodes**, node-type consistency, wait/retry bounds, determinism |
| `IvrExecutionSnapshotTest` *(new)* | 20 | multi-level traversal (3+ deep), the full DTMF alphabet, immutability, lossless JSON round trip, deterministic writing, dotted keys, malformed-snapshot refusal |
| `IvrExecutionServiceTest` *(new)* | 26 | answer→root, resolved media URI, multi-level walk, invalid input, no input, **exact** retry budgets, terminal action, duplicate/late/expired digit safety, abandon, and the structural no-campaign-retry guarantee |
| `IvrPostgresConstraintTest` *(new)* | 10 | the **schema** refuses cross-tree, duplicate, invalid, self-loop and out-of-range writes — real PostgreSQL |
| `IvrTenantIsolationTest` *(new)* | 11 | every cross-tenant path refused; foreign ≡ nonexistent |
| `IvrFromCampaignServiceTest` *(new)* | 12 | behaviour preservation, valid output, repointing to a reference, idempotency, refusals, and snapshot immutability |
| `IvrOpenApiContractTest` *(new)* | 9 | the generated document, as above |

**All 57 pre-existing DTMF tests remain green** (`DtmfExecutionServiceTest` 18,
`DtmfConfigTest` 15, `DtmfCollectorTest` 13, `DtmfLifecycleIntegrationTest` 4,
`DtmfEslEventServiceTest` 4, `DtmfAgentActionTest` 3) alongside the 98-test DTMF +
PLAYFILE set. Nothing was deleted, disabled, `@Disabled` or excluded.

No test was weakened. Three DTMF fixtures moved to the canonical storage-reference
shape and now assert the **resolved media path** — the same update VB-6E made to
the PLAYFILE fixtures — because the contract genuinely changed.

---

## 15. Known limitations

1. **No live FreeSWITCH was executed.** There is no FreeSWITCH in this environment
   and none in `infra/docker-compose.yml`. The IVR runtime is proven against real
   persistence, a real snapshot, and the real media boundary; a live SIP/DTMF run
   is unverified, exactly as VB-4D/4E/4F and VB-6E stated.
2. **`telephony.freeswitch.enabled` is `false`** in the shipped `dev` profile, so
   a default local run still uses the no-op adapters, which fail loudly.
3. **`min_digit_duration` is not implemented.** A digit is processed as reported.
   Adding it is a telephony change, which the brief forbids.
4. **No "go back" affordance.** Cycles are rejected (§8), so returning to a
   previous menu needs a bounded structure in a future phase.
5. **Exhausting a node's input budget ends the call silently** — no farewell
   prompt, because the step row carries budgets rather than prompt ids and
   resolving the snapshot on the timeout path would put a live-tree read there.
6. **TTS prompts are unsupported** (§7) — no synthesis runtime exists.
7. **Node-count and depth are bounded only by the request `@Size(max = 200)`.**
   Cycle detection makes any shape safe, but a pathologically deep tree is the
   operator's choice to make, not something this phase second-guesses.
8. **`@Version` / optimistic locking is still absent** platform-wide (VB-6E
   audit OD-E). The IVR write path is a single whole-tree transaction.
9. **OD-2 remains open** (carried from VB-6D): `SWITCHED_OFF` / `NOT_REACHABLE`
   have no target-carrier cause list.

---

## 16. Scope not touched

Reusable-IVR-adjacent work that is explicitly **not** in this phase: voice AI,
speech recognition, natural-language IVR, a general workflow engine, omnichannel,
WhatsApp flows, a drag-and-drop builder, analytics, **IVR version history**,
**cross-tree transitions**, **unrestricted graph/cycle support**, a new telephony
engine, a new FreeSWITCH protocol implementation, a new scheduler, a new event
bus, and campaign versioning. `docs/campaign-readiness.md` was read but **not
modified**.

---

*Canonical VB-6F record. The audit this implements is
`docs/VB-6F-REUSABLE-IVR-AUDIT.md`.*
