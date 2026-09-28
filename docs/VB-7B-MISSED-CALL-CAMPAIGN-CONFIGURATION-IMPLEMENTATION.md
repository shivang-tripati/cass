# VB-7B — MISSED_CALL Campaign Configuration + Minimal Runtime

Implementation record for VB-7B. The design is the one audited in
`docs/VB-7B-MISSED-CALL-CAMPAIGN-CONFIGURATION-AUDIT.md`; its nine open decisions
were locked before any code was written.

- Baseline: `c9b468f` (VB-7A), **V53**, suite **1683 / 0 F / 0 E / 1 S**, **16 modules, 0 cycles**
- Migration head after this phase: **V54**
- Suite after this phase: see §17

---

## 1. Status

**COMPLETE** — all 28 acceptance criteria satisfied.

---

## 2. Baseline

| Item | Verified |
|---|---|
| Commit / `origin/main` | `c9b468f`, identical, 0 ahead |
| Migration head | V53 (52 files) |
| Tests | 1683 run, 0 F, 0 E, 1 skipped |
| Architecture | `ArchitectureTest` PASS, 16 modules, 0 cycles |
| Runtime | Java 17, Spring Boot 4.1.0, Jackson 3, Modulith 2.1.0 |

No pre-existing failures; nothing was fixed to make the baseline pass.

---

## 3. Decisions locked and how each was honoured

| OD | Decision | Implementation |
|---|---|---|
| **OD-1** | Platform-controlled termination after the ring window is the success | The MISSED_CALL sweep calls the **existing** `VoiceMediaController.terminateCall` and records **no** failure code. `HangupCauseMapper` and `EslEventService` are untouched, so cause 16 → `COMPLETED` → no retry. Carrier cause 19 is left exactly as it was. |
| **OD-2** | An answered call is not hung up immediately | `onAnswered` **rebases** the deadline onto the answer instant. The call stays connected and silent until the sweep terminates it — never until the 5-minute stale recovery. |
| **OD-5** | One duration, two phases; bounds 10–60, default 30 | `MissedCallRingWindow` — one authority read by both the parser and the runtime. Max is derived to stay far under the 5-minute stale threshold, so the policy always decides the outcome before recovery does. |
| **OD-8** | A+B: campaign-level service **and** an extracted deadline authority | `MissedCallExecutionService` (campaign) + `CallSessionDeadlineAuthority` (campaign), with `PlayfileExecutionService` delegating to it. |
| **OD-3/4** | Safety semantics unchanged | `DailyDialLimitService` and `DailyAttemptSafetyService` are **not modified**. Participation is proven by test, not by code. |
| **OD-6** | Model A audience | Unchanged. `createInitialAttempts` is untouched; the PostgreSQL test proves a member contact becomes an attempt row and the group reference is frozen. |
| **OD-7** | Identical compliance, no bypass | No compliance code written. `CallEligibilityService` / `VoiceEligibilityService` are untouched. |
| **OD-9** | Existing tick, no new scheduler | `MissedCallExecutionService` contains **no `@Scheduled`**. The sweep is a `runStep` on the existing `CampaignExecutionOrchestrator.scheduledTick`. |

---

## 4. CampaignType and configuration

`CampaignType.MISSED_CALL` added. The sealed hierarchy is updated in both
required places — `permits` and the **exhaustive** `switch` — with **no default
arm**, so the compiler refuses to build until a new type has a parse arm. That is
the same compile-time guarantee VB-7A relied on.

`MissedCallCampaignConfig(Integer ringDurationSeconds, ConfigSchemaVersion)` under
root key `"missedCall"`. Strict and total: absent root, missing duration,
non-integer, out-of-range, and **unknown fields** all raise
`CampaignConfigInvalidException` → HTTP 400 via the existing handler.

**One field, deliberately.** The other five concepts the brief named are not here
because they already exist as campaign-level columns with their own single
authorities, and all are already snapshotted:

