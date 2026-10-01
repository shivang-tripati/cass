# 04 — Modules

FreeSWITCH's functionality is delivered by **loadable modules**. Core
functionality (channels, bridges, RTP) is built in; almost everything else is a
module that must be loaded before it exists.

A module is loaded because a line in `autoload_configs/modules.conf.xml`
requests it. If a line is commented out, the module does not exist — and there
is no error; the API it provides simply is not there.

---

## 1. What this environment actually loads

**CONFIRMED** by reading the stock `modules.conf.xml` in the image:

| Module | State | Needed for |
|---|---|---|
`mod_sofia` | **enabled** | all SIP: profiles, gateways, INVITE, registrations |
`mod_event_socket` | **enabled** | ESL — the entire Java ↔ FreeSWITCH interface |
`mod_commands` | **enabled** | `uuid_broadcast`, `uuid_kill`, `uuid_bridge`, `bgapi`, `status`, `reloadxml` |
`mod_dptools` | **enabled** | dialplan applications: `playback`, `answer`, `bridge`, `play_and_collect_digits` |
`mod_loopback` | **enabled** | the `loopback/` endpoint, useful for local tests |
`mod_spandsp` | enabled | tones/fax; present |
`mod_verto` | enabled | WebRTC signalling; **not needed here** — it binds `0.0.0.0:1337/udp` internally (not published) and logs `Invalid External RTP IP` |
`mod_xml_curl` | **commented out** | dynamic directory/dialplan from an HTTP service. Deliberately not used — see [dynamic-extension-dialplan notes](06-SIP.md) |
`mod_signalwire` | **commented out** | the SignalWire cloud dialer. Not in the image's build |
`mod_av` | enabled but **FAILS to load** | see below |

### The `mod_av` failure

**CONFIRMED.** At startup:

```text
[CRIT] Error Loading module /usr/lib/freeswitch/mod/mod_av.so
**Error loading shared library libavformat.so.62: No such file or directory
  (needed by /usr/lib/freeswitch/mod/mod_av.so)**
```

This is a **pre-existing defect in the image**, not something this project
introduced and not a regression. `mod_av` is a media/recording convenience
module; nothing in this project's plan needs it. It is recorded here so that a
future engineer does not mistake it for a new problem, and does not try to
"fix" the environment to eliminate it.

### Verifying what is loaded

```text
Command:  fs_cli -x "module_exists mod_sofia"
Purpose:  ask FreeSWITCH whether a specific module is loaded
Success:  "true"
Failure:  "false" -> the module is not loaded; check modules.conf.xml
When:     first check when a capability is unexpectedly missing
```

There is also `fs_cli -x "show modules"` for the full list with state, and
`/usr/lib/freeswitch/mod/` inside the container for what the image actually
ships.

---

## 2. `mod_event_socket` — the ESL module

### What it does

`mod_event_socket` is FreeSWITCH's **network control interface**. It opens a
TCP listener and speaks ESL, a line-oriented, text-based protocol. Over one
connection a client can:

* receive an unprompted **authentication request** banner,
* **authenticate** with a shared password,
* **subscribe** to a chosen set of events, which FreeSWITCH then pushes
  unprompted and indefinitely,
* issue **commands** and read their replies.

### Why Java needs it

FreeSWITCH is a telephony engine; the Java platform is the control plane that
decides policy. ESL is the only seam between them. Without it the Java platform
would have to speak SIP itself, implement media handling, and re-implement
hangup semantics — i.e. build the switch. ESL keeps FreeSWITCH as the engine
and keeps telephony out of the application.

It is also the only way Java learns about facts it cannot ask for
synchronously: that a call was answered, that a key was pressed, that a call
ended and why.

### Listener and authentication

```text
Listen address   configured by event_socket.conf.xml
                 this environment: 127.0.0.1:8021
Password         shared secret; this environment: rendered from the
                 FREESWITCH_PASSWORD environment variable at start-up
```

**CONFIRMED stock values, for contrast:** `listen-ip` is `::` (every
interface) and `password` is `ClueCon` — a publicly documented default. A
stock FreeSWITCH exposed on any reachable network is effectively open control
of the switch. Both are overridden here; see
[14-SECURITY.md](14-SECURITY.md).

### Commands used by this project

Exactly five, plus the two handshake commands. Full detail in
[05-ESL.md](05-ESL.md).

