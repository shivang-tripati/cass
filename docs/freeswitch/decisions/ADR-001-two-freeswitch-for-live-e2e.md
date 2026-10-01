# ADR-001 — Two FreeSWITCH instances for live end-to-end validation

## Status

**Accepted** (architecture). **Not yet implemented** — the provider instance is
introduced in the CONNECT_BY_AGENT phase.

## Context

The Java platform's entire outbound path builds a single dial-string form:

```text
sofia/gateway/<gateway>/<destination>
```

This is hard-coded in one place, `EslClient.originateWithJob`, and it is used
for the customer leg, the agent leg, and agent-originated outbound. Two
consequences follow:

1. **FreeSWITCH can never dial a locally registered user.** `sofia/gateway/…`
   speaks only to a configured trunk.
2. **The agent leg is not routed differently.** `ConnectByAgentService`
   originates the agent leg with a **null** gateway name, so it falls back to
   `telephony.freeswitch.gateway`. Both paths therefore converge on the same
   `sofia/gateway/…` form.

Meanwhile, the platform's model of a call is precise and event-driven. It pins
the channel UUID before placing the call via `origination_uuid`, and it
correlates every subsequent `CHANNEL_*` event by that UUID. It collects DTMF
**passively** from `CHANNEL_DTMF` events; it never asks FreeSWITCH to collect
digits.

Together these mean the environment must give FreeSWITCH a **real SIP peer**
whose media and signalling terminate somewhere that is not the campaign
channel itself.

## Decision

Introduce a **second FreeSWITCH instance — a provider / PBX simulator** — and
place it behind gateway `fs-gateway` on the platform instance.

```text
  Java
    |  ESL
    v
  Platform FreeSWITCH            (exists now)
    |  SIP INVITE / RTP
    v
  gateway "fs-gateway"
    |
    v
  Provider FreeSWITCH           (FUTURE)
    |  answers, plays audio, emits DTMF, returns real SIP codes
    v
  SIP endpoint                   (FUTURE)
```

Phase B ships only the platform instance, because nothing dials yet.

## Alternatives

**A. One FreeSWITCH, and a self-referencing gateway** (its `proxy` points at
its own `external` profile). *Rejected.* The re-entering call creates a
**second channel**. DTMF from the handset is then reported against that inner
channel's UUID, which the platform does not know, so `CHANNEL_DTMF` would never
correlate. `uuid_broadcast … aleg` would play into a loop, and A/B-leg
semantics — on which `aleg` playback correctness depends — become meaningless.

**B. One FreeSWITCH, and change the Java dial string** to `user/<user>@<host>`
or `sofia/internal/…` for local endpoints. *Rejected for now.* The prompt for
this track forbids changing Java absent demonstrated evidence, and the
dial-string form is a contract that a future change to the platform would have
to be designed and reviewed properly. It is recorded as a candidate finding
rather than a fix.

**C. A lightweight SIP proxy** (Kamailio, OpenSIPS) as the far end.
*Rejected.* The project architecture explicitly excludes it, and it would not
easily produce controllable DTMF, audio, and arbitrary hangup causes without
scripting a second engine anyway.

**D. A real PSTN carrier.** *Rejected for this purpose.* It cannot be
controlled, cannot be made deterministic, costs money, and was explicitly
deferred pending approval.

## Why

A simulator under our control is the only option that satisfies all of these
simultaneously:

* the campaign channel is a **single real channel** with a real SIP peer, so
  `CHANNEL_DTMF` carries the pinned UUID and DTMF correlation is testable;
* `PLAYBACK_START` / `PLAYBACK_STOP` / `PLAYBACK_ERROR` behave normally on a
  real A-leg;
* `uuid_bridge` joins two normal channels, so `CHANNEL_BRIDGE` with the
  expected `Bridge-B-Unique-ID` can actually be observed;
* **hangup causes are real.** A dialplan can be made to answer, decline, be
  busy, time out, or return a specific status, producing genuine SIP response
  codes and the genuine FreeSWITCH hangup causes the platform must be validated
  against;
* the agent endpoint can sit behind the gateway, which is what the existing
  Java dial-string contract requires.

A second FreeSWITCH is also the cheapest way to get all of that: it already
speaks SIP, plays media, generates DTMF, and can be driven by a dialplan
without writing code.

## Consequences

**Easier:**

* hangup-cause validation uses real SIP responses, not guesses;
* audio and DTMF are genuinely end-to-end;
* the failure matrix becomes scriptable — dial a number, get a cause;
* the environment is reproducible and free.

**Harder:**

* roughly doubles FreeSWITCH's memory footprint on a Docker VM with ~2.97 GB
  allocated. This is the tightest resource constraint in the design, and it is
  the reason the provider instance is added late rather than in Phase B;
* a second configuration tree to maintain, and a second thing that can be
  misconfigured;
* the provider instance is a **simulator, not a carrier**. It will not
  reproduce carrier-specific behaviour — early media quirks, unusual
  response codes, codec behaviours, or NAT traversal. That gap can only be
  closed against a real provider, later, with approval.

**Operational impact for a production engineer.** When a call fails in the
live test environment, you must first establish which side is at fault: the
platform instance, or the simulator. The two have separate containers, separate
logs, and separate configuration trees. In production there is no second
instance — the far end is a carrier or a customer PBX, and the debugging
method is the same but the second log does not exist.

## Future Reconsideration

Revisit when:

* a real carrier is introduced — at that point the simulator becomes a
  development-only component and production debugging must be re-documented
  without it;
* the Java dial-string contract changes to support local endpoints, at which
  point a single instance may suffice and this decision is superseded;
* memory pressure on the test host makes a second instance impractical, in
  which case the lighter-weight options above should be re-evaluated rather
  than abandoning live validation.
