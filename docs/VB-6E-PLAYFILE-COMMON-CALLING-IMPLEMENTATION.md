# VB-6E — PLAYFILE Production Execution + Common Calling Configuration — Implementation

> **Status: COMPLETE.** Implements the full P0 + P1 scope established by
> `docs/VB-6E-PLAYFILE-COMMON-CALLING-AUDIT.md`. Reusable IVR remains **VB-6F**;
> nothing in this phase touches it.

---

## 1. Why this phase exists

The VB-6E audit's headline was not a missing feature. It was that **the campaign
execution pipeline could not complete a single call end-to-end**, for two
independent reasons, each sufficient on its own:

1. **The FreeSWITCH ESL client was protocol-nonconformant in four ways.** It could
   not complete a handshake, it dropped every inbound event, and it persisted a
   background *job* UUID as a *channel* UUID. Verified by reading the code.
2. **The scheduler's execution-start step could never succeed.** It required an
   interactive user that a scheduler thread does not have, and one `catch` then
   disabled all four remaining steps of the tick.

Both were invisible because every ESL test mocked the client or hand-built event
objects, so the protocol layer had **zero** coverage. This phase fixes the
protocol, proves it against a real socket, and then adds the three absent safety
behaviours (max call duration, stale-call recovery, honoured pause).

---

## 2. ESL protocol design

### 2.1 The four defects, and the fix

| # | Defect (verified pre-VB-6E) | Fix |
|---|---|---|
| **D1** | FreeSWITCH sends an unprompted `Content-Type: auth/request` banner. The client sent `auth` first, then read, then tested `startsWith("+OK")` — so it read the *banner* and `connect()` failed on its first command. | `connect()` reads and consumes the banner **before** sending `auth`, and asserts `banner.isAuthBanner()`. |
| **D2** | The verdict lives in the `Reply-Text` **header**. Every `startsWith("+OK")` on the raw aggregate text therefore failed. | All verdicts read through `EslMessage.isOk()/isError()/verdict()`, which parse `Reply-Text`. A reply with **no** verdict is an error, never a false success. |
| **D3** | A plain event's first line is its `Content-Type`; `Event-Name` lives in the **body**. The old code compared the first socket line against the subscribed names, so every event was dropped. | `EslMessage.readMessage()` reads a header block to the blank line, then exactly `Content-Length` characters. `Event-Name` is read from the body. |
| **D4** | `bgapi originate` answers `+OK Job-UUID: <job>`; the code took `parts[1]` as "the channel UUID" and persisted it. | The channel UUID is **chosen by us** via the documented `origination_uuid` channel variable. See §2.2. |

### 2.2 Job-UUID vs channel UUID

Rather than correlate a late `CHANNEL_CREATE` back to a job, the channel identity
is **pinned before the call is placed**:

```
bgapi originate {origination_uuid=<attemptId>,origination_caller_id_number=<callerId>}sofia/gateway/<gw>/<dest>
```

`FreeSwitchOutboundDialer` passes the **`CallAttempt` id** as the channel UUID. The
channel UUID is therefore known deterministically, so:

- every later ESL event correlates by `Call-UUID` with no race, no polling, and
  no extra table;
- `call_attempts.provider_call_id` holds a real channel identity, which is what
  `EslEventService` resolves against;
- the background job UUID stays **internal to `EslClient`**, returned by a
  private method and never exposed on any public contract — so it cannot be
  persisted as a channel identity by mistake.

The same defect existed on the **agent-leg** path (`ConnectByAgentService`,
`InboundCallService`, `AgentOutboundCallService` all stored the returned value as
`agentLeg.providerCallId`). The four-argument `originate` overload now generates
a channel UUID and returns it, so those callers are correct without change.

### 2.3 The fake ESL server

`FakeEslServer` is an in-process `ServerSocket` that speaks genuine frames:

