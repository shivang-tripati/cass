"""
Phase C - C6: the EXACT header set of each CHANNEL_* event this FreeSWITCH
emits, printed unfiltered.

Why unfiltered, and why a hangup specifically:

  Phase B documented the Java ESL client as correlating channel state on the
  `Call-UUID` header, and listed "CHANNEL_* header casing" as NOT YET TESTED.
  An earlier run of the Phase C call test showed that several events on this
  switch carry NO `Call-UUID` header at all - the channel UUID arrives in
  `Unique-ID`. If that holds for CHANNEL_HANGUP, the Java client's correlation
  is reading a header that is not there, which is a real integration defect
  rather than a documentation gap.

  This script therefore prints every header of every event, in the order
  FreeSWITCH sends it, for a call that is created, answered and hung up. No
  filtering, no assumed casing, no assumed set.
"""
import sys
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402
from c4_c5_c8_c9_c10 import pw  # noqa: E402

DEST = "sofia/internal/1002@172.25.0.2"
SUBS = ["CHANNEL_CREATE", "CHANNEL_PROGRESS", "CHANNEL_PROGRESS_MEDIA",
        "CHANNEL_ANSWER", "CHANNEL_HANGUP", "CHANNEL_BRIDGE", "CHANNEL_DTMF",
        "PLAYBACK_START", "PLAYBACK_STOP", "PLAYBACK_ERROR"]


def main():
    e = Esl(pw("FREESWITCH_PASSWORD"))
    e.connect()
    e.subscribe(SUBS)
    cu = str(uuidlib.uuid4())
    print("pinned A-leg UUID:", cu)
    ct, reply, _ = e.api(
        "bgapi originate {origination_uuid=%s,"
        "origination_caller_id_number=+15551230000}%s &park()" % (cu, DEST))
    print("originate reply :", reply.strip())

    printed = set()
    hung = False
    end = time.time() + 45
    while time.time() < end and not hung:
        ev = e.next_event(timeout=max(1, int(end - time.time())))
        if ev is None:
            continue
        name, h = ev
        if h.get("Unique-ID") != cu and name not in (
                "PLAYBACK_START", "PLAYBACK_STOP", "PLAYBACK_ERROR"):
            continue
        if name in printed:
            continue
        printed.add(name)
        print("\n" + "=" * 74)
        print("EVENT %s   (full header set, exactly as received)" % name)
        print("=" * 74)
        for k in sorted(h):
            v = h[k]
            if len(v) > 110:
                v = v[:110] + "..."
            print("  %-34s = %s" % (k, v))
        if name == "CHANNEL_HANGUP":
            hung = True

    print("\n" + "=" * 74)
    print("EVENTS OBSERVED FOR THE PINNED CHANNEL:", sorted(printed))
    print("Call-UUID present on any of them:",
          "Call-UUID" in {k for name in printed for k in h_keys})
    e.result("uuid_kill %s NORMAL_CLEARING" % cu)
    time.sleep(2)
    e.close()


h_keys = set()

if __name__ == "__main__":
    main()
