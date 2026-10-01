# 00 — FreeSWITCH Fundamentals

Written for a developer who has **never** worked with FreeSWITCH. Every
concept follows the same shape:

```text
Concept / What it means / Why this project uses it /
Where it appears in this project / How to inspect it / Common failure
```

---

## 1. What FreeSWITCH is

**Concept.** FreeSWITCH is an open-source, multi-protocol *softswitch* — a
software program that replaces the hardware phone exchanges (PBXs) that
telephone companies and businesses used to buy. It handles telephone calls
completely in software: it accepts calls, decides where they should go,
routes them, plays recorded audio to callers, collects keypresses, and tears
calls down.

**What it means.** A softswitch is the *switching* half of a telephony system.
It is not the application, not the business logic, and not your database. In
this project FreeSWITCH is **only** the telephony/media engine. All business
rules — which contact to call, what campaign they belong to, whether a call
counts as a success, when to retry — live in the Java application.

**Why this project uses it.** A B2B voice/OBD platform needs a real media
engine. FreeSWITCH provides SIP signalling, RTP audio transport, DTMF
detection, and a scriptable control interface (ESL). It is battle-tested and
widely deployed.

**Where it appears.** One container in this repository:
`infra/docker-compose.freeswitch.yml`, image
`ghcr.io/patrickbaus/freeswitch-docker:1.11.1`.

**How to inspect it.**

```text
Command:  fs_cli -x "status"
Purpose:  ask FreeSWITCH whether it is alive and for how long
Success:  first line begins with "UP", e.g.
          "UP 0 years, 0 days, 0 hours, 0 minutes, 50 seconds, ..."
          and "FreeSWITCH (Version 1.11.1-release 64bit) is ready"
Failure:  connection refused, or "Error Connecting", or a non-UP state
When:     first thing to check, always
```

**Common failure.** FreeSWITCH is running and reports `UP`, but nothing works.
`UP` only means the process is alive — it does not mean SIP is working, does
not mean ESL is reachable, and does not mean a gateway is registered. Always
check the specific subsystem.

---

## 2. What SIP is

**Concept.** SIP (Session Initiation Protocol) is the signalling language
computers use to set up, modify and end real-time sessions — mostly voice and
video calls.

**What it means.** SIP is a *text-based* protocol, carried inside HTTP-like
requests and responses, running directly over TCP or UDP. It negotiates a call
and then usually hands the actual audio to a second protocol (RTP, see §18).
SIP itself carries **no audio**.

**Why this project uses it.** SIP is the universal language between
telephony systems, softphones, and carriers. FreeSWITCH speaks it; the
Java platform never speaks it directly — it tells FreeSWITCH what to do over
ESL, and FreeSWITCH does the SIP.

**Where it appears.** Two listening ports in this environment: `5060` (the
`internal` profile, for softphones) and `5080` (the `external` profile, for
gateways).

**How to inspect it.** See [06-SIP.md](06-SIP.md).

**Common failure.** A softphone registers and the platform is healthy, but a
call fails at the "200 OK" stage because SDP (the media description inside
INVITE) advertised a bad IP address. Signalling succeeded; media never
started.

---

## 3. What a SIP profile is

**Concept.** A **Sofia profile** is FreeSWITCH's SIP endpoint configuration. It
defines how FreeSWITCH listens for and handles SIP traffic on a particular set
of ports, with a particular policy.

**What it means.** Think of a profile as "one SIP door into the building",
with its own address, port set, codec list, security rules and DTMF policy.
FreeSWITCH can have many profiles at once. Each independent incoming or
outgoing call is placed onto one profile.

**Why this project uses it.** The Java contract needs two distinct SIP roles:
local endpoints (softphones, agents) and provider/gateway traffic. Expressing
them as two profiles keeps the policies separate and matches FreeSWITCH's own
convention (`internal` for local, `external` for trunks).

**Where it appears.**

