# VB-8F — Channel Correlation & External Outcome Recovery

## 1. Status

**COMPLETE WITH DEFERRED FINDINGS**

The audit cleared all ten stop conditions; none fired. The F-8E-03 gap is closed with
a correlation fallback that is secondary to `providerCallId`, gated on
application-owned evidence, and tenant-scoped from persisted context.

---

## 2. Baseline

| | |
|---|---|
| `HEAD` | `3a89e5c` |
| `origin/main` | `3a89e5c` |
| Divergence | `0 / 0` |
| Working tree | **210 dirty entries**, VB-7C.3 / VB-8B / VB-8D / VB-8E uncommitted, plus user-owned FreeSWITCH and frontend work |
| Test baseline | 2063 (0F / 0E / 2S) |
| Architecture baseline | 16 modules / 0 cycles |
| Migration baseline | `V55`, 54 files |

No reset, clean, stash, commit or push. All 210 pre-existing entries preserved.

---

## 3. Audit Scope

Every correlation path was traced, not just the obvious ones:

`EslEvent` (header contract, `CHANNEL_IDENTITY_HEADERS`, `getCallUuid`,
`getOriginationUuid`, `getHangupCause`, `getBridgeBUuid`),
`EslEventService.processEvent` end to end, `handleChannelHangup`,
`isSuccessfulCompletion`, `EslClient` (**both** `originate` overloads and
`originateWithJob` / `originateOnInternalProfile`), `FreeSwitchOutboundDialer`,
`FreeSwitchAgentLegDialer`, `OutboundDialService` (TX-2 ordering),
`CallAttemptRepository`, `CallSessionRepository`, `CallLegRepository`,
`CampaignExecutionRepository`, `CampaignRuntimeConfigResolver`,
`DailyDialLimitService` (`admit` / `confirmAccepted` / `releaseReservation` /
`resolveUsageDate`), `HangupCauseMapper`, `StaleCallReconciler`,
`CampaignExecutionOrchestrator.reconcileExecution`, plus the user-owned
`EslEventRuntimeContractTest` and `EslProtocolTest` (read-only, unmodified).

Structural search for `origination_uuid`, `providerCallId`, `Unique-ID`, `uuid`,
`CHANNEL_HANGUP`, `CHANNEL_ANSWER`, `CHANNEL_CREATE`, `CHANNEL_BRIDGE`,
`uuid_exists` across main and test.

**`uuid_exists` does not appear anywhere in the repository.**

---

## 4. Existing Correlation Model

`FreeSwitchOutboundDialer` pins the channel identity itself:

```java
String channelUuid = request.callAttemptId() == null ? null : request.callAttemptId().toString();
// EslClient: bgapi originate {origination_uuid=<attemptId>,...}
```

So for a campaign customer leg, `Unique-ID == origination_uuid == CallAttempt.id`,
deterministic **before the call is placed**. `providerCallId` is then persisted to
that same value, and `EslEventService` correlates on it.

Two facts from the audit that shaped the whole design:

- **`variable_origination_uuid` reaches the event.** `EslEvent.getOriginationUuid()`
  reads it, and the user-owned runtime contract test asserts it is reported when
  the switch echoes it back. `EslEvent`'s own javadoc documents *measured* header
  dumps across five event types including `CHANNEL_HANGUP`, showing
  `variable_*` headers are present.
- **Only the platform can set it.** FreeSWITCH generates channel UUIDs itself for
  every channel it receives or originates. The pinned variable is present only when
  *we* originated the channel with an explicit identity.

---

## 5. Ownership Model

**Primary (authoritative, unchanged):** `event.providerCallId` → `CallSession` /
`CallAttempt`. Resolves or it does not; when it resolves, nothing else runs.

**Fallback (recovery-only):** the conjunction below. Every element is required; a
missing element means no attribution.

| # | Evidence | What it proves |
|---|---|---|
| 1 | `variable_origination_uuid` present | the **platform** originated this channel with a pinned identity — inbound, browser/WebRTC and unrelated channels cannot satisfy this |
| 2 | it **equals** the event's channel identity | the pinned value *is* this channel, so the platform is the creator and the identity is deliberate rather than random |
| 3 | it parses as a `UUID` | it could name an attempt at all |
| 4 | that attempt is `IN_PROGRESS` | it was claimed and is still unsettled — no terminal resurrection, no double-transition |
| 5 | that attempt has **no** `CallSession` | the dispatch transaction really did roll back; if a session exists, primary owns it |
| 6 | attempt has tenant + contact + DID | the VB-6C ledger identity is knowable, so nothing is recorded against a guess |
| 7 | tenant comes from **that persisted row** | tenant is never inferred from a UUID, number, DID or channel name |

