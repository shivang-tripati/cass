# 07 — RTP and Media

How audio gets from the application's filesystem to a caller, and why the
volume layout looks the way it does.

---

## 1. Three different things, routinely confused

```text
  SIP    signalling   "let's call, I can do PCMU, media is at 10.0.0.5:30010"
                     text protocol, carries NO audio

  RTP    media        the actual audio samples, in real time
                     binary, time-sensitive, lossy-tolerant UDP

  ESL    control      Java -> FreeSWITCH: "play this file on this channel"
                     FreeSWITCH -> Java: "the call was answered"
                     carries NO audio and NO SIP
```

A call can be perfect on SIP, perfect on ESL, and completely silent on RTP.
Those are three independent investigations. This document is about the third.

---

## 2. The media path in this environment

```text
  Application                          FreeSWITCH container
  ───────────                          ────────────────────

  audio asset row in PostgreSQL
          │
          │  storage_reference =  audio/<tenantId>/<assetId>/<file>.wav
          │  (a LOGICAL name, meaningful only to the app)
          v
  MediaUriResolver
          │  validates: exactly 3 segments, prefix "audio/",
          │  two parseable UUIDs, tenant must match the caller,
          │  asset id must match, no "..", no backslash, no scheme
          v
  /usr/share/freeswitch/sounds/<tenantId>/<assetId>/<file>.wav
          │
          │  audio.storage.freeswitch-media-root  +  the reference segments
          │
          │  ========== the FreeSWITCH-visible path ==================
          v
  ESL: uuid_broadcast <channel-uuid> /usr/share/freeswitch/sounds/.../x.wav aleg
          │
          v
  FreeSWITCH reads the file, encodes it, sends it as RTP
          │
          v
  audio reaches the caller
```

**With the media root overridden to `/media/obd`**, the path becomes:

```text
/media/obd/<tenantId>/<assetId>/<file>.wav
```

and the host directory `backend/data/audio` is bind-mounted there.

---

## 3. Why the volume is `backend/data/audio` → `/media/obd`

```text
  D:\work\agile\obd-platform\backend\data\audio      (host)
              │  Docker bind mount, READ-ONLY
              v
  /media/obd                                          (in the container)
```

Three decisions, each with a reason.

**Decision: a bind mount of a host directory, not object storage.**
**Why:** this project's plan explicitly defers S3/object storage for media. A
bind mount is the simplest thing that makes a file the application wrote
readable by FreeSWITCH, and it is trivially inspectable — you can look at the
directory from both sides.
**Alternative rejected:** RustFS/S3, which already exists in this repository for
other purposes. Adding it would make audio debugging require HTTP tooling and
would obscure the simplest possible failure.
**Consequence:** audio files are local-only. There is no CDN, no caching, and
no multi-host media story. That is fine for a single-host development
environment and must be revisited before production.

**Decision: read-only (`ro`).**
**Why:** FreeSWITCH only ever *reads* media. Read-only turns a whole class of
mistake into an immediate, obvious error — if the entrypoint or FreeSWITCH ever
tried to write there, it would fail loudly instead of corrupting the
application's data or leaving stray files the application would later try to
manage.
**Consequence:** the application must own writing. If you ever need FreeSWITCH
to write into that tree (call recordings, for example), this mount must change
and a separate write path must be designed. Do not just drop the `ro`.

**Decision: `/media/obd`, not `/usr/share/freeswitch/sounds`.**
**Why:** FreeSWITCH has its own built-in sound tree at
`/usr/share/freeswitch/sounds` (the `moh` hold-music package, and the
`local_stream://moh` reference both profiles use). Mounting the application's
directory *over* that path would shadow the built-in sounds and break anything
relying on them. Mounting at `/media/obd` instead gives the application a clean,
deterministic subtree and leaves the vendor sounds intact.
**Alternative rejected:** shadowing the sounds directory. It appears simpler,
and it breaks hold music and any stock prompt.
**Consequence:** two separate media roots exist, and the application must be
told which one to use. See §6.

---

## 4. Verifying the media path

