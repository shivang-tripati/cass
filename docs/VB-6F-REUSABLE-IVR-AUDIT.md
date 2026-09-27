# VB-6F — Reusable IVR Trees + Multi-Level DTMF — Audit

> **Phase:** audit-only. No production code, migration, or test was changed while
> producing this document. The only write was this file.
>
> **Baseline:** commit `544b260`, Flyway **V52**, suite **1478 / 0 F / 0 E / 1 S**,
> ArchitectureTest **1 / 0 / 0 / 0**, **0 Modulith cycles**, 15 modules.

---

## 1. The headline finding

The repository already contains a **complete, correct, working single-level DTMF
runtime**. VB-6F is therefore *not* a build-from-scratch phase. It is the
evolution of a proven foundation, and the audit's single most important
conclusion is architectural:

> **Do not build a second DTMF runtime, and do not put the IVR domain where it
> would create a module cycle.**

Two independent facts drive the whole design, and both were verified by reading
the code rather than inferring it:

1. `com.shivang.obd.voice` imports **nothing** from `campaign`, `audio`, or any
   other domain module. It is a **leaf**. `DtmfConfig`, `DtmfCollector` and
   `DtmfInteraction` all live there, and `DtmfInteraction` references the
   campaign only as a **bare `UUID` column** — deliberately, so the leaf stays a
   leaf.
2. `DtmfExecutionService` lives in **`campaign`**, not `voice`, because it needs
   `CallAttempt`, `CampaignExecution` and `CampaignRuntimeConfigResolver`. The
   split is already *pure domain in `voice`, orchestration in `campaign`*, with
   `voice` depending on `DtmfCollectorTrigger` (an interface) that `campaign`
   implements.

VB-6F follows that split exactly. The domain goes in the leaf; the runtime goes
in `campaign`; the existing trigger interface is reused. **No new module, no new
event boundary, no new scheduler.**

---

## 2. What exists today (verified)

### 2.1 The current DTMF flow

```
Campaign(DTMF)
  └─ type_config JSONB  {"dtmf":{expected,maxDigits,terminator,timeoutSecs,action}}
       │
       ▼  CHANNEL_ANSWER
DtmfExecutionService.onAnswered          validate AUDIO asset (VB-5E authority)
  └─ mediaController.playAudio(...)      → FreeSWITCH
       │
       ▼  PLAYBACK_STOP
DtmfExecutionService.onPlaybackCompleted
  ├─ parse DtmfConfig from the IMMUTABLE EXECUTION SNAPSHOT (VB-6A)
  ├─ INSERT dtmf_interactions  (result=COLLECTING, expires_at=now+timeout)
  └─ session → WAITING_FOR_DTMF
       │
       ▼  CHANNEL_DTMF
DtmfExecutionService.onDtmfDigit
  ├─ load the single interaction for the session
  ├─ DtmfCollector.feed(config, collected, digit)      ← PURE classifier
  ├─ non-terminal  → persist collected digits
  └─ terminal       → DtmfResultService.finalizeInteraction  ← ATOMIC CLAIM
                      VALID + action=CONNECT_BY_AGENT → AgentConnectTrigger
                      otherwise                             → hangUpCall()
       │
       ▼  expires_at < now  (DtmfTimeoutScheduler, 1 s poll)
DtmfExecutionService.onDtmfTimeout → TIMEOUT → hangUpCall()
```

### 2.2 Existing DTMF runtime components, and how reusable they are

| Component | Location | Reusable? | Note |
|---|---|---|---|
| `DtmfConfig` | `voice.dtmf` | **Yes, as-is** | strict/total parser; `isCollectableDigit` is the DTMF alphabet (`0-9 * #`); `DtmfConfig.of(interaction)` rebuilds config from the persisted snapshot |
| `DtmfCollector` | `voice.dtmf` | **Partly** | pure, stateless — but built around a single `expected` sequence. Cannot express "any of several digits each mapping somewhere". Needs a *sibling*, not a rewrite. |
| `DtmfInteraction` | `voice.dtmf` | **No — must not change** | one row per session (`findByCallSessionIdAndDeletedAtIsNull` returns `Optional`). Multi-level IVR needs *many* rows per session. |
| `DtmfResultType` | `voice.dtmf` | **Yes** | `COLLECTING/VALID/INVALID/TIMEOUT/ABANDONED` |
| `DtmfResultService` | `voice.dtmf` | **Yes, as a pattern** | the atomic-claim idiom (`claimTerminal`) is exactly what IVR needs; must not be reused directly (wrong table) |
| `DtmfTimeoutScheduler` | `voice.dtmf` | **Yes, extended** | already the single 1 s poller; the brief forbids a second scheduler, so IVR steps are folded into this one scan |
| `DtmfCollectorTrigger` | `voice.media` | **Yes** | the single DTMF event boundary; IVR rides the same two methods |
| `DtmfExecutionService` | `campaign` | **Yes, extended** | the only DTMF consumer; gains an IVR branch, keeps the single-level path byte-for-byte |
| `VoiceMediaController` | `voice.media` | **Yes** | `playAudio(sessionId, legId, audioUri)` |
| `DtmfActions` | `voice.dtmf` | **Yes** | `TERMINATE` / `CONNECT_BY_AGENT` — the terminal-action vocabulary already exists |

