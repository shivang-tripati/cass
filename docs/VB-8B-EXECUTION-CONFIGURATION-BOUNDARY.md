# VB-8B — Execution Configuration Boundary & Manual Attempt Hardening

**Status: COMPLETE**
Full suite **2038 tests / 0 failures / 0 errors / 2 skipped**, `BUILD SUCCESS`.
Architecture **16 modules / 0 cycles**. Migration head **V55 — unchanged**.
Remediation of VB-8A findings **F-01**, **F-02**, **F-03**.

---

## 1. Baseline

| Item | Value |
|---|---|
| HEAD | `3a89e5c` — "VB-7C.2: integrations + campaign configuration (configuration only)" |
| `origin/main` | `3a89e5c` |
| Divergence | `0 / 0` |
| VB-8A audit | `docs/VB-8A-CAMPAIGN-EXECUTION-SCHEDULER-AUDIT.md` |
| Pre-phase full suite | 1991 tests / 0F / 0E / 2S |
| Post-phase full suite | **2038 tests / 0F / 0E / 2S** (+47) |
| Migration head at start / end | V54→V55 (from VB-7C.3) / **V55 — unchanged** |
| Architecture at start / end | 16 / 0 cycles / **16 / 0 cycles** |

**Working tree was and remained dirty** — user's FreeSWITCH work plus this
platform's uncommitted VB-7C.3 work. See §14.

---

## 2. Scope

Implemented exactly the three findings VB-8A handed over, and nothing else.

| In scope | Done |
|---|---|
| F-01 — harden `CallAttemptService.createAttempt` | §3, §4 |
| F-02 — remove dead mutable campaign bindings / stale comments | §5 |
| F-03 — prove the editable/executable invariant | §6 |
| Tests, OpenAPI, documentation | §8, §13, this file |

**Not implemented** (explicitly out of scope, and verified absent from the diff —
§13.2): webhook delivery/signing/retries, report runtime, aggregation,
pseudonymisation, scheduler redesign/cadence, retry-policy redesign, daily
dial-limit redesign, daily-attempt redesign, DND/DNC redesign, whitelist
redesign, routing, capacity, FreeSWITCH, ESL, audio, IVR, MISSED_CALL,
campaign versioning, legacy execution compatibility, configuration history,
Kafka, Kubernetes, distributed locks, Redis, new modules, migrations, REST shape
changes.

---

## 3. F-01 — root cause

`POST /api/v1/campaigns/{campaignId}/executions/{executionId}/attempts` is a
second, independent writer into an existing execution's attempt table. It had no
relationship to the execution's frozen configuration at all: it never injected
`CampaignRuntimeConfigResolver`, loaded the `CampaignEntity` purely for an
existence check, and wrote three execution-affecting values straight from the
HTTP body.

| Request field | Before | Why it was a hole |
|---|---|---|
| `didId` | persisted verbatim | The attempt could name a DID the execution never froze. The dial path prefers the frozen DID (`config.didId()`), so the *call* was mostly safe, but the persisted row disagreed with the snapshot. |
| `scheduledAt` | persisted verbatim, defaulting to `Instant.now()` | **The calling-hours window was applied only in `CampaignExecutionOrchestrator`** (`calculateNextScheduledAt` / `adjustToScheduleWindow`, private). `OutboundDialService.processAttempt` uses only the frozen *timezone*, never `dailyStartTime`/`dailyEndTime`, and `processDueAttempts` selects on status + `scheduledAt` alone. So a manual attempt could be queued and dialled entirely outside the campaign's calling hours. |
| `attemptNumber` | persisted verbatim, `@Positive` only | The frozen `retryPolicy` is consulted only in `processRetriesForExecution`. An injected attempt number was never evaluated against it, so retry governance could be bypassed. |
| audience | none | The dial-time group check is **skipped when the frozen campaign sets `callOnWhitelistNumbers = true`** (`CallEligibilityService`: `if (!enforceWhitelist)`), so a whitelist campaign's manual attempt could reach any tenant contact. |
| execution lifecycle | none | Attempts could be injected into `COMPLETED`, `FAILED` or `CANCELLED` executions, resurrecting finished work. |
| comment | `// Validate contact exists and belongs to the campaign's contact group` | **False**: no group validation was performed. |

