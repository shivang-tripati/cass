# VB-6D.3 — Campaign Safety, Daily Attempt Admission & Final Retry Integration — Implementation Report

## 1. Status

**COMPLETE / READY.** The last remaining VB-6D scope is implemented: a global, concurrency-safe
Voice Blast campaign-attempt ceiling with an optional stricter per-campaign override, integrated
into the existing scheduler and dial pipeline, with VB-6C's provider-accepted limit provably
untouched.

One design decision is worth stating up front because it drove the whole phase: **the campaign
attempt counter counts DISPATCHES, not provider acceptances, and therefore needs no reservation
lifecycle at all.** VB-6C counts at `+OK` — an event that happens *after* the admitting transaction
commits — so it must hold a slot across the dial and needs `reserve`/`confirm`/`release`. The
campaign attempt counter consumes at dial issuance, *inside* the admitting transaction, so the
increment **is** the consumption. One counter, one statement, and nothing a crash could strand.

## 2. Baseline

| Item | Value |
|---|---|
| Commit | `a0c6694` "VB-6D: establish campaign retry safety policy" |
| Branch | `main`, in sync with `origin/main` |
| Working tree | clean except `docs/campaign-readiness.md` (the user's local IVR roadmap note — **preserved untouched, never staged**) |
| Migration head | **V49** |
| Full suite | **1270 tests / 0 failures / 0 errors / 1 skipped** |
| ArchitectureTest | 1/0/0/0 — 0 cycles |
| Pre-existing failures | none |

## 3. Implemented

| # | Item | Artefact |
|---|---|---|
| 1 | Daily attempt ledger | `voice_blast_daily_attempts` (V50) + `VoiceBlastDailyAttempt` entity |
| 2 | Atomic admission | `VoiceBlastDailyAttemptRepository` — `insertBucketRow` (`ON CONFLICT DO NOTHING`) + `reserve` (single conditional `UPDATE`) |
| 3 | Single authority | `DailyAttemptSafetyService` — effective limit, ceiling, validation, admission, metrics |
| 4 | Platform constant | `MAX_DAILY_ATTEMPTS_PER_CONTACT = 10` (evidence-based, §5) |
| 5 | Campaign override | `maxDailyAttempts` on campaign **and** immutable snapshot (V51), frozen at execution creation |
| 6 | Validation | `@CampaignDailyAttempts` + `DailyAttemptSafetyService.assertConfigurable` + two DB `CHECK`s |
| 7 | Taxonomy | `CallFailureCode.DAILY_ATTEMPT_LIMIT_REACHED` (PERMANENT, pre-dispatch) |
| 8 | Dial-path wiring | one `admit(...)` call in `OutboundDialService`, immediately before `dialer.dial(...)` |
| 9 | API + OpenAPI | `maxDailyAttempts` on create/update/response with full `@Schema` documentation |
| 10 | Observability | two Micrometer counters on the existing `obd.*` convention |
| 11 | OD-6 resolution | proven structurally unreachable; locked by `CallerHangupResolutionTest` — **no code change** |

## 4. Final safety model

The real execution flow, with each authority named. Nothing is duplicated and no ordering was
invented — the new gate slots into the existing pipeline at the one point that matches its
semantics:

```
CampaignExecutionOrchestrator.scheduledTick()          [existing, untouched]
  ├─ start REQUESTED executions                        [existing]
  ├─ processRetries()                                  [existing]
  │    └─ RetryPolicyService.evaluate(...)             [VB-6D.2, pure]
  │         → RetryDecision (retryable, #, nextEligibleAt, reason)
  │         ✗ permanent / pre-dispatch / disabled / exhausted → no retry
  └─ processDueAttempts()                              [existing]
       └─ OutboundDialService.processAttempt(attempt)
            1. resolve execution SNAPSHOT              [CampaignRuntimeConfigResolver]
            2. resolveUsageDate(snapshot timezone)     [DailyDialLimitService]  ← one day authority
            3. buildDestinationNumber(contact)
            4. ELIGIBILITY: blocklists/DNC/protected/whitelist   [VoiceEligibilityService]
            5. ROUTING → actualOutboundDidId            [routing services]
            6. VB-6C admit   (tenant, contact, DNID, day)  [DailyDialLimitService]  ← reserve
            7. CAPACITY reserve                          [VoiceCapacityService]
            8. VB-6D.3 admit (tenant, contact, day)     [DailyAttemptSafetyService] ← consume
            9. dialer.dial(...)                         [provider]
           10. +OK → VB-6C confirmAccepted             [DailyDialLimitService]
               rejected/failed → VB-6C releaseReservation
```

Every layer keeps its existing authority. The new gate is the only addition, and it sits
**after** compliance, routing, VB-6C and capacity, and **immediately before** the dial.

## 5. Daily attempt semantics

**Key / dimensions** — `voice_blast_daily_attempts`:

| Dimension | In the key? | Rationale |
|---|---|---|
| `tenant_id` | **yes** | isolation boundary; a cross-tenant key can never collide |
| `contact_id` | **yes** | the entity being protected |
| `usage_date` | **yes** | calendar day in the snapshot's IANA timezone |
| `campaign_id` | **no** | **this is the cross-campaign guarantee** — one ceiling for the tenant |
| `did_id` | **no** | **this is why DNID rotation cannot reset it** |

**Platform ceiling = 10**, chosen from repository evidence rather than invented:
`retry_max_attempts` is already bounded `0..10` by the V14 `CHECK` and by the DTO, and
`RetryRule.MAX_RETRIES` is 10. So a contact is never dialled more often in a day than the platform
already considers sane for a single execution. Asserted by `DA-4`.

**Effective limit** = `min(platform, campaignOverride)`, defaulting to the platform value:

```
effectiveLimit = campaignOverride == null ? 10 : min(campaignOverride, 10)
```

The `min` is belt-and-braces: the DTO constraint, the domain guard and the DB `CHECK` all refuse
`> 10` before it can be stored or frozen.

**Does a retry count as an attempt?** **Yes.** A retry is a dispatch and is charged like any other.
That is the entire point: a retry policy allowing many retries must not be able to dial a contact
indefinitely.

**Lifecycle boundary — the exact answer to each case:**

| Event | Consumes an attempt? | Why |
|---|---|---|
| DND / blocklist / protected / whitelist rejection | **No** | step 4, strictly before this gate |
| Invalid contact, missing snapshot, invalid timezone | **No** | steps 1–3, before this gate |
| Routing failure | **No** | step 5, before this gate |
| VB-6C daily DNID limit reached | **No** | step 6, before this gate |
| No capacity / gateway unavailable | **No** | step 7, before this gate; the attempt is requeued with its attempt number intact and `startedAt` cleared, so re-pickup cannot double-charge |
| **Dial issued, provider rejects** | **Yes** | a dial was sent; the number may have rung. If rejections were free, a permanently broken number would be dialled forever |
| **Dial issued, provider accepts** | **Yes** | as above |
| Accepted, then NO_ANSWER / BUSY / hangup | **Yes** | the attempt already happened |
| Ceiling already reached | **No** | the conditional `UPDATE` grants nothing, so nothing is consumed |

**No reservation lifecycle exists** — and `DA-14` asserts that no `release`/`decrement` method is
even present, because one would be dead code inviting a bug: a "released" attempt was still a dial.

## 6. Retry interaction

`RetryPolicyService` was **not** modified and must not be: it answers *"should a retry exist?"*,
which is a pure policy question over the immutable snapshot. Daily attempt safety answers *"may a
dispatch occur today?"*, which is an admission question about mutable shared state. Keeping them
apart is what lets the policy stay pure and concurrency-free.

The two compose as an AND, and a daily-safety rejection never spends retry budget:

```
RetryPolicyService says retryable  AND  DailyAttemptSafetyService admits  →  the retry dials
RetryPolicyService says retryable  AND  daily safety refuses             →  attempt fails with
                                                                          DAILY_ATTEMPT_LIMIT_REACHED
```

This works because `DAILY_ATTEMPT_LIMIT_REACHED` is registered as a **PRE_DISPATCH** code in
`FailureClassification`, so `RetryPolicyService` will never even generate a retry for it — proven
by `DA-16`: a campaign configured with the maximum 10 retries still cannot retry past the ceiling.

The orthogonality is the point: a campaign with `maxDailyAttempts=1` and 5 retries allowed gets
exactly one dial that day, and its remaining retry budget is simply never used.

## 7. VB-6C preservation — explicit proof

| Property | VB-6C (unchanged) | VB-6D.3 (new) |
|---|---|---|
| Table | `voice_blast_daily_usage` | `voice_blast_daily_attempts` |
| Key | tenant + contact + **DNID** + day | tenant + contact + day |
| Counted event | provider **acceptance** (`+OK`) | **dispatch** (dial issuance) |
| Counters | `reserved_count` + `used_count` | `attempt_count` |
| Lifecycle | reserve → confirm / release | single immediate consume |
| Ceiling | 3 | 10 |
| Service | `DailyDialLimitService` | `DailyAttemptSafetyService` |
| Failure code | `DAILY_LIMIT_REACHED` | `DAILY_ATTEMPT_LIMIT_REACHED` |
| Row lock authority | yes | yes (same pattern) |

`DailyDialLimitService`, `VoiceBlastDailyUsage`, `VoiceBlastDailyUsageEntry` and both repositories
were **not modified**. The dial path still calls `DailyDialLimitService.admit` at step 6 and
`confirmAccepted` only at `+OK`. `CONC-9` proves the separation from committed state: filling the
attempt ceiling four times creates **no** `voice_blast_daily_usage` row.

## 8. CALLER_HANGUP resolution (OD-6)

**Resolved: the feared failure mode is structurally impossible. No code change was made, and the
taxonomy was deliberately not weakened.** Three independent proofs from the real event flow:

1. **`CALLER_HANGUP` is assigned in exactly one place** — `InboundCallService`, on a `CallLeg` and a
   `CallSession`. That service's own comment (line 266) states *"inbound sessions have no
   CallAttempt"*, so the code can never reach the column the retry gate reads.
2. **`CallAttemptService.failAttempt(...)` has zero callers**, so it is not a vector either.
3. **A successful call is not a retry candidate by construction.**
   `EslEventService.handleChannelHangup` sets `status = COMPLETED` and `failureCode = null` on
   success, and `processRetries` loads only `status = FAILED` attempts. A system-initiated teardown
   after completion therefore cannot be read as a failed attempt regardless of the failure code.

Additionally, the campaign path's failure code comes from `HangupCauseMapper`, whose closed output
set provably does not contain `CALLER_HANGUP` (`HANG-R1`).

For defence in depth against a future carrier signal, `HANG-R5` proves that a genuine hangup-class
outcome becomes a canonical `HANGUP_UNKNOWN` → `HANGUP` category, which a campaign can switch off
through its own `HANGUP` rule — so the control exists without inventing a code.

**What would change this:** if a campaign-path producer ever began writing `CALLER_HANGUP` onto an
attempt, the proof would need revisiting. That is now watched by `HANG-R1`/`HANG-R4`.

## 9. SWITCHED_OFF / NOT_REACHABLE

**Unchanged and still deliberately unmapped.** No carrier-specific mapping was invented. Both
remain supported, configurable retry-policy categories — so a future reliable provider signal needs
no schema or model change — but **no `CallFailureCode` maps to them**, proven exhaustively over the
whole enum (`UNSUP-1` in `RetryPolicyModelTest`). Causes that *might* mean them (`27`, `15`, `22`,
`31`, `SUBSCRIBER_OFF_HOOK`) resolve to `HANGUP_UNKNOWN` → the `HANGUP` rule (`UNSUP-2`).

Adding them requires a target-carrier cause list, which is audit **OD-2** — still open, and
correctly so.

## 10. Database

| Migration | Contents |
|---|---|
| **V50** `voice_blast_daily_attempts.sql` | Ledger table. `UNIQUE (tenant_id, contact_id, usage_date)`, `CHECK (attempt_count >= 0)`, index on `(contact_id, usage_date)`. **No `did_id` column** — verified by `CONC-11`. No soft-delete columns (physical accounting state, same rationale as V47) |
| **V51** `campaign_daily_attempt_limit.sql` | `max_daily_attempts SMALLINT` on `campaigns` **and** `campaign_execution_configurations`, each `CHECK (IS NULL OR BETWEEN 1 AND 10)` |

V1–V49 untouched. No versioning, no history table, no `MAX+1`, no compatibility layer, no backfill.

## 11. API / OpenAPI

`maxDailyAttempts` added to the **existing** campaign DTOs — no new endpoint, no duplicate API:

| Schema | Documented |
|---|---|
| `CreateCampaignRequest` | `integer`, min 1, max 10, example 4, not `required` |
| `UpdateCampaignRequest` | same; null clears the override |
| `CampaignResponse` | same; null means platform default |

The description explicitly warns the reader off the most dangerous confusion — that
`maxDailyAttempts` is **not** `dailyDialLimit` — and states the null/default semantics. Generated
OpenAPI is the source of truth; the existing 400 `VALIDATION_ERROR` model is reused unchanged
(`EAPI-5`/`EAPI-6`).

**OpenAPI verification** — `CampaignOpenApiContractTest` grew 8 → 11 tests, all green: `OAS-E1`
(field on all three schemas with correct bounds), `OAS-E2` (description distinguishes the two
limits and states null semantics), `OAS-E3` (both daily controls coexist, each with its own
ceiling — 3 and 10). VB-6C's and VB-6D.2's assertions (`OAS-C1..D4`) are untouched and green.

## 12. Tests

| Suite | Count | Covers |
|---|---|---|
| `DailyAttemptSafetyServiceTest` *(new)* | 26 | effective limit, campaign ceiling, platform ceiling enforcement, validation matrix, admission order, day source, no-release invariant, VB-6C separation |
| `DailyAttemptConcurrencyPostgresIntegrationTest` *(new)* | 11 | **real PostgreSQL**: 20 workers → 3; cross-campaign sharing; stricter ceiling; contact/tenant independence; day rollover; duplicate-dispatch idempotency; restart; VB-6C untouched; V51 chain; key excludes `did_id` |
| `CallerHangupResolutionTest` *(new)* | 5 | the OD-6 resolution, as executable evidence |
| `CampaignDailyAttemptApiSliceTest` *(new)* | 12 | REST: accept/echo 1..10, omitted stays absent, UPDATE, both limits together, reject 0/-1/11/100 |
| `CampaignOpenApiContractTest` *(extended)* | +3 | `OAS-E1..E3` |
| `OutboundDialServiceRoutingTest` *(extended)* | 0 assertions changed | new gate mocked to ADMITTED so the suite keeps testing the dial pipeline |
| `DialBatchContinuationPostgresIntegrationTest` *(extended)* | 0 assertions changed | now wires the **real** `DailyAttemptSafetyService`, so both controls run against real PostgreSQL |

**No existing test was weakened or deleted.** Every pre-existing assertion is intact; the only
changes to existing files were constructor wiring for the new dependency.

### Focused runs

| Run | Result |
|---|---|
| `DailyAttemptSafetyServiceTest` + `CallerHangupResolutionTest` + `DailyAttemptConcurrencyPostgresIntegrationTest` | **42 tests / 0 F / 0 E / 0 S** |
| `DailyAttemptConcurrencyPostgresIntegrationTest` alone (final) | **11 / 0 / 0 / 0** |
| `CampaignDailyAttemptApiSliceTest` + `CampaignOpenApiContractTest` | **23 / 0 / 0 / 0** |
| `VoiceBlastDailyDialLimitPostgresIntegrationTest` (VB-6C's own concurrency suite, after wiring) | **12 / 0 / 0 / 0** |

### Full regression

```
[INFO] Tests run: 1327, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
```

| | Baseline (`a0c6694`) | VB-6D.3 | Delta |
|---|---|---|---|
| Tests | 1270 | **1327** | **+57** |
| Failures | 0 | **0** | — |
| Errors | 0 | **0** | — |
| Skipped | 1 | **1** | — |
| `ArchitectureTest` | 1 / 0 / 0 / 0 | **1 / 0 / 0 / 0 — 0 cycles** | — |
| Flyway head | V49 | **V51** | V50, V51 |

**The +57 reconciles exactly**, with no unexplained remainder:

```
DailyAttemptSafetyServiceTest              26
DailyAttemptConcurrencyPostgresIntegrationTest 11
CallerHangupResolutionTest                  5
CampaignDailyAttemptApiSliceTest            12
CampaignOpenApiContractTest (OAS-E1..E3)     3
                                          ---
                                           57   =  1327 - 1270
```

**Pre-existing phase suites re-verified green in the same run** (no failures or errors anywhere in
the 1327):

| Phase | Suites | Tests |
|---|---|---|
| VB-6C | `DailyDialLimitServiceTest` 10 · `DailyDialLimitObservabilityTest` 8 · `CampaignDailyDialLimitServiceTest` 8 · `CampaignDailyDialLimitValidationTest` 18 · `VoiceBlastDailyDialLimitPostgresIntegrationTest` 12 · `DailyDialLimitSnapshotPostgresIntegrationTest` 5 · `DialBatchContinuationPostgresIntegrationTest` 4 | 65 |
| VB-6D.1 | `RetryPolicyModelTest` 64 · `CallFailureCodeCanonicalizationTest` 15 · `CallFailureCodeTest` 6 · `EslEventServiceFailureCodeTest` 22 | 107 |
| VB-6D.2 | `RetryPolicyValidatorTest` 34 · `CampaignRetryPolicyApiSliceTest` 20 · `PlayfileRetrySemanticsTest` 7 · `RetryPolicySnapshotPostgresIntegrationTest` 7 | 68 |
| Architecture | `ArchitectureTest` | 1 |

Both PostgreSQL suites migrated a clean chain through V50 and V51 in the full run, so the
migrations are proven to apply from empty, not just incrementally.

## 13. Architecture

**0 cycles.** Both new classes are in the `campaign` module, next to `DailyDialLimitService`; the
new repository is campaign-owned. No `Campaign ↔ Telephony` edge: `voice` still imports no
campaign type in main code, and `DailyAttemptSafetyService` reuses `DailyDialLimitService` for the
day seam rather than introducing a competing clock or calendar authority. `ArchitectureTest`
1/0/0/0.

## 14. Documentation

| Document | Update |
|---|---|
| `docs/VB-6D.3-IMPLEMENTATION-REPORT.md` | this report |
| `docs/VB-6D-CAMPAIGN-SAFETY-RETRY-AUDIT.md` | VB-6D marked COMPLETE; §24 sequence closed out; VB-6D.1–6D.3 status |
| `docs/VB-6D.1-…`, `docs/VB-6D.2-…` | left as the historical phase records |
| `docs/VB-6-CAMPAIGN-AUDIT.md` | unchanged this phase |

Architecture and security reference docs (`01_ARCHITECTURE.md`, `06_SECURITY.md`) remain 0-byte
placeholders — pre-existing, and out of scope to invent.

## 15. Known limitations

1. **`SWITCHED_OFF` / `NOT_REACHABLE` are configurable but unreachable** (§9). Needs a
   target-carrier cause list (OD-2).
2. **`HANGUP_UNKNOWN` is still classified retryable** (VB-6D.1's intentional `TEMPORARY`). It is
   now governed by the campaign's `HANGUP` rule, so a tenant that does not want hangup redials can
   switch that rule off — but the platform default is still "retry".
3. **Stranded holds in the VB-6C ledger remain possible** (a crash between reserve and
   confirm). The new attempt ledger has no such window by construction, but VB-6C's is unchanged
   and still only observable via the `…reserved.buckets` gauge. Reconciliation remains deferred.
4. **Degenerate timezone config** (two same-tenant campaigns sharing contact with different
   snapshot timezones disagreeing on the day boundary) is inherited from VB-6C.1 and now applies to
   both daily controls, since they share the one day seam.
5. **`DailyDialLimitSnapshotPostgresIntegrationTest` and `CampaignResourceValidationPostgresIntegrationTest`**
   still contain bounded calling windows; the first was made time-independent in VB-6D.2, the
   second deliberately asserts schedule readiness. If the latter is ever made to execute, it will
   need the same treatment.
6. **The attempt ledger is not queryable relationally** (e.g. "which contacts hit their ceiling
   today"). No such report exists or is required; a relational model would cost a join and a second
   sync point for a value that is inherently one aggregate.

## 16. Final verdict

**VB-6D — COMPLETE / READY.**

Justified by: global campaign attempt safety implemented and proven atomic under real concurrency;
campaign-specific stricter limit implemented and provably unable to exceed the platform maximum;
effective-limit semantics in one named authority; cross-campaign semantics implemented and tested;
retry interaction implemented with an orthogonal AND that never spends retry budget; compliance
verified to run before the gate so no rejection consumes budget; the **existing** scheduler
integrated with no new `@Scheduled` method; VB-6C provably independent and untouched; the
provider-acceptance boundary unchanged; OD-6 resolved with executable evidence;
SWITCHED_OFF/NOT_REACHABLE not falsely mapped; API documented in the generated OpenAPI and
verified; two migrations applied on a clean chain; focused, concurrency and full suites green;
architecture cycle-free.

**The next product phase is:**

> **VB-6E — PLAYFILE + Common Calling Configuration**

and **not** another VB-6D sub-phase. VB-6D introduced no externally tracked sub-phase beyond
6D.1/6D.2/6D.3, and is now closed.
