# 01 — Architecture

What actually exists **right now**, after Phase B. This is not the intended
end state. Where something is planned, it is labelled **FUTURE** and drawn
dotted, and it is not something you can rely on today.

Last updated: end of Phase B.

---

## 1. The environment as it exists now

```text
Windows host
│
├── Docker Desktop (WSL2, linux/amd64)
│
└── Docker
    │
    ├── network: obd-telephony  (bridge)
    │   │
    │   └── obd-freeswitch   172.25.0.2/16
    │       hostname: freeswitch
    │       │
    │       ├── FreeSWITCH 1.11.1-release 64bit   (the only process)
    │       │   │
    │   │   ├── mod_event_socket ── ESL listener on 0.0.0.0:8021
    │   │   │                        (published 127.0.0.1:8021; password from env)
    │       │   │
    │       │   ├── mod_sofia
    │       │   │   ├── profile "internal"  :5060 udp+tcp   SIP-URL
    │       │   │   │                                  sip:mod_sofia@172.25.0.2:5060
    │       │   │   └── profile "external"  :5080 udp+tcp
    │       │   │       sip:mod_sofia@172.25.0.2:5080
    │       │   │
    │       │   └── gateway "fs-gateway"  (bound to profile external)
    │       │       proxy = sip:freeswitch-provider:5080
    │       │       register = false      state = NOREG
    │       │       ▲ THIS SERVICE DOES NOT EXIST YET
    │       │
    │       ├── /media/obd            ← bind mount, read-only
    │       │   └── from ../../backend/data/audio
    │       │
    │       └── /var/log/freeswitch   ← bind mount (compiled fsxml, logs)
    │           └── from ../../infra/freeswitch/logs
    │
    └── published to 127.0.0.1 only
        ├── 8021/tcp          ESL
        ├── 5060/udp,5060/tcp internal SIP
        ├── 5080/udp,5080/tcp external SIP
        └── 30000-30099/udp   RTP
```

**CONFIRMED** — every element above was verified with `fs_cli` and `docker`
commands; see [../LIVE-FREESWITCH-PHASE-B.md](../LIVE-FREESWITCH-PHASE-B.md)
for the raw evidence.

---

## 2. Component inventory

| Component | Purpose | Protocol | Port | Direction | Owner | Failure modes |
|---|---|---|---|---|---|---|
`obd-freeswitch` container | the telephony/media engine | — | — | — | this repository | process crash, unhealthy, network unreachable |
FreeSWITCH process | execute telephony | — | — | — | SignalWire project (MPL-1.1 core) | startup config error, module load failure, crash loop |
`mod_event_socket` | ESL: accept commands, push events | TCP | 8021 | inbound to FreeSWITCH | FreeSWITCH | not loaded, wrong password, not reachable, ACL rejects |
`mod_sofia` | the SIP stack | — | — | — | FreeSWITCH | not loaded, profile will not bind |
profile `internal` | local SIP endpoints (softphones, future agents) | SIP udp+tcp | 5060 | both | this repository | profile not recognised, port already bound, ACL rejects |
profile `external` | provider/gateway leg | SIP udp+tcp | 5080 | both | this repository | as above |
gateway `fs-gateway` | named outbound trunk | SIP | (uses 5080) | outbound | this repository | **not loaded** (silently), invalid name, `register` left at its `true` default |
`/media/obd` | application audio, visible to FreeSWITCH | filesystem | — | read-only | this repository | mount missing (now fails startup), wrong path shape |
RTP | call media | UDP | 30000-30099 | both | FreeSWITCH | SDP address unreachable, codec mismatch, port blocked |

**NOT YET PRESENT** (deliberately, in Phase B): the Java application connected
to ESL, a softphone, a provider FreeSWITCH, any call, any audio.

---

## 3. What is NOT running, and why that is correct

Some things a reader might expect to see are absent. Each absence is
deliberate:

| Absent | Why |
|---|---|
A second FreeSWITCH | The provider/PBX simulator is introduced in a later phase, when CONNECT_BY_AGENT is tested. Adding it now would create a second source of half-working behaviour. See [decisions/ADR-001](decisions/ADR-001-two-freeswitch-for-live-e2e.md). |
The Java application connected | Phase B is infrastructure only. `telephony.freeswitch.enabled` stays `false`. |
A softphone / registered endpoint | Nothing registers in Phase B, and the stock FreeSWITCH directory ships the well-known `default_password=1234`, so enabling registration would mean accepting a weak credential. See [14-SECURITY.md](14-SECURITY.md). |
PostgreSQL, Redis | Already provided by the existing `infra/docker-compose.yml`, which this phase did not touch. |
Any published port on `0.0.0.0` | Security boundary. See [14-SECURITY.md](14-SECURITY.md). |
IPv6 SIP profiles | The stock image enables `internal-ipv6` and `external-ipv6`, which bind `::1:5060` and `::1:5080` — the very ports needed here. They are removed by an explicit profile list. |
SIP TLS (5061/5081) and WebSocket (5066/7443) | Out of scope. The stock internal profile binds 5066 and 7443; those bindings are omitted, so those ports are not bound at all. |

