# 03 — Configuration

How FreeSWITCH's configuration actually loads, which files this environment
uses, and what breaks when each is wrong.

---

## 1. The two-stage configuration model — read this first

This is the single most important structural fact about FreeSWITCH
configuration, and it explains several otherwise baffling behaviours.

```text
STAGE 1 — PREPROCESS
  /etc/freeswitch/freeswitch.xml
      |
      |  <X-PRE-PROCESS cmd="include" .../>  walks the tree
      |  and writes ONE flattened document to
      v
  /var/log/freeswitch/freeswitch.xml.fsxml      <-- the COMPILED config
      |
STAGE 2 — PARSE
  modules read from the compiled document:
      switch_xml_open_cfg("sofia.conf", ...)
      switch_xml_open_cfg("switch.conf", ...)
```

Consequences that bit us during Phase B:

| Consequence | Why |
|---|---|
**A file can be present, valid, and completely ignored.** If it is not reached by an include chain, it never enters the compiled document. | The compiler only walks `freeswitch.xml`'s includes. |
**"It is in the compiled config" is not the same as "a module read it".** The compiled document is a flat soup of many `<configuration name="...">` roots from many files. A module looks up *its own* file by name. | e.g. `switch_xml_open_cfg("sofia.conf", ...)` finds the `sofia.conf` node specifically. |
**The preprocessor executes its own directives anywhere in the raw text — including inside XML comments.** | A commented-out example of an include directive is still an include directive as far as the preprocessor is concerned. This actually happened. See [13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md). |

**Debugging technique that follows from this.** When FreeSWITCH is not
behaving as the config suggests, look at the *compiled* document. It shows
exactly what every module will see, and it is the fastest way to prove a file
was included:

```text
Command:  docker exec obd-freeswitch grep -c "OBD Phase B" /var/log/freeswitch/freeswitch.xml.fsxml
Purpose:  count how many of OUR config sections survived preprocessing
Success:  a non-zero count, and a larger count than the number of files
          (comments are stripped, so a count difference is expected)
Failure:  0 -> the include chain is broken; the file is not being read
```

---

## 2. The include chain in this environment

```text
/etc/freeswitch/freeswitch.xml                    (stock, untouched)
   │
   ├── <X-PRE-PROCESS cmd="include" data="vars.xml"/>
   │        stock. Defines $${...} preprocessor variables.
   │        Includes the two stun-set lookups (see below) and
   │        default_password=1234.
   │
   ├── <X-PRE-PROCESS cmd="include" data="autoload_configs/*.xml"/>
   │        │
   │        ├── acl.conf.xml  ................ OURS
   │        ├── event_socket.conf.xml  ...... RENDERED from our template
   │        ├── modules.conf.xml  .......... stock (decides what loads)
   │        ├── sofia.conf.xml  ............ OURS  (explicit profile list)
   │        ├── switch.conf.xml  ........... OURS  (RTP range etc.)
   │        └── ~70 more stock files we do not touch
   │
   ├── dialplan/*.xml   ..................... stock, untouched
   ├── chatplan/*.xml   ..................... stock, untouched
   ├── directory/*.xml  ..................... stock, untouched  (default.xml)
   └── lang/*           ..................... stock, untouched
```

**Important:** the `gateway/` directory is **not** part of any include chain.
See §5.

---

## 3. File reference

### 3.1 `autoload_configs/event_socket.conf.xml`

```text
File        /etc/freeswitch/autoload_configs/event_socket.conf.xml
Purpose     configure the ESL listener
Loaded by   mod_event_socket, at module load
When        once, at startup
Source      infra/freeswitch/conf/autoload_configs/event_socket.conf.xml.in
            (a TEMPLATE, rendered at container start)
```

Settings this environment sets, and why each one exists:

| Setting | Value | Why |
|---|---|---|
`listen-ip` | `127.0.0.1` | The stock value is `::` — **every interface**, which would make ESL reachable from the whole Docker network. Loopback + a Docker published port gives the same convenience with a far smaller blast radius. |
`listen-port` | `8021` | ESL standard port, matches the Java default. |
`password` | rendered from `$FREESWITCH_PASSWORD` | The stock value is the publicly known `ClueCon`. |
`apply-inbound-acl` | `obd-dev-acl` | Defence in depth: even on loopback, only private ranges may connect. |
`stop-on-bind-error` | `true` | Fail loudly rather than running with no ESL listener. |

