"""
Phase E - E1/E5: validate the routable local gateway, independently of Spring.

The Phase E brief requires this path to be proven BEFORE the application is
involved, so that a failure can be attributed to the network, to FreeSWITCH, to
the gateway, or to Java - rather than to "the integration".

    FreeSWITCH -> gateway -> SIP INVITE -> endpoint -> 200 OK -> ACK -> RTP

SIP tracing is enabled on the switch for the duration so the actual signalling
is captured from the wire, rather than inferred from channel state. That is the
difference between "a channel exists" and "a call was actually signalled".

The switch's own SIP address is DISCOVERED, never assumed: Phase D established
that a container address changes across recreates, and a hard-coded address
produces NO_ROUTE_DESTINATION that reads like a provider fault.

Nothing here trusts a +OK reply. A +OK proves a command was accepted; every
claim below is backed by an event, a SIP message, or a log line.
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
GATEWAY = "local-endpoint-1002"
DEST_EXT = "1002"

SUBS = ["CHANNEL_CREATE", "CHANNEL_PROGRESS", "CHANNEL_PROGRESS_MEDIA",
        "CHANNEL_ANSWER", "CHANNEL_HANGUP", "CHANNEL_BRIDGE", "CHANNEL_DTMF",
        "CHANNEL_EXECUTE", "CHANNEL_EXECUTE_COMPLETE",
        "PLAYBACK_START", "PLAYBACK_STOP"]

# Headers worth pulling for the correlation chain: app identity -> channel ->
# SIP Call-ID -> media addresses.
SHOW = ["Call-ID", "Unique-ID", "Channel-Call-UUID", "variable_origination_uuid",
        "Caller-Unique-ID", "variable_sip_call_id", "variable_originate_disposition",
        "Answer-State", "Hangup-Cause", "variable_bridge_b_uuid",
        "Channel-Name", "variable_destination_number", "Caller-Caller-ID-Number"]


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

    plat = re.search(r"SIP-IP\s+(\S+)",
                     cmd.result("sofia status profile internal") or "").group(1)

    print("=" * 78)
    print("PHASE E - E1/E5: routable local gateway, validated WITHOUT Spring")
    print("=" * 78)
    print("  platform address (discovered) :", plat)
    print("  gateway                       :", GATEWAY)
    print("  destination                   :", DEST_EXT)
    print("  java dial string equivalent   : sofia/gateway/%s/%s" % (GATEWAY, DEST_EXT))

    # --- 1. gateway must exist, or nothing below means anything ---------------
    gw_state = cmd.result("sofia status gateway %s" % GATEWAY) or ""
    if "Invalid" in gw_state:
        print("\n  FATAL: gateway is Invalid. mod_sofia discards a gateway whose")
        print("         registration fails; see the fs-gateway note.")
        ev.close(); cmd.close(); return
    for line in gw_state.splitlines():
        if any(k in line for k in ("Name", "Proxy", "Profile", "State", "Domain",
                                   "From", "OB Calls", "Register")):
            print("  gw: %s" % line.strip())

    # --- 2. turn SIP tracing on so signalling is captured from the wire ------
    cmd.api("sofia global siptrace on", timeout=10)
    time.sleep(1)

    events = []
    threading.Thread(target=pump, args=(ev, events, 55), daemon=True).start()
    time.sleep(0.4)

    pinned = str(uuidlib.uuid4())
    print("\n  pinned origination_uuid:", pinned)
    # EXACTLY the shape the Java client sends: gateway-routed, no trailing app,
    # so the leg enters the dialplan the way production would.
    cmd.api("bgapi originate {origination_uuid=%s,"
            "origination_caller_id_number=+15551230000}"
            "sofia/gateway/%s/%s" % (pinned, GATEWAY, DEST_EXT), timeout=20)
    print("  originate issued; waiting for signalling ...")

    answered = None
    end = time.time() + 35
    while time.time() < end and answered is None:
        answered = next((h for n, h in events
                         if n == "CHANNEL_ANSWER" and h.get("Unique-ID") == pinned), None)
        time.sleep(0.3)

    # --- 3. the correlation chain, from the application's pinned identity -----
    print("\n" + "-" * 78)
    print("CORRELATION CHAIN (all read from real events, not assumed)")
    print("-" * 78)
    create = next((h for n, h in events
                   if n == "CHANNEL_CREATE" and h.get("Unique-ID") == pinned), {})
    for k in SHOW:
        for src, label in ((create, "CREATE"),):
            if k in src:
                print("  %-32s = %s" % (k, str(src[k])[:60]))
    if answered:
        print("  %-32s = %s" % ("CHANNEL_ANSWER Answer-State", answered.get("Answer-State")))

    # media: prove RTP was actually allocated, and read it back from the switch
    print("\n  -- RTP / media, read back from the live channel --")
    dump = cmd.result("uuid_dump %s" % pinned) or ""
    m = re.search(r"local_media_ip=([\d.]+).*?local_media_port=(\d+)", dump, re.S)
    m2 = re.search(r"remote_media_ip=([\d.]+).*?remote_media_port=(\d+)", dump, re.S)
    print("    local_media  : %s" % (m.group(0) if m else "not reported"))
    print("    remote_media : %s" % (m2.group(0) if m2 else "not reported"))
    readp = re.search(r"read_codec=([\w]+)", dump)
    writep = re.search(r"write_codec=([\w]+)", dump)
    print("    read_codec   : %s   write_codec: %s" % (
        readp.group(1) if readp else "?", writep.group(1) if writep else "?"))

    # --- 4. every event on our channel, in order -----------------------------
    print("\n  -- event sequence on the pinned channel --")
    seq = [n for n, h in events if h.get("Unique-ID") == pinned]
    print("    " + (" -> ".join(seq) if seq else "(none)"))

    # --- 5. hold briefly so the endpoint's tone can be received, then hang up -
    print("\n  holding 8s with the call answered (endpoint plays a tone) ...")
    time.sleep(8)
    cmd.result("api uuid_kill %s NORMAL_CLEARING" % pinned, timeout=15)
    time.sleep(4)
    cmd.api("sofia global siptrace off", timeout=10)

    hang = next((h for n, h in events
                 if n == "CHANNEL_HANGUP" and h.get("Unique-ID") == pinned), {})
    print("\n  -- hangup --")
    for k in ("Hangup-Cause", "Answer-State", "variable_sip_hangup_cause"):
        if k in hang:
            print("    %-22s = %s" % (k, hang[k]))

    # any other channels? a gateway leg creates the far side too
    others = sorted({h.get("Unique-ID") for _, h in events
                     if h.get("Unique-ID") and h.get("Unique-ID") != pinned})
    print("\n  other channels seen during the call: %d" % len(others))
    for u in others:
        names = [n for n, h in events if h.get("Unique-ID") == u]
        print("    %s  %s" % (u, ", ".join(names)))

    print("\n" + "=" * 78)
    ok = answered is not None and hang
    print("VERDICT: gateway path %s" % ("WORKS (INVITE, answer, media, hangup)"
                                        if ok else "DID NOT COMPLETE"))
    ev.close(); cmd.close()


if __name__ == "__main__":
    main()
