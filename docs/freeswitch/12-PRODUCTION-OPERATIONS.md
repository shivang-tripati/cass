# 12 — Production Operations

How to investigate safely when this environment carries real traffic.

> **This environment is a DEVELOPMENT environment.** Almost everything here is
> single-host, loopback-bound, and has no failover. This document covers both
> "how to work on it safely today" and "what changes when it is production",
> because the two are easy to confuse and the confusion is expensive.

---

## 1. First response — before changing anything

```text
 1. Establish scope.  How many calls are affected? Is it all calls or a
    subset? Is it new or ongoing? Are customers affected or only the platform?
 2. Do NOT restart anything yet.  A restart destroys the evidence AND drops
    live calls. Capture first.
 3. Capture the state below.
 4. Only then decide.
```

The instinct to restart is the most expensive one in this document. If the
cause is a bad config file, restarting will either fix it silently (leaving
nobody knowing why) or fail identically — and in both cases you have lost the
channel events that would have told you what happened.

---

## 2. Health check — the six questions

```text
Command: fs_cli -x "status"
  Q1  Is the process alive?         first line begins "UP"
  Q2  How long has it been up?      the "0 years, 0 days, N hours" part
                                     -> if N is small, something restarted
  Q3  What is the session count?    "N session(s) - peak P, last 5min M"
  Q4  Is it near the ceiling?       "N session(s) max"
  Q5  Are calls succeeding?         CALLS-OUT vs FAILED-CALLS-OUT below
  Q6  Is CPU/load sane?             "min idle cpu"

Command: fs_cli -x "sofia status"
  profiles RUNNING?  gateways present and in an expected state?
  registration count sane?

Command: fs_cli -x "sofia status profile internal"
  FAILED-CALLS-IN / FAILED-CALLS-OUT  -> non-zero with zero successes is the
  single strongest early signal of a SIP-side fault.

Command: docker exec obd-freeswitch netstat -tulnp
  Are the expected listeners present, and is anything UNEXPECTED bound?
  A listener that has appeared since you last looked is a finding.

Command: fs_cli -x "show channels"
  Active calls, and their state.
```

**In this environment, the expected baseline is:** 2 profiles RUNNING, 1
gateway in `NOREG`, 0 registrations, 0 calls, 0 aliases. Any deviation from that
baseline during Phase B is itself the finding.

---

## 3. Logs

```text
Where:
  /var/log/freeswitch/freeswitch.log          (bind-mounted to
                                               infra/freeswitch/logs on the host)
  /var/log/freeswitch/freeswitch.xml.fsxml    (the COMPILED configuration)

Access:
  docker compose -f infra/docker-compose.freeswitch.yml logs -f freeswitch
  docker logs obd-freeswitch --tail 200
  docker exec obd-freeswitch tail -f /var/log/freeswitch/freeswitch.log
```

**Timestamps** are local to the container (UTC in this environment) and are
**not** the same clock as the Java application's log lines. When correlating
Java events with FreeSWITCH events, convert. This is a classic source of
"the event came before the cause" confusion that is really a clock-offset
confusion.

**Raising verbosity — and putting it back.** This is the operation most often
left in a bad state:

```text
⚠  PRODUCTION IMPACT — verbosity is not free.
    At debug level FreeSWITCH writes far more, consumes more CPU and disk, and
    can itself become a cause of an outage on a busy host. Raise it for a
    bounded window on a bounded number of profiles, and revert immediately.

    Global, via config (needs a restart):
      switch.conf.xml  loglevel  info -> debug
      ⚠  RESTARTS SERVICE
    Per profile, via config (needs a restart):
      sip_profiles/*.xml   debug  0 -> 10
      ⚠  RESTARTS SERVICE
    SIP message tracing, no restart:
      fs_cli -x "sofia global siptrace on"      ⚠  very noisy
      fs_cli -x "sofia global siptrace off"     <-- ALWAYS do this
```

