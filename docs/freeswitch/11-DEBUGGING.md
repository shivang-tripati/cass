# 11 — Debugging Runbook
| A build fails with 0 tests and a "cannot access the file" error | the build log is being written INTO the directory `clean` deletes | [13](13-TROUBLESHOOTING.md) entry 30 |
| A call answers then dies in under a second | an `[ERR] Invalid Application` in the far end log; the cause string is misleading | [13](13-TROUBLESHOOTING.md) entry 28 |
| `invalid uuid` / `No such channel` but `uuid_dump` works | the channel is already gone - measure its lifetime, do not blame the command | [13](13-TROUBLESHOOTING.md) entry 29 |

| The whole suite reports 0 tests | is ONE test file failing `testCompile`? narrow with `testIncludes` - do not edit the other owner's file | [13](13-TROUBLESHOOTING.md) entry 30 |
Practical. Written to be followed top to bottom during an incident.

> **Phase B scope note.** No call has been placed in this environment yet. The
> call-related branches of the decision tree below are marked
> **NOT YET TESTED** — they describe where to look, and they will be corrected
> by real evidence in later phases. The infrastructure branches (top half) are
> **CONFIRMED** and have been exercised repeatedly.

---

## 1. The five-minute triage

Do these in order. Stop when you find the answer. Most incidents are resolved
in steps 1–3.

```text
  STEP 1  Is the container running and healthy?
          docker compose -f infra/docker-compose.freeswitch.yml ps
          Look for "healthy", not merely "running".

  STEP 2  Is FreeSWITCH itself alive?
          fs_cli -x "status"
          Look for a first line beginning "UP".

  STEP 3  Is the subsystem you care about up?
          sofia status            (SIP profiles + gateways)
          module_exists <name>    (a specific module)

  STEP 4  Read the log.
          docker compose -f infra/docker-compose.freeswitch.yml logs --tail 200
          Start from the BOTTOM — the newest error is the relevant one.

  STEP 5  Only now, change one thing and retest.
```

**The single most important rule in this document:** change **one** thing, then
retest. Changing three settings and re-running tells you nothing about which
one mattered, and is how an environment ends up in a state nobody understands.

---

## 2. Decision tree — infrastructure

```text
CONTAINER NOT RUNNING
   |
   +-- Exited immediately, repeatedly (crash loop)?
   |      |
   |      +-- docker compose ... logs
   |          |
   |          +-- "Cannot Initialize [[error near line N]: unclosed <!--]"
   |          |      -> malformed XML. Look for NESTED <!-- --> inside a comment.
   |          |         See 13-TROUBLESHOOTING.md
   |          |
   |          +-- "FATAL: refusing a known-weak ... FREESWITCH_PASSWORD"
   |          |      -> the entrypoint refused to start. Fix infra/.env.
   |          |
   |          +-- "FATAL: /media/obd does not exist"
   |          |      -> the media bind mount failed
   |          |
   |          +-- "[CRIT] Error Loading module ..."  (repeatedly)
   |                 -> a module failed. Is it mod_av? That one is a known,
   |                    harmless image defect. Anything else is real.
   |
   +-- Exited once, cleanly?
          -> probably the entrypoint. Read the FIRST log lines, not the last.

CONTAINER RUNNING BUT NOT HEALTHY
   |
   +-- healthcheck failing
          |
          +-- fs_cli -x "status" works?
          |      +-- NO  -> FreeSWITCH is up but ESL is not reachable.
          |      |         Check 8021 bind + password.
          |      +-- YES -> healthcheck is misconfigured. Compare its command
          |                with the one in docker-compose.freeswitch.yml.
          |
          +-- "fs_cli: Error Connecting []"
                 -> almost always the password. Re-read it from infra/.env
                    rather than retyping it.

fs_cli SAYS "0 profiles"
   |
   +-- The profile file's ROOT ELEMENT must be <profile name="...">
      A <configuration name="sofia.conf"> root compiles fine and is ignored.
      See 13-TROUBLESHOOTING.md

PROFILE PRESENT BUT NOT "RUNNING"
   |
   +-- Is the port already taken?
      docker exec obd-freeswitch netstat -tulnp | grep -e 5060 -e 5080
      A leftover listener means a second instance or a stale container.
   |
   +-- Is the ACL denying it?  check apply-inbound-acl in the profile

"0 gateways"
   |
   +-- Does the gateway have register="false"?
      mod_sofia DEFAULTS register to true. With the default it tries to
      REGISTER, fails, and DISCARDS the gateway with no log line.
      See 13-TROUBLESHOOTING.md
   |
   +-- Is the <gateways> block inside the PROFILE node, not sofia.conf?
   |
   +-- Is the gateway name valid? letters, digits, . - _ only.
      A name with a space is rejected with "Ignoring invalid name"
      in the log at DEBUG level.

A CONFIG CHANGE "DID NOTHING"
   |
   +-- Is the file actually in the container?
          docker exec obd-freeswitch cat <path>
   +-- Did it survive preprocessing?
          docker exec obd-freeswitch grep -c "<your marker>" \
            /var/log/freeswitch/freeswitch.xml.fsxml
   +-- Did a MODULE actually read it?  change one value to something
      unmistakable and see if FreeSWITCH echoes it. See 03-CONFIGURATION.md §6
```

