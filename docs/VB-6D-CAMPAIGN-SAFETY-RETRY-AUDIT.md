# VB-6D — Campaign Safety & Retry Policy — AUDIT

**Phase: AUDIT ONLY. No production code, migration, test, entity, service, controller, or
configuration was modified. The only file written by this phase is this document.**

---

## 0. BLOCKER — Scope conflict requiring confirmation before any implementation

**This phase was instructed to audit "VB-6D = Campaign Safety & Retry Policy", and its §27
explicitly forbids IVR, reusable IVR trees, and multi-level DTMF.**

**However, `docs/campaign-readiness.md` was hand-edited (uncommitted, after commit `619deac`) to
describe VB-6D as "DTMF / IVR Configuration":**

> *"VB-6D — DTMF / IVR Configuration … Build a reusable IVR tree instead of hard-coding DTMF flows
> inside each campaign … Support multi-level DTMF navigation: e.g. 1 → Sales → 2 → Support …
> Campaign references an ivrTreeId … Main goal: turn the current single-level DTMF campaign flow
> into a reusable, configurable multi-level IVR system."*

These two definitions are mutually exclusive. The repository is the source of truth, and the
repository currently says **IVR**; the task brief says **retry/safety** and forbids IVR.

This audit therefore:

- **Completes the requested retry/safety discovery** — it is valuable regardless of which scope wins,
  because the retry defects documented in §3–§13 are real and independent of IVR.
- **Does not assume the scope question is settled.** See §23 Open Decision **OD-1**, which is
  **blocking**. No implementation should start before it is answered.

Working tree at audit time: `619deac` + one uncommitted documentation edit
(`docs/campaign-readiness.md`). The audit left that edit untouched — **it is the user's work, not
mine to revert.**

---

## 1. Executive summary

| Finding | Impact on VB-6D |
|---|---|
| **A retry scheduler already exists and already runs.** `CampaignExecutionOrchestrator.scheduledTick()` is `@Scheduled(fixedDelay = 30000)` and already calls `processRetries()` and `dialService.processDueAttempts()`. | VB-6D does **not** build a scheduler. It supplies *policy* to an existing loop. |
| **Retry configuration already exists end-to-end.** `RetryPolicySpec` on `CampaignEntity`, frozen into `CampaignExecutionConfiguration` (V44), resolved via `CampaignRuntimeConfigResolver`. | **EXTEND** an existing pipeline. Do **not** build a new one. |
| **Retry is a flat policy**: one `maxAttempts` + one `intervalSeconds` + `FIXED` only. No per-rule behaviour exists. | Per-rule retry is genuinely **NEW**. |
| **`failure_code` leaks unbounded dynamic strings.** `mapHangupCauseToCode` falls through to `"HANGUP_" + cause`, which is not in `CallFailureCode`, and therefore classifies as **retryable** by default. | **Blocking prerequisite** for any per-rule retry work (§4.3). |
| **No daily *attempt* counting exists anywhere.** VB-6C counts *provider-accepted dials*, DNID-scoped. | Global daily-attempt policy is genuinely **NEW** and semantically distinct. |
| **Compliance already re-runs on every dial**, so retry cannot bypass it. | **KEEP** — verify, do not rebuild. |
| **No advisory locks / `SKIP LOCKED` / `FOR UPDATE` exist anywhere in 48 migrations.** | The only concurrency precedent is VB-6C's conditional single-statement `UPDATE`. Follow it. |
| **Project docs for scope, architecture and security are 0 bytes.** | Reduces available intent; noted, not fixed here. |

---

## 2. Current baseline

| Item | Value |
|---|---|
| Commit | `619deac` |
| Branch / tracking | `main` → `origin/main`, in sync |
| Working tree | 1 uncommitted doc edit (`docs/campaign-readiness.md`, user-authored) |
| Full suite | 1040 tests / 0 failures / 0 errors / 1 skipped |
| ArchitectureTest | 1/0/0/0 — 0 cycles |
| Flyway head | **V48** (47 applied migrations; `V43` is intentionally absent) |
| Modules | Campaign, Voice Core, Contact, DID, Audio, TTS, Tenant, Reseller, Account, common |
| Declared campaign types | `PLAYFILE`, `DTMF`, `CONNECT_BY_AGENT` (no `MISSED_CALL`) |
| `CallType` | `VOICE_BLAST`, `CONTACT_CENTER_INBOUND`, `CONTACT_CENTER_OUTBOUND`, `AI`, `INTERNAL` |

---

## 3. Current retry architecture

### 3.1 Where retry configuration lives — **EXTEND**

`RetryPolicySpec` (`@Embeddable`, campaign-owned value object):

| Field | Column | Constraint |
|---|---|---|
| `maxAttempts` | `retry_max_attempts` | `NOT NULL DEFAULT 0`, `CHECK BETWEEN 0 AND 10` (V14) |
| `intervalSeconds` | `retry_interval_seconds` | `CHECK (max_attempts = 0 OR (interval IS NOT NULL AND interval > 0))` (V14) |
| `strategy` | `retry_strategy` | `NOT NULL DEFAULT 'FIXED'`, `CHECK IN ('FIXED')` (V14) |

It is a **first-class field on `CampaignEntity`** — **not** inside `typeConfig`
(`typeConfig` carries DTMF/agent type configuration only).

### 3.2 It is already frozen into the execution snapshot — **KEEP**

`CampaignExecutionConfiguration` (V44) already has `retry_max_attempts`,
`retry_interval_seconds`, `retry_strategy`; `CampaignConfigurationSnapshot.retryPolicySpec()` and
`CampaignRuntimeConfigResolver` already carry `RetryPolicySpec retryPolicy`. The immutability
requirement of §9 is **already satisfied for the flat policy** — any per-rule extension must
follow the same freeze path.

### 3.3 The retry algorithm (`CampaignExecutionOrchestrator.processRetriesForExecution`)

Triggered from `scheduledTick()` every 30 s, over all `RUNNING` executions:

1. Load `FAILED` attempts for the execution.
2. Resolve retry policy **from the snapshot** (`runtimeConfigResolver.resolve(execution)`).
3. If `maxAttempts == null || <= 0` → return (no retries configured).
4. `maxTotalAttempts = 1 + maxAttempts` — **per contact, per execution** (attempt numbering is
   `(execution_id, contact_id, attempt_number)`).
5. Skip if `CallFailureCode.isPermanent(failureCode)`.
6. `nextAttemptNumber = attemptNumber + 1`; skip if `> maxTotalAttempts`.
7. **Idempotency**: skip if a row already exists for
   `(execution_id, contact_id, nextAttemptNumber)`.
8. **Re-validate resources against the snapshot**: contact still valid + in the audience group;
   DID still valid.
9. `nextScheduledAt = completedAt + intervalSeconds` (fallback `60`), then
   `adjustToScheduleWindow(config, …)`.
10. **Insert a new `CallAttempt`** with `status = QUEUED`, same `didId` as the *snapshot* DID.

### 3.4 Answers to the §4 questions

