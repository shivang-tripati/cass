# LIVE FREESWITCH — PHASE E.5: RE-READ CAMPAIGN CONTRACT + FIRST APPLICATION CALL

**Status: BLOCKED — the campaign contract is moving again, and the full-suite
run is invalid due to concurrent build contention**

Two independent blockers, both measured rather than assumed. Neither is a
telephony defect, and neither is reported as a pass.

---

## 1. Executive Summary

| | |
|---|---|
| Campaign contract stable | **NO — moving now** |
| `3a89e5c` in the committed baseline | **YES** |
| Full repository baseline | **INVALID RUN** — concurrent build contention, not a code result |
| E.3 gateway preserved | **yes** — verified unchanged |
| Live telephony topology | **healthy** |
| Application-originated call | **NOT ATTEMPTED** |
| J1 runtime correlation | **AMBIGUOUS** — unchanged |
| Source files modified by E.5 | **NONE** |
| Architecture changed | **NO** |

## 2. Ownership Boundary

**At the start of the phase**

```text
HEAD: 3a89e5cf8861d3ecb574757c1a66b3552c458745
       3a89e5c  VB-7C.2: integrations + campaign configuration (configuration only)
       33e303e  VB-7C.1: campaign configuration correctness hardening
       828bec4  VB-7B: MISSED_CALL campaign configuration + minimal runtime
```

`3a89e5c` **is** an ancestor of HEAD, so the mission's premise — that the
campaign workstream has committed — is satisfied.

| Owner | Paths |
|---|---|
| Concurrent campaign workstream | 3 modified campaign source files + `docs/campaign-readiness.md` |
| Phase D/E (telephony) | 6 modified + 2 untracked under `telephony/` |
| Phase E (FreeSWITCH, docs, harness) | untracked `infra/freeswitch*/`, `docs/freeswitch/`, `tools/`, `docs/LIVE-*.md` |

**At the end of the phase: identical.** No concurrent file modified, reset,
stashed, reverted or reformatted. `WebhookConfig.java` untouched. No source file
of any kind was modified by Phase E.5.

## 3. Integrity

```text
git status --short                 31 paths
git diff --stat                    13 files changed, +1094/-61
git diff --cached --name-status    EMPTY
git diff --diff-filter=U          none
git stash list                     empty
parked / vb7c1 / orig / rej / bak  none
infra/docker-compose.yml           CLEAN
```

## 4. Campaign Contract Stability Gate — FAILED

This is the phase's first and most important gate, and it did not pass.

```text
now: 22:11:06

campaign source files changed in the last  15 min : 4
campaign source files changed in the last  30 min : 4
campaign source files changed in the last  60 min : 6
campaign source files changed in the last 120 min : 17

most recent changes:
  22:11:02  CampaignConfigurationService.java     <- 4 SECONDS before this check
  22:10:29  CampaignRuntimeConfigResolver.java
  22:08:30  CampaignConfigurationSnapshot.java
```

Three uncommitted campaign source files are in flight:

```text
M CampaignConfigurationService.java     +36
M CampaignConfigurationSnapshot.java    +94
M CampaignRuntimeConfigResolver.java    +66
3 files changed, 193 insertions(+), 3 deletions(-)
```

### These are precisely the files a seed must satisfy

| File | Why a seeded campaign depends on it |
|---|---|
| `CampaignConfigurationSnapshot` | the entity behind `campaign_executions.configuration_snapshot_id`, a **NOT NULL** FK |
| `CampaignRuntimeConfigResolver` | resolves `type_config` / `integration_config` at dial time |
| `CampaignConfigurationService` | the configuration write path |

And the diff shows exactly what is being added:

```text
+    private JsonNode integrationConfig;
+    public Optional<com.shivang.obd.campaign.config.CampaignIntegrationConfig>
```

**A new `integrationConfig` field is being added to the snapshot entity while
this phase runs.** That is the precise field a seeded campaign execution would
have to populate, and its shape, validation and semantics are mid-flight.

### Why this is a stop, not a caution

The brief's condition is explicit:

> "If new concurrent campaign changes appear during the phase, stop and classify
> the campaign contract as moving again."

This is materially worse than the E.4 stop. In E.4 the *readiness rule* had been
stable for 2h19m and only the surrounding area was moving. Here the **execution
configuration snapshot contract itself** — the record `campaign_executions`
cannot exist without — is being rewritten.

A campaign seeded now would be validated against an entity that is gaining a
field as we speak. If the call then failed at the readiness gate or at
configuration resolution, the failure would be **indistinguishable from a
telephony defect**. That is the specific misreading this project has already
paid for twice, and the one the brief's layer-by-layer triage discipline exists
to prevent.

## 5. Full Repository Baseline — THIS RUN IS INVALID

The command was correct and unnarrowed, and the log went outside `target/`
per the E.4 lesson:

