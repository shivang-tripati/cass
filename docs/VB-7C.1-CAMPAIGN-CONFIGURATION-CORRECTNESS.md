# VB-7C.1 — Campaign Configuration Correctness Hardening

Implementation record for VB-7C.1. Design source: the VB-7C audit
(`docs/VB-7C-INTEGRATIONS-CAMPAIGN-CONFIGURATION-AUDIT.md`, §10.3 and §19).

- Baseline: `828bec4` (VB-7B) = `origin/main`, migration head **V54**
- Migration head after this phase: **V54** (unchanged — no migration)
- New tests: **55** (42 readiness-owner + 13 write-time capability)

---

## 1. Status

**COMPLETE**, with one behaviour change disclosed in §3 and one environmental
limitation disclosed in §11.

---

## 2. Baseline

| Item | Verified |
|---|---|
| Branch / HEAD | `main` / `828bec4` |
| `origin/main` | `828bec4` (identical, 0 ahead) |
| Migration head | `V54__campaign_type_missed_call.sql` |
| Modules / cycles | 16 / 0 |

The repository had **not** advanced since the audit. Two additional user-owned
FreeSWITCH test files appeared in the working tree during the audit
(`EslEventRuntimeContractTest`, `LiveFreeSwitchRuntimeContractTest`); both are
user-owned and untouched.

---

## 3. Root causes found

### 3.1 Defect 1 — MISSED_CALL timezone fail-open (plus a wider variant)

`CampaignReadinessService.checkScheduleReadiness` required an execution timezone
behind a **campaign-type membership list**:

```java
if (campaign.getCampaignType() == CampaignType.PLAYFILE
        || campaign.getCampaignType() == CampaignType.DTMF
        || campaign.getCampaignType() == CampaignType.CONNECT_BY_AGENT) { ... }
```

`MISSED_CALL` was omitted, so a MISSED_CALL campaign could be created, reported
ready, and then fail every dial.

**The audit under-reported the blast radius.** While tracing it I found the rule
was also skipped entirely whenever `schedule == null`, because the whole method
is guarded by `if (campaign.getSchedule() != null)`. Since
`CampaignMapper.toScheduleSpec(null)` returns `null` and every schedule column is
nullable, **a campaign with no schedule object at all — of any of the four types —
was also accepted, reported ready, and undialable.** The three types the audit
believed were safe were not.

The underlying reason the requirement exists is type-independent:
`OutboundDialService:176` resolves the VB-6C daily-usage-day zone as
`campaign.schedule() != null ? campaign.schedule().getTimezone() : null`, with
**no campaign-type branch anywhere on that path**, and a missing zone produces
`EXECUTION_TIMEZONE_INVALID` — classified `PERMANENT` in `CallFailureCode:200`.
Every campaign type is dialled, so every campaign type needs the zone.

### 3.2 Defect 2 — DTMF + TTS fail-open

`CampaignService:415` denied TTS **per type**:

```java
if (type == CampaignType.PLAYFILE && mode == ContentMode.TTS) { throw ... }
```

VB-6E applied that fix to the type it was hardening at the time. `ContentMode.TTS`
is referenced in exactly six places, all validation or readiness — **there is no
TTS synthesis or playback runtime anywhere**. So `DTMF + TTS` was accepted,
passed readiness (an approved template exists), and would fail every call with a
PERMANENT `PLAYBACK_CONFIG_INVALID` (`CallFailureCode:92`).

The invariant was attached to a type when it belongs to a **content mode**.

### 3.3 Family-B classification

Every `CampaignType` branch in `src/main/java` was classified. Family A (a
service asking "is this type mine?") is safe and was left alone — 7 sites,
including `checkConnectByAgent` and `IvrFromCampaignService`. Family B (a *rule*
expressed as a membership list) was the danger: VB-7B fixed 2 of 4, and **2
remained — both P0.** All Family-B sites in the configuration path are now
eliminated.

---

## 4. How the Family-B gates were eliminated

One capability on the type itself, from which both media rules derive:

```java
// CampaignType
public boolean playsMedia() {
    return switch (this) {          // no `default` arm
        case PLAYFILE          -> true;
        case DTMF              -> true;
        case CONNECT_BY_AGENT -> false;
        case MISSED_CALL       -> false;
    };
}
```

