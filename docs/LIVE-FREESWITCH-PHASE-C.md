# LIVE FREESWITCH - PHASE C: LOCAL LIVE TELEPHONY VALIDATION

**Status: PASS WITH LIMITATIONS**

Phase C set out to prove, by observation rather than by reading configuration,
how the live FreeSWITCH environment actually behaves. It did that for the SIP
signalling, ESL, media-allocation, PLAYFILE and hangup layers. It did **not**
complete a proven two-way-audio call between two endpoints, and it did not
exercise the Java application's own outbound call path. Those gaps are recorded
below with the exact reason and the exact next step, not glossed over.

This document is the phase record. The durable, reusable knowledge is in
[`docs/freeswitch/`](freeswitch/README.md); this document explains what was done,
what broke, and why the fixes look the way they do.

---

## 1. Objective

Prove the actual runtime behaviour of the existing FreeSWITCH environment
locally, using local SIP endpoints, and record the result so that a future
engineer with no FreeSWITCH experience can operate and debug it.

The specific thing Phase B could not do was the interesting thing: Phase B proved
that configuration *exists and is read*. It could not prove that a call
connects, that media flows, that audio plays, that DTMF is decoded, or that the
headers the Java client reads actually exist.

## 2. Prerequisites

Everything Phase B produced, unchanged at the start of this phase:

| Item | State |
|---|---|
| `infra/docker-compose.freeswitch.yml` | running, healthy, 0 restarts |
| FreeSWITCH | `1.11.1-release 64bit` |
| Image digest | `sha256:8b55a395739a79dc9565a11057cc78dcbd9d28ef61432cc19041daf749cbfd52` |
| Network | `obd-telephony` |
| Media root | `backend/data/audio` bind-mounted read-only at `/media/obd` |
| ESL secret | rendered from `infra/.env`, never committed |

Two host facts, established before any code was written, shaped the whole phase:

- **Python 3.13 is available** at `C:\Python313\python.exe` and can open raw
  sockets. `D:\python.exe` is a broken 3.11 install (missing DLL, exit code
  `0xC0000135`) and is not the interpreter to use.
- **The Docker daemon still cannot resolve a registry.** No new image can be
  pulled. Every container in this phase runs the image that was already loaded.

## 3. Architecture

### 3.1 What the environment looks like now

```
                    obd-telephony (bridge)
   ┌──────────────────────────────────────────────────────────────┐
   │                                                              │
   │  obd-freeswitch            172.25.0.2   "the platform switch" │
   │    profile internal  :5060      ESL :8021  RTP 30000-30099     │
   │    profile external  :5080                                      │
   │    gateway fs-gateway -> freeswitch-provider:5080  (NOREG)     │
   │                                                              │
   │  obd-fs-endpoint-1001   172.25.0.3/4  registers as 1001       │
   │  obd-fs-endpoint-1002   172.25.0.3/4  registers as 1002       │
   └──────────────────────────────────────────────────────────────┘
          ▲                          ▲                    ▲
          │ 127.0.0.1:8021           │ 127.0.0.1:8031    │ :8032
          │                          │                    │
      host tooling              host tooling         host tooling
   (ESL harness)              (read its media)     (read its media)
```

### 3.2 The decision that made the endpoints in-network

This was not a preference. It was measured, and the measurement is the reason
ADR-001 was already right.

The Windows host has **no route** to the Docker bridge network:

```
Test-NetConnection 172.25.0.2 -Port 5060  ->  TcpTestSucceeded=False
ping 172.25.0.2                            ->  no reply
route print                                 ->  no 172.25.0.0/16 entry
```

FreeSWITCH advertises its own container address in both `Contact` and SDP, so:

| From the host | Result | Why |
|---|---|---|
| SIP over **TCP** to `127.0.0.1:5060` | works | published port, connection is stateful |
| SIP over **UDP** to `127.0.0.1:5060` | **broken** | Docker's UDP proxy does not return the response to the original client port |
| `REGISTER` from the host | works | one-way; FreeSWITCH only has to answer |
| FreeSWITCH **calling back** the host | **impossible** | it would send to a contact of `127.0.0.1`, which inside the container is the container's own loopback |
| RTP to the host | **impossible** | same reason |

A host softphone can therefore *place* calls and be *registered*, but can never
be *called* and can never *hear* audio. A callable, media-capable endpoint has to
be inside the Docker network. This is recorded as ADR-004.

### 3.3 Why the endpoints are FreeSWITCH and not SIPp

The Docker daemon cannot resolve a registry, so no new image can be fetched.
The already-pinned `ghcr.io/patrickbaus/freeswitch-docker:1.11.1` was reused, so
this phase adds **no new image and no new supply-chain surface**. A second
FreeSWITCH is in any case a more faithful peer than a bare SIP UA, because it
performs real REGISTER / 401 / digest / 200 OK transactions and real RTP.

## 4. What was implemented

Infrastructure changes only. No Java, no tests, no migrations, and
`infra/docker-compose.yml` is byte-for-byte untouched (`git diff -- infra/docker-compose.yml`
is empty, verified before and after).

