# LIVE FREESWITCH — PHASE E.1: `uuid_broadcast` CONTRACT & SPRING INTEGRATION GATE

**Status: PASS WITH LIMITATIONS**

The phase's central question has a definite answer:

> **The exact command the Java client generates — `api uuid_broadcast <uuid> <file> aleg`
> — is valid on this FreeSWITCH. It is accepted and playback begins.**

The `-ERR invalid uuid` recorded in Phase E was **not** a FreeSWITCH contract
problem and **not** a Java defect. It was a symptom of a one-word defect in the
Phase C test endpoint's dialplan, which had been silently truncating every call
for the life of the project. That is category **D** in the brief's
classification, and the evidence for it is below.

---

## 1. Executive Summary

| | |
|---|---|
| `uuid_broadcast` contradiction | **EXPLAINED AND RESOLVED** |
| Java command generation correct? | **YES** — verified against the live switch |
| Java files changed | **NONE** |
| Root cause | `wait` is not a registered application in this build |
| Spring compile gate | **PASS** — the concurrent blocker cleared on its own |
| Spring startup | **PASS** — profile `dev`, Tomcat 8081, 40.4 s |
| ESL authentication (in-app) | **PASS** |
| Event subscription (in-app) | **PASS** — 11 events, from the running application |
| Harmless command through the app path | **NOT YET TESTED** |
| First Java-originated call | **NOT YET TESTED** — no campaign seeded |
| Two-way audio | **CONFIRMED — LOCAL** (was NOT PROVEN) |
| Architecture changed | **NO** |

## 2. Starting State

Phase E's findings were treated as established and not re-argued, except the
`uuid_broadcast` contradiction, which the brief required be reproduced rather
than reinterpreted.

## 3. Working Tree Integrity

Verified before any change:

```text
git status --short                 39 paths, all attributed
git diff --stat                    17 files changed
git diff --cached --stat           EMPTY
git diff --diff-filter=U          none
git stash list                     empty
parked / vb7c1 / orig / rej / bak  none
infra/docker-compose.yml           CLEAN
Phase D Java fixes                 intact (J4 api-prefix guard present)
```

The Phase D ownership boundary held. The concurrent workstream had grown to 18
campaign paths. **No concurrent file was modified, reverted, stashed, renamed or
formatted.**

## 4. The Contradiction, Reproduced

Placed one call through the production Java dial string
(`sofia/gateway/local-endpoint-1002/1002`), then ran a controlled matrix on the
same answered channel.

```text
channel state : ACTIVE / CS_EXECUTE / Answer-State=answered
sip_call_id   : 0589a2c7-35eb-1240-92b2-726b734c0ee8
media         : local port 30026   remote 172.25.0.4:18050   codec PCMU
```

| Test | Command | Reply |
|---|---|---|
| A | `api uuid_dump <uuid>` | `CHANNEL_DATA` (**valid**) |
| B | `api uuid_broadcast <uuid> <file> aleg` — **exactly what Java sends** | `-ERR invalid uuid` |
| C | `api uuid_broadcast <uuid> <file>` | `-ERR invalid uuid` |
| D | `api uuid_broadcast <uuid> <file> both` | `-ERR invalid uuid` |
| E | `api uuid_broadcast <uuid> <file> <own uuid>` | `-ERR invalid uuid` |
| F | `api play <uuid> <file>` | `-ERR play Command not found!` |
| G | `api uuid_kill <uuid> NORMAL_CLEARING` — **discriminator** | `-ERR No such channel!` |

Repeated on a second, independently placed channel: **identical in every case.**

## 5. Establishing the Ground Truth

Two commands disagreed about the same UUID, so the UUID itself was checked
rather than believed.

```text
show channels
  0c1fa37d-f939-444f-a238-1422eb063c69,outbound,...,sofia/internal/1002,
  CS_EXECUTE,1002,...,park,...,PCMU,...,ACTIVE
  1 total.

api uuid_dump 0c1fa37d-f939-444f-a238-1422eb063c69
  Unique-ID returned  : 0c1fa37d-f939-444f-a238-1422eb063c69
  MATCHES             : True
```

**The UUID was valid, and the switch held exactly one channel — mine.** So
"invalid uuid" was not a statement about the UUID.

Note `Core-UUID` differs (`51088ffc-…`); that is normal and is not the identity
used by these commands.

## 6. The Actual Cause — the channel was already dead