**The agent leg is the interesting case.** `EslClient`'s 4-arg `originate` generates
`UUID.randomUUID()` and still *pins* it, so agent legs **do** carry
`variable_origination_uuid` — but to a random value that names no attempt. They pass
checks 1–3 and fail at the lookup, by construction rather than by luck.

---

## 6. F-8E-03 Root Cause

```
FreeSWITCH places the call          channel Unique-ID == attempt id  (real side effect)
        ↓
TX-2 rolls back                    no CallSession, no CallLeg, no providerCallId,
                                   no capacity allocation, no VB-6C/VB-6D.3 rows
        ↓
CHANNEL_HANGUP arrives             the event carries everything needed to identify the call
        ↓
findByProviderCallIdAndDeletedAtIsNull(channelUuid)
                                   → miss: the column that would have matched is the
                                     very column the rollback erased
        ↓
"no call attempt found", debug log, return false
                                   the real outcome is discarded; the attempt stays
                                   IN_PROGRESS until VB-8E's orphan sweep settles it
                                   as CANCELLED with the outcome explicitly unknown
```

The identity was never lost — FreeSWITCH echoed the attempt id back on the event.
The **persisted** half of the correlation key was lost, and correlation had exactly
one key.

---

## 7. Correlation Decision Table

| # | Event source | providerCallId resolves | Unique-ID matches a candidate | Domain/context proves campaign outbound | Tenant proves ownership | Action |
|---|---|---|---|---|---|---|
| 1 | normal campaign outbound | yes | yes | yes | yes | **primary path only**; fallback never consulted |
| 2 | TX-2 rollback + CHANNEL_HANGUP | no | yes | yes (pinned == identity) | yes (from the attempt row) | **recover** the real outcome |
| 3 | inbound call | no | no | no — platform never originated it | n/a | no attribution |
| 4 | agent leg, leg row present | no (matches leg) | no | claimed by the agent path first | n/a | agent path only |
| 5 | CONNECT_BY_AGENT agent leg | no | no (random pin) | no — random pin names no attempt | n/a | no campaign attribution |
| 6 | browser / WebRTC | no | no | no — not platform-originated | n/a | no attribution |
| 7 | unrelated FreeSWITCH channel | no | no | no | n/a | no attribution |
| 8 | another tenant's attempt id pinned | no | yes | yes | yes — **from the attempt's own row** | settles only *that* attempt; no cross-tenant write is possible |
| 9 | ambiguous / multiple candidates | no | — | — | — | **no attribution**; never "first row", "newest", "by tenant" |
| 10 | stale attempt (terminal) | no | yes | yes | yes | no attribution — no resurrection |
| 11 | already-correlated session | yes | — | — | — | **primary path**; recovery additionally declines |
| 12 | event with no channel identity | no | — | — | — | dropped with a warning, as before |
| 13 | malformed pinned UUID | no | no | no | n/a | no attribution, no throw |
| 14 | duplicate CHANNEL_HANGUP | yes (recovered id) | — | — | — | primary path + terminal guard; idempotent |
| 15 | foreign channel pinning a campaign-looking UUID via raw ESL | no | yes | yes | yes | **would** attribute — see §16 F-8F-02; requires FreeSWITCH socket access, outside the trust boundary |
| 16 | non-terminal event (answer/progress) on a lost dispatch | no | yes | yes | yes | deliberately ignored; VB-8E's orphan sweep is the backstop |

---

## 8. Safe Fallback Contract

The fallback lives **only** inside the `attemptOpt.isEmpty()` branch of
`processEvent`, which is reachable only when the primary lookup definitively found
nothing. It is structurally impossible for it to run when primary resolves.

**Primary correlation → (if unresolved) → validated recovery → (if any check fails)
→ no attribution, no retry, no mutation.**

Ambiguity is resolved by declining, never by choosing. Tenant ownership is read from
the persisted attempt row and nowhere else. The recovery re-reads and re-validates
the attempt itself rather than trusting the caller's conclusion, because "still
claimed, no session" is a database fact that an event cannot assert.

