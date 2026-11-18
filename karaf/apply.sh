#!/bin/bash
# Apply a Karaf account overlay to the ONOS container (runs on the VM).
#
#   karaf/apply.sh [--no-restart] tenant   C2/C3: tenant accounts in tenantgroup (role
#                                          'tenant'), gw-svc in guestgroup (viewer), no guest
#   karaf/apply.sh [--no-restart] noctl    C1 "no controls": tenant accounts in admingroup
#   karaf/apply.sh [--no-restart] stock    the container's original users.properties
#
# Karaf's JAAS PropertiesLoginModule re-reads users.properties on every login,
# so a copied overlay is live within seconds, even without a restart (verified:
# step 5 below polls for it). The restart is still the default because
# evaluation runs want a clean ONOS; --no-restart is for quick switches.
#
# Steps (all under /tmp/sdn-onos.lock, which this script takes itself; set
# SDN_LOCK_HELD=1 if the caller already holds it, e.g. the harness):
#   0. save the container's current users.properties (for rollback)
#   1. docker cp users.<overlay>.properties -> $KARAF/etc/users.properties, verify md5
#   2. docker restart onos                                  (skipped with --no-restart)
#   3. ready check: /onos/v1/devices 200 for onos:rocks     (skipped with --no-restart)
#   4. if the tenant topology runs: mnctl.sh discover, must MATCH (skipped with --no-restart)
#   5. account probe: t1-op and guest on GET /onos/v1/flows must give the overlay's
#      expected codes (polled for up to PROBE_TIMEOUT s)
#   6. print the state of apps that matter for the evaluation
# If any step after the copy fails, the saved file is copied back (live at
# once, thanks to the per-login re-read) and the script exits non-zero.
set -u
DIR=$(cd "$(dirname "$0")" && pwd)
KARAF=/root/onos/apache-karaf-4.2.14
UP=$KARAF/etc/users.properties
LOCK=/tmp/sdn-onos.lock
READY_TIMEOUT=${READY_TIMEOUT:-300}
PROBE_TIMEOUT=${PROBE_TIMEOUT:-20}
MNCTL=$DIR/../topology/mnctl.sh
URL=http://localhost:8181

die() { echo "apply.sh: $*" >&2; exit 1; }

restart=1
if [ "${1:-}" = --no-restart ]; then restart=0; shift; fi
ov=${1:-}
[ -n "$ov" ] || { sed -n '2,27p' "$0"; exit 2; }
src=$DIR/users.$ov.properties
[ -r "$src" ] || die "no overlay $src"
# expected HTTP codes on GET /onos/v1/flows: "<t1-op> <guest>"
case "$ov" in
    tenant) expect="403 401" ;;
    noctl)  expect="200 401" ;;
    stock)  expect="401 200" ;;
    *)      expect="" ;;
esac

if [ "${SDN_LOCK_HELD:-0}" != 1 ]; then
    exec 9>>"$LOCK" || die "cannot open $LOCK"
    echo "waiting for $LOCK ..."
    flock 9 || die "flock failed"
    export SDN_LOCK_HELD=1
fi

echo "== 0. save current users.properties"
backup=$(mktemp /tmp/users.properties.prev.XXXXXX)
sudo docker exec onos cat $UP > "$backup" && [ -s "$backup" ] || die "could not save current $UP"
echo "saved to $backup (md5 $(md5sum < "$backup" | cut -c1-32))"

fail() {
    echo "apply.sh: FAILED: $*" >&2
    echo "apply.sh: restoring previous users.properties from $backup" >&2
    if sudo docker cp "$backup" onos:$UP &&
       [ "$(sudo docker exec onos md5sum $UP | cut -c1-32)" = "$(md5sum < "$backup" | cut -c1-32)" ]; then
        echo "apply.sh: previous users.properties restored (live on next login)" >&2
        rm -f "$backup"
    else
        echo "apply.sh: RESTORE FAILED; previous file kept at $backup" >&2
    fi
    exit 1
}

echo "== 1. copy $src"
sudo docker cp "$src" onos:$UP || fail "docker cp"
want=$(md5sum < "$src" | cut -c1-32)
got=$(sudo docker exec onos md5sum $UP | cut -c1-32)
[ "$want" = "$got" ] || fail "md5 mismatch after copy ($want != $got)"
echo "users.properties md5 $got"

if [ $restart = 1 ]; then
    echo "== 2. docker restart onos"
    t0=$(date +%s)
    sudo docker restart onos >/dev/null || fail "docker restart"

    echo "== 3. ready check"
    code=000
    while [ $(( $(date +%s) - t0 )) -lt "$READY_TIMEOUT" ]; do
        code=$(curl -s -o /dev/null -w '%{http_code}' -u onos:rocks $URL/onos/v1/devices)
        [ "$code" = 200 ] && break
        sleep 2
    done
    [ "$code" = 200 ] || fail "ONOS not ready after ${READY_TIMEOUT}s (last HTTP $code)"
    echo "ONOS ready after $(( $(date +%s) - t0 ))s"

    echo "== 4. host rediscovery"
    echo "-- ONOS view right after restart, before rediscovery (informational):"
    python3 "$DIR/../topology/check_onos.py" 2>&1 | grep -E '^hosts|^  h|^devices|RESULT' | sed 's/^/   /'
    echo "-- rediscovery:"
    if "$MNCTL" status >/dev/null 2>&1; then
        "$MNCTL" discover 120 || fail "discover/check_onos did not MATCH"
    else
        echo "tenant topology not running: skipped (start it, then run mnctl.sh discover)"
    fi
else
    echo "== 2-4. skipped (--no-restart)"
fi

echo "== 5. account probe (GET /onos/v1/flows)"
if [ -n "$expect" ]; then
    t0=$(date +%s); seen=""
    while :; do
        a=$(curl -s -o /dev/null -w '%{http_code}' -u t1-op:t1-op-pw $URL/onos/v1/flows)
        b=$(curl -s -o /dev/null -w '%{http_code}' -u guest:guest $URL/onos/v1/flows)
        seen="$a $b"
        [ "$seen" = "$expect" ] && break
        [ $(( $(date +%s) - t0 )) -ge "$PROBE_TIMEOUT" ] && break
        sleep 1
    done
    echo "t1-op/guest -> $seen (expected $expect) after $(( $(date +%s) - t0 ))s"
    [ "$seen" = "$expect" ] || fail "overlay not in effect"
else
    echo "no probe defined for overlay '$ov'"
fi

echo "== 6. app state"
for app in org.onosproject.fwd org.onosproject.proxyarp org.onosproject.hostprovider \
           lu.uni.sdn.southbound_mac_extractor lu.uni.sdn.api_gateway; do
    st=$(curl -s -u onos:rocks $URL/onos/v1/applications/$app |
         python3 -c 'import json,sys; print(json.load(sys.stdin).get("state","?"))' 2>/dev/null)
    echo "$app ${st:-not installed}"
    if [ "$app" = org.onosproject.fwd ] && [ "$st" = ACTIVE ]; then
        echo "apply.sh: WARNING org.onosproject.fwd is ACTIVE (its flows pollute the measurements)" >&2
    fi
done
rm -f "$backup"
exit 0
