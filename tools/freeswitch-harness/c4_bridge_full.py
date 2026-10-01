"""
Phase C - the definitive C4 / C5 / C8 / C9 / C10 run, with a real bridge.

Dial string under test: `user/1002`

  This is the local analogue of what the Java client does with
  `sofia/gateway/<gateway>/<destination>`: a short dial string that FreeSWITCH
  resolves, through the directory, to a destination's registered contact. Using
  the same SHAPE matters - `sofia/gateway/fs-gateway/1002` is the production
  form, and it cannot be exercised locally because no carrier exists, so
  `user/1002` stands in for it and exercises the identical resolution path
  (directory -> dial-string -> registered contact -> bridge).

  It only works now because the directory defines the domain's dial-string
  parameter. Without it FreeSWITCH logs, for the leg that tries to bridge:

      [ERR] mod_dptools.c:4419 No dial-string available, please check your
      user directory.

  and the call produces a channel, an answered peer, and NO MEDIA. Signalling
  looks perfect; audio silently does not flow. That is the single most
  misleading failure in this phase.

What is asserted, and from where:

  C4  a real bridge: CHANNEL_ANSWER on the originated leg AND CHANNEL_BRIDGE
      carrying both legs' Unique-IDs
  C5  RTP actually allocated and actually flowing BOTH ways:
        - the platform's own media port and packet counters (endpoint -> platform)
        - the ENDPOINT's own counters (platform -> endpoint)
      Counting what the platform sent would prove nothing about arrival.
  C8  PLAYFILE of a real file at the media root, and its playback events
  C9  a deliberately missing file: the real log text, and whether ANY ESL event
      reports it (Phase B recorded the Playback-Error header as unknown)
  C10 DTMF emitted by the endpoint, captured on the platform
  C14 the hangup cause produced by uuid_kill
"""
import re
import sys
import threading
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402

PLATFORM = ("127.0.0.1", 8021)
ENDPOINT_B = ("127.0.0.1", 8032)
DIAL = "user/1002"
GOOD = "/media/obd/phase-c-test-tone.wav"
BAD = "/media/obd/phase-c-deliberately-absent.wav"
SUBS = ["CHANNEL_CREATE", "CHANNEL_PROGRESS", "CHANNEL_ANSWER", "CHANNEL_DTMF",
        "CHANNEL_HANGUP", "CHANNEL_BRIDGE", "PLAYBACK_START", "PLAYBACK_STOP",
        "PLAYBACK_ERROR"]


def pw(name):
    import os
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "..", "infra", ".env")
    for line in open(p, encoding="utf-8"):
        if line.startswith(name + "="):
            return line.strip().split("=", 1)[1]
    raise RuntimeError(name)


def pump(esl, sink, seconds):
    end = time.time() + seconds
    while time.time() < end:
        ev = esl.next_event(timeout=max(1, int(end - time.time())))
        if ev is None:
            continue
        sink.append(ev)


