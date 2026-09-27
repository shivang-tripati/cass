
VB-0
Routing + Capacity + Reservation
        ↓
VB-1
PLAYFILE
        ↓
VB-2
DTMF Collection
        ↓
DTMF Result / Action Boundary
        ↓
Future CONNECT_BY_AGENT


Don't implement these yet

For VB-3, I'd deliberately exclude:

predictive dialing
progressive dialing
skill-based sophisticated routing
agent queues with complex distribution
browser WebRTC
Android SIP
mobile forwarding
call recording
supervisor/barge/whisper
AI agent
STT/TTS
omnichannel


Recommended roadmap

VB-0  Routing / Capacity / Reservation       ✅
VB-1  PLAYFILE                               ✅
VB-2  DTMF                                   ✅
VB-3  CONNECT_BY_AGENT                       ← NEXT
VB-4  Agent/Queue foundation
VB-5  Inbound calling
VB-6  Browser WebRTC agent endpoint
VB-7  Android SIP agent endpoint
VB-8  External mobile forwarding
VB-9  Progressive/Preview dialing
VB-10 Predictive dialing
VB-11 Call recording / analytics
VB-12 Voice AI


Runtime validation — PASS

This is one of the strongest parts of the implementation.

Runtime does not trust campaign creation/activation state. It re-reads canonical resource state when actually needed. That covers:

PLAYFILE
DTMF audio
campaign execution orchestration
initial attempts
per-retry DID checks
attempt creation


There is one small deferred optimization:

SipGatewayRoutingAdapter currently converts the gateway entity to GatewayRouteView, then authorization re-resolves the entity inside the same transaction. The report correctly identifies this as a micro-optimization, not a correctness issue.

Decision

VB-5G → COMPLETE → READY.


dnid could change in runtime also ?? buisness verification

VB-6B.2
Deferred: file membership import, cross-group copy/move, reverse listing/analytics, VB-6C daily limits, membership update semantics.


Verdict: VB-6C.1 COMPLETE / APPROVED
A Voice Blast contact cannot receive more than the effective daily accepted-dial limit for the same tenant + contact + actual DNID + calendar day, even under PostgreSQL concurrency.
**need to be check daily limit at global level or just tenant, 

One limitation to carry forward
reserve
  ↓
process crashes
  ↓
reserved_count remains elevated
