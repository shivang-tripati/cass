"""
Phase D - J3 mechanism probe: the identity change a dialplan transfer causes.

The production-shaped originate (no &app) is currently failing silently at the
dialplan, which is a separate matter. J3 asks a narrower, mechanism-level
question that does not depend on it:

    when a channel is TRANSFERRED, does the pinned origination_uuid survive,
    and does the new channel link back to it?

The `transfer` app is exactly what the stock dialplan runs, so driving it
directly isolates the mechanism deterministically. Three forms are compared:

    &park()                       app bypasses the dialplan - baseline
    &transfer(1002 XML default)   transfer to another context - the mechanism
    &park() then a second channel  control

What is measured for every channel seen:
  - its own Unique-ID
  - whether it still reports the pinned value in variable_origination_uuid
  - whether ANY header links it back to the pinned channel

The third question is the one the J3 design depends on: if nothing links back,
the application cannot follow the relationship from events alone and must
resolve the live channel at command time instead.
"""
import re
import sys
import threading
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402
from c4_bridge_full import pw  # noqa: E402

SUBS = ["CHANNEL_CREATE", "CHANNEL_PROGRESS", "CHANNEL_PROGRESS_MEDIA",
        "CHANNEL_ANSWER", "CHANNEL_HANGUP", "CHANNEL_BRIDGE",
        "CHANNEL_EXECUTE", "CHANNEL_EXECUTE_COMPLETE",
        "PLAYBACK_START", "PLAYBACK_STOP", "PLAYBACK_ERROR", "CHANNEL_DTMF"]

LOOK = ["variable_origination_uuid", "Origination-UUID", "variable_bridge_uuid",
        "Bridge-A-Unique-ID", "Bridge-B-Unique-ID", "Other-Channel-UUID",
        "Unique-ID", "Channel-Call-UUID", "Call-UUID", "Caller-Unique-ID",
        "variable_uuid", "Channel-Name", "variable_current_application",
        "variable_dialed_extension", "variable_transfer_extension",
        "variable_originate_disposition"]


def pump(esl, sink, seconds):
    end = time.time() + seconds
    while time.time() < end:
        try:
            ev = esl.next_event(timeout=max(1, int(end - time.time())))
        except Exception:
            return
        if ev is not None:
            sink.append(ev)


def run_case(ev, cmd, plat, label, tail):
    print("\n" + "=" * 78)
    print("CASE: %s" % label)
    print("  tail: %s" % tail)
    print("=" * 78)
    dest = "sofia/internal/1002@%s" % plat
    pinned = str(uuidlib.uuid4())
    _, reply, _ = cmd.api("bgapi originate {origination_uuid=%s,"
                         "origination_caller_id_number=+15551230000}%s %s"
                         % (pinned, dest, tail), timeout=20)
    print("  pinned: %s" % pinned)
    print("  reply : %s" % reply.strip())

    events = []
    threading.Thread(target=pump, args=(ev, events, 16), daemon=True).start()
    time.sleep(16)

    order, by_channel = [], {}
    for n, h in events:
        u = h.get("Unique-ID")
        if u and u not in by_channel:
            by_channel[u] = []
            order.append(u)
        if u:
            by_channel[u].append((n, h))

    print("  channels: %d" % len(order))
    for u in order:
        print("    %s%s" % (u, "   <== PINNED" if u == pinned else ""))
        print("      events: %s" % ", ".join(n for n, _ in by_channel[u]))

    linked = False
    for u in order:
        if u == pinned:
            continue
        for n, h in by_channel[u]:
            for c in LOOK:
                if c in h and str(h[c]) == pinned:
                    print("    LINK FOUND: %s %s = %s" % (n, c, pinned))
                    linked = True

    if len(order) <= 1:
        print("  VERDICT: pinned identity preserved (no second channel).")
    elif linked:
        print("  VERDICT: a new channel appeared AND links back to the pinned UUID.")
    else:
        print("  VERDICT: a new channel appeared and DOES NOT link back.")
    for u in order:
        try:
            cmd.result("uuid_kill %s NORMAL_CLEARING" % u, timeout=8)
        except Exception:
            pass
    time.sleep(2)
    return len(order), linked


def main():
    ev = Esl(pw("FREESWITCH_PASSWORD")); ev.connect(); ev.subscribe(SUBS)
    cmd = Esl(pw("FREESWITCH_PASSWORD")); cmd.connect()
    m = re.search(r"SIP-IP\s+(\d+\.\d+\.\d+\.\d+)",
                  ev.result("sofia status profile internal") or "")
    if not m:
        print("could not determine the platform address")
        ev.close(); cmd.close(); return
    plat = m.group(1)
    print("platform address (discovered): %s" % plat)

    run_case(ev, cmd, plat, "baseline - app bypasses the dialplan", "&park()")
    run_case(ev, cmd, plat, "the mechanism - transfer to another context",
             "&transfer(1002 XML default)")

    ev.close(); cmd.close()


if __name__ == "__main__":
    main()
