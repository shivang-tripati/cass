"""
Phase C - C5: does audio ACTUALLY flow, in both directions?

Signalling is already proven: a platform-originated call to extension 1002
reaches the endpoint, is answered, and produces CHANNEL_BRIDGE. But a bridge and
a connected call do not prove media. So this measures RTP directly, from both
ends, while a call is up:

  * the platform's own counters  -> proves endpoint -> platform audio arrived
  * the ENDPOINT's own counters  -> proves platform -> endpoint audio arrived

Counting only what the platform SENT would be worthless: packets handed to the
socket are not packets that arrived. A two-way claim needs the far end to
confirm receipt, which is why the endpoint's ESL is published at all.
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
EP_B = ("127.0.0.1", 8032)
DEST = "sofia/internal/1002@172.25.0.2"
SUBS = ["CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_BRIDGE", "CHANNEL_HANGUP",
        "PLAYBACK_START", "PLAYBACK_STOP"]


def pump(esl, sink, seconds):
    end = time.time() + seconds
    while time.time() < end:
        ev = esl.next_event(timeout=max(1, int(end - time.time())))
        if ev is not None:
            sink.append(ev)


def show(esl, uuid, label):
    out = esl.result("uuid_debug_media " + uuid) or ""
    print("\n  --- %s : %s ---" % (label, uuid[:8]))
    for line in out.splitlines():
        s = line.strip()
        if (s.startswith("Codec") or "packets" in s.lower() or "bytes" in s.lower()
                or s.startswith("Remote") or "read" in s.lower()
                or s.startswith("Local") or "timer" in s.lower()):
            print("     ", s)


def main():
    fs = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM); fs.connect()
    ep = Esl(pw("ENDPOINT_ESL_PASSWORD"), *EP_B); ep.connect()
    fs.subscribe(SUBS); ep.subscribe(SUBS)
    fev, eev = [], []
    threading.Thread(target=pump, args=(fs, fev, 70), daemon=True).start()
    threading.Thread(target=pump, args=(ep, eev, 70), daemon=True).start()
    time.sleep(0.5)

    cu = str(uuidlib.uuid4())
    print("=" * 78)
    print("C5  RTP measurement, call up: %s" % DEST)
    print("=" * 78)
    ct, reply, _ = fs.api("bgapi originate {origination_uuid=%s,"
                          "origination_caller_id_number=+15551230000}%s &park()"
                          % (cu, DEST), timeout=20)
    print("  originate reply:", reply.strip())

    bridged = False
    end = time.time() + 35
    while time.time() < end and not bridged:
        bridged = any(n == "CHANNEL_BRIDGE" for n, _ in fev)
        time.sleep(0.4)
    print("\n  bridge observed:", bridged)

    legs = []
    for n, h in fev:
        if n == "CHANNEL_BRIDGE":
            legs = [x for x in (h.get("Bridge-A-Unique-ID"),
                                h.get("Bridge-B-Unique-ID")) if x]
            print("  bridge legs: %s" % ", ".join(x[:8] for x in legs))

    if not legs:
        print("  no bridge; platform saw:")
        for n, h in fev:
            print("     ", n, str(h.get("Unique-ID"))[:8])
        return

    print("\n  letting media run for 12s (the endpoint's dialplan plays audio")
    print("  toward the platform, and the platform's leg is a media sink)...")
    time.sleep(12)

    print("\n  [platform side]")
    for leg in legs:
        show(fs, leg, "platform leg")
    print("\n  [endpoint 1002 side - the far end]")
    ech = ep.result("show channels") or ""
    print("  endpoint channels:", ech.strip()[:200] or "<none>")
    m = re.search(r"\[([0-9a-f-]{36})\]", ech)
    if m:
        show(ep, m.group(1), "endpoint 1002 leg")

    print("\n  --- platform events ---")
    for n, h in fev:
        extra = ""
        if h.get("variable_local_media_port"):
            extra = " local_media_port=%s" % h["variable_local_media_port"]
        if h.get("Channel-Read-Codec-Name"):
            extra += " codec=%s" % h["Channel-Read-Codec-Name"]
        if h.get("Answer-State"):
            extra += " state=%s" % h["Answer-State"]
        print("   %-18s %s%s" % (n, str(h.get("Unique-ID"))[:8], extra))
    print("\n  --- endpoint 1002 events ---")
    for n, h in eev:
        print("   %-18s %s" % (n, str(h.get("Unique-ID"))[:8]))

    for leg in legs:
        fs.result("uuid_kill %s NORMAL_CLEARING" % leg)
    fs.result("uuid_kill %s NORMAL_CLEARING" % cu)
    time.sleep(2)
    fs.close(); ep.close()


if __name__ == "__main__":
    main()