| Command | Purpose here |
|---|---|
`auth <password>` | authenticate the connection |
`event plain <names>` | subscribe to the ten events Java consumes |
`bgapi originate {...}sofia/gateway/<gw>/<dest>` | place a call, with a caller-chosen channel UUID |
`uuid_broadcast <uuid> <path> aleg` | play an audio file to the call's A-leg |
`uuid_kill <uuid> NORMAL_CLEARING` | end a channel deliberately |
`uuid_bridge <a> <b>` | join two channels |

Note what is **absent**: there is no DTMF-collection command, because Java
collects DTMF passively from events. See [02-CALL-FLOW.md](02-CALL-FLOW.md) §3.

### Events consumed by this project

```text
CHANNEL_CREATE  CHANNEL_PROGRESS  CHANNEL_PROGRESS_MEDIA  CHANNEL_ANSWER
CHANNEL_DTMF    CHANNEL_HANGUP    PLAYBACK_START  PLAYBACK_STOP
PLAYBACK_ERROR  CHANNEL_BRIDGE
```

### Security implications

mod_event_socket is **full control of the switch**. Anyone who authenticates
can place calls, terminate live calls, bridge calls together, and play audio.
It is not a read-only status port. Treat it as equivalent to shell access on
the host from the platform's point of view.

Two consequences for this environment:

* it is bound to loopback *inside* the container and published to
  `127.0.0.1` *on the host* — never to `0.0.0.0`;
* the credential is generated locally, lives only in a git-ignored file, and
  is re-rendered on every start.

### How to determine whether it is loaded

```text
Command:  fs_cli -x "module_exists mod_event_socket"
Success:  "true"
Failure:  "false"
```

And whether the listener is actually up:

```text
Command:  docker exec obd-freeswitch netstat -tlnp | grep 8021
Success:  "tcp 0 0 127.0.0.1:8021 0.0.0.0:* LISTEN 1/freeswitch"
Failure:  no output -> the module is loaded but the listener failed to bind
          (check listen-ip, the ACL, and stop-on-bind-error)
```

---

## 3. `mod_sofia` — the SIP module

### What Sofia is

**Sofia** is FreeSWITCH's SIP stack — the library that speaks the protocol.
**`mod_sofia`** is the module that exposes Sofia as a FreeSWITCH endpoint, so
that FreeSWITCH can accept and place SIP calls.

A separate concept, **Sofia profile**, is one named SIP "door": its own ports,
codec policy, security rules, DTMF behaviour and gateway set. A FreeSWITCH can
run several at once. Full explanation in
[00-FREESWITCH-FUNDAMENTALS.md §3](00-FREESWITCH-FUNDAMENTALS.md).

### What mod_sofia owns

* **SIP profiles** — the listening endpoints (`internal` on 5060, `external`
  on 5080 here).
* **Gateways** — named outbound trunks (`fs-gateway` here).
* **Registrations** — softphones logging in, tracked per profile.
* **INVITE handling** — building outbound INVITEs and processing inbound ones.
* **The dialplan integration** — routing an inbound call into a context.
* **Call screening** — translating SIP responses into FreeSWITCH hangup causes.

### Why this project needs it

Java dials with `sofia/gateway/<name>/<number>`. That string is meaningless
without mod_sofia. There is no way to place a SIP call in FreeSWITCH without it.

### internal vs external — the convention, and why it matters here

| | `internal` | `external` |
|---|---|---|
Intended for | local endpoints you control: softphones, agents, PBX extensions | trunks to systems you do not control: carriers, partner PBXs |
Typical trust | high — you provisioned the endpoints | low — you authenticate to it, or it to you |
Default auth | may authenticate registrations | does not authenticate calls (`auth-calls=false`) |
This environment | softphone registration, local testing | the profile `fs-gateway` is bound to |

The split matters because it keeps the security policy separable. In this
environment the `external` profile carries the gateway, and no local endpoint is
reachable through it.

### How to inspect Sofia state

```text
Command:  fs_cli -x "sofia status"
Purpose:  every profile, plus gateways and registration counts
Success output looks like:

        Name          Type       Data                                            State
  ==============================================================================================
             external  profile    sip:mod_sofia@172.25.0.2:5080                  RUNNING (0)
 external::fs-gateway gateway   sip:FreeSWITCH@freeswitch-provider:5080        NOREG
             internal  profile    sip:mod_sofia@172.25.0.2:5060                  RUNNING (0)
  ==============================================================================================
  2 profiles 0 aliases
```

How to read it:

| Field | Meaning |
|---|---|
`RUNNING (0)` | the profile is up; `(0)` is the registration count |
`NOREG` | this gateway is intentionally not registered (correct here — see [00 §19](00-FREESWITCH-FUNDAMENTALS.md)) |
`0 profiles` | **nothing loaded** — your profile files were not recognised |
`0 aliases` | normal; aliases come from a `<domains parse=...>` block we do not use |