---

## 3. Decision tree — calls (NOT YET TESTED)

This is where the platform's real work will be found. Written from the
predicted flows; correct it as evidence arrives.

```text
"Java says the call started but nothing happened"
   |
   +-- Did the originate command get a +OK?
   |      |
   |      +-- NO  -> read the -ERR text. Then:
   |      |         - "no route"/"gateway" -> gateway not loaded, or name wrong
   |      |         - "not a valid UUID"  -> origination_uuid missing/malformed
   |      |         - anything else       -> re-run by hand in fs_cli
   |      |
   |      +-- YES -> accepted only. Keep going. +OK from bgapi does NOT mean
   |                 the call connected.
   |
   +-- Did a SIP INVITE leave?
   |      |
   |      +-- NO  -> the gateway's proxy is unreachable, or DNS fails, or
   |      |         the dial string is wrong.
   |      |         Check: sofia status gateway fs-gateway
   |      |         Turn on SIP tracing (DEVEL ONLY) to see the attempt.
   |      |
   |      +-- YES -> the far end received it. Keep going.
   |
   +-- What SIP response came back?
   |      |
   |      +-- 100/180 only, then nothing -> no answer. Look for CHANNEL_HANGUP
   |      |         and read Hangup-Cause.
   |      +-- 200 OK -> answered. Look for CHANNEL_ANSWER.
   |      +-- 4xx/5xx -> rejected. The response maps to a hangup cause.
   |      +-- 404 -> nobody at that number / gateway misrouting
   |      +-- 486 -> busy.  480 -> unavailable/no answer.  603 -> declined.
   |
   +-- Is RTP flowing?  (see the media section of 07-RTP-AND-MEDIA.md)
   |
   +-- Did Java receive the event?
          |
          +-- NO  -> is Java's ESL subscription still open?
          |         Is the channel UUID equal to the one Java pinned?
          |         Was the event header name cased exactly as expected?
          |         See 05-ESL.md §3.
          |
          +-- YES but Java ignored it
                 -> correlation. Check provider_call_id in the database
                    against the Call-UUID on the event.
```

---

## 4. "The call is not working. What do I check first?"

As a decision tree, with the reasoning attached:

```text
Is FreeSWITCH UP?  ── no ──► container / logs / process
        │ yes
        v
Is mod_sofia loaded?  ── no ──► module list / config
        │ yes
        v
Is the profile RUNNING?  ── no ──► profile config / port bind / ACL
        │ yes
        v
Did Java connect to ESL?  ── no ──► event_socket config / port / auth
        │ yes
        v
Did FreeSWITCH accept the originate?  ── no ──► dial string / gateway / UUID
        │ yes
        v
Did an INVITE leave?  ── no ──► gateway proxy / DNS / reachability
        │ yes
        v
Did the far end answer?  ── no ──► SIP response / hangup cause
        │ yes
        v
Is RTP flowing?  ── no ──► SDP address / codec / Docker path
        │ yes
        v
Did Java receive and process CHANNEL_*?  ── no ──► ESL subscription / headers
        │ yes
        v
The platform's own logic is wrong  ──► Java-side investigation, not FreeSWITCH
```