| Question | Answer |
|---|---|
| Where is retry config stored? | `campaigns` table (`RetryPolicySpec` embeddable), V14 |
| Part of `CampaignEntity`? | **Yes**, first-class |
| Inside `typeConfig`? | **No** |
| Frozen into `CampaignExecutionConfiguration`? | **Yes** (V44 columns) |
| Resolved through `CampaignRuntimeConfigResolver`? | **Yes** |
| How is retryability decided? | `CallFailureCode.isPermanent(String)` → `RetryClass` |
| How is `maxTotalAttempts` calculated? | `1 + retryPolicy.getMaxAttempts()`, per contact per execution |
| How is `attemptNumber` consumed? | Monotonic per `(execution, contact)`; a retry takes `previous + 1` |
| Does a retry create a new `CallAttempt`? | **Yes** — a new row; the previous stays `FAILED` (history preserved) |
| What happens to the previous attempt? | Untouched, remains `FAILED`; soft-delete columns available but unused here |
| Which failures requeue? | Every code with `RetryClass.TEMPORARY` |
| Which are terminal? | `PERMANENT` set: `REJECTED`, `DIAL_FAILED`, `PLAYBACK_CONFIG_INVALID`, `DTMF_CONFIG_INVALID`, `CONNECT_BY_AGENT_UNSUPPORTED`, `AGENT_CONFIG_INVALID`, `AGENT_ENDPOINT_INVALID`, `AGENT_TENANT_MISMATCH`, `CAMPAIGN_NOT_FOUND`, `EXECUTION_CONFIG_MISSING`, `CONTACT_INVALID`, `EXECUTION_TIMEZONE_INVALID`, **`DAILY_LIMIT_REACHED`** |
| Canonical enums vs strings? | `CallFailureCode` is a **canonical enum**; `CallAttempt.failureCode` is a **plain `String`** (deliberate: forward-compatible, `fromCode` → `Optional.empty()`) |
| Where are classifications defined? | `CallFailureCode.RetryClass` — enum-carried, single source |
| Delays fixed or configurable? | **Configurable**, but **FIXED only** (`RetryStrategy` has exactly one constant) |
| Campaign timezone used? | Indirectly — `adjustToScheduleWindow` clamps the retry into the snapshot's schedule window/timezone |
| Jitter/backoff? | **None.** `RetryStrategy` documents that others "must not be added without a product requirement" |
| Is that relied upon? | Yes — `ck_campaigns_retry_strategy CHECK IN ('FIXED')` hard-bans anything else at the DB level |

### 3.5 Defect found — unreachable `60`-second fallback — **SIMPLIFY** *(later phase)*

```java
Instant nextScheduledAt = baseTime.plusSeconds(intervalSeconds != null ? intervalSeconds : 60);
```

`ck_campaigns_retry_interval` forces `interval IS NOT NULL` whenever `max_attempts > 0`, and step 3
already returned for `max_attempts <= 0`. The `: 60` branch is therefore **unreachable for any
DB-persisted campaign** — a silent magic number. Low risk; flagged, not fixed.

---

## 4. Failure taxonomy audit

### 4.1 Requested VB-6D outcomes vs the current `CallFailureCode`

| VB-6D outcome | Exists? | Current code | Class | Producer |
|---|---|---|---|---|
| `NO_ANSWER` | **Yes** | `NO_ANSWER` | TEMPORARY | dial result, or hangup cause `19` |
| `BUSY` | **Yes** | `BUSY` | TEMPORARY | dial result, or hangup cause `17`/`USER_BUSY` |
| `FAILED` | **Partial** | no generic `FAILED`. `DIAL_FAILED` (PERMANENT) = originate failure; `TEMPORARY_FAILURE` (TEMPORARY) = cause 41 | mixed | dial result; cause `41` |
| `SWITCHED_OFF` | **No** | — | — | — |
| `NOT_REACHABLE` | **No** | — | — | — |
| `HANGUP` | **Partial** | `HANGUP_UNKNOWN`, `CALLER_HANGUP`; **plus dynamic `HANGUP_<cause>`** | TEMPORARY | hangup mapping |

### 4.2 `SWITCHED_OFF` / `NOT_REACHABLE` feasibility — **cannot be classified today**

`EslEventService.mapHangupCauseToCode` maps only `16,17,19,21,34,41,47` (plus the FreeSWITCH
symbolic aliases). The Q.850 causes a carrier would use for these two categories — `27`
(destination off-hook / subscriber unreachable), `15` (unallocated number), `22` (network out of
order), `31` (normal temporary failure) — are **all unmapped**, and FreeSWITCH's symbolic spellings
vary per provider.

**Verdict: `SWITCHED_OFF` and `NOT_REACHABLE` are not real supported outcomes today.** They are
desired business categories requiring provider-specific cause mapping, and they cannot be derived
reliably from what the telephony boundary currently exposes. Classifying them would be
fabrication. See **OD-2**.

### 4.3 Blocking defect — unbounded dynamic failure codes — **REMEDIATED in VB-6D.1** ✅

> **Status: FIXED.** VB-6D.1 replaced both duplicated mappers with a single total, closed
> boundary (`com.shivang.obd.voice.call.HangupCauseMapper`) and added
> `CallFailureCode.canonicalize(String)`. No provider string can reach `failure_code` any more.
> See `docs/VB-6D.1-FAILURE-TAXONOMY-IMPLEMENTATION.md`. The findings below are retained as the
> original audit record.

```java
default -> "HANGUP_" + hangupCause;   // e.g. "HANGUP_27", "HANGUP_NOT_DEFINED"
```

Consequences, all material to VB-6D:

1. The value is **not a `CallFailureCode` constant**, so `fromCode` returns `Optional.empty()` and
   `isPermanent` returns `false` → **every unmapped cause is silently classified retryable**.
2. Because classification is by identity, `HANGUP_UNKNOWN` — the enum constant explicitly
   documented as *"Unrecognized or missing hangup cause"* — is only reachable when the cause is
   **`null`**. For any *unrecognized non-null* cause the system persists a different string.
3. `failure_code` is `VARCHAR(50)`; a long symbolic cause can overflow → `DataIntegrityViolation`
   mid-hangup-handling.
4. Per-rule retry (§5) is **meaningless until this is closed**, because "which rule applies" cannot
   be answered for codes outside the enum.

Also noted: `mapHangupCauseToCode` returns `"COMPLETED"` for cause `16`, which is not a
`CallFailureCode` constant — but that branch is unreachable because `isSuccessfulCompletion("16")`
short-circuits first. Cosmetic.

> **Correction to the above, found during VB-6D.1 implementation:** the `"COMPLETED"` branch was
> **not** merely cosmetic. It was dead in the ESL path but **live in the agent-outbound path**,
> whose success test compared only the symbolic `"NORMAL_CLEARING"`. A provider reporting the
> numeric cause `16` was therefore marked `FAILED` and stamped with the non-canonical code
> `"COMPLETED"`; an *absent* cause was optimistically treated as a *success*. Both are corrected
> in VB-6D.1, and the two adapters can no longer disagree about the same provider event.

Minor cosmetic: `CallFailureCode.java:151` places the `// === Orchestration …` section header on the
same line as the preceding constant, so the header is swallowed by the trailing comment.

### 4.4 Compliance codes are classified retryable — **REFACTOR** *(policy decision)*

