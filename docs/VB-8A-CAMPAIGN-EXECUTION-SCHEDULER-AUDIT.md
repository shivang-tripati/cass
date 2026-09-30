# VB-8A — Campaign Execution & Scheduler Audit

**AUDIT ONLY. No production code, migration, test, API, scheduler, telephony or
infrastructure change was made by this phase.**

**Status: FINDINGS** (1 HIGH, 1 LOW-latent, 3 LOW/INFO) — see §15
**Baseline commit:** `3a89e5c` (HEAD = `origin/main`, divergence `0/0`)
**Phase record:** `docs/VB-8A-CAMPAIGN-EXECUTION-SCHEDULER-AUDIT.md`

---

## 1. Executive Summary

The question VB-8A exists to answer:

> For every campaign field or behaviour read after an execution is created, does
> the execution path use the governing frozen `CampaignConfigurationSnapshot`, or
> can mutable campaign state still affect execution?

**Answer: the campaign execution path is overwhelmingly snapshot-governed, with
one genuine and one latent exception.**

**CONFIRMED — the orchestrator, retry, dial, PLAYFILE, DTMF, IVR and
CONNECT_BY_AGENT paths all source execution configuration from the frozen
snapshot.** Every one of them resolves through
`CampaignRuntimeConfigResolver.CampaignRuntimeConfig`, which is built solely from
`CampaignConfigurationSnapshot`. No API on the snapshot, the resolver, or their
records returns a `CampaignEntity`. This is not a claim of intent — it was
traced call-by-call (§5, §6, §7).

**CONFIRMED — one real leak.** `CallAttemptService.createAttempt` is a live REST
endpoint that writes directly into an existing execution's attempt table using
**client-supplied** `didId`, `attemptNumber` and `scheduledAt`, without
consulting the snapshot and without any execution-lifecycle guard. It has
**zero test coverage**. Three frozen guarantees are not re-validated at dial
time, so this path bypasses them (§15 F-01, HIGH).

**CONFIRMED — `integrationConfig` is frozen but unconsumed.** `asIntegrationConfig()`
has no production consumer outside the resolver that populates it. This matches
the state VB-7C.3 documented, and is **not** a defect — but it does mean the
webhook/privacy freeze is currently unexercised by any runtime path (§10).

**CONFIRMED — the scheduler respects the snapshot for configuration.** Of the 7
steps on the single campaign scheduler tick, 6 source configuration from the
frozen snapshot and none reads campaign configuration. The one gate that reads
live campaign state — the readiness check at execution start — is safe *today*
only by an indirect invariant, and is recorded as a latent risk rather than a
defect (§15 F-03).

**Concurrent work:** the working tree carried the user's FreeSWITCH work and this
phase's own uncommitted VB-7C.3 work throughout. Nothing was stashed, reset,
restored, staged, or reformatted. No campaign execution/scheduler source file
changed during the audit, so no conclusion is invalidated by source drift.

---

## 2. Baseline

| Item | Value |
|---|---|
| HEAD | `3a89e5c` — "VB-7C.2: integrations + campaign configuration (configuration only)" |
| `origin/main` | `3a89e5c` |
| Divergence | `0 / 0` |
| Branch | `main...origin/main` (no ahead/behind markers) |
| Staged | nothing (`git diff --cached --stat` empty) |
| Stashes | empty |
| Parked/interference files | **NONE** (`*parked*`, `*vb8a*`, `*.orig`, `*.rej`, `*.bak` — none found) |
| Maven/Java concurrency | **NONE** at audit start (no `mvn`/`mvnw`/`java` process) |
| Full suite | see §2.1 |
| Architecture | **16 modules / 0 cycles**, `ArchitectureTest` PASS |
| Migration head | V55, 54 files (as left by VB-7C.3) |

### 2.1 Full test suite

Command: `.\mvnw.cmd -o surefire:test -DforkCount=1 -DreuseForks=true`
(bounded `MAVEN_OPTS=-Xmx1400m`, log written outside `target/`).

```
Tests run: 1991, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS          Total time: 13:01 min
```

**Matches the reference baseline exactly: 1991 / 0 / 0 / 2.** No regression, and
no test was altered to reach it.

| Skipped | Reason | Pre-existing? |
|---|---|---|
| `ObdApplicationTests` (1) | `@Disabled("Requires 'dev' profile with live Postgres and Redis; enable once a Testcontainers harness exists")` — present in the **committed** source at baseline | Yes |
| `LiveFreeSwitchRuntimeContractTest` (1 of 4) | `assumeTrue` on the live FreeSWITCH credential (L68); a **user-owned untracked** file, skipped because the credential is not set | Yes |

#### 2.1a Disclosure — one baseline attempt was invalidated

The first background baseline attempt was **INVALID and discarded, not reported**:

- It was started while nothing else ran, but partway through I mistakenly launched a
  second `mvnw` invocation against the same `target/surefire-reports` to sanity-check
  the build. That created **concurrent Maven contention**.
- I then killed the resulting `java` processes, which also terminated the baseline's
  surefire fork. That log never produced an aggregate line.

Per §5 of the brief, a suite that cannot be trusted because of concurrent build
contention is marked **INVALID / UNVERIFIED** rather than compared. That is what was
done: the contended log was discarded, all `java` processes were stopped, the
environment was confirmed clean (`java: 0`), and the suite was **re-run once, alone**.
The number above is from that single uncontended run.

Environment during the clean run: 649 MB free of 5,996 MB against **11** Docker
containers — the user's 9 (`obd-freeswitch`, `obd-fs-endpoint-1001`,
`obd-fs-endpoint-1002`, `auth-starter-{keycloak,postgres,redis,mailpit}`,
`obd-postgres`, `obd-redis`) plus Testcontainers' own `testcontainers-ryuk` reaper.
**No container was stopped, removed or reconfigured**, and the user's 9 were verified
intact afterwards. Earlier phases observed the host killing the JVM mid-suite under
memory pressure; that did not occur in the clean run, which completed in full.

### 2.2 Working-tree ownership

Verified with `git status --short`, `git status --branch --short`,
`git log -1 --oneline`, `git diff --stat`, `git diff --cached --stat`,
`git stash list`.

**Committed vs uncommitted matters for this audit.** `HEAD` (`3a89e5c`) does
**not** contain the VB-7C.3 snapshot hardening — it is uncommitted working-tree
state. Therefore:

> **Everything in §4–§13 was inspected against the working tree, not `3a89e5c`.**
> The `integrationConfig` field, the `asIntegrationConfig()` accessor, the V55
> column and the `CampaignRuntimeConfig.integrationConfig` component all exist
> only as uncommitted changes.

Records in §2.2a.

#### 2.2a Ownership ledger

**This phase changed:** `docs/VB-8A-CAMPAIGN-EXECUTION-SCHEDULER-AUDIT.md`
(this file) and nothing else.

**Pre-existing user-owned modifications, preserved untouched:** `.gitignore`,
`backend/docs/future-hardening.md`, `docs/campaign-readiness.md`,
`infra/.env.example`, and the telephony sources `EslClient.java`, `EslEvent.java`,
`EslEventService.java`, `FreeSwitchOutboundDialer.java`, `EslProtocolTest.java`,
`FakeEslServer.java`.

**Untracked user-owned files, never staged:** `backend/data/`, `tools/`,
`infra/docker-compose.freeswitch*.yml`, `infra/freeswitch/`,
`infra/freeswitch-endpoint/`, `docs/freeswitch/`,
`docs/LIVE-FREESWITCH-PHASE-{B,C,D,E,E1,E2,E3,E4,E5}.md`,
`EslEventRuntimeContractTest.java`, `LiveFreeSwitchRuntimeContractTest.java`.