A gate that was **considered and deliberately rejected**: requiring
`Call-Direction: outbound`. This repository has no measured evidence that FreeSWITCH
reports that header on an originated channel's hangup — the captured dumps in
`EslEvent` list `variable_call_uuid` and `variable_uuid`, and the runtime contract's
real-shape fixture carries no direction header. Requiring an unproven header would
have converted the recovery into a permanent silent no-op, which is a worse outcome
than the marginal defence-in-depth it buys. Inbound is already excluded by
requirement 1: the platform does not originate inbound channels, so it cannot have
set their pinned variable.

---

## 9. uuid_exists Assessment

**Not used, and deliberately so.**

1. *What ambiguity would it resolve?* Only the inverse case — "was the channel still
   alive when we went looking?". It cannot establish campaign ownership, tenant, or
   call domain, which are the questions that actually gate attribution.
2. *When would it be called?* Inside an ESL event handler, i.e. **inside the database
   transaction** that `processEvent` opens. That is precisely the boundary rule 16
   forbids: adding a network round-trip to FreeSWITCH inside a DB transaction that
   holds a lock on the attempt row.
3. *What does true prove?* A channel with that UUID exists. It does not prove that
   channel is ours.
4. *What does false prove?* Nothing conclusive — the channel may have hung up
   between the event and the probe, which is the common case for `CHANNEL_HANGUP`.
5. *Can it race?* Yes, by construction: the event that triggered the probe is
   itself the channel's disappearance.
6. *Net effect?* It would add an external I/O dependency and a new failure mode to
   a path whose safety comes from evidence already in hand, in exchange for
   information that cannot change an attribution decision.

Not used. The evidence the design actually needs is already present on the event.

---

## 10. Implementation

| File | Change |
|---|---|
| `campaign/OrphanedDispatchRecovery.java` | **New.** Owns the settle. Re-validates the attempt, maps the hangup through the canonical `HangupCauseMapper`, recovers `providerCallId`, records the acceptance, saves. |
| `telephony/EslEventService.java` | **+1 field** (`Optional<OrphanedDispatchRecovery>`, mirroring the three existing optional boundaries), **+1 legacy 11-arg constructor** so every existing construction site is untouched, **+1 discriminator** (`orphanedCampaignAttemptId`), **+1 branch** inside the primary-miss path. |

No refactor of `EslClient`, `EslEvent`, the dial path, or any existing handler.
`handleChannelHangup` is **not** reused for the settle because it assumes a session;
instead the canonical *mapping* (`HangupCauseMapper`) and the identical
terminal-status guard are applied to the session-less case, so there is exactly one
hangup taxonomy and one notion of an already-settled attempt.

Layering follows the codebase's existing convention: **telephony owns correlation,
campaign owns policy and state** — the same split as `agentConnectEvents`,
`inboundCallEvents` and `outboundAgentCallService`.

### A defect the focused tests could not catch

`EslEventService` was `@RequiredArgsConstructor`, so it had exactly one constructor.
Retaining the pre-VB-8F constructor for existing call sites gave it **two**, and
Spring — given more than one constructor with no `@Autowired` — falls back to looking
for a no-arg constructor. The result was `No default constructor found`, failing the
`eslEventService` bean and taking **75 tests across the suite with it**, all of them
in unrelated classes that merely load the application context.

Every VB-8F test constructs `EslEventService` directly, so none of them exercised
container injection; only the full suite did. The fix follows the existing
`StaleCallReconciler` precedent: both constructors written out explicitly, with
`@Autowired` on the full one. `@RequiredArgsConstructor` was removed, since a
generated constructor cannot be annotated.

---

## 11. Database / Migration Changes

**None.** Head remains `V55`, 54 files. The recovery uses only existing columns
(`status`, `failure_code`, `failure_reason`, `completed_at`, `provider_call_id`) and
introduces **no new persistent state and no new status value**. `CANCELLED` /
`COMPLETED` / `FAILED` and the existing `VoiceBlastDailyUsageEntry` were all already
there — the recovery writes them, it does not extend them.

---

## 12. API / OpenAPI Changes

**None.** No controller, request, response, status code, validation or schema
change. No OpenAPI regeneration required, and none performed.

---

## 13. Regression Matrix

