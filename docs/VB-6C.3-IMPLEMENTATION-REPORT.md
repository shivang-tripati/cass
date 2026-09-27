# VB-6C.3 — Voice Blast Daily Dial Limit: Final Observability + Over-Engineering Review — Implementation Report

**Status: COMPLETE.** Operational visibility added using the project's *existing* Micrometer
convention. No new infrastructure, no new migration, no new scheduler, no semantics change. The
daily-limit business rule is byte-for-byte unchanged. **VB-6C is declared COMPLETE.**

---

## 1. Baseline

| Item | Value |
|---|---|
| Full suite (end of VB-6C.2) | **1032 tests / 0 failures / 0 errors / 1 skipped** |
| ArchitectureTest | 1/0/0/0 — 0 cycles |
| Flyway head | V48 |

## 2. Final Test Result

| Item | Value |
|---|---|
| Full Maven suite | **1040 tests / 0 failures / 0 errors / 1 skipped — BUILD SUCCESS** (3:13) |
| Delta | **+8**, fully accounted for: `DailyDialLimitObservabilityTest` (8 tests). `OpenApiDumpIT` contributed **0** to either count (see §19) |
| The 1 skipped test | `ObdApplicationTests.contextLoads` — pre-existing, unrelated |
| Surefire configuration | **Untouched** — `pom.xml` still declares no surefire plugin config |
| Tests suppressed, disabled, weakened, or deleted | **None** |

## 3. ArchitectureTest

**1/0/0/0 — 0 cycles.** `DailyDialLimitService` already existed inside the `campaign` module and
gained only a `MeterRegistry` parameter; no new module, service, or package was introduced, and no
`Campaign ↔ Telephony` dependency was added.

## 4. Flyway Head

**V48 — unchanged.** No migration in this phase. The only persistence change is a **read-only
diagnostic query** (§8), not schema. §10 therefore holds trivially: `voice_blast_daily_usage`,
`voice_blast_daily_usage_entries`, `campaigns.daily_dial_limit` and
`campaign_execution_configurations.daily_dial_limit` are all untouched.

---

## 5. Observability Mechanism Chosen

**Existing Micrometer `MeterRegistry` counters** — no new framework, no new observability service.

Inspection (§1) found the convention is already established in exactly two places:

| Reference | Pattern |
|---|---|
| `SuspiciousActivityDetector` | `Counter.builder("obd.security.suspicious.signals").tag("type", …).tag("severity", …).register(registry).increment()` + `log.isInfoEnabled()` guard |
| `SecurityMaintenanceService` | private `counter(String name)` helper, `Counter.builder(name).register(registry).increment(n)` |

Both use dotted `obd.<domain>.<signal>` names, **enum-valued tags only**, and keep identifiers in
log lines rather than metric labels. VB-6C.3 reuses that convention verbatim. Logging is plain SLF4J
parameterized logging, as everywhere else in the codebase.

Three signals, all on `DailyDialLimitService` — the existing campaign-owned policy boundary. No new
class was created for observability.

| Meter | Type | Tag | Meaning |
|---|---|---|---|
| `obd.campaign.voiceblast.dailylimit.reached` | Counter | `effectiveLimit` (values `1`/`2`/`3`) | Dials refused because the bucket was exhausted |
| `obd.campaign.voiceblast.execution.timezone.invalid` | Counter | none | Dials refused because the execution snapshot timezone was unusable |
| `obd.campaign.voiceblast.dailylimit.reserved.buckets` | Gauge | none | Buckets currently holding an un-released reservation (normally `0`) |

The `effectiveLimit` tag is the only label used anywhere. It is bounded to three values and answers
the operator's first question — *which limit is binding?* — without introducing a single unbounded
dimension.

## 6. DAILY_LIMIT_REACHED Visibility

Incremented in `DailyDialLimitService.admit(...)` on the `DAILY_LIMIT_REACHED` branch — the single
point where the decision is made, alongside the single existing log line for that event. The dial
path itself was left silent for this event (§6, see §10).

## 7. EXECUTION_TIMEZONE_INVALID Visibility

Incremented in `DailyDialLimitService.resolveUsageDate(...)` on both failure paths (missing/blank
and malformed), immediately before throwing. `OutboundDialService` already emitted a
`log.error` naming the attempt and execution, so **no new log was added**. The offending timezone
string is deliberately **not** a metric tag (unbounded) and appears only in the existing exception
message, which the dial path logs.

