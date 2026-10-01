# 05 — ESL (Event Socket Library)

The complete ESL contract as this project uses it. This is the document to
read before debugging anything that involves the Java application.

---

## 1. What ESL is, and what it is not

ESL is a **text-based, line-oriented protocol over TCP** (port 8021 here).
A client connects, authenticates, optionally subscribes to events, and then
issues commands.

```text
                    NOT ESL                     IS ESL
  ┌──────────────────────────────────┬────────────────────────────────┐
  │ SIP  signalling between phones   │ Java -> FreeSWITCH commands    │
  │ RTP  audio between phones        │ FreeSWITCH -> Java events      │
  │ Dialplan rules inside FreeSWITCH │ FreeSWITCH -> Java events      │
  └──────────────────────────────────┴────────────────────────────────┘
```

ESL carries **no audio** and **no SIP**. If you are chasing an audio problem,
ESL will tell you a channel exists and what state it is in; it will not tell
you why nobody can hear.

---

## 2. Connection and authentication

The handshake is **server-speaks-first**. This trips up almost every new
implementation:

```text
  Client                                FreeSWITCH
    |                                       |
    |----------- TCP connect --------------->|
    |                                       |
    |  <----- "Content-Type: auth/request" -|   unprompted banner
    |         (blank line terminates it)     |
    |                                       |
    |------- "auth <password>" ------------->|
    |                                       |
    |  <----- "Content-Type: command/reply"-|
    |         "Reply-Text: +OK accepted"     |
    |                                       |
    |  connection is now command-ready       |
```

Three rules that are easy to get wrong:

1. **Read the banner before sending `auth`.** FreeSWITCH sends it unprompted.
   If you send `auth` first, your subsequent read consumes the banner and
   authentication appears to fail for no reason.
2. **The verdict is in the `Reply-Text` *header*, not the first line of text.**
   A `+OK` reply is two lines: `Content-Type: command/reply` and
   `Reply-Text: +OK accepted`. Testing `startsWith("+OK")` on the first line
   never matches.
3. **A message is a header block terminated by a blank line, then exactly
   `Content-Length` characters of body.** Not "until a blank line" for events —
   event bodies can contain blank-looking content, and under-reading or
   over-reading desynchronises the whole stream permanently.

**This project's history matters here.** All four of the above were defects in
this repository's own `EslClient` before VB-6E, and each was invisible to the
test suite because the tests mocked the client rather than speaking the
protocol. See [13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md).

---

## 3. Event subscription

```text
Command:  event plain CHANNEL_CREATE CHANNEL_PROGRESS CHANNEL_PROGRESS_MEDIA
          CHANNEL_ANSWER CHANNEL_DTMF CHANNEL_HANGUP PLAYBACK_START
          PLAYBACK_STOP PLAYBACK_ERROR CHANNEL_BRIDGE
Reply:    +OK 0.0ms
```

`event plain` (as opposed to `event json`) delivers events as
`text/event-plain` frames. In a plain event:

* the **first line** is `Content-Type: text/event-plain`
* the **second line** is `Content-Length: <n>`
* then a blank line
* then the body: `Event-Name: <NAME>` followed by every event header

**`Event-Name` lives in the body, not the first line.** Comparing the first
socket line against subscribed event names drops every event. This is the
fourth historical defect in this project.

### The ten events this project subscribes to

