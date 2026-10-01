# 06 — SIP

How SIP works, and how it works *here*. Written for someone who has never
deployed a SIP system.

---

## 1. SIP in one paragraph

SIP is a text protocol for asking other computers to start, modify and end
real-time sessions. Text requests and responses travel over UDP (or TCP), and
each one has a method and a status code. SIP negotiates the call; a second
protocol (RTP) carries the audio.

---

## 2. The messages that matter here

### Setting a call up

| Message | Direction | Meaning |
|---|---|---|
`REGISTER` | endpoint → server | "I am extension 1001, send my calls to this contact." Registration is how a softphone tells a server where it is. |
`INVITE` | caller → callee | "I want to start a call." Carries the SDP, which describes codecs and media addresses. |
`100 Trying` | callee → caller | "I have received your INVITE." Purely informational. |
`180 Ringing` | callee → caller | "The phone is alerting." |
`183 Session Progress` | callee → caller | Early media: a tone or announcement is already playing. |
`200 OK` | callee → caller | **The call is answered.** Carries the callee's SDP answer. |
`ACK` | caller → callee | Confirms receipt of the `200 OK`. Ends the 3-way handshake. |
`BYE` | either → other | **The call is over.** |
`CANCEL` | caller → callee | Abort before the call is answered. |

**A common mistake:** treating `180 Ringing` as success. It means the far end
is alerting, not that anyone answered. Only `200 OK` is an answer, and only
`200 OK` causes FreeSWITCH to emit `CHANNEL_ANSWER`.

### During the call

| Message | Meaning |
|---|---|
`INFO` | the standard out-of-band way to carry **DTMF** (keypresses) during a call |
`UPDATE` | change session parameters without renegotiating |
`PRACK` / `REFER` | flow control; transfer |

`INFO` matters here because it is one of the two DTMF transports this project
must validate. Its typical body is:

```text
Content-Type: application/dtmf-relay

Signal=1
Duration=160
```

Note the encoding subtlety: some endpoints send `Signal=10` for `*` and
`Signal=11` for `#`. FreeSWITCH normalises this to a single character in
`DTMF-Digit`. **NOT YET TESTED** against a real endpoint — see
[02-CALL-FLOW.md §3.3](02-CALL-FLOW.md).

### Responses that mean failure

| Code | Name | FreeSWITCH hangup cause | What it usually means |
|---|---|---|---|
`486` | Busy Here | `USER_BUSY` | the endpoint is on another call |
`480` | Temporarily Unavailable | `NO_ANSWER` | no answer within the screening period, or temporarily unreachable |
`503` | Service Unavailable | `NORMAL_TEMPORARY_FAILURE` | try again shortly — congestion or a transient fault |
`603` | Decline | `CALL_REJECTED` | the user deliberately refused the call |
`404` | Not Found | `SUBSCRIBER_ABSENT`-ish | nobody at that number |
`487` | Request Terminated | `ORIGINATOR_CANCEL` | the caller hung up before the far end answered |
`603` with a gateway reject | | `CALL_REJECTED` | carrier policy refusal |
`407` | Proxy Authentication Required | | a proxy in the path wants credentials |

And the success case: **`200 OK` then `BYE`** is a normal, clean end.

**How this project uses them.** The `CHANNEL_HANGUP` event carries
`Hangup-Cause` (a **symbolic** FreeSWITCH name such as `USER_BUSY`, not a
number) and `Hangup-Cause-Code` (the numeric Q.850 value). The Java platform
reads the **symbolic** one and maps it to a business outcome:

| FreeSWITCH `Hangup-Cause` | Q.850 | Java business code |
|---|---|---|
`NORMAL_CLEARING` | 16 | success — not a failure at all |
`USER_BUSY` | 17 | `BUSY` |
`NO_ANSWER` | 19 | `NO_ANSWER` |
`CALL_REJECTED` | 21 | `REJECTED` |
`NO_CIRCUIT_AVAILABLE` | 34 | `CONGESTION` |
`NORMAL_TEMPORARY_FAILURE` | 41 | `TEMPORARY_FAILURE` |
`RESOURCE_UNAVAILABLE` | 47 | `RESOURCE_UNAVAILABLE` |
anything else, or absent | | `HANGUP_UNKNOWN` (treated as retryable) |

