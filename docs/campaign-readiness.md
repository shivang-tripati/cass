| Capability | Current state | What I recommend |
|---|---|---|
| PLAYFILE | Supported | Expand configuration |
| DTMF | Supported | Expand into configurable IVR behavior |
| CONNECT_BY_AGENT | Supported | Use existing agent foundation |
| MISSED_CALL | **Missing** | Add as 4th campaign type |
| DNID/DID selection | Supported | Keep |
| Audio / TTS | Supported | Keep resource-reference model |
| Retry max attempts | Supported, basic | Expand by failure reason |
| Retry interval | Supported, fixed | Expand per retry rule |
| Retry on NO ANSWER/BUSY/etc. | **Not sufficiently modeled** | Add explicit retry rules |
| Daily max calls/contact | **Not currently supported as global policy** | Add platform/tenant campaign safety policy |
| Contact group | Supported | Keep as reusable source |
| Direct 1/2/10/100 contacts | **Missing** | Add inline target source |
| CSV/Excel during campaign creation | **Missing** | Add campaign-import source |
| Very large lists | Existing contact/group architecture | Keep asynchronous bulk ingestion |
| Invalid DTMF audio | **Missing as campaign configuration** | Add |
| No-DTMF audio | **Missing as campaign configuration** | Add |
| Input retry | **Missing** | Add |
| Wait timeout | Basic DTMF timeout exists | Expose/configure |
| Multi-level IVR | **Missing** | Add reusable IVR tree |
| Agent assignment | Supported for CONNECT_BY_AGENT | Make campaign-specific |
| Agent deactivation protection | Existing agent foundation | Preserve/inforce |
| Webhooks | Configuration concept exists | Formalize event subscriptions |
| Hide destination number in reports | **Missing** | Add reporting privacy setting |
| Whitelist calling | Existing phone-list architecture | Make campaign policy explicit |
| Max call duration | **Missing/needs modeling** | Add |
| Scheduler | Not yet | Later |
| Execution/pacing | Not yet | Later |



UX should look approximately like:
Create Campaign
────────────────────────────────────

1. Campaign
   Name
   Campaign Type
   Description

2. Audience (for each type number/leads must be save as contact entity/table  as we consider any callable number/lead as contact record so later provider detail history like phone/contact in mobile phones)
   ○ Existing Contact Group
   ○ Paste Numbers
   ○ Upload CSV / Excel

3. Calling
   DNID / Caller ID
   Calling policy
   Max calls per contact/day (must be in 1 <= call <= 3)
   Max call duration
   Whitelist/DND behavior

4. Campaign Content
   Type-specific configuration

5. Retry & Safety
   Retry rules
   Daily contact limit
   Calling window

6. Schedule & Webhooks
   Schedule
   Timezone
   Webhook events

             [Save Draft]
             [Create Campaign]


And importantly:
Don't force every user through every section.
For example:
PLAYFILE
    Campaign
    Audience
    Calling
    Content
    Retry & Safety
    Schedule

while:
DTMF
    Campaign
    Audience
    Calling
    DTMF / IVR
    Retry & Safety
    Schedule

and:
CONNECT_BY_AGENT
    Campaign
    Audience
    Calling
    Agent Configuration
    Retry & Safety
    Schedule

and:
MISSED_CALL
    Campaign
    Audience
    Calling
    Retry & Safety
    Schedule

That keeps campaign creation simple.


3. Audience selection needs to change
Currently the conceptual flow is:
Create Contact Group
       ↓
Upload contacts
       ↓
Create Campaign
       ↓
Select Contact Group

Instead:
Audience/contact Source

○ Contact Group
○ Paste Numbers
○ Upload File


Small test campaign
User wants:
9876543210
9876543211
9876543212

They should simply paste:
9876543210
9876543211
9876543212

and click: Add 3 contacts, No group required.


10–100 contacts
Same thing.
Paste numbers
     ↓
Normalize
     ↓
Validate
     ↓
Show preview
     ↓
Create campaign


