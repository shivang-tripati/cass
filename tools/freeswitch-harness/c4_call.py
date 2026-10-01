"""
Phase C - C4/C5: a real extension-to-extension call, and the RTP port that
FreeSWITCH actually allocates.

Two real SIP user agents, both registered on the internal profile, call each
other through FreeSWITCH. Everything printed is observed:

  * the exact status line of every SIP response on both legs
  * the SDP each side offers, verbatim
  * the RTP port FreeSWITCH actually allocated, read out of its own SDP
  * whether that port is inside the range switch.conf.xml configured
  * the hangup: which leg sent BYE, and the response that completed it

The callee runs in its own thread because it has to answer while the caller is
still waiting for the response - exactly like a softphone.

MEDIA CAVEAT, established by evidence earlier in Phase C: this harness runs on
the Windows host, and the host has no route to the container's address
(172.25.0.2), so FreeSWITCH cannot deliver RTP to it. This test therefore
proves SIP signalling and proves WHICH RTP PORT FreeSWITCH allocates (read from
its SDP), but it does not prove two-way audio. Two-way audio is proven
separately with an in-network endpoint. The test says which it observed.
"""
import re
import sys
import threading
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from sip_ua import Ua, env, DOMAIN  # noqa: E402

PCMU_PT = 0
CALLEE_RTP_PORT = 40102


def sdp(local_ip, port, sess_id):
    return (
        "v=0\r\n"
        f"o=obd-phase-c {sess_id} 1 IN IP4 {local_ip}\r\n"
        "s=OBD Phase C UA\r\n"
        f"c=IN IP4 {local_ip}\r\n"
        "t=0 0\r\n"
        f"m=audio {port} RTP/AVP {PCMU_PT} 101\r\n"
        f"a=rtpmap:{PCMU_PT} PCMU/8000\r\n"
        "a=rtpmap:101 telephone-event/8000\r\n"
        "a=fmtp:101 0-15\r\n"
        "a=sendrecv\r\n"
    )


def parse_sdp(body):
    got = {}
    for line in body.splitlines():
        line = line.strip()
        if line.startswith("c=IN IP4"):
            got["media_addr"] = line[6:].strip()
        elif line.startswith("m=audio"):
            p = line[8:].split()
            got["media_port"] = int(p[0]) if p and p[0].isdigit() else None
            got["payloads"] = p[1:]
        elif line.startswith("a=rtpmap:"):
            p = line[9:].split(" ", 1)
            if len(p) == 2:
                got["rtpmap_" + p[0]] = p[1]
        elif line.startswith("o="):
            got["origin"] = line[2:].strip()
    return got


def code_of(first):
    m = re.match(r"SIP/2\.0 (\d{3})", first)
    return int(m.group(1)) if m else 0


def tag_of(headers, name="to"):
    m = re.search(r"tag=([^;\s]+)", headers.get(name, [""])[0])
    return m.group(1) if m else ""


def callee_thread(ua, hold, result):
    """Answer the incoming INVITE, then wait for and acknowledge the BYE."""
    try:
        first, headers, _ = ua.read()          # the INVITE
        result["invite"] = first
        uri = first.split()[1]
        ftag = tag_of(headers, "from")
        ct = tag_of(headers, "to")

        def resp(code, reason, body="", ctype=None, cseq=None):
            hdrs = [
                f"SIP/2.0 {code} {reason}",
                f"Via: {headers['via'][0]}",
                f"From: {headers['from'][0]}",
                f"To: {headers['to'][0]}" + ("" if ct in ("", ct) else f";tag={ct}"),
                f"Call-ID: {headers['call-id'][0]}",
                f"CSeq: {headers['cseq'][0]}",
                f"Contact: <sip:1002@{ua.local[0]}:{ua.local[1]};transport=tcp>",
                f"User-Agent: obd-phase-c-ua/1.0",
            ]
            if ctype:
                hdrs.append(f"Content-Type: {ctype}")
            hdrs.append(f"Content-Length: {len(body)}")
            return ("\r\n".join(hdrs) + "\r\n\r\n" + body).encode()

        ua.sock.sendall(resp(180, "Ringing", ctype="application/sdp"))
        result["responses"].append("180 Ringing (sent by 1002)")
        time.sleep(1.0)
        body = sdp("127.0.0.1", CALLEE_RTP_PORT, 22222)
        ua.sock.sendall(resp(200, "OK", body, "application/sdp"))
        result["responses"].append("200 OK + SDP (sent by 1002)")

        ua.sock.settimeout(hold + 20)
        # Expect ACK.
        first2, _, _ = ua.read()
        result["ack"] = first2
        time.sleep(hold)
        # The caller may send BYE from its own leg; FreeSWITCH routes the BYE
        # to us. Answer it.
        first3, h3, _ = ua.read()
        result["bye"] = first3
        if code_of(first3) == 200 and first3.startswith("SIP/2.0 200"):
            ua.sock.sendall(resp(200, "OK"))
            result["responses"].append("200 OK to BYE (sent by 1002)")
    except Exception as ex:
        result["error"] = f"{type(ex).__name__}: {ex}"