**Not yet validated against a live endpoint.** Some FreeSWITCH causes the
mapper does not list — `ORIGINATOR_CANCEL`, `CHANNEL_UNBRIDGED`,
`SUBSCRIBER_ABSENT`, `MEDIA_TIMEOUT` — currently collapse to
`HANGUP_UNKNOWN`. One of those is worth watching: in a bridged call, the
non-anchor leg often hangs up with `CHANNEL_UNBRIDGED`, which is *normal
teardown*, not a failure. The live failure matrix must record what actually
arrives before anyone changes the mapper. **Do not adjust the mapper to make a
test pass.**

---

## 3. The roles, defined

| Term | Meaning in this project |
|---|---|
**registrar** | the SIP server that stores registrations. Here, FreeSWITCH's `internal` profile. |
**proxy** | the server that routes a call onward. Here, FreeSWITCH when a call goes to a gateway. |
**endpoint** | anything that sends or receives SIP: a softphone, a PBX, a carrier. |
**caller / callee** | who initiated / who is called. |
**trunk / gateway** | a configured route to a remote SIP system. Here, `fs-gateway`. |
**domain** | a SIP namespace. Not used dynamically here — see §8. |

A useful mental model: **registration is a softphone telling the server its
current address; a gateway is the server knowing in advance where a far system
lives.** Most endpoints register; trunks are configured.

---

## 4. Profiles and ports in this environment

```text
        SIP messages in
              │
   ┌──────────┴───────────┐
   │                      │
internal :5060        external :5080
   │                      │
   │                      └── carries gateway "fs-gateway"
   │                          the outbound leg to a provider
   │
   └── local softphones
       register here, calls arrive here
```

| | `internal` | `external` |
|---|---|---|
Port | 5060 UDP + TCP | 5080 UDP + TCP |
Bound URL | `sip:mod_sofia@172.25.0.2:5060;transport=udp,tcp` | `sip:mod_sofia@172.25.0.2:5080;transport=udp,tcp` |
Purpose | softphones, local endpoints | the gateway leg |
Auth | `auth-calls=false`, `auth-subscriptions=false` in Phase B | `auth-calls=false` |
Reachable from host | yes, `127.0.0.1:5060` | yes, `127.0.0.1:5080` |

**Why two profiles rather than one.** The two roles have opposite security
postures. `internal` faces endpoints you provision and trust. `external` faces
systems you do not control. Keeping them separate means the external profile
never has to carry local-endpoint policy, and the internal profile never has to
carry carrier-facing exposure. It is also FreeSWITCH's own convention, so
anyone who has run FreeSWITCH before will read it correctly.

**Confirming what is actually bound:**

```text
Command:  fs_cli -x "sofia status"
Purpose:  see the two profiles and their bind addresses
Success:  two rows, "internal ... RUNNING" and "external ... RUNNING",
          each with its own port
Failure:  "0 profiles" -> the profile files were not recognised at all
```

---

## 5. The gateway `fs-gateway`

```text
  Java
    │  ESL: bgapi originate {origination_uuid=...}
    │        sofia/gateway/fs-gateway/<number>
    v
  FreeSWITCH resolves the name "fs-gateway" to a configuration object
    │
    │  that object says: use profile "external", send INVITEs to
    │  proxy sip:freeswitch-provider:5080
    v
  SIP INVITE leaves on port 5080
```

Three things to understand:

1. **The Java dial string names a *gateway*, never a profile.** The profile
   comes from the gateway's own `sofia-profile` setting. That is why the
   gateway must state it explicitly.
2. **`register=false` means FreeSWITCH never sends REGISTER for it.** It is a
   static, origination-only trunk. Its state is therefore `NOREG`, which is
   correct and not a fault.
3. **The proxy hostname `freeswitch-provider` does not resolve yet.** With
   `register=false` it is never resolved during Phase B. It is the name the
   provider instance will use, so no change is needed when it arrives.

**Inspecting a gateway:**