| Path | Purpose |
|---|---|
| `infra/docker-compose.freeswitch-endpoints.yml` | **new.** two endpoint containers |
| `infra/freeswitch-endpoint/scripts/entrypoint.sh` | **new.** renders secrets, resolves the platform address, execs FreeSWITCH |
| `infra/freeswitch-endpoint/conf/autoload_configs/sofia.conf.xml` | **new.** one profile, explicit |
| `infra/freeswitch-endpoint/conf/autoload_configs/acl.conf.xml` | **new.** same policy as the platform |
| `infra/freeswitch-endpoint/conf/autoload_configs/event_socket.conf.xml.in` | **new.** endpoint ESL template |
| `infra/freeswitch-endpoint/conf/sip_profiles/obd-endpoint.xml.in` | **new.** profile + registering gateway |
| `infra/freeswitch-endpoint/conf/dialplan/obd-endpoint.xml.in` | **new.** answer, emit DTMF, play, wait, hang up |
| `infra/freeswitch/conf/directory/default.xml.in` | **replaces** the image's stock directory |
| `infra/freeswitch/conf/directory/obd-test.xml.in` | **deleted** - superseded by the single directory above |
| `infra/freeswitch/conf/sip_profiles/internal.xml` | `auth-subscriptions` enabled, `challenge-realm` pinned |
| `infra/freeswitch/conf/autoload_configs/event_socket.conf.xml.in` | `listen-ip` corrected |
| `infra/freeswitch/scripts/entrypoint.sh` | renders the directory, adds a stock-user guard |
| `infra/docker-compose.freeswitch.yml` | mounts the new directory template, new env vars |
| `backend/data/audio/phase-c-test-tone.wav` | local test asset, git-ignored |
| `tools/freeswitch-harness/*.py` | the test harness (see §6.1) |

## 5. WHY each change was made

Every change below exists because a test failed and the failure was understood
first. Full symptom-by-symptom entries are in
[`docs/freeswitch/13-TROUBLESHOOTING.md`](freeswitch/13-TROUBLESHOOTING.md);
this is the summary and the reasoning.

### 5.1 ESL must listen on the container address, not on loopback

| | |
|---|---|
| **Decision** | `listen-ip` = `0.0.0.0` inside the container; the Compose mapping stays `127.0.0.1:8021` |
| **Why** | A published port is delivered to the container's **network address**, not its loopback. With `listen-ip=127.0.0.1` the published port accepted the TCP connection and closed it with zero bytes. |
| **Alternative rejected** | Keep `127.0.0.1` and drop the published port, requiring `docker exec` for all ESL. Rejected: the intended access path is a **host-run Java process** connecting to `localhost:8021`. Phase B never exercised that path because every ESL test used `docker exec`, which bypasses the published port entirely. |
| **Consequence** | ESL is reachable from other containers on `obd-telephony` as well as from this machine. That is intended - a containerised Java application is meant to reach `freeswitch:8021` - and `apply-inbound-acl=obd-dev-acl` still limits peers to loopback and RFC1918. |

The control that proves it, rather than a guess:

```
netstat inside container   127.0.0.1:8021   <- loopback only
host -> 127.0.0.1:8021    recv 0 bytes       <- broken
netstat inside container   172.25.0.2:5060   <- container address
host -> 127.0.0.1:5060    "SIP/2.0 200 OK"  <- works
inside container 127.0.0.1:8021 -> auth banner <- works
```

### 5.2 Own the directory completely

The image ships a directory defining users `1000`-`1014` whose password is set
by `vars.xml` to a publicly known value. Phase C had to enable registration in
order to observe it, which turned that latent weakness into a live one. The
directory is now owned by this project: one file, one domain, two users, and an
entrypoint guard that refuses to start if any other user id appears.

This is ADR-005.

### 5.3 Pin the authentication realm

With the stock `challenge-realm=auto_to`, the realm is taken from the `To`
header - that is, from whatever address the client dialled. That makes a
registration's identity depend on the network path used to reach FreeSWITCH, and
it desynchronises the realm used to *authenticate* from the key used to *file the
registration* (`user@realm`). The realm is now pinned to `$${domain}`.

### 5.4 The endpoints join as additional FreeSWITCH instances

Already covered in §3.2. ADR-004.

### 5.5 An explicit test asset, in a native format

`backend/data/audio` contained only a `.gitignore`. `mod_av` cannot load in this
image (missing `libavformat.so.62`), so a compressed file could not be played and
a *codec* failure would have been indistinguishable from a *missing file* failure.
The asset is therefore 8 kHz mono 16-bit PCM WAV, which FreeSWITCH handles
natively. PCMU's sample rate, so transcoding is trivial.

## 6. Evidence

### 6.1 The harness

The tests are scripts, not ad-hoc commands, so they can be re-run. They live
**outside the repository's product code** under `tools/freeswitch-harness/`:

| Script | Question it answers |
|---|---|
| `esl.py` | a real ESL client: framing, auth, subscription, `api` vs `bgapi` |
| `sip_ua.py` | a real SIP user agent: REGISTER with digest, INVITE, ACK, BYE |
| `probe_realm.py` | which realm does the platform actually challenge with? |
| `c4_dialstrings.py` | which dial strings create a channel, and which fail? |
| `c4_bridge_search.py` | which dial strings produce a real **bridge**? |
| `c5_rtp_counters.py` | what RTP ports are actually allocated on a live call? |
| `c6_event_verbatim.py` | which events arrive, and which header carries the UUID? |
| `c6_headers_verbatim.py` | the **complete** header set of each event, unfiltered |
| `c6_hangup_playback_dtmf.py` | the complete header set of hangup, playback and DTMF |
| `make_test_tone.py` | generates the WAV test asset |

`esl.py` and `sip_ua.py` are hand-written rather than library-based on purpose:
the point is to see exactly what FreeSWITCH sends, not what a library normalises.

### 6.2 C1 - runtime, confirmed

```
State=running Health=healthy Restarts=0
UP 0 years, 0 days, 1 hours, ...
FreeSWITCH (Version 1.11.1-release  64bit) is ready
  external  profile  sip:mod_sofia@172.25.0.2:5080   RUNNING (0)
  external::fs-gateway  gateway  sip:FreeSWITCH@freeswitch-provider:5080  NOREG
  internal  profile  sip:mod_sofia@172.25.0.2:5060   RUNNING (0)
```

`NOREG` on `fs-gateway` is correct, not a fault: no carrier exists, and the
gateway has `register=false`.

### 6.3 C3 - registration, CONFIRMED - LOCAL

The observed flow, for both extensions, from a real SIP client:

```
REGISTER (no auth)         -> SIP/2.0 401 Unauthorized
REGISTER (digest, qop=auth)-> SIP/2.0 200 OK
```

with the challenge carrying `realm="172.25.0.2"` and `qop="auth"`. Sofia's own
view, which is the authoritative one:

```
User:         1002@172.25.0.2
Contact:      <sip:gw+platform-switch@172.25.0.3:5060;transport=udp;gw=platform-switch>
Status:       Registered(UDP)(unknown) EXP(...) EXPSECS(3556)
Ping-Status:  Reachable
IP:           172.25.0.3
Auth-User:    1002
Auth-Realm:   172.25.0.2
```

Both in-network endpoints register by **gateway** (`gw+platform-switch@...`), and
a host client registers over TCP. All three are `Reachable`.

### 6.4 C5 - RTP allocation, CONFIRMED - LOCAL

Phase B could show the RTP range only in the file, and recorded this as
**PARTIAL**. It is now resolved, because the ports are read back off live
channels during a bridged call:

```
local_media_port = 30022    remote_media_port = 30036
local_media_port = 30036    remote_media_port = 30022
local_media_port = 30028
local_media_port = 30070
local_media_port = 30082
```

Every one is inside `30000-30099`, the range configured in `switch.conf.xml`.
The ports are additionally visible in `CHANNEL_CREATE` itself, as
`variable_local_media_port`, before the call is even answered.

Codec negotiation observed: `read_codec = PCMU`, `write_codec = PCMU`.

**Two-way audio is NOT confirmed.** See §10.1.

### 6.5 C6 - ESL, and three findings that change the Java contract

Complete, unfiltered header sets were captured for `CHANNEL_CREATE`,
`CHANNEL_ANSWER`, `PLAYBACK_START`, `PLAYBACK_STOP` and `CHANNEL_HANGUP`.

**(a) There is no `Call-UUID` header.** The channel UUID arrives as:

```
Unique-ID           = 348eecfb-7d68-48f8-a566-3cac24e4a8cc
Channel-Call-UUID   = 348eecfb-7d68-48f8-a566-3cac24e4a8cc
Caller-Unique-ID    = 348eecfb-7d68-48f8-a566-3cac24e4a8cc
variable_call_uuid  = 348eecfb-7d68-48f8-a566-3cac24e4a8cc
variable_uuid       = 348eecfb-7d68-48f8-a566-3cac24e4a8cc
Call-UUID           = ABSENT
```

`EslEvent.getCallUuid()` is `headers.get("Call-UUID")`. It therefore returns
`null` for **every** event type, including `CHANNEL_HANGUP`, and
`EslEventService`'s `log.warn("Received ESL event without Call-UUID: ...")`
would fire on every single event. This is defect **J1** in §7.2.

**(b) An `api` command returns its result in the body, not in `Reply-Text`.**

```
api status    -> Reply-Text: (empty)      body: "UP 0 years, ..."
bgapi status  -> Reply-Text: "+OK Job-UUID: 77898af7-..."
```

`Reply-Text` is for command-level verdicts (`auth`, `event`, `bgapi`), not for
`api` results. A client that reads only `Reply-Text` will believe every
diagnostic command returned nothing.

**(c) `+OK Job-UUID` is not evidence that a call was placed.** `bgapi`
acknowledges the *command*; the command then runs on a task thread and can fail
with no feedback at all. Observed directly:

```
bgapi originate {origination_uuid=...}user/1002
  -> +OK Job-UUID: f1d32e2f-...
  -> 0 channels
  -> log: [WARNING] switch_ivr_originate.c:2324 No origination URL specified!
```

