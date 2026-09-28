# VB-7B — MISSED_CALL Campaign Configuration Audit

## 1. Executive Summary

`MISSED_CALL` is **completely absent** from the repository. Not a placeholder,
not dead code — a repository-wide case-insensitive search of `backend/src/`
(Java, SQL, YAML, XML, JSON, main **and** test) returns **zero** occurrences.
Every hit is in `docs/`, and most of those are historical audits correctly
recording that the type does not exist.

Three findings dominate the implementation picture, and they materially change
the size of the phase:

1. **A migration IS required, contrary to the brief's expectation.** The
   `campaigns.campaign_type` column is `VARCHAR(30)` guarded by
   `ck_campaigns_type CHECK (campaign_type IN ('PLAYFILE','DTMF','CONNECT_BY_AGENT'))`
   (`V14__rebuild_campaign_domain.sql:21-22`). `MISSED_CALL` cannot be persisted
   without altering that constraint — a `V54`. It is a pure additive
   `DROP CONSTRAINT` / `ADD CONSTRAINT`; no table, column or enum is involved.
   This is the *only* reason a migration is needed. The configuration itself
   needs none.

2. **Five of the six expected concepts already exist as snapshotted
   campaign-level columns.** `CampaignConfigurationSnapshot` already freezes
   `did_id`, `contact_group_id`, the four retry fields plus `retry_rules`, seven
   schedule fields, `max_daily_attempts` (VB-6D.3), `daily_dial_limit`
   (VB-6C.2), `call_on_whitelist_numbers`, and `max_call_duration_seconds`.
   **Only "ring duration" is genuinely new**, and `type_config` JSONB is its
   correct home. So the configuration work is smaller than the brief implies.

3. **The runtime gap is smaller than it looks, and one product decision dominates
   it.** `bgapi originate` carries **no media argument**
   (`EslClient:327`) — playback is a separate `uuid_broadcast` that only
   execution services invoke. A ring-only MISSED_CALL therefore needs **no media
   work at all**: it must simply not play, and must end the channel. The
   decisive question is that the platform's existing semantics make **who hangs
   up first** determine the outcome: our own `uuid_kill` produces cause 16
   (`NORMAL_CLEARING`) which `EslEventService` records as **COMPLETED /
   success**, whereas a carrier-reported cause 19 is recorded as
   **FAILED / `NO_ANSWER`**, which is a *retryable* category. Whether
   "nobody answered" is MISSED_CALL's success or its failure is therefore not an
   implementation detail — it inverts the existing taxonomy and is **OD-1**.

Everything structural is in place. The safety, retry, compliance, scheduling,
snapshot and routing layers are **entirely campaign-type-neutral** (§10, §13,
§15), so MISSED_CALL participates in all of them automatically. What is missing
is (a) the enum/CHECK/sealed-permit addition, (b) a typed config holding one
field, (c) **four fail-open configuration gates** that would silently mis-handle
a fourth type (§16), and (d) a decision on OD-1/OD-2 that determines whether a
runtime is required at all.

**Verdict: READY WITH DECISIONS.** The architecture is sound and the
configuration phase is small; a small number of explicit product decisions —
chiefly OD-1 and OD-2 — must be answered before implementation, because they
determine whether VB-7B ships configuration alone or configuration plus a
runtime seam.

---

## 2. Baseline

Verified by `cd backend; .\mvnw.cmd -o clean test '-DargLine=-Xmx900m'`
(`BUILD SUCCESS`).

| Item | Verified value |
|---|---|
| Commit (HEAD) | **`c9b468f`** — VB-7A: CONNECT_BY_AGENT campaign configuration and execution |
| Branch | `main` |
| `origin/main` | **`c9b468f`** — identical; 0 commits ahead |
| Tests | **1683 run, 0 failures, 0 errors, 1 skipped** |
| Pre-existing skip | `ObdApplicationTests.contextLoads` (1) — unchanged since the VB-6F baseline |
| Migration head | **V53** (`V53__ivr_trees.sql`), 52 migration files; next free is **V54** |
| Modules | **16** (15 sub-packages under `com.shivang.obd` + the root package) |
| Architecture | `ArchitectureTest` **PASS** (10.80 s) — `ApplicationModules.verify()`, **0 cycles** |
| Runtime | Java **17**, Spring Boot **4.1.0**, Jackson **3** (`tools.jackson`), Modulith **2.1.0** |

No pre-existing failures. Nothing was fixed.

### Working tree — user-owned, preserved and untouched

```
 M backend/docs/future-hardening.md
 M docs/campaign-readiness.md
 M infra/.env.example
?? backend/data/
?? docs/LIVE-FREESWITCH-PHASE-B.md
?? docs/freeswitch/
?? infra/docker-compose.freeswitch.yml
?? infra/freeswitch/
?? tools/
```

The FreeSWITCH development track (`infra/*`, `docs/freeswitch/`,
`docs/LIVE-FREESWITCH-PHASE-B.md`) is a **separate, uncommitted, user-owned
effort**. Nothing in it was read for audit content, modified, staged, or
included in this audit's conclusions. §25 confirms VB-7B does not depend on it.

---

## 3. Existing MISSED_CALL Support

### Repository-wide search

`git grep -i -E 'missed[ _-]?call'` across all tracked files: **all hits are in
`docs/`**. Case-insensitive search of `backend/src/` across
`*.java, *.sql, *.yml, *.yaml, *.xml, *.json` (main **and** test):
**zero occurrences**.

Classifying against the brief's five possibilities:

| Possibility | Verdict |
|---|---|
| 1. exists as an enum/configuration type | **No** |
| 2. exists only as a placeholder | **No** — there is no placeholder either |
| 3. completely absent | **YES — completely absent** |
| 4. appears in tests/docs but not production | **Docs only.** Appears in 8 audit/implementation docs and in the user-owned `docs/campaign-readiness.md`; **zero** test references |
| 5. appears in dead/legacy code | **No** |

### Classification of the documentary references

| Document | Nature of the reference |
|---|---|
| `docs/VB-6-CAMPAIGN-AUDIT.md:139` | Explicitly records absence: *"NOT PRESENT — `CampaignType` has 3 values; zero MISSED_CALL references in main code"* |
| `docs/VB-6-CAMPAIGN-AUDIT.md:141` | Pre-identifies the **exact** runtime gap: *"the dial path treats NO_ANSWER as FAILED …; MISSED_CALL needs NO_ANSWER reclassified as the success outcome plus guaranteed no media/no bridge"* — **independently confirmed by this audit in §17** |
| `docs/VB-6-CAMPAIGN-AUDIT.md:221` | Records the required column change: *"Postgres named-enum or varchar addition — V34 precedent exists; safe additive; verify enum column type first"* — verified as `VARCHAR(30)` (§19) |
| `docs/VB-6C-…-AUDIT.md:21` | *"There is no `MISSED_CALL` type today (the brief lists it; the enum does not contain it — a factual correction to the brief's assumption)"* |
| `docs/VB-6-CAMPAIGN-AUDIT.md:240,257` | Capability matrix row, empty except a one-line note |
| `docs/VB-7A-…-AUDIT.md:791,1159` | Correctly predicted *"VB-7B (MISSED_CALL) will require a `V54` to extend the `ck_campaigns` CHECK constraint"* — **confirmed (§19)** |
| `docs/campaign-readiness.md` (user-owned) | `MISSED_CALL \| **Missing** \| Add as 4th campaign type`. Not read for audit content beyond this table cell; never modified |

**No source-level support, placeholder, dead code, or test scaffolding exists.**
This is a genuinely greenfield campaign type on top of mature foundations.

---

## 4. Campaign Type Architecture

### The sealed hierarchy (post-VB-7A)

`campaign/config/CampaignTypeConfig.java`:

```java
public sealed interface CampaignTypeConfig
        permits PlayfileCampaignConfig, DtmfCampaignConfig, IvrCampaignConfig,
                ConnectByAgentCampaignConfig {          // L17

    static CampaignTypeConfig fromTypeConfig(CampaignType type, JsonNode typeConfig) {
        return switch (type) {                            // L46 — EXHAUSTIVE
            case PLAYFILE -> PlayfileCampaignConfig.fromTypeConfig(typeConfig);      // L47
            case DTMF -> IvrCampaignConfig.selectsIvr(typeConfig)                    // L48
                    ? IvrCampaignConfig.fromTypeConfig(typeConfig).orElseThrow()
                    : DtmfCampaignConfig.fromTypeConfig(typeConfig);
            case CONNECT_BY_AGENT -> ConnectByAgentCampaignConfig.fromTypeConfig(typeConfig);  // L51
        };
    }
}
```

### Per-type representation