```text
Command:  docker exec obd-freeswitch ls -ld /media/obd
Purpose:  confirm the mount is present inside the container
Success:  "drwxrwxrwx 1 root root ... /media/obd"
Failure:  "No such file or directory" -> the bind mount failed

Command:  docker exec obd-freeswitch sh -c "touch /media/obd/.probe"
Purpose:  confirm the mount is read-only, as intended
Success:  "touch: /media/obd/.probe: Read-only file system"
          (a successful touch is a PROBLEM, not a success)
Failure:  touch succeeded -> the mount is not :ro; fix the compose file

Command:  docker exec obd-freeswitch sh -c "ls -R /media/obd | head -40"
Purpose:  see the tenant/asset path shape FreeSWITCH will be given
Success:  audio/<tenant-uuid>/<asset-uuid>/<file>.wav
Failure:  a flat file, or a different shape -> MediaUriResolver will not be
          able to build a path, and playback will fail
```

**The entrypoint already fails the container if `/media/obd` is missing.** That
is deliberate: a missing media mount is otherwise silent until a campaign runs,
surfacing as an opaque `PLAYBACK_ERROR` much later with no obvious cause.

---

## 5. RTP ports

| | Value | Label |
|---|---|---|
Configured start | `30000` | CONFIRMED present in the active `switch.conf.xml` |
Configured end | `30099` | CONFIRMED |
Published to host | `127.0.0.1:30000-30099/udp` | CONFIRMED (100 mappings) |
| Effective allocation | - | **CONFIRMED - LOCAL** (Phase C: observed 30022, 30028, 30036, 30070, 30082) |
FreeSWITCH stock default | `16384`–`32768` | CONFIRMED (from `switch_rtp.c`; the stock config leaves these commented out) |

**Why the stock range is not used.** In the stock `switch.conf.xml` the two
parameters are *commented out*, so FreeSWITCH silently uses 16,384 ports.
Publishing that through Docker's port proxy is slow, and it is a very large
exposure envelope for a development machine. 100 ports is ample for local
testing.

**Why the effective range cannot be read at rest.** FreeSWITCH keeps the range
in a C static and exposes `switch_rtp_get_start_port()`, but no `fs_cli` API
reaches it. Verified attempts and their results:

| Attempt | Result |
|---|---|
`global_getvar switch_rtp_start_port` | `-ERR no reply` |
`global_getvar rtp_start_port` | `-ERR no reply` |
`switch_rtp_get_start_port` as an API | `-ERR Command not found!` |

Ports are only allocated when a channel needs media. So the effective range is
confirmed at the **first live call**, not before. Do not record "RTP range
verified" on the strength of the config file alone.

---

## 6. The application side (not configured in Phase B)

When the application is connected, it must be told that FreeSWITCH's media root
is `/media/obd`. Two ways, **neither requiring a Java code change**:

```text
# 1. an environment variable Spring reads
SPRING_APPLICATION_JSON={"audio":{"storage":{"freeswitch-media-root":"/media/obd"}}}

# 2. a command-line property
--audio.storage.freeswitch-media-root=/media/obd
```

**Known gap.** `application-dev.yaml` has no key for `freeswitch-media-root`,
so today the application would use the Java default
`/usr/share/freeswitch/sounds` — which is **not** where the volume is mounted.
Playback would therefore fail. A one-line YAML placeholder would tidy this up,
but it is a repository change and is deliberately not made in Phase B. Recorded
for the phase that connects the application.

---

## 7. What still has to be proven about media

**Phase C resolved most of this list. Current status:**

| Question | Status | Evidence |
|---|---|---|
| a WAV in `backend/data/audio` is readable at the resolved path | **CONFIRMED - LOCAL** | `PLAYBACK_START` / `PLAYBACK_STOP`; section 11.5 |
| `uuid_broadcast <uuid> <path> aleg` yields START then STOP | **CONFIRMED - LOCAL** | section 11.5 |
| the real RTP port allocation, and whether 30000-30099 suffices | **CONFIRMED - LOCAL** | 30022/30028/30036/30070/30082; section 11.1 |
| what a bad path actually produces | **CONFIRMED - LOCAL, and no ESL event** | log-only; section 11.6 |
| the audio is genuinely audible at a real SIP endpoint | **NOT YET TESTED** | needs a bridged call including the endpoint's leg; section 11.3 |
| that the A-leg is the customer side, so `aleg` playback is not heard by the agent | **NOT YET TESTED** | needs CONNECT_BY_AGENT |

