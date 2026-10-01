# LIVE FREESWITCH — PHASE E: LOCAL GATEWAY & SPRING APPLICATION INTEGRATION

**Status: PASS WITH LIMITATIONS — with two objectives BLOCKED by concurrent work**

Phase E set out to make the real Spring application place a real call through a
routable local gateway. It got the gateway working, and it proved that with the
peer as witness. It could not start the application, for a reason outside this
phase's ownership, and it could not close the platform→endpoint audio leg.

Two things in this phase matter beyond its own deliverables:

1. **A false positive was caught and is now documented.** The platform reported
   a clean `CHANNEL_ANSWER` for a call that had never left the machine. The
   switch was answering its own `voicemail` dialstring. Every local signal said
   success. Only the peer's own state showed the truth.
2. **A concurrent workstream's untracked file does not compile**, which blocks
   the entire Maven build and therefore every Java-side objective. It is not
   this phase's to fix, and was not fixed.

---

## 1. Executive Summary

| | |
|---|---|
| **E1** routable local gateway | **CONFIRMED — LOCAL** (peer-verified) |
| **E2** Spring application startup | **BLOCKED** — build broken by concurrent untracked file |
| **E3** Java-originated call via Spring | **BLOCKED** — same cause |
| **E4** RTP / two-way audio | **PARTIAL** — one leg confirmed, the other not |
| **E5** PLAYFILE (platform→customer) | **PARTIAL** — unresolved, see §9 |
| **E6** DTMF | **PARTIAL** — RTP-layer tones received, no `CHANNEL_DTMF` event |
| **E7** IVR | **NOT TESTED** — depends on DTMF |
| **E8** CONNECT_BY_AGENT | **NOT TESTED** — depends on E3 |
| Max duration | **NOT TESTED** — depends on E3 |
| Failure matrix | **NOT TESTED** — depends on E3 |
| Architecture changed | **NO** |
| Carrier / PSTN | **NOT TESTED** |

## 2. Starting State

Phase D's contract was treated as established and was not re-litigated:

| | |
|---|---|
| J1 | `Channel-Call-UUID` / `Unique-ID` is the channel identity — **RESOLVED** |
| J2 | playback outcomes follow the measured lifecycle — **RESOLVED** |
| J3 | no current-channel abstraction required — **RESOLVED BY EVIDENCE** |
| J4 | channel commands are `api`-prefixed — **RESOLVED** |

J4 was independently re-confirmed this phase: `api uuid_broadcast` and
`api uuid_kill` are accepted; the bare forms are not.

## 3. Working Tree Integrity

Re-verified at the start of the phase:

```text
git status --short                     30 paths, all attributed
git diff --name-status                 13 modified, all attributed
git diff --cached --name-status         EMPTY
git diff --diff-filter=U               EMPTY
git stash list                         EMPTY
infra/docker-compose.yml               CLEAN
EslProtocolTest.java                   present, 556 lines, 21 tests
parked / vb7c1 / orig / rej / bak      none
```

The Phase D integrity check established a **reviewed commit-candidate set of 84
files** (what `git add -A --dry-run` would stage, individually reviewed, secret
scan clean). Those are the candidate files, not 84 changed files.

Ownership boundary held: no concurrent campaign/voice file was modified,
reverted, stashed, formatted or renamed during Phase E.

## 4. Gateway Design

**ADR-007.** The design was found by measurement, and the first three attempts
failed. The record matters more than the result, because the obvious
configuration does not work and the reason is not documented anywhere.

### What was tried

