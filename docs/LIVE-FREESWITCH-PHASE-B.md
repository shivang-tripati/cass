# Live FreeSWITCH — Phase B

**Status: PASS** (with one item recorded as PARTIAL — see §11, L2.8)

**What this phase is for, in one line:** stand up a real FreeSWITCH container
and prove, with evidence, that the telephony engine the Java platform depends on
is running correctly and is not exposed to the network.

This document is a *phase* record. It answers "what did we build, why, what did
we prove, and what is left?". For how FreeSWITCH itself works, read
[`freeswitch/`](freeswitch/README.md).

**Scope boundary:** no Java source, test, configuration or migration was
modified. `infra/docker-compose.yml` was not modified. The other conversation's
in-progress VB-7A work was present in the working tree throughout and was not
touched.

---

## 1. Objective

The Java platform already contains a complete, production-shaped FreeSWITCH
integration — an ESL client, an outbound dialer, a media controller, an event
router, hangup-cause mapping. It has been validated only against an in-process
protocol double. Nothing has ever spoken to a real FreeSWITCH.

Phase B exists to close the *infrastructure* half of that gap, and only that
half. By the end of it we wanted to be able to say, with evidence rather than
assumption:

* a real FreeSWITCH is running, and is genuinely healthy rather than merely
  starting;
* the two modules the platform depends on — `mod_event_socket` and `mod_sofia` —
  are loaded;
* the SIP profiles the platform's contract assumes exist, actually exist and
  are bound;
* the gateway the platform will dial through, by name, exists and is loaded;
* ESL is reachable **only** the way it should be, with a credential that is not
  a default and is not in the repository;
* the RTP port range is narrowed and the media directory is mounted, so that
  later phases are not blocked on them;
* every one of those facts is recorded as a command and its output, so a future
  engineer can re-run the checks rather than trust this document.

**What Phase B explicitly is not:** it is not a call. No call is placed, no
audio plays, no DTMF is collected, no bridge is made, and the Java application
is not connected. `telephony.freeswitch.enabled` remains `false`.

---

## 2. Prerequisites

State that had to be true before Phase B could start. Each is CONFIRMED unless
marked otherwise.

| Prerequisite | State |
|---|---|
A FreeSWITCH image is available and appropriate | `ghcr.io/patrickbaus/freeswitch-docker:1.11.1` verified to exist for `linux/amd64`, digest `sha256:8b55a39…` |
The Java contract is known | Phase A audit complete: exact commands, events, dial-string form, UUID strategy, media path and DTMF model all established from source |
A working directory exists for media | `backend/data/audio` created (it did not exist) |
Secrets are not committed | `infra/.env` already git-ignored by `infra/.gitignore` |
Docker is available and usable | Docker Desktop 29.1.3 on WSL2, `linux/amd64` — but see the **blocker** below |
Existing infrastructure must not be disturbed | `infra/docker-compose.yml` under a separate Compose project name, on a separate Docker network |

**The prerequisite that did not hold:** the Docker daemon could not resolve
**any** registry, so no image could be pulled. Resolved without touching global
configuration — see §7, problem 8, and
[`freeswitch/13-TROUBLESHOOTING.md §12`](freeswitch/13-TROUBLESHOOTING.md).

---

## 3. Architecture

What Phase B produced, in full.

```text
  Windows host  (Docker Desktop on WSL2, linux/amd64)
  │
  │   Docker Compose project "obd-freeswitch"  (separate from "infra")
  │
  └── network  obd-telephony  (bridge)
      │
      └── obd-freeswitch   172.25.0.2/16   hostname: freeswitch
          │
          ├── entrypoint  /bin/sh /opt/obd/entrypoint.sh
          │     · renders the ESL credential from the environment
          │     · refuses known-weak secrets
          │     · fails if the media mount is missing
          │     · exec /usr/bin/freeswitch   (FreeSWITCH becomes PID 1)
          │
          ├── FreeSWITCH 1.11.1-release 64bit
          │     │
          │     ├── mod_event_socket
          │     │     ESL listener  127.0.0.1:8021
          │     │     apply-inbound-acl=obd-dev-acl
          │     │     password rendered from $FREESWITCH_PASSWORD
          │     │
          │     └── mod_sofia
          │           ├── profile "internal"  :5060 udp+tcp
          │           ├── profile "external"  :5080 udp+tcp
          │           └── gateway "fs-gateway"  (profile external)
          │                 proxy   sip:freeswitch-provider:5080
          │                 register false      state NOREG
          │                 ▲ FUTURE: the provider instance
          │
          ├── /media/obd        ← bind mount, read-only
          │     from  ../../backend/data/audio
          │
          └── /var/log/freeswitch   ← bind mount
                from  ../../infra/freeswitch/logs
                (holds freeswitch.log and the compiled freeswitch.xml.fsxml)

  published to 127.0.0.1 only:
      8021/tcp              ESL
      5060/udp, 5060/tcp    internal SIP
      5080/udp, 5080/tcp    external SIP
      30000-30099/udp       RTP  (100 ports, not the stock 16,384)
```

**NOT PRESENT and deliberately so:** the Java application, a softphone, the
provider FreeSWITCH, any call, any audio, any SIP registration, any published
port on `0.0.0.0`.

Full architecture with per-component failure modes:
[`freeswitch/01-ARCHITECTURE.md`](freeswitch/01-ARCHITECTURE.md).

---

## 4. What Was Implemented

### Files created

```text
infra/docker-compose.freeswitch.yml
infra/freeswitch/conf/autoload_configs/event_socket.conf.xml.in   (TEMPLATE)
infra/freeswitch/conf/autoload_configs/acl.conf.xml
infra/freeswitch/conf/autoload_configs/sofia.conf.xml
infra/freeswitch/conf/autoload_configs/switch.conf.xml
infra/freeswitch/conf/sip_profiles/internal.xml
infra/freeswitch/conf/sip_profiles/external.xml
infra/freeswitch/gateway/fs-gateway.xml
infra/freeswitch/scripts/entrypoint.sh
infra/freeswitch/logs/.gitignore
backend/data/audio/.gitignore
```

