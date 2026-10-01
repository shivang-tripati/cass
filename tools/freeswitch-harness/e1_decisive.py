"""
Phase E - E1/E5: the decisive gateway test, with the ENDPOINT as the witness.

Why this script checks the far end rather than trusting the platform
=====================================================================

An earlier attempt appeared to succeed: the platform reported CHANNEL_ANSWER for
`sofia/gateway/local-endpoint-1002/1002`. It was a FALSE POSITIVE, and this
script exists so that cannot be reported again.

What was actually happening: the gateway's proxy had been pointed at the
switch's own SIP address, so the INVITE looped back to the platform, where the
stock public context answered the channel. The platform reported a perfectly
good answer for a call that never left the building. The tells were only
visible from outside:

    rtp_remote_sdp_str     = _undef_     <- no SDP was ever exchanged
    endpoint live channels = 0           <- the far end had no call at all
    endpoint UDP counters  = unchanged    <- no media, and no RTP to it

The lesson is recorded rather than just fixed: a channel event reports what the
LOCAL switch believes. Only the peer's own state can confirm that a call
happened somewhere.

So this script asserts on peer evidence:
  1. the endpoint has a live channel          (it really is participating)
  2. the endpoint's own dialplan ran          (it did not just sit there)
  3. the platform received a remote SDP       (media was negotiated)
  4. the endpoint's UDP counters moved         (packets actually crossed)
  5. the hangup cause is a real one, not NO_ROUTE_DESTINATION

Any of these failing means the call did NOT happen, regardless of what
CHANNEL_ANSWER said.
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
ENDPOINT = ("127.0.0.1", 8032)
GATEWAY = "local-endpoint-1002"
DEST = "1002"
ENDPOINT_C = "obd-fs-endpoint-1002"

SUBS = ["CHANNEL_CREATE", "CHANNEL_PROGRESS", "CHANNEL_ANSWER", "CHANNEL_HANGUP",
        "CHANNEL_DTMF", "PLAYBACK_START", "PLAYBACK_STOP", "CHANNEL_BRIDGE"]


def udp(container):
    out = subprocess.run(["docker", "exec", container, "cat", "/proc/net/snmp"],
                         capture_output=True, text=True, timeout=30)
    rows = [l for l in out.stdout.splitlines() if l.startswith("Udp:")]
    d = dict(zip(rows[0].split()[1:], (int(v) for v in rows[1].split()[1:])))
    return d["InDatagrams"], d["OutDatagrams"]


def endpoint_channels(epl):
    """Count the endpoint's live channels.

    NOT `uuid_dump` with no argument: that returns
    `-USAGE: <uuid> [format]` and lists nothing, so a regex over it always finds
    zero UUIDs. That mistake briefly made a working call look like a dead one.
    `show channels` is the correct query and reports a real total.
    """
    out = epl.result("show channels") or ""
    m = re.search(r"(\d+)\s+total", out)
    return int(m.group(1)) if m else 0


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
    epl = Esl(pw("ENDPOINT_ESL_PASSWORD"), *ENDPOINT); epl.connect()
    # A SEPARATE connection carries the endpoint event stream. Sharing one
    # socket between the event pump and synchronous commands makes a command
    # read the next EVENT instead of its reply (Phase C, 12.4).
    epl_ev = Esl(pw("ENDPOINT_ESL_PASSWORD"), *ENDPOINT); epl_ev.connect()
    epl_ev.subscribe(SUBS)

    print("=" * 78)
    print("PHASE E - E1/E5 decisive test: the ENDPOINT is the witness")
    print("=" * 78)
    gw = cmd.result("sofia status gateway %s" % GATEWAY) or ""
    for line in gw.splitlines():
        if any(k in line for k in ("Name", "Profile", "Realm", "Proxy", "State")):
            print("  gw: %s" % line.strip())
    print("  endpoint channels before : %d" % endpoint_channels(epl))
    e_in0, e_out0 = udp(ENDPOINT_C)

    events = []
    threading.Thread(target=pump, args=(ev, events, 70), daemon=True).start()
    time.sleep(0.4)

    pinned = str(uuidlib.uuid4())
    dial = "sofia/gateway/%s/%s" % (GATEWAY, DEST)
    print("\n  dial string : %s" % dial)
    print("  pinned uuid : %s" % pinned)

    # The endpoint's OWN event stream is the primary witness. A live-channel
    # count is a sample and can miss a call that has already ended; the peer's
    # event stream is the record of what actually happened to it.
    ep_events = []
    threading.Thread(target=pump, args=(epl_ev, ep_events, 70), daemon=True).start()

    cmd.api("bgapi originate {origination_uuid=%s,"
            "origination_caller_id_number=+15551230000}%s &park()" % (pinned, dial),
            timeout=20)

    # The endpoint's dialplan answers, sends DTMF, plays a tone, then waits.
    # Sample the peer mid-call, while it must be answered and sending.
    time.sleep(12)

    print("\n" + "-" * 78)
    print("PEER EVIDENCE - all read from the ENDPOINT, not the platform")
    print("-" * 78)
    ep_ch = endpoint_channels(epl)
    print("  1. endpoint live channels      : %d %s"
          % (ep_ch, "PASS" if ep_ch else "FAIL - the far end has no call"))
    if ep_ch > 0:
        eu = ""
        print("       channel uuid on endpoint  : %s" % eu)
        st = epl.result("uuid_dump %s" % eu) or ""
        for l in st.splitlines():
            if any(k in l for k in ("Channel-Name", "Channel-Call-State",
                                    "Answer-State", "Channel-Read-Codec-Name",
                                    "Channel-Write-Codec-Name")):
                print("       %-27s: %s" % (l.split(":")[0], l.split(":", 1)[1].strip()))

    e_in1, e_out1 = udp(ENDPOINT_C)
    print("\n  4. endpoint UDP  In +%-7d  Out +%-7d  %s"
          % (e_in1 - e_in0, e_out1 - e_out0,
             "PASS - media crossed" if (e_in1 - e_in0) > 0 or (e_out1 - e_out0) > 0
             else "FAIL - no packets at all"))

    sdp = (cmd.result("uuid_getvar %s rtp_remote_sdp_str" % pinned) or "").strip()
    got_sdp = bool(sdp) and sdp != "_undef_"
    print("  3. platform received remote SDP: %s"
          % ("PASS" if got_sdp else "FAIL - _undef_, nothing was negotiated"))

    mine = [(n, h) for n, h in events if h.get("Unique-ID") == pinned]
    answered = any(n == "CHANNEL_ANSWER" for n, _ in mine)
    print("\n  platform-side events: %s" % " -> ".join(n for n, _ in mine))
    print("  platform CHANNEL_ANSWER        : %s (NOT trusted on its own)"
          % answered)

    # The endpoint's dialplan emits DTMF then a tone; both are peer proof.
    dtmf = [h.get("DTMF-Digit") for n, h in mine if n == "CHANNEL_DTMF"]
    if dtmf:
        print("  2. DTMF received from endpoint : %s PASS" % "".join(dtmf))
    else:
        print("  2. DTMF from endpoint          : none seen yet (peer is in its wait step)")

    cmd.result("api uuid_kill %s NORMAL_CLEARING" % pinned, timeout=15)
    time.sleep(4)
    hang = next((h for n, h in mine if n == "CHANNEL_HANGUP"), {})
    cause = hang.get("Hangup-Cause")
    print("\n  5. hangup cause                : %s %s"
          % (cause, "PASS" if cause and cause != "NO_ROUTE_DESTINATION"
             else "FAIL - the call never connected"))

    print("\n  -- endpoint's own view of the call it just took --")
    ep_ch2 = endpoint_channels(epl)
    print("     channels still live after our hangup: %d" % ep_ch2)

    checks = {
        "peer took the call (endpoint event stream)": any(
            n == "CHANNEL_ANSWER" for n, _ in ep_events),
        "peer actually played media": any(
            n == "PLAYBACK_START" for n, _ in ep_events),
        "remote SDP negotiated": got_sdp,
        "media packets crossed the network": (e_in1 - e_in0) > 0 or (e_out1 - e_out0) > 0,
        "hangup is a real cause": bool(cause) and cause != "NO_ROUTE_DESTINATION",
    }
    print("\n  -- the endpoint's own event stream, in order --")
    for n, h in ep_events:
        print("     %-16s %s  %s" % (n, (h.get("Channel-Name") or "")[:44],
                                     h.get("Hangup-Cause") or h.get("Answer-State") or ""))
    print("\n  -- peer DTMF emitted by the endpoint (its dialplan sends a known set) --")
    digits = [h.get("DTMF-Digit") for n, h in ep_events if n == "CHANNEL_DTMF"]
    print("     %s" % ("".join(digits) if digits else "(none)"))

    print("\n" + "=" * 78)
    if all(checks.values()):
        print("VERDICT: GATEWAY PATH GENUINELY WORKS - CONFIRMED -- LOCAL")
        print("  The endpoint really took the call, media really crossed, and the")
        print("  hangup is a negotiated release rather than a routing failure.")
    else:
        print("VERDICT: GATEWAY PATH DID NOT GENUINELY WORK")
        for k, v in checks.items():
            print("   %-26s %s" % (k, "ok" if v else "FAILED"))
        print("  A platform-side CHANNEL_ANSWER without peer evidence is a false")
        print("  positive and is explicitly NOT being reported as success.")
    print("=" * 78)

    ev.close(); cmd.close(); epl.close(); epl_ev.close()


if __name__ == "__main__":
    main()
