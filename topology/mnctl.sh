#!/bin/bash
# Control the tenant topology on the VM without an interactive Mininet CLI.
#
#   mnctl.sh start [--static-arp]   build the net detached (setsid+nohup, PID file)
#   mnctl.sh stop                   SIGTERM the daemon -> net.stop(); wait for exit
#   mnctl.sh status                 daemon pid, host pids, OVS controller connection
#   mnctl.sh exec <host> <cmd...>   run a command inside a Mininet host (mnexec -a)
#   mnctl.sh discover [timeout]     wait for 3 devices + 4 links in ONOS, make every
#                                   host send one ARP request, then run check_onos.py
#                                   and return its exit code (0 = MATCH)
#   mnctl.sh clean                  sudo mn -c (removes stale bridges/interfaces)
#
# start/stop/clean/discover change shared state: run them under the team lock:
#   flock /tmp/sdn-onos.lock -c "~/project/topology/mnctl.sh start"
# This script never takes the lock itself (nested flock on the same file would
# deadlock), and the daemon is launched with every fd >= 3 closed so it never
# inherits (and holds on to) the lock.
set -u
DIR=$(cd "$(dirname "$0")" && pwd)
STATE_DIR=${SDN_TENANTS_STATE:-/tmp/sdn-tenants}
PIDFILE=$STATE_DIR/tenants.pid
STATE=$STATE_DIR/state.json
LOG=${SDN_TENANTS_LOG:-/tmp/sdn-tenants.log}

die() { echo "mnctl: $*" >&2; exit 1; }

daemon_pid() {
    [ -r "$PIDFILE" ] || return 1
    local p; p=$(cat "$PIDFILE")
    [ -n "$p" ] && sudo kill -0 "$p" 2>/dev/null && echo "$p"
}

host_pid() {
    [ -r "$STATE" ] || die "topology not running (no $STATE)"
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["hosts"][sys.argv[2]]["pid"])' \
        "$STATE" "$1" 2>/dev/null || die "unknown host '$1'"
}

cmd_start() {
    local p b left=""
    if p=$(daemon_pid); then die "already running (pid $p)"; fi
    for b in s1 s2 s3; do
        sudo ovs-vsctl br-exists "$b" && left="$left $b"
    done
    [ -z "$left" ] || die "leftover OVS bridge(s):$left (crashed run?). Run 'mnctl.sh clean' under the lock first"
    sudo rm -f "$STATE" "$PIDFILE"
    sudo mkdir -p "$STATE_DIR"
    : > "$LOG" 2>/dev/null || sudo sh -c ": > '$LOG'; chmod 666 '$LOG'"
    (
        # close inherited fds (e.g. the flock fd) before daemonizing
        for fd in $(ls /proc/$BASHPID/fd); do
            [ "$fd" -gt 2 ] && eval "exec $fd>&-" 2>/dev/null
        done
        exec sudo setsid nohup python3 "$DIR/tenants.py" run --state-dir "$STATE_DIR" "$@" \
            >>"$LOG" 2>&1 </dev/null
    ) &
    local bg=$!
    for _ in $(seq 1 60); do
        if [ -r "$STATE" ]; then
            echo "started: daemon pid $(cat "$PIDFILE"), log $LOG"
            cat "$STATE"; echo
            return 0
        fi
        # the launcher (sudo) exits when the daemon exits: a crash during the build
        [ -d "/proc/$bg" ] || break
        sleep 0.5
    done
    echo "--- log ---"; cat "$LOG"
    die "topology did not come up (daemon exited or 30 s timeout)"
}

cmd_stop() {
    local p
    p=$(daemon_pid) || { echo "not running"; sudo rm -f "$PIDFILE" "$STATE"; return 0; }
    sudo kill -TERM "$p"
    for _ in $(seq 1 60); do
        sudo kill -0 "$p" 2>/dev/null || { echo "stopped (pid $p)"; tail -n 3 "$LOG"; return 0; }
        sleep 0.5
    done
    die "pid $p still alive after 30 s; consider 'mnctl.sh clean'"
}