```
<- Content-Type: auth/request
   (blank line)
-> auth <password>
<- Content-Type: command/reply
   Reply-Text: +OK accepted
   (blank line)
-> event plain CHANNEL_ANSWER …
<- Content-Type: command/reply
   Reply-Text: +OK 0.0ms
<- Content-Type: text/event-plain
   Content-Length: 214
   (blank line)
   Event-Name: CHANNEL_ANSWER
   Call-UUID: …
```

It can deliver a frame **fragmented** across several TCP writes, and **two events
coalesced** into one write, so the client's framing is exercised rather than
assumed. `EslProtocolTest` drives the real `EslClient` against it — nothing is
mocked and the client's internals are never stubbed.

**It is a protocol double, not a media server.** It never opens a SIP channel and
never plays audio. See §11 for what was and was not executed.

---

## 3. Scheduler execution identity

`startExecution` began with `requireUserId()` → `SecurityContextHolder`, which
nothing outside a servlet request populates. The fix is an explicit second entry
point, not a fabricated user:

| Entry point | Identity | Authorization |
|---|---|---|
| `startExecution(id)` (interactive, **unchanged**) | request security context | `CAMPAIGN_EXECUTE` against the execution's tenant |
| `startExecutionAsSystem(id)` (scheduler, new) | the **execution row's** `tenantId`, written when the execution was requested through the authenticated API | no user capability (there is no user); tenant isolation **enforced** by loading the campaign as `(campaignId, tenantId)` |

Requirements met:

- the scheduler can start an eligible execution;
- tenant isolation is *enforced*, not relaxed — a foreign campaign is not found;
- no `SecurityContextHolder` is set, so there is no thread-local leakage
  (`SCHED-3` asserts an existing context is neither read nor overwritten);
- the interactive path and its capability check are byte-for-byte unchanged.

Both entry points converge on `doStartExecution`, so they cannot diverge in what
they consider startable. Readiness uses the *same* rules via
`readinessService.evaluateForSystem`, which shares `evaluateResolved` with the
interactive `evaluate`.

## 4. Tick step isolation

`scheduledTick` wrapped all five steps in one `try/catch`, and is no longer
`@Transactional`:

```java
runStep("start-requested-executions", …);   // uses startExecutionAsSystem
runStep("process-retries",         …);
runStep("dial-due-attempts",       …);
runStep("pump-esl-events",         …);
runStep("reconcile-executions",    …);
runStep("reconcile-stale-calls",   …);      // VB-6E, same tick
```

`runStep` logs a failure with the step's identity and lets the remaining steps
run. Failures are never silent, and the scheduler thread always survives. The
cadence is unchanged, there is still exactly **one** `@Scheduled` method, and each
step now owns its own transaction so one rollback cannot discard another's work
or hold a transaction open across outbound I/O (`TICK-7`, `TICK-8`).

## 5. Media URI mapping

`MediaUriResolver` is the single place a logical storage reference becomes a path
FreeSWITCH can open. Before VB-6E the raw reference
(`audio/{tenant}/{asset}/{file}.wav`) went straight into `uuid_broadcast`, which
FreeSWITCH resolves against its **own** sound directory, so playback could not
succeed in any real deployment.

The mapping is strict, because the result reaches a telephony command:

- **shape validated first** — exactly `audio/{tenantUuid}/{assetUuid}/{file}`,
  three segments and two parseable UUIDs. Traversal, absolute paths, URLs,
  doubled separators, backslashes and NULs are rejected *before* any path is
  built, so they are unrepresentable rather than merely filtered;
- **tenant and asset identity must match** the call's tenant and the asset being
  played;
- **no path is touched** — it resolves a name, and never stats, opens or moves a file.

The root is configuration (`audio.storage.freeswitch-media-root`, default
`/usr/share/freeswitch/sounds`), so a containerised FreeSWITCH is a config change
rather than a code change. This also closes the audit's finding that the
metadata-only registration path could push a **client-supplied** string into
`uuid_broadcast`: such a string no longer matches the grammar and is refused.

## 6. PRE_DISPATCH classification repair