---

## 4. F-01 — remediation

### 4.1 One boundary, not a new mechanism

`CallAttemptService` now injects the **existing** `CampaignRuntimeConfigResolver`
and resolves the execution's frozen `CampaignRuntimeConfig`. No new resolver, no
second configuration source, no fallback.

The service's `CampaignRepository` dependency was **removed entirely**, so
`snapshot value if present else live campaign` is not merely avoided — it is
unrepresentable. `ManualCallAttemptBoundaryTest.B7-2` asserts this
structurally (no field of type `CampaignRepository` exists).

The campaign-execution relationship check is now a comparison of two identifiers
already in hand (`execution.getCampaignId()` vs the path variable), which is
what the campaign load was standing in for.

### 4.2 Per-field contract

| Value | Rule | Reused authority |
|---|---|---|
| `didId` | **Derived** from `config.didId()`. A supplied value that disagrees is **rejected** (409) rather than silently discarded — there is exactly one correct DID, so a mismatch is always a client error, never an ambiguity. A supplied value that agrees is accepted. | snapshot |
| `scheduledAt` | **Derived** from the frozen schedule. The request field is still accepted (no API shape change) but is **not authoritative**: supplying it changes nothing. | `ExecutionScheduleCalculator` |
| `attemptNumber` | **Validated** against the frozen ceiling. | `RetryPolicySpec.maxPermittedAttemptNumber()` |
| audience | Contact must be a live member of the **frozen** `contactGroupId`. | `ContactGroupMemberRepository.existsByContactGroupIdAndContactId` |
| runnability | `REQUESTED` / `RUNNING` only. | `CampaignExecutionStatus` |

### 4.3 Reused, not duplicated

* **Calling window** — the calculation was *private to the orchestrator*, so the
  manual path could not honour it. Rather than let a second implementation
  appear, it was extracted to a new `ExecutionScheduleCalculator` (`@Component`,
  campaign package, no dependencies beyond `ScheduleSpec`). `CampaignExecutionOrchestrator`
  now delegates to it; its own private copies are gone. **One implementation, two
  callers.**
* **Retry ceiling** — `RetryPolicySpec.maxPermittedAttemptNumber()` derives
  `1 + maxRetries` through the existing `flatAsRule(...)` conversion, so the
  domain's most heavily documented invariant still has exactly one home. Per-category
  rules "can only restrict", so the flat allowance is the correct ceiling for a
  path that has no failure category yet.
* **DID validity**, **contact identity**, **duplicate detection** — unchanged
  existing calls. Resource validity deliberately stays dynamic (VB-6A
  snapshot-vs-resource rule).

### 4.4 Runnable statuses — derived, not assumed

