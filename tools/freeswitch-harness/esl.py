"""
Phase C live-test harness: a real ESL (Event Socket Library) client.

Written against the FreeSWITCH ESL wire protocol, NOT against any assumption:
frames are read as "header block, blank line, exactly Content-Length bytes of
body", and the event name is taken from the BODY, not the first line.

Purpose: observe what this FreeSWITCH instance ACTUALLY emits, so the
documentation can be corrected with evidence instead of prediction.
"""
import hashlib
import os
import re
import socket
import sys
import time

HOST = "127.0.0.1"
PORT = 8021


def env_password():
    """Read the ESL secret from infra/.env without ever printing it."""
    env_path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "..", "..", "infra", ".env")
    for line in open(env_path, encoding="utf-8"):
        if line.startswith("FREESWITCH_PASSWORD="):
            return line.strip().split("=", 1)[1]
    raise RuntimeError("FREESWITCH_PASSWORD not found in infra/.env")


class Esl:
    """A single ESL connection. Handles auth, subscription and framing."""

    def __init__(self, password, host=HOST, port=PORT, timeout=15):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        self.buf = b""
        self.password = password
        self.banner = None

    # ---- framing -------------------------------------------------------
    def _fill(self):
        chunk = self.sock.recv(65536)
        if not chunk:
            raise ConnectionError("ESL connection closed by FreeSWITCH")
        self.buf += chunk

    def read_message(self):
        """Read exactly one ESL message: headers, blank line, Content-Length body."""
        # 1. header block, terminated by a blank line
        while b"\r\n\r\n" not in self.buf and b"\n\n" not in self.buf:
            self._fill()
        idx = self.buf.find(b"\r\n\r\n")
        sep = 4
        if idx < 0:
            idx = self.buf.find(b"\n\n")
            sep = 2
        head = self.buf[:idx].decode(errors="replace")
        self.buf = self.buf[idx + sep:]

        headers = {}
        for line in head.splitlines():
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip()] = v.strip()

        # 2. body of exactly Content-Length characters
        n = int(headers.get("Content-Length", "0") or 0)
        while len(self.buf) < n:
            self._fill()
        body = self.buf[:n].decode(errors="replace")
        self.buf = self.buf[n:]
        return headers, body

    # ---- protocol ------------------------------------------------------
    def send(self, command):
        self.sock.sendall((command + "\n\n").encode())

    def connect(self):
        """Consume the unprompted auth banner, then authenticate."""
        headers, _ = self.read_message()
        self.banner = headers.get("Content-Type", "")
        if not self.banner.lower().startswith("auth/"):
            raise RuntimeError(f"expected auth banner, got {self.banner!r}")
        self.send("auth " + self.password)
        headers, _ = self.read_message()
        reply = headers.get("Reply-Text", "")
        if not reply.startswith("+OK"):
            raise RuntimeError(f"authentication failed: {reply!r}")
        return self.banner, reply

    def api(self, command, timeout=None):
        """Issue a command.

        Returns (content_type, Reply-Text, body).

        OBSERVED IN PHASE C - do not be surprised by this:
        for an `api ...` command, FreeSWITCH returns an EMPTY Reply-Text and
        puts the actual result in the message BODY (Content-Type
        "api/response"). Reply-Text is populated for command-level verdicts
        (auth, event, bgapi) but not for api results.
        """
        if timeout:
            self.sock.settimeout(timeout)
        self.send(command)
        headers, body = self.read_message()
        return (headers.get("Content-Type", ""),
                headers.get("Reply-Text", ""), body)

    def result(self, command, timeout=15):
        """Convenience: run an `api` command and return its body, stripped."""
        ct, reply, body = self.api("api " + command, timeout=timeout)
        return body.rstrip("\r\n")

    def subscribe(self, event_names):
        ct, reply, _ = self.api("event plain " + " ".join(event_names))
        if not reply.startswith("+OK"):
            raise RuntimeError(f"subscription rejected: {reply!r}")
        return reply

    def next_event(self, timeout=20):
        """Return (event_name, headers) for the next plain event, or None."""
        deadline = time.time() + timeout
        self.sock.settimeout(timeout)
        while time.time() < deadline:
            try:
                headers, body = self.read_message()
            except socket.timeout:
                return None
            ct = headers.get("Content-Type", "")
            if not ct.lower().startswith("text/event"):
                continue
            body_headers = {}
            for line in body.splitlines():
                if ":" in line:
                    k, v = line.split(":", 1)
                    body_headers[k.strip()] = v.strip()
            name = body_headers.get("Event-Name") or headers.get("Event-Name")
            return name, {**headers, **body_headers}
        return None

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


if __name__ == "__main__":
    esl = Esl(env_password())
    banner, reply = esl.connect()
    print("auth banner  :", banner)
    print("auth reply   :", reply)
    # NOTE: a bare API name is NOT a valid inbound ESL command. FreeSWITCH
    # accepts a fixed set of inbound commands (api, bgapi, event, filter,
    # linger, exit, hup, log, sendmsg, recv). A bare "status" is rejected
    # with "-ERR command not found". Observed live in Phase C.
    print("api status   :", esl.api("api status")[1])
    print("bgapi status :", esl.api("bgapi status")[1])
    esl.close()