`uuid_kill` reporting "No such channel" while `uuid_dump` succeeded pointed at
lifetime rather than identity. Polling `show channels` once a second settled it:

```text
t+1s  channels=0  answered=True  hungup=True
>>> the channel was dead at t+1s

CHANNEL_CREATE  ringing
CHANNEL_ANSWER  answered
CHANNEL_EXECUTE answered
CHANNEL_HANGUP  DESTINATION_OUT_OF_ORDER
```

The platform log gave the window precisely:

```text
14:25:48.605  [INFO]  sofia.c:8693  Channel [sofia/internal/1002] has been answered
14:25:49.205  [INFO]  sofia.c:1065  Hangup [CS_EXECUTE] [DESTINATION_OUT_OF_ORDER]
```

**The channel lived 600 ms.** Any command issued a second later addresses a
channel that no longer exists. `uuid_dump` succeeded only because it was issued
in the same instant and raced the teardown.

### The SIP exchange, and where the BYE came from

```text
send 1276 bytes to udp/[172.25.0.4]:5060
  INVITE sip:1002@endpoint-b:5060 SIP/2.0
  c=IN IP4 172.25.0.3
  m=audio 30042 RTP/AVP 0 8 9 102 101 103

recv 1053 bytes
  SIP/2.0 200 OK
  c=IN IP4 172.25.0.4
  m=audio 27914 RTP/AVP 0 101

send 422 bytes
  ACK sip:1002@172.25.0.4:5060;transport=udp SIP/2.0

recv 606 bytes
  BYE sip:gw+local-endpoint-1002@172.25.0.3:5060;transport=udp;gw=local-endpoint-1002
```

The call was negotiated correctly — proper 200 OK, valid SDP both ways, ACK sent.
**The endpoint then sent the BYE**, about 600 ms later. The platform was not
hanging up; the far end was.

### Why the far end hung up

The endpoint's own log is unambiguous:

```text
EXECUTE [depth=0] sofia/obd-endpoint/FreeSWITCH@endpoint-b:5060 answer()
[NOTICE] mod_dptools.c:1406 Channel has been answered
[ERR]   switch_core_session.c:2771 Invalid Application wait
[NOTICE] switch_core_session.c:2772 Hangup [CS_EXECUTE] [DESTINATION_OUT_OF_ORDER]
```

**`Invalid Application wait`.**

## 7. Verifying the Diagnosis

### The application registry

```text
registered applications: 179

  wait                       no
  sleep                      YES  (mod_dptools)
  answer                     YES  (mod_dptools)
  send_dtmf                  YES  (mod_dptools)
  playback                   YES  (mod_dptools)
  hangup                     YES  (mod_dptools)
```

`wait` is **not** registered in this FreeSWITCH 1.11.1 build. The registry
contains `wait_for_answer` and `wait_for_silence`, but not `wait`.

### Bisection — the dialplan was reduced in stages at runtime

| Stage | Endpoint dialplan | Result |
|---|---|---|
| 1 | as committed: `answer → send_dtmf → playback → wait → hangup` | died at 1.0 s, `DESTINATION_OUT_OF_ORDER` |
| 2 | `send_dtmf` removed | died at 1.0 s — **`send_dtmf` is not the cause** |
| 3 | minimal: `answer → wait 20000 → hangup` | died at 1.0 s, and produced **no** `CHANNEL_ANSWER` |

Stage 3 mattered: it ruled out the endpoint's actions entirely and pointed at the
shared `wait`. Both hypotheses I had formed (premature `send_dtmf`, and the
`tone_stream` playback) were tested and **rejected**.

The runtime edits were reverted; the committed file was untouched until the cause
was known.

## 8. The Fix

One word, in the Phase C test endpoint's dialplan — which is Phase-owned test
infrastructure, not product code and not concurrent work:

```diff
-      <action application="wait" data="@@ANSWER_HOLD_MS@@"/>
+      <action application="sleep" data="@@ANSWER_HOLD_MS@@"/>
```

`answer`, `send_dtmf`, `playback` and `hangup` were all verified present, so
`wait` was the only invalid application.

### Result after the fix

```text
STEP 1  baseline, dialplan as committed
  life= 20.0s  hangup=(still up)
  events: CHANNEL_CREATE -> CHANNEL_ANSWER
  api uuid_broadcast <uuid> <file> aleg -> '+OK Message sent'
  playback events on the channel        -> ['PLAYBACK_START']
```

