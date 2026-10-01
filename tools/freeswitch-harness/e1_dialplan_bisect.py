"""
Phase E.1: bisect the endpoint dialplan to find what tears the call down.

ESTABLISHED SO FAR
==================
A call through the local gateway is answered, then destroyed ~600 ms later with
Hangup-Cause = DESTINATION_OUT_OF_ORDER. That 600 ms lifetime is the whole
explanation for the Phase E contradiction: a command issued a second later is
addressing a channel that no longer exists, so:

    api uuid_broadcast <uuid> <file> aleg  ->  -ERR invalid uuid
    api uuid_kill <uuid> NORMAL_CLEARING   ->  -ERR No such channel!
    api uuid_dump <uuid>                   ->  sometimes succeeds (races the teardown)

None of those errors is about the UUID.

REJECTED SO FAR
===============
`send_dtmf` was removed at runtime and the teardown persisted, so it is not the
sole cause. The next candidate is `playback tone_stream://...`, which generates
a *tone* as audio and therefore also depends on the media path being ready.

METHOD
======
The endpoint's dialplan is reduced in stages, at runtime, inside the container.
Nothing in the repository is touched, and the committed file is restored at the
end. Each stage asks the only question that matters: does the channel survive?

  stage 1  as committed
                     answer -> send_dtmf -> playback -> wait -> hangup
  stage 2  no send_dtmf
                     answer ->           playback -> wait -> hangup
  stage 3  no send_dtmf, no playback
                     answer ->                     wait -> hangup

If stage 3 survives, the cause is in the dialplan and can be bisected further.
If stage 3 still dies, the cause is in the SIP/SDP/media negotiation itself and
no dialplan change will fix it - which is a materially different conclusion and
the reason this is a bisection rather than another guess.
"""
import subprocess
import sys
import threading
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402
from c4_bridge_full import pw  # noqa: E402

ENDPOINT_C = "obd-fs-endpoint-1002"
DIALPLAN = "/etc/freeswitch/dialplan/obd-endpoint.xml"
FILE = "/media/obd/phase-e-rtp-probe.wav"

SUBS = ["CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_HANGUP",
        "PLAYBACK_START", "PLAYBACK_STOP", "CHANNEL_DTMF"]

# A minimal dialplan that answers and waits. Rendered from the same root element
# and context name as the real one, so only the actions differ.
MINIMAL = """<?xml version="1.0"?>
<context name="obd-endpoint">
  <extension name="e1-probe">
    <condition field="${destination_number}" expression="^\\d+$">
      <action application="answer"/>
      <action application="wait" data="20000"/>
      <action application="hangup"/>
    </condition>
  </extension>
</context>
"""


def sh(script):
    return subprocess.run(["docker", "exec", ENDPOINT_C, "sh", "-c", script],
                          capture_output=True, text=True, timeout=60)


def put(text):
    """Write a dialplan into the container without shell quoting hazards."""
    p = subprocess.run(["docker", "exec", "-i", ENDPOINT_C, "sh", "-c",
                        "cat > %s" % DIALPLAN],
                       input=text, capture_output=True, text=True, timeout=60)
    return p.returncode


def pump(esl, sink, seconds):
    end = time.time() + seconds
    while time.time() < end:
        try:
            e = esl.next_event(timeout=1)
        except Exception:
            return
        if e is not None:
            sink.append(e)


def one_call(cmd, got, label, hold=8):
    p = str(uuidlib.uuid4())
    t0 = time.time()
    base = len(got)
    cmd.api("bgapi originate {origination_uuid=%s,"
            "origination_caller_id_number=+15551230000}"
            "sofia/gateway/local-endpoint-1002/1002 &park()" % p, timeout=20)
    hu = None
    for _ in range(hold):
        time.sleep(1)
        hu = next((h for n, h in got[base:]
                   if n == "CHANNEL_HANGUP" and h.get("Unique-ID") == p), None)
        if hu:
            break
    life = time.time() - t0
    names = [n for n, h in got[base:] if h.get("Unique-ID") == p]
    print("  %-26s life=%5.1fs  hangup=%-26s" % (label, life,
                                                 (hu or {}).get("Hangup-Cause", "(ALIVE)")))
    print("       events: %s" % (" -> ".join(names) or "none"))
    return p, hu, life


def main():
    ev = Esl(pw("FREESWITCH_PASSWORD")); ev.connect(); ev.subscribe(SUBS)
    cmd = Esl(pw("FREESWITCH_PASSWORD")); cmd.connect()
    epl = Esl(pw("ENDPOINT_ESL_PASSWORD"), "127.0.0.1", 8032); epl.connect()
    got = []
    threading.Thread(target=pump, args=(ev, got, 300), daemon=True).start()
    time.sleep(0.5)

    print("=" * 78)
    print("PHASE E.1 - bisecting the endpoint dialplan")
    print("=" * 78)

    print("\nSTAGE 1  as committed (answer, send_dtmf, playback, wait, hangup)")
    p1, h1, l1 = one_call(cmd, got, "stage 1 committed")
    if h1 is None:
        cmd.result("uuid_kill %s NORMAL_CLEARING" % p1, timeout=10)
    time.sleep(2)

    print("\nSTAGE 3  minimal: answer, wait 20s, hangup  (no DTMF, no playback)")
    sh("cp %s /opt/obd-dialplan.bak" % DIALPLAN)
    put(MINIMAL)
    epl.api("api reloadxml", timeout=10)
    time.sleep(3)
    p3, h3, l3 = one_call(cmd, got, "stage 3 minimal", hold=12)

    if h3 is None:
        print("\n  -> STAGE 3 SURVIVED. The channel is now long-lived.")
        print("  -> The exact Java command can finally be tested on a live channel:")
        base = len(got)
        r = cmd.result("uuid_broadcast %s %s aleg" % (p3, FILE))
        time.sleep(8)
        pb = [n for n, h in got[base:] if h.get("Unique-ID") == p3
              and n.startswith("PLAYBACK")]
        print("\n     api uuid_broadcast <uuid> <file> aleg -> %r" % r)
        print("     playback events on the channel        -> %s" % (pb or "none"))
        sdp = (cmd.result("uuid_getvar %s rtp_remote_sdp_str" % p3) or "").strip()
        print("     rtp_remote_sdp_str                    -> %s"
              % ("present" if sdp not in ("", "_undef_") else "_undef_"))
        rk = cmd.result("uuid_kill %s NORMAL_CLEARING" % p3)
        print("     api uuid_kill <uuid>                  -> %r" % rk)
    else:
        print("\n  -> STAGE 3 STILL DIES. The cause is NOT in the endpoint's")
        print("     dialplan actions; it is in the SIP/SDP/media negotiation.")

    print("\nRESTORE  committed dialplan")
    sh("cp /opt/obd-dialplan.bak %s && rm -f /opt/obd-dialplan.bak" % DIALPLAN)
    epl.api("api reloadxml", timeout=10)
    time.sleep(2)
    chk = sh("grep -c 'application=' %s" % DIALPLAN).stdout.strip()
    print("  action lines restored: %s" % chk)

    ev.close(); cmd.close(); epl.close()


if __name__ == "__main__":
    main()
