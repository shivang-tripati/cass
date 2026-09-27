# VB-6C.2 — Voice Blast Daily Dial Limit: Campaign Configuration + Execution Snapshot + REST API + OpenAPI — Implementation Report

**Status: COMPLETE.** The campaign-configured daily dial limit is persisted, frozen into the
immutable execution snapshot, enforced from that snapshot at dial time, and documented in the
generated OpenAPI specification. The complete regression suite is green and the modular
architecture remains cycle-free.

---

## 1. Baseline

| Item | Value | Source |
|---|---|---|
| Full suite (end of VB-6C.1) | **981 tests / 0 failures / 0 errors / 1 skipped** | `docs/VB-6C-IMPLEMENTATION-REPORT.md` §2 |
| ArchitectureTest | 1/0/0/0 — 0 cycles | VB-6C.1 report §2 |
| Flyway head | **V47** (`V47__create_voice_blast_daily_usage.sql`) | `db/migration/` |

### 1.1 State found on arrival

The working tree already contained an **uncommitted, non-green VB-6C.2 work-in-progress**. The
product code was essentially complete, but the phase was **not finished**: the suite did not pass.

| Item | Value |
|---|---|
| Full suite as found | **998 tests / 2 failures / 3 errors / 1 skipped** |
| Broken tests | 5 — **all in test code, none in product code** |
| Flyway head as found | V48 (migration present but unproven by a green chain) |

The five pre-existing defects, and their true nature:

| # | Test | Symptom | Actual cause |
|---|---|---|---|
| 1 | `CampaignOpenApiContractTest.campaignOperationsRemainDocumented` | `$ref` read as `""` | **Test bug.** JSON Pointer `…/content/application-json/…`; RFC 6901 requires `/` escaped as `~1`. The generated spec was always correct. |
| 2–4 | `DailyDialLimitSnapshotPostgresIntegrationTest.snapshotImmutability` / `nullDefaultSemantics` / `stricterLimitsSnapshotAndResolve` | `CAMPAIGN_NOT_EXECUTABLE_STATE: … DRAFT` | **Test-harness bug.** The helper created a DRAFT campaign and executed it immediately; readiness permits only SCHEDULED/RUNNING. The 3 mandatory tests had therefore never actually run. |
| 5 | `DailyDialLimitSnapshotPostgresIntegrationTest.databaseCheckRejectsInvalidValues` | expected `DataIntegrityViolationException` | **Test bug.** A native `UPDATE` surfaces Hibernate's `ConstraintViolationException`; Spring's translation only applies on the JPA/connection paths. The CHECK constraint itself was working. |

---

## 2. Final Results

| Item | Value |
|---|---|
| Full Maven suite | **1032 tests / 0 failures / 0 errors / 1 skipped — BUILD SUCCESS** (3:22) |
| ArchitectureTest | **1/0/0/0 — 0 cycles** (`ApplicationModules.verify()`) |
| Flyway head | **V48** (`V48__add_campaign_daily_dial_limit.sql`) |
| Focused VB-6C.2 tests | 54 executed, 0 failures |
| VB-6C.1 regression | 53 executed, 0 failures |
| The 1 skipped test | `ObdApplicationTests.contextLoads` — pre-existing, unrelated |
| Tests suppressed, disabled, weakened, or deleted | **None** — Surefire has no configuration in `pom.xml`; no test was skipped to obtain a green build |

**Test-count reconciliation** (nothing hidden, nothing invented):

| Step | Tests |
|---|---|
| VB-6C.1 baseline | 981 |
| VB-6C.2 work-in-progress already in the tree on arrival (+17) | 998 |
| Added in this phase: `CampaignApiSliceTest` (16) + `CampaignDailyDialLimitValidationTest` (18) | **+34** |
| **Final** | **1032** |

---

## 3. Flyway Migration Number

**V48** — `backend/src/main/resources/db/migration/V48__add_campaign_daily_dial_limit.sql`