```text
Command:  fs_cli -x "sofia status profile internal"
Purpose:  full configuration and counters for one profile
```

Fields worth knowing:

| Field | Meaning and what a wrong value causes |
|---|---|
`URL` / `BIND-URL` | the address and transports actually bound. A wrong BIND-URL means nothing can reach the profile. |
`RTP-IP` / `SIP-IP` | auto-detected here (172.25.0.2). These go into SDP. Wrong ⇒ silent calls. |
`CODECS IN` / `CODECS OUT` | preference order. No common codec with the peer ⇒ no audio. |
`TEL-EVENT 101` | the RTP payload type carrying DTMF. Must be agreed in SDP. |
`DTMF-MODE` | the profile's preferred DTMF method (`rfc2833` here). With `liberal-dtmf=true` this is a preference, not a restriction. |
`Context public` | which dialplan context inbound calls enter. |
`REGISTRATIONS` | softphones currently registered. `0` is expected in Phase B. |
`CALLS-IN` / `CALLS-OUT` / `FAILED-CALLS-*` | counters. Non-zero failures with zero successes is a strong early signal. |

```text
Command:  fs_cli -x "sofia status gateway fs-gateway"
Purpose:  one gateway in detail
Success:  Name / Profile / Proxy / State / Status / Uptime / call counters
Failure:  "Invalid Gateway!" -> that name is not loaded
```

---

## 4. `mod_commands` — the API surface

**What it does.** Provides the `api` interface that both ESL and `fs_cli` expose.
Without it there are no `uuid_*` commands and no `bgapi`.

**Why it matters here.** Every single command the Java platform uses comes from
this module. If it is not loaded, ESL connects and authenticates and then every
command returns `-ERR Command not found`.

**How to verify.**

```text
Command:  fs_cli -x "module_exists mod_commands"
Success:  "true"
```

---

## 5. `mod_dptools` — the dialplan applications

**What it does.** Provides the applications a dialplan calls: `playback`,
`answer`, `bridge`, `hangup`, `play_and_collect_digits`, `transfer`, and so on.

**Why it matters here, and a warning.** It is loaded because the stock
dialplan uses it. But this project's Java platform deliberately does **not** use
dialplan applications to express campaign logic — it drives channels
individually over ESL.

**A specific warning for the DTMF phase.** `play_and_collect_digits` is the
obvious-looking way to collect DTMF in a dialplan. **Do not add it.** The Java
platform collects DTMF passively from `CHANNEL_DTMF` events and never awaits an
application result. A dialplan that captures digits would consume them, and
would also change the A-leg media model that `aleg` playback depends on.

**How to verify.**

```text
Command:  fs_cli -x "module_exists mod_dptools"
Success:  "true"
```

---

## 6. Modules that are loaded but unused

| Module | Why it is loaded | What it costs us |
|---|---|---|
`mod_verto` | stock `modules.conf.xml` enables it | binds `0.0.0.0:1337/udp` inside the container (not published) and logs `Invalid External RTP IP` |
`mod_spandsp` | stock | nothing significant |
`mod_av` | stock | **fails to load** with a missing `libavformat.so.62`; pre-existing image defect, harmless |
`mod_loopback` | stock | nothing; useful for later local tests |

If a future phase wants a minimal module set, trimming these is safe and
optional. It is **not** done in Phase B because "smallest change that works" is
the discipline here, and the image's module list is not a security boundary —
the port bindings are.

---

## 7. Adding a module later

If a future phase needs a module that is not loaded:

1. Add or uncomment the line in
   `infra/freeswitch/conf/autoload_configs/modules.conf.xml` (this file is
   currently stock; overriding it means mounting it like the others).
2. Restart the container.
   ```text
   ⚠  RESTARTS SERVICE — interrupts the ESL connection. Java's event loop will
       reconnect on its next 60-second sweep, but any in-flight call context is
       lost. Safe in development; take a change window in production.
       docker compose -f infra/docker-compose.freeswitch.yml restart freeswitch
   ```
3. Verify with `fs_cli -x "module_exists <name>"` and check the startup log for
   a `[CRIT] Error Loading module` line.
4. Record in this document **why the module is needed**, not just that it is
   enabled.
5. Re-check the port bindings — a new module may open new listeners. Re-run the
   `netstat` check from [12-PRODUCTION-OPERATIONS.md](12-PRODUCTION-OPERATIONS.md).
