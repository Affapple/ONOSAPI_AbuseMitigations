#!/usr/bin/env python3
"""Multi-tenant Mininet topology for the SDN API-abuse project (Track A, step A1).

              s1 (shared core)
             /                \\
          s2                    s3
       /      \\              /      \\
    h1a        h2a        h1b        h2b

Tenant t1: h1a, h1b (10.0.1.0/24)    Tenant t2: h2a, h2b (10.0.2.0/24)
All hosts are configured with a /16 so ARP works across tenants; tenant
isolation is a policy question, not an addressing one.

Port numbers are fixed here (addLink port1/port2), so the connect points in
tenants.json never shift.

Usage:
  python3 tenants.py json [OUT]     write the tenant registry (no Mininet needed)
  sudo python3 tenants.py run       build the net, then block until SIGTERM/SIGINT,
                                    then net.stop(). Meant to be started detached
                                    by mnctl.sh (no interactive CLI).
Options for 'run':
  --state-dir DIR   where pid/state files go (default /tmp/sdn-tenants)
  --controller IP:PORT  (default 127.0.0.1:6653)
  --static-arp      pre-populate ARP caches on all hosts (see O5 notes)
"""
import argparse
import json
import os
import signal
import sys
import time

DPID = {"s1": 1, "s2": 2, "s3": 3}

# name, tenant, ip, mac, switch, switch port
HOSTS = [
    ("h1a", "t1", "10.0.1.1", "00:00:00:00:01:01", "s2", 1),
    ("h1b", "t1", "10.0.1.2", "00:00:00:00:01:02", "s3", 1),
    ("h2a", "t2", "10.0.2.1", "00:00:00:00:02:01", "s2", 2),
    ("h2b", "t2", "10.0.2.2", "00:00:00:00:02:02", "s3", 2),
]
HOST_PREFIXLEN = 16

# (switch a, port on a, switch b, port on b)
TRUNKS = [
    ("s1", 1, "s2", 3),
    ("s1", 2, "s3", 3),
]

TENANTS = {
    "t1": {"subnets": ["10.0.1.0/24"],
           "accounts": {"t1-op": "operator", "t1-view": "viewer"}},
    "t2": {"subnets": ["10.0.2.0/24"],
           "accounts": {"t2-op": "operator", "t2-view": "viewer"}},
}


def onos_dev(sw):
    return "of:%016x" % DPID[sw]


def cp(sw, port):
    return "%s/%d" % (onos_dev(sw), port)


def registry():
    tenants = {}
    for tid, t in TENANTS.items():
        hosts = [h for h in HOSTS if h[1] == tid]
        tenants[tid] = {
            "subnets": list(t["subnets"]),
            "hosts": [{"name": n, "mac": mac, "ip": ip, "location": cp(sw, p)}
                      for (n, _, ip, mac, sw, p) in hosts],
            "edgePorts": [cp(sw, p) for (_, _, _, _, sw, p) in hosts],
            "accounts": dict(t["accounts"]),
            "quotas": {},
        }
    trunk_ports = []
    for (a, pa, b, pb) in TRUNKS:
        trunk_ports += [cp(a, pa), cp(b, pb)]
    return {
        "tenants": tenants,
        "trunkPorts": sorted(trunk_ports),
        "admins": ["onos"],
        "serviceAccount": "gw-svc",
    }


def write_json(path):
    with open(path, "w") as f:
        json.dump(registry(), f, indent=2)
        f.write("\n")
    print("wrote %s" % path)


def ipv6_off(h):
    """Keep IPv6 autoconf/ND chatter out of the workloads.

    On the course VM the kernel boots with ipv6.disable=1 (see /proc/cmdline),
    so /proc/sys/net/ipv6 does not exist and there is nothing to do. On a
    kernel with IPv6, disable it per host and fail loudly if that fails."""
    if h.cmd("test -d /proc/sys/net/ipv6 && echo yes").strip() != "yes":
        return "kernel-disabled"
    out = h.cmd("sysctl -w net.ipv6.conf.all.disable_ipv6=1 "
                "net.ipv6.conf.default.disable_ipv6=1 >/dev/null 2>&1; "
                "cat /proc/sys/net/ipv6/conf/all/disable_ipv6").strip()
    if out != "1":
        raise RuntimeError("%s: could not disable IPv6 (got %r)" % (h.name, out))
    return "sysctl-disabled"


