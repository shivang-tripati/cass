VB-4 — Contact Center Foundation & Basic ACD

Goal: Build the minimum production-grade contact-center layer supporting inbound calling, outbound agent calls, queues, deterministic ACD assignment, agent presence/status, endpoint management, and agent/admin APIs.

VB-4 should establish the foundation that later phases can extend into progressive/predictive dialing, WebRTC, Android SIP, recording, AI agents, omnichannel, etc.

1. VB-4 scope
A. Agent / Contact-Center Foundation

Extend the agent model created in VB-3 rather than creating another agent system.

Support:

Agent lifecycle
Agent administrative status
Agent runtime/presence status
Agent availability
Agent endpoint management
Active-call state
Agent call history
Tenant-scoped agent management

A useful distinction should remain:

Administrative status
        ↓
Can this agent participate in calls at all?

Presence / availability
        ↓
Can this agent receive a call RIGHT NOW?

For example:

Admin:
ACTIVE
SUSPENDED
DISABLED

Presence:
OFFLINE
ONLINE
AWAY
BUSY

And an ACD eligibility check could conceptually be:

Agent is ACTIVE
        +
Agent has valid endpoint
        +
Agent is ONLINE
        +
Agent is AVAILABLE
        +
Agent has no conflicting active call
        +
Agent belongs to the queue
        ↓
Eligible

Don't blindly introduce these exact enum names if the existing VB-3 implementation already has equivalent concepts. Inspect first and extend the existing model.

2. Agent Endpoint Management

VB-3 already introduced agent endpoints.

VB-4 should turn them into a usable management capability.

Support at minimum:

Agent
 ├── Endpoint 1
 ├── Endpoint 2
 └── ...

Endpoint types should reuse the existing architecture.

For the current MVP:

SIP

should be the actual supported endpoint.

WebRTC and Android SIP remain future work.

Admin capabilities:

create endpoint
update endpoint
enable/disable endpoint
remove/deactivate endpoint
validate endpoint configuration
view endpoint status/configuration
tenant isolation

Do not build a new SIP stack.

FreeSWITCH remains responsible for actual media/telephony.

3. Queue / Basic ACD

This is the biggest new piece of VB-4.

Introduce:

Queue

with tenant ownership.

Conceptually:

Tenant
 ├── Queue: Sales
 │     ├── Agent A
 │     ├── Agent B
 │     └── Agent C
 │
 └── Queue: Support
       ├── Agent B
       └── Agent D

Queue should contain enough configuration for the MVP:

name
description
active/inactive
tenant
timeout
maximum waiting callers
overflow destination/policy
agent selection strategy

Don't overbuild queue strategies.

For VB-4, use one deterministic ACD strategy.

For example:

eligible agents
       ↓
filter unavailable/busy agents
       ↓
filter agents not belonging to queue
       ↓
deterministic ordering
       ↓
reserve agent
       ↓
originate
       ↓
bridge

The exact ordering should be based on the existing VB-3 deterministic selection implementation rather than inventing a second algorithm.

4. Queue Membership

Introduce an explicit relationship:

Queue ↔ Agent

with tenant-safe membership.

Potential fields:

queue_id
agent_id
priority
enabled
created_at
updated_at

Priority can exist even if the initial algorithm doesn't make sophisticated use of it.

Important:

Queue membership must not itself mean the agent is currently available.

For example:

Queue membership = YES
Agent presence   = OFFLINE

means:

NOT ELIGIBLE
5. Waiting Calls

This is where VB-4 becomes an actual ACD rather than simply an agent directory.

When an inbound call arrives:

Inbound Call
     ↓
DID
     ↓
Tenant
     ↓
Queue
     ↓
Waiting
     ↓
Agent selection
     ↓
Agent reservation
     ↓
Agent originate
     ↓
Agent answers
     ↓
Bridge

The waiting state must be persisted.

Do not rely on an in-memory queue.

PostgreSQL remains the source of truth.

6. Queue Timeout / Overflow

A queue needs a deterministic exit path.

Example:

Caller enters queue
       ↓
wait
       ↓
agent becomes available?
   ┌───┴───┐
  YES      NO
   ↓        ↓
assign    timeout
            ↓
        overflow

The MVP should support explicit queue policies such as:

HANGUP
VOICEMAIL / PLAYFILE boundary
EXTERNAL NUMBER

But do not implement every destination type yet.

If a destination isn't already supported by the platform, create a clear boundary rather than fake implementation.

Most importantly:

No agent available
        ≠
infinite retry

A caller must eventually have a deterministic outcome.

7. Inbound Calling

This should be a major VB-4 deliverable.

The initial inbound path:

FreeSWITCH
    ↓
Inbound call
    ↓
DID
    ↓
