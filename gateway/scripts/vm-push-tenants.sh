#!/bin/sh
# Runs ON THE VM. Pushes tenants.json into ONOS network config at
#   apps/lu.uni.sdn.api_gateway/tenants
# The gateway listens for netcfg events and reloads its registry; an invalid
# document is rejected by ONOS (HTTP 400) and the previous registry is kept.
# Push after the app is active (a push made earlier is queued and applied at
# activation). The registry survived ONOS container restarts in Track A's A2
# tests; if it is ever missing the gateway logs a WARN and denies everything
# except whoami until this script is run again.
#
# Must hold the ONOS lock:
#   flock /tmp/sdn-onos.lock sh ~/gw/vm-push-tenants.sh ~/gw/tenants.json
set -eu
TENANTS=$1
APP=lu.uni.sdn.api_gateway
CRED=${ONOS_CRED:-onos:rocks}
URL=http://localhost:8181/onos/v1

echo "== push $TENANTS -> netcfg apps/$APP/tenants"
code=$(curl -s -o /tmp/gw-push.out -w '%{http_code}' -u "$CRED" -X POST -H 'Content-Type: application/json' \
       "$URL/network/configuration/apps/$APP/tenants" --data-binary @"$TENANTS")
echo "HTTP $code"; case $code in 2*) ;; *) cat /tmp/gw-push.out; echo; exit 1;; esac
