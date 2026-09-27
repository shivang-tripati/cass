# VB-6C.1 — Voice Blast Daily Dial Limit: Ledger + Atomic Admission — Implementation Report

**Status: COMPLETE.** The daily-limit runtime invariant is demonstrated under real PostgreSQL concurrency; the complete regression suite remains green. Final recommendation for VB-6C.2: **READY**.

---

## 1. Baseline (before this phase)

| Item | Value | Source |
|---|---|---|
| Full suite (end of VB-6B.2) | 950 tests / 0 failures / 0 errors / 1 skipped | `docs/VB-6C-DAILY-DIAL-LIMITS-RETRY-AUDIT.md` §2 |
| ArchitectureTest | 1/0/0/0 — 0 cycles | audit §2 |
| Flyway head | V46 (`V46__contact_identity_and_group_membership.sql`) | `db/migration/` |

## 2. Final Results (after this phase)

| Item | Value |
|---|---|
| Full Maven suite | **981 tests / 0 failures / 0 errors / 1 skipped — BUILD SUCCESS** |
| ArchitectureTest | **1/0/0/0 — 0 cycles** (`ApplicationModules.verify()`) |
| Flyway head | **V47** (`V47__create_voice_blast_daily_usage.sql`) |
| Focused VB-6C.1 tests | 12 PG integration + 20 unit = 32 new tests, all green |
| No test suppressed, disabled, weakened, or deleted | Confirmed — Surefire config untouched |

---

## 3. Implementation Summary

VB-6C.1 delivers the runtime foundation only (no API, no config field — VB-6C.2 scope):

| Component | Kind | Purpose |
|---|---|---|
| `V47__create_voice_blast_daily_usage.sql` | NEW migration | Ledger schema + all invariants as DB constraints |
| `VoiceBlastDailyUsage` | NEW entity | Atomic daily bucket `(tenant, contact, actualDID, usageDate)` |
| `VoiceBlastDailyUsageEntry` | NEW entity | Physical per-attempt usage row, `UNIQUE(call_attempt_id)` |
| `VoiceBlastDailyUsageRepository` | NEW repo | Atomic conditional `reserve` / `confirmUsed` / `releaseReservation` + `insertBucketRow` |
| `VoiceBlastDailyUsageEntryRepository` | NEW repo | Entry reads + `existsByCallAttemptId` fast path |
| `DailyDialLimitService` | NEW service | The campaign-owned policy boundary: admission, confirmation, release, effective limit, usage-day resolution |
| `ExecutionTimezoneInvalidException` | NEW exception | Deterministic invalid/missing timezone failure |
| `CallFailureCode.DAILY_LIMIT_REACHED` | EXTEND | Canonical code, `RetryClass.PERMANENT` (deliberate — see §8) |
| `CallFailureCode.EXECUTION_TIMEZONE_INVALID` | EXTEND | Canonical code, `RetryClass.PERMANENT` |
| `OutboundDialService` | EXTEND | Admission after route selection; usage at `+OK`; hold release on every pre-acceptance failure |
| `CampaignReadinessService` | EXTEND | Voice Blast readiness requires snapshot timezone (`SCHEDULE_TIMEZONE_REQUIRED`) |
| Tests | NEW + EXTEND | Unit + real-PostgreSQL concurrency suite |

## 4. Persistence Design (audit §8 ambiguity resolved)

**Two relational structures — both required, neither sufficient alone:**

1. **`voice_blast_daily_usage`** (the bucket) — PK `id`, `UNIQUE (tenant_id, contact_id, did_id, usage_date)`, `reserved_count INT >= 0`, `used_count INT >= 0` (CHECK-enforced). This carries the **atomic-admission** guarantee: the conditional UPDATE makes PostgreSQL's row lock the over-admission authority. No read-then-write race exists anywhere in the design.

2. **`voice_blast_daily_usage_entries`** (the ledger) — `UNIQUE (call_attempt_id)` plus denormalized bucket columns, `provider_call_id`, audit stamps. This carries the **per-attempt idempotency** guarantee: a replayed acceptance physically cannot create a second row, independent of any application guard.