| Profile | Port | Role in this project | File |
|---|---|---|---|
`internal` | 5060 | softphone registration, local endpoint testing | `infra/freeswitch/conf/sip_profiles/internal.xml` |
`external` | 5080 | the profile the `fs-gateway` trunk is bound to | `infra/freeswitch/conf/sip_profiles/external.xml` |

**How to inspect it.**

```text
Command:  fs_cli -x "sofia status"
Purpose:  list every Sofia profile and its state
Success:  you see a row like
            internal  profile  sip:mod_sofia@172.25.0.2:5060  RUNNING (0)
          and the summary line "2 profiles 0 aliases"
Failure:  "0 profiles"  -> your profile XML was not recognised at all
          profile listed but not RUNNING -> it failed to bind
When:     whenever SIP is not behaving
```

For detail on one profile:

```text
Command:  fs_cli -x "sofia status profile internal"
Purpose:  full configuration and counters for one profile
Success:  a block with Name, URL, BIND-URL, CODECS IN/OUT, TEL-EVENT,
          DTMF-MODE, REGISTRATIONS etc.
Failure:  "Profile not found" -> the profile does not exist
When:     to check codecs, DTMF payload type, or registration count
```

**Common failure — and this one cost us real time.** A Sofia profile file must
have `<profile name="...">` as its **root element**. If the root is
`<configuration name="sofia.conf">` instead, FreeSWITCH compiles the file
happily, includes it, and then **silently ignores it**. The symptom is
`sofia status` reporting `0 profiles` while the file is obviously present and
valid XML. See [13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md) entry
"Profile silently ignored".

---

## 4. What a SIP gateway is

**Concept.** A **Sofia gateway** is a named, pre-configured route to another
SIP system — a trunk, a carrier, a partner PBX. It carries settings such as
where to send calls (`proxy`), whether to register with the far end, and which
profile it belongs to.

**What it means.** When FreeSWITCH is told to call `sofia/gateway/<name>/<number>`,
it is saying "place this call through the trunk called `<name>`". The gateway
name is a *label* that must exist in FreeSWITCH's configuration, otherwise
FreeSWITCH cannot build the INVITE and the call fails immediately.

**Why this project uses it.** The Java platform builds **every** outbound dial
string as `sofia/gateway/<gateway>/<destination>`, for both the customer leg
and the agent leg. That single fact dictates the whole design: FreeSWITCH can
never dial a locally registered user, because `sofia/gateway/...` only ever
speaks to a configured trunk.

**Where it appears.** `infra/freeswitch/gateway/fs-gateway.xml`, included into
the `external` profile's `<gateways>` block.

**How to inspect it.**

```text
Command:  fs_cli -x "sofia status gateway"
Purpose:  list every gateway with its state
Success:  a row like
            external::fs-gateway  sip:FreeSWITCH@freeswitch-provider:5080  NOREG
          and the summary "1 gateway: ..."
Failure:  "0 gateways"  -> the gateway was not loaded
          "Invalid Gateway!" for a named lookup -> that name is not loaded
When:     whenever a dialled call fails instantly with no SIP activity
```

**Common failure.** `register` defaults to **true**. If a gateway has no
explicit `<param name="register" value="false"/>`, FreeSWITCH tries to REGISTER
against a provider that does not exist, fails, and discards the gateway —
reporting `0 gateways` with no error in the log. See
[13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md) entry "Gateway invisible".

---

## 5. What a channel is

**Concept.** A **channel** is FreeSWITCH's internal representation of one
participating end of one call. Each phone, each leg, each bridge side is a
channel.

**What it means.** Every call in FreeSWITCH is built from channels. A call
between two SIP endpoints has (at least) two channels. FreeSWITCH creates,
drives, and destroys them. Channels are what ESL events describe and what ESL
commands act on.