| # | Configuration | Result |
|---|---|---|
| 1 | `proxy endpoint-b:5060`, declared on `external` | `NO_ROUTE_DESTINATION` |
| 2 | same, `sofia-profile=internal` | channel renamed `sofia/internal/1002`, still `NO_ROUTE_DESTINATION` |
| 3 | same + `realm=$${domain}` | still `NO_ROUTE_DESTINATION` |
| 4 | `proxy=$${domain}:5060` (the switch's own address) | `CHANNEL_ANSWER` — **but a false positive**, see §5 |

### The three findings

**Finding 1 — mod_sofia routes outbound SIP by domain.** It resolves a
destination against the registration database of the profile it originates from.
It will not send to an address merely because that address is reachable. Every
direct-address form fails, on both profiles, by IP and by DNS name:

```text
sofia/external/1002@172.25.0.4:5060    -> NO_ROUTE_DESTINATION
sofia/internal/1002@172.25.0.4:5060    -> NO_ROUTE_DESTINATION
sofia/external/1002@endpoint-b:5060     -> NO_ROUTE_DESTINATION
sofia/internal/1002@<switch domain>     -> CHANNEL_ANSWER
```

The transport was provably fine throughout: DNS resolved, the endpoint answered
ping with 0% loss, and `acl 172.25.0.3 obd-dev-acl` returned `true`. A working
transport is not a route.

**Finding 2 — a gateway's origination profile is the profile that DECLARES it.**
The gateway's own `sofia-profile` parameter is a hint the declaring profile
overrides. With the gateway declared in `external.xml` and
`sofia-profile=internal`, origination still produced `sofia/external/...` and
the parameter had no effect whatsoever. The declaration moved to `internal.xml`.

**Finding 3 — the endpoint's dialplan matched the wrong variable.** Its
condition used `${sip_destination_user}`, which for a gateway-originated call is
the *gateway's* identity, not the number dialled. Observed on the endpoint:

```text
New Channel sofia/obd-endpoint/FreeSWITCH@endpoint-b:5060
Processing FreeSWITCH <+15551230000>->1002 in context obd-endpoint
No Route, Aborting
```

`1002` is the destination number; `FreeSWITCH` is the Request-URI user. `^\d+$`
could never match a gateway-originated call. Corrected to
`${destination_number}`.

### Final configuration

`proxy=endpoint-b:5060` (a DNS name, not an address), `register=false`,
`sofia-profile=internal`, `realm=$${domain}`, declared in `internal.xml`.

A DNS name is used because a container address is not stable: it moved from
`172.25.0.4` to `172.25.0.3` during this phase across a Docker restart, and
registrations followed it. A hard-coded address fails as `NO_ROUTE_DESTINATION`,
which reads as a network fault. The shared alias `obd-fs-endpoint` is
deliberately **not** used: both endpoints answer to it, so which one wins is a
property of the resolver rather than of the configuration.

`fs-gateway` is untouched and remains on `external`, unroutable by design
because no carrier exists.

## 5. The false positive — the most important finding of this phase

With the gateway proxy pointed at the switch's own address, this appeared to
work:

```text
dial string : sofia/gateway/local-endpoint-1002/1002
platform    : CHANNEL_ANSWER, Answer-State=answered
VERDICT at the time: "the Java dial string shape now works"
```

It did not work. The proxy was the switch's own SIP address, so the INVITE looped
back to the platform, where the **stock `public` context answered it with
`voicemail`**:

```xml
<extension name="local_extension">
  <condition field="destination_number" expression="^(10[01][0-9])$">
    <action application="voicemail" data="default"/>
```

The tells were only visible from outside:

```text
rtp_remote_sdp_str     = _undef_     no SDP was ever exchanged
endpoint live channels = 0           the far end had no call
endpoint UDP counters  = unchanged   no RTP reached it
```

and in the log, two channels named `loopback/voicemail-a` and
`loopback/voicemail-b`, which are easy to miss because the originated leg's
event looks entirely normal.

**The lesson, which is why the dialplan is now explicit rather than inherited:**

> A channel event reports what the *local* switch believes. Only the peer's own
> state can confirm that a call happened somewhere. A switch that answers its
> own dialstring will report success for anything it has a pattern for.

The platform dialplan is now `infra/freeswitch/dialplan/public.xml`, mounted
read-only. It bridges a **registered** extension to its contact and returns
`404` for an unregistered one. Voicemail answering was the defect; it is gone.

`e1_decisive.py` now asserts on peer evidence and is built so it cannot report
success on local signals alone.

## 6. Gateway Validation — with the endpoint as witness

```text
dial string : sofia/gateway/local-endpoint-1002/1002
pinned uuid : 112cfc35-9dd2-4cc4-9b56-abad-a2ac3e07e94b
gateway     : Profile internal, Realm 172.25.0.3, Proxy sip:endpoint-b:5060
```

**The endpoint's own event stream** (a separate ESL connection, because one
socket cannot carry both a command and an event stream):

