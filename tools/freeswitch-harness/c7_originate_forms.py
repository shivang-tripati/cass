"""
Phase C - C7 (investigation): which bgapi originate forms actually place a call?

This matters because the Java EslClient builds its command in one specific
shape. Phase B documented that shape as the contract, but a documented
contract is not a working command, so it is tested here against the running
switch.

For each form: issue the command, then ask FreeSWITCH how many channels exist.
`+OK Job-UUID` is NOT evidence of success - bgapi reports acceptance of the
command, and the command can still fail afterwards. That distinction is the
whole point of this test.
"""
import sys
import time
import uuid as uuidlib

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from esl import Esl  # noqa: E402
from c4_c5_c10 import pw_from_env  # noqa: E402

FORMS = [
    ("no space, single token  (JAVA'S FORM)",
     "{origination_uuid=%(u)s,origination_caller_id_number=+15551230000}user/1002"),
    ("no space, sofia URL     (JAVA'S FORM, gateway-shaped)",
     "{origination_uuid=%(u)s,origination_caller_id_number=+15551230000}sofia/internal/1002@172.25.0.2"),
    ("space before URL",
     "{origination_uuid=%(u)s,origination_caller_id_number=+15551230000} user/1002"),
    ("space, with &app()",
     "{origination_uuid=%(u)s,origination_caller_id_number=+15551230000} user/1002 &echo()"),
    ("no variables at all",
     "user/1002"),
    ("no variables, loopback app",
     "loopback/app/sleep 8000"),
    ("no space, loopback app  (known-good from Phase B)",
     "{origination_uuid=%(u)s,origination_caller_id_number=+15551230000}loopback/app/sleep 8000"),
    ("space, loopback app",
     "{origination_uuid=%(u)s,origination_caller_id_number=+15551230000} loopback/app/sleep 8000"),
    ("originate + a single channel var, no space",
     "{origination_uuid=%(u)s}user/1002"),
]


def channel_count(e):
    out = e.result("show channels") or ""
    for line in out.splitlines():
        if "total" in line:
            return line.strip()
    return out.strip()[:80] or "<none>"


def main():
    e = Esl(pw_from_env("FREESWITCH_PASSWORD"))
    e.connect()
    for label, template in FORMS:
        cu = str(uuidlib.uuid4())
        cmd = "bgapi originate " + (template % {"u": cu})
        ct, reply, _ = e.api(cmd)
        job = reply.split("Job-UUID:")[1].strip() if "Job-UUID" in reply else "-"
        time.sleep(6)
        print("  %-44s" % label)
        print("      reply        : %s" % reply.strip())
        print("      pinned chan  : %s" % cu)
        print("      job uuid     : %s" % job)
        print("      pinned==job  : %s" % (cu == job))
        print("      channels     : %s" % channel_count(e))
        print()
    e.close()


if __name__ == "__main__":
    print("=" * 78)
    print("C7 investigation: which bgapi originate form actually places a call?")
    print("=" * 78)
    main()
