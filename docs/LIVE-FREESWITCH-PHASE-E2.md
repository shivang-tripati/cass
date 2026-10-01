# LIVE FREESWITCH — PHASE E.2: SEEDED APPLICATION CALL & EVENT CORRELATION

**Status: BLOCKED — the core correlation gate was not achieved**

The question E.2 exists to answer remains **unanswered**:

> Does the running Spring application's real ESL event path receive and
> correlate the events for an actual application-created `CallAttempt`?

E.1's result was explicitly ambiguous. This phase did **not** convert it into a
pass, and it did not convert it into a failure. It established the build, test
and wiring facts that make the answer reachable, and it identified the precise
remaining seed gap. What it did not do is place a call through the application —
so J1 in the running application is still **AMBIGUOUS**, and is reported that
way.

I would rather hand over an exact, actionable map than a seeded half-dataset
that produces a misleading negative.

---

## 1. Executive Summary

| | |
|---|---|
| Working-tree ownership verified | **yes** |
| Concurrent work touched | **NO** |
| Application compiles | **yes** — exit 0, 633 classes |
| Focused automated baseline | **86 tests, 86 passed, 0 failed** |
| Real application entry point identified | **yes** |
| Correlation key alignment verified in code | **yes** — this is the key finding |
| Minimum valid seed created | **NO** |
| Application-originated call | **NO** |
| J1 correlation in the running application | **AMBIGUOUS** — unchanged |
| Peer-witnessed application call | **NO** |
| Two-way RTP for an application call | **NOT YET TESTED** |
| Application persistence verified | **NO** |
| Architecture changed | **NO** |

## 2. Starting State

Phase E.1's results were treated as established and not re-investigated: the
gateway, the endpoint, the `sleep` fix, ESL auth and subscription,
`api uuid_broadcast <uuid> <file> aleg` as the confirmed valid command form, and
two-way RTP. None of it was re-litigated, and no Java command shape was changed.

## 3. Integrity Gate

```text
git status --short                 46 paths
git diff --stat                    20 files changed, 1555 insertions
git diff --cached --stat           EMPTY
git diff --diff-filter=U          none
git stash list                     empty
parked / vb7c1 / orig / rej / bak  none
infra/docker-compose.yml           CLEAN
```

The concurrent workstream had grown to **25 campaign/voice paths**, including 12
untracked files. All left untouched.

## 4. Build State

```text
concurrent Maven processes: 0
class files before: 633
mvnw -q -o compile -> exit 0
class files after:  633
target/classes/.../telephony/*.class: 35
```

No contention this phase. The `mvn clean` interference seen in E.1 did not recur.

## 5. Automated Baseline

### A concurrent-workstream blocker, reported not absorbed

The first run failed with **0 tests executed**:

```text
[ERROR] .../campaign/ZzOpenApiShapeDumpTest.java:[17,2] cannot find symbol
[ERROR] .../campaign/ZzOpenApiShapeDumpTest.java:[22,5] cannot find symbol
[INFO]  BUILD FAILURE
```

`ZzOpenApiShapeDumpTest.java` is an **untracked** concurrent file that imports
classes absent from this classpath:

```text
org.springdoc.core.configuration.SpringDocConfiguration          MISSING
org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration  MISSING
```

Because `test-compile` compiles *all* test sources, one uncompilable file blocks
the entire test suite. **This is the same class of blocker E.1 hit in
`WebhookConfig.java`, now in the concurrent workstream's test tree.**

**Not fixed.** Two build-flag workarounds were attempted; only the second worked,
and neither touches their file:

| Attempt | Result |
|---|---|
| `-Dmaven.compiler.testExcludes=**/ZzOpenApiShapeDumpTest.java` | did not take |
| `-Dmaven.compiler.testIncludes=**/shivang/obd/telephony/**/*.java` | **worked** |

### Result

