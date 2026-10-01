"""
Phase C - C4 / C5 / C8 / C9 / C10, all from ONE real call.

    platform switch (A-leg, UUID pinned)  <-- SIP + RTP -->  endpoint 1002

The call form that works, established by measurement in
c4_dialstrings.py and confirmed in the platform's own log:

    bgapi originate {origination_uuid=<uuid>,origination_caller_id_number=<cid>}
                    sofia/internal/1002@172.25.0.2 &park()

    - the pinned origination_uuid becomes the A-LEG's UUID, so the caller of
      the API holds the channel it created. This is the property the Java
      client depends on.
    - the trailing &park() keeps the A-leg alive under the caller's control
      instead of handing it to the dialplan.
    - WITHOUT &park() the A-leg enters the stock dialplan, which for a 4-digit
      number does not route to the registered contact at all: it runs the
      stock "change default_password / sleep(10000)" branch. Observed.

Everything asserted below is read back from FreeSWITCH, from BOTH ends:

  C4   the call connects: CHANNEL_ANSWER on the platform, with the pinned UUID
  C5   RTP allocated (the port, and packets in each direction)
       - platform "packets received" proves endpoint -> platform audio
       - ENDPOINT  "packets received" proves platform -> endpoint audio
       Counting what the platform SENT would prove nothing about arrival.
  C8   PLAYFILE: uuid_broadcast of a real file, and the playback events
  C9   PLAYBACK FAILURE: uuid_broadcast of a path that does not exist, and the
       REAL header and text FreeSWITCH emits - Phase B had this as
       NOT YET TESTED and recorded the header as unknown.
  C10  DTMF: the endpoint's dialplan emits a known digit sequence, and the
       platform's CHANNEL_DTMF headers are captured verbatim.
"""
import re
import sys
import threading
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402

PLATFORM = ("127.0.0.1", 8021)
ENDPOINT_B = ("127.0.0.1", 8032)      # extension 1002
DEST = "sofia/internal/1002@172.25.0.2"
GOOD_FILE = "/media/obd/phase-c-test-tone.wav"
BAD_FILE = "/media/obd/phase-c-deliberately-absent.wav"

SUBS = ["CHANNEL_CREATE", "CHANNEL_PROGRESS", "CHANNEL_ANSWER", "CHANNEL_DTMF",
        "CHANNEL_HANGUP", "CHANNEL_BRIDGE", "PLAYBACK_START", "PLAYBACK_STOP",
        "PLAYBACK_ERROR"]

SHOW = ["Channel-Name", "Channel-Origin", "Channel-Answer-State",
        "Channel-Codec-Read", "Channel-Codec", "Caller-Caller-ID-Number",
        "Caller-Destination-Number", "Hangup-Cause", "Hangup-Cause-Code",
        "DTMF-Digit", "DTMF-Subevent", "DTMF-Duration", "Playback-Error",
        "sip_hangup_disposition", "Caller-Disposition",
        "variable_current_application", "variable_sip_profile_name",
        "variable_sip_local_network_ip", "variable_sip_network_ip",
        "variable_sip_network_port", "sip_call_id"]


def pw(name):
    import os
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "..", "infra", ".env")
    for line in open(p, encoding="utf-8"):
        if line.startswith(name + "="):
            return line.strip().split("=", 1)[1]
    raise RuntimeError(name)


def collect(esl, sink, seconds):
    end = time.time() + seconds
    while time.time() < end:
        ev = esl.next_event(timeout=max(1, int(end - time.time())))
        if ev is None:
            break
        sink.append(ev)


