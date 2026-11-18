#!/bin/bash
# Apply an evaluation config on the VM and leave ONOS clean for a run.
#
#   harness/config.sh <C1|C2|C3|C3F> [--no-restart] [--out FILE]
#
#   C1  noctl overlay (tenants in admingroup, use /onos/v1); gateway: every module off
#   C2  tenant overlay (tenants use /onos/gw);               gateway: aclEnabled only
#   C3  tenant overlay;                                      gateway: all defaults
#   C3F C3 with throttleMode=fixed (Lab2-like, evaluation question 4)
#
# 1. karaf/apply.sh <overlay>: users.properties, ONOS restart, ready check, discover + MATCH
#    (--no-restart: copy + account probe only)  2. gateway ACTIVE and serving
# 3. purge intents and non-core flows  4. set EVERY gateway property, read all back (--out FILE)
# 5. unblock the tenants  6. mnctl.sh discover: must MATCH
# Exit 1 on any failure. Takes /tmp/sdn-onos.lock unless SDN_LOCK_HELD=1.
set -u
H=$(cd "$(dirname "$0")" && pwd)
P=$(dirname "$H")
die() { echo "config.sh: ABORT: $*" >&2; exit 1; }

cfg=${1:-}; shift
restart=""; out=/dev/null
while [ $# -gt 0 ]; do
    case "$1" in
        --no-restart) restart=--no-restart ;;
        --out) out=$2; shift ;;
        *) die "unknown option $1" ;;
    esac
    shift
done
case "$cfg" in
    C1) overlay=noctl ;;
    C2|C3|C3F) overlay=tenant ;;
    *) sed -n '2,15p' "$0"; exit 2 ;;
esac
if [ "${SDN_LOCK_HELD:-0}" != 1 ]; then
    exec 9>>/tmp/sdn-onos.lock && flock 9 || die "lock"
    export SDN_LOCK_HELD=1
fi

echo "== config $cfg (overlay $overlay $restart) $(date -Is)"
"$P/topology/mnctl.sh" status >/dev/null || die "topology not running"
"$P/karaf/apply.sh" $restart "$overlay" || die "karaf/apply.sh $overlay"
echo "== gateway ready";   python3 "$H/onosctl.py" wait-gw 180 || die "gateway not ready"
echo "== purge";           python3 "$H/onosctl.py" purge || die "purge"
echo "== gateway config";  python3 "$H/onosctl.py" gwconfig "$cfg" /tmp/gwconfig.$$ || die "gateway config"
cat /tmp/gwconfig.$$ > "$out"; rm -f /tmp/gwconfig.$$
echo "== unblock tenants"; python3 "$H/onosctl.py" reset || die "unblock"
echo "== discover";        "$P/topology/mnctl.sh" discover 120 || die "topology check did not MATCH"
echo "== config $cfg done $(date -Is)"