```text
CHANNEL_CREATE   sofia/obd-endpoint/FreeSWITCH%40endpoint-b%3A5060  ringing
CHANNEL_ANSWER   ...                                                   answered
PLAYBACK_START   ...                                                   answered
PLAYBACK_STOP    ...                                                   answered
CHANNEL_HANGUP   ...                                     DESTINATION_OUT_OF_ORDER
```

Media state read back from the live channel:

```text
local_media_ip  = 172.25.0.3     local_media_port  = 30078
remote_media_ip = 172.25.0.4     remote_media_port = 31378
read_codec = PCMU                write_codec = PCMU
```

`remote_media_ip` is the endpoint's address and `remote_media_port` is in the
endpoint's own range (its stock 16384-32768), which is what a real negotiated
call looks like — the earlier self-answer showed the platform's own address in
both fields.

**E1 — CONFIRMED — LOCAL.**

## 7. Spring Application — BLOCKED

The application was launched against the live switch with
`--telephony.freeswitch.enabled=true` and:

```text
FREESWITCH_HOST=127.0.0.1  FREESWITCH_PORT=8021
FREESWITCH_GATEWAY=local-endpoint-1002  FREESWITCH_PROFILE=internal
password read from the git-ignored infra/.env, never printed
```

It did not start, and the reason is not telephony:

```text
[ERROR] COMPILATION ERROR :
[ERROR] .../campaign/config/WebhookConfig.java:[152,14] no suitable method found
        for put(java.lang.String,tools.jackson.databind.node.ArrayNode)
[INFO] 1 error
[INFO] Failed to execute goal ...maven-compiler-plugin:3.15.0:compile
```

**Attribution.** `WebhookConfig.java` is **untracked** (`??`) — new, uncommitted
work from the concurrent campaign/integrations workstream, alongside five other
untracked files in the same package and two new documents
(`docs/VB-7C-INTEGRATIONS-CAMPAIGN-CONFIGURATION-AUDIT.md`,
`docs/VB-7C.1-CAMPAIGN-CONFIGURATION-CORRECTNESS.md`). It is not Phase E code,
and Phase D's ownership boundary assigns campaign/voice to that workstream.

**Blast radius.** `default-compile` failing means `mvnw compile`, `mvnw test`
and `spring-boot:run` all fail. `target/classes/com/shivang/obd/telephony` was
left empty by the failed build. **Every Java objective in Phase E is therefore
blocked, and no Java test can currently be run at all** — including the Phase D
live suite.

**Not fixed, deliberately.** Editing an untracked file in another workstream's
in-flight feature, mid-authoring, is how two agents lose each other's work. The
fix is one line in their file, and it is theirs.

## 8. RTP

Phase C left two-way audio unproven. This phase got one leg measured.

### What is excluded as evidence

Re-confirmed this phase, on a live answered channel:

```text
rtp_audio_in_packet_count  = _undef_
rtp_audio_out_packet_count = _undef_
rtp_audio_in_octet_count   = _undef_
rtp_audio_out_octet_count  = _undef_
api uuid_debug_media <uuid>  ->  -ERR api Command not found!
```

No packet-capture tool (`tcpdump`, `tshark`, `dumpcap`) exists in either image,
and no image can be pulled because the Docker daemon's DNS is broken. Per-packet
RTP logging is not emitted at debug on this build either — 101 console lines were
produced during a call and none were RTP.

The only real measurement available is the kernel's own UDP counters
(`/proc/net/snmp`) read inside each container. These are real packet counts, not
inferences. They are per-container rather than per-stream, so the design makes
media dominate: a 30-second file (`backend/data/audio/phase-e-rtp-probe.wav`)
rather than the 3-second one, and counters sampled immediately either side.

### Result