**Why this project uses it.** The Java platform never creates a FreeSWITCH
concept of its own for a call; it tracks *its own* entities (`CallAttempt`,
`CallSession`, `CallLeg`) and maps them onto FreeSWITCH channels by UUID.

**Where it appears.** There are no channels yet in Phase B — no call has been
placed.

**How to inspect it.**

```text
Command:  fs_cli -x "show channels"
Purpose:  list live channels
When:     during any call investigation
```

**Common failure.** Acting on a channel UUID that has already been destroyed
returns `-ERR` from ESL. This is normal during teardown races, not a fault.

---

## 6. What an A-leg and a B-leg are

**Concept.** Within one call, FreeSWITCH distinguishes two sides:

- the **A-leg** — the side that was *created first*, the caller side
- the **B-leg** — the side that was *bridged to*, the callee side

**What it means.** "Leg" is just industry shorthand for one end of a call.
Every two-party call has an A-leg and a B-leg. The letter is positional
history, not importance: if you originate, your new channel is the A-leg.

**Why this project uses it.** It matters because of a specific FreeSWITCH
behaviour: `uuid_broadcast <uuid> <file> aleg` plays a file **only to the A-leg**
of a call. For an outbound voice campaign, the campaign channel is the A-leg,
and the callee is the B-leg — so "play to A-leg only" means "play to the
customer, not into the bridge". Getting this backwards would play the
customer's audio to the agent.

**Where it appears.** `EslClient.playFile()` issues
`uuid_broadcast <channel> <path> aleg`. The customer leg is always the A-leg
because Java originates it.

**Common failure.** If a dialled channel is bridged *into* a leg rather than
originating it, the campaign channel is no longer the A-leg, and `aleg`
playback lands on the wrong side.

---

## 7. What a UUID is, and what `origination_uuid` means

**Concept.** A UUID (Universally Unique Identifier) is a 128-bit identifier,
usually printed like
`8b55a395-739a-79dc-9565-a11057cc78dc`. FreeSWITCH assigns one channel UUID
per channel. Separately, FreeSWITCH assigns a **Job-UUID** to each background
API job.

**What it means — and this distinction is the single most important one in
this project:**

| Identifier | What it identifies | Lifetime |
|---|---|---|
**Channel UUID** | a real media/SIP channel | as long as the channel exists |
**Job-UUID** | a background task submitted to FreeSWITCH | as long as the task runs |
**SIP Call-ID** | the call within the SIP protocol itself | the whole SIP dialog |
**ESL lock UUID** | a specific connection/command | one command |

**`origination_uuid` is a channel variable that lets the *caller choose* the
channel UUID in advance.** Normally FreeSWITCH invents one. If you supply it,
the channel is guaranteed to have that identity from the instant it exists.

**Why this project uses it.** This is the whole basis of event correlation.
Java originates with `origination_uuid = <CallAttempt.id>`. Therefore:

```text
Java CallAttempt.id
       |
       |  origination_uuid
       v
FreeSWITCH Channel UUID          (chosen by us, known BEFORE the call)
       |
       |  every CHANNEL_* event carries Call-UUID
       v
Java CallAttempt / CallSession / CallLeg
```

Without this, Java would have to learn the channel UUID *after* the fact —
either by polling, or by waiting for a `CHANNEL_CREATE` and guessing which
background job it belonged to. With it, the identity is deterministic and
there is no race.

**Where it appears.** `EslClient.originateWithJob()` builds
`bgapi originate {origination_uuid=<uuid>,origination_caller_id_number=<callerId>}sofia/gateway/<gw>/<dest>`.
The ESL reply's `Job-UUID` header is read but only logged — it is never
persisted as a channel identity.

**How to inspect it.**

```text
Command:  fs_cli -x "originate {origination_uuid=11111111-2222-3333-4444-555555555555}sofia/status"
Purpose:  prove a channel gets the UUID you chose
Success:  "11111111-2222-3333-4444-555555555555" and
          "+OK choppy 11111111-2222-3333-4444-555555555555"
          and "CHANNEL_ANSWER" reported against that same UUID
When:     when diagnosing "Java says the call started but no event arrived"
```

