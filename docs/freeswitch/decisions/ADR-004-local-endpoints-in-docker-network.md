# ADR-004 - Local SIP endpoints must run inside the Docker network

## Status

**Accepted and implemented** in Phase C. This ADR records a measured constraint,
not a preference, and it is the reason `infra/docker-compose.freeswitch-endpoints.yml`
exists.

## Context

Phase C's objective is to prove that the existing FreeSWITCH environment
performs real SIP, media, ESL and call operations. That requires SIP endpoints
that can register, be called, exchange RTP, and send DTMF.

The obvious approach is a softphone on the Windows host - Linphone, MicroSIP or
Zoiper. Before choosing that, the network was measured.

## The measurement

```text
Test-NetConnection 172.25.0.2 -Port 5060  ->  TcpTestSucceeded=False
ping 172.25.0.2                            ->  no reply
route print                                 ->  no 172.25.0.0/16 entry
```

**The Windows host has no route to the Docker bridge network.** FreeSWITCH
advertises its container address (`172.25.0.2`) in both `Contact` and SDP, which
produces this capability split:

| From a host softphone | Result | Why |
|---|---|---|
| `REGISTER` | works | one-way; FreeSWITCH only has to answer |
| place a call | works over **TCP** | published port; the connection is stateful |
| place a call over **UDP** | **broken** | Docker's UDP proxy does not return the response to the original client port |
| be **called** by FreeSWITCH | **impossible** | FreeSWITCH would send to a contact of `127.0.0.1`, which inside the container is the container's own loopback |
| **receive RTP** | **impossible** | same reason |

The SIP/TCP control confirms the mechanism rather than the conclusion:

```text
OPTIONS to 127.0.0.1:5060 over TCP  -> SIP/2.0 200 OK
OPTIONS to 127.0.0.1:5060 over UDP  -> no response
```

So a host endpoint can register and can place calls, and can do **nothing else**.
It can never be called back and can never hear or be heard.

## Decision

Local SIP endpoints run as **additional FreeSWITCH containers on the
`obd-telephony` network**, one per test extension.

- `obd-fs-endpoint-1001` registers as extension `1001`
- `obd-fs-endpoint-1002` registers as extension `1002`

Each is a real SIP endpoint performing real REGISTER / 401 / digest / 200 OK
transactions, real INVITE, real RTP and real DTMF generation. Each has exactly
one profile, one registering gateway, and a dialplan whose entire behaviour is
*answer, emit a known DTMF sequence, play a prompt, wait, hang up*.

They register by **gateway** (`register=true`), which produces the same
registration flow a softphone would, from the other direction.

## Why FreeSWITCH and not SIPp

The Docker daemon on this machine cannot resolve a registry, so **no new image can
be pulled**. Options were therefore constrained to the image already present.

Reusing `ghcr.io/patrickbaus/freeswitch-docker:1.11.1` - the image the project
already pins and trusts - adds **no new image and no new supply-chain surface**.
A second FreeSWITCH is also a more faithful peer than a bare SIP UA, because it
performs the full registration and media stack.

## Alternatives considered

**A softphone on the Windows host.** Rejected on measurement: it cannot receive
calls and cannot carry media, so it cannot validate the properties Phase C
exists to prove. Its usable surface (register, originate) is already covered by
the in-network endpoints, and by a scripted SIP client in the test harness.

**A host-side scripted SIP/ESL client.** Kept, and used - it is how
registration, realm behaviour, and the ESL wire protocol were all validated. It
is simply not sufficient on its own, because it cannot be called.

**Publishing additional RTP ports and advertising a reachable SDP address.** This
is the standard NAT workaround. Rejected: it requires `ext-rtp-ip` /
`ext-sip-ip` pointing at an address the host can route to, and the host has no
route into the bridge network at all. There is no address to advertise.

**`network_mode: host` for the platform switch.** Rejected: it would give the
container the host's network identity, changing the environment that Phase B
deliberately built and invalidating its published-port model.

## Consequences

**Good**

- Real registration, real calls, real RTP and real DTMF are all locally testable.
- Container-to-container media needs no published RTP port, so the exposure
  surface does not grow.
- Endpoints are fully reproducible: no GUI, no manual configuration, no state on
  the developer's machine.
- The same topology is what ADR-001 already specified for a future carrier
  simulator, so this is a rehearsal rather than a detour.

**Bad, and accepted**

- Three FreeSWITCH instances instead of one, so more memory and more startup
  time.
- Each needs its own log directory. Sharing one corrupts the compiled config
  cache and `core.db` (troubleshooting entry 18).
- Each needs its own ESL password, rendered from the environment at start-up.
- The endpoints publish ESL on loopback (`127.0.0.1:8031`, `:8032`) purely so
  their own media counters can be read. Without that, a two-way audio claim
  cannot be substantiated - and asserting audio without it is exactly the
  mistake Phase C is trying to avoid.

**Neutral**

- No new image, so this decision is reversible by deleting two compose services.

## Relationship to ADR-001

ADR-001 specified "two FreeSWITCH instances" for the eventual carrier
interoperability test, and noted that a provider simulator is the right way to
avoid depending on a real carrier. This ADR applies the same shape one step
earlier, for local endpoint testing, and supplies the measured reason it has to
work that way.

## Verification

```text
$pw = <FREESWITCH_PASSWORD from infra/.env>
fs_cli -x "sofia status profile internal reg"
```

Both endpoints appear, `Registered(UDP)`, `Ping-Status: Reachable`, with in-network
contact addresses, and both are placeable and callable.
