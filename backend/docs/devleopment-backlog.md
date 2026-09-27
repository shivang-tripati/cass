VB-3 Operational Smoke Test

Real FreeSWITCH
   ↓
Real caller
   ↓
DTMF "connect"
   ↓
Agent originate
   ↓
Agent answers
   ↓
uuid_bridge
   ↓
CHANNEL_BRIDGE
   ↓
Conversation
   ↓
Hangup
   ↓
Verify:
  - agent reservation released
  - gateway reservation released
  - both CallLegs finalized
  - CallSession finalized
  - no stale reservation