**What breaks if it is wrong:** ESL unreachable (wrong `listen-ip`), or every
Java connection rejected as unauthenticated (wrong password), or the container
healthy but Java silently receiving no events at all (ACL too tight).

**How to verify it:**

```text
Command:  docker exec obd-freeswitch netstat -tlnp | grep 8021
Purpose:  see the actual bind address FreeSWITCH ended up with
Success:  "tcp 0 0 127.0.0.1:8021 0.0.0.0:* LISTEN 1/freeswitch"
Failure:  ":::8021"  -> your listen-ip override did not take effect
```

**The template rule.** The substitution token must appear **exactly once**, in
the password parameter. The entrypoint replaces *every* occurrence, so a second
occurrence — even inside a comment — writes the live secret into the rendered
file as prose. This happened once; see
[13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md).

### 3.2 `autoload_configs/acl.conf.xml`

```text
File        /etc/freeswitch/autoload_configs/acl.conf.xml
Purpose     network access-control lists
Loaded by   core; referenced by name from profiles and mod_event_socket
Source      infra/freeswitch/conf/autoload_configs/acl.conf.xml
```

Why it is replaced rather than left stock: the stock file defines only `lan`
and `domains`, and relies on FreeSWITCH's **auto-generated** lists
(`rfc1918.auto`, `nat.auto`, `localnet.auto`, `loopback.auto`). Those are
derived from the interfaces the process sees — inside a container that is one
synthetic `eth0`, so the effective policy becomes an accident of Docker's
network numbering.

This environment defines `obd-dev-acl` explicitly:

```xml
<list name="obd-dev-acl" default="deny">
  <node type="allow" cidr="127.0.0.1/32"/>
  <node type="allow" cidr="::1/128"/>
  <node type="allow" cidr="10.0.0.0/8"/>
  <node type="allow" cidr="172.16.0.0/12"/>
  <node type="allow" cidr="192.168.0.0/16"/>
</list>
```

`172.16.0.0/12` covers Docker's bridge allocations, which come from
`172.17.0.0/16` upward. The stock `domains` list is retained because the
untouched stock directory still references `$${domain}`.

**What breaks if it is wrong:** everything is denied (SIP registrations and
ESL both refused) or nothing is restricted (the ACL is not actually applied).
`default="deny"` is the safe direction to fail.

### 3.3 `autoload_configs/switch.conf.xml`

```text
File        /etc/freeswitch/autoload_configs/switch.conf.xml
Purpose     core settings: RTP port range, session limits, log level
Loaded by   core, at startup
Source      infra/freeswitch/conf/autoload_configs/switch.conf.xml
```

| Setting | Value | Why |
|---|---|---|
`rtp-start-port` / `rtp-end-port` | `30000` / `30099` | **In the stock file these are commented out**, so FreeSWITCH uses compiled-in defaults of 16384-32768 (`switch_rtp.c`). 16,384 published UDP ports is neither needed nor acceptable for local development. |
`switchname` | `obd-dev` | Distinguishes this instance in logs. |
`max-sessions` | `1000` | Stated explicitly rather than inherited. |
`loglevel` | `info` | Stock is `debug`, which is very noisy for a single-purpose container. |

**How to verify it — and the honest limit.** `fs_cli` cannot report the RTP
range at runtime (verified: no API reaches `switch_rtp_get_start_port()`). What
*can* be proven is that the file is the one being read. During Phase B
`max-sessions` was briefly set to `42`; `fs_cli -x status` then reported
`42 session(s) max`, which only that file could produce. It has been reverted to
`1000`. The effective RTP allocation is **NOT YET TESTED** — it is confirmed at
the first live call.

### 3.4 `autoload_configs/sofia.conf.xml`

```text
File        /etc/freeswitch/autoload_configs/sofia.conf.xml
Purpose     mod_sofia global settings + the list of profiles
Loaded by   mod_sofia, at startup and on reload
Source      infra/freeswitch/conf/autoload_configs/sofia.conf.xml
```

