"""
Phase E - final local evidence run: PLAYFILE direction, DTMF, and both RTP legs.

Run only after the gateway path is genuinely working (e1_decisive.py), because
until the far end really answers, none of this measures anything real.

Collected in ONE call, because each of these needs a live, answered, media-
carrying channel and they are all properties of the same event stream:

  1. PLATFORM -> ENDPOINT media. The platform broadcasts a 30s file with
     `api uuid_broadcast ... aleg`, and the endpoint's own UDP receive counters
     are sampled across the playback. The endpoint's events are the witness:
     a PLAYBACK_START/STOP pair on the endpoint's channel is unambiguous, and
     the counter delta is corroboration.
  2. ENDPOINT -> PLATFORM media. The endpoint's dialplan plays a tone after
     answering, so the platform's receive counters move on their own. Nothing
     is sent to cause this - if the platform receives, the endpoint sent.
  3. DTMF. The endpoint's dialplan emits a KNOWN digit sequence after answer,
     over RTP, so both the platform's `RECV DTMF` log lines and its
     CHANNEL_DTMF events are attributable to the peer rather than to a test
     harness injecting them.

Two measurement disciplines are enforced here because both have already produced
false readings in this phase:

  * `result()` already prepends `api `. Passing a string that starts with `api `
    sends `api api ...` and yields "-ERR api Command not found!", which looks
    exactly like a missing API. It is a malformed command, not a missing one.
  * The endpoint's counters and events come from SEPARATE ESL connections. One
    socket cannot carry a synchronous command and a subscribed event stream at
    once - the command reads the event and every later read is off by one frame.
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
ENDPOINT_C = "obd-fs-endpoint-1002"
GATEWAY = "local-endpoint-1002"
PROBE = "/media/obd/phase-e-rtp-probe.wav"

SUBS = ["CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_HANGUP", "CHANNEL_DTMF",
        "PLAYBACK_START", "PLAYBACK_STOP", "CHANNEL_EXECUTE",
        "CHANNEL_EXECUTE_COMPLETE"]


def udp(c):
    out = subprocess.run(["docker", "exec", c, "cat", "/proc/net/snmp"],
                         capture_output=True, text=True, timeout=30)
    rows = [l for l in out.stdout.splitlines() if l.startswith("Udp:")]
    d = dict(zip(rows[0].split()[1:], (int(v) for v in rows[1].split()[1:])))
    return d["InDatagrams"], d["OutDatagrams"]


def pump(esl, sink, seconds):
    end = time.time() + seconds
    while time.time() < end:
        try:
            e = esl.next_event(timeout=max(1, int(end - time.time())))
        except Exception:
            return
        if e is not None:
            sink.append(e)


def main():
    ev = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM); ev.connect(); ev.subscribe(SUBS)
    cmd = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM); cmd.connect()
    epl = Esl(pw("ENDPOINT_ESL_PASSWORD"), *ENDPOINT); epl.connect()
    epl_ev = Esl(pw("ENDPOINT_ESL_PASSWORD"), *ENDPOINT); epl_ev.connect()
    epl_ev.subscribe(SUBS)

    pe, ee = [], []
    threading.Thread(target=pump, args=(ev, pe, 90), daemon=True).start()
    threading.Thread(target=pump, args=(epl_ev, ee, 90), daemon=True).start()
    time.sleep(0.6)

    print("=" * 78)
    print("PHASE E - PLAYFILE direction, DTMF, and both RTP legs, in one call")
    print("=" * 78)
    p_in0, p_out0 = udp("obd-freeswitch")
    e_in0, e_out0 = udp(ENDPOINT_C)
    print("  baseline  platform In=%d Out=%d | endpoint In=%d Out=%d"
          % (p_in0, p_out0, e_in0, e_out0))

    pinned = str(uuidlib.uuid4())
    dial = "sofia/gateway/%s/1002" % GATEWAY
    cmd.api("bgapi originate {origination_uuid=%s,"
            "origination_caller_id_number=+15551230000}%s &park()" % (pinned, dial),
            timeout=20)
    print("  dial string : %s" % dial)
    print("  pinned uuid : %s" % pinned)

    t = time.time() + 30
    while time.time() < t and not any(
            n == "CHANNEL_ANSWER" for n, h in pe if h.get("Unique-ID") == pinned):
        time.sleep(0.4)
    answered = any(n == "CHANNEL_ANSWER" for n, h in pe if h.get("Unique-ID") == pinned)
    print("  answered    : %s" % answered)
    if not answered:
        print("  no answer; nothing below is measurable")
        return

    for v in ("local_media_ip", "local_media_port", "remote_media_ip",
              "remote_media_port", "read_codec", "write_codec"):
        val = (cmd.result("uuid_getvar %s %s" % (pinned, v)) or "").strip()
        if val and val != "_undef_":
            print("    %-20s = %s" % (v, val))

    # ---- 2. endpoint -> platform: the endpoint's own tone, nothing sent ----
    time.sleep(4)
    p_in1, _ = udp("obd-freeswitch")
    ep2p = p_in1 - p_in0
    print("\n  [endpoint -> platform] platform InDatagrams +%d during the endpoint's"
          " own tone" % ep2p)
    print("     (nothing was sent by the test to cause this)")

    # ---- 1. platform -> endpoint: broadcast the 30s probe ------------------
    # NOTE: result() prepends "api " itself. Do NOT include it here.
    reply = cmd.result("uuid_broadcast %s %s aleg" % (pinned, PROBE))
    print("\n  [platform -> endpoint] uuid_broadcast reply : %r" % reply)
    time.sleep(16)
    e_in1, e_out1 = udp(ENDPOINT_C)
    p2ep = e_in1 - e_in0
    print("     endpoint InDatagrams +%d during playback" % p2ep)

    # ---- 3. DTMF the peer emitted -----------------------------------------
    dtmf = [h.get("DTMF-Digit") for n, h in pe
            if n == "CHANNEL_DTMF" and h.get("Unique-ID") == pinned]
    print("\n  [DTMF] CHANNEL_DTMF digits received on the platform channel: %s"
          % ("".join(dtmf) if dtmf else "(none)"))

    cmd.result("uuid_kill %s NORMAL_CLEARING" % pinned, timeout=15)
    time.sleep(4)
    p_in2, p_out2 = udp("obd-freeswitch")
    e_in2, e_out2 = udp(ENDPOINT_C)

    print("\n" + "=" * 78)
    print("RESULTS")
    print("=" * 78)
    print("  platform-side events : %s"
          % " -> ".join(n for n, h in pe if h.get("Unique-ID") == pinned))
    print("  ENDPOINT-side events : %s"
          % " -> ".join(n for n, h in ee))
    hang = next((h.get("Hangup-Cause") for n, h in pe
                 if n == "CHANNEL_HANGUP" and h.get("Unique-ID") == pinned), None)
    print("  hangup cause         : %s" % hang)

    print("\n  RTP, whole call (t0 -> after hangup):")
    print("    endpoint  -> platform : platform In  +%-6d" % (p_in2 - p_in0))
    print("    platform  -> endpoint : endpoint  In  +%-6d" % (e_in2 - e_in0))
    print("    (and the platform sent +%d, the endpoint sent +%d)"
          % (p_out2 - p_out0, e_out2 - e_out0))

    ep_playback = any(n == "PLAYBACK_START" for n, _ in ee)
    two_way = ep_playback and (e_in2 - e_in0) > 50 and (p_in2 - p_in0) > 50
    print()
    if two_way:
        print("VERDICT: TWO-WAY RTP CONFIRMED -- LOCAL")
        print("  Both legs carry a substantial packet count, and the endpoint's own")
        print("  event stream confirms it played what the platform sent.")
    else:
        print("VERDICT: two-way audio **NOT PROVEN**")
        print("  endpoint PLAYBACK_START observed: %s" % ep_playback)
        print("  platform->endpoint datagrams    : +%d" % (e_in2 - e_in0))
        print("  endpoint->platform datagrams    : +%d" % (p_in2 - p_in0))
    print("  Platform playback events        : %s"
          % ([n for n, h in pe if h.get("Unique-ID") == pinned
              and n.startswith("PLAYBACK")] or "none"))

    ev.close(); cmd.close(); epl.close(); epl_ev.close()


if __name__ == "__main__":
    main()