### Files modified

```text
infra/.env.example          +32 lines, placeholders only, no secret
infra/.env                  local secret added; git-ignored
```

### Files explicitly NOT modified

```text
infra/docker-compose.yml         verified byte-identical (git diff empty)
backend/src/**                   no Java source or test touched
backend/src/main/resources/**    no application configuration touched
database migrations              untouched
```

### FreeSWITCH configuration changed

| File | Change |
|---|---|
`event_socket.conf.xml` | `listen-ip` `::` → `127.0.0.1`; `password` `ClueCon` → rendered secret; added `apply-inbound-acl`, `stop-on-bind-error` |
`acl.conf.xml` | added `obd-dev-acl` (loopback + RFC1918, default deny) |
`sofia.conf.xml` | replaced the `*.xml` glob with two explicit profile includes |
`switch.conf.xml` | set the RTP range that stock leaves commented out; `switchname`; `max-sessions`; `loglevel=info` |
`sip_profiles/internal.xml` | replaced the 22 KB stock file with a minimal profile; removed TLS, websocket, presence; `liberal-dtmf`, `proxy-info` on; SIP auth off |
`sip_profiles/external.xml` | same, minimal, plus the `<gateways>` block that carries `fs-gateway` |
`gateway/fs-gateway.xml` | new: `proxy`, `register=false`, `sofia-profile=external` |

### Modules involved

Loaded and used: `mod_event_socket`, `mod_sofia`, `mod_commands`, `mod_dptools`.
Loaded but unused: `mod_loopback`, `mod_spandsp`, `mod_verto`.
Failing to load (pre-existing image defect, harmless): `mod_av`.
Deliberately not loaded: `mod_xml_curl`, `mod_signalwire` (already commented
out in the stock module list).

### Docker changes

One new Compose project (`obd-freeswitch`), one new network (`obd-telephony`),
one service, four bind mounts, one named host directory created.

### Networking changes

Seven port mappings, all loopback-bound. One Docker network, deliberately not
shared with the existing `obd-network` used by PostgreSQL and Redis.

### Commands introduced

```text
docker compose -f infra/docker-compose.freeswitch.yml up -d | ps | logs | down
docker exec obd-freeswitch fs_cli -H 127.0.0.1 -P 8021 -p <secret> -x "<api>"
docker exec obd-freeswitch netstat -tulnp
docker inspect obd-freeswitch --format '{{.State.Health.Status}}'
docker network inspect obd-telephony
```

---

## 5. WHY — the decisions

Each decision in the form: decision, why, alternative, why rejected,
consequence.

### 5.1 A separate Compose file and a separate project name

**Decision.** `infra/docker-compose.freeswitch.yml`, with
`name: obd-freeswitch` at the top of the file.

**Why.** The existing `infra/docker-compose.yml` provides PostgreSQL, Redis and
RustFS and is used by other work. A second Compose file in the same directory
would otherwise default to the **same project name** (`infra`), which makes
Compose treat the two sets of services as one project — so `down` on one could
take down the other, and `up` would warn about orphans.

**Alternative rejected.** Adding a service to the existing file. Forbidden by
the phase scope, and it would couple the telephony environment's lifecycle to
the database's.

**Consequence.** Two independent stacks that cannot interfere. A reader must
know to pass `-f infra/docker-compose.freeswitch.yml`; the file's header
comment says so.

### 5.2 Bridge networking, not `network_mode: host`

**Decision.** A private bridge network, `obd-telephony`.

**Why.** Three reasons, in order of weight.

1. On Docker Desktop, `host` means the **WSL2 VM's** network namespace, not
   Windows. Windows↔VM port-forwarding behaviour is version-dependent and
   would need re-verification on every Docker Desktop upgrade.
2. Publishing the stock 16,384-port RTP range through Docker's port proxy is
   slow and is exactly the exposure the plan forbids. Narrowing the range to
   100 ports makes the published surface trivial to reason about.
3. **Container-to-container media never crosses the host boundary.** When the
   provider instance is added, RTP between the two switches stays inside the
   Docker network and needs no published port at all. The published RTP range
   exists only for a Windows-side softphone.

**Alternatives rejected.** `network_mode: host` (the image author's
recommendation) — accepted only as a fallback, and only with recorded evidence
that bridge fails. Publishing the full stock RTP range — an order of magnitude
more host exposure for no development benefit.

**Consequence.** A softphone test will need attention to SDP-advertised
addresses, because FreeSWITCH advertises its `172.25.0.2` container address. That
is a known Phase C/D area, recorded rather than pre-emptively solved.

### 5.3 A template plus a start-up renderer, instead of a config file with a password

**Decision.** Commit `event_socket.conf.xml.in` containing a substitution
token. An entrypoint renders it into the live config path at container start,
using `$FREESWITCH_PASSWORD`.

**Why.** FreeSWITCH XML has no reliable environment interpolation for this
value. Every alternative leaves a credential — or a usable placeholder — in the
repository. Rendering at start-up means the committed artefact contains **no
credential at all**, and there is no default to fall back to.

**Alternatives rejected.**

* *Commit a config file with a placeholder password.* FreeSWITCH reads the
  committed file directly, so the placeholder becomes a working password.
* *Commit a "development only" real-looking password.* Permanently in git
  history, and exactly the `fs-password` mistake already present in the
  platform's YAML.
* *Docker secrets / a secret manager.* Targets orchestrators this project has
  ruled out. Revisit if production is ever containerised with one.

