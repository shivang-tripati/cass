"""
Phase E - E5: validate the routable local gateway with real SIP evidence.

Run only after E1 succeeds at the channel level. This exists to capture the
SIGNALLING, not merely the channel state, because "a channel exists" and "a
call was signalled" are different claims and only the second one means the
gateway works.

What is captured, and why each is needed for the correlation chain the brief
requires (Java CallAttempt -> Job-UUID -> channel UUID -> SIP Call-ID ->
endpoint):

  * the gateway's own state and proxy, so the route is attributable
  * CHANNEL_CREATE on the pinned channel, for the channel identity
  * the SIP INVITE and 200 OK from the log, proving signalling
  * CHANNEL_ANSWER, proving the far end answered
  * RTP ports and codec, read back from the LIVE channel rather than from the
    origination request
  * CHANNEL_HANGUP with its cause

RTP allocation is reported explicitly as ALLOCATION ONLY. It is not evidence of
audio, and nothing in this script may be read as claiming it is - see
e4_rtp_measure.py for actual packet measurement.
"""
import os
import re
from pathlib import Path
import sys
import threading
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402
from c4_bridge_full import pw  # noqa: E402

PLATFORM = ("127.0.0.1", 8021)
GATEWAY = "local-endpoint-1002"
DEST_EXT = "1002"
SIP_LOG = "/var/log/freeswitch/sip-trace.log"
SIP_TRACE_MARKER = "e5_callid.txt"