`DNC_BLOCKED`, `NOT_WHITELISTED`, `PLATFORM_BLOCKED`, `PLATFORM_PROTECTED`, `RESELLER_BLOCKED` are
all `TEMPORARY`. A number placed on a DNC list will fail identically on every retry, so the current
classification burns the whole retry budget on a guaranteed-to-fail outcome.

`CallFailureCode`'s own javadoc anticipates this: *"Per-case semantic classification (e.g. making
eligibility rejections permanent) is a future policy decision and deliberately NOT pre-baked
here."* VB-6D is that decision point — but it is a **product** decision (**OD-3**), not an
engineering one.

---

## 5. Provider acceptance boundary

### 5.1 Exact existing dispatch lifecycle (`OutboundDialService.processAttempt`)

```
CallAttempt QUEUED
  └─ 1. resolve execution snapshot            (VB-6A; live campaign never read)
  └─ 2. resolveUsageDate(snapshot timezone)    → EXECUTION_TIMEZONE_INVALID
  └─ 3. buildDestinationNumber(contact)       → CONTACT_INVALID
  └─ 4. eligibility.evaluate(...)             → DNC_BLOCKED / NOT_WHITELISTED / INVALID_DID / …
  └─ 5. route selection
  └─ 6. actualOutboundDidId = selected route DID
  └─ 7. DailyDialLimitService.admit(...)      → DAILY_LIMIT_REACHED   [VB-6C admission]
  └─ 8. voiceCapacity.reserve(gateway)        → requeue                [pre-acceptance]
  └─ 9. dialer.dial(...)  ──► DIAL_REQUEST_ACCEPTED  (+OK)   [USAGE BOUNDARY]
                                └─ DailyDialLimitService.confirmAccepted(...)
                            or DIAL_REQUEST_REJECTED / FAILED → releaseReservation
  └─ 10. CallSession + CallLeg created; attempt IN_PROGRESS
  └─ 11. ESL events drive terminal state (CHANNEL_HANGUP → COMPLETED / FAILED)
```

### 5.2 Where VB-6D retry counting must occur — **two distinct points**

The prompt's central question deserves a precise answer, because the two answers differ by layer:

| Count | Where | Why |
|---|---|---|
| **Per-rule retry decision** (does this *failure* warrant another attempt?) | `CampaignExecutionOrchestrator.processRetriesForExecution`, step 5 — at **failure classification time** | This is where `failureCode` is known and where the retry row is created. It is a **policy** decision, already correctly located. |
| **Daily attempt admission** (may *any* attempt dispatch today?) | `OutboundDialService.processAttempt`, a **new step adjacent to step 7** | Must be at dispatch time, holding a reservation exactly like VB-6C, because the count must be atomic against concurrent workers. |

### 5.3 Does a pre-acceptance failure consume budget? — **the evidence-based answer**

Existing, proven precedent (VB-6C.1) is unambiguous and should be copied:

- Pre-acceptance rejection (routing, capacity, dial originate failure) →
  `releaseReservation(...)` → **consumes nothing**.
- Provider `+OK` → `confirmAccepted(...)` → **consumes one**.

**This is the only concurrency- and cost-correct semantic available in the repository, and it is
the recommended default for VB-6D's daily attempt ceiling** (`OD-4` documents the alternative and
why it is rejected): a pre-acceptance failure is *not evidence that the subscriber was contacted*,
so charging it against a contact-protection budget punishes transient infrastructure faults.

Consequence, stated explicitly because it is a real behavioural choice: under this model a contact
whose gateway is down can be retried **unboundedly within one day**, because pre-acceptance
failures never increment the counter. VB-6D therefore needs a **separate** bound on pre-acceptance
churn — the natural one being the existing `maxTotalAttempts` plus a fixed-interval delay, not the
daily safety budget. Recorded as **OD-4**.

---

## 6. VB-6C daily DNID limit — must remain intact

### 6.1 A and B are semantically **different** — do not collapse

| | **A — VB-6C (existing)** | **B — VB-6D (proposed)** |
|---|---|---|
| Scope | Voice Blast | Voice Blast (proposed) |
| Bucket | `(tenant, contact, actual DNID, day)` | `(tenant, contact, day)` — **no DNID** |
| Counts | **provider-accepted** dials | **attempted dispatches** (recommended) |
| Ceiling | 3/day, campaign may lower to 1–3 | TBD (**OD-5**) |
| Purpose | Subscriber-contact protection, per DNID | Blast-volume protection, DNID-agnostic |
| Mechanism | bucket row + conditional `UPDATE` | **TBD** |

They differ on **bucket key**, **counted event**, and **purpose**. Merging them would corrupt both:
a DNID-scoped counter cannot express a DNID-agnostic ceiling, and an attempt-scoped counter cannot
express "3 *accepted* dials".

**Recommendation: `NEW` structure, reusing the VB-6C *pattern*, sharing no row and no code path
with it.** VB-6C's `DailyDialLimitService`, `voice_blast_daily_usage`,
`voice_blast_daily_usage_entries` and its three native statements are **`KEEP` — untouched**.

### 6.2 Reuse / extension / separate — compared

| Option | Assessment |
|---|---|
| **Reuse** the VB-6C bucket for a global count | **Rejected.** Would require dropping `did_id` from the key (breaking the DNID contract) or storing two different counters in one row (conflating an accepted-dial count with an attempt count). Both corrupt the proven design. |
| **Extend** `voice_blast_daily_usage` with an extra attempt column | **Rejected.** The bucket key is `(tenant, contact, did, date)`; a DNID-agnostic global count would be split across rows and require a cross-row `SUM` — losing the single-statement atomicity that is the entire concurrency argument (`PG-DL2`). |
| **Separate policy structure, same pattern** | **Recommended.** One bucket table keyed `(tenant, contact, date)` with the same conditional-`UPDATE` admission, same `ON CONFLICT DO NOTHING` creation, and the same "reservation is a hold" shape. ~15 lines of native SQL that the team has already proven correct. |

The only genuine question is whether VB-6D's ceiling needs a per-attempt **idempotency ledger**
(like `voice_blast_daily_usage_entries`). Recommendation: **no** — see §7.3.

---

## 7. Global max attempts / contact / day

### 7.1 The requirement is ambiguous — ambiguity documented, not silently resolved

The brief asks for "global maximum attempts/contact/day" without fixing scope. The dimensions that
are genuinely undecided:

| Question | Status |
|---|---|
| Platform-wide ceiling, or campaign-configurable stricter? | Both — platform constant + optional campaign value, exactly as VB-6C's `MAX_VOICE_BLAST_DAILY_DIAL_LIMIT` pattern (**recommended**, see §8) |
| DNID-scoped? | **Recommend NO** — otherwise it duplicates VB-6C and is defeated by DNID rotation |
| Tenant-scoped? | **Recommend YES**, with `tenant_id` in the bucket key for isolation, but the *ceiling* as a platform constant (not tenant-tunable in this phase) |
| Which timezone defines the day? | **Recommend the execution snapshot's IANA timezone** — identical to VB-6C's `resolveUsageDate`, already proven, already fails deterministically on invalid zones |
| Provider acceptance or attempted dispatch? | **Recommend attempted dispatch** (§5.3) |
| Does a retry consume it? | **Recommend yes** — that is the point of a blast ceiling |
| Do different campaigns share it? | **Recommend YES** — that is what makes it a *global* safety ceiling and stops two campaigns jointly blasting one contact |
| Does changing DNID reset it? | **Recommend NO** — this is the key differentiator from VB-6C and the reason it must be a separate structure |
| Voice Blast only? | **Recommend YES** — Contact Center and future Omnichannel have different protection needs; do not couple them now |
| Coexists with VB-6C's 3/day? | **Recommend YES, as a stricter AND-gate** — both must admit independently |

