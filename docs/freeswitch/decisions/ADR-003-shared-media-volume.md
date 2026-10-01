# ADR-003 — Shared media volume for application audio

## Status

**Accepted** and implemented in Phase B. The application side is wired in a
later phase; no Java change is required to do so.

## Context

The platform stores tenant audio assets in its own filesystem and hands
FreeSWITCH a **logical** storage reference, of the exact shape:

```text
audio/<tenantId>/<assetId>/<fileName>
```

That name is meaningful only to the application. FreeSWITCH, running in a
different filesystem — and, in the intended deployment, a different container —
cannot open it.

The platform already solved the *naming* problem: `MediaUriResolver` validates
the reference strictly (exactly three segments, a fixed prefix, two parseable
UUIDs, the tenant must match the caller, the asset must match, no traversal, no
backslash, no URL scheme) and converts it to an absolute POSIX path rooted at
`audio.storage.freeswitch-media-root`. The default root is
`/usr/share/freeswitch/sounds` — the FreeSWITCH convention.

What is missing is the thing that makes the path *exist* on FreeSWITCH's
filesystem. That is a deployment decision, not a code decision.

This was also the subject of a historical platform defect: before
`MediaUriResolver` existed, the raw logical reference was passed straight to
`uuid_broadcast`, FreeSWITCH resolved it against its own sound directory, and
playback silently failed in any real deployment.

## Decision

Bind-mount the application's audio directory into FreeSWITCH at a dedicated
path, and point the application's media root at it.

```text
  D:\work\agile\obd-platform\backend\data\audio      (host)
              │  Docker bind mount, READ-ONLY
              v
  /media/obd                                          (inside FreeSWITCH)
```

and the application is told, **without a Java code change**:

```text
SPRING_APPLICATION_JSON={"audio":{"storage":{"freeswitch-media-root":"/media/obd"}}}
```

or `--audio.storage.freeswitch-media-root=/media/obd`.

FreeSWITCH's own `/usr/share/freeswitch/sounds` is **not** shadowed.

## Alternatives

**A. Mount over `/usr/share/freeswitch/sounds`.**
*Rejected.* It appears simplest — the default root would then be correct with
no configuration. But it shadows the vendor sound tree, breaking the `moh`
hold-music package that both profiles reference via
`local_stream://moh`, and any stock prompt. A campaign environment that cannot
play hold music is broken in a way nobody notices until a call is on hold.

**B. Object storage (RustFS/S3) with FreeSWITCH fetching.**
*Rejected for this stage.* The repository already runs RustFS, so this is
tempting. It was explicitly deferred: it makes every media debugging session
require HTTP tooling, adds a network dependency to the audio path, and
introduces cache-expiry and eventual-consistency questions that have nothing to
do with validating FreeSWITCH. Revisit before production multi-host deployment.

**C. Copy files into the FreeSWITCH container at runtime.**
*Rejected.* Two copies, no single source of truth, and drift is guaranteed.
The application's database would stop describing reality.

**D. Use `nginx_httpfs` or a stream from a URL.**
*Rejected.* Adds a network hop, a protocol FreeSWITCH must be built with, and
latency and failure modes, for a single-host development environment.

**E. Have FreeSWITCH fetch from the application over HTTP.**
*Rejected for now.* The same objections as (B), and it makes FreeSWITCH depend
on the application being up.

## Why

A bind mount is the shortest path between "a file the application wrote" and "a
path FreeSWITCH can open", and it is trivially inspectable from both sides —
which matters enormously when a playback failure has to be diagnosed.

The application root must be **configurable rather than hard-coded**, and it
already is: `AudioStorageProperties.freeswitchMediaRoot`. That turns what would
otherwise be a code change into a launch-time property, which is the whole point
of the phase boundary that keeps the Java repository untouched.

Mounting at `/media/obd` rather than the sounds directory keeps two concerns
separate: the vendor's tree and the application's tree. It also makes the mount
obvious in a `docker inspect` listing, which is worth more than the saved line
of configuration.

## Consequences

**Easier:**

* no Java change to connect the application;
* media is inspectable from the host with a file browser;
* the path shape is directly verifiable inside the container;
* the mount is read-only, so FreeSWITCH physically cannot corrupt application
  data, and a stray write attempt fails loudly.

**Harder:**

* **single host only.** A bind mount is local to one machine. Multi-host
  deployment requires shared storage or object storage. This is the main
  limitation and must be addressed before scaling out.
* the application and FreeSWITCH must agree on the root string, and nothing
  enforces that except configuration. A mismatch produces a `PLAYBACK_ERROR`
  at campaign time, not a startup failure.
* **a missing mount was originally silent.** It is now a startup failure —
  the entrypoint checks for `/media/obd` and refuses to start. That check is
  the single most valuable line in the entrypoint.

**Operational impact for a production engineer.**

* Verify the mount before believing any playback result. If `/media/obd` is
  absent or empty, every campaign fails at playback, not at dial.
* FreeSWITCH logs and the application's media records must be kept in step.
  A file present in the database but absent on disk is a `PLAYBACK_ERROR`, and
  the error header is where you look first.
* The read-only mount is load-bearing for safety, not an optimisation. If
  recording is ever added, it needs a **separate** write path, not a relaxed
  mode on this one.

## Future Reconsideration

Revisit when:

* the platform is deployed on more than one application host — shared storage
  or object storage becomes mandatory;
* call recording is added, which needs a writable media path with its own
  lifecycle and retention;
* audio assets exceed what is sensible on local disk;
* CDN or signed-URL delivery is required for tenant isolation at scale.