```text
Command:  fs_cli -x "sofia status gateway"
Purpose:  list all gateways with state
Success:  "external::fs-gateway  sip:FreeSWITCH@freeswitch-provider:5080  NOREG"
Failure:  "0 gateways" -> it did not load. The usual cause is `register`
          left at its `true` default, which makes FreeSWITCH try to register,
          fail, and discard the gateway silently.

Command:  fs_cli -x "sofia status gateway fs-gateway"
Purpose:  one gateway in full detail
Success:  Name / Profile / Proxy / State / Status / Uptime / call counters
Failure:  "Invalid Gateway!" -> that name is not loaded
```

---

## 6. Inspecting SIP signalling

### FreeSWITCH native tools

```text
Command:  fs_cli -x "sofia status"
Purpose:  profile and gateway overview, registration counts
Success:  RUNNING profiles, expected gateway count
When:     always, before any SIP investigation

Command:  fs_cli -x "sofia status profile internal"
Purpose:  one profile's full config and live counters
Key fields:  REGISTRATIONS, CALLS-IN, CALLS-OUT, FAILED-CALLS-IN,
             FAILED-CALLS-OUT
Success:  FAILED-CALLS should be 0 while nothing is being tested
Failure:  non-zero failures with zero successes -> investigate immediately
When:     a softphone cannot register, or calls fail
```

**Live call signalling** requires a channel to exist, so it is **NOT YET
TESTED** here. When it is, the commands will be:

```text
Command:  fs_cli -x "show channels as json"
Purpose:  list live channels; each has the variable sip_call_id
Success:  a channel with a UUID matching what Java pinned

Command:  fs_cli -x "uuid_dump <channel-uuid>"
Purpose:  every channel variable, including sip_call_id, bridge variables,
          and the media address in use
Success:  a sip_call_id is present and non-empty
When:     correlating a FreeSWITCH channel with a carrier's records

Command:  fs_cli -x "sofia global siptrace on"
          ⚠  DEVEL ONLY / noisy. Produces very verbose SIP logging.
          Turn it off again with "sofia global siptrace off".
When:     last resort, when you need the actual SIP messages
```

**Where SIP messages appear in the log.** FreeSWITCH's console log records
SIP activity at the profile's `debug` setting. This environment runs at
`loglevel=info` and profiles at `debug=0`, so **individual SIP messages are not
logged by default**. To see them you must raise the profile's `debug` value —
which is why the debugging runbook
([11-DEBUGGING.md](11-DEBUGGING.md)) treats verbosity changes as a deliberate,
reversible step.

### Packet capture

**Not configured in Phase B.** A later phase may add `tcpdump` in the
container. Until then, if you need to see the actual bytes, the options are a
short-lived capture container on `obd-telephony`, or a capture on the Windows
host. Document whichever is used; do not assume it exists.

---

## 7. Codecs, SDP and why a call can connect and still be silent

Three separate things must line up for audio. A failure in any one of them
produces a connected-but-silent call, which is the most confusing SIP symptom
there is.

```text
1. CODEC AGREEMENT
   Both sides list what they support; one is chosen. If there is no overlap,
   no audio. Here: PCMU, PCMA, G722, OPUS.

2. ADDRESS AGREEMENT (SDP)
   Each side states an IP and port for its media. If that address is
   unreachable — 0.0.0.0, a private address the other side cannot route to,
   or a Docker bridge address the host cannot reach — audio goes nowhere.

3. ACTUAL PATH
   Packets must be permitted between the two endpoints on the agreed port.
   Docker port mappings and host firewalls both apply.
```

**The Docker-specific trap.** A containerised FreeSWITCH advertises its Docker
bridge address in SDP (here `172.25.0.2`). A softphone on the Windows host
cannot necessarily reach a `172.x` address directly. That is why the RTP range
is published to `127.0.0.1` and why a softphone test may need a host-side
address that FreeSWITCH knows how to advertise. **This is a known Phase C/D
area and is NOT YET TESTED.**

**Inspecting what was negotiated:**