**Untracked VB-7C.3 artifacts carried from the previous phase:** `V55__execution_snapshot_integration_config.sql`,
`ExecutionSnapshotIntegrationConfigTest.java`,
`ExecutionSnapshotIntegrationConfigPostgresIntegrationTest.java`,
`docs/VB-7C.3-CAMPAIGN-SNAPSHOT-HARDENING.md`.

**Source-stability check:** no file under `backend/src/main/java/com/shivang/obd/campaign/`
or the execution/dial/scheduler path was modified during this audit. `git status`
at audit end is identical to audit start. No conclusion below is affected by
source drift.

---

## 3. Actual Execution Graph

Discovered from source, not assumed. `CampaignExecutionOrchestrator` is the only
campaign-execution scheduler; it runs one tick of seven steps.

```
POST /api/campaigns/{id}/executions
  └─ CampaignController.execute
      └─ CampaignExecutionService.execute                     [API creation]
           ├─ CampaignReadinessService.evaluate(campaignId)   L65  ← LIVE campaign (PRE-execution, correct)
           └─ CampaignConfigurationService.createExecutionSnapshot(campaign)  L87
                └─ validateAndParse(typeConfig) + freezeIvrIfSelected
                └─ CampaignExecutionConfiguration.materialize(... toSnapshot(campaign, frozen) ...)
                     └─ CampaignConfigurationSnapshot(..., integrationConfig=validated)   ← FROZEN HERE

@Scheduled(fixedDelay = 30000)  CampaignExecutionOrchestrator.scheduledTick()   L490
  │
  ├─[1] start-requested-executions                                          L492
  │     executionRepository.findByStatusAndDeletedAtIsNull(REQUESTED)   ← platform-wide, unpaged
  │     └─ startExecutionAsSystem → doStartExecution                      L182
  │          ├─ campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull  L196  ← LIVE campaign (tenant-scoped)
  │          ├─ readinessService.evaluateForSystem(campaign.getId(), tenantId)  L201  ← LIVE readiness  [F-03]
  │          │     └─ evaluateResolved(CampaignEntity) → reads status, schedule, campaignType,
  │          │        contentMode, audioAssetId, ttsTemplateId, typeConfig, integrationConfig,
  │          │        contactGroupId, didId   ← ALL mutable campaign fields
  │          ├─ [deferred → return] | [else → execution = FAILED]         L210-219
  │          └─ createInitialAttempts(execution, campaign)                L228 / L240
  │               ├─ runtimeConfigResolver.resolve(execution)       L245  ← FROZEN
  │               ├─ resourceValidator.validateDid(config.didId()) L256  ← validity dynamic (correct)
  │               ├─ memberRepository.findByContactGroupId(config.contactGroupId())  L266  ← live membership (correct)
  │               ├─ calculateNextScheduledAt(config.schedule(), now)        L280  ← FROZEN window
  │               └─ new CallAttempt{ executionId, campaignId, tenantId, contactId,
  │                    didId=config.didId(), attemptNumber=1, scheduledAt, status=QUEUED }  L290-300
  │
  ├─[2] process-retries                                                    L500
  │     executionRepository.findByStatusAndDeletedAtIsNull(RUNNING)   ← platform-wide
  │     └─ processRetriesForExecution                                      L325
  │          ├─ runtimeConfigResolver.resolve(execution)           L341  ← FROZEN  ("live campaign is not consulted")
  │          ├─ retryPolicyService.evaluate(config.retryPolicy(), failureCode, attemptNumber, completedAt)  L349
  │          ├─ isContactStillValid(contactId, tenantId, config.contactGroupId())  L373
  │          ├─ isDidStillValid(config.didId(), tenantId)         L378
  │          ├─ adjustToScheduleWindow(config, decision.nextEligibleAt())  L386  ← FROZEN window
  │          └─ new CallAttempt{..., didId=config.didId(), attemptNumber=decision.nextAttemptNumber(),
  │               scheduledAt=nextScheduledAt, QUEUED }         L388-398
  │
  ├─[3] dial-due-attempts                                                  L502
  │     └─ OutboundDialService.processDueAttempts()                  L95
  │          attemptRepository.findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(QUEUED, now)  ← status+time ONLY
  │          └─ processAttempt(attempt)                                 L120
  │               ├─ campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull  L135  ← LIVE (existence + PAUSED)
  │               ├─ resolveExecutionConfig(attempt) → resolver       L156/503  ← FROZEN
  │               ├─ [PAUSED → requeue, no budget consumed]           L149
  │               ├─ dailyDialLimitService.resolveUsageDate(frozen schedule timezone)  L175  ← FROZEN tz
  │               ├─ buildDestinationNumber(attempt)                  L188/520  ← live contact, NO group predicate
  │               ├─ eligibilityService.evaluate(Context{tenantId, didId=frozen,
  │               │        contactGroupId=frozen, enforceWhitelist=frozen}, destinationNumber)  L201
  │               │     ├─ VoiceEligibilityService: platform/reseller/tenant blocklist + DNC + whitelist  ← LIVE (correct)
  │               │     └─ if (!enforceWhitelist) isNumberInCampaignContactGroup(frozen group, number)  ← frozen group, live membership
  │               ├─ voiceRoutingService.resolveRoute(tenantId, resellerId, destNumber,
  │               │        didId=frozen, campaignType=frozen, profileId=null → tenant default)  L236
  │               ├─ dailyDialLimitService.admit(..., effectiveLimit(frozen dailyDialLimit))    L285  ← FROZEN
  │               ├─ voiceCapacity.reserve(route.gatewayId(), tenantId)                        L299  ← live gateway state
  │               ├─ dailyAttemptSafety.admit(tenantId, contactId, frozen timezone, frozen maxDailyAttempts)  L333  ← FROZEN
  │               └─ dialer.dial(new OutboundDialRequest(attemptId, route.didE164Number,
  │                        destinationNumber, executionId, attemptNumber, GatewayRoute))        L310
  │                    └─ FreeSwitchOutboundDialer
  │
  ├─[4] pump-esl-events        → EslEventProcessor.ensureEventProcessing()      L504
  ├─[5] reconcile-executions    → reconcileExecution(executionId)              L506
  ├─[6] reconcile-stale-calls   → StaleCallReconciler.reconcile                 L517
  └─[7] terminate-missed-call-budgets → MissedCallExecutionService.terminateExpired  L528
        └─ runtimeConfigResolver.resolve(...) at 3 sites (L127, L265, L321)  ← FROZEN

POST /api/campaigns/{campaignId}/executions/{executionId}/attempts   [F-01]
  └─ CampaignController.createAttempt  (CampaignController L274)
      └─ CallAttemptService.createAttempt   L61
           ├─ findVisibleExecution(executionId, currentScope())          L63
           ├─ campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull   L77  ← LIVE, existence only
           ├─ contactRepository.findByIdAndTenantIdAndDeletedAtIsNull    L84
           ├─ resourceValidator.validateDid(request.didId(), tenantId)  L92
           └─ new CallAttempt{ ..., didId=request.didId(),
                attemptNumber=request.attemptNumber(),
                scheduledAt=request.scheduledAt() != null ? ... : Instant.now() }  L115-122
                ← NO snapshot consult · NO lifecycle guard · NO readiness gate
```

---

## 4. Governing Freeze Boundary (as actually implemented)

Answers to §6 of the brief, from source:

| Question | Answer | Evidence |
|---|---|---|
| **What is frozen?** | 23 fields: `campaignType`, `contactGroupId`, `didId`, `contentMode`, `audioAssetId`, `ttsTemplateId`, `scheduleStartDate`, `scheduleEndDate`, `dailyStartTime`, `dailyEndTime`, `timezone`, `allowedDaysOfWeek`, `holidayCalendarId`, `retryMaxAttempts`, `retryIntervalSeconds`, `retryStrategy`, `retryRules`, `maxDailyAttempts`, `maxCallDurationSeconds`, `typeConfig`, `callOnWhitelistNumbers`, `dailyDialLimit`, plus `integrationConfig` (V55, added VB-7C.3) | `CampaignConfigurationSnapshot` |
| **When is it frozen?** | Inside the execution-creation transaction, before the execution row is inserted | `CampaignConfigurationService.createExecutionSnapshot` L70-88, `@Transactional(REQUIRED)`, called from `CampaignExecutionService.execute` L87 |
| **Where persisted?** | Relational columns on `campaign_execution_configurations`; `integration_config` is JSONB (V55). Two JSONB columns total, both in use | `V44`, `V55__execution_snapshot_integration_config.sql` |
| **What validation before persistence?** | `CampaignTypeConfigValidator.validateAndParse` for `typeConfig`; `CampaignIntegrationConfig.fromJson` for `integrationConfig` (re-parsed, then `.toJson()` — canonical, not raw client JSON) | `createExecutionSnapshot` L72-74; `validatedIntegrationConfig` L212-222 |
| **What representation is stored?** | Canonical validated JSON for both payload columns; scalar columns verbatim (null preserved as null) | `CampaignConfigurationService.toSnapshot` |
| **What can mutate after execution creation?** | The `Campaign` row in full, plus all *resources* it references (DID, audio, TTS, queue, IVR tree) and all *runtime* state (contact membership, blocklists/DNC, gateway capacity). The snapshot itself is never updated — the execution's FK to it is `NOT NULL` | `CampaignExecutionConfiguration`; `campaignRepository` is write-only via `CampaignService` |
| **How exposed to runtime?** | `CampaignRuntimeConfigResolver.resolve(execution)` → `CampaignRuntimeConfig` record, built in `fromSnapshot(CampaignExecutionConfiguration)` | `CampaignRuntimeConfigResolver` L176-201 |
| **Can runtime get the mutable `CampaignEntity`?** | **No.** No API on `CampaignConfigurationSnapshot`, the resolver, or `CampaignRuntimeConfig` returns one | `ExecutionSnapshotIntegrationConfigTest` SNAP-IC-E2/E3/E4 (reflective, green) |
| **Can runtime get mutable campaign config directly?** | **Not through the resolver.** It can, however, by injecting `CampaignRepository` — and two schedulers do (§6, §15 F-01/F-03) | source trace |

