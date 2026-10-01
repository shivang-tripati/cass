# LIVE FREESWITCH - PHASE D: RUNTIME CORRELATION & CONTRACT HARDENING

**Status: PASS WITH LIMITATIONS**

Phase D set out to make the Java integration understand the FreeSWITCH runtime
contract that Phase C measured, without redesigning the telephony architecture.
It did that, and in doing so it found a defect considerably more severe than the
three it was asked to fix.

**The headline: `uuid_kill` and `uuid_broadcast` were being rejected by
FreeSWITCH.** The platform's playback, hangup and bridge commands had never
worked against a real switch. The entire test suite was green, because the
protocol double answered `+OK accepted` to every command it did not recognise.
Only running the real client against the real switch exposed it.

Phase D changes no architecture, adds no abstraction, and modifies no file
outside `com.shivang.obd.telephony`. It is a phase record plus the durable
knowledge in [`docs/freeswitch/`](freeswitch/README.md).

---

## 1. Executive Summary

| | |
|---|---|
| **J1** event correlation | **RESOLVED** — code fix, live-verified |
| **J2** playback failure | **RESOLVED** — code fix, live-verified |
| **J3** channel identity | **RESOLVED by evidence — no code change was needed** |
| **J4** *(found in Phase D)* api-prefixed commands | **RESOLVED** — code fix, live-verified |
| Architecture changed | **NO** |
| Automated tests | telephony **144/144 green**; full suite green apart from 4 classes in another workstream's area |
| Live tests | **4/4 green against the running switch** |
| Carrier / PSTN | **NOT TESTED** |

## 2. Scope

Phase D changed, and intentionally did not change:

**Changed**

- `EslEvent` — channel identity resolution (J1) and case-insensitive headers.
- `EslClient` — `api`-prefixed channel commands (J4), `api` reply handling, the
  new `CHANNEL_EXECUTE_COMPLETE` subscription (J2), and one narrow test seam.
- `EslEventService` — playback lifecycle detection (J2).
- `FakeEslServer` — now models the switch's rejection of unrecognised commands.
- Tests: a new contract suite, a live suite, and corrections to stale assertions.

**Deliberately not changed**

- No telephony architecture. No new abstraction, no event bus, no state machine
  redesign, no database change.
- No campaign, audio, IVR, DTMF, agent or scheduling code.
- No infrastructure. `infra/docker-compose.yml` is untouched and was verified
  clean. One prerequisite is *documented* rather than applied — see §12.
- No Java in any package other than `telephony`.

## 3. Phase C Defects Entering Phase D

| # | Phase C finding | Phase D outcome |
|---|---|---|
| **J1** | `Call-UUID` is not emitted; identity arrives as `Channel-Call-UUID` / `Unique-ID` | code fix |
| **J2** | a missing file produces no `PLAYBACK_ERROR`; only a log line | code fix, using a mechanism measured in Phase D |
| **J3** | a transfer mints a new channel UUID | **no code change needed** — the pinned identity was measured to remain addressable |

## 4. Runtime Contract

Established by measurement, and now asserted by tests.

```text
CallAttempt ID                 application identity (a business UUID)
        │  stored as
        ▼
CallSession.providerCallId    the UUID Java SUPPLIED via origination_uuid
        │
        │  FreeSWITCH reports it back as
        ▼
  variable_origination_uuid    on the channel Java created
  Channel-Call-UUID            the authoritative channel identity  ← J1 reads this
  Unique-ID                    the conventional channel identity  ← J1 falls back to this
        │
        │  NOT the same thing
        ▼
  ESL Job-UUID                 a background TASK id, never a channel identity
```

Three distinctions the code now enforces:

- **`Job-UUID` is never a channel identity.** It appears only in
  `originateWithJob`, which is private for exactly that reason, and
  `EslEvent.getCallUuid()` does not read it. There is a test asserting a
  `Job-UUID`-only event has no channel identity.
- **`origination_uuid` is the value Java supplies**, echoed back by the switch.
  `EslEvent.getOriginationUuid()` exposes it separately.