---

## 5. Turning up verbosity — safely, and putting it back

**Never leave verbosity raised.** In development it wastes disk and buries real
errors; in production it can degrade performance and fill a disk.

```text
⚠  DEVEL ONLY — temporarily raises FreeSWITCH's log level.
    Edit infra/freeswitch/conf/autoload_configs/switch.conf.xml:
        <param name="loglevel" value="info"/>     ->  "debug"
    ⚠  RESTARTS SERVICE
    docker compose -f infra/docker-compose.freeswitch.yml restart freeswitch
    ...
    ⚠  ALWAYS put it back to "info" and restart again.
```

```text
⚠  DEVEL ONLY — logs every SIP message for one profile. Very noisy.
    fs_cli -x "sofia global siptrace on"
    fs_cli -x "sofia global siptrace off"        <-- do not forget this
```

```text
DEVEL ONLY — show SIP messages for one profile only.
    Edit the profile XML:  <param name="debug" value="0"/>  ->  "10"
    ⚠  RESTARTS SERVICE. Revert to 0 afterwards.
```

```text
DEVEL ONLY — reload config without a restart (safer than restarting when you
only changed a dialplan or a directory).
    fs_cli -x "reloadxml"
    Success: "+OK [Success]"
    Note:     this does NOT reload the sofia profiles. For those use:
              fs_cli -x "sofia profile <name> rescan"
```

---

## 6. Reading the logs

```text
Where the log lives:
    Host      infra/freeswitch/logs/   (bind-mounted to /var/log/freeswitch)
    Container /var/log/freeswitch/freeswitch.log
    Stream    docker compose -f infra/docker-compose.freeswitch.yml logs -f freeswitch

The compiled configuration also lives there:
    /var/log/freeswitch/freeswitch.xml.fsxml
    This is the single most useful file when config changes appear to be
    ignored. See 03-CONFIGURATION.md §1.
```

**How to read a line:**

```text
2026-09-28 05:14:34.996883  0.00% [WARNING] sofia.c:5319 rtp-timeout-sec deprecated
                              |         |          |        |         |
                              |         |          |        |         the message
                              |         |          |        source file
                              |         |          |        source LINE NUMBER
                              |         |          |        severity
                              |         |          |        session percentage
                              |         |          |        microseconds since start
                              |         |          |        timestamp, local to the
                              |         |          |        container (UTC in this env)
                              |         |          |        process id
                              |         |          |        date
```

**Read from the bottom up.** The last error before the symptom is almost always
the cause. `grep` the log for the relevant term rather than reading it all:

```text
docker logs obd-freeswitch 2>&1 | grep -i -e gateway -e profile
docker logs obd-freeswitch 2>&1 | grep -i -e Cannot\ Initialize -e CRIT -e ERR
```

**Severity that matters to you:**

| Level | Meaning here |
|---|---|
`ERR` | something failed. Look at it. |
`CRIT` | something failed to load. The feature is absent. |
`WARNING` | often deprecation noise; read it, decide if it matters, then move on. |
`NOTICE` | module and profile lifecycle — useful for confirming what loaded. |
`INFO` | normal. |

---

## 7. Things that look like failures but are not