```text
Command:  fs_cli -x "sofia status profile internal"
Purpose:  the profile's codec preference list
Success:  "CODECS IN  PCMU,PCMA,G722,OPUS" — but this is the PREFERENCE,
          not what a specific call negotiated. For that you need a live
          channel: fs_cli -x "uuid_getvar <channel-uuid> rtp_use_codec"
```

`rtp_use_codec` on a live channel is the authoritative answer to "what codec is
this call actually using". **NOT YET TESTED.**

---

## 8. DTMF over SIP

| Method | Transport | Depends on media? | FreeSWITCH profile setting |
|---|---|---|---|
RFC 2833 / 4733 | RTP `telephone-event` packets | **yes** | `rfc2833-pt` (101 here) |
SIP `INFO` | a SIP request during the call | no | `liberal-dtmf`, `proxy-info` |

Two settings exist in this environment specifically to make both work, and
their defaults are the trap:

| Setting | Stock default | Here | Why the default is wrong for this project |
|---|---|---|---|
`liberal-dtmf` | `false` | `true` | With `false`, FreeSWITCH accepts DTMF only via the profile's preferred method. An endpoint that sends SIP INFO when FreeSWITCH expects RFC 2833 is **silently ignored** — the digit simply never arrives, with no error anywhere. Later testing deliberately covers both methods, so both must be accepted. |
`proxy-info` | `false` | `true` | With `false`, inbound SIP INFO is **consumed locally** instead of relayed across a bridge. Once an agent is bridged to a customer, the agent's keypresses would be swallowed. |

**Confirming the policy is loaded:**

```text
Command:  docker exec obd-freeswitch grep -e liberal-dtmf -e proxy-info \
            /etc/freeswitch/sip_profiles/internal.xml
Success:  two lines, both value="true"
Failure:  missing -> the file did not take effect; recheck the compiled config
```

**Confirming what the profile reports:**

```text
Command:  fs_cli -x "sofia status profile internal"
Success:  "DTMF-MODE  rfc2833" and "TEL-EVENT  101"
Note:     DTMF-MODE shows the PREFERENCE. liberal-dtmf and proxy-info are not
          surfaced here — grep the profile file to confirm those.
```

---

## 9. Multi-tenancy: not used here, and why it matters to know about

FreeSWITCH can partition endpoints into SIP **domains**, so extension `1001`
could exist independently for two tenants. A repository document
(`backend/docs/dynamic-extension-dialplan.md`) sketches using `mod_xml_curl` to
serve the directory from the Spring Boot application.

**That design is not implemented and is not used in this environment.**
Verified: there is no `xml-handler` controller, no `mod_xml_curl` (it is
commented out in the stock module list), and the present phase has no
registration at all.

It is recorded here because it is the natural future answer if per-tenant
extension numbers are ever required, and because the existing endpoints model
in the Java platform is not registration-based at all — the platform *dials* an
agent's `dial_target` string rather than looking up a registered contact.

---

## 10. A note on the Java side, so you do not go looking for SIP code

The Java platform **never speaks SIP**. It has no SIP stack, no SDP parser and
no User-Agent. Every SIP concept on that side is expressed as either:

* an ESL command, or
* a value in the database (a gateway name, a `dial_target`).

So when you are debugging SIP, you are debugging FreeSWITCH's behaviour, and
Java is either telling it what to do or listening to what it reports.

---

## 11. PHASE C: observed SIP behaviour

Registration, realms and the directory, established by observation.

### 11.1 The observed REGISTER flow

```text
REGISTER (no credentials)
  <- SIP/2.0 401 Unauthorized
     WWW-Authenticate: Digest realm="172.25.0.2", nonce="...", algorithm=MD5, qop="auth"

REGISTER (with digest, qop=auth)
  -> SIP/2.0 200 OK
```

**Two details that are easy to get wrong:**

* The challenge specifies **`qop="auth"`**, so the response must be the
  RFC 2617 qop form: `MD5(HA1:nonce:nc:cnonce:qop:HA2)`. Sending `qop=auth` while
  computing the older RFC 2069 form yields **`403`, not `401`** - so a 403 on the
  second REGISTER means "credentials or digest computation wrong", not "no
  challenge was issued".