**Never** run production at debug level permanently. Record in the change log
that it was raised and that it was reverted.

---

## 4. Active calls

```text
Command: fs_cli -x "show channels"
Purpose:  every live channel, its UUID and its state
Success:  you can match a UUID against what Java persisted as provider_call_id
          or what appeared in a CHANNEL_* event
When:     any live-call investigation

Command: fs_cli -x "show channels as json"
Purpose:  the same, machine-readable

Command: fs_cli -x "show bridges"
Purpose:  active bridges and the two channel UUIDs on each side
When:     CONNECT_BY_AGENT, or any bridged call

Command: fs_cli -x "uuid_dump <channel-uuid>"
Purpose:  EVERY channel variable: sip_call_id, bridge variables, the media
          address and port in use, hangup cause if set, DTMF state
Success:  a non-empty sip_call_id
When:     you need to correlate with a carrier's records, or understand why
          media is not flowing
Note:     this is the single most informative command in FreeSWITCH
```

**Correlating a channel to Java:**

```text
  1. Take the channel UUID from show channels, or from a CHANNEL_* event.
  2. Look it up:
       call_attempts.provider_call_id
       call_sessions.provider_call_id
       call_legs.provider_call_id
  3. That row tells you tenant, campaign, attempt number, and the state the
     platform believes the call is in.
  4. If no row matches, the channel is not one Java owns - a leftover, or a
     softphone's own channel.
```

---

## 5. SIP problems

```text
SYMPTOM: calls do not connect at all
  1. sofia status                  -> profiles RUNNING?
  2. sofia status gateway <name>   -> gateway loaded and proxy correct?
  3. module_exists mod_sofia
  4. netstat                       -> is the profile's port actually bound?
  5. FAILED-CALLS-OUT on the profile -> tells you the far end is rejecting

SYMPTOM: calls connect, immediately hang up
  -> Read Hangup-Cause on CHANNEL_HANGUP and map it (see 06-SIP.md).
     404 = nobody/misrouted.  486 = busy.  480 = unavailable.
     503 = transient.  603 = declined.

SYMPTOM: softphone cannot register
  1. sofia status profile internal  -> REGISTRATIONS count
    2. Is auth-subscriptions true?   (true since Phase C, but ONLY safe
       because the stock directory was replaced - see ADR-005)
  3. Is the softphone's target the right profile and port?
  4. Is its source IP inside obd-dev-acl?  Outside = silently dropped.
  5. acl.conf.xml

  SYMPTOM: REGISTER is answered 403 (a correct password still fails)
    -> 403 on the AUTHENTICATED retry does NOT mean "wrong password". It means
       "no such user for the realm that was challenged". Read the log, which
       names the exact user@realm FreeSWITCH looked for:
         grep sofia_reg freeswitch.log
       Three causes, in order of likelihood:
         a) the user id is defined twice (stock vs project) and the wrong one wins
         b) challenge-realm=auto_to produced a realm the directory does not define
         c) genuinely the wrong password, once (a) and (b) are ruled out
       See 06-SIP.md 11.2 and 11.3.

  SYMPTOM: a registration gateway reports [503]
    -> NOT a credential problem. The registrar cannot service the request; in
       this environment that means the platform switch is restarting. Note the
       retry backoff grows (30s, 60s, 90s), so fixing the cause does not restore
       registration promptly - restart the endpoint rather than waiting.
       See 06-SIP.md 11.7.

SYMPTOM: registration works, calls do not reach the endpoint
  -> the dialplan. Confirm the called number matches a pattern in the context
     the profile points at (public here).
```

---

## 6. RTP and audio problems