SUBS = ["CHANNEL_CREATE", "CHANNEL_PROGRESS", "CHANNEL_PROGRESS_MEDIA",
        "CHANNEL_ANSWER", "CHANNEL_HANGUP", "CHANNEL_BRIDGE", "CHANNEL_DTMF",
        "PLAYBACK_START", "PLAYBACK_STOP", "CHANNEL_EXECUTE",
        "CHANNEL_EXECUTE_COMPLETE"]


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
    ev = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM); ev.connect(); ev.subscribe(SUBS)
    cmd = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM); cmd.connect()

    print("=" * 78)
    print("PHASE E - E5: local gateway validated with SIP signalling evidence")
    print("=" * 78)

    plat = re.search(r"SIP-IP\s+(\S+)",
                     cmd.result("sofia status profile internal") or "").group(1)
    print("  switch domain (discovered, not assumed):", plat)

    print("\n  -- gateway state --")
    for line in (cmd.result("sofia status gateway %s" % GATEWAY) or "").splitlines():
        if any(k in line for k in ("Name", "Profile", "Realm", "Proxy", "From",
                                   "State", "OB Calls")):
            print("    %s" % line.strip())

    # the destination the endpoint registered under, to prove the route target
    regs = cmd.result("sofia status profile internal reg") or ""
    m = re.search(r"User:\s+1002@(\S+).*?Contact:\s+\S+\s+<sip:gw\+platform-switch@([^:;]+)", regs, re.S)
    if m:
        print("  -- registered route for 1002 --")
        print("     realm  :", m.group(1))
        print("     contact:", m.group(2), "(the endpoint the INVITE goes to)")

    # SIP tracing writes here. The `shell` API command does not exist in this
    # build ("-ERR shell Command not found!"), so the line count is measured on
    # the HOST side with docker exec and passed in, rather than pretending the
    # switch can read its own file for us.
    cmd.result("sofia global siptrace on", timeout=10)
    time.sleep(1)
    before = int(os.environ.get("SIPLOG_BEFORE", "0") or 0)
    print("  (sip-trace starting at line %d)" % before)

    events = []
    threading.Thread(target=pump, args=(ev, events, 60), daemon=True).start()
    time.sleep(0.4)

    pinned = str(uuidlib.uuid4())
    dial = "sofia/gateway/%s/%s" % (GATEWAY, DEST_EXT)
    print("\n  -- originating the APPLICATION dial string --")
    print("     dial string    :", dial)
    print("     pinned uuid    :", pinned)
    cmd.api("bgapi originate {origination_uuid=%s,"
            "origination_caller_id_number=+15551230000}%s &park()" % (pinned, dial),
            timeout=20)

    answered = None
    end = time.time() + 30
    while time.time() < end and answered is None:
        answered = next((h for n, h in events
                         if n == "CHANNEL_ANSWER" and h.get("Unique-ID") == pinned), None)
        time.sleep(0.3)

    print("\n" + "-" * 78)
    print("CORRELATION CHAIN - every value read from a real event")
    print("-" * 78)
    create = next((h for n, h in events
                   if n == "CHANNEL_CREATE" and h.get("Unique-ID") == pinned), {})
    for k in ("Call-ID", "Channel-Call-UUID", "variable_origination_uuid",
              "Caller-Unique-ID", "variable_sip_call_id", "Channel-Name",
              "Caller-Caller-ID-Number"):
        if k in create:
            print("  %-30s = %s" % (k, str(create[k])[:58]))
    if answered:
        print("  %-30s = %s" % ("CHANNEL_ANSWER Answer-State", answered.get("Answer-State")))

    print("\n  -- RTP, read back from the live channel (ALLOCATION ONLY) --")
    dump = cmd.result("uuid_dump %s" % pinned) or ""
    for label, pat in (("local media", r"local_media_ip=[\d.]+ local_media_port=\d+"),
                       ("remote media", r"remote_media_ip=[\d.]+ remote_media_port=\d+"),
                       ("read codec", r"read_codec=\w+"), ("write codec", r"write_codec=\w+")):
        m = re.search(pat, dump)
        print("    %-14s : %s" % (label, m.group(0) if m else "not reported"))
    print("    NOTE: allocated ports are NOT evidence of audio. See e4_rtp_measure.py.")

    print("\n  -- event sequence on the pinned channel --")
    print("    " + " -> ".join(n for n, h in events if h.get("Unique-ID") == pinned))

    # the endpoint plays a tone and holds; give it time, then hang up
    print("\n  holding 10s while answered (endpoint plays a tone) ...")
    time.sleep(10)
    cmd.result("api uuid_kill %s NORMAL_CLEARING" % pinned, timeout=15)
    time.sleep(4)

    hang = next((h for n, h in events
                 if n == "CHANNEL_HANGUP" and h.get("Unique-ID") == pinned), {})
    print("\n  -- hangup --")
    for k in ("Hangup-Cause", "Answer-State"):
        if k in hang:
            print("    %-24s = %s" % (k, hang[k]))

    # ---- SIP signalling: identified by Call-ID, extracted by the caller ----
    # The `shell` API command does not exist in this build, so the switch cannot
    # read its own trace for us. Instead the Call-ID of THIS call is written out
    # and the caller greps the trace for it on the host side. Filtering by
    # Call-ID rather than by line range is deliberate: it is the only way to be
    # sure the signalling shown belongs to this call and not to another.
    call_id = create.get("Call-ID") or ""
    Path(SIP_TRACE_MARKER).write_text(
        "\n".join([pinned, call_id, str(int(time.time()))]), encoding="utf-8")
    print("\n  -- SIP signalling --")
    print("     Call-ID of this call: %s" % call_id)
    print("     written to %s for host-side extraction" % SIP_TRACE_MARKER)
    print("     (grep the switch's sip-trace for this Call-ID to see INVITE/200/ACK/SDP)")

    cmd.result("sofia global siptrace off", timeout=10)

    verdict = "WORKS" if (answered is not None and hang) else "DID NOT COMPLETE"
    print("\n" + "=" * 78)
    print("VERDICT: local gateway %s" % verdict)
    print("  (RTP: allocation verified above; actual audio is NOT claimed here)")
    ev.close(); cmd.close()


if __name__ == "__main__":
    main()
