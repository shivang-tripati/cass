#!/bin/sh
# Phase C diagnostic, run INSIDE the platform container.
# Deliberately avoids quotes and angle brackets in the greps: this is invoked
# through several layers of shell quoting from PowerShell, and a stray quote
# turns a diagnostic into a syntax error.
echo '--- domain names present in the LIVE directory file ---'
grep -o 'domain name=[^ >]*' /etc/freeswitch/directory/obd-test.xml
echo '--- domain names present in the COMPILED config ---'
grep -o 'domain name=[^ >]*' /var/log/freeswitch/freeswitch.xml.fsxml | sort | uniq -c
echo '--- user ids present in the LIVE directory file ---'
grep -o 'user id=[^ >]*' /etc/freeswitch/directory/obd-test.xml
echo '--- password param present, and does it look substituted? ---'
grep -c 'name=.password.' /etc/freeswitch/directory/obd-test.xml
grep -c '@@' /etc/freeswitch/directory/obd-test.xml
echo '--- the length of each rendered password (not the value) ---'
sed -n 's/.*name=.password. value=.\(.*\).\/>/\1/p' /etc/freeswitch/directory/obd-test.xml | awk '{print "  length", length($0)}'
echo '--- what the stock vars.xml sets domain to ---'
grep -n 'data=.domain' /etc/freeswitch/vars.xml