`CampaignExecutionStatus` has exactly five values. The orchestrator starts
`REQUESTED` and reconciles `RUNNING`; `COMPLETED`, `FAILED` and `CANCELLED` are
the lifecycle's terminal outcomes. `ATTEMPT_CREATABLE_EXECUTION_STATUSES =
{REQUESTED, RUNNING}` was read off that lifecycle, and `B5-4` asserts the
refusal for *every* enum value rather than spot-checking.

---

## 5. F-02 — cleanup

| Site | Change |
|---|---|
| `PlayfileExecutionService` | Removed `CampaignEntity liveCampaign` — assigned, never read. Replaced with a comment stating the campaign load is an existence/tenant-scope guard only. |
| `DtmfExecutionService` | Same removal. Additionally **corrected a comment that asserted a forbidden fallback**: it claimed configuration came from the snapshot "(falling back to live config only for legacy executions)". There is no such fallback and there must never be one — a missing snapshot is a deterministic `ExecutionConfigurationMissingException`. |
| `CallAttemptService` | Removed the unused `ContactGroupRepository` field and the `CampaignRepository` dependency. |
| `OutboundDialService` | Corrected a comment claiming `buildDestinationNumber` checks audience-group membership; it has no group predicate by design, and the check lives in `CallEligibilityService`. |

**Deliberately not done:** VB-8A also flagged a never-called mutable-campaign
adapter (`CallEligibility:47` / `telephony/CallEligibilityService:78`). It lives
in the telephony module, which VB-8B lists as out of scope. It remains open and
is reported in §15.

---

## 6. F-03 — invariant and test guard

**Readiness behaviour is unchanged.** `checkLifecycleState` still accepts exactly
`SCHEDULED` and `RUNNING`; the only change is that it now asks
`CampaignLifecyclePolicy.isExecutable(status)` instead of inlining the two
constants, so the gate and the policy cannot drift. 51 readiness tests unchanged
and green.

Added to `CampaignLifecyclePolicy`:

* `EXECUTABLE_STATUSES` — the set, now named rather than duplicated in two places.
* `isExecutable(CampaignStatus)` — static, so `CampaignReadinessService` needs no
  new constructor dependency (11 existing construction sites untouched).
* `editableAndExecutableAreDisjoint()` — the invariant, expressible.
* Javadoc explaining *why* the invariant is load-bearing: if editability ever
  widened to an executable state, the scheduler's readiness gate would begin
  deciding eligibility from mutable configuration.

A matching comment was added at `doStartExecution` where live campaign state is
consulted. It states the reasoning plainly and **does not overclaim**: readiness
is a pre-execution gate; configuration comes from the snapshot; editability and
executability are disjoint; and it openly notes the limits — live *resource*
degradation (a deleted asset, a withdrawn DID) can still move the gate, which is
intentional under VB-6A.

**Tests** (`CampaignLifecycleInvariantsTest`, 11 tests): the disjointness holds;
every one of the 7 `CampaignStatus` values is non-overlapping (parameterized, so
a new status is covered automatically); the enumerable sets really are
`{SCHEDULED, RUNNING}` and `{DRAFT}` so the assertion cannot pass vacuously; the
`SCHEDULED → DRAFT` unlock toggles both sides simultaneously; and
`assertEditable` still names the current state.

---

## 7. Files changed

### Production (10)

| File | Change |
|---|---|
| `campaign/CallAttemptService.java` | **F-01 core.** Frozen DID / schedule / attempt ceiling / audience / runnability; `CampaignRepository` + unused `ContactGroupRepository` removed |
| `campaign/ExecutionScheduleCalculator.java` | **New.** The one canonical calling-window calculation, extracted from the orchestrator |
| `campaign/CampaignExecutionOrchestrator.java` | Delegates to the calculator; F-03 explanatory comment at the readiness gate; 5 orphaned `java.time` imports removed |
| `campaign/CampaignLifecyclePolicy.java` | `EXECUTABLE_STATUSES`, `isExecutable`, `editableAndExecutableAreDisjoint` |
| `campaign/CampaignReadinessService.java` | Delegates the executable check to the single authority (behaviour identical) |
| `campaign/RetryPolicySpec.java` | `maxPermittedAttemptNumber()`, derived via `flatAsRule` |
| `campaign/PlayfileExecutionService.java` | F-02 dead binding removed |
| `campaign/DtmfExecutionService.java` | F-02 dead binding removed + forbidden-fallback comment corrected |
| `campaign/OutboundDialService.java` | F-02 stale comment corrected |
| `campaign/CampaignController.java` | OpenAPI operation description + 400/409 causes |

### Tests (8)

**New (4):** `ManualCallAttemptBoundaryTest` (28), `CampaignLifecycleInvariantsTest` (11),
`ManualAttemptBoundaryPostgresIntegrationTest` (7), plus one new case in
`CampaignOpenApiContractTest`.

**Existing, constructor plumbing only (4):** `CampaignExecutionOrchestratorSchedulerTest`,
`MissedCallPostgresIntegrationTest`, `PlayfileRetrySemanticsTest` (one added
argument each), `CampaignGovernanceHardeningPostgresIntegrationTest` (the
`CallAttemptService` construction, plus the resolver field it needed).

---

## 8. Tests added — 47

| Class | Tests | Covers |
|---|---|---|
| `ManualCallAttemptBoundaryTest` | **28** | B-1 happy path (2) · B-2 DID frozen (3) · B-3 schedule frozen (4) · B-4 attempt numbering (6) · B-5 terminal refused (4) · B-6 audience (3) · B-7 post-snapshot irrelevance + structural proof (2) · B-8 tenant isolation (4) |
| `CampaignLifecycleInvariantsTest` | **11** | F-03 requirements 15, 16 + the readiness behaviour that must not change |
| `ManualAttemptBoundaryPostgresIntegrationTest` | **7** | Real rows: frozen values persisted · refused requests write nothing · **real post-snapshot campaign edit cannot reach the attempt while a new execution picks it up** · cross-tenant execution and contact · terminal execution writes nothing |
| `CampaignOpenApiContractTest` | **+1** | Generated document carries the boundary semantics, the new 409 causes, and an unchanged request shape |

Against the required matrix: (1) B1-1/B1-2 · (2) B2-1, PGB-3 · (3) B3-1, PGB-1 ·
(4) B4-3/B4-4, PGB-2 · (5) B5-1..B5-4 · (6) PGB-6 · (7) PGB-5, PGB-5b ·
(8) B6-2 · (9) PGB-4 · (10) B4-5/B4-6 (same `RetryPolicySpec` instance the
orchestrator uses) · (11) unchanged `EXECUTION_TIMEZONE_INVALID` path in
`OutboundDialService` untouched and green · (12) B3-2/B3-4 via
`ExecutionScheduleCalculator` · (13) B4-1..B4-6 · (14) B2-1/B2-2/B2-3, PGB-1 ·
(15) F03-1 · (16) F03-2, F03-3, F03-4 · (17) 51 readiness tests unchanged.

**Two test-found corrections, both mine, no production defect:**
* `B7-1` was initially written to assert a *rejected* request succeeds. The
  service correctly refused it; the test was wrong and was rewritten.
* The PostgreSQL membership fixture initially omitted the `NOT NULL`
  `tenant_id` on `contact_group_members`. Fixture bug, corrected.

---

## 9. Focused test result

| Suite | Result |
|---|---|
| `ManualCallAttemptBoundaryTest` + `CampaignLifecycleInvariantsTest` + `ManualAttemptBoundaryPostgresIntegrationTest` | **46 / 0F / 0E / 0S** |
| `CampaignOpenApiContractTest` | **38 / 0F / 0E / 0S** |
| Campaign + per-type + architecture + boundary (74 classes) | **1058 / 0F / 0E / 0S** |

---

## 10. Full test result

```
.\mvnw.cmd -o -q clean test-compile
.\mvnw.cmd -o surefire:test -DforkCount=1 -DreuseForks=true