| Event | Headers Java reads | Meaning | Present? |
|---|---|---|---|
`CHANNEL_CREATE` | `Call-UUID`, `Call-Direction`, `Caller-Destination-Number`, `Caller-Caller-ID-Number` | a channel appeared (used to detect inbound) | event **CONFIRMED - LOCAL**; `Call-UUID` **does not exist**, see section 9.1 |
`CHANNEL_PROGRESS` | `Call-UUID` | far end is being alerted | event EXPECTED; `Call-UUID` absent |
`CHANNEL_PROGRESS_MEDIA` | `Call-UUID` | early media (a ringback tone) | event EXPECTED; `Call-UUID` absent |
`CHANNEL_ANSWER` | `Call-UUID` | the call was answered | event **CONFIRMED - LOCAL**; `Call-UUID` absent |
`CHANNEL_DTMF` | `Call-UUID`, **`DTMF-Digit`** | a key was pressed | **NOT YET TESTED** |
`CHANNEL_HANGUP` | `Call-UUID`, **`Hangup-Cause`** | the channel ended, and why | event **CONFIRMED - LOCAL**; `Call-UUID` absent, `Hangup-Cause` confirmed |
`PLAYBACK_START` | `Call-UUID` | audio playback began | event **CONFIRMED - LOCAL**; `Call-UUID` absent |
`PLAYBACK_STOP` | `Call-UUID` | audio playback completed | event **CONFIRMED - LOCAL**; `Call-UUID` absent |
`PLAYBACK_ERROR` | `Call-UUID`, `Playback-Error` | audio playback failed | **event NOT emitted for a missing file**, see section 9.4 |
`CHANNEL_BRIDGE` | `Call-UUID`, **`Bridge-B-Unique-ID`** | two channels were bridged | event **CONFIRMED - LOCAL**; `Call-UUID` absent |

> **The `Call-UUID` column above describes what the Java code reads, not what
> FreeSWITCH sends.** Phase C established that this header does not exist on any
> of these events. The channel UUID is carried by `Channel-Call-UUID` and by
> `Unique-ID`. See section 9.1 and defect **J1**. This table is left showing the
> code's intent so the mismatch stays visible, rather than being edited to look
> correct.

### Events this project does NOT subscribe to

`CHANNEL_DESTROY`, `CHANNEL_UNBRIDGED`, `CUSTOM`, `HEARTBEAT`, `SOFTEXECUTE`,
`RE_SCHEDULE`, `SESSION_PROGRESS`, `SIP_GATEWAY_STATE`.

Two practical consequences:

* **You cannot use ESL to observe a bridge ending.** Use `fs_cli -x "show
  bridges"` or the console log.
* **There is no `CUSTOM` channel to the application.** If a future design needs
  FreeSWITCH to call back into Java beyond these ten events, that mechanism
  does not exist yet. Recorded as a known gap.

### Header casing is load-bearing

`EslEvent` in this project is a plain `HashMap` with **case-sensitive** lookup,
and `EslClient` copies event headers verbatim from the body. So FreeSWITCH's
canonical spelling must be used exactly:

```text
Call-UUID        (not call-uuid)
Hangup-Cause     DTMF-Digit     Bridge-B-Unique-ID
Call-Direction   Caller-Destination-Number   Caller-Caller-ID-Number
Playback-Error
```

**NOT YET TESTED** against a live server. The planned verification is to
capture one verbatim `text/event-plain` frame per event type from the real
FreeSWITCH and diff the header names against this table. If a header is missing
or differently cased, that is recorded as a finding — the Java side is **not**
changed to accommodate it without that evidence.

---

## 4. Commands used by this project

### 4.1 `bgapi originate` — placing a call

```text
bgapi originate {origination_uuid=<uuid>,origination_caller_id_number=<callerId>}
               sofia/gateway/<gateway>/<destination>
```

| Part | Meaning |
|---|---|
`bgapi` | run the command in the background and reply immediately |
`origination_uuid=<uuid>` | **choose the channel UUID yourself** — this is the whole correlation strategy |
`origination_caller_id_number` | the CLI the far end sees |
`sofia/gateway/<gw>/<dest>` | dial through a named gateway. A profile is **never** named here; the profile comes from the gateway's own `sofia-profile` setting |

Reply:

```text
Content-Type: command/reply
Reply-Text: +OK Job-UUID: 7c3f0e5a-...
```

`bgapi` means the `+OK` confirms only that the *task was accepted*. It says
nothing about whether the call connected, rang, or was answered. A `Job-UUID`
is **not** a channel.

### 4.2 `uuid_broadcast` — playing audio

```text
uuid_broadcast <channel-uuid> <absolute-file-path> aleg
```

* The path must be one **FreeSWITCH can open**. This is why
  `MediaUriResolver` converts the application's logical reference into an
  absolute path (see [07-RTP-AND-MEDIA.md](07-RTP-AND-MEDIA.md)).
* `aleg` means **A-leg only** — the customer side, not into the bridge. For an
  outbound campaign the campaign channel is the A-leg, so this is "play to the
  customer".
* `+OK` means the command was accepted, **not** that playback finished.
  Completion is `PLAYBACK_STOP`; failure is `PLAYBACK_ERROR`.