1,000–1,00,000
Upload CSV/XLSX
       ↓
Upload/parse asynchronously
       ↓
Validation
       ↓
Deduplication
       ↓
Preview
       ↓
Import

50 lakh contacts
Do not load them into the campaign creation request.
Instead:
File
 ↓
Object storage
 ↓
Import job
 ↓
Streaming/chunk processing
 ↓
Contact/lead records
 ↓
Campaign audience



The UI remains the same.
This gives us:
1 contact
10 contacts
100 contacts
10,000 contacts
50 lakh contacts

without creating five different product experiences.

4. I would introduce an AudienceSource
Rather than making contactGroupId the central concept:
Campaign
   |
   +-- AudienceSource
          |
          +-- CONTACT_GROUP
          +-- INLINE
          +-- FILE_IMPORT

Conceptually:
enum CampaignAudienceType {
    CONTACT_GROUP,
    INLINE,
    FILE_IMPORT
}

Then:
CONTACT_GROUP
    contactGroupId

INLINE
    campaign audience entries

FILE_IMPORT
    importJobId

This is much cleaner than:
contactGroupId nullable
contacts nullable
file nullable
...

Important
For large datasets, the campaign should not store millions of numbers in campaigns.
The campaign references the audience/import.

5. Global contact protection: yes, we should add it
Your requirement:
call a contact number only 3 times in a day, not more than that

I would not make this merely a campaign setting.
It should be a global safety policy, with campaign configuration allowed to be stricter but never weaker.
For example:
Platform policy
MAX_ATTEMPTS_PER_CONTACT_PER_DAY = 3

Then campaign says:
Maximum attempts per contact/day
[ 2 ]

Valid.
But:
Campaign says 10

must become:
Effective limit = min(campaignLimit, globalLimit)
                       ↓
                       3

Even better:
Global Policy
--------------
Maximum contact attempts/day: 3

Campaign
--------
Maximum contact attempts/day: 2

Effective
----------
2

This prevents an accidental campaign configuration from bypassing the safety rule.

6. This must be enforced at dial time
Not merely when campaign is created.
Because two campaigns could simultaneously attempt:
Campaign A → 2 calls
Campaign B → 2 calls

and the contact could accidentally receive 4 calls.
So eventually the admission path needs something like:
Contact
   ↓
Global policy
   ↓
Campaign policy
   ↓
Attempts already made today
   ↓
Can dial?

And the final decision needs to be concurrency-safe.
Conceptually:
attempts_today < effective_limit

must be enforced atomically.
This belongs in the call eligibility / campaign execution boundary, not only in campaign CRUD.

Retry configuration: user-facing configuration to:
Retry Policy

☑ Enable retry

Retry rules:

NO ANSWER
  ☑ Retry
  Max retries: 2
  Delay: 05:00

BUSY
  ☑ Retry
  Max retries: 2
  Delay: 10:00

FAILED
  ☑ Retry
  Max retries: 1
  Delay: 15:00

SWITCHED OFF
  ☑ Retry
  Max retries: 2
  Delay: 30:00

NOT REACHABLE
  ☑ Retry
  Max retries: 2
  Delay: 30:00

HANGUP
  ☑ Retry
  Max retries: 1
  Delay: 05:00


8. Don't model retry as only "max attempts", instead 
RetryPolicy
   |
   +-- NO_ANSWER
   +-- BUSY
   +-- FAILED
   +-- SWITCHED_OFF
   +-- NOT_REACHABLE
   +-- HANGUP

Each rule:
RetryRule
-----------
enabled
maxRetries
delay

Potentially later:
retry window
allowed days
backoff strategy

9. PLAYFILE configuration
Don't just have: audioAssetId, for every future use. Introduce semantic content slots.
For example:
PLAYFILE

Welcome / Primary Audio
[ Welcome.wav ]

No Response Audio
[ NoResponse.wav ]

Invalid Response Audio
[ Invalid.wav ]