**Consequence.** Rotation is one line in `infra/.env` plus a recreate. The
template carries an editing rule — the token must appear **exactly once** — and
that rule exists because breaking it leaked the live secret into the rendered
file during this phase (§7, problem 1).

### 5.4 An entrypoint that fails fast and then `exec`s

**Decision.** A four-step entrypoint: validate the credential, verify the media
mount, render the ESL config, `exec /usr/bin/freeswitch`.

**Why.** Each step converts a silent failure into a loud one.

| Check | Silent failure it prevents |
|---|---|
credential present, ≥16 chars, not a known default | ESL running with a guessable or placeholder password |
`/media/obd` exists | playback failing at campaign time with an opaque `PLAYBACK_ERROR` |
no unsubstituted token survives | a partially-rendered config being used as if it were correct |
`exec` at the end | PID 1 being a shell, so `SIGTERM` is swallowed and every stop is an ungraceful kill |

**Alternatives rejected.** Configuring FreeSWITCH directly from the environment
— not reliably supported. Skipping the checks for a "development" environment —
which is precisely how the hardening ended up silently not in effect (§7,
problem 6).

**Consequence.** The container refuses to start in several conditions that would
otherwise be invisible. It also means the Compose file **must** declare
`entrypoint:`, and omitting it is a silent security regression — which happened.

### 5.5 Explicit profile list instead of a glob

**Decision.** `sofia.conf.xml` names `internal.xml` and `external.xml`
individually.

**Why.** The stock glob `../sip_profiles/*.xml` also loads
`internal-ipv6.xml` and `external-ipv6.xml`, which bind `::1:5060` and
`::1:5080` — the exact ports needed — and which this project does not want. IPv6
SIP is out of scope: no TLS listener, no websocket binding, no IPv6 endpoint.

**Alternatives rejected.** Deleting the IPv6 profile files from the image — not
possible without a rebuild, and fragile. Leaving the glob and hoping the ports
do not clash — the clash is real and observed.

**Consequence.** Exactly `2 profiles 0 aliases`, and the two IPv6 listeners are
gone. A future profile must be added to the list explicitly, which is the
desired behaviour: the running configuration is auditable.

### 5.6 Minimal profile files rather than the stock ones

**Decision.** Hand-written ~100-line profiles in place of the stock 22 KB
`internal.xml`.

**Why.** The stock file is mostly documentation, and it carries settings this
project must not have: TLS listeners, websocket bindings on 5066 and 7443,
presence, and a large surface of unexamined defaults. Replacing it makes every
active setting visible and reviewable.

**Alternatives rejected.** Patching the stock file with a diff — the diff would
be applied on every container recreate, and the result would still be hard to
read. Leaving it stock and accepting the listeners — unacceptable, they are
unplanned attack surface on a SIP service.

**Consequence.** `netstat` shows exactly the intended listeners. The cost is
that a stock setting we did not think about is not present, so a future need
must be added deliberately.

### 5.7 `sip-ip` and `rtp-ip` deliberately unset

**Decision.** Neither address is pinned in either profile.

**Why.** Unset, the SIP listener binds all interfaces inside the container —
so it is reachable from the Docker network and through the published ports —
while a real address is still auto-detected for the SDP connection line. Pinning
`rtp-ip` to `0.0.0.0` would advertise `0.0.0.0` in SDP and break media for
every call; pinning it to the container address would break any host-side peer
that cannot route to `172.25.x`.

**Alternatives rejected.** `sip-ip=0.0.0.0` explicitly — same effect for SIP,
but pointless indirection. Pinning `rtp-ip` — breaks media, as above.

**Consequence.** `sofia status` reports `RTP-IP 172.25.0.2` and
`SIP-IP 172.25.0.2`, which is the correct auto-detected value. The host-softphone
SDP problem remains and is documented as a known Phase C/D item.

### 5.8 `liberal-dtmf` and `proxy-info` on, with no DTMF collection configured

**Decision.** Both set to `true` on both profiles; no
`play_and_collect_digits`, no `read`, no `start_dtmf` anywhere.

**Why.** The Java platform collects DTMF **passively**: it plays a prompt, then
waits for `CHANNEL_DTMF`. It never asks FreeSWITCH to collect digits — the
`collectDtmf` method throws `UnsupportedOperationException` and is never called
from production code. So FreeSWITCH must *emit* the event and must not run a
digit-collection application.

`liberal-dtmf=true` is required because the stock default is `false`, which
accepts DTMF only via the profile's preferred method. An endpoint sending SIP
INFO when FreeSWITCH expects RFC 2833 is **silently ignored** and Java receives
nothing. Later testing deliberately covers both transports, so both must be
accepted.

`proxy-info=true` is required because the stock default `false` consumes inbound
SIP INFO locally instead of relaying it across a bridge. Once an agent is
bridged to a customer, the agent's keypresses would be swallowed.

**Alternatives rejected.** *Configuring a dialplan to collect digits* — it
would consume them before Java sees them and would change the A-leg media model
that `aleg` playback depends on. *Leaving the defaults* — one of the two DTMF
transports would fail silently in a way that looks like a Java bug.

**Consequence.** Both DTMF transports can be tested later, and agent DTMF
survives a bridge. No digits are consumed anywhere. **NOT YET TESTED** — this is
a hypothesis until a real endpoint presses a key.

### 5.9 SIP authentication disabled for Phase B

**Decision.** `auth-calls=false` and `auth-subscriptions=false`.

**Why.** Nothing registers in Phase B, and the stock FreeSWITCH directory ships
`default_password=1234` (`vars.xml`). Enabling registration now would mean
accepting a publicly known credential in exchange for a capability nothing uses.

**Alternatives rejected.** *Enabling registration with a strong new directory* —
scope creep; a directory belongs with the endpoints that need it. *Leaving
`auth-subscriptions` at its stock value* — that would accept `1234`.