| Concept | Existing authority | Snapshot column |
|---|---|---|
| DID | `CampaignResourceValidationService.validateDid` | `did_id` |
| audience | `ContactGroupMemberRepository` (Model A) | `contact_group_id` |
| retry | `RetryPolicyService` | `retry_max_attempts`, `retry_interval_seconds`, `retry_strategy`, `retry_rules` |
| schedule | orchestrator | the seven schedule columns |
| safety | `DailyDialLimitService`, `DailyAttemptSafetyService` | `daily_dial_limit`, `max_daily_attempts` |

Duplicating any of them would create a second source of truth. The campaign
carries **no** audio, content mode, TTS, DTMF, IVR, queue, agent or webhook
configuration.

### Ring window bounds — derived, not arbitrary

| Constant | Value | Derivation |
|---|---|---|
| `MIN_RING_SECONDS` | 10 | A window shorter than this cannot express "ring the subscriber" |
| `MAX_RING_SECONDS` | 60 | Matches the platform's existing ring scale (`CONNECT_TIMEOUT_SECONDS` = 60) and stays far below `StaleCallReconciler`'s 5-minute threshold, which records a **failure** — a budget above it would let recovery turn a successful ring into a retryable failure |
| `DEFAULT_RING_SECONDS` | 30 | Used only where the existing configuration semantics permit a default; stored configs always carry an explicit value |

Deliberately independent of `AgentRingWindow` (agent leg) and
`MaxCallDurationPolicy` (established-session lifetime) — a test asserts they are
not the same authority.

---

## 5. V54 — the one migration

`V54__campaign_type_missed_call.sql` widens one constraint:

```sql
ALTER TABLE campaigns DROP CONSTRAINT IF EXISTS ck_campaigns_type;
ALTER TABLE campaigns ADD CONSTRAINT ck_campaigns_type
    CHECK (campaign_type IN ('PLAYFILE','DTMF','CONNECT_BY_AGENT','MISSED_CALL'));
```

`campaign_type` is `VARCHAR(30)` with a CHECK, **not** a PostgreSQL enum, so this
is a plain additive constraint swap — no `ALTER TYPE`, no new table, no new
column, no index. Verified against real PostgreSQL: MISSED_CALL persists, all
three pre-existing types still persist, and a bogus type is still rejected by the
same named constraint.

No configuration migration: the payload rides the existing
`campaigns.type_config` (V14) and `campaign_execution_configurations.type_config`
(V44) JSONB.

---

## 6. The four validation gates — corrected

This was the highest-priority item in the audit, and the most damaging to get
wrong.

### Content requirement — now inclusive

```java
// CampaignService.validateContent  +  CampaignReadinessService.checkContentConfiguration
if ((type == CampaignType.PLAYFILE || type == CampaignType.DTMF) && mode == null) { ... }
```

Previously `type != CONNECT_BY_AGENT && mode == null`, which was correct only
while the enum had three values. A negated condition is **fail-open by
construction**: a new type is required to have content until someone remembers
to exclude it. An inclusive list is safe by default — a fifth type is excluded
until deliberately added.

### typeConfig requirement — now delegated, not listed

```java
// CampaignService.validateTypeConfig
try { CampaignTypeConfig.fromTypeConfig(type, typeConfig); }
catch (CampaignConfigInvalidException e) { throw business(e.getMessage()); }
```

Previously `required = type == DTMF || type == CONNECT_BY_AGENT; if (!required) return;`
— the same fail-open list in a second file. It would have skipped **all**
MISSED_CALL configuration validation, at write time, at activation, and at
readiness. Delegating means the sealed hierarchy is the single authority and the
exhaustive `switch` is compiler-enforced.

This is **strictly stronger** for the two types already listed, and
**unchanged** for PLAYFILE (whose valid configuration is the empty object). Both
directions are proven by test, including the canary that a PLAYFILE campaign
carrying a non-empty payload is now rejected — the same fail-open shape.