cmd_status() {
    local p
    if p=$(daemon_pid); then
        echo "running: daemon pid $p"
        python3 - "$STATE" <<'EOF'
import json, os, sys
s = json.load(open(sys.argv[1]))
print("started %s controller %s staticArp %s" % (s["started"], s["controller"], s["staticArp"]))
for n, h in sorted(s["hosts"].items()):
    alive = os.path.exists("/proc/%d" % h["pid"])
    print("  %-4s pid %-6d %-12s %s %s" % (n, h["pid"], h["ip"], h["mac"], "alive" if alive else "DEAD"))
EOF
        sudo ovs-vsctl show | grep -E 'Bridge|is_connected|error' | sed 's/^/  /'
    else
        echo "not running"
        return 3
    fi
}

cmd_exec() {
    [ $# -ge 2 ] || die "usage: exec <host> <cmd...>"
    local h=$1; shift
    local p; p=$(host_pid "$h") || exit 1
    exec sudo mnexec -a "$p" "$@"
}

cmd_discover() {
    local timeout=${1:-60}
    [ -r "$STATE" ] || die "topology not running"
    # 1. wait until ONOS sees every switch and every trunk link
    python3 "$DIR/check_onos.py" --infra-only --wait "$timeout" \
        || die "ONOS does not see the expected devices/links (see above)"
    # 2. Host learning. ONOS 2.7's hostprovider learns a host's IP only from
    # ARP; its priority-40000 ARP->CONTROLLER rule is always installed. The
    # priority-5 ip/arp->CONTROLLER rules come from the Lab3 app
    # (lu.uni.sdn.southbound_mac_extractor, MacCollector.requestPackets) and
    # exist only while that app is active; with them, plain IPv4 packet-ins
    # also teach hostprovider MAC+location (but no IP). We don't rely on them:
    # for each host, drop the neighbour entry of its same-tenant peer, ping
    # once (the ARP request is punted to ONOS; nothing answers it, there is
    # no proxyarp), then restore the permanent entry if the topology runs
    # with static ARP. Same-tenant peers only: no cross-tenant traffic.
    local static
    static=$(python3 -c 'import json,sys; print(int(json.load(open(sys.argv[1]))["staticArp"]))' "$STATE")
    python3 - "$DIR/tenants.json" <<'PYEOF' |
import json, sys
reg = json.load(open(sys.argv[1]))
for t in reg["tenants"].values():
    for h in t["hosts"]:
        for p in t["hosts"]:
            if p is not h:
                print(h["name"], p["ip"], p["mac"])
                break
PYEOF
    while read -r h ip mac; do
        local pid; pid=$(host_pid "$h") || exit 1
        sudo mnexec -a "$pid" ip neigh del "$ip" dev "$h-eth0" 2>/dev/null
        printf '%s -> %s: ' "$h" "$ip"
        sudo mnexec -a "$pid" ping -c 1 -W 1 "$ip" >/dev/null 2>&1 \
            && printf 'reply' || printf 'no reply'
        if [ "$static" = 1 ]; then
            sudo mnexec -a "$pid" ip neigh replace "$ip" lladdr "$mac" dev "$h-eth0" nud permanent \
                && printf ', static ARP entry restored' || printf ', FAILED to restore static ARP'
        fi
        echo
    done
    # 3. verify against tenants.json; the exit code is the result
    python3 "$DIR/check_onos.py" --wait 10
}

# WARNING: mn -c kills every process whose command line contains "mininet:",
# including the calling ssh shell if that string appears in it.
cmd_clean() { sudo mn -c 2>&1 | tail -n 5; sudo rm -f "$PIDFILE" "$STATE"; }

c=${1:-}; [ $# -gt 0 ] && shift
case "$c" in
    start) cmd_start "$@" ;;
    stop) cmd_stop ;;
    status) cmd_status ;;
    exec) cmd_exec "$@" ;;
    discover) cmd_discover "$@" ;;
    clean) cmd_clean ;;
    *) sed -n '2,15p' "$0"; exit 2 ;;
esac