Why not one table: a pure counter cannot express "this attempt was already counted" (no per-attempt key to violate), and a pure per-attempt ledger cannot enforce `usage < limit` atomically under 20 workers without a locking protocol. The pair gives both guarantees with the smallest normalized shape. **No JSON arrays, no application-only counters, no Redis/Kafka/distributed locks.**

Tenant isolation: `tenant_id` is part of the unique key AND every statement is fully parameterized by tenant (verified by unit test `bucketKeyIsFullyParameterized` and PG test `tenantAndContactIsolation`). FK to `tenants (id)`; `contact_id`/`did_id` are plain UUID references following the `call_attempts` V22 precedent — composite FKs would require new `UNIQUE (id, tenant_id)` keys on `contacts`/`dids`, which §13 forbids modifying.

Physical rows, no soft delete (same rationale as `contact_group_members`, V46): usage rows are accounting facts, not lifecycle entities.

## 5. Transaction Model & Provider-Acceptance Boundary

The lifecycle is **admission (hold) → acceptance (count) / failure (release)**:

```
route selection (actual DNID known)
   → reserve:   UPDATE ... SET reserved_count = reserved_count + 1
                WHERE bucket AND reserved_count + used_count < :effectiveLimit
   → originate (+OK handling unchanged)
   → +OK  → confirmUsed: used_count+1, reserved_count-1 (guarded reserved_count > 0)
            + INSERT entry UNIQUE(call_attempt_id)   [same transaction as providerCallId stamp]
   → any pre-acceptance failure (BUSY/NO_ANSWER/REJECTED/PROVIDER_UNAVAILABLE/FAILED/
     capacity reject, dialer exception) → releaseReservation: reserved_count-1
```

- **Counting happens exactly at the `DIAL_REQUEST_ACCEPTED` branch** — the ESL originate `+OK <uuid>` receipt, materialized as `attempt.setProviderCallId(...)` (audit §10 boundary). Post-acceptance outcomes (ring, no-answer, busy, hangup, ESL failures) count; they can never release.
- **Nothing before acceptance consumes a slot** — verified by PG-DL9 (BUSY → `used_count = 0`, `reserved_count = 0`) and unit `DailyLimitHoldLifecycle`.
- Admission is a **hold**, not usage: a capacity rejection or originate failure returns it. A crash between reserve and confirm leaks a hold (bounded by one dial, released at next conditional transition of the row; documented limitation §13).
- Single transaction: the `+OK` confirm (+1 entry, +1 used) commits in the same transaction as the attempt save that stamps `providerCallId` — usage and attempt state are consistent by construction (audit §17 recommendation adopted).

## 6. Timezone Decision (audit §2 — explicit, no fallback)

- **Authoritative zone:** the execution snapshot's `ScheduleSpec.timezone` (existing, IANA) — read through the existing VB-6A snapshot seam (`CampaignRuntimeConfigResolver` → `CampaignRuntimeConfig.schedule()`), no new timezone source, no Contact Center change.
- **Day computation:** `LocalDate.now(clock.withZone(snapshotZone))` in `DailyDialLimitService.resolveUsageDate`.
- **Null/blank/invalid zone:** the dial fails **deterministically** — `EXECUTION_TIMEZONE_INVALID`, permanent, before any admission, routing, or dialing (PG-DL10 proves no bucket row is ever created). **No JVM/UTC fallback** — an ambiguous day boundary is a compliance hazard.
- **Readiness gate (narrowest existing boundary):** `CampaignReadinessService.checkScheduleReadiness` now adds `SCHEDULE_TIMEZONE_REQUIRED` for all three campaign types (PLAYFILE/DTMF/CONNECT_BY_AGENT — the Voice Blast execution paths) when the schedule has no timezone. This extends the pre-existing rule ("a configured window requires a timezone") rather than creating a duplicate validation mechanism, and fails executions at request time instead of dial time.
- A test suite ran across a real Kolkata midnight during development and caught the boundary live — the day is now pinned via an injectable `Clock` (test-visible constructor).