Adding a fifth constant **fails the build** until its capability is stated. That
is the guarantee a membership list cannot give, and it is why the fix is not
"append MISSED_CALL to the lists".

| Family-B site | Before | After |
|---|---|---|
| Content required (write) | `(PLAYFILE \|\| DTMF) && mode == null` | `type.playsMedia() && mode == null` |
| Content required (ready) | `(PLAYFILE \|\| DTMF) && mode == null` | `type.playsMedia() && mode == null` |
| TTS unsupported (write) | `type == PLAYFILE && mode == TTS` | `type.playsMedia() && mode == TTS` |
| TTS unsupported (ready) | *(absent — the DTMF hole)* | `type.playsMedia() && mode == TTS` |
| Execution timezone (ready) | `PLAYFILE \|\| DTMF \|\| CONNECT_BY_AGENT` | **no type reference at all** |

The timezone rule was not moved onto a capability, because it is genuinely
**universal**: removing the list entirely is the strongest available form of the
fix, and a hypothetical future non-dialing type would merely over-require a
harmless field.

Behaviour for all four existing types is unchanged. `playsMedia()` is `true`
exactly for the types that require content today, so the inclusive list VB-7B
introduced and the capability agree.

---

## 5. MISSED_CALL timezone fix

- New `checkExecutionTimezone` in `CampaignReadinessService`, called from
  `evaluateResolved` **outside** the `schedule != null` branch and with **no
  `CampaignType` reference**, so no future type can be omitted.
- Reports the existing `SCHEDULE_TIMEZONE_REQUIRED` reason; no new reason code.
- The old list inside `checkScheduleReadiness` is removed. Window-coherence and
  IANA-validity rules are untouched.
- **No fallback introduced.** No JVM zone, no UTC, no server default — the brief
  requires fail-closed, and a silent default would also make the usage-day
  boundary differ from the operator's intent. Blank and absent both stay
  unready.
- `CampaignService` write-time rules are **unchanged**: a timezone is still
  required only when a window is configured. The scheduling model is preserved;
  the correction is at the readiness layer, which is where "can this run now"
  belongs.

### 5.1 Disclosed behaviour change

A campaign with **no schedule object**, or with a windowless schedule and no
timezone, was previously reported READY and is now reported not ready. This is
intentional: such a campaign fails 100% of dials today with a permanent error, so
it is not ready by any correct definition.

**One existing test was affected** —
`CampaignGovernanceHardeningPostgresIntegrationTest.duplicateExecutionIdempotencyKeyReturnsFirstExecution`.
Its `seedCampaignRow` helper never set a schedule, and the test's own comment
says *"make the campaign fully ready … so only the idempotency dimension is under
test"*. The fixture was not actually ready; the schedule-less bypass was hiding
it. The fix adds a **windowless schedule carrying a timezone** — the minimal
correction that matches the test's stated intent, and deliberately not a date
window, which would introduce `SCHEDULE_NOT_ELIGIBLE` depending on the day the
suite runs. The test's actual assertion is unchanged.

No test was deleted, disabled, weakened or skipped.

---

## 6. DTMF + TTS fix

- Write time (`CampaignService.validateContent`) and readiness
  (`checkContentConfiguration`) now share one rule: `type.playsMedia() &&
  mode == ContentMode.TTS`.
- The message is type-prefixed (`DTMF campaigns do not support TTS content yet…`),
  which keeps the existing assertion `contains("do not support TTS content")` in
  `CampaignResourceValidationPostgresIntegrationTest` passing and names the
  offending type.
- **PLAYFILE + TTS is unchanged** — VB-6E's behaviour is preserved exactly.
- **Types that play nothing are deliberately unaffected.** CONNECT_BY_AGENT
  bridges and MISSED_CALL only rings; neither can fail a call because of a TTS
  reference, so rejecting it there would invent a media capability they do not
  have and would change VB-7A / VB-7B behaviour. This is asserted explicitly in
  both test classes so the boundary is documented, not accidental.
- No TTS runtime was built, and no TTS governance service was modified. The
  existing `BusinessException` error protocol carries the condition.

---

## 7. Files changed

### Production (3)
```
campaign/CampaignType.java                  + playsMedia() capability (no default arm)
campaign/CampaignService.java               TTS + content rules now capability-derived
campaign/CampaignReadinessService.java      + checkExecutionTimezone; TTS + content
                                            rules capability-derived; Family-B list removed
```