## 9. Is the Java Command Contract Valid? — YES

The exact string the Java client generates, from
`EslClient.playFile` / `executeUuidCommand`:

```java
executeUuidCommand(String.format("uuid_broadcast %s %s aleg", channelUuid, audioPath), "playFile");
// and executeUuidCommand() sends: "api " + command
```

Sent to the live switch on a channel that is genuinely long-lived:

```text
api uuid_broadcast <uuid> /media/obd/phase-e-rtp-probe.wav aleg
  -> +OK Message sent
  -> PLAYBACK_START, PLAYBACK_STOP on the channel
```

**The Java command generation is correct. No Java change was made.** The Phase E
observation is reclassified as **D — the original probe was invalid**, for a
specific and demonstrated reason: it was measuring a channel that had already
been destroyed 600 ms after answer.

## 10. Peer-Witnessed Playback and Two-Way Audio

With the channel surviving, the full correlation chain was exercised on one call:

```text
dial string   sofia/gateway/local-endpoint-1002/1002
pinned        8dc7d410-4ab7-4f50-bd3e-2e4bf74e042b
media         local 172.25.0.3:30036   remote 172.25.0.4:32214   PCMU both ways

uuid_broadcast reply        +OK Message sent

platform-side events : CHANNEL_CREATE -> CHANNEL_ANSWER -> CHANNEL_EXECUTE
                        -> CHANNEL_EXECUTE -> PLAYBACK_START -> PLAYBACK_STOP
                        -> CHANNEL_HANGUP -> CHANNEL_EXECUTE_COMPLETE
ENDPOINT-side events : CHANNEL_CREATE -> ... -> CHANNEL_ANSWER -> ...
                        -> PLAYBACK_START -> PLAYBACK_STOP -> CHANNEL_HANGUP

hangup cause         NORMAL_CLEARING        (was DESTINATION_OUT_OF_ORDER)
```

RTP, as kernel UDP counters on both peers:

| Direction | Packets |
|---|---|
| endpoint → platform | **+102** (endpoint sent 101) |
| platform → endpoint | **+814** (platform sent 817) |

**TWO-WAY AUDIO: CONFIRMED — LOCAL.**

This is a promotion from Phase E's **NOT PROVEN**, and the reason it was
previously unprovable is now known: the platform-to-endpoint leg was being
measured on a channel that was already gone.

Both peers independently report the playback lifecycle, so this is peer-witnessed
rather than inferred from a local `+OK`.

## 11. Why This Was Missed for Two Phases

The endpoint had **never actually held a call open**. It answered, and died
600 ms later. Every earlier observation was true of that window and nothing more:

* Phase C recorded the endpoint answering and sending a tone — true, within 600 ms.
* Phase C could not observe `CHANNEL_DTMF` — because the channel ended before
  DTMF could be delivered and collected.
* Phase C's "no RTP counters" investigation never got a long-lived channel.
* Phase D's DTMF and RTP work was blocked on exactly this.
* Phase E's `uuid_broadcast` errors were the symptom, misread as a command
  contract problem.

The teaching point is not about `wait`. It is:

> **An unavailable application in a dialplan is not reported to the caller. The
> core aborts the channel with a misleading cause** — here `DESTINATION_OUT_OF_ORDER`,
> which reads as a SIP ordering problem. A test fixture that is subtly broken
> produces confident, wrong measurements, and the longer it survives the more
> authoritative it looks.

## 12. Spring Integration Gate

### Ownership transfer

The gate was reached while the concurrent workstream was still active, and
**Phase E.1 did not touch their file.** The state recorded at the gate:

```text
HEAD:  33e303e  VB-7C.1: campaign configuration correctness hardening
WebhookConfig.java status: ?? (still untracked; they had edited it at 19:55)
conflicts: none
```

They resolved it themselves. Verified before proceeding.

### Gate 1 — compile: **PASS**

```text
mvnw -q -o compile    -> exit 0
```

### Gate 2 — Spring startup: **PASS**

```text
The following 1 profile is active: "dev"
Tomcat started on port 8081 (http) with context path '/'
Started ObdApplication in 40.425 seconds
```

Configuration resolved from the environment; no secret value appears in any log
line. The password was supplied from the git-ignored `infra/.env` and is never
logged.

### Gate 3 — ESL authentication: **PASS**

```text
[event-processor] EslEventScheduler : Starting FreeSWITCH ESL event processing loop
```