```text
command: mvnw -o test -Dmaven.compiler.testIncludes=**/shivang/obd/telephony/**/*.java
                -Dtest='EslProtocolTest,EslEventRuntimeContractTest,EslEventServiceTest,
                       PlayfileLifecycleEslTest,EslEventServiceFailureCodeTest,
                       DtmfEslEventServiceTest,AgentEslRoutingTest,AgentLegDialerContractTest,
                       InboundEslRoutingTest,OutboundEslRoutingTest,OutboundEventOrderingTest'

classes: 11
tests run: 86
passed: 86
failures: 0
errors: 0
skipped: 0
BUILD SUCCESS
```

Phase D's contract fixes are green. This includes the strict `FakeEslServer`
command guard, so J4 has not regressed.

**Caveat, stated rather than hidden:** this baseline covers the **telephony
package only**, because a build flag was needed to route around a concurrent
file. The full repository suite was **not** run, so no claim is made about
campaign tests.

## 6. The Real Application Entry Point

Traced rather than invented. The production path is:

```text
POST /api/v1/campaigns/{id}/executions          CampaignController
      ↓
CampaignExecutionOrchestrator  (scheduled step)
      ↓  new CallAttempt(); attemptRepository.save(attempt)      L290/L300
OutboundDialService.dial(attemptId)                               L55
      ↓  routing selection -> GatewayRoute                       L269
      ↓  voiceCapacity.reserve(gatewayId, tenantId)               L299
      ↓  dialer.dial(route, ...)                                  L316
FreeSwitchOutboundDialer.dial(request)
      ↓  eslClient.originate(callerId, destination, gateway, profile, channelUuid)
      ↓  return OutboundDialResponse.accepted(channelUuidReturned) L90
attempt.setProviderCallId(response.providerCallId())               L366
CallSession.setProviderCallId(response.providerCallId())           L445
CallLeg.setProviderCallId(response.providerCallId())               L462
```

No new production API was created, and no test-only path was invented.

## 7. Correlation Key — the decisive structural finding

`FreeSwitchOutboundDialer` pins the channel UUID to the **CallAttempt's own id**:

```java
// VB-6E (audit finding P0.1-D): the channel UUID is chosen by us
// via FreeSWITCH's origination_uuid channel variable and is the
// CallAttempt's own id.
String channelUuid = request.callAttemptId() == null ? null
        : request.callAttemptId().toString();
...
return OutboundDialResponse.accepted(channelUuidReturned);
```

So the application's correlation chain is closed by construction:

```text
CallAttempt.id
   = origination_uuid          (what the application SUPPLIES)
   = FreeSWITCH channel UUID   (what every event reports as Channel-Call-UUID/Unique-ID)
   = CallAttempt.providerCallId, CallSession.providerCallId, CallLeg.providerCallId
   = the key EslEventService correlates on
```

**This is the correct and sufficient design for J1**, and it was verified in code
rather than assumed. Phase D's fix resolves that exact value through
`Channel-Call-UUID` → `Unique-ID`.

It does **not**, however, prove the running application correlates a real call.
The structural alignment is necessary; the runtime behaviour is unverified.

## 8. Why the Seed Was Not Completed

The minimal dataset is larger than it first appears, and the reason is specific:

```text
tenants=36  campaigns=9  contacts=3  audio=1
gateways=0  attempts=0    executions=0
```

**`sip_gateways` is empty.** `OutboundDialService` requires a routing decision
producing a `GatewayRoute` and then calls
`voiceCapacity.reserve(selectedRoute.gatewayId(), attempt.getTenantId())` before
dialling. With no gateway row there is nothing to select, nothing to reserve
against, and the dial path cannot be reached by any legitimate seeding.

Completing the seed correctly requires, at minimum:

| Record | Why |
|---|---|
| `sip_gateways` row naming `local-endpoint-1002` | the routing engine has nothing to select; **currently zero rows** |
| `contacts` | `call_attempts.contact_id` is `NOT NULL` |
| `audio_assets` (approved + `storage_reference`) | PLAYFILE needs a resolvable media URI |
| `campaigns` with schedule + timezone | the concurrent workstream's `checkExecutionTimezone` gates readiness |
| `contact_groups` + `contact_group_members` | `campaigns.contact_group_id` |
| `campaign_executions` | `call_attempts.execution_id` is `NOT NULL` |
| `dids` | `call_attempts.did_id` is `NOT NULL` |
| `call_attempts` in a dialable state | the thing under test |

