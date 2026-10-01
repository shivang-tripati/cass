"""
Phase C - C4/C5/C8/C10, the honest softphone path.

  endpoint 1001  --INVITE-->  platform switch  --INVITE-->  endpoint 1002
   (customer)       (dialplan bridges via the directory)     (agent)

Why the call is placed from the ENDPOINT rather than from the platform:

  Measured in Phase C, the platform-originated forms do not produce the
  end-to-end call this test needs:

    * `user/1002` as an originate URL fails with
        Cannot create outgoing channel of type [1002@{$dialed_domain}]
        cause: [CHAN_NOT_IMPLEMENTED]
      The `{$dialed_domain}` is UNEXPANDED - it is a dialplan variable, not a
      channel variable, so it is empty at originate time. `user/...` is a
      DIALPLAN target, not an originate URL.
    * `sofia/internal/1002@<ip> &park()` does create a channel with the pinned
      UUID and does reach the endpoint, but the platform's dialplan then
      transfers it and the transfer creates a NEW channel with a NEW UUID. The
      pinned origination_uuid does not survive. Anything that later addresses
      the call by that UUID is addressing a channel that no longer exists.

  Placing the call from endpoint 1001 - which is what a softphone does - avoids
  both problems and exercises the path the platform will actually see in
  production: an inbound leg, the stock dialplan, a directory lookup, a bridge
  to the destination's registered contact, and media.

What is measured:
  C4  the bridge, and both legs
  C5  RTP in BOTH directions, read from both endpoints' own counters
  C8  PLAYFILE from the platform into the live bridge
  C10 DTMF emitted by endpoint 1002, captured on the platform
"""
import re
import sys
import threading
import time

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402
from c4_bridge_full import pw  # noqa: E402

PLATFORM = ("127.0.0.1", 8021)
EP_A = ("127.0.0.1", 8031)      # extension 1001, the caller
EP_B = ("127.0.0.1", 8032)      # extension 1002, the callee
PLATFORM_IP = "172.25.0.2"
GOOD = "/media/obd/phase-c-test-tone.wav"
SUBS = ["CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_BRIDGE", "CHANNEL_HANGUP",
        "CHANNEL_DTMF", "PLAYBACK_START", "PLAYBACK_STOP", "PLAYBACK_ERROR"]


def pump(esl, sink, seconds):
    end = time.time() + seconds
    while time.time() < end:
        ev = esl.next_event(timeout=max(1, int(end - time.time())))
        if ev is not None:
            sink.append(ev)


def media(esl, uuid, label):
    out = esl.result("uuid_debug_media " + uuid) or ""
    print("    %s (%s):" % (label, uuid[:8]))
    for line in out.splitlines():
        s = line.strip()
        if s.startswith("Codec") or "packets" in s.lower() or "local media" in s \
           or s.startswith("Remote") or s.startswith("Bytes") or "read" in s.lower():
            print("       ", s)