**The `Playback-Error` assumption was wrong, and this is the important
correction.** The project's own tests assumed header text such as `FILE_NOT_FOUND`
and `RESOURCE_ERROR` for a bad path. No such header is ever emitted:

```text
uuid_broadcast <uuid> /media/obd/phase-c-deliberately-absent.wav aleg
  -> +OK Message sent
  -> no PLAYBACK_ERROR, and no PLAYBACK_START either
log: [WARNING] mod_sndfile.c:281 Error Opening File [...] [System error : No such
      file or directory.]
```

A missing file is reported **only in the log**. Any code or test asserting a
`Playback-Error` value is asserting something that does not happen. See
`05-ESL.md` section 9.4 and defect **J2**.

---
## 8. Why container-to-container media needs no published port

```text
  Windows host  ── published 127.0.0.1:30000-30099 ──►  FreeSWITCH
                            (only needed for a host-side softphone)

  FreeSWITCH  ════ Docker bridge, container to container ════►  FreeSWITCH
                172.25.0.2  ────────────────────────────►  172.25.x.y
                (stays inside the obd-telephony network;
                 no host port, no Windows firewall rule, no NAT)
```

This is the single most important networking fact for the later phases: once
the provider FreeSWITCH is added, media between the two switches never touches
the Windows host. Only a **Windows-side softphone** needs the published range.

**Consequence for testing:** a media test that runs entirely inside Docker is
far simpler and far more reliable than one involving a softphone. Do the
container-to-container test first.

---

## 9. A host softphone, when you get there

**NOT YET TESTED.** The known complications, in the order they will bite:

1. **The advertised address.** FreeSWITCH advertises `172.25.0.2` in SDP. A
   softphone on the Windows host cannot route to a `172.x` address by default.
   Either the softphone must be told to use a reachable address, or FreeSWITCH
   needs an externally-advertised RTP address.
2. **The return path.** Even if the softphone sends RTP to the published
   port, Docker's port proxy must forward it to the container.
3. **Windows firewall.** Inbound rules may be required for the published ports.
4. **Codec and payload agreement.** PCMU/PCMA first keeps this simple.

Each of these is a Docker-networking issue, not a FreeSWITCH issue. When one
bites, check it in that order rather than changing SIP configuration.

---

## 10. Quick reference

| Question | Answer | Where verified |
|---|---|---|
Where does the application put audio? | `backend/data/audio/<tenant>/<asset>/<file>` | app config |
Where does FreeSWITCH see it? | `/media/obd/<tenant>/<asset>/<file>` (root overridden) | `docker exec ls /media/obd` |
What is the default FreeSWITCH root? | `/usr/share/freeswitch/sounds` | `AudioStorageProperties` default |
Is the built-in sound tree shadowed? | **No** | mount target is `/media/obd` |
Is the volume writable? | **No**, `:ro` | `touch` probe refused |
What command plays audio? | `uuid_broadcast <uuid> <path> aleg` | `EslClient.playFile` |
What proves it finished? | `PLAYBACK_STOP` — not the command reply | `EslEventService` |
What proves it failed? | `PLAYBACK_ERROR` | `EslEventService` |
What RTP ports are configured? | 30000-30099 | `switch.conf.xml` |
Are those proven in use? | **No — NOT YET TESTED** | see §5 |

---

## 11. PHASE C: observed RTP behaviour

Section 7 listed "the real RTP port allocation" as the main open question. It is
now answered, and the answer is a method as much as a number.

### 11.1 The effective range is CONFIRMED - LOCAL

The configured range could not be read at rest, because FreeSWITCH holds it in a
C static with no `fs_cli` API. It can be read **from a live channel**, and the
value is available in the `CHANNEL_CREATE` event itself - before the call is even
answered:

```text
CHANNEL_CREATE  Unique-ID=8649c919  variable_local_media_port=30028
CHANNEL_ANSWER  Unique-ID=3b7c6d82  variable_local_media_port=30070
```

and on a bridged pair, read back per leg:

```text
uuid_getvar <leg-A> local_media_port   -> 30022
uuid_getvar <leg-A> remote_media_port  -> 30036
uuid_getvar <leg-B> local_media_port   -> 30036
uuid_getvar <leg-B> remote_media_port  -> 30022
```

Ports observed across several calls: **30022, 30028, 30036, 30070, 30082**. All
inside `30000-30099`. The configured range is in effect, and it is sufficient
for the observed concurrency.