`CampaignReadinessService` reports an unparseable payload with a
type-appropriate code via one helper, `invalidTypeConfigReasonCode`, so the
write-time and readiness surfaces cannot drift. `checkConnectByAgent` no longer
re-reports a parse failure (the generic check owns it), which prevents a
duplicate reason in the VB-7A response.

---

## 7. Snapshot behaviour

No new snapshot model, no version column, no history table, no legacy fallback.

Frozen: `campaign_type` = `MISSED_CALL`, plus the existing DID, audience, retry,
schedule, safety and compliance columns, plus `typeConfig.missedCall.ringDurationSeconds`
through the existing JSONB copy.

Live, re-checked per execution (VB-6A snapshot-vs-resource rule): DID status and
allocation, gateway and provider availability, contact validity, DND/blocklist,
whitelist, and group membership at execution creation.

Proven against PostgreSQL: the duration round-trips both JSONB columns; a
campaign edit after execution creation leaves the earlier snapshot byte-identical
while a later execution picks up the new value; and the runtime resolves its
budget from the snapshot, not the mutable campaign.

---

## 8. DID and audience — pure reuse

No MISSED_CALL DID model. `campaigns.did_id` +
`CampaignResourceValidationService.validateDid` (live, not deleted, same tenant,
`ACTIVE`, `ASSIGNED`), frozen as `did_id`, re-validated at execution creation.

`VoiceRoutingService` is **untouched**. The existing rule stands: the selected
route's DID wins, the campaign's is the fallback. MISSED_CALL does not make the
campaign DID authoritative — that would be a routing change and belongs to a
different phase.

Audience: Model A, unchanged. `createInitialAttempts` resolves live membership
and materialises attempts; later membership changes cannot affect a running
execution. No MISSED_CALL audience table.

Tenant isolation is preserved by the inherited validators, and proven for both the
DID and the audience: a foreign reference is reported with the **same code and
message** as a missing one, so the response cannot be used to probe another
tenant.

---

## 9. Retry, safety and compliance — proven, not modified

| Concern | Status |
|---|---|
| `RetryPolicyService` | **Unmodified.** MISSED_CALL uses it exactly like every other Voice Blast campaign. |
| `DailyDialLimitService` | **Unmodified.** Provider acceptance (`+OK`) consumes the budget; everything after counts. |
| `DailyAttemptSafetyService` | **Unmodified.** A provider-accepted attempt participates. |
| `CallEligibilityService` / `VoiceEligibilityService` | **Unmodified.** DND/blocklist first, whitelist restrictive, DID and group validation tenant-scoped, retries re-enter the same pipeline. |

A successful MISSED_CALL carries **no** failure code, so it is `COMPLETED` and
never retried. Carrier `NO_ANSWER` remains `NO_ANSWER` → `FAILED` → `TEMPORARY` →
existing retry rules. No MISSED_CALL retry category was added and
`HANGUP_UNKNOWN` is untouched.

---

## 10. Runtime flow

```
CampaignExecution → CallAttempt → OutboundDialService
  → eligibility → routing → capacity → daily limits (all type-neutral)
  → bgapi originate  [NO media argument]
  → CHANNEL_PROGRESS / RINGING
  → scheduledTick "terminate-missed-call-budgets"
      → pre-answer budget elapsed?  stamp deadline + terminateCall   [no failure code]
  → CHANNEL_HANGUP cause 16 → EslEventService → COMPLETED, no failure code

If the callee answers:
  → CHANNEL_ANSWER → MissedCallExecutionService.onAnswered
      → rebase deadline = answeredAt + ringDurationSeconds
      → no media, no collection, no bridge; stay connected
  → scheduledTick sweep → deadline elapsed → terminateCall
  → CHANNEL_HANGUP cause 16 → COMPLETED
```

There is no media, no DTMF, no IVR, no agent, no queue and no bridge anywhere on
this path.

### Why `PlaybackTrigger`