def new_net(controller):
    from mininet.net import Mininet
    from mininet.node import RemoteController, OVSSwitch
    from mininet.link import Link

    ip, port = controller.split(":")
    net = Mininet(controller=None, switch=OVSSwitch, link=Link,
                  autoSetMacs=False, autoStaticArp=False, build=False)
    net.addController("c0", controller=RemoteController, ip=ip, port=int(port))
    return net


def build_net(net, static_arp):
    """Populate and start 'net'. Returns per-host IPv6 status."""
    sw = {}
    for name, dpid in sorted(DPID.items()):
        sw[name] = net.addSwitch(name, dpid="%016x" % dpid,
                                 protocols="OpenFlow13", failMode="secure")
    for (a, pa, b, pb) in TRUNKS:
        net.addLink(sw[a], sw[b], port1=pa, port2=pb)
    for (n, _, hip, mac, s, p) in HOSTS:
        h = net.addHost(n, ip="%s/%d" % (hip, HOST_PREFIXLEN), mac=mac)
        net.addLink(h, sw[s], port2=p)
    net.build()
    net.start()
    ipv6 = {h.name: ipv6_off(h) for h in net.hosts}
    if static_arp:
        net.staticArp()
    return ipv6


def run(args):
    os.makedirs(args.state_dir, exist_ok=True)
    pidfile = os.path.join(args.state_dir, "tenants.pid")
    statefile = os.path.join(args.state_dir, "state.json")
    stop_sigs = {signal.SIGTERM, signal.SIGINT}
    stop = {"flag": False}

    def on_signal(signum, _frame):
        # only reached while the net is being built (signals are blocked
        # afterwards and collected with sigwait)
        stop["flag"] = True

    for sig in stop_sigs:
        signal.signal(sig, on_signal)

    with open(pidfile, "w") as f:
        f.write("%d\n" % os.getpid())
    net = None
    rc = 0
    try:
        net = new_net(args.controller)
        ipv6 = build_net(net, args.static_arp)
        state = {
            "pid": os.getpid(),
            "started": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "controller": args.controller,
            "staticArp": args.static_arp,
            "ipv6": ipv6,
            "hosts": {h.name: {"pid": h.pid, "ip": h.IP(), "mac": h.MAC()}
                      for h in net.hosts},
            "switches": {s.name: {"dpid": s.dpid} for s in net.switches},
        }
        with open(statefile + ".tmp", "w") as f:
            json.dump(state, f, indent=2)
        os.rename(statefile + ".tmp", statefile)
        print("topology up: %s" % json.dumps(state), flush=True)
        # Race-free wait: block the stop signals, then check the flag (set by
        # a signal that arrived during the build), then sigwait. A signal that
        # arrives after the block stays pending and sigwait returns at once.
        # (Blocking only now: the host shells forked during the build must
        # not inherit a blocked SIGINT/SIGTERM.)
        signal.pthread_sigmask(signal.SIG_BLOCK, stop_sigs)
        if not stop["flag"]:
            sig = signal.sigwait(stop_sigs)
            print("got signal %d" % sig, flush=True)
        signal.pthread_sigmask(signal.SIG_UNBLOCK, stop_sigs)
    except BaseException as e:
        rc = 1
        print("topology FAILED: %r" % (e,), flush=True)
        import traceback
        traceback.print_exc()
    finally:
        print("stopping topology", flush=True)
        for p in (statefile + ".tmp", statefile):
            try:
                os.remove(p)
            except OSError:
                pass
        if net is not None:
            try:
                net.stop()
            except Exception as e:
                rc = 1
                print("net.stop() failed: %r; run 'mnctl.sh clean'" % (e,), flush=True)
        try:
            os.remove(pidfile)
        except OSError:
            pass
        print("stopped", flush=True)
    return rc


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd")
    j = sub.add_parser("json")
    j.add_argument("out", nargs="?",
                   default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                        "tenants.json"))
    r = sub.add_parser("run")
    r.add_argument("--state-dir", default="/tmp/sdn-tenants")
    r.add_argument("--controller", default="127.0.0.1:6653")
    r.add_argument("--static-arp", action="store_true")
    args = ap.parse_args()
    if args.cmd == "json":
        write_json(args.out)
    elif args.cmd == "run":
        if os.geteuid() != 0:
            sys.exit("run needs root (sudo)")
        sys.exit(run(args))
    else:
        ap.print_help()
        sys.exit(2)


if __name__ == "__main__":
    main()