The platform is an outbound channel with no bridge, so during the call it is
only sending. Anything it *receives* therefore came from the endpoint.

```text
[endpoint -> platform]  platform InDatagrams +64   during the endpoint's own tone
                        (the test sent nothing to cause this)
[platform -> endpoint]  the broadcast never started - see §9
```

Whole-call totals: endpoint sent **+68**, platform received **+64**; platform
sent **+5**, endpoint received **+4**.

**endpoint → platform: CONFIRMED — LOCAL.** Sixty-plus real packets crossed the
Docker bridge from the far end and arrived, unprompted.

**platform → endpoint: NOT PROVEN.** The platform's own `uuid_broadcast` did not
start (see §9), and the +4 the endpoint received is consistent with SIP
keepalive rather than RTP.

**TWO-WAY AUDIO — NOT PROVEN.** Upgrading this to confirmed would require the
platform's audio to be seen at the far end, and it has not been.

## 9. PLAYFILE (platform → customer) — PARTIAL, unresolved

This is the direction the campaign's own playback uses, and it is the one that
is not working. Recorded exactly as found:

```text
call answered                 yes (peer CHANNEL_ANSWER, SDP negotiated)
api uuid_broadcast <uuid> /media/obd/phase-e-rtp-probe.wav aleg
  -> -ERR invalid uuid
api uuid_broadcast <uuid> /media/obd/phase-e-rtp-probe.wav       (no aleg)
  -> -ERR invalid uuid
```

and, on the same UUID, moments earlier:

```text
api uuid_dump <uuid>  -> CHANNEL_DATA, Core-UUID: 51088ffc-...
show channels         -> 1 total
```

**A contradiction that is not resolved.** The switch reports the UUID as invalid
for `uuid_broadcast` while reporting it valid for `uuid_dump`, on a live
answered channel with a negotiated SDP. The file exists, is a valid RIFF/WAV, and
is readable by the switch. No `sndfile` or `playback` error appears in the log.

This matters because **the Java client always sends the `aleg` form**, so if this
is a real defect it would affect every production playback. It is therefore
recorded as an open defect against `EslClient.playFile` / `uuid_broadcast`, not
as a Phase E observation, and it must be re-tested once the build is usable.

Phase C's `+OK Message sent` → `PLAYBACK_START` → `PLAYBACK_STOP` sequence for a
valid file was **not** reproduced in Phase E, and the cause is unknown.

## 10. DTMF — PARTIAL

The endpoint's dialplan emits a known digit sequence (`0159#*`) after answer,
over RTP. The platform received them at the RTP layer:

```text
[INFO] switch_channel.c:529 RECV DTMF 0:2080
[INFO] switch_channel.c:529 RECV DTMF 1:2080
[INFO] switch_channel.c:529 RECV DTMF 5:2080
```

So **DTMF tones are crossing the media path**, which Phase C could not establish
(its endpoint emitted DTMF before any bridge existed, so there was no RTP
stream). That is a genuine promotion, and it closes Phase C §6.11's blocking
precondition.

However **no `CHANNEL_DTMF` event was observed on the platform channel**, which is
what the Java application consumes. `DTMF-Digit` never arrived. Phase D's
`docs/freeswitch/05-ESL.md` §9.3 therefore remains **NOT YET TESTED** — tones
received is not the same as an event delivered, and only the second is what the
application can use.

## 11. IVR — NOT TESTED

Depends on the DTMF event path (§10), which is not established. No IVR Java code
was touched. This also means Phase C §9.3's open question about DTMF header
casing and value representation is still open.

## 12. CONNECT_BY_AGENT — NOT TESTED

Not attempted. It requires the Spring application (blocked, §7) and a working
agent leg. The customer leg is now proven, so a bridged two-channel test is the
natural next step once the build works. `LocalPhaseD` had covered the
`CHANNEL_BRIDGE` correlation path in unit tests; that remains true and was not
weakened.

## 13. Max Duration — NOT TESTED

Requires the application (`StaleCallReconciler`) and a `CallSession` with a
deadline. Blocked by §7.

## 14. Failure Matrix — NOT TESTED

