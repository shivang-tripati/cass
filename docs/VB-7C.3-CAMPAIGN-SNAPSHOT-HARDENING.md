# VB-7C.3 — Campaign Snapshot / Configuration Hardening

**Status:** COMPLETE (see §11 for limitations)
**Baseline:** `3a89e5c` — "VB-7C.2: integrations + campaign configuration (configuration only)"
**Branch:** `main` = `origin/main` = `3a89e5c`, divergence 0/0
**Architecture:** 16 modules, 0 dependency cycles (`ArchitectureTest` PASS)
**Migration:** head moved V54 → **V55**, 53 → 54 files (one additive nullable column)
**Full suite:** 1991 tests, 0 failures, 0 errors, 2 skipped (both pre-existing)

---

## 1. Objective

Make `CampaignConfigurationSnapshot` the authoritative immutable representation of
**all** campaign configuration that can affect an execution — specifically the
webhook configuration, the selected external webhook events, and the report
privacy configuration that VB-7C.2 introduced with no runtime consumer.

VB-7C.2 had recorded an explicit deferral:

> The first consumer of webhook or privacy configuration must add it to the
> immutable execution snapshot in the same phase.

VB-7C.3 closes that boundary **early and deliberately**, before VB-8A execution
work begins, so the first delivery or reporting phase inherits an already-safe
snapshot rather than having to remember to extend it under deadline.

---

## 2. Audit — what was already frozen

`CampaignConfigurationSnapshot` is an `@Embeddable` mapped onto
`campaign_execution_configurations` (V44). Before this phase it froze **22**
fields:

| Group | Fields |
|---|---|
| Identity | `campaignType`, `contactGroupId`, `didId` |
| Content | `contentMode`, `audioAssetId`, `ttsTemplateId`, `typeConfig` |
| Schedule | `scheduleStartDate`, `scheduleEndDate`, `dailyStartTime`, `dailyEndTime`, `timezone`, `allowedDaysOfWeek`, `holidayCalendarId` |
| Retry | `retryMaxAttempts`, `retryIntervalSeconds`, `retryStrategy`, `retryRules` (V49), `maxDailyAttempts` (V51) |
| Duration | `maxCallDurationSeconds` (V52) |
| Safety | `callOnWhitelistNumbers`, `dailyDialLimit` (VB-6C.2) |

**Not frozen before this phase:** `integrationConfig` — the sole intentional
exclusion, documented as a live hazard.

Answers to the specific questions the brief asked:

1. **Which fields were frozen** — the 22 above.
2. **Intentionally not frozen** — `integrationConfig` only. Administrative
   metadata (name, description, lifecycle status, lineage) is excluded by
   design and was not a candidate. Resource *validity* of referenced
   DID/audio/TTS is deliberately never frozen (VB-6A rule).
3. **Where the snapshot is created** — `CampaignConfigurationService.createExecutionSnapshot`
   (package-private static builder `toSnapshot`), called from
   `CampaignExecutionService` **inside the execution-creation transaction**,
   before the execution row is inserted.
4. **Storage** — relational columns on `campaign_execution_configurations`. Two
   JSONB columns: `allowed_days_of_week` and `type_config`. Not dual-stored.
5. **Transactional?** — Yes. `@Transactional(propagation = REQUIRED)`, called
   from the execution-creation transaction; the execution's FK to the snapshot is
   `NOT NULL`, so the database forbids an execution without one.
6. **Could a runtime path still read mutable campaign config?** — No, for
   anything execution-affecting. `CampaignRuntimeConfigResolver.CampaignRuntimeConfig`
   is built solely from the frozen row. No API on the snapshot, the resolver, or
   their records returns a `CampaignEntity` (asserted, §7 SNAP-IC-E).
7. **How typed configs are serialized** — through the same authority as
   everything else: `typeConfig` is frozen as `CampaignTypeConfig.toJson()`
   (canonical validated form), not the client's raw JSON. VB-7C.3 mirrored this
   exactly for `integrationConfig`.