### 4.3 `uuid_kill` — ending a channel

```text
uuid_kill <channel-uuid> NORMAL_CLEARING
```

```text
⚠  TERMINATES CALLS — this drops a live call leg immediately. It is how the
    platform enforces max call duration and how it tears down after playback.
    The `NORMAL_CLEARING` cause is deliberate: the platform treats a normal
    release as successful completion, so the cause must describe the intent.
    Using a different cause changes the recorded outcome.
```

### 4.4 `uuid_bridge` — joining two channels

```text
uuid_bridge <caller-channel-uuid> <agent-channel-uuid>
```

The **first** UUID is the anchor. This project always passes the caller leg
first. Confirmation is the `CHANNEL_BRIDGE` event, not this command's reply.

---

## 5. The three UUIDs — Job-UUID, Channel UUID, origination_uuid

This is the concept most often got wrong, and it is the fourth historical
defect in this project's own client.

```text
  Job-UUID          Identifies a BACKGROUND TASK FreeSWITCH accepted.
                    Short-lived. Belongs to the API layer, not to a call.
                    ⚠ NEVER persist this as a call identity.

  Channel UUID      Identifies a real media/SIP channel.
                    Lives as long as the channel. This is what every
                    CHANNEL_* event reports as Call-UUID.

  origination_uuid  A channel VARIABLE. Setting it makes FreeSWITCH use YOUR
                    value as the channel UUID instead of generating one.
                    This is how the platform makes channel identity
                    deterministic before the call is placed.
```

### Why the platform supplies the UUID itself

```text
  Without origination_uuid:
    Java originate -> gets back only a Job-UUID
                    -> must poll, or wait for CHANNEL_CREATE,
                       and guess which job that channel belonged to
                    -> RACY. A fast call can be over before the guess lands.
                    -> a lost event means the attempt hangs forever.

  With origination_uuid = <CallAttempt.id>:
    Java originate -> the channel UUID is known BEFORE the call
                    -> every later event correlates by exact equality
                    -> no polling, no race, no extra table
```

The cost is a constraint: the value must be a valid UUID, and it must be unique
per call attempt. Using the `CallAttempt` primary key satisfies both.

**How to verify it by hand** (safe, makes no outbound call if pointed at
`sofia/status`):

```text
Command:  fs_cli -x "originate {origination_uuid=11111111-2222-3333-4444-555555555555}sofia/status"
Success:  the reply contains "11111111-2222-3333-4444-555555555555"
          and FreeSWITCH emits CHANNEL_ANSWER for that same UUID
```

---

## 6. Correlation chain

```text
  CallAttempt.id  (Java, a UUID)
        │
        │  placed as  origination_uuid=  in the bgapi originate
        v
  FreeSWITCH Channel UUID          <-- chosen by us, not generated
        │
        │  appears as  Call-UUID:  in every CHANNEL_* / PLAYBACK_* event
        v
  Java  E -> call_attempts.provider_call_id
            call_sessions.provider_call_id
            call_legs.provider_call_id   (agent leg has its own)
        │
        │  matched in this order (agent leg first, because an agent channel
        │  never matches an attempt):
        v
  call_attempts  ->  call_sessions  ->  call_legs
```

**Which Java service does this?** `EslEventService.processEvent` is the single
entry point. Its resolution order is deliberate:

1. look for a call **session** by provider call id;
2. on a miss, look for an **AGENT leg** by provider call id — checked *before*
   the miss-return, because agent channels never match an attempt;
3. handle `CHANNEL_BRIDGE` by `Bridge-B-Unique-ID`, which likewise never
   matches an attempt;
4. otherwise look for a **call attempt** by provider call id;
5. otherwise, for sessions with no attempt, delegate to the inbound or
   agent-originated-outbound boundary.

If you are adding a new event type, it goes in that order. Skipping step 2 or 3
is how agent events silently get dropped.

---

## 7. Replies, and how failures surface

Every command reply carries its verdict in the **`Reply-Text` header**:

| Reply-Text prefix | Meaning |
|---|---|
`+OK` | the command was accepted. **For `bgapi` this means the task was queued, not that the call connected.** |
`-ERR` | the command was rejected. The rest of the line says why. |

