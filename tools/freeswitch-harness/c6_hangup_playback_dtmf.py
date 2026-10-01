"""
Phase C - C6/C9/C10: definitive header capture for CHANNEL_HANGUP, the playback
events, and DTMF.

The question this exists to answer:

  EslEvent.java reads the channel UUID from the header `Call-UUID`
  (EslEvent.getCallUuid -> headers.get("Call-UUID")), and
  EslEventService.java warns "Received ESL event without Call-UUID" when it is
  missing. But the CHANNEL_CREATE and CHANNEL_ANSWER frames captured from this
  switch carried NO `Call-UUID` header - the channel UUID appeared as
  `Channel-Call-UUID`, as `Unique-ID`, as `Caller-Unique-ID` and as
  `variable_call_uuid`.

  If CHANNEL_HANGUP also lacks `Call-UUID`, then the Java platform cannot
  correlate a single channel event to a CallAttempt, and that is an integration
  defect rather than a documentation gap. So this prints the COMPLETE header
  set of every event of interest, with nothing filtered, and answers the
  question directly instead of inferring it from other event types.
"""
import sys
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402
from c4_c5_c8_c9_c10 import pw  # noqa: E402

DEST = "sofia/internal/1002@172.25.0.2"
GOOD = "/media/obd/phase-c-test-tone.wav"
BAD = "/media/obd/phase-c-deliberately-absent.wav"
SUBS = ["CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_HANGUP", "CHANNEL_DTMF",
        "CHANNEL_BRIDGE", "PLAYBACK_START", "PLAYBACK_STOP", "PLAYBACK_ERROR"]

WANT = ["Event-Name", "Unique-ID", "Call-UUID", "Channel-Call-UUID",
        "Caller-Unique-ID", "Other-Channel-UUID", "variable_call_uuid",
        "variable_uuid", "Hangup-Cause", "Hangup-Cause-Code",
        "sip_hangup_disposition", "Caller-Disposition", "sip_term_status",
        "DTMF-Digit", "DTMF-Subevent", "DTMF-Duration", "Playback-Error",
        "Bridge-A-Unique-ID", "Bridge-B-Unique-ID", "Answer-State",
        "Channel-State", "Channel-Name", "Caller-Destination-Number"]


def dump(name, h):
    print("\n" + "-" * 74)
    print("%s  for channel %s" % (name, h.get("Unique-ID")))
    print("-" * 74)
    for k in WANT:
        if k in h:
            print("  %-28s = %s" % (k, h[k]))
    missing = [k for k in ("Call-UUID",) if k not in h]
    print("  ABSENT: %s" % (", ".join(missing) if missing else "(none)"))


def main():
    e = Esl(pw("FREESWITCH_PASSWORD"))
    e.connect()
    e.subscribe(SUBS)
    cu = str(uuidlib.uuid4())
    print("pinned A-leg UUID:", cu)
    ct, reply, _ = e.api(
        "bgapi originate {origination_uuid=%s,"
        "origination_caller_id_number=+15551230000}%s &park()" % (cu, DEST))
    print("originate reply :", reply.strip(), "\n")

    seen = []
    answered = False

    def pump(seconds):
        nonlocal answered
        end = time.time() + seconds
        while time.time() < end:
            ev = e.next_event(timeout=max(1, int(end - time.time())))
            if ev is None:
                continue
            n, h = ev
            # Record EVERY event for the pinned channel, not just a chosen few.
            # Filtering to a whitelist here is what hid the playback and hangup
            # events on the first run of this script.
            if h.get("Unique-ID") != cu and h.get("Channel-Call-UUID") != cu:
                continue
            key = (n, h.get("Event-Sequence"))
            if key in [(s[0], s[1].get("Event-Sequence")) for s in seen]:
                continue
            seen.append((n, h))
            dump(n, h)
            if n == "CHANNEL_ANSWER":
                answered = True

    pump(22)
    print("\n>>> answered:", answered)

    if answered:
        print("\n>>> uuid_broadcast GOOD file:", GOOD)
        print("   ", e.result("uuid_broadcast %s %s aleg" % (cu, GOOD)).strip()[:60])
        pump(8)

        print("\n>>> uuid_broadcast MISSING file:", BAD)
        print("   ", e.result("uuid_broadcast %s %s aleg" % (cu, BAD)).strip()[:60])
        pump(8)

    print("\n>>> uuid_kill NORMAL_CLEARING")
    print("   ", e.result("uuid_kill %s NORMAL_CLEARING" % cu).strip()[:60])
    pump(10)

    print("\n" + "=" * 74)
    print("PLAYBACK events for the pinned channel (C8/C9):")
    for n, h in seen:
        if n.startswith("PLAYBACK"):
            dump(n, h)
    if not any(n.startswith("PLAYBACK") for n, _ in seen):
        print("  none captured for the pinned channel")

    print("\n" + "=" * 74)
    print("ALL DTMF events seen on this connection (C10), any channel:")
    e.close()


if __name__ == "__main__":
    main()