Pre-VB-6E a routing rejection persisted the raw `VoiceRoutingReason` name
(`ROUTE_REJECTED_GATEWAY_DISABLED`, …) into `call_attempts.failure_code`. Those
are not `CallFailureCode` members, so `canonicalize` mapped every one to
`HANGUP_UNKNOWN` → `CONTACT_OUTCOME`/`HANGUP`. **A disabled gateway consumed
campaign retry budget and could re-dial a number that was never called** —
directly contradicting the VB-6D design, where `CallFailureCode.ROUTE_REJECTED`
sits in the pre-dispatch set for exactly this reason and was never written.

`PreDispatchFailureMapper` maps every pre-dispatch reason to a canonical
pre-dispatch code:

- an already-canonical code (the eligibility gate propagates `DNC_BLOCKED`,
  `NOT_WHITELISTED`, `INVALID_DID`, …) **passes through unchanged**;
- capacity reasons → `TEMPORARILY_UNAVAILABLE` (also pre-dispatch, and requeued);
- DID / authorization reasons → `INVALID_DID`;
- everything else → `NO_ELIGIBLE_GATEWAY`.

An **unknown or absent reason maps to `NO_ELIGIBLE_GATEWAY`, never to
`HANGUP_UNKNOWN`.** An unmapped *pre-dispatch* reason must never be dressed up as
a post-dispatch contact outcome — that is the precise failure being repaired.

`PRE-8`/`PRE-9` prove the outcome that matters: under a campaign policy
permitting 5 retries, a routing rejection is still **not retryable** and its
reason contains `PRE_DISPATCH`. `PRE-11` proves the opposite over-correction did
not happen: `NO_ANSWER`, `BUSY` and `PLAYBACK_FAILED` remain retryable.

`CallFailureCode.ROUTE_REJECTED` remains a declared-but-unwritten constant; the
mapper uses the granular codes, which is strictly more informative.

## 7. Max call duration

**Semantics.** The maximum lifetime of an **established** call session, measured
from answer. Explicitly **not** a ring timeout, **not** a provider connection
timeout, and **not** a playback length.

**Chain.** `CampaignEntity.maxCallDurationSeconds` → `CreateCampaignRequest` /
`UpdateCampaignRequest` / `CampaignResponse` → `campaigns.max_call_duration_seconds`
(V52) → `CampaignConfigurationService.toSnapshot` →
`campaign_execution_configurations.max_call_duration_seconds` → `CampaignRuntimeConfig`
→ applied at answer. One rule in three boundaries: the `@MaxCallDurationSeconds`
constraint, `MaxCallDurationPolicy.assertConfigurable`, and the V52 `CHECK`s.

**Default 300 s, range 1..3600, null = platform default** — the same
convention VB-6C.2 and VB-6D.3 established.

**Why application-side, not `absolute_timeout`.** FreeSWITCH can enforce this
itself, and that was considered and rejected. A provider-side timer terminates
the channel without recording intent and is reported with a normal-clearing cause
— which this platform classifies as a **successful** completion. A
provider-side timeout would be indistinguishable from a callee hanging up after
the blast was delivered, and a timed-out call would be recorded `COMPLETED`.

Instead the deadline is computed once at answer and **persisted**
(`call_sessions.deadline_at`). Persisting it makes the sweep one indexed range
scan and makes the timeout **idempotent**: a duplicate or late ESL event cannot
move the deadline or resurrect the call.

**Enforcement reuses the existing mechanism.** The sweeper records
`MAX_DURATION_EXCEEDED` on the session *first*, then asks the media boundary to
terminate. The resulting `CHANNEL_HANGUP` picks the failure up through the same
`session.getFailureCode()` precedence `PLAYBACK_CONFIG_INVALID` already uses — so
a timeout can never be confused with a legitimate normal release.

**Classification.** `MAX_DURATION_EXCEEDED` is `TEMPORARY` and **deliberately not
pre-dispatch**: the subscriber was genuinely connected and heard part of the
blast, so a redial is a real possibility and the campaign's own rules should
govern it. `DUR-10` asserts it stays outside the pre-dispatch set, so a future
edit cannot "helpfully" move it.

## 8. PAUSED semantics

