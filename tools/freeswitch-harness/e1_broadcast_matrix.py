"""
Phase E.1: resolve the `uuid_broadcast` contradiction with a controlled matrix.

THE CONTRADICTION
=================
Phase E recorded, on a live answered channel:

    api uuid_broadcast <uuid> <file> aleg   ->  -ERR invalid uuid
    api uuid_dump <uuid>                    ->  CHANNEL_DATA  (valid)

and concluded the Java command contract was unresolved. The brief requires it be
explained rather than reinterpreted, so the first job here is to establish
whether the UUID is in fact invalid at that instant.

THE DISCRIMINATOR
=================
FreeSWITCH says "invalid uuid". That is a claim about the UUID, and it must be
tested rather than believed. Three other commands address the SAME channel:

    uuid_dump <uuid>      - reads the channel
    uuid_kill  <uuid>     - mutates and destroys it
    uuid_broadcast ...    - the one failing

`uuid_kill` is the sharpest test, because it can only succeed if the switch
holds that exact channel. If `uuid_kill` succeeds where `uuid_broadcast` reports
an invalid UUID, then the UUID is valid, the error is about something else, and
"invalid uuid" is a misleading message rather than a diagnosis.

So the matrix deliberately interleaves `uuid_dump` and `uuid_broadcast` at the
SAME instant, and tries `uuid_kill` LAST, after every other test, so that the
channel is still alive for the comparison.

Every test is also repeated against a FRESH channel, because a single sample
cannot distinguish "this command form is wrong" from "this channel was in the
wrong state".
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
FILE = "/media/obd/phase-e-rtp-probe.wav"

SUBS = ["CHANNEL_CREATE", "CHANNEL_ANSWER", "CHANNEL_HANGUP",
        "PLAYBACK_START", "PLAYBACK_STOP", "CHANNEL_EXECUTE",
        "CHANNEL_EXECUTE_COMPLETE"]


def udp(c):
    out = subprocess.run(["docker", "exec", c, "cat", "/proc/net/snmp"],
                         capture_output=True, text=True, timeout=30)
    rows = [l for l in out.stdout.splitlines() if l.startswith("Udp:")]
    d = dict(zip(rows[0].split()[1:], (int(v) for v in rows[1].split()[1:])))
    return d["InDatagrams"]


def pump(esl, sink, seconds):
    end = time.time() + seconds
    while time.time() < end:
        try:
            e = esl.next_event(timeout=max(1, int(end - time.time())))
        except Exception:
            return
        if e is not None:
            sink.append(e)


def place_call(cmd, pe, ee, p):
    """Place the call the production Java dialer would place, and wait for answer."""
    cmd.api("bgapi originate {origination_uuid=%s,"
            "origination_caller_id_number=+15551230000}"
            "sofia/gateway/local-endpoint-1002/1002 &park()" % p, timeout=20)
    t = time.time() + 30
    while time.time() < t and not any(
            n == "CHANNEL_ANSWER" for n, h in pe if h.get("Unique-ID") == p):
        time.sleep(0.3)
    return any(n == "CHANNEL_ANSWER" for n, h in pe if h.get("Unique-ID") == p)


def state(cmd, p):
    """Identity + call state, read from the live channel."""
    out = {}
    for v in ("sip_call_id", "local_media_port", "remote_media_ip",
              "remote_media_port", "rtp_remote_sdp_str", "endpoint_disposition"):
        val = (cmd.result("uuid_getvar %s %s" % (p, v)) or "").strip()
        out[v] = val
    dump = cmd.result("uuid_dump %s" % p) or ""
    for key in ("Channel-Call-State", "Channel-State", "Answer-State",
                "Channel-Read-Codec-Name"):
        m = re.search(r"%s:\s*(\S+)" % key, dump)
        out[key] = m.group(1) if m else "-"
    return out


def matrix(cmd, pe, ee, p, label, do_kill_last=True):
    print("\n" + "-" * 76)
    print("MATRIX on %s   channel %s" % (label, p))
    print("-" * 76)
    st = state(cmd, p)
    print("  channel state : %s / %s / answer=%s" % (
        st["Channel-Call-State"], st["Channel-State"], st["Answer-State"]))
    print("  sip_call_id   : %s" % st["sip_call_id"])
    print("  media         : local port %s  remote %s:%s  codec %s" % (
        st["local_media_port"], st["remote_media_ip"],
        st["remote_media_port"], st["Channel-Read-Codec-Name"]))
    print("  rtp_remote_sdp: %s" % (
        "present" if st["rtp_remote_sdp_str"] not in ("", "_undef_") else "_undef_"))
    print("  disposition   : %s" % st["endpoint_disposition"])

    # A. does the switch hold this channel at all, right now?
    dump = cmd.result("uuid_dump %s" % p) or ""
    print("\n  A  api uuid_dump %s" % ("<uuid>"))
    print("       -> %s" % ("CHANNEL_DATA (valid)" if "CHANNEL_DATA" in dump
                            else repr(dump[:70])))

    n0 = len(pe)
    e0 = udp(ENDPOINT_C)
    tests = [
        ("B  api uuid_broadcast <uuid> <file> aleg   <-- EXACTLY what Java sends",
         "uuid_broadcast %s %s aleg" % (p, FILE)),
        ("C  api uuid_broadcast <uuid> <file>",
         "uuid_broadcast %s %s" % (p, FILE)),
        ("D  api uuid_broadcast <uuid> <file> both",
         "uuid_broadcast %s %s both" % (p, FILE)),
        ("E  api uuid_broadcast <uuid> <file> <own uuid as leg>",
         "uuid_broadcast %s %s %s" % (p, FILE, p)),
        ("F  api play <uuid> <file>",
         "play %s %s" % (p, FILE)),
    ]
    results = {}
    for desc, line in tests:
        r = cmd.result(line)
        time.sleep(4)
        pb = [n for n, h in pe[n0:] if h.get("Unique-ID") == p
              and n.startswith("PLAYBACK")]
        e1 = udp(ENDPOINT_C)
        results[desc[3]] = (r, pb, e1 - e0)
        print("\n  %s" % desc)
        print("       reply  : %r" % r)
        print("       events : %s" % (pb or "none"))
        print("       peer In: +%d" % (e1 - e0))
        n0 = len(pe)

    # G. the discriminator, LAST so the channel survives every other test
    if do_kill_last:
        r = cmd.result("uuid_kill %s NORMAL_CLEARING" % p)
        time.sleep(3)
        hung = any(n == "CHANNEL_HANGUP" and h.get("Unique-ID") == p for n, h in pe)
        print("\n  G  api uuid_kill <uuid> NORMAL_CLEARING   <-- DISCRIMINATOR")
        print("       reply  : %r" % r)
        print("       hangup : %s" % ("CHANNEL_HANGUP observed" if hung else "no hangup"))
    return results


def main():
    ev = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM); ev.connect(); ev.subscribe(SUBS)
    cmd = Esl(pw("FREESWITCH_PASSWORD"), *PLATFORM); cmd.connect()
    epl = Esl(pw("ENDPOINT_ESL_PASSWORD"), *ENDPOINT); epl.connect()
    epl_ev = Esl(pw("ENDPOINT_ESL_PASSWORD"), *ENDPOINT); epl_ev.connect()
    epl_ev.subscribe(SUBS)

    pe, ee = [], []
    threading.Thread(target=pump, args=(ev, pe, 240), daemon=True).start()
    threading.Thread(target=pump, args=(epl_ev, ee, 240), daemon=True).start()
    time.sleep(0.6)

    print("=" * 76)
    print("PHASE E.1 - the uuid_broadcast matrix")
    print("=" * 76)
    print("  file under test: %s" % FILE)
    print("  dial string    : sofia/gateway/local-endpoint-1002/1002")
    print("  note           : uuid_kill is run LAST in every matrix, so the")
    print("                    channel survives every other test.")

    p1 = str(uuidlib.uuid4())
    if not place_call(cmd, pe, ee, p1):
        print("  first call did not answer; cannot proceed")
        return
    r1 = matrix(cmd, pe, ee, p1, "RUN 1 (fresh channel)")

    time.sleep(3)
    p2 = str(uuidlib.uuid4())
    if not place_call(cmd, pe, ee, p2):
        print("  second call did not answer")
        return
    r2 = matrix(cmd, pe, ee, p2, "RUN 2 (fresh channel, repeat)")

    print("\n" + "=" * 76)
    print("COMPARISON OF THE TWO INDEPENDENT RUNS")
    print("=" * 76)
    for k in sorted(set(list(r1.keys()) + list(r2.keys()))):
        a = r1.get(k, ("-", [], 0))
        b = r2.get(k, ("-", [], 0))
        same = "SAME" if a[0] == b[0] else "DIFFERENT"
        print("  test %-3s run1=%-28r run2=%-28r %s" % (k, a[0], b[0], same))
        if a[2] or b[2]:
            print("        peer received: run1 +%d   run2 +%d" % (a[2], b[2]))

    ev.close(); cmd.close(); epl.close(); epl_ev.close()


if __name__ == "__main__":
    main()