## 8. Ledger Visibility Decision — observe, do not reconcile

VB-6C.1's known limitation (a hard crash between reservation and confirmation strands a hold) is
surfaced with **one diagnostic `SELECT COUNT(*)`** and one Micrometer `Gauge` — no table, no
scheduler, no sweeper, no worker, no lock.

```java
@Query(value = "SELECT COUNT(*) FROM voice_blast_daily_usage WHERE reserved_count > 0", nativeQuery = true)
long countBucketsWithReservations();
```

Rationale: a non-zero gauge is *evidence* that stranded capacity exists. It creates no repair path,
no polling loop, and no operational burden until someone decides the evidence warrants one. Per §7,
reconciliation is **DEFERRED**, not built.

## 9. Over-Engineering Review

| Component | Verdict | Reasoning |
|---|---|---|
| `DailyDialLimitService` | **KEEP** | The campaign-owned policy boundary. Owns admission/confirm/release, the effective-limit rule, and the usage-day resolution. Removing it would push daily-limit policy into the dial pipeline. |
| `VoiceBlastDailyUsage` (bucket counter) | **KEEP** | The atomic admission authority. `PG-DL2` proves 20 concurrent workers yield exactly 3 admissions *from PostgreSQL state*. Irreplaceable by application logic. |
| `VoiceBlastDailyUsageEntry` (per-attempt ledger) | **KEEP** | `UNIQUE(call_attempt_id)` makes double-counting *physically impossible*, independent of application discipline (`PG-DL3`, `PG-DL12`). See §12. |
| `VoiceBlastDailyUsageRepository` (3 native statements) | **KEEP** | The conditional `UPDATE … WHERE reserved_count + used_count < :limit` cannot be a derived query, and the single-statement form *is* the concurrency argument. |
| `entryRepository.existsByCallAttemptId` | **KEEP** | Fast-path idempotency that avoids poisoning the caller's transaction with a constraint violation. The unique key remains the physical guarantee; this is the cheap path, not the only one. |
| `usedCount(...)` | **KEEP** | Not speculative: **10** real assertions in `VoiceBlastDailyDialLimitPostgresIntegrationTest` read state through it to prove the concurrency invariants. Deleting it would force those tests to re-derive the same query. |
| V47 | **KEEP** | Two-table design is load-bearing (below). |
| V48 | **KEEP** | Two nullable columns + named CHECKs. No new table. |
| `CampaignConfigurationSnapshot.dailyDialLimit` | **KEEP** | Required to freeze the value; without it §9's snapshot immutability could not hold. |
| `CampaignRuntimeConfig.dailyDialLimit` | **KEEP** | The snapshot→runtime seam. Adding no field here would have forced the dial path to read the live entity — explicitly forbidden. |
| `@DailyDialLimit` annotation | **KEEP** | The single DTO-boundary rule. (Consolidated in VB-6C.2; a duplicate wrongly-typed annotation was removed there.) |
| `DailyDialLimitService.assertConfigurable` | **KEEP** | Covers entities built outside REST. Not redundant with the DTO constraint. |
| Duplicate `log.info` on `DAILY_LIMIT_REACHED` | **REMOVE** | Provably redundant — see §10. |
| `OpenApiDumpIT` | **REMOVE** | See §19. |
| Stale "no campaign limit is configurable in this phase" javadoc | **SIMPLIFY** | Contradicted the code since VB-6C.2; corrected. |

### SIMPLIFY

Exactly **one** simplification: the stale `DailyDialLimitService` class javadoc, which still claimed
"no campaign limit is configurable in this phase (VB-6C.2), so the platform max always applies" —
false since VB-6C.2 shipped. Corrected rather than left to mislead the next reader.

**No structural simplification was available or warranted.** Every remaining component is either
load-bearing for a proven invariant or actively exercised by tests.

### The two-table design (§12) — explicitly not touched

Both tables are necessary because the two guarantees are independent and neither structure can carry
the other:

- **Bucket counter** — enforces the limit under concurrency. Requires a mutable counter row with a
  unique key and conditional arithmetic; a ledger of per-attempt rows cannot enforce "at most 3"
  atomically without a self-join/contention mess.
- **Per-attempt ledger** — enforces exactly-once. Requires a physical `UNIQUE(call_attempt_id)`; a
  counter row alone cannot distinguish "one acceptance recorded twice" from "two acceptances."