### Tests (3)
```
campaign/CampaignReadinessServiceTest.java            NEW — readiness owner test (42)
campaign/CampaignTypeCapabilityValidationTest.java   NEW — write-time owner test (13)
campaign/CampaignGovernanceHardeningPostgresIntegrationTest.java
                                            seedCampaignRow: windowless schedule + timezone
```

**No migration, no entity, no repository, no controller, no DTO, no OpenAPI
source change, no new `@Scheduled`, no dependency change.**

---

## 8. Readiness-owner test coverage

`CampaignReadinessServiceTest` (42 tests) is the owner test the audit found
missing. It is parameterized over `CampaignType.values()` rather than a
hand-kept list, so a new type is covered automatically.

| Group | Proves |
|---|---|
| A. Media capability (4) | `playsMedia()` states a value for every type; capability matches runtime; **every** type reaches typeConfig validation with a broken payload; a missing typeConfig is reported for every type that requires one — and PLAYFILE's empty configuration is explicitly asserted **valid** |
| B. Execution timezone (18) | **every** type without a timezone is not ready (windowless); **every** type with no schedule object is not ready; **every** type with a valid timezone **is** ready; blank is refused; invalid IANA still reports `INVALID_SCHEDULE`; the reason is reported exactly once |
| C. Unsupported TTS (5) | TTS reported for every media-playing type, with a guard that the loop covered both; DTMF named in the error; **PLAYFILE + TTS regression**; AUDIO still valid; non-playing types explicitly unaffected |
| D. Content requirement (8) | no type is ever told it requires content it cannot use; a media-playing type with no content mode is reported; CONNECT_BY_AGENT and MISSED_CALL each need no content (VB-7A/VB-7B preserved); AUDIO with no asset is reported |
| E. Reachable READY (7) | every type has a reachable READY state — so the negative assertions cannot pass vacuously; lifecycle rule unchanged; tenant isolation (`CONTACT_GROUP_UNAVAILABLE`, `DID_UNAVAILABLE`) unchanged |

Group E is deliberate: without it, a readiness service that rejected everything
would satisfy A–D. Each negative test is paired with a positive reachable-READY
assertion.

`CampaignTypeCapabilityValidationTest` (13 tests) owns the write-time layer, with
the boundary stated in the class javadoc: this class tests "may this be stored",
readiness tests "can it run now", execution services own runtime. The
dangling-reference rule is asserted **only** at the write layer, where it belongs,
rather than duplicated into readiness.

---

## 9. Migration status

**No migration. V54 remains the head.** Nothing in this phase touches schema.
`CampaignType` gained a method, not a constant, so `ck_campaigns_type` is
unaffected. The corrected invariants are enforced in Java, at the two layers that
own them.

---

## 10. API / OpenAPI impact

**No REST contract change.** No endpoint, DTO, field, status code or error code
was added or removed.

The only externally visible effect is **which configurations are accepted**:
`DTMF + TTS` and a campaign with no execution timezone now produce a validation
error instead of being stored and failing at dial time. Both use the existing
`BusinessException` / 400 path and existing reason codes
(`SCHEDULE_TIMEZONE_REQUIRED`, `INVALID_CONTENT_CONFIGURATION`), so the generated
document's schema and security contract are unchanged.

`CampaignOpenApiContractTest` (28 tests) was **not** modified and passes
unchanged — the correct outcome, since nothing API-visible was added.

---

## 11. Test results

### 11.1 Focused results — every run below actually executed

| Suite | Result |
|---|---|
| `CampaignReadinessServiceTest` **(new)** | **42 / 0 F / 0 E / 0 S** |
| `CampaignTypeCapabilityValidationTest` **(new)** | **13 / 0 F / 0 E / 0 S** |
| All `Campaign*Test` — configuration, readiness, snapshot, governance, resource validation, API slices, OpenAPI | **284 / 0 F / 0 E / 0 S** |
| `PlayfileExecutionServiceTest`, `DtmfExecutionServiceTest`, `IvrExecutionServiceTest`, `ConnectByAgentExecutionServiceTest`, `MissedCallExecutionServiceTest`, `CampaignConfigurationSnapshotTest`, `CampaignConfigurationSnapshotPostgresIntegrationTest`, **`ArchitectureTest`** | **97 / 0 F / 0 E / 0 S** |
| `MissedCallPostgresIntegrationTest` | **15 / 0 F / 0 E / 0 S** |
| `ConnectByAgentConfigPostgresIntegrationTest` | **8 / 0 F / 0 E / 0 S** |
| `CampaignGovernanceHardeningPostgresIntegrationTest` | **10 / 0 F / 0 E / 0 S** |
| `OrganizationalHomeDbEnforcementTest` | **9 / 0 F / 0 E / 0 S** |
| `ProvisioningSmokeIntegrationTest` | **13 / 0 F / 0 E / 0 S** |