- **The channel identity is read from headers, in a defined order**, not from a
  single hard-coded name. See `EslEvent.CHANNEL_IDENTITY_HEADERS`.

### Header resolution order

| Order | Header | Why |
|---|---|---|
| 1 | `Channel-Call-UUID` | the explicit channel-identity header |
| 2 | `Unique-ID` | the conventional channel header; present on every event measured |
| 3 | `Call-UUID` | **compatibility only** — this switch never emits it |

## 5. J1 Resolution

**Problem.** `EslEvent.getCallUuid()` read `Call-UUID`. Phase C measured that
header absent on all five event types captured, so it returned `null` for every
event, `EslEventService.processEvent` dropped every event at its first guard, and
the "without Call-UUID" warning fired on every event.

**Observation.** The channel UUID is present under five names; the two
authoritative ones are `Channel-Call-UUID` and `Unique-ID`. Header lookup was
also **case-sensitive** in `EslEvent` while `EslMessage` was case-insensitive —
an inconsistency that could silently reintroduce the same class of defect.

**Fix.**

1. `getCallUuid()` resolves through `CHANNEL_IDENTITY_HEADERS`, in order.
2. `EslEvent` now stores headers in a case-insensitive map, matching
   `EslMessage.header`. A casing change by the provider can no longer silently
   break correlation.
3. The guard in `processEvent` now names the headers it searched, so a future
   header change is diagnosable rather than mysterious.

**Evidence** — live, from `LiveFreeSwitchRuntimeContractTest`:

```text
LiveFreeSwitchRuntimeContractTest.liveEventsResolveChannelIdentity  PASSED
  assertThat(created.getHeader("Call-UUID")).isNull()      <- switch emits none
  assertThat(created.getCallUuid()).isEqualTo(channelUuid)  <- identity resolves
  assertThat(created.getOriginationUuid()).isEqualTo(channelUuid)
```

**Regression tests.** 12 in `EslEventRuntimeContractTest` plus 4 in
`EslProtocolTest`: each identity header in isolation, precedence, case
insensitivity, blank-as-absent, `Job-UUID` rejected, unrelated channel not
correlated, missing identity safe no-op.

## 6. J2 Resolution

**Problem.** The client read a `Playback-Error` header from a `PLAYBACK_ERROR`
event. Phase C established that a missing file produces neither, while
`uuid_broadcast` still answers `+OK Message sent`. So the failure branch was
unreachable and a call with a missing asset sat in `ANSWERED` until the
maximum-duration sweeper killed it, recorded as `MAX_DURATION_EXCEEDED` rather
than the media failure it actually was.

**Investigation first, as required.** Rather than assume a mechanism, Phase D
captured both cases on one live answered channel and compared them:

| Case | Command reply | Events on the channel |
|---|---|---|
| valid file | `+OK Message sent` | `CHANNEL_EXECUTE`, **`PLAYBACK_START`**, **`PLAYBACK_STOP`**, `CHANNEL_EXECUTE_COMPLETE` |
| missing file | `+OK Message sent` | `CHANNEL_EXECUTE`, `CHANNEL_EXECUTE_COMPLETE` |

Both cases emit `CHANNEL_EXECUTE` and `CHANNEL_EXECUTE_COMPLETE`; those do not
discriminate. The only difference is **`PLAYBACK_START`**. The `+OK` reply is
byte-identical in both cases. `PLAYBACK_ERROR` is never emitted.

**Fix.** `CHANNEL_EXECUTE_COMPLETE` was added to the subscription and closes the
request. If playback had started by then, the request succeeded; if it had not,
the accepted command produced no audio, which is a media failure. The
`PLAYBACK_ERROR` branch is retained — a real switch may emit it for other
failures — and both routes converge on one `handlePlaybackFailure` method, so
they cannot disagree about classification or teardown.

**Semantic distinction now enforced:**