### 2.3 The `expected`-sequence limitation (the actual gap)

`DtmfCollector.feed` decides by `config.expected()`: a digit is `COLLECTING`
while it is a prefix of `expected`, `VALID` on exact match, `INVALID`
otherwise. A single expected value therefore means:

- **one** acceptable answer per interaction, and
- **zero** retries — the javadoc says so explicitly: *"No IVR framework: one
  expected sequence, one timeout, explicit invalid-input behavior (terminal
  INVALID, no retry)."*

That last sentence is the code stating its own boundary. VB-6F is exactly the
work that sentence defers.

### 2.4 Interaction/session state model — and where the current node belongs

The brief asks whether the active node belongs in `call_sessions`, interaction
state, execution state, or the existing DTMF state object. **The evidence
answers this: the existing DTMF state object.**

`V35__add_dtmf_interaction.sql` records the decision explicitly:

> *"The granular result lives on `dtmf_interactions` (single source of truth);
> duplicating VALID/INVALID/TIMEOUT on the session would create two sources of
> truth."*

`CallSessionStatus` carries only the coarse `WAITING_FOR_DTMF`. So the current IVR
node, the retry counters and the per-node deadline must live in **one new
per-step table**, not on `call_sessions`. This is the one place the brief's
"Do not duplicate the same state across multiple tables" forces a new table, and
it is the IVR analogue of `dtmf_interactions` rather than a duplicate of it.

### 2.5 Snapshot behaviour (VB-6A)

`campaign_execution_configurations.type_config` is `JSONB`
(`CampaignConfigurationSnapshot.typeConfig`, `@JdbcTypeCode(SqlTypes.JSON)`), and
it is written **once** at execution creation
(`CampaignConfigurationService.createExecutionSnapshot`, and the columns are
`updatable = false`). Runtime resolves through
`CampaignRuntimeConfigResolver.resolve(execution)`; `requireExecutionSnapshot`
has **no fallback to the live campaign** by design.

`DtmfExecutionService` already reads DTMF config *exclusively* from this
snapshot (`config.asDtmf()`), and comments that the live campaign is never
consulted. **That is the pattern to extend**: the frozen IVR tree goes into the
same `type_config` JSONB. No new snapshot table, no IVR versioning, no history.

### 2.6 Prompt / resource governance

`CampaignResourceValidationService` is the single authority
(`validateAudio(id, tenantId)` → `ResourceValidationResult(usable, code)`;
codes `AUDIO_NOT_AVAILABLE`, `AUDIO_NOT_APPROVED`, …). Ownership, approval and
storage-reference semantics are already enforced there, and `DtmfExecutionService`
already calls it. VB-6E added `MediaUriResolver` in `audio` for the
logical-reference → FreeSWITCH-path translation.

### 2.7 Authorisation and REST conventions

- `OrganizationContextHolder.current()` → `Scope.of(ctx)` → `(tenantId, resellerId)`
- `authorizationService.requireCapability(userId, CAP_X, AccessCheck.forTenant(id))`
- capability strings are **private constants in the service** (`TtsTemplateService`:
  `TTS_VIEW` / `TTS_MANAGE` / `TTS_APPROVE`)
- `TtsTemplateController` is the model: `/api/v1/<plural>`, `ApiResponse<T>`,
  `List<T>` + `page/size/sort/status/search`, `@Tag`/`@Operation`/`@ApiResponse`
  per operation, `@SecurityRequirement(name = "bearerAuth")`, and
  **"a foreign resource and a nonexistent resource are indistinguishable (404)"**