* The **banner-free ordering** matters for clients: the 401 is the response to
  the first REGISTER, and the second REGISTER is a *new transaction* with a new
  `Via` branch and `CSeq: 2`.

### 11.2 A 403 does not mean "wrong password"

This is the single most misleading SIP behaviour found in Phase C, and it cost
real time.

```text
REGISTER (no auth)          -> SIP/2.0 401 Unauthorized
REGISTER (correct digest)   -> SIP/2.0 403 Forbidden
```

A wrong password and an **unknown user** are indistinguishable from the client.
Both answer `403`. The log is the only thing that distinguishes them:

```text
[WARNING] sofia_reg.c:3210 Can't find user [1001@127.0.0.1] from 172.25.0.1
 You must define a domain called '127.0.0.1' in your directory and add a user
 with the id="1001" attribute
```

That sentence names the exact `user@realm` FreeSWITCH looked for, and it is the
diagnosis. **Read it before touching a password.**

### 11.3 `challenge-realm` must be pinned

The stock value is `auto_to`, which takes the realm from the `To` header - that
is, from whatever address the client happened to dial. Two consequences, both
observed:

**1. A host client and an in-network client are challenged for different realms.**
A client dialling `127.0.0.1` is challenged for realm `127.0.0.1`; a container
dialling `172.25.0.2` is challenged for realm `172.25.0.2`. The directory must
then cover both, which is fragile and easy to get wrong.

**2. A registration can authenticate and still be unreachable.** FreeSWITCH files
registrations as `user@realm`, but the dialplan looks users up by a *different*
key. The result is a registration that exists, a user that exists, and a call
that fails:

```text
Processing 1001 <1001>->1002 in context default
bridge(user/1002@172.25.0.2)
Cannot create outgoing channel of type [error] cause: [USER_NOT_REGISTERED]
```

**Fix**, in `sip_profiles/internal.xml`:

```xml
<param name="challenge-realm" value="$${domain}"/>
```

`$${domain}` resolves at config-compile time to the container's own address, so
authentication, the registration key and the dialplan's user lookup all agree on
one value. Verified: `sofia status profile internal` now reports
`Challenge Realm 172.25.0.2`.

### 11.4 Extension numbers are a shared namespace with the image

The stock `directory/default.xml` defines users `1000`-`1014` in the same
domain, authenticating with the value in `vars.xml`. The project's test
extensions are also `1001`/`1002`.

The directory lookup returns one match, and the **stock** definition won - so a
correct password was compared against the wrong stored value and rejected with
`403`. Extension numbers collided with the image's own.

**Fix: own the directory.** Replace the stock file rather than adding one beside
it, so there is nothing to collide with. `infra/freeswitch/conf/directory/default.xml.in`
is now the entire directory: one domain, two users, and a `dial-string` parameter
(see 11.5). An entrypoint guard refuses to start if any other user id appears.

**Verified afterwards:**

```text
user ids defined            -> 1 x 1001, 1 x 1002   (no stock users)
REGISTER 1000 + stock password -> SIP/2.0 403 Forbidden
REGISTER 1001 + correct one    -> SIP/2.0 200 OK
```

### 11.5 The `dial-string` parameter is mandatory

A directory domain defined without it produces the most misleading failure
possible: **the call answers, and there is no audio.**

```text
[ERR] mod_dptools.c:4419 No dial-string available, please check your user
      directory.
```

`user/<ext>` is resolved *through* that parameter. Without it FreeSWITCH cannot
build a bridge target, so the legs are never connected - while registration, the
INVITE and the 200 OK all succeed. Required form:

```xml
<domain name="$${domain}">
  <params>
    <param name="dial-string"
           value="{$presence_id}${dialed_user}@{$dialed_domain}"/>
  </params>
  ...
```

### 11.6 Registration as seen by Sofia, for both kinds of client

```text
User:         1002@172.25.0.2
Contact:      <sip:gw+platform-switch@172.25.0.3:5060;transport=udp;gw=platform-switch>
Status:       Registered(UDP)(unknown) EXP(...) EXPSECS(3556)
Ping-Status:  Reachable
IP:           172.25.0.3
Auth-User:    1002
Auth-Realm:   172.25.0.2
```