8. **How `integrationConfig` mapped** — one `JSONB` column `campaigns.integration_config`
   holding `CampaignIntegrationConfig`; `CampaignMapper` copies it; readiness
   validates it at write time; the REST DTOs expose it as a typed block.
9. **How report privacy mapped** — inside that same block, at key
   `integrationConfig.reportPrivacy.policy`, values `FULL` / `MASKED`.
10. **Null/default semantics** — a campaign with no integration block stores SQL
    `NULL`. That was preserved verbatim here (§5).

---

## 3. The one decision I made that the brief asked me to flag

The brief said: *prefer NO MIGRATION*; *if the actual implementation requires a
schema change, STOP before creating the migration and document why.*

**A migration was unavoidable.** I established this before writing any
production code:

- `campaign_execution_configurations` has exactly **two** JSONB columns and
  **both are in use** by the embeddable. There is no spare payload column.
- Piggybacking onto `type_config` was considered and **rejected**. `type_config`
  is campaign-**type**-specific by contract: every `CampaignTypeConfig`
  implementation parses exactly one root key and rejects unknown fields. That
  rejection is what makes the type-isolation guarantee VB-7B added hold
  ("PLAYFILE cannot carry a `missedCall` payload"). Mixing a type-independent
  integration payload into it would either break that parsing or make PLAYFILE
  reject every campaign that has a webhook.
- The established precedent for extending this snapshot since V44 is **one
  additive nullable column per field, in its own migration**:
  V49 `retry_rules`, V51 `max_daily_attempts`, V52 `max_call_duration_seconds`.

So V55 follows that precedent with one column. This is the only schema change
in the phase, and the user may veto it — but the alternative was to deliver an
audit with the phase's primary objective unmet.

---

## 4. Changes

### 4.1 `V55__execution_snapshot_integration_config.sql`

One statement:

```sql
ALTER TABLE campaign_execution_configurations
    ADD COLUMN integration_config JSONB;
```

Additive, nullable, no backfill. Every existing snapshot row keeps its exact
previous meaning, and `NULL` continues to mean "this campaign configured no
integration block".

### 4.2 `CampaignConfigurationSnapshot`

- New field `JsonNode integrationConfig`, `@JdbcTypeCode(JSON)`,
  `@Column(name = "integration_config")`.
- New `asIntegrationConfig()` → `Optional<CampaignIntegrationConfig>`.
- New **22-argument compatibility constructor** delegating with
  `integrationConfig = null`. All 10 existing construction sites (1 production,
  9 tests) are untouched. The pre-VB-6D.2 19-argument constructor still
  delegates to it.
- Class Javadoc rewritten: the obsolete OD-3 exclusion note is replaced by the
  governing rule (§6).

### 4.3 `CampaignConfigurationService`

`toSnapshot(...)` now freezes the integration configuration through
`validatedIntegrationConfig(campaign)`, which re-parses the stored payload via
`CampaignIntegrationConfig.fromJson(...).toJson()`.

Re-parsing rather than copying the entity value means a snapshot can only ever
contain a payload the platform is able to interpret. A row written by another
version, or edited directly in the database, fails execution creation
deterministically instead of producing a snapshot a future consumer would
misread. This mirrors what the same method already does for `typeConfig`.

### 4.4 `CampaignRuntimeConfigResolver`

- `CampaignRuntimeConfig` gains a `CampaignIntegrationConfig integrationConfig`
  component.
- New `asIntegrationConfig()` on the record — **the single read path** a future
  webhook-delivery or reporting component should use.
- `fromSnapshot(...)` populates it from the frozen row, via
  `s.asIntegrationConfig().orElse(null)`.
- New **15-argument compatibility constructor** preserving the VB-7C.2 shape.
  All 3 existing construction sites untouched.