**Why this closes entry 11 of the troubleshooting file.** The Phase B technique
- prove a value by changing it and seeing FreeSWITCH report it back - is
unnecessary here. The port is simply reported in the event, so the question
"is the range in effect?" is answered directly rather than by fingerprinting.

Codec negotiation observed on the same calls: `read_codec = PCMU`,
`write_codec = PCMU`, with `Channel-Read-Codec-Rate = 8000` and
`Channel-Read-Codec-Bit-Rate = 64000`.

### 11.2 Proving audio actually flows: what is and is not available

**A port was allocated. That is not the same as audio arriving**, and Phase C
found that the obvious ways to check do not work.

| Attempt | Result | What it means |
|---|---|---|
| `uuid_getvar <uuid> rtp_audio_in_packet_count` | `_undef_` | not a channel variable on this build |
| `uuid_getvar <uuid> rtp_audio_out_packet_count` | `_undef_` | same |
| `uuid_debug_media <uuid>` | `-USAGE: <uuid> <read\|write\|both\|vread\|vwrite\|vboth\|all> <on\|off>` | **not a statistics command** - it toggles debug logging |

That last one is the trap. `uuid_debug_media` looks like the obvious way to read
media state and returns a usage string, which reads like a failure. Its real
signature toggles per-direction media debug:

```bash
fs_cli -x "uuid_debug_media <uuid> both on"
```

**What does work, best first:**

1. **Read the peer's own counters.** The far end must be reachable. This is why
   the test endpoints publish ESL on `127.0.0.1:8031` and `:8032` - a media
   claim is only two-way if the far end confirms receipt.
2. **Enable media debug on both ends** and read the resulting log lines.
3. **Capture packets** on the Docker network and count UDP flows to the
   negotiated ports.

**What is not sufficient**, and has been mistaken for proof during this phase:

* reading `local_media_port` - proves a port was allocated
* seeing `CS_CONSUME_MEDIA` in `show channels` - proves media state, not flow
* observing a `CHANNEL_BRIDGE` - proves the legs were connected
* counting packets the switch **sent** - proves the socket was written to

The distinction is not pedantry. A call that answers, bridges, allocates a port
and never carries audio is a completely ordinary failure - it is what a missing
`dial-string` parameter produces (troubleshooting entry 16).

### 11.3 Two-way audio is still NOT YET TESTED

The bridge observed during Phase C was between two platform-side legs, both
reporting `remote_media_ip = 172.25.0.2`. The endpoint's leg was not proven to be
part of it, so audio crossing the network is unproven.

Status of the three distinct claims, kept separate on purpose:

| Claim | Status |
|---|---|
| SIP signalling works (INVITE, 200 OK, ACK, BYE) | **CONFIRMED - LOCAL** |
| RTP is allocated inside the configured range | **CONFIRMED - LOCAL** |
| Audio flows in both directions | **NOT YET TESTED** |

**The next step** is to replace the platform's stock dialplan with an explicit
one that bridges an inbound call to a registered extension directly to its
contact, then read `rtp_audio_in_packet_count` on the **endpoint's** leg. A
non-zero received count on the far end is the only acceptable proof.

### 11.4 A host softphone cannot carry media on this machine

Section 9 anticipated complications with a host softphone. The situation is
worse than "complications", and it is now measured:

```text
Test-NetConnection 172.25.0.2 -Port 5060 -> TcpTestSucceeded=False
ping 172.25.0.2                          -> no reply
route print                             -> no 172.25.0.0/16 entry
```

The Windows host has **no route** to the Docker bridge network. FreeSWITCH
advertises its container address in `Contact` and in SDP, so a host softphone:

| Can it... | | Why |
|---|---|---|
| `REGISTER` with the host | yes | one-way; FreeSWITCH only answers |
| place a call from the host | yes | published port, and **TCP only** |
| be **called back** by FreeSWITCH | **no** | it would send to a contact of `127.0.0.1`, which inside the container is the container's own loopback |
| **receive RTP** | **no** | same reason |
| use **UDP** from the host | **no** | Docker's UDP proxy does not return the response to the original client port |

So a host softphone is useful for registration and for placing calls, and
**cannot participate in media at all**. This is why the Phase C endpoints run as
containers on `obd-telephony` (ADR-004).