**Consequence.** A softphone **cannot** register in Phase B. That is intended
and is a documented known limitation. Phase C introduces real extensions with
real per-extension secrets before enabling it.

### 5.10 One gateway name, `fs-gateway`

**Decision.** Exactly one gateway, named `fs-gateway`.

**Why.** The platform resolves the gateway from **two different places**:

* campaign outbound, from `sip_gateways.free_switch_gateway_name`;
* the agent leg, which is originated with a **null** gateway name and therefore
  falls back to `telephony.freeswitch.gateway`, whose development default is
  `fs-gateway`.

One physical gateway serves both, and the future database seed and environment
variable will use the same string.

**Alternatives rejected.** *Two gateways, one per path* — more to keep aligned,
with no benefit. *A name that encodes the role* — the name is a contract held by
the database column and the environment variable, so a descriptive name would
have to be spelled identically in three places.

**Consequence.** `fs-gateway` is the single value the later seed must use. It is
documented in `.env.example` at the point where the application settings are
listed.

### 5.11 `register="false"` on the gateway

**Decision.** Explicit `register=false`.

**Why.** `mod_sofia` **defaults `register` to `true`**
(`sofia_gateway.c`: `char *register_str = "true"`). With the default, the
gateway immediately attempts to REGISTER against a provider that does not exist,
fails, and mod_sofia **discards it** — so the gateway vanishes with no error in
the log. This cost the longest single debugging session of the phase; see §7,
problem 2.

**Alternatives rejected.** *Omitting the parameter* — the default is `true`.
*Adding real credentials* — there is no provider, and a fabricated
registration would be a lie.

**Consequence.** The gateway is a static, origination-only trunk whose state is
`NOREG` — which is **correct**, not a fault. Registration is intentionally not
tested, and no success is fabricated.

### 5.12 The gateway declared inside the profile

**Decision.** The gateway is included from the `external` profile's
`<gateways>` block, not from a `sip_profiles/external/gateway/` directory.

**Why.** Read from the FreeSWITCH 1.11.1 source: `mod_sofia` contains **no**
per-profile gateway-directory scan, and `switch_xml.c` contains no `gateway` or
`sip_profiles` reference at all. Gateways resolve only as
`sofia.conf → profiles → profile[@name] → gateways → gateway`. The
widely-documented directory convention is not read by this version.

**Alternatives rejected.** *The conventional directory* — verified not to work
here. *A `<gateways>` block in `sofia.conf.xml`* — compiles cleanly and is
silently ignored, because the block must be a child of the *profile* node.

**Consequence.** A structural requirement that is invisible in a config file
and produces no error when wrong. It is now documented in the profile itself,
in the gateway file, in
[`freeswitch/03-CONFIGURATION.md §5`](freeswitch/03-CONFIGURATION.md) and in
troubleshooting entry 9.

### 5.13 A narrow RTP range, and an honest statement about it

**Decision.** `rtp-start-port 30000`, `rtp-end-port 30099`, and exactly that
100-port range published to `127.0.0.1`.

**Why.** In the stock `switch.conf.xml` the RTP range is **commented out**, so
FreeSWITCH silently uses its compiled-in default of 16384-32768 — 16,384 ports
(`switch_rtp.c`). Publishing that through Docker's port proxy is slow and is an
unnecessarily large host exposure. 100 ports is ample for local development.

**Alternatives rejected.** *Leaving the stock default commented out* — an
accidental 16,384-port exposure. *Publishing the full range* — same.

**Consequence.** The configuration is in place and in the file FreeSWITCH
actually reads. The **effective** range is not observable at rest: FreeSWITCH
1.11 exposes no `fs_cli` API for `switch_rtp_get_start_port()` (three access
routes checked, all unavailable), and ports are allocated only when a channel
needs media. Recorded as PARTIAL rather than PASS, and to be confirmed at the
first live call.

### 5.14 Media mounted at `/media/obd`, read-only

**Decision.** `backend/data/audio` → `/media/obd`, `:ro`.

**Why.** Three reasons, each with a cost. A bind mount is the shortest path from
"a file the application wrote" to "a path FreeSWITCH can open", and it is
inspectable from both sides. Read-only turns any accidental write into a loud
failure rather than silent corruption of application data. And `/media/obd`
rather than the sounds directory keeps the vendor's `moh` sound tree — which both
profiles reference via `local_stream://moh` — intact.

**Alternatives rejected.** *Mounting over `/usr/share/freeswitch/sounds`* — it
would make the default root correct with no configuration, and it would break
hold music and every stock prompt. *Object storage via the existing RustFS* —
explicitly deferred; it would make every media debugging session require HTTP
tooling. *Copying files into the container* — two copies and guaranteed drift.

**Consequence.** Media is single-host only, which is a real limitation before
any multi-host deployment. The application must be told the root is
`/media/obd`; `application-dev.yaml` has no key for it, so that is done with
`SPRING_APPLICATION_JSON` or a command-line property and **no Java change** is
required. Recorded as a known gap.

Full reasoning: [`decisions/ADR-003`](freeswitch/decisions/ADR-003-shared-media-volume.md).

### 5.15 A healthcheck that asks FreeSWITCH, not the socket

**Decision.** `fs_cli -p "$FREESWITCH_PASSWORD" -x status | grep -q '^UP'`.

**Why.** A TCP-port probe would report healthy while FreeSWITCH was up but
unusable — no profiles, no modules, or a broken configuration. Asking
FreeSWITCH for its own state is the only check that means what the word means.
The password must be passed explicitly because the stock one is no longer in
use.

**Alternatives rejected.** *TCP probe* — proves nothing about FreeSWITCH. *The
image's own healthcheck* — it invokes `fs_cli` without a password, which cannot
authenticate now that the credential is randomised.

