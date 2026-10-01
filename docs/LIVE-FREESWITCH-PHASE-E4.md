# LIVE FREESWITCH — PHASE E.4: FULL REPOSITORY BASELINE + FIRST SPRING-ORIGINATED CALL

**Status: BLOCKED — the full-repository baseline was achieved; the call was not
attempted, because the campaign contract is still moving**

This phase delivered the one thing E.3 could not: **a genuine, unnarrowed
full-repository test baseline — the first in the project's history.** It did not
place a call, and the reason is the brief's own explicit stop condition, not
budget or difficulty.

---

## 1. Executive Summary

| | |
|---|---|
| **Full repository baseline** | **CONFIRMED** — 1005 tests, 1 failure, 0 errors, 2 skipped |
| Concurrent work touched | **NO** |
| E.3 gateway gate preserved | **yes** — unchanged, both E.4 rules honoured |
| Campaign contract stable? | **NO — actively moving** |
| Application-originated call | **NOT ATTEMPTED** — stop condition met |
| J1 runtime correlation | **AMBIGUOUS** — unchanged |
| Architecture changed | **NO** |

The single full-suite failure is the long-known concurrent
`checkExecutionTimezone` one, and it is the *only* failure in 1005 tests.

## 2. Ownership Boundary

**Attribution at the start of the phase**

| Owner | Paths |
|---|---|
| Concurrent campaign/voice workstream | 11 modified + 10 untracked files under `campaign/`, plus `docs/campaign-readiness.md` and three `docs/VB-7C*` documents |
| Phase D/E (telephony) | 4 modified + 2 untracked under `telephony/` |
| Phase E (FreeSWITCH + docs) | untracked `infra/freeswitch/`, `infra/freeswitch-endpoint/`, `docs/freeswitch/`, `tools/` |

**Attribution at the end of the phase: identical.** Nothing was added to,
removed from, or modified in any concurrent path. `WebhookConfig.java` remains
untracked and unedited. `ZzOpenApiShapeDumpTest.java` remains absent — that
workstream completed and removed it, and it was not recreated.

No source file was modified in Phase E.4. Its only change is data already seeded
in E.3, verified unchanged.

## 3. Integrity

```text
git status --short                 50 paths
git diff --stat                    22 files changed, +1649/-91
git diff --name-status             22 entries
git diff --cached --name-status    EMPTY
git diff --diff-filter=U          none
git stash list                     empty
parked / vb7c1 / orig / rej / bak  none
infra/docker-compose.yml           CLEAN
```

No conflicts, no stash, no shadow files, no accidental FreeSWITCH changes.

### A self-inflicted failure worth recording

The first baseline run reported `BUILD FAILURE` with **zero** test classes:

```text
Failed to execute goal maven-clean-plugin:3.5.0:clean (default-clean)
  Failed to delete ...\target\e4-full-baseline.txt:
  The process cannot access the file because it is being used by another process
```

The cause was mine: I redirected Maven's own output into `target/` and then ran
`clean`, which tried to delete the file being written. This is the same class of
collision as the concurrent `mvn clean` that damaged E.1, and the lesson is
identical — **never write a build log inside the directory the build cleans.**
The run was repeated with the log outside `target/` and produced the real
result.

## 4. Full Repository Baseline — the deliverable

Run exactly as the project intends, with **no** test filtering of any kind:

```text
mvnw -o clean test
```

No `-Dtest`, no `-Dmaven.compiler.testIncludes`, no
`-Dmaven.compiler.testExcludes`. The narrowing workaround that E.1 and E.2 were
forced to use is gone, because the concurrent `ZzOpenApiShapeDumpTest.java` that
broke `test-compile` is no longer present.

```text
test classes : 160
tests        : 1005
failures     : 1
errors       : 0
skipped      : 2
build        : FAILURE (because of the single failure)
```

### The one failure — concurrent, not ours

```text
com.shivang.obd.audio.AudioUploadPostgresIntegrationTest
  .readyWithApprovedStoredAsset:279
  Expecting value to be true but was false        <- response.ready()
```

This is the identical failure recorded in Phase D, Phase E and Phase E.2, and its
cause is unchanged: the concurrent workstream's uncommitted
`checkExecutionTimezone` readiness rule rejects a campaign fixture written before
the rule existed. Not fixed, not touched, not absorbed.

### Telephony results within the full run

Every telephony class is green, which re-verifies the Phase D fixes under a full
build rather than a narrowed one:

```text
AgentEslRoutingTest                 9 tests  0 fail  0 err
AgentLegDialerContractTest          7        0        0
DtmfEslEventServiceTest             4        0        0
EslEventServiceFailureCodeTest      22       0        0
EslProtocolTest                     21       0        0
FreeSwitchPropertiesValidationTest   8        0        0
FreeSwitchVoiceMediaControllerTest  9        0        0
InboundEslRoutingTest               8        0        0
LiveFreeSwitchRuntimeContractTest   4        0        0   <- LIVE, against the switch
OutboundEslRoutingTest              8        0        0
OutboundEventOrderingTest           7        0        0
```

`LiveFreeSwitchRuntimeContractTest` running 4/4 green inside a full build is
worth noting: the Phase D live suite passes without any narrowing, which it never
could before E.1.

**1005 tests, one failure, and that failure is not in telephony.** This is the
baseline every later phase should be compared against.

## 5. E.3 Gate Facts — Preserved

Verified unchanged, and both E.4 rules explicitly re-checked rather than assumed:

```text
gateways_total=1  e3=1  allocations=1  tenants_e3=1
executions=0      attempts=0

gateway : e3-local-endpoint-1002 | provider=LOCAL | fs_gw=local-endpoint-1002
        | profile=internal | status=ACTIVE | enabled=true | owner=PLATFORM
```

| E.4 rule | Required | Actual | |
|---|---|---|---|
| 5.1 owner type | not `TENANT` | `PLATFORM`, `owner_tenant_id IS NULL` | honoured |
| 5.2 FreeSWITCH profile | `internal`, not the entity default | `internal` | honoured |

No gateway was recreated, changed, or removed. The route remains:

```text
sofia/gateway/local-endpoint-1002/<contact number>
```

FreeSWITCH and the endpoint are both `running/healthy`, 0 restarts.

## 6. Why the Call Was Not Attempted

The brief's stop condition is explicit:

> "If the current campaign work is still moving while you work, stop rather than
> creating a test dataset against an unstable contract."

**It is still moving.** Measured, not assumed:

```text
campaign files touched in the last 90 minutes : 8
most recent campaign change                   : 21:18:30  VB-7C.2-...md
ANY source file touched in the last 25 minutes: 1
  21:38:52  TtsGovernancePostgresIntegrationTest.java
```

Work is landing *right now* — a source file changed 20 minutes before this
check, and a new VB-7C.2 design document landed 40 minutes before it.

### The honest nuance

It would have been convenient to argue the specific contract I need is stable,
and that argument is partly true:

```text
CampaignReadinessService.java  mtime 19:39:47   (unchanged for 2h19m)
checkExecutionTimezone         still: schedule == null || timezone blank
                                 -> SCHEDULE_TIMEZONE_REQUIRED
```

So the one readiness rule that gates a dial has in fact been stable for over two
hours, and the files on the dial path are untouched.

But that is a narrower claim than the stop condition asks for, and it is not
comfortable. The campaign workstream has already demonstrated, twice in this
project, that a readiness rule can appear mid-phase and invalidate a seed:
`checkExecutionTimezone` itself was added while E.2 was running and broke that
phase's fixture. A new `VB-7C.2` integration-configuration document landed during
E.3 and again during E.4, and `campaigns` carries a `type_config` /
`integration_config` pair that those documents govern — the exact columns a
seeded campaign must satisfy.

Creating a campaign dataset now would produce a result whose validity depends on
when it ran, against a contract with a demonstrated habit of moving. If the call
then failed at the readiness gate, the failure would be **indistinguishable from
a telephony defect** — the precise misreading that has already cost this project
two phases.

Stopping at a proven baseline, with the instability measured rather than
asserted, is the correct outcome and is explicitly sanctioned by the brief.

## 7. What Was Not Done, and the Exact Reason for Each

| Item | Status | Reason |
|---|---|---|
| Remaining seed records | not created | campaign contract moving (§6) |
| Execution eligibility validated | not attempted | depends on the seed |
| Scheduler path run | not attempted | no eligible execution exists |
| CallAttempt created | no | `call_attempts = 0` |
| `FreeSwitchOutboundDialer` reached | no | no dial occurred |
| Capacity reservation/release | not tested | requires a dial |
| J1 runtime classification | **AMBIGUOUS** | unchanged; no application-created attempt |
| Peer witness, RTP, persistence | not tested | no application call |
| Full suite after the call | not run | no call; the pre-call baseline in §4 stands |

## 8. J1 — Still AMBIGUOUS

Unchanged, and deliberately not upgraded.

The structural finding is intact and now sits on a routable gateway:

```text
CallAttempt.id = origination_uuid = FreeSWITCH channel UUID
               = attempt/session/leg .providerCallId
               = the key EslEventService correlates on
```

No `CallAttempt` exists, so the Java event stream has never had one to resolve.
Phase E's rule still applies: *a local switch event proves what the switch
believes; only the application observing a correlated event proves J1.* Neither
has happened. `AMBIGUOUS` is the honest classification.

## 9. Live Evidence Matrix

| Scenario | Result | Evidence |
|---|---|---|
| Full repository baseline | **CONFIRMED** | 1005 tests, 1 concurrent failure |
| Telephony green in a full build | **CONFIRMED** | 11 classes, 0 failures |
| Live ESL suite green in a full build | **CONFIRMED** | 4/4 against the switch |
| E.3 gateway preserved | **CONFIRMED** | unchanged, both rules honoured |
| Campaign contract stable | **NO** | 8 files in 90 min, 1 in 25 min |
| Readiness validated | **NOT YET TESTED** | stopped per §6 |
| Scheduler ran the execution | **NOT YET TESTED** | no execution |
| CallAttempt created | **NOT YET TESTED** | `attempts = 0` |
| Dialer reached | **NOT YET TESTED** | no dial |
| J1 runtime | **AMBIGUOUS** | unchanged |
| Peer witness | **NOT YET TESTED** | no call |
| RTP for an application call | **NOT YET TESTED** | no call |
| Capacity reserve/release | **NOT YET TESTED** | no dial |
| Persistence | **NOT YET TESTED** | no attempt |
| Carrier / PSTN | **NOT TESTED** | — |

Two-way RTP for an application-originated call is **not** claimed. E.1 measured
it for harness-placed calls; that is not this phase's result.

## 10. Documentation

This document only. No historical Phase C/D/E/E.1/E.2/E.3 finding was
rewritten, and no prediction was converted into a fact.

The 1005-test baseline is recorded here as the reference point for later phases.

## 11. Security

| Check | Result |
|---|---|
| live credential values in files changed | **0** |
| ESL/SIP passwords anywhere | **none** |
| `.env`, `*.fsxml`, runtime logs staged | **none** |
| generated `.pyc` staged | **none** |
| new host ports | **none** |
| `infra/docker-compose.yml` | **untouched**, diff clean |
| concurrent files modified | **NONE** |
| source files modified by E.4 | **NONE** |

## 12. Files Changed

**None.** No source file, no configuration, no schema. The only E.3 seed rows
were verified unchanged, not modified.

**Documentation**

- `docs/LIVE-FREESWITCH-PHASE-E4.md` *(new)*

## 13. Architecture Impact

**NO.**

## 14. Next Recommended Phase

Sequenced so the next phase executes rather than investigates.

1. **Wait for the campaign workstream to go quiet**, then confirm stability with
   the same measurement used in §6: no `src` file modified for a defined window
   (30 minutes is a reasonable bar), and `CampaignReadinessService` unchanged.
   Re-read `checkExecutionTimezone` and the campaign configuration columns at
   that moment, not from this document.
2. **Seed the remaining records** against the contract as it stands then:
   contact (`1002`, the in-network extension), an `APPROVED` audio asset whose
   `storage_reference` resolves to the validated probe WAV, a DID whose
   `e164_number` matches `^\+[1-9][0-9]{6,14}$` and whose `provider` is `LOCAL`
   (required — `SipGatewayResolver` compares DID provider to gateway provider
   case-insensitively), a contact group with that contact, a campaign satisfying
   every current readiness check, a `campaign_execution_configurations` row, and
   a `REQUESTED` execution.
3. **Do not invoke the dialer directly.** Create the `REQUESTED` execution and
   let the 30-second `scheduledTick` run `start-requested-executions` and
   `dial-due-attempts`. The attempt must be `QUEUED` with `scheduled_at` in the
   past, which is exactly what `processDueAttempts` selects.
4. **Enable DEBUG for `EslClient` and `EslEventService` for that one call.**
   Required: with no matching attempt, INFO level logs nothing either way, which
   is precisely why E.1 and E.2 were unreadable.
5. **Then** capture the correlation chain, peer witness, RTP for that call,
   capacity release, and persistence — and classify J1 as CONFIRMED, FAILED or
   NOT RECEIVED with the event evidence to support it.
6. **Treat the 1005-test baseline in §4 as the comparison point.** If the
   campaign workstream lands changes, the baseline moves with it and that must
   be stated rather than presented as a regression in telephony.