```text
SYMPTOM: no audio at all
  1. Is RTP flowing?  uuid_getvar <uuid> local_media_ip / remote_media_ip
  2. Which codec?    uuid_getvar <uuid> rtp_use_codec
     -> a codec was negotiated but neither side can encode/decode it
  3. Are the addresses reachable from each endpoint?
  4. Docker: is the port range published, and does the far side advertise an
     address the other can actually route to?  See 07-RTP-AND-MEDIA.md §9
  5. Check SDP for a 0.0.0.0 or unroutable address - this is the #1 cause of
     "connected but silent"

SYMPTOM: one-way audio
  -> usually a firewall or NAT direction issue, not FreeSWITCH.
     Check both media addresses, and whether one side's packets return.

SYMPTOM: audio cuts out after ~30 s
  -> rtp-timeout-sec. Note that this environment deliberately does not set it
     (deprecated in 1.11); the default applies.

SYMPTOM: playback to a caller fails
  1. Is /media/obd present and readable inside the container?
  2. Does the path shape match <root>/<tenant>/<asset>/<file>?
  3. What does the Playback-Error header on the PLAYBACK_ERROR event say?
  4. Can FreeSWITCH read the file AT ALL?
     docker exec obd-freeswitch ls -l <path>
```

---

## 7. ESL problems

```text
SYMPTOM: Java reports the provider is unavailable
  1. Is ESL listening?      netstat -tulnp | grep 8021
  2. Is the password right? compare infra/.env with the rendered config
  3. Is the ACL rejecting?  apply-inbound-acl in the rendered config
  4. Is the port published and is Java using the right host?
       host-run Java      -> localhost:8021
       containerised Java -> freeswitch:8021  (NOT localhost)
  5. mod_event_socket loaded?

SYMPTOM: Java is connected but receives no events
  1. Is the event subscription still open?  (no linger/heartbeat is sent, so
     a silently dead connection is not detected until something is written)
  2. Was `event plain` sent, with exactly the ten expected names?
  3. Is the event's Call-UUID equal to the channel UUID Java pinned?
  4. Are the header names cased exactly as FreeSWITCH spells them?
     See 05-ESL.md §3.

SYMPTOM: replies are unreadable / garbled
  -> framing. Content-Length must be honoured exactly. See 05-ESL.md §2.
```

---

## 8. Gateway problems

```text
SYMPTOM: gateway missing entirely ("0 gateways")
  -> register is defaulting to true and failing silently. Set register=false
     for a static trunk, or supply credentials for a dynamic one.
     See 13-TROUBLESHOOTING.md.

SYMPTOM: gateway present but every call fails instantly
  1. sofia status gateway <name>  -> Proxy address correct?
  2. Is the proxy resolvable FROM the FreeSWITCH container?
  3. Is the gateway bound to the profile you think?
  4. Is the dial string's gateway name exactly the configured name?

SYMPTOM: gateway shows DOWN / TRYING
  -> it is trying to register and failing. Check credentials and the far end.
     In THIS environment fs-gateway is supposed to be NOREG.
```

---

## 9. Hangup-cause correlation

This is the chain you must be able to walk in your head during an incident:

```text
  the far end returns an SIP status
            |
            |  mod_sofia translates SIP -> FreeSWITCH hangup cause
            |  (e.g. 486 -> USER_BUSY)
            v
  CHANNEL_HANGUP event, header  Hangup-Cause: USER_BUSY
            |            (and  Hangup-Cause-Code: 17)
            |  Java reads the SYMBOLIC header, not the numeric code
            v
  HangupCauseMapper.toFailureCode("USER_BUSY")
            |
            |  total and closed: never invents a meaning, never echoes
            |  provider text
            v
  a canonical business code, e.g.  BUSY
            |
            v
  recorded on the attempt, and classified for retry
```

**Two things that will send you down the wrong path:**

1. **Reading the wrong header.** `Hangup-Cause` is the symbolic name;
   `Hangup-Cause-Code` is the number. The platform reads the symbolic one.
   Reading the numeric one against a symbolic table silently produces
   `HANGUP_UNKNOWN` for every call.