**Common failure.** Confusing `Job-UUID` with the channel UUID. If Java
persisted a Job-UUID, *no* subsequent `CHANNEL_HANGUP` would ever correlate and
every call would look stuck forever.

---

## 8. What ESL is, and the three things it carries

**Concept.** ESL (Event Socket Library) is FreeSWITCH's TCP control and
notification interface. One connection carries three kinds of traffic:

1. **Commands** you send (originate, play a file, hang up, bridge)
2. **Replies** to those commands (`+OK ...` or `-ERR ...`)
3. **Events** FreeSWITCH pushes to you unprompted (a call answered, a call
   ended, a key was pressed)

**What it means.** It is FreeSWITCH's API. It is not SIP, not RTP, and not
telephony — it is how software tells FreeSWITCH what to do and finds out what
happened.

**Why this project uses it.** Because the Java application must be a
*control plane*: it decides policy, FreeSWITCH executes telephony. ESL is the
seam. It is also how Java learns about asynchronous facts (an answer, a
hangup, a DTMF digit) that it cannot request synchronously.

**Where it appears.** `EslClient.java` in the Java platform;
`event_socket.conf.xml` in FreeSWITCH; port 8021.

**How to inspect it.** See [05-ESL.md](05-ESL.md).

**Common failure.** Reading the verdict from the first line of the socket
instead of from the `Reply-Text` header, or assuming the first line of an
event is the event name. Both were real defects in this project's own client
before VB-6E. See [13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md).

---

## 9. What `fs_cli` is

**Concept.** `fs_cli` is FreeSWITCH's command-line client. It connects to
ESL (usually as a second client) and lets you run API commands by hand.

**What it means.** It is your primary hands-on tool. Anything FreeSWITCH can do
that a plugin can do, `fs_cli` can do from a terminal.

**Why this project uses it.** It is how an engineer validates that FreeSWITCH
is healthy, checks a profile, inspects a gateway, and — in Phase E — will
manually exercise ESL before the Java application is connected.

**Where it appears.** Inside the container.

**How to inspect it.** The exact invocation used throughout this project:

```text
Command:  docker exec obd-freeswitch fs_cli -H 127.0.0.1 -P 8021 -p "<password>" -x "status"
Purpose:  run one API command non-interactively and print the result
Success:  the command's output, e.g. "UP 0 years, ..."
Failure:  "[ERROR] fs_cli.c:1699 main() Error Connecting []"  -> ESL is not
          reachable, or the password is wrong
When:     always; this is the first diagnostic for almost every problem
```

On Windows PowerShell, read the password from the git-ignored env file rather
than retyping it:

```text
$pw = (Get-Content ..\infra\.env | Select-String '^FREESWITCH_PASSWORD=').ToString() -replace '^FREESWITCH_PASSWORD=',''
docker exec obd-freeswitch fs_cli -H 127.0.0.1 -P 8021 -p $pw -x "sofia status"
```

**Common failure.** Omitting `-p`. The stock image password was `ClueCon`; this
environment's is a random value in `infra/.env`, so `fs_cli` without `-p`
cannot authenticate. `fs_cli` also needs `-H`/`-P` when the listener is not on
the default local interface.

---

## 10. What Sofia and `mod_sofia` are

**Concept.** **Sofia** is FreeSWITCH's SIP stack — the library that actually
speaks the protocol. **`mod_sofia`** is the loadable module that exposes Sofia
as a FreeSWITCH endpoint.

**What it means.** When you `load mod_sofia`, FreeSWITCH gains the ability to
listen for and place SIP calls. It is the module that owns profiles, gateways,
registrations, INVITEs, and the `sofia` API.