| Stage | Signal |
|---|---|
| command accepted | `+OK` on the reply — **not** evidence of playback |
| playback started | `PLAYBACK_START` — the only positive evidence audio is playing |
| playback completed | `PLAYBACK_STOP` |
| playback failed | `CHANNEL_EXECUTE_COMPLETE` with no preceding `PLAYBACK_START` |

**Evidence** — live, both cases, plus 7 unit tests covering valid playback,
missing media, completion, explicit `PLAYBACK_ERROR`, duplicate completion,
hangup after completion, and a recorded failure surviving a `NORMAL_CLEARING`
hangup.

**Business semantics preserved.** `PLAYBACK_FAILED`, its TEMPORARY
classification, the single hangup-path reservation release, and the
`session.getFailureCode()` precedence are all unchanged.

## 7. J3 Resolution

**This resolved to no code change, and that is the finding.**

Phase C observed a transfer minting a new channel UUID and assumed the stored
identity would go stale. Phase D tested the assumption rather than acting on it.

**Measurement 1 — a call creates several channels, and none links back.**

```text
pinned 34b5c6c8  events: CHANNEL_CREATE, CHANNEL_ANSWER, CHANNEL_EXECUTE
3aa6cc08         events: ... CHANNEL_BRIDGE
59dd8c24         events: ... PLAYBACK_START, PLAYBACK_STOP
```

`CHANNEL_BRIDGE` is anchored on a channel that is **not** the pinned one, and no
header on any of them links back to the pinned UUID. The premise of J3 was
correct.

**Measurement 2 — but the pinned UUID is still addressable.** While the call was
bridged:

```text
uuid_broadcast <pinned> /media/obd/phase-c-test-tone.wav aleg
  -> +OK Message sent
  -> PLAYBACK_START and PLAYBACK_STOP on the PINNED channel
uuid_kill <pinned> NORMAL_CLEARING
  -> +OK
  -> CHANNEL_HANGUP on the PINNED channel, Hangup-Cause = NORMAL_CLEARING
```

**Deviation from the Phase C expectation.** Phase C recorded "a dialplan
transfer mints a new channel, so the pinned UUID is stale". That is true of the
*bridge* participants. It is **not** true of the *originated* channel, which is
the identity the platform addresses.

**Resolution.** No code change. The existing model — persist the supplied
`origination_uuid` as `providerCallId`, and address media and hangup by it — is
correct. Introducing a "current channel" concept would have been an abstraction
the observed behaviour does not require, which the brief explicitly warns
against.

**What did change** is the documentation, which previously implied the pinned
UUID is the only identity in play. It is now stated precisely: the pinned UUID is
the addressable identity of the originated leg; other channels exist on the
far side; and no header links them together.

## 8. PLAYFILE Validation

| Scenario | Expected | Observed | Result |
|---|---|---|---|
| valid file | start, complete, no false failure | `PLAYBACK_START` then `PLAYBACK_STOP`, session `PLAYBACK_COMPLETED` | PASS |
| missing file | failure detected, no false success | no `PLAYBACK_START`; `CHANNEL_EXECUTE_COMPLETE`; `PLAYBACK_FAILED` recorded, teardown requested | PASS |
| hangup during playback | single terminal transition | teardown once, reservation released once | PASS |
| duplicate completion | idempotent | teardown invoked exactly once | PASS |

## 9. Hangup Validation

```text
Java uuid_kill (api-prefixed) -> +OK
  -> CHANNEL_HANGUP, Unique-ID = the pinned UUID
  -> Hangup-Cause = NORMAL_CLEARING
  -> attempt COMPLETED, reservation released once
```

A failure recorded before the hangup is **not** overwritten as success — the
existing `session.getFailureCode()` precedence still holds, verified by a test
where a missing file is followed by a `NORMAL_CLEARING` hangup and the attempt
still fails. Hangup taxonomy is unchanged.

## 10. Max Duration Validation

`StaleCallReconciler.finalizeOverdueSession` records `MAX_DURATION_EXCEEDED` on
the session, then calls `mediaController.terminateCall(sessionId,
session.getProviderCallId())`. That is the same pinned identity proven
addressable in §7, and the teardown now succeeds because the command is
correctly `api`-prefixed. **No code change required.**