### 2.8 Database conventions

From `V35` and my own `V52`: forward-only, `gen_random_uuid()` PKs,
`tenant_id UUID NOT NULL REFERENCES tenants(id)`, `AuditableEntity` columns
(`created_at/updated_at/created_by/updated_by/deleted_at/deleted_by`),
`CREATE TYPE … AS ENUM` for statuses, explicit named indexes, and bounds
enforced by `CHECK` constraints duplicating the code constants.

---

## 3. VB-6E cross-phase regression found (must be fixed in VB-6F)

**`DtmfExecutionService:216` still passes the raw storage reference:**

```java
mediaController.playAudio(session.getId(), legIdOf(session), asset.getStorageReference());
```

`PlayfileExecutionService:214/232` (fixed in VB-6E) does:

```java
mediaUri = mediaUriResolver.resolveMediaUri(asset.getStorageReference(), asset.getId(), tenantId);
mediaController.playAudio(session.getId(), legIdOf(session), mediaUri);
```

VB-6E corrected the PLAYFILE path and left the DTMF path untouched, so **every
DTMF campaign still sends `audio/<tenant>/<asset>/<file>.wav` to `uuid_broadcast`**,
which FreeSWITCH resolves against its own sound directory. DTMF campaign playback
therefore cannot work in a real deployment — the same defect VB-6E was chartered
to remove, in the one campaign type VB-6F must build on.

This is in scope and unavoidable: IVR prompts traverse this exact path, and the
acceptance criteria require prompt audio governance to be *reused and working*.
Fixing it is a one-line-equivalent change in `DtmfExecutionService`, plus wiring
the resolver. It also hardens the existing single-level DTMF path, so it is a
regression **fix**, not a regression risk.

---

## 4. Gap analysis — what reusable IVR actually requires

| # | Gap | Consequence if unaddressed |
|---|---|---|
| G1 | No reusable tree resource; the flow lives in campaign `type_config` | cannot be shared by N campaigns; every change is N edits |
| G2 | `DtmfCollector` matches one `expected` value | no menus, no branching |
| G3 | One `dtmf_interactions` row per session | no per-node progress, no resume, no audit of where the caller got to |
| G4 | No invalid-input retry — a wrong digit is terminal `INVALID` | one mispress ends the call; unusable for real IVR |
| G5 | No no-input retry distinct from invalid | the brief requires them to be separate outcomes |
| G6 | No per-node prompt | cannot play a different prompt per menu |
| G7 | No terminal-action model beyond a single interaction-wide `action` | cannot hang up at one leaf and connect to an agent at another |
| G8 | Prompt audio passes the raw storage reference | see §3 |
| G9 | No authoritative whole-tree validator | a broken tree is discovered on a live call |
| G10 | No lifecycle on the resource | unusable trees are selectable |

---

## 5. Proposed IVR domain

### 5.1 Module placement (the decisive choice)

```
module-voice (LEAF — imports nothing)
  └── voice.ivr
        IvrTree, IvrNode, IvrTransition          (entities)
        IvrTreeRepository, IvrNodeRepository, IvrTransitionRepository
        IvrTreeStatus, IvrNodeType, IvrTerminalAction
        IvrTreeValidator                          (PURE structural validation)
        IvrTraversal                              (PURE digit → target resolution)
        IvrExecutionSnapshot + IvrSnapshotCodec   (immutable value + JSON codec)
        IvrPromptSpec, IvrNodeSnapshot            (immutable value types)
        IvrStep, IvrStepResultType, IvrStepRepository, IvrStepClaimService
        IvrTimeoutPoller wiring via the EXISTING DtmfTimeoutScheduler

module-campaign (orchestration)
  └── IvrExecutionService      (plays prompts, routes digits, advances nodes)
      IvrTreeService / IvrTreeController / IvrController DTOs
      IvrCampaignConfig         ({"ivr":{...}} inside the DTMF type_config)
      DtmfExecutionService      (gains the IVR branch; single-level path unchanged)
```

`voice.ivr` must **not** import `campaign` or `audio`. Prompt assets are stored as
**bare UUIDs** in the node row and validated in `campaign` through the existing
`CampaignResourceValidationService` — the same trick `DtmfInteraction` uses for
`campaignId`, and the same discipline VB-6E applied to
`MediaUriResolver`'s javadoc when it had to drop a cross-module `{@link}`.