The `gw+` prefix in the contact identifies a **gateway registration** - a
FreeSWITCH instance registering as a client. The endpoints in this environment
use that mechanism, which is a faithful substitute for a softphone: it performs
the same REGISTER / 401 / digest / 200 OK exchange.

A plain host client registers the same way over TCP:

```text
User:         1001@127.0.0.1
Contact:      <sip:1001@127.0.0.1:59735;transport=tcp>
Status:       Registered(TCP)(unknown)  Ping-Status: Reachable
```

Note the `User:` key still shows the **pinned realm** (`172.25.0.2`) even for a
host client, which is the effect of 11.3.

### 11.7 A `503` during registration means the switch is restarting

```text
[ERR] sofia_reg.c:2661 platform-switch Failed Registration with status Service
      Unavailable [503]. failure #6
```

`503 Service Unavailable` from a registration gateway is not a credential
problem. It is what a registrar returns when it cannot service the request - in
this environment, observed only while the platform switch was mid-restart.

The retry backoff grows (30 s, 60 s, 90 s), so **fixing the underlying problem
does not restore registration promptly** - the endpoint will sit at a long
interval. Restart the endpoint, or force a re-registration, rather than waiting.

### 11.8 A registered endpoint's Contact must be reachable

An endpoint registered from the network *alias* rather than its address puts the
alias in the `To` header, and therefore into the realm:

```text
realm="freeswitch"     <- not a domain the platform knows
```

The platform then cannot find the user, and registration fails with `403` for
reasons that have nothing to do with the password.

**Rule: register using the address, not the alias.** The endpoint's entrypoint
resolves the platform's address with `getent hosts freeswitch` at start-up and
substitutes it into both `proxy` and `realm`, so this stays correct across
container recreates. It also refuses to start if the name cannot be resolved,
rather than registering with a name that will never work.

### 11.9 What the local endpoints are, and why they are in-network

Two additional FreeSWITCH instances on `obd-telephony`, each registering one
extension:

| Container | Extension | Address |
|---|---|---|
| `obd-fs-endpoint-1001` | `1001` | `172.25.0.3` / `.4` (Docker assigns) |
| `obd-fs-endpoint-1002` | `1002` | the other of the two |

They are real SIP endpoints: real REGISTER, real digest, real INVITE, real RTP,
real DTMF generation via `send_dtmf`. Each has one profile, one gateway, and a
dialplan whose entire behaviour is *answer, emit a known DTMF sequence, play a
prompt, wait, hang up*.

**Why not a host softphone:** the Windows host has no route to the bridge
network (measured - see `07-RTP-AND-MEDIA.md` 11.4), so a host endpoint can
register and place calls but can never be called back and can never receive
audio. A callable, media-capable endpoint has to be in the network. This is
ADR-004.

**Why not SIPp:** the Docker daemon cannot resolve a registry on this machine, so
no new image can be pulled. Reusing the already-pinned image adds no new
supply-chain surface.

### 11.10 `rfc2833-pt` must match on both ends

In-band DTMF (RFC 4733) is negotiated on a **payload type number**. If the two
ends disagree, DTMF is negotiated on different payload types and the tones are
never decoded - while SIP signalling looks perfectly healthy, because
signalling does not depend on it.

Both the platform's internal profile and each endpoint's profile pin
`rfc2833-pt` to `101`. `liberal-dtmf` and `dtmf-duration` are also mirrored, so an
event generated on one side is decoded identically on the other.

**DTMF itself remains NOT YET TESTED.** The endpoint's dialplan calls `send_dtmf`
immediately after `answer`, but the legs are not yet bridged at that point, so
there is no RTP stream for the tones to travel on. The fix is to establish the
bridge first, which requires replacing the stock dialplan.

### 11.11 Config-file structure that fails silently

Two mistakes that produce no error a newcomer would recognise:

| Mistake | Result |
|---|---|
| root element `<config>` instead of `<configuration>` in `sofia.conf.xml` | `[ERR] sofia.c:4494 Open of sofia.conf failed` - **no SIP port at all** |
| root element `<config>` instead of `<configuration>` in `event_socket.conf.xml` | **all settings ignored**, including the password - the stock credential stays live |
| `<param>` outside `<settings>` in a profile | `[ERR] sofia.c:4567 No Settings, check the new config!` - profile does not start |
| `port` instead of `listen-port` in event_socket | ignored, and invisible because the default is also 8021 |

In every case FreeSWITCH starts, reports `UP`, and is healthy on ESL. The only
reliable detection is behavioural: check the bound ports, or a value
FreeSWITCH reports back. See troubleshooting entries 14 and 16.

---

---

## 12. PHASE E: routing, gateways, and a false positive

Phase C established that the host cannot route into the container network.
Phase D established the Java/ESL contract. Phase E made the dial string the Java
dialer actually builds - `sofia/gateway/<gw>/<dest>` - reach a real endpoint,
and in doing so produced the most instructive failure of the project so far.

### 12.1 mod_sofia routes by domain, not by address

It resolves an outbound destination against the registration database of the
profile it originates from. It will not send to an address merely because that
address is reachable. Measured on both profiles, by IP and by DNS name:

```text
sofia/external/1002@172.25.0.4:5060    -> NO_ROUTE_DESTINATION
sofia/internal/1002@172.25.0.4:5060    -> NO_ROUTE_DESTINATION
sofia/external/1002@endpoint-b:5060     -> NO_ROUTE_DESTINATION
sofia/internal/1002@<switch domain>     -> CHANNEL_ANSWER
```

Throughout, the transport was provably healthy: DNS resolved, the endpoint
answered ping with 0% loss, and `acl 172.25.0.3 obd-dev-acl` returned `true`.

**A working transport is not a route.** The single most useful thing to check
before blaming the network is whether a ROUTE exists, not whether packets can
move. `NO_ROUTE_DESTINATION` is a routing verdict, and it looks exactly like a
network fault.

Practical consequence: **in SIP the registration is the route.** Reachability is
configured through the registration contact, never by addressing a device.

### 12.2 A gateway's profile comes from where it is declared

mod_sofia resolves gateways as

```text
sofia.conf -> profiles -> profile[@name] -> gateways -> gateway
```

so the profile that *declares* a gateway is the profile it originates from. The
gateway's own `sofia-profile` parameter is a hint that the declaring profile
overrides. With the gateway in `external.xml` and `sofia-profile=internal`,
origination still produced `sofia/external/...` and the parameter did nothing.

**When a gateway's profile setting appears to be ignored, check which file
declares it before editing the parameter.**

### 12.3 A gateway-originated Request-URI is not the number dialled

This one cost real time, because the log looks like it agrees with you:

```text
New Channel sofia/obd-endpoint/FreeSWITCH@endpoint-b:5060
Processing FreeSWITCH <+15551230000>->1002 in context obd-endpoint
No Route, Aborting
```

The channel and Request-URI say `FreeSWITCH` - that is the *gateway's* identity.
`1002` is the dialled number, in `destination_number`. A dialplan matching
`${sip_destination_user}` against `^\d+$` therefore never matches a
gateway-originated call, and rejects a perfectly delivered INVITE.

**Match `${destination_number}`.** It is what FreeSWITCH itself logs and it is
set on every inbound call.

Corollary, learned by making the mistake: **nested `<condition>` elements are
ANDed.** Retaining the old condition as a "fallback" with `break="false"` on the
outer one made every gateway call fail again, with an identical symptom, which
made it look like the first fix had not applied. Alternatives are sibling
extensions or a single alternation expression - never a nested pair.

### 12.4 The stock dialplan answers your test calls (and lies to you)

This is the most important item in this section.

The image's stock `public` context answers every extension `1000-1019` locally:

```xml
<extension name="local_extension">
  <condition field="destination_number" expression="^(10[01][0-9])$">
    <action application="voicemail" data="default"/>
```

So a switch carrying the stock dialplan will report a clean `CHANNEL_ANSWER`
for any of those extensions **without placing a call at all**. In Phase E this
produced a false positive that was believed for several steps.