Not independently re-tested end-to-end in this phase — it is covered by the
existing `StaleCallReconciler` and `AgentConnectTimeoutScheduler` tests, and the
underlying command path is live-verified. Recorded as PARTIAL in §14.

## 11. DTMF / IVR Validation

**Not re-exercised in Phase D, and deliberately so.** DTMF remains
**NOT YET TESTED** from Phase C for a reason Phase D did not change: the endpoint
emits DTMF immediately after answer, before the legs are bridged, so there is no
RTP stream for RFC 4733 events to travel on. Bridging first requires a dialplan
change that is out of scope here.

J1's fix directly benefits DTMF: `handleChannelDtmf` is reached only after
correlation, and correlation previously always failed. A unit test now covers
DTMF correlating through the real header set. IVR is unchanged.

## 12. CONNECT_BY_AGENT Validation

**Not re-exercised, and one prerequisite is missing.** A test asserts a
`CHANNEL_BRIDGE` anchored on a foreign channel is safely ignored rather than
misapplied to this call.

The blocker is infrastructure, and it is documented rather than worked around
(§24 of the brief). `EslClient.originate` builds
`sofia/gateway/<gateway>/<destination>`. The only configured gateway is
`fs-gateway`, whose proxy `freeswitch-provider:5080` **does not resolve**,
because no carrier exists:

```text
Name  fs-gateway     Proxy  sip:freeswitch-provider:5080    State NOREG
FailedCallsOUT 0
```

So the Java client cannot place a local call through its production path at all.
**The smallest change that would unblock it** is one additional gateway on the
platform's `external` profile pointing at the in-network test extension, leaving
`fs-gateway` untouched for the future carrier. That is a Phase C infrastructure
addition, not a Java change, and it is left for an explicit decision rather than
made unilaterally here.

The live tests therefore place calls on the internal profile through a narrow,
test-only client seam, and say so.

## 13. Automated Test Results

```text
mvnw -o test -Dtest='<all telephony classes>'    -> Tests run: 144, Failures: 0, Errors: 0
mvnw -o test -Dtest='LiveFreeSwitchRuntimeContractTest'
                                                  -> Tests run: 4, Failures: 0, Errors: 0
```

Live tests are skipped unless a switch is configured, so the suite stays green
without one.

### 13.1 Failures outside this phase, and who owns them

Every `telephony` class is green. One class outside this phase fails:

```text
AudioUploadPostgresIntegrationTest.readyWithApprovedStoredAsset
  AudioUploadPostgresIntegrationTest.java:279
  Expecting value to be true but was false          <- response.ready()
```

**This is not Phase D's defect, and Phase D did not fix it.** Attribution is
established from the working tree, not inferred:

| Evidence | Finding |
|---|---|
| `CampaignReadinessService.java` is `M` (modified, uncommitted) | the file is not as at `HEAD` |
| `checkExecutionTimezone` occurs **3×** in the working tree, **0×** at `HEAD` | the rule is uncommitted work in flight |
| `HEAD`'s version has 8 `check*` methods, none of them `checkExecutionTimezone` | the rule is new, not a long-standing one |
| the new rule adds `SCHEDULE_TIMEZONE_REQUIRED` when `schedule.timezone` is blank, and is called **unconditionally** — unfiltered by campaign type and outside the null-schedule branch | it fires for any fixture lacking a timezone |
| `AudioUploadPostgresIntegrationTest` never sets an execution timezone (no `setExecutionTimezone` anywhere in the file) | the fixture cannot satisfy the new rule |
| the failing assertion is `response.ready()` | exactly what the new reason suppresses |

So: a new, uncommitted readiness rule rejects an existing fixture that was
written before the rule existed. The correct owner is the workstream that added
the rule and that owns the fixture.

