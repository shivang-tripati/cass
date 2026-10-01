# 14 — Security

What this environment protects, how, and why — plus what would have to change
before it could face real traffic.

---

## 1. The threat model in one paragraph

This environment's FreeSWITCH is reachable from exactly one place: the Windows
host's loopback interface. It cannot register, cannot place calls, and has
never placed a call. The realistic threats today are a **credential leak**
(an ESL password escaping into a log or a commit), and a **misconfiguration
that silently disables a control** — which is the failure that actually
happened during Phase B and is far more likely than an attack.

---

## 2. ESL security

### Why ESL is the crown jewel

An ESL credential is not a status credential. It is **full control of the
switch**: place calls, terminate live calls, bridge arbitrary channels
together, play audio, and read everything. Treat it as equivalent to shell
access on the host.

### What the stock image does, and why it is unacceptable here

Read directly from the image:

```xml
<param name="listen-ip" value="::"/>
<param name="listen-port" value="8021"/>
<param name="password" value="ClueCon"/>
```

`ClueCon` is printed in FreeSWITCH's own documentation. `::` means every
interface. A stock FreeSWITCH exposed on a reachable network is an open
telephony switch. Separately, the platform's own development default,
`fs-password`, is a committed value in a public repository and equally
unusable.

### What this environment does

| Control | Value | Why |
|---|---|---|
| Bind address | `0.0.0.0` **inside** the container, `127.0.0.1` on the host | **CORRECTED in Phase C.** The stock `::` reaches the whole Docker network. Binding container loopback was also wrong: a published port is delivered to the container's *network* address, so `listen-ip 127.0.0.1` made the published port accept a connection and close it with zero bytes. See entry 13. |
Host binding | `127.0.0.1:8021` | Never `0.0.0.0`. Not on the LAN. |
Credential | 32 random bytes, base64url, generated locally | Not a default, not a committed value, not guessable. |
Storage | `infra/.env`, git-ignored | Verified: `git check-ignore infra/.env` → ignored; `git ls-files` → not tracked. |
Delivery | rendered at container start from `$FREESWITCH_PASSWORD` | The committed artefact contains **no credential at all**, not even a weak placeholder. |
Rotation | trivially: change `.env`, restart | No rebuild, no config edit. |
Refusal | entrypoint rejects the stock value, the platform dev value, the `.env.example` placeholder, and anything under 16 characters | A copied `.env.example` fails loudly instead of silently weakening ESL. |
Defence in depth | `apply-inbound-acl=obd-dev-acl` | Even a loopback peer from an unexpected address is rejected. |
Fail-safe | `stop-on-bind-error=true` | Refuses to start rather than running with no listener. |

### The one control that failed silently

**CONFIRMED:** during Phase B the entrypoint was never executed, because the
Compose file mounted it without setting `entrypoint:`. The result was the
**stock** config file — `listen-ip ::` and the default credential — while the
container reported itself perfectly healthy. The hardening had been written,
mounted, and was simply not in effect.

Two lessons, both general:

* **A mounted file is not an applied file.** Assert the outcome, not the
  intent.
* **Health does not mean configured.** `UP` said the process was alive, not
  that it was safe.

The mitigations now in place: the entrypoint prints a banner, so its absence is
visible in the log; and the verification checks the *rendered* file's contents,
not the template.

### Where the credential physically exists at runtime

**CONFIRMED, and important for anyone copying files off this machine.** The
credential is present in three places, all of them inside the container or in
the git-ignored env file:

| Location | Why it is there | Git risk |
|---|---|---|
`infra/.env` | the source of truth for the value | ignored by `infra/.gitignore:1`; untracked |
the container's environment | passed in by Compose | not persisted to disk |
`/etc/freeswitch/autoload_configs/event_socket.conf.xml` | FreeSWITCH must know its own password; written mode `600` by the entrypoint | inside the container only |
`/var/log/freeswitch/freeswitch.xml.fsxml` | **the compiled configuration contains the rendered password** | bind-mounted to `infra/freeswitch/logs/`, which the root `.gitignore:12:logs/` ignores |

That last one is the one to be careful with. `freeswitch.xml.fsxml` is the
flattened, preprocessed configuration — invaluable for debugging (see
[03-CONFIGURATION.md §1](03-CONFIGURATION.md)), and it **contains the ESL
credential in plaintext**.