| Observation | What it actually is |
|---|---|
`mod_av` fails to load | Pre-existing image defect (missing `libavformat.so.62`). Nothing in the plan needs it. |
`STUN Failed! [Timeout]` twice at startup | Stock `vars.xml` runs two `stun-set` lookups. Results are unused here. Costs ~10 s of startup. Not a fault. |
`Gateway ... NOREG` | **Correct.** `fs-gateway` is a static origination-only trunk with `register=false`. Nothing to register to yet. |
`Failed to set SCHED_FIFO` | The host kernel/WSL2 does not implement real-time scheduling. FreeSWITCH continues. |
`mod_verto` binds `1337/udp` and logs `Invalid External RTP IP` | mod_verto is stock-enabled and unused here. The port is not published. |
Startup takes ~35 s | Two 5 s STUN timeouts plus normal init. The healthcheck's `start_period` covers it. |
`0 aliases` in `sofia status` | Normal. Aliases come from a `<domains parse=...>` block this project does not use. |
FreeSWITCH is `UP` but a call fails | `UP` only means the process is alive. Check the subsystem. |

---

## 8. "Where do I look?" — consolidated

| Problem | First place | Second place | Label |
|---|---|---|---|
| Container will not start | `docker compose logs` | XML parse error -> [13](13-TROUBLESHOOTING.md) | CONFIRMED |
| Container healthy, `fs_cli` will not connect from the host | **is 8021 bound to the container address?** `netstat` inside | [13](13-TROUBLESHOOTING.md) entry 13 | CONFIRMED |
| `fs_cli` works inside but the published port returns 0 bytes | `listen-ip` in the rendered `event_socket.conf.xml` | [13](13-TROUBLESHOOTING.md) entry 13 | CONFIRMED |
| A module is inert but FreeSWITCH is UP | **root element: `<configuration>` not `<config>`** | [13](13-TROUBLESHOOTING.md) entry 14 | CONFIRMED |
| Correct password answered `403` | **read the `sofia_reg.c` log line** - it names the `user@realm` | extension collision / realm; entry 15 | CONFIRMED |
| Registration gateway reports `[503]` | is the platform switch restarting? | restart the endpoint; backoff is long | CONFIRMED |
| Registration gateway reports `[403]` | is the gateway registering by **alias** instead of address? | entry 15, and `06-SIP.md` 11.8 | CONFIRMED |
| SIP profile missing | profile file **root element** | [13](13-TROUBLESHOOTING.md) entry 14 | CONFIRMED |
| SIP profile not RUNNING | `No Settings` -> are `<param>`s inside `<settings>`? | port already bound | CONFIRMED |
| Gateway missing | `register` param | [13](13-TROUBLESHOOTING.md) | CONFIRMED |
| Gateway `NOREG` | is that expected? (yes, for `fs-gateway`) | — | CONFIRMED |
| Call connects but **no audio** | **`No dial-string` in the log** - the bridge never happened | [13](13-TROUBLESHOOTING.md) entry 16 | CONFIRMED |
| Which RTP ports are actually in use | `uuid_getvar <uuid> local_media_port` on a live call | [07](07-RTP-AND-MEDIA.md) 11.1 | CONFIRMED |
| Need to prove audio is flowing | **read the far end`s counters**, not the near end`s | `rtp_audio_in_packet_count` is `_undef_`; entry 17 | NOT YET TESTED |
| Playback "failed" but no error event | **absence of `PLAYBACK_START`**; then the `mod_sndfile` log line | [13](13-TROUBLESHOOTING.md) entry 17; [05](05-ESL.md) 9.4 | CONFIRMED |
| Compressed audio will not play | `mod_av` cannot load on this image - use PCM WAV | [07](07-RTP-AND-MEDIA.md) 11.5 | CONFIRMED |
| DTMF missing | media must flow first, then `rfc2833-pt` on **both** ends | [06](06-SIP.md) 11.10 | NOT YET TESTED |
| Agent DTMF lost after bridging | `proxy-info` | [06](06-SIP.md) §8 | NOT YET TESTED |
| Java sees events but cannot correlate them | **is `Call-UUID` actually present?** read `Unique-ID` | defect J1, [05](05-ESL.md) 9.1 | CONFIRMED |
| `+OK Job-UUID` but no call | the command was queued, not executed - look for `CHANNEL_CREATE` | [05](05-ESL.md) 9.6 | CONFIRMED |
| An ESL command times out on a busy connection | commands and events share one socket | [05](05-ESL.md) 9.7 | CONFIRMED |
| Bridge not confirmed | `Bridge-A-Unique-ID` / `Bridge-B-Unique-ID` | `show bridges` | CONFIRMED |
| A `uuid_kill`/`uuid_broadcast` does nothing | **did the dialplan transfer mint a new UUID?** | defect J3, [05](05-ESL.md) 9.8 | CONFIRMED |
| Unexpected hangup | the SIP response | `Hangup-Cause` vs the Java mapper | NOT YET TESTED |
| A config change did nothing | the compiled `freeswitch.xml.fsxml` | [03](03-CONFIGURATION.md) §6 | CONFIRMED |
| Two containers crash-loop with `Cannot Open log directory` | **do they share a log directory?** | entry 18 | CONFIRMED |
| A mounted template seems ignored | **do two templates share one mount target?** | entry 18 | CONFIRMED |
| A per-service `volumes:` broke the shared config | a YAML key **replaces** the anchor, it does not append | entry 18 | CONFIRMED |