Forward-only; V1–V47 untouched, no historical migration rewritten, no backfill. The same nullable
column is added to both the campaign configuration and the immutable execution snapshot, each with
its own named CHECK constraint — no new table (this is campaign configuration, not a ledger):

```sql
ALTER TABLE campaigns
    ADD COLUMN daily_dial_limit SMALLINT
    CONSTRAINT ck_campaigns_daily_dial_limit
        CHECK (daily_dial_limit IS NULL OR daily_dial_limit BETWEEN 1 AND 3);

ALTER TABLE campaign_execution_configurations
    ADD COLUMN daily_dial_limit SMALLINT
    CONSTRAINT ck_cec_daily_dial_limit
        CHECK (daily_dial_limit IS NULL OR daily_dial_limit BETWEEN 1 AND 3);
```

**Backward compatibility (§24):** existing campaigns keep `NULL` after the migration, which *is* the
"platform maximum of 3" semantics. No version detection, no legacy DTO, no runtime fallback.

---

## 4. CampaignEntity Change

`campaign.daily_dial_limit SMALLINT NULL` → `CampaignEntity.dailyDialLimit` (nullable `Integer`),
placed with the other common execution-affecting configuration columns. Javadoc records the
snapshot-boundary rule and the configured-vs-effective distinction.

The platform maximum remains a **code-level invariant**, never tenant-configurable:

```java
public static final int PLATFORM_DAILY_DIAL_LIMIT = 3;
public static final int MAX_VOICE_BLAST_DAILY_DAIL_LIMIT = PLATFORM_DAILY_DIAL_LIMIT;
```

---

## 5. DTO Changes

| DTO | Change |
|---|---|
| `CreateCampaignRequest` | `+ @DailyDialLimit @Schema(minimum="1", maximum="3") Integer dailyDialLimit` |
| `UpdateCampaignRequest` | same, PUT semantics (omitted/null clears the explicit limit) |
| `CampaignResponse` | `+ @Schema Integer dailyDialLimit` |

Project convention is a **nullable `Integer`** in a `record` — followed. No new DTO pattern invented.
`CampaignMapper` threads the value on create, update and **clone** (a clone keeps the source's
configured limit — `CampaignMapperTest`).

---

## 6. Validation Behavior

One canonical rule, three boundaries, no duplicated logic and no magic numbers:

| Boundary | Mechanism |
|---|---|
| REST input | `@DailyDialLimit` composed constraint on both request DTOs |
| Domain / service | `DailyDialLimitService.assertConfigurable(...)`, called by `CampaignService.create` **and** `.update` — covers entities built outside REST |
| Database | `ck_campaigns_daily_dial_limit` + `ck_cec_daily_dial_limit` (V48) |

**Correction made in this phase.** The working tree carried **two** annotations for one rule:
`campaign.DailyDialLimit` and `campaign.dto.ValidDailyDialLimit`. Worse, `DailyDialLimit` — the one
actually applied to the DTOs — declared
`@Constraint(validatedBy = ValidDailyDialLimit.Validator.class)` while its own nested
`Validator` implemented `ConstraintValidator<ValidDailyDialLimit, Integer>`, i.e. a validator typed
for a *different* annotation. This was consolidated: `DailyDialLimit` is now self-contained and
correctly typed (`ConstraintValidator<DailyDialLimit, Integer>`), and the duplicate
`ValidDailyDialLimit` was removed. The new DTO-validation suite is precisely the test that would
have caught this; it now confirms the constraint fires (see §12, §18).

---

## 7. Execution Snapshot Change

`CampaignConfigurationSnapshot.dailyDialLimit` (nullable `Integer`, `@Column(daily_dial_limit)`) —
part of the VB-6A immutable `@Embeddable` payload. Copied at execution creation by
`CampaignConfigurationService.toSnapshot(...)` in the same transaction that inserts the execution row.

