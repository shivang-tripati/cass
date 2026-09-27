# VB-6D.1 — Canonical Failure Taxonomy & Provider Cause Mapping — Implementation Report

**Status: COMPLETE.** Arbitrary provider hangup strings can no longer become business failure
codes. Unknown causes have deterministic canonical behaviour, and retry classification no longer
depends on a lookup quietly returning empty. No migration, no API change, no new infrastructure.

---

## 1. Executive summary

The VB-6D audit found a blocking prerequisite: VB-6D will decide retries per canonical failure
category, but the telephony boundary could emit values that were *not* canonical categories. Two
separate adapters each translated provider hangup causes privately, and both ended in
`"HANGUP_" + cause` — an unbounded, provider-derived string. `CallFailureCode.fromCode` returns
empty for it, so the retry gate fell through its permissive branch and a carrier's arbitrary text
decided whether a contact got re-dialled.

VB-6D.1 replaces both with **one** total, closed boundary
(`com.shivang.obd.voice.call.HangupCauseMapper`) and adds
`CallFailureCode.canonicalize(String)`, so classification always routes through a reviewed constant.

Three defects were found and fixed. The audit had identified one; implementing it surfaced two
more, one of which was a live correctness bug (see §3).

| # | Defect | Severity |
|---|---|---|
| 1 | `HANGUP_<provider string>` persisted as a business failure code, classified retryable | **Blocking** (known from audit) |
| 2 | Numeric cause `16` marked `FAILED` and stamped with the non-canonical code `"COMPLETED"` on the agent-outbound path | **Live correctness bug** (new) |
| 3 | An absent (`null`) hangup cause treated as a *successful* completion on the agent-outbound path | **New** |

The two adapters also **disagreed with each other** about the same provider event; they now share
one implementation, so divergence is structurally impossible.

## 2. Initial repository state