| Test | Scenario | Expected | Actual |
|---|---|---|---|
| F8-A | primary resolves | primary only; fallback not consulted | ✅ |
| F8-B | TX-2 rollback + hangup | real outcome recorded, id recovered | ✅ |
| F8-B2 | `USER_BUSY` hangup | `FAILED` + canonical `BUSY` | ✅ |
| F8-B3 | no second dispatch | 0 dials, 1 attempt total | ✅ |
| F8-B4 | capacity | never released (`verifyNoInteractions`) | ✅ |
| F8-B5 | acceptance recorded | exactly one entry, correct providerCallId | ✅ |
| F8-B6 | bucket not inflated | `usedCount == 0` (hold was rolled back) | ✅ |
| F8-B7 | execution converges | `COMPLETED` | ✅ |
| F8-B8 | retry not duplicated | attempt is `FAILED`, policy decides | ✅ |
| F8-B-RB | **real forced TX-2 rollback** | dial happened, no session/id/ledger row | ✅ |
| F8-C | inbound channel | campaign attempt untouched | ✅ |
| F8-D/E | agent leg | agent path claims it, or no campaign attribution | ✅ |
| F8-F | unrelated channel | no attribution | ✅ |
| F8-F2 | foreign channel, attempt-shaped id, no pin | no attribution | ✅ |
| F8-F3 | foreign channel pinning a real attempt id | rejected (pinned ≠ identity) | ✅ |
| F8-G | cross-tenant | only the named attempt settles; ownership from its row | ✅ |
| F8-H | malformed / ambiguous UUID | no attribution, no throw | ✅ |
| F8-I | primary precedence | primary wins; fallback never overrides | ✅ |
| F8-J | duplicate hangup | idempotent; exactly one entry | ✅ |
| F8-K | terminal attempt | no resurrection | ✅ |
| F8-K2 | attempt that has a session | recovery declines; primary owns it | ✅ |
| F8-L | non-terminal event | never triggers recovery | ✅ |

`OrphanedDispatchRecoveryEslTest` 17 tests + `OrphanedDispatchRecoveryPostgresIntegrationTest` 15 tests = **32 new tests, all green**.

---

## 14. Real ESL Coverage

Stated precisely, because the distinction matters:

- **Fake ESL (`FakeEslServer`, user-owned, unmodified):** not needed — the new
  boundary is a pure event-correlation decision, so events are constructed directly
  in the `EslEvent` shape the real switch produces. No second fake was created.
- **PostgreSQL (Testcontainers, real, Flyway V1..V55):** the whole DB half of the
  contract — `IN_PROGRESS`, session absence, tenant identity, the ledger, the
  execution reconciliation, and a genuinely forced TX-2 rollback.
- **Event-shape fidelity:** the "real shape" fixtures reuse the exact header set the
  user-owned `EslEventRuntimeContractTest` documents, including
  `variable_origination_uuid`.
- **Live FreeSWITCH: NOT performed.** No live switch was available in this
  environment, and the user's live harness (`LiveFreeSwitchRuntimeContractTest`,
  `EslEventRuntimeContractTest`) was not modified or invoked. These tests are
  **not** a live FreeSWITCH validation, and this report does not claim one.

---

## 15. Safety Invariants

| Invariant | How it is enforced | Evidence |
|---|---|---|
| `providerCallId` primary | fallback lives only inside the `attemptOpt.isEmpty()` branch | F8-A, F8-I |
| Safe fallback | 7-element conjunction, every element required | F8-B + decision table |
| Tenant isolation | tenant read from the persisted attempt row, never inferred | F8-G, and the ESL-layer test asserting no lookup by id |
| Domain isolation | pinned variable ⇒ platform-originated; agent legs carry a random pin | F8-C, F8-D, F8-E, F8-F, F8-F2, F8-F3 |
| No foreign attribution | every non-campaign case fails at a required check | 9 foreign-channel tests |
| No ambiguous retry | any failed check ⇒ `return false`, nothing written | F8-H, F8-L |
| Idempotency | `IN_PROGRESS` required; the guard *is* the idempotency mechanism | F8-J |
| No double safety consumption | `confirmAccepted` unmodified: unique on `call_attempt_id` + hold-guarded | F8-B5, F8-B6 |
| No double capacity release | recovery performs no capacity call at all | F8-B4 |
| No terminal resurrection | only `IN_PROGRESS` is eligible | F8-K, F8-K2 |
| No second dispatch | recovery never reaches the dialer | F8-B3 |
| No retry-semantics change | settles to `FAILED`; `processRetries` remains the sole retry authority | F8-B8 |
| No external I/O in a transaction | recovery is pure DB; **no** `uuid_exists`, no ESL call | §9 |
| No new scheduler / queue / lock | none added | §10 |

---

## 16. Deferred Findings

### F-8F-01 (MEDIUM) — the recovered dial is not counted against the daily dial bucket