2. **`HANGUP_UNKNOWN` is not necessarily a bug.** Causes outside the mapped set
   — `ORIGINATOR_CANCEL`, `CHANNEL_UNBRIDGED`, `SUBSCRIBER_ABSENT` — resolve
   to it, and it is treated as retryable. In a bridged call, a leg ending with
   `CHANNEL_UNBRIDGED` is normal teardown, not a failure.

**Do not "fix" the mapper to make a test pass.** The failure matrix must first
record what FreeSWITCH actually sends. The audit doc
`docs/VB-6D.1-FAILURE-TAXONOMY-IMPLEMENTATION.md` is the contract.

---

## 10. Recovery — safe actions

In escalating order. Each is marked.

```text
  fs_cli -x "reloadxml"
      Safe. Reloads dialplan/directory. Does not reload sofia profiles.
      Use for a dialplan or directory fix.

  fs_cli -x "sofia profile <name> rescan"
      Safe-ish. Re-reads one profile. Drops nothing.

  fs_cli -x "sofia global siptrace off"
      Safe. Use to undo a tracing change you forgot about.

  fs_cli -x "module_exists <name>"
      Read-only.
```

## Recovery — actions with impact

```text
  ⚠  TERMINATES CALLS
      fs_cli -x "uuid_kill <uuid> <cause>"
      Drops one live leg. Use a cause that matches the intent - the platform
      treats a normal release as a SUCCESSFUL completion, so the cause
      changes the recorded business outcome.

  ⚠  RESTARTS SERVICE
      docker compose -f infra/docker-compose.freeswitch.yml restart freeswitch
      Drops the ESL connection (Java reconnects within ~60 s) and every live
      call. All in-memory state is lost.
      In development: fine.
      In production: a change window. Confirm the call queue is drained first.

  ⚠  DESTRUCTIVE
      docker compose -f infra/docker-compose.freeswitch.yml down
      Removes the container and the network.
      Configuration on disk survives. Container state does not.

      NEVER, as a reflex:
        docker system prune / docker volume prune / docker image prune
      These can destroy images and volumes belonging to OTHER projects on the
      same machine. On this machine, C: has limited free space, which creates
      pressure to prune - that pressure is not a reason to.
```

---

## 11. Development vs staging vs production

| | Development (this) | Staging | Production |
|---|---|---|---|
Host | single Windows machine | single Linux host | multiple, load-balanced |
ESL binding | loopback + published loopback | private VLAN | private VLAN, never public |
SIP binding | loopback-published | private | public, behind a firewall and a provider trunk |
RTP ports | 100, loopback-published | full range to a SIP-aware firewall | full range, monitored |
Credentials | local random secret in a git-ignored file | secret manager | secret manager, rotated |
SIP auth | **disabled** | per-extension | per-extension, strong, rate-limited |
Failover | none | none expected | DNS/SIP-level failover between FreeSWITCH nodes |
Media | bind mount, read-only | shared volume or object store | object store / CDN |
Logging | console, `loglevel=info` | shipped to a log system | shipped, with retention and alerting |
This document's commands | all safe | all safe | read-only only without a change window |

**The three biggest things that are different in production, and that this
document does not yet cover:**

1. **SIP is exposed to the internet.** Everything here assumes loopback. Real
   exposure means scanning, brute-force registration attempts, header
   injection, and volumetric abuse. ACLs and rate limiting become
   load-bearing.
2. **There is more than one instance.** Channel UUIDs, ESL sessions and
   registrations are per-instance. Anything that assumes "the" FreeSWITCH needs
   rethinking, including how Java's single ESL subscription is load-balanced.
3. **Failures are not yours alone.** A carrier can send unexpected responses,
   codecs can mismatch, and RTP can be blocked by someone else's firewall.
   Assume the far end is hostile and write the tests that way.

---

## 12. Pre-flight checklist for any future production change