Replacing either with JSON arrays, in-memory counters, application-only counters, or Redis was
considered and rejected outright — it would trade a proven PostgreSQL guarantee for a weaker,
untested one. **No change.**

## 10. Anything Simplified / Removed and Why

1. **Removed the duplicate `DAILY_LIMIT_REACHED` log** in `OutboundDialService`. Inspection proved
   two log lines fired for the *same event in the same call stack*: the service logged
   `tenant/contact/did/date/effectiveLimit`, and the caller immediately logged
   `attemptId/contact/did`. The service-side line is strictly more informative and is the only place
   that knows the effective limit, so the caller-side line was removed. No invariant lost — the
   attempt remains persisted with `failureCode = DAILY_LIMIT_REACHED` and is fully queryable. Per §6,
   *no new logs were added anywhere*.
2. **Removed `OpenApiDumpIT`** — see §19.
3. **Corrected the stale concurrency javadoc** (SIMPLIFY, above).

## 11. Anything Deliberately NOT Changed, and Why

| Not changed | Why |
|---|---|
| The daily-limit business rule | §9 frozen. `admit()`'s return logic, `effectiveLimit`, and the release/confirm transitions are untouched — only a counter and the pre-existing log were added around them. |
| V47 / V48 schema | §10. No correctness defect found. |
| `CampaignEntity` / DTOs / controllers | §16. No REST surface change ⇒ the generated OpenAPI is unchanged; `CampaignOpenApiContractTest` (4) and `ContactGroupMemberOpenApiContractTest` (5) remain green. |
| Contact Center / AI / `CallType` | §9. The signals live in the Voice Blast campaign path only; `CallType.VOICE_BLAST` scope is unchanged. |
| No reconciliation scheduler / sweeper / worker / lock | §8 explicitly prohibited, and §7 says defer without evidence. |
| No configuration versioning or history | §9. The single `campaign_execution_configurations` snapshot remains the only execution configuration. |
| No new metrics framework, event bus, or observability service | §3/§22. Micrometer and SLF4J already exist and are the convention. |
| `/actuator` endpoint exposure | Enabling `/actuator/metrics` is a platform/ops decision affecting the pre-existing `obd.security.*` meters equally. Out of scope for this phase — see §14. |

## 12. Privacy / Security Considerations

- **No metric label carries an unbounded identifier.** `DailyDialLimitObservabilityTest.OBS-8`
  asserts this rather than assuming it: every registered meter's tag keys are checked against a
  forbidden list (`tenant`, `campaignId`, `contactId`, `didId`, `phone`, `e164`, `attemptId`,
  `executionId`, `providerCallId`, `timezone`, `date`, …), and it asserts that exactly **one** meter
  carries any tag at all, with the single value `"2"` (the bounded effective limit).
- **Never** in a metric: phone numbers, raw E.164 values, contact identifiers, provider call IDs,
  secrets, tokens, or campaign configuration payloads.
- The existing logs carry UUIDs only (`tenant`, `contact`, `did`, `attempt`, `execution`) — no raw
  dialed numbers — consistent with the project's existing policy. The removed log line reduced
  log volume and removed nothing privacy-relevant.
- No new table, no persisted observability data, no new privacy model.

## 13. Full VB-6C Regression Evidence

| §18 requirement | Evidence (all green in the final run) |
|---|---|
| Campaign configuration → snapshot → runtime limit → actual route DID → atomic admission → +OK → exactly-once usage → DAILY_LIMIT_REACHED → observability | `DailyDialLimitSnapshotPostgresIntegrationTest` (5, real PG) wires config→snapshot→`CampaignRuntimeConfig`→`OutboundDialService`; `OutboundDialServiceRoutingTest` (19) covers admission→+OK→confirm/release; `DailyDialLimitObservabilityTest` (8) covers the final hop |
| `null → 3`, `1 → 1`, `2 → 2`, `3 → 3` | `PG-6C2-2`, `PG-6C2-3`; `DailyDialLimitServiceTest.EffectiveLimit`; `CampaignDailyDialLimitServiceTest.effectiveLimitSemantics` |
| `>3` and `<=0` rejected | `CampaignDailyDialLimitValidationTest` (10), `CampaignApiSliceTest` (8), `CampaignDailyDialLimitServiceTest` (8), `ck_*` CHECKs in `PG-6C2-4` |
| Concurrent workers cannot exceed the limit | `PG-DL2` — 20 workers → exactly 3 admissions |
| Duplicate acceptance cannot double-count | `PG-DL3`, `PG-DL12`, `OutboundDialServiceRoutingTest.DailyLimitHoldLifecycle` |
| Pre-acceptance failures consume zero | `PG-DL9`, `OutboundDialServiceRoutingTest.TemporaryFailures` |
| Cross-campaign buckets shared | `PG-DL4` |
| Different DNIDs independent | `PG-DL5` |
| Tenants isolated | `PG-DL6` |
| Snapshot changes do not affect existing executions | `PG-6C2-1` |
| Contact Center unaffected | no Contact Center path touched; `DailyDialLimitServiceTest.Isolation`, `voice` suites green |
| Daily-limit observability | `DailyDialLimitObservabilityTest` (8) — `OBS-1..OBS-8` |

