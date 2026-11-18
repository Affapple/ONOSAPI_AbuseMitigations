#!/usr/bin/env python3
"""B5 live test: conflict detection + spoofing prevention. Runs ON THE VM, under the lock:
    flock /tmp/sdn-onos.lock python3 ~/gw/b5-conflict.py

t2 keeps an H2H intent h2a<->h2b the whole time; it must stay INSTALLED and h2a must
reach h2b (ping through mnctl.sh exec) at every checkpoint.
1. conflict ON, B4 ON: spoofing / capture / rewrite payloads -> 409; legitimate H2H
   intents and owned-source flows of BOTH tenants coexist (no false conflicts).
2. conflict ON, B4 OFF: a broad flow overlapping t2's compiled flow at >= priority -> 409
   overlap_conflict, at a lower priority -> accepted; an empty-selector P2P passes
   submission and is withdrawn by the compiled-flow check (compiled_conflict).
3. conflict OFF (B4 on): what B4 alone lets through (accepted, removed at once).
The B2 limiter is off for the run; all changed config is restored, everything removed.
"""
import subprocess
import sys
import time

from gwlib import BASE, GW, S1, S2, call, check, expect, gw_flow_ids, saved_config, set_cfg, summary, wait_until

H1A, H1B, H2A, H2B = "00:00:00:00:01:01", "00:00:00:00:01:02", "00:00:00:00:02:01", "00:00:00:00:02:02"
IN = lambda p: {"type": "IN_PORT", "port": str(p)}       # noqa: E731
IPV4 = {"type": "ETH_TYPE", "ethType": "0x0800"}
OUT = lambda p: {"type": "OUTPUT", "port": str(p)}       # noqa: E731


def fl(dev, crit, instr, prio=20000):
    return {"flows": [{"priority": prio, "isPermanent": False, "timeout": 60, "deviceId": dev,
                       "selector": {"criteria": crit}, "treatment": {"instructions": instr}}]}


def h2h(key, a, b):
    return {"type": "HostToHostIntent", "key": key, "priority": 100, "one": a + "/None", "two": b + "/None"}


def state(key):
    _, js, _ = call("onos", "GET", BASE + "/onos/v1/intents")
    return {i["key"]: i["state"] for i in js.get("intents", [])}.get(key)