| Type | Config class | Root JSON key | Notes |
|---|---|---|---|
| `PLAYFILE` | `PlayfileCampaignConfig` | none (empty) | Accepts absent/null/**empty** `{}` — its config lives in campaign *columns* (`contentMode`, `audioAssetId`) |
| `DTMF` | `DtmfCampaignConfig` | `"dtmf"` | Delegates to the strict VB-2 `DtmfConfig` parser |
| `DTMF` (IVR) | `IvrCampaignConfig` | `"ivr"` | Same type, two shapes; VB-6F |
| `CONNECT_BY_AGENT` | `ConnectByAgentCampaignConfig` | `"connectByAgent"` | VB-7A; strict, rejects unknown fields |

`PlayfileCampaignConfig`'s permissiveness is the key precedent for §5: **a type
whose configuration is expressed in campaign columns has a legitimately empty
`typeConfig`.**

### Can MISSED_CALL be added without changing the architecture?

**Yes — and the compiler enforces it.** Adding `MISSED_CALL` to `CampaignType`
breaks compilation in three places, all of which are the correct places:

1. `CampaignType` — the enum value itself.
2. `CampaignTypeConfig.permits` — a `MissedCallCampaignConfig` must be added,
   or the sealed hierarchy is incomplete.
3. `CampaignTypeConfig.fromTypeConfig`'s `switch` — the **exhaustive** switch
   over the enum. Java requires a case, so the compiler refuses to build until
   MISSED_CALL's dispatch exists.

This is a genuinely strong safety property: a campaign type cannot be added
without a typed configuration, and a typed configuration cannot exist without a
dispatch arm. VB-7A relied on exactly this and the audit at
`docs/VB-7A-…-AUDIT.md:791` documents the same property.

### Required change inventory (§5 checklist)

| Layer | Change required | Evidence |
|---|---|---|
| Enum | **Yes** — add the constant | `CampaignType` (3 values today) |
| Sealed interface | **Yes** — `permits` + `switch` case | Compile-forced, above |
| Typed config class | **Yes** — one new record | §5 |
| DTO | **No new field** | `CreateCampaignRequest`/`UpdateCampaignRequest` already carry `campaignType` + `typeConfig` |
| JSON schema | **No** | `type_config` is unconstrained JSONB (`V14:73`) |
| Validation | **Yes — 4 sites (§16)** | Inclusive type lists fail open |
| Readiness | **Yes — same 4 sites** | Duplicated in a second file |
| Runtime dispatch | **Yes if OD-1 requires it** | §17 |
| DB CHECK | **Yes** | `V14:22`, §19 |

---

## 5. Proposed Configuration Shape

### The decisive insight: almost nothing is new

`CampaignConfigurationSnapshot` already freezes every concept the brief names
except ring duration. Verified by reading every `@Column` in that class:

| Brief concept | Existing snapshotted field | Verdict |
|---|---|---|
| **DID** | `did_id` (`@Column(name = "did_id")`) | **EXISTS** — `campaigns.did_id` column + `CampaignResourceValidationService.validateDid` |
| **audience** | `contact_group_id` | **EXISTS** — `campaigns.contact_group_id` + `ContactGroupMemberRepository` |
| **retry policy** | `retry_max_attempts`, `retry_interval_seconds`, `retry_strategy`, `retry_rules` (VB-6D.2) | **EXISTS** — `RetryPolicyService` is "the single authority" |
| **schedule** | `schedule_start_date`, `schedule_end_date`, `daily_start_time`, `daily_end_time`, `timezone`, `allowed_days_of_week`, `holiday_calendar_id` | **EXISTS** — `ScheduleSpec` |
| **safety policy** | `daily_dial_limit` (VB-6C.2), `max_daily_attempts` (VB-6D.3) | **EXISTS** — `DailyDialLimitService`, `DailyAttemptSafetyService` |
| **ring duration** | — | **NEW — the only new field** |

### Proposed shape

Following the `connectByAgent` convention exactly:

```json
{ "missedCall": { "ringDurationSeconds": 30 } }
```

```java
public record MissedCallCampaignConfig(
        Integer ringDurationSeconds,
        ConfigSchemaVersion schemaVersion) implements CampaignTypeConfig
```

One field. Rationale for keeping it this small is not minimalism for its own sake
— it is that **every other concept is a campaign-level column with its own
authority**, and duplicating any of it into `typeConfig` would create a second
source of truth that the snapshot, the validator and the retry service would
have to be kept in sync with. This is the identical argument VB-7A used to
justify storing only a `queueId`.

### What the configuration must NOT contain

Per the brief, and enforced structurally:

| Forbidden | Enforced by |
|---|---|
| `audioAssetId` / content mode | `campaigns.audio_asset_id` is validated per `contentMode`; MISSED_CALL sets neither, and `validateContent` must not require it (§16) |
| PLAYFILE / DTMF / IVR config | Each type parses only its own root key; `{"dtmf":…}` under a MISSED_CALL campaign is rejected by `MissedCallCampaignConfig`'s strict parse |
| `queueId`, agent selection, agent ring | Those live in `connectByAgent`, which only `CONNECT_BY_AGENT` dispatches to |
| `ivr` tree | Only `IvrCampaignConfig` reads it |
| TTS template | `campaigns.tts_template_id` stays null; `validateContent` rejects content references without a mode |

**Additional validation is *not* required for type isolation** — the sealed
hierarchy plus strict per-type parsing already makes cross-type leakage
impossible. What *is* required is the four fail-open gates in §16, which are a
different defect (a *missing* type being silently under-validated, not a
*foreign* type being accepted).

---

## 6. DID / DNID / Routing Findings

### Answer: MISSED_CALL uses the identical DID model. No new concept.

`campaigns.did_id` is a `UUID` FK validated by
`CampaignResourceValidationService.validateDid(didId, tenantId)`, which requires
live, not soft-deleted, same-tenant, `DidStatus.ACTIVE` (values: `ACTIVE`,
`INACTIVE`) and `AllocationState.ASSIGNED` (values: `AVAILABLE`, `ASSIGNED`).
Frozen into the snapshot as `did_id`. `createInitialAttempts` refuses to start
an execution with a null DID (`"Campaign missing contact group or DID"`) and
**re-validates** it at that point: *"DID usability stays dynamic."*

### Requested vs actual DID — a finding worth recording

`CampaignConfigurationSnapshot`'s own javadoc calls `did_id` the *"Requested
caller-ID DID reference"*, and that is literal. `OutboundDialService:278-279`:

```java
actualOutboundDidId = selectedRoute.didId() != null
        ? selectedRoute.didId()
        : (campaign.didId() != null ? campaign.didId() : attempt.getDidId());
```

**The voice routing layer's DID wins; the campaign's is only a fallback.** A
route profile can therefore dial a number the operator never configured. For
PLAYFILE this is invisible (the callee hears campaign content either way). For
MISSED_CALL it is *not* invisible — the dialled CLI is precisely what generates
the subscriber's missed-call notification and call-log entry. This is a real
product consideration, recorded as **OD-8**, and it needs no code: the mechanism
already exists and is proven.

### Tenant safety

`validateDid` is tenant-scoped, and `actualOutboundDidId` is what the safety
ledger is keyed on (`dailyDialLimitService.confirmAccepted(..., actualOutboundDidId, ...)`).
`OutboundDialService:233-234` also resolves `resellerId` from the attempt's own
tenant and passes it to routing, so reseller ownership participates without a
MISSED_CALL-specific change.

**Verdict: DID/DNID/routing requires no change, and no migration. The
requested-vs-routed distinction is inherited behaviour, surfaced as OD-8.**

---

## 7. Audience / Contact Findings

### Answer: fully inherited. Model A is documented and deliberate.

`CampaignExecutionOrchestrator.createInitialAttempts:244-249`, quoted verbatim:

> *"VB-6B.1: the audience is the group's **LIVE MEMBERSHIP** — contact identities
> are resolved through the membership bridge, then kept in memory only for attempt
> creation. Membership later changes cannot alter this already-started execution
> (**Model A**)."*

The precise semantics:

| Element | Behaviour |
|---|---|
| **Group reference** | **Frozen** into the snapshot as `contact_group_id` |
| **Membership** | Resolved **live** at execution creation, then materialised into `CallAttempt` rows |
| **Later membership change** | Cannot affect a running execution (attempts already exist; a per-contact unique index makes it idempotent) |
| **Deleted contacts** | Filtered: `.filter(c -> c.getDeletedAt() == null)` |
| **Tenant isolation** | `campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull`; `validateContactGroupReference` requires same-tenant and states *"SUPER_ADMIN authority does not relax it"* |
| **Empty audience** | `log.warn(...)` + `return` — **not** a readiness failure |

**OD-6 is therefore RESOLVED BY EXISTING ARCHITECTURE.** MISSED_CALL inherits
"Model A" with no new table, no new field, and no decision required.

The empty-audience behaviour is worth flagging for §16: today an empty group
produces a *warning and a silently empty execution*, not a readiness reason. That
is existing behaviour and this audit does not propose changing it, but an
operator building a MISSED_CALL campaign has no signal that it will reach nobody.

---

## 8. Ring Duration Findings

### Answer: no existing authority exists. This is the one genuinely new concept.

Every existing duration in the platform, and what it actually bounds:

| Concept | Authority | Bounds | Post-answer only? |
|---|---|---|---|
| Agent ring window | `AgentRingWindow` (VB-7A) | 10–240 s, default 60 | Agent **leg** only — irrelevant to a campaign caller leg |
| Agent connect timeout | `AgentConnectTimeoutScheduler` | 60 s default | Agent legs in `DIALING`/`RINGING` |
| Max call duration | `MaxCallDurationPolicy` (VB-6E) | 1–3600 s, default 300 | **Yes** — `deadline_at` is only ever set from `answeredAt` |
| Stale attempt threshold | `StaleCallReconciler.STALE_ATTEMPT_THRESHOLD` | 5 min | Recovery sweep, not a policy |
| ACD hold TTL | `AcdService.RESERVATION_TTL` | 5 min | Inbound queue holds |
| DTMF timeout | `DtmfTimeoutScheduler` / `DtmfConfig.timeoutSecs` | DTMF only | DTMF interaction |

**None of these can serve as a MISSED_CALL ring duration.** `MaxCallDurationPolicy`
is explicitly *"the **maximum lifetime of an established outbound call session**"* —
it starts at answer, and bounding an *established* call is the opposite of what
MISSED_CALL needs. Using it would be a semantic collision, and would leave the
ring unbounded (the session has no `deadline_at` until answered).

### The enforcement gap, precisely located

`deadline_at` is set in **exactly one place** in the whole codebase —
`PlayfileExecutionService.applyMaxCallDuration:349`:

```java
int seconds = MaxCallDurationPolicy.effectiveSeconds(config.maxCallDurationSeconds());
Instant answeredAt = session.getAnsweredAt() != null ? session.getAnsweredAt() : Instant.now();
session.setDeadlineAt(answeredAt.plusSeconds(seconds));
```

It is a **private** method on the PLAYFILE service, invoked only on the PLAYFILE
answer path, and it is the only writer of `deadline_at` in the platform. A
MISSED_CALL campaign would therefore have **no session deadline at all**: an
answered MISSED_CALL would sit on the bridge until the 5-minute
`STALE_ATTEMPT_RECONCILED` sweep — which would record a *dispatched failure* for
a call that actually connected.

### No-media is already free

`EslClient:327` builds the originate as:

```
bgapi originate {origination_uuid=%s,origination_caller_id_number=%s}%s
```

**No media argument.** Playback is a separate `uuid_broadcast <uuid> <path> aleg`
(`EslClient:368`) reached only through `playFile(...)`, which only
`PlayfileExecutionService` and `DtmfExecutionService`/`IvrExecutionService` call.

**Therefore a MISSED_CALL needs no media work whatsoever.** The correct
implementation is: originate (rings), do not play, terminate at the ring
deadline. The `inCallFailureRecorded` / `session.getFailureCode()` precedence in
`EslEventService:477-487` means a MISSED_CALL service can record intent *before*
asking for teardown and the existing classifier will honour it — **with no change
to `EslEventService`.**

**Verdict: `ringDurationSeconds` is a new, type-specific, validated,
snapshotted field. Its enforcement needs one new timeout authority scoped to
MISSED_CALL, plus a decision on whether to generalise `applyMaxCallDuration` or
add a sibling. OD-5 and OD-8.**

---

## 9. Retry Policy Findings

### Answer: reuse entirely. Integration is zero.

`RetryPolicyService` is documented as *"the single authority for 'may this failed
attempt be retried, and when?'"* and has **zero** `CampaignType` references
(verified by grep). `processRetries` in `CampaignExecutionOrchestrator` reads the
policy from the **frozen snapshot** and re-validates contact and DID before
retrying.

MISSED_CALL therefore participates in retries automatically. The integration
required is **none**. `RetryPolicyService` must not be modified.

### The one genuine tension

`CallFailureCode.NO_ANSWER(RetryClass.TEMPORARY)` and
`FailureClassification` maps it to `RetryRuleCategory.NO_ANSWER`. So:

- If OD-1 resolves to **"no-answer is MISSED_CALL's success"**, then success is
  `COMPLETED` (no retry) — but a *carrier-reported* cause 19 is `FAILED` and
  **will be retried**. A correctly-functioning MISSED_CALL could therefore retry
  the very outcome it is trying to produce.
- If OD-1 resolves to **"only a completed ring is success"**, cause 19 stays a
  failure and retrying it is right.

This is not a design flaw to fix; it is the direct consequence of the existing
taxonomy, and it is precisely why OD-1 cannot be decided by an implementer.

### Which categories make sense

`RetryRuleCategory` = `NO_ANSWER`, `BUSY`, `HANGUP`, `FAILED`, `SWITCHED_OFF`
(the last deferred, per the carried-forward OD-2 item). All are already
selectable; MISSED_CALL adds no category.

---

## 10. Daily Safety Findings

### Answer: MISSED_CALL participates automatically. No change, no new policy.

Verified by grep: **`DailyDialLimitService` and `DailyAttemptSafetyService`
contain zero `CampaignType` references.** They are campaign-owned and invoked
from the dial pipeline at two boundaries, with no type branch.

### OD-3 — RESOLVED BY EXISTING ARCHITECTURE: provider acceptance consumes the dial limit

`DailyDialLimitService`'s own javadoc states the semantics exactly:

> *"admission (reserve) → provider acceptance (+OK) → usage (confirm) …*
> *A slot is **consumed** only when the provider accepted the originate (ESL
> `+OK <uuid>`, the `DIAL_REQUEST_ACCEPTED` boundary). Everything before
> acceptance … returns its hold and consumes nothing. **Everything after
> acceptance (ring, no-answer, busy, hangup, post-acceptance failure) counts.**"*

`OutboundDialService:371-373` confirms the call site. So:

| Outcome | Consumes VB-6C daily dial budget? |
|---|---|
| Pre-dispatch rejection (no route, no gateway, invalid DID) | **No** — hold released |
| Provider accepts (`+OK`), then any outcome | **Yes** |
| Provider unavailable | **No** — requeued without consuming the attempt number |

A MISSED_CALL ring consumes the budget, because it happens after acceptance.
**This is the compliance-correct answer and it is already the existing rule.**

### OD-4 — RESOLVED BY EXISTING ARCHITECTURE: the attempt ceiling applies

`DailyAttemptSafetyService` has the same structure and no type branch. Its own
javadoc tabulates *"Provider accepts then the call fails/NO_ANSWER"* as a
counting case.

### Is MISSED_CALL "Voice Blast"?

Yes, automatically and by design. `OutboundDialService:440` sets
`session.setCallType(CallType.VOICE_BLAST)` for **every** campaign call,
regardless of `CampaignType`. `CallType`'s javadoc is explicit: *"This is
distinct from CampaignType. CampaignType is a product orchestration concept.
CallType is a universal voice classification."* The safety ledger is keyed on
`CallType.VOICE_BLAST`.

**No new safety policy, no broadening of VB-6C/VB-6D, and no campaign-specific
safety framework is warranted.** §12's "safety policy" requirement is fully
satisfied by `daily_dial_limit` + `max_daily_attempts` + `call_on_whitelist_numbers`
— all three already snapshotted.

---

## 11. Safety Policy Findings

### Answer: "safety policy" is already fully covered. Do not build a second framework.

Mapping the brief's broad term onto existing authorities:

| Brief concept | Existing authority | Location |
|---|---|---|
| daily attempt ceiling | `DailyAttemptSafetyService` | VB-6D.3, `max_daily_attempts` |
| daily dial limit | `DailyDialLimitService` | VB-6C.2, `daily_dial_limit` |
| DND | `VoiceEligibilityService` (DNC/blocklists) | reached via `CallEligibilityService` |
| blocklists / platform blocks | `CallFailureCode.PLATFORM_BLOCKED`, `PLATFORM_PROTECTED`, `RESELLER_BLOCKED` | `FailureClassification` |
| whitelist | `call_on_whitelist_numbers` + `CallFailureCode.NOT_WHITELISTED` | `CallEligibilityService` |
| compliance checks | `CallEligibilityService` | `OutboundDialService:201` |
| timezone handling | `EXECUTION_TIMEZONE_INVALID`; snapshot `timezone` is authoritative | `OutboundDialService:169-178` |
| contact/DID/day keys | `voice_blast_daily_usage` keyed `(tenant, contact, did, date)` | V47/V50 |

Every one of these is campaign-type-neutral and already snapshotted. **There is
no missing capability, so there is nothing to build.** Introducing a
MISSED_CALL-specific safety policy would be exactly the duplicate-infrastructure
risk the brief prohibits.

---

## 12. Scheduling Findings

### Answer: fully inherited. No new scheduler, no new semantics.

`CampaignExecutionOrchestrator.scheduledTick` is a single `@Scheduled(fixedDelay = 30000)`
pass with **six steps, none of which branches on campaign type**:

| # | Step | MISSED_CALL behaviour |
|---|---|---|
| 1 | `start-requested-executions` | `REQUESTED → RUNNING`, calls `createInitialAttempts` |
| 2 | `process-retries` | `RetryPolicyService` on the frozen snapshot |
| 3 | `dial-due-attempts` | `OutboundDialService.processDueAttempts` |
| 4 | `pump-esl-events` | ESL event processing |
| 5 | `reconcile-executions` | aggregate rollup |
| 6 | `reconcile-stale-calls` | VB-6E `StaleCallReconciler` |

Timezone is authoritative from the snapshot
(`OutboundDialService:169-178`, failing closed with
`EXECUTION_TIMEZONE_INVALID` rather than falling back to UTC/JVM). Campaign
lifecycle (`DRAFT → SCHEDULED` activation, `PAUSED`, `ARCHIVED`) is entirely
campaign-type-neutral in `CampaignLifecyclePolicy`.

**Verdict: no scheduling change. OD-9 is RESOLVED BY EXISTING ARCHITECTURE** —
MISSED_CALL is executed through exactly the Voice Blast execution path, so there
is no separate schedule semantics to invent.

If a ring-duration timeout is required (OD-5), it does **not** need a new
scheduler: the existing `runStep(name, Runnable)` failure-boundary pattern on the
same tick, or a sibling of `AgentConnectTimeoutScheduler`, are both available
precedents. No new scheduling framework either way.

---

## 13. Snapshot Findings

### Frozen vs live — the exact classification

**Frozen (existing snapshotted fields, zero new work):**

| Field | Column |
|---|---|
| campaign type | `campaign_type` |
| DID reference | `did_id` |
| audience reference | `contact_group_id` |
| retry policy | `retry_max_attempts`, `retry_interval_seconds`, `retry_strategy`, `retry_rules` |
| schedule | `schedule_start_date`, `schedule_end_date`, `daily_start_time`, `daily_end_time`, `timezone`, `allowed_days_of_week`, `holiday_calendar_id` |
| safety | `daily_dial_limit`, `max_daily_attempts`, `call_on_whitelist_numbers` |
| **ring duration** | **`type_config` JSONB — the one new frozen value** |

**Live at execution time (never frozen) — the VB-6A snapshot-vs-resource rule:**

| Remains live | Authority |
|---|---|
| DID status / allocation | `CampaignResourceValidationService.validateDid`, re-checked in `createInitialAttempts` |
| Gateway availability | `VoiceRoutingService` (profile/overflow/failover) |
| Contact validity / deletion | `ContactRepository` filter at attempt creation |
| DND / blocklist / whitelist | `VoiceEligibilityService` + `CallEligibilityService` |
| Group membership | Live at execution creation (Model A, §7) |
| Provider availability | `OutboundDialService` pre-dispatch branch |
| Audience depth | Not a concept |

`CampaignConfigurationSnapshot`'s javadoc states the governing rule: *"Resource
**validity** of the referenced DID/audio/TTS is never frozen: every runtime check
stays dynamic (VB-6A snapshot-vs-resource rule)."* MISSED_CALL inherits it
unchanged.

**No versioning, no history table, no `MAX(version)+1`, no legacy fallback.** The
snapshot is written once in the execution's own transaction
(`CampaignConfigurationService.createExecutionSnapshot`, `REQUIRED` propagation)
and never updated.

---

## 14. Compliance Findings

### Answer: identical gates. OD-7 — RESOLVED BY EXISTING ARCHITECTURE.

`OutboundDialService:201-210` evaluates eligibility **before** dialling, through
the campaign-module adapter:

```java
EligibilityResult eligibility = eligibilityService.evaluate(new CallEligibility.Context(...));
if (!eligibility.isAllowed()) { /* block the attempt */ }
```

`CallEligibilityService` has **zero** `CampaignType` references. Its javadoc: *"Delegates
voice-layer eligibility (blocklists, DNC, DID validity, gateway availability) to
`VoiceEligibilityService`, and adds campaign-specific targeting logic: whitelist
enforcement (`callOnWhitelistNumbers`), contact group membership validation."*

So MISSED_CALL obeys the identical pipeline:

```
destination validation → platform/reseller blocklists → DNC → DID validity
  → gateway eligibility → whitelist → contact group membership