Two further hazards made stopping the right call rather than a defeatist one:

1. **The campaign configuration is being rewritten concurrently.**
   `CampaignReadinessService`, `CampaignService`, `CampaignEntity`,
   `CampaignConfigurationSnapshot` and their tests are all modified, and the
   `checkExecutionTimezone` rule is new and uncommitted. Seeding a campaign to
   satisfy rules that are still moving produces a test whose result depends on
   when it ran.
2. **Bypassing invariants to force a call was explicitly out of scope.** Seeding
   a campaign that the application itself would reject, or inserting an attempt
   directly, would produce a "pass" that proves nothing about the production
   path.

A half-built dataset that fails at the routing stage would have been
indistinguishable from a genuine defect in the telephony path — the exact
misreading this whole engagement has been about.

## 9. Event Correlation — J1 remains AMBIGUOUS

No application-created `CallAttempt` exists, so no application correlation was
observed. Carrying E.1's result forward unchanged:

```text
"without a channel identity" warnings : 0
EslEventService log lines            : 0
```

Both readings remain live — J1 working, **or** the application receiving nothing.
No case in the brief's A/B/C/D classification is claimed.

One narrowing fact was established: `EslEventService` logs nothing at INFO for
a well-formed event that finds no matching `CallAttempt`, so
`EslEventService log lines = 0` will remain uninformative unless DEBUG logging
is enabled **and** a matching `CallAttempt` exists. Both are required.

## 10. A Real Defect Found: a stale contract comment

Phase D corrected the correlation header in code but left one comment asserting
the old contract, in `FreeSwitchOutboundDialer`:

```java
// event correlates by Call-UUID with no race, no polling and no
// extra table.
```

`Call-UUID` is the header Phase D measured to be **absent from every event type
the platform consumes**. The code is correct; the comment named the wrong
header, and would have reintroduced the exact misconception Phase D removed.

Corrected, with the measurement and a pointer recorded inline. This was the only
Java change in Phase E.2, and it is a comment only — no behavioural change. The
pinned-UUID design the comment describes remains correct and is unchanged.

## 11. Live Evidence Matrix

| Scenario | Result | Evidence |
|---|---|---|
| Application compiles | **CONFIRMED** | exit 0, 633 classes |
| Focused telephony tests | **CONFIRMED** | 86/86 pass |
| Entry point identified | **CONFIRMED** | traced to `OutboundDialService` L366 |
| Correlation key alignment | **CONFIRMED** | `origination_uuid == CallAttempt.id == providerCallId` |
| Seed: gateway row | **BLOCKED** | 0 `sip_gateways` rows exist |
| Seed: full dataset | **NOT YET TESTED** | not attempted; see §8 |
| Application-originated call | **NOT YET TESTED** | depends on the seed |
| J1 in the running application | **AMBIGUOUS** | unchanged from E.1 |
| CallAttempt → channel UUID live | **NOT YET TESTED** | structurally correct, unproven |
| SIP Call-ID correlation | **NOT YET TESTED** | — |
| Peer channel confirmation | **NOT YET TESTED** | — |
| PLAYBACK_START / STOP (application call) | **NOT YET TESTED** | — |
| CHANNEL_HANGUP (application call) | **NOT YET TESTED** | — |
| Two-way RTP (application call) | **NOT YET TESTED** | — |
| Application persistence | **NOT YET TESTED** | — |
| Full repository suite | **NOT RUN** | blocked by a concurrent test file |
| Carrier / PSTN | **NOT TESTED** | — |

Two-way RTP for an *application-originated* call is deliberately listed as
untested. E.1 proved it for harness-placed calls; that result was not carried
forward as if it were measured for this phase.

## 12. Automated Tests After the Phase

