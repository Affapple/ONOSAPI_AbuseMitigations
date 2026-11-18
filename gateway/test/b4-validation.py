#!/usr/bin/env python3
"""B4 live test: payload validation. Runs ON THE VM, under the ONOS lock:
    flock /tmp/sdn-onos.lock python3 ~/gw/b4-validation.py

1. With validation ON (C3): every S3-style payload, including the auditor's four
   ACL-only payloads, gets 400 with its own reason; legitimate flows/intents pass.
2. With validation and conflict detection OFF (C2, ACL only): the auditor's four
   payloads are accepted.
   The catch-all at priority 40001 on s1 is installed with a 5 s timeout and removed
   at once (it outranks the LLDP punt rules); links are checked afterwards.
The B2 limiter is off for the run; every changed property is restored and every
created flow/intent removed.
"""
import sys
import time

from gwlib import BASE, GW, S1, S2, S3, call, check, expect, gw_flow_ids, saved_config, set_cfg, summary, \
    wait_until

H1A, H1B, H2A = "00:00:00:00:01:01", "00:00:00:00:01:02", "00:00:00:00:02:01"


def fl(dev, prio, criteria, instructions, timeout=60, permanent=False):
    f = {"priority": prio, "isPermanent": permanent, "deviceId": dev,
         "selector": {"criteria": criteria}, "treatment": {"instructions": instructions}}
    if not permanent:
        f["timeout"] = timeout
    return {"flows": [f]}


def out(p):
    return [{"type": "OUTPUT", "port": str(p)}]


IN1 = {"type": "IN_PORT", "port": "1"}


def p2p(key, criteria=None, treatment=None, prio=100):
    i = {"type": "PointToPointIntent", "key": key, "priority": prio,
         "ingressPoint": {"device": S2, "port": "1"}, "egressPoint": {"device": S3, "port": "1"}}
    if criteria is not None:
        i["selector"] = {"criteria": criteria}
    if treatment is not None:
        i["treatment"] = {"instructions": treatment}
    return i


def h2h(key, prio=100):
    return {"type": "HostToHostIntent", "key": key, "priority": prio, "one": H1A + "/None", "two": H1B + "/None"}


# The auditor's ACL-only payloads (B1 notes).
AUD_CATCHALL = fl(S1, 40001, [], out(2), timeout=5)
AUD_ETHDST = fl(S2, 20000, [{"type": "ETH_DST", "mac": H2A}], out(1))
AUD_EMPTY_S3 = fl(S3, 20000, [], out(1))
AUD_P2P = p2p("aud-p2p")


def links():
    _, js, _ = call("onos", "GET", BASE + "/onos/v1/links")
    return len(js["links"])