The only trustworthy success signals are a `CHANNEL_CREATE` whose `Unique-ID`
equals the pinned `origination_uuid`, and eventually a `CHANNEL_ANSWER`.

**Three UUIDs, all distinct, confirmed:**

| Identifier | Example | What it is |
|---|---|---|
| ESL `Job-UUID` | `cb276655-0c07-4a2f-ad72-fe2ee415da22` | the background **task** |
| pinned `origination_uuid` | `d262be83-728e-4532-b073-812310ca8552` | becomes the channel UUID (`variable_origination_uuid`) |
| SIP `Call-ID` | see `sip_call_id` | the SIP dialog |

The pinned value and the `Job-UUID` were different on every single test.

**A hazard for any client that shares one socket:** a synchronous command on a
connection that is *also* receiving subscribed events can read the next **event**
instead of the command's reply, leaving the real reply queued and every later read
off by one frame. Observed as a command that simply timed out. The fix is a
second connection for commands, which is what the harness does.

### 6.6 C8 - PLAYFILE, CONFIRMED - LOCAL

Using the Java client's exact command form:

```
uuid_broadcast <uuid> /media/obd/phase-c-test-tone.wav aleg
  -> +OK Message sent
  -> PLAYBACK_START
  -> PLAYBACK_STOP
log: EXECUTE [depth=1] ... playback(/media/obd/phase-c-test-tone.wav)
```

The media root resolves, the bind mount is visible read-only, and the file plays.

### 6.7 C9 - playback failure, CONFIRMED - LOCAL, and it contradicts Phase B

Phase B recorded the `Playback-Error` header as **NOT YET TESTED**. It is now
resolved, and the answer is that **no such event is emitted**:

```
uuid_broadcast <uuid> /media/obd/phase-c-deliberately-absent.wav aleg
  -> +OK Message sent            <- the command still succeeds
  -> NO PLAYBACK_ERROR event
  -> NO PLAYBACK_START either    <- playback never began
log: [WARNING] mod_sndfile.c:281 Error Opening File
     [/media/obd/phase-c-deliberately-absent.wav]
     [System error : No such file or directory.]
```

So a missing file is reported **only in the log**. `EslEventService` reads
`event.getHeader("Playback-Error")`, which can never be populated. This is
defect **J2** in §7.2. A correct implementation has to detect the absence of
`PLAYBACK_START`/`PLAYBACK_STOP`, or watch the log, or pre-check the file.

### 6.8 C14 - hangup, CONFIRMED - LOCAL

```
uuid_kill <uuid> NORMAL_CLEARING
  -> +OK
  -> CHANNEL_HANGUP
       Unique-ID        = <channel uuid>
       Hangup-Cause     = NORMAL_CLEARING
       Answer-State     = hangup
       Call-UUID        = ABSENT
log: Hangup sofia/internal/1002@172.25.0.2 [CS_EXECUTE] [NORMAL_CLEARING]
```

### 6.9 C4 - endpoint-to-endpoint call: PARTIAL, stated precisely

What **is** proven: a platform-originated call to a registered, in-network
endpoint reaches it, and the platform observes a real answer and a real bridge
with PCMU negotiated on both legs.

What is **not** proven: that the two legs of that bridge include the endpoint's
own leg, and therefore that audio crosses the network in both directions.

The reason is now understood. `sofia/internal/1002@172.25.0.2` sends the INVITE,
the endpoint answers, and the platform's stock dialplan then takes over the
channel. Two separate mechanisms are in play and they interleave confusingly:

```
Processing +15551230000 <+15551230000>->1002 in context public
transfer(1002 XML default)
Processing +15551230000 <+15551230000>->1002 in context default
```

The transfer creates a **new channel with a new UUID**. The pinned
`origination_uuid` does not survive it. That is defect **J3**, and it is the
reason a `user/1002` originate, which *looks* like the natural form, cannot work
at all:

```
Cannot create outgoing channel of type [1002@{$dialed_domain}]
  cause: [CHAN_NOT_IMPLEMENTED]
Cannot create outgoing channel of type [user] cause: [CHAN_NOT_IMPLEMENTED]
```

`{$dialed_domain}` is **unexpanded**: it is a dialplan variable, not a channel
variable, so it is empty at originate time. `user/...` is a *dialplan* target,
not a valid originate URL. The full search is reproducible via
`c4_bridge_search.py`, whose result is:

| Dial string | Channel created | Answered | Bridged |
|---|---|---|---|
| `user/1002` | no | - | - |
| `user/1002 &park()` | no | - | - |
| `user/1002 1002` | no | - | - |
| `user/1002 &park() 1002` | no | - | - |
| `sofia/internal/1002@172.25.0.2 &park()` | **yes** | **yes** | **yes** |
| `loopback/dial/user/1002` | yes | no | no |

`sofia/internal/...` is therefore the working local form, and it is the closest
available analogue of the production `sofia/gateway/<gw>/<dest>` - same shape: a
short dial string resolved through a profile.

### 6.10 C7 - Java-originated call: NOT YET TESTED

**Not attempted in this phase.** Running the Spring Boot application requires
PostgreSQL, Redis, a Maven build and seeding a tenant, campaign, contacts, DTMF
configuration, an audio asset and a gateway row. That is a substantial exercise in
its own right and belongs to the phase that connects Java, not to this one.