```text
  [ ] Change reviewed, and its operational impact written down
  [ ] Config validated offline where possible (does it parse?)
  [ ] Blast radius understood: how many calls does a restart drop?
  [ ] Rollback plan written BEFORE the change
  [ ] Monitoring in place for the thing being changed
  [ ] Verbosity NOT left raised
  [ ] A change window agreed if a restart is required
  [ ] Documentation updated in the same change  (see the project rule:
      documentation is part of acceptance, not an afterthought)
```

---

## 13. PHASE C: operational changes and the new production blockers

Phase C enabled SIP registration and added two test endpoint containers. Both
change the operational picture, and one of them moves the environment closer to
being dangerous rather than further away.

### 13.1 What an operator will see that is new

| Observation | Is it a fault? |
|---|---|
| two registrations in `sofia status profile internal reg` | **No.** The local test endpoints. Contacts look like `sip:gw+platform-switch@172.25.0.x:5060`. |
| three containers instead of one | **No.** `obd-freeswitch`, `obd-fs-endpoint-1001`, `obd-fs-endpoint-1002`. |
| `auth-subscriptions` is `true` | **No**, provided the stock directory stayed replaced. |
| a `503` from an endpoint gateway | Usually the platform restarting. Not a credential fault. |
| host SIP over UDP does not work | Expected on this machine. Use TCP, or an in-network endpoint. |

### 13.2 New production blocker: no registration rate limiting

**This is the one item Phase C moved closer to being dangerous.** The internal
profile now accepts registrations, and it has **no rate limiting and no
lockout**. See [14-SECURITY.md](14-SECURITY.md) section 3.

On a loopback-published port that is acceptable. On any reachable network it is
not, and it is the classic path to an open SIP proxy being used to make
fraudulent calls. Required before exposure:

1. registration rate limiting per source address
2. failed-authentication lockout with backoff
3. an explicit decision on whether registration should be reachable from any
   network at all, or only from a known SIP peer

### 13.3 New production blocker: the pinned UUID is not durable

The platform pins `origination_uuid` so it can address a channel deterministically.
Phase C established that **a dialplan transfer mints a new UUID** and the pinned
one is discarded ([02-CALL-FLOW.md](02-CALL-FLOW.md) 8.4, defect **J3**).

Consequence for operations: any diagnostic that finds a call by its `CallAttempt`
UUID will find nothing once the stock dialplan has transferred the channel.
Nothing should be built on that assumption until the dialplan is replaced.

### 13.4 The endpoints are not a security boundary

`obd-fs-endpoint-1001` and `-1002` are **test fixtures**. They:

* publish ESL on loopback so their own media counters can be read;
* register with credentials from `infra/.env`;
* answer every call and emit a fixed DTMF sequence.

They must never run in an environment reachable from anything untrusted, and they
must be removed entirely before any production deployment. They live in a
**separate Compose project** (`obd-fs-endpoints`) precisely so they can be stopped
or deleted without touching the platform switch.

### 13.5 Operational checks worth adding

```bash
# 1. the stock users must not be back in the directory
docker exec obd-freeswitch sh -c \
  grep -oE 'user id=.[0-9]+' /etc/freeswitch/directory/default.xml
# expect exactly: 1 x 1001, 1 x 1002

# 2. the realm must be pinned, not auto_to
fs_cli -x "sofia status profile internal" | grep -i challenge
# expect: Challenge Realm  172.25.0.2   (an address, not "auto")

# 3. ESL must be bound to the container address, not loopback
docker exec obd-freeswitch netstat -tlnp | grep 8021
# expect: 0.0.0.0:8021     (loopback here means the published port is broken)

# 4. the endpoints are registered and reachable
fs_cli -x "sofia status profile internal reg" | grep -c Reachable
# expect 2

# 5. no runtime state is tracked by git
git status --short | grep -E 'fsxml|logs-'     # expect no output
```

Check 3 catches the failure in [13](13-TROUBLESHOOTING.md) entry 13, which is
invisible to a health check and to every other check here.