```text
mvnw -o clean test    (no -Dtest, no testIncludes, no testExcludes)
log -> %TEMP%\e5-full-baseline.txt
```

It reported:

```text
test classes : 160
tests        : 937
failures     : 2
errors       : 161      <-- E.4 had 0
skipped      : 2
```

**These numbers must not be recorded as a baseline.** The run is invalid, and the
cause is provable rather than inferred.

### Evidence the run was corrupted, not the code

```text
java.lang.NoClassDefFoundError: com/shivang/obd/telephony/FakeEslServer
  Caused by: ClassNotFoundException: com.shivang.obd.telephony.FakeEslServer
```

28 classes errored, almost all of them the pattern:

```text
java.lang.IllegalStateException: ApplicationContext failure threshold (1)
exceeded: skipping repeated attempt to load context
```

Both are consistent with an **incomplete classpath**, and the post-run state
proves it:

```text
target/test-classes/com/shivang/obd/telephony/
  FakeEslServer.class  present : False
  EslProtocolTest.class present: False
  .class files               : 0
```

Surefire executed `EslProtocolTest` — a class that is not in
`target/test-classes`. The directory is empty *now*, minutes after the run. A
completed Maven run does not leave its own test classes missing.

Meanwhile:

```text
target/          last written 22:12:33
test-classes/   last written 22:12:06
java processes running now: 0
```

The campaign workstream was editing source in the same minute the build ran. Two
Maven builds contended for `target/`, and the loser left an incomplete classpath.

### Classification

| | |
|---|---|
| Layer | **Build environment / concurrent Maven contention** |
| Not a code regression | correct — `EslProtocolTest` passed 21/0/0 in the E.4 baseline at 160 classes |
| Not telephony | correct — a `FakeEslServer` classpath miss cannot indicate a protocol defect |
| Valid baseline | **E.4's: 1005 tests, 1 failure, 0 errors, 2 skipped** |
| Action taken | none; a concurrent build cannot be "fixed" from this phase |

Reporting 937/2/161 as a baseline would be reporting a corrupted measurement as
a project state. The E.4 figure stands as the last valid measurement, and it
must be re-established only when the tree is quiet.

## 6. E.3 Gate — Preserved

Verified unchanged, with every value the mission specifies:

```text
name                    = e3-local-endpoint-1002
provider                = LOCAL
profile                 = internal
enabled                 = true
status                  = ACTIVE
max_concurrent_channels = 10
priority                = 10
owner_type              = PLATFORM
owner_tenant_id         IS NULL          (ownership contract honoured)
route                   = sofia/gateway/local-endpoint-1002/<contact number>

gateways=1  allocations=1  executions=0  attempts=0
```

No second gateway created, and the existing gateway was not touched to
compensate for anything.

## 7. Live Telephony Preflight

A short preflight, not a repeat of E.1:

```text
obd-freeswitch        running/healthy   restarts=0
obd-fs-endpoint-1002  running/healthy   restarts=0

ESL status      UP 0 years, 0 days, 2 hours, 43 minutes
gateway         local-endpoint-1002  Profile internal  Proxy sip:endpoint-b:5060  NOREG
registrations   Total items returned: 2
```

`NOREG` is correct for a `register=false` origination-only trunk, and two
registrations are present, so the route the gateway depends on exists. The
E.1/E.3 topology is intact with no regression.

## 8. Why No Seed Was Created

The mission asks for the smallest dataset the *current committed* application
considers valid. The current committed application is `3a89e5c` — but the working
tree that would actually run is `3a89e5c` **plus three uncommitted files that are
being edited right now**, one of which adds the `integrationConfig` field to the
entity the execution's `NOT NULL` FK points at.

Seeding against `3a89e5c` alone would be seeding against source that is not the
application, because Maven compiles the working tree. Seeding against the working
tree would be seeding against a moving target.

Neither yields a result that means anything, so neither was done.

## 9. J1 — Still AMBIGUOUS

Unchanged and not upgraded.

```text
CallAttempt.id = origination_uuid = FreeSWITCH channel UUID
               = attempt/session/leg .providerCallId
               = the key EslEventService correlates on
```

`attempts = 0`. No application-created attempt has ever existed, so the Java
event stream has never had one to resolve. Structural evidence is not runtime
evidence, and this is the fifth consecutive phase in which that distinction has
been held rather than blurred.

## 10. Operational Lesson — Maven `clean` and Log Location

Recorded here as required, and it was applied in this phase.

**Never write Maven output into `target/` while running `clean`.**

```text
INVALID:  ./mvnw clean test > target/test.log
VALID:    ./mvnw clean test > /tmp/test.log 2>&1
```

The invalid form produces:

```text
Failed to execute goal maven-clean-plugin:clean
  Failed to delete ...\target\test.log:
  The process cannot access the file because it is being used by another process
```

