#!/bin/bash
# One evaluation run on the VM:   harness/run.sh <C1|C2|C3|C3F> <workload|baseline> <rep>
#
# Holds /tmp/sdn-onos.lock for the whole run:
#   config.sh <config> (ONOS restart, MATCH, purge, every gateway property set + read back,
#   unblock) -> wait until ONOS CPU is stable (last 10 s mean < SETTLE_CPU % and within 20 % of
#   the 10 s before; invalid after SETTLE_MAX s) -> collectors (docker, heap, flows) -> 3 s later L
#   starts (must be defined in $WORKLOADS) -> t0 = LEAD s later, so L's setup and the controller's
#   warm-up of L's code paths fall before the measured window
#   t0-LEAD .. t0+120 L
#   t0+30 .. t0+90    the named workload ("baseline": none)
#   then: collectors stop, tenants.json, meta.json, and the VM goes back to its default state
#   (tenant overlay forced, then config.sh C3 --no-restart: C3 defaults, purge, MATCH).
# Workload templates (WORKLOADS, default harness/workloads.conf; "NAME = command"), placeholders
#   {target} v1|gw  {duration}  {out} <run dir>/<NAME>.csv  {user} {password}  {project}
#   L runs as t2-op, the named workload as t1-op (password <user>-pw).
# Invalid run: a workload exits non-zero or overruns its window by GRACE s (it is killed), a
# collector dies, too few collector rows, L saw ONOS/the gateway down, or the gateway or ONOS
# changed during the run (see onosctl.py meta). Output: results/<config>/<workload>/<rep>/
# A run with a valid meta.json is skipped; any other old run dir is moved to results/_attic/.
# Exit: 0 valid (or skipped), 3 invalid, 1 setup/restore failure, 2 usage.
# Env: WORKLOADS, RESULTS, RESTART (1; 0 = purge only, no ONOS restart), SETTLE_CPU (8),
#      SETTLE_MAX (180), LEAD (30), GRACE (30)
set -u
H=$(cd "$(dirname "$0")" && pwd)
P=$(dirname "$H")
WORKLOADS=$(readlink -f "${WORKLOADS:-$H/workloads.conf}")
RESULTS=$(readlink -m "${RESULTS:-$P/results}")
RESTART=${RESTART:-1}; SETTLE_CPU=${SETTLE_CPU:-8}; SETTLE_MAX=${SETTLE_MAX:-180}; GRACE=${GRACE:-30}
LEAD=${LEAD:-30}; DURATION=120; ATTACK_START=30; ATTACK_LEN=60

