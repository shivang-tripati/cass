"""
Phase C - C6/C7/C9/C14: capture REAL ESL events from REAL calls.

Uses the same command forms the Java EslClient issues:
    bgapi originate {origination_uuid=<uuid>,origination_caller_id_number=<callerId>}
                    sofia/gateway/<gw>/<dest>
    uuid_broadcast <uuid> <path> aleg
    uuid_kill <uuid> NORMAL_CLEARING

Everything printed here is observed, not assumed. The point of this script is
to capture the EXACT event header names and values this FreeSWITCH emits, so
the documentation can be corrected.
"""
import sys
import threading
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl, env_password  # noqa: E402

SUBSCRIBED = [
    "CHANNEL_CREATE", "CHANNEL_PROGRESS", "CHANNEL_PROGRESS_MEDIA",
    "CHANNEL_ANSWER", "CHANNEL_DTMF", "CHANNEL_HANGUP",
    "PLAYBACK_START", "PLAYBACK_STOP", "PLAYBACK_ERROR", "CHANNEL_BRIDGE",
]

INTERESTING_HEADERS = [
    "Call-UUID", "Channel-UUID", "Event-Name", "Event-Subclass",
    "Unique-ID", "Caller-Caller-ID-Number", "Caller-Destination-Number",
    "Caller-Anonymous", "Channel-Name", "Channel-Origin",
    "Channel-Answered", "Channel-Answer-State", "Channel-Codec",
    "Channel-Codec-Read", "Hangup-Cause", "Hangup-Cause-Code",
    "DTMF-Digit", "DTMF-Subevent", "DTMF-Duration",
    "Playback-Error", "Bridge-A-Unique-ID", "Bridge-B-Unique-ID",
    "sip_call_id", "Caller-Disposition", "sip_hangup_disposition",
    "sip_term_status", "variable_sip_profile_name",
]


def run(desc, dest, extra_before=None, after=None, wait=25):
    """Subscribe, originate one call, and print every event frame received."""
    print("=" * 78)
    print("TEST:", desc)
    print("  dial string:", dest)
    print("=" * 78)

    channel_uuid = str(uuidlib.uuid4())
    print("  pinned channel UUID (origination_uuid):", channel_uuid)

    ctl = Esl(env_password())
    ctl.connect()
    ctl.subscribe(SUBSCRIBED)
    print("  subscribed to %d event names" % len(SUBSCRIBED))

    cmd = ("bgapi originate "
           "{origination_uuid=%s,origination_caller_id_number=+15551230000}"
           "%s" % (channel_uuid, dest))
    ct, reply, body = ctl.api(cmd)
    print("  originate reply :", reply.strip())
    print("  -> reply Content-Type:", ct)
    if "Job-UUID" in reply:
        job = reply.split("Job-UUID:")[1].strip()
        print("  -> JOB-UUID (NOT a channel):", job)
        print("     channel UUID we pinned  :", channel_uuid)
        print("     they differ:", job != channel_uuid)
    else:
        job = None

    if extra_before:
        print("  pre-answer action:", extra_before)

    events = []
    deadline = time.time() + wait
    while time.time() < deadline:
        ev = ctl.next_event(timeout=max(1, int(deadline - time.time())))
        if ev is None:
            break
        name, hdrs = ev
        events.append((name, hdrs))
        if hdrs.get("Call-UUID") != channel_uuid and name != "CHANNEL_CREATE":
            continue
        keep = {k: v for k, v in hdrs.items() if k in INTERESTING_HEADERS}
        print("  EVENT %-22s Call-UUID=%s" % (name, (hdrs.get("Call-UUID") or "-")[:8]))
        for k in INTERESTING_HEADERS:
            if k in keep and k not in ("Call-UUID",):
                print("        %-28s = %s" % (k, keep[k]))
        if after and name == after[0]:
            after[1]()

    ctl.close()
    print("  -- %d event(s) captured --" % len(events))
    print()
    return events, channel_uuid, job


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "all"

    if which in ("all", "loopback"):
        # A call that genuinely connects and completes, with no remote party
        # needed: loopback runs the application in-process.
        run("C6.2 loopback call (ANSWER + hangup, establishes event shape)",
            "loopback/app/answer")

    if which in ("all", "nomedia"):
        # Real SIP originate through the gateway to a number nobody answers.
        # Exercises SDP, provisional responses and a REAL hangup cause.
        run("C14.1 originate via fs-gateway to an unanswerable number "
            "(real SIP + real hangup cause)",
            "sofia/gateway/fs-gateway/1001",
            wait=30)

    if which in ("all", "badprofile"):
        # Deliberately impossible destination: proves FreeSWITCH's own error
        # text for a bad dial string, which is what the platform maps.
        run("C14.2 originate to a non-existent profile (originate-level failure)",
            "sofia/no-such-profile/1001",
            wait=12)
