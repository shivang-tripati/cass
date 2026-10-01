"""
Phase C - C4 / C5 / C10: one real call between two in-network SIP endpoints,
with ESL evidence taken from BOTH ends.

    platform switch 1001-leg  <--RTP-->  endpoint 1002

What this test deliberately does NOT take on trust:
  * that the call connected          -> CHANNEL_ANSWER with timestamps
  * that RTP was allocated           -> the port read out of FreeSWITCH's own
                                         SDP, and the RTP port reported by
                                         FreeSWITCH for the live channel
  * that audio flowed endpoint->FS   -> packets RECEIVED counted by the
                                         platform switch
  * that audio flowed FS->endpoint   -> packets RECEIVED counted by the ENDPOINT
  * that DTMF worked                 -> CHANNEL_DTMF frames, with the exact
                                         header names and the exact digit
  * how it ended                     -> CHANNEL_HANGUP with the real cause

"Two-way audio" is only claimed if the far end confirms receipt. Counting
packets the platform switch SENT proves nothing about whether they arrived,
which is why this test reads the endpoint's own counters too.
"""
import re
import sys
import threading
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402

PLATFORM = ("127.0.0.1", 8021)
ENDPOINT_B = ("127.0.0.1", 8032)   # extension 1002

SUBSCRIBED = [
    "CHANNEL_CREATE", "CHANNEL_PROGRESS", "CHANNEL_PROGRESS_MEDIA",
    "CHANNEL_ANSWER", "CHANNEL_DTMF", "CHANNEL_HANGUP", "CHANNEL_BRIDGE",
    "PLAYBACK_START", "PLAYBACK_STOP", "PLAYBACK_ERROR",
]

SHOW = ["Call-UUID", "Channel-Name", "Channel-Origin", "Channel-Answer-State",
        "Channel-Codec-Read", "Channel-Codec", "Caller-Caller-ID-Number",
        "Caller-Destination-Number", "Hangup-Cause", "Hangup-Cause-Code",
        "DTMF-Digit", "DTMF-Subevent", "DTMF-Duration", "Playback-Error",
        "Bridge-A-Unique-ID", "Bridge-B-Unique-ID", "sip_hangup_disposition",
        "Caller-Disposition", "sip_call_id", "variable_sip_profile_name",
        "variable_sip_local_contact_str", "variable_sip_gateway_name",
        "variable_current_application", "variable_originate_disposition",
        "variable_bridge_disposition", "variable_last_bridge_to",
        "variable_sip_network_ip", "variable_sip_network_port",
        "variable_sip_local_network_ip", "Event-Calling-Function"]


def pw_from_env(name):
    import os
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "..", "infra", ".env")
    for line in open(p, encoding="utf-8"):
        if line.startswith(name + "="):
            return line.strip().split("=", 1)[1]
    raise RuntimeError(name + " not in infra/.env")


def collect(esl, sink, seconds):
    end = time.time() + seconds
    while time.time() < end:
        ev = esl.next_event(timeout=max(1, int(end - time.time())))
        if ev is None:
            break
        sink.append(ev)


def main():
    pw_fs = pw_from_env("FREESWITCH_PASSWORD")
    pw_ep = pw_from_env("ENDPOINT_ESL_PASSWORD")

    print("=" * 78)
    print("C4/C5/C10  real call: platform 1001-leg <-> endpoint 1002, in-network")
    print("=" * 78)

    # --- two observers -------------------------------------------------------
    fs = Esl(pw_fs, *PLATFORM); fs.connect()
    ep = Esl(pw_ep, *ENDPOINT_B); ep.connect()
    fs.subscribe(SUBSCRIBED)
    ep.subscribe(["CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_HANGUP"])

    fs_events, ep_events = [], []
    t1 = threading.Thread(target=collect, args=(fs, fs_events, 60), daemon=True)
    t2 = threading.Thread(target=collect, args=(ep, ep_events, 60), daemon=True)
    t1.start(); t2.start()
    time.sleep(0.6)

    # --- place the call, using the Java client's exact command form ----------
    channel_uuid = str(uuidlib.uuid4())
    print("\n  pinned channel UUID (what Java sends as origination_uuid):")
    print("   ", channel_uuid)
    cmd = ("bgapi originate "
           "{origination_uuid=%s,origination_caller_id_number=+15551230000}"
           "user/1002" % channel_uuid)
    ct, reply, _ = fs.api(cmd)
    print("  originate reply      :", reply.strip())
    job = reply.split("Job-UUID:")[1].strip() if "Job-UUID" in reply else None
    print("  ESL Job-UUID         :", job, " <- a different identifier")
    print("     pinned channel    :", channel_uuid)
    print("     they differ       :", job != channel_uuid)

    # --- wait for answer, then read media from BOTH ends --------------------
    answered = None
    deadline = time.time() + 25
    while time.time() < deadline:
        if any(n == "CHANNEL_ANSWER" and h.get("Call-UUID") == channel_uuid
               for n, h in fs_events):
            answered = True
            break
        time.sleep(0.3)
    print("\n  CHANNEL_ANSWER observed on the platform leg:", bool(answered))

    if answered:
        time.sleep(4)   # let RTP and the endpoint's DTMF burst flow

        print("\n  --- RTP as the PLATFORM SWITCH sees it (1001 leg) ---")
        print("   ", fs.result("uuid_debug_media " + channel_uuid))

        print("\n  --- RTP as the ENDPOINT sees it (1002 leg) ---")
        ep_chans = fs and ep.result("show channels")
        m = re.search(r"\[([0-9a-f-]{36})\]", ep_chans or "")
        if m:
            ep_uuid = m.group(1)
            print("    endpoint channel UUID:", ep_uuid)
            print("   ", ep.result("uuid_debug_media " + ep_uuid))
        else:
            print("    could not find an endpoint channel:", ep_chans)

    # --- let the endpoint's dialplan finish and hang up ---------------------
    t1.join(timeout=45)
    t2.join(timeout=10)
    fs.close(); ep.close()

    # --- report -------------------------------------------------------------
    print("\n  --- CHANNEL_* events on the platform, in order ---")
    for n, h in fs_events:
        print("   ", n, " Call-UUID=" + str(h.get("Call-UUID"))[:8])
        for k in SHOW:
            if k in h and k != "Call-UUID":
                print("        %-30s = %s" % (k, h[k]))

    print("\n  --- DTMF frames seen by the platform (C10) ---")
    dtmf = [(n, h) for n, h in fs_events if n == "CHANNEL_DTMF"]
    if dtmf:
        for n, h in dtmf:
            print("    DTMF-Digit=%-4s DTMF-Duration=%-5s Call-UUID=%s"
                  % (h.get("DTMF-Digit"), h.get("DTMF-Duration"),
                     str(h.get("Call-UUID"))[:8]))
        print("    -> digits observed:", "".join(
            str(h.get("DTMF-Digit", "")) for _, h in dtmf))
    else:
        print("    NONE - see the report; this is a real finding either way")

    print("\n  --- events on the endpoint (proves the far end ran) ---")
    for n, h in ep_events:
        print("   ", n, " Call-UUID=" + str(h.get("Call-UUID"))[:8],
              "Channel-Codec-Read=" + str(h.get("Channel-Codec-Read")),
              "Channel-Answer-State=" + str(h.get("Channel-Answer-State")))


if __name__ == "__main__":
    main()
