# ADR-002 — Dedicated ESL security configuration and credential handling

## Status

**Accepted** and implemented in Phase B.

## Context

The platform's Java client authenticates to FreeSWITCH over TCP 8021 and uses
that connection for both commands and events. An ESL credential is not a
read-only status token: it grants the ability to place calls, terminate live
calls, bridge arbitrary channels, and play audio. It is, for practical
purposes, shell access to the telephony engine.

The stock FreeSWITCH configuration — read directly out of the chosen image —
is:

```xml
<param name="listen-ip" value="::"/>
<param name="listen-port" value="8021"/>
<param name="password" value="ClueCon"/>
```

`ClueCon` is published in FreeSWITCH's own documentation. `::` is every
interface. The platform's own committed development default is `fs-password`.
Any of these, on a reachable network, is an open switch.

There was also no precedent in this repository: no existing infrastructure
component had a secret of its own, so there was no established pattern to
follow.

## Decision

Four controls, implemented together.

**1. Bind to loopback, publish to loopback.**

```xml
<param name="listen-ip" value="127.0.0.1"/>
<param name="apply-inbound-acl" value="obd-dev-acl"/>
<param name="stop-on-bind-error" value="true"/>
```

Docker publishes `127.0.0.1:8021:8021`. ESL is reachable from the Windows
host and from the `obd-telephony` Docker network, and from nowhere else.

**2. The credential is generated locally, stored in a git-ignored file, and
rendered at container start.**

```text
  32 random bytes -> base64url -> infra/.env (git-ignored)
      -> $FREESWITCH_PASSWORD  ->  entrypoint renders it into
      -> /etc/freeswitch/autoload_configs/event_socket.conf.xml  (mode 600)
```

The committed artefact is a **template** containing a substitution token, not
a config file containing a password. This is deliberate: committing a config
file with a placeholder that FreeSWITCH actually reads would make the
placeholder a working password.

**3. The entrypoint refuses known-bad values.** `ClueCon`, `cluecon`,
`fs-password`, `1234`, the literal `.env.example` placeholder, and anything
under 16 characters are all rejected with a fatal error. A copied
`.env.example` therefore fails loudly instead of silently weakening ESL.

**4. The control is asserted, not assumed.** The rendered file is read and
checked: the live secret occurs exactly once (the password parameter), and the
stock and platform-default credentials occur zero times.

## Alternatives

**A. Commit a `event_socket.conf.xml` with a placeholder password.**
*Rejected.* FreeSWITCH reads the committed file directly, so the placeholder
becomes a working credential. Anyone who clones the repository inherits it.

**B. Commit a real-looking but "development only" password.**
*Rejected.* It ends up in git history permanently, and it is exactly the
`fs-password` mistake already present in the platform's YAML.

**C. Mount a secrets file produced by an external tool** (Docker secrets,
Vault agent).
*Rejected for Phase B.* Those mechanisms target orchestration platforms this
project has explicitly ruled out. Revisit if production is ever containerised
with an orchestrator.

**D. Bind ESL to `0.0.0.0` and rely on the network being private.**
*Rejected.* "The network is private" is an assumption that fails the moment a
second network, a VPN, or a CI runner is attached.

**E. Use the host's SSH keys or TLS client certificates for ESL.**
*Rejected as over-engineering for a loopback-bound development listener, and
not supported by `mod_event_socket`'s simple auth model. Appropriate only if
ESL is ever exposed beyond a management network.

## Why

The threat is not hypothetical for this project: **the hardening was written,
mounted, and silently not in effect** during Phase B, because the Compose file
mounted the entrypoint without setting `entrypoint:`. The container was healthy
throughout. That single experience is the strongest argument for control (4) —
asserting the outcome — and for control (3), which turns a misconfiguration into
a loud failure instead of a quiet weakness.

The credential-not-in-git decision also has direct evidence behind it. An
earlier draft of the template mentioned the substitution token inside a
comment; the entrypoint's `sed` replaced **every** occurrence and wrote the live
secret into the rendered config as prose. It was then printed by a verification
script and appeared in a session transcript. The credential was rotated. See
[../13-TROUBLESHOOTING.md §1](../13-TROUBLESHOOTING.md).

## Consequences

**Easier:**

* rotation is one line in `infra/.env` and one recreate;
* there is no secret anywhere in the repository, so the repository can be
  shared, archived and mirrored without a secret-handling process;
* a misconfiguration fails at startup with a clear message;
* a future containerised Java application connects to `freeswitch:8021` on the
  Docker network with no published port involved.

**Harder:**

* the credential exists in three places at runtime (env var, rendered config,
  and the process command line when `fs_cli` is used). All are inside the
  container or the git-ignored `.env`; none is in Git.
* the template has an editing rule that is easy to break: the substitution
  token must appear **exactly once**. The rule is documented in the template's
  own header.
* a healthcheck that needs the password must be written carefully — the shipped
  one passes it as an argument inside the container, where that is acceptable.

**Operational impact for a production engineer.**

* ESL is full control. An ESL credential belongs in a secret manager, must be
  rotatable, and its use should be auditable.
* To rotate: update the secret store, restart or reload the module, and
  reconnect every client. In this platform, Java reconnects within about
  60 seconds.
* The single most important habit: **after any change to ESL security, verify
  the rendered configuration**, not the template. The template is intent; the
  rendered file is fact.

## Future Reconsideration

Revisit when:

* ESL must be reachable beyond the host — then move to a management network,
  mTLS, or a brokered interface, and re-audit whether the Java client can
  support it;
* the platform is deployed with an orchestrator that provides real secret
  management;
* more than one application instance connects simultaneously, at which point
  credential distribution and rotation across instances needs designing.
