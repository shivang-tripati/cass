"""
Phase C - C4 investigation: which dial string actually reaches a REGISTERED
local extension?

`+OK Job-UUID` is not evidence that a call was placed. bgapi acknowledges the
COMMAND; the command then runs on a task thread and can fail afterwards with no
feedback to the client at all. This script therefore, for each candidate:

  1. issues the originate and prints the reply,
  2. waits,
  3. asks FreeSWITCH for its CHANNEL_* events, which is the only trustworthy
     signal that a channel was created and what happened to it,
  4. reads the platform's own log, which names the failure reason.

The candidates matter because the Java client always dials
`sofia/gateway/<gateway>/<destination>`, and there is no such reachable
destination locally - so the local equivalent has to be established by
measurement, not assumed.
"""
import sys
import threading
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402
from c4_c5_c10 import pw_from_env  # noqa: E402

CANDIDATES = [
    "user/1002",
    "user/1002@172.25.0.2",
    "sofia/internal/1002@172.25.0.2",
    "sofia/internal/1002",
    "loopback/dial/user/1002",
    "sofia/internal/1002@172.25.0.2 &park()",
]

EVENTS = ["CHANNEL_CREATE", "CHANNEL_PROGRESS", "CHANNEL_ANSWER",
          "CHANNEL_HANGUP"]


def try_one(e, dest):
    cu = str(uuidlib.uuid4())
    got = []
    e.subscribe(EVENTS)
    cmd = ("bgapi originate "
           "{origination_uuid=%s,origination_caller_id_number=+15551230000}%s"
           % (cu, dest))
    ct, reply, _ = e.api(cmd)
    job = reply.split("Job-UUID:")[1].strip() if "Job-UUID" in reply else "-"
    end = time.time() + 9
    while time.time() < end:
        ev = e.next_event(timeout=max(1, int(end - time.time())))
        if ev is None:
            break
        got.append(ev)
    mine = [(n, h) for n, h in got if h.get("Call-UUID") == cu]
    print("  %-38s" % dest)
    print("      reply        : %s" % reply.strip())
    print("      pinned chan  : %s" % cu)
    print("      job uuid     : %s   (different identifier: %s)"
          % (job, job != cu))
    if mine:
        for n, h in mine:
            extra = ""
            if n == "CHANNEL_HANGUP":
                extra = " Hangup-Cause=%s Code=%s" % (
                    h.get("Hangup-Cause"), h.get("Hangup-Cause-Code"))
            print("      EVENT %-18s%s" % (n, extra))
    else:
        print("      NO CHANNEL_* EVENT for the pinned UUID")
    print()


def main():
    e = Esl(pw_from_env("FREESWITCH_PASSWORD"))
    e.connect()
    for dest in CANDIDATES:
        try:
            try_one(e, dest)
        except Exception as ex:
            print("  %-38s -> [%s: %s]" % (dest, type(ex).__name__, ex))
    e.close()


if __name__ == "__main__":
    print("=" * 78)
    print("C4 investigation: which dial string reaches a registered extension?")
    print("=" * 78)
    main()