def main():
    fs = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM)
    fs.connect()
    ep = Esl(pw("ENDPOINT_ESL_PASSWORD"), *ENDPOINT_B)
    ep.connect()
    fs.subscribe(SUBS)
    ep.subscribe(["CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_HANGUP"])

    fs_ev, ep_ev = [], []
    threading.Thread(target=collect, args=(fs, fs_ev, 75), daemon=True).start()
    threading.Thread(target=collect, args=(ep, ep_ev, 75), daemon=True).start()
    time.sleep(0.5)

    cu = str(uuidlib.uuid4())
    print("=" * 78)
    print("C4/C5/C8/C9/C10   one real call, evidence from both ends")
    print("=" * 78)
    print("  pinned A-leg UUID :", cu)
    cmd = ("bgapi originate {origination_uuid=%s,"
           "origination_caller_id_number=+15551230000}%s &park()" % (cu, DEST))
    ct, reply, _ = fs.api(cmd)
    job = reply.split("Job-UUID:")[1].strip() if "Job-UUID" in reply else "-"
    print("  ESL Job-UUID      :", job)
    print("  pinned == Job-UUID:", cu == job,
          "  <- Job-UUID is a task id, not the channel")

    # wait for answer
    answered = False
    end = time.time() + 30
    while time.time() < end and not answered:
        answered = any(n == "CHANNEL_ANSWER" and h.get("Call-UUID") == cu
                       for n, h in fs_ev)
        time.sleep(0.3)
    print("\n  [C4] CHANNEL_ANSWER on the pinned A-leg:", answered)
    if not answered:
        print("  call did not answer; aborting rather than reporting a PASS")
        return

    time.sleep(4)   # let the endpoint's DTMF burst and prompt flow

    # ---- C5: media, read from BOTH ends ---------------------------------
    print("\n  [C5] RTP as the PLATFORM sees the A-leg")
    print("   ", (fs.result("uuid_debug_media " + cu) or "<none>").strip())

    ep_chans = ep.result("show channels") or ""
    m = re.search(r"\[([0-9a-f-]{36})\]", ep_chans)
    if m:
        ep_uuid = m.group(1)
        print("\n  [C5] RTP as the ENDPOINT sees its leg  (channel %s)" % ep_uuid)
        print("   ", (ep.result("uuid_debug_media " + ep_uuid) or "<none>").strip())
    else:
        print("\n  [C5] endpoint channel not found:", ep_chans.strip()[:120])

    # ---- C8: PLAYFILE, the Java mechanism --------------------------------
    print("\n  [C8] PLAYFILE via uuid_broadcast (the command the Java client uses)")
    print("       file:", GOOD_FILE)
    print("   reply:", fs.result("uuid_broadcast %s %s aleg" % (cu, GOOD_FILE)).strip()[:80])
    time.sleep(6)

    # ---- C9: deliberate playback failure ----------------------------------
    print("\n  [C9] deliberate PLAYBACK FAILURE, path does not exist")
    print("       file:", BAD_FILE)
    print("   reply:", fs.result("uuid_broadcast %s %s aleg" % (cu, BAD_FILE)).strip()[:80])
    time.sleep(6)

    # ---- hang up, the way the Java client does ----------------------------
    print("\n  [C14] hang up via uuid_kill (the command the Java client uses)")
    print("   reply:", fs.result("uuid_kill %s NORMAL_CLEARING" % cu).strip()[:80])
    time.sleep(4)

    # ---- report ----------------------------------------------------------
    print("\n  --- platform events for the pinned channel, verbatim headers ---")
    for n, h in fs_ev:
        if h.get("Call-UUID") != cu:
            continue
        print("   ", n)
        for k in SHOW:
            if k in h:
                print("        %-30s = %s" % (k, h[k]))

    print("\n  [C8] playback events:")
    pb = [(n, h) for n, h in fs_ev if n.startswith("PLAYBACK")]
    if pb:
        for n, h in pb:
            print("    %-16s Playback-Error=%s" % (n, h.get("Playback-Error")))
    else:
        print("    NONE captured")

    print("\n  [C10] DTMF seen by the platform:")
    dt = [(n, h) for n, h in fs_ev if n == "CHANNEL_DTMF"]
    if dt:
        for n, h in dt:
            print("    DTMF-Digit=%-4r DTMF-Subevent=%-8r DTMF-Duration=%-5r"
                  % (h.get("DTMF-Digit"), h.get("DTMF-Subevent"),
                     h.get("DTMF-Duration")))
        print("    digits in order:", "".join(str(h.get("DTMF-Digit", ""))
                                               for _, h in dt))
    else:
        print("    NONE captured")

    print("\n  --- endpoint events (independent confirmation the far end ran) ---")
    for n, h in ep_ev:
        print("   ", n, "uuid=" + str(h.get("Call-UUID"))[:8],
              "codec=" + str(h.get("Channel-Codec-Read")),
              "answer-state=" + str(h.get("Channel-Answer-State")))

    fs.close(); ep.close()


if __name__ == "__main__":
    main()
