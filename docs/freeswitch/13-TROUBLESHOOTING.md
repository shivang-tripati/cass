# 13 — Troubleshooting Knowledge Base

A permanent record of real problems, in the order they were actually hit.

**Do not delete an entry because it is fixed.** The next engineer will hit the
same problem, in a slightly different disguise, and this file is the fastest
way to recognise it. Every entry here cost real time to diagnose.

All entries are from **Phase B** (building the container). Entries from later
phases are appended.

**Index**

| # | Problem | Symptom keyword |
|---|---|---|
1 | [ESL credential leaked into rendered config](#1-esl-credential-leaked-into-the-rendered-config) | secret in a config file |
2 | [Gateway invisible: `register` defaults to true](#2-gateway-invisible-register-defaults-to-true) | `0 gateways` |
3 | [Sofia profile silently ignored](#3-sofia-profile-silently-ignored) | `0 profiles` |
4 | [Preprocessor directives execute inside XML comments](#4-preprocessor-directives-execute-inside-xml-comments) | config reappears after being removed |
5 | [Nested XML comments: `unclosed <!--` ](#5-nested-xml-comments-unclosed-----) | container crash loop |
6 | [Stock IPv6 profiles hold the ports we need](#6-stock-ipv6-profiles-hold-the-ports-we-need) | ports already bound |
7 | [Stock WebSocket bindings](#7-stock-websocket-bindings-5066--7443) | unexpected listeners |
8 | [A config file that is right but does nothing](#8-a-config-file-that-is-right-but-does-nothing) | change had no effect |
9 | [The gateway directory that is not read](#9-the-gateway-directory-that-is-not-read) | gateway file present, ignored |
10 | [The entrypoint that never ran](#10-the-entrypoint-that-never-ran) | password not applied |
11 | [RTP range cannot be proven at rest](#11-rtp-range-cannot-be-proven-at-rest) | "no way to check the ports" |
12 | [Docker daemon cannot resolve any registry](#12-docker-daemon-cannot-resolve-any-registry) | `docker pull` fails |
| 13 | [Published ESL port connects and closes immediately](#13-published-esl-port-connects-and-immediately-closes-phase-c) | `recv 0 bytes` |
| 14 | [A settings file that is silently ignored](#14-a-settings-file-that-is-silently-ignored-wrong-root-element-phase-c) | healthy, but a module is inert |
| 15 | [Correct password, rejected](#15-correct-password-rejected-extension-numbers-collide-with-the-stock-directory-phase-c) | `403` on a correct password |
| 16 | [A call that answers but has no media](#16-a-call-that-answers-but-has-no-media-missing-dial-string-phase-c) | connected, but silent |
| 17 | [Media counters are not available](#17-media-counters-are-not-available-how-to-actually-prove-audio-flows-phase-c) | `_undef_`, `-USAGE` |
| 18 | [Two containers sharing one log directory](#18-two-freeswitch-containers-sharing-one-log-directory-phase-c) | `Cannot Open log directory or XML Root!` |

**Phase at a glance**

| Entries | Phase |
|---|---|
| 1-12 | Phase B - building and validating the container |
| 13-18 | Phase C - live calls against real SIP endpoints |
| 19 | [ESL rejects a command it does not recognise](#19-eslint-rejects-a-command-it-does-not-recognise-phase-d) | `-ERR command not found` |
| 19-22 | Phase D - Java/ESL contract hardening against a live switch |
| 20 | [An `api` reply has an empty Reply-Text](#20-an-api-reply-has-an-empty-reply-text-phase-d) | `Unexpected reply` on success |
| 23 | Phase D - concurrent-session working-tree integrity |
| 24-27 | Phase E - local gateway, routing, and a false-positive answer |
| 21 | [The correlation header that does not exist](#21-the-correlation-header-that-does-not-exist-phase-d) | every event dropped |
| 28-29 | Phase E.1 - the uuid_broadcast contract, and a dialplan application that does not exist |
| 22 | [A test double that accepts everything](#22-a-test-double-that-accepts-everything-phase-d) | green suite, broken feature |
| 30 | Phase E.2 - seeded application call; blocked on a concurrent test file and an empty sip_gateways table |

| 31 | Phase E.3 - local gateway seeded and routable; application call still pending |
| 23 | [Work vanishes from the working tree mid-task](#23-work-vanishes-from-the-working-tree-mid-task-concurrent-session) | file deleted, `*.parked` copy appears |
---
| 24 | [The switch answers your call but nobody else sees it](#24-the-switch-answers-your-call-but-nobody-else-sees-it-phase-e) | `CHANNEL_ANSWER`, peer has no call |
| 25 | [`NO_ROUTE_DESTINATION` when the network is provably fine](#25-no_route_destination-when-the-network-is-provably-fine-phase-e) | DNS, ping and ACL all pass |
| 26 | [`acl` returns false for every address](#26-acl-returns-false-for-every-address-phase-e) | a correct list denies everything |
| 27 | [A gateway's `sofia-profile` is ignored](#27-a-gateways-sofia-profile-is-ignored-phase-e) | origination uses the declaring profile |

| 28 | [An unavailable dialplan application aborts the channel](#28-an-unavailable-dialplan-application-aborts-the-channel-phase-e1) | `Invalid Application`, `DESTINATION_OUT_OF_ORDER` |
| 29 | [`invalid uuid` / `No such channel` from a live channel](#29-invalid-uuid-and-no-such-channel-from-a-live-channel-phase-e1) | `uuid_dump` works, the others do not |
## 1. ESL credential leaked into the rendered config
| 30 | [One uncompilable test file blocks the whole suite](#30-one-uncompilable-test-file-blocks-the-whole-suite-phase-e2) | `Tests run: 0`, `cannot find symbol` in `testCompile` |

| 31 | [A check constraint accepts a value your Java enum cannot read](#31-a-check-constraint-accepts-a-value-your-java-enum-cannot-read-phase-e3) | insert rejected, or reads fail later |
### Symptom
| 32 | [A concurrent build corrupts your test run](#32-a-concurrent-build-corrupts-your-test-run-phase-e5) | mass `NoClassDefFoundError` / context errors |

The rendered `event_socket.conf.xml` contained the live ESL password **twice**:
once in the password parameter (correct) and once inside an XML comment
(incorrect). The first verification run also printed the secret into terminal
output, and therefore into a transcript.

### Impact

A real credential committed nowhere, but present in a live configuration file
and in a session log. Any process able to read the container's config, or any
person reading the terminal history or the transcript, had the switch's
control credential.

### Detection

A security check that asked "is the stock credential still active?" matched on
a *comment* that merely mentioned it, and produced a false positive. Chasing
that false positive is what exposed the substitution problem.

### Root Cause

The entrypoint substitutes with `sed 's|@@TOKEN@@|<value>|g'` — the `g` flag
replaces **every** occurrence. The template's explanatory comment contained the
literal token `@@FREESWITCH_PASSWORD@@` when describing the mechanism, so `sed`
replaced that one too.

Second, independent cause: the verification printed the secret because the
check greped for "password" and printed every matching line, rather than
printing only a property of the value.

### Diagnosis

```text
docker exec obd-freeswitch grep -c -i cluecon /etc/freeswitch/autoload_configs/event_socket.conf.xml
# -> non-zero, from the COMMENT, not the parameter
```

The tell is that the *stock* credential appears in a file we believed had
overridden it. That contradiction is what pointed at the comment.

### Fix

1. The template now carries an explicit editing rule: the token must appear
   **exactly once**, in the password parameter, and the file must not name any
   known credential (those checks live in the entrypoint, where they belong).
2. The credential was **rotated** — the old one was considered compromised by
   having been printed.
3. The entrypoint now fails startup if any token survives substitution, and
   refuses the stock value, the platform's dev value, the `.env.example`
   placeholder, and anything under 16 characters.
4. Verification compares *counts* and *properties*, never printing values.

### Verification

```text
occurrences of the live secret in the rendered file : 1   (the password param)
occurrences of the stock default credential        : 0
occurrences of the platform dev default credential : 0
```

### Prevention

* When a secret is templated into a file, **count its occurrences**. One is
  correct; more is a leak.
* Never print a secret to verify it. Verify its length, its count, and
  whether a known-bad value is absent.
* If a secret is ever printed, **rotate it**. Assume the transcript is
  permanent.

### Production relevance

**High.** In production an ESL credential is full control of the switch. A
secret in a comment survives every config reload and is copied by every log
shipper, backup and support bundle that touches the file. Rotation is the only
reliable remedy.

---

## 2. Gateway invisible: `register` defaults to true

### Symptom

`fs_cli -x "sofia status gateway"` reported `0 gateways`. Asking for the
gateway by name reported `Invalid Gateway!`. **The log contained nothing
whatsoever about gateways.**

### Impact

The entire outbound architecture was unavailable: every
`sofia/gateway/fs-gateway/...` originate would fail. Found during L2
validation of a check that must pass.

### Detection

The absence of any log line about gateways, when a gateway file was
demonstrably present and demonstrably present in the compiled configuration.
Silence where a message was expected is itself the signal.

### Root Cause

From `mod_sofia/sofia_gateway.c`:

```c
char *register_str = "true", ...
```

**`register` defaults to TRUE.** A gateway without an explicit
`register="false"` is treated as a dynamic trunk and FreeSWITCH immediately
attempts to REGISTER. There is no provider in this environment, so
registration fails, and mod_sofia discards the gateway. It does not leave a
failed gateway in a failed state — it removes it, so the symptom is absence
rather than an error.

An earlier version of the gateway file *documented* that registration was
intentionally disabled, but only in a comment; the parameter itself was never
set.

### Diagnosis

Four hypotheses were tested and eliminated, in order, which is the part worth
reproducing:

1. *"The proxy hostname `freeswitch-provider` does not resolve."* — replaced
   the proxy with `127.0.0.1:5080`; still 0 gateways. **Eliminated.**
2. *"The preprocessor include did not inject the gateway."* — inlined the
   gateway directly; still 0 gateways. **Eliminated.**
3. *"mod_sofia never reaches the gateway."* — renamed the gateway to
   `fs gateway PROBE` (invalid under mod_sofia's own
   `^[\w\.\-\_]+$` rule) and raised the log level. The log then showed:
   ```text
   [ERR] sofia.c:3773 Ignoring invalid name 'fs gateway PROBE'
   ```
   **This proved `parse_gateways` was being called all along**, which eliminated
   every configuration-location theory and pointed at the gateway being created
   and then dropped. That is the technique that broke the deadlock: **make
   FreeSWITCH complain deliberately, and read what it says.**
4. Read `parse_gateways` in the source and found the `register_str = "true"`
   default.

### Fix

```xml
<param name="register" value="false"/>
```

### Verification

```text
$ fs_cli -x "sofia status gateway"
     external::fs-gateway  sip:FreeSWITCH@freeswitch-provider:5080  NOREG  0.00  0/0  0/0
  1 gateway: Inbound(Failed/Total): 0/0,Outbound(Failed/Total):0/0

$ fs_cli -x "sofia status gateway fs-gateway"
  Name    fs-gateway
  Profile external
  Proxy   sip:freeswitch-provider:5080
  State   NOREG
  Status  UP
  Password no
```

`NOREG` with `Status UP` and `Password no` is the correct end state for a
static origination-only trunk.

### Prevention

* **Set `register` explicitly on every gateway.** Never rely on the default.
* When a configured object is *absent* rather than *broken*, suspect a
  deliberate default that causes it to be discarded.
* Learn the "make it complain on purpose" technique. It is faster than any
  amount of config comparison.

### Production relevance

**High.** With real carrier credentials, a registration failure shows up as a
gateway in `DOWN` or `TRYING`, and calls through it fail. The silent-removal
behaviour is specific to an unregistered gateway, but the class of problem —
an object whose absence is the only symptom — recurs constantly.

---

## 3. Sofia profile silently ignored

### Symptom

Custom `internal` and `external` profile files were mounted, were valid XML,
appeared correctly in the compiled configuration — and `sofia status` reported
**`0 profiles`**. Meanwhile the *stock* `internal-ipv6` and `external-ipv6`
profiles were running and had taken `::1:5060` and `::1:5080`.

### Impact

No usable SIP profile existed. Combined with entry 2, the environment could
neither send nor receive calls.

### Detection

`sofia status` showing `0 profiles` while `ls` showed the files present.

### Root Cause

Two separate mistakes:

1. **Wrong root element.** The files used
   `<configuration name="sofia.conf">` as their root. mod_sofia's profile
   files must use `<profile name="...">`. The stock files — including
   `internal-ipv6.xml` — use `<profile name="internal-ipv6">`. A file with the
   wrong root is preprocessed, included, and then invisible to mod_sofia.
2. **A glob include.** The stock `sofia.conf.xml` includes
   `../sip_profiles/*.xml`, which also pulled in the IPv6 profiles.

### Diagnosis

Read the stock profile file in the image and compared its first line:

```text
$ docker run --rm --entrypoint sh <image> -c "head -3 \
    /etc/freeswitch/sip_profiles/internal-ipv6.xml"
<profile name="internal-ipv6">
```

That single line answered the question. The lesson is that the **stock files
inside the image are the authoritative reference**, not your memory of what
FreeSWITCH config looks like.

### Fix

* Change both profile files' root element to `<profile name="internal">` /
  `<profile name="external">`.
* Replace the glob in `sofia.conf.xml` with two explicit includes.

### Verification

```text
$ fs_cli -x "sofia status"
         external  profile  sip:mod_sofia@172.25.0.2:5080  RUNNING (0)
         internal  profile  sip:mod_sofia@172.25.0.2:5060  RUNNING (0)
  2 profiles 0 aliases
```

### Prevention

* **Read the stock files in the image before writing your own.** They are
  right there and they are correct.
* A correct-looking file that is silently ignored is almost always a
  structural problem (root element, namespace, nesting), not a value problem.
* Prefer explicit includes over globs in a controlled environment: a glob
  quietly brings in whatever else is in the directory.

### Production relevance

**High.** In production the profile directory will contain files you did not
write, and a glob will load all of them. Explicit includes make the running
configuration auditable.

---

## 4. Preprocessor directives execute inside XML comments

### Symptom

After replacing the stock `sofia.conf.xml` glob with explicit includes, the
container crash-looped with:

```text
Cannot Initialize [[error near line 3139]: unclosed <!--]
```

Even though every config file was valid XML and every `<!--`/`-->` pair was
balanced.

### Impact

The container could not start at all. Total outage of the environment.

### Detection

The XML error message, plus a successful standalone XML parse of every file —
a contradiction that pointed at the preprocessor rather than the parser.

### Root Cause

FreeSWITCH's `freeswitch.xml` documents its own preprocessor, and this rule is
in it:

> All comments starting with `#command` will be preprocessed and never sent to
> the xml parser.

The preprocessor operates on **raw text**, before XML parsing. So a directive
written inside an XML comment is still a directive. The comment explaining
*why* the glob was replaced contained a verbatim copy of the glob's include
directive — which the preprocessor then executed, re-injecting the very glob
the file existed to remove, and desynchronising the comment structure.

### Diagnosis

The string `X-PRE-PROCESS` appeared inside a comment. A scan of every config
file for that token *while inside a comment* identified the file and the line
in one step.

### Fix

Reword the comment so it never contains the literal token. A rule was added to
this repository: **no preprocessor directive may appear in a FreeSWITCH config
file outside an actual directive position**, including in comments.

### Verification

A scan across all config files now reports zero occurrences of the token inside
a comment; the container starts; the profile list is exactly the two intended
profiles.

### Prevention

* **Do not paste real directives into comments**, even as documentation. Use
  prose.
* When a config file behaves differently from its own text, suspect the
  preprocessor before the XML parser.
* Add a lint step for config files: balanced comments, no nesting, no
  directives in comments. This is cheap and catches a whole class of errors.

### Production relevance

**Medium-high.** Any templated or generated FreeSWITCH config is at risk. A
generated comment that quotes an example directive will activate it.

---

## 5. Nested XML comments: `unclosed <!--`

### Symptom

Container crash-looping, exit 255, on every start:

```text
ERROR: Failed to set SCHED_FIFO scheduler (Function not implemented)
Cannot Initialize [[error near line 3659]: unclosed <!--]
```

### Impact

Total outage. This was the first fatal error of the phase.

### Detection

The explicit "unclosed <!--" in FreeSWITCH's own error message.

### Root Cause

`switch.conf.xml` had a comment explaining that the stock file's RTP settings
are commented out — and it quoted them **including their comment markers**:

```xml
<!--
  The stock lines read:
      <!-- RTP port range -->
      <!-- <param name="rtp-start-port" value="16384"/> -->
-->
```

XML comments **cannot nest**. The inner `<!--` is literal text, the first `-->`
closes the outer comment, and the trailing `-->` opens a comment that is never
closed.

**The trap that made this expensive:** a naive count of `<!--` versus `-->`
reported 7 and 7 — "balanced" — because a nested open is still counted as an
open. Only a *nesting-aware* scan detects it.

### Diagnosis

Wrote a comment-nesting scanner that tracks open/close state and reports a
nested `<!--` inside an open comment, plus any comment left open at end of
file. It immediately flagged `switch.conf.xml`.

### Fix

Rewrote the comment to describe the stock lines in prose, without their comment
markers. The same scanner is now part of the pre-start checks.

### Verification

The nesting-aware scan reports all six config files clean, and the container
starts.

### Prevention

* **Count comment openers and closers only with a nesting-aware scan.** A plain
  count cannot detect this class of bug.
* Never quote a markup delimiter inside prose in an XML file.
* Consider a pre-commit lint for XML comments. This is a three-line check that
  would have caught it.

### Production relevance

**Medium.** The same class of error appears in hand-edited FreeSWITCH
configuration, and it presents as a container that will not start with an error
message that does not obviously point at your file. Read the compiled
`freeswitch.xml.fsxml` to find the offending line.

---

## 6. Stock IPv6 profiles hold the ports we need

### Symptom

Custom IPv4 profiles would not start. The listeners that existed were:

```text
tcp  ::1:5060   LISTEN   1/freeswitch
tcp  ::1:5080   LISTEN   1/freeswitch
```

`netstat` inside the container, when nothing IPv4 should have been bound.

### Impact

No IPv4 SIP endpoint was possible. The environment could not accept a softphone
or originate a call.

### Detection

Comparing the bound ports against the expected configuration, and then
noticing that the IPv4 profiles — the ones written on purpose — were absent
while two profiles nobody asked for were running.

### Root Cause

The stock `sofia.conf.xml` includes `../sip_profiles/*.xml`, a glob. The image
ships `internal-ipv6.xml` and `external-ipv6.xml` alongside the IPv4 profiles.
Those IPv6 profiles bind `::1:5060` and `::1:5080`.

### Diagnosis

```text
$ docker exec <container> ls /etc/freeswitch/sip_profiles/
external/  external-ipv6/  external-ipv6.xml  internal-ipv6.xml  internal.xml
```

`internal-ipv6.xml` and `external-ipv6.xml` are stock, and the glob picks them
up.

### Fix

Replace the glob with two explicit includes in `sofia.conf.xml`.

### Verification

`sofia status` shows exactly `2 profiles`, bound to `172.25.0.2:5060` and
`:5080`; `netstat` no longer shows `::1:5060` or `::1:5080`.

### Prevention

* **Always look at what a glob actually matches** before trusting it.
* An environment should load the profiles it declares, not every profile that
  happens to be present.

### Production relevance

**Medium.** Shipping a config directory that contains profiles for
environments you are not running is a common cause of port conflicts in
production, especially when a second node uses a different port map.

---

## 7. Stock WebSocket bindings (5066 / 7443)

### Symptom

While auditing listeners, the stock `internal` profile was found to bind
`ws-binding :5066` and `wss-binding :7443`.

### Impact

Two extra listeners that the project does not use and does not need. Neither
was published to the host, so the exposure was internal to the container — but
they were still unplanned listeners on a security-sensitive service.

### Detection

Reading the full list of active parameters from the stock profile file rather
than assuming what it contained.

### Root Cause

FreeSWITCH's stock internal profile enables WebSocket signalling by default.

### Fix

The custom internal profile omits `ws-binding` and `wss-binding` entirely, so
those listeners are never created. TLS parameters are omitted for the same
reason, so 5061 and 5081 are not bound either.

### Verification

```text
$ docker exec <container> netstat -tulnp | grep -E ':(5061|5081|5066|7443|21|2222) '
(no output)
```

### Prevention

* After changing a stock config, **always diff the resulting listener set**
  against what you intended. `netstat` inside the container is the authority.
* Unconfigured is not the same as harmless.

### Production relevance

**Medium.** WebSocket and TLS listeners on a SIP port range are a meaningful
attack surface and a certificate-management burden. Enable them deliberately or
not at all.

---

## 8. A config file that is right but does nothing

### Symptom

A configuration change appeared to be completely ignored, repeatedly, across
several restarts.

### Impact

Wasted time; nearly led to a wrong "fix".

### Detection

The compiled configuration file — `/var/log/freeswitch/freeswitch.xml.fsxml` —
showed the change **was** present, while the running behaviour was unchanged.

### Root Cause

Not one cause but a class of causes, and the lesson is that "the file is
correct" and "a module read the file" are different claims. The compiled
document is a flat concatenation of many `<configuration name="...">` roots
from many files, and a module looks up **its own file by name**. A file can be
included, present in the compiled document, and still not be the node a given
module reads.

### Diagnosis

```text
$ docker exec <container> grep -c "OBD Phase B" /var/log/freeswitch/freeswitch.xml.fsxml
```

Then, to prove a module actually *consumed* the file, set one value to
something unmistakable that FreeSWITCH echoes back:

```text
switch.conf.xml:  max-sessions 1000 -> 42
$ fs_cli -x "status"
  42 session(s) max          <-- proof
```

Reverted to 1000 immediately afterwards.

### Fix

For each class of cause, a specific fix: nested comments (entry 5), preprocessor
in comments (entry 4), wrong root element (entry 3), wrong section (entry 9).

### Verification

The three-stage technique in [03-CONFIGURATION.md §6](03-CONFIGURATION.md) is
now the standard.

### Prevention

* Use the three-stage verification: file in container → compiled document →
  a value FreeSWITCH reports back.
* When a change has no effect, prove the *mechanism* before changing the
  *content*.

### Production relevance

**High.** Config that silently fails to apply is the most dangerous class of
fault, because the system looks configured and behaves otherwise.

---

## 9. The gateway directory that is not read

### Symptom

`fs-gateway.xml` placed at the conventional
`sip_profiles/external/gateway/fs-gateway.xml`, with the profile directory
bind-mounted into place — and no gateway. The file was valid, present, and in
the compiled configuration.

### Impact

Blocked the L2.6 acceptance check. Was the main reason entries 2 and 3 were
hard to diagnose: a wrong theory about the location was pursued first.

### Detection

Reading the FreeSWITCH source rather than continuing to experiment.

### Root Cause

In FreeSWITCH 1.11.1, gateways resolve **only** through the configuration
tree:

```text
sofia.conf → profiles → profile[@name] → gateways → gateway
```

(mod_sofia `sofia.c`: `switch_xml_child(xprofile, "gateways")`, then
`switch_xml_child(gateways_tag, "gateway")`.)

There is **no per-profile gateway-directory scan** in `mod_sofia`, and
`switch_xml.c` contains no `gateway` or `sip_profiles` reference at all. The
widely-documented `sip_profiles/<profile>/gateway/*.xml` convention is not read
by this version.

The most instructive detail: an intermediate attempt placed `<gateways>`
directly in `sofia.conf.xml`, which compiled cleanly, appeared in the compiled
document, and was **also silently ignored** — because it must be a child of the
*profile* node.

### Diagnosis

Searched the downloaded FreeSWITCH sources for the directory mechanism and
found none:

```text
$ grep -n "dir_read_xml" sofia.c          -> only switch_xml_open_cfg
$ grep -n "gateway|sip_profiles|sofia_dir" switch_xml.c   -> no matches
```

### Fix

Include the gateway from the `external` profile's `<gateways>` block:

```xml
<gateways>
  <X-PRE-PROCESS cmd="include" data="../gateway/fs-gateway.xml"/>
</gateways>
```

### Verification

`sofia status gateway` reports `external::fs-gateway ... NOREG`, and
`sofia status gateway fs-gateway` shows `Profile external`.

### Prevention

* **When a configuration convention is not working, verify it against the
  source of the exact version you are running.** The documented layout is not
  a guarantee.
* Prefer, where possible, one declarative path for a configuration item rather
  than a directory whose contents you do not control.
* Structure errors are silent. Test structure explicitly.

### Production relevance

**High.** A FreeSWITCH upgrade that changes how a configuration file is
discovered would silently drop trunks. Version-pinning this knowledge and
re-testing it after every upgrade is worthwhile.

---

## 10. The entrypoint that never ran

### Symptom

The rendered `event_socket.conf.xml` was the **stock** file — `listen-ip ::`
and the well-known default credential — even though the template and the
entrypoint script were both mounted and present in the container.

### Impact

The ESL hardening was silently not in effect. The container was healthy and
reported `UP`, so nothing looked wrong; only reading the file revealed it.

### Detection

Comparing the file's `description` attribute against what the template
contained. The stock file says `description="Socket Client"`; the template
says something different. That mismatch identified the file as stock.

### Root Cause

The Compose file mounted the entrypoint script and the template, but **no
`entrypoint:` key was set**. Docker therefore ran the image's own
`CMD ["/usr/bin/freeswitch"]`, bypassing the entrypoint entirely. The script
was present, readable, and never executed.

### Diagnosis

```text
$ docker exec <container> cat /opt/obd/entrypoint.sh    # exists
$ docker logs <container> | grep "OBD Phase B"           # nothing - never ran
$ docker inspect <container> --format '{{json .Config.Entrypoint}}'
  null                                            <-- the whole story
```

### Fix

```yaml
entrypoint: ["/bin/sh", "/opt/obd/entrypoint.sh"]
```

Invoked via `/bin/sh` deliberately, so the script needs neither the executable
bit nor a shebang, and CRLF line endings from a Windows checkout cannot break
it.

### Verification

The log now contains the entrypoint's banner line, and the rendered config has
the correct `listen-ip`, `apply-inbound-acl` and `stop-on-bind-error`.

### Prevention

* **A mounted script is not an executed script.** Always confirm the entrypoint
  is wired, and confirm the script's own output appears in the log.
* Add a startup assertion that fails loudly if a security control is not in
  effect. The entrypoint does this for the credential and the media mount.

### Production relevance

**High.** This is the general shape of "I changed a security setting but it is
not in effect": the change is invisible, and the system reports itself healthy.
Assertions beat intentions.

---

## 11. RTP range cannot be proven at rest

### Symptom

No way to confirm from `fs_cli` that the configured RTP range
(`30000-30099`) is the range FreeSWITCH will actually use.

### Impact

The L2.8 acceptance check could not be given a clean PASS.

### Detection

Deliberately hunting for a runtime API rather than assuming none existed.

### Root Cause

FreeSWITCH keeps the range in a C static and exposes
`switch_rtp_get_start_port()`, but no ESL or `fs_cli` API reaches it:

| Attempt | Result |
|---|---|
`global_getvar switch_rtp_start_port` | `-ERR no reply` |
`global_getvar rtp_start_port` | `-ERR no reply` |
`switch_rtp_get_start_port` as an API | `-ERR Command not found!` |
grep of the startup log for the range | not logged |

Ports are only allocated when a channel needs media.

### Fix

No fix — this is a property of FreeSWITCH. The check was instead split honestly:

* **PASS** for the configuration being in the file FreeSWITCH actually reads
  (proven by the `max-sessions` fingerprint technique).
* **NOT YET TESTED** for the effective allocation, to be confirmed at the first
  live call.

### Verification

`max-sessions` temporarily set to 42; `fs_cli -x status` reported
`42 session(s) max`; reverted to 1000.

### Prevention

* Distinguish **"configured"** from **"observed"** in every acceptance check.
  Writing "RTP range verified" on the strength of a config file is wrong.
* Use a value that FreeSWITCH echoes back when you need to prove a file is
  live.

### Production relevance

**Low.** In production the range is confirmed by the first call, and then by
monitoring for port-exhaustion symptoms. The discipline of not claiming
unobserved behaviour is the transferable part.

---

## 12. Docker daemon cannot resolve any registry

### Symptom

```text
failed to resolve reference "ghcr.io/patrickbaus/freeswitch-docker:1.11.1":
  dialing ghcr.io:443 via direct connection because Docker Desktop has no
  HTTPS proxy: connecting to ghcr.io:443: dial tcp: lookup ghcr.io: no such host
```

The same failure for `docker.io` — so not registry-specific, and not an image
problem.

### Impact

No image could be pulled. Phase B was blocked at the first step.

### Detection

Testing a tiny unrelated image (`hello-world`) and getting an identical DNS
failure, which ruled out the FreeSWITCH image and the registry.

### Root Cause

The Docker Desktop daemon runs inside WSL2, whose resolver
(`/etc/resolv.conf` → `nameserver 10.255.255.254`) is unreachable from inside
the VM. The **Windows host** resolves and reaches the same registries fine, so
the failure is specific to the daemon's network.

A secondary effect: the host's own DNS is bursty — `curl` intermittently fails
to resolve while `Resolve-DnsName` succeeds.

### Diagnosis

```text
$ wsl -d docker-desktop -- cat /etc/resolv.conf
  nameserver 10.255.255.254
$ Resolve-DnsName ghcr.io            # host: resolves
$ docker pull hello-world            # daemon: "no such host"
```

The split between host-OK and daemon-failed is the whole diagnosis.

### Fix

A side-load path: fetch the image over the host network using the registry HTTP
API, assemble an OCI archive, and `docker load` it. This changes no global
configuration and does not restart Docker Desktop, so it does not disturb the
other containers running on this machine.

The Windows-host DNS burstiness also had to be handled: the fetch waits for a
successful `Resolve-DnsName` before each attempt, and retries.

### Verification

```text
==> layer 3/3  ae99e551676f  13.7 MB
==> docker load
Loaded image: ghcr.io/patrickbaus/freeswitch-docker:1.11.1
```

The loaded digest `sha256:8b55a39…` matches the linux/amd64 manifest verified
during the audit, so provenance is intact.

### Prevention

* When `docker pull` fails, test with a trivial image before blaming the image
  or the registry.
* Distinguish "the daemon has no network" from "the image does not exist" —
  they present similarly.
* `docker load` is always available as a fallback and needs no daemon network.

### Production relevance

**Not applicable to the FreeSWITCH environment itself** — this is a property of
one development machine. It is recorded because it will recur on that machine,
and because the workaround is safe and non-disruptive. If daemon DNS is ever
repaired, plain `docker pull` works and the workaround becomes unnecessary.

## 13. Published ESL port connects and immediately closes (Phase C)

### Symptom

A TCP client connects to the published ESL port, the connect succeeds, and the
connection is then closed with **zero bytes**. No banner, no error. The listener
is healthy when tested from inside the container.

### Impact

Any ESL client outside the container - including the intended host-run Java
application - cannot connect at all. A configuration check that inspects the
Docker mapping and the in-container bind will still pass.

### Detection

```bash
# from the host
python -c "import socket;c=socket.create_connection(('127.0.0.1',8021),timeout=5);\
print(len(c.recv(200)))"
# -> 0     broken

# inside the container - this works
docker exec obd-freeswitch sh -c \
  "printf '' | timeout 3 nc 127.0.0.1 8021 | head -1"
# -> Content-Type: auth/request
```

The control that makes it unambiguous - compare with a port bound to the
container's *network* address:

```bash
docker exec obd-freeswitch netstat -tlnp | grep -E '8021|5060'
# tcp  127.0.0.1:8021   <- loopback only
# tcp  172.25.0.2:5060  <- container address
```

`5060`, bound to the container address, works from the host. `8021`, bound to
loopback, does not. The difference is the cause.

### Root Cause

Phase B set `listen-ip` to `127.0.0.1`, reasoning that loopback inside the
container plus a loopback-published host port is the narrowest possible
exposure.

**That is wrong under Docker.** A published port is delivered to the container's
**network address**, not to its loopback interface. So with
`listen-ip=127.0.0.1` the host's connection is accepted by the port forwarder
and then dropped, because there is no listener at the backend address.

Phase B's acceptance check verified the Docker mapping (`HostIp = 127.0.0.1`) and
the in-container bind (`127.0.0.1:8021`) as two separate facts and never combined
them, because every ESL test used `docker exec`, which bypasses the published
port entirely.

### Diagnosis

Ask which address the process actually bound, not which address the config file
names. `netstat` inside the container answers it in one line.

### Fix

`listen-ip` = `0.0.0.0` inside the container. The host mapping stays
`127.0.0.1:8021`, so the security boundary is unchanged from the host's
perspective - reachable from this machine only. What widens is reachability from
other containers on `obd-telephony`, which is intended, and
`apply-inbound-acl=obd-dev-acl` still restricts peers to loopback and RFC1918.

### Verification

```text
docker exec obd-freeswitch netstat -tlnp | grep 8021
# tcp  0.0.0.0:8021  0.0.0.0:*  LISTEN  1/freeswitch

python -c "import socket;c=socket.create_connection(('127.0.0.1',8021),timeout=6);\
c.settimeout(6);print(c.recv(300))"
# b'Content-Type: auth/request\n\n'
```

### Prevention

* **Never validate a published port by its two halves.** A correct mapping and a
  correct in-container bind do not imply the composition works. Test the path a
  real client uses.
* When a service must be reachable from outside a container, bind it to the
  container's network address and control exposure with the published mapping
  plus an ACL - not with loopback inside the container.

### Production relevance

**Directly applicable.** The same mistake in a real deployment publishes a port
that accepts connections and serves nothing, which looks like a firewall problem
and is not. The general rule - bind the address your clients actually reach -
applies to every containerised service.

## 14. A settings file that is silently ignored (wrong root element) (Phase C)

### Symptom

FreeSWITCH starts, reports `UP`, and is completely healthy on ESL - but one
module is inert. For `mod_sofia` there is **no SIP listener at all**. For
`mod_event_socket` the listener runs on its compiled-in defaults.

### Impact

For sofia: nothing can register or be called; the switch appears to work.
For event socket: **the configured password is not in effect**, so the stock
image credential becomes live.

### Detection

```bash
docker logs obd-fs-endpoint-1001 2>&1 | grep -i 'sofia.c\|No Settings'
# [ERR] sofia.c:4494 Open of sofia.conf failed          <- wrong root element
# [ERR] sofia.c:4567 No Settings, check the new config! <- params not in <settings>

docker exec obd-fs-endpoint-1001 netstat -tlnp | grep 5060
# (nothing)   <- the tell
```

### Root Cause

Three independent mistakes, each of which fails **silently**:

1. **Wrong root element.** `sofia.conf.xml` and `event_socket.conf.xml` must have
   a root element named `<configuration name="...">`. With `<config name="...">`
   the module's own lookup fails, the file is ignored wholesale, and the module
   keeps its compiled-in defaults. The correct form is visible in the platform
   switch's own files.
2. **Wrong parameter name.** mod_event_socket's port parameter is `listen-port`,
   not `port`. Setting `port` is ignored - and because the default also happens
   to be 8021, the mistake is invisible.
3. **`bind-host` is not a parameter.** Setting it alongside a correct
   `listen-ip` is at best ignored; relying on it alone leaves the listener on
   loopback, which is entry 13 again.

### Diagnosis

Compare the file's root element and parameter names against a file known to work
in the same image - the platform switch's `event_socket.conf.xml` is the
reference. Then confirm the module bound the address you intended, with
`netstat`.

### Fix

```xml
<configuration name="event_socket.conf" description="...">
  <settings>
    <param name="listen-ip" value="0.0.0.0"/>
    <param name="listen-port" value="8021"/>
    <param name="password" value="..."/>
    <param name="apply-inbound-acl" value="obd-dev-acl"/>
    <param name="stop-on-bind-error" value="true"/>
  </settings>
</configuration>
```

### Verification

```text
docker exec obd-fs-endpoint-1001 netstat -tlnp | grep -E '8021|5060'
# tcp  0.0.0.0:8021     LISTEN   ESL on the container address
# tcp  172.25.0.3:5060   LISTEN   SIP profile is up
```

`stop-on-bind-error=true` is worth setting everywhere: it converts "silently
continued on defaults" into a startup failure.

### Prevention

* When replacing a FreeSWITCH config file, copy the **root element and parameter
  names** from a file known to work in the same image. Do not reconstruct them
  from memory.
* After any config change, assert a *behavioural* fact: a bound port, a
  registered gateway, a value FreeSWITCH reports back. A healthy `status` proves
  nothing about any individual module.

### Production relevance

**Directly applicable, and a security issue.** The event-socket variant leaves
the stock ESL credential active while the service looks healthy. A production
deployment that replaces this file with a subtly wrong structure would run an
unauthenticated ESL interface - which is full remote call control.

## 15. Correct password, rejected: extension numbers collide with the stock directory (Phase C)

### Symptom

A `REGISTER` with the **correct** password is answered `403 Forbidden`, and a
registration gateway logs a growing failure count.

### Impact

Registration is impossible, and the symptom points at the wrong cause. An
engineer will naturally re-check the password, re-generate the secret, and
re-verify the digest - all of which are correct and all of which change nothing.

### Detection

```bash
docker logs obd-freeswitch 2>&1 | grep -A2 sofia_reg
# [WARNING] sofia_reg.c:3210 Can't find user [1001@127.0.0.1] from 172.25.0.1
#  You must define a domain called '127.0.0.1' in your directory and add a user
#  with the id="1001" attribute
```

FreeSWITCH names the exact `user@realm` it looked for. That sentence is the
diagnosis; nothing else will tell you.

### Root Cause

**Extension numbers are a shared namespace with whatever the image ships.**

The stock `directory/default.xml` defines users `1000`-`1014` in the same domain
as the project's test directory. The project's directory also defines `1001`. The
directory lookup returns one match, and the **stock** definition won - whose
password is the value in `vars.xml`. So the correct password was compared against
the wrong stored value and rejected.

A related trap, found first and worth stating separately:

> **A `403` on the authenticated retry of a `REGISTER` does not mean "wrong
> password".** It means "no such user for the realm that was challenged". A wrong
> password and an unknown user are indistinguishable from the client.

The realm itself was a second, separate problem. With the stock
`challenge-realm=auto_to`, the realm is taken from the `To` header - from
whatever address the client dialled - so a host client and an in-network client
were challenged for *different realms*. FreeSWITCH files registrations as
`user@realm`, so a registration could authenticate successfully and still be
unreachable, because it was filed under a key the dialplan never asks for:

```text
Processing 1001 <1001>->1002 in context default
bridge(user/1002@172.25.0.2)
Cannot create outgoing channel of type [error] cause: [USER_NOT_REGISTERED]
```

### Diagnosis

1. Read the `sofia_reg.c` line. It names the `user@realm` actually looked up.
2. Compare against what you believe you are registering as. A mismatch in
   *either* half is the bug.
3. Confirm how many definitions exist for that id:

```bash
docker exec obd-freeswitch sh -c \
  docker exec obd-freeswitch grep -oE 'user id=.[0-9]+' /etc/freeswitch/directory/default.xml
```

### Fix

Two changes, both required.

**Own the directory completely** - replace the stock file rather than adding one
beside it, so there is nothing to collide with.
`infra/freeswitch/conf/directory/default.xml.in` is the whole directory: one
domain, two users.

**Pin the realm** in `sip_profiles/internal.xml`:

```xml
<param name="challenge-realm" value="$${domain}"/>
```

so authentication, the registration key and the dialplan's user lookup all agree
on one value.

An entrypoint guard now refuses to start if any user id other than 1001/1002 is
present, so this cannot silently regress.

### Verification

```text
# correct password now succeeds
REGISTER (no auth)          -> SIP/2.0 401 Unauthorized
REGISTER (digest, qop=auth) -> SIP/2.0 200 OK

# the stock credential is rejected
REGISTER as 1000 with the stock default password -> SIP/2.0 403 Forbidden

# exactly one definition per id
docker exec obd-freeswitch sh -c \
  docker exec obd-freeswitch grep -cE 'user id=.1001' /etc/freeswitch/directory/default.xml
# -> 1
```

### Prevention

* When choosing test extension numbers, check the stock directory first - or
  better, replace the stock directory so the question cannot arise.
* Treat a `403` on an authenticated SIP retry as "not found", and read the log
  line before touching a password.
* Keep one file per configuration area. Two files declaring the same domain or
  context is legal and produces confusing, order-dependent behaviour.
* Pin `challenge-realm` explicitly. `auto_to` makes a registration's identity
  depend on the network path used to reach the switch.

### Production relevance

**Directly applicable.** Both halves recur in production. The realm mismatch is
the more dangerous one: it produces a switch that authenticates subscribers,
stores registrations under keys nothing looks up, and reports every call as
`USER_NOT_REGISTERED` - a fault that reads like a carrier problem and is not.
The collision is also a genuine security concern: any provisioning that reuses
shipped extension numbers inherits the shipped credentials.

## 16. A call that answers but has no media (missing `dial-string`) (Phase C)

### Symptom

A call to a registered extension **answers**. Signalling looks completely
healthy. And there is no audio, and no DTMF, and no error anywhere obvious.

### Impact

This is the most misleading failure mode found in Phase C, because every
signalling-level check passes. A test that asserts "the call connected" is
satisfied. Only a test that asserts *media* catches it.

### Detection

The log names it, on the leg that tried to bridge:

```bash
grep 'No dial-string' /path/to/freeswitch.log
# [ERR] mod_dptools.c:4419 No dial-string available, please check your user
#       directory.
```

The channel exists, an answer was received, and no bridge was attempted.

### Root Cause

The directory's domain was defined without its `dial-string` parameter:

```xml
<domain name="$${domain}">
  <groups>
    <group name="default">
      <users>
        <user id="1002">
          ...
```

`user/<ext>` is resolved **through that parameter**. With it missing, FreeSWITCH
cannot build a bridge target, so the two legs are never connected. Registration
works, the INVITE works, the 200 OK works - and media silently does not flow.

### Diagnosis

Confirm the parameter is present in the **compiled** configuration, not just the
source file, because the two can differ:

```bash
docker exec obd-freeswitch sh -c \
  'grep -c "name=\"dial-string\"" /var/log/freeswitch/freeswitch.xml.fsxml'
# -> must be at least 1
```

### Fix

```xml
<domain name="$${domain}">
  <params>
    <param name="dial-string"
           value="{$presence_id}${dialed_user}@{$dialed_domain}"/>
  </params>
  <groups>
    ...
  </groups>
</domain>
```

### Verification

A call now bridges, and the RTP counters are non-zero **on both ends**:

```bash
fs_cli -x "show channels"        # two legs, CS_CONSUME_MEDIA
fs_cli -x "uuid_getvar <uuid> local_media_port"
```

Asserting `local_media_port` alone is not enough - it proves a port was
allocated, not that audio arrived. See entry 17.

### Prevention

* When writing a directory by hand, treat `dial-string` as mandatory. It is easy
  to overlook because registration works perfectly without it.
* Distinguish three claims explicitly in every test: **signalling connected**,
  **media flows**, **audio is two-way**. Only the third implies the second, and
  this defect passes the first two.
* Assert media counters from the **far end**. Counting packets the switch sent
  proves nothing about arrival.

### Production relevance

**Directly applicable.** A mis-provisioned subscriber in a production directory
produces exactly this: a customer hears silence, and every signalling metric
looks perfect. Because the symptom is "no audio" rather than "call failed", it
is routinely misdiagnosed as a carrier or codec problem.

## 17. "Media counters" are not available: how to actually prove audio flows (Phase C)

### Symptom

Attempts to read RTP packet counters return `_undef_`, and
`uuid_debug_media` prints a usage string that looks like an error:

```text
rtp_audio_in_packet_count = _undef_
rtp_audio_out_packet_count = _undef_
-USAGE: <uuid> <read|write|both|vread|vwrite|vboth|all> <on|off>
```

### Impact

Without packet counters there is no direct way to assert that audio arrived, so
media tests degrade into "a port was allocated" - which proves nothing.

### Detection

```bash
fs_cli -x "uuid_getvar <uuid> rtp_audio_in_packet_count"
# -> _undef_      not available on this build
```

### Root Cause

Two separate facts, both established by observation rather than from
documentation:

1. The `rtp_audio_*` counters are **not channel variables** in this build, so
   `uuid_getvar` cannot reach them.
2. `uuid_debug_media` is **not a statistics command**. Its signature is
   `uuid_debug_media <uuid> <read|write|both|...> <on|off>` - it *toggles* media
   debug logging. Called with only a UUID it prints its usage string, which reads
   like a failure and is not.

### Diagnosis

Ask what the command actually does before concluding it is broken. The usage
string is the specification.

### Fix

Prove media flow from the **far end** instead. Three usable options, best first:

1. **Read the peer's own counters.** The far end must be reachable - which for a
   containerised peer means exposing its ESL on loopback. This is why
   `obd-fs-endpoint-1001` and `-1002` publish `127.0.0.1:8031` and `:8032`.
2. **Enable media debug on both ends** and read the resulting log lines:
   ```bash
   fs_cli -x "uuid_debug_media <uuid> both on"
   ```
3. **Capture packets** on the Docker network and count UDP flows to the
   negotiated ports.

What is *not* sufficient: reading `local_media_port`, reading `show channels`, or
observing a bridge. All three are consistent with a silent call.

### Verification

Two-way audio requires a non-zero received-packet count on **both** ends, at the
same time. One end's send counter proves only that the socket was written to.

### Prevention

* Distinguish **port allocated**, **packets sent**, and **packets received**. Only
  the third is evidence of audio.
* Prefer measuring at the far end. A sender can always prove it tried.
* When a metric is unavailable, find out *why* before designing around it. Here
  the answer changed the test design, rather than the conclusion.

### Production relevance

**Directly applicable.** Media monitoring in production needs a real source of
truth for this. On this build that means peer-side measurement or packet
capture; `uuid_debug_media` will not do it, and mistaking it for a statistics
command wastes time and can lead to a false PASS.

## 18. Two FreeSWITCH containers sharing one log directory (Phase C)

### Symptom

A container that starts normally reports itself healthy, then dies with:

```text
Cannot Initialize [Cannot Open log directory or XML Root!]
```

and restarts repeatedly. The configuration is correct.

### Impact

Confusing, because the error names configuration and the cause is neither
configuration nor the log directory's permissions.

### Detection

```bash
docker inspect <container> --format '{{.State.ExitCode}} {{.RestartCount}}'
# -> 2 10
```

Then compare the volume mounts: if two containers map the same host directory to
`/var/log/freeswitch`, that is the cause.

### Root Cause

Every FreeSWITCH instance writes three files into its log directory:

| File | Shared safely? |
|---|---|
| `freeswitch.log` | no - interleaved writes from two processes |
| `freeswitch.xml.fsxml` | no - the **compiled configuration cache** |
| `core.db` | no - SQLite, and SQLite assumes exclusive ownership |

Two instances sharing a directory corrupt the compiled configuration, and the
next start cannot read its own XML root.

### Diagnosis

```bash
docker compose -f <file> config --format json \
  | python -c "import json,sys;d=json.load(sys.stdin);\
[print(s,[v['source'] for v in d['services'][s]['volumes']\
 if v['target']=='/var/log/freeswitch']) for s in d['services']]"
```

Two identical sources in that list is the answer.

### Fix

One log directory per instance, and keep it **out** of any shared YAML anchor, so
that adding a per-service `volumes:` key cannot silently drop the inherited
mounts:

```yaml
services:
  endpoint-a:
    volumes:
      - ./conf/...:/etc/freeswitch/...:ro
      - ./logs-1001:/var/log/freeswitch
  endpoint-b:
    volumes:
      - ./conf/...:/etc/freeswitch/...:ro
      - ./logs-1002:/var/log/freeswitch
```

### Verification

```text
docker compose -f infra/docker-compose.freeswitch-endpoints.yml config --format json
# endpoint-a -> ...\logs-1001
# endpoint-b -> ...\logs-1002

docker inspect obd-fs-endpoint-1001 --format '{{.State.Health.Status}} {{.RestartCount}}'
# -> healthy 0
```

### Prevention

* One writable state directory per instance, always. Never share a log or data
  directory between two processes that both write it.
* In Compose, be aware that a key written in a service **replaces** the same key
  from a merge anchor rather than appending. Declaring `volumes:` to add one
  mount will drop every inherited mount, and the failure looks like a missing
  file rather than a YAML mistake.
* Give every bind-mounted template a **distinct** target path. Two templates at
  the same target means one silently shadows the other.

### Production relevance

**Directly applicable.** A shared `core.db` between two FreeSWITCH instances is
SQLite corruption under concurrent access, and a corrupted compiled-config cache
produces an error that points at configuration rather than at the shared file.
On a single-host deployment with several FreeSWITCH instances - a common
carrier-simulator topology - this is easy to create.

---

## 19. ESL rejects a command it does not recognise (Phase D)

### Symptom

Every `uuid_broadcast`, `uuid_kill` and `uuid_bridge` is answered:

```text
-ERR command not found
```

while `bgapi originate` works perfectly. Tests pass. Nothing in the logs looks
wrong.

### Impact

Total, and silent. Playback, hangup and bridge all fail, so no call can ever
complete — and the client sees only a rejected command per operation.

### Detection

```text
fs_cli -x "uuid_kill <uuid> NORMAL_CLEARING"
# -ERR command not found
fs_cli -x "api uuid_kill <uuid> NORMAL_CLEARING"
# +OK
```

### Root Cause

**ESL accepts a fixed set of inbound commands** — `api`, `bgapi`, `event`,
`filter`, `linger`, `exit`, `hup`, `log` — and rejects everything else.
`uuid_kill`, `uuid_broadcast` and `uuid_bridge` are **APIs**, so they must be
sent `api`-prefixed. `bgapi` is a genuine inbound command, which is why
originate worked and nothing else did.

The project's ESL client sent the channel commands bare, for the whole of its
life. It was never caught, because `FakeEslServer` replied `+OK accepted` to any
command it did not recognise.

### Diagnosis

Compare the two forms for any command you are unsure about. If the bare form
fails and the `api` form succeeds, this is the cause. The switch tells you
directly — there is no need to infer it.

### Fix

Prefix channel-addressed APIs:

```text
api uuid_kill <uuid> NORMAL_CLEARING
api uuid_broadcast <uuid> <path> aleg
api uuid_bridge <a> <b>
```

Do **not** prefix `bgapi originate` — it is a bare inbound command, and
prefixing it breaks it.

And make the double faithful: it must answer `-ERR command not found` to
anything it does not recognise.

### Verification

```text
mvnw test -Dtest=EslProtocolTest
# PHASE D J4: media and hangup commands are api-prefixed  PASSED
```

### Prevention

* **A protocol double must model what the server does with an unknown command.**
  A permissive default hides total failure of the command path. This was the
  single most valuable lesson of Phase D, and it cost nothing to apply and would
  have caught a defect that would have broken every call in production.
* When a client rejects or accepts a command based on the reply text, verify the
  exact reply shape with a real server before trusting it. See entry 20.

### Production relevance

**Directly applicable, and severe.** A deployment that had shipped would have
placed calls, answered them, and then failed to play audio or hang up — a
failure that looks like a media or carrier problem and is neither. The test suite
would have stayed green throughout.

## 20. An `api` reply has an empty Reply-Text (Phase D)

### Symptom

A command the switch has **accepted** is reported by the client as a failure:

```text
Unexpected reply for api uuid_kill: Content-Type=api/response
```

or, for a diagnostic, the client reports the command returned nothing.

### Impact

Every successful `api` call looks like a failure. Discovery code — resolving an
address, reading a status — silently gets an empty string.

### Detection

```text
fs_cli -x "api status"
# Content-Type: api/response
# Reply-Text:            (empty)
# body:                  UP 0 years, 0 days, ...
```

Compare with a command reply:

```text
fs_cli -x "bgapi status"
# Content-Type: command/reply
# Reply-Text: +OK Job-UUID: 77898af7-...
```

### Root Cause

Two reply shapes share one connection:

| Command sent | `Content-Type` | `Reply-Text` | Result in |
|---|---|---|---|
| `auth`, `event`, `bgapi` | `command/reply` | `+OK ...` | the header |
| `api <anything>` | `api/response` | **empty** | the **body** |

A client whose reply handling requires `+OK` rejects every successful `api`
call, and one that reads only `Reply-Text` sees nothing.

### Diagnosis

Print the whole frame, not just the verdict. The `Content-Type` distinguishes
the two shapes immediately.

### Fix

For `api` commands, treat **`-ERR` as the only failure**. Absence of a verdict
is the normal shape of a successful `api` reply, not a fault.

Keep the stricter rule for genuine command replies, where "no verdict" really
does indicate a malformed response.

### Verification

```text
mvnw test -Dtest=EslProtocolTest
# SEQ-5b: an api/response with no verdict is SUCCESS   PASSED
```

### Prevention

* Never assume one reply shape. `Content-Type` tells you which you have:
  `command/reply` versus `api/response`.
* Assert on the exact reply text, not on "the call did not throw".

### Production relevance

**Directly applicable.** A monitoring or discovery path built on `Reply-Text`
alone reports empty results for every successful query, which reads as "the
switch is not responding" rather than "the client is reading the wrong field".

## 21. The correlation header that does not exist (Phase D)

### Symptom

Every event is dropped with a warning naming a header that the switch never
sends:

```text
Received ESL event without Call-UUID: CHANNEL_ANSWER
```

No call ever progresses past DIALING. Nothing fails loudly.

### Impact

Total, and invisible. The application cannot correlate a single provider event
to a call, so no call ever reaches a terminal state, no reservation is released,
and no outcome is recorded.

### Detection

```text
grep 'without Call-UUID' application.log | wc -l
# a number equal to the number of events received
```

Then confirm against the switch what it actually sends:

```text
# capture a real event, unfiltered, and look for the identity headers
```

### Root Cause

`Call-UUID` is **not a FreeSWITCH header**. The channel UUID is present as
`Channel-Call-UUID`, `Unique-ID`, `Caller-Unique-ID`, `variable_call_uuid` and
`variable_origination_uuid`. A client that reads only `Call-UUID` gets `null`
from every event.

It stayed hidden because every test hand-built its event **with** the
`Call-UUID` header, so the test suite encoded the same wrong contract as the
code. This is the mirror image of entry 19: there the double was too
permissive, here the fixtures were too accommodating.

### Diagnosis

Capture complete, unfiltered header dumps from the live switch for every event
type the client consumes, and enumerate which identity headers are actually
present. Do not infer the header from a specification or from another
integration's code.

### Fix

Resolve identity through an ordered list, with the authoritative header first:

```text
Channel-Call-UUID  ->  Unique-ID  ->  Call-UUID (compatibility only)
```

Header lookup must be **case-insensitive**, because FreeSWITCH's casing is not
stable across the event set.

### Verification

```text
mvnw test -Dtest=EslEventRuntimeContractTest     # 22 contract tests
mvnw test -Dtest=LiveFreeSwitchRuntimeContractTest  # against the real switch
# J1: identity resolves with no Call-UUID header present   PASSED
```

### Prevention

* **Write tests from captured frames, not from prose.** A fixture that
  hand-builds a header block will happily assert a header the provider never
  sends.
* When a correlation key is not matching, log the headers that *were* present
  before concluding the event is malformed.
* Prefer a resolution list over a single hard-coded name, so an additional or
  renamed header is a one-line change.

### Production relevance

**Directly applicable.** This is the failure mode that makes a telephony
integration look "wired up but not working": every component is present, no
component reports an error, and not one call ever completes.

## 22. A test double that accepts everything (Phase D)

### Symptom

A whole subsystem reports as implemented and fully tested, and the entire
test suite is green — yet the feature has never once worked against the real
system.

### Impact

The most expensive kind of defect: the green suite is the evidence used to
declare the work done. In this project it concealed a defect that would have
broken every call.

### Detection

Ask whether the double could fail. A useful test:

> If this subsystem were completely broken, would any test go red?

If the answer is no, the double is not testing behaviour — only shape.

Concretely: `FakeEslServer` replied `+OK accepted` to any command it did not
recognise, so a client that sent every command in the wrong form passed. The
fix was one method: answer `-ERR command not found` to anything unrecognised.

### Root Cause

Doubles are usually written to unblock happy paths, and permissive defaults are
the path of least resistance. Framing was modelled faithfully; **dispatch was
not modelled at all**.

### Diagnosis

Compare the double's behaviour against the real server for a deliberately
malformed input. If the double accepts it and the server does not, that is the
gap.

### Fix

Make the double's failure modes match:

* unknown command -> `-ERR command not found`
* `api` command -> `api/response` with an empty `Reply-Text`
* `command/reply` with no verdict -> failure

### Verification

```text
mvnw test -Dtest=EslProtocolTest
# PHASE D J4: media and hangup commands are api-prefixed   PASSED
```

### Prevention

* Write the failure cases **before** the happy path, and derive them from the
  server's real behaviour rather than from what the client would prefer.
* Require a live-server test for any component whose contract is "the provider
  accepts our command". Framing can be proven with a double; acceptance cannot.
* Treat a green suite as necessary, never sufficient, evidence.

### Production relevance

**Directly applicable, and general.** The same reasoning covers any adapter over
an external system: mocks prove the code calls what you believe it calls; only
the real system proves the call is accepted.

## 23. Work vanishes from the working tree mid-task (concurrent session)

### Symptom

A file you have been editing is suddenly gone from the working tree, and a
suffixed copy of it exists elsewhere in the repository:

```text
D  backend/src/test/java/.../EslProtocolTest.java        <- deleted
?? backend/src/test/java/.../EslProtocolTest.java.vb7c1-parked
```

`git status` shows the file as deleted, and any test referring to it no longer
compiles.

### Impact

Potentially total, and entirely silent. Every edit made to that file is still
present — but in the copy, not where the build looks. Continuing to edit the
"deleted" path creates a second divergent file, and committing would delete a
tracked test.

### Detection

Run this **before and after** any period where another session may be working:

```text
git status --porcelain
git diff --name-status
git diff --cached --name-status
git diff --name-only --diff-filter=U
git stash list
```

Then look for the parked copy specifically:

```text
# any of these anywhere in the tree
*.parked    *parked*    *vb7c1*    *.orig    *.rej    *.bak
```

An unexpected `D` on a file you edited in this session is the signal. So is a
new untracked path whose name is a suffixed copy of a tracked file.

### Root Cause

A concurrent session or tooling relocated the file — here, a
`*.vb7c1-parked` suffix, which suggests a deliberate
"park the file I am about to rewrite" step by another agent working the same
repository. The parked copy is the *newer* content; the tracked path is what
the build reads.

This is not a git fault and not a crash. It is two writers on one working tree
with no coordination.

### Diagnosis

Before recreating or re-editing anything, establish **which copy holds your
work**:

```text
# does the parked copy contain your recent additions?
grep -c "your-marker" path/to/file.vb7c1-parked
grep -n "PHASE\|YOUR-MARKER" path/to/file.vb7c1-parked
```

If the parked copy has them and the tracked path does not, the parked copy is
the one to restore. Restoring the *tracked* path from the parked copy is safe
and idempotent; the reverse would discard the other session's work.

### Fix

Restore, verify, and re-run the affected tests — do not assume the restore was
complete:

```text
mv file.vb7c1-parked file
# confirm line count, brace balance, and that your tests are present
# then re-run the suite for that package
```

If the parked copy turns out to hold *another* session's newer work, do not
overwrite with yours. Merge deliberately, or park again under a distinct
suffix so neither side is lost.

### Verification

```text
# after restoring, the suite that covers the file must be green
mvnw test -Dtest='<the class in that file>'
```

For this instance: the parked copy held all Phase D additions; restoring it gave
a 556-line file with all 6 Phase D tests present and braces balanced, and the
telephony suite returned to 359 tests / 0 failures.

### Prevention

* **Check `git status` and `git stash list` at the start of every session.** It
  costs seconds and is the only reliable way to learn that someone else has
  moved your work.
* Treat a sudden `D` on a file you were editing as a stop-the-line event. Do not
  recreate it from memory.
* Keep a distinctive, greppable marker in work-in-progress files so ownership of
  a copy is provable rather than guessed.
* `git add -A --dry-run` before committing shows exactly what would be staged —
  including a deleted test file.
* Establish an explicit ownership boundary in a multi-session repository, and
  record it in the phase report so the next session inherits it.

### Production relevance

**Not a production concern — a process concern, and the process failure is the
expensive one.** The lesson generalises to any shared workspace: CI, a shared
staging box, or a build agent that regenerates files. A stale or regenerated
artefact that is committed looks like an intentional deletion, and the loss is
discovered when a test silently stops running.

## 24. The switch answers your call, but nobody else sees it (Phase E)

### Symptom

The platform reports a perfect call, and the far end has no idea it happened:

```text
CHANNEL_ANSWER, Answer-State=answered
```

while the peer reports nothing at all.

### Impact

**Total, and completely silent.** A test suite written against the local switch
will pass, a call-flow document will claim the path works, and not one call has
reached anyone. In this project it was believed for several steps before the
peer was consulted.

### Detection

Check the peer, not the switch. Any one of these on the far side means the call
is fake:

```text
rtp_remote_sdp_str   = _undef_        no SDP was ever exchanged
peer live channels   = 0              the far end has no call
peer UDP counters    = unchanged      no RTP reached it
```

and in the platform log, two easily-missed channels:

```text
loopback/voicemail-a
loopback/voicemail-b
```

### Root Cause

The image's stock `public` context answers every extension `1000-1019` locally
with `voicemail`, creating a loopback pair. The INVITE never leaves the machine,
yet the originated leg looks entirely normal to the event stream.

```xml
<extension name="local_extension">
  <condition field="destination_number" expression="^(10[01][0-9])$">
    <action application="voicemail" data="default"/>
```

This is not a FreeSWITCH bug - it is stock behaviour, and it is a landmine
because it makes the switch report success for anything it has a pattern for.

### Diagnosis

Ask which dialstring the local switch answers by itself. A switch that answers
its own dialplan will never report an unroutable call as failed. If the peer has
no channel and there is no SDP, the switch answered itself, full stop.

### Fix

Replace the context. Bridge a *registered* extension to its contact, and reject
an unregistered one honestly:

```xml
<extension name="obd-bridge-registered-extension">
  <condition field="${sip_destination_user}" expression="^\d{4}$"/>
  <condition field="${user_exists(sip_destination_user)}" expression="true">
    <action application="bridge" data="sofia/internal/${destination_number}@${domain}"/>
  </condition>
  <condition field="${user_exists(sip_destination_user)}" expression="false">
    <action application="respond" data="404 Not Found"/>
    <action application="hangup"/>
  </condition>
</extension>
```

### Verification

```text
# the peer must show its own call, on its own switch
tools/freeswitch-harness/e1_decisive.py
  ENDPOINT-side events: CHANNEL_CREATE -> CHANNEL_ANSWER -> PLAYBACK_START
                         -> PLAYBACK_STOP -> CHANNEL_HANGUP
  VERDICT: GATEWAY PATH GENUINELY WORKS - CONFIRMED -- LOCAL
```

### Prevention

* **A channel event reports what the LOCAL switch believes. Only the peer's own
  state can confirm a call happened somewhere.** This is the rule; the specific
  bug is incidental.
* Before trusting a call: the peer has a channel, SDP was exchanged both ways,
  the peer's counters moved, and the hangup cause is a negotiated response rather
  than a routing failure.
* Assert on peer evidence in tests. A test that only reads the local event stream
  cannot distinguish a real call from a local loopback.

### Production relevance

**Directly applicable, and severe.** A platform built on such a switch would
report healthy calls in every dashboard while placing none. Every alerting
signal - answer rate, duration, hangup cause - would be derived from the local
switch's opinion and would be confidently wrong.

## 25. `NO_ROUTE_DESTINATION` when the network is provably fine (Phase E)

### Symptom

```text
Hangup sofia/internal/1002 [CS_CONSUME_MEDIA] [NO_ROUTE_DESTINATION]
```

while DNS resolves, the peer answers ping with 0% loss, and the ACL passes:

```text
acl 172.25.0.3 obd-dev-acl   -> true
```

### Impact

Total loss of the outbound path, with a failure message that points at the
network and away from the cause.

### Detection

Establish in this order, and stop as soon as one of them is the answer:

```text
1. DNS            getent hosts <peer>
2. reachability   ping -c 2 <peer>
3. ACL            acl <host> <listname>     (host FIRST - see entry 26)
4. peer listening netstat -tulnp | grep 5060
5. registration   is the peer's contact present in THIS profile's database?
6. profile        which profile declares the gateway?
```

Steps 1-4 all passing means **the transport is fine and a ROUTE does not exist.**

### Root Cause

**mod_sofia routes outbound SIP by domain**, resolving against the registration
database of the profile it originates from. It will not send to an address just
because that address is reachable. Every direct-address form fails, on either
profile, by IP or by DNS name:

```text
sofia/external/1002@172.25.0.4:5060   -> NO_ROUTE_DESTINATION
sofia/internal/1002@172.25.0.4:5060   -> NO_ROUTE_DESTINATION
sofia/external/1002@endpoint-b:5060    -> NO_ROUTE_DESTINATION
sofia/internal/1002@<switch domain>    -> CHANNEL_ANSWER
```

The only route to a registered endpoint is its registration contact. In SIP
generally, the registration *is* the route - devices sit behind NAT and change
address constantly.

### Diagnosis

Two sub-cases, and they need different fixes:

* **No registration** - nothing to route to. Check the peer's registration
  attempts and the realm it challenges with.
* **Registration present but still unroutable** - the originating profile is
  wrong. mod_sofia resolves against the database of the profile it originates
  from, so a contact in `sofia_reg_internal` is invisible to a leg on `external`.

### Fix

Make the gateway originate from the profile that *holds the registration*:

```xml
<!-- declared in sip_profiles/internal.xml, not external.xml -->
<gateways>
  <X-PRE-PROCESS cmd="include" data="../gateway/local-endpoint.xml"/>
</gateways>
```

The declaring profile wins; see entry 27.

### Verification

```text
sofia status profile internal reg    # the contact must be here
sofia status gateway <name>          # Profile must be internal
```

### Prevention

* Treat `NO_ROUTE_DESTINATION` as a routing verdict, not a network verdict. Check
  for a route before touching the network.
* Never address a device by IP. Reach it through its registration.
* Record which profile each registration lives in; it is the profile a call to it
  must originate from.

### Production relevance

**Directly applicable.** In any real deployment devices are behind NAT and
change address. A configuration that dials device addresses is a configuration
that breaks in production and works in the lab only because the lab has no NAT.

## 26. `acl` says `false` for every address (Phase E)

### Symptom

The ACL that should permit the local network permits nothing:

```text
acl obd-dev-acl 10.1.1.1     -> false
acl obd-dev-acl 127.0.0.1    -> false
acl lan 10.1.1.1             -> false
```

and the list demonstrably contains the range being tested.

### Impact

Misleading. It sends you to rewrite a correct ACL, and the real fault is
elsewhere - which in this project was a routing problem that had nothing to do
with the ACL.

### Detection

Compare with an address that must be denied. If `false` everywhere, including
for `127.0.0.1`, the query is wrong, not the list.

### Root Cause

**Argument order.** The API is `acl <host> [listname]` - host first. The obvious
form, `acl <listname> <host>`, returns `false` for every input, which is exactly
what a broken, empty or missing list looks like.

### Diagnosis

```text
acl 172.25.0.3 obd-dev-acl    -> true     correct order, permitted
acl 1.2.3.4  obd-dev-acl      -> false    correct order, denied as designed
```

### Fix

Use `acl <host> <listname>`.

### Verification

```text
# assert both a permitted and a denied address, never one
```

### Prevention

* Learn the order once. It is the opposite of the natural reading.
* When a security control reports "everything denied", verify the query before
  assuming the control is wrong - especially one that is provably correct on
  disk.
* Always test a negative case. An ACL test that only checks an allowed address
  passes for a query that returns `false` for everything.

### Production relevance

**Generalisable, and worth a line in any runbook.** Security controls that
default to "deny" produce indistinguishable symptoms for "misconfigured" and
"mis-queried". Always confirm the query against a known-good input.

## 27. A gateway's `sofia-profile` is ignored (Phase E)

### Symptom

A gateway sets its origination profile, and origination still uses the other
one:

```text
<param name="sofia-profile" value="internal"/>

New Channel sofia/external/1002        <- external anyway
```

### Impact

Confusing, and the parameter is the obvious thing to keep editing. It will
never help.

### Detection

Compare the channel's profile in the originating event with the value in the
gateway file. If they differ, the declaration is winning.

### Root Cause

mod_sofia resolves gateways as:

```text
sofia.conf -> profiles -> profile[@name] -> gateways -> gateway
```

The profile that **declares** the gateway is the profile it originates from. The
gateway's `sofia-profile` parameter is a hint that the declaring profile
overrides. With the gateway declared in `external.xml`, setting
`sofia-profile=internal` had no effect whatsoever.

### Diagnosis

`sofia status gateway <name>` shows the declaring profile. The `<gateways>`
section's location in the profile files is the mechanism.

### Fix

Move the declaration into the profile that should originate the leg:

```xml
<!-- conf/sip_profiles/internal.xml -->
<gateways>
  <X-PRE-PROCESS cmd="include" data="../gateway/local-endpoint.xml"/>
</gateways>
```

This is also usually the *correct* placement, not merely a working one: a
gateway routes against the database of the profile it originates from, so a
contact registered on `internal` is unreachable from an `external` leg.

### Verification

```text
sofia status gateway <name>    # Profile column must be the intended profile
New Channel sofia/<intended-profile>/...   # in the originating event
```

### Prevention

* Read the profile files as the source of truth for gateway behaviour, not the
  gateway file alone.
* Keep carrier trunks and local/test trunks declared on different profiles. It
  enforces the boundary and makes accidental reachability harder.

### Production relevance

**Directly applicable.** Trunk segregation - internal vs external - is a real
security boundary, and it is enforced by *where you declare a gateway*, not by a
parameter inside it.

## 28. An unavailable dialplan application aborts the channel (Phase E.1)

### Symptom

A call answers, then is destroyed a few hundred milliseconds later, with a cause
that has nothing to do with the dialplan:

```text
[INFO]  mod_dptools.c:1406  Channel has been answered
[ERR]   switch_core_session.c:2771 Invalid Application wait
[NOTICE] switch_core_session.c:2772 Hangup [CS_EXECUTE] [DESTINATION_OUT_OF_ORDER]
```

No error is surfaced to the caller. From the far end it looks like a normal
release.

### Impact

**Severe and completely silent.** A test fixture that is subtly broken produces
confident, wrong measurements for as long as it exists. In this project it
survived two phases and generated a false `CHANNEL_ANSWER` diagnosis, a
misattributed `uuid_broadcast` contract failure, and an unprovable two-way audio
result — all three downstream of the same one-word defect.

### Detection

```text
# 1. is the channel suspiciously short-lived?
#    poll `show channels` once a second during a call. 600 ms is not a call.

# 2. ask the far end, not the near end
#    its log will contain the ERR line above

# 3. check the application registry rather than assuming
api show application
```

Then confirm the specific application is present:

```text
show application | grep -w <name>
```

### Root Cause

**`DESTINATION_OUT_OF_ORDER` is what FreeSWITCH reports when the core aborts a
channel on an invalid application** — it is not a SIP ordering fault. The name
reads like a protocol problem, so the search usually starts in the wrong place.

In this build:

```text
registered applications: 179
  wait        no        <- the registry has wait_for_answer, wait_for_silence
  sleep       YES (mod_dptools)
  answer/send_dtmf/playback/hangup  YES (mod_dptools)
```

`wait` is simply not a registered application in FreeSWITCH 1.11.1 here; `sleep`
is the equivalent.

### Diagnosis

Bisect the dialplan at runtime rather than reasoning about it. Reduce it in
stages — this is what ruled out two plausible-sounding causes in minutes:

| Stage | Dialplan | Result |
|---|---|---|
| 1 | as committed | died at 1.0 s |
| 2 | `send_dtmf` removed | died at 1.0 s — **not the cause** |
| 3 | `answer → wait 20000 → hangup` | died at 1.0 s, no `CHANNEL_ANSWER` |

Stage 3 is the decisive one: it removed every action except the suspect and
proved the fault was shared, not action-specific.

**Check the application's actual existence before assuming a dialplan is
correct.** A name that looks plausible to someone who knows SIP is not
guaranteed to exist.

### Fix

Use an application that is actually registered, and verify it rather than
assuming:

```xml
<action application="sleep" data="30000"/>
```

### Verification

```text
# the channel must now outlive the hold period
tools/freeswitch-harness/e1_teardown_isolate.py
  baseline  life= 20.0s  hangup=(still up)
  api uuid_broadcast <uuid> <file> aleg -> '+OK Message sent'
  playback events on the channel        -> ['PLAYBACK_START']
```

### Prevention

* **Treat `Invalid Application` as a first-class finding.** It aborts the channel
  and the cause it reports is misleading. Never pass over an `[ERR]` line in a
  switch log.
* Assert on call **duration** in tests, not only on answer. A fixture that dies
  at 600 ms passes every "did it answer?" check.
* Validate dialplan applications against `show application` for the *actual*
  build, especially anything added to a long-lived fixture.
* When a test fixture is long-lived, re-verify it whenever a symptom looks like a
  product defect. This one survived two phases precisely because it produced
  plausible results.
* Revert runtime-only dialplan experiments from a backup, and confirm the
  committed file is intact afterwards — otherwise the next run inherits the
  experiment.

### Production relevance

**Directly applicable, and the failure mode is worse in production than in a
lab.** A dialplan action that does not exist on the deployed build aborts every
call at the same point, with a SIP cause that points away from the real fault.
Nothing in the SIP trace distinguishes it from a network ordering problem. The
generalisation: **validate configuration against the running build, and treat
`Invalid Application` as a total-failure cause regardless of what the cause
string says.**

## 29. "invalid uuid" and "no such channel" from a live channel (Phase E.1)

### Symptom

Two FreeSWITCH commands disagree about the same channel in the same instant:

```text
api uuid_dump <uuid>                   -> CHANNEL_DATA      (valid)
api uuid_broadcast <uuid> <file> aleg   -> -ERR invalid uuid
api uuid_kill <uuid> NORMAL_CLEARING    -> -ERR No such channel!
```

and the message names the UUID as the problem.

### Impact

Misleading, and expensive. These two errors were read as evidence that the
`uuid_broadcast` command contract was wrong, which would have meant a defect in
every production playback. They were not: the channel had already been destroyed
600 ms after answer.

### Detection

**Never conclude the UUID is invalid because a command says so.** Establish it
independently, and test a command that can only succeed if the switch holds that
exact channel:

```text
1. show channels                 -> is the uuid listed? how many channels?
2. api uuid_dump <uuid>          -> does the returned Unique-ID match the uuid?
3. poll `show channels` each second through the call -> how long does it live?
```

Step 3 is the one that answers it. A channel that disappears within a second of
answering explains every "no such channel" result without any UUID being at
fault.

### Root Cause

The errors are true and the interpretation is wrong. They report the **absence
of the channel**, not the **invalidity of the UUID**. `uuid_dump` succeeds only
because it is issued in the same instant and races the teardown — so a single
successful `uuid_dump` beside a failing `uuid_broadcast` is a timing artefact,
not a contradiction.

### Diagnosis

```text
t+1s  channels=0   answered=True   hungup=True
CHANNEL_HANGUP  DESTINATION_OUT_OF_ORDER
```

Then find who hung up — the near end or the far end. The far end's log and the
SIP trace settle it: here the **endpoint** sent the BYE, and its own log
explained why (entry 28).

### Fix

There is no fix for the symptom. Fix whatever destroys the channel; the
"invalid uuid" errors disappear with it. If a caller must be robust regardless,
then re-resolve the channel and tolerate a missing one rather than assuming a
permanent mapping.

### Verification

```text
tools/freeswitch-harness/e1_broadcast_matrix.py
  A api uuid_dump <uuid>                     -> CHANNEL_DATA (valid)
  B api uuid_broadcast <uuid> <file> aleg    -> +OK Message sent
  G api uuid_kill <uuid> NORMAL_CLEARING     -> +OK
```

### Prevention

* **Test the identity, do not infer it from an error string.** `show channels`
  and the returned `Unique-ID` are the authority.
* **Measure channel lifetime in every call test.** Almost every "the provider
  rejected the command" conclusion is really "the channel was already gone".
* `Core-UUID` differs from `Unique-ID` and is not what these commands address.
  Confusing them invites the same false conclusion.
* When two commands disagree, look for lifetime and timing before suspecting
  either command's contract.

### Production relevance

**Directly applicable.** Real media servers reject commands for dead channels
constantly — a leg can end between your read of state and your write. A client
that treats a rejection as a contract violation will "fix" a working command
path into a broken one, which is the worst possible outcome from a misread
error.

## 30. One uncompilable test file blocks the whole suite (Phase E.2)

### Symptom

The entire test suite runs **zero** tests and fails at `test-compile`, from a
file that has nothing to do with the feature under test:

```text
[ERROR] .../campaign/ZzOpenApiShapeDumpTest.java:[17,2] cannot find symbol
[INFO]  BUILD FAILURE
Tests run: 0
```

Meanwhile `mvn compile` succeeds, so the application is fine and the build
"looks broken" for no visible reason.

### Impact

Total for testing, and completely silent about cause. Every team member working
in the same tree is blocked, and the error names an import rather than the
missing dependency.

### Detection

```text
# is it my code?
mvnw -q -o compile                  # if this passes, main is fine

# does the failure name ONE file?
mvnw -o test 2>&1 | grep '\.java:\[' | sort -u

# does that file belong to someone else's in-flight work?
git status --porcelain -- <path>    # '??' means untracked, i.e. in progress
```

The last step is the one that matters. An untracked file that does not compile is
almost always another workstream mid-edit, not a defect you own.

### Root Cause

`test-compile` compiles **all** test sources. One uncompilable file fails the
phase and no test runs — there is no partial success.

In this instance the file imported `org.springdoc...SpringDocConfiguration` and
`SpringDocWebMvcConfiguration`, which do not exist at those coordinates on this
classpath. It was an untracked concurrent file, created to dump an OpenAPI shape.

### Diagnosis

Separate *compilation* from *test execution* before concluding anything about
your own code. A zero-test result is a build-pipeline signal, not a test result.

### Fix

**Do not edit the other workstream's file.** Narrow the compilation instead, via
a build flag, which leaves their file exactly as it is:

```text
mvnw -o test -Dmaven.compiler.testIncludes=**/shivang/obd/<yourpackage>/**/*.java \
                 -Dtest='<your test classes>'
```

Note that `-Dmaven.compiler.testExcludes=...` did **not** take effect here, while
`testIncludes` did. Verify the flag works before relying on it.

### Verification

```text
classes: 11   tests run: 86   passed: 86   failures: 0
BUILD SUCCESS
```

### Prevention

* Always run `mvn -o compile` before `mvn -o test`. "Cannot find symbol" during
  `testCompile` is a different problem from a failing test.
* Record the narrowing flag in the phase report. A baseline that silently covers
  only part of the suite must never be reported as a full-suite result.
* Agree an ownership boundary for untracked work-in-progress, so one agent's
  scratch file does not block everyone else's suite. Naming scratch files
  distinctively makes the cause obvious.
* A `Zz`-prefixed diagnostic-dump test is still a test: it compiles, and it is
  someone else's to finish.

### Production relevance

**Not a production concern — a continuous-integration concern, and CI failures
block delivery.** In shared workspaces a half-written file by one author can stop
every other author's pipeline, and the failure message points at an import rather
than at the person who owns the file.

## 31. A check constraint accepts a value your Java enum cannot read (Phase E.3)

### Symptom

A hand-written seed row is rejected for a reason that looks wrong:

```text
ERROR: new row for relation "sip_gateways" violates check constraint
       "ck_sip_gateways_owner_consistency"
```

...even though the values you set satisfy the obvious reading of the rule. And
the neighbouring rule looks permissive:

```text
ck_sip_gateways_owner_type :: CHECK (owner_type IN ('PLATFORM','RESELLER','TENANT'))
```

### Impact

A slow, misleading trap in two directions. Choose the "correct" value and the
insert is refused for a reason that sends you down the wrong path. Or remove the
obstacle and insert `TENANT` — which the database accepts and the application
then fails to read.

### Detection

Compare the **database** constraint with the **Java** enum before choosing:

```text
select pg_get_constraintdef(oid) from pg_constraint where conrelid='sip_gateways'::regclass;
```

```text
public enum SipGatewayOwnerType { PLATFORM, RESELLER }   <- no TENANT
```

They are not the same set. Here the DB allows `TENANT` for `owner_type` and
`DEGRADED` for `status`; neither exists in the Java enums.

### Root Cause

**The database check constraints are wider than the JPA enums.** The DB
protects referential-ish shape; the enum is what Hibernate can actually
materialise. A row outside the enum is not a constraint violation at write time
— it is a failure much later, when the entity is loaded, and it surfaces far
from the seed that caused it.

So: **the Java enum is the binding constraint, not the check constraint.**

### Diagnosis

For any seeded value, ask two questions, not one:

1. does the DB accept it?
2. **can the application read it back?**

A "no" on either makes the value unusable. A "yes, and the enum is narrower"
means the enum decides.

### Fix

Use a value the enum declares, and grant tenant access through the mechanism the
code actually reads:

```sql
owner_type        = 'PLATFORM'
owner_reseller_id = NULL
owner_tenant_id   = NULL      -- required: PLATFORM forbids both ids
-- tenant access via sip_gateway_allocations, which is what
-- findAllEligibleForTenant() queries
```

### Verification

```text
insert -> ERROR ck_sip_gateways_owner_consistency   (first attempt)
insert -> INSERT 0 1                              (corrected)
eligible -> gateway=local-endpoint-1002 profile=internal status=ACTIVE enabled=true
```

### Prevention

* Read the entity enum **and** the DB constraint before seeding anything
  persisted. They are separate contracts and only one of them is enforced at
  write time.
* Never treat a successful insert as proof the value is usable. Prove the
  application can read the row.
* Prefer seed values that mirror what the production code already writes, not
  what the schema appears to permit.
* Prefer the application's create-service over direct SQL where one exists: its
  validation encodes the enum, which SQL does not.

### Production relevance

**Directly applicable, and a data-integrity risk rather than a build risk.** A
row that inserts cleanly and cannot be read is worse than one that fails to
insert: it sits in production until the code path that needs it runs. Hand-edited
rows, migration scripts and admin fixes are where this happens, and the failure
is reported against innocent code.

## 32. A concurrent build corrupts your test run (Phase E.5)

### Symptom

A full test run reports an implausible mass failure, with errors that are not
about your code:

```text
tests 937, failures 2, errors 161        (previous clean run: 1005 / 1 / 0)

java.lang.NoClassDefFoundError: com/shivang/obd/telephony/FakeEslServer
  Caused by: ClassNotFoundException

java.lang.IllegalStateException: ApplicationContext failure threshold (1)
exceeded: skipping repeated attempt to load context
```

A test class you know compiled is reported as `ClassNotFoundException`.

### Impact

**A corrupted measurement presented as a project state.** 28 classes errored and
the totals look like a catastrophic regression. Acting on that number would mean
chasing regressions that do not exist, or worse, "fixing" working code.

### Detection

The decisive check, and it takes seconds:

```text
# after the run, do the test classes actually exist?
ls target/test-classes/<your-package>/*.class
```

If surefire executed a class that is not in `target/test-classes`, the run was
corrupted — a completed Maven run does not leave its own test classes missing.

```text
target/         last written 22:12:33
test-classes/  last written 22:12:06
java processes running now: 0
```

Also correlate against the source tree: if another package's files were modified
in the same minutes as your run, suspect contention before conclusions.

### Root Cause

**Two Maven builds sharing one `target/`.** `clean` deletes the directory the
other build is compiling into, and the loser ends up with a partial classpath.

The failure is not random. Missing test doubles produce `NoClassDefFoundError`,
and every Spring integration test that then needs a context reports
`ApplicationContext failure threshold exceeded` — a cascade with one cause.

### Diagnosis

1. `NoClassDefFoundError` for a class that exists in `src` is a **classpath**
   problem, never a logic problem.
2. "ApplicationContext failure threshold exceeded" is a **downstream** symptom.
   Find the first failure that is actually about your code.
3. Check whether another build ran. Cross-reference `target/` write times
   against other packages' file modification times.

### Fix

**Prevent it, because you cannot fix it from inside your run:**

```text
before a test run:   check for a concurrent mvn/java build
during:              if one appears, stop the run and discard the result
after:               verify the test classes exist before trusting any number
```

Always write build logs outside `target/` when using `clean` (entry 30 and
E.4/E.5). Use a scratch log path.

### Verification

```text
ls target/test-classes/com/shivang/obd/telephony/*.class
  -> many files, including FakeEslServer.class   => run is trustworthy
  -> 0 files, yet surefire reported EslProtocolTest => run was corrupted
```

### Prevention

* Treat a sudden large error count with no corresponding source change as
  **build-environment evidence, not regression evidence**, until disproven.
* Never publish a test total without confirming the classpath was intact. The
  cheap check is a `ls` of your own package.
* Record the last *known-good* baseline with its commit, so a corrupted run can
  be dismissed immediately rather than investigated.
* Coordinate build windows in a shared repository. Two agents running Maven
  against one `target/` is the underlying defect, and no amount of retrying a
  single run fixes it.

### Production relevance

**Not a production concern — a CI/trust concern, and trust is the product.** A
flaky pipeline that reports fabricated regressions trains reviewers to ignore
red builds. The genuinely dangerous failure is the opposite one: a corrupted run
that happens to be *green* is never investigated at all.