`CampaignStatus.PAUSED` was cosmetic: the only runtime reader of campaign status
was the readiness check, reached solely from execution start and from REST, so a
paused campaign's queued attempts kept dialling and its failed attempts kept
retrying.

`OutboundDialService.processAttempt` now checks `PAUSED` **before any budget is
touched** and **requeues** the attempt rather than failing it. Consequences:

- nothing new is dispatched;
- no VB-6C hold and no VB-6D.3 consumption is charged merely for being paused;
- the attempt keeps its **attempt number**, so a resume is a *first* attempt and
  spends no retry budget;
- no failure code or `completedAt` is recorded, so a pause is never mistaken for
  a call outcome;
- active calls are **not** terminated — they finish naturally, as specified.

On the start path, a `PAUSED` (or merely not-yet-executable) campaign now leaves
its execution `REQUESTED` for a later attempt instead of **failing** it. Pre-VB-6E
a pause destroyed pending work, and since `PAUSED` has no path back to
`REQUESTED`, it was unrecoverable. Configuration and resource failures still fail
deterministically, because those would still be true next tick and silently
retrying them forever would be a hot loop against the database.

## 9. Reconciliation / sweeper

`StaleCallReconciler` runs as the **final step of the existing tick** — no new
scheduler, no framework, no distributed lock. It handles two distinct stale
shapes, separated because they mean different things:

| Shape | Meaning | Code |
|---|---|---|
| answered session past `deadline_at` | max call duration exceeded | `MAX_DURATION_EXCEEDED` |
| attempt `IN_PROGRESS` with no reported outcome past a 5-minute threshold | provider may or may not have connected the subscriber — precisely what a campaign retry is for | `STALE_ATTEMPT_RECONCILED` |

The 5-minute threshold sits safely above every provider-side timeout the platform
already waits on (ESL command 30 s, connect 10 s, a ring cycle), so the sweeper
never races a merely slow call, and matches the existing capacity-reservation
window so both reconcilers mature on the same horizon.

**Guarantees, each asserted:** idempotent (a recorded failure code makes a second
pass a no-op — `SWEEP-3`); never resurrects a completed call (`SWEEP-4`,
`SWEEP-9`, `SWEEP-10`); never invents a pre-dispatch failure (`SWEEP-13`); creates
no retry of its own, so no duplicate dispatch is possible (`SWEEP-14`); bounded
per pass (`MAX_BATCH = 200`, matching the other reconcilers); tenant-safe by
construction (`SWEEP-12`); a dead channel cannot lose the classification
(`SWEEP-6`).

The candidate queries are read-only and each finalisation runs in its own
transaction, so a slow provider teardown cannot hold the sweep open and one bad
session cannot block the rest.

## 10. DID / routing correctness

**Profile-pinned DID ownership.** `buildVoiceRoute` now filters a pinned DID by
`(id, tenantId, ACTIVE, ASSIGNED)` — the same tenant-bounded predicate the rest of
the platform uses — before the provider compatibility check. Pre-VB-6E the filter
was "row exists, not deleted, provider string matches", so a routing profile could
pin another tenant's assigned DID, or a pool DID with `tenant_id IS NULL`, and
have it **dialed as the caller ID** — becoming both the CLI the subscriber sees
and the VB-6C bucket key. The refusal is a route rejection, surfaced as a
pre-dispatch failure: no dial, no budget spent, and **no silent fallback** to the
campaign DID. A new `Optional`-returning repository finder was added for this
(the existing one returns `boolean`).

**`route_type` filtering.** `VoiceRouteProfile.routesOfType(RouteType)` filters
the union of the three per-type collections. Pre-VB-6E all three were unfiltered
`@OneToMany` associations, so `getPrimaryRoutes()`, `getOverflowRoutes()` and
`getFailoverRoutes()` returned the **same rows** — the primary pass iterated
overflow and failover entries and labelled its winner `PRIMARY`.