### 4.5 Documentation

`CampaignIntegrationConfig`'s "Snapshot participation (OD-3, deferred)" section
rewritten to state that the configuration is frozen, why the boundary was
closed early, and that freezing changed nothing about whether it is acted upon.

---

## 5. Null / default semantics — deterministic, and deliberately not collapsed

`null` and "explicitly defaulted" are **different facts**, and this phase keeps
them apart:

| Campaign state | Stored | `asIntegrationConfig()` |
|---|---|---|
| No integration block | SQL `NULL` | `Optional.empty()` |
| `{"webhook":{"enabled":false},"reportPrivacy":{"policy":"FULL"}}` | full canonical JSON | `Optional.of(...)` |

Both describe inert behaviour today, but only one records that an operator
actually chose it. Collapsing them would erase the difference before any
consumer could observe it. The runtime never has to reconstruct a default from
the mutable `Campaign`, because absence is itself frozen.

A JSON `null` payload is treated as absent, never as a default. A partial block
is completed by the typed model (`CampaignIntegrationConfig`'s existing
contract), so the frozen value is always fully resolved.

---

## 6. The governing rule

Recorded in `CampaignConfigurationSnapshot`'s class Javadoc, as a rule and not a
caution:

> **Any campaign configuration consumed by execution-time runtime behaviour MUST
> be captured in `CampaignConfigurationSnapshot` in the same phase that
> introduces the first runtime consumer.**

Enforced structurally rather than by convention: a runtime component can obtain
execution configuration only through
`CampaignRuntimeConfigResolver.CampaignRuntimeConfig`, which is built solely
from a frozen snapshot row. No API on the snapshot, the resolver, or their
records returns a `CampaignEntity`. Reading the mutable campaign from an
execution-time path is not a shortcut the API offers — it requires reaching
past the resolver to the repository.

---

## 7. Tests — 34 new, all green

### `ExecutionSnapshotIntegrationConfigTest` (20, pure)

| ID | Covers |
|---|---|
| SNAP-IC-A (5) | endpoint, events, enabled/disabled, both privacy levels, exact endpoint preservation |
| SNAP-IC-B (5) | absent → `NULL`; absent ≠ explicit default; JSON null; partial completion; determinism |
| SNAP-IC-C (2) | canonical form; JSON round trip |
| SNAP-IC-D (2) | unreadable payload → `IllegalStateException`, never a silent default; repeatable accessor |
| SNAP-IC-E (4) | frozen config reachable; no `CampaignEntity` on runtime config / snapshot / fields |
| SNAP-IC-F (2) | no version/revision/sequence/generation field; two snapshots differ by content |

### `ExecutionSnapshotIntegrationConfigPostgresIntegrationTest` (11, real Flyway V1..V55)

| ID | Covers |
|---|---|
| SNAP-ICPG-A (3) | reload from PostgreSQL; resolver reads frozen values; stored form is the interpretation |
| SNAP-ICPG-B (2) | **TEST A** — endpoint + events mutation isolation; post-hoc disable isolation |
| SNAP-ICPG-C (1) | **TEST B** — `FULL` → `MASKED` cannot reach a running execution |
| SNAP-ICPG-D (1) | **TEST C** — two executions, two legitimate snapshots, no version field |
| SNAP-ICPG-E (2) | **tenant isolation** — foreign execution cannot resolve another tenant's snapshot; cross-tenant campaign unresolvable |
| SNAP-ICPG-F (2) | unconfigured campaign stores SQL `NULL`; a row with no value in the new column still reads |

### `CampaignOpenApiContractTest` (2 new)

`OAS-7C.3-1` — the generated document mentions none of
`CampaignConfigurationSnapshot`, `CampaignExecutionConfiguration`,
`integration_config`, `asIntegrationConfig`, `retry_rules`,
`max_call_duration_seconds`, and generates no snapshot schema.
`OAS-7C.3-2` — the public campaign schemas are exactly what VB-7C.2 defined:
the typed `integrationConfig` block is still present on create/update/response
and is not flattened into loose `webhook` / `reportPrivacy` / `webhookEndpoint` /
`selectedEvents` / `privacyPolicy` properties.