The loop only starts on an authenticated connection, so the application
authenticated to the live switch as part of its own startup.

### Gate 4 — event subscription: **PASS**

```text
[event-processor] EslClient : Subscribed to FreeSWITCH events:
  CHANNEL_CREATE, CHANNEL_PROGRESS, CHANNEL_PROGRESS_MEDIA, CHANNEL_ANSWER,
  CHANNEL_DTMF, CHANNEL_HANGUP, PLAYBACK_START, PLAYBACK_STOP, PLAYBACK_ERROR,
  CHANNEL_BRIDGE, CHANNEL_EXECUTE_COMPLETE
```

Eleven events, including the `CHANNEL_EXECUTE_COMPLETE` Phase D added. The
subscription is issued by the real application over its own connection.

### Gates 5 and 6 — **NOT YET TESTED**

A harmless command through the application's own ESL path, and the first
application-originated call, both require seeded tenant/campaign/contact/audio
and a gateway row. That was out of budget, and the environment was contended:
a concurrent `mvn clean` twice deleted `target/` mid-run, once destroying a
running instance and its log without an error.

**A real call was placed through the gateway while the application was
subscribed**, producing `CHANNEL_CREATE → CHANNEL_ANSWER → PLAYBACK_START →
PLAYBACK_STOP` on the switch. The application produced:

```text
"without a channel identity" warnings : 0
EslEventService log lines            : 0
```

**This is honestly ambiguous and is not claimed as proof of J1.** Zero warnings
is consistent with J1 working, and equally consistent with the application
receiving nothing. With no `CallAttempt` in the database, `processEvent` finds no
match and returns `false` quietly either way. Distinguishing those requires
seeded data or DEBUG-level event logging, and is left for the next phase rather
than asserted here.

The application placed no call of its own: no campaign was seeded.

## 13. Failure Scenario Matrix

Not exercised. All six conditions require calls placed by the application.

The one hangup cause observed organically is now `NORMAL_CLEARING` rather than
the `DESTINATION_OUT_OF_ORDER` that the broken fixture produced — which is
itself a useful data point: the fixture had been masking a clean release.

## 14. Live Evidence Matrix

| Scenario | Result | Evidence |
|---|---|---|
| `uuid_dump` vs `uuid_broadcast` disagreement | **EXPLAINED** | channel lived 600 ms |
| UUID validity established independently | **PASS** | `show channels`, dump `Unique-ID` match |
| Matrix repeated on a second channel | **PASS** | identical results |
| `wait` registered? | **NO** | application registry, 179 apps |
| `send_dtmf` cause hypothesis | **REJECTED** | stage 2 still died |
| Minimal dialplan still dies | **CONFIRMED** | stage 3, no CHANNEL_ANSWER |
| `api uuid_broadcast … aleg` accepted | **PASS** | `+OK Message sent` |
| `PLAYBACK_START` / `PLAYBACK_STOP` | **PASS** | both peers' event streams |
| Hangup cause | **PASS** | `NORMAL_CLEARING` |
| RTP endpoint → platform | **PASS** | +102 packets |
| RTP platform → endpoint | **PASS** | +814 packets |
| Two-way audio | **CONFIRMED — LOCAL** | both directions, both peers |
| DTMF as an application event | **NOT YET TESTED** | still no `CHANNEL_DTMF` |
| Spring compile | **PASS** | exit 0 |
| Spring startup | **PASS** | 40.4 s, port 8081 |
| ESL authentication in-app | **PASS** | event loop started |
| Subscription in-app | **PASS** | 11 events |
| App-path harmless command | **NOT YET TESTED** | no seeded data |
| First Java-originated call | **NOT YET TESTED** | no seeded data |
| IVR / CONNECT_BY_AGENT / max duration | **NOT YET TESTED** | depend on a Java call |
| Carrier / PSTN | **NOT TESTED** | — |

## 15. Automated Tests

Not run, and not required to settle this phase's question.

`mvnw compile` passes, so the suite is now runnable; it was not executed because
the phase's deliverable was the live command contract, and the concurrent
workstream was actively building and cleaning the tree. **No test result is
claimed.** Phase D's suites were not modified in this phase.

## 16. Security