What *was* done instead, and is worth stating plainly: **the exact ESL command
strings the Java client issues were executed against the live switch**, using the
Java client's command forms, so that the FreeSWITCH side of the contract is
proven even though the Java side is not yet running. That is what produced §6.5,
§6.6, §6.7 and §6.8.

### 6.11 C10 - DTMF: NOT YET TESTED, with the reason identified

No `CHANNEL_DTMF` event was ever observed. The cause is understood and is a
consequence of §6.9: the endpoint's dialplan calls `send_dtmf` immediately after
`answer`, but at that point the two legs are not yet bridged, so there is no RTP
stream and RFC 4733 in-band events have nowhere to travel.

DTMF therefore requires the bridge to be established first, which requires
replacing the platform's stock dialplan with an explicit one so that an inbound
call to a registered extension bridges directly to its contact. That is the first
item of the next phase (§12). It is a configuration change, not a code change, and
nothing about it is blocked.

The predicted event shape, for whoever runs it next, is recorded in
[`docs/freeswitch/05-ESL.md`](freeswitch/05-ESL.md) and is still labelled
**NOT YET TESTED**.

## 7. Problems encountered

### 7.1 Infrastructure defects found and fixed

Each was classified before anything was changed, per the fix policy.

| # | Defect | Class | Status |
|---|---|---|---|
| I1 | ESL bound to container loopback; published port unusable | configuration | **fixed** |
| I2 | `challenge-realm=auto_to` desynchronised auth realm from registration key | configuration | **fixed** |
| I3 | Stock directory users `1000`-`1014` with a public default password, now reachable because registration was enabled | **security** | **fixed** |
| I4 | Extension `1001` collides with the stock directory; a correct password is answered `403` | configuration | **fixed** |
| I5 | Directory domain missing its `dial-string` parameter: channel answers, never bridges, no media, no error | configuration | **fixed** |
| I6 | Endpoint `sofia.conf.xml` root element `<config>` instead of `<configuration>`: `Open of sofia.conf failed`, no SIP port at all | configuration | **fixed** |
| I7 | Endpoint ESL root element `<config>`, and `port` instead of `listen-port`: **all settings silently ignored, stock credential live** | **security** | **fixed** |
| I8 | Endpoint profile `<param>` outside `<settings>`: `No Settings, check the new config!` | configuration | **fixed** |
| I9 | Two templates bind-mounted at the same `/opt/obd/obd-endpoint.xml.in`: one shadows the other | configuration | **fixed** |
| I10 | A per-service `volumes:` key **replaces** the merge-anchor's list instead of appending, so every inherited mount was lost | configuration | **fixed** |
| I11 | Two containers sharing one log directory corrupt the compiled config and `core.db`: `Cannot Initialize [Cannot Open log directory or XML Root!]` | configuration | **fixed** |
| I12 | Endpoint gateway registered via the network *alias*, putting the alias in the `To` header and therefore in the realm | configuration | **fixed** |
| I13 | Endpoint `rfc2833-pt` not pinned to match the platform's, which would silently break DTMF negotiation | configuration | **fixed (preventively)** |

I3, I4 and I7 are the interesting ones, because in all three cases **FreeSWITCH
started normally, reported itself UP, and behaved in a way that looked like a
network or password problem.** I7 in particular left the endpoint's ESL running
on the image's stock default credential; that is a security-relevant failure that
only surfaced because the entrypoint's own token check failed and refused to
start. The guard did its job.

I4 deserves its own note, because the symptom actively misleads:

```
[WARNING] sofia_reg.c:3210 Can't find user [1001@127.0.0.1] from 172.25.0.1
 You must define a domain called '127.0.0.1' in your directory
```

A `403` on the authenticated retry of a `REGISTER` does **not** mean "wrong
password". It means "no such user for the realm that was challenged". Here it did
not even mean "wrong user" - it meant the right user id was defined twice, by the
stock directory and by the project, and the stock definition won.

### 7.2 Java-side defects found, documented, NOT changed

These are **integration defects in the existing Java code**, established by
observation against a real switch. No Java file was modified in this phase: the
Java application is another workstream's, and the brief for this phase is
validation.

**J1 - `Call-UUID` does not exist. Correlation is broken for every event.**

`EslEvent.java:40` is `return headers.get("Call-UUID");`. No event observed on
this switch carries that header, on any event type, including `CHANNEL_HANGUP`.
The channel UUID is present as `Channel-Call-UUID` and as `Unique-ID`.

Consequence if unchanged: `EslEventService`'s
`log.warn("Received ESL event without Call-UUID: ...")` fires for every event,
and no event correlates to a `CallAttempt`. Every call would appear to hang
forever, because the hangup is correlated by UUID.

Suggested fix: read `Channel-Call-UUID`, falling back to `Unique-ID`. Both were
present on all five event types captured.

**J2 - `Playback-Error` does not exist. Playback failures are undetectable.**