---

## 4. The three network zones

```text
ZONE 1 — the Windows host
    127.0.0.1:8021, :5060, :5080, :30000-30099
    Only this machine can reach FreeSWITCH. Nothing is on the LAN.

ZONE 2 — the obd-telephony Docker network  (172.25.0.0/16)
    Container IP 172.25.0.2, DNS alias "freeswitch"
    A future containerised Java application would connect to freeswitch:8021
    here, with no published port involved.

ZONE 3 — the public internet
    NOT REACHED. The container's outbound traffic is not relied upon by
    anything in Phase B, and the published ports are loopback-only.
```

**Consequence worth internalising:** a container-to-container RTP stream in a
later phase (platform FreeSWITCH ↔ provider FreeSWITCH) travels **entirely
inside Zone 2**. It never touches the Windows host, never crosses the Docker
port proxy, and therefore does not need the published 30000-30099 range. That
range exists only for a **Windows-side softphone**.

---

## 5. Why bridge networking, not host networking

**CONFIRMED decision** (Phase B). The image author recommends
`network_mode: host`. It was rejected. Reasoning is recorded in
[../LIVE-FREESWITCH-PHASE-B.md](../LIVE-FREESWITCH-PHASE-B.md) §5 and in
[decisions/ADR-002](decisions/ADR-002-*.md). In short:

* On Docker Desktop, `host` means the **WSL2 VM's** namespace, not Windows.
  Windows↔VM forwarding behaviour varies by Docker Desktop version and would
  have to be re-verified on every upgrade.
* Publishing the stock 16,384-port RTP range through Docker's proxy is slow and
  is exactly the exposure this project must avoid.
* Container-to-container media does not need any published RTP port at all.

Escalation to host networking remains available, but only with evidence.

---

## 6. The container's process tree

Only one process matters, and this is worth stating because it is easy to
break:

```text
docker entrypoint  ->  /bin/sh /opt/obd/entrypoint.sh
                          │  renders the ESL config from the environment
                          │  fails fast if the secret is weak or /media/obd
                          │  is missing
                          └─ exec /usr/bin/freeswitch     <-- becomes PID 1
```

Because the entrypoint ends with `exec`, FreeSWITCH **is** PID 1 and receives
`SIGTERM` directly on `docker stop`, so it can shut down cleanly. If that
`exec` were removed, PID 1 would be a shell, signals would not be forwarded, and
every stop would be an ungraceful kill. The entrypoint also is the reason
`docker compose` must carry an explicit `entrypoint:` key — omitting it runs
the image's own `CMD` and silently skips all of the above. That mistake was
made and caught during Phase B; see
[13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md).

---

## 7. Planned future topology (NOT YET BUILT)

Draw dotted, read with suspicion. Nothing below exists.

```text
FUTURE — Java application (Phase F+)
  │
  │ ESL, TCP
  │   host-run        -> localhost:8021        (published, loopback only)
  │   containerised   -> freeswitch:8021        (Zone 2 DNS alias)
  v
Platform FreeSWITCH                       <-- exists now
  │
  │ SIP INVITE, RTP
  v
Gateway "fs-gateway"  (profile external, :5080)
  │
  v
Provider FreeSWITCH                       <-- FUTURE
  │  exists to be a real SIP peer: it answers,
  │  plays audio, emits DTMF, and produces real SIP
  │  response codes, so hangup causes and RTP are genuine.
  v
SIP endpoint / softphone                   <-- FUTURE
```

Rationale for the second instance is in
[decisions/ADR-001](decisions/ADR-001-two-freeswitch-for-live-e2e.md). The
essential point: a FreeSWITCH that dials **itself** cannot validate DTMF
correlation or playback, because the far end of the call is then a second
channel whose UUID Java does not know.

---

## 8. Version and provenance

| Item | Value | Label |
|---|---|---|
FreeSWITCH version | `1.11.1-release 64bit` | CONFIRMED |
Image | `ghcr.io/patrickbaus/freeswitch-docker:1.11.1` | CONFIRMED |
linux/amd64 digest | `sha256:8b55a395739a79dc9565a11057cc78dcbd9d28ef61432cc19041daf749cbfd52` | CONFIRMED |
Base OS | Alpine 3.23.4, FHS layout | CONFIRMED |
Core licence | MPL-1.1 (per `switch.h` header) | CONFIRMED |
Image wrapper licence | GPL-3.0 (`patrickbaus/freeswitch-docker` LICENSE) | CONFIRMED |
Upstream stable | 1.11.3 (2026-08-28) | CONFIRMED (researched) |
**Version gap** | image is 1.11.1, upstream is 1.11.3; 1.11.2/1.11.3 carry further security hardening | CONFIRMED — accepted for a loopback dev container |
CVE coverage | 1.11.1 fixes CVE-2026-49840, an ESL `Content-Length` heap overflow reachable pre-authentication | CONFIRMED |