**A reply with no verdict at all is itself an error.** A well-behaved client
treats a missing `Reply-Text` as a failure rather than assuming success. This
project's client does.

**If Java cannot connect at all** you get an exception rather than a reply:
`Authentication rejected`, `Connection timeout`, `Failed to connect`. The
platform maps these to "provider unavailable" rather than to a call failure,
because no call was ever attempted.

---

## 8. Timeouts and connection lifetime

Two timeouts govern ESL, from `FreeSwitchProperties`:

| Setting | Default | Governs |
|---|---|---|
`connectTimeoutSeconds` | 10 | establishing the TCP connection |
`commandTimeoutSeconds` | 30 | socket read timeout |

**Connection lifetime is short by design.** Every command in this project
opens a new `EslClient`, authenticates, issues one command, and closes. Only
the event subscription is long-lived, held by `EslEventScheduler`.

Two consequences worth knowing when debugging:

* **An idle event connection will look dead.** The socket read timeout is 30 s,
  and a quiet call produces no events. The client catches that timeout and
  continues rather than treating it as a failure. You will see nothing in the
  log for a silent call, and that is correct.
* **There is no `linger` and no `HEARTBEAT`.** A connection that is silently
  broken is not detected until something is written. This affects failure
  detection, not correctness, at this scale.

---


---

## 9. PHASE C: what the real event stream actually contains

Everything in this section is **observed**, captured from a live call on this
installation with unfiltered header dumps. It supersedes the predictions in
sections 3 and 6 where they disagree, and it is the reason two Java-side defects
are now known.

**How it was captured:** `tools/freeswitch-harness/c6_headers_verbatim.py` and
`c6_hangup_playback_dtmf.py` print the **complete** header set of every event of
interest, with nothing filtered and no casing assumed.

### 11.1 There is no `Call-UUID` header

This is the single most important correction in this file.

| Predicted (Phase B) | Observed (Phase C) |
|---|---|
| the channel UUID is reported as `Call-UUID` | **absent on every event type** |

The channel UUID is present, under five different names:

```text
Unique-ID          = 348eecfb-7d68-48f8-a566-3cac24e4a8cc
Channel-Call-UUID  = 348eecfb-7d68-48f8-a566-3cac24e4a8cc
Caller-Unique-ID   = 348eecfb-7d68-48f8-a566-3cac24e4a8cc
variable_call_uuid = 348eecfb-7d68-48f8-a566-3cac24e4a8cc
variable_uuid      = 348eecfb-7d68-48f8-a566-3cac24e4a8cc
Call-UUID          = ABSENT
```

Verified absent on `CHANNEL_CREATE`, `CHANNEL_ANSWER`, `PLAYBACK_START`,
`PLAYBACK_STOP` and `CHANNEL_HANGUP`.

`Unique-ID` and `Channel-Call-UUID` were present on **all five**, and are the
headers to read. `Unique-ID` is the more conventional choice and is what
FreeSWITCH's own tooling uses.

### 11.2 Confirmed header names and values

For `CHANNEL_ANSWER` on a real outbound call, verbatim:

| Header | Observed value | Note |
|---|---|---|
| `Event-Name` | `CHANNEL_ANSWER` | lives in the **body** for `text/event-plain` |
| `Unique-ID` | the channel UUID | |
| `Channel-Call-UUID` | same as `Unique-ID` | |
| `Answer-State` | `answered` | `ringing` / `early` / `answered` / `hangup` |
| `Channel-State` | `CS_CONSUME_MEDIA` | core state machine name |
| `Channel-Name` | `sofia/internal/1002%40172.25.0.2` | **URL-encoded**: `@` is `%40` |
| `Caller-Destination-Number` | `1002` | the dialled number |
| `Caller-Caller-ID-Number` | `%2B15551230000` | **URL-encoded**: `+` is `%2B` |
| `Channel-Read-Codec-Name` | `PCMU` | |
| `Channel-Read-Codec-Rate` | `8000` | |
| `variable_local_media_port` | `30070` | the allocated RTP port |
| `variable_sip_profile_name` | `internal` | |
| `variable_origination_uuid` | the value Java supplied | proof the pin took effect |
| `Event-Sequence` | monotonic per switch | useful for ordering |

