# Phase C diagnostic: what realm does the platform challenge with, and what
# user@realm does it look for? Printed from the live wire and the live log.
import sys

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from sip_ua import Ua, env  # noqa: E402

ext = sys.argv[1] if len(sys.argv) > 1 else "1001"
ua = Ua(ext, env("SIP_%s_PASSWORD" % ext))
first, h, _ = ua.roundtrip("REGISTER", "sip:127.0.0.1")
print("  first REGISTER  :", first)
print("  WWW-Authenticate:", h.get("www-authenticate", [""])[0])

trace = ua.register()
for step, first in trace:
    print("  %-30s -> %s" % (step, first))
ua.close()
