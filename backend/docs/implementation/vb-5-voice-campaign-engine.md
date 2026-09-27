```text
                    ┌──────────────┐
                    │   Campaign   │
                    └──────┬───────┘
                           │
             ┌─────────────┼─────────────┐
             ↓             ↓             ↓
          DNID         Audio Asset     TTS Template
             │             │             │
        ownership       approval       approval
        assignment      lifecycle      lifecycle
        availability    storage        visibility
             │             │             │
             └─────────────┼─────────────┘
                           ↓
                  Campaign Validation
                           ↓
                   Campaign Execution
                           ↓
                     VB-5 Scheduler
```

So I would insert a **resource-governance phase before the campaign engine**.

# Revised roadmap

```text
VB-0   Voice Routing + Capacity                 ✅
VB-1   PLAYFILE                                 ✅
VB-2   DTMF                                     ✅
VB-3   CONNECT_BY_AGENT                         ✅
VB-4   Contact Center                           ✅
       ├── Agent
       ├── Queue
       ├── ACD
       ├── Inbound
       ├── Agent Outbound
       └── Hardening

VB-5   Voice Resource & Campaign Foundation     ← NEXT
       ├── 5A Audio Asset
       ├── 5B TTS Template
       ├── 5C DID Lifecycle & Assignment
       ├── 5D Campaign Resource Validation
       └── 5E Hardening

VB-6   Campaign Execution Engine
       ├── Campaign Work
       ├── Scheduler
       ├── Progressive
       ├── Preview
       └── Predictive later
```

I would **not necessarily call the entire thing VB-5 yet** until we inspect the existing campaign/audio/TTS/DID modules. The first coding-agent phase should be an **inspection + dependency map**, followed by small implementation phases.

---

# 1. Audio Asset needs to become a real resource

Right now you effectively have metadata, but the actual audio object needs to become part of the system.

The desired model is:

```text
Tenant
   │
   └── AudioAsset
          ├── name
          ├── description
          ├── original filename
          ├── media type
          ├── duration
          ├── size
          ├── sample rate
          ├── channels
          ├── format
          ├── storage key
          ├── storage URL/reference
          ├── status
          ├── uploadedBy
          ├── approvedBy
          └── timestamps
```

But **do not couple the domain to local filesystem storage**.

Use something conceptually like:

```java
interface AudioAssetStorage {
    StoredObject upload(...);
    void delete(...);
    ResourceReference get(...);
}
```

Then:

```text
AudioAssetService
       │
       └── AudioAssetStorage
              ├── LocalAudioAssetStorage
              └── S3AudioAssetStorage      future
```

The database should store a **storage key/object reference**, not make the local filesystem path part of the domain contract.

For example:

```text
audio/
  tenant/{tenantId}/assets/{assetId}/original.wav
```

Later:

```text
S3 bucket
  audio/
    tenant/{tenantId}/assets/{assetId}/original.wav
```

The application shouldn't need to change its domain model.

---

# 2. WAV + MP3

Yes, I would support **both WAV and MP3 at the product level**, provided the actual FreeSWITCH/media path handles them reliably.

But there is an important distinction:

> **Upload format and playback format do not have to be the same.**

For example:

```text
User uploads
   ├── WAV
   └── MP3
        ↓
Backend validation
        ↓
Metadata extraction
        ↓
Optional normalization/transcoding
        ↓
Canonical playback asset
        ↓
FreeSWITCH
```

The backend should therefore inspect the actual file rather than trusting:

```text
filename = something.wav
Content-Type = audio/wav
```

because those can be spoofed.

Metadata should be derived from the actual media.

Potentially:

```text
format
codec
sample rate
channels
bitrate
duration
size
```

And the system should define the **canonical FreeSWITCH-compatible playback requirements** rather than scattering media assumptions throughout campaign code.

If transcoding is introduced, keep it behind the audio service:

```text
AudioAsset
     ↓
AudioProcessingService
     ↓
CanonicalPlaybackAsset
```

not inside `CampaignService` or `FreeSwitchVoiceMediaController`.

---

# 3. Audio approval model

Your permission model is clear and should become explicit.

### Super Admin

Can:

```text
UPLOAD
VIEW
PLAY
APPROVE
REJECT
DELETE
```

### Tenant/Admin

Can:

```text
UPLOAD
VIEW
PLAY
DELETE
```

Cannot:

```text
APPROVE
REJECT
```

### Reseller

Based on your stated rule, reseller should likewise **not approve/reject tenant audio**.

The important part is that approval is **not just a UI restriction**.

The backend must enforce:

```text
approveAudio()
       ↓
SUPER_ADMIN only
```