---

## 9. Safe versus destructive

Read-only. Safe at any time:

```text
fs_cli -x "status"
fs_cli -x "sofia status"
fs_cli -x "sofia status profile internal"
fs_cli -x "sofia status gateway"
fs_cli -x "module_exists mod_sofia"
fs_cli -x "show channels"
fs_cli -x "show bridges"
docker compose -f infra/docker-compose.freeswitch.yml ps
docker compose -f infra/docker-compose.freeswitch.yml logs
docker exec obd-freeswitch netstat -tulnp
```

Affects live state — know what you are doing:

```text
⚠  RESTARTS SERVICE
    docker compose -f infra/docker-compose.freeswitch.yml restart freeswitch
    Drops the ESL connection. Java's event loop reconnects on its next
    60-second sweep. In development: harmless.
    In production: a change window, and expect brief event loss.

⚠  RELOADS CONFIG
    fs_cli -x "reloadxml"
    Softer than a restart for dialplan/directory changes. Does not reload
    sofia profiles.

⚠  TERMINATES CALLS
    fs_cli -x "uuid_kill <uuid> <cause>"
    Drops a live call leg. Also how the platform enforces max call duration.

⚠  DESTRUCTIVE
    docker compose -f infra/docker-compose.freeswitch.yml down
    Removes the container and the network. Config on disk survives; the
    container state does not. Note: `down` also removes the network, which
    other compose projects do not share — but never add `-v` casually, and
    never run docker prune.
```

---

## 10. When you are completely stuck

Collect this before asking anyone:

```text
1.  docker compose -f infra/docker-compose.freeswitch.yml ps
2.  fs_cli -x "status"
3.  fs_cli -x "sofia status"
4.  docker compose -f infra/docker-compose.freeswitch.yml logs --tail 200
5.  The exact command you ran and its exact output, unedited.
6.  Which layer you believe is at fault, and why.
7.  What you changed immediately before it started happening.
```

Layers, in order — never skip one:

```text
  Docker
    v
  FreeSWITCH startup
    v
  module loading
    v
  configuration parsing
    v
  ESL
    v
  SIP
    v
  RTP / media
    v
  Java integration
    v
  campaign execution
```

Most long debugging sessions are wasted because someone started at the bottom
of this list.

---

## 11. PHASE C additions to the runbook

The Phase C-specific commands, and what each one actually tells you. All of them
are read-only.

### 12.1 Ask the running system, not the file

The single most useful habit established in Phase C. Every real defect was found
by a question with a machine-readable answer, not by reading configuration.

```bash
# Which address did the process actually BIND? Not what the file says.
docker exec obd-freeswitch netstat -tlnp | grep -E '8021|5060'

# Is the registration actually stored, and where?
fs_cli -x "sofia status profile internal reg"

# What realm does this profile challenge with?
fs_cli -x "sofia status profile internal" | grep -i challenge

# What did the directory actually resolve to? (compiled, not source)
docker exec obd-freeswitch sh -c \
  'grep -c "name=\"dial-string\"" /var/log/freeswitch/freeswitch.xml.fsxml'

# Are there stock users present? (they must not be)
docker exec obd-freeswitch sh -c \
  grep -oE 'user id=.[0-9]+' /etc/freeswitch/directory/default.xml
```

