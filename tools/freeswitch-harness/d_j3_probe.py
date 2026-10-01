"""
Phase D - J3 evidence probe, production-shaped.

Phase D brief, J3: prove how channel identity behaves across a dialplan
transfer, and base the fix on observed behaviour.

The previous probe used `&park()`, which BYPASSES the dialplan, so no transfer
occurred and the pinned UUID trivially survived. That is not the production
shape. EslClient.originate builds:

    bgapi originate {origination_uuid=<uuid>,origination_caller_id_number=<cid>}
                    sofia/gateway/<gw>/<dest>

with NO trailing application, so the originated leg ENTERS the dialplan and can
be transferred. This probe uses exactly that shape (against the internal profile,
since no carrier exists) and measures what happens.

Two things are established here, and the J3 design depends on both:
  1. does the pinned UUID survive, or does a new channel appear?
  2. if a new channel appears, does ANY header link it back to the pinned one?

The second question decides whether the application can follow the relationship
from events alone, or must resolve the live channel at command time.
"""
import re
import sys
import threading
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402
from c4_bridge_full import pw  # noqa: E402

# Production-shaped: no &app(), so the leg enters the dialplan.
#
# The platform's address is DISCOVERED, never hard-coded. Phase D found that the
# platform's container IP is not stable: recreating containers moved it from
# 172.25.0.2 to 172.25.0.4, and registrations keyed to the old address linger in
# the registrar for their full expiry. A hard-coded address silently produced
# NO_ROUTE_DESTINATION and looked like a FreeSWITCH defect.
DEST = None  # resolved at runtime, see main()

SUBS = ["CHANNEL_CREATE", "CHANNEL_PROGRESS", "CHANNEL_PROGRESS_MEDIA",
        "CHANNEL_ANSWER", "CHANNEL_HANGUP", "CHANNEL_BRIDGE",
        "CHANNEL_EXECUTE", "CHANNEL_EXECUTE_COMPLETE",
        "PLAYBACK_START", "PLAYBACK_STOP", "PLAYBACK_ERROR", "CHANNEL_DTMF"]

LINK = ["variable_origination_uuid", "Origination-UUID",
        "variable_bridge_uuid", "Bridge-A-Unique-ID", "Bridge-B-Unique-ID",
        "Other-Channel-UUID", "Unique-ID", "Channel-Call-UUID", "Call-UUID",
        "Caller-Unique-ID", "variable_uuid", "variable_current_application",
        "variable_originate_disposition", "variable_dialed_extension",
        "Channel-Name", "variable_originate_uuid"]


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
    # Two connections: one for events, one for commands. Sharing one socket
    # makes a command read an EVENT instead of its reply (observed in Phase C).
    ev = Esl(pw("FREESWITCH_PASSWORD")); ev.connect(); ev.subscribe(SUBS)
    cmd = Esl(pw("FREESWITCH_PASSWORD")); cmd.connect()

    # Discover the platform's own address the way the endpoint entrypoint does:
    # ask FreeSWITCH, do not assume it.
    plat_ip = (ev.result("sofia status profile internal") or "")
    m = re.search(r"SIP-IP\s+(\d+\.\d+\.\d+\.\d+)", plat_ip)
    if not m:
        print("could not determine the platform address from sofia status")
        ev.close(); cmd.close(); return
    plat = m.group(1)
    dest = "sofia/internal/1002@%s" % plat

    events = []
    threading.Thread(target=pump, args=(ev, events, 50), daemon=True).start()
    time.sleep(0.4)

    pinned = str(uuidlib.uuid4())
    print("=" * 78)
    print("J3 PROBE (production-shaped originate, no &app)")
    print("=" * 78)
    print("  platform address       :", plat, "(discovered, not assumed)")
    print("  pinned origination_uuid:", pinned)
    print("  dial string             :", dest)
    _, reply, _ = cmd.api("bgapi originate {origination_uuid=%s,"
                         "origination_caller_id_number=+15551230000}%s"
                         % (pinned, dest), timeout=20)
    print("  originate reply         :", reply.strip())

    time.sleep(24)

    order, by_channel = [], {}
    for n, h in events:
        uid = h.get("Unique-ID")
        if uid and uid not in by_channel:
            by_channel[uid] = []
            order.append(uid)
        if uid:
            by_channel[uid].append((n, h))

    print("\n  distinct channels observed: %d" % len(order))
    for i, uid in enumerate(order, 1):
        tag = "   <== PINNED" if uid == pinned else ""
        print("    %d. %s%s" % (i, uid, tag))
        print("         events: %s" % ", ".join(n for n, _ in by_channel[uid]))

    linked = False
    for uid in order:
        if uid == pinned:
            continue
        print("\n" + "-" * 78)
        print("SECOND CHANNEL %s - does anything link it to the pinned one?" % uid[:8])
        for n, h in by_channel[uid]:
            for c in LINK:
                if c in h:
                    val = str(h[c])
                    if val == pinned:
                        linked = True
                        print("    %-22s %-30s = %s   <<< LINKS TO PINNED"
                              % (n, c, val[:50]))
                    elif c in ("Unique-ID", "Channel-Call-UUID", "Call-UUID",
                               "Caller-Unique-ID", "variable_uuid"):
                        print("    %-22s %-30s = %s" % (n, c, val[:50]))
        if not linked:
            print("    >>> nothing links this channel to the pinned UUID <<<")

    print("\n" + "=" * 78)
    if len(order) == 1:
        print("RESULT: the pinned channel survived - no transfer occurred.")
    elif linked:
        print("RESULT: a new channel appeared AND links back to the pinned UUID.")
    else:
        print("RESULT: a new channel appeared and DOES NOT link back.")
        print("        -> the application cannot follow it from events alone.")

    for uid in order:
        try:
            cmd.result("uuid_kill %s NORMAL_CLEARING" % uid, timeout=8)
        except Exception:
            pass
    time.sleep(2)
    ev.close(); cmd.close()


if __name__ == "__main__":
    main()