DID ownership / tenant resolution
    ↓
Inbound routing policy
    ↓
Queue OR direct agent
    ↓
ACD
    ↓
Agent
    ↓
Bridge

The system must create the same canonical domain objects used elsewhere:

CallSession
   ├── CALLER leg
   └── AGENT leg

rather than introducing a separate inbound-call model.

8. DID → Tenant → Queue/Agent

This needs to be explicit.

The inbound DID is the routing identity.

For example:

+919810000001
       ↓
Tenant A
       ↓
Sales Queue

or:

+919810000002
       ↓
Tenant B
       ↓
Agent 42

No silent fallback to another tenant or DID.

Routing should remain:

deterministic + explainable + tenant-scoped.

9. Inbound Call Lifecycle

The target lifecycle should be something like:

INBOUND
   ↓
ROUTING
   ↓
QUEUED
   ↓
WAITING_FOR_AGENT
   ↓
AGENT_RESERVED
   ↓
CONNECTING_AGENT
   ↓
RINGING
   ↓
ANSWERED
   ↓
BRIDGING
   ↓
BRIDGED
   ↓
ACTIVE
   ↓
HANGUP
   ↓
CLEANUP

However, reuse existing CallSessionStatus and CallLegStatus wherever possible.

Don't create duplicate statuses merely because the diagram is easier to understand.

The coding agent must inspect the existing status model first.

10. Outbound Agent Calls

VB-4 should also establish the basic agent-originated outbound foundation.

But keep it deliberately simple:

Agent
  ↓
select endpoint
  ↓
originate external number
  ↓
agent leg / caller leg
  ↓
bridge

This is not the predictive/progressive dialer.

No:

campaign pacing
predictive dialing
agent preview
power dialing
dialer optimization
campaign-level concurrency algorithm

Those belong later.

VB-4 only establishes the contact-center call primitive.

11. Agent Presence

We need to distinguish:

Administrative state

from:

Runtime presence

and:

Call state

For example:

Agent:
ACTIVE
ONLINE
AVAILABLE
0 active calls

→ eligible.

Whereas:

Agent:
ACTIVE
ONLINE
BUSY
1 active call

→ not eligible.

The system should have a clear state transition model rather than allowing arbitrary combinations.

12. Agent APIs

VB-4 should introduce REST APIs for the agent/admin surface.

Admin

Conceptually:

GET    /api/v1/agents
POST   /api/v1/agents
GET    /api/v1/agents/{id}
PATCH  /api/v1/agents/{id}
Status / presence
GET   /api/v1/agents/{id}/status
PATCH /api/v1/agents/{id}/status
Endpoints
GET    /api/v1/agents/{id}/endpoints
POST   /api/v1/agents/{id}/endpoints
PATCH  /api/v1/agents/{id}/endpoints/{endpointId}
DELETE /api/v1/agents/{id}/endpoints/{endpointId}
Queues
GET    /api/v1/queues
POST   /api/v1/queues
GET    /api/v1/queues/{id}
PATCH  /api/v1/queues/{id}
Membership
POST   /api/v1/queues/{id}/agents/{agentId}
DELETE /api/v1/queues/{id}/agents/{agentId}
Active calls
GET /api/v1/agents/{id}/calls/active
History
GET /api/v1/agents/{id}/calls

These are illustrative API boundaries, not a command to create every endpoint exactly as written. The coding agent should inspect the existing API conventions first.

13. Call History

Reuse:

CallSession
CallLeg

as the source of call history.

Do not create a duplicate:

AgentCallHistory

table unless inspection demonstrates a real need.

Agent history should be a tenant-safe projection/query over the canonical voice call data.

14. Concurrency

This is critical.

Two callers cannot both successfully reserve the same agent.

We already proved this pattern in VB-3.

VB-4 should reuse the same PostgreSQL transactional reservation approach:

Find eligible agents
       ↓
attempt atomic reservation
       ↓
one transaction wins
       ↓
other caller tries next eligible agent

No Redis lock.

No Java synchronized block.

No JVM-local lock.

No distributed lock infrastructure.

15. Inbound Call Admission

The inbound side also needs capacity protection.

Conceptually:

Inbound call
      ↓
tenant routing
      ↓
queue
      ↓
queue capacity?
      ↓
agent eligibility
      ↓
agent reservation

Gateway capacity from VB-0 still applies.

Agent capacity from VB-3 still applies.

Therefore:

Gateway capacity
        +
Queue capacity
        +
Agent capacity

all participate in admission.

16. What VB-4 explicitly DOES NOT include

This is important to prevent scope explosion.

Not VB-4