Busy / no-answer / rejected / temporary-failure / resource-unavailable /
normal-clearing all require placing calls through the application. Blocked by §7.

The one hangup cause observed organically was `DESTINATION_OUT_OF_ORDER`, from
`uuid_kill` against a channel the far end was holding open — a real SIP
response, not a routing failure, but it is not one of the six conditions under
test and the taxonomy was not exercised.

## 15. Automated Tests

**Not run. Not runnable.** `mvnw compile` fails on a concurrent untracked file
(§7), so `mvnw clean test` cannot execute. No test result is reported for Phase
E, and none should be inferred from this document.

The Phase D suites remain in the tree and were last green: telephony 103 tests /
0 failures, live suite 4/4 against the switch. They must be re-run once the build
compiles; they were not modified in Phase E.

## 16. Security

| Check | Result |
|---|---|
| live credential values in any file changed or added | **0** (scanned against the 14 values in git-ignored `infra/.env`) |
| ESL password in source, harness or docs | **none** — read from `infra/.env` at runtime, never printed |
| SIP passwords committed | **none** |
| `infra/.env` / `*.fsxml` / runtime logs committed | **none** — ignore rules verified active |
| new host ports published | **none** — the gateway adds no port mapping |
| carrier credentials | **none** — no carrier exists; `fs-gateway` untouched and unroutable |
| local gateway blast radius | `register=false`, no credentials, dials a private Docker address only |
| `infra/docker-compose.yml` | **untouched**, `git diff` clean |

`EslEventService`'s own JSON is not involved. The one credential used in this
phase was read from `infra/.env` at runtime for the app launch and was never
echoed, and the launch log was not committed.

## 17. Live Evidence Matrix

| Scenario | Result | Evidence |
|---|---|---|
| Gateway resolves (DNS, ACL, transport) | **PASS** | `getent`, `acl … true`, ping 0% loss |
| Gateway ownership correct | **PASS** | `internal::local-endpoint-1002` |
| SIP INVITE reaches the endpoint | **PASS** | endpoint `receiving invite from 172.25.0.3:5060` |
| Endpoint answers | **PASS** | endpoint `CHANNEL_ANSWER`, `Answer-State=answered` |
| SDP negotiated | **PASS** | `remote_media_ip=172.25.0.4`, codec PCMU |
| RTP allocation | **PASS** | `30078` / `31378` |
| RTP packets endpoint→platform | **PASS** | +64 received, +68 sent |
| RTP packets platform→endpoint | **NOT PROVEN** | broadcast never started |
| Two-way audio | **NOT PROVEN** | one leg only |
| Endpoint↔platform hangup | **PASS** | `DESTINATION_OUT_OF_ORDER` |
| DTMF tones on the wire | **PASS** | `RECV DTMF 0/1/5` |
| DTMF `CHANNEL_DTMF` event | **NOT PROVEN** | event never observed |
| Spring application startup | **BLOCKED** | compile error, concurrent file |
| Java originate via Spring | **BLOCKED** | same |
| PLAYFILE (platform→customer) | **PARTIAL** | `-ERR invalid uuid`, unresolved |
| Hangup via Java | **BLOCKED** | requires the build |
| Max duration | **BLOCKED** | requires the build |
| IVR | **NOT TESTED** | depends on DTMF event |
| CONNECT_BY_AGENT | **NOT TESTED** | depends on the build |
| Failure matrix | **NOT TESTED** | depends on the build |
| Carrier / PSTN | **NOT TESTED** | — |

## 18. Files Changed

**FreeSWITCH / infrastructure** (test-only, private network, no ports added)

- `infra/freeswitch/gateway/local-endpoint.xml` *(new)* — the local gateway
- `infra/freeswitch/dialplan/public.xml` *(new)* — replaces the stock context
  that answered calls with voicemail
- `infra/freeswitch/conf/sip_profiles/internal.xml` — declares the gateway
- `infra/freeswitch/conf/sip_profiles/external.xml` — documents why it is not here
- `infra/docker-compose.freeswitch.yml` — one read-only mount for the dialplan
- `infra/freeswitch-endpoint/conf/dialplan/obd-endpoint.xml.in` — condition
  corrected to `destination_number`