def main():
    created_flows = []
    created_intents = []
    with saved_config(["validationEnabled", "conflictEnabled", "rateLimitEnabled", "adaptiveEnabled"]):
        set_cfg({"rateLimitEnabled": False, "adaptiveEnabled": False, "validationEnabled": True})
        links0 = links()

        print("== 1. validation ON: the auditor's ACL-only payloads")
        check("catch-all, priority 40001, s1 -> trunk", "t1-op", "POST", "/flows", 400, "bad_priority",
              body=AUD_CATCHALL)
        check("catch-all, priority 20000, s1 -> trunk", "t1-op", "POST", "/flows", 400, "match_not_owned",
              body=fl(S1, 20000, [], out(2)))
        check("s2: no IN_PORT, ETH_DST=h2a -> t1 edge", "t1-op", "POST", "/flows", 400, "match_not_owned",
              body=AUD_ETHDST)
        check("s3: empty selector -> t1 edge", "t1-op", "POST", "/flows", 400, "match_not_owned", body=AUD_EMPTY_S3)
        check("P2P of:2/1 -> of:3/1, empty selector", "t1-op", "POST", "/intents", 400, "selector_not_owned",
              body=AUD_P2P)

        print("== 2. validation ON: one reason per rule")
        check("ETH_TYPE-only match", "t1-op", "POST", "/flows", 400, "match_not_owned",
              body=fl(S2, 20000, [{"type": "ETH_TYPE", "ethType": "0x0800"}], out(3)))
        check("trunk IN_PORT only (s1/1)", "t1-op", "POST", "/flows", 400, "match_not_owned",
              body=fl(S1, 20000, [IN1], out(2)))
        check("drop-all on s2", "t1-op", "POST", "/flows", 400, "drop_not_owned", body=fl(S2, 20000, [], []))
        check("priority 99", "t1-op", "POST", "/flows", 400, "bad_priority", body=fl(S2, 99, [IN1], out(3)))
        check("permanent", "t1-op", "POST", "/flows", 400, "permanent_not_allowed",
              body=fl(S2, 20000, [IN1], out(3), permanent=True))
        check("timeout 301", "t1-op", "POST", "/flows", 400, "bad_timeout", body=fl(S2, 20000, [IN1], out(3), 301))
        check("CONTROLLER output", "t1-op", "POST", "/flows", 400, "controller_output",
              body=fl(S2, 20000, [IN1], [{"type": "OUTPUT", "port": "CONTROLLER"}]))
        check("TUNNEL_ID match", "t1-op", "POST", "/flows", 400, "criterion_not_allowed",
              body=fl(S2, 20000, [IN1, {"type": "TUNNEL_ID", "tunnelId": 5}], out(3)))
        check("P2P selector with t2's ETH_SRC", "t1-op", "POST", "/intents", 400, "selector_not_owned",
              body=p2p("v-p2p-foreign", [{"type": "ETH_SRC", "mac": H2A}]))
        check("P2P rewriting ETH_DST to h2a", "t1-op", "POST", "/intents", 400, "rewrite_not_owned",
              body=p2p("v-p2p-rw", [{"type": "ETH_SRC", "mac": H1A}],
                       [{"type": "L2MODIFICATION", "subtype": "ETH_DST", "mac": H2A}]))
        check("H2H priority 40000", "t1-op", "POST", "/intents", 400, "bad_priority", body=h2h("v-h2h-hi", 40000))
        check("IPV4_SRC without ETH_TYPE (OVS: BAD_PREREQ)", "t1-op", "POST", "/flows", 400, "missing_prerequisite",
              body=fl(S2, 20000, [IN1, {"type": "IPV4_SRC", "ip": "10.0.1.1/32"}], out(3)))
        check("TCP_DST without IP_PROTO", "t1-op", "POST", "/flows", 400, "missing_prerequisite",
              body=fl(S2, 20000, [IN1, {"type": "ETH_TYPE", "ethType": "0x0800"},
                                  {"type": "TCP_DST", "tcpPort": 80}], out(3)))
        body = h2h("v-h2h-c")
        body["constraints"] = [{"type": "BandwidthConstraint", "bandwidth": 1000}]
        check("H2H with constraints", "t1-op", "POST", "/intents", 400, "constraints_not_allowed", body=body)
        time.sleep(1)
        expect("nothing installed by the rejected requests", not gw_flow_ids("onos", "t1"),
               str(gw_flow_ids("onos", "t1")))

        print("== 3. validation ON: legitimate payloads pass")
        st, js, _ = check("own edge port s2/1 -> trunk", "t1-op", "POST", "/flows", 200,
                          body=fl(S2, 20000, [IN1], out(3)))
        created_flows += [(S2, f["flowId"]) for f in (js or {}).get("flows", [])] if st == 200 else []
        st, js, _ = check("owned ETH_SRC transit on s1 (trunk -> trunk)", "t1-op", "POST", "/flows", 200,
                          body=fl(S1, 20000, [IN1, {"type": "ETH_SRC", "mac": H1A}], out(2)))
        created_flows += [(S1, f["flowId"]) for f in (js or {}).get("flows", [])] if st == 200 else []
        st, js, _ = check("owned IPV4_SRC on s1", "t1-op", "POST", "/flows", 200,
                          body=fl(S1, 20000, [{"type": "ETH_TYPE", "ethType": "0x0800"},
                                              {"type": "IPV4_SRC", "ip": "10.0.1.1/32"}], out(2)))
        created_flows += [(S1, f["flowId"]) for f in (js or {}).get("flows", [])] if st == 200 else []
        if check("H2H between own hosts", "t1-op", "POST", "/intents", 201, body=h2h("v-h2h"))[0] == 201:
            created_intents.append("v-h2h")
        if check("P2P with owned ETH_SRC", "t1-op", "POST", "/intents", 201,
                 body=p2p("v-p2p-ok", [{"type": "ETH_SRC", "mac": H1A}]))[0] == 201:
            created_intents.append("v-p2p-ok")

        print("== 4. validation + conflict OFF (C2, ACL only): the auditor's payloads are accepted")
        set_cfg({"validationEnabled": False, "conflictEnabled": False})
        for label, body, dev in (("catch-all, priority 40001, s1 -> trunk (timeout 5 s)", AUD_CATCHALL, S1),
                                 ("s2: ETH_DST=h2a -> t1 edge", AUD_ETHDST, S2),
                                 ("s3: empty selector -> t1 edge", AUD_EMPTY_S3, S3)):
            st, js, _ = check(label, "t1-op", "POST", "/flows", 200, body=body)
            if st == 200:
                fid = js["flows"][0]["flowId"]
                created_flows.append((dev, fid))
                if dev == S1:   # outranks the LLDP punt: remove at once
                    wait_until(lambda: fid in gw_flow_ids("onos", "t1"), 5)
                    check("remove the priority-40001 catch-all now", "onos", "DELETE",
                          "/flows/%s/%s" % (S1, fid), 204)
                    created_flows.remove((dev, fid))
        if check("P2P empty selector", "t1-op", "POST", "/intents", 201, body=AUD_P2P)[0] == 201:
            created_intents.append("aud-p2p")

        print("== 5. cleanup")
        for k in created_intents:
            check("delete intent " + k, "onos", "DELETE", "/intents/%s?tenant=t1" % k, 204)
        if created_flows:
            wait_until(lambda: all(i in gw_flow_ids("onos", "t1") for _, i in created_flows), 10)
            check("delete %d flows" % len(created_flows), "onos", "DELETE", "/flows", 204,
                  body={"flows": [{"deviceId": d, "flowId": i} for d, i in created_flows]})
        wait_until(lambda: not gw_flow_ids("onos", "t1"), 15)
        time.sleep(3)
        expect("links unchanged after the brief catch-all (%d)" % links0, links() == links0, str(links()))
    _, js, _ = call("onos", "GET", BASE + "/onos/v1/intents")
    left_int = [i["key"] for i in js["intents"] if i["appId"].startswith("lu.uni.sdn.gw")]
    expect("nothing left: intents %s, t1 flows %s" % (left_int, gw_flow_ids("onos", "t1")),
           not left_int and not gw_flow_ids("onos", "t1"))
    return summary("B4")


if __name__ == "__main__":
    sys.exit(main())