**Consequence.** `start_period` is 40 s, because stock `vars.xml` performs two
5-second STUN lookups that time out. See §10.

---

## 6. Evidence

Every check, with the command and its output. Reproduce with the script
described in
[`freeswitch/11-DEBUGGING.md`](freeswitch/11-DEBUGGING.md).

### 6.1 Container and process

```text
$ docker compose -f infra/docker-compose.freeswitch.yml ps
NAME             IMAGE                                          STATE     STATUS
obd-freeswitch   ghcr.io/patrickbaus/freeswitch-docker:1.11.1   running   Up About a minute (healthy)

$ docker inspect obd-freeswitch --format '{{.State.Status}} {{.RestartCount}}'
running 0

$ docker inspect obd-freeswitch --format '{{.State.Health.Status}}'
healthy
```

**Interpretation.** Running, zero restarts (no crash loop), and the healthcheck
— which requires FreeSWITCH to report `UP` — is passing.

### 6.2 FreeSWITCH state

```text
$ docker exec obd-freeswitch fs_cli -H 127.0.0.1 -P 8021 -p <secret> -x status
UP 0 years, 0 days, 0 hours, 0 minutes, 50 seconds, 472 milliseconds, 865 microseconds
FreeSWITCH (Version 1.11.1-release  64bit) is ready
0 session(s) since startup
0 session(s) - peak 0, last 5min 0
0 session(s) per Sec out of max 30, peak 0, last 5min 0
1000 session(s) max
min idle cpu 0.00/93.63
Current Stack Size/Max 240K/8192K
```

**Interpretation.** `UP` on the first line — the healthcheck's criterion.
Version confirmed as 1.11.1. Zero sessions, as expected with no calls.

### 6.3 Modules

```text
$ docker exec obd-freeswitch fs_cli -H 127.0.0.1 -P 8021 -p <secret> -x "module_exists mod_event_socket"
true
$ ... -x "module_exists mod_sofia"
true
```

**Interpretation.** Both modules the platform depends on are loaded.

### 6.4 ESL security

```text
$ docker exec obd-freeswitch netstat -tulnp | grep 8021
tcp  0  0  127.0.0.1:8021  0.0.0.0:*  LISTEN  1/freeswitch

$ docker exec obd-freeswitch grep -e listen-ip -e listen-port -e apply-inbound-acl \
      -e stop-on-bind-error /etc/freeswitch/autoload_configs/event_socket.conf.xml
        <param name="listen-ip" value="127.0.0.1"/>
        <param name="listen-port" value="8021"/>
        <param name="apply-inbound-acl" value="obd-dev-acl"/>
        <param name="stop-on-bind-error" value="true"/>

occurrences of the live secret in the rendered file : 1   (the password param)
occurrences of the stock default credential        : 0
occurrences of the platform dev default credential : 0

$ docker port obd-freeswitch | grep 8021
8021/tcp -> 127.0.0.1:8021
mappings NOT on 127.0.0.1 (of 105 total) : 0
```

**Interpretation.** Loopback inside the container and loopback on the host. The
secret appears exactly once — the password parameter — and neither the stock nor
the platform default is present. The stock `listen-ip` was `::`, i.e. every
interface.

### 6.5 SIP profiles

```text
$ docker exec obd-freeswitch fs_cli -H 127.0.0.1 -P 8021 -p <secret> -x "sofia status"
          Name      Type      Data                                            State
 =============================================================================================
       external  profile    sip:mod_sofia@172.25.0.2:5080                     RUNNING (0)
 internal  profile    sip:mod_sofia@172.25.0.2:5060                           RUNNING (0)
 =============================================================================================
 2 profiles 0 aliases

$ ... -x "sofia status profile internal"
    URL               sip:mod_sofia@172.25.0.2:5060
    BIND-URL          sip:mod_sofia@172.25.0.2:5060;transport=udp,tcp
    Context           public
    CODECS IN         PCMU,PCMA,G722,OPUS
    CODECS OUT        PCMU,PCMA,G722,OPUS
    TEL-EVENT         101
    DTMF-MODE         rfc2833
    REGISTRATIONS     0
```

**Interpretation.** Exactly the two intended profiles, both `RUNNING`, each
bound to the container address on the expected port and transport. IPv4 only —
the stock IPv6 profiles are absent. `(0)` is the registration count, expected
zero. `DTMF-MODE rfc2833` is the profile's *preference*; `liberal-dtmf=true`
means it is not a restriction (the profile file is the authority for that, not
this output).

### 6.6 Gateway

```text
$ docker exec obd-freeswitch fs_cli -H 127.0.0.1 -P 8021 -p <secret> -x "sofia status gateway"
    Profile::Gateway-Name   Data                                       State   Ping Time  IB  OB
 ==========================================================================================
 external::fs-gateway      sip:FreeSWITCH@freeswitch-provider:5080     NOREG   0.00       0/0 0/0
 ==========================================================================================
 1 gateway: Inbound(Failed/Total): 0/0,Outbound(Failed/Total):0/0

$ ... -x "sofia status gateway fs-gateway"
Name    fs-gateway
Profile external
Scheme  Digest
Realm   freeswitch-provider:5080
Username FreeSWITCH
Password no
Proxy   sip:freeswitch-provider:5080
State   NOREG
Status  UP
Uptime  60s
CallsIN 0
CallsOUT 0
```

**Interpretation.** The gateway is **loaded**, bound to the **external**
profile, pointed at the future provider, holding **no credentials**, and in
state **`NOREG`** — which is the correct and intended state for a static
origination-only trunk with `register=false`. Registration is intentionally not
performed and no success is fabricated.

### 6.7 Media