For `CHANNEL_HANGUP`:

| Header | Observed value |
|---|---|
| `Unique-ID` | the channel UUID |
| `Hangup-Cause` | `NORMAL_CLEARING` |
| `Answer-State` | `hangup` |

**Header values are URL-encoded.** `%2B` for `+`, `%40` for `@`, `%20` for
space. A caller-ID comparison that expects a literal `+15551230000` will not
match. This is not documented in FreeSWITCH's own headers, and it was found by
reading the raw frames.

### 11.3 `CHANNEL_DTMF`: still NOT YET TESTED

The endpoint's dialplan calls `send_dtmf` immediately after `answer`, but at that
point the legs are not yet bridged, so there is no RTP stream for RFC 4733
in-band events to travel on. No `CHANNEL_DTMF` event was ever observed.

The **predicted** shape, to be confirmed rather than assumed next time:

| Header | Predicted |
|---|---|
| `DTMF-Digit` | the character: `0`-`9`, `*`, `#` |
| `DTMF-Duration` | milliseconds the tone was held |
| `Unique-ID` | the channel UUID (not `Call-UUID`) |

**Do not treat this table as confirmed.** It is what the section 3 table predicts,
corrected only for the `Unique-ID` finding in 11.1.

### 11.4 There is no `PLAYBACK_ERROR` event for a missing file

Phase B listed `PLAYBACK_ERROR` / `Playback-Error` as the mechanism for detecting
a failed playback. **It does not exist for a missing file.** Observed:

```text
uuid_broadcast <uuid> /media/obd/phase-c-deliberately-absent.wav aleg
  reply        -> +OK Message sent        <- the command SUCCEEDS
  PLAYBACK_ERROR -> not emitted
  PLAYBACK_START -> not emitted           <- playback never began
  log          -> [WARNING] mod_sndfile.c:281 Error Opening File
                  [/media/obd/phase-c-deliberately-absent.wav]
                  [System error : No such file or directory.]
```

A **successful** playback of a real file produces `PLAYBACK_START` then
`PLAYBACK_STOP`, both with `Unique-ID` and no `Playback-Error` header.

So the reliable signal for "playback did not happen" is the **absence** of
`PLAYBACK_START`/`PLAYBACK_STOP` within an expected window. A client that waits
for a `Playback-Error` header will wait forever.

### 11.5 `api` returns its result in the body, not in `Reply-Text`

Section 7 says every reply carries its verdict in `Reply-Text`. That is true for
**command-level** replies and **false for `api`**:

| Command | `Reply-Text` | Result location |
|---|---|---|
| `auth <password>` | `+OK accepted` | - |
| `event plain ...` | `+OK event listener enabled plain` | - |
| `bgapi <cmd>` | `+OK Job-UUID: <uuid>` | - |
| `api status` | **(empty)** | the **body**, `Content-Type: api/response` |
| `api sofia status` | **(empty)** | the body |

```text
api status    -> Reply-Text: ''   body: 'UP 0 years, 0 days, 1 hours, ...'
bgapi status  -> Reply-Text: '+OK Job-UUID: 77898af7-2b90-4ed6-85fb-2f114063d509'
```

A client that reads only `Reply-Text` will conclude that every diagnostic command
returned nothing. Note also that **a bare API name is not a valid inbound
command** - `status` alone returns `-ERR command not found`; it must be
`api status`.

### 11.6 `+OK Job-UUID` is not evidence of a call

Reinforcing section 7 with a hard observation, because it is the easiest mistake
to make when writing tests:

```text
bgapi originate {origination_uuid=<uuid>}user/1002
  reply    -> +OK Job-UUID: f1d32e2f-...
  channels -> 0 total
  log      -> [WARNING] switch_ivr_originate.c:2324 No origination URL specified!
```

The command was accepted. The task then failed, silently, with no feedback to the
client. **The only trustworthy success signals are:**

1. `CHANNEL_CREATE` whose `Unique-ID` equals the pinned `origination_uuid`
2. followed by `CHANNEL_ANSWER` for that same UUID

### 11.7 Do not multiplex commands and events on one socket

Observed failure mode: a synchronous `api` call on a connection that is *also*
receiving subscribed events can read the **next message**, which may be an event
rather than the command's reply. The real reply is then still queued, and every
subsequent read is off by one frame. The symptom is a command that simply times
out, with the switch entirely healthy.

