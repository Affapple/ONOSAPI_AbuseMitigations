#!/bin/sh
# Runs ON THE VM. Installs (or reinstalls) the gateway app and optionally pushes
# the tenant registry into ONOS network config. Same REST calls as ONOS's own
# `onos-app <node> reinstall! <oar>` (bin/onos-app in the container).
#
# Must hold the ONOS lock:
#   flock /tmp/sdn-onos.lock sh ~/gw/vm-install.sh ~/gw/api_gateway-1.0-SNAPSHOT.oar [~/gw/tenants.json]
# Exits non-zero if ONOS is not ready or any step does not return 2xx.
set -eu
OAR=$1
TENANTS=${2:-}
APP=lu.uni.sdn.api_gateway
CRED=${ONOS_CRED:-onos:rocks}
URL=http://localhost:8181/onos/v1

echo "== wait for ONOS REST (up to 120 s)"
i=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' -u "$CRED" "$URL/applications")" = 200 ]; do
    i=$((i + 1)); [ $i -gt 60 ] && { echo "ONOS not ready" >&2; exit 1; }
    sleep 2
done

echo "== uninstall $APP (ONOS answers 204 whether or not it was installed)"
code=$(curl -s -o /dev/null -w '%{http_code}' -u "$CRED" -X DELETE "$URL/applications/$APP")
echo "HTTP $code"; case $code in 2*) ;; *) exit 1;; esac
# Uninstall can complete asynchronously (seen after an ONOS restart: install got 409).
i=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' -u "$CRED" "$URL/applications/$APP")" = 404 ]; do
    i=$((i + 1)); [ $i -gt 30 ] && { echo "$APP still installed after 60 s" >&2; exit 1; }
    sleep 2
done

echo "== install + activate $OAR"
# ONOS can answer 404 above while Karaf is still tearing the bundle down; the POST then
# gets 409 "already installed" and the late uninstall would leave NO gateway. Retry.
i=0
while :; do
    code=$(curl -s -o /tmp/gw-install.out -w '%{http_code}' -u "$CRED" -X POST \
           -H 'Content-Type: application/octet-stream' "$URL/applications?activate=true" --data-binary @"$OAR")
    echo "HTTP $code"
    case $code in
        2*) break ;;
        409) i=$((i + 1)); [ $i -gt 10 ] && { cat /tmp/gw-install.out; exit 1; }
             echo "uninstall still finishing, retry in 3 s"; sleep 3 ;;
        *) cat /tmp/gw-install.out; exit 1 ;;
    esac
done

if [ -n "$TENANTS" ]; then
    sh "$(dirname "$0")/vm-push-tenants.sh" "$TENANTS"
fi

echo "== app state"
curl -s -u "$CRED" "$URL/applications/$APP" | python3 -c \
  'import json,sys; a=json.load(sys.stdin); print(a["name"], a["version"], a["state"])'

echo "== wait for /onos/gw (up to 60 s)"
i=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' -u "$CRED" http://localhost:8181/onos/gw/whoami)" = 200 ]; do
    i=$((i + 1)); [ $i -gt 30 ] && { echo "/onos/gw not serving" >&2; exit 1; }
    sleep 2
done
echo "/onos/gw/whoami 200"