...which reports `BUILD FAILURE` with **zero tests executed**. That is not a test
failure and must never be reported as one. E.4 hit this and cost a run; E.5
avoided it by writing the log outside `target/`.

The related and more dangerous variant, which E.5 *did* hit, is a **second Maven
build deleting the same `target/` mid-run** — see §5. Both failures look like
build breakage and neither is one.

## 11. Live Evidence Matrix

| Scenario | Result | Evidence |
|---|---|---|
| `3a89e5c` in the committed baseline | **CONFIRMED** | `merge-base --is-ancestor` |
| Campaign contract stable | **NO** | 3 uncommitted files, one changed 4 s before the check |
| `integrationConfig` contract settled | **NO** | +94 lines adding the field now |
| Full repository baseline | **INVALID RUN** | `NoClassDefFoundError`; `target/test-classes/.../telephony/` = 0 classes |
| Last valid baseline | E.4: **1005 / 1 / 0 / 2** | standalone run, no contention |
| E.3 gateway preserved | **CONFIRMED** | all values verified |
| Telephony preflight | **CONFIRMED** | both containers healthy, 0 restarts |
| ESL reachable, gateway present, 2 registrations | **CONFIRMED** | `sofia status` |
| Seed created | **NOT ATTEMPTED** | moving contract |
| Scheduler ran an execution | **NOT YET TESTED** | no execution |
| CallAttempt created | **NOT YET TESTED** | `attempts = 0` |
| Dialer reached | **NOT YET TESTED** | no dial |
| J1 runtime | **AMBIGUOUS** | unchanged |
| Peer witness / RTP / capacity / persistence | **NOT YET TESTED** | no call |
| Carrier / PSTN | **NOT TESTED** | — |

RTP for an application-originated call remains **NOT YET TESTED**. E.1's
harness-call measurement is not inherited.

## 12. Documentation

This document, the README index row, and a knowledge-base entry for the
build-contention class of failure. No historical finding was rewritten and no
prediction was converted into a fact.

## 13. Security

| Check | Result |
|---|---|
| live credential values in files changed | **0** |
| `.env`, `*.fsxml`, runtime logs staged | **none** |
| generated `.pyc` staged | **none** |
| new host ports | **none** |
| `infra/docker-compose.yml` | **untouched**, diff clean |
| concurrent files modified | **NONE** |
| source files modified by E.5 | **NONE** |

## 14. Files Changed

**None.** No source file, no configuration, no schema, no seed data. The E.3
rows were verified unchanged.

**Documentation**

- `docs/LIVE-FREESWITCH-PHASE-E5.md` *(new)*

## 15. Architecture Impact

**NO.**

## 16. Remaining Limitations

| Item | Status |
|---|---|
| Application-originated call | **NOT YET TESTED** |
| J1 runtime correlation | **AMBIGUOUS** — five phases unresolved |
| Capacity reservation/release | **NOT YET TESTED** |
| Peer witness, RTP, persistence | **NOT YET TESTED** |
| Current valid full-suite baseline | **stale** — E.4's figure, and `target/` is contended |
| Campaign contract | **moving** |

## 17. Next Recommended Phase

1. **Do not start a telephony phase until the campaign workstream is idle.**
   The measurable bar: no file under `backend/src/main/java/.../campaign`
   modified for 30 minutes, and no Maven process running. Both were violated
   during E.5.
2. **Re-establish the full baseline first, and confirm it is a clean run.** Two
   independent conditions must both hold: no concurrent build, and telephony
   test classes actually present in `target/test-classes` afterwards. Verify
   `FakeEslServer.class` exists before trusting any result. If the count is not
   ≥1005, the run was corrupted again.
3. **Then re-read the campaign contract from the working tree**, not from this
   document — especially `CampaignConfigurationSnapshot` and
   `CampaignRuntimeConfigResolver`, whose `integrationConfig` shape must be
   settled.
4. **Then seed** and drive the 30-second scheduler, with DEBUG on
   `EslClient`/`EslEventService` for the single call, as E.5 planned.
5. **Re-baseline expectation:** if the committed campaign work changes test
   counts, that is a moving baseline to be stated explicitly, not a telephony
   regression.

## 18. Related Documents

- [`LIVE-FREESWITCH-PHASE-E4.md`](LIVE-FREESWITCH-PHASE-E4.md) — the last valid baseline and the prior stop
- [`LIVE-FREESWITCH-PHASE-E3.md`](LIVE-FREESWITCH-PHASE-E3.md) — the gateway this phase preserved
- [`LIVE-FREESWITCH-PHASE-E1.md`](LIVE-FREESWITCH-PHASE-E1.md) — the Spring gate
- [`freeswitch/13-TROUBLESHOOTING.md`](freeswitch/13-TROUBLESHOOTING.md) — entries 30, 32