def main():
    fs = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM); fs.connect()
    ep = Esl(pw("ENDPOINT_ESL_PASSWORD"), *ENDPOINT_B); ep.connect()
    fs.subscribe(SUBS)
    ep.subscribe(["CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_HANGUP"])
    fsev, epev = [], []
    threading.Thread(target=pump, args=(fs, fsev, 90), daemon=True).start()
    threading.Thread(target=pump, args=(ep, epev, 90), daemon=True).start()
    time.sleep(0.5)

    cu = str(uuidlib.uuid4())
    print("=" * 78)
    print("C4/C5/C8/C9/C10 with a REAL bridge   dial string: %s" % DIAL)
    print("=" * 78)
    print("  pinned channel UUID :", cu)
    ct, reply, _ = fs.api("bgapi originate {origination_uuid=%s,"
                          "origination_caller_id_number=+15551230000}%s"
                          % (cu, DIAL))
    job = reply.split("Job-UUID:")[1].strip() if "Job-UUID" in reply else "-"
    print("  ESL Job-UUID        :", job, "  (task id, not the channel)")

    bridged = answered = False
    end = time.time() + 30
    while time.time() < end and not bridged:
        answered = answered or any(n == "CHANNEL_ANSWER" for n, _ in fsev)
        bridged = any(n == "CHANNEL_BRIDGE" for n, _ in fsev)
        time.sleep(0.3)
    print("\n  [C4] CHANNEL_ANSWER seen      :", answered)
    print("  [C4] CHANNEL_BRIDGE  seen     :", bridged)
    if not bridged:
        print("  no bridge - reporting what was seen instead of a PASS")
        for n, h in fsev:
            print("     ", n, str(h.get("Unique-ID"))[:8])
        return

    for n, h in fsev:
        if n == "CHANNEL_BRIDGE":
            print("        Bridge-A-Unique-ID =", h.get("Bridge-A-Unique-ID"))
            print("        Bridge-B-Unique-ID =", h.get("Bridge-B-Unique-ID"))
            print("        (the originated leg is one of these two)")

    time.sleep(4)
    print("\n  [C5] PLATFORM media, for each leg of the bridge")
    for n, h in fsev:
        if n == "CHANNEL_BRIDGE":
            for leg in (h.get("Bridge-A-Unique-ID"), h.get("Bridge-B-Unique-ID")):
                if not leg:
                    continue
                out = fs.result("uuid_debug_media " + leg) or ""
                print("    leg %s:" % leg[:8])
                for line in out.splitlines():
                    if any(k in line for k in ("local media", "Remote", "packets",
                                               "Codec", "Bytes", "RTP")):
                        print("      ", line.strip())

    print("\n  [C5] ENDPOINT media, for its own leg")
    ech = ep.result("show channels") or ""
    m = re.search(r"\[([0-9a-f-]{36})\]", ech)
    if m:
        eu = m.group(1)
        print("    endpoint channel", eu)
        out = ep.result("uuid_debug_media " + eu) or ""
        for line in out.splitlines():
            if any(k in line for k in ("local media", "Remote", "packets",
                                       "Codec", "Bytes", "RTP")):
                print("      ", line.strip())
    else:
        print("    endpoint channels:", ech.strip()[:150])

    print("\n  [C8] PLAYFILE a real file:", GOOD)
    print("     ", fs.result("uuid_broadcast %s %s aleg" % (cu, GOOD)).strip()[:60])
    pump(fs, fsev, 8)

    print("\n  [C9] PLAYFILE a missing file:", BAD)
    print("     ", fs.result("uuid_broadcast %s %s aleg" % (cu, BAD)).strip()[:60])
    pump(fs, fsev, 8)

    print("\n  [C14] uuid_kill NORMAL_CLEARING")
    print("     ", fs.result("uuid_kill %s NORMAL_CLEARING" % cu).strip()[:60])
    time.sleep(4)

    print("\n" + "=" * 78)
    print("EVENT SUMMARY (all channels, in arrival order)")
    for n, h in fsev:
        print("  %-22s uuid=%-10s port=%-7s codec=%-6s cause=%s"
              % (n, str(h.get("Unique-ID"))[:8],
                 h.get("variable_local_media_port") or "-",
                 h.get("Channel-Read-Codec-Name") or "-",
                 h.get("Hangup-Cause") or "-"))

    print("\n  [C10] DTMF frames on the platform:")
    d = [(n, h) for n, h in fsev if n == "CHANNEL_DTMF"]
    if d:
        for n, h in d:
            print("    digit=%-5r subevent=%-9r duration=%-6r chan=%s"
                  % (h.get("DTMF-Digit"), h.get("DTMF-Subevent"),
                     h.get("DTMF-Duration"), str(h.get("Unique-ID"))[:8]))
    else:
        print("    none")

    print("\n  [C8/C9] playback frames:")
    pb = [(n, h) for n, h in fsev if n.startswith("PLAYBACK")]
    if pb:
        for n, h in pb:
            print("    %-15s chan=%s Playback-Error=%r"
                  % (n, str(h.get("Unique-ID"))[:8], h.get("Playback-Error")))
    else:
        print("    none")

    print("\n  endpoint-side events:")
    for n, h in epev:
        print("    %-16s uuid=%s codec=%s" % (n, str(h.get("Unique-ID"))[:8],
                                              h.get("Channel-Read-Codec-Name")))
    fs.close(); ep.close()


if __name__ == "__main__":
    main()