**Phase D changed nothing in that area.** The diff of `EslEventService.java`
touches no campaign import at all — its three `com.shivang.obd.campaign`
references (`CallAttempt`, `CallAttemptRepository`, `CallAttemptStatus`) are
pre-existing and unmodified. No Phase D file imports a campaign class except
`EslEventService`, which did so before this phase.

**Not fixed here, deliberately.** Editing that test, or relaxing the new rule to
keep it passing, would be absorbing another workstream's in-flight work into a
telephony phase — and one of those two edits would be wrong: if the rule is
intended, the *fixture* is what needs updating, not the rule.

**Note on the earlier count.** An earlier run of this phase recorded four
failing classes in this area. The concurrent workstream has since added
`CampaignTypeCapabilityValidationTest` and reworked
`CampaignReadinessServiceTest`; three of the four now pass, and only the audio
fixture remains. The failure set is therefore **live** — it changes under
foot — which is the main reason this section is attributed rather than merely
reported.

## 14. Live Evidence Matrix

| # | Scenario | Result |
|---|---|---|
| 1 | ESL authentication against the real switch | **PASS** — `UP` reported through the client |
| 2 | Event subscription accepted by the real switch | **PASS** — 11 events incl. `CHANNEL_EXECUTE_COMPLETE` |
| 3 | J1 identity from real headers; no `Call-UUID` | **PASS** |
| 4 | J2 playback lifecycle, valid vs missing file | **PASS** |
| 5 | J3 pinned UUID addressable after bridging | **PASS** — broadcast + kill both acted on it |
| 6 | J4 `uuid_kill` / `uuid_broadcast` / `uuid_bridge` | **PASS** — all accepted |
| 7 | Java-originated call via the production gateway path | **BLOCKED** — no routable gateway (§12) |
| 8 | Max duration end-to-end | **PARTIAL** — command path live-verified; not re-run end-to-end |
| 9 | DTMF | **NOT YET TESTED** — needs bridging first (Phase C §6.11) |
| 10 | IVR | **NOT YET TESTED** — depends on DTMF |
| 11 | CONNECT_BY_AGENT | **BLOCKED** — depends on #7 |
| 12 | Carrier / PSTN | **NOT TESTED** |

## 15. J4 — The Defect Phase D Found

Worth stating separately, because it is the most important result in this phase.

**Observation, measured on the live switch:**

```text
uuid_kill <uuid> NORMAL_CLEARING            -> -ERR command not found
api uuid_kill <uuid> NORMAL_CLEARING        -> accepted
uuid_broadcast <uuid> <path> aleg           -> -ERR command not found
api uuid_broadcast <uuid> <path> aleg       -> accepted
```

ESL accepts a fixed set of inbound commands — `api`, `bgapi`, `event`, `filter`,
`linger`, `exit`, `hup`, `log`. Anything else is rejected. `uuid_kill`,
`uuid_broadcast` and `uuid_bridge` are **APIs**, so they require the `api`
prefix. `bgapi` is a genuine inbound command, which is why `originate` worked
and the rest did not.

**Impact.** Every hangup, every playback and every bridge the platform ever
issued would have been rejected. The platform could not have completed a call
against a real carrier.

**Why nothing caught it.** `FakeEslServer` answered `+OK accepted` to any command
it did not recognise. The double was faithful about framing and unfaithful about
dispatch, and that is precisely the gap this phase existed to close. The double
now models the rejection:

```text
unknown command -> -ERR command not found
api <known>     -> +OK Message sent
api <other>     -> api/response (empty Reply-Text)
```

**Second defect, same family.** An `api` reply is
`Content-Type: api/response` with an **empty** `Reply-Text` and the result in
the body. The shared command path required `+OK`, so it rejected *successful*
`api` calls. Found live; fixed with a shared `executeApi` path where only
`-ERR` is a failure.

## 16. Known Limitations