* **Never commit it.** It is git-ignored today; keep it that way.
* **Never attach it to a support ticket or a bug report** without redacting the
  password parameter.
* **Any backup of the log directory is a backup of the credential.** This is a
  normal property of the design — FreeSWITCH has to know its password — but it
  should be a conscious decision, not a surprise.
* It is currently readable by anything that can read the log directory. In
  production, treat that directory as secret-bearing.

---

## 3. SIP exposure

| Control | State | Reasoning |
|---|---|---|
SIP published to host | `127.0.0.1:5060`, `127.0.0.1:5080` (udp+tcp) | Loopback only. A LAN attacker cannot reach them. |
SIP reachable in Docker | container IP on `obd-telephony` | Any container on that network can. Acceptable: the network holds only project containers. Reassess before adding an untrusted container. |
| SIP authentication | `auth-calls=false`, `auth-subscriptions=true` | **Changed in Phase C.** Registration is now enabled, because the registration flow had to be observed. That is only safe because the stock directory was **replaced**: it shipped users 1000-1014 authenticating with a publicly known `default_password` from `vars.xml`. See ADR-005. |
TLS | not configured | 5061/5081 are not bound. Certificates are not an inherited problem if the feature is off. |
WebSocket | not configured | 5066/7443 are not bound (the stock internal profile binds both). |
ACL | `obd-dev-acl`, `default="deny"`, loopback + RFC1918 | Replaces the stock reliance on auto-generated lists, which inside a container derive from a single synthetic interface and are therefore accidental. |

### The inbound-registration decision, stated plainly

A registration-enabled profile with a stock password is worse than a
registration-disabled one. The correct order is:

```text
1. disable registration
2. REMOVE the stock directory users, so no known password remains
3. add real extensions with strong per-extension secrets, and a real ACL
4. pin challenge-realm, so a registration's identity does not depend on
   which network path reached the switch
5. enable registration             <-- Phase C is here, having done 1-4
6. add rate limiting and lockout    <-- still FUTURE
```

Skipping to step 5 is how open SIP servers end up on the internet being used to
make fraudulent calls.

**Phase C is at step 5, having completed steps 1-4.** Step 6 is the remaining
gap and is marked NOT YET TESTED / FUTURE in
[12-PRODUCTION-OPERATIONS.md](12-PRODUCTION-OPERATIONS.md). There is currently no
registration rate limiting or lockout on this profile.

### What Phase C changed here, and what it caught

Enabling registration was not a one-line change. Two distinct security-relevant
defects had to be closed first, and **both failed silently** - FreeSWITCH started,
reported itself healthy, and behaved in a way that looked like a network problem.

**1. The stock directory had to be removed, not merely avoided.**
The stock file defines users `1000`-`1014` whose password is a publicly known
value in `vars.xml`. Phase B had left them in place while registration was
disabled, which was safe. Enabling registration without removing them would have
accepted a `REGISTER` authenticated with a published credential.

Phase C hit a second problem at the same time: the stock directory also defines a
user called **`1001`**, colliding with the project's test extension, so a
correct password was answered `403`. Two files declaring the same domain is
legal, and the stock definition won.

Both are fixed by **owning the directory**: one file, one domain, two users,
replacing the stock one. An entrypoint guard now refuses to start if any other
user id appears, so this cannot regress silently.

**Verified afterwards:**

```text
user ids defined                -> 1 x 1001, 1 x 1002   (no stock users)
REGISTER as 1000 with the stock default password -> SIP/2.0 403 Forbidden
REGISTER as 1001 with the per-extension password -> SIP/2.0 200 OK
```

**2. A wrong root element can leave the stock ESL credential live.**
While building the test endpoints, an `event_socket.conf.xml` was written with
the root element `<config>` instead of `<configuration>`. mod_event_socket looks
for a child element literally named `configuration`, found nothing, and **kept
its compiled-in defaults - including the stock password**. The endpoint reported
itself healthy on ESL the whole time.

It was caught only because the entrypoint's own token check failed and refused to
start. That guard is the reason this was a startup failure rather than a
credential exposure.

The general lesson, and it is the same one as Phase B's entry 10:

> **A mounted file is not an applied file, and a healthy service is not a
> correctly configured one.** The only reliable check is behavioural - a bound
> port, a rejected stock credential, a value the service reports back.

### Rate limiting: the remaining gap

With registration now enabled, this profile accepts registration attempts
without limit or lockout. On a loopback-published port that is acceptable for a
development machine. It is **not** acceptable on any network where an untrusted
party can reach the SIP port, and it is recorded as a production blocker in
[12-PRODUCTION-OPERATIONS.md](12-PRODUCTION-OPERATIONS.md).

---

## 4. Published ports, and why each one exists


**Phase C additions** - the local SIP test endpoints, in their own project
| Host binding | Container | Protocol | Why it must exist | Why it is safe |
|---|---|---|---|---|
`127.0.0.1:8031` | 8021 | tcp | endpoint 1001 ESL, so its **own media counters** can be read | loopback only; strong fixture password from `infra/.env`; test-only container |
`127.0.0.1:8032` | 8021 | tcp | endpoint 1002 ESL, same reason | loopback only; same |
`127.0.0.1:5061` | 5060 | udp+tcp | endpoint 1001 SIP, for packet tracing if ever needed | loopback only; `auth-calls=false` |
`127.0.0.1:5062` | 5060 | udp+tcp | endpoint 1002 SIP, same | loopback only; `auth-calls=false` |
| Host binding | Container | Protocol | Why it must exist | Why it is safe |
|---|---|---|---|---|
`127.0.0.1:8021` | 8021 | tcp | ESL, for `fs_cli` and manual tooling, and a later host-run Java process | loopback only; credential-gated; full control gated behind authentication |
`127.0.0.1:5060` | 5060 | udp+tcp | softphone registration and local calls | loopback only; no authentication accepted, so nothing can register |
`127.0.0.1:5080` | 5080 | udp+tcp | the external profile, for the gateway leg | loopback only; `auth-calls=false` |
`127.0.0.1:30000-30099` | same | udp | RTP for a Windows softphone | loopback only; **100 ports, not the stock 16,384** |

**Explicitly not published on the platform switch:** 5061, 5081, 5066, 7443, 21, 2222. (Ports 5061 and 5062 *are* published, but only by the Phase C test endpoints, on loopback - see the table above.)
**Explicitly not bound inside the container either:** 5061, 5081, 5066, 7443,
21, 2222 — verified with `netstat`.