The only tells were outside the switch's own event stream:

```text
rtp_remote_sdp_str     = _undef_     no SDP was ever exchanged
peer live channels     = 0           the far end had no call
peer UDP counters      = unchanged   no RTP reached it
```

plus two easily-missed channels in the log, `loopback/voicemail-a` and
`loopback/voicemail-b`.

**RULE: a channel event reports what the LOCAL switch believes. Only the peer's
own state can confirm that a call happened somewhere.** A switch that answers its
own dialstring reports success for anything it has a pattern for.

Concretely, before trusting a call:

* the peer must have a channel of its own
* an SDP must have been exchanged in both directions
* the peer's counters must have moved
* the hangup cause must be a negotiated response, not a routing failure

Phase E replaced the stock context with an explicit one that bridges a
*registered* extension to its contact and returns `404` for an unregistered one.
Voicemail answering was the defect.

### 12.5 Container addresses are not stable, twice over

During Phase E the platform switch's address moved from `172.25.0.4` to
`172.25.0.3` across a Docker restart. Registrations followed automatically,
because the endpoints re-register against whatever the current domain is. A
hard-coded address would have failed as `NO_ROUTE_DESTINATION`, which reads as a
network fault and sends you hunting for one.

So: prefer a **DNS name** where a name is genuinely unambiguous, and
`$${domain}` where the value must be the switch's own identity. And do not use
a shared network alias - both test endpoints answer to `obd-fs-endpoint`, so
which one wins is a property of the resolver, not of your configuration.

### 12.6 Dial strings and diagnostics that mislead

```text
api uuid_broadcast <uuid> <file> aleg   -> -ERR invalid uuid
api uuid_dump <uuid>                    -> CHANNEL_DATA, valid UUID
```

The same UUID, at the same moment, on a live answered channel with a negotiated
SDP. **Unresolved.** Recorded as an open defect because the Java client always
sends the `aleg` form, so if it is real it affects every production playback.
`uuid_dump` working does not prove the channel is suitable for media commands.

### 12.7 Two measurement traps that produced false readings

Both produced a wrong conclusion in this phase and are worth stating as rules.

**`api uuid_dump` with no argument does not list channels.** It returns
`-USAGE: <uuid> [format]`. A regex over that output finds zero UUIDs *always*,
so "the peer has no channels" was reported for a call that was working
perfectly. Use `show channels`, or the peer's own event stream.

**A command helper that already prepends `api ` will produce `api api ...`**
if you include the prefix yourself. The result is
`-ERR api Command not found!`, which reads exactly like a missing API. Check
what your helper sends before concluding the API is absent.

And the standing one from Phase C: **one ESL socket cannot carry a synchronous
command and a subscribed event stream at once.** The command reads the next
event instead of its reply, and every later read is off by one frame. Use two
connections whenever a peer's commands and events are both needed - which is
precisely what peer-verified testing requires.

### 12.8 What Phase E could measure, and what it still could not

Per-stream media counters remain unavailable on this build:

```text
rtp_audio_in_packet_count   = _undef_
rtp_audio_out_packet_count  = _undef_
api uuid_debug_media <uuid> -> -ERR api Command not found!
```

and no packet-capture tool exists in either image (`tcpdump`, `tshark`,
`dumpcap` all absent), with no image pullable because the Docker daemon's DNS is
broken. Per-packet RTP is not logged at debug either.

The measurement that *is* available is the kernel's own UDP counters
(`/proc/net/snmp`) read inside each container - real packet counts, but
per-container rather than per-stream. Sampled either side of a 30-second media
window (a 3-second file is ~400 packets and sits in the noise):

```text
endpoint -> platform : platform InDatagrams +64     the endpoint's own tone
```

Sixty-plus real packets crossed the Docker bridge, unprompted, because the
platform is an outbound channel with no bridge and was only sending - anything
it received necessarily came from the peer. That direction is
**CONFIRMED — LOCAL**. The reverse direction was not established, so
**TWO-WAY AUDIO REMAINS NOT PROVEN**, and the counter method alone would not
have been sufficient to claim it.