**Use a separate connection for commands.** The Phase C harness opens one
connection for the event subscription and a second for commands, and the
timing-out behaviour disappears.

This project's Java client opens a connection per command already, so it is not
exposed - but it is a trap for anyone extending the client to a shared,
long-lived connection.

### 11.8 The pinned `origination_uuid` does not survive a dialplan transfer

`origination_uuid` correctly becomes the channel UUID at originate time, visible
as `variable_origination_uuid` in `CHANNEL_CREATE`. But when the dialplan
**transfers** the channel, a new channel with a new UUID is created and the
original is gone:

```text
EXECUTE [depth=0] ... transfer(1002 XML default)
[NOTICE] switch_ivr.c:2303 Transfer sofia/internal/+15551230000@172.25.0.2 to XML[1002@default]
```

A later `uuid_broadcast` or `uuid_kill` addressed to the original UUID targets a
channel that no longer exists. This is why the platform should not rely on the
stock dialplan, and is the main reason the next phase replaces it.

### 11.9 `user/...` is not a valid originate URL

```text
Cannot create outgoing channel of type [1002@{$dialed_domain}]
  cause: [CHAN_NOT_IMPLEMENTED]
Cannot create outgoing channel of type [user] cause: [CHAN_NOT_IMPLEMENTED]
```

`{$dialed_domain}` is **unexpanded** - it is a dialplan variable, not a channel
variable, so it is empty at originate time. `user/<ext>` is a *dialplan* target,
resolved through the directory's `dial-string` parameter, and cannot be used as
an originate URL.

Measured matrix of dial strings against a registered, in-network extension:

| Dial string | Channel created | Answered | Bridged |
|---|---|---|---|
| `user/1002` | no | - | - |
| `user/1002 &park()` | no | - | - |
| `user/1002 1002` | no | - | - |
| `user/1002 &park() 1002` | no | - | - |
| `sofia/internal/1002@172.25.0.2 &park()` | **yes** | **yes** | **yes** |
| `loopback/dial/user/1002` | yes | no | no |

`sofia/internal/...` is the working local form and is the closest analogue of
the production `sofia/gateway/<gw>/<dest>` - same shape, a short dial string
resolved through a profile.

### 11.10 Connection and authentication, observed

```text
<- Content-Type: auth/request
-> auth <password>
<- Reply-Text: +OK accepted
-> event plain CHANNEL_CREATE CHANNEL_ANSWER ...
<- Reply-Text: +OK event listener enabled plain
```

The banner is **unprompted**: the server speaks first, before the client sends
anything. A client that writes its `auth` immediately on connect, without reading
first, will have its command interleaved with the banner.

### 11.11 Open Java defects found by this section

| # | Defect | Location | Evidence |
|---|---|---|---|
| **J1** | `getCallUuid()` reads `Call-UUID`, which does not exist | `EslEvent.java:40` | 11.1 |
| **J2** | Reads `Playback-Error`, which is never emitted | `EslEventService.java:363` | 11.4 |

**J1 is severe.** Because every event is correlated by channel UUID, and the
header read is always `null`, no event correlates to a `CallAttempt` and the
`EslEventService` warning fires on every event. Suggested fix: read
`Channel-Call-UUID`, falling back to `Unique-ID`.

**J2** means a missing audio file is undetectable by the current code, and the
call appears to play nothing indefinitely.

Neither was fixed in Phase C: the Java application is a separate workstream, and
this phase's remit is validation. Both are recorded in
`docs/LIVE-FREESWITCH-PHASE-C.md` section 7.2.

---

## 10. Security

ESL is **full control of the switch**. Treat an ESL credential as equivalent to
shell access on the host. See [14-SECURITY.md](14-SECURITY.md) for the full
argument; the short version:

* bound to loopback inside the container, published to `127.0.0.1` on the host;
* never on `0.0.0.0`;
* a locally generated 32-byte random secret, in a git-ignored file, re-rendered
  on every start;
* the entrypoint **refuses to start** on the stock default, the platform's dev
  default, the `.env.example` placeholder, or anything under 16 characters;
* additionally restricted by an ACL so that even a loopback peer from an
  unexpected address is rejected.