`EslEventService.java:363` reads `event.getHeader("Playback-Error")`. A missing
media file produces **no ESL event at all** - only a `WARNING` in the log from
`mod_sndfile.c:281`. The `uuid_broadcast` command still returns `+OK Message sent`.

Consequence if unchanged: a call whose audio file is missing looks like a call
that is simply not playing anything, indefinitely.

**J3 - The pinned `origination_uuid` does not survive a dialplan transfer.**

`origination_uuid` correctly becomes the channel UUID at originate time - it is
visible as `variable_origination_uuid` in `CHANNEL_CREATE`. But when the stock
dialplan transfers the channel, a **new channel with a new UUID** is created and
the original is gone. A subsequent `uuid_broadcast` or `uuid_kill` addressed to
the original UUID targets a channel that no longer exists.

This is a direct consequence of relying on the stock dialplan, and it is the
strongest argument for replacing that dialplan with an explicit one: it is not
only tidier, it is required for the platform's own UUID-pinning contract to hold.

**J4 - `user/...` is not a valid originate URL.** If any Java code path ever
prefixes a dial string with `user/`, it will fail with `CHAN_NOT_IMPLEMENTED`.
The production form `sofia/gateway/<gw>/<dest>` is unaffected; this is recorded
so the local test form and the production form are not confused.

## 8. Root cause analysis

The single theme behind I1-I13 and J1-J3:

> **FreeSWITCH fails silently.** It starts, reports UP, answers with a
> syntactically valid SIP response, and returns `+OK` to commands it then fails
> to execute.

Every one of these was found by asking FreeSWITCH a direct question and reading
its answer, not by reading configuration:

| Question that found the bug | Tool |
|---|---|
| Where is ESL actually bound? | `netstat` inside the container |
| What realm is challenged with? | a real `REGISTER`, and the `WWW-Authenticate` header |
| What user is the directory actually resolving? | `grep` in the log for `sofia_reg.c` |
| Did the config file take effect? | a value FreeSWITCH reports back |
| Did the call really connect? | `CHANNEL_ANSWER`, then `CHANNEL_BRIDGE` |
| Which RTP port was allocated? | `variable_local_media_port` in the event |

This is why the harness exists, and why it reads FreeSWITCH's own answers rather
than the configuration files.

## 9. Lessons learned

1. **A passing configuration check proves the file was read, not that it did
   anything.** Phase B's own acceptance check verified the Docker mapping
   (`HostIp = 127.0.0.1`) and the in-container bind (`127.0.0.1:8021`)
   separately, and both passed - while the combination, which is the only thing
   a client ever uses, was broken. Never validate a port by its two halves.

2. **Ask the running system, not the file.** Every defect in §7 was found by a
   question with a machine-readable answer. The most productive single command in
   the whole phase was `grep` over the log for `sofia_reg.c` - it turned a
   mysterious `403` into a sentence naming the exact user and realm it looked for.

3. **A `+OK` is a statement about a command, not about a call.** `bgapi`
   acknowledges receipt and nothing more. This bit three separate times, and in
   one case a "known good" form from Phase B turned out never to have connected
   at all - it created channels that died in ~20 ms with
   `Context sleep not found`, which Phase B's event capture had recorded as
   success because events *were* emitted.

4. **`403` on a SIP challenge means "not found", not "wrong secret".** And on a
   `REGISTER` it can also mean "found the wrong one of two identical ids".

5. **The image's defaults are a security surface.** Two of the defects here
   (stock directory users with a published password, ESL settings silently
   ignored so the stock ESL credential stayed live) exist only because a
   configuration file was replaced with the *wrong root element* or *the wrong
   parameter name*, and the failure was silent. Entry points that verify their
   own output - refusing to start if a token did not substitute, or if an
   unexpected user id is present - are what turns a silent failure into a loud
   one.

6. **Container-to-container media must be container-to-container.** The host has
   no route to the bridge network, so any design that assumes a host softphone can
   participate in media is wrong on this machine. This is worth knowing before
   anyone spends a day fighting a softphone.

7. **Two FreeSWITCH instances must never share a log directory.** They all write
   `freeswitch.log`, `freeswitch.xml.fsxml` and `core.db`, and the resulting
   corruption is reported as a configuration error.

## 10. Remaining risks

### 10.1 Two-way audio is unproven - the main gap

Signalling, bridging and RTP allocation are proven. That **audio actually
crosses the network in both directions** is not, because the bridge observed
during the test was between two platform-side legs.

Why it matters beyond the test: every media assumption in the platform - PLAYFILE
reaching the customer, DTMF arriving, the IVR hearing the caller - rests on this,
and none of it has been heard. It is the first thing the next phase must close.

The specific next step, in order:

1. Replace the platform's stock dialplan with an explicit one that bridges an
   inbound call to a registered extension directly to its contact, without a
   transfer that mints a new UUID. This also fixes J3.
2. Re-run `c5_rtp_counters.py` and read `rtp_audio_in_packet_count` on **both**
   the platform's leg and the endpoint's leg. A non-zero *in* count on the
   endpoint's leg is the only acceptable proof.
3. Only then re-run the DTMF test, which needs the bridge to exist first.

### 10.2 The platform's stock dialplan is still in place