---

## 9. "Where do I look?" — first-line triage

| Problem | Look first | Look second |
|---|---|---|
Container will not start | `docker compose ... logs` | [13-TROUBLESHOOTING](13-TROUBLESHOOTING.md) — look for `Cannot Initialize` |
Container healthy but `UP` never appears | `fs_cli -x status` | module load, `mods.conf` |
`fs_cli` says `Error Connecting []` | ESL password / `listen-ip` | Docker port mapping, ACL |
SIP profile missing (`0 profiles`) | the profile file's **root element** | [13-TROUBLESHOOTING](13-TROUBLESHOOTING.md) "Profile silently ignored" |
SIP profile present but not RUNNING | port already bound (check `netstat`) | ACL / `apply-inbound-acl` |
Gateway missing (`0 gateways`) | `register` parameter | [13-TROUBLESHOOTING](13-TROUBLESHOOTING.md) "Gateway invisible" |
Gateway `NOREG` unexpectedly | `register=false` present? | whether a provider exists yet |
Media not found | is `/media/obd` present and readable | is the path shape `<root>/<tenant>/<asset>/<file>` |
Something always fails at startup after a config edit | the **compiled** config `/var/log/freeswitch/freeswitch.xml.fsxml` | [13-TROUBLESHOOTING](13-TROUBLESHOOTING.md) "Compiled config lies" |

Call-related rows (answer, audio, DTMF, bridge, hangup) are **NOT YET TESTED**
and will be added in [02-CALL-FLOW.md](02-CALL-FLOW.md) and
[11-DEBUGGING.md](11-DEBUGGING.md) as those phases produce real calls.

---

## 10. PHASE C: the local SIP test endpoints

Two additional FreeSWITCH containers were added so that real calls could be
placed and media could be observed without a carrier. They live in a **separate
Compose project** (`obd-fs-endpoints`) and attach to the same `obd-telephony`
network as external members.

```text
    network: obd-telephony  (bridge)
      │
      ├── obd-freeswitch        172.25.0.2   "the platform switch"
      │   ├── profile internal  :5060   ESL :8021   RTP 30000-30099
      │   ├── profile external  :5080
      │   ├── gateway fs-gateway -> freeswitch-provider:5080  (NOREG)
      │   └── directory: 1001, 1002 only   (the stock users are REMOVED)
      │
      ├── obd-fs-endpoint-1001   registers as extension 1001
      │   ├── profile obd-endpoint :5060   ESL :8021
      │   ├── gateway platform-switch -> 172.25.0.2:5060  register=true
      │   └── dialplan: answer -> send_dtmf -> play -> wait -> hangup
      │
      └── obd-fs-endpoint-1002   registers as extension 1002
          └── (same shape)
```

**Endpoints must be in-network, and this is measured, not assumed.** The Windows
host has no route to the bridge network:

```text
Test-NetConnection 172.25.0.2 -Port 5060  ->  TcpTestSucceeded=False
route print                                 ->  no 172.25.0.0/16 entry
```

So a host softphone can `REGISTER` and can place a call, but FreeSWITCH can never
call it back (the contact would be `127.0.0.1`, which inside the container is the
container's own loopback) and RTP can never reach it. Host SIP over **UDP** also
fails, because Docker's UDP proxy does not return the response to the original
client port. Only SIP over **TCP** works from the host.

See [ADR-004](decisions/ADR-004-local-endpoints-in-docker-network.md).

### What each endpoint is, and is not

| Is | Is not |
|---|---|
| a real SIP user agent: REGISTER, 401, digest, 200 OK | a softphone with a GUI |
| able to place and receive calls | a carrier simulator |
| able to send RTP and RFC 4733 DTMF | a load-test tool |
| fully reproducible from Compose | a security boundary - it is a test fixture |

Each endpoint registers by **gateway** (`register=true`), which produces the same
registration flow a softphone would, from the other direction. Both appear in
`sofia status profile internal reg` as `Registered(UDP)` / `Reachable`.

### Operational notes specific to multiple instances

* **One log directory per instance.** Sharing one corrupts `freeswitch.xml.fsxml`
  and `core.db`; the symptom is
  `Cannot Initialize [Cannot Open log directory or XML Root!]` and a crash loop.
  See [13](13-TROUBLESHOOTING.md) entry 18.
* **A shared YAML anchor's `volumes:` list is replaced, not appended to**, by a
  per-service `volumes:` key. The endpoint compose file therefore spells its
  volume lists out in full.
* Each endpoint publishes ESL on loopback (`8031`, `8032`) purely so its **own
  media counters** can be read. A two-way audio claim is not substantiated
  without the far end confirming receipt.
* Endpoint 1002 registering as `1002` is the current stand-in for the future
  agent leg. When CONNECT_BY_AGENT is exercised, this is where the agent side
  comes from.