## 7. Actual DNID, Not Requested DID (audit §5)

The bucket key uses `selectedRoute.didId()` — the actual outbound DNID routing selected (`VoiceRoute.didRowId`, which carries the profile-pinned DID when a `VoiceRouteProfileEntry` pins one). Fallback to the snapshot DID exists only if a route carries no DID (defensive; cannot occur through `VoiceRoutingService` today). Routing behavior itself is untouched. Unit test `routeDidSubstitution_usesRouteDidForBucket` proves both admission and usage key on the route DID and **never** on the requested DID.

## 8. Retry Behavior (audit §6/§7 — DAILY_LIMIT_REACHED semantics)

`DAILY_LIMIT_REACHED` is classified **`RetryClass.PERMANENT`** — a deliberate, tested semantic:

- A same-day retry with the same snapshot (same DNID) **cannot succeed**; the bucket only empties at the next calendar day or when routing resolves a different DNID. The existing `isPermanentFailure` retry gate is exactly the mechanism that prevents a same-day retry loop — no retry-scheduler change needed (smallest change, audit §6).
- The "temporary/requeue-like" aspect from the audit is preserved *by construction*: the code is a **pre-provider rejection** — nothing was dialed, no slot consumed, and tomorrow (or another DNID) starts from a fresh bucket. PG-DL7 proves the day-boundary reset.
- `EXECUTION_TIMEZONE_INVALID` is likewise PERMANENT (fixing campaign config is the only remedy).
- Both classifications are pinned by retry tests (`dailyLimitReachedNeverRetriedSameDay`, `executionTimezoneInvalidNeverRetried`).

## 9. CONNECT_BY_AGENT Handling (audit §15)

No change was needed or made. Inspection confirms the customer leg of a CONNECT_BY_AGENT execution is a campaign Voice Blast outbound attempt that flows through the same `OutboundDialService.processAttempt` boundary (attempt rows created by the orchestrator from the snapshot; dial via the same pipeline; `CallType.VOICE_BLAST`) — so it correctly consumes the bucket via the shared boundary. The **agent leg** is originated later by `ConnectByAgentService` through `AgentLegDialer` — a completely separate code path that never touches `OutboundDialService` or the ledger — so an agent leg can never consume a customer slot. Agent-leg behavior unchanged.

## 10. Concurrency Evidence (PG-DL2 — proven from PostgreSQL state)

20 concurrent worker threads (latched start), limit 3, same `(tenant, contact, did, day)`:

| Asserted from DB | Result |
|---|---|
| Successful admissions | **exactly 3** (never 4) |
| `voice_blast_daily_usage.used_count` | **3** |
| `reserved_count` after confirmations | 0 |
| `voice_blast_daily_usage_entries` rows | 3 (one per distinct attempt) |

Mechanism: each worker runs `admit` in its own transaction; the conditional UPDATE row-locks the bucket; at 2 held + the 3rd grant, every loser's `WHERE reserved_count + used_count < 3` matches 0 rows. No lost updates, no over-admission, no advisory locks, no new infrastructure. Further: PG-DL8 (limit 2 enforced by the same conditional), PG-DL12 (6 concurrent duplicate confirmations of ONE attempt → exactly 1 usage row — duplicate safety under concurrency).

## 11. Duplicate / Idempotency Evidence

- **Sequential replay** (PG-DL3): same attempt's acceptance processed twice → 1 entry, `used_count = 1`.
- **Concurrent replay** (PG-DL12): 6 workers confirming the same acceptance → 1 entry, `used_count = 1`.
- Layers: (1) `existsByCallAttemptId` fast path — no-op before any write; (2) the confirm increment is guarded by the caller's hold (`reserved_count > 0`); (3) `UNIQUE (call_attempt_id)` — the physical backstop; the racing loser rolls back without having counted (its guarded increment matched 0 rows — the winner held the only reservation).
- Unit `duplicateConfirmationSuppressed` pins the catch-path behavior.