Tests run: 2038, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS          Total time: 10:28 min
```

1991 → 2038 = **+47**, exactly the new tests above. Nothing was removed.

Both skips are pre-existing and unrelated: `ObdApplicationTests` (`@Disabled` in
committed source at baseline) and 1 of 4 in the user-owned untracked
`LiveFreeSwitchRuntimeContractTest` (`assumeTrue` on the live FreeSWITCH
credential). **No `@Disabled` was added, no Surefire exclusion was added, no
existing test was weakened, deleted or skipped.**

Environment: 784 MB free of 5,996 MB against the user's 9 Docker containers. No
container was stopped, removed or reconfigured. The run completed in full — unlike
earlier phases, no host memory kill occurred.

---

## 11. Architecture result

`ArchitectureTest` PASS — **16 modules, 0 dependency cycles**.

`ExecutionScheduleCalculator` is a `@Component` inside the existing `campaign`
package with no dependency beyond `ScheduleSpec`. It adds no module, no new
infrastructure dependency, and no edge that could close a cycle. Campaign
orchestration remains separate from the voice-domain leaf services; the
telephony module was not touched.

Also verified: **no new `@Scheduled`** (the 17 pre-existing sites are unchanged —
`CampaignExecutionOrchestrator` kept its single `fixedDelay = 30000` tick), and no
new runtime configuration source.

---

## 12. Migration result

**No migration. V55 remains head; 54 files.**

None was needed: the boundary is enforced in application code. `attempt_number`,
`did_id` and `scheduled_at` already exist on `call_attempts`, and the frozen
values were already available on `campaign_execution_configurations`. No schema
change, no backfill, no index.

---

## 13. OpenAPI result

**No endpoint shape change.** Path, method, request fields (`contactId`, `didId`,
`attemptNumber`, `scheduledAt`) and response type are all unchanged; no new
endpoint, no new API version.

The *documented contract* did change — new 409 causes and a narrower 400 — so the
operation description and response annotations were updated, and the generated
document was inspected rather than assumed. `OAS-8B-1` asserts against the
generated spec that:

* the route still exists at the same path,
* the description states the snapshot is authoritative, that a campaign edit
  cannot change what the call dials, and that a terminal execution cannot be
  resurrected,
* the 409 description enumerates terminal execution / didId disagreement /
  attempt-number overrun,
* all four request fields still exist on the same schema.

37 → **38** OpenAPI tests, all green.

---

## 13.2 Scope verification

A case-sensitive scan of all 301 added production lines found **zero** forbidden
tokens: no `@Scheduled`, `HttpClient`, `WebClient`, `RestTemplate`, `Esl`,
`FreeSwitch`, `Sip`, `Rtp`, `cron`, `fixedDelay`, `Webhook`, `ReportPrivacy`,
`pseudonym`, `Kafka`, `Redis`, `ReentrantLock`, `integration_config`.

`CampaignEntity` / `campaignRepository` appear in added lines **only inside
Javadoc**, deliberately, explaining why they are absent. There is no
`Campaign → runtime config` edge, no `snapshot → live campaign` fallback, no
`client request → execution configuration override`, and no compatibility shim.

No unrelated formatting churn: five unused imports were removed only where my own
change orphaned them. Pre-existing unused imports elsewhere were left alone.

---

## 14. Working-tree safety statement

**No `git reset`, `git clean`, `git checkout --`, stash, stage, commit or push
was performed.** The repository is left uncommitted, exactly as required.

Verified preserved throughout:

* **User's FreeSWITCH/telephony work** — `EslClient`, `EslEvent`,
  `EslEventService`, `FreeSwitchOutboundDialer`, `EslProtocolTest`,
  `FakeEslServer`, the untracked `EslEventRuntimeContractTest` and
  `LiveFreeSwitchRuntimeContractTest`, and all `infra/freeswitch*`, `tools/`,
  `docs/freeswitch/`, `docs/LIVE-FREESWITCH-PHASE-*.md` entries. Untouched.
* **User's other work** — `.gitignore`, `backend/docs/future-hardening.md`,
  `docs/campaign-readiness.md`, `infra/.env.example`,
  `frontend/src/lib/api/contracts.ts`, `docs/frontend/`. Untouched.
* **VB-7C.3 work** — `V55__execution_snapshot_integration_config.sql`,
  `ExecutionSnapshotIntegrationConfig{,PostgresIntegration}Test`,
  `docs/VB-7C.3-CAMPAIGN-SNAPSHOT-HARDENING.md`, and the four campaign production
  files it modified. Untouched by VB-8B except where noted in §7 (none of those
  four were edited by this phase).
* **Concurrent frontend workstream** — a large `frontend/` changeset (dozens of
  tracked modifications plus untracked `docs/frontend/` and new components)
  **appeared during this phase**. It is not mine and was not touched; I wrote
  only under `backend/`. Flagging it because it landed mid-phase and is larger
  than anything recorded at VB-8A.
* **VB-8A audit** — `docs/VB-8A-CAMPAIGN-EXECUTION-SCHEDULER-AUDIT.md` left as-is.

---

## 15. Remaining risks / follow-ups

1. **`CallEligibility` mutable-campaign adapter is still there.**
   `CallEligibility:47` and `telephony/CallEligibilityService:78` expose
   `evaluate(CampaignEntity, String)` and are called from **nowhere** in
   `src/main` or `src/test`. It is dead, but it is a live API surface through
   which a future caller *could* take execution configuration from the campaign.
   Removing it means touching telephony, which VB-8B excludes. **Recommend a
   telephony-track cleanup.**
2. **Manual attempts can still front-run retry semantics in one narrow way.** An
   operator can legitimately create attempt *N* within the frozen ceiling even if
   attempt *N−1* has not failed yet. That is inherent to a manual-attempt feature
   and is now bounded by the policy rather than unbounded; whether it should also
   be gated on prior-attempt state is a **product decision, not a defect**.
3. **Whitelist mode is now tighter at creation than at dial.** Manual attempts
   require frozen-group membership even when `callOnWhitelistNumbers` is true,
   because the dial-time check is skipped for that mode. This makes manual
   attempts *consistently* with scheduler-created ones (which always select from
   the frozen group) rather than loosening them. If product intent is that a
   whitelist campaign's audience really is the tenant whitelist, that intent is
   now visible here and should be confirmed.
4. **The F-03 invariant is asserted, not enforced by the compiler or schema.** A
   change that widened editability would fail `CampaignLifecycleInvariantsTest`,
   but only if the suite runs. It is not a build-time constraint.
5. **Carried forward untouched:** whether report privacy should constrain the
   attempt-listing APIs (they expose `contactId` today), and whether
   aggregation/pseudonymisation belong in the privacy model. Neither was resolved.
   `integrationConfig` remains **frozen but unconsumed** — unchanged by VB-8B.

---

## 16. Explicit out-of-scope items

Not implemented, not started, and verified absent from the diff: webhook
delivery/signing/retries/status/dispatcher/worker/queue/secret management; report
generation/export/aggregation/pseudonymisation/privacy enforcement; scheduler
redesign, new scheduler, or cadence change; retry-policy redesign; daily
dial-limit or daily-attempt redesign; DND/DNC/whitelist redesign; routing or
capacity changes; FreeSWITCH/ESL/SIP/RTP/telephony changes; audio, IVR or
MISSED_CALL behaviour changes; campaign versioning or configuration history;
legacy execution compatibility; Kafka, Kubernetes, distributed locks, Redis;
new modules; migrations; REST endpoint shape changes; new API version.

**No live FreeSWITCH validation was performed and none is claimed.** No carrier
was contacted. No telephony code was modified.

---

## 17. Acceptance criteria

| Criterion | Met |
|---|---|
| F-01 fixed | ✅ §4 |
| Manual attempt cannot override frozen execution configuration | ✅ `CallAttemptService` no longer holds a `CampaignRepository`; structural test B7-2 |
| DID derived from / strictly validated against the frozen config | ✅ §4.2; B2-1..B2-3 |
| `scheduledAt` cannot bypass frozen scheduling rules | ✅ derived via the canonical calculator; B3-1, PGB-1 |
| `attemptNumber` cannot bypass frozen retry policy | ✅ B4-1..B4-6, PGB-2 |
| Non-runnable executions cannot create manual attempts | ✅ B5-1..B5-4, PGB-6 |
| Tenant isolation preserved | ✅ B8-1..B8-4, PGB-5, PGB-5b |
| Whitelist/audience enforcement correct | ✅ B6-1..B6-3 |
| DND/DNC/compliance not weakened | ✅ untouched; eligibility path unchanged |
| F-02 dead bindings removed / comments corrected | ✅ §5 |
| F-03 invariant explicitly tested | ✅ `CampaignLifecycleInvariantsTest`, 11 tests |
| Readiness behaviour not redesigned | ✅ behaviour identical; 51 tests unchanged |
| No new scheduler | ✅ 17 pre-existing `@Scheduled`, unchanged |
| No new migration | ✅ V55 remains head |
| No REST shape change | ✅ §13; asserted by OAS-8B-1 |
| No webhook / report implementation | ✅ |
| No retry-policy / daily-limit redesign | ✅ additive ceiling accessor only |
| No FreeSWITCH/ESL changes | ✅ |
| Full suite passes | ✅ 2038 / 0F / 0E / 2S |
| Architecture cycle-free | ✅ 16 / 0 |
| Report exists | ✅ this file |
| User-owned changes intact | ✅ §14 |
| No commit / no push | ✅ `HEAD` = `origin/main` = `3a89e5c` |

---

## 18. Final statement

> The execution boundary is now enforced, not merely documented.
>
> A manual operation may still identify **which** execution and **which**
> contact. It can no longer redefine that execution's **didId**, **when the call
> may happen**, **how many attempts are permitted**, **who may be called**, or
> **whether the execution is alive** — every one of those now comes from, or is
> checked against, the immutable snapshot, through the same canonical
> authorities the scheduler already used.
>
> The change adds no second configuration source, no compatibility fallback, no
> migration, no scheduler, and no new infrastructure. It removes two dead
> mutable-campaign bindings, corrects three comments that described invariants
> the code did not have (one of which advertised a forbidden legacy fallback),
> and turns a safety property that was previously emergent into an asserted
> invariant.