It still runs, and it still logs `CRIT ... change the default_password` on every
call, and it still performs a transfer that invalidates the platform's own UUID
contract. The directory is now owned by the project; the dialplan is not. This is
the highest-value remaining infrastructure change.

### 10.3 `mod_av` cannot load in this image

```
Error Loading module /usr/lib/freeswitch/mod/mod_av.so
Error loading shared library libavformat.so.62: No such file or directory
```

Pre-existing and harmless for PCM WAV, which FreeSWITCH handles natively. It
becomes a real constraint the moment the platform stores anything compressed -
MP3 prompts, for instance. Worth knowing before the media pipeline is designed.

### 10.4 The stock `vars.xml` STUN timeout costs ~10 s of startup

Also pre-existing. It delays every container start by roughly ten seconds and
makes start-up timing misleading when debugging.

### 10.5 RTP has no counter channel variable

`rtp_audio_in_packet_count` and its siblings return `_undef_` on this build. So
there is no `fs_cli` route to RTP packet counters; `uuid_debug_media` is not an
alternative, because it *toggles* debug logging rather than printing statistics:

```
-USAGE: <uuid> <read|write|both|vread|vwrite|vboth|all> <on|off>
```

This means proving media flow requires either the endpoint's own view, a packet
capture, or `mod_lua`/`mod_vmd` instrumentation. This is why §10.1 asks for the
*endpoint's* counters specifically.

### 10.6 Carrier-only behaviour remains entirely untested

Everything in §13's "carrier-dependent" row. None of it can be closed locally, and
none of it is guessed at anywhere in the documentation.

## 11. Validation matrix

| Test | Status | Environment | Evidence | Documentation |
|---|---|---|---|---|
| FreeSWITCH UP, healthy, 0 restarts | PASS | local | `docker inspect`, `fs_cli -x status` | `docs/freeswitch/01-ARCHITECTURE.md` |
| Profiles internal + external RUNNING | PASS | local | `sofia status` | `06-SIP.md` |
| Gateway `fs-gateway` NOREG as expected | PASS | local | `sofia status` | `06-SIP.md` |
| ESL listener reachable from host | PASS | local | `+OK accepted` over `127.0.0.1:8021` | `05-ESL.md`, entry 13 |
| RTP range effective at runtime | **PASS** (was PARTIAL) | local | `local_media_port` 30022/30028/30036/30070/30082 | `07-RTP-AND-MEDIA.md` |
| Local SIP endpoints established | PASS | local | two in-network containers registered | ADR-004 |
| SIP registration, host client | PASS | local | `401` -> digest `qop=auth` -> `200 OK` | `06-SIP.md` |
| SIP registration, in-network gateway | PASS | local | `Registered(UDP)`, `Reachable` | `06-SIP.md` |
| Stock credential rejected | PASS | local | stock user 1000 + stock password -> `403` | `14-SECURITY.md` |
| Endpoint-to-endpoint call | **PARTIAL** | local | INVITE sent, `CHANNEL_ANSWER`, `CHANNEL_BRIDGE`; legs not proven to include the endpoint | §6.9 |
| RTP allocation | **PASS** | local | ports inside 30000-30099 | `07-RTP-AND-MEDIA.md` |
| Two-way audio | **NOT YET TESTED** | local | no counter variable exists on this build | §10.1, §10.5 |
| ESL authentication | PASS | local | banner, `auth`, `+OK accepted` | `05-ESL.md` |
| ESL event framing | PASS | local | complete unfiltered header sets captured | `05-ESL.md` |
| `Call-UUID` header present | **FAIL - defect J1** | local | absent on all 5 event types | `05-ESL.md`, §7.2 |
| `Playback-Error` header present | **FAIL - defect J2** | local | no event emitted for a missing file | `05-ESL.md`, §7.2 |
| `origination_uuid` survives dialplan | **FAIL - defect J3** | local | transfer mints a new UUID | §6.9 |
| Job-UUID vs channel UUID distinguished | PASS | local | different on every test | `05-ESL.md` |
| Java originate | **NOT YET TESTED** | local | Java command forms executed from the harness instead | §6.10 |
| PLAYFILE | **PASS** | local | `PLAYBACK_START` / `PLAYBACK_STOP` | §6.6 |
| Playback failure | **PASS** (behaviour differs) | local | log-only, no ESL event | §6.7 |
| DTMF | **NOT YET TESTED** | local | needs the bridge first | §6.11 |
| IVR | NOT YET TESTED | local | depends on DTMF and on Java | - |
| CONNECT_BY_AGENT | NOT YET TESTED | local | depends on the bridge and on Java | - |
| Max duration | NOT YET TESTED | local | Java-owned (`deadline_at`) | - |
| Hangup via `uuid_kill` | **PASS** | local | `+OK` -> `CHANNEL_HANGUP`, `NORMAL_CLEARING` | §6.8 |
| Failure/hangup cause mapping | PARTIAL | local | only `NORMAL_CLEARING` and origination failures observed | `13-TROUBLESHOOTING.md` |
| Carrier interoperability | **NOT YET TESTED / FUTURE** | carrier | no carrier exists | - |