def main():
    fs = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM); fs.connect()
    a = Esl(pw("ENDPOINT_ESL_PASSWORD"), *EP_A); a.connect()
    b = Ep2 = Esl(pw("ENDPOINT_ESL_PASSWORD"), *EP_B); Ep2.connect()
    for e in (fs, a, b):
        e.subscribe(SUBS)
    fev, aev, bev = [], [], []
    for e, s in ((fs, fev), (a, aev), (b, bev)):
        threading.Thread(target=pump, args=(e, s, 80), daemon=True).start()
    time.sleep(0.5)

    print("=" * 78)
    print("C4/C5/C8/C10  endpoint 1001 -> platform -> endpoint 1002")
    print("=" * 78)
    print("  placing the call FROM endpoint 1001 to platform extension 1002")
    # The endpoint's own profile is named obd-endpoint, not internal, so the
    # dial string must name that profile: sofia/<profile>/<user>@<host>.
    ct, reply, _ = a.api("bgapi originate {origination_caller_id_number=1001}"
                         "sofia/obd-endpoint/1002@%s" % PLATFORM_IP, timeout=20)
    print("  endpoint 1001 reply:", reply.strip())

    bridged = False
    end = time.time() + 40
    while time.time() < end and not bridged:
        bridged = any(n == "CHANNEL_BRIDGE" for n, _ in fev)
        time.sleep(0.4)

    print("\n  [C4] CHANNEL_BRIDGE on the platform:", bridged)
    legs = []
    for n, h in fev:
        if n == "CHANNEL_BRIDGE":
            A = h.get("Bridge-A-Unique-ID")
            B = h.get("Bridge-B-Unique-ID")
            legs = [x for x in (A, B) if x]
            print("        Bridge-A-Unique-ID =", A)
            print("        Bridge-B-Unique-ID =", B)
    if not bridged:
        print("  no bridge. platform events:")
        for n, h in fev:
            print("     ", n, str(h.get("Unique-ID"))[:8])
        return

    time.sleep(5)
    print("\n  [C5] media, per leg, from the PLATFORM")
    for leg in legs:
        media(fs, leg, "platform leg")
    print("\n  [C5] media, from endpoint 1002 (the callee) - proves it RECEIVED")
    bch = b.result("show channels") or ""
    m = re.search(r"\[([0-9a-f-]{36})\]", bch)
    if m:
        media(b, m.group(1), "endpoint 1002 leg")
    print("\n  [C5] media, from endpoint 1001 (the caller) - proves it RECEIVED")
    ach = a.result("show channels") or ""
    m2 = re.search(r"\[([0-9a-f-]{36})\]", ach)
    if m2:
        media(a, m2.group(1), "endpoint 1001 leg")

    print("\n  [C8] PLAYFILE from the platform into the live bridge")
    target = legs[0] if legs else None
    if target:
        print("     ", fs.result("uuid_broadcast %s %s aleg"
                                % (target, GOOD)).strip()[:60])
    time.sleep(7)

    print("\n  [C14] hang up the customer leg")
    if target:
        print("     ", fs.result("uuid_kill %s NORMAL_CLEARING" % target).strip()[:60])
    time.sleep(5)

    print("\n" + "=" * 78)
    print("PLATFORM EVENTS")
    for n, h in fev:
        print("  %-18s uuid=%-9s port=%-6s codec=%-5s cause=%s"
              % (n, str(h.get("Unique-ID"))[:8],
                 h.get("variable_local_media_port") or "-",
                 h.get("Channel-Read-Codec-Name") or "-",
                 h.get("Hangup-Cause") or "-"))
    print("\n  [C10] DTMF captured on the platform:")
    d = [(n, h) for n, h in fev if n == "CHANNEL_DTMF"]
    if d:
        for n, h in d:
            print("    digit=%-5r subevent=%-9r duration=%-6r chan=%s"
                  % (h.get("DTMF-Digit"), h.get("DTMF-Subevent"),
                     h.get("DTMF-Duration"), str(h.get("Unique-ID"))[:8]))
        print("    sequence:", "".join(str(h.get("DTMF-Digit", ""))
                                       for _, h in d))
    else:
        print("    none")
    print("\n  [C8] playback frames:")
    for n, h in fev:
        if n.startswith("PLAYBACK"):
            print("    %-15s chan=%s err=%r" % (n, str(h.get("Unique-ID"))[:8],
                                                 h.get("Playback-Error")))
    print("\nENDPOINT 1002 EVENTS (far-end confirmation)")
    for n, h in bev:
        print("  %-18s uuid=%-9s codec=%-5s state=%s"
              % (n, str(h.get("Unique-ID"))[:8],
                 h.get("Channel-Read-Codec-Name") or "-",
                 h.get("Answer-State") or "-"))
    fs.close(); a.close(); b.close()


if __name__ == "__main__":
    main()