A fourth mapped collection was deliberately **not** added: giving the same
`VoiceRouteProfileEntry` four cascading associations with `orphanRemoval` is a
genuine flush hazard. The union is taken from the three existing mappings and
filtered once, at the point of use, which is the only version that is provably
correct and changes no persisted state.

---

## 11. Database

**V52** — `campaign_max_call_duration.sql`, forward-only, V1–V51 untouched:

| Change | Constraint |
|---|---|
| `campaigns.max_call_duration_seconds SMALLINT` | `CHECK (IS NULL OR BETWEEN 1 AND 3600)` |
| `campaign_execution_configurations.max_call_duration_seconds SMALLINT` | `CHECK (IS NULL OR BETWEEN 1 AND 3600)` |
| `call_sessions.deadline_at TIMESTAMPTZ` | `CHECK (IS NULL OR answered_at IS NULL OR deadline_at >= answered_at)` |
| `idx_call_sessions_deadline ON (deadline_at) WHERE deleted_at IS NULL AND deadline_at IS NOT NULL` | partial: the sweeper's access path, live rows only |

The bound is duplicated from the code constant exactly as V48 and V51 duplicated
theirs: the database refuses to store a value the application could not honour,
while `MaxCallDurationPolicy` remains the single authority for the effective
value. No backfill — existing campaigns keep `NULL`, meaning the 300 s default, so
no existing campaign changes behaviour, and existing sessions get a `NULL`
deadline and are never swept. **No new table, no campaign versioning, no history.**

---

## 12. API / OpenAPI

One field added to three **existing** DTOs — no new endpoint, no duplicate API:

| Schema | Documented |
|---|---|
| `CreateCampaignRequest` | `integer`, min 1, max 3600, example 180, not required |
| `UpdateCampaignRequest` | same; null clears the override |
| `CampaignResponse` | same; null means the 300 s default is in effect |

The description explicitly rules out the three dangerous misreadings (ring
timeout, provider connection timeout, playback length) and states the default.

Backward-compatible constructors were added at each arity step (15/16/17 for
`CreateCampaignRequest`, 13/14/15 for `UpdateCampaignRequest`, 22/23/24 for
`CampaignResponse`) so **every existing call site keeps its exact previous
meaning** — the same pattern VB-6D.2 and VB-6D.3 used.

**Generated OpenAPI verification** — `CampaignOpenApiContractTest` grew 11 → 14.
`OAS-F1` (field present with correct bounds on all three schemas), `OAS-F2`
(description states 300 and disambiguates all three timeouts; not required),
`OAS-F3` (all three timing ceilings coexist, 3 / 10 / 3600). `OAS-C1..E3` are
untouched and green.

---

## 13. PLAYFILE completion semantics (OD-A) — deliberately unchanged

Per the locked product decision:

- **established + PLAYFILE started + callee hangs up → `COMPLETED`, no retry.**
  This is what `EslEventService.handleChannelHangup` already does, and
  `PlayfileLifecycleEslTest.p13_remoteHangupDuringPlayback` already pins it. Both
  are retained unmodified.
- **never established → existing failure classification and retry policy**, as
  before.
- **genuine post-dispatch media failure → `PLAYBACK_FAILED`**, retryable under the
  campaign's rules, unchanged.
- **configuration/readiness error → `PLAYBACK_CONFIG_INVALID`**, permanent and
  never retried, unchanged. It is *not* collapsed into `HANGUP_UNKNOWN`.

The success decision still reads only the hangup cause and whether a failure was
already recorded on the session — it does not inspect the session's pre-hangup
status. That is intentional: it is a product decision, and changing it would
require product sign-off, not an engineering fix.

---

## 14. OD-B — PLAYFILE + TTS is refused at configuration time

Pre-VB-6E a PLAYFILE campaign could carry an APPROVED TTS template, pass
create, pass readiness, be scheduled, activated and executed, and then fail
**every** call with `PLAYBACK_CONFIG_INVALID` — a configuration an operator could
build, approve and watch fail 100 % of the time.

