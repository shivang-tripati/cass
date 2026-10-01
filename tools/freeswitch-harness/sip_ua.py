"""
Phase C - C3: a minimal, real SIP user agent.

Speaks SIP directly over TCP so that REGISTER / 401 / digest / 200 OK can be
observed with the actual bytes. No third-party SIP library: the point is to see
exactly what FreeSWITCH sends.

Transport note (observed in Phase C): from the Windows host, SIP over UDP is
unusable because Docker Desktop's UDP port proxy does not deliver the response
back to the original client port. SIP over TCP works. Use TCP here.
"""
import hashlib
import os
import re
import socket
import sys
import uuid as uuidlib

SERVER = ("127.0.0.1", 5060)
DOMAIN = "127.0.0.1"


def env(name):
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "..", "infra", ".env")
    for line in open(p, encoding="utf-8"):
        if line.startswith(name + "="):
            return line.strip().split("=", 1)[1]
    raise RuntimeError(f"{name} not found in infra/.env")


def parse(msg):
    head, _, body = msg.partition("\r\n\r\n")
    lines = head.split("\r\n")
    first = lines[0] if lines else ""
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers.setdefault(k.strip().lower(), []).append(v.strip())
    return first, headers, body


def md5(s):
    return hashlib.md5(s.encode()).hexdigest()


def digest(user, realm, password, method, uri, nonce=None, qop=None,
           nc="00000001", cnonce=None):
    """RFC 2617 digest.

    OBSERVED IN PHASE C: FreeSWITCH challenges with qop="auth", so the
    response MUST be the qop form:

        HA1 = MD5(user:realm:password)
        HA2 = MD5(method:uri)
        qop form     = MD5(HA1:nonce:nc:cnonce:qop:HA2)
        no-qop form  = MD5(HA1:nonce:HA2)

    Sending qop=auth while computing the no-qop form yields 403 Forbidden,
    not 401 - so a 403 on the second REGISTER means "credentials or digest
    computation wrong", not "no challenge was issued".
    """
    ha1 = md5(f"{user}:{realm}:{password}")
    ha2 = md5(f"{method}:{uri}")
    if qop:
        return md5(f"{ha1}:{nonce}:{nc}:{cnonce}:{qop}:{ha2}")
    return md5(f"{ha1}:{nonce}:{ha2}")


class Ua:
    def __init__(self, user, password, transport="TCP"):
        self.user, self.password, self.transport = user, password, transport
        self.sock = socket.create_connection(SERVER, timeout=10)
        self.sock.settimeout(10)
        self.local = self.sock.getsockname()
        self.branch = "z9hG4bK" + uuidlib.uuid4().hex[:12]
        self.auth = None

    def via(self):
        return (f"Via: SIP/2.0/{self.transport} {self.local[0]}:{self.local[1]}"
                f";branch={self.branch};rport")

    def build(self, method, uri, extra=(), body=""):
        auth = ""
        if self.auth:
            nonce = self.auth["nonce"]
            realm = self.auth["realm"]
            qop = self.auth.get("qop")
            cnonce = self.auth.get("cnonce") or uuidlib.uuid4().hex[:8]
            self.auth["cnonce"] = cnonce
            nc = self.auth["cseq"] and "%08d" % self.auth["cseq"] or "00000001"
            resp = digest(self.user, realm, self.password, method, uri,
                          nonce=nonce, qop=qop, nc=nc, cnonce=cnonce)
            auth = (f'Authorization: Digest username="{self.user}", realm="{realm}", '
                    f'nonce="{nonce}", uri="{uri}", response="{resp}", '
                    f'algorithm=MD5')
            if qop:
                auth += f', qop={qop}, nc={nc}, cnonce="{cnonce}"'
            auth += "\n"
        cseq = self.auth["cseq"] if self.auth else 1
        hdrs = [
            self.via(),
            f"Max-Forwards: 70",
            f"From: <sip:{self.user}@{DOMAIN}>;tag={self.branch}",
            f"To: <{uri}>",
            f"Call-ID: {self.branch}@{self.local[0]}",
            f"CSeq: {cseq} {method}",
            f"Contact: <sip:{self.user}@{self.local[0]}:{self.local[1]};transport=tcp>",
            "User-Agent: obd-phase-c-ua/1.0",
        ]
        if method == "REGISTER":
            hdrs.append("Expires: 300")
        hdrs.extend(extra)
        msg = f"{method} {uri} SIP/2.0\r\n" + "\r\n".join(hdrs) + "\r\n"
        if auth:
            msg += auth
        msg += f"Content-Length: {len(body)}\r\n\r\n{body}"
        return msg

    def roundtrip(self, method, uri, extra=(), body=""):
        self.sock.sendall(self.build(method, uri, extra, body).encode())
        return self.read()

    def read(self):
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("closed")
            buf += chunk
        head, _, rest = buf.partition(b"\r\n\r\n")
        first, headers, _ = parse(head.decode(errors="replace"))
        n = int(headers.get("content-length", ["0"])[0])
        while len(rest) < n:
            rest += self.sock.recv(65536)
        return first, headers, rest[:n].decode(errors="replace")

    def register(self):
        """Full REGISTER with digest challenge handling. Returns the trace."""
        trace = []
        uri = f"sip:{DOMAIN}"
        first, headers, _ = self.roundtrip("REGISTER", uri)
        trace.append(("REGISTER (no auth)", first))
        if "401" in first or "407" in first:
            www = headers.get("www-authenticate", [""])[0]
            m = re.search(r'realm="([^"]+)"', www)
            n = re.search(r'nonce="([^"]+)"', www)
            q = re.search(r'qop="?([a-z]+)"?', www)
            self.auth = {"realm": m.group(1) if m else "",
                         "nonce": n.group(1) if n else "",
                         "qop": q.group(1) if q else None,
                         "cseq": 2}
            first, headers, _ = self.roundtrip("REGISTER", uri)
            trace.append((f"REGISTER (digest, qop={self.auth['qop']})", first))
        return trace

    def unregister(self):
        uri = f"sip:{DOMAIN}"
        first, _, _ = self.roundtrip("REGISTER", uri,
                                     extra=["Expires: 0"], )
        return first

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


if __name__ == "__main__":
    ext = sys.argv[1] if len(sys.argv) > 1 else "1001"
    pw = env("SIP_%s_PASSWORD" % ext)
    ua = Ua(ext, pw)
    print(f"--- C3 SIP registration for extension {ext} ---")
    for step, first in ua.register():
        print(f"  {step:<26} -> {first}")
    print("  closing (implicit unregister on expiry)")
    ua.close()