### 7.2 The effective rule — proposed, not implemented

```
mayDispatchToday  =  globalDailyAttemptAdmission(tenant, contact, day, ceiling)
                 AND vb6cDailLimitAdmission(tenant, contact, actualDid, day)   [unchanged]
```

A conjunctive AND of two **independent** admissions. Never a minimum, never a sum, never a merge.
Both hold a reservation; both are released on pre-acceptance failure; VB-6C still confirms only at
`+OK`.

### 7.3 Does VB-6D need a per-attempt ledger? — **Recommend NO**

`voice_blast_daily_usage_entries` exists because **exactly-once usage** needs a physical
`UNIQUE(call_attempt_id)` — a counter alone cannot tell "one acceptance recorded twice" from "two
acceptances". VB-6D's daily attempt counter has no such ambiguity: the natural idempotency key
already exists as the **unique index `uq_call_attempts_execution_contact_attempt`**, and the attempt
row is created in its own transaction before dispatch. A second ledger table would be
**duplicative complexity** — explicitly rejected under §24.

---

## 8. Campaign-level stricter limit — **EXTEND**

Follows the VB-6C.2 pattern almost exactly, and requires no new design:

| Layer | Change |
|---|---|
| `CampaignEntity` | **EXTEND** — one nullable `maxAttemptsPerContactPerDay` column |
| `CampaignExecutionConfiguration` | **EXTEND** — same nullable column; **frozen at execution creation** |
| `CampaignRuntimeConfig` / resolver | **EXTEND** — carry the snapshot value; **live campaign never read at dispatch** |
| Validation | **NEW** — reuse the one-rule/three-boundaries shape: DTO constraint + `assertConfigurable` + DB `CHECK` |
| Defaults | `NULL` ⇒ platform ceiling. `NULL` is preserved verbatim, never normalized — keeps *configured* vs *effective* distinct, as VB-6C.2 established |

Change-after-execution behaviour is already proven by `PG-6C2-1`: existing executions keep their
frozen value; new executions pick up the new one.

---

## 9. Effective retry policy — layering, not arithmetic

The brief warns against mathematically combining limits. The correct model is a **layered
pipeline**, where each layer is a different *kind* of control. Combining any two of these would be
a category error:

| Layer | Kind | Authority (existing or proposed) |
|---|---|---|
| 1. Campaign lifecycle | eligibility | `CampaignLifecyclePolicy` (`DRAFT`-only edit) — **KEEP** |
| 2. Execution snapshot present | integrity | `CampaignRuntimeConfigResolver` — **KEEP** |
| 3. Per-rule retry eligibility | **policy** | `CampaignExecutionOrchestrator` (existing step 5) — **EXTEND** |
| 4. Per-contact attempt count | **policy** | `maxTotalAttempts = 1 + maxAttempts` — **KEEP** |
| 5. Global daily attempt ceiling | **hard safety admission** | **NEW** (VB-6D) |
| 6. VB-6C DNID accepted-dial limit | **hard safety admission** | `DailyDialLimitService` — **KEEP untouched** |
| 7. Compliance / DND / whitelist | eligibility | `VoiceEligibilityService` — **KEEP** |
| 8. DID validity | eligibility | `CampaignResourceValidationService` — **KEEP** |
| 9. Route availability | routing | `SipGatewayRoutingService` / `GatewayRoutingAdapter` — **KEEP** |
| 10. Capacity | capacity | `VoiceCapacityService` — **KEEP** |
| 11. Provider dispatch | transport | `FreeSwitchOutboundDialer` via `OutboundDialer` — **KEEP** |

Layers 5 and 6 are **admissions** (they reserve and can refuse). Layer 3 is **policy** (it decides
whether to *create* an attempt). Layer 7 is **eligibility** (it refuses a destination). They are
not substitutes and must never be expressed as one number.

**Precedence when several refuse:** the earliest layer in the list is the most specific explanation,
and its code is what should reach `failureCode` — because the retry decision (layer 3) reads that
code. VB-6C already relies on this: `DAILY_LIMIT_REACHED` is `PERMANENT` precisely so a same-day
retry cannot loop.

---

## 10. Retry-delay semantics

Existing behaviour (`@Scheduled(fixedDelay = 30000)`, `intervalSeconds`, `adjustToScheduleWindow`):

- **Base time is `completedAt`** of the failed attempt (fallback `now()`), so the delay is
  *relative to failure*, not to the previous attempt's schedule.
- **Fixed**, not exponential. `RetryStrategy` has one constant and the DB `CHECK` forbids others.
- **Clamped into the snapshot's schedule window** in the snapshot's timezone, so a retry never lands
  outside calling hours.

Assessment of the brief's options:

| Option | Verdict |
|---|---|
| fixed | **KEEP** — the existing model; no product requirement for anything else |
| per-rule | **NEW**, and the only delay change VB-6D needs |
| relative to failure time | **KEEP** (already the case) |
| bounded by campaign end | **KEEP** — `adjustToScheduleWindow` already clamps to the window |
| exponential backoff | **REJECT** — no repository or product evidence justifies it; `RetryStrategy` explicitly forbids adding strategies without a product requirement |
| jitter | **REJECT** — same; would also be untestable and non-deterministic |

Per-rule delay, if adopted, must be a nullable override resolved as
`rule.delay ?? policy.defaultDelay` — a single `DEFAULT` row plus per-rule overrides keeps the
existing FIXED model intact instead of introducing a strategy.

---

## 11. HANGUP semantics

The brief is right that "HANGUP" must not be one bucket. Current behaviour:

```
CHANNEL_HANGUP
  └─ inCallFailureRecorded ? recorded classification wins : isSuccessfulCompletion(cause)
        ├─ cause == 16 / NORMAL_CLEARING            → COMPLETED (not a failure)
        └─ otherwise                               → mapHangupCauseToCode(cause)
```

So today: a **normal clear is already distinguished** from a failure, and a **pre-hangup in-call
failure already wins over the hangup cause** (so `PLAYBACK_FAILED` is not overwritten by a
generic hangup code). That is the correct precedence and should be **KEEP**.

What a per-rule retry model would need, mapped against the existing taxonomy:

| Real-world event | Current representation | Correct? | Verdict |
|---|---|---|---|
| PLAYFILE played to completion, remote hung up normally | `COMPLETED` | Yes | **KEEP** |
| DTMF collection completed | `COMPLETED` | Yes | **KEEP** |
| CONNECT_BY_AGENT bridge ended normally | `COMPLETED` (or agent-leave codes) | Yes | **KEEP** |
| Never answered, remote gave up | `NO_ANSWER` (cause 19) | Yes | **KEEP** — retryable rule |
| Remote busy | `BUSY` (cause 17) | Yes | **KEEP** — retryable rule |
| Callee deliberately rejected | `REJECTED` (cause 21) | Yes — `PERMANENT` | **KEEP** |
| **Caller** (us) hung up early | `CALLER_HANGUP` | Yes | **KEEP**, but see below |
| Callee answered then hung up mid-playback | in-call failure wins, else cause-mapped | Partly | **AMBIGUOUS** — see OD-6 |
| Provider/network cleared the call | cause 16 → `COMPLETED`, or unmapped → `HANGUP_<cause>` | **No** | §4.3 |
| Unmapped/unknown cause | `HANGUP_<cause>` string leak | **No** | §4.3 |

Key risk for VB-6D: **`CALLER_HANGUP` is classified `TEMPORARY` (retryable)**. If *we* hang up
(e.g. playback finished and the app tore the channel down), re-dialing the contact is almost
certainly wrong and wastes blast budget. Distinguishing "remote hung up" from "we hung up after
successful completion" requires information the current boundary partly discards. Recorded as
**OD-6** — this is precisely the kind of question that must be answered by product, not guessed.

---

## 12. Whitelist / DND integration

### 12.1 Current behaviour — already correct, and already retry-safe

`VoiceEligibilityService.evaluate(tenant, reseller, destination, did, enforceWhitelist)` runs, in
order:

1. `PLATFORM_BLOCKLIST` → `PLATFORM_BLOCKED`
2. `PLATFORM_PROTECTED` → `PLATFORM_PROTECTED`
3. `RESELLER_BLOCKLIST` → `RESELLER_BLOCKED`
4. `TENANT_BLOCKLIST` (DNC) → `DNC_BLOCKED`
5. whitelist allow (only when `enforceWhitelist`) → `NOT_WHITELISTED`
6. DID existence / active / assigned / tenant-owned → `INVALID_DID`

**Answers:**

- **Where is DND checked?** Step 4, in `VoiceEligibilityService`, at dial time.
- **Where is whitelist checked?** Step 5 — and it is a **restriction, not a bypass**: it only
  *narrows* the allowed destinations. It **cannot** bypass any blocklist, because blocklists run
  first and unconditionally (`PhoneListType` documents the precedence order explicitly).
- **Before every retry?** **Yes, automatically.** A retry is a *new* `CallAttempt` row; it is
  picked up by `processDueAttempts()` and re-enters `OutboundDialService.processAttempt`, which
  calls eligibility at step 4 **before** routing, capacity, and provider dispatch. Retry therefore
  **cannot** bypass compliance.
- **Before routing / provider admission?** Yes — compliance is the *earliest* gate, ahead of
  routing (5), daily-limit admission (7), capacity (8) and dial (9).

### 12.2 The one nuance worth documenting — **KEEP, but record it**

- The **whitelist flag** is frozen in the execution snapshot (`callOnWhitelistNumbers`), so toggling
  it after an execution exists does **not** affect that execution's retries.
- The **list contents** are read live, so adding a number to the DNC/blocklist **does** stop a retry
  of an already-queued attempt.

That asymmetry is a direct and defensible consequence of VB-6A snapshot semantics — *policy
configuration* is frozen, *resource state* stays dynamic. It should be documented, not "fixed".

### 12.3 Target invariant

> **retry ≠ compliance bypass**

Already structurally guaranteed. VB-6D must **not** add any retry path that constructs an attempt
outside `processDueAttempts()`.

---

## 13. Concurrency-safe daily attempt admission

### 13.1 Existing precedent to copy — **NEW, following the VB-6C pattern**

| VB-6C mechanism | Reuse for VB-6D |
|---|---|
| Unique bucket key + conditional `UPDATE … WHERE a + b < :limit` | **Yes** — the whole concurrency argument |
| `INSERT … ON CONFLICT DO NOTHING` idempotent creation | **Yes** |
| Reservation is a *hold*, released on pre-acceptance failure | **Yes** |
| No advisory locks, no `SKIP LOCKED`, no read-then-write | **Yes** — none exist in 48 migrations |

**Explicitly rejected** (§24): advisory locks, `SKIP LOCKED` polling, distributed locks, Redis
counters, in-memory counters, a second per-attempt ledger table.

### 13.2 Required uniqueness analysis

`uq_call_attempts_execution_contact_attempt` is a **unique index** on
`(execution_id, contact_id, attempt_number)` (V22), and the orchestrator also does a
read-then-insert idempotency check. So **duplicate attempt creation is already physically
impossible** — the scheduler's 30 s tick can overlap safely. This is why a VB-6D per-attempt ledger
is unnecessary (§7.3).

### 13.3 Scenario analysis

| Scenario | Existing protection | VB-6D action |
|---|---|---|
| Two campaigns, same contact | VB-6C bucket shared; VB-6D global ceiling shared | **Intended** — the ceiling is global |
| Same campaign retries same contact | `attemptNumber` + unique index | **KEEP** |
| 20 concurrent workers | `PG-DL2` proves exactly 3 admissions | **Same pattern, new bucket** |
| Duplicate dispatch | `existsBy…` + unique index | **KEEP** |
| Provider acceptance races retry scheduling | Same transaction boundary as VB-6C: confirm at `+OK`, release otherwise | **KEEP** |
| Different DNIDs | VB-6C buckets independent | VB-6D **deliberately shares** (this is the difference) |
| Different tenants | `tenant_id` in every key + FK | **Must include `tenant_id` in the new bucket key** |
| Midnight boundary | `resolveUsageDate` in snapshot timezone | **Reuse the identical seam** |
| Pre-acceptance failure | `releaseReservation` | **Reuse** |
| Process crash between admission and dispatch | **Not self-healing** — strands a hold (documented VB-6C.3 limitation) | Accept the same trade-off; observable via the existing `…reserved.buckets` gauge pattern. **No sweeper** (§8 prohibited) |

### 13.4 The crash-window caveat, stated honestly

A crash after reservation but before dispatch leaves a hold that permanently consumes one unit of
the daily budget for that `(tenant, contact, day)`. VB-6C accepted this and made it *observable*
rather than self-healing. VB-6D should accept the identical trade-off for identical reasons —
self-healing requires a sweeper, which is explicitly prohibited. Budget for **observability only**.

---

## 14. Future scheduler safety contract

Derived from the actual pipeline, not assumed. Before a call may be dispatched:

| # | Precondition | Authority | Today |
|---|---|---|---|
| 1 | Execution is `RUNNING`; campaign lifecycle permits it | `CampaignLifecyclePolicy`, execution status | **KEEP** |
| 2 | Execution snapshot resolves (immutable) | `CampaignRuntimeConfigResolver` | **KEEP** |
| 3 | Attempt is `QUEUED` and `scheduledAt <= now` | `OutboundDialService.processDueAttempts` | **KEEP** |
| 4 | Snapshot timezone is a valid IANA zone | `DailyDialLimitService.resolveUsageDate` | **KEEP** |
| 5 | Contact still valid, same tenant, still in the audience group | `ContactIdentityService` | **KEEP** |
| 6 | Compliance: platform/reseller/tenant blocklists, DNC, protected | `VoiceEligibilityService` | **KEEP** |
| 7 | Whitelist satisfied when the snapshot enables it | `VoiceEligibilityService` | **KEEP** |
| 8 | DID valid, active, assigned, tenant-owned | `CampaignResourceValidationService` | **KEEP** |
| 9 | A route resolves, and its DNID is the recorded bucket key | routing services | **KEEP** |
| 10 | **Global daily attempt admission** | **NEW** | **NEW — layer 5** |
| 11 | VB-6C DNID accepted-dial admission | `DailyDialLimitService.admit` | **KEEP** |
| 12 | Gateway capacity available | `VoiceCapacityService` | **KEEP** |
| 13 | Attempt idempotency intact | unique index | **KEEP** |

**The contract's shape:** *eligibility* (1–9) → *admission* (10–11) → *capacity* (12) →
*transport* (dispatch). The scheduler must contain **none** of this logic — it may only ask each
authority in order and stop at the first refusal.

**Answering §16/§17 explicitly:**

- **Single authority for "may this attempt be retried?"** → a campaign-owned
  **retry policy service** that `CampaignExecutionOrchestrator` delegates to, reading policy from
  the **snapshot** and the failure's `CallFailureCode`. The orchestrator becomes a thin driver. The
  authority must be *one* place, so per-rule behaviour cannot diverge between the retry creator and
  any future scheduler.
- **Single authority for daily safety admission** → a campaign-owned
  **daily attempt admission service**, structurally parallel to (and deliberately separate from)
  `DailyDialLimitService`. Two admission services, two clearly-named buckets, no shared row.

---

## 15. Responsibility boundaries

| Component | Keeps | Gains | Must **not** gain |
|---|---|---|---|
| `CampaignExecutionOrchestrator` | Driving the 30 s tick, loading executions | delegating the retry decision | any new rule logic of its own |
| `OutboundDialService` | dispatch pipeline, provider boundary | one new admission call | retry policy, rule evaluation |
| `CampaignRuntimeConfigResolver` | snapshot → runtime view | new snapshot fields | live-campaign reads |
| `VoiceEligibilityService` | compliance | nothing | retry concepts |
| `DailyDialLimitService` | **VB-6C, untouched** | observability only (done in VB-6C.3) | attempt counting, retry |
| **NEW** retry policy service | per-rule decision, delay, max attempts | — | persistence, dispatch |
| **NEW** daily attempt admission service | global ceiling admission | — | retry rules, VB-6C logic |
| Scheduler (`scheduledTick`) | orchestration only | nothing | **any business policy** |

---

## 16. Entity / repository / migration impact

| Class | Verdict | Note |
|---|---|---|
| `CampaignEntity` | **EXTEND** | `maxAttemptsPerContactPerDay` (nullable) |
| `CampaignExecutionConfiguration` / `CampaignConfigurationSnapshot` | **EXTEND** | freeze the same value; `NULL` preserved |
| `CampaignRuntimeConfig` (+resolver) | **EXTEND** | carry it; live campaign never read |
| `CallAttempt` | **KEEP** | no new column needed — `attempt_number` + status suffice |
| `CallFailureCode` | **EXTEND / REFACTOR** | close the `HANGUP_<cause>` leak; add codes only if OD-2 resolves favourably; revisit compliance classification (OD-3) |
| `RetryPolicySpec` | **EXTEND** | per-rule overrides; `maxAttempts`/`intervalSeconds`/`FIXED` all retained |
| `RetryStrategy` | **KEEP** | one constant, DB-enforced |
| `CampaignExecutionOrchestrator` | **REFACTOR** | delegate the retry decision; keep the tick |
| `DailyDialLimitService` + its two entities/repos | **KEEP** | VB-6C frozen; no new columns |
| `VoiceEligibilityService` | **KEEP** | already re-evaluated per attempt |
| `VoiceBlastDailyUsage*` | **KEEP** | untouched |
| `PhoneList*` | **KEEP** | precedence already correct |
| **NEW** daily attempt bucket table | **NEW** | 1 table; same shape as V47 minus `did_id`, plus a per-attempt ledger only if a future requirement demands it (recommend: no) |
| **NEW** per-rule retry table | **NEW** | only if per-rule is in scope — could alternatively be a `typeConfig` JSON block, which avoids a table. **See OD-7** |

### 16.1 Migration estimate

| Scenario | Migrations |
|---|---|
| Per-rule rules as a **dedicated table** | `V49` rules · `V50` daily attempt bucket · `V51` snapshot/campaign columns (+ `V52` if an index is warranted) |
| Per-rule rules as **`typeConfig` JSON** (recommended) | `V49` daily attempt bucket · `V50` snapshot/campaign columns |
| Global ceiling only, no per-rule | `V49` daily attempt bucket · `V50` snapshot/campaign columns |

`V49`–`V50` is the honest floor. `V47` and `V48` are **not** modified.

---

## 17. API / OpenAPI impact

Every change must be documented through the **generated** OpenAPI (project rule; `CampaignOpenApiContractTest` is the enforcement point).

| Change | Endpoint | DTO |
|---|---|---|
| Campaign max daily attempts | `POST /api/v1/campaigns`, `PUT /api/v1/campaigns/{id}` | `CreateCampaignRequest`, `UpdateCampaignRequest` + `CampaignResponse`; `minimum=1`, nullable, description "Null uses the platform default" |
| Per-rule retry configuration | same | extend `RetryPolicyConfig`, or a new `retryRules` block — **shape depends on OD-7** |
| Global ceiling (platform constant) | — | **No API** — not tenant-configurable |
| Admission outcome (new code) | — | `CallFailureCode` is **not** an API enum; `CallAttemptResponse.failureCode` remains a `String` |

**No new endpoint.** In particular, **no observability endpoint** and **no retry-administration
endpoint**. `PUT` continues to be gated by `CampaignLifecyclePolicy` (`DRAFT` only), so changing
retry config after an execution exists remains impossible without unlocking — already proven by
`PG-6C2-1`.

Expected contract tests: create/update with the field omitted and at boundaries; invalid values →
400; response echoes `null` verbatim; generated spec exposes the field with correct bounds and
nullability; existing campaign endpoints remain documented.

---

## 18. Tenant / reseller isolation

| Boundary | Rule |
|---|---|
| New daily attempt bucket | `tenant_id` **MUST** be part of the primary/unique key, with an FK to `tenants`, exactly as `voice_blast_daily_usage` does |
| Cross-tenant reads | Impossible by construction; a cross-tenant key can never collide because `tenant_id` is in the key |
| Cross-campaign counting | **Intended** for the global ceiling — that is its purpose. The bucket deliberately omits `campaign_id` |
| Reseller visibility | No change. The ceiling is a platform constant, so there is no reseller-tunable value to leak |
| Shared contacts | Contacts are tenant-scoped (VB-6B.1). A contact UUID in another tenant cannot appear in this tenant's bucket |
| `failure_code` observability | Must not expose destination numbers; IDs only, per the existing logging/metric policy |

---

## 19. Test matrix (proposed — **not added in this phase**)

