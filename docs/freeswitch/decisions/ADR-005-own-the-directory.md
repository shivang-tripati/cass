# ADR-005 - Own the FreeSWITCH directory completely

## Status

**Accepted and implemented** in Phase C. This ADR closes a security gap that
Phase B deliberately left open, and which Phase C had to close in order to test
registration at all.

## Context

Phase B disabled SIP registration on the internal profile:

```xml
<param name="auth-subscriptions" value="false"/>
```

The reason is recorded in the config and in the Phase B report: the stock
FreeSWITCH directory defines users `1000`-`1014` whose password is set in
`vars.xml` to a **publicly known value**, and

```xml
<X-PRE-PROCESS cmd="set" data="default_password=1234"/>
```

Enabling registration against that directory would have accepted a `REGISTER`
authenticated with a published credential. Phase B's judgement was that
refusing to accept *any* registration is better than accepting one with a known
password.

Phase C needs to observe `REGISTER` / `401` / digest / `200 OK`. That means
enabling registration. Which means the stock users must be dealt with first.

## The collision

Beyond the security issue, the stock directory caused a second, subtler failure.
The stock file defines a user called **`1001`**, in the same domain as the
project's test extensions. FreeSWITCH's directory lookup returns one match, and
the **stock** definition won.

So a `REGISTER` for extension `1001` carrying the **correct** per-extension
password was answered:

```text
REGISTER (no auth)          -> SIP/2.0 401 Unauthorized
REGISTER (correct digest)   -> SIP/2.0 403 Forbidden
```

and the endpoint's gateway logged a growing failure count:

```text
[ERR] sofia_reg.c:2661 platform-switch Failed Registration with status
      Forbidden [403]. failure #6
```

This is actively misleading, because `403` on an authenticated retry normally
means "wrong password". Here it did not even mean "wrong user" - it meant the
right user id was defined **twice** and the wrong definition was found.

FreeSWITCH's own log is what settles it:

```text
[WARNING] sofia_reg.c:3210 Can't find user [1001@127.0.0.1] from 172.25.0.1
 You must define a domain called '127.0.0.1' in your directory and add a user
 with the id="1001" attribute
```

## A related defect: the realm must be pinned

With the stock `challenge-realm=auto_to`, the realm is taken from the `To`
header - that is, from whatever address the client dialled. A host client is
challenged for realm `127.0.0.1`; a container is challenged for realm
`172.25.0.2`.

FreeSWITCH files registrations as `user@realm`, but the dialplan looks users up
by a different key. The result is a registration that authenticates, a user that
exists, and a call that fails:

```text
Processing 1001 <1001>->1002 in context default
bridge(user/1002@172.25.0.2)
Cannot create outgoing channel of type [error] cause: [USER_NOT_REGISTERED]
```

## Decision

**The project owns the entire directory.** One file, one domain, two users,
rendered at container start from environment variables.

`infra/freeswitch/conf/directory/default.xml.in` is the whole directory, and it
**replaces** the image's `default.xml` rather than being added beside it. The
entrypoint renders it to `/etc/freeswitch/directory/default.xml`, which
`freeswitch.xml` picks up through its `directory/*.xml` include.

The domain's `dial-string` parameter is present, because without it
`user/<ext>` cannot resolve a bridge target and calls answer with no audio
(troubleshooting entry 16).

**`challenge-realm` is pinned** to `$${domain}` on the internal profile, so
authentication, the registration key and the dialplan's user lookup all agree on
one value.

**An entrypoint guard refuses to start** if any user id other than 1001/1002 is
present, so the stock users cannot silently return through a future image, a
stray mount, or an edit.

## Alternatives considered

**Keep the stock directory and use extension numbers it does not define** (for
example 2001/2002). Rejected: the stock users with a published password would
still be present and still registrable the moment `auth-subscriptions` is
enabled. It treats the symptom, not the exposure. Numbers outside the shipped
range are also fragile - a future image may define them.

**Mount the stock file read-only over the top of it.** Rejected on the same
ground: the stock users remain defined in the file, just not read. It works only
because of a load-order accident, and nothing documents the accident.

**Disable registration again and test it some other way.** Rejected: registration
cannot be observed without enabling it, and it is a core part of the platform's
operation.

**Filter the stock users with `apply-inbound-acl` or a realm restriction.**
Rejected: an ACL controls reachability, not authentication. The users would
still authenticate.

## Consequences

**Good**

- The known-password users are gone, so registration can be enabled safely.
- No extension collision is possible.
- One realm for all clients, so host and in-network endpoints behave identically.
- The directory is small enough to read in full, and its purpose is obvious.

**Bad, and accepted**

- Provisioning is now this project's responsibility. Adding an extension means
  editing the directory template. That is the correct trade for a test
  environment, and it is exactly the kind of explicitness production wants too.
- The entrypoint guard has to be kept in step with the template. It is
  deliberately narrow (it excludes 1001 and 1002 explicitly) so that a correct
  configuration is never refused.

**Neutral**

- `vars.xml` still contains the stock `default_password`, but with no user
  referencing it, it is inert.

## Verification

```text
# exactly one definition per id, and no stock users
docker exec obd-freeswitch sh -c \
  grep -oE 'user id=.[0-9]+' /etc/freeswitch/directory/default.xml
# -> 1 x 1001, 1 x 1002

# the stock credential is rejected
REGISTER as 1000 with the stock default password -> SIP/2.0 403 Forbidden

# the real extensions authenticate
REGISTER as 1001 with the per-extension password -> SIP/2.0 200 OK

# one realm, pinned
fs_cli -x "sofia status profile internal" | grep -i challenge
# -> Challenge Realm   172.25.0.2
```

## Production relevance

This is not a test-only concern. In production:

* Any provisioning that reuses extension numbers shipped by an image, package or
  PBX base **inherits those credentials**.
* `challenge-realm=auto_to` produces a switch that authenticates subscribers,
  stores registrations under keys nothing looks up, and reports every call as
  `USER_NOT_REGISTERED` - a fault that reads like a carrier problem.
* Shipping a directory full of accounts with published default passwords, in an
  image that is publicly available, is a standing exposure the moment
  registration is enabled.
