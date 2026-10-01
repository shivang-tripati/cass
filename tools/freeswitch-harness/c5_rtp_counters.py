"""
Phase C - C5: read real RTP packet counters, and note the API correction.

API CORRECTION, found by using it wrong first:
  `uuid_debug_media <uuid> <read|write|both|...> <on|off>` does NOT print
  statistics. It TURLES media debug logging on or off; called with only a uuid
  it replies:

      -USAGE: <uuid> <read|write|both|vread|vwrite|vboth|all> <on|off>

  which reads like a failure but is just the usage string. Phase B recorded no
  way to read RTP counters; the answer is the channel variables, via
  `uuid_getvar`:
      rtp_audio_in_packet_count / rtp_audio_out_packet_count
      rtp_audio_in_byte_count   / rtp_audio_out_byte_count
      local_media_port / remote_media_ip / remote_media_port
      read_codec / write_codec

  "in" is what this switch RECEIVED. A non-zero in-count on a leg is proof that
  audio arrived from the far end - which is the only acceptable basis for
  claiming two-way audio.
"""
import re
import sys
import threading
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402
from c4_bridge_full import pw  # noqa: E402

PLATFORM = ("127.0.0.1", 8021)
EP_B = ("127.0.0.1", 8032)
DEST = "sofia/internal/1002@172.25.0.2"
SUBS = ["CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_BRIDGE", "CHANNEL_HANGUP"]
VARS = ["rtp_audio_in_packet_count", "rtp_audio_out_packet_count",
        "rtp_audio_in_byte_count", "rtp_audio_out_byte_count",
        "local_media_port", "remote_media_ip", "remote_media_port",
        "read_codec", "write_codec", "local_media_ip"]


def pump(esl, sink, seconds):
    end = time.time() + seconds
    while time.time() < end:
        try:
            ev = esl.next_event(timeout=max(1, int(end - time.time())))
        except Exception:
            return
        if ev is not None:
            sink.append(ev)


def stats(esl, uuid, label):
    print("\n  --- %s : %s ---" % (label, uuid[:8]))
    got = {}
    for v in VARS:
        val = (esl.result("uuid_getvar %s %s" % (uuid, v)) or "").strip()
        if val and not val.startswith("-ERR"):
            got[v] = val
            print("     %-30s = %s" % (v, val))
    if not got:
        print("     (no variables readable - the channel is gone)")
    return got


def main():
    fs = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM); fs.connect()
    # A SECOND connection, used only for commands. This is not tidiness, it is
    # required: on a connection that is simultaneously receiving subscribed
    # events, a synchronous command can read the NEXT MESSAGE, which may be an
    # event rather than the command's reply - and then the real reply is still
    # queued and every later read is off by one frame. Observed in Phase C as
    # a command that simply timed out. The same hazard applies to any ESL
    # client that multiplexes commands and events on one socket.
    cmd = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM); cmd.connect()
    ep = Esl(pw("ENDPOINT_ESL_PASSWORD"), *EP_B); ep.connect()
    fs.subscribe(SUBS)
    fev = []
    threading.Thread(target=pump, args=(fs, fev, 60), daemon=True).start()
    time.sleep(0.4)

    cu = str(uuidlib.uuid4())
    print("=" * 78)
    print("C5  RTP packet counters on a live, bridged call")
    print("=" * 78)
    ct, reply, _ = cmd.api("bgapi originate {origination_uuid=%s,"
                           "origination_caller_id_number=+15551230000}%s &park()"
                           % (cu, DEST), timeout=20)
    print("  originate:", reply.strip())

    bridged = False
    end = time.time() + 35
    while time.time() < end and not bridged:
        bridged = any(n == "CHANNEL_BRIDGE" for n, _ in fev)
        time.sleep(0.4)
    legs = []
    for n, h in fev:
        if n == "CHANNEL_BRIDGE":
            legs = [x for x in (h.get("Bridge-A-Unique-ID"),
                                h.get("Bridge-B-Unique-ID")) if x]
    print("  bridge:", bridged, " legs:", ", ".join(x[:8] for x in legs))

    # let the endpoint's dialplan play its prompt toward the platform
    time.sleep(10)
    # and have the platform play a file into the call, so both directions carry
    for leg in legs:
        cmd.result("uuid_broadcast %s /media/obd/phase-c-test-tone.wav aleg" % leg)
    time.sleep(8)

    for leg in legs:
        stats(cmd, leg, "platform leg")
    stats(cmd, cu, "pinned originate channel")

    # the endpoint's own view
    ech = cmd.result("show channels") or ""
    m = re.search(r"\[([0-9a-f-]{36})\]", ech)
    if m:
        stats(cmd, m.group(1), "channel on the platform (post-bridge)")
    ech2 = ep.result("show channels") or ""
    m2 = re.search(r"\[([0-9a-f-]{36})\]", ech2)
    if m2:
        stats(ep, m2.group(1), "endpoint 1002 leg (FAR END - proves receipt)")
    else:
        print("\n  endpoint 1002 has no channel right now:", ech2.strip()[:120])

    for leg in legs + [cu]:
        cmd.result("uuid_kill %s NORMAL_CLEARING" % leg)
    time.sleep(2)
    fs.close(); cmd.close(); ep.close()


if __name__ == "__main__":
    main()