**No configuration versioning** (§7): no version numbers, no `MAX(version)`, no history table, no
legacy fallback, no dual-read, no live-campaign fallback. The existing
`campaign_execution_configurations` row remains the single immutable execution configuration.

---

## 8. Runtime Wiring

The live `CampaignEntity` is **never** read at dial time. The chain is:

```
CampaignEntity.dailyDialLimit
   → CampaignConfigurationService.toSnapshot()      (execution creation)
   → CampaignExecutionConfiguration.configuration   (immutable, @Immutable)
   → CampaignRuntimeConfigResolver.CampaignRuntimeConfig.dailyDialLimit
   → OutboundDialService
   → DailyDialLimitService.effectiveLimit(campaign.dailyDialLimit())
   → admit(...) / confirmAccepted(...) / releaseReservation(...)
```

In `OutboundDialService` the local variable happens to be named `campaign`, but its type is
`CampaignRuntimeConfigResolver.CampaignRuntimeConfig` — the **snapshot view**, obtained from
`resolveExecutionConfig(attempt)`. `CampaignRuntimeConfigResolver` has no live-campaign path at all;
its javadoc states the "no fallback to the live campaign" rule explicitly.

`effectiveLimit` was already signature-compatible (`effectiveLimit(Integer)`); the call site now
receives the snapshot value instead of a constant-`null`. The VB-6C.1 daily-limit business rule is
untouched.

---

## 9. Null / Default Semantics

`null` is preserved verbatim end-to-end and is **never** normalized during persistence:

| Layer | Value |
|---|---|
| Campaign row | `NULL` |
| Execution snapshot | `NULL` |
| API response | `null` (absent from JSON) |
| Runtime | `effectiveLimit(null) = 3` |

Configured and effective stay conceptually distinct: the request is stored as asked; the policy
value is computed at runtime.

---

## 10. Snapshot Immutability Evidence — **PROVEN**

`DailyDialLimitSnapshotPostgresIntegrationTest` (real PostgreSQL, real service stack) — the
mandatory §16 scenario, now actually executing:

| Step | Assertion |
|---|---|
| campaign limit = 3 | `campaignLimit = 3` |
| execution E1 created | `snapshotLimit(E1) = 3`, `effectiveLimit(E1) = 3` |
| campaign changed to 1 | `campaignLimit = 1` |
| **E1 after the campaign change** | **`snapshotLimit(E1) = 3`, `effectiveLimit(E1) = 3`** |
| execution E2 created | `snapshotLimit(E2) = 1`, `effectiveLimit(E2) = 1` |

**The edit is performed through the real path**, not by writing the column directly: the campaign is
unlocked with the explicit `SCHEDULED → DRAFT` transition, the change goes through
`CampaignService.update` (mapper → `assertEditable` → `assertConfigurable` → save), and it is
re-scheduled. VB-6A editability rules are respected, not bypassed.

> **Explicit statement:** *does changing the campaign after execution creation leave the existing
> execution snapshot unchanged?* **YES — proven by the test above.**

---

## 11. Null Default Evidence — **PROVEN**

`PG-6C2-2`: campaign `dailyDialLimit = null` → `campaignLimit = null` → execution created →
`snapshotLimit = null` (not converted to 3) → `effectiveLimitOf = 3`.

> **Explicit statement:** *is `dailyDialLimit = null → effectiveLimit 3` proven by tests?*
> **YES** — at unit level (`DailyDialLimitServiceTest.EffectiveLimit`,
> `CampaignDailyDialLimitServiceTest.effectiveLimitSemantics`) and against real PostgreSQL
> (`PG-6C2-2`).

---

## 12. Validation Test Matrix (§18)

| Value | DTO boundary | Service boundary | DB boundary |
|---|---|---|---|
| `null` | valid | valid | valid |
| `1` | valid | valid | valid |
| `2` | valid | valid | valid |
| `3` | valid | valid | valid |
| `0` | rejected | rejected | rejected |
| `-1` | rejected | rejected | rejected |
| `4` | rejected | rejected | rejected |
| `100` | rejected | rejected | rejected |