- `backend/data/audio/phase-e-rtp-probe.wav` *(new, git-ignored)* — 30s probe

**Harness** (outside the product)

- `tools/freeswitch-harness/e1_decisive.py` *(new)* — peer-witnessed gateway test
- `tools/freeswitch-harness/e1_gateway_validate.py` *(new)*
- `tools/freeswitch-harness/e5_gateway_signalling.py` *(new)*
- `tools/freeswitch-harness/e4_rtp_measure.py` *(new)*
- `tools/freeswitch-harness/e_final_evidence.py` *(new)*

**Not changed:** `infra/docker-compose.yml`; any Java; any file owned by the
concurrent campaign/voice workstream.

## 19. Architecture Impact

**NO.** No Java was changed. No abstraction was added. The infrastructure change
is a development-environment dialplan and a test-only gateway on a private
Docker network, with no new port and no credential.

## 20. Known Limitations

| Item | Status |
|---|---|
| Spring application has never connected to a live switch | **BLOCKED** |
| Java-originated call through the production gateway path | **BLOCKED** |
| PLAYFILE platform→customer | **PARTIAL** — `-ERR invalid uuid`, unresolved |
| Two-way audio | **NOT PROVEN** — endpoint→platform only |
| DTMF as an application event | **NOT PROVEN** — tones received, event not |
| IVR | **NOT TESTED** |
| CONNECT_BY_AGENT | **NOT TESTED** |
| Max duration | **NOT TESTED** |
| Failure matrix | **NOT TESTED** |
| Automated tests | **NOT RUN** — build blocked |
| Carrier / PSTN | **NOT TESTED** |

## 21. Next Phase

Only evidence-backed work:

1. **Let the campaign workstream finish `WebhookConfig.java`.** Every Java
   objective is gated on it, and it is one line in their file. Re-run
   `mvnw clean test` and confirm the Phase D suites are still green.
2. **Re-test `uuid_broadcast` against a live answered channel** and resolve the
   `-ERR invalid uuid` / `uuid_dump works` contradiction (§9). If it is real, it
   is a defect in `EslClient.playFile` affecting every production playback, and
   it is higher priority than anything else here.
3. **Start the application** with the gateway working, and place the first
   Java-originated call. Everything else in §16's blocked list unblocks from it.
4. **Close the platform→endpoint RTP leg** and reclassify two-way audio.
5. **Then** DTMF event delivery, IVR, CONNECT_BY_AGENT, max duration, failure
   matrix.

## 22. Related Documents

- [`LIVE-FREESWITCH-PHASE-D.md`](LIVE-FREESWITCH-PHASE-D.md) — the Java/ESL contract this phase built on
- [`LIVE-FREESWITCH-PHASE-C.md`](LIVE-FREESWITCH-PHASE-C.md) — where two-way audio was left unproven
- [`freeswitch/decisions/ADR-007-local-gateway-routing.md`](freeswitch/decisions/ADR-007-local-gateway-routing.md)
- [`freeswitch/06-SIP.md`](freeswitch/06-SIP.md) — routing-by-domain, and the voicemail false positive
- [`freeswitch/13-TROUBLESHOOTING.md`](freeswitch/13-TROUBLESHOOTING.md) — entries added by this phase

---

> **Corrected in Phase E.1.** Two statements in this report were wrong in their
> implication, and are corrected rather than deleted:
>
> * **§9** recorded `uuid_broadcast` returning `-ERR invalid uuid` as an open
>   defect against `EslClient.playFile`. **It is not a defect.** The Java command
>   is correct and is accepted; the endpoint's dialplan used an application
>   (`wait`) that does not exist in this build, so every call was destroyed
>   ~600 ms after answer. See `LIVE-FREESWITCH-PHASE-E1.md` §9.
> * **§8** recorded two-way audio as **NOT PROVEN**. That is superseded by
>   **CONFIRMED — LOCAL** (+102 packets endpoint→platform, +814 the other way),
>   for the same reason: the earlier measurement was taken on a dead channel.