`CampaignService.validateContent` now rejects `PLAYFILE` + `ContentMode.TTS`
during write validation, with the project's normal `VALIDATION_ERROR` contract.
It is refused because the *runtime* does not exist, not because of the template —
so it holds even for a perfectly valid APPROVED GLOBAL template, which is exactly
the pre-VB-6E trap.

**TTS synthesis is not implemented and is not faked.** TTS remains a governed
resource with unchanged rules; those rules keep their coverage
(`CampaignResourceValidationServiceTest`'s four TTS cases, and
`TtsGovernancePostgresIntegrationTest`'s write/activation gate, which now use
`CONNECT_BY_AGENT`, a type that legitimately accepts TTS).

---

## 15. Test evidence

### 15.1 Focused suites added

| Suite | Tests | Proves |
|---|---|---|
| `EslProtocolTest` *(new)* | 14 | The protocol over a **real socket**: auth banner, `Reply-Text` verdicts, plain-event delivery, **fragmented** frames reassembled, **coalesced** frames parsed independently, unsubscribed events ignored, bgapi job vs pinned channel UUID, `-ERR` and verdict-less replies as failures. Sequences A–E from the brief. |
| `MediaUriResolverTest` *(new)* | 27 | Valid mapping, determinism, independence from the write location, configured root, tenant isolation, asset identity, and 10+ traversal/malformed refusals. |
| `MaxCallDurationPolicyTest` *(new)* | 29 | Default 300, range 1..3600, clamping, domain guard, and that the two new codes are **dispatched, not pre-dispatch**. |
| `MaxCallDurationConfigurationTest` *(new)* | 9 | Persist → freeze → resolve; an edit after execution creation does not change the frozen value. |
| `PreDispatchFailureMapperTest` *(new)* | 31 | Every routing reason maps to a pre-dispatch code; unknown never becomes `HANGUP_UNKNOWN`; **no retry budget consumed**; dispatched failures still retryable. |
| `StaleCallReconcilerTest` *(new)* | 14 | Max-duration enforcement and idempotency, stranded recovery, no resurrection of completed calls, bounded threshold, budget integrity. |
| `CampaignExecutionOrchestratorSchedulerTest` *(new)* | 14 | Scheduled start with an empty `SecurityContext`; no authentication leakage; interactive path still requires a user; every step isolated; one scheduler; tick not transactional; pause defers rather than destroys. |
| `OutboundDialServicePausedTest` *(new)* | 5 | Pause blocks dispatch, consumes no budget, preserves the attempt number, and resuming dispatches again. |
| `VoiceRoutingPinnedDidOwnershipTest` *(new)* | 5 | A pinned DID must pass ownership/ACTIVE/ASSIGNED; no silent fallback; `route_type` filtering is disjoint and ordered. |
| `CampaignOpenApiContractTest` *(extended)* | +3 | `OAS-F1..F3`; 11 to 14, every prior assertion untouched. |


### 15.2 Full suite

| | Baseline (`0dfe308`) | After VB-6E |
|---|---|---|
| Tests run | 1327 | **1478** |
| Failures | 0 | **0** |
| Errors | 0 | **0** |
| Skipped | 1 | **1** |
| `ArchitectureTest` | 1 / 0 / 0 / 0 | **1 / 0 / 0 / 0** |
| Modulith cycles | 0 | **0** |
| Flyway head | V51 | **V52** |

The delta reconciles exactly: `1327 + 148` (new suites) `+ 3` (extended OpenAPI suite) `= 1478`.

The single skipped test is the pre-existing `ObdApplicationTests.contextLoads`, which
self-documents as needing a Testcontainers harness. It was skipped at baseline and
is skipped now; VB-6E did not add, disable or annotate it.

### 15.3 Existing suites re-verified

All green, with their post-change counts:

| Suite | Tests |
|---|---|
| `HangupCauseMapperTest` (VB-6D.1) | 58 |
| `RetryPolicyModelTest` (VB-6D.2) | 67 |
| `RetryPolicyValidatorTest` (VB-6D.2) | 34 |
| `DailyAttemptSafetyServiceTest` (VB-6D.3) | 26 |
| `DailyAttemptConcurrencyPostgresIntegrationTest` (VB-6D.3, real PG) | 11 |
| `VoiceBlastDailyDialLimitPostgresIntegrationTest` (VB-6C, real PG) | 12 |
| `DailyDialLimitServiceTest` / `CampaignDailyDialLimitServiceTest` | 10 / 8 |
| `DailyDialLimitSnapshotPostgresIntegrationTest` (real PG) | 5 |
| `DailyDialLimitObservabilityTest` | 8 |
| `CampaignDailyDialLimitValidationTest` | 10 |
| `CampaignDailyAttemptApiSliceTest` | 12 |
| `RetryPolicySnapshotPostgresIntegrationTest` (real PG) | 7 |
| `CampaignRetryPolicyApiSliceTest` | 20 |
| `CallerHangupResolutionTest` (OD-6) | 5 |
| `EslEventServiceFailureCodeTest` | 22 |
| `PlayfileLifecycleEslTest` | 29 |
| `PlayfileRetrySemanticsTest` | 7 |
| `PlayfileExecutionServiceTest` | 12 |
| `PlayfileLifecycleIntegrationTest` (real PG) | 3 |
| `CampaignResourceValidationPostgresIntegrationTest` (real PG) | 20 |
| `TtsGovernancePostgresIntegrationTest` (real PG) | 22 |
| `VoiceRoutingServiceTest` / `VoiceRoutingDIDTest` | 11 / 6 |
| `VoicePolicyHierarchyTest` / `VoiceExplainabilityTest` | 8 / 2 |
| `AgentLegDialerContractTest` | 7 |
| `CampaignOpenApiContractTest` | 14 |

### 15.4 Honest notes on what changed in existing tests

No test was deleted, disabled, `@Disabled`, or excluded, and no Surefire
exclusion was added. Four existing tests were **updated because the production
contract genuinely changed**, and each is recorded here rather than glossed over:

- **`AgentLegDialerContractTest`** fed the client the **fabricated** frame
  `"+OK accepted\n\n"`. That is not anything FreeSWITCH sends — a real reply is a
  header block terminated by a blank line with the verdict in the `Reply-Text`
  header. That fabricated frame is precisely why the old verdict parsing was
  never exercised, and why it was wrong. It now injects real `command/reply`
  frames. The test's *assertions* (command text on the wire, `-ERR` raising
  `EslException`) are unchanged.
- **`PlayfileExecutionServiceTest`** used the non-canonical storage reference
  `tenants/tenant-a/promo.wav` and asserted that raw reference reached
  `playAudio`. It now uses the canonical `audio/{tenant}/{asset}/{file}` shape
  and asserts the **translated media path**, with the real resolver wired in. One
  of its cases (`assetWithoutStorageReferenceRejected`) is now enforced by the
  resolver rather than by a blank-string check, and still asserts
  `never().playAudio(...)`.
- **`PlayfileLifecycleIntegrationTest`** likewise moved from a non-canonical
  reference to one built from the seeded asset's own ids, and asserts the media
  path starts with the configured FreeSWITCH root.
- **Four routing suites** now also stub the new tenant-scoped DID ownership
  lookup, which is the contract that was added. Their existing stubs and
  assertions are untouched.

Two `TtsGovernancePostgresIntegrationTest` cases and one
`CampaignResourceValidationPostgresIntegrationTest` case moved from `PLAYFILE` to
`CONNECT_BY_AGENT`, because they test the **TTS resource gate** and `PLAYFILE`
can no longer reach it. Their assertions are unchanged. The TTS resource rules
themselves keep their original coverage in
`CampaignResourceValidationServiceTest` (four TTS cases).

### 15.5 OpenAPI verification against the generated document

`CampaignOpenApiContractTest` grew 11 → 14; `OAS-C1..E3` are untouched and green.
Beyond the assertions, the document was generated and **inspected directly**
(`/v3/api-docs`, 165,685 bytes, 83 paths, 129 schemas):

```
maxCallDurationSeconds (CreateCampaignRequest)
  type    : integer / int32
  minimum : 1
  maximum : 3600
  example : 180
  required: false
```