**Known, accepted:** 8081/8082 (mod_sofia's management HTTP) and 1337/udp
(mod_verto) are bound inside the container on the container IP, and are not
published. They are reachable only from `obd-telephony`. If that network ever
hosts an untrusted container, these should be revisited.

---

## 5. RTP exposure

RTP is the least interesting and the most easily over-exposed protocol. It
carries no credentials and no signalling; its only security property is that it
should not be an unnecessary open door.

| Consideration | This environment |
|---|---|
Stock range | 16384-32768 — 16,384 ports, and **commented out** in stock config so FreeSWITCH silently uses it |
Range here | 30000-30099 — 100 ports, set explicitly |
Published | only the 100, to `127.0.0.1` |
Container-to-container | **no published port needed at all** — media stays inside the Docker network |
Windows firewall | no inbound rule needed for the container-to-container path |

The general rule this encodes: **publish the narrowest range that works, and
publish nothing for traffic that never leaves the host boundary.**

---

## 6. Credentials inventory

| Credential | Where it lives | In Git? | Rotation |
|---|---|---|---|
ESL password | `infra/.env` → env var → rendered config | **No** | change `.env`, restart |
SIP extension passwords | **none exist yet** | n/a | Phase C must introduce per-extension secrets |
Gateway credentials | **none exist** (`register=false`, `Password no`) | n/a | when a real trunk is added |
Postgres/Redis/RustFS | `infra/.env` (pre-existing) | No | out of scope for this document |

**Rules this repository follows:**

1. No secret in any committed file, not even as a working default.
2. `.env.example` carries placeholders and an explanation of what each is for.
3. The entrypoint refuses known-bad values, so a copied example fails loudly.
4. If a secret is ever printed to a terminal, **rotate it**. Assume the
   transcript is permanent. This happened once during Phase B.
5. Never print a secret to verify it — verify its length, its occurrence count,
   and the absence of known-bad values.

---

## 7. Secrets handling, in practice

```text
Reading the ESL password without retyping it (Windows PowerShell):

  $pw = (Get-Content ..\infra\.env | Select-String '^FREESWITCH_PASSWORD=').ToString() `
        -replace '^FREESWITCH_PASSWORD=',''
  docker exec obd-freeswitch fs_cli -H 127.0.0.1 -P 8021 -p $pw -x "status"

Rotating it:

  generate 32 random bytes, base64url
  replace the FREESWITCH_PASSWORD line in infra/.env
  ⚠  RESTARTS SERVICE
  docker compose -f infra/docker-compose.freeswitch.yml up -d --force-recreate
```

The value never needs to be pasted into a terminal, a shell history file, a
screenshot or a chat message.

---

## 8. Logging risks

| Risk | Status here |
|---|---|
Secret in the FreeSWITCH log | **CONFIRMED hazard.** A secret written into a config *comment* is not a secret; it is published to every log shipper, backup and support bundle. See [13-TROUBLESHOOTING.md §1](13-TROUBLESHOOTING.md). The template now forbids this by construction. |
ESL auth failures logged | stock `log-auth-failures` is a *sofia* setting; ESL logs connection and auth events. With a strong credential and loopback binding, brute force is not a practical concern. Production should still watch for it. |
Call content in logs | SIP tracing (`siptrace`) logs messages, not audio. Media content is not logged. **NOT YET TESTED** with tracing enabled here. |
Caller/callee numbers in logs | **Yes** — SIP activity logs numbers. Treat FreeSWITCH logs as containing personal data. |
Verbose logging left on | A real risk. `loglevel=info` is the shipped value; tracing must be explicitly turned off. See [12-PRODUCTION-OPERATIONS.md §3](12-PRODUCTION-OPERATIONS.md). |
`uuid_dump` in a support bundle | dumps every channel variable, which can include numbers, headers and vendor data. Sanitise before sharing. |

---

## 9. Container-level considerations

| Consideration | Status |
|---|---|
Runs as root | **Yes — the upstream image's `USER freeswitch` is commented out.** Accepted for an isolated dev container on a private bridge network, and documented rather than silently accepted. Production should run non-root. |
Capabilities | Only `SYS_NICE`, which the image author recommends for RTP timing. The host kernel does not implement it; FreeSWITCH logs the failure and continues. |
Network | A dedicated `obd-telephony` bridge, not shared with the database/Redis project. |
Egress | Nothing in the environment *depends* on outbound traffic. The only outbound attempt is the stock STUN lookup, which fails harmlessly. |
Host filesystem | Only three mounts: config files (read-only), the media directory (read-only), and the log directory (read-write). No host paths are exposed beyond these. |
Image provenance | Pinned by tag, and the linux/amd64 digest was verified against the registry manifest (`sha256:8b55a39…`). Core is MPL-1.1; the image wrapper is GPL-3.0. |

---

## 10. What must change before this faces the internet

Explicitly out of scope for Phase B, and the reason Phase B is safe today:

```text
  [ ] SIP exposed publicly behind a firewall, with only the provider's
      address ranges permitted
  [ ] SIP registration enabled, with per-extension strong secrets,
      rate limiting and lockout
  [ ] A real carrier trunk, with credentials from a secret manager
  [ ] TLS, with certificates and a renewal process
  [ ] The RTP range managed by a SIP-aware firewall, not by Docker
  [ ] Container running as a non-root user
  [ ] ESL reachable only over a private management network, and audited
  [ ] Log shipping with retention, access control and redaction
  [ ] Rate limiting on registration, calls, and ESL
  [ ] Multiple instances with a documented failover model
  [ ] Secrets in a secret manager, not a file
  [ ] Alerting on the failure classes in the Phase H failure matrix
```

---

## 11. The single most important security habit

**Assert the control, do not assume it.**

Every security control in this environment was written, mounted, and —
in at least one case — silently not in effect. The controls that survived were
the ones that were *checked*: counting secret occurrences, reading the rendered
config rather than the template, listing actual listeners, and confirming a
host mapping is on loopback.

When you add a control, add the check with it, at the same time. A control
without a verification is a comment.