Not rerun beyond the baseline in §5, because no code path changed. The only Java
change is a comment. The baseline of §5 therefore still stands as the phase's
test result.

## 13. Documentation

This document, plus a troubleshooting entry for the concurrent-test-file blocker
and a correction note in the Phase D report. No historical Phase C/D/E/E.1
finding was rewritten.

## 14. Security

| Check | Result |
|---|---|
| live credential values in files changed | **0** |
| ESL/SIP passwords in source, tests or docs | **none** |
| `.env`, `*.fsxml`, runtime logs staged | **none** |
| generated `.pyc` staged | **none** |
| new host ports | **none** |
| `infra/docker-compose.yml` | **untouched**, diff clean |
| concurrent workstream files modified | **NONE** |
| secrets printed in this report | **none** — only key *names* and the words "MISSING" |

No seed data was written to the database, so no tenant or contact record was
created and nothing needs retracting.

## 15. Files Changed

- `backend/src/main/java/com/shivang/obd/telephony/FreeSwitchOutboundDialer.java`
  — comment only, correcting the correlation-header name
- `docs/LIVE-FREESWITCH-PHASE-E2.md` *(new)*
- `docs/freeswitch/13-TROUBLESHOOTING.md` — entry 30
- `docs/freeswitch/11-DEBUGGING.md` — index row
- `docs/freeswitch/README.md` — index row
- `docs/LIVE-FREESWITCH-PHASE-D.md` — note that the stale comment is now fixed

**Not changed:** `infra/docker-compose.yml`; any telephony behaviour; any
concurrent file; no database rows.

## 16. Architecture Impact

**NO.** One comment corrected. No behaviour, no schema, no configuration, no new
abstraction.

## 17. Known Limitations

| Item | Status |
|---|---|
| J1 correlation in the running application | **AMBIGUOUS** — the phase's core question, unanswered |
| Application-originated call | **NOT YET TESTED** |
| PLAYFILE through the application | **NOT YET TESTED** |
| Application persistence state | **NOT YET TESTED** |
| Full repository suite | **NOT RUN** — concurrent test file blocks `test-compile` |
| SIP Call-ID → peer correlation for an application call | **NOT YET TESTED** |

## 18. Next Phase

Ordered by what unblocks the most.

1. **Seed one `sip_gateways` row** naming `local-endpoint-1002`, profile
   `internal`, enabled, with a non-zero `max_concurrent_channels`. This is the
   single hard blocker: with zero gateway rows the routing engine cannot select
   and `voiceCapacity.reserve` has nothing to reserve against.
2. **Then** the remaining records, using the application create-API where one
   exists so business invariants are honoured rather than bypassed.
3. **Enable DEBUG for `EslEventService` and `EslClient` for exactly one call.**
   This is required — with no matching `CallAttempt`, INFO level yields nothing
   either way, which is why E.1 was ambiguous.
4. **Place the call through `OutboundDialService`**, then verify the full chain
   `CallAttempt.id → origination_uuid → channel UUID → SIP Call-ID → peer
   channel → PLAYBACK_START/STOP → CHANNEL_HANGUP`, plus bidirectional RTP and
   the persisted final state.
5. **Ask the concurrent workstream to finish `ZzOpenApiShapeDumpTest.java`.** It
   currently blocks the whole test suite for everyone, and the workaround used
   here (a build flag limiting compilation to the telephony package) means no
   campaign test result can be claimed in this phase.

## 19. Related Documents

- [`LIVE-FREESWITCH-PHASE-E1.md`](LIVE-FREESWITCH-PHASE-E1.md) — the gate this phase was meant to open
- [`LIVE-FREESWITCH-PHASE-E.md`](LIVE-FREESWITCH-PHASE-E.md) — gateway and peer-witness evidence
- [`LIVE-FREESWITCH-PHASE-D.md`](LIVE-FREESWITCH-PHASE-D.md) — the J1 contract this phase verified structurally
- [`freeswitch/05-ESL.md`](freeswitch/05-ESL.md) 12.5 — the channel identity rule