```

**There is no bypass and none should be created.** A different campaign type is
not a reason to skip DND. The prompt's warning is taken seriously and the
evidence supports full participation.

---

## 15. Campaign Lifecycle / Readiness

### The four fail-open gates — the highest-value finding in this audit

`CampaignService.validateContent:435`:

```java
if (type != CampaignType.CONNECT_BY_AGENT && mode == null) {
    throw business(type + " campaigns require content (audio or TTS).");
}
```

`CampaignService.validateTypeConfig:441`:

```java
boolean required = type == CampaignType.DTMF || type == CampaignType.CONNECT_BY_AGENT;
if (!required) { return; }        // <-- MISSED_CALL returns here: NO validation
```

`CampaignReadinessService.checkContentConfiguration:299` and `:307` are the
**same two rules duplicated in a second file**.

| # | Site | Existing condition | MISSED_CALL consequence | Severity |
|---|---|---|---|---|
| 1 | `CampaignService:435` | `type != CONNECT_BY_AGENT && mode == null` → throw | MISSED_CALL **required to have content** | **FAIL-CLOSED, wrong** — a contentless campaign becomes uncreatable |
| 2 | `CampaignService:441` | `required = DTMF \|\| CONNECT_BY_AGENT` | MISSED_CALL **skips typeConfig validation entirely** | **FAIL-OPEN** — an invalid/legacy payload passes silently |
| 3 | `CampaignReadinessService:299` | duplicate of #1 | readiness reports *"MISSED_CALL campaigns require content"* | **FAIL-CLOSED, wrong** |
| 4 | `CampaignReadinessService:307` | duplicate of #2 | readiness never validates the config | **FAIL-OPEN** |

Sites 2 and 4 are the dangerous ones: they are the **same anti-pattern VB-7A had
to fix** for `CONNECT_BY_AGENT`, where the audit recorded the same inclusive-list
shape. They are a *latent* bug today (the enum has exactly the two types the
list names) which becomes a *live* bug the moment a fourth type is added.

Sites 1 and 3 must become inclusive (`PLAYFILE || DTMF`) rather than negated, so
that a future fifth type is excluded by default.

**Note also a pre-existing duplication:** the content rule and the
typeConfig-required rule each exist in **two** files. §23 recommends a single
authority, but this audit does **not** propose refactoring it now — the minimal
VB-7B change is to make the condition correct in both places, and record the
duplication as hardening.

### MISSED_CALL readiness gates, itemised

| Gate | Authority | MISSED_CALL? |
|---|---|---|
| type config present and valid | `validateTypeConfig` / `CampaignTypeConfig.fromTypeConfig` | **Yes** — but site #2/#4 must be fixed first |
| DID exists, same tenant, `ACTIVE` + `ASSIGNED` | `validateDid` + `checkDid` | **Yes** — inherited |
| audience exists, same tenant | `validateContactGroupReference` + `checkContactGroup` | **Yes** — inherited |
| audience non-empty | *no existing rule* (`log.warn` + return) | **No gate** — existing behaviour, not changed here |
| ring duration in range | `MissedCallCampaignConfig` (new) | **Yes** — new |
| retry policy valid | `RetryPolicyValidator` | **Yes** — inherited |
| schedule valid | `validateScheduleWindow` / `checkScheduleReadiness` | **Yes** — inherited |
| safety policy valid | `DailyDialLimitService.assertConfigurable`, `DailyAttemptSafetyService.assertConfigurable`, `MaxCallDurationPolicy.assertConfigurable` | **Yes** — inherited |
| gateway available | — | **No gate** — correctly runtime |
| provider available | — | **No gate** — correctly runtime |
| agent availability | — | **Not applicable** — MISSED_CALL has no agent concept |

The last three rows are the brief's "runtime availability must not become
configuration readiness" rule, and the existing architecture already honours it
(DID *validity* is a gate; gateway/provider *availability* is not).

---

## 16. Type Isolation

**Structural protection is already complete and requires no new validation.**

| Leak vector | Why it is already closed |
|---|---|
| PLAYFILE config into MISSED_CALL | `MissedCallCampaignConfig` reads only `"missedCall"`; a `{"playfile":…}` payload is rejected as absent |
| DTMF config into MISSED_CALL | Same — `{"dtmf":…}` rejected |
| IVR tree into MISSED_CALL | Same — `{"ivr":…}` rejected |
| `connectByAgent` config into MISSED_CALL | Same — `{"connectByAgent":…}` rejected; `queueId`/agent fields are unreachable |
| Unknown fields | `ConnectByAgentCampaignConfig` (VB-7A) established the **reject-unknown-field** pattern; `MissedCallCampaignConfig` should copy it |
| A `MISSED_CALL` payload on a PLAYFILE campaign | `PlayfileCampaignConfig` accepts only absent/null/empty — a `{"missedCall":…}` payload is **rejected** as non-empty |
| Cross-type envelope on the REST layer | `campaignType` + `typeConfig` are validated together through `CampaignTypeConfig.fromTypeConfig`, at **create, update, activation and snapshot creation** |

The genuine isolation risk is the **opposite** direction and is the four gates
in §15: a MISSED_CALL campaign being *under*-validated because the code asks
"is this one of the types I know about?" rather than "which type is this?".

**Required fix: invert the two inclusive lists. No new isolation validation.**

---

## 17. Runtime Findings

### Current state: MISSED_CALL would dial, ring, and then be misclassified

Traced end to end:

```
CampaignExecution  (REQUESTED → RUNNING; createInitialAttempts)
  → CallAttempt QUEUED
  → scheduledTick "dial-due-attempts" → OutboundDialService
      → CallEligibilityService.evaluate(...)          [no type branch]
      → voiceRoutingService.resolveRoute(...)          [no type branch]
      → dailyDialLimitService.reserve(...)             [no type branch]
      → dailyAttemptSafetyService...                   [no type branch]
      → VoiceCapacityService.reserve(...)              [no type branch]
      → originate                                     [bgapi originate — NO media]
  → CHANNEL_PROGRESS (RINGING)
  → ??? no PlaybackTrigger owns MISSED_CALL, so nothing acts
  → carrier ring timeout, OR nobody acts, OR the 5-min stale sweep
  → CHANNEL_HANGUP → EslEventService → HangupCauseMapper