❌ Predictive dialing
❌ Progressive dialing
❌ Preview dialing
❌ Campaign dialer optimization
❌ WebRTC agent client
❌ Android SIP client
❌ FCM call wakeup
❌ Call recording
❌ Transcription
❌ AI voice agents
❌ STT/TTS
❌ LLM integration
❌ Omnichannel
❌ WhatsApp/contact-center messaging
❌ Advanced workforce management
❌ Supervisor whisper/barge
❌ Call monitoring
❌ Complex skills-based routing
❌ Kafka
❌ Kubernetes
❌ New SIP stack
❌ Asterisk/Kamailio
❌ Redis-based locking

The goal is a solid basic ACD, not a complete enterprise contact center.


17. VB-4 architecture

The resulting architecture should look approximately like:

                    ┌──────────────────────┐
                    │     Admin / Agent    │
                    │        APIs          │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │   Contact Center     │
                    │                      │
                    │ Agents               │
                    │ Presence             │
                    │ Queues               │
                    │ Membership           │
                    │ ACD                  │
                    │ Assignment           │
                    └──────────┬───────────┘
                               │
                 ┌─────────────┴──────────────┐
                 │                            │
                 ▼                            ▼
          Inbound Calling              Agent Outbound
                 │                            │
                 └─────────────┬──────────────┘
                               ▼
                    ┌──────────────────────┐
                    │ CallSession / Legs   │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │ Routing + Capacity   │
                    │     VB-0             │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │    FreeSWITCH / ESL  │
                    └──────────────────────┘


18. Recommended VB-4 internal phases

I would actually have the coding agent implement VB-4 in these internal steps:

VB-4A — Agent foundation
Agent lifecycle
richer status/presence
endpoint management
agent APIs
active-call queries
history queries
VB-4B — Queue foundation
Queue
queue membership
queue configuration
waiting calls
queue APIs
VB-4C — ACD
eligibility
deterministic selection
atomic reservation
assignment
timeout
overflow
VB-4D — Inbound calling
DID
 ↓
tenant
 ↓
queue/direct agent
 ↓
ACD
 ↓
agent
 ↓
bridge
VB-4E — Outbound agent calling

Basic agent → external number calling using the existing FreeSWITCH infrastructure.

VB-4F — Hardening
tenant isolation
idempotency
concurrency
stale reservations
hangup cleanup
PostgreSQL integration
ESL contract tests
regression
documentation

This gives us a manageable implementation while still calling the entire thing VB-4.

VB-4 Definition of Done

I would consider VB-4 complete only when all of these are true:

Agent
 Agent lifecycle works
 Administrative status works
 Runtime presence works
 Availability is deterministic
 SIP endpoint management works
 Tenant isolation verified
Queue
 Queue CRUD
 Queue membership
 Waiting caller persistence
 Queue capacity
 Timeout
 Explicit overflow behavior
ACD
 Eligibility
 Deterministic selection
 Atomic reservation
 No double assignment
 Reservation cleanup
 Stale reservation reconciliation
Inbound
 FreeSWITCH inbound event
 DID resolution
 Tenant resolution
 Queue/direct-agent routing
 CallSession
 Caller CallLeg
 Agent CallLeg
 Agent originate
 Agent answer
 Bridge confirmation from event
 Caller hangup cleanup
 Agent hangup cleanup
Outbound
 Agent can initiate external call
 Existing gateway routing/capacity reused
 Agent/external legs correctly represented
 Bridge/hangup lifecycle correct
APIs
 Agent APIs
 Presence/status APIs
 Endpoint APIs
 Queue APIs
 Membership APIs
 Active-call API
 Call-history API
Verification
 Unit tests
 PostgreSQL integration tests
 concurrency tests
 tenant isolation tests
 migration test
 ESL contract tests
 existing VB-0 regression
 existing VB-1 regression
 existing VB-2 regression
 existing VB-3 regression
 clean compile
 test-compile
 full regression
 documentation updated
 final implementation report


 One important design decision

I would not make queues depend directly on FreeSWITCH.

The layering should stay:

FreeSWITCH
   ↓
ESL event / command boundary
   ↓
Voice / Call domain
   ↓
Contact Center
   ↓
ACD / Queue
   ↓
Agent selection

The contact-center layer decides:

Who should receive this call?

The voice/telephony layer decides:

How do we actually connect those legs?

That separation will become extremely valuable when we later add WebRTC, Android SIP, external forwarding, AI agents, and omnichannel.

Therefore, I would lock the phase as:

VB-4 — Contact Center Foundation & Basic ACD
Build the tenant-scoped agent, endpoint, presence, queue, membership, waiting-call, deterministic ACD, inbound calling, basic agent outbound calling, and agent/admin API foundation while reusing all VB-0–VB-3 routing, capacity, reservation, CallSession/CallLeg, ESL, and FreeSWITCH primitives.