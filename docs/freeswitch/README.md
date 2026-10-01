# FreeSWITCH Engineering Knowledge Base
A command with no warning is read-only or otherwise safe.
| A registration is rejected with 403 | [06-SIP.md](06-SIP.md) 11.2 - a 403 means "not found", not "wrong password" |
| No audio on a call that connected | [07-RTP-AND-MEDIA.md](07-RTP-AND-MEDIA.md) 11.2 and [13](13-TROUBLESHOOTING.md) entry 16 |
| A media file will not play | [07-RTP-AND-MEDIA.md](07-RTP-AND-MEDIA.md) 11.5 - `mod_av` cannot load on this image |
# FreeSWITCH Engineering Knowledge Base
| ESL says "command not found" for something I sent | [05-ESL.md](05-ESL.md) 12.1 - only a few commands are inbound; APIs need `api ` |
| An accepted command is reported as a failure | [05-ESL.md](05-ESL.md) 12.2 - an `api` reply has an empty `Reply-Text` |
| The switch drops or ignores my events | [05-ESL.md](05-ESL.md) 12.5 - there is no `Call-UUID` header |
| A green test suite but the feature never worked | [13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md) entry 22 - the double accepted everything |

| A file I was editing vanished / shows as deleted | [13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md) entry 23 - a concurrent session parked it; check for `*.parked` |
This directory is the **persistent, phase-independent** knowledge base for the
| The switch says my call answered but the peer saw nothing | [06-SIP.md](06-SIP.md) 12.4 - the stock dialplan answers with voicemail |
| `NO_ROUTE_DESTINATION` but DNS, ping and the ACL are all fine | [06-SIP.md](06-SIP.md) 12.1 - mod_sofia routes by domain |
| `acl` returns false for every address | [06-SIP.md](06-SIP.md) 12.7 - the argument order is host, then list |
| My gateway ignores its `sofia-profile` | [06-SIP.md](06-SIP.md) 12.2 - the declaring profile wins |
| DTMF tones arrive but the app never sees an event | [06-SIP.md](06-SIP.md) 12.8 and [07-RTP-AND-MEDIA.md](07-RTP-AND-MEDIA.md) |
FreeSWITCH telephony engine used by the OBD Platform. It is not a record of
| A call answers then dies in a fraction of a second | [13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md) entry 28 - an unavailable application aborts the channel |
| "invalid uuid" but `uuid_dump` works | [13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md) entry 29 - the channel is already gone |
any one phase.

If you are new to FreeSWITCH, read these in order. Do not skip
[00-FREESWITCH-FUNDAMENTALS.md](00-FREESWITCH-FUNDAMENTALS.md) — almost every
confusion in this system comes from not knowing what a *channel*, a *profile*,
or a *leg* actually is.

## Where to start

| Your situation | Read |
|---|---|
I have never used FreeSWITCH | [00-FREESWITCH-FUNDAMENTALS.md](00-FREESWITCH-FUNDAMENTALS.md) |
I need to know what is running right now | [01-ARCHITECTURE.md](01-ARCHITECTURE.md) |
A call is not working | [02-CALL-FLOW.md](02-CALL-FLOW.md) then [11-DEBUGGING.md](11-DEBUGGING.md) |
Something will not start | [03-CONFIGURATION.md](03-CONFIGURATION.md) |
I need to know what a module does | [04-MODULES.md](04-MODULES.md) |
ESL / event socket problem | [05-ESL.md](05-ESL.md) |
SIP signalling problem | [06-SIP.md](06-SIP.md) |
No audio, or audio is broken | [07-RTP-AND-MEDIA.md](07-RTP-AND-MEDIA.md) |
I am on call and something is wrong | [12-PRODUCTION-OPERATIONS.md](12-PRODUCTION-OPERATIONS.md) |
Something broke and I need the history | [13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md) |
Is this environment safe? | [14-SECURITY.md](14-SECURITY.md) |
Why is it built this way? | [decisions/](decisions/) |

## How to read the labels

Every factual claim in this knowledge base carries one of these labels. This
is not decoration — it is how you know whether you can rely on a statement.

| Label | Meaning |
|---|---|
| **CONFIRMED - LOCAL** | Directly observed in this environment, against the local FreeSWITCH and local SIP endpoints. Command and output are given. |
| **CONFIRMED - SIMULATED** | Observed against a SIP/provider simulation. |
| **CONFIRMED - CARRIER** | Observed against a real SIP carrier or provider. |
| **EXPECTED** | Follows from CONFIRMED facts or from FreeSWITCH source, but has not been observed here yet. |
| **NOT YET TESTED** | Implemented or configured, but no evidence yet. Do not assume it works. |
| **NOT TESTABLE LOCALLY** | Inherently dependent on a carrier or environment that does not exist yet. |
| **FUTURE** | Planned for a later phase. Does not exist yet. Read with suspicion. |

Bare **CONFIRMED** means CONFIRMED - LOCAL.