This direction is legal, provably acyclic, and mirrors the existing DTMF split.

### 5.2 Entities (simplest model that satisfies every requirement)

`IvrTree` — `id, tenantId, name, description, status, rootNodeId?, created/updated…`
`IvrNode` — `id, treeId, nodeKey, nodeType, promptAudioAssetId?, inputWaitSeconds,
invalidPromptAudioAssetId?, invalidInputRetries, noInputPromptAudioAssetId?,
noInputRetries, terminalAction?`
`IvrTransition` — `id, nodeId, dtmfInput, targetNodeId`

Deliberate choices:

- **No `treeId` on `IvrTransition`.** The parent node already carries the tree.
  Duplicating it would allow a transition row to disagree with its own node's
  tree, which is exactly the class of integrity bug the brief asks to prevent.
  Cross-tree reachability is enforced by validation over the in-memory tree plus
  composite FKs that pin `target_node_id` to the same tree.
- **Node configuration lives on the node, not in JSONB.** §7 of the brief asks
  for a decision here. The project already has typed relational resources
  (`audio_assets`, `did`, `tts_templates`, `contact_groups`) and a typed
  `CampaignTypeConfig` for *campaign* config only. An IVR tree is a navigable
  resource that must be inspectable, diffable and constraint-enforced, so it
  stays relational. JSONB is used for the **frozen execution snapshot**, which
  is the one place the brief's "bounded, validated snapshot is the better
  execution representation" clearly applies.
- **Retry counts live on the node, not per transition**, because invalid/no-input
  are properties of *asking a question*, not of any one answer.
- **One `nodeKey` per tree** gives stable, human-meaningful references in the API
  and in the snapshot without inventing a version history.

### 5.3 Prompt governance

An `IvrNode` prompt is a **bare `UUID` audio asset id**. Validation goes through
`CampaignResourceValidationService.validateAudio(id, tenantId)` — the existing
authority, unchanged. No second authorization system, no new provider
abstraction. Playback uses `VoiceMediaController.playAudio` with a URI produced
by VB-6E's `MediaUriResolver`. TTS prompts are **out of scope**: §14/§6 of the
brief permit TTS "only if those capabilities already exist and are
production-supported", and VB-6E established (OD-B) that **TTS synthesis does not
exist**. Prompts are therefore AUDIO-only, and the audit records that as
intentional deferral rather than a gap.

---

## 6. Retry semantics (the ambiguity the brief asks me to resolve)

The brief requires me to define whether the configured number means *retries
after the initial input* or *total attempts*, and to use the existing semantic
style. The existing style is **unambiguous and well documented**:

- `DtmfConfig.maxDigits` — "collection cap"; `maxDigits` is the **total** count.
- `RetryPolicySpec.maxAttempts` — VB-6D.2 counts **retries**; `maxAttempts` is
  validated `0..10` and "counts retries" was locked as a product decision, with
  `CountingRetryRule.count` likewise counting retries.

Because IVR input retries are *not* campaign retries but are still a
"how many more times may I ask" configuration, the closest semantic precedent is
the **retry** reading. I therefore define:

> **`invalidInputRetries` / `noInputRetries` = the number of ADDITIONAL attempts
> granted after the initial one.** `0` means one attempt total, then the node's
> terminal behaviour. `2` means up to three total attempts.

This is stated in the OpenAPI description, in the entity javadoc and in a test,
so it cannot be misread later. It is deliberately *not* "total attempts",
because that reading would silently double every operator's intent.

Critically, and asserted by tests: an IVR input retry
- creates **no** new `CallAttempt`,
- consumes **no** VB-6D.3 attempt,
- reserves **no** VB-6C accepted dial,
- and never calls `RetryPolicyService`.

Retry exhaustion resolves the node's **terminal action**; it does not fail the
call as an attempt failure.

---

## 7. Snapshot design

`IvrExecutionSnapshot` — a flat, immutable, fully-resolved value:

```
treeId, treeName, rootNodeKey
nodes:  [ nodeKey → { nodeType, promptAudioAssetId, inputWaitSeconds,
                     invalidPromptAudioAssetId, invalidInputRetries,
                     noInputPromptAudioAssetId, noInputRetries,
                     terminalAction,
                     transitions: [ dtmfInput → targetNodeKey ] } ]
```