usage() { sed -n '2,23p' "$0"; exit 2; }
[ $# = 3 ] && [ -r "$WORKLOADS" ] || usage
cfg=$1; wl=$2; rep=$3
target=$(python3 "$H/onosctl.py" target "$cfg" 2>/dev/null) || usage
R=$RESULTS/$cfg/$wl/$rep
if grep -qs '"valid": true' "$R/meta.json"; then echo "run.sh: skip $cfg/$wl/$rep (done)"; exit 0; fi
render() {  # name duration user
    python3 "$H/onosctl.py" render "$WORKLOADS" "$1" target="$target" duration="$2" out="$R/$1.csv" \
        user="$3" password="$3-pw" project="$P"
}
Lcmd=$(render L $((LEAD + DURATION)) t2-op) || { echo "run.sh: L is not defined in $WORKLOADS"; exit 2; }
Wcmd=""
if [ "$wl" != baseline ]; then Wcmd=$(render "$wl" $ATTACK_LEN t1-op) || { echo "run.sh: $wl not in $WORKLOADS"; exit 2; }; fi
if [ -d "$R" ]; then mkdir -p "$RESULTS/_attic" && mv "$R" "$RESULTS/_attic/$cfg-$wl-$rep.$(date +%s)"; fi
mkdir -p "$R" && cd "$R" || exit 1
exec > >(trap '' INT TERM; exec tee -a run.log) 2>&1  # tee survives a Ctrl-C, so cleanup can still log

now() { date +%s.%N | cut -c1-14; }
at() { awk -v t="$t0" -v a="$1" 'BEGIN{printf "%.3f", t+a}'; }
sleep_to() { sleep "$(awk -v d="$(at "$1")" -v n="$(now)" 'BEGIN{d-=n; print (d>0?d:0)}')"; }
tl() { echo "$1=$2" >> timeline.txt; }
invalid=""
bad() { echo "run.sh: INVALID: $*"; invalid="$invalid;$*"; }
start() {  # name command: own session (no terminal signals), lock fd closed; sets $pid
    setsid bash -c "$2" > "$1.log" 2>&1 < /dev/null 9>&- &
    pid=$!
}
stop() {  # pid: SIGTERM to its group, SIGKILL after 5 s
    kill -TERM -- "-$1" 2>/dev/null
    for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$1" 2>/dev/null || break; sleep 0.5; done
    kill -KILL -- "-$1" 2>/dev/null; wait "$1" 2>/dev/null
}
reap() {  # pid deadline name: wait for a workload until t0+deadline (then kill it), record its exit code
    local rc
    while kill -0 "$1" 2>/dev/null && awk -v d="$(at "$2")" -v n="$(now)" 'BEGIN{exit !(n<=d)}'; do sleep 0.5; done
    if kill -0 "$1" 2>/dev/null; then stop "$1"; rc=124; bad "$3 overran, killed"; else wait "$1"; rc=$?; fi
    tl "ts_$3_end" "$(now)"; tl "exit_$3" $rc; echo "$3 exit $rc"
}
force_tenant() {  # whatever an interrupted config left: container running, tenant overlay in effect
    local i
    for i in $(seq 1 40); do
        sudo timeout -s KILL 30 docker start onos > /dev/null 2>&1
        sudo timeout -s KILL 30 docker cp "$P/karaf/users.tenant.properties" \
            onos:/root/onos/apache-karaf-4.2.14/etc/users.properties 2>/dev/null
        [ "$(curl -s -m 10 -o /dev/null -w '%{http_code}' -u t1-op:t1-op-pw http://localhost:8181/onos/v1/flows)" = 403 ] &&
            { echo "tenant overlay in effect (t1-op GET /onos/v1/flows -> 403)"; return 0; }
        sleep 5
    done
    return 1
}
restore() {  # default VM state; retried, e.g. when an interrupted ONOS restart is still under way
    local i
    for i in 1 2 3; do
        force_tenant && setsid "$H/config.sh" C3 --no-restart && return 0
        echo "restore attempt $i failed"; sleep 10
    done
    return 1
}

tl config "$cfg"; tl scenario "$wl"; tl rep "$rep"; tl target "$target"; tl duration $DURATION
tl cmd_L "$Lcmd"; [ -n "$Wcmd" ] && tl "cmd_$wl" "$Wcmd"
echo "== $cfg/$wl/$rep $(date -Is)"; echo "L: $Lcmd"; echo "$wl: ${Wcmd:-none}"
exec 9>>/tmp/sdn-onos.lock || exit 1
echo "waiting for /tmp/sdn-onos.lock"; flock 9 || exit 1
export SDN_LOCK_HELD=1
tl ts_lock "$(now)"

collectors=""; workloads=""; done_=0
cleanup() {
    trap '' INT TERM PIPE  # a second Ctrl-C must not abort the restore
    [ $done_ = 1 ] && return; done_=1
    for p in $collectors; do kill -0 "$p" 2>/dev/null || bad "collector pid $p died"; done
    for p in $workloads $collectors; do stop "$p"; done
    tl ts_collectors_stop "$(now)"
    python3 "$H/onosctl.py" snapshot . || bad "tenants snapshot failed"
    python3 "$H/onosctl.py" state > state.post.json
    python3 "$H/onosctl.py" host > host.post.json
    tl invalid "$invalid"
    python3 "$H/onosctl.py" meta .
    echo "== restore default VM state"
    restore >> restore.log 2>&1 || { echo "run.sh: RESTORE FAILED (restore.log)"; exit 1; }
    tl ts_end "$(now)"
}
trap cleanup EXIT
trap 'bad interrupted; exit 130' INT TERM

opt=""; [ "$RESTART" = 1 ] || opt=--no-restart
# own session: a Ctrl-C reaches only this script, which then waits for config.sh to finish
if ! setsid "$H/config.sh" "$cfg" $opt --out gwconfig.json > config.log 2>&1; then
    tail -n 15 config.log; bad "config.sh failed"; exit 1
fi
tl ts_configured "$(now)"
python3 "$H/onosctl.py" settle "$SETTLE_MAX" "$SETTLE_CPU" > settle.json  # meta.json: invalid if not stable
tl ts_settled "$(now)"; echo "settle: $(cat settle.json)"
python3 "$H/onosctl.py" state > state.pre.json
for c in docker heap flows; do start "collect-$c" "python3 $H/collect.py $c ."; collectors="$collectors $pid"; done
sleep 3
start L "$Lcmd"; Lpid=$pid; workloads=$Lpid; tl ts_L_start "$(now)"
sleep "$LEAD"
python3 "$H/onosctl.py" host > host.pre.json
t0=$(now); tl t0 "$t0"; tl attack_start "$(at $ATTACK_START)"; tl attack_end "$(at $((ATTACK_START + ATTACK_LEN)))"
if [ -n "$Wcmd" ]; then
    sleep_to $ATTACK_START
    start "$wl" "$Wcmd"; workloads="$workloads $pid"; tl "ts_${wl}_start" "$(now)"
    reap "$pid" $((ATTACK_START + ATTACK_LEN + GRACE)) "$wl"
fi
sleep_to $DURATION
reap "$Lpid" $((DURATION + GRACE)) L
tl ts_run_end "$(now)"
cleanup
grep -q '"valid": true' meta.json && exit 0
exit 3
