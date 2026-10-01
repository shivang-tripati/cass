#!/bin/sh
# Phase C diagnostic, run inside the platform container.
# Is the stock directory colliding with the mounted test directory?
echo '--- every directory file FreeSWITCH loads ---'
ls /etc/freeswitch/directory/
echo '--- domain names, per file ---'
for f in /etc/freeswitch/directory/*.xml; do
  printf '  %-28s ' "$(basename "$f")"
  grep -o 'domain name=[^ >]*' "$f" | tr '\n' ' '
  echo
done
echo
echo '--- domain names in the COMPILED config, with counts ---'
grep -o 'domain name=[^ >]*' /var/log/freeswitch/freeswitch.xml.fsxml | sort | uniq -c
echo
echo '--- user ids defined by the STOCK default.xml ---'
grep -o 'user id=[^ >]*' /etc/freeswitch/directory/default.xml | tr '\n' ' '
echo
echo '--- is user 1001 defined in the stock file? ---'
grep -c 'user id=.1001.' /etc/freeswitch/directory/default.xml
echo
echo '--- what password does the stock directory use for its users? ---'
grep -n 'default_password' /etc/freeswitch/vars.xml