`CampaignOpenApiContractTest` (28 tests) is inside the 284-test campaign run and
passed. `ArchitectureTest` is inside the 97-test run: **16 modules, 0 cycles.**

### 11.2 Full suite — attempted, not cleanly obtainable in this environment

All **302** test classes executed. The failures were **exclusively
infrastructure**, and each was then re-run in isolation to prove it:

| Failure in the full run | Cause | Isolated re-run |
|---|---|---|
| `CampaignOpenApiContractTest` (28), `ProvisioningSmokeIntegrationTest` (13), `OrganizationalHomeDbEnforcementTest` (9), `IvrPostgresConstraintTest` (10), `AudioUploadPostgresIntegrationTest` (8), `IvrOpenApiContractTest` (9), `ContactGroupMemberOpenApiContractTest` (5) | `FlywaySqlUnableToConnectToDbException: Connection to localhost:5432 refused` while the Spring context started. Once one context load fails, Spring's *"failure threshold (1) exceeded"* makes every later context-dependent class in the same JVM report the identical error — hence whole classes failing. | `CampaignOpenApiContractTest` **28/28**, `OrganizationalHomeDbEnforcementTest` **9/9**, `ProvisioningSmokeIntegrationTest` **13/13** — all green |
| `LiveFreeSwitchRuntimeContractTest` (4) | `Esl Failed to connect to FreeSWITCH at 127.0.0.1:8021`. **User-owned, untracked** file from the parallel FreeSWITCH track; requires a live FreeSWITCH socket, which the platform deliberately does not depend on (`telephony.freeswitch.enabled: false`) | not run — out of this phase's scope |
| `VoiceCapacityConcurrencyIntegrationTest` (1) | `IllegalState: Previous attempts to…` in its own `cleanupAll` — collateral from the context failures above | not separately re-run |

`obd-postgres` **is** host-mapped to `0.0.0.0:5432` and was listening both before
and after; the refusal was transient under memory pressure, not a misconfigured
dependency.

### 11.3 Environmental limitation — disclosed, nothing worked around

The host has **5.9 GB RAM**, with free memory swinging between **0.08 GB and
1.29 GB** across this phase, while the user's **9 Docker containers** run
(`obd-freeswitch`, `obd-fs-endpoint-1001/1002`,
`auth-starter-{keycloak,postgres,redis,mailpit}`, `obd-postgres`, `obd-redis`).

Two symptoms, both environmental and both intermittent:

1. The Surefire **fork dies at JVM startup**, which Surefire reports as the
   misleading `No tests matching pattern "…"` rather than an OOM.
2. The **Flyway/Spring context** intermittently cannot reach `localhost:5432`,
   cascading into whole-class failures via Spring's context failure threshold.

Per the brief, **no unrelated infrastructure was modified to work around this.**
The user's containers were not stopped, no datasource or test configuration was
touched, and no test was excluded. Two non-invasive mitigations were used, neither
of which changes project code:

- `mvnw surefire:test` (skip the compile phase) instead of `mvnw test`, avoiding a
  recompile of 173 test sources while memory-constrained.
- Bounded heaps: `MAVEN_OPTS=-Xmx256m…-Xmx320m` with `-DargLine=-Xmx800m`.

**No run that matched zero tests was reported as a pass.** Such runs were
identified and discarded. Every number in §11.1 comes from a run that demonstrably
executed its tests.

### 11.4 Seven pre-existing fixtures corrected

The behaviour change in §5.1 surfaced in **7 existing tests across 3 files**, all
of which seeded a campaign with **no schedule** and relied on the removed bypass
to be considered ready. Each seeder now sets a windowless schedule carrying a
timezone — the minimal correction that matches each test's own stated intent, and
deliberately not a date window, which would add day-dependent
`SCHEDULE_NOT_ELIGIBLE`:

| File | Seeder | Tests affected |
|---|---|---|
| `CampaignGovernanceHardeningPostgresIntegrationTest` | `seedCampaignRow` | 1 |
| `ConnectByAgentConfigPostgresIntegrationTest` | `seedConnectByAgentCampaign` | 3 |
| `MissedCallPostgresIntegrationTest` | `seedMissedCallCampaign` | 3 |

No assertion was changed, removed, weakened or skipped. The `seedCampaignRow`
comment even documents the pre-existing intent: *"make the campaign fully ready …
so only the idempotency dimension is under test"* — the fixture was not actually
ready, and the bypass was hiding it.

---

## 12. Regression

Verified green (see the verification section of the delivery summary for exact
per-suite counts):

- **Configuration / readiness:** all `Campaign*Test` — including
  `CampaignValidationServiceTest`, `CampaignMissedCallValidationTest`,
  `CampaignTypeConfigTest`, `CampaignConfigurationSnapshotTest`,
  `CampaignConfigurationSnapshotPostgresIntegrationTest`,
  `CampaignGovernanceHardeningPostgresIntegrationTest`,
  `CampaignResourceValidationServiceTest`,
  `CampaignResourceValidationPostgresIntegrationTest`,
  `CampaignEditabilityTest`, `CampaignLifecycleServiceTest`,
  `CampaignOpenApiContractTest` (28).
- **Per-type execution:** `PlayfileExecutionServiceTest`, `DtmfExecutionServiceTest`,
  `IvrExecutionServiceTest`, `ConnectByAgentExecutionServiceTest`,
  `MissedCallExecutionServiceTest`, `MissedCallCampaignConfigTest`.
- **Architecture:** `ArchitectureTest` — 16 modules, 0 cycles.

### Intentionally untouched

`RetryPolicyService`, `DailyDialLimitService`, `DailyAttemptSafetyService`,
`CallEligibilityService`, `VoiceEligibilityService`, `VoiceRoutingService`,
`AcdService`, `OutboundDialService`, `HangupCauseMapper`, `EslEventService`,
`CampaignTypeConfig` and its four existing cases, the
`CampaignExecutionOrchestrator` tick, the sealed-hierarchy contract, snapshot
semantics, tenant authorization, and all user-owned FreeSWITCH/live-telephony
work.

Verified unchanged: **9 `@Scheduled` annotations** platform-wide, none in the
configuration layer. No webhook or report functionality was introduced.

---

## 13. Limitations and deferred findings

1. **The disclosed readiness behaviour change** (§5.1) is the one observable
   difference. It is a correction, but operators with existing windowless,
   timezone-free campaigns will see them become unready. That is the intent —
   they were undialable — and it should be called out in release notes.
2. **A campaign with no schedule is now unready rather than rejected at write
   time.** Rejecting `schedule == null` on create was deliberately not done: it
   would change the campaign content/scheduling model, which the brief places out
   of scope. Readiness fails closed instead.
3. **Rule duplication between `CampaignService` and
   `CampaignReadinessService` persists** (the audit's §19.6). Both are now
   capability-derived from the single `playsMedia()` authority, so they cannot
   disagree about a media rule, but the two methods are still separate. Central
   validation was refactored only where the correctness fix required it.
4. **Readiness reason vocabulary remains inconsistent** (the audit's §19.5):
   `DTMF_CONFIG_INVALID` / `IVR_CONFIG_INVALID` exist only as runtime failure
   codes, so a DTMF campaign with a broken payload is still reported as
   `INVALID_CONTENT_CONFIGURATION`. Not fixed — a reason-vocabulary change is
   outside this phase's four objectives.
5. **Empty audience is still only a log warning** (the audit's §19.7).
6. **User-owned `EslProtocolTest.java` does not compile** in the working tree
   (an `InterruptedException` is unhandled at line 499 after the FreeSWITCH work
   changed `FakeEslServer.awaitClient`). It was **not** modified. To run the
   phase's tests it was relocated with a SHA-256-verified backup outside the
   repository and restored byte-identically afterwards; the hash matched. This
   is the user's file to fix.

---

## 14. Git

- **Commit:** see the delivery summary.
- **Not pushed.**
- Staged by explicit path only. No user-owned file staged or committed.