---

## 11. Troubleshooting ESL

| Symptom | Most likely cause | Check |
|---|---|---|
`fs_cli` says `Error Connecting []` | wrong password, or ESL not listening | `netstat -tlnp \| grep 8021`; confirm the `-p` value matches `infra/.env` |
Container healthy, Java sees no events | ACL too tight, or a subscription was never made | `grep apply-inbound-acl` in the rendered config; confirm `event plain` was sent |
Java authenticates then every command fails | `mod_commands` not loaded | `fs_cli -x "module_exists mod_commands"` |
Events arrive but `Call-UUID` never matches | the channel UUID is not the one Java chose | was `origination_uuid` actually sent? inspect the raw frame |
Garbled / merged events | framing is wrong — body length not honoured | see §2 rule 3; compare against `Content-Length` |
A command returns `-ERR` with no useful text | an error whose detail is not in the reply | run the same command in `fs_cli` by hand for a better message |
The secret appears in a config file where it should not | the substitution token appeared more than once in the template | see [13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md) |

### Getting the password without retyping it

```powershell
# Windows PowerShell
$pw = (Get-Content ..\infra\.env | Select-String '^FREESWITCH_PASSWORD=').ToString() -replace '^FREESWITCH_PASSWORD=',''
docker exec obd-freeswitch fs_cli -H 127.0.0.1 -P 8021 -p $pw -x "status"
```

Do not paste the password into a terminal you are about to share, a screenshot,
or a chat transcript. It was rotated once during Phase B for exactly that
reason.

---

## 12. PHASE D: the command contract, and the defect a double could not find

Sections 4 and 9 above describe the commands the platform issues. Phase D ran
them against the live switch and found that two of them were being **rejected**.

### 12.1 ESL accepts a fixed set of inbound commands

ESL does not accept arbitrary commands. The inbound set is small:

```text
api     bgapi    event    filter    linger    exit    hup    log
```

Anything else is answered:

```text
-ERR command not found
```

`uuid_kill`, `uuid_broadcast` and `uuid_bridge` are **APIs**, not inbound
commands, so they must be sent `api`-prefixed. Measured on the live switch:

```text
uuid_kill <uuid> NORMAL_CLEARING         -> -ERR command not found
api uuid_kill <uuid> NORMAL_CLEARING     -> accepted
uuid_broadcast <uuid> <path> aleg        -> -ERR command not found
api uuid_broadcast <uuid> <path> aleg    -> accepted
uuid_bridge <a> <b>                      -> -ERR command not found
api uuid_bridge <a> <b>                  -> accepted
```

`bgapi` **is** a genuine inbound command, which is why `bgapi originate` works
and the channel commands do not. Do not `api`-prefix `bgapi`; that breaks it.

This is the single most important thing to know when writing ESL client code, and
it is invisible to a test double that replies success to everything.

### 12.2 An `api` reply has an empty Reply-Text

Two different reply shapes come back from the same connection:

| Command sent | `Content-Type` | `Reply-Text` | Result is in |
|---|---|---|---|
| `auth`, `event`, `bgapi` | `command/reply` | `+OK ...` | the header |
| `api <anything>` | `api/response` | **empty** | the **body** |

Measured:

```text
api status   -> Content-Type: api/response   Reply-Text: ''   body: 'UP 0 years, ...'
api sofia status -> Content-Type: api/response   Reply-Text: ''
```

**Consequence for any client.** A reply path that requires `+OK` will reject
every *successful* `api` call, and a client that reads only `Reply-Text` will
believe the command returned nothing. The verdict that means failure for an
`api` call is `-ERR`, and nothing else.

For a command-reply, "no verdict" genuinely is a failure — keep that rule, but do
not apply it to `api` replies.

### 12.3 What this cost, and how it stayed hidden

`uuid_broadcast` and `uuid_kill` were sent bare for the whole of the project's
life. Every playback, hangup and bridge would have been refused by a real
switch, so the platform could not have completed a call against a carrier.

The suite was green, because `FakeEslServer` answered `+OK accepted` to any
command it did not recognise. The double was faithful about **framing** and
unfaithful about **dispatch** — a specific, and now-closed, gap. The double
rejects unknown commands, exactly as the switch does.