Because it is this codebase's established `CHANNEL_ANSWER` seam into the campaign
execution layer, and its contract says implementations act only for the types they
own so several beans coexist without dispatch ambiguity. `DtmfExecutionService`
is a `PlaybackTrigger` for the same reason — it also plays nothing. Nothing about
playback is used.

---

## 11. Deadline semantics

`CallSessionDeadlineAuthority` is now the **single writer** of `deadline_at`.
Before this phase the column had exactly one writer in the whole platform: a
*private* method on `PlayfileExecutionService`. That was fine with one user and
quietly wrong with two, so the calculation and persistence were extracted and
PLAYFILE now delegates to it. **PLAYFILE behaviour is unchanged**: same anchor
(`ANSWERED`), same value (`MaxCallDurationPolicy`), same idempotency (an existing
deadline is never re-stamped, so a duplicate answer cannot move it).

| Anchor | Used by | Measured from |
|---|---|---|
| `SESSION_START` | MISSED_CALL pre-answer | the session's initiation |
| `ANSWERED` | PLAYFILE max duration; MISSED_CALL post-answer | the answer instant |

The post-answer case is what satisfies "an answered call does not retain the
pre-answer deadline": the answer **rebases** it, and the rebasing is idempotent.

### Deadline ownership — why the reconciler had to be touched

`StaleCallReconciler` enforces a **cap**: a call established longer than its
maximum duration is a fault, so it records `MAX_DURATION_EXCEEDED` before
terminating. That failure code is what makes the attempt `FAILED` and retryable.

A MISSED_CALL session also carries a `deadline_at`, but its deadline is a
**mission budget** — reaching it *is* the success. Terminating it must record
nothing, or the recorded-code precedence in `EslEventService` would turn a
successful ring into a retryable failure.

So the two policies would have raced over one column. Rather than let a shared
reconciliation sweep silently make campaign-type decisions, it asks one question
and declines, through a narrow `CallSessionDeadlineOwner` seam:

- the interface is **side-effect free** and knows nothing about the type;
- `MissedCallExecutionService` implements it by asking "is this a MISSED_CALL
  execution with a deadline?";
- the reconciler keeps owning maximum duration entirely, and `ownsDeadline` is
  absent (`null` provider) in every pre-existing construction site, so all of
  them behave exactly as before.

This is deliberately the *opposite* of turning the reconciler into the MISSED_CALL
policy: the policy stays in the campaign module.

Two further race protections: the pre-answer path stamps the deadline and
requests the teardown **in the same transaction**, so a stamped-but-unterminated
session is never observable; and a session with `deadline_at IS NULL` is invisible
to the reconciler's query in the first place.

---

## 12. Answered-call behaviour

On answer: nothing is played, nothing is collected, nothing is bridged, and the
call is **not** immediately classified as a failure. It gets the post-answer
deadline and is terminated at it, which classifies as `COMPLETED` under the locked
semantics.

Idempotency reuses the existing patterns: the guard requires
`status == ANSWERED`, and `applyDeadline(..., rebase = true)` is a no-op when a
deadline already exists, so a duplicate `CHANNEL_ANSWER` cannot create a second
timer, move the budget forward, or produce a second terminal transition. Proven by
test.

---

## 13. No new scheduler

`MissedCallExecutionService` contains **no** `@Scheduled` annotation. The sweep is
invoked as `runStep("terminate-missed-call-budgets", ...)` on the **existing**
`CampaignExecutionOrchestrator.scheduledTick`, in its own failure boundary, so it
can neither delay nor be delayed by another step.

It is injected as an **optional setter** dependency rather than a constructor
argument, specifically so neither of the two test harnesses that build the
orchestrator by hand changes.

No Quartz, no second `ScheduledExecutorService`, no Redis timers, no Kafka, no
background-worker framework, no distributed locks.

The sweep is bounded (`SWEEP_BATCH = 200`) and pre-filtered by the **minimum**
legal budget, so a pre-filter can never miss a due call; the exact configured
budget is then resolved per candidate from the frozen snapshot, and a call whose
longer configured window has not elapsed is left ringing.

---