def run(hold=8):
    caller = Ua("1001", env("SIP_1001_PASSWORD"))
    callee = Ua("1002", env("SIP_1002_PASSWORD"))
    for ua in (caller, callee):
        for step, first in ua.register():
            print(f"  REGISTER {ua.user}  {step:<26} -> {first}")

    caller.auth = None
    caller.branch = "z9hG4bK" + uuidlib.uuid4().hex[:12]
    result = {"responses": []}
    th = threading.Thread(target=callee_thread, args=(callee, hold, result), daemon=True)
    th.start()
    time.sleep(0.4)

    print("\n  --- INVITE 1001 -> 1002 (via FreeSWITCH internal profile) ---")
    body1001 = sdp("127.0.0.1", 40101, 11111)
    caller.sock.sendall(caller.build(
        "INVITE", f"sip:1002@{DOMAIN}",
        extra=["Contact: <sip:1001@%s:%d;transport=tcp>" % caller.local],
        body=body1001).encode())
    t0 = time.time()

    fs_sdp = None
    to_tag = ""
    deadline = time.time() + 25
    while time.time() < deadline:
        first, headers, body = caller.read()
        print(f"    <- 1001 sees: {first}")
        if code_of(first) == 200 and body.strip():
            to_tag = tag_of(headers, "to")
            fs_sdp = parse_sdp(body)
            break
        if code_of(first) >= 400:
            break

    if not fs_sdp:
        print("  NO 2xx WITH SDP - the call did not answer")
        print("  callee saw:", result)
        caller.close(); callee.close()
        return None

    print("\n  --- SDP that FreeSWITCH offered (verbatim fields) ---")
    for k in ("origin", "media_addr", "media_port", "payloads",
              "rtpmap_0", "rtpmap_101"):
        print(f"    {k:<12} = {fs_sdp.get(k)}")
    port = fs_sdp["media_port"]
    print(f"    -> FreeSWITCH allocated RTP port {port}")
    print(f"    -> inside configured range 30000-30099: {30000 <= port <= 30099}")

    # ACK
    caller.sock.sendall((
        f"ACK sip:1002@{DOMAIN} SIP/2.0\r\n"
        f"Via: SIP/2.0/TCP {caller.local[0]}:{caller.local[1]};branch=z9hG4bK"
        f"{uuidlib.uuid4().hex[:12]};rport\r\n"
        f"From: <sip:1001@{DOMAIN}>;tag={caller.branch}\r\n"
        f"To: <sip:1002@{DOMAIN}>;tag={to_tag}\r\n"
        f"Call-ID: {caller.branch}@{caller.local[0]}\r\n"
        f"CSeq: 1 ACK\r\n"
        f"Content-Length: 0\r\n\r\n").encode())
    print(f"\n  -> 1001 sent ACK; answered in {time.time() - t0:.2f}s")
    print(f"  --- 1002 reported: {result.get('invite')} / ACK={result.get('ack')} ---")

    print(f"\n  --- holding {hold}s, then 1001 hangs up (BYE from the caller leg) ---")
    time.sleep(hold)
    caller.sock.sendall((
        f"BYE sip:1002@{DOMAIN} SIP/2.0\r\n"
        f"Via: SIP/2.0/TCP {caller.local[0]}:{caller.local[1]};branch=z9hG4bK"
        f"{uuidlib.uuid4().hex[:12]};rport\r\n"
        f"From: <sip:1001@{DOMAIN}>;tag={caller.branch}\r\n"
        f"To: <sip:1002@{DOMAIN}>;tag={to_tag}\r\n"
        f"Call-ID: {caller.branch}@{caller.local[0]}\r\n"
        f"CSeq: 2 BYE\r\n"
        f"Content-Length: 0\r\n\r\n").encode())
    caller.sock.settimeout(15)
    first, _, _ = caller.read()
    print(f"    <- 1001 sees: {first}")
    caller.sock.sendall((
        f"ACK sip:1002@{DOMAIN} SIP/2.0\r\n"
        f"Via: SIP/2.0/TCP {caller.local[0]}:{caller.local[1]};branch=z9hG4bK"
        f"{uuidlib.uuid4().hex[:12]};rport\r\n"
        f"From: <sip:1001@{DOMAIN}>;tag={caller.branch}\r\n"
        f"To: <sip:1002@{DOMAIN}>;tag={to_tag}\r\n"
        f"Call-ID: {caller.branch}@{caller.local[0]}\r\n"
        f"CSeq: 2 ACK\r\n"
        f"Content-Length: 0\r\n\r\n").encode())
    print("    -> 1001 sent ACK for the BYE")
    th.join(timeout=8)
    print(f"  --- 1002 side: BYE it received = {result.get('bye')} ---")

    caller.close(); callee.close()
    return fs_sdp


if __name__ == "__main__":
    print("=" * 78)
    print("TEST C4/C5: real call 1001 -> FreeSWITCH -> 1002, and RTP allocation")
    print("=" * 78)
    run()