VB-6C suite totals in the final run: `VoiceBlastDailyDialLimitPostgresIntegrationTest` 12,
`DailyDialLimitServiceTest` 10, `OutboundDialServiceRoutingTest` 19,
`DailyDialLimitSnapshotPostgresIntegrationTest` 5, `CampaignDailyDialLimitServiceTest` 8,
`CampaignDailyDialLimitValidationTest` 10, `CampaignApiSliceTest` 8,
`CampaignOpenApiContractTest` 4, `DailyDialLimitObservabilityTest` 8 — **all 0 failures / 0 errors**.

## 14. Known Limitations

1. **Counters are in-memory only.** No Prometheus registry dependency exists and `/actuator/metrics`
   is not exposed (only `/actuator/health`), so these meters are readable in-process but not
   scrapeable over HTTP today. This is **pre-existing and uniform** — the established
   `obd.security.suspicious.signals` and `obd.security.cleanup.*` meters are in exactly the same
   position. Exposing them is a platform/ops decision affecting all existing meters, deliberately
   not made unilaterally in a campaign phase.
2. **Stranded holds are surfaced, never repaired.** A process death between `reserve` and
   `confirm`/`release` permanently consumes capacity for that `(tenant, contact, did, day)` bucket.
   The gauge makes it *visible*; nothing fixes it. Capacity self-heals at the next calendar day in
   the snapshot timezone. Reconciliation is DEFERRED pending evidence.
3. **`OpenApiDumpIT` removal is now safe**, but it means a future debugger wanting to dump the spec
   must write a throwaway test again. Accepted: it asserted nothing that
   `CampaignOpenApiContractTest` does not already assert.
4. Inherited from VB-6C.1: two same-tenant campaigns sharing contact + DNID with different snapshot
   timezones can disagree on day boundaries (audit §22, accepted).

## 15. Final VB-6C Completion Verdict

| Criterion | Status |
|---|---|
| Observability sufficient | ✅ 2 counters + 1 gauge on the existing Micrometer convention |
| No unnecessary infrastructure introduced | ✅ zero new classes/packages/migrations/schedulers |
| No daily-limit semantics changed | ✅ §9 frozen list verified by the full suite |
| No Contact Center behavior changed | ✅ untouched |
| No configuration versioning introduced | ✅ single snapshot remains |
| No reconciliation scheduler introduced | ✅ explicitly deferred |
| PostgreSQL invariants intact | ✅ V47/V48 untouched |
| Concurrency tests green | ✅ `PG-DL2` 20 workers → 3 |
| Duplicate-acceptance tests green | ✅ `PG-DL3`, `PG-DL12` |
| Snapshot immutability green | ✅ `PG-6C2-1` |
| API/OpenAPI tests green | ✅ 4 + 5, generated spec unchanged |
| ArchitectureTest cycle-free | ✅ 1/0/0/0, 0 cycles |
| Full Maven regression green | ✅ 1040 / 0 / 0 / 1 |

# VB-6C COMPLETE

VB-6C.1 (ledger + atomic admission) · VB-6C.2 (campaign configuration + snapshot + REST + OpenAPI) ·
VB-6C.3 (observability + over-engineering review) are all complete.

The final design is the smallest one that safely enforces the rule: **one nullable campaign column,
one frozen snapshot column, one counter row, one per-attempt entry table, one policy service, three
metrics, and no moving parts beyond those the business rule requires.** A new developer can read
`DailyDialLimitService` and understand the whole feature without a separate monitoring system.

**STOPPED after VB-6C.3. VB-6D and all other campaign features NOT started.**