## 14. OpenAPI changes

No endpoint, DTO field, security or error contract changed. The generated document
was **inspected**, not just annotated — a temporary diagnostic dump was used to
establish how springdoc actually renders these schemas before the assertions were
written, and it revealed that `campaignType` is inlined on the property rather
than emitted as a standalone `CampaignType` component. The contract test asserts
the real shape.

`typeConfig` is documented on all three campaign schemas with the MISSED_CALL
shape, the 10–60 bounds, the fact that the campaign plays no media and owns no
agent/queue/input configuration, that the other concepts are ordinary campaign
fields, the answered-call rebase, and that a completed ring is not retried while a
carrier-reported no-answer still is.

`CampaignType`'s generated enum is asserted to carry all four values on both the
request and the response schemas. The 400 contract and `bearerAuth` are asserted
unchanged, and the absence of any `agents` or `missed` path is asserted.

8 new contract tests; `CampaignOpenApiContractTest` is now 28 tests.

---

## 15. Readiness changes

`CampaignReadinessService` only. It gained the two gate corrections and one new
reason code, `INVALID_MISSED_CALL_CONFIGURATION`, alongside the existing
`INVALID_AGENT_CONFIGURATION`.

MISSED_CALL readiness covers: valid typed config, valid ring window, valid DID,
valid audience reference, valid retry policy, valid schedule, valid safety
configuration — the last three entirely inherited.

It is **not** gated on gateway availability, provider availability, contact
availability, agent availability or queue availability. No new readiness service,
no new architecture.

---

## 16. Files changed

### New (13)
```
backend/src/main/resources/db/migration/V54__campaign_type_missed_call.sql
backend/src/main/java/com/shivang/obd/campaign/config/MissedCallRingWindow.java
backend/src/main/java/com/shivang/obd/campaign/config/MissedCallCampaignConfig.java
backend/src/main/java/com/shivang/obd/campaign/CallSessionDeadlineAuthority.java
backend/src/main/java/com/shivang/obd/campaign/CallSessionDeadlineOwner.java
backend/src/main/java/com/shivang/obd/campaign/MissedCallExecutionService.java
backend/src/test/java/com/shivang/obd/campaign/config/MissedCallCampaignConfigTest.java
backend/src/test/java/com/shivang/obd/campaign/CampaignMissedCallValidationTest.java
backend/src/test/java/com/shivang/obd/campaign/MissedCallExecutionServiceTest.java
backend/src/test/java/com/shivang/obd/campaign/MissedCallPostgresIntegrationTest.java
docs/VB-7B-MISSED-CALL-CAMPAIGN-CONFIGURATION-IMPLEMENTATION.md
```

### Modified (12)
```
CampaignType.java                      + MISSED_CALL
CampaignTypeConfig.java                + permits, + exhaustive switch arm
CampaignService.java                   2 gate corrections
CampaignReadinessService.java           2 gate corrections, + reason helper
CallSessionDeadlineAuthority.java       (new, extracted from Playfile)
PlayfileExecutionService.java          delegates to the shared authority
StaleCallReconciler.java               + declines foreign-owned deadlines
CampaignExecutionOrchestrator.java     + 1 tick step, + optional setter
CampaignRuntimeConfigResolver.java     + asMissedCall()
CallSessionRepository.java             + 2 bounded sweep queries
CreateCampaignRequest.java             + OpenAPI documentation
UpdateCampaignRequest.java             + OpenAPI documentation
CampaignResponse.java                  + OpenAPI documentation
CampaignOpenApiContractTest.java       + 8 contract tests
```

Constructor arity changes on `PlayfileExecutionService` and
`StaleCallReconciler` were absorbed by **backward-compatible secondary
constructors** so no existing test had to change.

---

## 17. Tests added

