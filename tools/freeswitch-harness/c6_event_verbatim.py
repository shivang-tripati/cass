"""
Phase C diagnostic: which CHANNEL_* events actually reach a subscriber, and
what is in their Call-UUID header?

The platform's own log says a channel was created, invited, answered and
parked. An earlier run of the full test saw none of that as ESL events for the
pinned channel. One of those two observations is wrong, and the whole Phase C
evidence base depends on knowing which - so this prints every event as it
arrives, unfiltered, with the identifiers it carries.
"""
import sys
import threading
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402
from c4_c5_c8_c9_c10 import pw  # noqa: E402

DEST = "sofia/internal/1002@172.25.0.2"
SUBS = ["CHANNEL_CREATE", "CHANNEL_PROGRESS", "CHANNEL_PROGRESS_MEDIA",
        "CHANNEL_ANSWER", "CHANNEL_HANGUP", "CHANNEL_BRIDGE",
        "CHANNEL_DTMF", "PLAYBACK_START", "PLAYBACK_STOP", "PLAYBACK_ERROR"]

IDENT = ["Event-Name", "Call-UUID", "Unique-ID", "Other-Channel-UUID",
         "Channel-Name", "Channel-Answer-State", "Hangup-Cause",
         "Hangup-Cause-Code", "DTMF-Digit", "Playback-Error",
         "Bridge-A-Unique-ID", "Bridge-B-Unique-ID", "sip_call_id"]


def main():
    e = Esl(pw("FREESWITCH_PASSWORD"))
    e.connect()
    print("subscription reply:", e.subscribe(SUBS))

    cu = str(uuidlib.uuid4())
    print("pinned A-leg UUID:", cu)
    cmd = ("bgapi originate {origination_uuid=%s,"
           "origination_caller_id_number=+15551230000}%s &park()" % (cu, DEST))
    ct, reply, _ = e.api(cmd)
    print("originate reply :", reply.strip())
    print()
    print("--- every event as it arrives ---")
    end = time.time() + 32
    seen = 0
    while time.time() < end:
        ev = e.next_event(timeout=max(1, int(end - time.time())))
        if ev is None:
            continue
        name, h = ev
        seen += 1
        same = "  <== PINNED" if h.get("Call-UUID") == cu else ""
        print("  %-24s Call-UUID=%-38s Unique-ID=%-38s%s"
              % (name, str(h.get("Call-UUID")), str(h.get("Unique-ID")), same))
        for k in IDENT:
            if k in h and k not in ("Event-Name", "Call-UUID", "Unique-ID"):
                print("        %-22s = %s" % (k, h[k]))
    print("\ntotal events received:", seen)
    e.result("uuid_kill %s NORMAL_CLEARING" % cu)
    time.sleep(2)
    e.close()


if __name__ == "__main__":
    main()