Suites: `CampaignDailyDialLimitValidationTest` (new — DTO, previously untested),
`CampaignDailyDialLimitServiceTest` (service), `DailyDialLimitSnapshotPostgresIntegrationTest`
(database, both constraints by name).

---

## 13. API Examples / Tests

New `CampaignApiSliceTest` (standalone MockMvc, `ContactGroupMemberApiSliceTest` pattern) —
the REST boundary had **no** coverage on arrival:

| Case | Result |
|---|---|
| `POST` `dailyDialLimit` omitted | 201, service receives `null`, response omits the field |
| `POST` = 1 / 2 / 3 | 201, value reaches the service unchanged and is echoed |
| `POST` = 0 / -1 / 4 / 100 | 400 `VALIDATION_ERROR`, `errors[?(@.field=='dailyDialLimit')]`, **service never invoked** |
| `PUT` omitted / 1 / 2 / 3 / 0 / -1 / 4 / 100 | as above |

Validation failures use the **existing** `GlobalExceptionHandler` ProblemDetail contract — no new
error format. Observed code path:
`MethodArgumentNotValidException … codes [DailyDialLimit.createCampaignRequest.dailyDialLimit …]`.

---

## 14. OpenAPI Evidence

The **generated** specification is the source of truth (springdoc metadata endpoint over a real
application context — no hand-maintained spec). `CampaignOpenApiContractTest` asserts on
`/v3/api-docs`:

| Test | Asserts |
|---|---|
| OAS-C1 | `dailyDialLimit` on `CreateCampaignRequest`: `type=integer`, `minimum=1`, `maximum=3`, not `required`, description mentions *Voice Blast* / *1-3* / *3* |
| OAS-C2 | same bounds on `UpdateCampaignRequest` |
| OAS-C3 | present + described on `CampaignResponse` ("Null uses the platform maximum of 3") |
| OAS-C4 | create/update paths still documented, **400 response model preserved**, and both `requestBody` `$ref`s resolve (now with a second assertion on the update `$ref`) |

Nullability is expressed by the field **not** being `required` — the correct OpenAPI representation
of "omitted = platform default".

---

## 15. Tenant Authorization Evidence

No new permissions, no new endpoints, no bypass. `dailyDialLimit` is carried by the existing
campaign DTOs behind the existing `CAMPAIGN_MANAGE` / `CAMPAIGN_EXECUTE` capability checks and the
existing organizational-boundary scoping (`findVisible`, `findByIdAndTenantIdAndDeletedAtIsNull`).
There is no route that exposes the field without the campaign write path, so no tenant can reach
another tenant's campaign configuration. Existing `AuthorizationEnforcementTest`,
`SecuritySliceTest` and the campaign boundary lookups are unchanged and green.

---

## 16. PostgreSQL Migration Evidence

`DailyDialLimitSnapshotPostgresIntegrationTest` boots a `postgres:16-alpine` Testcontainer and runs
the **full Flyway chain from a clean database** (`spring.flyway.enabled=true`,
`ddl-auto=none`):

- `PG-6C2-5` — `flyway_schema_history` contains version `48`; the snapshot table carries exactly one
  `daily_dial_limit` column.
- `PG-6C2-4` — 0 / -1 / 4 rejected by **`ck_campaigns_daily_dial_limit`**, and additionally by
  **`ck_cec_daily_dial_limit`** on `campaign_execution_configurations` (newly added: the frozen
  configuration cannot receive an invalid value either). Assertion strengthened to name the
  constraint that fired. `NULL` and 1–3 remain writable.
- `PG-6C2-1/2/3` — full create → schedule → execute → snapshot → resolve round trip on real
  PostgreSQL.

---

## 17. VB-6C.1 Regression Evidence — 53 tests, 0 failures

