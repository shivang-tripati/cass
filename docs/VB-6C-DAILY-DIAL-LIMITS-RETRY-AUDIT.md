# VB-6C — Voice Blast DNID-Scoped Daily Dial Limits & Retry Governance Audit

## 1. Audit Status

**AUDIT COMPLETE — IMPLEMENTATION NOT STARTED.** No production code, tests, migrations, APIs, OpenAPI contracts, schedulers, or architecture were modified in this phase. Every "Current State" claim below cites the actual class/repository/table inspected; recommendations are marked **RECOMMENDED** and nothing is presented as existing.

## 2. Baseline

- Full suite (end of VB-6B.2): **950 tests / 0 failures / 0 errors / 1 skipped / BUILD SUCCESS**.
- `ArchitectureTest` (Spring Modulith `ApplicationModules.verify()`): 1/0/0/0 — **0 cycles**.
- Flyway head: **V46** (`V46__contact_identity_and_group_membership.sql`).
- Environment note: full runs on this Windows host require reduced JVM memory (Maven `-Xmx512m`, surefire fork `-Xmx384m`); two native-OOM crashes occurred and were cleaned during VB-6B.2.

## 3. Business Rule

For **Voice Blast campaigns only**, the same contact/phone number may receive at most **3 provider-accepted outbound dial attempts** using the **same DNID** during the **same calendar day**. Bucket identity: `(contact, DNID, calendar day)` — NOT (contact, day), NOT (contact, gateway, day), NOT (tenant, contact, day), except that tenant isolation of the bucket is architecturally required (§21). A campaign may configure a stricter limit: `effectiveLimit = min(platformVoiceBlastMax, campaignLimit)`, never above the platform max.

## 4. Voice Blast Scope Boundary