| Suite | Tests | Covers |
|---|---|---|
| `MissedCallCampaignConfigTest` | 21 | configuration (valid, round-trip, missing root/duration, <10, >60, non-integer, unknown field, both bounds, default degradation, max-under-stale) and type isolation (all 4 cross-type leak directions + DTMF-agent-action unchanged + dispatch resolves all 4 types) |
| `CampaignMissedCallValidationTest` | 8 | the four gates: contentless MISSED_CALL valid, PLAYFILE/DTMF still require content, CONNECT_BY_AGENT unchanged, typeConfig actually validated, legacy shape rejected, DTMF not weakened, PLAYFILE empty accepted, PLAYFILE non-empty now rejected |
| `MissedCallExecutionServiceTest` | 19 | reach + post-answer deadline, no media, PLAYFILE/DTMF/CONNECT_BY_AGENT not diverted, duplicate answer idempotent, non-ANSWERED ignored, missing links, snapshot-sourced budget, termination without a failure code, not-due left alone, unanswered termination + stamping, not-owned never claimed, ownership, terminal not re-terminated, answered bounded by its own budget, pre-filter bounded, `onPlaybackCompleted` no-op |
| `MissedCallPostgresIntegrationTest` | 15 | V54 accepts MISSED_CALL / rejects bogus / all prior types persist; snapshot round-trip, edit-does-not-mutate, later execution new value; readiness ready/invalid-config/missing-config; tenant isolation for DID and audience; safety participation and Model A audience; CONNECT_BY_AGENT non-regression |
| `CampaignOpenApiContractTest` (+8) | 28 total | generated enum, bounds, no-media/no-agent, answered behaviour, update+response, bounds match the authority, no API surface change, example parses |

**New tests: 71.** No existing test was weakened, disabled, skipped or deleted.
The only edits to existing tests are the **8 additive** OpenAPI cases.

---

## 18. PostgreSQL verification

`MissedCallPostgresIntegrationTest` runs against a real Testcontainers PostgreSQL
with Flyway V1..**V54**, `ddl-auto: none`, and asserts the persisted behaviour
rather than mocks for: the migration, both JSONB columns, snapshot immutability,
readiness reasons, and tenant-isolated lookups.

Confirmed: MISSED_CALL persists; a bogus type is rejected by `ck_campaigns_type`
(the named constraint, proving the widening did not weaken it); all three
pre-existing types still persist; the ring duration round-trips; a campaign edit
does not mutate an existing snapshot; a later execution picks up the new value;
readiness reports `INVALID_MISSED_CALL_CONFIGURATION` for an unparseable payload;
a foreign DID and a foreign audience are reported identically to missing ones;
and a MISSED_CALL execution materialises its audience into attempt rows through
the ordinary start path.

---

## 19. Regression

The full suite is the regression proof, because these campaign types share almost
every code path this phase touched:

- **PLAYFILE** — `PlayfileExecutionService` changed (deadline extraction); its
  0-argument-free behaviour, anchor, value and idempotency are identical, and its
  own suite is green.
- **DTMF / IVR (VB-6F)** — the typeConfig gate became stricter-by-delegation; the
  DTMF action contract and the IVR tree parsing are unchanged and green.
- **CONNECT_BY_AGENT (VB-7A)** — shares the sealed hierarchy, both corrected
  gates, the readiness reason helper and the new orchestrator step. A dedicated
  PostgreSQL case asserts a CONNECT_BY_AGENT campaign still reports
  `AGENT_QUEUE_NOT_AVAILABLE` and never a MISSED_CALL reason.
- **VB-6C** daily dial limit, **VB-6D.1** taxonomy, **VB-6D.2** retry policy,
  **VB-6D.3** attempt safety — services untouched, suites green.
- **VB-6E** max duration — `StaleCallReconciler` and `PlayfileExecutionService`
  touched; suite green.
- **Campaign lifecycle, snapshot and OpenAPI** suites green.
- `HangupCauseMapperTest` (14/16/28 cases) green — the hangup taxonomy is
  unchanged.

---

## 20. Architecture

**16 modules, 0 cycles.** No new module, no new `@NamedInterface`, no new module
edge. `voice` still does not depend on `campaign`.

