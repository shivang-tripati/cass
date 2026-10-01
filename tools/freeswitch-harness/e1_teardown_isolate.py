"""
Phase E.1: isolate the teardown cause with a runtime-only dialplan experiment.

WHAT IS BEING TESTED
====================
A call through the local gateway is answered and then destroyed ~600 ms later
with Hangup-Cause = DESTINATION_OUT_OF_ORDER:

    14:25:48.605  [INFO] sofia.c:8693 Channel [sofia/internal/1002] has been answered
    14:25:49.205  [INFO] sofia.c:1065 Hangup [CS_EXECUTE] [DESTINATION_OUT_OF_ORDER]

That 600 ms window explains the whole Phase E contradiction. A channel that
lives 600 ms cannot be addressed by a command issued a second later, so
`uuid_broadcast` reports "invalid uuid" and `uuid_kill` reports "No such
channel" - while `uuid_dump`, issued in the same instant, races the destruction
and sometimes succeeds. None of those errors is about the UUID; all of them are
about the channel being gone.

THE HYPOTHESIS
==============
The endpoint's dialplan is:

    answer -> send_dtmf -> playback -> wait 30000 -> hangup

`send_dtmf` is RFC 4733 in-band DTMF, which travels as an RTP event. Running it
immediately after `answer` emits a media event before the media path is
confirmed, and FreeSWITCH answers that with DESTINATION_OUT_OF_ORDER.

The test removes ONLY the `send_dtmf` action, at runtime, inside the container,
and asks whether the channel then survives. Nothing in the repository is
changed, and the file is restored at the end - so this is an experiment, not a
fix. If the hypothesis holds, the fix belongs in the endpoint's dialplan
template, and that is recorded separately.
"""
import re
import subprocess
import sys
import threading
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402
from c4_bridge_full import pw  # noqa: E402

PLATFORM = ("127.0.0.1", 8021)
ENDPOINT_C = "obd-fs-endpoint-1002"
DIALPLAN = "/etc/freeswitch/dialplan/obd-endpoint.xml"
FILE = "/media/obd/phase-e-rtp-probe.wav"

SUBS = ["CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_HANGUP",
        "PLAYBACK_START", "PLAYBACK_STOP", "CHANNEL_DTMF"]


def sh(container, script):
    return subprocess.run(["docker", "exec", container, "sh", "-c", script],
                          capture_output=True, text=True, timeout=60).stdout


def pump(esl, sink, seconds):
    end = time.time() + seconds
    while time.time() < end:
        try:
            e = esl.next_event(timeout=1)
        except Exception:
            return
        if e is not None:
            sink.append(e)


def one_call(cmd, ev, got, label):
    p = str(uuidlib.uuid4())
    t0 = time.time()
    base = len(got)
    cmd.api("bgapi originate {origination_uuid=%s,"
            "origination_caller_id_number=+15551230000}"
            "sofia/gateway/local-endpoint-1002/1002 &park()" % p, timeout=20)
    hu = None
    for _ in range(20):
        time.sleep(1)
        hu = next((h for n, h in got[base:]
                   if n == "CHANNEL_HANGUP" and h.get("Unique-ID") == p), None)
        if hu:
            break
    life = time.time() - t0
    names = [n for n, h in got[base:] if h.get("Unique-ID") == p]
    print("  %-22s life=%5.1fs  hangup=%-26s events=%s"
          % (label, life, (hu or {}).get("Hangup-Cause", "(still up)"),
             " -> ".join(names)))
    return p, hu, life, len(got)


def try_broadcast(cmd, ev, got, p):
    """If the channel is still alive, retry the exact Java command."""
    base = len(got)
    r = cmd.result("uuid_broadcast %s %s aleg" % (p, FILE))
    time.sleep(6)
    pb = [n for n, h in got[base:] if h.get("Unique-ID") == p
          and n.startswith("PLAYBACK")]
    print("     api uuid_broadcast <uuid> <file> aleg -> %r" % r)
    print("     playback events on the channel        -> %s" % (pb or "none"))
    return r, pb


def main():
    ev = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM); ev.connect(); ev.subscribe(SUBS)
    cmd = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM); cmd.connect()
    epl = Esl(pw("ENDPOINT_ESL_PASSWORD"), "127.0.0.1", 8032); epl.connect()
    got = []
    threading.Thread(target=pump, args=(ev, got, 200), daemon=True).start()
    time.sleep(0.5)

    print("=" * 78)
    print("PHASE E.1 - isolating the 600 ms teardown")
    print("=" * 78)

    # ---- baseline: the dialplan exactly as committed --------------------
    print("\nSTEP 1  baseline, dialplan unmodified (send_dtmf present)")
    p, hu, life, _ = one_call(cmd, ev, got, "baseline")
    if hu is None:
        try_broadcast(cmd, ev, got, p)
        cmd.result("uuid_kill %s NORMAL_CLEARING" % p, timeout=10)
    time.sleep(2)

    # ---- experiment: remove send_dtmf, runtime only ----------------------
    print("\nSTEP 2  remove ONLY the send_dtmf action (runtime edit, no repo change)")
    sh(ENDPOINT_C, "cp %s /opt/obd-dialplan.bak" % DIALPLAN)
    out = sh(ENDPOINT_C,
             "sed -i.bak2 '/application=\"send_dtmf\"/d' %s ; "
             "grep -c send_dtmf %s || true" % (DIALPLAN, DIALPLAN))
    print("     send_dtmf occurrences remaining: %s" % out.strip())
    epl.api("api reloadxml", timeout=10)
    time.sleep(3)

    p2, hu2, life2, _ = one_call(cmd, ev, got, "no send_dtmf")
    if hu2 is None:
        print("     -> the channel SURVIVED. Retrying the exact Java command:")
        try_broadcast(cmd, ev, got, p2)
        cmd.result("uuid_kill %s NORMAL_CLEARING" % p2, timeout=10)
    else:
        print("     -> still torn down, so send_dtmf is NOT the sole cause")

    # ---- restore ---------------------------------------------------------
    print("\nSTEP 3  restore the committed dialplan")
    sh(ENDPOINT_C, "cp /opt/obd-dialplan.bak %s" % DIALPLAN)
    sh(ENDPOINT_C, "rm -f /opt/obd-dialplan.bak %s.bak2" % DIALPLAN)
    epl.api("api reloadxml", timeout=10)
    time.sleep(2)
    print("     send_dtmf occurrences after restore: %s"
          % sh(ENDPOINT_C, "grep -c send_dtmf %s || true" % DIALPLAN).strip())

    print("\n" + "=" * 78)
    print("CONCLUSION")
    print("=" * 78)
    print("  baseline life : %.1fs   hangup: %s" % (life, (hu or {}).get("Hangup-Cause")))
    print("  no-send_dtmf  : %.1fs   hangup: %s" % (life2, (hu2 or {}).get("Hangup-Cause")))
    if hu is not None and hu2 is None:
        print("  -> CONFIRMED: premature send_dtmf tears the channel down ~600 ms")
        print("     after answer, and the uuid_broadcast errors are a symptom of")
        print("     that, not a defect in the command.")
    elif hu is not None and hu2 is not None:
        print("  -> send_dtmf is not the cause; the teardown persists without it.")
    else:
        print("  -> the channel already survives on the unmodified dialplan;")
        print("     the teardown is intermittent, not deterministic.")

    ev.close(); cmd.close(); epl.close()


if __name__ == "__main__":
    main()