**Rule: a protocol double must model what the server does with a command it does
not understand, not just how it frames messages.** The permissive default is
the defect.

### 12.4 Do not multiplex commands and events on one socket

A synchronous command on a connection that is also receiving subscribed events
can read the next **event** instead of the command's reply. The real reply stays
queued and every later read is off by one frame. The symptom is a command that
times out against a perfectly healthy switch.

Use a separate connection for commands. Any client sharing one long-lived socket
for both is exposed.

### 12.5 The channel identity rule

FreeSWITCH emits **no `Call-UUID` header**. The channel UUID is present as:

| Header | Notes |
|---|---|
| `Channel-Call-UUID` | the explicit channel-identity header — **read this first** |
| `Unique-ID` | the conventional channel header — the fallback |
| `Caller-Unique-ID` | also equals the channel UUID |
| `variable_call_uuid` | also equals the channel UUID |
| `variable_origination_uuid` | the value the application supplied |
| `Call-UUID` | **absent**; read only for compatibility |

Resolve identity by trying them **in order** rather than hard-coding one name.
Header casing is not stable across the event set, so lookup must be
case-insensitive.

This project learned that the hard way: the correlation code read `Call-UUID`
for its entire life, so **no event ever correlated to a call**, while every test
passed because each test hand-built its event with that same header.

### 12.6 The playback lifecycle, as observed

For one `uuid_broadcast` on an answered channel:

| Case | Command reply | Events on the channel |
|---|---|---|
| valid file | `+OK Message sent` | `CHANNEL_EXECUTE`, **`PLAYBACK_START`**, **`PLAYBACK_STOP`**, `CHANNEL_EXECUTE_COMPLETE` |
| missing file | `+OK Message sent` | `CHANNEL_EXECUTE`, `CHANNEL_EXECUTE_COMPLETE` |

The command reply is **identical** in both cases, and **no `PLAYBACK_ERROR`
event or `Playback-Error` header is produced for a missing file** — the failure
appears only in the log:

```text
[WARNING] mod_sndfile.c:281 Error Opening File
          [/media/obd/absent.wav] [System error : No such file or directory.]
```

So the rule for detecting playback outcomes:

| Outcome | Signal |
|---|---|
| command accepted | `+OK` — **not** evidence of playback |
| playback started | `PLAYBACK_START` — the only positive evidence |
| playback completed | `PLAYBACK_STOP` |
| playback failed | `CHANNEL_EXECUTE_COMPLETE` with **no** preceding `PLAYBACK_START` |

`CHANNEL_EXECUTE` / `CHANNEL_EXECUTE_COMPLETE` fire in **both** cases and do not
discriminate on their own.

Any code that waits for a `Playback-Error` header waits forever. See
[`docs/LIVE-FREESWITCH-PHASE-D.md`](../LIVE-FREESWITCH-PHASE-D.md) §6.

### 12.7 A channel's identity stays addressable

A call involves several channels: the originated A-leg, the far end's own
channels, and the leg the bridge is anchored on. `CHANNEL_BRIDGE` is **not**
anchored on the originated leg, and no header links the far-end channels back to
it.

But the **originated** channel — the one whose UUID the application pinned with
`origination_uuid` — remains addressable for the whole call. Verified live while
a call was bridged:

```text
api uuid_broadcast <pinned> <file> aleg -> +OK, then PLAYBACK_START/STOP on <pinned>
api uuid_kill <pinned> NORMAL_CLEARING  -> +OK, then CHANNEL_HANGUP on <pinned>
```

So an application that supplies and stores the channel UUID can address that leg
for playback, hangup and timeout without tracking any other channel. It does not
need a "current channel" concept for the leg it created.

### 12.8 Channel addresses are not stable across container recreates

A container address is not a constant. In Phase D, recreating containers moved
the platform switch from `172.25.0.2` to `172.25.0.4`, and **registrations keyed
to the old address linger in the registrar for their full expiry** — producing
contradictory-looking `sofia status` output and a `NO_ROUTE_DESTINATION` that
looks like a FreeSWITCH fault.

**Discover the address; never hard-code it.** Ask the switch
(`sofia status profile internal` → `SIP-IP`) or resolve a service name. A stale
address produces a failure that reads as a provider problem.
