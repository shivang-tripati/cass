# VB-6D.2 — Campaign Retry Rule Model & Effective Retry Policy — Implementation Report

**Status: COMPLETE.** A campaign can now configure retry behaviour per canonical failure
category. The policy is frozen into the immutable execution snapshot, evaluated by one pure
authority, and can only ever *restrict* retries — a permanent failure stays non-retryable
regardless of configuration.

---

## 1. Executive summary

VB-6D.1 made provider outcomes canonical. VB-6D.2 turns that vocabulary into a configurable
policy, and — the part that actually matters — puts **one** authority between a failed attempt and
a retry.

The pre-existing retry logic was inline in `CampaignExecutionOrchestrator.processRetriesForExecution`:
a null-check, a permanence check, an attempt-number comparison, and `intervalSeconds` arithmetic,
with the rules implicit in code. VB-6D.2 replaces that with a resolved `RetryDecision` from a pure
`RetryPolicyService`, so the orchestrator no longer knows any failure code, category, or allowance.

Three design decisions carry the phase:

| Decision | Rationale |
|---|---|
| **Pre-dispatch rejections are excluded from campaign rules entirely** | A DND block, a missing snapshot, or an exhausted daily bucket cannot be fixed by dialling again. Letting them consume retry budget would burn a contact's whole allowance on an outcome that was never a call. |
| **The permanent-failure check is step 3 of a fixed order** | `effectiveRetry = policyAllows AND failureIsEligible`, never an OR. No configuration can promote a permanent outcome. |
| **Rules are JSONB, not a relational child table** | A bounded group (≤6), always read/written as one unit, never queried relationally, and frozen verbatim into the snapshot. Two tables would add a join and a second thing to sync to express one aggregate value. |

Two **real defects** were found and fixed while implementing, both surfaced by tests rather than by
inspection (§16, §17).

## 2. Initial repository state