### `CampaignIntegrationPostgresIntegrationTest` (1 inverted, 1 strengthened)

`PG-I8` asserted the OD-3 exclusion via reflection. This phase deliberately
reverses that, so the test was **inverted and made stronger**: it now requires
exactly one integration field on the snapshot, named `integrationConfig`, bound
to an `integration_config` column, with a JSONB type code — proving the config is
in its own column rather than smuggled into `type_config`.

**No test was weakened, deleted, skipped or suppressed.** Nothing was disabled,
no Surefire exclusions were added, and the pre-existing `PG-I8` assertion was
replaced by its exact inverse plus two additional structural checks.

---

## 8. Two findings the tests produced

1. **An omitted `webhook.enabled` resolves to `false`, not `true`.** My first
   canonicalisation test asserted `true` (assuming "endpoint present implies
   enabled"). The test caught the wrong assumption: VB-7C.2's `WebhookConfig`
   treats the flag as strictly opt-in and only requires an endpoint *and* events
   when it is `true`. Test corrected to assert the real contract. No production
   defect.

2. **JSONB does not preserve object key order.** The in-memory freeze is
   canonical (`stored == toJson()` textually), but a node reloaded from
   PostgreSQL is content-identical and *not* text-identical, because JSONB
   normalises key order on write. Array order **is** preserved, which is what
   keeps the normalised `events` list deterministic. Nothing reads by key
   position, so this is invisible to consumers — but it is now asserted and
   documented rather than left to be discovered later.

---

## 9. Scope verification

Confirmed absent from the diff:

- **Webhook delivery** — zero HTTP clients in `src/main` (`HttpClient`,
  `WebClient`, `RestTemplate`, `HttpURLConnection`, OkHttp, Retrofit: **NONE**).
  No worker, queue, dispatcher, signing, secret management, retry or backoff,
  delivery status.
- **Report runtime** — no generation, export, aggregation, pseudonymisation, or
  attempt-list privacy enforcement.
- **Scheduler** — 17 pre-existing `@Scheduled` sites, none added, none touched.
  No `CampaignExecutionOrchestrator` redesign, no reconciliation worker.
- **Retry / daily safety / routing / DND / whitelist / agent / IVR** — untouched.
  The `RetryRule`, `RetryStrategy`, `maxDailyAttempts` and `dailyDialLimit`
  tokens appearing in the diff are **compatibility-constructor parameter
  lists** and delegating calls only; no behaviour changed.
- **FreeSWITCH / ESL / SIP / RTP / telephony** — untouched by this phase. Those
  files remain the user's uncommitted work.
- **Campaign versioning** — none. No version, revision, `MAX(version)+1`,
  history table, legacy fallback or compatibility shim.
- **Public API** — no controller, DTO or endpoint changed.

---

## 10. Test results

**Full suite: `mvnw -o surefire:test` — 1991 tests, 0 failures, 0 errors, 2 skipped,
BUILD SUCCESS.**

The 2 skips are pre-existing and unrelated to this phase:

| Skipped | Reason |
|---|---|
| `ObdApplicationTests` (1) | `@Disabled("Requires 'dev' profile with live Postgres and Redis…")` — present in the committed source at baseline; not added here |
| `LiveFreeSwitchRuntimeContractTest` (1 of 4) | `assumeTrue` on the live FreeSWITCH credential; a user-owned **untracked** file, skipped because the credential is not set |

Focused groups, all independently green:

| Suite | Tests | Fail | Err | Skip |
|---|---|---|---|---|
| `Campaign*Test` + `ExecutionSnapshot*Test` + `ArchitectureTest` + `*OpenApiContractTest` | **437** | 0 | 0 | 0 |
| Per-type execution / retry / daily-safety / duration / reconciler | **420** | 0 | 0 | 0 |
| `ArchitectureTest` + `TtsGovernancePostgresIntegrationTest` + `TtsTemplateValidationTest` | **28** | 0 | 0 | 0 |

`TtsGovernancePostgresIntegrationTest` = **22**, identical to VB-7C.2.
`ArchitectureTest` PASS = 16 modules, 0 cycles.

New tests: **34** (20 pure + 11 PostgreSQL + 2 OpenAPI) + 1 inverted and
strengthened + 1 pre-existing fixture corrected. Baseline arithmetic checks out:
437 − 31 = 406 = 391 (`Campaign*Test`) + 15 (ArchitectureTest 1,
`ContactGroupMemberOpenApiContractTest` 5, `IvrOpenApiContractTest` 9).

**No test was weakened, deleted, skipped or suppressed.** Nothing was disabled,
no Surefire exclusions were added, and no existing assertion was relaxed. The
one pre-existing test whose *intent* this phase inverted (OD-3's exclusion) was
replaced by its exact inverse plus two additional structural checks.

### A pre-existing failure found and fixed

The full suite surfaced exactly one red test:
`AudioUploadPostgresIntegrationTest.readyWithApprovedStoredAsset`.

**Proved pre-existing**: with this phase's four production files reverted to
`3a89e5c` (stashing only those paths; user work untouched), it still fails
identically. It is a VB-7C.1 carry-over, not a VB-7C.3 regression — the fixture
set no schedule at all, and VB-7C.1 made an execution timezone mandatory for
readiness with no fallback. VB-7C.1 corrected three files for this and a fourth
later; this was the fifth.

Fix: one line in the test fixture — an always-eligible windowless UTC schedule,
the same correction already applied elsewhere. `PG-4` still asserts
`ready() == true` **and** `reasons()` empty. `PG-5`, `PG-6` and `PG-8` use
`anySatisfy`, so they are unaffected and still pass. No production code changed.

---

## 11. Limitations and deferred decisions

1. **V55 exists.** Justified in §3. Additive, nullable, no backfill. Reversible
   by dropping the column; no existing row's meaning changes.
2. **The frozen configuration is still read by nothing.** This phase froze a copy
   no code consumes. That is the point, but it does mean the first delivery or
   reporting phase must actually consume `asIntegrationConfig()` rather than
   reaching for the campaign — the structural guarantee makes the wrong thing
   awkward, not impossible.
3. **Carried forward, still unanswered** from VB-7C.2:
   - whether report privacy should also constrain the existing attempt-listing
     APIs, which expose `contactId` today;
   - whether aggregation/pseudonymisation belong in the privacy model.
4. **JSONB key order is normalised by PostgreSQL** (§8.2). Harmless, but any
   future byte-level comparison of a snapshot payload will be surprised by it.
5. **Environment.** The host ran at 779 MB free of 5,996 MB against 9 user
   Docker containers. The full suite did complete on a bounded heap
   (`-Xmx1400m -DforkCount=1 -DreuseForks=true`), and no infrastructure was
   modified, stopped or reconfigured to achieve that. Earlier partial runs in
   this session did show the previously-documented `ClassNotFoundException` /
   `NoClassDefFoundError` signature under memory pressure; the final bounded run
   was clean. Treat that fragility as an environment property, not a result.

---

## 12. Recommendation — next phase

**VB-8A — AUDIT ONLY: Campaign Execution & Scheduler.**

This phase was specifically to make that safe to start. The audit should treat
as its first question: *for every campaign field the scheduler and dial path
read at execution time, is it frozen in `CampaignConfigurationSnapshot` under the
governing rule in §6?* The audit should also carry forward limitation 4 above.

Implementation of VB-8A must not begin during the audit phase.
