"""
Phase D - J3: is the pinned origination_uuid still ADDRESSABLE after bridging?

The previous probe established the shape of the problem:
  * the pinned channel is the originated A-leg; it answers and survives;
  * the call also creates other channels (the far end, and the bridge anchor);
  * CHANNEL_BRIDGE is anchored on one of THOSE, not on the pinned channel;
  * no header links a foreign channel back to the pinned UUID.

That leaves exactly one question that decides whether J3 needs a CODE fix or
only a DOCUMENTED correction:

    after the call is bridged, do uuid_broadcast and uuid_kill addressed to the
    PINNED uuid still act on the live call?

If yes -> the existing application code, which persists the pinned uuid as
providerCallId and addresses media by it, is CORRECT, and J3 resolves as a
corrected assumption plus documentation.

If no -> the stored identity is stale after bridging and media/hangup silently
no-op, which is the real defect and needs a current-channel concept.

Both commands are issued against the pinned uuid while a call is up and
bridged, and the result is read back from the switch rather than from the +OK
reply - because +OK proves only that a command was accepted, which is exactly the
mistake J2 is about.
"""
import re
import sys
import threading
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402
from c4_bridge_full import pw  # noqa: E402

PLATFORM = ("127.0.0.1", 8021)
SUBS = ["CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_BRIDGE", "CHANNEL_HANGUP",
        "PLAYBACK_START", "PLAYBACK_STOP", "PLAYBACK_ERROR",
        "CHANNEL_EXECUTE", "CHANNEL_EXECUTE_COMPLETE"]
TONE = "/media/obd/phase-c-test-tone.wav"


def pump(esl, sink, seconds):
    end = time.time() + seconds
    while time.time() < end:
        try:
            ev = esl.next_event(timeout=max(1, int(end - time.time())))
        except Exception:
            return
        if ev is not None:
            sink.append(ev)


def main():
    ev = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM); ev.connect(); ev.subscribe(SUBS)
    cmd = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM); cmd.connect()
    m = re.search(r"SIP-IP\s+(\d+\.\d+\.\d+\.\d+)",
                  cmd.result("sofia status profile internal") or "")
    plat = m.group(1)
    print("=" * 78)
    print("J3: is the pinned origination_uuid still addressable after bridging?")
    print("=" * 78)
    print("  platform: %s" % plat)

    events = []
    threading.Thread(target=pump, args=(ev, events, 60), daemon=True).start()
    time.sleep(0.4)

    pinned = str(uuidlib.uuid4())
    dest = "sofia/internal/1002@%s" % plat
    _, reply, _ = cmd.api("bgapi originate {origination_uuid=%s,"
                         "origination_caller_id_number=+15551230000}%s &park()"
                         % (pinned, dest), timeout=20)
    print("  pinned  : %s" % pinned)
    print("  reply   : %s" % reply.strip())

    # wait until the call is actually bridged
    bridged_at = None
    end = time.time() + 30
    while time.time() < end and bridged_at is None:
        for n, _ in events:
            if n == "CHANNEL_BRIDGE":
                bridged_at = time.time()
                break
        time.sleep(0.4)
    print("  bridged : %s" % bool(bridged_at))

    pinned_events = [n for n, h in events if h.get("Unique-ID") == pinned]
    print("  events on the PINNED channel: %s" % ", ".join(pinned_events))

    if not bridged_at:
        print("\n  no bridge observed; cannot judge post-bridge addressing")
        ev.close(); cmd.close(); return

    print("\n  --- addressing the PINNED uuid while the call is bridged ---")
    print("  uuid_dump:")
    dump = cmd.result("uuid_dump " + pinned) or ""
    print("    " + (dump.strip().splitlines()[0] if dump.strip() else "<empty>"))
    print("    answered=%s" % ("yes" if re.search(r"answered|State.*ANSWERED|CHANNEL_ANSWER", dump, re.I) else "no/unclear"))

    print("\n  uuid_broadcast <pinned> %s aleg" % TONE)
    r = cmd.result("uuid_broadcast %s %s aleg" % (pinned, TONE), timeout=15)
    print("    reply: %r" % (r.strip() if r else "<empty>"))

    print("\n  waiting for playback events on the pinned channel ...")
    end = time.time() + 12
    while time.time() < end:
        for n, h in events:
            if h.get("Unique-ID") == pinned and n.startswith("PLAYBACK"):
                print("    %s on PINNED -> %s" % (n, "PROVES the pinned uuid is addressable"))

    print("\n  uuid_kill <pinned> NORMAL_CLEARING")
    r = cmd.result("uuid_kill %s NORMAL_CLEARING" % pinned, timeout=15)
    print("    reply: %r" % (r.strip() if r else "<empty>"))
    time.sleep(4)

    hung = any(n == "CHANNEL_HANGUP" and h.get("Unique-ID") == pinned
               for n, h in events)
    print("    CHANNEL_HANGUP observed for the PINNED channel: %s" % hung)
    print("\n  VERDICT: %s" % (
        "the pinned uuid remains the addressable identity for media and hangup"
        if hung else "the pinned uuid did NOT produce a hangup - stored identity is stale"))
    ev.close(); cmd.close()


if __name__ == "__main__":
    main()