## 12. Test Matrix (audit §18 mapping)

**Unit (all green):** effective limit = 3 / below 3 / cannot exceed 3; bucket-key construction + full parameterization (tenant/contact/DID isolation); usage-day in snapshot IANA zone (`UTC` vs `Asia/Kolkata` with a fixed clock); null/blank/invalid timezone fail-closed; admission admitted vs `DAILY_LIMIT_REACHED`; confirm writes entry+increment; duplicate suppressed; `EXECUTION_TIMEZONE_INVALID` fails without dialing or admitting; `DAILY_LIMIT_REACHED` fails permanently without dialing, without releasing (nothing held); accepted dial keeps IN_PROGRESS + `providerCallId` + exactly one `confirmAccepted`; capacity/provider-unavailable/dialer-exception/busy release the hold without confirming; route-DID substitution keys on actual DID; retries never scheduled for `DAILY_LIMIT_REACHED`/`EXECUTION_TIMEZONE_INVALID`.

**PostgreSQL integration (all green, `postgres:16-alpine`, full Flyway chain V1..V47):** bucket creation + unique identity (PG-DL1); **20 concurrent workers / limit 3 → exactly 3** (PG-DL2); duplicate acceptance no-double-count (PG-DL3); cross-campaign shared bucket (PG-DL4); different DNIDs independent (PG-DL5); tenant + contact isolation (PG-DL6); midnight/calendar-day boundary reset (PG-DL7); stricter effective limit (PG-DL8); end-to-end dial path — pre-acceptance BUSY consumes nothing, `+OK` consumes exactly one, 4th admission → `DAILY_LIMIT_REACHED` with no dial (PG-DL9); invalid snapshot timezone fails deterministically with zero buckets (PG-DL10); usage date computed in snapshot timezone end-to-end (PG-DL11); concurrent duplicate confirmations (PG-DL12).

## 13. Known Limitations

1. **Hold leak on hard crash** between reserve and confirm/release leaves `reserved_count` inflated until the bucket row next transitions; bounded by one dial per affected bucket, self-heals at the day boundary. (A reconciliation sweeper is deliberately out of scope — no new scheduler in this phase.)
2. **Campaign-specific limit not yet configurable** — `effectiveLimit(null) = 3` always in this phase; the service accepts the snapshot value already (`effectiveLimit(Integer)`), VB-6C.2 threads it through.
3. **Degenerate config** (two same-tenant campaigns sharing contact+DNID with different snapshot timezones disagree on day boundaries) — accepted + documented per audit §22; a tenant-zone rule belongs to 6C.2 if product rejects the edge.
4. **Multi-JVM dial dispatch** remains a pre-existing single-JVM assumption (audit §14); the ledger itself is multi-JVM-safe.

## 14. Non-Goals Honored

No campaign API/DTO/OpenAPI changes; no `dailyDialLimit` field; no MISSED_CALL; no audience/import/IVR/webhook/privacy/max-duration changes; no Contact/ContactGroup/Contact Center/AI limits; no Redis/Kafka/distributed locking/Kubernetes/new microservice; no configuration versioning; no legacy shims; no scheduler rewrite; no Contact redesign; no routing or ESL or capacity redesign; no reporting dashboards. **STOP after VB-6C.1.**

## 15. Exact Next Step

**VB-6C.2 — Campaign `dailyDialLimit` configuration + API.** Add the validated column to campaigns + snapshot embeddable + `CampaignRuntimeConfig`; wire `OutboundDialService`'s `effectiveLimit(null)` call site to `effectiveLimit(campaign.dailyDialLimit())`; DTO/validation/OpenAPI per the VB-6B.2 standing rule; edge cases A–D (snapshot immutability) re-verified; API contract tests.

**Recommendation: READY for VB-6C.2.**