| Item | Status |
|---|---|
| Java-originated call through the gateway path | **BLOCKED** — needs a routable local gateway |
| Two-way audio across the network | **NOT PROVEN** (Phase C §10.1) — still needs a bridged call whose bridge includes the endpoint leg, measured at the far end |
| DTMF | **NOT YET TESTED** — needs bridging first |
| IVR | **NOT YET TESTED** — depends on DTMF |
| Max duration end-to-end | **PARTIAL** — command path verified, not re-run end-to-end |
| Carrier / PSTN behaviour | **NOT TESTED** — no carrier exists |

## 17. Security

| Check | Result |
|---|---|
| Credentials committed | none |
| ESL password in source or test | none — read from git-ignored `infra/.env` or an env var |
| SIP passwords committed | none |
| Secrets in documentation | none |
| Runtime log dirs (which contain rendered credentials) still git-ignored | **yes**, verified |
| New ESL or SIP exposure introduced | none — no infrastructure changed |
| `infra/docker-compose.yml` | untouched, `git diff` clean |

The live test reads the secret from `infra/.env` at runtime and never prints,
asserts, or logs it.

## 18. Files Changed

**Main**

- `backend/src/main/java/com/shivang/obd/telephony/EslEvent.java`
- `backend/src/main/java/com/shivang/obd/telephony/EslClient.java`
- `backend/src/main/java/com/shivang/obd/telephony/EslEventService.java`

**Test**

- `backend/src/test/java/com/shivang/obd/telephony/EslEventRuntimeContractTest.java` *(new)*
- `backend/src/test/java/com/shivang/obd/telephony/LiveFreeSwitchRuntimeContractTest.java` *(new)*
- `backend/src/test/java/com/shivang/obd/telephony/EslProtocolTest.java`
- `backend/src/test/java/com/shivang/obd/telephony/FakeEslServer.java`

**Harness (outside the product)**

- `tools/freeswitch-harness/d_j2_playback_probe.py`
- `tools/freeswitch-harness/d_j3_probe.py`
- `tools/freeswitch-harness/d_j3_transfer_probe.py`
- `tools/freeswitch-harness/d_j3_addressability.py`

**Repository hygiene**

- `.gitignore` — added `__pycache__/`, `*.py[cod]`, `.venv/`, `venv/`

**Not changed:** `infra/docker-compose.yml`, any FreeSWITCH configuration, any
Java outside `telephony`, and any file owned by the concurrent
campaign/voice workstream.

## 18a. Pre-Phase-E repository integrity check

Run because a concurrent session was observed relocating a file out of the
working tree mid-phase (see §7 and the note in §12). Its purpose is to leave
Phase E a tree whose ownership is unambiguous.

| Check | Result |
|---|---|
| `git status` | 30 changed + untracked paths, all accounted for below |
| `git diff --name-status` | 13 modified, all accounted for |
| `git diff --cached` | **empty** — nothing staged, no half-finished commit |
| `git diff --diff-filter=U` | **empty** — no conflicted paths |
| `git stash list` | **empty** — no work parked in a stash |
| `*.parked` anywhere in the tree | **none** |
| files matching `parked` / `vb7c1` / `.orig` / `.rej` / `.bak` | **none** |
| `EslProtocolTest.java` present and whole | **yes** — 556 lines, +167/−4, braces balanced, all 6 Phase D tests intact |
| `git add -A --dry-run` | 84 files, reviewed individually (below) |
| live credential values in anything that would be staged | **0** |
| `infra/.env` staged? | **no** — `infra/.gitignore:1:.env` |
| `*.fsxml` staged? | **no** — `.gitignore:30:*.fsxml` |
| `infra/freeswitch/logs/` staged? | **no** — `.gitignore:28` |
| `infra/freeswitch-endpoint/logs-*/` staged? | **no** — `.gitignore:29` |
| telephony suite after the hygiene fix | **359 tests, 0 failures, 0 errors** |

### The one real defect it found

`__pycache__/*.pyc` would have been committed. Running the harness had produced
six compiled-bytecode files under `tools/freeswitch-harness/`, and no ignore
rule covered them. They are interpreter-version-specific build artifacts that
regenerate on first import and make diffs unreadable. Fixed by the `.gitignore`
rule above; the staged set dropped from 90 files to 84, and all 22 harness
`.py` files remain tracked.