However, for PLAYFILE specifically, no response/invalid response doesn't naturally apply unless there is an interaction stage.  So don't expose irrelevant fields.
The configuration must be type-aware.

10. DTMF should become a real IVR configuration
This is where your product becomes much more powerful. Instead of: {"expected": "1" }

the user should see:

DTMF / IVR

Welcome Audio
[ Press1toContinue.wav ]

Input wait time
[ 10 seconds ]

Valid inputs
[ 1 ] [ 2 ] [ 3 ]

Invalid Response Audio
[ InvalidOption.wav ]

No Response Audio
[ NoInput.wav ]

Retry on invalid input
[✓]

Invalid input retries
[ 2 ]

Retry on no input
[✓]

No-input retries
[ 2 ]


11. Separate "invalid input" and "no input"
No response
Audio played
     ↓
wait 10 seconds
     ↓
nothing pressed
     ↓
No DTMF

Invalid response
Audio played
     ↓
user presses 9
     ↓
9 isn't valid
     ↓
INVALID INPUT

They should have separate:
Audio
Retry count
Behavior


12. Multi-level IVR should NOT be embedded entirely inside Campaign
IVR Tree
   |
   +-- Node
       |
       +-- Prompt
       +-- Input
       +-- Action
       +-- Next Node

Campaign merely selects: ivrTreeId

Example:
Customer Support IVR

START
 |
 +-- 1 → Sales
 |       |
 |       +-- 1 → New Orders
 |       +-- 2 → Existing Orders
 |
 +-- 2 → Support
 |       |
 |       +-- 1 → Technical
 |       +-- 2 → Billing
 |
 +-- 9 → Agent

Then campaign configuration becomes:
IVR
[ Customer Support IVR ▼ ]

[Create new IVR]
This makes IVR reusable.


13. CONNECT_BY_AGENT

Agent routing

○ Specific agents
○ Agent group / queue

Agents:
☑ Agent A
☑ Agent B
☐ Agent C

Valid input:
1 → Connect to agent
2 → Continue IVR
9 → Exit

And enforce: Only CONNECT_BY_AGENT campaigns
        ↓
can reference campaign agents

Agent lifecycle rule
ACTIVE
   ↓
call starts
   ↓
BUSY
   ↓
call ends
   ↓
AVAILABLE

If the admin clicks deactivate while BUSY: Agent cannot be deactivated
until active call ends


14. MISSED_CALL campaign
CampaignType

PLAYFILE
DTMF
CONNECT_BY_AGENT
MISSED_CALL

Its execution is fundamentally different:
Dial destination
      ↓
allow ring
      ↓
do NOT wait for answer
      ↓
terminate

The product should present:
MISSED CALL

DNID / Caller ID
[ +91 XXXXX XXXXX ]

Maximum ring duration
[ 10 sec ]

Audience
[ Contact Group ▼ ]

No:
- audio
- DTMF
- IVR
- agent
- TTS
This is a good example of why the campaign UI must be discriminated by campaign type.

15. Whitelist / DND / calling policy
The existing platform already has a phone-list concept including whitelist/protected/block/DNC-style lists.

expose campaign behavior explicitly:
Calling Compliance

○ Normal policy
○ Whitelist only

DND / blocked numbers
○ Skip
○ Allow only if explicitly whitelisted

But the campaign should never bypass platform-level compliance policy

Think:
Platform safety
      ↓
Tenant policy
      ↓
Campaign policy
      ↓
Contact eligibility

not:
Campaign says whitelist → bypass everything

16. Maximum call duration
Add:
Maximum Call Duration
[ MM : SS ]
Campaign configuration owns the desired limit:campaign.maxCallDuration

Execution enforces it.
00:30
01:00
02:00
05:00

17. Webhook configuration
Webhooks

☑ Enable webhooks

URL
https://example.com/webhooks/call

Events

☑ ALL
☐ ANSWERED
☐ NO_ANSWER
☐ BUSY
☐ FAILED
☐ VALID_INPUT
☐ INVALID_INPUT
☐ NO_INPUT
☐ COMPLETED

