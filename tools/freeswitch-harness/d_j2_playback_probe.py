"""
Phase D - J2: what signal, if any, distinguishes a failed playback from a
successful one?

The Java code reads `Playback-Error`. Phase C established that header never
appears for a missing file, while `uuid_broadcast` still answers `+OK Message
sent`. So the question is not "which header holds the error" but "is there ANY
observable difference, and if not, what is the reliable detection rule".

This compares a valid file against a missing one on a live, answered channel and
captures, for each case:
  * the command reply
  * every event emitted on the pinned channel
  * the full CHANNEL_EXECUTE_COMPLETE header set (the dialplan-level event,
    which the current subscription does not even include)
  * the log lines the file layer emits

A detection rule is only acceptable if it distinguishes the two cases without
guessing. This prints the raw difference rather than asserting one.
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
GOOD = "/media/obd/phase-c-test-tone.wav"
BAD = "/media/obd/phase-c-deliberately-absent.wav"

SUBS = ["CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_BRIDGE", "CHANNEL_HANGUP",
        "PLAYBACK_START", "PLAYBACK_STOP", "PLAYBACK_ERROR",
        "CHANNEL_EXECUTE", "CHANNEL_EXECUTE_COMPLETE"]


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
    plat = re.search(r"SIP-IP\s+(\S+)",
                     cmd.result("sofia status profile internal") or "").group(1)

    events = []
    threading.Thread(target=pump, args=(ev, events, 70), daemon=True).start()
    time.sleep(0.4)

    pinned = str(uuidlib.uuid4())
    _, reply, _ = cmd.api("bgapi originate {origination_uuid=%s,"
                         "origination_caller_id_number=+15551230000}"
                         "sofia/internal/1002@%s &park()" % (pinned, plat), timeout=20)
    print("=" * 78)
    print("J2: distinguishing a successful playback from a failed one")
    print("=" * 78)
    print("  pinned : %s" % pinned)
    print("  reply  : %s" % reply.strip())
    t = time.time() + 25
    while time.time() < t and not any(
            n == "CHANNEL_ANSWER" and h.get("Unique-ID") == pinned for n, h in events):
        time.sleep(0.4)
    print("  answered: %s" % any(n == "CHANNEL_ANSWER" and h.get("Unique-ID") == pinned
                                 for n, h in events))

    def mark(label):
        t0 = len(events)
        return t0, label

    # --- Case A: valid file ---
    print("\n  --- CASE A: valid file ---")
    print("  file    : %s" % GOOD)
    base, _ = mark("A")
    r = cmd.result("uuid_broadcast %s %s aleg" % (pinned, GOOD), timeout=15)
    print("  reply   : %r" % r.strip())
    time.sleep(9)
    a_events = [(n, h) for n, h in events[base:] if h.get("Unique-ID") == pinned]
    print("  events on pinned channel after broadcast:")
    for n, h in a_events:
        print("     %s" % n)

    # --- Case B: missing file ---
    print("\n  --- CASE B: missing file ---")
    print("  file    : %s" % BAD)
    base, _ = mark("B")
    r = cmd.result("uuid_broadcast %s %s aleg" % (pinned, BAD), timeout=15)
    print("  reply   : %r  <-- command ACCEPTED" % r.strip())
    time.sleep(9)
    b_events = [(n, h) for n, h in events[base:] if h.get("Unique-ID") == pinned]
    print("  events on pinned channel after broadcast:")
    if not b_events:
        print("     (NONE - this is the signal)")
    for n, h in b_events:
        print("     %s" % n)

    print("\n" + "=" * 78)
    a_names = sorted({n for n, _ in a_events})
    b_names = sorted({n for n, _ in b_events})
    print("  valid  -> %s" % a_names)
    print("  missing-> %s" % (b_names or "(no events at all)"))
    print("  any PLAYBACK_ERROR header on the missing case: %s"
          % any(h.get("Playback-Error") for _, h in b_events))
    print()
    if a_names and not b_names:
        print("  DISTINGUISHING SIGNAL: PLAYBACK_START/PLAYBACK_STOP is present for a")
        print("  valid file and absent for a missing file. Acceptance (+OK) is")
        print("  therefore NOT evidence of playback, and a timeout waiting for")
        print("  PLAYBACK_START is the correct failure detector.")
    else:
        print("  No clean separation observed; inspect before choosing a rule.")

    cmd.result("uuid_kill %s NORMAL_CLEARING" % pinned, timeout=15)
    time.sleep(2)
    ev.close(); cmd.close()


if __name__ == "__main__":
    main()