### 12.2 Live RTP inspection

```bash
# Which ports are actually allocated, on a live call?
fs_cli -x "uuid_getvar <uuid> local_media_port"
fs_cli -x "uuid_getvar <uuid> remote_media_port"
fs_cli -x "uuid_getvar <uuid> remote_media_ip"
fs_cli -x "uuid_getvar <uuid> read_codec"

# Did RTP counters arrive? (they do not, on this build)
fs_cli -x "uuid_getvar <uuid> rtp_audio_in_packet_count"   # -> _undef_

# Turn on media debug. NOTE: this is not a statistics command.
fs_cli -x "uuid_debug_media <uuid> both on"
```

**`uuid_debug_media` is the trap in this area.** Its signature is
`uuid_debug_media <uuid> <read|write|both|...> <on|off>` - it *toggles* debug
logging. Called with only a UUID it prints a usage string that looks like an
error. Reading it as a statistics command wastes time and can produce a false
PASS. See [13](13-TROUBLESHOOTING.md) entry 17.

### 12.3 The three things to keep separate

Every media assertion in this project has been wrong at least once because these
were conflated. Keep them distinct:

| Claim | How to check | What it proves |
|---|---|---|
| a port was allocated | `local_media_port` | configuration took effect |
| packets were **sent** | peer-side counters, or a capture | the socket was written |
| packets were **received** | **the far end's** counters | audio arrived |

| ESL answers `-ERR command not found` for a command the platform sends | **is it api-prefixed?** only `api`/`bgapi`/`event`/... are inbound commands | [13](13-TROUBLESHOOTING.md) entry 19 |
| A command the switch ACCEPTED is reported as a failure | an `api` reply has an empty `Reply-Text`; the result is in the body | [13](13-TROUBLESHOOTING.md) entry 20 |
| Every event is dropped with "without Call-UUID" | the switch sends `Channel-Call-UUID`/`Unique-ID`; read the real frame | [13](13-TROUBLESHOOTING.md) entry 21 |
| A whole subsystem is green but has never worked | is the test double rejecting unknown commands? | [13](13-TROUBLESHOOTING.md) entry 22 |
| Playback silently did nothing | no `PLAYBACK_START` - the file could not be opened | [05](05-ESL.md) 12.6 |
| Registrations look contradictory, or `NO_ROUTE_DESTINATION` | a container address changed; stale registrations linger until expiry | [05](05-ESL.md) 12.8 |
Only the third justifies "audio works". The far end must be reachable, which is
| A file I edited is suddenly `D` in git status | another session parked it as `*.parked`; restore the copy that holds your work | [13](13-TROUBLESHOOTING.md) entry 23 |
why the test endpoints publish ESL on `127.0.0.1:8031` and `:8032`.
| The switch answers a call the peer never saw | is a local app answering your dialstring? (stock dialplan uses voicemail) | [13](13-TROUBLESHOOTING.md) entry 24 |
| `NO_ROUTE_DESTINATION`, transport provably fine | mod_sofia routes by domain - is there a registration for that profile? | [13](13-TROUBLESHOOTING.md) entry 25 |
| `acl` denies everything, even `127.0.0.1` | argument order is `acl <host> <listname>` | [13](13-TROUBLESHOOTING.md) entry 26 |
| A gateway ignores its `sofia-profile` | the profile that DECLARES a gateway is the one it originates from | [13](13-TROUBLESHOOTING.md) entry 27 |
| "The peer has no channels" but the call works | `uuid_dump` with no arg returns `-USAGE`; use `show channels` | [06](06-SIP.md) 12.7 |

### 12.4 Reading a call end to end

```bash
# 1. the channel exists and is the one you pinned
fs_cli -x "show channels" | grep <your-uuid>

# 2. it was answered
fs_cli -x "uuid_dump <uuid>" | grep -i -E 'answer|state|codec'

# 3. the legs were joined
fs_cli -x "show bridges"

# 4. media was allocated
fs_cli -x "uuid_getvar <uuid> local_media_port"

# 5. it ended, and why
fs_cli -x "uuid_dump <uuid>" | grep -i -E 'hangup|cause|disposition'
```