**Risk.** The recovered acceptance records the per-attempt ledger entry but does
**not** increment the VB-6C bucket, because `confirmUsed` is guarded by the pre-dial
hold and that hold rolled back with the crash. The contact can therefore be dialled
one more time that day than the limit allows. The gap exists today and is not made
worse by this phase.

**Why it does not fit VB-8F.** Enforcing the bucket requires a *retroactive
admission* for a dial that already happened. Admission is defined as a pre-dial gate;
inventing a post-dial equivalent is a change to the daily-safety model, which rule 8
forbids and rule 15C warns against guessing. Reconstructing the hold would also
manufacture a reservation the platform has no evidence was ever granted.

**Recommended future phase.** `VB-6E — retroactive daily-limit reconciliation`,
deciding deliberately whether a recovered acceptance should consume a slot, and if
so introducing an explicit, separately-audited operation rather than reusing
`admit`.

**Regression test to add then.** Recover a hangup, then assert the bucket increments
by exactly one — or, if the decision is "do not consume", assert that explicitly so
the choice is pinned rather than incidental. Today `acceptanceDoesNotInflateTheBucket`
pins the *current* behaviour so it cannot drift silently.

### F-8F-02 (INFO) — operator with raw ESL socket access can forge attribution

A channel originated with `origination_uuid` equal to a live, session-less attempt id
**would** satisfy every check. `EslClient.originateOnInternalProfile` can do exactly
this, but it is documented as *"No production code path calls it"* and exists only
for live event-contract validation. An adversary at that level can do far worse than
mis-attribute one call, so this is outside the trust boundary and is recorded rather
than engineered against. No mitigation is proposed, because any real one (for
example, a second pinned secret variable) would add platform-owned state to solve a
threat the system does not otherwise defend against.

### F-8F-03 (INFO) — non-terminal events on a lost dispatch are ignored

A session-less `CHANNEL_ANSWER` is deliberately dropped: it carries no final outcome
and nothing could act on it without a session. If the hangup is also lost, VB-8E's
orphan sweep settles the attempt `CANCELLED` with the outcome unknown — the
pre-existing conservative backstop, unchanged.

---

## 17. Final Verification

| Scope | Result |
|---|---|
| Focused correlation (F8-A..L, ESL boundary) | `OrphanedDispatchRecoveryEslTest` — **17 run / 0F / 0E / 0S** |
| PostgreSQL (F8-B + isolation) | `OrphanedDispatchRecoveryPostgresIntegrationTest` — **15 run / 0F / 0E / 0S** |
| Existing telephony regression | 9 classes incl. user-owned `EslEventRuntimeContractTest` and `EslProtocolTest` — **130 run / 0F / 0E / 0S** |
| Full suite | **2095 run / 0 failures / 0 errors / 2 skipped** — `BUILD SUCCESS` |
| Test delta | 2063 → 2095, exactly the 32 new tests; no existing test altered |
| Architecture | `ArchitectureTest` green — **16 modules / 0 cycles** |
| Migration | `V55`, 54 files, **0 added** |
| API / OpenAPI | **unchanged** |
| Working tree | 4 VB-8F files; 210/210 pre-existing entries preserved |
| Git | `HEAD` `3a89e5c`; 0 staged; 0 stashes; **no commit, no push** |

The first full-suite run failed with 75 errors from the constructor defect described
in §10; the second, after the fix, is the result above. Both runs are recorded rather
than only the successful one.

---

## 18. Final Verdict

**READY WITH DEFERRED FINDINGS**

### Safe
Identity from pinned `origination_uuid` plus database-validated state — `providerCallId` primary, fallback structurally unreachable when primary resolves, inbound/browser/unrelated channels excluded by origination itself, agent legs excluded by their random pin, malformed UUIDs rejected, ambiguity declined. No fabricated session, no capacity release, no double ledger consumption, no resurrection, no new retry model, no external I/O in a transaction.

### Incomplete
**F-8F-01 (MEDIUM):** a recovered dial's ledger entry is recorded but its VB-6C **bucket** is not incremented, because the hold rolled back and the increment is hold-guarded. Real gap; not worsened by this phase; cannot be fixed without changing the daily-safety model, so deferred with rationale and a pinned current-behaviour test. **F-8F-02/F-8F-03 (INFO):** recorded, not engineered against.

### No live FreeSWITCH validation
`uuid_exists` rejected on correctness and transaction-boundary grounds. No live switch was exercised, and no such claim is made.
