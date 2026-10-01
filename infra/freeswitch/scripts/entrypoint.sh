#!/bin/sh
#
# OBD Platform - Phase B live FreeSWITCH environment
# Container entrypoint.
#
# Responsibility: render the ESL password from the environment into a
# FreeSWITCH configuration file, verify the two things that would otherwise
# fail silently, then hand off to FreeSWITCH itself.
#
# Why this exists rather than a committed config file: the stock image
# ships password "ClueCon" on "::". Committing any real password is not
# acceptable, and committing a placeholder in a file FreeSWITCH reads
# directly would mean the placeholder is a usable password. Rendering at
# start-up makes the committed artefact contain no credential at all.
#
# Invoked as: /bin/sh /opt/obd/entrypoint.sh  (so file mode / shebang are
# irrelevant, and CRLF in the source tree cannot break it).

set -eu

TEMPLATE='/opt/obd/event_socket.conf.xml.in'
TARGET='/etc/freeswitch/autoload_configs/event_socket.conf.xml'
DIR_TEMPLATE='/opt/obd/default.xml.in'
DIR_TARGET='/etc/freeswitch/directory/default.xml'
MEDIA_DIR='/media/obd'

# --- 1. the credential --------------------------------------------------------------
if [ -z "${FREESWITCH_PASSWORD:-}" ]; then
  echo 'FATAL: FREESWITCH_PASSWORD is not set. It must come from infra/.env' >&2
  echo '       (compose reads .env from the project directory, infra/).' >&2
  exit 1
fi

# Refuse the values that ship with the platform or the image, so a copied
# .env.example or a stale default cannot quietly weaken ESL.
case "$FREESWITCH_PASSWORD" in
  ClueCon|cluecon|fs-password|1234|FREESWITCH_PASSWORD|replace-with-strong-local-secret)
    echo 'FATAL: refusing a known-weak or placeholder FREESWITCH_PASSWORD.' >&2
    exit 1
    ;;
esac

if [ "${#FREESWITCH_PASSWORD}" -lt 16 ]; then
  echo "FATAL: FREESWITCH_PASSWORD is ${#FREESWITCH_PASSWORD} chars; 16 minimum." >&2
  exit 1
fi

# --- 2. SIP endpoint secrets (Phase C) ----------------------------------------------
# Phase C enables SIP registration for two LOCAL TEST EXTENSIONS ONLY. The stock
# directory ships default_password=1234, so these must be explicitly strong, and
# must never be left at a default.
for var in SIP_1001_PASSWORD SIP_1002_PASSWORD; do
  eval "val=\${$var:-}"
  if [ -z "$val" ]; then
    echo "FATAL: $var is not set. Local SIP test extensions cannot be created." >&2
    exit 1
  fi
  if [ "${#val}" -lt 12 ]; then
    echo "FATAL: $var is ${#val} chars; 12 minimum." >&2
    exit 1
  fi
  case "$val" in
    1234|ClueCon|fs-password|replace-with-strong-local-secret)
      echo "FATAL: refusing a known-weak $var." >&2
      exit 1
      ;;
  esac
done

# --- 3. media mount -----------------------------------------------------------------
# A missing mount is otherwise silent: FreeSWITCH starts happily and only
# fails much later, at playback time, as an opaque PLAYBACK_ERROR.
if [ ! -d "$MEDIA_DIR" ]; then
  echo "FATAL: $MEDIA_DIR does not exist - the application media bind mount failed." >&2
  exit 1
fi

# --- 4. render the ESL config --------------------------------------------------------
# Escape the characters that are special in a sed replacement so that a
# password containing & \ or | cannot corrupt the file.
escaped=$(printf '%s' "$FREESWITCH_PASSWORD" | sed -e 's/[\\&|]/\\&/g')
sed -e "s|@@FREESWITCH_PASSWORD@@|${escaped}|g" "$TEMPLATE" > "$TARGET"
chmod 600 "$TARGET"

if grep -q '@@FREESWITCH_PASSWORD@@' "$TARGET"; then
  echo 'FATAL: ESL password placeholder was not substituted.' >&2
  exit 1
fi

# --- 5. render the directory -------------------------------------------------------
# This REPLACES the image's stock directory/default.xml rather than adding a second
# file beside it. Both are picked up automatically (freeswitch.xml includes
# directory/*.xml at start-up, after this script runs), and having two files that
# each declare a domain with the same name is what allowed a stock user 1001 to
# shadow the test user 1001 and answer a correct password with 403. One file,
# one domain, two users. See infra/freeswitch/conf/directory/default.xml.in.
d1=$(printf '%s' "$SIP_1001_PASSWORD" | sed -e 's/[\\&|]/\\&/g')
d2=$(printf '%s' "$SIP_1002_PASSWORD" | sed -e 's/[\\&|]/\\&/g')
sed -e "s|@@SIP_1001_PASSWORD@@|${d1}|g" -e "s|@@SIP_1002_PASSWORD@@|${d2}|g" \
    "$DIR_TEMPLATE" > "$DIR_TARGET"
chmod 600 "$DIR_TARGET"

if grep -q '@@SIP_100' "$DIR_TARGET"; then
  echo 'FATAL: SIP password placeholder was not substituted.' >&2
  exit 1
fi

# Guard the defect this file exists to prevent: if the stock users were ever
# reintroduced (by a future image, a stray volume mount, or an edit here), the
# platform would accept a REGISTER authenticated with a publicly known password.
# The two test extensions are legitimate, so they are excluded explicitly - a
# blanket pattern such as 10[0-9][0-9] matches 1001 and 1002 as well and would
# refuse to start a correct configuration.
STOCK_USERS=$(grep -o 'user id="[0-9]*"' "$DIR_TARGET" \
              | tr -cd '0-9\n' \
              | grep -v -e '^1001$' -e '^1002$' || true)
if [ -n "$STOCK_USERS" ]; then
  echo 'FATAL: unexpected user ids in the directory:' >&2
  echo "       $STOCK_USERS" >&2
  echo '       Ids outside 1001/1002 belong to the image stock directory and' >&2
  echo '       authenticate with a publicly known password.' >&2
  exit 1
fi

unset FREESWITCH_PASSWORD escaped SIP_1001_PASSWORD SIP_1002_PASSWORD d1 d2

echo "OBD Phase C: ESL config rendered, local SIP test directory rendered, media root present at $MEDIA_DIR"

# --- 4. hand off ---------------------------------------------------------------------
exec /usr/bin/freeswitch