**Retry rules** — NO_ANSWER, BUSY, FAILED, SWITCHED_OFF*, NOT_REACHABLE*, HANGUP (remote / caller /
post-answer), per-rule count, per-rule delay, no retry past maximum, permanent never retried,
unknown-code handling (post-§4.3 fix). *\*conditional on OD-2.*

**Daily safety** — global ceiling enforced; campaign stricter override; `null` ⇒ platform default;
`>ceiling` and `<=0` rejected; cross-campaign sharing; different tenants isolated; DNID-independence
(rotating DNID does **not** reset the ceiling); calendar-day boundary; snapshot timezone;
snapshot immutability (ceiling changed after execution → old execution unaffected).

**Concurrency** (real PostgreSQL, mirroring VB-6C's approach) — 20 concurrent workers yield exactly
`ceiling`; duplicate dispatch; exactly-once admission; acceptance racing retry; crash between
admission and dispatch leaves an observable hold.

**Compliance** — DND blocks a retry; whitelist restriction holds on retry; compliance re-evaluated
on every retry; whitelist-flag frozen vs list-contents live.

**Provider boundary** — pre-acceptance failure consumes no daily budget; `+OK` consumes exactly one;
post-acceptance failure consumes one.

**Regression** — `PLAYFILE`, `DTMF`, `CONNECT_BY_AGENT`; Contact Center unaffected; **all VB-6C
suites unchanged and green** (the hard gate: `VoiceBlastDailyDialLimitPostgresIntegrationTest` 12,
`DailyDialLimitSnapshotPostgresIntegrationTest` 5, `DailyDialLimitServiceTest` 10,
`OutboundDialServiceRoutingTest` 19, `DailyDialLimitObservabilityTest` 8).

---

## 20. Architecture / Modulith impact

- **Direction today:** `campaign` → `voice` (65 imports), `tenant` (11), `contact` (8), `audio` (7),
  `did` (3), `tts` (1). **`campaign` → `telephony`: zero imports.** `telephony` has no
  `@ApplicationModule`, so telephony adapters are reached through voice-owned ports.
- **VB-6D's new services belong in `campaign`**, next to `DailyDialLimitService`. That preserves the
  existing direction and introduces **no new edge**.
- **Cycle risk: none identified.** Both new services depend only on campaign-owned repositories and
  the snapshot. Neither needs telephony information — retry rules are keyed on `CallFailureCode`,
  which is a campaign enum.
- **Explicitly forbidden and not proposed:** a `Campaign ↔ Telephony` concrete dependency. If a rule
  ever needs hangup causes, the cause must be normalized to a `CallFailureCode` **inside the
  existing `telephony → campaign` direction** — which is already how `EslEventService` works, and is
  the pattern any §4.3 fix must follow.

---

## 21. Observability / security

Signals VB-6D will need, all on the existing `obd.*` Micrometer convention established in VB-6C.3
(no new framework):

| Signal | Kind |
|---|---|
| retry scheduled | counter, `rule` tag (bounded) |
| retry rejected by policy | counter, `reason` tag (bounded) |
| daily attempt limit reached | counter, `ceiling` tag (bounded) |
| DND / whitelist rejection | counter, `code` tag (bounded) |
| permanent failure | counter, `code` tag (bounded) |
| provider acceptance / pre-acceptance failure | already covered by VB-6C signals |

**Cardinality discipline (identical to VB-6C.3, and enforced by an existing test pattern):**
`tenant`, `campaignId`, `contactId`, `didId`, `phone`, `e164`, `attemptId`, `executionId`,
`providerCallId` and free-form cause strings are **forbidden as metric labels**. Identifiers stay
in logs; the offending timezone/cause value stays in the exception message, never in a tag.

**Never in metrics, logs, or new columns:** raw phone numbers, E.164 values, provider credentials,
tokens, full campaign configuration payloads, audio/TTS content.

---

## 22. Over-engineering review — explicitly rejected

| Rejected | Reason |
|---|---|
| Redis / Kafka / Kubernetes / microservices | No requirement in the repository |
| Distributed locks, advisory locks, `SKIP LOCKED` | **Zero precedent in 48 migrations.** VB-6C's conditional `UPDATE` is the proven mechanism |
| Event sourcing / CQRS / event bus | No requirement; would duplicate the `CallAttempt` history that already exists |
| A generic rules engine | 6 rules with bounded values; a table + a switch is smaller and testable |
| A second per-attempt ledger (VB-6D) | `uq_call_attempts_execution_contact_attempt` already guarantees attempt uniqueness (§7.3) |
| A new scheduler framework | `@Scheduled(fixedDelay=30000)` already runs the tick |
| A reconciliation sweeper | Prohibited, and unnecessary while the stranded-hold gauge is the only evidence |
| Exponential backoff / jitter | No product requirement; DB `CHECK` forbids extra strategies |
| Campaign configuration versioning | Explicitly forbidden |
| A legacy compatibility layer | Nothing to be compatible with |
| AI-based retry decisions | Absurd for 6 deterministic rules |
| A new observability database / metrics service | Metrics are in-memory and sufficient |
| Merging VB-6C and VB-6D into one generic counter | Their semantics differ on bucket key, counted event, and purpose (§6.1) |

**Preferred mechanisms, all already present:** PostgreSQL constraints · existing transactions ·
existing conditional updates · the campaign snapshot · `CampaignRuntimeConfigResolver` ·
`VoiceEligibilityService` · `DailyDialLimitService` · `CampaignExecutionOrchestrator`.

---

## 23. Open decisions — business confirmation required

| ID | Decision | Why it cannot be decided by engineering | Blocks |
|---|---|---|---|
| **OD-1** | **Is VB-6D retry/safety, or IVR/DTMF?** The repo doc says IVR; the brief says retry/safety and forbids IVR. | Product scope | **EVERYTHING** |
| OD-2 | Are `SWITCHED_OFF` / `NOT_REACHABLE` required as first-class outcomes? Provider cause mapping is unreliable, so this needs a target-carrier list and an accepted error budget. | Product + carrier reality | Per-rule design |
| OD-3 | Should compliance rejections (`DNC_BLOCKED`, `NOT_WHITELISTED`, `*_BLOCKED`) become `PERMANENT`? | Product policy; VB-6A explicitly deferred it | Taxonomy, retry |
| OD-4 | Confirm pre-acceptance failures consume **no** daily budget. If so, accept that gateway faults can retry unboundedly within a day, bounded instead by `maxTotalAttempts` + fixed delay. | Cost/compliance judgement | Daily admission |
| OD-5 | The global ceiling's value, and whether it is DNID-agnostic (recommended) or per-DNID. | Product | Daily admission |
| OD-6 | Is a **callee answered then hung up** retryable? And should `CALLER_HANGUP` (our hangup) stay retryable? | Product | HANGUP rules |
| OD-7 | Per-rule retry as a **dedicated table** (queryable, constrained) or a **`typeConfig` JSON block** (no migration, no new entity)? | Trade-off: queryability vs. schema surface | Migration count |
| OD-8 | Does the daily attempt budget apply to **retry** dispatches as well as first attempts? (Recommended: yes.) | Product | Daily admission |

---

## 24. Recommended implementation sequence

Derived from the repository, not assumed. **Only valid if OD-1 resolves to retry/safety.**

| Phase | Scope | Rationale |
|---|---|---|
| **VB-6D.1** | **Close the `HANGUP_<cause>` leak.** Map unknown causes to canonical codes (incl. `HANGUP_UNKNOWN`), fix the `VARCHAR(50)` risk, decide `CALLER_HANGUP`. | **Prerequisite for everything.** Per-rule retry is meaningless while codes escape the enum. Small, self-contained, no new persistence. |
| **VB-6D.2** | **Per-rule retry model + configuration** (table or `typeConfig`, per OD-7), frozen into the snapshot, exposed through the existing `RetryPolicyConfig` DTO and generated OpenAPI. New campaign-owned retry policy service; orchestrator delegates to it. | Builds on a trustworthy taxonomy. Uses the existing freeze + validation pattern proven by VB-6C.2. |
| **VB-6D.3** | **Global daily attempt admission** — new bucket, VB-6C pattern, `tenant+contact+day`, snapshot timezone, no DNID. Conjunctive AND with VB-6C in `OutboundDialService`. Observability only, no sweeper. | Independent of rules; adds the hard safety ceiling. Must not disturb VB-6C. |
| **VB-6D.4** | **Compliance / retry integration** — confirm re-evaluation, resolve OD-3 classification, record the whitelist-flag vs list-contents asymmetry. | Small; mostly verification plus a policy decision. |
| **VB-6D.5** | **Scheduler safety contract + hardening** — codify the §14 preconditions as an ordered, testable contract; add the observability counters. | Last: it validates the whole chain. No new dispatch logic. |

**Hard gate carried into every phase:** all VB-6C suites stay green; `ArchitectureTest` stays
cycle-free; Flyway stays forward-only; no test is weakened to obtain green.

---

## 25. Explicit non-goals

Not in VB-6D: IVR · reusable IVR trees · multi-level DTMF · new PLAYFILE features · max call
duration · reporting privacy · scheduler rewrite · `MISSED_CALL` · Contact Center retry policy ·
omnichannel · AI voice · new telephony transport · Redis / Kafka / Kubernetes · campaign versioning
· legacy execution compatibility. Also excluded: changing the VB-6C daily DNID limit, and building
any reconciliation worker.

---

## 26. Final audit verdict

> **UPDATE — VB-6D.1 has since been implemented.** The technical prerequisite this audit called
> blocking is **closed**: the unbounded `HANGUP_<cause>` leak no longer exists, unknown causes have
> deterministic canonical behaviour, and retry classification no longer depends on a lookup
> returning empty. See `docs/VB-6D.1-FAILURE-TAXONOMY-IMPLEMENTATION.md`.
>
> **The verdict below is otherwise unchanged: VB-6D is still BLOCKED on OD-1 (the scope
> conflict).** The taxonomy work was worth doing under either scope reading and is not wasted, but
> no retry-policy phase should begin until the product confirms what VB-6D is.

```
VB-6D AUDIT STATUS:  BLOCKED — OD-1 (scope conflict)
```

**Blocking reason, precisely:** the repository's own roadmap document defines VB-6D as
*"DTMF / IVR Configuration"* (reusable IVR trees, multi-level DTMF, `ivrTreeId`), while this
phase's brief defines it as *"Campaign Safety & Retry Policy"* and explicitly forbids IVR. The two
are mutually exclusive. Implementing either before this is confirmed risks building the wrong
feature.

**If OD-1 resolves to retry/safety (this audit's assumption), the technical verdict is:**

```
READY FOR IMPLEMENTATION
```

with the caveat that **VB-6D.1 (the `HANGUP_<cause>` leak) is a hard prerequisite** rather than an
optional cleanup: until unmapped hangup causes stop persisting as unbounded strings that classify as
retryable, per-rule retry cannot be specified or tested correctly.

> ✅ **That prerequisite is now satisfied.** VB-6D.1 is implemented and green. Conditional on OD-1
> resolving to retry/safety, the recommended sequence in §24 now begins at **VB-6D.2**.

### Baseline

`619deac` · `main` → `origin/main` · 1040 tests / 0 failures / 0 errors / 1 skipped ·
ArchitectureTest 1/0/0/0, 0 cycles · Flyway **V48** · 1 uncommitted user doc edit.

### Should change

`CampaignEntity` · `CampaignExecutionConfiguration` / `CampaignConfigurationSnapshot` ·
`CampaignRuntimeConfig` + `CampaignRuntimeConfigResolver` · `RetryPolicySpec` ·
`CallFailureCode` · `CampaignExecutionOrchestrator` · `EslEventService.mapHangupCauseToCode` ·
`RetryPolicyConfig` DTO (+ `CreateCampaignRequest` / `UpdateCampaignRequest` / `CampaignResponse`) ·
`docs/VB-6-CAMPAIGN-AUDIT.md` (scope row).

**NEW:** a campaign-owned retry policy service · a campaign-owned daily attempt admission service ·
a per-rule retry structure (table or `typeConfig`, per OD-7) · a daily attempt bucket table ·
tests for all of the above.

### Should NOT change

`DailyDialLimitService` · `VoiceBlastDailyUsage` · `VoiceBlastDailyUsageEntry` ·
`VoiceBlastDailyUsageRepository` · `VoiceBlastDailyUsageEntryRepository` · `CampaignType` ·
`RetryStrategy` · `VoiceEligibilityService` · `PhoneListService` / `PhoneListType` ·
`VoiceCapacityService` · routing adapters · `FreeSwitch*` / `EslClient` / `EslEventService`
**beyond the cause-mapping fix** · `CallAttempt` · migrations **V1–V48** · `pom.xml` (no new
dependency) · Surefire configuration.

### Proposed migrations

**2 minimum** (`V49` daily attempt bucket, `V50` campaign + snapshot columns), **3–4** if per-rule
rules take a dedicated table (OD-7). V47 and V48 are not modified.

### Proposed API changes

Two nullable campaign-configurable fields (`maxAttemptsPerContactPerDay`, and per-rule retry config
whose shape depends on OD-7) on the existing campaign create/update/response DTOs. **No new
endpoints.** Generated OpenAPI must document both, per project rule.

### Proposed test additions

Per §19 — roughly 4 groups (retry rules, daily safety, concurrency against real PostgreSQL,
compliance) plus full regression across `PLAYFILE` / `DTMF` / `CONNECT_BY_AGENT` / Contact Center /
all VB-6C suites. Final count cannot be stated without OD-2 and OD-7; it will be reported, not
predicted.

### Open business decisions

**OD-1 (blocking)**, OD-2, OD-3, OD-4, OD-5, OD-6, OD-7, OD-8 — §23.

### Implementation sequence

VB-6D.1 taxonomy fix → VB-6D.2 per-rule retry → VB-6D.3 daily attempt admission → VB-6D.4
compliance/retry integration → VB-6D.5 scheduler safety contract.

---

**Audit complete. No production code was written. VB-6D.1 NOT started.**

*One inconsistency worth noting: §1 of the brief states the working tree is clean at `619deac`.
It was not — `docs/campaign-readiness.md` carries an uncommitted edit that redefines VB-6D. That
edit is preserved untouched.*