Present with identical bounds on `UpdateCampaignRequest` and `CampaignResponse`.
The three call-timing ceilings coexist and are independently bounded:

```
dailyDialLimit          min=1  max=3     example=2
maxDailyAttempts        min=1  max=10    example=4
maxCallDurationSeconds  min=1  max=3600  example=180
```

**No endpoint was added or changed** — a scan of all 83 paths for
`duration|timeout|max` returns nothing, and the 14 campaign paths are the
pre-existing set. The only API change in VB-6E is the single documented field.

---

## 16. Known limitations

1. **No live FreeSWITCH was executed.** There is no FreeSWITCH instance in this
   environment and none in `infra/docker-compose.yml`. The protocol is proven
   against a real socket speaking real frames; a live SIP/media run remains
   unverified, exactly as the VB-4D/4E/4F reports stated.
2. **`telephony.freeswitch.enabled` is `false` in the shipped `dev` profile**, so
   a default local run still uses the no-op adapters. The no-ops fail loudly
   rather than faking success, which is what keeps the lifecycle honest.
3. **The no-op implementations open a new connection per operation.** The real
   adapters do too — six `new EslClient(...)` sites. This is the pre-existing
   VB-1..VB-4 design, not introduced here, and it is a latency/resilience
   concern rather than a correctness one.
4. **`@Version` / optimistic locking is still absent** platform-wide (audit
   OD-E). The workarounds are the conditional-UPDATE pattern (unchanged) and the
   single-threaded event loop. A multi-node deployment would need a decision that
   was not in scope.
5. **Eligibility still runs twice per dial** (`OutboundDialService` and
   `VoiceRoutingService`), and `CallEligibilityService` still has no dedicated
   test class. Both are recorded in the audit as scope calls (OD-D) and were
   deliberately not bundled into a protocol-safety phase.
6. **Routing profiles and SIP gateways still have no REST write path** and must
   be seeded out of band. The DID-ownership fix closes the risk regardless of
   how the profile is created.
7. **The reconciler does not release a VB-6C hold** for a stranded attempt; the
   existing 5-minute capacity reconciler frees the reservation row independently.
8. **A lost `PLAYBACK_STOP` whose `PLAYBACK_START` was also lost** still leaves the
   session until the stray-call threshold. The sweep bounds that rather than
   resolving the event-loss cause.
9. **OD-A was deliberately left alone**, per the locked product decision. The
   success decision reads only the hangup cause and whether a failure was already
   recorded, not the session's pre-hangup status. Changing it would need product
   sign-off, not an engineering fix.
10. **OD-2 remains open** (carried from VB-6D): `SWITCHED_OFF` and
    `NOT_REACHABLE` are configurable but have no target-carrier cause list, so no
    carrier-specific mapping was invented.

---

## 17. Environment configuration

```yaml
telephony:
  freeswitch:
    enabled: true        # false in the shipped dev profile -> no-op adapters
audio:
  storage:
    enabled: true
    freeswitch-media-root: /usr/share/freeswitch/sounds   # NEW in VB-6E
    base-directory: data/audio
```

`freeswitch-media-root` is the path **as FreeSWITCH sees it**. It equals
`base-directory` on a single host and differs when the application and FreeSWITCH
run in different containers. It defaults to the standard FreeSWITCH sounds
directory, so an unpackaged single-host deployment needs no extra configuration.

---

## 18. Scope not touched

Reusable IVR / IVR tree (VB-6F), TTS synthesis, webhooks, report privacy,
`integrationConfig`, RECURRING scheduling, `holidayCalendarId`, routing-profile
REST API, MP3 duration extraction, file garbage collection, object storage, new
infrastructure, Kafka, Kubernetes, microservices, a new scheduler, and
dead-code cleanup unrelated to VB-6E. `docs/campaign-readiness.md` was read but
**not modified**.

---

*Canonical VB-6E record. The audit this implements is
`docs/VB-6E-PLAYFILE-COMMON-CALLING-AUDIT.md`.*
