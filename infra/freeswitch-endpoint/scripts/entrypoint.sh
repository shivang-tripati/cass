#!/bin/sh
# OBD Platform - Phase C: entry point for a local SIP test endpoint container.
#
# WHAT THIS DOES
# Renders three configuration files from templates, refusing to start if any
# secret is missing or weak, then execs FreeSWITCH so it is PID 1's process and
# receives SIGTERM directly.
#
# WHY RENDERING INSTEAD OF MOUNTING
# The same reason as the platform switch: no credential, not even a placeholder,
# is committed to the repository. The templates contain tokens; the live files
# are produced here and are never written back to the working tree.
#
# WHY IT RESOLVES THE PLATFORM'S ADDRESS ITSELF
# The registration realm must match the realm the platform switch authenticates
# against, and FreeSWITCH files a registration under user@realm. The platform
# switch pins its realm to its own domain, which resolves to its container IP.
# The endpoint therefore has to REGISTER using that same IP - not the network
# alias, because the alias would become the To host and therefore the realm.
# Resolving it at start-up keeps that correct across container recreates without
# hard-coding an address that Docker Desktop is free to change.

set -eu

ESL_TEMPLATE='/opt/obd/event_socket.conf.xml.in'
ESL_TARGET='/etc/freeswitch/autoload_configs/event_socket.conf.xml'
PROFILE_TEMPLATE='/opt/obd/obd-profile.xml.in'
PROFILE_TARGET='/etc/freeswitch/sip_profiles/obd-endpoint.xml'
DIALPLAN_TEMPLATE='/opt/obd/obd-dialplan.xml.in'
DIALPLAN_TARGET='/etc/freeswitch/dialplan/obd-endpoint.xml'

PLATFORM_HOST='freeswitch'
PLATFORM_SIP_PORT='5060'

for var in FREESWITCH_PASSWORD EXTENSION SIP_PASSWORD; do
  eval "val=\${$var:-}"
  if [ -z "$val" ]; then
    echo "FATAL: $var is not set; this endpoint cannot be configured." >&2
    exit 1
  fi
done

# Reuse the platform entrypoint's rules: no known-weak or placeholder values,
# and a minimum length. These are development fixtures, but "it's only a test
# fixture" is exactly how a weak credential reaches production.
case "$FREESWITCH_PASSWORD" in
  ClueCon|cluecon|fs-password|1234|ENDPOINT_ESL_PASSWORD|replace-with-strong-local-secret)
    echo 'FATAL: refusing a known-weak or placeholder ESL password.' >&2
    exit 1
    ;;
esac
if [ "${#FREESWITCH_PASSWORD}" -lt 16 ]; then
  echo "FATAL: FREESWITCH_PASSWORD is ${#FREESWITCH_PASSWORD} chars; 16 minimum." >&2
  exit 1
fi

case "$SIP_PASSWORD" in
  ClueCon|cluecon|fs-password|1234|replace-with-strong-local-secret)
    echo 'FATAL: refusing a known-weak SIP password for the endpoint.' >&2
    exit 1
    ;;
esac
if [ "${#SIP_PASSWORD}" -lt 12 ]; then
  echo "FATAL: SIP_PASSWORD is ${#SIP_PASSWORD} chars; 12 minimum." >&2
  exit 1
fi

if [ "${#EXTENSION}" -lt 3 ] || [ "${#EXTENSION}" -gt 6 ]; then
  echo "FATAL: EXTENSION must be 3-6 digits, got '$EXTENSION'." >&2
  exit 1
fi

DTMF_DIGITS="${DTMF_DIGITS:-0159#*}"
ANSWER_HOLD_MS="${ANSWER_HOLD_MS:-30000}"

# Resolve the platform switch to an address this container can reach.
PLATFORM_IP="$(getent hosts "$PLATFORM_HOST" 2>/dev/null | awk '{print $1; exit}' || true)"
if [ -z "$PLATFORM_IP" ]; then
  echo "FATAL: cannot resolve '$PLATFORM_HOST' on the Docker network." >&2
  echo "       Is the platform switch up and attached to obd-telephony?" >&2
  exit 1
fi
PLATFORM_REALM="$PLATFORM_IP"

# Escape the characters that are special on the right-hand side of a sed
# replacement, so a secret containing & \ or | cannot corrupt the output.
esc() { printf '%s' "$1" | sed -e 's/[\\&|]/\\&/g'; }

esl_pw=$(esc "$FREESWITCH_PASSWORD")
sed -e "s|@@FREESWITCH_PASSWORD@@|${esl_pw}|g" "$ESL_TEMPLATE" > "$ESL_TARGET"
chmod 600 "$ESL_TARGET"

sip_pw=$(esc "$SIP_PASSWORD")
ext=$(esc "$EXTENSION")
digits=$(esc "$DTMF_DIGITS")
hold=$(esc "$ANSWER_HOLD_MS")
sed -e "s|@@SIP_PASSWORD@@|${sip_pw}|g" \
    -e "s|@@EXTENSION@@|${ext}|g" \
    -e "s|@@PLATFORM_REALM@@|${PLATFORM_REALM}|g" \
    -e "s|@@PLATFORM_PROXY@@|${PLATFORM_IP}:${PLATFORM_SIP_PORT}|g" \
    "$PROFILE_TEMPLATE" > "$PROFILE_TARGET"
chmod 600 "$PROFILE_TARGET"

sed -e "s|@@DTMF_DIGITS@@|${digits}|g" \
    -e "s|@@ANSWER_HOLD_MS@@|${hold}|g" \
    "$DIALPLAN_TEMPLATE" > "$DIALPLAN_TARGET"
chmod 600 "$DIALPLAN_TARGET"

for f in "$ESL_TARGET" "$PROFILE_TARGET" "$DIALPLAN_TARGET"; do
  if grep -q '@@' "$f"; then
    echo "FATAL: a substitution token survived rendering in $f" >&2
    exit 1
  fi
done

unset FREESWITCH_PASSWORD SIP_PASSWORD esl_pw sip_pw digits

echo "OBD Phase C endpoint: extension $EXTENSION rendered, platform switch at $PLATFORM_REALM:$PLATFORM_SIP_PORT (realm $PLATFORM_REALM)"
exec /usr/bin/freeswitch