**Governing rule (recorded in `CampaignConfigurationSnapshot`'s Javadoc):**
*Any campaign configuration consumed by execution-time runtime behaviour MUST be
captured in `CampaignConfigurationSnapshot` in the same phase that introduces the
first runtime consumer.*

---

## 5. Execution-Time Configuration Matrix

Classification per §8. **Every row is source-evidenced.**

| Configuration / Behaviour | Read location | Timing | Source | Frozen? | Post-exec mutation possible? | Classification |
|---|---|---|---|---|---|---|
| `campaignType` | `PlayfileExecutionService:180`, `DtmfExecutionService:279`, `OutboundDialService:241` | runtime | `config.campaignType()` | Yes | No | **FROZEN** |
| `contactGroupId` | `Orchestrator:246,373`, `OutboundDialService:206` | attempt creation + dial | `config.contactGroupId()` | Yes | No | **FROZEN** |
| `didId` | `Orchestrator:247,378,393`, `OutboundDialService:205,240,280` | attempt creation + dial | `config.didId()` | Yes | No | **FROZEN** |
| `contentMode` | `PlayfileExecutionService:187` | runtime | `config.contentMode()` | Yes | No | **FROZEN** |
| `audioAssetId` | `PlayfileExecutionService:187,202` | runtime | `config.audioAssetId()` | Yes (reference) | Reference: no. **Validity: yes** | **FROZEN** + **DERIVED-FROM-FROZEN** (`resourceValidator.validateAudio`) |
| `ttsTemplateId` | readiness `checkTtsTemplate:597`; TTS path | runtime | snapshot | Yes | Reference: no. Validity: yes | **FROZEN** |
| `typeConfig` (all types) | `CampaignRuntimeConfigResolver:191` `parseTypeConfig(s.getTypeConfig())` | runtime | snapshot | Yes | No | **FROZEN** |
| Schedule window (`dailyStartTime`/`dailyEndTime`/dates/days) | `Orchestrator:280` `calculateNextScheduledAt`, `:386` `adjustToScheduleWindow` | **attempt creation only** | `config.schedule()` | Yes | No | **FROZEN** (see F-01: not re-checked at dial) |
| `timezone` | `OutboundDialService:176,336` | runtime | `config.schedule().getTimezone()` | Yes | No | **FROZEN** |
| `retryMaxAttempts` / `IntervalSeconds` / `Strategy` / `retryRules` | `Orchestrator:350` `retryPolicyService.evaluate(config.retryPolicy(), …)` | runtime | snapshot | Yes | No | **FROZEN** |
| `maxDailyAttempts` | `OutboundDialService:338` | dial | `config.maxDailyAttempts()` | Yes | No | **FROZEN** |
| `maxCallDurationSeconds` | `PlayfileExecutionService:371` `MaxCallDurationPolicy.effectiveSeconds(config.maxCallDurationSeconds())` | runtime | snapshot | Yes | No | **FROZEN** |
| `dailyDialLimit` | `OutboundDialService:287` `effectiveLimit(config.dailyDialLimit())` | dial | snapshot | Yes | No | **FROZEN** |
| `callOnWhitelistNumbers` | `OutboundDialService:207` | dial | `config.callOnWhitelistNumbers()` | Yes | No | **FROZEN** |
| IVR tree (nodes/prompts) | `IvrCampaignConfig.snapshot()` → `IvrExecutionService` | runtime | `typeConfig` IVR payload captured at exec creation (`IvrSnapshotCapture`) | Yes | No | **FROZEN** |
| CONNECT_BY_AGENT `queueId` | `ConnectByAgentExecutionService:115` `resolveExecutionConfig(attempt)` → `AgentConnectRequest` | runtime | snapshot | Yes | No | **FROZEN** |
| MISSED_CALL ring budget | `MissedCallExecutionService:127,265,321` | runtime | snapshot (3 resolver sites) | Yes | No | **FROZEN** |
| **`integrationConfig`** (webhook + report privacy) | **no runtime consumer** | — | snapshot | **Frozen, UNCONSUMED** | No | **FROZEN / NOT CONSUMED** (§10) |
| **`campaign.status == PAUSED`** | `OutboundDialService:149` | dial | **LIVE campaign** | **No** | Yes — intentionally | **DELIBERATE-CONTROL-SIGNAL** (§8 permits a stronger category) |
| Campaign **existence** | `OutboundDialService:138`, `PlayfileExecutionService:163`, `DtmfExecutionService` | runtime | LIVE campaign | No | Yes — intentionally | **CONTROLLED** (fail-closed guard, never read for config) |
| **Readiness gate at execution start** | `Orchestrator:201` `evaluateForSystem` | scheduler | **LIVE campaign** | **No** | Yes | **INDIRECT-SAFE** — latent risk, F-03 |
| DND / DNC / blocklist / whitelist | `VoiceEligibilityService:63,77` via `CallEligibilityService:55` | dial | **LIVE external state** | No | Yes — **correct** | **RUNTIME-EXTERNAL** (§14) |
| Contact group **membership** | `Orchestrator:266`, `CallEligibilityService:isNumberInCampaignContactGroup` | attempt creation + dial | **LIVE** | No | Yes — **correct** | **RUNTIME-EXTERNAL** (VB-6B.1 Model A: *which* group frozen, *who* is in it live) |
| DID / audio / TTS / queue **validity** | `CampaignResourceValidationService` | attempt creation + dial | **LIVE** | No | Yes — **correct** | **RUNTIME-EXTERNAL** (VB-6A snapshot-vs-resource rule) |
| Routing profile | `OutboundDialService:242` `profileId = null` | dial | **tenant default**, not campaign | n/a | n/a | **TENANT-CONFIG** (no campaign-level routing config exists) |
| Gateway route + capacity | `voiceRoutingService:236`, `voiceCapacity.reserve:299` | dial | **live gateway state** | n/a | n/a | **RUNTIME-EXTERNAL** |
| Tenant context | `execution.getTenantId()` at every step | all | execution row | Yes | No | **EXECUTION-IMMUTABLE** |
| `attempt.didId` (injected) | `CallAttemptService:120` | attempt creation | **CLIENT REQUEST** | **No** | Yes | **MUTABLE-CLIENT-SUPPLIED — F-01** |
| `attempt.attemptNumber` (injected) | `CallAttemptService:121` | attempt creation | **CLIENT REQUEST** | **No** | Yes | **MUTABLE-CLIENT-SUPPLIED — F-01** |
| `attempt.scheduledAt` (injected) | `CallAttemptService:122` | attempt creation | **CLIENT REQUEST** | **No** | Yes | **MUTABLE-CLIENT-SUPPLIED — F-01** |

**Tally:** Frozen 18 · Derived-from-frozen 1 · Execution-immutable 1 ·
Deliberate control signal 1 · Controlled existence guard 1 · Indirect-safe gate 1 ·
Runtime-external 6 · Tenant/global config 2 · **Client-supplied leaks 3 (one root cause, F-01)**.

---

## 6. Scheduler Audit

`CampaignExecutionOrchestrator.scheduledTick()` — `@Scheduled(fixedDelay = 30000)`,
one scheduler, seven steps, each in its own failure boundary (`runStep` L538).

| # | Step | Loads `CampaignEntity`? | Resolves frozen config? | Mutable config read? |
|---|---|---|---|---|
| 1 | start-requested-executions | **Yes** L196 | Yes L245 | Yes — **readiness gate L201** (F-03) + existence |
| 2 | process-retries | No | Yes L341 | **No** — comment L339: *"The live campaign is not consulted"* |
| 3 | dial-due-attempts | **Yes** L135 (delegated) | Yes L156 | Yes — existence + **PAUSED** only |
| 4 | pump-esl-events | No | n/a | No |
| 5 | reconcile-executions | No | n/a | No |
| 6 | reconcile-stale-calls | No | n/a | No |
| 7 | terminate-missed-call-budgets | No | Yes (3 sites) | No |

**Does the scheduler re-read campaign configuration?** No, in every step that
actually *executes* configuration. Step 1's readiness gate is the one exception,
and it gates *whether to start*, not *what to run* — see F-03 for why this is
currently safe.

**Does it recompute values that should have been frozen?** No. All of
`calculateNextScheduledAt` / `adjustToScheduleWindow` operate on
`config.schedule()` from the snapshot.

**Other schedulers:** `StaleCallReconciler`, `DtmfTimeoutScheduler`,
`AgentConnectTimeoutScheduler`, `AcdMaintenanceScheduler`,
`InboundAcdRetryScheduler`, `VoiceCapacityServiceImpl`,
`AgentStaleReservationReconciler`, `EslEventScheduler`,
`SecurityCleanupScheduler` — **none** reference `CampaignEntity` or
`CampaignRepository`. CONFIRMED by exhaustive grep of `src/main`.

**Query scope (F-04):** `findByStatusAndDeletedAtIsNull(status)` and
`findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(status, time)` are
**platform-wide, unpaged and unbounded** — no tenant predicate, no `LIMIT`, no
batch chunking. Tenant isolation is nevertheless preserved because every
downstream step re-derives `tenantId` from the row and uses tenant-scoped
lookups (§13). This is a scalability and long-transaction concern, not a freeze
violation.

---

## 7. CallAttempt Audit

### 7.1 Field provenance — orchestrator-created attempts

| Source | Transformation | Destination | Mutability |
|---|---|---|---|
| snapshot | none | `attempt.didId` | FROZEN |
| snapshot `contactGroupId` | live membership lookup → contact IDs | `attempt.contactId` | audience group FROZEN, membership LIVE |
| literal `1` | none | `attempt.attemptNumber` | constant |
| snapshot `schedule` | `calculateNextScheduledAt(schedule, now)` | `attempt.scheduledAt` | **DERIVED-FROM-FROZEN** |
| snapshot `campaignId`/`tenantId` | none | `attempt.campaignId`/`tenantId` | EXECUTION-IMMUTABLE |
| execution status | none | `CallAttemptStatus.QUEUED` | — |

Retry attempts (`processRetriesForExecution` L388-398): `attemptNumber` and
`scheduledAt` are **derived from frozen** `config.retryPolicy()` +
`adjustToScheduleWindow(config, …)`; `didId` from `config.didId()`.
CONFIRMED FROZEN/DERIVED.

Retry metadata is frozen in the sense that the *policy* is; per-attempt counters
(`attemptNumber`, `completedAt`) live on the attempt row, which is correct.

### 7.2 Field provenance — client-created attempts

`CallAttemptService.createAttempt` L115-122 — see §11 F-01. `didId`,
`attemptNumber`, `scheduledAt` all **CLIENT REQUEST**. No transformation, no
snapshot consultation.

---

## 8. Retry / Safety / Eligibility Audit

### 8.1 Retry — CONFIRMED FROZEN

`retryPolicyService.evaluate(config.retryPolicy(), failedAttempt.getFailureCode(),
failedAttempt.getAttemptNumber(), failedAttempt.getCompletedAt())` — the policy
comes from the snapshot. `RetryPolicyService` is a pure function of its inputs
(no counters, no I/O), and permanence classification is its step 3. The
orchestrator's dead duplicate predicate was deliberately removed (comment L405-417).

> **Answer to the critical question:** if campaign retry configuration changes
> after execution creation, the existing execution uses its **frozen** policy.
> CONFIRMED by `RetryPolicySnapshotPostgresIntegrationTest` (7 tests).

**Exception:** the manual API (F-01) can create attempts with arbitrary attempt
numbers, which the retry policy never evaluates — it only *decides* whether to
create the *next* attempt.

### 8.2 Daily dial limit (VB-6C) — CONFIRMED FROZEN

`dailyDialLimitService.admit(tenantId, contactId, actualOutboundDidId, usageDate,
effectiveLimit(config.dailyDialLimit()))` L285-287. Bucket day computed in the
**frozen timezone** (L175-176); an invalid zone fails deterministically with no
JVM/UTC fallback. Rejection is terminal for the day, never retried same-day.
Admission is after routing (so the *actual* DNID is bucketed) and before capacity
reservation. Verified by `DailyDialLimitSnapshotPostgresIntegrationTest` (5) and
`VoiceBlastDailyDialLimitPostgresIntegrationTest` (12).

### 8.3 Daily attempt safety (VB-6D.3) — CONFIRMED FROZEN

`dailyAttemptSafety.admit(tenantId, contactId, frozen timezone, config.maxDailyAttempts())`
L333-338, placed immediately before the dial is issued. Consumption is a single
conditional UPDATE — no reservation, so nothing can strand.

### 8.4 Capacity — RUNTIME-EXTERNAL (correct)

`voiceCapacity.reserve(gatewayId, tenantId)` L299. Deliberately live: a
transient capacity shortage must requeue, not be frozen.

### 8.5 DND / eligibility — RUNTIME-EXTERNAL (correct, and correctly separated)

`VoiceEligibilityService` evaluates, at dial time: platform blocklist → reseller
blocklist → tenant blocklist/DNC → DID compatibility. These are **live external
compliance state** and must stay current — freezing them would defeat the
purpose. §14's distinction applies directly: this is *not* campaign
configuration.

The one **campaign** input to eligibility is `enforceWhitelist`, taken from the
**frozen** `config.callOnWhitelistNumbers()` (L207). Correctly separated.

Campaign targeting (`NOT_IN_CAMPAIGN_TARGETS`) uses the **frozen** group with
**live** membership, matched by normalised phone number
(`CallEligibilityService:61-65`). Note: this check is **skipped** when
whitelist enforcement is on — relevant to F-01.

### 8.6 Duplicate prevention — EXECUTION-IMMUTABLE

DB unique index on `(execution_id, contact_id, attempt_number)` makes duplicate
creation physically impossible; cheap pre-checks exist at `Orchestrator:285` and
`:367`.

---

## 9. Routing / Gateway Audit

```
Execution → frozen didId + frozen campaignType + tenant default profile
          → voiceRoutingService.resolveRoute(tenantId, resellerId, destinationNumber, …)
          → VoiceRoutingDecision (selectedRoute / routeType / rejectedRoutes)
          → dailyDialLimitService.admit(…, selectedRoute.didId())     ← ACTUAL DNID, post-routing
          → voiceCapacity.reserve(selectedRoute.gatewayId(), tenantId)
          → OutboundDialRequest(attemptId, selectedRoute.didE164Number(), …, GatewayRoute)
          → FreeSwitchOutboundDialer
```

| Input | Source | Classification |
|---|---|---|
| Campaign DID | snapshot (`config.didId()`, with `attempt.getDidId()` only as fallback) | **FROZEN** |
| Campaign type (route hint) | snapshot (`config.campaignType().name()`) | **FROZEN** |
| Routing profile | `null` → tenant default (`OutboundDialService:242`) | **TENANT-CONFIG** — no campaign-level routing config exists in the model |
| Reseller | `tenantRepository.findById(tenantId).map(resellerId)` | TENANT-CONFIG |
| Selected route / gateway | live `VoiceRoutingService` | RUNTIME-EXTERNAL |
| Capacity | live `voiceCapacity` | RUNTIME-EXTERNAL |
| Outbound DNID | `selectedRoute.didE164Number()` — may substitute a profile-pinned DID | RUNTIME-EXTERNAL, deliberately post-routing so the DNID the provider sees is what gets bucketed |

**Tenant boundary:** route resolution is keyed on `attempt.getTenantId()` and
`resellerId`; capacity reservation is `(gatewayId, tenantId)`. No campaign field
can widen routing. CONFIRMED.

**No telephony code was read for behaviour assessment beyond the call sites above,
and none was modified.**

---

## 10. Integration Configuration Audit

> ### `SNAPSHOT NOT CONSUMED`

Exhaustive grep of `src/main` for `asIntegrationConfig` and
`CampaignIntegrationConfig`:

| Site | Nature |
|---|---|
| `CampaignConfigurationSnapshot:310` | the accessor definition |
| `CampaignRuntimeConfigResolver:200` | populates the record component from the frozen row |
| `CampaignRuntimeConfigResolver:154` | the record's own accessor definition |
| `CampaignMapper:126-169` | REST read/serialise |
| `CampaignService:518-527` | write-time validation |
| `CampaignReadinessService:356` | write-time readiness |
| `CreateCampaignRequest` / `UpdateCampaignRequest` / `CampaignResponse` | DTOs |

**There is no production call site that reads the frozen integration
configuration during execution.** No scheduler step, no dial-path decision, no
attempt creation, no execution service touches it.

**This is not a defect.** It is precisely the state VB-7C.3 documented and VB-8A
was asked to confirm. No webhook delivery, report generation, signing, or privacy
enforcement exists in `src/main` (verified: zero HTTP clients of any kind).

**Mutable fallbacks in execution paths:** none. No execution path reads
`campaign.getIntegrationConfig()`. So the classification is clean `NOT CONSUMED`,
**not** `MIXED`.

**Risk carried forward:** the freeze is currently unexercised. The first delivery
or reporting phase must consume `CampaignRuntimeConfig.asIntegrationConfig()`;
the structural guarantee (no `CampaignEntity` on the resolver) makes the wrong
choice awkward but not impossible.

---

## 11. Type Configuration Audit

> ### `SNAPSHOT CONSUMED` — CONFIRMED

Single authoritative parse point:

`CampaignRuntimeConfigResolver.parseTypeConfig(s.getCampaignType(), s.getTypeConfig())`
(`fromSnapshot`, L191) → `CampaignTypeConfig.fromTypeConfig(type, typeConfig)`.

| Type | Runtime consumer | Source | Classification |
|---|---|---|---|
| **PLAYFILE** | `PlayfileExecutionService:173-187` — `config.campaignType() != PLAYFILE` → no playback; `config.contentMode() != AUDIO` → `PLAYBACK_CONFIG_INVALID`; `config.audioAssetId()` → `validateAudio` | snapshot | **FROZEN** |
| **DTMF** | `DtmfExecutionService:260,279` — type guard + `asIvr()`/single-level config from snapshot | snapshot | **FROZEN** |
| **CONNECT_BY_AGENT** | `ConnectByAgentExecutionService:115` — `resolveExecutionConfig(attempt)`; `queueId`/`ringSeconds` flow into `AgentConnectRequest` from the snapshot's `ConnectByAgentCampaignConfig` | snapshot | **FROZEN** |
| **MISSED_CALL** | `MissedCallExecutionService:127,265,321` — 3 resolver sites | snapshot | **FROZEN** |
| **IVR** | `IvrCampaignConfig.snapshot()` returns the frozen tree; `IvrExecutionService` receives `IvrExecutionSnapshot` only | snapshot (`typeConfig` IVR payload, captured at exec creation) | **FROZEN** |
| Future types | type-specific parsing is behind the exhaustive `CampaignTypeConfig` seam; a new type must satisfy `CampaignType.playsMedia()` and the readiness `switch` | — | Structurally enforced |

**Type isolation is preserved.** `CampaignTypeConfig.fromTypeConfig` rejects unknown
root keys, so no type can carry another's payload, and `integrationConfig` was
deliberately kept in its own column rather than smuggled into `typeConfig`.

---

## 12. Mutation-After-Execution Analysis

```
T0 Campaign configuration exists.
T1 Execution created ──► CampaignConfigurationSnapshot frozen.
T2 Campaign configuration changes (only possible in DRAFT — see below).
T3 Scheduler processes the execution.
```

**Does the architecture guarantee the freeze invariant?**

For the configuration that is actually **executed** — **YES**, and it is proven
by test, not merely by design. Proven per-field:

| Frozen value | Proof |
|---|---|
| `didId`, `contactGroupId` | `CampaignConfigurationSnapshotPostgresIntegrationTest` CFG-B/C — "campaign edit affects only executions created after the edit"; "the running execution stays on its snapshot" |
| `typeConfig` (PLAYFILE/DTMF/MISSED_CALL) | `CampaignTypeConfigTest` (9), `MissedCallPostgresIntegrationTest` (15), `CampaignConfigurationSnapshotPostgresIntegrationTest` CFG-A2 |
| retry policy | `RetryPolicySnapshotPostgresIntegrationTest` (7) |
| `dailyDialLimit` | `DailyDialLimitSnapshotPostgresIntegrationTest` (5) |
| `maxCallDurationSeconds` | `MaxCallDurationConfigurationTest$SnapshotFreeze` |
| IVR tree | `IvrExecutionSnapshotTest$Immutability` (2), `IvrFromCampaignServiceTest$CampaignRepointing` |
| `integrationConfig` (webhook/privacy) | `ExecutionSnapshotIntegrationConfigPostgresIntegrationTest` — 14 mutation-isolation assertions, all green |

**Not proven by test:**

| Gap | Why it matters |
|---|---|
| The **readiness gate** (F-03) evaluated against a post-T1 campaign edit | `CampaignExecutionOrchestratorSchedulerTest` **mocks** `CampaignReadinessService` (L65) and stubs `evaluateForSystem` — it proves the deferred-vs-failed *branch*, never which campaign state is consulted |
| `CallAttemptService.createAttempt` (F-01) | **Zero tests reference `createAttempt`** — the endpoint is entirely uncovered |

**Verdict:** the invariant is **PROVEN for the configuration that executes**, and
**UNPROVEN at the two boundaries where live campaign state still reaches
execution** — both of which are findings below.

---

## 13. Tenant Isolation

### 13.1 Reused VB-7C.3 evidence

| Property | Proof | Status |
|---|---|---|
| Foreign execution → another tenant's snapshot fails closed | `ExecutionSnapshotIntegrationConfigPostgresIntegrationTest` SNAP-ICPG-E1 (`ExecutionConfigurationMissingException`) | CONFIRMED |
| Cross-tenant campaign lookup returns empty | same class, SNAP-ICPG-E2 (`findByIdAndTenantIdAndDeletedAtIsNull` → empty) | CONFIRMED |
| Resolver cannot yield a `CampaignEntity` | `ExecutionSnapshotIntegrationConfigTest` SNAP-IC-E2/E3/E4 | CONFIRMED |

### 13.2 Execution-path audit

Traced `tenant → campaign → execution → snapshot → runtime config → contact → gateway`.

| Step | Predicate | Status |
|---|---|---|
| `CampaignExecutionOrchestrator.doStartExecution` | `campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull(campaignId, execution.getTenantId())` L196 — *"Never widened to a caller scope: the execution row is the authority"* | CONFIRMED |
| `OutboundDialService.processAttempt` | `findByIdAndTenantIdAndDeletedAtIsNull(attempt.getCampaignId(), attempt.getTenantId())` L135 | CONFIRMED |
| `OutboundDialService.resolveExecutionConfig` | `executionRepository.findByIdAndDeletedAtIsNull` → `resolver.resolve` → `findByIdAndTenantId` **inside** the resolver | CONFIRMED — a cross-tenant execution→snapshot pairing fails closed (proved by SNAP-ICPG-E1) |
| `PlayfileExecutionService` / `DtmfExecutionService` | `findByIdAndTenantIdAndDeletedAtIsNull(attempt.getCampaignId(), attempt.getTenantId())` | CONFIRMED |
| `buildDestinationNumber` | `contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(contactId, attempt.getTenantId())` | CONFIRMED |
| Eligibility / DND | keyed on `attempt.getTenantId()` | CONFIRMED |
| Routing | `voiceRoutingService.resolveRoute(attempt.getTenantId(), resellerId, …)` | CONFIRMED |
| Capacity | `voiceCapacity.reserve(gatewayId, attempt.getTenantId())` | CONFIRMED |
| `CallAttemptService.createAttempt` | `findVisibleExecution(executionId, currentScope())` then `execution.getTenantId()`; `campaignRepository.findByIdAndTenantIdAndDeletedAtIsNull` | CONFIRMED — tenant-safe, but see F-01 for the config gap |
| Scheduler selection queries | `findByStatusAndDeletedAtIsNull(status)` — **no tenant predicate** | By design: a platform scheduler must serve all tenants. Every downstream step re-derives `tenantId` from the row before any tenant-scoped access. **No cross-tenant read found.** |

**Conclusion: tenant isolation is intact across the whole execution path.** The
unscoped scheduler queries are a scope-and-performance concern (F-04), never a
leak.

---

## 14. Test Coverage Inventory

| Category | Tests exist | Invariant proven | Proves frozen-vs-mutable? |
|---|---|---|---|
| Snapshot creation | Yes — `CampaignConfigurationSnapshotPostgresIntegrationTest` (7), `CampaignConfigurationSnapshotTest` (3) | Frozen fields match the campaign at creation; one snapshot per execution | Partially |
| Snapshot immutability | Yes — same class CFG-B/C, `MaxCallDurationConfigurationTest$SnapshotFreeze`, `IvrExecutionSnapshotTest$Immutability` | Campaign edit after creation cannot change an existing snapshot | **YES** |
| Integration configuration | Yes — `ExecutionSnapshotIntegrationConfigTest` (20), `…PostgresIntegrationTest` (11), `CampaignIntegrationValidationTest` (16), `WebhookEventVocabularyTest` (9) | Canonical form, strict validation, null≠default, endpoint/event/privacy mutation isolation | **YES** |
| Scheduler | Yes — `CampaignExecutionOrchestratorSchedulerTest` (14) | Step isolation, one scheduler, no long transaction, paused→deferred, unrunnable→failed | **NO** — readiness service is mocked |
| Execution readiness | Yes — `CampaignReadinessServiceTest` (51), `CampaignResourceValidationPostgresIntegrationTest` (20) | Every readiness rule, parameterized over all `CampaignType.values()` | No (write-time concern) |
| Due-execution selection | **Partial** — `DialBatchContinuationPostgresIntegrationTest` (4) | Batch continuation; `CampaignExecutionOrchestratorSchedulerTest` TICK-5/6 | **NO** — no test proves the selection is snapshot-driven |
| `CallAttempt` creation | Orchestrator: yes. **Manual API: NO TESTS** | — | **NO** — F-01 untested |
| Retry | Yes — `RetryPolicySnapshotPostgresIntegrationTest` (7), `RetryPolicyModelTest`, `RetryPolicyValidatorTest`, `PlayfileRetrySemanticsTest` | Policy resolution, permanence, delay arithmetic, frozen sourcing | **YES** |
| Daily safety | Yes — `DailyDialLimitSnapshotPostgresIntegrationTest` (5), `VoiceBlastDailyDialLimitPostgresIntegrationTest` (12), `DailyAttemptConcurrencyPostgresIntegrationTest` (11), `DailyAttemptSafetyServiceTest` | Frozen limits, DNID bucket, concurrency, separation from VB-6C | **YES** |
| Routing | Yes — `OutboundDialServiceRoutingTest` (19), `OutboundDialServicePausedTest` (5) | Route selection, failover, permanent-vs-capacity classification, pause | Partially |
| DND | Yes — via `CallEligibilityService`; `VoiceEligibilityService` | Live blocklist/DNC evaluation | N/A (external state) |
| Tenant isolation | Yes — `ExecutionSnapshotIntegrationConfig…` SNAP-ICPG-E1/E2, `CampaignGovernanceHardeningPostgresIntegrationTest`, `ContactIdentity*` | Cross-tenant resolution fails closed | **YES** |
| Runtime configuration | Yes — `ExecutionSnapshotIntegrationConfigTest` SNAP-IC-E | No `CampaignEntity` reachable from runtime config/snapshot | **YES** |
| OpenAPI | Yes — `CampaignOpenApiContractTest` (37) | Snapshot never leaks into the public document | **YES** |

**Coverage gaps identified:** manual attempt creation (F-01, zero tests);
readiness-gate-vs-snapshot (F-03, mocked); due-selection snapshot sourcing (no
direct test).

---

## 15. Findings

### F-01 — HIGH — Manual attempt creation bypasses the frozen configuration

| | |
|---|---|
| **Location** | `CallAttemptService.createAttempt` L61-125; route `CampaignController` L274 `POST /{campaignId}/executions/{executionId}/attempts` |
| **Observed** | A caller with `CAMPAIGN_EXECUTE` creates a `CallAttempt` inside an existing execution from entirely **client-supplied** `didId` (`@NotNull`), `attemptNumber` (`@NotNull @Positive` — no upper bound) and `scheduledAt` (nullable → `Instant.now()`). The service **never injects `CampaignRuntimeConfigResolver`** and consults nothing frozen. The loaded `CampaignEntity` (L77) is used only for an existence check — the comment at L75 *"Validate contact exists and belongs to the campaign's contact group"* is **stale**: no group validation is performed there. There is **no execution-status guard** (attempts can be injected into `REQUESTED`, `COMPLETED` or `FAILED` executions) and **no readiness gate**. |
| **Expected** | Attempt creation for an existing execution should derive `didId`, attempt numbering and `scheduledAt` from the execution's frozen snapshot — or at minimum re-validate them against it — and should refuse a non-runnable execution. |
| **Evidence** | Three independently verified frozen guarantees are **not re-checked at dial time**:<br>1. **Calling-hours window** — `adjustToScheduleWindow`/`calculateNextScheduledAt` are called *only* inside `CampaignExecutionOrchestrator` (L280, L386). `OutboundDialService.processAttempt` uses only the frozen **timezone** (L176, L336), never `dailyStartTime`/`dailyEndTime`. `processDueAttempts` selects on status + `scheduledAt` alone.<br>2. **Retry governance** — the frozen `retryPolicy` is consulted only in `processRetriesForExecution` (L349). An injected `attemptNumber` is never evaluated against `retryMaxAttempts`/`retryRules`.<br>3. **Audience** — the dial-time `isNumberInCampaignContactGroup` check (frozen group, live membership) is **skipped entirely when the frozen campaign sets `callOnWhitelistNumbers = true`** (`CallEligibilityService` L61 `if (!enforceWhitelist)`).<br>Corroborating: `attempt.didId` is persisted from the request, so the attempt row can disagree with the frozen DID. The dial path prefers the frozen DID (L205/240), which limits but does not eliminate the divergence.<br>**Zero tests reference `createAttempt`** — the endpoint is entirely uncovered. |
| **Impact** | An authorised operator action silently changes what a frozen execution does: dialling outside the frozen calling hours, dialling beyond the frozen retry budget, or dialling outside the frozen audience when whitelist enforcement is on. Mitigating: daily dial limit, daily attempt ceiling, DND/blocklist/whitelist compliance and the frozen DID are all still enforced at dial time; the caller needs `CAMPAIGN_EXECUTE`. |
| **Likely remediation (NOT implemented)** | Smallest change: in `createAttempt`, resolve the execution's frozen config and either (a) derive `didId`/`scheduledAt` from it and reject an `attemptNumber` above the frozen `retryMaxAttempts`, or (b) re-validate the client values against the frozen snapshot; plus reject non-`RUNNING`/`REQUESTED` executions. Align the stale comment at L75. Add tests. No schema change, no API shape change. |

### F-02 — LOW — Dead mutable-campaign bindings that imply a configuration read that never happens

| | |
|---|---|
| **Location** | `PlayfileExecutionService:168`, `DtmfExecutionService:256` (`CampaignEntity liveCampaign = campaignOpt.get();` — assigned, **never used**); `CallAttemptService:77` (`campaign` used only for `.orElseThrow`); `CallEligibility:47` + `telephony/CallEligibilityService:78` (mutable-campaign adapter, **never called** in `src/main` or `src/test`); stale comment at `OutboundDialService:190-191` claiming `buildDestinationNumber` checks group membership, which `buildDestinationService:520` and its Javadoc L511-519 deliberately do **not** do (the check lives in `CallEligibilityService`) |
| **Observed** | Four locations where a mutable `CampaignEntity` is bound but never read for configuration, plus one never-called mutable-campaign adapter |
| **Expected** | No binding that suggests a live configuration read where none occurs |
| **Evidence** | `Select-String 'liveCampaign'` returns exactly one hit per file (the declaration). The compatibility adapter has no call site. |
| **Impact** | No functional defect — the freeze is intact. Misleading to a future maintainer: the names suggest a live read that the design explicitly forbids, inviting exactly the regression VB-7C.3's rule guards against. F-01's stale comment is the same defect class and did mask a real gap. |
| **Likely remediation (NOT implemented)** | Delete the dead bindings or replace with a boolean existence check; delete or explicitly deprecate the unused adapter; correct the `OutboundDialService` comment. |

### F-03 — LOW (latent risk) — The execution start-gate reads live campaign state; safety is emergent, not explicit

| | |
|---|---|
| **Location** | `CampaignExecutionOrchestrator.doStartExecution` L201-202: `readinessService.evaluateForSystem(campaign.getId(), tenantId)` → `CampaignReadinessService.evaluateResolved(CampaignEntity)` (L108), which reads `getStatus()`, `getSchedule()`, `getCampaignType()`, `getContentMode()`, `getAudioAssetId()`, `getTtsTemplateId()`, `getTypeConfig()`, `getIntegrationConfig()`, `getContactGroupId()`, `getDidId()` |
| **Observed** | The gate deciding whether an execution **starts at all** is evaluated against the **live mutable campaign**, while the configuration the execution then runs is **frozen** (`createInitialAttempts` L245). |
| **Expected** | A start-gate consistent with the freeze would evaluate the frozen configuration. |
| **Evidence — why this is NOT a defect today.** The gate is provably safe by construction, via an *indirect* invariant:<br>• `CampaignLifecyclePolicy.EDITABLE_STATUSES = { DRAFT }` — only `DRAFT` is editable, so **no configuration can change** unless the campaign is `DRAFT`.<br>• `checkLifecycleState` emits `CAMPAIGN_NOT_EXECUTABLE_STATE` for any status other than `SCHEDULED`/`RUNNING` — and `DRAFT` qualifies.<br>• `isDeferredRatherThanFailed` (L680-683) defers whenever that single code is present.<br>⇒ Any post-creation configuration edit necessarily lands the campaign in `DRAFT`, so the execution is **deferred, never permanently failed**.<br>⇒ Conversely, any *non-deferred* readiness failure requires an executable (`SCHEDULED`/`RUNNING`) campaign whose configuration cannot have been edited — so such failures are purely **resource validity**, which VB-6A deliberately keeps dynamic.<br>Confirmed untested: `CampaignExecutionOrchestratorSchedulerTest` **mocks** `CampaignReadinessService` (L65). |
| **Impact** | None today. **Latent:** if editability is ever widened to `SCHEDULED`/`RUNNING` — or a new transition allows editing while executable — a post-creation edit could **permanently FAIL** an execution whose frozen snapshot was runnable (`doStartExecution` L215-218 sets `FAILED` + `completedAt`, with no recovery path), and a "fixing" edit could let a frozen-unrunnable snapshot start and dial an invalid frozen asset. |
| **Likely remediation (NOT implemented)** | Do not change behaviour now. Record the coupling explicitly — e.g. a test asserting *no* campaign status is both editable and executable, and a comment at `doStartExecution` L201 naming `EDITABLE_STATUSES` as the reason live readiness is safe. Optionally add an assertion-level readiness check over frozen config as defence in depth. |

### F-04 — INFO — Scheduler selection queries are platform-wide, unpaged and unbounded

| | |
|---|---|
| **Location** | `CampaignExecutionRepository.findByStatusAndDeletedAtIsNull(status)`; `CallAttemptRepository.findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(status, time)` |
| **Observed** | No tenant predicate, no `LIMIT`, no batching. Every REQUESTED execution and every due attempt platform-wide is materialised into memory each 30 s tick. |
| **Expected** | Platform-wide scope is correct for a system scheduler; bounded batches are normal practice. |
| **Evidence** | Repository signatures; `Orchestrator` L493-494, L507-508, `OutboundDialService` L96-97. |
| **Impact** | No freeze or isolation violation — tenant scoping is re-derived per row (§13.2). Scalability and long-transaction/lock risk under load. Plausibly a contributor to the host memory fragility recorded in §2.1. |
| **Likely remediation (NOT implemented)** | Page or chunk the two selection queries if measured load warrants. No behaviour change intended. |

### F-05 — INFO — `integrationConfig` freeze is unconsumed

| | |
|---|---|
| **Location** | `CampaignConfigurationSnapshot:310`, `CampaignRuntimeConfigResolver:154,200` |
| **Observed** | Zero production consumers of `asIntegrationConfig()` outside the resolver that populates it (§10). |
| **Expected** | Expected and documented — VB-7C.3 froze the configuration before any consumer existed, by design. |
| **Evidence** | Exhaustive `src/main` grep; zero HTTP clients of any kind exist in `src/main`. |
| **Impact** | None today. The freeze is unexercised, so it has no runtime proof beyond the 31 tests VB-7C.3 added. |
| **Likely remediation (NOT implemented)** | None for this phase. The first delivery/reporting phase must consume `asIntegrationConfig()`. |

---

## 16. Product Decisions Carried Forward

Preserved unresolved, as instructed. Neither is answered by existing product
requirements found during this audit.

1. **Should report privacy constrain the attempt-listing APIs?** The
   `listAttempts` / `getAttempt` endpoints (`CallAttemptService` L132/L161) return
   `contactId` and phone-derived data today. With `reportPrivacy = MASKED` now
   *frozen* per execution but *not enforced anywhere*, the privacy model is
   currently a stored preference with no consumer. Whether it must gate these
   endpoints is a product decision — note that `listAttempts` is a cross-execution
   read, so it has no single frozen config to consult and would need an explicit
   rule.

2. **Does aggregation or pseudonymisation belong in the privacy model?**
   `ReportPrivacy` has exactly two values, `FULL` and `MASKED`
   (`ReportPrivacy.java`), reusing `EslClient.maskNumber`'s last-4 convention.
   No aggregation or pseudonymisation exists. Whether either should is unresolved.

---

## 17. Scope Verification

This phase was audit-only. Verified by `git status` before and after.

| Category | Changes | Note |
|---|---|---|
| Production implementation | **0** | No `src/main` file added, modified or deleted |
| Migrations | **0** | No new migration; V55 (from VB-7C.3) left untouched |
| API / REST contracts | **0** | No controller, DTO or OpenAPI change |
| Scheduler | **0** | `CampaignExecutionOrchestrator` read only; no `@Scheduled` added, changed or removed (17 pre-existing sites unchanged) |
| Telephony / FreeSWITCH / ESL / SIP / RTP | **0** | Read-only at the call sites in §9; user-owned changes untouched |
| Tests | **0** | No test added, modified, disabled or skipped; the suite was **run**, not edited |
| Infrastructure | **0** | No container stopped, reconfigured or removed; the 9 user Docker containers were left running |
| Git operations | **0** | No stash, reset, restore, checkout, stage, commit or push |
| Documentation | **1** | `docs/VB-8A-CAMPAIGN-EXECUTION-SCHEDULER-AUDIT.md` (this file) |

**One difference from the ideal, stated plainly:** the audit document itself is a
write, which §24 requires. That is the only artefact.

**No `@Disabled` was added. No Surefire exclusion was added. No existing test was
weakened, deleted or skipped.** The 2 skipped tests in the suite
(`ObdApplicationTests`, and 1 of 4 in the user-owned untracked
`LiveFreeSwitchRuntimeContractTest`) are pre-existing and unrelated.

---

## 18. Recommendation

VB-8A is **AUDIT COMPLETE WITH FINDINGS**. Per §18, implementation must not begin
here. The smallest evidence-driven next steps, in order:

**VB-8B — EXECUTION CONFIGURATION BOUNDARY (smallest remediation phase)**
Scope, strictly limited to F-01 and F-02:

1. **F-01 (HIGH).** In `CallAttemptService.createAttempt`, stop trusting the
   client for execution-affecting values. Either derive `didId` and `scheduledAt`
   from the execution's frozen snapshot, or re-validate the supplied values
   against it; bound `attemptNumber` by the frozen retry policy; refuse a
   non-runnable execution. Correct the stale comment. Add the missing tests.
   *No schema change, no API shape change, no new endpoint.*
2. **F-02 (LOW).** Remove the four dead mutable-campaign bindings and the unused
   mutable-campaign adapter; correct the `OutboundDialService` comment.
3. **F-03 (LOW).** Add a test asserting no campaign status is simultaneously
   editable and executable, plus a comment at `doStartExecution` naming
   `EDITABLE_STATUSES` as the reason live readiness is currently safe. **Do not
   change the gate's behaviour** — it is correct today.

**Not recommended for a phase of its own:** F-04 (measure first) and F-05 (belongs
to the first delivery/reporting phase).

**If F-01 is judged out of scope for now**, VB-8A still must not proceed to
implementation of execution features until it is resolved: it is the only place
where the freeze boundary is genuinely bypassable today, and it is a live,
completely untested endpoint.

**Carried forward unchanged:** the two product decisions in §16, and the standing
rule that the first runtime consumer of any execution-affecting configuration
must capture it in the snapshot in the same phase.

---

### Final statement

> **VB-7C.3 froze the configuration. VB-8A established that execution honours the
> freeze** — across the orchestrator, retry, daily-safety, dial, PLAYFILE, DTMF,
> IVR, MISSED_CALL and CONNECT_BY_AGENT paths, all of which resolve through the
> frozen snapshot, and with `integrationConfig` correctly classified as
> **SNAPSHOT NOT CONSUMED** rather than MIXED.
>
> **One genuine leak was found and documented, not fixed:** a live, untested REST
> endpoint writes client-supplied execution-affecting values directly into a
> frozen execution's attempt table. **One latent risk was found:** the execution
> start-gate reads live campaign state and is safe today only because editability
> and executability are disjoint — an invariant asserted nowhere.
>
> Measure → trace → classify → document → recommend. Nothing was fixed.