```text
$ docker exec obd-freeswitch ls -ld /media/obd
drwxrwxrwx 1 root root 4096 Sep 28 03:52 /media/obd

$ docker exec obd-freeswitch touch /media/obd/.probe
touch: /media/obd/.probe: Read-only file system

$ docker compose -f infra/docker-compose.freeswitch.yml config | grep -A1 backend.data.audio
  source: D:\work\agile\obd-platform\backend\data\audio
  target: /media/obd
```

**Interpretation.** The mount resolves to the correct host path — verified via
`docker compose config` specifically to catch a wrong relative-path resolution
— and is read-only as intended. No audio files exist yet; creating them is the
PLAYFILE phase's business.

### 6.8 RTP

```text
$ docker exec obd-freeswitch grep -e rtp-start-port -e rtp-end-port \
      /etc/freeswitch/autoload_configs/switch.conf.xml
        <param name="rtp-start-port" value="30000"/>
        <param name="rtp-end-port" value="30099"/>

$ docker exec obd-freeswitch fs_cli -H 127.0.0.1 -P 8021 -p <secret> -x status | grep 'session(s) max'
1000 session(s) max
```

**Interpretation, stated precisely.** The values are in the configuration file
FreeSWITCH is reading. To prove the *file* is read rather than falling back,
`max-sessions` was temporarily set to `42` and `fs_cli` reported
`42 session(s) max` — a value only that file can produce. It was then reverted
to `1000`, as shown above. The **effective** allocation range is **not
observable at rest**; see §11, L2.8.

### 6.9 Listeners and network

```text
$ docker exec obd-freeswitch netstat -tulnp | grep freeswitch
tcp  0 0 172.25.0.2:8082   0.0.0.0:*  LISTEN  1/freeswitch
tcp  0 0 172.25.0.2:8081   0.0.0.0:*  LISTEN  1/freeswitch
tcp  0 0 127.0.0.1:8021    0.0.0.0:*  LISTEN  1/freeswitch
tcp  0 0 172.25.0.2:5080   0.0.0.0:*  LISTEN  1/freeswitch
tcp  0 0 172.25.0.2:5060   0.0.0.0:*  LISTEN  1/freeswitch
tcp  0 0 ::1:8082          :::*        LISTEN  1/freeswitch
tcp  0 0 ::1:8081          :::*        LISTEN  1/freeswitch
udp  0 0 172.25.0.2:5060   0.0.0.0:*            1/freeswitch
udp  0 0 172.25.0.2:5080   0.0.0.0:*            1/freeswitch
udp  0 0 0.0.0.0:1337     0.0.0.0:*            1/freeswitch

$ docker exec obd-freeswitch netstat -tulnp | grep -E ':(5061|5081|5066|7443|21|2222) '
(no output)

$ docker network inspect obd-telephony
network name   : obd-telephony
network driver : bridge
attached: obd-freeswitch  ip=172.25.0.2/16

published mappings: 105   (tcp 3, udp 102)
mappings NOT on 127.0.0.1: 0
```

**Interpretation.** Only the intended SIP ports, ESL, and two management
interfaces (8081/8082 mod_sofia, 1337/udp mod_verto) — all unpublished, all
inside the container. The out-of-scope ports 5061, 5081, 5066, 7443, 21 and
2222 are **not bound at all**, which the stock configuration would have done for
5066 and 7443.

### 6.10 Git safety

```text
$ git diff -- infra/docker-compose.yml
(empty)

$ git status --short | grep -E 'infra/|backend/data'
 M infra/.env.example
?? backend/data/
?? docs/LIVE-FREESWITCH-PHASE-B.md
?? docs/freeswitch/
?? infra/docker-compose.freeswitch.yml
?? infra/freeswitch/

$ git check-ignore -v infra/.env
infra/.gitignore:1:.env      infra/.env
```

**Interpretation.** The existing Compose file is byte-identical. Everything
this phase added is additive. The local secret is ignored and untracked.

---

## 7. Problems encountered

Eight real problems, all resolved. Full entries with the required structure in
[`freeswitch/13-TROUBLESHOOTING.md`](freeswitch/13-TROUBLESHOOTING.md). Listed
here with a one-line cause and the fix.

| # | Problem | One-line root cause | Fix |
|---|---|---|---|
1 | ESL secret written into the rendered config as prose, and printed to a terminal | `sed 's/…/…/g'` replaced **every** occurrence of the token, and the template's comment contained it | token restricted to one occurrence; secret **rotated**; verification changed to compare counts, never print values |
2 | Gateway invisible — `0 gateways`, `Invalid Gateway!`, no log entry | `mod_sofia` defaults `register` to **`true`**; registration failed and the gateway was discarded | `<param name="register" value="false"/>` |
3 | Sofia profiles silently ignored — `0 profiles` | root element was `<configuration name="sofia.conf">` instead of `<profile name="...">` | root element corrected; stock IPv6 profiles excluded by explicit includes |
4 | Container crash loop — `unclosed <!--` | an `<!--` **nested inside** an existing comment; XML comments cannot nest | comment rewritten; a nesting-aware scan added to the checks |
5 | Glob re-injected by a comment | FreeSWITCH's preprocessor executes its directives in **raw text**, including inside XML comments | the literal directive removed from all comments; a lint rule added |
6 | ESL hardening silently not in effect | the entrypoint was mounted but `entrypoint:` was never set in Compose, so the image's own `CMD` ran | `entrypoint:` added; the entrypoint now prints a banner so its absence is visible |
7 | Stock IPv6 profiles and websocket bindings created unplanned listeners | `sofia.conf.xml` used a glob; the stock internal profile binds 5066 and 7443 | explicit profile list; TLS and websocket parameters omitted |
8 | `docker pull` failed for **every** registry | the WSL2 resolver `10.255.255.254` is unreachable from the Docker daemon, while the Windows host resolves fine | image fetched over the host network and `docker load`ed; no global config changed, no restart, nothing pruned |

---