```

### The decisive existing semantics

`EslEventService:477-487`:

```java
String recordedFailureCode = session != null ? session.getFailureCode() : null;
boolean inCallFailureRecorded = recordedFailureCode != null && !recordedFailureCode.isBlank();
boolean success = !inCallFailureRecorded && isSuccessfulCompletion(hangupCause);
```

`isSuccessfulCompletion` → `HangupCauseMapper.isNormalClearing` → cause `16` /
`NORMAL_CLEARING`.

`HangupCauseMapper`'s table maps `19`/`NO_ANSWER` → `CallFailureCode.NO_ANSWER`
(failure), and its javadoc is explicit that cause 16 *"is a successful
completion, never a failure."*

`StaleCallReconciler`'s javadoc names the collision directly: *"the normal-clearing
cause our own `uuid_kill` produces is exactly the cause that otherwise means
'delivered blast'."*

### Therefore, the outcome depends entirely on who hangs up first

| Scenario | Hangup cause | Today | Is that right for MISSED_CALL? |
|---|---|---|---|
| We kill at the ring deadline | 16 `NORMAL_CLEARING` | **COMPLETED / success** | **Probably the desired outcome** — OD-1 |
| Carrier reports no-answer | 19 `NO_ANSWER` | **FAILED**, `RetryClass.TEMPORARY`, **retried** | **Conflicts** — the same real-world event is a retryable failure. OD-1 |
| Carrier reports busy | 17 `BUSY` | FAILED, retried | Correct |
| Callee answers | — | no trigger acts; sits answered → 5-min `STALE_ATTEMPT_RECONCILED` (FAILED, retried) | **Wrong** — OD-2 |
| No deadline set, callee never answers | — | 5-min `STALE_ATTEMPT_RECONCILED` (FAILED) | Coincidentally right, but for the wrong reason and after 5 minutes |

**This confirms the VB-6 audit's prediction exactly** (`docs/VB-6-CAMPAIGN-AUDIT.md:141`):
*"the dial path treats NO_ANSWER as FAILED …; MISSED_CALL needs NO_ANSWER
reclassified as the success outcome plus guaranteed no media/no bridge."*

### The minimum runtime seam (if OD-1/OD-2 require one)

1. A `MissedCallExecutionService` in `campaign` implementing `PlaybackTrigger`,
   guarding on `campaignType() == MISSED_CALL` — **exactly the VB-7A
   `ConnectByAgentExecutionService` precedent**, on the same `CHANNEL_ANSWER`
   seam. It records no media and applies the ring deadline.
2. Generalise or sibling `PlayfileExecutionService.applyMaxCallDuration` — it is
   20 lines, private, and PLAYFILE-only today. It is the *only* writer of
   `deadline_at`, and `StaleCallReconciler` already enforces it.
3. **No change to `EslEventService`, `OutboundDialService`, `HangupCauseMapper`,
   `AcdService`, or any safety/retry service.** The
   `inCallFailureRecorded` precedence at `EslEventService:477` means a MISSED_CALL
   service can record its intent before requesting teardown and the existing
   classifier will honour it.

### If OD-1 resolves to "no-answer is success"

The work is larger than the seam above, and this is the one place where
VB-7B could become architecturally significant. Making a *carrier-reported*
cause 19 a success requires a campaign-type-aware branch in the hangup
classification — the `inCallFailureRecorded` mechanism already provides a
**type-agnostic** way to express it (record the code before teardown), but a
carrier hangup arrives *after* we stop acting, so there is nothing left to record
before it. That path genuinely does need a new seam, most likely a
MISSED_CALL-specific sweeper or an attempt-level override applied when the hangup
arrives. **This is the branch that decides whether VB-7B is one phase or two.**

---

## 18. API / OpenAPI Findings

**No new endpoint. No new DTO field.** `CreateCampaignRequest`,
`UpdateCampaignRequest` and `CampaignResponse` already carry `campaignType` and
`typeConfig`; the campaign list filter `CampaignSpecifications.hasType(CampaignType)`
is driven by `CampaignType.values()` and picks up the new constant automatically.

Required OpenAPI work, following the VB-7A pattern:

| Item | Change |
|---|---|
| `typeConfig` description on all three schemas | Document the `missedCall` shape, the ring bounds, and that the referenced DID/audience/retry/schedule/safety live in the campaign's own fields |
| `example` | A valid `{"missedCall":{"ringDurationSeconds":30}}` |
| Bounds | Ring range must be stated in the description, as VB-7A did for CONNECT_BY_AGENT |
| Availability vs configuration | State that ring duration is configuration, and that a subscriber answering early is not a configuration failure |
| `CampaignType` enum | Springdoc renders the enum automatically; the new constant appears with no manual work |
| Security | Unchanged — `bearerAuth` |
| 400 contract | Unchanged — `CampaignConfigInvalidException extends BusinessException` → `GlobalExceptionHandler` → 400 |

Verification requirement: `CampaignOpenApiContractTest` must be extended to
**inspect the generated document**, not merely the annotations — the same
requirement VB-6C.2/VB-6D.3/VB-6E/VB-7A each satisfied.

---

## 19. Database / Migration Findings

### A migration IS required — one constraint, nothing else

`V14__rebuild_campaign_domain.sql:21-22`:

```sql
campaign_type VARCHAR(30) NOT NULL
    CONSTRAINT ck_campaigns_type
    CHECK (campaign_type IN ('PLAYFILE', 'DTMF', 'CONNECT_BY_AGENT'))