and:

```text
Campaign creation
       ↓
audio.status == APPROVED
```

The UI should hide rejected/unapproved assets from selection, but the API must independently reject them.

---

# 4. Audio lifecycle

I would make the lifecycle explicit rather than treating approval as a nullable field.

Something along the lines of:

```text
UPLOADING
    ↓
PROCESSING
    ↓
PENDING_APPROVAL
    ├──→ APPROVED
    │       ↓
    │    selectable
    │
    └──→ REJECTED
            ↓
         not selectable

APPROVED
    ↓
SOFT_DELETED
```

The exact states should be determined after inspecting the existing schema.

The important invariant is:

```text
Only APPROVED + non-deleted audio
can be selected by a campaign.
```

And **runtime validation must repeat that rule**.

---

# 5. DID needs a proper ownership/assignment model

This is actually more important than it may initially appear.

Your hierarchy is:

```text
SUPER_ADMIN
     │
     ├── directly owns/assigns DID → Tenant
     │
     └── assigns DID → Reseller
                         │
                         └── assigns DID → Tenant
```

The provenance must remain.

For example:

```text
DID: +91XXXXXXXXXX

Provisioned by: SUPER_ADMIN

Current owner:
    Reseller A

Assigned tenant:
    Tenant B
```

That lets billing later answer:

```text
Who should Super Admin bill?
        ↓
Reseller A

Who should Reseller bill?
        ↓
Tenant B
```

Even if the DID ultimately belongs to the tenant operationally, the **commercial provenance must not disappear**.

---

# 6. DID lifecycle

This should be explicit as well.

For example:

```text
AVAILABLE
    ↓
ASSIGNED
    ↓
REVOKED
    ↓
AVAILABLE
```

or whatever states the existing domain already defines.

The critical invariant you described is:

> A DID already assigned cannot simply be assigned again.

Therefore:

```text
assign(DID, TenantB)
```

must fail if:

```text
DID.status != AVAILABLE
```

The correct sequence is:

```text
ASSIGNED
   ↓
REVOKE
   ↓
AVAILABLE
   ↓
ASSIGN
```

And this needs to be enforced **transactionally**, not only through frontend validation.

Two simultaneous requests must not be able to assign the same DID.

That means this belongs in the same family of PostgreSQL concurrency guarantees we've already used for agents, queues and reservations.

---

# 7. DID visibility

The hierarchy needs to be enforced in queries.

### Super Admin

Can see:

```text
all DIDs
```

and assign:

```text
→ reseller
→ reseller tenant
→ direct tenant
```

### Reseller

Can see/use:

```text
DIDs assigned to reseller
        +
DIDs assigned down to its tenants
```

but cannot see unrelated reseller/tenant DIDs.

### Tenant Admin

Can see/use:

```text
DIDs assigned to its tenant
```

Not:

```text
another tenant's DID
reseller-owned unassigned DID
another reseller's DID
```

And campaign validation must verify this again.

---

# 8. TTS should follow the same governance model

I agree with your **Global vs Tenant** distinction.

I would model:

```text
TtsTemplate
   │
   ├── scope = GLOBAL
   │
   └── scope = TENANT
```

### Global template

Created/managed by:

```text
SUPER_ADMIN
```

After approval:

```text
GLOBAL + APPROVED
```

it becomes available to eligible tenants.

Conceptually:

```text
Super Admin
     ↓
Global TTS Template
     ↓
APPROVED
     ↓
Tenant A ─┐
Tenant B ─┼── can select
Tenant C ─┘
```

### Tenant template

```text
Tenant Admin
     ↓
Create
     ↓
PENDING_APPROVAL
     ↓
Super Admin
     ↓
APPROVED
     ↓
Tenant can use
```

Before approval:

```text
VISIBLE = maybe yes
SELECTABLE = NO
CAMPAIGN USABLE = NO
```

That distinction is important.

---

# 9. Campaign validation becomes a first-class service

This is the biggest architectural change I would make.

Don't put this logic inside:

```java
CampaignService
```

as a giant collection of checks.

Create something conceptually like:

```java
CampaignResourceValidationService
```

or, after inspecting the existing code, extend an existing validation abstraction.

It should validate:

```text
Campaign
   │
   ├── DID
   │     ├── exists
   │     ├── tenant-visible
   │     ├── assigned
   │     ├── active
   │     └── usable
   │
   ├── Audio
   │     ├── exists
   │     ├── tenant-visible
   │     ├── APPROVED
   │     ├── not deleted
   │     └── playable
   │
   └── TTS
         ├── exists
         ├── global OR tenant-owned
         ├── APPROVED
         ├── not deleted
         └── usable
```