**Flattened and keyed by `nodeKey`**, with targets expressed as keys rather than
UUIDs. Traversal then needs zero database reads, zero UUID resolution and zero
ordering ambiguity — traversal is a map lookup on an in-memory value.

It is serialised into the existing
`campaign_execution_configurations.type_config` JSONB under `{"ivr":{…}}`, written
once at execution creation, with the column already `updatable = false`.

**Resource governance stays dynamic, exactly as VB-6A §9 established:** the
snapshot freezes *what was requested* (the asset id); ownership, approval and
storage-availability are still checked at playback time through
`CampaignResourceValidationService`. This preserves the existing semantic split
rather than inventing a new snapshot philosophy.

No IVR versioning, no `MAX(version)+1`, no history table, no compatibility
fallback, no legacy branching — all explicitly out of scope.

---

## 8. Runtime integration

`DtmfExecutionService` remains the **only** DTMF event consumer. It gains one
branch at the top of each entry point:

```
config.asIvr()  present  →  ivrExecutionService.<same method>
config.asDtmf() present  →  the existing single-level code, untouched
```

Per-node live state lives in **`ivr_steps`**, one row per node visit:

```
tenantId, callSessionId, callAttemptId, executionId, treeId,
nodeKey, inputWaitSeconds, expiresAt,
invalidAttempts, noInputAttempts,
result (IvrStepResultType), resultAt
```

The **current node is the newest non-terminal `ivr_step` for the session** —
one lookup, exactly mirroring how the current DTMF interaction is found. No IVR
state is written to `call_sessions`, so §20's "do not duplicate state across
tables" is satisfied.

`WAITING_FOR_DTMF` is reused unchanged for every IVR node: it already means
"waiting for caller input", which is precisely the state between a prompt
finishing and a digit arriving. A new session status would duplicate it.

**Timeout:** `ivr_steps.expires_at` is scanned by the **existing**
`DtmfTimeoutScheduler` at its existing 1 s cadence, which gains one extra query
and dispatches `onIvrStepTimeout`. No second scheduler, no new timer framework.

**Idempotency:** the existing atomic-claim idiom is reused verbatim on
`ivr_steps` (`UPDATE … WHERE id = :id AND result = 'COLLECTING'`), giving:
duplicate DTMF, late DTMF, digit-after-transition, digit-after-terminal and
digit-after-hangup all become no-ops. No second idempotency framework.

**Max call duration (§29):** the IVR does not touch
`call_sessions.deadline_at`. VB-6E's single authoritative deadline stands; a node
wait can never extend or reset it. If the session deadline passes mid-IVR, the
VB-6E reconciler terminates the call exactly as it would for PLAYFILE.

---

## 9. Tree validation (`IvrTreeValidator`, pure)

Runs on **edit**, **activation**, **attach-to-campaign** and **snapshot
creation** — never on a live call. Detects: no root, multiple roots, duplicate
`(node, dtmf)` transition, target missing/dangling, cross-tree target,
cross-tenant anything, invalid DTMF character, **cycle**, **unreachable node**,
non-MENU node carrying transitions, TERMINAL node carrying a prompt-less
mismatch, invalid wait time, negative retries, unusable prompt asset.

**Cycles: rejected.** The brief's default recommendation is adopted and the
platform has no counter-argument: a finite tree gives a provable termination
bound, makes every `expires_at` finite, and keeps traversal a bounded walk.

> **Correction, found during implementation.** This audit originally claimed that
> "return to a previous menu" was already expressible, because a transition may
> target *any* node in the same tree including an ancestor. **That was wrong.**
> A back-edge to an ancestor *is* a cycle by graph definition, and it permits an
> unbounded walk, which is exactly what the brief prohibits. The validator and
> its tests therefore reject it, and
> `IvrTreeValidatorTest.backwardTransitionIsAcycle` pins that behaviour. A "go
> back" affordance needs a bounded, explicit structure — a parent pointer applied
> on retry, or a dedicated re-prompt node per level — which is a future phase
> rather than a graph edge.

Database-level enforcement (`V53`): `UNIQUE(tree_id, node_key)`,
`UNIQUE(node_id, dtmf_input)`, `CHECK` on the DTMF character class, `CHECK` on
wait/retry bounds, and a composite FK pinning `target_node_id` to a node of the
**same tree** — so a cross-tree transition is unrepresentable, not merely
rejected in Java.

---

## 10. Lifecycle