The SIP/TCP control, for reference:

```text
OPTIONS via published 127.0.0.1:5060 -> SIP/2.0 200 OK
OPTIONS via published 127.0.0.1:5060/udp -> no response (proxy loses the reply)
```

### 11.5 The media volume works, and what it must contain

The bind mount is visible and usable read-only:

```text
docker exec obd-freeswitch ls -la /media/obd/
-rwxrwxrwx 1 root root  48044 phase-c-test-tone.wav
```

A playback of a real file, using the Java client's exact command form:

```text
uuid_broadcast <uuid> /media/obd/phase-c-test-tone.wav aleg
  -> +OK Message sent
  -> PLAYBACK_START
  -> PLAYBACK_STOP
log: EXECUTE [depth=1] ... playback(/media/obd/phase-c-test-tone.wav)
```

**The file must be a natively-decodable format.** `mod_av` cannot load in this
image:

```text
Error Loading module /usr/lib/freeswitch/mod/mod_av.so
Error loading shared library libavformat.so.62: No such file or directory
```

So **16-bit PCM WAV works** (handled by FreeSWITCH's core file handling, no
module needed) and **compressed formats do not**. This matters when the media
pipeline is designed: if the platform intends to store MP3 prompts, that is a
blocker on this image, not a detail.

The Phase C test asset is 8 kHz mono 16-bit PCM - PCMU's sample rate, so
transcoding is trivial - and is generated by
`tools/freeswitch-harness/make_test_tone.py`. It is a local file in a
git-ignored directory, not a repository change.

### 11.6 A missing file is silent on ESL

```text
uuid_broadcast <uuid> /media/obd/phase-c-deliberately-absent.wav aleg
  -> +OK Message sent          <- the command SUCCEEDS
  -> no PLAYBACK_ERROR
  -> no PLAYBACK_START        <- playback never began
log: [WARNING] mod_sndfile.c:281 Error Opening File
      [/media/obd/phase-c-deliberately-absent.wav]
      [System error : No such file or directory.]
```

The only signal is the log line. Any media monitoring must therefore treat
**absence of `PLAYBACK_START`** as the failure indicator, not the presence of an
error header. See `05-ESL.md` section 9.4 and defect **J2**.

---

---

## 13. PHASE E: measuring RTP when the switch will not count packets for you

Phase C established that media counters are unavailable on this build. Phase E
re-confirmed it on a live answered channel and then found what *is* measurable.

```text
rtp_audio_in_packet_count   = _undef_
rtp_audio_out_packet_count  = _undef_
rtp_audio_in_octet_count    = _undef_
rtp_audio_out_octet_count   = _undef_
api uuid_debug_media <uuid> -> -ERR api Command not found!
```

and no capture tool exists in either image (`tcpdump`, `tshark`, `dumpcap`), with
no image pullable because the Docker daemon's DNS is broken. Per-packet RTP is
also not logged at debug - 101 console lines were produced during a call and none
were RTP.

### What is available

**The kernel's own UDP counters, read inside each container from
`/proc/net/snmp`.** Real packet counts, not inferences - but per-container
rather than per-stream, so the experiment must make media dominate everything
else:

* a **30-second** file, not the 3-second one. One second of PCMU is ~400 packets
  and sits in the noise; 30 seconds is ~12000.
* counters sampled immediately either side of the playback window
* **both** ends sampled, because a one-way result must not be called two-way
* attribute a direction by what the sender was *not* doing - the platform is an
  outbound channel with no bridge, so during the window it is only sending, and
  anything it receives necessarily came from the peer

```text
[endpoint -> platform]  platform InDatagrams +64   during the endpoint's own tone
                        (the test sent nothing to cause this)
```

Sixty-plus real packets crossed the Docker bridge unprompted. That direction is
**CONFIRMED — LOCAL**.

The reverse direction was not established, because the platform's own
`uuid_broadcast` never started playback (see `LIVE-FREESWITCH-PHASE-E.md` §9).
So:

> **TWO-WAY AUDIO REMAINS NOT PROVEN.**

This is exactly the discipline Phase C called for. Ports allocated, an SDP
offered and a `CHANNEL_ANSWER` all occurred; none of them is audio arriving, and
the one measurement available was not sufficient to claim both directions.