## 8. Root cause analysis

The full symptom → cause → diagnosis → fix → verification → production-
recognition chain for each is in
[`freeswitch/13-TROUBLESHOOTING.md`](freeswitch/13-TROUBLESHOOTING.md). Three
deserve emphasis here because the *reasoning* generalises.

### 8.1 Silence is the hardest failure mode

Three of the eight problems presented as **absence**, not error: no gateway, no
profile, and hardening that was not applied. FreeSWITCH accepted every one of
those configurations without complaint, and the container reported itself
healthy.

The technique that finally broke the gateway deadlock was to **make
FreeSWITCH complain deliberately**: renaming the gateway to a value invalid
under mod_sofia's own `^[\w\.\-\_]+$` rule produced
`[ERR] sofia.c:3773 Ignoring invalid name 'fs gateway PROBE'` — proving
`parse_gateways` was being reached all along, and eliminating every theory about
configuration location. Read the source next and the default appeared.

**Lesson: when a configured object is absent, suspect a default that discards
it. And when reasoning stalls, make the system emit an error on purpose.**

### 8.2 "The file is correct" and "a module read the file" are different claims

The compiled configuration is a flat concatenation of many
`<configuration name="…">` roots from many files, and a module looks up **its
own** file by name. A file can be present, valid, included, and present in the
compiled document — and still not be the node the module reads.

The verification technique that resolves this: change one value to something
unmistakable that FreeSWITCH **reports back** (`max-sessions 1000 → 42`), observe
it, then revert. Apply it only to a value FreeSWITCH echoes — never to
something the product depends on.

**Lesson: assert the control, not the intent.** A control without a
verification is a comment.

### 8.3 Documentation is not the specification, and memory is not either

Two of the eight problems came from assuming FreeSWITCH worked a particular
way. The gateway location and the profile root element were both wrong. The
authoritative references were the **stock files inside the image** and the
**FreeSWITCH 1.11.1 source**, neither of which matched the assumption.

**Lesson: when a configuration convention does not behave as documented,
verify against the source of the exact version running.** Read what is in front
of you before you debug it.

---

## 9. Lessons learned

1. **Assert every control.** The ESL hardening was written, mounted, and not in
   effect, with a healthy container throughout. Every security control now ships
   with the check that proves it is active.
2. **Make it impossible to commit a secret.** The template approach means there
   is no credential in the repository at all — not even a working placeholder.
3. **A default is a decision you did not make.** `register=true` and a commented
   RTP range are both stock defaults that silently produced the wrong behaviour.
   Set both explicitly.
4. **Structure errors are silent; read the stock files.** Both structural
   mistakes were resolved by reading a stock file in the image, not by reasoning.
5. **Count markers only with a nesting-aware check.** A plain `<!--`/`-->`
   count reports nested comments as balanced.
6. **The preprocessor runs before the parser.** A directive inside a comment is
   still a directive. Never paste one into prose.
7. **Change one thing, then retest.** Most of the wasted time in this phase came
   from changing configuration and restarting several times before identifying
   the single relevant change.
8. **Test structure and value separately.** A file can be perfectly valid XML
   and structurally wrong for its purpose.
9. **A "silent" failure is a finding in itself.** "The log says nothing about
   gateways" was the clue that pointed at a deliberate discard.
10. **Verify relative paths through the tool, not by inspection.** The media
    mount resolves relative to the Compose file's directory, not the shell's.
    `docker compose config` proves it.

---

## 10. Remaining risks

### Known limitations of this environment

| Risk | Impact | Mitigation / status |
|---|---|---|
Stock `vars.xml` performs two `stun-set` lookups | ~10 s extra startup; two `ERR` lines in the log | **Not fixed.** Suppressing them means replacing the 19 KB `vars.xml` the untouched directory and dialplan depend on. The healthcheck's 40 s `start_period` covers it. Not a fault. |
Stock directory ships `default_password=1234` | a well-known credential exists in the image | **Mitigated by disabling SIP registration entirely.** Phase C must add real extensions with real secrets before enabling it. |
`mod_av` fails to load (`libavformat.so.62` missing) | a `[CRIT]` line at every startup | **Pre-existing image defect**, harmless, nothing planned needs it. Documented so it is not mistaken for a regression. |
`SCHED_FIFO` / `SCHED_OTHER` not implemented | two `ERROR` lines at startup | Host kernel/WSL2 limitation. FreeSWITCH continues. |
Container runs as **root** | larger blast radius if the container is compromised | Accepted for an isolated dev container on a private bridge network, and **documented rather than silently accepted**. Production must run non-root. |
Image is 1.11.1; upstream stable is 1.11.3 | missing post-1.11.1 security hardening | **Accepted and disclosed.** 1.11.1 does fix CVE-2026-49840, the pre-auth ESL `Content-Length` heap overflow. Building 1.11.3 is a later option, not a Phase B task. |
`backend/data/audio` holds runtime audio with no backup | an environment without a media backup | Out of scope; single-host development only |

### Untested behaviour

Everything call-related. No call, no audio, no DTMF, no bridge, no hangup cause,
no Java connection, no softphone, no provider. Enumerated in §1 and tracked as
**NOT YET TESTED** throughout
[`freeswitch/`](freeswitch/README.md).

Specifically outstanding, and known to be non-trivial:

* a host softphone's SDP-advertised address (FreeSWITCH advertises
  `172.25.0.2`, which a Windows host may not route to);
* whether `DTMF-Digit` header casing and value format match what the Java
  platform expects, captured from a real server;
* the real `Playback-Error` text FreeSWITCH emits for a bad path — the
  project's own tests assume values like `FILE_NOT_FOUND` that have never been
  seen from a real server;
* the `sofia/gateway/<gw>/<user@host>` dial-string form, which
  `FreeSwitchAgentLegDialer`'s own javadoc contradicts and which must be tested
  before anyone concludes it is a defect;