The one new cross-package surface is `CallSessionRepository`'s two bounded sweep
queries, which are **campaign-type-neutral by design** — the voice layer must not
know which policy stamped a deadline, so the caller decides ownership. That is why
`CallSessionDeadlineOwner` exists as an interface the reconciler consumes without
importing the MISSED_CALL class.

---

## 21. Known limitations

1. **An unanswered MISSED_CALL is bounded by our own sweep, not by the carrier.**
   A carrier-reported cause 19 is still a retryable failure, so the same real-world
   event can be classified either way depending on who hangs up first. This is the
   locked OD-1 decision, recorded here because it is the one place where the
   platform's outcome taxonomy genuinely differs per campaign type.
2. **Termination is a poll, not a timer.** With a 30-second tick and a budget as
   short as 10 seconds, a call can outlive its budget by up to one tick. Making the
   sweep interval campaign-relative would mean a scheduler per campaign, which this
   phase explicitly forbids.
3. **The sweep is one transaction per terminated call** rather than a batch
   finalize, matching the established per-entity reconciler pattern. At high
   MISSED_CALL volume a batched variant would be preferable; at current volumes
   (bounded to 200 per pass) it is not worth the divergence.
4. **The pre-answer pre-filter resolves a snapshot per candidate.** Bounded and
   over-inclusive by design, but it is a per-candidate read; a persisted
   pre-answer deadline would avoid it at the cost of a second column, which this
   phase was told not to add.
5. **A pre-answer deadline and a `STALE_ATTEMPT_RECONCILED` sweep can both apply.**
   In practice the MISSED_CALL budget (max 60 s) always expires far before the
   5-minute stale threshold, and `ownsDeadline` makes the reconciler decline, so
   the ordering is safe — but it is ordering-by-magnitude, not ordering-by-check.
6. **Empty audience is still only a log warning**, as it is for every other
   campaign type. An operator building a MISSED_CALL campaign that reaches nobody
   gets no readiness signal. Pre-existing behaviour, deliberately not changed
   here.
7. **The pre-existing content/typeConfig rule duplication** between
   `CampaignService` and `CampaignReadinessService` remains. The rules are now
   correct in both places and the reason codes are centralised in one helper, but
   the duplication itself was not refactored, to keep this phase's diff honest.
8. **`VoiceRoutingService`'s dead `callType` parameter** (the audit's P2 finding) is
   untouched, as instructed. It is harmless — nothing reads it — but a future
   per-type routing change would be building on a misleading signal.
9. **The configured DID is still not authoritative.** Routing may substitute a
   different DID than the campaign requested, which for a MISSED_CALL is visible to
   the subscriber as the CLI on their missed-call entry. Changing that is a
   routing decision and belongs to a later phase.

---

## 22. Migration inventory

| Version | Purpose | State |
|---|---|---|
| V53 | IVR trees (VB-6F) | pre-existing |
| **V54** | **widen `ck_campaigns_type` to include `MISSED_CALL`** | **added by VB-7B** |

Next free version: **V55**. Flagged for VB-7C.

---

## 23. Git

- **Commit:** see the final implementation report.
- **Not pushed** unless explicitly instructed.
- Staged precisely by path; no unrelated file was included.
- **User-owned work preserved and untouched**: `backend/docs/future-hardening.md`,
  `docs/campaign-readiness.md`, `infra/.env.example`, `infra/docker-compose.freeswitch.yml`,
  `infra/docker-compose.freeswitch-endpoints.yml`, `infra/freeswitch/`,
  `infra/freeswitch-endpoint/`, `docs/freeswitch/`,
  `docs/LIVE-FREESWITCH-PHASE-B.md`, `tools/`, `backend/data/`, and the
  `docs/VB-7B-...-AUDIT.md` report from the audit phase.
- The FreeSWITCH development track was neither modified nor depended upon;
  `telephony.freeswitch.enabled` remains `false` in the shipped `dev` profile and
  no test requires live FreeSWITCH.