`show channels` prints one line per channel with a trailing bracketed UUID. That
is the fastest way to get a UUID from a dial string when one was not pinned.

### 12.5 Driving the local endpoints

The endpoints have their own ESL, published on loopback, so their internal state
can be inspected the same way as the platform's:

```bash
$epw = <ENDPOINT_ESL_PASSWORD from infra/.env>
docker exec obd-fs-endpoint-1002 fs_cli -H 127.0.0.1 -P 8021 -p $epw -x "show channels"
docker exec obd-fs-endpoint-1002 fs_cli -H 127.0.0.1 -P 8021 -p $epw \
  -x "sofia status gateway platform-switch"
```

A `503` from `sofia status gateway` means the platform is restarting, not that
credentials are wrong. See [06](06-SIP.md) 11.7.

### 12.6 Reading an event frame without writing a client

```bash
$pw = <FREESWITCH_PASSWORD from infra/.env>

# subscribe, then originate, then read raw frames
fs_cli -p "$pw" -x "event plain CHANNEL_CREATE CHANNEL_ANSWER CHANNEL_HANGUP"
fs_cli -p "$pw" -x "bgapi originate {origination_uuid=11111111-2222-3333-4444-555555555555}\
sofia/internal/1002@172.25.0.2 &park()"
```

Use a **second `fs_cli` connection** for the command. On a connection that is
also receiving subscribed events, a command can read the next *event* instead of
its reply, and then everything after it is off by one frame. The symptom is a
command that hangs while the switch is perfectly healthy.

The Phase C harness (`tools/freeswitch-harness/`) does this properly and prints
complete, unfiltered header sets: `c6_headers_verbatim.py` for the header
inventory, `c5_rtp_counters.py` for media, `c4_bridge_search.py` for which dial
strings actually work.

### 12.7 A shell-quoting trap that wastes real time

Some of the diagnostic commands in this knowledge base look correct and are
unusable from PowerShell, because the argument passes through **two** shells -
PowerShell, then the container's `sh`. A pattern containing escaped double quotes
gets mangled somewhere in that chain, and the failure looks like a FreeSWITCH
problem:

```text
PS> docker exec obd-freeswitch sh -c 'grep -o "user id=\"[0-9]*\"" /etc/.../default.xml'
/bin/sh: line 0: user id=.: not found
exit 1
```

That exit code is a **quoting** failure, not an empty result. The trap is
particularly nasty here, because the healthy answer for these checks *is* a short
or empty output.

**The robust form: pass the command to `docker exec` directly, with no `sh -c`
at all.**

```text
PS> docker exec obd-freeswitch grep -oE 'user id=.[0-9]+' /etc/freeswitch/directory/default.xml
user id="1001
user id="1002
```

Two habits follow:

* **Use `docker exec <container> <cmd> <args...>`** rather than
  `docker exec <container> sh -c '<cmd>'` whenever the command is a single
  program. It removes a whole shell from the chain.
* **Never conclude "no results" from a non-zero exit alone.** Re-run with a
  pattern you know must match, to distinguish "no match" from "the command never
  ran". The Phase C harness works around this entirely by copying a small script
  into the container (`docker cp`) and running `sh /tmp/x.sh`.

### 12.8 When you are out of ideas

Ask FreeSWITCH to name the failure. Three greps that have each solved a real
problem in this project:

```bash
# which user did the registrar actually look for?
grep 'sofia_reg' freeswitch.log

# which dial string did the platform actually try to bridge?
grep 'No dial-string\|CHAN_NOT_IMPLEMENTED\|USER_NOT_REGISTERED' freeswitch.log

# what did the file layer do with the media?
grep 'mod_sndfile\|mod_file' freeswitch.log
```

Each of those lines states the cause in a sentence. Chasing the symptom
(passwords, codecs, firewall) instead of reading the line is what turns a
five-minute fix into an afternoon.