| §25 requirement | Test |
|---|---|
| limit 3 permits at most 3 | `PG-DL2` — 20 concurrent workers, exactly 3 admissions |
| limit 2 / limit 1 enforce below 3 | `PG-DL8`; `DailyDialLimitSnapshotPostgresIntegrationTest` PG-6C2-3 |
| null behaves as 3 | `DailyDialLimitServiceTest.EffectiveLimit`; PG-6C2-2 |
| cross-campaign bucket sharing | `PG-DL4` |
| different DNIDs independent | `PG-DL5` |
| tenant isolation | `PG-DL6` |
| provider acceptance is the usage boundary | `PG-DL9`, `OutboundDialServiceRoutingTest.DailyLimitHoldLifecycle` |
| pre-acceptance failure consumes zero | `PG-DL9`, `TemporaryFailures` |
| duplicate acceptance exactly-once | `PG-DL3`, `PG-DL12` |
| actual route DID is the bucket key | `PG-DL5`, `OutboundDialServiceRoutingTest` |
| timezone behaviour unchanged | `DailyDialLimitServiceTest.Timezone`, `PG-DL10`, `PG-DL11` |
| `DAILY_LIMIT_REACHED` non-retryable | `PermanentFailures`, `PlayfileRetrySemanticsTest` |
| Contact Center unaffected | no Contact Center code path touched; `CallType.VOICE_BLAST` scope unchanged |

---

## 18. Architecture Result

`ArchitectureTest` — **1/0/0/0, 0 cycles** (`ApplicationModules.verify()`). No `Campaign ↔ Telephony`
concrete dependency was introduced; the field travels inside the existing campaign-owned
`CampaignRuntimeConfig` record, and the voice/telephony boundary is untouched.

---

## 19. Known Limitations

1. **`OpenApiDumpIT` is a leftover debug harness** (prints a slice of the spec to stdout). It passes
   and was left in place rather than deleted, but it is not a real assertion suite.
2. **The limit is not editable while a campaign is SCHEDULED** — a consequence of VB-6A editability
   (DRAFT only), not of this phase. Practically, the limit must be set before scheduling.
3. **Degenerate configuration** (two same-tenant campaigns sharing contact + DNID with different
   snapshot timezones) can disagree on day boundaries — inherited from VB-6C.1 and still accepted
   per audit §22.
4. `CampaignResponse` documents no `minimum`/`maximum` (only the request schemas do). Intentional:
   a response never carries an out-of-range value, and the bound is stated in the description.

## 20. Deferred Work

**Nothing deferred inside VB-6C.2.** All of §29's non-goals remain untouched: no VB-6C.3
observability, no reconciliation sweeper, no reservation cleanup scheduler, no scheduler rewrite,
no distributed locking, no Redis/Kafka/Kubernetes work, no Contact Center / AI / Omnichannel limits,
no IVR, no `MISSED_CALL`, no webhooks, no report privacy, no max-call-duration, no audience/file
import, no Contact redesign, no configuration versioning or history, no new retry/routing/capacity
logic, no new FreeSWITCH behaviour. Unrelated code was not "cleaned up".

## 21. Exact Next Step

**VB-6C.3 — observability for the daily dial limit** (metrics/alerts on
`DAILY_LIMIT_REACHED` and `EXECUTION_TIMEZONE_INVALID` rates, plus ledger reconciliation visibility).
Not started.

---

## 22. Verdict

All completion criteria are met: `dailyDialLimit` is persisted; `null`/1/2/3 semantics are correct;
invalid values are rejected at three boundaries; execution snapshots freeze the configured value;
the runtime consumes the snapshot and never the live campaign; VB-6C.1 runtime enforcement still
passes; the REST API and the **generated** OpenAPI are documented and verified; tenant authorization
is unchanged; the PostgreSQL migration chain is green from a clean database; `ArchitectureTest` is
cycle-free; and the full Maven regression is green.

**READY for VB-6C.3.**