* the effective RTP allocation range;
* whether a host-side softphone can register at all once Phase C enables
  registration.

### Environment-specific assumptions

* Docker Desktop on WSL2 behaves differently from Docker on Linux. Specifically:
  `host` networking means the VM's namespace, and the daemon's DNS is currently
  broken (§7 problem 8).
* **Disk pressure.** `C:` had 9.4 GB free, with 21 GB of reclaimable images and
  11.8 GB of build cache. Nothing was pruned, and nothing should be without
  explicit approval — other projects share this machine.
* The two existing projects on this machine (`infra` for the database,
  `auth-starter`) were left running and untouched throughout.

### Production differences

This environment assumes loopback-only access and a single host. Production
requires, at minimum: SIP behind a firewall with provider address ranges
allowed; registration with per-extension secrets, rate limiting and lockout; a
real carrier trunk with credentials from a secret manager; TLS; a
SIP-aware firewall managing the RTP range; a non-root container; log shipping
with retention and redaction; secret management rather than a file; and
alerting on the failure classes the later phase will define. Enumerated in
[`freeswitch/14-SECURITY.md §10`](freeswitch/14-SECURITY.md).

---

## 11. Validation matrix

| Check | Requirement | Result | Evidence |
|---|---|---|---|
| L1.1 | Container starts | **PASS** | `State=running RestartCount=0`; `ps` → `running` |
| L1.2 | Healthcheck healthy | **PASS** | `Health=healthy`; test = `fs_cli -p <secret> -x status \| grep -q '^UP'`, i.e. it asks FreeSWITCH for its own state, not a TCP probe |
| L1.3 | FreeSWITCH status UP | **PASS** | `UP 0 years, 0 days, 0 hours, 0 minutes, 50 seconds…` / `FreeSWITCH (Version 1.11.1-release 64bit) is ready` |
| L2.1 | mod_event_socket loaded | **PASS** | `module_exists mod_event_socket` → `true` |
| L2.2 | ESL configured securely | **PASS** | listener `127.0.0.1:8021` (stock `::`); live secret ×1; stock credential ×0; platform dev credential ×0; `apply-inbound-acl=obd-dev-acl`; 0 of 105 host mappings off `127.0.0.1` |
| L2.3 | mod_sofia loaded | **PASS** | `module_exists mod_sofia` → `true` |
| L2.4 | Internal profile exists | **PASS** | `internal profile sip:mod_sofia@172.25.0.2:5060 RUNNING (0)`; `BIND-URL …:5060;transport=udp,tcp` |
| L2.5 | External profile exists | **PASS** | `external profile sip:mod_sofia@172.25.0.2:5080 RUNNING (0)`; `BIND-URL …:5080;transport=udp,tcp` |
| L2.6 | fs-gateway exists | **PASS** | `external::fs-gateway … NOREG`; detail: `Profile external`, `State NOREG`, `Status UP`, `Password no` — **loaded, not registered** |
| L2.7 | /media/obd readable | **PASS** | `drwxrwxrwx /media/obd`; write refused `Read-only file system`; compose resolves `backend\data\audio` → `/media/obd` |
| L2.8 | RTP range configured | **PARTIAL** | `rtp-start-port 30000` / `rtp-end-port 30099` in the file proven active (`max-sessions` fingerprint returned `42 session(s) max`, reverted to `1000`). **Effective allocation NOT observable at rest** — no `fs_cli` API reaches `switch_rtp_get_start_port()`; confirmed at first live call |
| L2.9 | Docker network correct | **PASS** | `obd-telephony` / bridge / `obd-freeswitch 172.25.0.2/16`; 105 mappings all on `127.0.0.1`; `5061/5081/5066/7443/21/2222` not bound |
| L2.10 | Existing Compose untouched | **PASS** | `git diff -- infra/docker-compose.yml` → empty, before and after |
| — | No Java source/test/migration modified | **PASS** | no `backend/src` change by this phase; the 32 modified Java files belong to the other conversation's VB-7A work and were untouched |
| — | No secret committed | **PASS** | `git check-ignore infra/.env` → ignored; not tracked; committed template contains a token, not a credential |

**L2.8 is deliberately not a clean PASS.** The configuration is in place and in
the file FreeSWITCH reads, but FreeSWITCH 1.11 offers no runtime API for the
range, and ports are allocated only when a channel needs media. Recording it as
PASS would be claiming an observation that was not made.

---

## 12. Next Phase

Phase B enables, and nothing more:

* **Phase C / L3 — manual ESL validation.** The `fs_cli` and raw-socket
  plumbing is known-good: a listener exists, on loopback, with a credential the
  operator can supply. The next step is to exercise the **protocol** by hand —
  the authentication banner, `event plain` subscription, and one real
  `bgapi originate` — before any Java is involved. This isolates protocol
  problems from integration problems.
* **A known-answer check on the media mount.** The volume exists and is
  read-only, so the PLAYFILE phase starts from a mounted path rather than from
  a suspected one.

Phase B does **not** establish, and must not be read as establishing: that
Java can talk to FreeSWITCH; that any call can be placed; that audio or DTMF
works; that a bridge can be made; or that any hangup cause is understood. All
of that is **NOT YET TESTED**.

The documentation that a later phase will extend:
[`freeswitch/02-CALL-FLOW.md`](freeswitch/02-CALL-FLOW.md) (call sequences),
[`freeswitch/05-ESL.md`](freeswitch/05-ESL.md) (the command and event contract),
[`freeswitch/11-DEBUGGING.md`](freeswitch/11-DEBUGGING.md) (the runbook), and
[`freeswitch/13-TROUBLESHOOTING.md`](freeswitch/13-TROUBLESHOOTING.md) (new
entries appended, never removed).
