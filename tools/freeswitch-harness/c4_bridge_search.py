"""
Phase C - C4: find the dial string that produces a real BRIDGE to a registered
in-network endpoint, and show why each candidate fails.

The requirement is a bridge, not merely an answered channel. Three separate
things have been observed to go wrong, and they look identical from outside:

  * "No origination URL specified!"      - the URL was not parsed at all
  * "No dial-string available..."         - the directory cannot build a target
  * a channel that answers but never bridges

So each candidate is issued and the platform's own log is consulted for the
reason, rather than trusting the command's +OK reply.
"""
import sys
import threading
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402
from c4_bridge_full import pw  # noqa: E402

CANDIDATES = [
    "user/1002",
    "user/1002 &park()",
    "user/1002 1002",
    "user/1002 &park() 1002",
    "sofia/internal/1002@172.25.0.2 &park()",
    "loopback/dial/user/1002",
]
SUBS = ["CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_BRIDGE", "CHANNEL_HANGUP"]


def main():
    e = Esl(pw("FREESWITCH_PASSWORD"))
    e.connect()
    e.subscribe(SUBS)
    for dest in CANDIDATES:
        got = []
        threading.Thread(target=lambda g=got: _pump(e, g, 14), daemon=True).start()
        time.sleep(0.3)
        cu = str(uuidlib.uuid4())
        ct, reply, _ = e.api("bgapi originate {origination_uuid=%s,"
                             "origination_caller_id_number=+15551230000}%s"
                             % (cu, dest))
        time.sleep(14)
        names = [n for n, _ in got]
        print("  %-42s" % dest)
        print("      events      : %s" % (", ".join(sorted(set(names))) or "none"))
        print("      pinned chan : %s  (answered: %s, bridged: %s)"
              % (cu[:8], "CHANNEL_ANSWER" in names, "CHANNEL_BRIDGE" in names))
        for n, h in got:
            if n == "CHANNEL_BRIDGE":
                print("        bridge A=%s B=%s"
                      % (str(h.get("Bridge-A-Unique-ID"))[:8],
                         str(h.get("Bridge-B-Unique-ID"))[:8]))
        e.result("uuid_kill %s NORMAL_CLEARING" % cu)
        time.sleep(1)
        print()
    e.close()


def _pump(esl, sink, seconds):
    end = time.time() + seconds
    while time.time() < end:
        ev = esl.next_event(timeout=max(1, int(end - time.time())))
        if ev is None:
            continue
        sink.append(ev)


if __name__ == "__main__":
    print("=" * 78)
    print("C4: which dial string produces a real bridge to extension 1002?")
    print("=" * 78)
    main()