**CURRENT IMPLEMENTATION:**
- `CampaignType` has exactly three values: `PLAYFILE`, `DTMF`, `CONNECT_BY_AGENT` (`campaign/CampaignType.java`). There is **no** `MISSED_CALL` type today (the brief lists it; the enum does not contain it — a factual correction to the brief's assumption).
- The voice core classifies calls with `CallType` (`voice/call/CallType.java`): `VOICE_BLAST`, `CONTACT_CENTER_INBOUND`, `CONTACT_CENTER_OUTBOUND`, `AI`, `INTERNAL`. Only **two** producers set `CallSession.callType` today:
  - `campaign/OutboundDialService.createCallSession` → `CallType.VOICE_BLAST` (line ~295) — the campaign dial pipeline.
  - `voice/outbound/AgentOutboundCallService` → `CallType.CONTACT_CENTER_OUTBOUND` (line ~197) — agent-initiated outbound, already a distinct classification.
- `EslEventService` already branches on `CallType.CONTACT_CENTER_INBOUND/OUTBOUND` (lines ~131/~139), proving the type is the established dispatch boundary.
- `telephony/EslEventService.java` and `voice/call/CallSession.java` default `callType` to `VOICE_BLAST` only for the campaign path.

**RECOMMENDED:** scope the daily limit by the **campaign execution path** (`CampaignType.PLAYFILE` + `DTMF`, and `CONNECT_BY_AGENT`'s customer leg — see T below), which is exactly the path that flows through `CampaignExecutionOrchestrator` → `OutboundDialService.processDueAttempts` → `dialer.dial` and stamps `CallType.VOICE_BLAST` sessions. The policy must live on the campaign-execution/dial boundary, never on `Contact`, `CallAttempt` (as a schema field), or `CallSession` globally — `AgentOutboundCallService`'s customer leg already carries `CONTACT_CENTER_OUTBOUND` and would be structurally excluded.

**OPEN DECISION (T):** whether `CONNECT_BY_AGENT` customer legs count. Current fact: a CONNECT_BY_AGENT campaign execution creates its initial attempt rows through the same orchestrator (`attempt.setDidId(didId)` from the snapshot) and its dialing flows through the same `OutboundDialService` dial boundary (the agent bridge happens post-answer via `ConnectByAgentService`). So the **pre-answer customer leg is a Voice Blast-shaped provider dial** and would naturally consume the bucket; the agent leg is not a customer dial and must never count.

## 5. Current Campaign Architecture

- `CampaignEntity` (table `campaigns`, V14/V9): carries `contactGroupId`, `didId`, `typeConfig` (JSON), `callOnWhitelistNumbers`, embedded `RetryPolicySpec` (`retry_max_attempts`, `retry_interval_seconds`, `retry_strategy`) and `ScheduleSpec` (dates, window, **IANA `timezone`**, `allowedDaysOfWeek`, `holidayCalendarId`). No daily-limit field exists anywhere (grep `dailyLimit|daily_limit` = zero hits in code and migrations).
- `CampaignService` validates references with `validateContactGroupReference` + `CampaignResourceValidationService.validateDid` (`CAMPAIGN_VIEW`/`CAMPAIGN_MANAGE`/`CAMPAIGN_EXECUTE` capabilities).
- `CampaignReadinessService.checkContactGroup` verifies group exists/not-deleted/same-tenant; DID must exist, be ACTIVE + ASSIGNED, same tenant.

## 6. Current Execution Snapshot Architecture

- VB-6A model: `CampaignExecutionConfiguration` (one row per execution, NOT NULL FK) holds an immutable `CampaignConfigurationSnapshot` embeddable: `campaignType`, `contactGroupId`, `didId`, content refs, full schedule (incl. `timezone`), full retry policy, `typeConfig`, `callOnWhitelistNumbers`.
- `CampaignRuntimeConfigResolver.resolve(execution)` is the **single seam** — runtime code never reads live campaign config for execution-affecting values, no fallback, no versioning (`ConfigSchemaVersion.V1` constant only).
- `CampaignRuntimeConfig` record exposes `campaignType()`, `didId()`, `contactGroupId()`, `retryPolicy()`, `schedule()`, `typeConfig()`.

**RECOMMENDED:** campaign-level daily limit belongs in this snapshot (a new snapshot column, e.g. `daily_dial_limit`), resolved through the existing `CampaignRuntimeConfig` seam. Platform max = application policy (constant or admin data), combined dynamically at admission: `effective = min(platformMax, snapshotLimitOrDefault)`.

## 7. Contact Identity Audit

- VB-6B.1 model: `ContactEntity` = tenant-level identity, `UNIQUE (tenant_id, phone_number) WHERE deleted_at IS NULL`; one contact = **exactly one** canonical E.164 `phone_number` (length 20, CHECK-contract via `ContactValidation.E164`); soft delete removes liveness; memberships in `contact_group_members`.
- `CallAttempt.contactId` (NOT NULL, `idx_call_attempts_contact`) references the identity; `CallSession` does **not** reference contact — it stores `destinationNumber` (normalized dial string, `PhoneNumberNormalizer.normalize(contact.getPhoneNumber())` in `OutboundDialService.buildDestinationNumber`).
- The dial string and the stored canonical form may differ in leading-zero/normalization edge handling only via `PhoneNumberNormalizer`; identity resolution at dial time is by `contactId` + tenant.

**RECOMMENDED (Decision B):** key the daily bucket by **`contact_id` (UUID)**, not phone string. Reasons: (1) `call_attempts` already carries `contact_id` NOT NULL + indexed, while the phone string is only reconstructible via a join; (2) VB-6B.1 guarantees one live identity per `(tenant, phone)` — contactId ⇔ phone is 1:1 among live rows, so per-phone semantics are preserved; (3) soft-deleted-then-recreated numbers get a new identity, and the old attempt rows keep the old id — no accidental bucket collision across identity generations; (4) the tenant dimension is implied: `contact_id` values are tenant-unique by construction, but the counter row should still carry `tenant_id` for isolation (§21) and query scoping.

## 8. DNID/DID Audit

- `DidEntity` (`did/DidEntity.java`, table `dids`): `tenantId`, `resellerId`, `e164Number` (unique live: `uq_dids_e164_live`), `provider`, `status`, `capabilities`. `DidRepository` offers tenant-scoped and global finds.
- **DNID selection:** the campaign/execution snapshot carries `didId` (the *requested caller-ID DID*). `CampaignExecutionOrchestrator.createInitialAttempts` stamps `attempt.setDidId(config.didId())` — the snapshot value, at attempt creation.
- **Routing can substitute the DNID:** `VoiceRoutingService.buildVoiceRoute(gateway, profileDidId, campaignDid)` — when a `VoiceRouteProfileEntry` pins its own `didId`, the route **legitimately switches provider identity** (documented "Gateway A + DID-A → failover → Gateway B + DID-B"). The `VoiceRoute` handed to the dialer then carries `didRowId = profile DID`, `didE164Number = profile DID's e164`.
- `OutboundDialService` passes `campaign.didId() (snapshot) ?? attempt.getDidId()` into eligibility and routing; the **actual outbound DNID** is `selectedRoute.didE164Number()`/`selectedRoute.didRowId()` — known only **after** `resolveRoute` returns.
- `CallAttempt.didId` is the *requested* DID; `CallSession.didId` = `attempt.getDidId()` (also the requested one, not the profile-substituted one — a minor pre-existing inconsistency worth noting).

**RECOMMENDED (Decisions C/§29 ordering):** the daily-limit admission check must run **after** routing selection and use the **actual route DID** (`selectedRoute.didRowId()`), because that is the DNID the provider will see. The attempt row's `didId` stays the requested one; the usage ledger must store the actual DID id used (and, ideally, the e164 for reporting).

## 9. CallAttempt Audit

- `CallAttempt` (`campaign/CallAttempt.java`, V22 + V23 + V27): `executionId`, `campaignId`, `tenantId`, `contactId`, `didId` (all NOT NULL), `attemptNumber` (1-based, unique per execution+contact+number: `uq_call_attempts_execution_contact_attempt`), `status` (`QUEUED`/`IN_PROGRESS`/`COMPLETED`/`FAILED`/`CANCELLED`), `scheduledAt`, `startedAt`, `completedAt`, `failureCode` (string, taxonomy in `CallFailureCode`), `failureReason`, `providerCallId` (FreeSWITCH channel UUID, unique-ish lookup `findByProviderCallIdAndDeletedAtIsNull`).
- Indexes: tenant+deleted, execution, campaign, **status**, **scheduled_at**, **contact_id**, **did_id** — all single-column or tenant-led; **no composite (contact_id, did_id) or (did_id, contact_id) index exists** (§24).

## 10. Provider-Acceptance Boundary

Traced lifecycle: orchestrator creates QUEUED → `processDueAttempts` claims → snapshot resolution → `buildDestinationNumber` (contact resolution) → `eligibilityService.evaluate` → status `IN_PROGRESS` + `startedAt` → `voiceRoutingService.resolveRoute` → `voiceCapacity.reserve` → `dialer.dial(routedRequest)` → `FreeSwitchOutboundDialer` → `EslClient.originate` sends `bgapi originate …` and parses the reply → `OutboundDialResponse`:

- **`DIAL_REQUEST_ACCEPTED`** = ESL reply `+OK <uuid>`: the provider **accepted the dial** and returned the channel UUID. `OutboundDialService` then sets `attempt.setProviderCallId(uuid)`, leaves status `IN_PROGRESS` (completion comes later via ESL `CHANNEL_HANGUP`), and creates `CallSession(DIALING)` + `CallLeg(DIALING)`.
- All other results (`BUSY`, `NO_ANSWER`, `REJECTED`, `FAILED`→`DIAL_FAILED`, `PROVIDER_UNAVAILABLE`) are **pre-acceptance dial-request outcomes** — the provider never accepted the call. (`BUSY`/`NO_ANSWER` here are fast synchronous rejections from the gateway originate, NOT post-acceptance outcomes; post-acceptance BUSY/NO_ANSWER arrive as ESL hangup causes 17/19 later.)

**RECOMMENDED (Decision D):** the authoritative "provider accepted" event in the existing code is exactly the `DIAL_REQUEST_ACCEPTED` branch of `OutboundDialService.processAttempt` — i.e., the ESL originate `+OK <uuid>` receipt, materialized by `attempt.setProviderCallId(...)`. This is the single existing boundary where `count = 1` must be recorded. Note: ESL "originate accepted" means FreeSWITCH accepted the originate job (pre-answer, pre-ring); the audit treats this as the acceptance boundary because it is the strongest signal the current integration defines and is precisely where `providerCallId` is stamped. ESL `CHANNEL_ANSWER` is a later, weaker (post-ring) signal and must **not** be the counting boundary.

## 11. Retry Flow Audit

- `CampaignExecutionOrchestrator.processRetriesForExecution`: for each FAILED attempt of a RUNNING execution, reads retry policy **from the snapshot**, computes `maxTotalAttempts = 1 + retryMaxAttempts`, skips permanent failures (`CallFailureCode.isPermanent`), skips if next number exists (idempotency via `uq_call_attempts_execution_contact_attempt`), revalidates contact (identity-scoped) and DID (dynamic resource check), schedules `completedAt + intervalSeconds` adjusted to the snapshot schedule window, creates a **new** `CallAttempt` row with `attemptNumber = n+1` and **`didId = config.didId()`** (the snapshot DID — same as the original).
- Requeue (pre-dispatch): `OutboundDialService.requeueAttempt` resets to `QUEUED`, **without consuming the attempt number** (used for capacity/routing/provider-unavailable paths).
- Retry creation does **not** check the daily limit today (obviously — the feature doesn't exist).

**RECOMMENDED (Decisions E/§16):** retries consume the daily bucket **only when their own dial is provider-accepted** (same `DIAL_REQUEST_ACCEPTED` boundary as initial attempts) — which falls out naturally if counting lives at the dial boundary rather than attempt creation. Because `uq_call_attempts_execution_contact_attempt` is per-execution, cross-execution retries of the same contact+DNID are separate rows and each counts individually. Admission ordering for the retry dial is identical to initial dials (§16).

## 12. Failure Taxonomy Audit

`CallFailureCode` (VB-6A canonical enum, string-persisted, `RetryClass.TEMPORARY/PERMANENT`): covers `BUSY`, `NO_ANSWER`, `REJECTED`, `DIAL_FAILED`, `PROVIDER_UNAVAILABLE`, `CONGESTION`, `TEMPORARY_FAILURE`, `RESOURCE_UNAVAILABLE`, `HANGUP_UNKNOWN`, in-call media failures, agent failures, eligibility rejections (`INVALID_DID`, `DNC_BLOCKED`, `NOT_WHITELISTED`, `NOT_IN_CAMPAIGN_TARGETS`, `NO_ELIGIBLE_GATEWAY`, `TEMPORARILY_UNAVAILABLE`…), orchestration rejections (`CAMPAIGN_NOT_FOUND`, `EXECUTION_CONFIG_MISSING`, `CONTACT_INVALID`, `ROUTE_REJECTED`), inbound/agent-outbound codes. **Gaps only (do not change now):** no `DAILY_LIMIT_REACHED` (or equivalent) code exists; there is no `SWITCHED_OFF`/`NOT_REACHABLE` code — ESL unknown causes map to `HANGUP_<CAUSE>` (forward-compat handled by `fromCode` returning empty → temporary). `fromCode`/`retryClassOf` make adding a code later a pure enum extension with deterministic behavior.

**RECOMMENDED (§28):** add `DAILY_LIMIT_REACHED(RetryClass.TEMPORARY)` in the implementation phase — temporary, because the same attempt becomes dialable again the next calendar day / after a DNID change; it must not create an immediate retry spin (the daily-reset semantics make scheduled retry pointless the same day; recommend `markFailed` without retry-eligibility side effects or a dedicated schedule-to-next-day decision left to implementation).

## 13. Compliance / DND / Whitelist Audit

- Two-layer eligibility exists: `telephony/CallEligibilityService` (campaign-facing: `VoiceEligibilityService.evaluate` → blocklist/DNC/whitelist/number validity, then `NOT_IN_CAMPAIGN_TARGETS` membership check unless whitelist-enforced) and voice-layer `VoiceEligibility` re-evaluated **inside routing** (`VoiceRoutingService` step 2).
- Whitelist restricts rather than bypasses safety (`callOnWhitelistNumbers` snapshot flag); retries re-run the full eligibility path every dispatch (`processDueAttempts` runs `evaluate` per attempt, per retry).

**RECOMMENDED (Decisions J/K):** daily-limit admission sits **after** both eligibility layers and before/after routing per §16 — rejected DND/whitelist/eligibility/routing/capacity attempts never reach the dialer and therefore never cross the acceptance boundary, so they consume nothing. This preserves the existing compliance model with zero parallel structures. The daily limit does **not** replace or modify any compliance check and must be re-evaluated on every retry dispatch (it is a dial-time admission policy like eligibility).

## 14. Scheduler / Dispatch Audit

- Single JVM scheduler: `CampaignExecutionOrchestrator.scheduledTick()` (`@Scheduled(fixedDelay = 30000)`) — sequential phases: start REQUESTED executions → `processRetries()` → `dialService.processDueAttempts()` → `ensureEventProcessing()` → reconcile.
- `processDueAttempts` selects `findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(QUEUED, now)` — no `SKIP LOCKED`, no claim query, no `@Modifying` conditional update anywhere in `campaign/` (grep confirms zero). Duplicate-dispatch protection today is: status guard (`processAttempt` returns false if not QUEUED), the execution+contact+attemptNumber unique index for row creation, and `providerCallId` correlation for ESL events.
- Multi-instance concern: the code has no instance coordination for the dial loop; two JVMs would both fetch the same QUEUED rows and race on the status transition (optimistic, last-write-wins on save) — a pre-existing single-JVM assumption, not introduced by this phase.

**RECOMMENDED:** no new scheduler. The daily-limit admission belongs inside `OutboundDialService.processAttempt` (per-attempt, transactional), which inherits whatever single/multi-JVM posture the dial loop has; the admission primitive must itself be concurrency-safe regardless (§17) so a future multi-instance deployment stays correct.

## 15. Capacity / Routing Interaction

Existing order inside `processAttempt`: eligibility → IN_PROGRESS → routing (which internally re-checks voice eligibility, gateway state/authorization, **and per-gateway capacity** via `voiceCapacity.checkCapacity` for primary/overflow/failover candidates) → explicit `voiceCapacity.reserve(gatewayId, tenantId)` after selection → dial. Capacity rejection → requeue without consuming retry. Capacity uses PostgreSQL advisory locks (`VoiceCapacityServiceImpl`: `pg_try_advisory_xact_lock` with `CHANNEL_LOCK_BASE`/`CPS_LOCK_BASE`, transaction-scoped release).

## 16. Daily-Limit Admission Point

**CURRENT:** no daily-limit concept exists anywhere.

**RECOMMENDED placement (Decision §11):** inside `OutboundDialService.processAttempt`, **after routing selection succeeds** (because the bucket key needs the *actual* route DID, §8) and **before** `voiceCapacity.reserve` + `dialer.dial`:

```
eligibility → routing → [DAILY-LIMIT ADMISSION: (tenant, contactId, actualDID, day)] → capacity reserve → dial → DIAL_REQUEST_ACCEPTED → record usage
```

Rationale: (1) before routing, the actual DNID is unknown (profile-pinned DID switch, §8); (2) after the dialer, admission is too late; (3) capacity is a transient "right now" concern while the daily limit is a policy concern — checking the policy first avoids consuming capacity headroom for a call that policy forbids; (4) a rejection here maps to a `requeueAttempt`-style non-consumption path (see §28 semantics). Counting (**usage +1**) happens only in the `DIAL_REQUEST_ACCEPTED` branch, in the same transactional attempt save that stamps `providerCallId`.

**OPEN DECISION:** admission could alternatively be checked pre-routing using the *requested* DID (cheaper, one query earlier) with a second check post-routing if the route DID differs — implementation phase should weigh the double-check cost vs. the (rare) profile-DID-switch case. The single post-routing check is the simplest correct default.

## 17. Concurrency Analysis

**CURRENT primitives available (all PostgreSQL, no Redis in the dial path — the `data-redis` starter exists but `VoiceCapacityServiceImpl` and `AgentReservationService` explicitly use `pg_try_advisory_xact_lock`):**
1. `pg_try_advisory_xact_lock(bigint)` — proven pattern (`VoiceCapacityServiceImpl`, `AgentReservationService` with `AGENT_LOCK_BASE = 0x300000000L`).
2. Unique partial indexes as idempotency authorities (`uq_call_attempts_execution_contact_attempt`, `uq_contacts_tenant_phone_live`, `uq_cgm_group_contact`).
3. `saveAndFlush`-in-guarded-block to surface DIVE deterministically (VB-6B.1/6B.2 pattern).
4. Transaction-scoped coordination (`TransactionTemplate`, `@Transactional`).

**RECOMMENDED (Decisions P/Q):** a dedicated daily-usage ledger row per `(tenant_id, contact_id, did_id, usage_date)` with `attempts_used INT`, and admission = **conditional atomic UPDATE** (the brief's preferred primitive):

```sql
UPDATE voice_blast_daily_usage
SET attempts_used = attempts_used + 1
WHERE tenant_id = :t AND contact_id = :c AND did_id = :d AND usage_date = :day
  AND attempts_used < :effectiveLimit
```
→ `rowCount == 1` ⇒ admitted (slot reserved); `rowCount == 0` ⇒ insert-if-absent then retry the conditional update (unique PK on the 4-tuple makes row creation race-safe: `INSERT … ON CONFLICT DO NOTHING`, then re-UPDATE). This is atomic under concurrent workers without long locks: two workers at 2/3 with limit 3 → exactly one UPDATE matches, the other sees `rowCount 0`. An advisory-lock alternative (`pg_try_advisory_xact_lock(hash(tenant,contact,did,day))` around count+increment) also works and reuses the established pattern; the conditional UPDATE is preferred because it makes the constraint (`attempts_used < limit`) the authority rather than lock discipline. **The usage increment must be a separate committed transaction from the dial** (or use the same TX as the attempt save — analysis for implementation: same-TX keeps providerCallId+usage atomic but the attempt save commits with the batch; a SERIALIZABLE risk does not arise with conditional UPDATE row locking).

**OPEN DECISION:** whether the +1 write commits in the same transaction as the `DIAL_REQUEST_ACCEPTED` attempt save (atomicity vs. holding the row lock longer). Recommendation: same transaction — the dial-accept branch is short and the row lock is held only until commit; correctness (no double count, no lost count) dominates.

## 18. Idempotency Analysis

**CURRENT:** duplicate dispatch prevention = status guard (`QUEUED` check) inside the same JVM loop; `uq_call_attempts_execution_contact_attempt` prevents duplicate attempt rows; ESL events correlate by `providerCallId` (`findByProviderCallIdAndDeletedAtIsNull`); `EslEventService` ignores unknown `providerCallId`s. `DIAL_REQUEST_ACCEPTED` occurs exactly once per attempt row because the status guard prevents re-dialing an `IN_PROGRESS` attempt.

**RECOMMENDED (Decision R):** usage accounting keyed by **`call_attempt_id`** (unique column on the ledger entry or a uniqueness rule "one usage increment per attempt") guarantees a duplicated internal event can never double-count one provider dial. If the ledger is a pure counter (no per-attempt rows), the guarantee comes from the attempt-status guard being the only code path that increments — implementation phase should add a `UNIQUE (call_attempt_id)` on a per-attempt usage/ledger row (or an `attempts_recorded` marker) to make double-counting physically impossible, not merely unlikely.

## 19. Cross-Campaign Analysis

**CURRENT:** attempts are per-execution rows; nothing shares state across campaigns/tenants beyond the DID/contact rows themselves.

**RECOMMENDED (Decision F):** the ledger key `(tenant_id, contact_id, did_id, usage_date)` **deliberately excludes campaign/execution** — Campaign B querying the bucket sees Campaign A's usage because both write the same row. Enforcement is not campaign-local; it is platform-scoped within Voice Blast. The tenant dimension in the key keeps buckets isolated (§21). Case E answered: two campaigns, same contact, same DNID, same day → shared 3-slot bucket.

## 20. Cross-DNID Analysis

**CURRENT:** `VoiceRoute.didRowId`/`didE164Number` distinguish the actual outbound DNID per dial; profile-pinned DIDs legitimately vary per route.

**RECOMMENDED (Decisions G/H):** DNID is part of the ledger key → DNID A 3/3 does not affect DNID B (independent rows). Example in §45 verified by construction.

## 21. Tenant / Reseller Isolation

**CURRENT:** every attempt row carries `tenantId`; DID rows are tenant/reseller-owned; all queries are tenant-scoped (`findByIdAndTenantId…` conventions); reseller visibility via `hierarchyTenantIds`; platform unbounded.

**RECOMMENDED (Decision I):** `tenant_id` is part of the ledger key/PK → a tenant can never consume another tenant's bucket; resellers' tenants remain isolated; no new authorization rules — the existing scoping conventions apply unchanged.

## 22. Calendar-Day / Timezone Analysis

**CURRENT authoritative timezone model:** the **campaign snapshot's `ScheduleSpec.timezone`** (IANA string, e.g. `Asia/Kolkata`, V14 column `timezone length 64`, documented "the execution engine interprets the window in this zone") — used by `CampaignExecutionOrchestrator.adjustToScheduleWindow`, `calculateNextScheduledAt`, `CampaignReadinessService`, `CampaignService` validation. There is **no** tenant-level or organization-level timezone anywhere (no column, no config); DB timestamps are `TIMESTAMPTZ`/`Instant`; reports don't exist yet. `ScheduleSpec.timezone` is nullable (optional until the campaign is configured).

**RECOMMENDED (Decision L):** the day bucket = `LocalDate` in the execution snapshot's timezone. **OPEN DECISION (blocker-grade):** the snapshot `timezone` is nullable — the implementation must define the fallback (recommend: tenant timezone does not exist today, so either (a) require timezone for Voice Blast campaigns via readiness validation, or (b) fall back to the JVM default / UTC explicitly). Also note: two campaigns sharing a (contact, DNID) with **different** timezones would disagree on day boundaries; with a shared ledger keyed by `usage_date`, the implementation must pick ONE authoritative zone per bucket — recommend storing `usage_date` computed in the **dialing execution's snapshot timezone** and documenting the residual edge (two same-tenant campaigns with different timezones sharing contact+DNID is a degenerate configuration; if unacceptable, key additionally by zone or use tenant-level zone — flagged as an implementation-phase decision).

## 23. Persistence / Database Analysis

**Can existing data answer the query?** Yes for *reporting*: `call_attempts` has `contact_id`, `did_id`, `provider_call_id` (NOT the acceptance flag), `status`, timestamps. **No for admission:** (1) "provider-accepted" is only derivable as `provider_call_id IS NOT NULL AND status IN (IN_PROGRESS, COMPLETED, FAILED-with-post-acceptance-codes)` — but `status`/`failureCode` cannot distinguish "accepted then failed (cause 17)" from "sync BUSY at originate" (both end `FAILED`/`BUSY` — V22 rows for sync-fails never get a providerCallId, so `provider_call_id IS NOT NULL` **is** actually the discriminator; however it is not constrained, and status transitions like `CANCELLED`/future changes make it fragile); (2) a `COUNT(*)`-then-dial admission has an inherent race (two workers both count 2 < 3) — count-then-act is **not** concurrency-safe; (3) no `usage_date` column exists — day bucketing needs a computed date in the campaign timezone, which is expensive to compute per row in SQL and cannot be indexed simply.

**RECOMMENDED (Decision P):** a new small ledger table (`voice_blast_daily_usage`: PK/unique `(tenant_id, contact_id, did_id, usage_date)`, `attempts_used`, plus per-attempt usage rows with `UNIQUE(call_attempt_id)` for idempotent increments — exact shape in implementation phase). Justification: admission requires an atomic conditional-increment authority that derived counts cannot provide (§17), and the acceptance boundary (`provider_call_id` stamping) is not durable/queriable enough to be the source of truth. Increment is written at the `DIAL_REQUEST_ACCEPTED` boundary; the ledger is therefore always consistent with attempts by construction (both written in the same TX).

## 24. Index / Query Analysis

**CURRENT `call_attempts` indexes:** `idx_call_attempts_tenant_deleted (tenant_id, deleted_at)`, execution, campaign, status, scheduled_at, **contact_id**, **did_id** — all useful for other queries but **no composite (contact_id, did_id)** and nothing supporting a per-day derived count (which would need the computed `usage_date` anyway).

**RECOMMENDED:** the ledger table's unique key `(tenant_id, contact_id, did_id, usage_date)` **is** the access path — every admission is a 1-row point lookup/update (index-only). No additional index on `call_attempts` is required for correctness; if post-hoc analytics want "attempts per contact+DID+day" from attempts, a `(contact_id, did_id)` composite could be added later for reporting only — clearly a read optimization, not the correctness authority.

## 25. Performance / Scale Analysis

- Admission cost: one point UPDATE (+ possible one INSERT) per dial attempt — O(1), indexed, comparable to the existing `voiceCapacity.reserve` (which already does multiple queries + an advisory lock per dial).
- Query frequency = dial frequency (bounded by gateway CPS limits that capacity already enforces); no per-contact batch pre-check is needed — admission is inherently per-dial.
- Row-per-day churn: bucket rows for (contact × DNID) pairs per day; at 10k contacts/tenant/day with a handful of DNIDs this is tens of thousands of narrow rows/day — trivial for PostgreSQL; old days can be pruned later (retention policy deferred).
- No cache/Redis justified: the conditional UPDATE is already the minimal atomic operation.

## 26. API / OpenAPI Impact

**CURRENT:** `CreateCampaignRequest`/`UpdateCampaignRequest` (`campaign/dto/`) carry name, type, runMode, contactGroupId, didId, content refs, `@Valid ScheduleConfig`, `@Valid RetryPolicyConfig`, `typeConfig`, `integrationConfig`. `CampaignResponse` mirrors. No daily-limit field. VB-6B.2 established the standing OpenAPI rule (`@Schema`-documented DTOs, generated `/v3/api-docs`, contract tests).

**RECOMMENDED (Decisions M/V/W):** implementation adds an optional, validated `dailyDialLimit` (1–3, or the platform max) to campaign create/update DTOs + `CampaignResponse`, flows into `CampaignConfigurationSnapshot`/`CampaignRuntimeConfig`, and is OpenAPI-documented per the standing rule (required=false, min/max constraints, `effectiveLimit = min(platform, campaign)` semantics documented). If the platform max stays an application constant, no admin API is needed in the first implementation cut.

## 27. Authorization Impact

**CURRENT:** campaign create/update gated by `CAMPAIGN_MANAGE` (`CampaignService` lines 56–59, 117); execute by `CAMPAIGN_EXECUTE`; tenant-scoped via `AccessCheck.forTenant`; platform/reseller handled by `AuthorizationService` scope covers.

**RECOMMENDED:** no new capability; `dailyDialLimit` rides the existing `CAMPAIGN_MANAGE` gate (it is campaign configuration). Platform max = immutable application policy constant (or platform-admin data later) — **not** tenant-configurable; never exceeding 3 is validated server-side (bean validation + service check).

## 28. Failure / Rejection Semantics

**CURRENT:** rejections reuse `CallFailureCode` strings persisted on the attempt; capacity/routing/provider-unavailable → `requeueAttempt` (no retry consumption); eligibility failures → `markFailed` (consume retry per taxonomy).

**RECOMMENDED:** new code `DAILY_LIMIT_REACHED` (§12) with `RetryClass.TEMPORARY` semantics but **requeue-like handling** (do not create same-day retries — they cannot succeed until the day resets or the DNID changes; recommend failing the attempt permanently-for-today without retry scheduling, exact behavior in implementation). It must be visible in reporting (failureReason text includes limit/context) and must **not** consume a bucket slot (nothing was dialed). Canonical home: `CallFailureCode` enum — no duplicate taxonomy.

## 29. Boundary: Provider Acceptance

See §10. Summary chain: `CallAttempt created (QUEUED)` → claimed (`IN_PROGRESS`, `startedAt`) → `resolveRoute` → `reserve` → `OutboundDialRequest` → `FreeSwitchOutboundDialer.dial` → `EslClient.originate` → **`+OK <uuid>` reply** → `OutboundDialResponse.accepted(uuid)` → `DIAL_REQUEST_ACCEPTED` branch → `attempt.setProviderCallId(uuid)` → ringing (ESL `CHANNEL_PROGRESS`) → answer (`CHANNEL_ANSWER`) → hangup (`CHANNEL_HANGUP`, cause mapping in `mapHangupCauseToCode`). **Strongest authoritative existing acceptance signal = the `+OK <uuid>` originate receipt**, already materialized as `providerCallId` on the attempt.

## 30. Attempt Counting vs Call Outcome

**CURRENT:** post-acceptance outcomes arrive via ESL `CHANNEL_HANGUP`: cause 16 → `COMPLETED`; 17→`BUSY`, 19→`NO_ANSWER`, 21→`REJECTED`, 34→`CONGESTION`, 41→`TEMPORARY_FAILURE`, 47→`RESOURCE_UNAVAILABLE`, unknown→`HANGUP_<CAUSE>` — all while `providerCallId` remains set, distinguishing them from pre-acceptance sync failures (no providerCallId).

**RECOMMENDED:** all provider-accepted outcomes (ANSWERED, NO ANSWER, BUSY-post-acceptance, FAILED-after-acceptance, causes 17/19/21/34/41/47/unknown) consume exactly one slot each — which the §10 boundary gives automatically, because counting happens at acceptance, before any outcome exists. No outcome mapping changes needed.

## 31–34. (combined) DNID Edge Cases, Migration Assessment

**Case A (campaign reconfigured after snapshot):** execution keeps snapshot DID A (`CampaignRuntimeConfigResolver` never reads live config) — bucket follows DNID A for that execution. ✔ existing semantics.
**Case B (retry after config change):** retries stamp `didId = config.didId()` — the **snapshot** DID; retry uses the execution's DNID. ✔.
**Case C (DNID unavailable after snapshot):** dial-time governance rejects dynamically (`resourceValidator.validateDid` at execution start; `VoiceRoutingService` step 3 `ROUTE_REJECTED_INVALID_DID` at dial) — no dial, no slot. ✔.
**Case D (DNID changed before execution creation):** the new execution's snapshot captures the current campaign `didId`. ✔.
**Case E (two campaigns, same contact+DNID):** shared bucket via the campaign-agnostic ledger key (§19). ✔ enforceable platform-wide.

**Migration assessment (§34):** required (§23) — one forward migration (V47-class) creating the Voice Blast daily-usage ledger table with its unique key + per-attempt idempotency unique; **no** changes to `call_attempts`/`contacts`/`dids`; no backfill (the policy applies prospectively; day buckets start empty). Rollback: drop-table reversibility, no data coupling.

## 35. Test Gap Matrix (future, not written now)

- **UNIT:** effective-limit calc (min semantics; campaign > 3 rejected; default = platform max); DNID-scoped key building; Voice-Blast-only scope guard (non-VB types bypass); `DAILY_LIMIT_REACHED` classification; day-boundary computation in snapshot timezone incl. fallback.
- **POSTGRESQL:** same contact+DNID reaches 3 → 4th admission rejected; different DNID independent 3; different contact independent 3; cross-campaign shared count; tenant isolation; **concurrent admission at the 2/3 boundary (two transactions, exactly one wins)**; duplicate dispatch → single increment (per-attempt unique); midnight reset; provider-accepted vs pre-dispatch rejection slot accounting; retry consumption after acceptance.
- **INTEGRATION:** scheduler → admission → dial path; retry → admission; snapshot-DNID vs route-DID switch (profile-pinned DID); acceptance-boundary counting (`+OK` vs sync-fail).

## 36. Architecture Assessment

Dependency graph stays acyclic: the ledger lives in the **campaign** module (the policy is campaign-dial-path specific) or a small `voice.dailylimit` slice — **RECOMMENDED: campaign module**, because both the caller (`OutboundDialService`) and the Voice Blast classification live there, and `voice/` must not grow campaign-policy dependencies (Modulith `verify()` currently 0 cycles; VB-5G boundary: telephony → voice, never reverse). No new infrastructure; PostgreSQL remains the source of truth (§17).

## 37. Reuse / Extend / Refactor / New Matrix

| Component | Class | Verdict |
|---|---|---|
| Campaign type model | `CampaignType` | REUSE AS-IS |
| Execution snapshot | `CampaignConfigurationSnapshot`, `CampaignRuntimeConfigResolver`, `CampaignRuntimeConfig` | EXTEND (add campaign daily limit to snapshot + resolver view) |
| Campaign DTOs | `Create/UpdateCampaignRequest`, `CampaignResponse` | EXTEND (optional `dailyDialLimit`, OpenAPI-documented) |
| Campaign entity/mapper | `CampaignEntity`, `CampaignMapper` | EXTEND (column + mapping) |
| Attempt model | `CallAttempt`, `CallAttemptRepository` | REUSE AS-IS (no schema change; `providerCallId` untouched) |
| Dial pipeline | `OutboundDialService.processAttempt` | EXTEND (admission post-routing; usage record at `DIAL_REQUEST_ACCEPTED`) |
| Orchestration | `CampaignExecutionOrchestrator` | REUSE AS-IS (no scheduler change) |
| Failure taxonomy | `CallFailureCode` | EXTEND (add `DAILY_LIMIT_REACHED`) |
| Eligibility/compliance | `CallEligibilityService`, `VoiceEligibility`, phone lists | REUSE AS-IS (untouched, ordering preserved) |
| Routing | `VoiceRoutingService`, `VoiceRoute` | REUSE AS-IS (actual DID already exposed) |
| Capacity | `VoiceCapacityService(Impl)` | REUSE AS-IS (advisory-lock precedent only) |
| DID/DNID | `DidEntity`, `DidRepository`, `VoiceRouteProfile` | REUSE AS-IS |
| Contact model | `ContactEntity`, `ContactRepository`, `ContactGroup*` | REUSE AS-IS (no ownership/policy fields) |
| Voice core | `CallSession`, `CallLeg`, `EslEventService` | REUSE AS-IS |
| Daily-usage ledger | (none exists) | **NEW** (`voice_blast_daily_usage` + admission service) |
| Anything to REMOVE | — | none (no legacy shims exist for this concern) |

## 38. Resolved Design Decisions (A–X)

- **A. Key:** `(tenant_id, contact_id, did_id, calendar day)` — tenant included for isolation; campaign excluded (shared bucket).
- **B. Identity:** `contact_id` (UUID), not phone string (§7).
- **C. DNID:** the **actual route DID** (`VoiceRoute.didRowId`) post-routing, not the configured snapshot DID when they differ.
- **D. Provider accepted:** ESL originate `+OK <uuid>` → `DIAL_REQUEST_ACCEPTED` branch → `providerCallId` stamped (§10).
- **E. Retries:** consume a slot iff their dial is provider-accepted (same boundary).
- **F. Cross-campaign:** shared bucket when contact+DNID+day match (§19).
- **G/H. Different DNIDs:** independent buckets; A 3/3 never blocks B.
- **I. Tenants:** never share buckets (`tenant_id` in key).
- **J/K. Pre-provider failures, DND/whitelist/eligibility/capacity/routing rejections:** consume nothing (no dial occurred).
- **L. Timezone:** execution snapshot `ScheduleSpec.timezone` (authoritative, existing); fallback for null = explicit implementation decision (flagged).
- **M/N. Campaign limit:** new snapshot column via existing VB-6A snapshot machinery; validated ≤ platform max at create/update.
- **O. Platform max 3:** application policy constant (server-side authority).
- **P. New persistence:** yes — daily-usage ledger (§23); derived `call_attempts` counting is insufficient (race + acceptance-flag fragility).
- **Q. PostgreSQL sufficiency:** yes — conditional UPDATE increment + unique key (§17); no Redis/Kafka.
- **R. Idempotency:** per-attempt unique usage marker + attempt-status guard (§18).
- **S. Voice Blast types:** `PLAYFILE`, `DTMF`, and CONNECT_BY_AGENT's customer leg (§4); `MISSED_CALL` does not exist in `CampaignType`.
- **T. CONNECT_BY_AGENT:** pre-answer customer leg counts (same dial boundary); agent legs never count.
- **U. Contact Center independence:** policy keyed to the campaign dial path + `VOICE_BLAST` classification; `AgentOutboundCallService` (`CONTACT_CENTER_OUTBOUND`) and inbound never touch the ledger; no fields on shared primitives.
- **V. API:** one optional campaign config field (§26); no new endpoints required.
- **W. OpenAPI:** `@Schema` docs per the VB-6B.2 standing rule + contract assertions.
- **X. Sequence:** §39 below.

## 39. Recommended Implementation Sequence

**VB-6C.1 — Ledger + admission foundation (campaign module).**
Scope: V47 ledger table; `VoiceBlastDailyUsage` entity + repository with conditional-increment admission; effective-limit resolution (platform constant + snapshot value); day computation in snapshot timezone (with explicit fallback decision); `DAILY_LIMIT_REACHED` failure code; dial-path integration point behind a small port so the dial service stays thin. Schema: new table only. API: none. Concurrency: conditional UPDATE + unique key + per-attempt idempotency unique (§17–18). Tests: PG matrix §35 (concurrency boundary is the core). Out of scope: campaign config field, reporting.

**VB-6C.2 — Snapshot/campaign configuration + API.**
Scope: `dailyDialLimit` column on campaigns + snapshot embeddable + `CampaignRuntimeConfig`; DTO/validation/OpenAPI per standing rule; effective-limit = min(platform, snapshot). Dependencies: 6C.1. Tests: validation bounds, snapshot immutability across edits (edge cases A–D re-verified), API contract test.

**VB-6C.3 — Observability hardening (optional/small).**
Scope: rejection reason surfaced in attempt `failureReason`; (future) reporting of per-bucket usage; retention/pruning policy decision. No schema change beyond 6C.1 (reporting reads attempts + ledger).

## 40. Risks

- **Acceptance-boundary drift:** if FreeSWITCH `+OK` semantics ever change (async job queuing), the counting boundary must be re-derived from `EslClient` behavior — pinned in §10.
- **Timezone fallback (blocker-grade open decision):** null snapshot timezone needs a ruling before 6C.1.
- **Profile-pinned DID switch:** rare but real; admission must key on route DID or double-check (§16 open decision).
- **Multi-JVM dispatch:** current dial loop is single-JVM by assumption; the ledger is multi-JVM-safe but attempt claiming is not — out of scope, noted.
- **Degenerate config:** two same-tenant campaigns sharing contact+DNID but different snapshot timezones disagree on day boundaries (§22) — accept + document, or add tenant-zone rule in 6C.2.

## 41. Explicit Non-Goals

Contact Center / inbound / agent / AI / SMS / WhatsApp limits; queue/predictive/progressive/preview dialing; omnichannel policy engine; billing; Redis/Kafka/K8s/microservices; new telephony stack or FreeSWITCH commands; scheduler rewrite; Contact/ContactGroup/membership redesign; configuration versioning; legacy compatibility; `Contact.dailyCallCount`-style fields; reporting implementation.

## 42. Decision Table

| Decision | Current State | Recommended Direction |
|---|---|---|
| Scope | No daily-limit concept exists anywhere | Voice Blast campaign dial path only |
| Daily key | `call_attempts(contact_id, did_id)` exist uncombined; no day bucket | contact + actual-DNID + calendar day (+tenant) |
| Platform max | none | 3 (application constant) |
| Campaign max | no field on `CampaignEntity`/snapshot/DTOs | snapshot field, ≤ 3, `min(platform, campaign)` |
| Cross-campaign | attempts are per-execution rows only | shared bucket via campaign-agnostic ledger |
| Different DNID | route DID may differ from snapshot DID (profile pinning) | independent buckets keyed on actual route DID |
| Retry | new attempt row per retry; same snapshot DID; no limit checks | counts only when provider-accepted |
| DND rejection | eligibility layers before dial; no provider contact | does not count |
| Capacity rejection | `requeueAttempt` without retry consumption | does not count |
| Provider acceptance | `DIAL_REQUEST_ACCEPTED` + `providerCallId` stamp (`+OK <uuid>`) | exact counting boundary |
| Timezone | snapshot `ScheduleSpec.timezone` (IANA, nullable) | snapshot zone; fallback = explicit decision |
| Persistence | derived count impossible to make race-safe | NEW ledger table, conditional UPDATE |
| Concurrency | advisory locks + unique indexes proven | conditional atomic increment (no new infra) |
| Contact Center | `CallType.CONTACT_CENTER_*` already separate | independent future policy; no shared fields |

## 43. Data-Flow (conceptual, post-implementation)

```
Voice Blast Campaign (PLAYFILE/DTMF/CONNECT_BY_AGENT)
        |
        v
Execution Snapshot (didId, retry policy, timezone, dailyDialLimit)
        |
        v
Audience (contact_group_members -> live contacts)          [VB-6B model]
        |
        v
Attempt created: didId = snapshot DID (uq per execution+contact+n)
        |
        v
OUTBOUND DIAL SERVICE (per due attempt)
   1. contact identity resolution (tenant-scoped, live)
   2. eligibility (blocklist/DNC/whitelist/targeting)      -- rejects never count
   3. routing (VoiceRoutingService)
        -> ACTUAL route DID = VoiceRoute.didRowId          -- bucket DNID
   4. DAILY-LIMIT ADMISSION  (tenant, contactId, routeDID, day)
        +---- rejected --> DAILY_LIMIT_REACHED (no slot consumed, no dial)
        |
        v
   5. capacity reserve (advisory locks)                    -- rejects never count
        |
        v
   6. FreeSWITCH originate (bgapi)
        +---- not accepted (BUSY/NO_ANSWER/REJECTED/FAILED/PROVIDER_UNAVAILABLE)
        |         --> pre-acceptance; NO slot consumed
        v
   7. +OK <uuid>  == PROVIDER ACCEPTED
        |
        v
      providerCallId stamped; usage +1 (atomic, per-attempt idempotent)
        |
        v
   Call attempt lifecycle (ESL ringing/answer/hangup -> COMPLETED/FAILED codes)
```

Policy **admission** (step 4) and actual **provider-accepted counting** (step 7) are distinct events; only step 7 writes usage.

## 44. Example Matrix (intended semantics)

| Scenario | Result |
|---|---|
| Contact X + DNID A: dials 1,2,3 accepted | 1/3, 2/3, 3/3; 4th admission → `DAILY_LIMIT_REACHED` |
| Contact X + DNID B same day | 0/3 → independently allowed (3 fresh slots) |
| Contact Y + DNID A | independent from Contact X |
| Campaign A: X+DNID A accepted ×2, then Campaign B: X+DNID A | sees 2/3 shared; only 1 remaining |
| Campaign B: X+DNID B | full allowance available |
| X+DNID A: DND/whitelist/eligibility rejection | no slot consumed |
| X+DNID A: routing/capacity rejection, provider unavailable | no slot consumed |
| X+DNID A: sync BUSY at originate (pre-acceptance) | no slot consumed |
| X+DNID A: accepted then NO_ANSWER (ESL cause 19) | slot consumed (1) |
| Retry (attempt 2) provider-accepted same day | slot consumed (2) |
| Retry provider-rejected pre-acceptance | no slot consumed |
| Next calendar day (snapshot tz) | bucket resets to 0/3 |

## 45. Current-Facts vs Recommendations Convention

Throughout: "CURRENT IMPLEMENTATION" = verified code/table facts with class names; "RECOMMENDED" = proposed design, not yet existing; "OPEN DECISION" = requires ruling before implementation (timezone fallback §22; admission placement variant §16; same-TX increment §17).

## 46. Final Audit Gate

- [x] No production/test/migration/API/OpenAPI/scheduler/architecture changes; no new infrastructure; no unrelated refactor
- [x] Baseline recorded (§2); ArchitectureTest recorded; Flyway head V46 recorded
- [x] DNID source established (§8); contact identity established (§7); provider-acceptance boundary established (§10)
- [x] Retry behavior established (§11); cross-campaign (§19); different-DNID (§20); tenant isolation (§21); timezone (§22)
- [x] Concurrency (§17) + idempotency (§18) strategies identified
- [x] Voice Blast-only (§4) and Contact Center boundaries (§4, U) established
- [x] Persistence (§23), index (§24), API/OpenAPI (§26), migration (§34) assessed; test gaps (§35); sequence proposed (§39); non-goals (§41)

**FINAL AUDIT DECISION:** Implementation is feasible with one new ledger table + one campaign snapshot column + one failure code; PostgreSQL conditional-increment admission satisfies the hard concurrency requirement with existing primitives; the only blocker-grade open decision is the null-timezone fallback rule. **AUDIT COMPLETE — IMPLEMENTATION NOT STARTED. STOP.**
