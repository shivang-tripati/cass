# ADR-006 - Authoritative FreeSWITCH channel identity and command forms

## Status

**Accepted and implemented** in Phase D.

## Context

Phase C captured the real event stream from a live FreeSWITCH and found that
the Java integration's understanding of it was wrong in two independent ways.

**The correlation header does not exist.** `EslEvent.getCallUuid()` read
`Call-UUID`. Measured with complete, unfiltered header dumps on
`CHANNEL_CREATE`, `CHANNEL_ANSWER`, `PLAYBACK_START`, `PLAYBACK_STOP` and
`CHANNEL_HANGUP`, that header is **absent from every one**. The channel UUID is
present as `Channel-Call-UUID`, `Unique-ID`, `Caller-Unique-ID`,
`variable_call_uuid` and `variable_origination_uuid`. The consequence was that
no event ever correlated to a call: every event returned `null` and was dropped
at the first guard in `processEvent`.

**The commands were being rejected.** `uuid_kill`, `uuid_broadcast` and
`uuid_bridge` were sent as bare inbound ESL commands. ESL accepts a small fixed
set of inbound commands - `api`, `bgapi`, `event`, `filter`, `linger`, `exit`,
`hup`, `log` - and answers anything else with `-ERR command not found`. These
are APIs and must be `api`-prefixed. Measured:

```text
uuid_kill <uuid> NORMAL_CLEARING          -> -ERR command not found
api uuid_kill <uuid> NORMAL_CLEARING      -> accepted
uuid_broadcast <uuid> <path> aleg         -> -ERR command not found
api uuid_broadcast <uuid> <path> aleg     -> accepted
```

So every playback, hangup and bridge the platform issued was refused, and no
call could ever have completed against a carrier. `bgapi originate` worked
because `bgapi` genuinely is an inbound command.

A related trap, found in the same phase: an `api` reply is
`Content-Type: api/response` with an **empty** `Reply-Text` and the result in
the body. A reply path that requires `+OK` rejects every *successful* `api`
call.

**Why neither was caught.** `FakeEslServer` answered `+OK accepted` to any
command it did not recognise, and every test hand-built its event with a
`Call-UUID` header. The double and the fixtures encoded the same wrong contract
as the code, so the suite was green and the feature had never worked.

## Decision

**1. Resolve channel identity through an ordered header list, not one name.**

```text
Channel-Call-UUID  ->  Unique-ID  ->  Call-UUID (compatibility only)
```

`Channel-Call-UUID` is the explicit channel-identity header. `Unique-ID` is the
conventional one and was present on every event measured. `Call-UUID` is read
last and only for compatibility, because this switch never emits it.

**2. Header lookup is case-insensitive.** FreeSWITCH's casing is not stable
across the event set, and a case-sensitive miss would silently reintroduce the
same class of defect.

**3. Channel-addressed commands are `api`-prefixed; `bgapi` is not.**

**4. For `api` commands, `-ERR` is the only failure.** Absence of a verdict is
the normal shape of a successful `api` reply. A genuine `command/reply` with no
verdict remains a failure.

**5. `Job-UUID` is never a channel identity.** It is confined to the private
originate method and is never read by event correlation.

**6. A protocol double must model what the server does with a command it does
not understand.** The default is `-ERR command not found`.

## Alternatives considered

**Hard-code `Unique-ID` alone.** Rejected: one header name is a single point of
failure, and Phase C showed five names carry the same value. A list degrades
gracefully when one is absent.

**Keep `Call-UUID` as the primary.** Rejected: the header does not exist. Keeping
it primary would preserve a defect that the evidence has disproved.

**Send channel commands bare and treat `-ERR command not found` as an error the
caller must handle.** Rejected: it would push a known, avoidable protocol error
onto every call site, and the switch has a correct form for these commands.

**Keep the double permissive so existing tests pass unchanged.** Rejected
explicitly, and at a cost: five pre-existing assertions required the bare,
non-functional command form and had to be corrected. Those assertions encoded
behaviour that cannot work against a real switch, so correcting them was not
weakening them — it was fixing the specification the tests were written against.

**Add a "current channel" abstraction for J3.** Considered and **rejected on
evidence**. Phase C observed a dialplan transfer minting a new UUID and inferred
the stored identity would go stale. Phase D measured it: the bridge is anchored
on a *different* channel, but the originated leg - the one whose UUID the
application supplied - stays addressable for the whole call. Verified live with
`api uuid_broadcast` and `api uuid_kill` against the pinned UUID while the call
was bridged. The existing model was already correct, so a new abstraction would
have been complexity with no behaviour to represent.

## Consequences

**Good**

- Correlation works against the real switch, verified live.
- Playback, hangup and bridge are actually accepted, verified live.
- The double now fails on malformed input, so this class of defect cannot
  silently return.
- A provider that renames or adds an identity header is a one-line change.

**Bad, and accepted**

- Three identity headers are consulted per event, so the resolution order is
  load-bearing and must be documented and tested. It is.
- The permissive double had to be tightened, which required correcting
  pre-existing assertions. That cost is far smaller than the defect it hid.

**Neutral**

- `Call-UUID` support is retained rather than removed, so any event source that
  does emit it keeps working.
- No schema change, no new dependency, no architecture change.

## Evidence

```text
# live, against the running switch
LiveFreeSwitchRuntimeContractTest.liveEventsResolveChannelIdentity   PASSED
  getHeader("Call-UUID") is null        <- the switch emits none
  getCallUuid() equals the pinned UUID   <- identity resolves anyway
  getOriginationUuid() equals it too

LiveFreeSwitchRuntimeContractTest.liveAuthenticationSucceeds       PASSED
LiveFreeSwitchRuntimeContractTest.liveSubscriptionIsAccepted      PASSED
LiveFreeSwitchRuntimeContractTest.livePlaybackLifecycleIsObservable PASSED

# automated
EslProtocolTest               17 tests, 0 failures
EslEventRuntimeContractTest   22 tests, 0 failures
all telephony classes        144 tests, 0 failures
```

Troubleshooting entries 19-22 record the symptoms, the diagnosis and the
prevention for each of these.

## Production relevance

Both defects are severe and both are silent. Correlation failure leaves every
call stuck with no error anywhere. Command rejection leaves the platform placing
and answering calls that can never play audio or hang up. A deployment built on
either would look healthy in every dashboard while completing no calls at all.