---

# 10. And validate twice

You specifically mentioned this, and I strongly agree.

### Validation #1 — Campaign creation/update

```text
POST /campaigns

        ↓

validate resources

        ↓

DID ✓
Audio ✓
TTS ✓
Tenant ownership ✓
Approval ✓
Lifecycle ✓

        ↓

campaign saved
```

But that is **not enough**.

Because resources can change after the campaign is created.

Example:

```text
10:00
Campaign created
Audio = APPROVED

10:30
Super Admin rejects/deletes audio

11:00
Campaign starts
```

Therefore:

### Validation #2 — Runtime execution

```text
Campaign starts
       ↓
resolve campaign resources
       ↓
validate current state
       ↓
DID still usable?
Audio still approved?
TTS still approved?
Tenant still owns resource?
Resource still available?
       ↓
YES → execute
NO  → don't dial
```

This prevents stale configuration from bypassing current business rules.

---

# 11. This also affects VB-1 and VB-2

This is why I would **not treat this as merely a future campaign feature**.

We already have:

```text
VB-1 PLAYFILE
VB-2 DTMF
```

and those currently have media/configuration boundaries.

The new architecture should establish a canonical:

```text
AudioAsset
     ↓
AudioAssetService
     ↓
PlaybackResource
     ↓
FreeSwitchVoiceMediaController
```

rather than letting campaign code directly deal with arbitrary files.

Similarly, future TTS should eventually become:

```text
TtsTemplate
     ↓
TtsProcessingService
     ↓
AudioResource
     ↓
FreeSWITCH
```

This gives us a clean boundary for future AI voice as well.

---

# 12. The dependency graph becomes much cleaner

Ultimately I would like the architecture to look roughly like:

```text
                    ┌───────────────┐
                    │    Campaign   │
                    └───────┬───────┘
                            │
                  Resource Validation
                            │
          ┌─────────────────┼─────────────────┐
          ↓                 ↓                 ↓
       DID/CLI          Audio Asset       TTS Template
          │                 │                 │
     Assignment          Approval           Approval
     Ownership           Storage            Visibility
     Lifecycle           Processing         Scope
          │                 │                 │
          └─────────────────┼─────────────────┘
                            ↓
                     Campaign Execution
                            ↓
                   Voice Routing/Capacity
                            ↓
                       CallSession
                            ↓
                        CallLeg(s)
                            ↓
                        FreeSWITCH
```

That is a much stronger foundation for the eventual campaign engine.

---

# What I recommend we do next

Instead of immediately writing the **VB-5 Campaign Execution prompt**, I would make the next phase:

## **VB-5 — Voice Resource Governance & Campaign Dependency Hardening**

And split it incrementally:

### **VB-5A — Inspection & Resource Architecture**

Inspect the actual existing implementation of:

* Audio entities/services/controllers/repositories
* campaign `PLAYFILE` configuration
* TTS entities/services if present
* DID entities/services/assignment
* tenant/reseller ownership
* existing approval/status enums
* storage abstractions
* campaign validation
* FreeSWITCH media boundary
* existing migrations
* existing authorization rules
* current architecture cycles

**No implementation until the inspection is complete.**

### **VB-5B — Audio Assets**

Actual upload + storage abstraction + metadata extraction + lifecycle + approval + tenant isolation + soft delete + playback + campaign usability.

### **VB-5C — TTS Templates**

Global/tenant scope + approval + ownership + lifecycle + campaign usability.

### **VB-5D — DID Assignment & Commercial Ownership**

Super Admin → Reseller → Tenant hierarchy, direct Super Admin → Tenant assignment, lifecycle, exclusive assignment, revocation, provenance/billing ownership.

### **VB-5E — Campaign Resource Validation**

Unify:

```text
Campaign
 ├── DID
 ├── Audio
 └── TTS
```

with both:

```text
CREATE/UPDATE validation
```

and:

```text
RUNTIME execution validation
```

### **VB-5F — Hardening**

Then do exactly what worked for VB-4F:

```text
tenant isolation
ownership
authorization
state machines
concurrency
PostgreSQL constraints
idempotency
stale resources
runtime validation
regression
architecture
```

Only after **VB-5F** should we begin the actual **Campaign Execution/Scheduler** milestone.

That sequencing avoids building a campaign scheduler on top of resources whose ownership, approval, storage, and lifecycle semantics are still ambiguous. It also gives us a much cleaner foundation for **PLAYFILE → TTS → future AI-generated audio**, without tying any of them directly to local storage or FreeSWITCH internals.
