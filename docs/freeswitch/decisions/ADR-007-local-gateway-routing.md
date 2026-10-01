# ADR-007 - Local endpoint routing goes through SIP registration, not an address

## Status

**Accepted and implemented** in Phase E.

## Context

Phase D proved the Java/ESL contract but could not make the platform place a
call. The only configured gateway, `fs-gateway`, proxies to
`freeswitch-provider:5080`, which does not resolve because no carrier exists:

```text
fs-gateway  Proxy sip:freeswitch-provider:5080  State NOREG
getent hosts freeswitch-provider   -> no output
sofia status gateway fs-gateway    -> FailedCallsOUT=0
```

So `sofia/gateway/<gw>/<dest>` - the exact string the Java dialer builds - could
not reach anything. Phase E had to create the smallest local path that makes it
real, without a carrier, and without exposing anything new.

The obvious configuration - a trunk whose proxy is the endpoint's address -
does not work, and the reason is not documented anywhere in FreeSWITCH's
configuration surface. It is recorded here so the next person does not have to
rediscover it.

## Decision

**Route to the endpoint through its SIP registration contact, and let the
gateway select the profile. Do not address the endpoint's container address.**

The gateway:

```xml
<gateway name="local-endpoint-1002">
  <param name="proxy"          value="endpoint-b:5060"/>
  <param name="register"       value="false"/>
  <param name="sofia-profile"  value="internal"/>
  <param name="realm"          value="$${domain}"/>
  <param name="local-network-acl" value="obd-dev-acl"/>
</gateway>
```

declared in `conf/sip_profiles/internal.xml`, not `external.xml`.

### The three measured facts behind it

**1. mod_sofia routes outbound SIP by domain.** It resolves a destination against
the registration database of the profile it originates from. It will not send to
an address merely because that address is reachable. Every direct-address form
fails, on both profiles, by IP and by DNS name:

```text
sofia/external/1002@172.25.0.4:5060    -> NO_ROUTE_DESTINATION
sofia/internal/1002@172.25.0.4:5060    -> NO_ROUTE_DESTINATION
sofia/external/1002@endpoint-b:5060     -> NO_ROUTE_DESTINATION
sofia/internal/1002@<switch domain>     -> CHANNEL_ANSWER
```

The transport was provably healthy throughout - DNS resolved, the endpoint
answered ping with 0% loss, and the ACL passed. **A working transport is not a
route**, and confusing the two is what makes this expensive to diagnose.

**2. A gateway's origination profile is the profile that declares it.** The
gateway's own `sofia-profile` parameter is a hint the declaring profile
overrides. With the gateway declared in `external.xml` and
`sofia-profile=internal`, origination still produced `sofia/external/...` and
the parameter had no effect at all. The declaration is the mechanism; the
parameter is documentation.

**3. The endpoint's dialplan must match `destination_number`, not
`sip_destination_user`.** For a gateway-originated call the Request-URI carries
the *gateway's* identity, not the number dialled:

```text
New Channel sofia/obd-endpoint/FreeSWITCH@endpoint-b:5060
Processing FreeSWITCH <+15551230000>->1002 in context obd-endpoint
No Route, Aborting
```

## Alternatives considered

**Point the gateway's proxy at the endpoint's address directly.** This is the
textbook trunk configuration and it does not route. Rejected on measurement.

**Point the gateway's proxy at the switch's own SIP address.** This appeared to
work - the platform reported a clean `CHANNEL_ANSWER` - and was **wrong**. The
INVITE looped back to the platform, where the stock `public` context answered it
with `voicemail`. The peer's own state showed the truth: no channel, no SDP
(`rtp_remote_sdp_str = _undef_`), no RTP. Rejected, and the false positive is
now covered by a test that asserts on peer evidence.

**Give the endpoint an address that is a domain the switch serves.** Possible,
and it would make direct routing work, but it changes the endpoint's identity
and buys nothing: the registration is already the durable, self-updating route,
and it survives the container being recreated.

**Use a `bridge` app in the platform dialplan instead of a gateway.** Would work
for inbound calls, but Java's dial string is `sofia/gateway/<gw>/<dest>`, so the
gateway has to exist for the production path regardless. Kept the change minimal:
the gateway is a static, credential-free, origination-only trunk.

## Consequences

**Good**

- `sofia/gateway/local-endpoint-1002/1002` - the production Java dial string -
  now reaches a real SIP endpoint. Verified with the endpoint as witness:
  `CHANNEL_CREATE → CHANNEL_ANSWER → PLAYBACK_START → PLAYBACK_STOP →
  CHANNEL_HANGUP`.
- Survives container recreation. The address moved from `172.25.0.4` to
  `172.25.0.3` during this phase and every registration followed it
  automatically, because the endpoints re-register against whatever the current
  domain is. A literal address would have failed silently as
  `NO_ROUTE_DESTINATION`, which reads as a network fault.
- No new port, no credential, no carrier. `register=false` means the gateway
  authenticates to nothing and is discoverable by nothing.
- `fs-gateway` untouched, so the future carrier keeps its own identity and
  credential path.

**Bad, and accepted**

- The route depends on a registration existing. If the endpoint is not
  registered the call cannot route, and the failure is
  `NO_ROUTE_DESTINATION` rather than something more descriptive. Acceptable: the
  registration's presence is checkable, and the alternative failure is worse.
- A container address appears in no configuration file. That is the point, and
  it is why the shared alias `obd-fs-endpoint` is explicitly not used - both
  endpoints answer to it, so which one wins is a property of the resolver rather
  than of the configuration.
- The platform dialplan had to be replaced, because the stock one answers
  extension numbers with `voicemail`. That is a real change with a real blast
  radius, and it is what made the false positive possible in the first place.

## Evidence

```text
# peer-witnessed gateway validation, tools/freeswitch-harness/e1_decisive.py
dial string : sofia/gateway/local-endpoint-1002/1002

ENDPOINT-side events (the peer, not the platform):
  CHANNEL_CREATE  ringing
  CHANNEL_ANSWER  answered
  PLAYBACK_START  answered
  PLAYBACK_STOP   answered
  CHANNEL_HANGUP  DESTINATION_OUT_OF_ORDER

negotiated media, read back from the live channel:
  local_media_ip  = 172.25.0.3   local_media_port  = 30078
  remote_media_ip = 172.25.0.4   remote_media_port = 31378
  read/write codec = PCMU

RTP, measured as kernel UDP counters:
  endpoint -> platform : +64 received  (the test sent nothing to cause this)
```

See `docs/LIVE-FREESWITCH-PHASE-E.md` §4, §5 and §6.

## Production relevance

**Directly applicable, and it is not specific to this test topology.** Every real
deployment reaches a registered handset through its contact, not by addressing a
device IP — devices are behind NAT, change address constantly, and are not
reachable in any way a switch can assume. The generalisation of this ADR is
therefore not "use a DNS name for the proxy" but:

> **In SIP, the registration is the route. Configure reachability, not
> addresses.**

The corollary matters just as much: a switch that answers its own dialstring
will report success for anything it has a pattern for. Voicemail answering
extension 1002 produced a clean `CHANNEL_ANSWER` for a call that never left the
machine. Local switch state is not evidence that a call happened.