Stock behaviour is a **glob**:

```xml
<profiles>
  <X-PRE-PROCESS cmd="include" data="../sip_profiles/*.xml"/>
</profiles>
```

That pulls in every profile file in the image, including
`internal-ipv6.xml` and `external-ipv6.xml`, which bind `::1:5060` and
`::1:5080` — the exact ports this environment needs.

This file therefore lists the two profiles **explicitly**:

```xml
<profiles>
  <X-PRE-PROCESS cmd="include" data="../sip_profiles/internal.xml"/>
  <X-PRE-PROCESS cmd="include" data="../sip_profiles/external.xml"/>
</profiles>
```

**CONFIRMED result:** `sofia status` reports exactly `2 profiles 0 aliases`,
and neither listener is bound on `::1`.

**Note — there is deliberately no `<gateways>` block in this file.** mod_sofia
looks for gateways inside the *profile* node, not here. A `<gateways>` block
placed here compiles cleanly and is then silently ignored. See §5.

### 3.5 `sip_profiles/internal.xml` and `sip_profiles/external.xml`

```text
File        /etc/freeswitch/sip_profiles/{internal,external}.xml
Purpose     one Sofia profile each
Loaded by   mod_sofia, via the includes in sofia.conf.xml
Root element  MUST be  <profile name="internal"> / <profile name="external">
```

**The root element is not cosmetic.** With `<configuration name="sofia.conf">`
as the root, the file is preprocessed, included, appears correctly in the
compiled document — and mod_sofia ignores it. `sofia status` then says
`0 profiles`. This cost real time; see
[13-TROUBLESHOOTING.md](13-TROUBLESHOOTING.md).

Key settings, both profiles:

| Setting | Value | Why |
|---|---|---|
`sip-port` | 5060 / 5080 | internal / external convention |
`context` | `public` | where the stock dialplan lives |
`inbound/outbound-codec-prefs` | `PCMU,PCMA,G722,OPUS` | the interoperable common denominator |
`rfc2833-pt` | `101` | matches stock convention |
`liberal-dtmf` | `true` | **required.** Stock default is `false`, which accepts DTMF only via the profile's preferred method. An endpoint sending SIP INFO when FreeSWITCH expects RFC 2833 is silently ignored and Java receives nothing. Later testing deliberately covers both methods. |
`proxy-info` | `true` | **required for the agent test.** Stock default `false` consumes inbound SIP INFO locally instead of relaying it across a bridge, so an agent's keypresses would be lost once bridged. |
`auth-calls` | `false` | stock external-profile behaviour |
`auth-subscriptions` | `false` on internal | **deliberate.** Nothing registers in Phase B, and the stock directory ships `default_password=1234` (`vars.xml`). Enabling registration now would mean accepting a well-known credential. |
`apply-inbound-acl` / `local-network-acl` | `obd-dev-acl` | private network only |
`sip-ip` / `rtp-ip` | **unset** | See below. |

**Why `sip-ip` and `rtp-ip` are unset.** Unset, the SIP listener binds all
interfaces inside the container (so it is reachable from the Docker network and
from published ports) while a real address is still auto-detected for the SDP
connection line. Pinning `rtp-ip` to `0.0.0.0` would advertise `0.0.0.0` in SDP
and break media for every call.

**Deliberately omitted, and why:**

| Omitted | Consequence |
|---|---|
all `tls*` parameters | no TLS listener; 5061/5081 never bound |
`ws-binding`, `wss-binding` | the stock internal profile binds `:5066` and `:7443`; omitting them means those ports are **not bound at all** |
`presence` settings | not needed |
`rtp-timeout-sec`, `rtp-hold-timeout-sec` | deprecated in 1.11 — honoured but warn on every load, and the suggested replacements are channel variables, not profile parameters. Compiled defaults match the stock values, so omitting them is behaviour-neutral and warning-free. |

### 3.6 `gateway/fs-gateway.xml`

```text
File on host  infra/freeswitch/gateway/fs-gateway.xml
Mounted at    /etc/freeswitch/gateway/fs-gateway.xml
Included by   the <gateways> block of sip_profiles/external.xml
Loaded by     mod_sofia, at startup
```