| Item | Value |
|---|---|
| Commit / branch | `619deac`, `main` → `origin/main` |
| Working tree | VB-6D.1 present but **uncommitted** (plus the user's `campaign-readiness.md` edit — preserved untouched) |
| Full suite | **1138 tests / 0 failures / 0 errors / 1 skipped** |
| ArchitectureTest | 1/0/0/0 — 0 cycles |
| Flyway head | **V48** |
| Existing retry model | `RetryPolicySpec` embeddable: `maxAttempts` (0–10), `intervalSeconds`, `strategy` (`FIXED` only) |
| Existing retry decision | Inline in the orchestrator: permanence gate → `1 + maxAttempts` → idempotency → resource re-validation → `completedAt + intervalSeconds` → schedule-window clamp |

## 3. Existing retry behaviour (preserved)

`maxAttempts` has always counted **retries**, so `maxTotalAttempts = 1 + maxAttempts` — the
pre-VB-6D.2 meaning is unchanged, and a campaign created before this phase behaves identically
(verified: `RPS-4`, `CNT-3`, and the untouched `PlayfileRetrySemanticsTest`).

## 4. Design decisions

| Decision | Choice | Rejected alternative |
|---|---|---|
| Where the policy lives | `campaign` (policy ownership) | voice/telephony — that owns provider mapping, already done in VB-6D.1 |
| Shape of a decision | `RetryDecision` record | scattered booleans |
| Rule storage | JSONB column on campaign **and** snapshot | relational child tables |
| Delay representation | `RetryDelay` value object, `MM:SS` | raw seconds (drifts from the product form) |
| Category vocabulary | `RetryRuleCategory` (6 product categories) | one rule per `CallFailureCode` (~45, unmanageable) |
| Empty `rules` | flat fields govern entirely | defaulting every category to a generated rule |

## 5. Retry rule model

```
RetryPolicySpec                        (embeddable, campaign + snapshot)
 ├── maxAttempts / intervalSeconds / strategy      ← pre-existing flat allowance
 └── rules: List<RetryRule>                        ← NEW, nullable
      └── RetryRule(category, enabled, maxRetries, retryDelay)
           categories: NO_ANSWER | BUSY | HANGUP | FAILED | SWITCHED_OFF | NOT_REACHABLE
```

`RetryPolicySpec.ruleFor(category)` resolves an explicit rule, else renders the flat allowance as a
rule — so a flat allowance of `0` yields a **disabled** rule, keeping "no retries configured"
meaning exactly that.

## 6. Canonical failure mapping

`FailureClassification` is the single boundary (no switch is repeated anywhere):

| Canonical code(s) | Category |
|---|---|
| `NO_ANSWER`, `AGENT_NO_ANSWER` | `NO_ANSWER` |
| `BUSY`, `AGENT_BUSY` | `BUSY` |
| `HANGUP_UNKNOWN`, `CALLER_HANGUP`, `REJECTED` | `HANGUP` |
| every other contact outcome | `FAILED` (default — the mapping has no holes) |

The mapping is **total**: the code is canonicalized first, so an absent or unrecognized value
becomes `HANGUP_UNKNOWN` → `HANGUP`. An arbitrary provider string can never choose a category
(`MAP-2`).

## 7. Retry count semantics

`maxRetries` counts retries; `maxTotalAttempts = 1 + maxRetries`. `maxRetries = 2` → 3 attempts.

**Retry count is never consumed by non-attempts.** A pre-dispatch rejection is classified
`PRE_DISPATCH` and never reaches a retry decision, so no budget is spent. Capacity and
provider-unavailable rejections are *requeued* by the existing pipeline — the attempt returns to
`QUEUED` with its attempt number untouched — so they consume nothing by construction (§9).

## 8. Retry delay semantics

`RetryDelay` is a `MM:SS` value object, minutes 00–99, seconds 00–59, minimum 00:01. The next
eligible instant is `failedAt + delay` — an `Instant` operation, proven identical under three
different default timezones (`MDL-5`). Schedule-window clamping is deliberately **not** in the
delay: it is a snapshot concern owned by the orchestrator, and mixing them would make the delay
untestable in isolation.

The 99:59 ceiling is intentionally narrower than the legacy 604800-second interval: a delay measured
in days is a scheduling decision, and the campaign schedule already expresses "come back tomorrow".

## 9. HANGUP_UNKNOWN behaviour

VB-6D.1 left `HANGUP_UNKNOWN = TEMPORARY`. That constant is **unchanged**. VB-6D.2 makes the
treatment **explicit**: `HANGUP_UNKNOWN` is classified as the `HANGUP` category and is therefore
governed by the campaign's `HANGUP` rule — retryable when that rule allows, suppressed when it is
disabled (`HANG-1`, `HANG-2`). It is no longer decided by an empty lookup.

Whether unknown outcomes *should* be retryable remains a product question (audit OD-2/OD-6) and is
now a one-rule change rather than a taxonomy-wide edit.

## 10. SWITCHED_OFF / NOT_REACHABLE limitation

**Not implemented, deliberately, and no carrier mapping was fabricated.** Both remain configurable
categories (so a future reliable provider signal needs no schema or model change), but **no
`CallFailureCode` maps to them** — proven exhaustively over the whole enum (`UNSUP-1`). Causes that
*might* mean them (`27`, `15`, `22`, `31`) resolve to `HANGUP_UNKNOWN` → the `HANGUP` rule
(`UNSUP-2`). The API and OpenAPI both document this explicitly.

## 11. Execution snapshot representation

`V49__campaign_retry_rules.sql` adds `retry_rules JSONB` to **both** `campaigns` and
`campaign_execution_configurations`, each with a `CHECK (retry_rules IS NULL OR
jsonb_typeof(retry_rules) = 'array')`.

`CampaignConfigurationService.toSnapshot` freezes the rules verbatim at execution creation. Proven
against real PostgreSQL:

- `RPS-2` — campaign edited from `NO_ANSWER: 3 retries` to `0` after E1 was created; **E1 still
  runs 3**.
- `RPS-3` — E1 (3 retries / 10:00) and E2 (1 retry / 01:00) resolve to genuinely different policies.
- `RPS-4` — a campaign with no rules round-trips as `null`, preserving the pre-VB-6D.2 behaviour bit
  for bit.

No versioning, no history table, no fallback read, no live-campaign read at retry time.

## 12. API changes

Existing campaign DTOs **extended**, no new endpoints:

| Type | Change |
|---|---|
| `RetryPolicyConfig` | `+ List<RetryRuleConfig> rules` (nullable) |
| `RetryRuleConfig` *(new)* | `category`, `enabled`, `maxRetries` (0–10), `retryDelay` (`MM:SS`) |

Validation is layered honestly: bean validation owns **shape** (`@Pattern` for `MM:SS`, `@Min`/`@Max`
for the count, `@NotNull` for category), and `RetryPolicyValidator` owns **semantics** that a
per-field annotation structurally cannot express (duplicate category, enabled-without-delay,
delay-on-a-rule-that-permits-nothing). `retryDelay` is deliberately *not* `@NotBlank` — that is how a
category is switched off.

## 13. OpenAPI evidence

Generated spec is the source of truth; extended `CampaignOpenApiContractTest` (4 → 8 tests):

| Test | Asserts |
|---|---|
| OAS-D1 | `RetryPolicyConfig` documents the flat fields *and* the `rules` array; `maxAttempts` description states the `1 + maxAttempts` formula |
| OAS-D2 | `RetryRuleConfig` enumerates all six categories including the two reserved; `maxRetries` 0–10; `retryDelay` pattern `^\d{2}:\d{2}$`; delay not `required` |
| OAS-D3 | `retryPolicy` present on create, update **and** response schemas |
| OAS-D4 | `dailyDialLimit` (VB-6C.2) unchanged — type, min 1, max 3 |

## 14. Database / migration impact

**One migration: V49.** Flyway head V48 → **V49**, verified on a clean container by `RPS-6`. V1–V48
untouched. No configuration-version table, no history table, no metrics table.

A `CHECK` enumerating ~45 codes was **rejected**: `failure_code`-style shared columns plus a bounded
vocabulary would need a migration per added code, and the domain validator already owns the rule.

## 15. Architecture impact

**0 cycles.** Retry policy lives in `campaign`; provider mapping stayed in voice/telephony
(`HangupCauseMapper`, untouched). `RetryPolicyService` is constructed with **no collaborators**,
which is asserted (`DET-2`) — it structurally cannot touch a repository, reserve capacity, or call a
provider. `voice` still imports no campaign type in main code.

The orchestrator's dead `isPermanentFailure` helper was **removed** rather than left behind: a
second copy of the predicate is a second thing to keep in sync.

## 16. Defect found — invalid delay produced HTTP 500, not 400

`@Pattern("^\\d{2}:\\d{2}$")` matches `"00:60"` and `"00:00"` perfectly. Those reached
`RetryDelay.parse`, which threw a raw `IllegalArgumentException` — surfacing as **HTTP 500** instead
of the documented 400.

Fixed by giving the validator ownership of parsing: `RetryPolicyValidator.validateView(view)`
converts a parse failure into `BusinessException(VALIDATION_ERROR)`, which maps onto the existing
ProblemDetail contract. Proven by `VAL-17` and `RAPI-12`.

## 17. Defect found — `RetryDecision` was not comparable by value

`RetryDecision` is a record, but it wrapped a `FailureClassification` that had no `equals`, so two
evaluations of identical inputs compared **unequal** — the "deterministic" property did not actually
hold. Converted `FailureClassification` to a record (`DET-1`).

A third issue was in my own test, not the product: a tautological assertion, removed.

## 18. Tests added

| Suite | Count | Covers |
|---|---|---|
| `RetryPolicyModelTest` *(new)* | 67 | delay parsing/round-trip/range, timezone independence, count semantics, delay calculation, canonical mapping, permanent floor, `HANGUP_UNKNOWN`, unsupported categories, determinism/purity |
| `RetryPolicyValidatorTest` *(new)* | 34 | valid/invalid policies, duplicates, enabled-without-delay, delay-on-disabled, range limits, rule resolution, view-level parsing (the 500→400 fix) |
| `RetryPolicySnapshotPostgresIntegrationTest` *(new)* | 7 | real PostgreSQL: persistence, snapshot freeze, **campaign edit does not change an existing execution**, two executions with different policies, null round-trip, V49 chain, invalid policy rejected |
| `CampaignRetryPolicyApiSliceTest` *(new)* | 20 | REST: accept + echo, disabled-without-delay, legacy shape, omitted policy, malformed delay, out-of-range count, missing/unknown category, reserved categories accepted |
| `CampaignOpenApiContractTest` *(extended)* | 4 → 8 | OAS-D1..D4 (§13) |
| `PlayfileRetrySemanticsTest` *(extended)* | 7, 0 assertions changed | constructor now receives a real `RetryPolicyService` |

**No existing test was weakened or deleted.** One existing test was made *robust*: see §20.

## 19. Full regression evidence

| Item | Value |
|---|---|
| **Full Maven suite** | **1270 tests / 0 failures / 0 errors / 1 skipped — BUILD SUCCESS** (4:30) |
| Baseline before this phase | 1138 / 0 / 0 / 1 |
| Delta | **+132**, fully reconciled: 67 + 34 + 7 + 20 + 4 |
| The 1 skipped test | `ObdApplicationTests.contextLoads` — pre-existing, unrelated |
| **ArchitectureTest** | **1/0/0/0 — 0 cycles** (`ApplicationModules.verify()`) |
| **Flyway head** | **V49** (V48 → V49; V1–V48 untouched), verified on a clean container by `RPS-6` |
| Surefire configuration | Untouched — `pom.xml` declares none |
| Tests suppressed / disabled / weakened / deleted | **None** |
| **OpenAPI** | Generated spec is the source of truth; 8 contract tests green. `retryPolicy` now documented on create/update/response, with the two reserved categories explicitly marked as not yet provider-detectable |

**VB-6C regression — every suite unchanged and green**, confirming the daily DNID limit was not
disturbed:

| VB-6C suite | Result |
|---|---|
| `VoiceBlastDailyDialLimitPostgresIntegrationTest` (real PostgreSQL concurrency) | 12 / 0 / 0 |
| `DailyDialLimitServiceTest` | 10 / 0 / 0 |
| `DailyDialLimitObservabilityTest` | 8 / 0 / 0 |
| `DailyDialLimitSnapshotPostgresIntegrationTest` (snapshot immutability) | 5 / 0 / 0 |
| `OutboundDialServiceRoutingTest` | 19 / 0 / 0 |
| `CampaignDailyDialLimitServiceTest` | 8 / 0 / 0 |
| `CampaignApiSliceTest` | 8 / 0 / 0 |
| `CampaignDailyDialLimitValidationTest` | 10 / 0 / 0 |
| `HangupCauseMapperTest` / `CallFailureCodeCanonicalizationTest` (VB-6D.1) | 58 / 15, 0 failures |
| `EslEventServiceFailureCodeTest` (VB-6D.1) | 22 / 0 / 0 |

Focused phase runs: 121 (model + validator), 20 (API slice), 8 (OpenAPI), 7 (PostgreSQL snapshot) —
all green.

## 20. Known limitations

1. **A pre-existing test was time-of-day flaky and was fixed.** `DailyDialLimitSnapshotPostgresIntegrationTest`
   (a VB-6C.2 test) used a 09:00–18:00 calling window, so its execution step failed for any run
   after 18:00 IST — it passed at 16:00 and failed at 18:24 in this session. Since the test asserts the
   frozen `dailyDialLimit` and not the calling window, the window is now full-day. **No assertion was
   changed**; tests that deliberately assert schedule readiness keep their bounded window. The same
   latent hazard remains in `CampaignResourceValidationPostgresIntegrationTest` and
   `CampaignTestSupport` if they are ever made to execute — flagged, not changed.
2. **`SWITCHED_OFF` / `NOT_REACHABLE` are configurable but unreachable** (§10).
3. **Retry policy is not yet consulted by anything but the orchestrator's retry loop.** The daily
   attempt safety ceiling, its concurrency-safe admission, and its interaction with VB-6C are all
   deferred to VB-6D.3 by design.
4. **`CALLER_HANGUP` is classified `HANGUP`** (retryable). If *we* tear the channel down after a
   successful completion, re-dialling may be wrong. This is audit OD-6 and needs a product answer;
   the category is at least now explicit and configurable per campaign.
5. **The rules JSONB is not queryable relationally** (e.g. "which campaigns retry BUSY"). No such
   report exists or is required; a relational model would cost a join and a second sync point.

## 21. Deferred VB-6D.3 work

Not implemented, by instruction: global max attempts/contact/day, campaign stricter daily attempt
limit, daily-attempt admission, concurrency reservation for VB-6D daily attempts, scheduler changes,
IVR, PLAYFILE changes, `MISSED_CALL`, Contact Center daily limits.

**VB-6C is unchanged**: `DailyDialLimitService`, `voice_blast_daily_usage`,
`voice_blast_daily_usage_entries` and their repositories were not modified. The two controls are
separate by design — retry policy answers *"may this failed attempt be retried?"*, the VB-6C DNID
limit answers *"may another provider-accepted dial occur for this contact + actual DNID + day?"* —
and both must eventually be satisfied.

**Compliance is not bypassed**: a retry is a new `QUEUED` `CallAttempt` that re-enters
`processDueAttempts` → eligibility, so DND/blocklist/whitelist are re-evaluated every time. The
orchestrator additionally re-validates the contact and DID before creating the retry.

**All three campaign types** (`PLAYFILE`, `DTMF`, `CONNECT_BY_AGENT`) share the retry path; the
decision keys on `CallFailureCode`, which is type-agnostic, so no type-specific handling was
introduced. Their media failures simply land in the `FAILED` bucket.

## 22. Final verdict

```
READY
```

- Retry policy is explicit and deterministic ✅ (`RetryPolicyService`, zero collaborators, `DET-1`/`DET-2`)
- Retry rules are validated ✅ (shape at the DTO, semantics in the domain, structure in the DB)
- Canonical failure categories drive policy ✅ (total mapping, §6)
- Permanent failures cannot be made retryable ✅ (`PERM-1`: even a maximally permissive policy loses)
- Execution snapshots contain immutable retry policy ✅ (`RPS-2`/`RPS-3`, real PostgreSQL)
- Retry count and delay semantics are unambiguous ✅ (§7/§8, `CNT-1`, `MDL-5`)
- Compliance is not bypassed ✅ (§21)
- VB-6C remains unchanged ✅
- No scheduler was unnecessarily rebuilt ✅ (`@Scheduled(fixedDelay=30000)` untouched; the loop now consumes a decision)
- Tests are green ✅
- Architecture is cycle-free ✅