Two rows are marked PASS where the *behaviour* is confirmed but a *defect* was
found - "Playback failure" and "RTP range". In both cases the infrastructure is
correct and the finding is about how the behaviour should be interpreted, so the
row records what was proven and the defect is recorded separately in §7.2.

## 12. Next phase

Strictly in dependency order, because each step unblocks the next.

**Step 1 - own the dialplan (infrastructure, no Java).**
Replace the platform's stock `public`/`default` contexts with explicit ones that
bridge an inbound call to a registered extension directly to its contact, with
no transfer that mints a new UUID. This is the single change that unblocks
everything below, and it fixes J3. Model it on
`infra/freeswitch-endpoint/conf/dialplan/obd-endpoint.xml.in`, which is already
minimal and explicit.

**Step 2 - prove two-way audio.**
Re-run `c5_rtp_counters.py`. Success is a non-zero `rtp_audio_in_packet_count`
on the **endpoint's** leg, not the platform's. Until that number is observed,
documented claims about audio are EXPECTED, not CONFIRMED.

**Step 3 - DTMF, then IVR.**
With media flowing, the endpoint's dialplan will reach the platform with RFC 4733
events. Capture the real `CHANNEL_DTMF` headers and promote
`DTMF-Digit` / `DTMF-Subevent` / `DTMF-Duration` in `05-ESL.md` from
NOT YET TESTED to CONFIRMED - LOCAL. Then the IVR chain.

**Step 4 - raise the three Java defects.**
J1, J2 and J3 are now established with evidence and each has an identified fix.
They should be raised against the Java workstream rather than fixed inside this
one, so that the ownership boundary stays clean. **J1 is the priority**: with
`Call-UUID` never present, the platform cannot correlate a single event, and no
amount of FreeSWITCH-side work will make an end-to-end test pass until it is
addressed.

**Step 5 - run the Java application.**
Only after step 4. The seeding burden (tenant, campaign, contacts, DTMF config,
audio asset, gateway row) is real, and testing against a platform that cannot
correlate its own events would produce a failure that looks like a telephony
fault.

**Not until a carrier exists:** SIP response-code-to-hangup-cause mapping against
a real provider, carrier-side `REGISTER` challenges, carrier codec negotiation
and transcoding, carrier DTMF quirks, and real-world early media. These are
recorded as NOT TESTABLE LOCALLY and are not estimated anywhere.

## 13. Carrier-dependent items

None of the following can be validated without a real SIP provider. They are
listed so that nobody mistakes their absence for an oversight, and none of them
is guessed at in any document.

- Behaviour of `sofia/gateway/<gw>/<dest>` against a real trunk. The gateway
  exists and is `NOREG`; the dial *string shape* is what Phase C exercised
  locally via `sofia/internal/...`.
- Carrier `REGISTER` challenges, auth-realm conventions, and IP-based auth.
- Real SIP response codes for busy / no-answer / rejected / unavailable, and the
  `Hangup-Cause` each produces. Locally only `NORMAL_CLEARING` and
  origination-level failures were observed.
- Carrier codec offer/answer and any transcoding FreeSWITCH must perform.
- Real DTMF: SIP INFO vs RFC 4733 preference, and real-world digit timing.
- 180/183 early media and ringback from a carrier.
- Trunk-level NAT behaviour, which `sip-force-contact` and friends exist to
  handle and which cannot be exercised against a same-network peer.

## 14. Related documents

- [`docs/freeswitch/README.md`](freeswitch/README.md) - knowledge base index and labelling convention
- [`docs/freeswitch/05-ESL.md`](freeswitch/05-ESL.md) - the confirmed event header set
- [`docs/freeswitch/06-SIP.md`](freeswitch/06-SIP.md) - registration, realms, the directory
- [`docs/freeswitch/07-RTP-AND-MEDIA.md`](freeswitch/07-RTP-AND-MEDIA.md) - observed RTP ports
- [`docs/freeswitch/11-DEBUGGING.md`](freeswitch/11-DEBUGGING.md) - "where do I look" table
- [`docs/freeswitch/13-TROUBLESHOOTING.md`](freeswitch/13-TROUBLESHOOTING.md) - entries 13-20, added by this phase
- [`docs/freeswitch/decisions/ADR-004`](freeswitch/decisions/) - endpoints must be in-network
- [`docs/freeswitch/decisions/ADR-005`](freeswitch/decisions/) - own the directory
- [`docs/LIVE-FREESWITCH-PHASE-B.md`](LIVE-FREESWITCH-PHASE-B.md) - the phase this one follows

---

> **Superseded in part by Phase D.** The three Java-side defects this phase
> raised were resolved in [`LIVE-FREESWITCH-PHASE-D.md`](LIVE-FREESWITCH-PHASE-D.md):
> J1 and J2 by code change, and **J3 by evidence — no code change was needed**,
> because the pinned channel identity was measured to remain addressable after
> bridging. Phase D also found a **fourth, more severe defect** this phase could
> not see from the outside: channel commands must be `api`-prefixed, and were
> being rejected outright. Sections 6.5, 6.7 and 7.2 below are retained as the
> record of what was known at the time; the resolutions are in Phase D.