def ping_h2():
    r = subprocess.run(["sh", "/home/mininet/project/topology/mnctl.sh", "exec", "h2a", "ping", "-c", "3", "-W", "1",
                        "10.0.2.2"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)
    line = [l for l in r.stdout.splitlines() if "packet loss" in l]
    return line[0] if line else r.stdout.strip()[-120:]


def t2_ok(where):
    st = state("c-t2")
    p = ping_h2()
    expect("t2 H2H %s: INSTALLED and h2a->h2b ping works" % where, st == "INSTALLED" and " 0% packet loss" in p,
           "state=%s; %s" % (st, p))


def conflicts(t):
    _, js, _ = call("onos", "GET", GW + "/admin/tenants/" + t)
    return js.get("conflicts", {}), js.get("lastConflict")


def post_flow(label, user, body, want, reason=None):
    st, js, _ = check(label, user, "POST", "/flows", want, reason, body=body)
    return [(f["deviceId"], f["flowId"]) for f in js["flows"]] if st == 200 else []


def admin_delete(made):
    if made:
        wait_until(lambda: all(i in gw_flow_ids("onos") for _, i in made), 10)
        check("delete %d flow(s) as admin" % len(made), "onos", "DELETE", "/flows", 204,
              body={"flows": [{"deviceId": d, "flowId": i} for d, i in made]})


SPOOF_MAC = fl(S2, [IN(1), {"type": "ETH_SRC", "mac": H2A}], [OUT(3)])
SPOOF_IP = fl(S2, [IN(1), IPV4, {"type": "IPV4_SRC", "ip": "10.0.2.1/32"}], [OUT(3)])
CAPTURE_MAC = fl(S2, [IN(1), {"type": "ETH_DST", "mac": H2B}], [OUT(3)])
CAPTURE_OWNED_SRC = fl(S1, [IN(1), {"type": "ETH_SRC", "mac": H1A}, {"type": "ETH_DST", "mac": H2B}], [OUT(2)])
CAPTURE_IP = fl(S2, [IN(1), IPV4, {"type": "IPV4_DST", "ip": "10.0.2.0/24"}], [OUT(3)])
REWRITE_SRC = fl(S2, [IN(1)], [{"type": "L2MODIFICATION", "subtype": "ETH_SRC", "mac": H2A}, OUT(3)])
REWRITE_IPDST = fl(S2, [IN(1), IPV4], [{"type": "L3MODIFICATION", "subtype": "IPV4_DST", "ip": "10.0.2.2"}, OUT(3)])
BROAD_S1 = fl(S1, [IN(1)], [OUT(2)])


def main():
    with saved_config(["conflictEnabled", "validationEnabled", "rateLimitEnabled", "adaptiveEnabled"]):
        set_cfg({"rateLimitEnabled": False, "adaptiveEnabled": False, "conflictEnabled": True,
                 "validationEnabled": True})
        print("== 0. t2's reference H2H")
        check("t2-op POST H2H h2a<->h2b", "t2-op", "POST", "/intents", 201, body=h2h("c-t2", H2A, H2B))
        wait_until(lambda: state("c-t2") == "INSTALLED", 20)
        t2_ok("at start")
        c0, _ = conflicts("t1")

        print("== 1. conflict ON, B4 ON: spoofing / capture / rewrites -> 409")
        post_flow("foreign ETH_SRC next to own IN_PORT", "t1-op", SPOOF_MAC, 409, "spoofed_source")
        post_flow("foreign IPV4_SRC next to own IN_PORT", "t1-op", SPOOF_IP, 409, "spoofed_source")
        post_flow("ETH_DST=h2b", "t1-op", CAPTURE_MAC, 409, "dst_capture")
        post_flow("owned ETH_SRC -> ETH_DST=h2b on s1", "t1-op", CAPTURE_OWNED_SRC, 409, "dst_capture")
        post_flow("IPV4_DST=10.0.2.0/24", "t1-op", CAPTURE_IP, 409, "dst_capture")
        post_flow("rewrite ETH_SRC to h2a", "t1-op", REWRITE_SRC, 409, "foreign_rewrite")
        post_flow("rewrite IPV4_DST to h2b", "t1-op", REWRITE_IPDST, 409, "foreign_rewrite")
        check("P2P owned ETH_SRC -> ETH_DST=h2b", "t1-op", "POST", "/intents", 409, "dst_capture",
              body={"type": "PointToPointIntent", "key": "c-p2p-cap", "priority": 100,
                    "ingressPoint": {"device": S2, "port": "1"},
                    "egressPoint": {"device": "of:0000000000000003", "port": "1"},
                    "selector": {"criteria": [{"type": "ETH_SRC", "mac": H1A}, {"type": "ETH_DST", "mac": H2B}]}})
        c1, last = conflicts("t1")
        print("      t1 conflict counters: %s -> %s; last: %s" % (c0, c1, (last or "")[:110]))
        expect("rejections recorded per reason", c1.get("spoofed_source", 0) - c0.get("spoofed_source", 0) == 2
               and c1.get("dst_capture", 0) - c0.get("dst_capture", 0) == 4
               and c1.get("foreign_rewrite", 0) - c0.get("foreign_rewrite", 0) == 2)
        t2_ok("after the rejected payloads")

        print("== 1b. no false conflicts: both tenants' legitimate rules coexist")
        cc_before = conflicts("t1")[0].get("compiled_conflict", 0)     # counters are cumulative: use deltas
        t2_before = sum(conflicts("t2")[0].values())
        check("t1-op POST H2H h1a<->h1b", "t1-op", "POST", "/intents", 201, body=h2h("c-t1", H1A, H1B))
        wait_until(lambda: state("c-t1") == "INSTALLED", 20)
        time.sleep(3)                                    # give the compiled-flow check time to run
        expect("t1 H2H stays INSTALLED (compiled check finds no conflict)", state("c-t1") == "INSTALLED",
               str(state("c-t1")))
        made = []
        made += post_flow("t1 own edge s2/1 -> trunk", "t1-op", fl(S2, [IN(1)], [OUT(3)]), 200)
        made += post_flow("t1 owned ETH_SRC transit on s1", "t1-op",
                          fl(S1, [IN(1), {"type": "ETH_SRC", "mac": H1A}], [OUT(2)]), 200)
        made += post_flow("t1 owned IPV4_SRC on s1 (overlaps t2's flows field-wise, not by source)", "t1-op",
                          fl(S1, [IPV4, {"type": "IPV4_SRC", "ip": "10.0.1.1/32"}], [OUT(2)], prio=30000), 200)
        made += post_flow("t2 own edge s2/2 -> trunk", "t2-op", fl(S2, [IN(2)], [OUT(3)]), 200)
        made += post_flow("t2 owned ETH_SRC transit on s1", "t2-op",
                          fl(S1, [IN(1), {"type": "ETH_SRC", "mac": H2A}], [OUT(2)]), 200)
        c2, _ = conflicts("t2")
        expect("t2 got no new conflict violations", sum(c2.values()) == t2_before, str(c2))
        expect("t1 got no new compiled_conflict", conflicts("t1")[0].get("compiled_conflict", 0) == cc_before,
               str(conflicts("t1")[0]))
        admin_delete(made)
        t2_ok("with both tenants' rules installed")

        print("== 2. conflict ON, B4 OFF: overlap and the compiled-flow check")
        set_cfg({"validationEnabled": False})
        post_flow("s1 IN_PORT=1 only, priority 20000 (>= t2's 100)", "t1-op", BROAD_S1, 409, "overlap_conflict")
        low = post_flow("same at priority 50 (< t2's 100)", "t1-op", fl(S1, [IN(1)], [OUT(2)], prio=50), 200)
        admin_delete(low)
        cc_before = conflicts("t1")[0].get("compiled_conflict", 0)
        check("P2P of:2/1 -> of:3/1, empty selector (passes submission)", "t1-op", "POST", "/intents", 201,
              body={"type": "PointToPointIntent", "key": "c-p2p", "priority": 100,
                    "ingressPoint": {"device": S2, "port": "1"},
                    "egressPoint": {"device": "of:0000000000000003", "port": "1"}})
        gone = wait_until(lambda: state("c-p2p") in ("WITHDRAWN", "WITHDRAWING")
                          and conflicts("t1")[0].get("compiled_conflict", 0) == cc_before + 1, 30)
        c3, last = conflicts("t1")
        print("      c-p2p state=%s; t1 conflicts %s; last: %s" % (state("c-p2p"), c3, (last or "")[:160]))
        expect("compiled-flow check withdrew the P2P (state %s) and recorded exactly one new compiled_conflict"
               % state("c-p2p"), gone)
        t2_ok("after the transit-capture P2P was withdrawn")
        check("purge the withdrawn P2P", "onos", "DELETE", "/intents/c-p2p?tenant=t1", 204)

        print("== 2b. intents installed while conflictEnabled was off are re-checked when it is turned on")
        set_cfg({"conflictEnabled": False})                    # B4 still off
        check("P2P empty selector with conflict OFF", "t1-op", "POST", "/intents", 201,
              body={"type": "PointToPointIntent", "key": "c-p2p-off", "priority": 100,
                    "ingressPoint": {"device": S2, "port": "1"},
                    "egressPoint": {"device": "of:0000000000000003", "port": "1"}})
        wait_until(lambda: state("c-p2p-off") == "INSTALLED", 20)
        time.sleep(3)
        expect("installed and not checked while off", state("c-p2p-off") == "INSTALLED", str(state("c-p2p-off")))
        cc_before = conflicts("t1")[0].get("compiled_conflict", 0)
        set_cfg({"conflictEnabled": True})                     # false -> true: one rescan
        rescanned = wait_until(lambda: state("c-p2p-off") in ("WITHDRAWN", "WITHDRAWING")
                               and conflicts("t1")[0].get("compiled_conflict", 0) == cc_before + 1, 30)
        expect("turning conflictEnabled on withdrew it (state %s, compiled_conflict %d -> %d)"
               % (state("c-p2p-off"), cc_before, conflicts("t1")[0].get("compiled_conflict", 0)), rescanned)
        t2_ok("after the rescan")
        check("purge c-p2p-off", "onos", "DELETE", "/intents/c-p2p-off?tenant=t1", 204)

        print("== 3. conflict OFF: what B4 alone lets through (removed at once)")
        set_cfg({"conflictEnabled": False, "validationEnabled": True})
        made = []
        for label, body in (("foreign ETH_SRC next to own IN_PORT", SPOOF_MAC), ("ETH_DST=h2b", CAPTURE_MAC),
                            ("owned ETH_SRC -> ETH_DST=h2b on s1", CAPTURE_OWNED_SRC),
                            ("rewrite ETH_SRC to h2a", REWRITE_SRC)):
            made += post_flow(label + " (accepted)", "t1-op", body, 200)
        admin_delete(made)
        set_cfg({"validationEnabled": False})
        made = post_flow("s1 IN_PORT=1 only, priority 20000, B4+B5 off (accepted)", "t1-op", BROAD_S1, 200)
        admin_delete(made)
        set_cfg({"validationEnabled": True, "conflictEnabled": True})
        wait_until(lambda: not gw_flow_ids("onos"), 10)
        t2_ok("at the end")

        print("== 4. cleanup")
        for k, t in (("c-t1", "t1"), ("c-t2", "t2")):
            check("delete intent " + k, "onos", "DELETE", "/intents/%s?tenant=%s" % (k, t), 204)
    _, js, _ = call("onos", "GET", BASE + "/onos/v1/intents")
    left = [i["key"] for i in js["intents"] if i["appId"].startswith("lu.uni.sdn.gw")]
    expect("nothing left: intents %s, tenant flows %s" % (left, gw_flow_ids("onos")), not left
           and not gw_flow_ids("onos"))
    return summary("B5")


if __name__ == "__main__":
    sys.exit(main())