`DRAFT → ACTIVE → ARCHIVED`, mirroring the `TtsTemplateStatus` /
`CampaignStatus` convention (a small typed enum, never free strings).

- Only `ACTIVE` trees may be attached to a campaign or snapshotted.
- `ARCHIVED` is **not** a hard delete: historical execution snapshots are
  self-contained JSONB, so an archived tree's executions remain fully executable
  and auditable (§25).
- `DELETE` is a soft delete (`deleted_at`), consistent with every other resource.

---

## 11. Create-IVR-from-campaign-flow (§16)

A deterministic, **idempotent**, transactional conversion:

```
DTMF campaign {"dtmf":{expected,maxDigits,terminator,timeoutSecs,action}}
  → IvrTree (DRAFT→ACTIVE), name "<campaign name> IVR"
  → root IvrNode  (MENU, prompt = the campaign's own audioAssetId,
                   inputWaitSeconds = timeoutSecs)
  → one IvrTransition  expected → terminal IvrNode
  → that terminal node carries action = the campaign's action
  → campaign typeConfig rewritten to {"ivr":{treeId,…}}; audioAssetId cleared
```

Behaviour equivalence: the caller still hears the same prompt, still must press
the same digit, still gets the same action on success, and still times out after
the same number of seconds. The difference is that the result is now editable and
reusable.

Idempotency: if the campaign already references an IVR tree, the operation
returns that tree unchanged rather than creating a second one. Retrying is safe.

---

## 12. API surface (smallest complete set)