But internally, don't store "ALL" plus every individual event as contradictory state.

Normalize: subscriptionMode = ALL subscriptionMode = SELECTED events = [...]

Eventually webhook delivery should be asynchronous and retryable, but don't build the dispatcher in this campaign-configuration phase.

18. Report privacy
Make it an explicit campaign privacy setting:
Reporting Privacy

Destination number visibility:

○ Full number
○ Mask number
○ Last 4 digits
○ Hide number
But again, don't actually build reporting yet. Campaign stores the policy, reporting later consumes it.

19. DNID should remain a first-class campaign resource
Current campaign already has:didId

But we should not allow the scheduler/execution phase to assume the DID is still valid.


20. Campaign configuration should be versionable/frozen

Suppose:
Campaign A
Retry = 3
Audio = welcome-v1
DID = X

is scheduled.
Then user edits:
Retry = 10
Audio = welcome-v2

We need a clear rule.
I recommend:
Draft
Mutable.
Scheduled
Configuration becomes execution-ready.
Running
Do not silently mutate execution semantics.
For future executions, either:
new configuration

or better initially:
campaign configuration snapshot

This becomes particularly important once the scheduler exists.
We should design this now even if actual snapshotting is implemented in the execution phase.


21. Campaign configuration model
Campaign
│
├── Identity
│   ├── name
│   ├── description
│   └── type
│
├── Audience
│   └── AudienceSource
│
├── Calling
│   ├── did
│   ├── maxCallDuration
│   ├── whitelistPolicy
│   └── reportingPrivacy
│
├── Content
│   ├── contentMode
│   ├── audio
│   └── tts
│
├── RetryPolicy
│   ├── noAnswer
│   ├── busy
│   ├── failed
│   ├── switchedOff
│   ├── notReachable
│   └── hangup
│
├── SafetyPolicy
│   └── maxAttemptsPerContactPerDay
│
├── TypeConfig
│   │
│   ├── PLAYFILE
│   │
│   ├── DTMF
│   │   ├── inputWait
│   │   ├── validInputs
│   │   ├── invalidAudio
│   │   ├── noResponseAudio
│   │   ├── retryInvalid
│   │   ├── retryNoResponse
│   │   └── ivrTree
│   │
│   ├── CONNECT_BY_AGENT
│   │   ├── agents
│   │   ├── queue
│   │   └── validInput
│   │
│   └── MISSED_CALL
│       └── ringDuration
│
├── Schedule
│
├── Webhooks
│
└── Status


22. One thing I would NOT do
Don't turn typeConfig into an unvalidated arbitrary JSON blob.
Right now we already use typeConfig for DTMF and CONNECT_BY_AGENT, and the campaign service validates that required type-specific configuration exists.   Pasted code (2)
We should evolve that into strict typed configurations:

CampaignType
      ↓
TypeConfig parser
      ↓
PlayfileConfig
DtmfConfig
ConnectByAgentConfig
MissedCallConfig

Then:
invalid field
missing field
wrong type
unsupported option

fails at campaign creation rather than during a live call.


23. Recommended implementation phases



VB-6A — Campaign Targeting Foundation
Implement:
- audience source abstraction
- contact group source
- inline numbers
- CSV/XLSX import reference
- normalization
- duplicate handling
- audience counts
- tenant isolation
- small inline campaigns
- large import boundary
- campaign target validation
No scheduler.
VB-6B — Campaign Safety & Retry Policy
Implement:
- retry rule model
- NO_ANSWER
- BUSY
- FAILED
- SWITCHED_OFF
- NOT_REACHABLE
- HANGUP
- per-rule retry count
- per-rule delay
- global max attempts/contact/day
- campaign-level stricter limit
- effective policy
- concurrency-safe daily attempt admission
- whitelist/DND policy integration
This is particularly important because it becomes a hard safety boundary for the future scheduler.
VB-6C — PLAYFILE + common calling configuration
Implement:
- max call duration
- content selection
- welcome/primary content
- calling policy
- reporting privacy
- campaign configuration validation


