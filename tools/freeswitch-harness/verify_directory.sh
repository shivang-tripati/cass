#!/bin/sh
# Phase C verification, run inside the platform container.
# Confirms the directory is exactly what the project intends: ONE domain, the
# dial-string parameter present, and no stock users.
echo '--- user ids defined ---'
grep -o 'user id="[0-9]*"' /etc/freeswitch/directory/default.xml | sort | uniq -c
echo '--- dial-string parameter present? ---'
grep -c 'name="dial-string"' /etc/freeswitch/directory/default.xml
echo '--- any unsubstituted token? ---'
grep -c '@@' /etc/freeswitch/directory/default.xml
echo '--- any stock user left in the COMPILED config? ---'
grep -o 'user id="[0-9]*"' /var/log/freeswitch/freeswitch.xml.fsxml | sort | uniq -c
echo '--- domains in the compiled config ---'
grep -o 'domain name="[^ >]*"' /var/log/freeswitch/freeswitch.xml.fsxml | sort | uniq -c