| Check | Result |
|---|---|
| live credential values in any file added or changed | **0** |
| ESL/SIP passwords in source, harness or docs | **none** — read from git-ignored `infra/.env` at runtime, never logged |
| `.env`, `*.fsxml`, runtime logs committed | **none** |
| generated `.pyc` staged | **none** — ignore rule verified active |
| `hs_err_pid*.log` / `replay_pid*.log` in `backend/` | **git-ignored** (`.gitignore:13`), pre-existing, and contain **no** live secret values |
| new host ports published | **none** |
| `infra/docker-compose.yml` | **untouched**, diff clean |
| Concurrent workstream files modified | **NONE** |

## 17. Files Changed

**Test-only infrastructure** (Phase-owned)

- `infra/freeswitch-endpoint/conf/dialplan/obd-endpoint.xml.in` — `wait` → `sleep`
- `tools/freeswitch-harness/e1_broadcast_matrix.py` *(new)*
- `tools/freeswitch-harness/e1_teardown_isolate.py` *(new)*
- `tools/freeswitch-harness/e1_dialplan_bisect.py` *(new)*

**Documentation**

- `docs/LIVE-FREESWITCH-PHASE-E1.md` *(new)*
- `docs/freeswitch/13-TROUBLESHOOTING.md` — entries 28–29
- `docs/freeswitch/11-DEBUGGING.md` — index rows
- `docs/freeswitch/README.md` — index rows
- `docs/LIVE-FREESWITCH-PHASE-E.md` — correction pointer

**Not changed:** `infra/docker-compose.yml`; any Java; any FreeSWITCH profile,
gateway or platform dialplan; any concurrent file.

## 18. Architecture Impact

**NO.** One application name corrected in a development test fixture. No product
code, no abstraction, no schema, no configuration surface.

## 19. Corrections to Earlier Documentation

Phase E §9 recorded the `uuid_broadcast` failure as an **open defect** against
`EslClient.playFile`. That statement was wrong in its implication, and is
corrected here without being deleted:

> **Phase E wrote:** "`uuid_broadcast` returns `-ERR invalid uuid` … recorded as
> an open defect because the Java client always sends the `aleg` form."
>
> **Phase E.1 measured:** the Java command is correct and is accepted. The
> endpoint's dialplan used an application that does not exist in this build, so
> every call was destroyed ~600 ms after answer. There is no defect in
> `EslClient.playFile`.

Phase E §8 also recorded two-way audio as **NOT PROVEN**. That is superseded by
**CONFIRMED — LOCAL** (§10), for the same reason: the measurement was taken on a
dead channel.

## 20. Known Limitations

| Item | Status |
|---|---|
| App-path harmless command | **NOT YET TESTED** — no seeded data |
| First Java-originated call | **NOT YET TESTED** — no seeded data |
| J1 correlation in the running app | **AMBIGUOUS** — 0 warnings, but also 0 event-log lines |
| DTMF as an application event | **NOT YET TESTED** — still no `CHANNEL_DTMF` |
| IVR, CONNECT_BY_AGENT, max duration, failure matrix | **NOT YET TESTED** |
| Automated test suite | **NOT RUN** — runnable now, not executed |
| Carrier / PSTN | **NOT TESTED** |

## 21. Next Phase

Only evidence-backed work:

1. **Seed the minimum application data** — tenant, campaign, contacts, audio
   asset with a stored reference, and a `sip_gateways` row naming
   `local-endpoint-1002` — then place the first Java-originated call. Gates 5
   and 6 are the only thing standing between this and a genuine end-to-end path.
2. **Raise the ESL event loop to DEBUG** for one run, so `EslEventService` is
   observed consuming a real event. That converts the ambiguous §12 result into
   a proven one.
3. **Then** PLAYFILE valid and missing media through the application, DTMF event
   delivery, IVR, CONNECT_BY_AGENT, max duration, failure matrix.
4. **Re-run the full test suite** now that the tree compiles.

## 22. Related Documents

- [`LIVE-FREESWITCH-PHASE-E.md`](LIVE-FREESWITCH-PHASE-E.md) — the phase this resolves
- [`LIVE-FREESWITCH-PHASE-D.md`](LIVE-FREESWITCH-PHASE-D.md) — the J1–J4 contract, unchanged
- [`freeswitch/decisions/ADR-007-local-gateway-routing.md`](freeswitch/decisions/ADR-007-local-gateway-routing.md) — routing
- [`freeswitch/13-TROUBLESHOOTING.md`](freeswitch/13-TROUBLESHOOTING.md) — entries 28–29