```

`VARCHAR(30)` has room for `MISSED_CALL` (11 chars). The change is a pure
additive `ALTER TABLE campaigns DROP CONSTRAINT ck_campaigns_type; ALTER TABLE
campaigns ADD CONSTRAINT ck_campaigns_type CHECK (campaign_type IN ('PLAYFILE','DTMF','CONNECT_BY_AGENT','MISSED_CALL'));`
— the same additive pattern VB-6C.2/VB-6D.3 used for their own CHECKs. **No
Postgres enum is involved**, so no `ALTER TYPE`. `docs/VB-6-CAMPAIGN-AUDIT.md:221`
speculated about a named enum and correctly flagged that the column type had to
be verified first; it is `VARCHAR`, so the simpler path applies.

This is the **sole** justification for `V54`, and it was predicted by both
`docs/VB-7A-…-AUDIT.md:1159` and by this audit's own §19. The brief's §21
expectation that "migration should NOT be required" is **incorrect for the CHECK
constraint**, though correct for the configuration.

### Configuration storage: no migration

| Requirement | Home | Migration? |
|---|---|---|
| `missedCall` type config | `campaigns.type_config` JSONB (`V14:73`) | **No** |
| Frozen in execution | `campaign_execution_configurations.type_config` JSONB (`V44:44`) | **No** |
| DID | `campaigns.did_id` | **No** |
| audience | `campaigns.contact_group_id` | **No** |
| retry | existing 4 columns + `retry_rules` JSONB | **No** |
| schedule | existing 7 columns | **No** |
| safety | `daily_dial_limit`, `max_daily_attempts`, `call_on_whitelist_numbers` | **No** |
| ring duration | inside `missedCall` in `type_config` | **No** |

### Tables that must NOT be created

`missed_call_campaigns`, `campaign_missed_call_configs`, `missed_call_retries`,
`missed_call_safety`, and any campaign↔queue/agent assignment table. Nothing
requires relational structure: the one new value is a scalar inside existing
JSONB, and every other value already has a column and an authority.

### Flag for the next phase

`V54` is taken by this CHECK. The carried-forward item for **VB-7C**
(integrations) and any future `MISSED_CALL` *reporting* schema should assume
**V55** onward.

---

## 20. Module / Architecture Findings

**16 modules, 0 cycles, unchanged.** `ArchitectureTest` passes.

| Concern | Owner | MISSED_CALL effect |
|---|---|---|
| Campaign configuration & orchestration | `campaign` | **Holds the new config, the enum, the validation fixes** |
| Voice telephony (ESL, originate) | `telephony` / `voice` | Unchanged; `bgapi originate` is already media-free |
| Routing | `voice.routing` | Unchanged; `resolveRoute`'s `callType` param is **dead** |
| Compliance | `voice.eligibility` + `campaign.CallEligibilityService` | Unchanged, automatically applied |
| Audio / TTS | `audio`, `tts` | **Untouched** — MISSED_CALL has no media |
| Contact / audience | `contact` | Unchanged (Model A) |
| Authz | `authz` | Unchanged — `CAMPAIGN_VIEW`/`MANAGE`/`EXECUTE` suffice |
| Agent / queue | `voice.agent`, `voice.acd`, `voice.queue` | **Untouched** — MISSED_CALL has no agent concept |

**No new module, no new `@NamedInterface`, no new module edge.** The runtime seam,
if OD-1/OD-2 require one, is a `campaign` service — the same place VB-7A put
`ConnectByAgentExecutionService`.

### A latent defect found (P2 hardening, not P0)

`VoiceRoutingService.resolveRoute(..., String callType, ...)` declares and
documents `callType` (`@param callType the type of call`) and **never reads it**
— verified: the only two occurrences in the class are the javadoc line and the
parameter declaration. Meanwhile `OutboundDialService:241` passes
`campaign.campaignType().name()` into it.

So a **`CampaignType` name is being passed into a parameter named for a
different enum** (`CallType` = `VOICE_BLAST` / `CONTACT_CENTER_*` / `AI`), and
the value is discarded. It is harmless today — nothing branches on it — and it
confirms routing is **fully campaign-type-neutral**, so MISSED_CALL routing needs
no change. But if a future phase wants per-type routing, that misleading
parameter is a trap. Recorded as P2 hardening; **not** a VB-7B blocker.

---

## 21. Existing Test Coverage

### MISSED_CALL references in tests: **zero**

### What the existing suite already guarantees

| Area | Class | Covers |
|---|---|---|
| Type config dispatch | `CampaignTypeConfigTest`, `ConnectByAgentCampaignConfigTest` | sealed dispatch, per-type parse, cross-type rejection |
| Config/sealed exhaustiveness | compile-time | the `switch` has no default arm, so a new enum constant breaks the build |
| DID validation | `CampaignResourceValidationPostgresIntegrationTest` | tenant scoping, `ACTIVE`/`ASSIGNED` |
| Audience | `ContactGroupMemberPostgresIntegrationTest`, `CampaignConfigurationSnapshotPostgresIntegrationTest` | membership, tenant isolation |
| Retry | `RetryPolicySnapshotPostgresIntegrationTest`, `PlayfileRetrySemanticsTest` | freeze + classification |
| Daily safety | `DailyDialLimitSnapshotPostgresIntegrationTest`, `VoiceBlastDailyDialLimitPostgresIntegrationTest`, `DailyAttemptSafetyServiceTest` | ledger, ceilings |
| Snapshot | `CampaignConfigurationSnapshotPostgresIntegrationTest`, `IvrExecutionSnapshotTest` | freeze, edit-does-not-mutate |
| Lifecycle | `CampaignLifecycleServiceTest`, `CampaignEditabilityTest` | states, DRAFT-only edit |
| Hangup classification | `HangupCauseMapperTest` | 14 + 16 + 28 cases |
| Stale reconciliation | `StaleCallReconcilerTest` | `MAX_DURATION_EXCEEDED`, `STALE_ATTEMPT_RECONCILED` |
| Routing | `VoiceRoutingServiceTest`, `VoicePolicyHierarchyTest` | profile/overflow/failover |
| Agent + queue + ACD | 23 classes | selection, reservation, queue, ACD |
| OpenAPI | `CampaignOpenApiContractTest` (20), `IvrOpenApiContractTest`, `ContactGroupMemberOpenApiContractTest` | generated document |
| Architecture | `ArchitectureTest` | 0 cycles |

### Would any existing test break when MISSED_CALL is added?

Verified by grep: **no test asserts `CampaignType.values()` cardinality, no test
enumerates the type set, and no test references `ck_campaigns_type` by name.**
The `DataIntegrityViolationException` assertions all target *other* constraints
(snapshot NOT NULL FK, `ck_campaigns_daily_dial_limit`, contact-group membership).

`CampaignService:668` uses `CampaignSpecifications.hasType(parseEnum(typeStr, CampaignType.values()))`
— a value-set-driven filter that adapts automatically.

**Adding MISSED_CALL should not break any existing test.** Adding the DB CHECK
value is likewise purely additive.

---

## 22. Proposed Implementation Test Matrix

Not added now — for the future implementation.

### A. Configuration (7)
1. valid MISSED_CALL config accepted; round-trip stable
2. missing `missedCall` entry rejected
3. ring duration below min rejected
4. ring duration above max rejected
5. non-integer ring duration rejected
6. unknown field rejected
7. ring duration at both bounds accepted

### B. Type isolation (6)
8. PLAYFILE payload cannot carry `missedCall`
9. DTMF payload cannot carry `missedCall`
10. IVR-bearing DTMF payload cannot carry `missedCall`
11. a `missedCall` payload is rejected on a PLAYFILE campaign (non-empty, so `PlayfileCampaignConfig` refuses it)
12. a `connectByAgent` payload is rejected for MISSED_CALL
13. a DTMF campaign whose terminal action is `CONNECT_BY_AGENT` still parses as DTMF

### C. The four gate fixes (4) — *the regression tests that matter most*
14. `validateTypeConfig` validates MISSED_CALL (guard against re-fail-open)
15. a MISSED_CALL campaign with **no** content is creatable (guard against fail-closed)
16. readiness does **not** demand content for MISSED_CALL
17. readiness **does** report an invalid MISSED_CALL config

### D. Tenant isolation (4)
18. same-tenant DID accepted
19. foreign DID reported as unavailable, not as "wrong tenant"
20. same-tenant audience accepted
21. foreign audience rejected, with no existence leak

### E. Snapshot (4)
22. config frozen into `campaign_execution_configurations.type_config` (PostgreSQL, JSONB round trip)
23. editing ring duration after execution creation does not mutate the snapshot
24. a later execution picks up the new value in its own snapshot
25. the runtime request reads the snapshot, not the live campaign

### F. Readiness (5)
26. ACTIVE DID + valid audience + valid config → ready
27. `INACTIVE` DID → `DID_UNAVAILABLE`, not ready
28. foreign DID → `DID_UNAVAILABLE`, indistinguishable from missing
29. ring duration out of range → `INVALID_MISSED_CALL_CONFIGURATION`
30. **gateway/provider unavailability does not become a readiness failure**

### G. Runtime — only if OD-1/OD-2 require a seam (to be finalised with the decision)
31. an answered MISSED_CALL campaign reaches the MISSED_CALL service (the VB-7A
    `PlaybackTrigger` guard-test analogue — the exact regression VB-7A had to add)
32. **no media is played** — `playFile` is never called
33. the ring deadline terminates the channel and the attempt reaches a terminal state
34. a PLAYFILE/DTMF campaign is not diverted into the MISSED_CALL service
35. a duplicate CHANNEL_ANSWER is idempotent
36. an agent-less / queue-less deployment is unaffected

### H. Migration (1)
37. PostgreSQL accepts and persists `campaign_type = 'MISSED_CALL'`; the CHECK still rejects a bogus value

**Total ≈ 37 tests, 6 of which (group G) are conditional on OD-1/OD-2.**
Groups A–F are configuration-only and need no runtime.

---

## 23. Duplicate Infrastructure Analysis

| Proposed new thing | Why an existing authority must be reused instead |
|---|---|
| MISSED_CALL retry engine | `RetryPolicyService` is *"the single authority"*, has zero type references, and is snapshot-driven. Reuse. |
| MISSED_CALL safety policy | `DailyDialLimitService` + `DailyAttemptSafetyService` are campaign-type-neutral and already keyed on `CallType.VOICE_BLAST`. A new policy is a second ledger on the same `(tenant, contact, did, date)` key. Reuse. |
| MISSED_CALL schedule/scheduler | One `@Scheduled` tick, six type-neutral steps. A new scheduler is forbidden and unnecessary. Reuse. |
| MISSED_CALL compliance path | `CallEligibilityService` → `VoiceEligibilityService`. A bypass would skip DND. Reuse. |
| MISSED_CALL DID validation | `CampaignResourceValidationService.validateDid`. Reuse. |
| MISSED_CALL audience validation | `validateContactGroupReference` + Model A membership. Reuse. |
| MISSED_CALL readiness | `CampaignReadinessService`. Extend the existing reason vocabulary; do not fork the service. Reuse. |
| MISSED_CALL snapshot | `CampaignConfigurationSnapshot` + `CampaignConfigurationService`, via `type_config`. **No new model, no versioning.** Reuse. |
| MISSED_CALL ring timeout authority | `AgentRingWindow` is agent-leg scoped and cannot be reused. A **new, narrowly-scoped** timeout is justified — but it must be a sibling of the existing pattern, not a second generic timeout framework. |
| MISSED_CALL content validation | `validateContent` already exists (duplicated in 2 files). Reuse and correct, do not add a third copy. |
| MISSED_CALL media/ring | `bgapi originate` is already media-free. **No media code at all.** Reuse by doing nothing. |
| New campaign module | Campaign configuration belongs in `campaign`, as all three existing types demonstrate. Reuse. |

**Only one genuinely new authority is justified: the MISSED_CALL ring deadline.**
Everything else is reuse.

---

## 24. Open Decisions

Nine, as the brief requires. Each is marked with what the repository already
establishes.

### OD-1 — What constitutes successful MISSED_CALL delivery? — **DECISION REQUIRED**

**Repository evidence.** `HangupCauseMapper` maps cause 16 / `NORMAL_CLEARING` to
success (`EslEventService:481`) and cause 19 to `NO_ANSWER`, a `FAILED`,
`RetryClass.TEMPORARY`, **retryable** outcome. `StaleCallReconciler` records our
own `uuid_kill`'s normal-clearing cause as *"exactly the cause that otherwise
means 'delivered blast'"*. `CallFailureCode` has **no** code representing
"delivered/ringed" — success is `status = COMPLETED` with a null failure code.

**Options.**
- **A.** "Rang for the full window without answer, ended by us (cause 16)" is
  success. Already true today. Requires only that we terminate at the deadline.
- **B.** "Carrier reported no-answer (cause 19)" is also success. **Not true
  today** — it is a retryable failure, so a working MISSED_CALL would retry the
  very outcome it wants. Needs a new classification seam.
- **C.** Only `COMPLETED` is success, and no-answer is a normal failure.
  Equivalent to A in practice.

**Consequence.** A is a small change. **B is the branch that decides whether
VB-7B is one phase or two**, because a carrier hangup arrives after we stop
acting, so the type-agnostic `inCallFailureRecorded` mechanism cannot express it
in advance.

**Decision needed.** A or B.

### OD-2 — What happens if the recipient answers? — **DECISION REQUIRED**

**Repository evidence.** An answered call fires `CHANNEL_ANSWER`; every
`PlaybackTrigger` self-guards by type and MISSED_CALL owns none, so nothing
acts. `deadline_at` is written only by `PlayfileExecutionService:349`, so
MISSED_CALL sessions have **no deadline** and would sit until the 5-minute
`STALE_ATTEMPT_RECONCILED` sweep — recorded as a dispatched *failure*, and
retried.

**Options.**
- **A.** Hang up immediately on answer; the call was a "miss" that did not happen
  → a distinct, non-retryable outcome.
- **B.** Apply a short post-answer cap (a session deadline) then terminate →
  reusable via generalising `applyMaxCallDuration`.
- **C.** Let the existing 5-minute stale sweep terminate it → zero code, but a
  5-minute answered call and a *retryable* failure, both almost certainly wrong.

**Consequence.** A and B both need a seam; B reuses an existing one. C is
free but wrong on both counts.

**Decision needed.** A or B. C should be rejected.

### OD-3 — Does provider acceptance consume the daily dial limit? — **RESOLVED BY EXISTING ARCHITECTURE**

`DailyDialLimitService`: *"A slot is consumed only when the provider accepted the
originate … Everything after acceptance (ring, no-answer, busy, hangup,
post-acceptance failure) counts."* Call site `OutboundDialService:371`. The
ledger is keyed on `CallType.VOICE_BLAST`, which every campaign call gets at
`OutboundDialService:440`.

**Yes — a MISSED_CALL ring consumes VB-6C budget, and that is the
compliance-correct answer. No decision, no change.** Confirmed for the
implementation to assert, not to redesign.

### OD-4 — Does MISSED_CALL use the existing daily attempt ceiling? — **RESOLVED BY EXISTING ARCHITECTURE**

`DailyAttemptSafetyService` has zero `CampaignType` references and is invoked
from the same pipeline. Its javadoc tabulates *"Provider accepts then the call
fails/NO_ANSWER"* as a counting case.

**Yes. No change.** The implementation should assert participation, not extend
VB-6D.

### OD-5 — What does ring duration technically control? — **DECISION REQUIRED**

**Repository evidence.** No existing authority applies (§8). `AgentRingWindow`
is agent-leg scoped; `MaxCallDurationPolicy` bounds an *established* session
from `answeredAt`, which is the opposite of a ring window; the 5-minute
`STALE_ATTEMPT_THRESHOLD` is a recovery sweep, not a policy.

**Options.**
- **A.** Pre-answer only: terminate the ringing channel if unanswered at
  `answeredAt_absent + ringDurationSeconds`. The classic meaning.
- **B.** Pre- and post-answer: the same budget also caps a call that *is* answered
  (which subsumes OD-2 = B).
- **C.** Carrier-side: set a `ringDuration` in the originate so the *carrier*
  enforces it. Not expressible in the current `bgapi originate` string
  (`EslClient:327` builds it with no timeout variable), so this needs a
  telephony change.

**Consequence.** A is the minimal, semantically honest choice. B folds OD-2 in
and reuses `deadline_at`. C crosses the telephony boundary VB-7B should not.

**Decision needed.** A or B, and the numeric range. A floor of ~10 s and a
ceiling of ~60 s follow the platform's existing ring scale
(`AgentRingWindow` 10–240) — but the **ceiling** here should be well under
`StaleCallReconciler`'s 5 minutes so the ring deadline always precedes the
recovery sweep, exactly as VB-7A bounded `AgentRingWindow` under the ACD TTL.

### OD-6 — Live or frozen audience membership? — **RESOLVED BY EXISTING ARCHITECTURE**

`CampaignExecutionOrchestrator:244`: *"the audience is the group's **LIVE
MEMBERSHIP** … Membership later changes cannot alter this already-started
execution (**Model A**)."*

**Live at execution creation; the group *reference* is frozen. No decision.**

### OD-7 — Same DND/whitelist/compliance behaviour? — **RESOLVED BY EXISTING ARCHITECTURE**

`CallEligibilityService` (zero type references) delegates to
`VoiceEligibilityService` for blocklists/DNC/DID/gateway and adds whitelist
(`callOnWhitelistNumbers`) and group-membership checks, evaluated at
`OutboundDialService:201` before the dial.

**Identical gates, no bypass. No decision.** A MISSED_CALL campaign that could
evade DND would be a compliance defect, and nothing in the brief's scope
justifies one.

### OD-8 — Does MISSED_CALL need a runtime service, or can an existing lifecycle service carry it? — **DECISION REQUIRED**

**Repository evidence.** There is **no** existing service that rings and
terminates. The three nearest neighbours each do something else:
`PlayfileExecutionService` plays media then applies a *post-answer* deadline;
`DtmfExecutionService`/`IvrExecutionService` play media then collect input;
`AgentOutboundCallService` (VB-4E) originates from an agent to a customer and
**bridges** on answer, and is scoped to `CONTACT_CENTER_OUTBOUND`, not campaign
execution.

**Options.**
- **A.** A new `MissedCallExecutionService` in `campaign` implementing
  `PlaybackTrigger` — the exact VB-7A `ConnectByAgentExecutionService`
  precedent, on the same `CHANNEL_ANSWER` seam, reading the frozen snapshot.
  Small, local, and it needs no change to any other service.
- **B.** Generalise `PlayfileExecutionService.applyMaxCallDuration` into a shared
  deadline applier. Slightly wider blast radius, but removes the
  PLAYFILE-only oddity that `deadline_at` is written in exactly one place.

**Consequence.** A is minimal. **B is arguably the better engineering** and the
audit recommends A + B's extraction together, since the ring deadline and the
max-duration deadline are the same mechanism applied at two different moments.

**Secondary, same decision:** for a MISSED_CALL, the dialled CLI *is* the
product, and routing may substitute a different DID than the campaign requested
(`OutboundDialService:278-279`). Should a MISSED_CALL campaign's configured DID
be authoritative, or does route substitution stand? **The repository's answer is
that routing wins**; if the product wants the campaign's DID to be authoritative,
that is a routing change and belongs to a different phase.

**Decision needed.** A, A+B, or B. Plus: is route-substituted DID acceptable for
this campaign type?

### OD-9 — Schedule semantics outside the Voice Blast execution path? — **RESOLVED BY EXISTING ARCHITECTURE**

MISSED_CALL is a `CampaignType` and therefore executed by
`CampaignExecutionOrchestrator` → `OutboundDialService` like every other
campaign. All six tick steps are type-neutral, and timezone comes from the
snapshot (failing closed with `EXECUTION_TIMEZONE_INVALID`).

**There is no separate schedule semantics. The premise of the question does not
arise. No decision.**

### Decision summary

| OD | Status | Blocks implementation? |
|---|---|---|
| OD-1 success definition | **DECISION REQUIRED** | **Yes** — determines phase count |
| OD-2 answered behaviour | **DECISION REQUIRED** | **Yes** |
| OD-3 daily dial limit | RESOLVED | No |
| OD-4 attempt ceiling | RESOLVED | No |
| OD-5 ring semantics + range | **DECISION REQUIRED** | **Yes** — needs the numeric bounds |
| OD-6 audience liveness | RESOLVED | No |
| OD-7 compliance | RESOLVED | No |
| OD-8 runtime seam | **DECISION REQUIRED** | **Yes** — but a safe default exists |
| OD-9 scheduling | RESOLVED | No |

**Five resolved, four open — and the four open ones are all answerable in one
conversation.** The configuration work (P0/P1) depends on none of them except
OD-5's range.

---

## 25. Recommended Implementation Scope

One coherent phase, mirroring how VB-6E and VB-7A coupled configuration to a
minimal runtime. Dependencies are tightly coupled: shipping a configuration
nobody can execute is exactly the "dead configuration" trap VB-7A's audit
warned about, and shipping a runtime before the success definition is settled
risks building the wrong thing.

**This section proposes; it does not implement.**

### P0 — configuration correctness and invariants

1. **`V54`**: extend `ck_campaigns_type` to include `MISSED_CALL`. Pure additive
   `ALTER TABLE`; no enum, no table, no column.
2. **`CampaignType`**: add the constant with a javadoc stating what a
   MISSED_CALL campaign does.
3. **`MissedCallCampaignConfig`**: the typed record
   (`ringDurationSeconds`, `schemaVersion`), root key `"missedCall"`. Strict and
   total; **reject unknown fields**, copying VB-7A's pattern. Range from
   `AgentRingWindow`-style shared constants so the validator and the enforcer
   cannot disagree — the exact lesson of VB-7A.
4. **Sealed wiring**: `permits` + the exhaustive `switch` arm. Compiler-enforced.
5. **The four gate fixes** (§15) — `CampaignService:435`, `:441`,
   `CampaignReadinessService:299`, `:307`. Invert the two inclusive lists to be
   *inclusive* (`PLAYFILE || DTMF` for content; all types for typeConfig) so a
   future type cannot silently fail open again. **This is the single most
   important P0 item** and the cheapest to get wrong.
6. **Readiness reason** for an invalid MISSED_CALL config, in the existing
   non-leaking style alongside the VB-7A agent reasons.

### P1 — runtime integration (gated on OD-1/OD-2/OD-5/OD-8)

7. `MissedCallExecutionService` in `campaign` implementing `PlaybackTrigger`,
   guarding on `campaignType() == MISSED_CALL`, reading the frozen snapshot.
8. The ring deadline terminator, as a **sibling** of
   `AgentConnectTimeoutScheduler` on the **existing** tick — no new scheduler
   framework, no distributed lock.
9. Extract `applyMaxCallDuration` from `PlayfileExecutionService` so
   `deadline_at` has one writer instead of a PLAYFILE-only one (OD-8 = A+B).
10. **No change** to `EslEventService`, `OutboundDialService`,
    `HangupCauseMapper`, `RetryPolicyService`, `DailyDialLimitService`,
    `DailyAttemptSafetyService`, `CallEligibilityService`,
    `VoiceRoutingService`, or anything in `audio`/`tts`/`contact`.

### P2 — hardening, docs, observability

11. Add the one new ring-timeout authority's bounds to the shared-constants
    pattern and assert them in a test, as VB-7A did for `AgentRingWindow`.
12. **Rename or document `VoiceRoutingService`'s dead `callType` parameter** (§20)
    so a future per-type routing change is not built on a false signal. Explicitly
    *not* a VB-7B dependency.
13. Note the pre-existing content/typeConfig rule duplication across
    `CampaignService` and `CampaignReadinessService`; do not refactor in this
    phase.
14. Generated-OpenAPI contract tests for every documented claim (queue→DID,
    strategy→ring, bounds, 400 contract, bearer security).
15. Implementation documentation: `docs/VB-7B-MISSED-CALL-CAMPAIGN-CONFIGURATION.md`,
    mirroring the VB-7A record.
16. Consider a readiness reason for an **empty audience** — today it is a
    `log.warn` only (§7). Flagged, not proposed for this phase.

---

## 26. Explicit Out-of-Scope Items

- **MISSED_CALL webhooks / notifications** — the brief's VB-7C; `integrationConfig`
  is persisted and read by no code.
- **Report privacy** — not a MISSED_CALL concern.
- **AI agents / voice AI** — `CallType.AI` exists in the enum but is unused;
  MISSED_CALL is explicitly not it.
- **Predictive / progressive / preview dialing** — a different `CallType`.
- **Reusable IVR changes** — VB-6F shipped; MISSED_CALL has no IVR.
- **TTS synthesis** — still unimplemented (VB-6E `OD-B`); MISSED_CALL plays no
  media at all, so this is doubly out of scope.
- **PLAYFILE / DTMF / CONNECT_BY_AGENT behaviour changes** — the four gate fixes
  are the *minimum* correction and must leave all three types' behaviour
  identical.
- **A new scheduler** — explicitly forbidden and unnecessary (§12).
- **A MISSED_CALL retry engine / safety policy / compliance path** — §23.
- **Report privacy masking, audience scale, import limits** — VB-6B concerns.
- **Any change to the FreeSWITCH development track** (`infra/freeswitch/`,
  `infra/docker-compose.freeswitch.yml`, `docs/freeswitch/`,
  `docs/LIVE-FREESWITCH-PHASE-B.md`) — user-owned and uncommitted (§25).
  `telephony.freeswitch.enabled` is `false` in the shipped `dev` profile
  (`application-dev.yaml:46`), and VB-7B must not depend on live FreeSWITCH; the
  existing socket-level ESL double remains the verification surface.
- **`docs/campaign-readiness.md`, `backend/docs/future-hardening.md`** — user-owned.
- **Campaign configuration versioning, snapshot history, legacy fallback** —
  forbidden.
- **VB-7C integrations**, and any further migration beyond `V54`.

---

## 27. Final Verdict

# READY WITH DECISIONS

**Not BLOCKED.** No fundamental architectural or product decision blocks
progress: the architecture is sound, the module graph is acyclic, and five of the
nine open decisions are already settled by the existing design. Calling it
BLOCKED would confuse "the implementation work is missing" — which is the whole
point of an audit — with a genuine impasse.

**Why not READY:** four decisions are genuinely open, and one of them (OD-1)
determines whether VB-7B is a single small phase or a phase plus a new
classification seam. Implementing configuration before OD-1 is settled risks
building a campaign type whose success definition is wrong — the precise
"configuration an operator can build and watch fail 100% of the time" failure
that `CampaignService:405-414` records VB-6E having fixed for TTS.

### Why it is not blocked — the affirmative evidence

- `MISSED_CALL` is absent, so nothing must be unwound, migrated or deprecated.
- The sealed `CampaignTypeConfig` hierarchy with an **exhaustive** switch makes
  the type addition compiler-enforced in exactly the right three places.
- **5 of 6 expected configuration concepts already exist as snapshotted,
  validated, authority-backed campaign columns.** Only ring duration is new, and
  it fits existing JSONB — no configuration migration.
- The **one** required migration is a single additive CHECK-constraint `ALTER`,
  predicted in advance by `docs/VB-7A-…-AUDIT.md:1159`.
- Safety, retry, compliance, scheduling, routing and audience are **all**
  campaign-type-neutral — MISSED_CALL participates automatically (OD-3, OD-4,
  OD-6, OD-7, OD-9 all resolved).
- `bgapi originate` carries **no media argument**, so a ring-only call needs no
  media code whatsoever.
- `EslEventService`'s `inCallFailureRecorded` precedence offers a **type-agnostic**
  way to record outcome intent, so the runtime seam need not touch the
  classifier.
- **No existing test asserts `CampaignType` cardinality**, so the enum addition
  should break nothing.
- Constructor-arity and dispatch precedent (VB-7A) shows the pattern for optional
  new seams without touching ~20 unrelated tests.

### The one thing to get right

**The four fail-open/fail-closed gates (§15).** Two of them
(`CampaignService:441`, `CampaignReadinessService:307`) would silently skip all
MISSED_CALL configuration validation, and two would demand content the type must
not have. They are the same inclusive-list anti-pattern VB-7A had to correct for
CONNECT_BY_AGENT, and they are invisible until a fourth type exists. This is the
cheapest item in the phase to get wrong and the most damaging.

### Answering OD-1, OD-2, OD-5 and OD-8 in one conversation

unblocks the phase. A safe default exists for OD-8 (a
`MissedCallExecutionService` mirroring VB-7A's), so if the product wants to move
quickly, answering **OD-1 = A** (our own cause-16 termination is success),
**OD-2 = B** (cap an answered call via a session deadline),
**OD-5 = B** (the budget caps pre- and post-answer) yields a single coherent
phase with **no change to any shared classifier** — the smallest possible
correct MISSED_CALL.

---

## 28. Final Audit Verification

| Check | Result |
|---|---|
| No production source changed | **Yes** — 0 files in `src/main` touched |
| No migration changed | **Yes** — 0 files in `db/migration` touched |
| No test changed | **Yes** — 0 files in `src/test` touched |
| No API implementation changed | **Yes** |
| No pom.xml / build file changed | **Yes** |
| No user-owned file changed | **Yes** — all 9 user paths byte-identical to the pre-audit `git status` |
| Only the audit report created | **Yes** — `docs/VB-7B-MISSED-CALL-CAMPAIGN-CONFIGURATION-AUDIT.md` |
| No files staged | **Yes** — `git diff --cached` empty |
| No commit created | **Yes** — HEAD still `c9b468f` |
| No push performed | **Yes** — `origin/main` still `c9b468f` |
| Baseline tests unchanged | **Yes** — 1683 / 0 F / 0 E / 1 S, `BUILD SUCCESS` |
| Architecture unchanged | **Yes** — `ArchitectureTest` pass, 16 modules, 0 cycles |
| FreeSWITCH track untouched | **Yes** — `infra/*`, `docs/freeswitch/`, `docs/LIVE-FREESWITCH-PHASE-B.md` unmodified and unstaged |