VB-6D — DTMF / IVR Configuration
agree so let's move towards prompting agent for VB-6D is about reusable IVR (Interactive Voice Response) trees.
In short:
- Build a reusable IVR tree instead of hard-coding DTMF flows inside each campaign.
- Support multi-level DTMF navigation: e.g. 1 → Sales → 2 → Support.
- Campaign references an ivrTreeId.
- Define nodes/prompts, valid DTMF inputs, timeouts, retries, and transitions.
- Reuse existing audio/TTS governance.
- Freeze the selected IVR configuration into the execution snapshot, consistent with VB-6A.
- Integrate with the existing DTMF runtime without redesigning telephony.
- Preserve tenant isolation and existing campaign architecture.
Main goal: turn the current single-level DTMF campaign flow into a reusable, configurable multi-level IVR system.
Implement:
- valid inputs
- input wait time
- invalid-input audio
- no-input audio
- retry invalid
- retry no-input
- IVR tree domain
- multi-level nodes
- transitions
- reusable IVR selection
- create IVR from campaign flow
This is where the current DTMF implementation should evolve from the existing basic typeConfig/WAITING_FOR_DTMF foundation. The existing DTMF implementation deliberately stopped before IVR trees and richer interaction flows.


VB-6E — CONNECT_BY_AGENT campaign configuration
Implement:
- campaign agent assignment
- agent selection rules
- input → agent action
- agent activation/deactivation invariants
- validation that only CONNECT_BY_AGENT can use agents
- no AI agent yet
VB-6F — MISSED_CALL
Implement:
MISSED_CALL

with:
- DID
- audience
- ring duration
- retry policy
- schedule
- safety policy
No audio/DTMF/agent configuration.
VB-6G — Integrations / campaign configuration
Implement:
- webhook configuration
- event selection
- report privacy configuration
- configuration validation
- readiness checks
Still no execution scheduler.

24. Then Campaign Execution / Scheduler becomes much simpler

Campaign
   ↓
Is campaign runnable?
   ↓
Get next eligible audience item
   ↓
Check global safety policy
   ↓
Check campaign retry policy
   ↓
Check DID
   ↓
Check whitelist/DND
   ↓
Check calling window
   ↓
Check gateway capacity
   ↓
Create/dispatch attempt


Campaign
   ↓
Is campaign runnable?
   ↓
Get next eligible audience item
   ↓
Check global safety policy
   ↓
Check campaign retry policy
   ↓
Check DID
   ↓
Check whitelist/DND
   ↓
Check calling window
   ↓
Check gateway capacity
   ↓
Create/dispatch attempt


And the execution layer handles:
PLAYFILE
DTMF
CONNECT_BY_AGENT
MISSED_CALL

according to the already validated campaign configuration.

25. Most important product principle
The campaign creation experience should feel like:
"Tell us what you want to call, who you want to call, what should happen, and when."

make the UI progressive disclosure:
                    Create Campaign
                          │
             ┌────────────┴────────────┐
             │                         │
       Campaign Type              Audience
             │                         │
      ┌──────┼──────┐          ┌──────┼──────┐
      │      │      │          │      │      │
   Playfile DTMF Agent       Group  Paste   File
                     │
                     │
               type-specific
               configuration

One architectural decision I strongly recommend
CAMPAIGN CONFIGURATION
        │
        │ "what the user wants"
        ▼
CAMPAIGN POLICY
        │
        │ "what the platform permits"
        ▼
CALL ELIGIBILITY
        │
        │ "can this specific contact
        │  be called right now?"
        ▼
EXECUTION

or your 3 calls/day requirement this is critical:
Campaign says:       3
Tenant policy says:  3
Platform policy:     3
                     ↓
Effective:           3

but:
Campaign says:       10
Tenant policy says:  5
Platform policy:     3
                     ↓
Effective:           3

That gives us a genuine safety boundary rather than a UI setting that the future scheduler could accidentally bypass.