The single most common mistake in telephony debugging is treating an
**EXPECTED** behaviour as a **CONFIRMED** one. When you verify something,
update the label in this file in the same commit.

### How CONFIRMED was reached in Phase C

Every Phase C CONFIRMED label comes from a **machine-readable answer from the
running system**, not from reading a configuration file:

| Claim | How it was confirmed |
|---|---|
| ESL is reachable from the host | the `auth/request` banner over the published port |
| extension 1001 authenticates | a real `REGISTER`, then a real `200 OK` |
| the RTP range is in effect | `local_media_port` on live channels: 30022, 30028, 30036, 30070, 30082 |
| the event header set | complete, unfiltered header dumps of five event types |
| a missing file fails silently on ESL | absence of `PLAYBACK_START`, plus the `mod_sndfile` log line |

That is deliberate. Phase B proved a great deal about what the configuration
files *contain*, and one of its acceptance checks passed while the feature was
actually broken - because the check inspected the port mapping and the in-container
bind separately, and never the path a client really uses. See
[13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md) entry 13.

## Phase documents

The knowledge base answers "how does FreeSWITCH work?". The phase documents
answer "what did we build, prove, and leave undone?".

| Document | Answers |
|---|---|
[../LIVE-FREESWITCH-PHASE-B.md](../LIVE-FREESWITCH-PHASE-B.md) | Phase B: FreeSWITCH container, ESL hardening, SIP profiles, gateway, RTP, media mount |
| [../LIVE-FREESWITCH-PHASE-C.md](../LIVE-FREESWITCH-PHASE-C.md) | Phase C: local SIP endpoints, registration, live calls, RTP allocation, ESL event reality, PLAYFILE, and three open Java defects |

| [../LIVE-FREESWITCH-PHASE-D.md](../LIVE-FREESWITCH-PHASE-D.md) | Phase D: the Java/ESL contract hardened against a live switch - event correlation, playback lifecycle, channel identity, and the `api`-prefix defect |
A new phase document is added at `docs/LIVE-FREESWITCH-PHASE-<X>.md`, and this
| [../LIVE-FREESWITCH-PHASE-E.md](../LIVE-FREESWITCH-PHASE-E.md) | Phase E: the local gateway made routable, a false-positive answer caught, and the Java objectives blocked by a concurrent build break |
knowledge base is updated in the same change.
| [../LIVE-FREESWITCH-PHASE-E1.md](../LIVE-FREESWITCH-PHASE-E1.md) | Phase E.1: the `uuid_broadcast` contract resolved (the Java command is correct), the cause was a dialplan application that does not exist, and the Spring gate opened |

| [../LIVE-FREESWITCH-PHASE-E2.md](../LIVE-FREESWITCH-PHASE-E2.md) | Phase E.2: seeded application call - correlation key proven in code, the call itself BLOCKED on an empty sip_gateways table |
| The whole test suite runs 0 tests | [13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md) entry 30 - one uncompilable test file |
| [../LIVE-FREESWITCH-PHASE-E3.md](../LIVE-FREESWITCH-PHASE-E3.md) | Phase E.3: the local gateway seeded through the application's own rules and proven routable |
| A seed row inserts but the app cannot read it | [13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md) entry 31 - the DB check is wider than the Java enum |
| [../LIVE-FREESWITCH-PHASE-E5.md](../LIVE-FREESWITCH-PHASE-E5.md) | Phase E.5: campaign contract still moving; baseline run invalidated by concurrent build |
| A test run shows a mass error count I did not cause | [13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md) entry 32 - check for a concurrent Maven build |
| [../LIVE-FREESWITCH-PHASE-E4.md](../LIVE-FREESWITCH-PHASE-E4.md) | Phase E.4: the first full-repository baseline (1005 tests) - the call was not attempted because the campaign contract is still moving |
## Ground rules for this documentation

1. **Record decisions, not just files.** A file listing tells you nothing six
   months later. "Why bridge networking instead of host networking" is the
   part you will need.
2. **Never delete a resolved problem.** [13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md)
   is a permanent record. The next engineer will hit the same problem and the
   fix will already be written down.
3. **Quote evidence.** Every CONFIRMED claim should be reproducible with a
   command. If you cannot reproduce it, it is EXPECTED, not CONFIRMED.
4. **Mark destructive commands.** See the warning convention below.
5. **Assume the reader has forgotten everything.** Assume they have never used
   FreeSWITCH, cannot ask the person who built it, and cannot ask an AI.

### Warning convention used throughout

```text
⚠  DEVEL ONLY          safe in this development environment only
⚠  RESTARTS SERVICE     interrupts the FreeSWITCH process
⚠  TERMINATES CALLS     will drop live call legs
⚠  DESTRUCTIVE          removes data, images or state
⚠  PRODUCTION IMPACT    unsafe in production without a change window
```

A command with no warning is read-only or otherwise safe.