**Why this project uses it.** Because Java dials with `sofia/gateway/...` and
registers softphones on a Sofia profile. There is no way to place a SIP call
in FreeSWITCH without it.

**Where it appears.** Loaded by the stock `modules.conf.xml`; configured by
`sofia.conf.xml`, `sip_profiles/*.xml`, `gateway/fs-gateway.xml`.

**How to inspect it.**

```text
Command:  fs_cli -x "module_exists mod_sofia"
Purpose:  confirm the module is loaded, without guessing
Success:  "true"
Failure:  "false"  -> check modules.conf.xml
When:     before any SIP troubleshooting
```

**Common failure.** The module is loaded but a profile is missing, so the
module answers `true` while no calls are possible. Always follow up with
`sofia status`.

---

## 11. What a dialplan is

**Concept.** A **dialplan** (FreeSWITCH's "XML dialplan") is the rulebook
that decides what happens to an incoming call: which application to run, where
to send the call, whether to answer it.

**What it means.** When a call arrives, FreeSWITCH matches the called number
against patterns in the dialplan and executes the first matching set of
actions. An action might be "answer", "play a file", "hang up with a cause", or
"bridge to a user".

**Why this project uses it — and why it barely does.** The Java platform
performs the *decision* (which campaign, which agent, which playback) and
instructs FreeSWITCH per-channel via ESL. FreeSWITCH therefore does **not**
need a dialplan to express campaign logic. What it does need is a dialplan that
does not interfere: a campaign channel must not be handed to something like
`voicemail` before Java can act on it.

**Where it appears.** The stock `dialplan/` directory is left untouched in this
environment. `context` is set to `public` in both profiles, which is where the
stock dialplan lives.

**Common failure.** For inbound calling, if the dialplan bridges the channel to
an application such as `voicemail` or `voiceportal`, the Java application never
receives the answer event it is waiting for, and the session hangs in its
initial state. This is recorded as a Phase C requirement.

---

## 12. What RTP is, and SIP vs RTP vs ESL

**Concept.** RTP (Real-time Transport Protocol) carries the actual audio
samples between two endpoints. It is a separate protocol from SIP, usually
running over a different UDP port.

**What it means.** Three different transports are in play, and confusing them
is the most common source of wasted debugging:

```text
SIP   = signalling.  "I would like to call you, I can do G711 or PCMU,
                     and my media would arrive at 10.0.0.5 port 30010."
                     Text protocol. Carries NO audio.

RTP   = media.       The audio samples themselves, in real time.
                     Binary, time-sensitive, lossy-tolerant UDP.

ESL   = control.     Java telling FreeSWITCH what to do, and FreeSWITCH
                     telling Java what happened. TCP on 8021.
                     Carries NO audio and no SIP.
```

A call can be perfectly healthy on SIP and completely silent on RTP. Those are
two different failures with two different investigations.

**Why this project uses it.** FreeSWITCH is the media engine, so RTP handling
is delegated to it entirely. Java never touches RTP.

**Where it appears.** Port range 30000-30099 in this environment.

**How to inspect it.** No command exists in FreeSWITCH 1.11 to report the
configured RTP range at runtime — see §13.

**Common failure.** SIP negotiated fine, both sides agreed on G711, and there
is still no audio — because the SDP connection address was `0.0.0.0` or an
address the packets can never reach (very common when one side is behind NAT,
or when Docker networking is misconfigured).

---

## 13. The RTP port range, and why it cannot be proven at rest

**Concept.** FreeSWITCH allocates each media stream a UDP port from a
configured range. The stock range is 16384-32768 — 16,384 ports.

**What it means.** In the stock `switch.conf.xml` these two settings are
**commented out**, so FreeSWITCH uses compiled-in defaults
(`switch_rtp.c`: `#define RTP_START_PORT 16384`). Publishing a 16,384-port
range through Docker is slow and unnecessary for local development, so this
environment narrows it to 100 ports.

**Why this project uses it.** A narrow range limits the host-side exposure
envelope, and container-to-container media never traverses the host at all.

**Where it appears.** `switch.conf.xml` (`rtp-start-port` 30000,
`rtp-end-port` 30099), mirrored by the published UDP port range.

**How to inspect it.** This is the honest answer, and it matters:

```text
Command:  fs_cli -x "global_getvar switch_rtp_start_port"
Purpose:  attempt to read the effective value
Failure:  "-ERR no reply"    <- what actually happens
```

FreeSWITCH 1.11 keeps the range in a C static (`START_PORT`) and exposes
`switch_rtp_get_start_port()`, but **no `fs_cli` API reaches it**. Verified in
this environment:

| Attempted | Result |
|---|---|
`global_getvar switch_rtp_start_port` | `-ERR no reply` |
`global_getvar rtp_start_port` | `-ERR no reply` |
`switch_rtp_get_start_port` (as an API) | `-ERR Command not found!` |

**CONFIRMED:** the values are present in the configuration file that FreeSWITCH
is actually reading. (Proven by temporarily setting `max-sessions` to `42` and
observing `fs_cli -x status` report `42 session(s) max` — a value only that
file can produce — then reverting to `1000`.)

**EXPECTED:** allocated ports fall inside 30000-30099.
**NOT YET TESTED:** actual allocation. Confirmed at the first live call in a
later phase.

**Common failure — for you, the engineer.** Do not write "RTP range verified"
because the config file looks right. It is verified only when a channel has
actually been allocated a port.

---

## 14. What media is, and where audio comes from

**Concept.** "Media" in FreeSWITCH means the audio (and potentially video)
payload of a call, together with the codecs used to encode it.

**What it means.** When the Java platform wants to play a recording to a
caller, it does not stream audio itself. It tells FreeSWITCH "play this file on
this channel", and FreeSWITCH reads the file, encodes it, and sends it as RTP.

**Why this project uses it.** The Java application stores audio on its own
filesystem and must tell FreeSWITCH a path **that FreeSWITCH can actually
open**. A path like `audio/<tenant>/<asset>/file.wav` is meaningful only to the
application. `MediaUriResolver` converts it into an absolute path inside
FreeSWITCH's filesystem.

**Where it appears.**

```text
backend/data/audio            (host: the application's local audio store)
     |  Docker bind mount, read-only
     v
/media/obd                    (inside FreeSWITCH: a path FreeSWITCH can open)
     |
     v
uuid_broadcast <channel> /media/obd/<tenant>/<asset>/<file>.wav aleg
```

**How to inspect it.** See [07-RTP-AND-MEDIA.md](07-RTP-AND-MEDIA.md).

**Common failure.** Java sends a logical reference, FreeSWITCH cannot open it,
and the only symptom is a `PLAYBACK_ERROR` event much later. The volume mount
is the usual culprit. This environment makes the entrypoint **fail the
container** if `/media/obd` is missing, so a broken mount is caught at startup
rather than mid-campaign.

---

## 15. What DTMF is

**Concept.** DTMF (Dual-Tone Multi-Frequency) is the keypad tone system —
the beeps for `0`-`9`, `*`, `#`. It is how a phone communicates what the
caller pressed.

**What it means.** The tones can travel three ways:

| Method | How it travels | Notes |
|---|---|---|
**RFC 2833 / 4733** ("rfc2833" in FreeSWITCH) | as special packets inside the RTP stream | what carriers use; requires the media path to work |
**SIP INFO** | as a SIP `INFO` request during the call | out-of-band; works even with no media |
**in-band** | as audible tones in the audio itself | rarely used, fragile |

**Why this project uses it.** Java collects DTMF. Critically, it does so
**passively**: it plays a prompt, then waits for FreeSWITCH to *report* digits
as events. Java never asks FreeSWITCH to collect digits.

**Where it appears.** `CHANNEL_DTMF` ESL events, header `DTMF-Digit`. In
FreeSWITCH, profiles are configured with `liberal-dtmf=true` and
`proxy-info=true`.

**How to inspect it.**

```text
Command:  fs_cli -x "sofia status profile internal"
Purpose:  see the negotiated DTMF policy
Success:  "DTMF-MODE  rfc2833" and "TEL-EVENT  101"
          (liberal-dtmf and proxy-info do not appear here - see the config)
When:     before a DTMF test
```

**Common failure.** With the stock `liberal-dtmf=false`, FreeSWITCH accepts DTMF
only via the method the profile prefers. An endpoint that sends SIP INFO when
FreeSWITCH expects RFC 2833 is silently ignored, and Java sees nothing. That
silence is the signature of this problem.

---

## 16. What a codec is

**Concept.** A **codec** is an algorithm that encodes or decodes audio, for
example PCMU (G.711 µ-law) or PCMA (G.711 A-law).

**What it means.** Codecs are negotiated during call setup — both sides
advertise what they support and one is chosen. If no common codec exists, the
call cannot have media.

**Why this project uses it.** FreeSWITCH must not be the only thing in the
path that constrains codecs, or test results become unrepresentative. PCMU and
PCMA first, then G722 and OPUS, is the common denominator for softphones,
carriers and test tooling.

**Where it appears.** `inbound-codec-prefs` and `outbound-codec-prefs` in both
profiles.

**How to inspect it.**

```text
Command:  fs_cli -x "sofia status profile internal"
Purpose:  see the codec preference list
Success:  "CODECS IN   PCMU,PCMA,G722,OPUS" and
          "CODECS OUT  PCMU,PCMA,G722,OPUS"
When:     when a call connects but there is no audio
```

**Common failure.** A call connects, SDP is exchanged, and there is still no
audio because the two sides picked different codecs — or because a
`telephone-event` payload type for DTMF was not agreed, so keys are lost even
though voice works.

---

## 17. What a bridge is

**Concept.** A **bridge** is the connection FreeSWITCH maintains between two
channels, carrying media between them.

**What it means.** When an agent's leg is joined to a customer's leg, FreeSWITCH
creates a bridge between the two channels. Audio and DTMF then flow across it.

**Why this project uses it.** `CONNECT_BY_AGENT` requires Java to place a
customer leg, place an agent leg, and join them. FreeSWITCH's `uuid_bridge`
does the joining.

**Where it appears.** `uuid_bridge <caller-uuid> <agent-uuid>` — the **caller
leg is the anchor (A-leg)**. Java confirms the bridge from the `CHANNEL_BRIDGE`
event, not from the command's success.

**How to inspect it.**

```text
Command:  fs_cli -x "show bridges"
Purpose:  list active bridges and their two channel UUIDs
When:     during a CONNECT_BY_AGENT test
```

**Common failure.** The bridge command returning `+OK` does **not** mean the
bridge is established. Only a `CHANNEL_BRIDGE` event with the expected
`Bridge-B-Unique-ID` proves it. Treating command success as confirmation is a
correctness bug, not a cosmetic one.

---

## 18. What hangup causes are

**Concept.** A **hangup cause** is the standard reason a call ended. The
numbers are ITU-T Q.850 cause codes; SIP also has its own response codes, and
FreeSWITCH translates between them.

**What it means.** When a call ends, FreeSWITCH attaches a symbolic cause name
to the `CHANNEL_HANGUP` event. This Java platform maps those names onto its own
business vocabulary.

**Why this project uses it.** Retries, billing and campaign outcomes all depend
on *why* a call failed. `busy` and `no answer` are worth retrying; `rejected`
usually is not; `normal clearing` is a success.

**Where it appears.** `CHANNEL_HANGUP` event, header `Hangup-Cause` (symbolic,
e.g. `USER_BUSY`) and `Hangup-Cause-Code` (numeric, e.g. `17`). The Java
platform reads the **symbolic** header.

**How to inspect it.** During a live call, on the `CHANNEL_HANGUP` event. In
Phase B there are no calls, so this is **NOT YET TESTED** against a real SIP
endpoint.

**Common failure.** Expecting the numeric Q.850 code. FreeSWITCH's
`Hangup-Cause` header is the symbolic name. Reading the wrong header yields
`HANGUP_UNKNOWN` for every call and silently poisons retry decisions.

---

## 19. What gateway registration states mean, and what `NOREG` means *here*

**Concept.** A gateway can either be a *static* trunk (you dial out through it,
it never registers) or a *dynamic* trunk (it logs in to the far PBX with
credentials, like a softphone).

FreeSWITCH reports a gateway's state. Common values:

| State | Meaning |
|---|---|
`NOREG` | this gateway is not registered, and is not trying to |
`UP` / `REG` | registered successfully |
`DOWN` | was registered, registration lost |
`TRYING` | attempting registration |
`REJECTED` | the far end rejected our credentials |

**What `NOREG` means in THIS environment, specifically.** `fs-gateway` is a
static, origination-only trunk. It has `register=false`, so FreeSWITCH never
sends a REGISTER for it, and there is no provider behind it in Phase B. `NOREG`
is therefore the **correct and desired** state, not a fault.

`fs_cli -x "sofia status gateway fs-gateway"` also reports `Status UP` and
`Password no`, which together confirm: the gateway object is healthy, it is
registered-by-design, and it holds no credentials.

**Why this matters.** It is very easy to mistake a deliberate `NOREG` for a
broken gateway and start "fixing" a non-problem. Equally, a gateway that
*silently* failed to load also looks like absence, not like an error — which is
why Phase B documents the `register` default trap.

**Common failure.** Adding a `register` parameter to a gateway that has no
credentials and no provider. FreeSWITCH attempts registration, fails, and
**discards the gateway entirely** — the gateway disappears rather than showing
an error. See [13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md).

---

## 20. Quick glossary

| Term | One-line meaning |
|---|---|
**Channel** | one participating end of a call inside FreeSWITCH |
**A-leg / B-leg** | the first-created and the bridged-to side of a call |
**Profile** | a named SIP listener with its own ports and policy |
**Gateway** | a named outbound route to another SIP system |
**Leg** | industry term for one end of a call |
**UUID** | 128-bit identifier; the channel identity |
**Job-UUID** | identifier of a background API task — *not* a channel |
**origination_uuid** | the channel variable that lets you choose the channel UUID |
**ESL** | FreeSWITCH's TCP control/event interface (port 8021) |
**fs_cli** | command-line client for FreeSWITCH's API |
**Sofia** | FreeSWITCH's SIP stack |
**mod_sofia** | the module that exposes Sofia as a FreeSWITCH endpoint |
**mod_event_socket** | the module that implements ESL |
**Dialplan** | rules deciding what happens to an incoming call |
**SIP** | signalling protocol; carries no audio |
**RTP** | Real-time Transport Protocol; carries the audio |
**SDP** | the part of SIP that describes media capabilities and addresses |
**Media** | the audio/video payload of a call |
**Codec** | the audio encoding algorithm (PCMU, PCMA, G722, OPUS) |
**DTMF** | keypad tones; via RFC 2833/4733, SIP INFO, or in-band |
**telephone-event** | the RTP payload type carrying DTMF in RFC 2833 |
**Bridge** | the media connection between two channels |
**Hangup cause** | the standard reason a call ended (Q.850 / SIP) |
**Registration** | a softphone or gateway logging in to a SIP server |
**INVITE / ACK / BYE / CANCEL / INFO** | SIP methods: start, confirm, end, cancel, in-call |
**`+OK` / `-ERR`** | ESL and CLI success and failure verdicts |