This is worth noting for its own sake: the rule was missing because the harness
is run **in place** rather than installed, so the usual "never commit a venv"
assumption did not apply and nothing prompted it.

### Ownership boundary

| Owner | Paths |
|---|---|
| Phase D (telephony) | `com.shivang.obd.telephony` — 3 main + 4 test files |
| Phase D (documentation) | `docs/LIVE-FREESWITCH-PHASE-D.md`, `docs/freeswitch/**` |
| Phase D (harness) | `tools/freeswitch-harness/*.py` |
| **Concurrent workstream — not touched** | `CampaignReadinessService.java`, `CampaignService.java`, `CampaignType.java`, `CampaignGovernanceHardeningPostgresIntegrationTest.java`, `CampaignReadinessServiceTest.java`, `CampaignTypeCapabilityValidationTest.java`, `docs/campaign-readiness.md` |

The boundary is enforced in both directions: no Phase D file outside
`telephony` was edited, and no concurrent file was edited, reverted, stashed or
formatted. `EslEventService`'s only campaign references are three pre-existing
imports that appear on no `+` or `-` line of its diff.


## 19. Architecture Impact

**NO.** The architecture is unchanged. No new abstraction, no new event bus, no
new state machine, no schema change, no new dependency.

Notably, J3 resolved to **no change at all**: the existing model already
represented the observed behaviour correctly, and a "current channel" concept
would have been an abstraction the evidence did not require. That is recorded
because the temptation to add one was real and the evidence did not support it.

## 20. Next Phase

Only work the evidence justifies:

1. **Decide on the routable local gateway** (§12). One gateway on the `external`
   profile pointing at the in-network test endpoint unblocks Java-originated
   calls, CONNECT_BY_AGENT, and end-to-end PLAYFILE.
2. **Run the Spring application** against the live switch for the first time.
   Every layer beneath the ESL client is now verified, but the application has
   still never connected to a real FreeSWITCH.
3. **Complete the bridge** so the far end's media counters can be read, then
   close the two-way audio gap (Phase C §10.1).
4. **DTMF and IVR**, once bridging works.
5. **Re-verify the four failing `campaign`/`audio` classes** once the concurrent
   workstream settles — not this phase's code, but it must not be lost.

## 21. Related Documents

- [`docs/LIVE-FREESWITCH-PHASE-C.md`](LIVE-FREESWITCH-PHASE-C.md) — the phase whose findings this one acted on
- [`docs/freeswitch/05-ESL.md`](freeswitch/05-ESL.md) — the confirmed event and command contract
- [`docs/freeswitch/13-TROUBLESHOOTING.md`](freeswitch/13-TROUBLESHOOTING.md) — entries added by this phase

---

> **Continued in Phase E.** [`LIVE-FREESWITCH-PHASE-E.md`](LIVE-FREESWITCH-PHASE-E.md)
> acted on this phase's two carried-forward items. J4 was independently
> re-confirmed (`api uuid_broadcast` and `api uuid_kill` accepted, bare forms
> rejected). The Java-originated call this report could not make possible was
> blocked in Phase E by a **concurrent workstream's untracked, non-compiling
> file**, so every Java objective there is BLOCKED rather than done. Phase E also
> found and fixed the platform dialplan defect that had been producing a
> false-positive `CHANNEL_ANSWER` in earlier phases, and established that
> **TWO-WAY AUDIO REMAINS NOT PROVEN** with one direction measured.
---

> **Follow-up, Phase E.2.** This phase corrected the correlation header in code
> but left one comment in `FreeSwitchOutboundDialer` asserting that events
> "correlate by Call-UUID" — the header this phase measured to be absent from
> every event type the platform consumes. The code was already correct; the
> comment named the wrong header and would have reintroduced the misconception.
> Corrected in Phase E.2, with the measurement recorded inline. No behavioural
> change. The pinned-UUID design that comment describes is unchanged and remains
> correct: `origination_uuid` is the `CallAttempt.id`.