| Item | Value |
|---|---|
| Commit / branch | `619deac`, `main` → `origin/main` |
| Working tree | 2 uncommitted docs (user's `campaign-readiness.md`; my VB-6D audit) — **both preserved untouched** |
| Full suite | 1040 tests / 0 failures / 0 errors / 1 skipped |
| ArchitectureTest | 1/0/0/0 — 0 cycles |
| Flyway head | V48 |
| Module directions | `campaign → voice`, `telephony → voice`, `telephony → campaign`; `voice` imports **no** campaign type |

## 3. Exact defects found

### 3.1 The known defect — unbounded provider strings *(both adapters)*

```java
// EslEventService.mapHangupCauseToCode   (telephony)
default -> "HANGUP_" + hangupCause;

// AgentOutboundCallService.mapHangupCause (voice.outbound)  — the audit missed this one
default -> "HANGUP_" + hangupCause;
```

1. Not a `CallFailureCode` constant → `fromCode` empty → `isPermanent` false → **every unmapped
   cause silently retryable**.
2. The enum's own `HANGUP_UNKNOWN` ("unrecognized or missing hangup cause") was reachable *only*
   when the cause was `null`; any unrecognized non-null cause produced a different string.
3. `failure_code` is `VARCHAR(50)`; a long symbolic cause could overflow mid-hangup.
4. Per-rule retry would have been unspecifiable, because "which rule applies" cannot be answered
   for a value outside the vocabulary.

### 3.2 New — numeric `16` marked FAILED with a non-canonical code

Both mappers returned `"COMPLETED"` for cause `16`. `COMPLETED` is **not** a `CallFailureCode`
constant, and it is not a failure code at all — completion is expressed through
`CallAttemptStatus.COMPLETED`.

In `EslEventService` the branch was unreachable (`isSuccessfulCompletion` short-circuited first),
so the audit judged it cosmetic. In `AgentOutboundCallService` it was **live**, because that
adapter's success test was `"NORMAL_CLEARING".equals(hangupCause)` — it recognised only the
symbolic form. A provider reporting the numeric `16` therefore produced:

```
session.status = FAILED
session.failureCode = "COMPLETED"   ← non-canonical, and semantically contradictory
```

### 3.3 New — absent cause optimistically treated as success

`boolean success = "NORMAL_CLEARING".equals(hangupCause) || hangupCause == null;`

An **absent** cause — the least informative outcome possible — was assumed to be a successful
completion. `EslEventService` treated the same input as a failure (`HANGUP_UNKNOWN`). So the two
adapters reached opposite conclusions for identical provider input.

## 4. Files / classes inspected

`CallFailureCode` · `CampaignExecutionOrchestrator` (retry gate, `isPermanentFailure`) ·
`OutboundDialService` (dial pipeline, provider boundary) · `EslEventService` (CHANNEL_HANGUP
handling, `isSuccessfulCompletion`, cause mapping) · `AgentOutboundCallService` (agent-outbound
hangup, cause mapping) · `ConnectByAgentService` · `InboundCallService` · `AgentConnectTimeoutScheduler` ·
`DtmfExecutionService` · `PlayfileExecutionService` · `CallAttempt` / `CallAttemptStatus` ·
`PhoneNumberNormalizer` (utility-class convention) · migrations `V14` (retry columns),
`V22` (`call_attempts.failure_code VARCHAR(50)`) · all 28 `setFailureCode` producers ·
tests: `CallFailureCodeTest`, `EslEventServiceTest`, `AgentOutboundCallServiceTest`, and every
ESL/playback/DTMF/agent test referencing a hangup cause.

## 5. Changes implemented

| Change | File | Type |
|---|---|---|
| Single total, closed cause→code boundary | `voice/call/HangupCauseMapper.java` | **NEW** |
| ESL adapter delegates; local mapper + local `isSuccessfulCompletion` deleted | `telephony/EslEventService.java` | **REFACTOR** |
| Agent-outbound adapter delegates; local mapper deleted; divergent success predicate corrected | `voice/outbound/AgentOutboundCallService.java` | **REFACTOR** + **BUG FIX** |
| `canonicalize(String)` added; `retryClassOf`/`isPermanent` route through it | `campaign/CallFailureCode.java` | **EXTEND** |
| Canonical-boundary + canonicalization + persistence-safety tests | 3 new / 1 extended test class | **NEW / EXTEND** |

**No new module edge.** Both call sites already imported `voice.call`, so placing the shared mapper
there added **zero** dependencies. This was the deciding factor: the vocabulary is owned by
`campaign`, which already depends on `voice` — putting the mapper in `campaign` would have forced
`voice → campaign` and created a Modulith cycle.

## 6. Canonical failure mapping table

| Provider cause (Q.850 / FreeSWITCH alias) | Canonical `CallFailureCode` | Retry class |
|---|---|---|
| `17`, `USER_BUSY` | `BUSY` | TEMPORARY |
| `19`, `NO_ANSWER` | `NO_ANSWER` | TEMPORARY |
| `21`, `CALL_REJECTED` | `REJECTED` | PERMANENT |
| `34`, `NO_CIRCUIT_AVAILABLE` | `CONGESTION` | TEMPORARY |
| `41`, `NORMAL_TEMPORARY_FAILURE` | `TEMPORARY_FAILURE` | TEMPORARY |
| `47`, `RESOURCE_UNAVAILABLE` | `RESOURCE_UNAVAILABLE` | TEMPORARY |
| `16`, `NORMAL_CLEARING` (case-insensitive) | **not a failure** — normal release → `COMPLETED` status, `failureCode = null` | n/a |
| everything else, incl. `27`, `15`, `22`, `31`, blank, absent | `HANGUP_UNKNOWN` | TEMPORARY |

All pre-existing correct mappings are preserved. Surrounding whitespace is trimmed before lookup,
so a padded provider header still resolves to its real cause (a small improvement: previously
`" 17"` would have leaked a string).

## 7. Unknown-provider behavior

Unmapped, malformed, blank, or absent causes resolve to `HANGUP_UNKNOWN` — a real
`CallFailureCode`. The mapper **never** echoes, prefixes, or derives from provider text, so no input
can invent a business meaning. `HangupCauseMapper.supportedFailureCodes()` exposes the closed
output set so this is *verifiable* rather than merely intended: `TAX-6` and `BOUND-7` both sweep
causes and assert every produced value is canonical and classifiable.

## 8. Retry-classification safety behavior

Three distinct questions, deliberately separated:

| API | Question | Unknown input |
|---|---|---|
| `fromCode(s)` | "Is this a known code?" | `Optional.empty()` — **unchanged**, still strict |
| `canonicalize(s)` | "Which code does this mean?" | `HANGUP_UNKNOWN` — **new**, never null |
| `retryClassOf(s)` / `isPermanent(s)` | "How is it retried?" | delegates to `canonicalize`, then the constant's class |

The empty-lookup path is no longer an implicit `orElse(false)` accident; it is an explicit,
named policy. **Every canonical code keeps exactly the classification it had** — no existing
assertion changed, and `CallFailureCodeTest` passes untouched.

**Deliberate non-change:** `HANGUP_UNKNOWN` remains `TEMPORARY` (retryable). Flipping it would
change retry behaviour, which is VB-6D.2's job and a product decision (audit OD-2/OD-6). It is now a
**one-constant** change in one reviewed place rather than a side effect of a failed lookup. This is
recorded as a known limitation (§15), not silently decided.

## 9. SWITCHED_OFF / NOT_REACHABLE limitation

**Not implemented, deliberately.** FreeSWITCH surfaces these only as carrier-dependent SIP/Q.850
causes (`27` destination off-hook, `15` unallocated number, `22` network out of order, `31`
temporary failure) whose spelling varies per carrier and gateway. Mapping them would present a
guess as a classification. They remain a future policy category (audit OD-2); today those causes
resolve deterministically to `HANGUP_UNKNOWN`. `SCOPE-1` asserts they are absent from the enum.

## 10. Database impact

**No migration. Flyway head unchanged at V48.**

`failure_code VARCHAR(50)` is now sufficient *by construction*: persisted values are canonical enum
names, the longest being `EXECUTION_TIMEZONE_INVALID` (25 chars). The overflow risk disappears
because the input is no longer unbounded, so no column change is required.

A `CHECK` enumerating ~45 codes was considered and **rejected**: `failure_code` is shared by
`call_attempts`, `call_sessions` and `call_legs`, it is nullable for successes, and the list would
need amending by a migration every time a code is added. Enforcing canonicality at the single
mapper choke point plus tests is smaller and does not make routine vocabulary growth expensive.

## 11. API / OpenAPI impact

**None. No REST surface change** — no field, DTO, endpoint, validation, or response change. The
field was already a plain `String` on `CallAttemptResponse`, and the vocabulary it may contain is
unchanged in type, only in the set of legal values. Generated OpenAPI is therefore identical; no
OpenAPI test required modification, and the existing contract tests
(`CampaignOpenApiContractTest` 4, `ContactGroupMemberOpenApiContractTest` 5) pass untouched.

## 12. Architecture impact

- **No cycle.** `HangupCauseMapper` lives in `voice.call`; both consumers already depended on it.
  `campaign → voice` and `telephony → {voice, campaign}` are unchanged; `voice` still imports no
  campaign type.
- **Ownership respected:** campaign owns the *meaning* of the vocabulary
  (`CallFailureCode` + its `RetryClass`); voice owns the *translation* from provider input. This is
  exactly the documented principle — "Voice owns telephony-facing abstractions; Telephony adapters
  implement voice-owned contracts."
- **VB-6C untouched:** `DailyDialLimitService`, `voice_blast_daily_usage`,
  `voice_blast_daily_usage_entries` and their repositories were not read for behaviour nor modified.
  The daily DNID-scoped provider-accepted limit is unchanged.

## 13. Tests added / changed

| Suite | Count | Covers |
|---|---|---|
| `HangupCauseMapperTest` *(new)* | 58 | known mappings, canonical output, whitespace trimming, unknown/blank/absent → canonical unknown, no provider-text leak, closed output set, normal-clearing recognition |
| `CallFailureCodeCanonicalizationTest` *(new)* | 15 | `canonicalize` for every code + junk, `fromCode` stays strict, classification never reads emptiness, `isPermanent` delegation, SWITCHED_OFF/NOT_REACHABLE absent |
| `EslEventServiceFailureCodeTest` *(new)* | 22 | end-to-end persistence boundary: canonical codes land on attempt/session, unmapped → unknown, normal clearing persists no code, `COMPLETED` never persisted as a failure, every persisted code classifiable |
| `AgentOutboundCallServiceTest` *(extended)* | +3 (20→23) | O21 numeric `16` completes, O22 unmapped → canonical unknown, O23 absent cause is a failure not a success |

**98 new/extended tests. No existing test was modified, deleted, disabled, or weakened.**
`CallFailureCodeTest` (6) — which asserts `isPermanent("HANGUP_CALL_REJECTED_EXTRA") == false` —
still passes unmodified, because canonicalizing that legacy value to `HANGUP_UNKNOWN` (TEMPORARY)
preserves the result. Two of my own new assertions failed initially and were **corrected to be more
precise, not weakened**: a prefix check that the canonical `HANGUP_UNKNOWN` inevitably violates,
and an expectation that a padded `"user_busy "` should be unknown rather than correctly `BUSY`.

## 14. Full regression evidence

| Item | Value |
|---|---|
| **Full Maven suite** | **1138 tests / 0 failures / 0 errors / 1 skipped — BUILD SUCCESS** (9:59) |
| Baseline before this phase | 1040 / 0 / 0 / 1 |
| Delta | **+98**, fully accounted for: 58 + 15 + 22 + 3 |
| The 1 skipped test | `ObdApplicationTests.contextLoads` — pre-existing, unrelated |
| **ArchitectureTest** | **1/0/0/0 — 0 cycles** (`ApplicationModules.verify()`) |
| **Flyway head** | **V48 — unchanged; no migration in this phase** |
| Surefire configuration | Untouched — `pom.xml` declares none |
| Tests suppressed / disabled / weakened / deleted | **None** |
| OpenAPI | Untouched; no API change. `CampaignOpenApiContractTest` (4) and `ContactGroupMemberOpenApiContractTest` (5) green |

Focused phase run: **128 tests / 0 failures** across the new taxonomy suites plus the pre-existing
`CallFailureCodeTest`, `EslEventServiceTest` and `AgentOutboundCallServiceTest`.

**VB-6C regression — every suite unchanged and green**, confirming the daily DNID limit was not
disturbed:

| VB-6C suite | Result |
|---|---|
| `VoiceBlastDailyDialLimitPostgresIntegrationTest` (real PostgreSQL concurrency) | 12 / 0 / 0 |
| `DailyDialLimitSnapshotPostgresIntegrationTest` (snapshot immutability) | 5 / 0 / 0 |
| `DailyDialLimitServiceTest` | 10 / 0 / 0 |
| `DailyDialLimitObservabilityTest` | 8 / 0 / 0 |
| `OutboundDialServiceRoutingTest` | 19 / 0 / 0 |
| `CampaignDailyDialLimitServiceTest` | 8 / 0 / 0 |
| `CampaignApiSliceTest` | 8 / 0 / 0 |
| `CampaignDailyDialLimitValidationTest` | 10 / 0 / 0 |
| `CampaignOpenApiContractTest` | 4 / 0 / 0 |

## 15. Known limitations

1. **`HANGUP_UNKNOWN` is still classified `TEMPORARY` (retryable).** An unrecognized cause is
   therefore still a candidate for retry. This is unchanged behaviour, now explicit and
   changeable in one constant — but it is a product question (audit OD-2/OD-6) and belongs to
   VB-6D.2, not to a taxonomy phase.
2. **Cause `27`/`15`/`22`/`31` are not distinguished.** They collapse to `HANGUP_UNKNOWN`. Correct
   today given the evidence available; a carrier-specific mapping needs a target-carrier list.
3. **Legacy rows.** Any pre-existing `HANGUP_<string>` value in the database canonicalizes to
   `HANGUP_UNKNOWN` and classifies as before. No data migration was run, which is correct here: the
   project is pre-production (Flyway V48, no deployments) and such rows cannot exist.
4. **Other persistence sites were not changed.** `PlayfileExecutionService`, `DtmfExecutionService`
   and the agent services write literal codes already present in the enum; they were verified, not
   modified. `CallAttemptService`'s `failAttempt(...)` accepts a caller-supplied code and was not
   changed — a future phase should route it through `canonicalize` if an untrusted producer ever
   appears.
5. **Cosmetic, pre-existing, untouched:** `CallFailureCode.java:151` places the
   `// === Orchestration …` section header on the same line as the preceding constant. Out of scope.

## 16. Deferred VB-6D work

Nothing from the retry/safety scope was implemented: no per-rule retry count, no per-rule delay, no
campaign retry configuration, no global daily attempt limit, no campaign stricter limit, no
effective retry policy, no daily-attempt admission, no scheduler change, no observability, no IVR,
no PLAYFILE change. VB-6C's daily DNID limit is untouched.

The audit's §24 sequence now starts at **VB-6D.2** — conditional on the still-open **OD-1 scope
conflict** (retry/safety vs IVR), which this phase does not resolve.

## 17. Final verdict

```
READY
```

- Arbitrary provider hangup strings can no longer become business failure codes ✅
- Unknown causes have deterministic canonical behaviour ✅
- Retry classification cannot accidentally treat unknown values as retryable ✅ *(the empty-lookup
  path is now explicit and routed through a reviewed constant, not an implicit permissive
  default — with the residual product decision on `HANGUP_UNKNOWN` documented in §15.1)*
- Existing call lifecycle semantics remain correct ✅ *(and two latent bugs fixed)*
- Focused tests pass ✅ *(98 new/extended; 128 in the focused phase run)*
- Full regression passes ✅ *(1138 / 0 / 0 / 1 — BUILD SUCCESS)*
- Architecture remains cycle-free ✅
- No migration ✅ · No API change ✅ · No VB-6C change ✅