| Setting | Value | Why |
|---|---|---|
`proxy` | `freeswitch-provider:5080` | names the service the provider FreeSWITCH will use in a later phase, so no change will be needed then |
`register` | `false` | **load-bearing, not cosmetic.** mod_sofia defaults this to `true`. With the default, the gateway tries to REGISTER against a non-existent provider, fails, and is **discarded** — `0 gateways`, no log entry. |
`sofia-profile` | `external` | explicit because the Java dial string never contains a profile, so FreeSWITCH resolves the profile from *this* setting |

**What breaks if it is wrong:** the gateway silently does not exist, and every
`sofia/gateway/fs-gateway/...` originate fails.

### 3.7 Files deliberately NOT touched

| File | Why it is left stock |
|---|---|
`vars.xml` | 19 KB of preprocessor variables. The untouched `directory/` and `dialplan/` depend on many of them. Replacing it to silence two STUN timeouts is not worth the risk. It does contain `default_password=1234` and the `stun-set` lookups — both recorded as known issues. |
`modules.conf.xml` | Already correct: `mod_sofia`, `mod_event_socket`, `mod_commands`, `mod_dptools`, `mod_loopback` enabled; `mod_xml_curl` and `mod_signalwire` already commented out. |
`dialplan/*` | The Java platform makes per-channel decisions over ESL, so no dialplan expresses campaign logic. Stock dialplan is adequate and unopinionated here. |
`directory/*` | No endpoints exist in Phase B. `default.xml`'s stock users are unused because registration is disabled. |
`extensions.conf` | stock; not used |

---

## 4. Settings and their effect, quick table

| Symptom | Config file | Most likely setting |
|---|---|---|
ESL unreachable | `event_socket.conf.xml` | `listen-ip`, or the ACL, or the port mapping |
Every Java action fails auth | rendered `event_socket.conf.xml` | `password` not the value Java is sending |
`0 profiles` | `sip_profiles/*.xml` | wrong **root element** |
Profile present, not RUNNING | `sip_profiles/*.xml` | port already in use |
`0 gateways` | `gateway/*.xml` | `register` left at its `true` default |
SIP calls instantly rejected | `acl.conf.xml` | ACL denying, or the peer's source IP outside `obd-dev-acl` |
Audio is one-directional | `sip_profiles/*.xml` | `rtp-ip`/`sip-ip` pinned wrongly, or NAT |
DTMF never arrives | `sip_profiles/*.xml` | `liberal-dtmf` false, or no `telephone-event` in SDP |
Agent DTMF lost after bridging | `sip_profiles/*.xml` | `proxy-info` false |
Container crash-loops at startup | any | XML that does not parse — check for **nested comments** |

---

## 5. The gateway location trap

**CONFIRMED by reading FreeSWITCH 1.11.1 source.** The widely-documented
location `sip_profiles/<profile>/gateway/*.xml` is **not read** by this
version. `mod_sofia` contains no directory scan, and `switch_xml.c` contains no
`gateway` or `sip_profiles` reference. Gateways resolve **only** through the
configuration tree:

```text
sofia.conf
   └─ profiles
        └─ profile[@name='external']
             └─ gateways                    <-- must be HERE
                  └─ gateway[@name='fs-gateway']
```

The alternative source, `<domains>`/`<user>` entries carrying a nested
`<gateways>` block, is only consulted for a profile's `parse` domains and is not
used here.

---

## 6. Verifying a config change actually took effect

Three techniques, in increasing order of authority:

1. **Check the file in the container** — proves the bind mount delivered it.
2. **Check the compiled document** — proves the preprocessor kept it.
   ```text
   docker exec obd-freeswitch grep -c "OBD Phase B" /var/log/freeswitch/freeswitch.xml.fsxml
   ```
3. **Change one value to something unmistakable and observe FreeSWITCH
   report it.** This is the only technique that proves a *module* read the
   file, and it is what caught the RTP/`max-sessions` ambiguity in Phase B.
   Apply it to a value FreeSWITCH echoes back (session limits, log level),
   never to something the product depends on.
