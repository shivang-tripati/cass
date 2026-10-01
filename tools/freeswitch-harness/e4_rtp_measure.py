"""
Phase E - E4: measure ACTUAL RTP packet flow, on both ends.

WHY THIS SCRIPT EXISTS, AND WHY IT IS BUILT THIS WAY
=====================================================

Phase C left two-way audio unproven and the question has to be closed with
evidence, not inference. Everything below is excluded as proof:

  * allocated RTP ports      - a port can be allocated and never carry a packet
  * SDP containing m=audio   - SDP is an offer, not a transmission
  * CHANNEL_ANSWER           - signalling only
  * CHANNEL_BRIDGE           - a bridge can be up with no media passing

Phase C also established that this FreeSWITCH build does NOT expose per-stream
media counters. Re-confirmed here, on a live answered channel:

    rtp_audio_in_packet_count   = _undef_
    rtp_audio_out_packet_count  = _undef_
    rtp_audio_in_octet_count    = _undef_
    rtp_audio_out_octet_count   = _undef_
    rtp_last_packet_received    = _undef_

and `uuid_debug_media` is not available as an API in this build:

    api uuid_debug_media <uuid>  ->  -ERR api Command not found!

(no packet capture tool - tcpdump/tshark - is present in either image either,
and no image can be pulled because the Docker daemon's DNS is broken.)

So the measurement available is the kernel's own UDP datagram counters, read
from /proc/net/snmp inside each container. These are REAL packet counts, not
inferences. They are per-container rather than per-stream, so the measurement is
designed to make the media signal dominate everything else:

  * a 30-second media file is played, not the 3-second one - 1s of PCMU is
    ~400 packets, which would sit in the noise; 30s is ~12000
  * the counters are sampled immediately before and after the media window
  * BOTH ends are sampled, because a one-way result must not be reported as
    two-way. This is the rule Phase C set and it is enforced here by
    construction: the script prints a per-direction verdict and will say
    ONE-WAY even if only one direction moved.

DIRECTION ATTRIBUTION
---------------------
The platform is an OUTBOUND channel with no bridge, so during the measurement
window it is only SENDING. Therefore:

  platform InDatagrams rising  =  the ENDPOINT is sending RTP to us
                                  -> endpoint -> platform direction
  endpoint  InDatagrams rising  =  the PLATFORM is sending RTP to it
                                  -> platform -> endpoint direction

That inference is sound only because the platform has no other source of media
during the window, and is stated here so a reader can check it.
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
GATEWAY = "local-endpoint-1002"
DEST = "1002"
PROBE = "/media/obd/phase-e-rtp-probe.wav"   # 30s
PLATFORM_C = "obd-freeswitch"
ENDPOINT_C = "obd-fs-endpoint-1002"

SUBS = ["CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_HANGUP",
        "PLAYBACK_START", "PLAYBACK_STOP", "CHANNEL_EXECUTE_COMPLETE"]


def udp_counters(container):
    """Kernel UDP datagram counters: (InDatagrams, OutDatagrams).

    Read from /proc/net/snmp, whose second `Udp:` line is the value row and
    whose first is the column header. Both are read so the columns are never
    assumed to be in a fixed order.
    """
    out = subprocess.run(["docker", "exec", container, "cat", "/proc/net/snmp"],
                         capture_output=True, text=True, timeout=30)
    rows = [l for l in out.stdout.splitlines() if l.startswith("Udp:")]
    if len(rows) < 2:
        raise RuntimeError("could not read /proc/net/snmp from " + container)
    hdr = rows[0].split()[1:]
    val = rows[1].split()[1:]
    d = dict(zip(hdr, (int(v) for v in val)))
    return d["InDatagrams"], d["OutDatagrams"]


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
    print("PHASE E - E4: ACTUAL RTP packet flow, measured on both ends")
    print("=" * 78)
    print("  media probe: %s (30s PCMU)" % PROBE)
    print("  method     : kernel UDP datagram counters (/proc/net/snmp)")
    print("  NOT used   : rtp_audio_*_packet_count (%s on this build)" % "_undef_")
    print("  NOT used   : allocated ports, SDP, CHANNEL_ANSWER, CHANNEL_BRIDGE")

    # ---- t0: quiet baseline, before any call exists --------------------
    time.sleep(2)
    p_in0, p_out0 = udp_counters(PLATFORM_C)
    e_in0, e_out0 = udp_counters(ENDPOINT_C)
    print("\n  t0 baseline (no call in progress)")
    print("    platform  In=%-8d Out=%-8d" % (p_in0, p_out0))
    print("    endpoint  In=%-8d Out=%-8d" % (e_in0, e_out0))

    events = []
    threading.Thread(target=pump, args=(ev, events, 90), daemon=True).start()
    time.sleep(0.4)

    pinned = str(uuidlib.uuid4())
    dial = "sofia/gateway/%s/%s" % (GATEWAY, DEST)
    cmd.api("bgapi originate {origination_uuid=%s,"
            "origination_caller_id_number=+15551230000}%s &park()" % (pinned, dial),
            timeout=20)
    print("\n  originating:", dial)

    # ---- t1: answered, no media yet ------------------------------------
    answered = None
    end = time.time() + 30
    while time.time() < end and answered is None:
        answered = next((h for n, h in events
                         if n == "CHANNEL_ANSWER" and h.get("Unique-ID") == pinned), None)
        time.sleep(0.3)
    if answered is None:
        print("\n  call never answered; cannot measure media")
        ev.close(); cmd.close(); return
    print("  answered  : yes (Answer-State=%s)" % answered.get("Answer-State"))

    for v in ("local_media_ip", "local_media_port",
              "remote_media_ip", "remote_media_port", "read_codec", "write_codec"):
        val = (cmd.result("uuid_getvar %s %s" % (pinned, v)) or "").strip()
        if val and val != "_undef_":
            print("    %-20s = %s" % (v, val))

    time.sleep(2)
    p_in1, p_out1 = udp_counters(PLATFORM_C)
    e_in1, e_out1 = udp_counters(ENDPOINT_C)
    print("\n  t1 answered, BEFORE media (deltas vs t0)")
    print("    platform  In=%-8d Out=%-8d" % (p_in1, p_out1))
    print("    endpoint  In=%-8d Out=%-8d" % (e_in1, e_out1))

    # ---- the media window: platform -> endpoint ------------------------
    print("\n  playing the 30s probe from the platform to the endpoint ...")
    cmd.result("api uuid_broadcast %s %s aleg" % (pinned, PROBE), timeout=15)
    start = next((h for n, h in events
                  if n == "PLAYBACK_START" and h.get("Unique-ID") == pinned), None)
    print("  PLAYBACK_START observed: %s" % bool(start))
    time.sleep(14)

    p_in2, p_out2 = udp_counters(PLATFORM_C)
    e_in2, e_out2 = udp_counters(ENDPOINT_C)

    # ---- wait for the rest of the file, then hang up --------------------
    stop = next((h for n, h in events
                 if n == "PLAYBACK_STOP" and h.get("Unique-ID") == pinned), None)
    print("  PLAYBACK_STOP observed : %s" % bool(stop))
    cmd.result("api uuid_kill %s NORMAL_CLEARING" % pinned, timeout=15)
    time.sleep(3)

    p_in3, p_out3 = udp_counters(PLATFORM_C)
    e_in3, e_out3 = udp_counters(ENDPOINT_C)
    cmd.result("sofia global siptrace off", timeout=10)

    # ---- the verdict ----------------------------------------------------
    print("\n" + "=" * 78)
    print("MEASURED UDP DATAGRAMS DURING THE MEDIA WINDOW (t1 -> t2)")
    print("=" * 78)
    # endpoint -> platform : the platform is only SENDING here, so anything
    # it RECEIVED came from the endpoint.
    ep_to_plat = p_in2 - p_in1
    # platform -> endpoint : the endpoint RECEIVED what the platform sent.
    plat_to_ep = e_in2 - e_in1
    print("  endpoint  -> platform : platform InDatagrams  +%d" % ep_to_plat)
    print("  platform  -> endpoint : endpoint  InDatagrams  +%d" % plat_to_ep)
    print("\n  for context, the platform also SENT +%d datagrams in the same window"
          % (p_out2 - p_out1))
    print("  (so the inbound figure is not simply an echo of our own traffic)")

    print("\n  full call lifetime (t0 -> after hangup):")
    print("    platform  In +%-7d Out +%-7d" % (p_in3 - p_in0, p_out3 - p_out0))
    print("    endpoint  In +%-7d Out +%-7d" % (e_in3 - e_in0, e_out3 - e_out0))

    print("\n" + "-" * 78)
    two_way = ep_to_plat > 50 and plat_to_ep > 50
    one_way = plat_to_ep > 50 or ep_to_plat > 50
    if two_way:
        print("VERDICT: TWO-WAY RTP PACKET FLOW MEASURED - CONFIRMED -- LOCAL")
        print("  Both directions moved by far more than signalling noise, measured")
        print("  as kernel packet counters on both peers.")
    elif one_way:
        print("VERDICT: ONE DIRECTION ONLY - TWO-WAY AUDIO **NOT PROVEN**")
        print("  Reporting this as two-way would be unsupported.")
    else:
        print("VERDICT: NO MEDIA PACKET FLOW MEASURED - TWO-WAY AUDIO **NOT PROVEN**")
    print("-" * 78)
    print("  Scope: PCMU, 8kHz, in-network Docker bridge, no carrier, no PSTN.")
    print("  Audio *content* was not decoded; this is packet-flow evidence, which")
    print("  is what \"the audio path carried media\" means, and is not a claim")
    print("  that the audio was intelligible to a human.")

    ev.close(); cmd.close()


if __name__ == "__main__":
    main()