Following `TtsTemplateController` exactly:

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/ivr-trees` | create a tree (with nodes + transitions inline) |
| `GET` | `/api/v1/ivr-trees` | list, tenant-scoped, `page/size/sort/status/search` |
| `GET` | `/api/v1/ivr-trees/{id}` | inspect — **the menu is directly readable** |
| `PUT` | `/api/v1/ivr-trees/{id}` | replace editable fields |
| `DELETE` | `/api/v1/ivr-trees/{id}` | soft delete |
| `POST` | `/api/v1/ivr-trees/{id}/status` | DRAFT→ACTIVE / ARCHIVED |
| `POST` | `/api/v1/campaigns/{id}/ivr-tree` | **create-IVR-from-campaign-flow** |

Nodes and transitions are submitted **inline with the tree** rather than as
separate CRUD endpoints. Rationale: a tree is only ever meaningful as a whole
(one root, no cycles, reachability), so whole-tree replace is the only write
shape that can be validated atomically. Per-node endpoints would let a client
persist an invalid tree between calls — precisely the failure §24 forbids.
Caps: `IVR_VIEW` / `IVR_MANAGE`, private constants on the service.

---

## 13. Migration plan

`V53__ivr_trees.sql`, forward-only, V1–V52 untouched:

- `CREATE TYPE ivr_tree_status AS ENUM ('DRAFT','ACTIVE','ARCHIVED')`
- `CREATE TYPE ivr_node_type AS ENUM ('MENU','TERMINAL')`
- `CREATE TYPE ivr_terminal_action AS ENUM ('TERMINATE','CONNECT_BY_AGENT')`
- `CREATE TYPE ivr_step_result_type AS ENUM ('WAITING_INPUT','ADVANCED','INVALID_RETRY','NO_INPUT_RETRY','TERMINAL_REACHED','ABANDONED')`
- `ivr_trees`, `ivr_nodes`, `ivr_transitions`, `ivr_steps`
- the uniqueness, CHECK and composite-FK constraints from §9
- `ivr_steps` deadline index for the existing 1 s poller

**No** `type_config` change, **no** `dtmf_interactions` change, **no**
`call_sessions` change, **no** snapshot-table change. The frozen IVR rides the
existing JSONB column.

---

## 14. Test strategy

New suites: structural validation (incl. cycle + reachability + cross-tree),
prompt governance, retry-semantics definition, traversal (3+ levels deep),
snapshot immutability against live edits, `ivr_steps` idempotency under
duplicate/late digits, timeout, terminal action, tenant isolation (negative
cases), create-from-campaign equivalence + idempotency, and PostgreSQL constraint
tests proving a cross-tree transition is *unrepresentable*.

Regression: all 57 existing DTMF tests
(`DtmfExecutionServiceTest` 18, `DtmfConfigTest` 15, `DtmfCollectorTest` 13,
`DtmfLifecycleIntegrationTest` 4, `DtmfEslEventServiceTest` 4,
`DtmfAgentActionTest` 3) must stay green and unmodified, plus the full VB-5/6
suites and the §3 media-URI fix.

---

## 15. Architectural dependency direction

```
authz ─▶ (nothing new)
audio ─▶ (nothing new)                     ← MUST NOT import campaign or ivr
voice ─▶ (nothing new)                     ← leaf; voice.ivr added here
telephony ─▶ campaign                      ← pre-existing, unchanged
campaign ─▶ voice.ivr, voice.dtmf, audio, telephony
```

Checks performed against the repository before designing this:
`module-voice → campaign` = **NONE**; `module-voice → audio` = **NONE**;
`module-audio → campaign` = **NONE**. Adding `voice.ivr` and
`campaign → voice.ivr` introduces exactly one new edge in an existing legal
direction. Expected cycle count: **0**, to be verified by the project's own
`ArchitectureTest`.

---

## 16. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| Refactoring `DtmfInteraction` to allow many rows per session would break all 57 DTMF tests | **High** | **Do not touch it.** Separate `ivr_steps` table. |
| "Multi-level" read as "generalized workflow engine" | Medium | Two node types only (`MENU`,`TERMINAL`); no engine, no plugins, no arbitrary actions |
| Retry-count ambiguity reappearing later | Medium | Defined in §6, asserted in a test, and stated in OpenAPI |
| Snapshot bloat for large trees | Low | Trees are operator-authored menus (tens of nodes); JSONB is the right home; a pathological tree is rejected by a node-count bound |
| Live DTMF digit timing (`min_digit_duration`) | Medium | Not implemented in VB-6F — out of scope, no telephony change permitted. Recorded as a limitation; a digit is processed as reported. |
| Media-URI defect (§3) silently reintroduced | Low | Single choke point (`IvrExecutionService` + fixed `DtmfExecutionService`), asserted by test |

---

## 17. Explicit scope exclusions

Reusable IVR is the *only* thing VB-6F adds. Deferred, deliberately, with
reasons rather than by omission:

- **TTS prompts** — no synthesis runtime exists (VB-6E OD-B). AUDIO only.
- **Cross-tree transitions** — keeps tree ownership and snapshotting
  deterministic, per the brief.
- **Cycles / graph traversal** — a finite tree only; "go back" is a transition to
  any node in the same tree.
- **IVR versioning / history / `MAX(version)+1`** — the immutable execution
  snapshot already provides the required guarantee.
- **Per-node CRUD endpoints** — whole-tree replace only, so no invalid
  intermediate state is persistable.
- **`min_digit_duration` / telephony timing changes** — would be a telephony
  change, which the brief forbids.
- **Answering-machine detection, call recording, billing per node** — unrelated.
- Everything in the brief's §39 list (voice AI, speech recognition, NLU,
  omnichannel, WhatsApp, drag/drop builder, analytics, new infrastructure).

---

## 18. Audit conclusion and recommendation

The foundation is genuinely reusable: the DTMF event boundary, the timeout
poller, the atomic-claim idempotency, the snapshot mechanism, the resource
governance authority and the media-URI resolver all exist and are correct.

The genuine work is:

1. **A reusable tree resource** (3 tables + lifecycle + validation + REST).
2. **A per-step state table** so multi-level traversal is possible at all
   (`dtmf_interactions` structurally cannot express it).
3. **A pure node classifier** beside `DtmfCollector`, reusing its DTMF alphabet
   and result vocabulary.
4. **Invalid-input and no-input retry**, which the current runtime explicitly
   does not have.
5. **A flattened, keyed snapshot** in the existing JSONB column.
6. **The §3 media-URI regression fix** in `DtmfExecutionService`, without which
   every prompt in this phase is non-functional.

**Recommendation: proceed to implementation in a single phase.** The items are
mutually dependent — a tree without a snapshot cannot be proven immutable, and
retries without per-step state cannot be proven in-call — so splitting them would
produce unverifiable intermediate states, exactly as VB-6E should not be split.

**Product decisions required before implementation: none that cannot be
inferred.** The two the brief flags (`retry` semantics, cycles) are resolved from
repository precedent and stated above (§6, §9). TTS prompt support is resolved by
evidence (no synthesis runtime exists). The only genuinely open question is
whether per-node REST endpoints are required by anything outside this repository;
nothing in it does, so whole-tree replace is the recommendation.
