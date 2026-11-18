#!/usr/bin/env python3
"""B7 live test: bypass auditor + rejection counters. Runs ON THE VM, under the lock:
    flock /tmp/sdn-onos.lock python3 ~/gw/b7-audit.py

(a) normal gateway operations of both tenants (flows + H2H intents) -> 0 bypass events;
(b) an admin flow via /onos/v1/flows with no appId (codec default org.onosproject.rest)
    -> exactly one bypass event with the right device/appId/flowId; then removed;
(b2) an admin flow via /onos/v1/flows?appId=lu.uni.sdn.gw.t1 (V3 spoof) -> flagged;
(c) an admin intent via /onos/v1/intents under lu.uni.sdn.gw.t1 (V7 spoof) -> the intent is
    flagged (its compiled flows belong to a tenant intent and are not double-counted);
(d) auditEnabled=false -> no events; (e) rejection counters per tenant/module/reason.
B2/B6 are off for the run; config restored; everything created is removed.
"""
import sys
import time

from gwlib import BASE, GW, S2, call, check, expect, gw_flow_ids, saved_config, set_cfg, summary, wait_until

V1 = BASE + "/onos/v1"
H1A, H1B, H2A, H2B = "00:00:00:00:01:01", "00:00:00:00:01:02", "00:00:00:00:02:01", "00:00:00:00:02:02"


def audit():
    _, js, _ = call("onos", "GET", GW + "/admin/audit")
    return js


def flow(in_port, prio=20000):
    return {"priority": prio, "isPermanent": False, "timeout": 120, "deviceId": S2,
            "selector": {"criteria": [{"type": "IN_PORT", "port": str(in_port)},
                                      {"type": "ETH_TYPE", "ethType": "0x0800"}]},
            "treatment": {"instructions": [{"type": "OUTPUT", "port": "3"}]}}


def h2h(key, a, b):
    return {"type": "HostToHostIntent", "key": key, "priority": 100, "one": a + "/None", "two": b + "/None"}


def state(key):
    _, js, _ = call("onos", "GET", V1 + "/intents")
    return {i["key"]: i["state"] for i in js.get("intents", [])}.get(key)


def main():
    with saved_config(["auditEnabled", "auditAllowedApps", "rateLimitEnabled", "adaptiveEnabled"]):
        set_cfg({"auditEnabled": True, "rateLimitEnabled": False, "adaptiveEnabled": False})
        check("reset audit counters", "onos", "POST", "/admin/audit/reset", 204)
        a = audit()
        print("      allowedApps=%s auditEnabled=%s" % (a["allowedApps"], a["auditEnabled"]))
        expect("allowlist default derived from the VM", a["allowedApps"] == ["org.onosproject.core"])

        print("== (a) normal gateway operations -> 0 bypass events")
        made = []
        for user, port in (("t1-op", 1), ("t2-op", 2)):
            st, js, _ = check("%s flow via the gateway" % user, user, "POST", "/flows", 200, body={"flows": [flow(port)]})
            if st == 200:
                made.append((S2, js["flows"][0]["flowId"]))
        check("t1 H2H via the gateway", "t1-op", "POST", "/intents", 201, body=h2h("a-t1", H1A, H1B))
        check("t2 H2H via the gateway", "t2-op", "POST", "/intents", 201, body=h2h("a-t2", H2A, H2B))
        wait_until(lambda: state("a-t1") == "INSTALLED" and state("a-t2") == "INSTALLED", 20)
        time.sleep(4)                                    # intent-compiled flows are checked after 2 s
        a = audit()
        print("      bypassTotal=%s trackedGatewayFlows=%s trackedGatewayIntents=%s"
              % (a["bypassTotal"], a["trackedGatewayFlows"], a["trackedGatewayIntents"]))
        expect("0 bypass events after normal operations (incl. 12 intent-compiled flows)", a["bypassTotal"] == 0,
               str(a["recent"])[:300])
        wait_until(lambda: all(i in gw_flow_ids("onos") for _, i in made), 10)
        check("cleanup flows (gateway, admin)", "onos", "DELETE", "/flows", 204,
              body={"flows": [{"deviceId": d, "flowId": i} for d, i in made]})
        for k, t in (("a-t1", "t1"), ("a-t2", "t2")):
            check("cleanup intent " + k, "onos", "DELETE", "/intents/%s?tenant=%s" % (k, t), 204)

        print("== (b) admin flow via /onos/v1/flows (default appId org.onosproject.rest) -> one event")
        st, js, _ = call("onos", "POST", V1 + "/flows", {"flows": [flow(1, 20100)]})
        fid = js["flows"][0]["flowId"] if st == 200 else None
        print("      POST /onos/v1/flows -> %s flowId=%s" % (st, fid))
        wait_until(lambda: audit()["bypassTotal"] >= 1, 10)
        time.sleep(2)
        a = audit()
        last = a["recent"][-1] if a["recent"] else {}
        print("      bypassTotal=%s recent[-1]=%s" % (a["bypassTotal"], last))
        expect("exactly one event: flow, of:..02, org.onosproject.rest, that flowId",
               a["bypassTotal"] == 1 and last.get("kind") == "flow" and last.get("device") == S2
               and last.get("appId") == "org.onosproject.rest" and last.get("id") == str(fid))
        st, _, _ = call("onos", "DELETE", V1 + "/flows/%s/%s" % (S2, fid))
        print("      removed: DELETE /onos/v1/flows -> %s" % st)

        print("== (b2) admin flow via /onos/v1/flows?appId=lu.uni.sdn.gw.t1 (V3 spoof) -> flagged")
        st, js, _ = call("onos", "POST", V1 + "/flows?appId=lu.uni.sdn.gw.t1", {"flows": [flow(1, 20200)]})
        fid2 = js["flows"][0]["flowId"] if st == 200 else None
        wait_until(lambda: audit()["bypassTotal"] >= 2, 10)
        last = audit()["recent"][-1]
        print("      recent[-1]=%s" % last)
        expect("flagged: tenant appId, not created through the gateway",
               last.get("appId") == "lu.uni.sdn.gw.t1" and last.get("id") == str(fid2)
               and "not created through the gateway" in last.get("why", ""))
        call("onos", "DELETE", V1 + "/flows/%s/%s" % (S2, fid2))

        print("== (c) admin intent via /onos/v1/intents under lu.uni.sdn.gw.t1 (V7 spoof) -> flagged")
        body = h2h("audit-spoof", H1A, H1B)
        body["appId"] = "lu.uni.sdn.gw.t1"
        st, _, _ = call("onos", "POST", V1 + "/intents", body)
        print("      POST /onos/v1/intents -> %s" % st)
        wait_until(lambda: audit()["bypassTotal"] >= 3, 10)
        wait_until(lambda: state("audit-spoof") == "INSTALLED", 20)
        time.sleep(4)
        a = audit()
        last = a["recent"][-1]
        print("      bypassTotal=%s recent[-1]=%s" % (a["bypassTotal"], last))
        expect("the intent is flagged, its compiled flows are not double-counted",
               a["bypassTotal"] == 3 and last.get("kind") == "intent" and last.get("appId") == "lu.uni.sdn.gw.t1"
               and last.get("id") == "audit-spoof" and "not submitted through the gateway" in last.get("why", ""))
        st, _, _ = call("onos", "DELETE", V1 + "/intents/lu.uni.sdn.gw.t1/audit-spoof")
        print("      removed: DELETE /onos/v1/intents/lu.uni.sdn.gw.t1/audit-spoof -> %s" % st)

        print("== (d) auditEnabled=false -> nothing recorded")
        set_cfg({"auditEnabled": False})
        st, js, _ = call("onos", "POST", V1 + "/flows", {"flows": [flow(1, 20300)]})
        fid3 = js["flows"][0]["flowId"] if st == 200 else None
        time.sleep(3)
        expect("bypassTotal unchanged (3)", audit()["bypassTotal"] == 3, str(audit()["bypassTotal"]))
        call("onos", "DELETE", V1 + "/flows/%s/%s" % (S2, fid3))
        set_cfg({"auditEnabled": True})

        print("== (e) rejection counters per tenant / module / reason")
        before = audit()["rejections"].get("t1", {}).get("validation", {}).get("bad_priority", 0)
        bad = flow(1, 99)
        check("t1 invalid flow", "t1-op", "POST", "/flows", 400, "bad_priority", body={"flows": [bad]})
        check("karaf (no gateway role)", "karaf", "GET", "/flows", 403, "unknown_account")
        r = audit()["rejections"]
        print("      rejections[t1]=%s" % r.get("t1"))
        expect("t1.validation.bad_priority +1", r.get("t1", {}).get("validation", {}).get("bad_priority", 0)
               == before + 1)
        expect("non-tenant callers counted under _other",
               r.get("_other", {}).get("acl", {}).get("unknown_account", 0) >= 1)

        wait_until(lambda: state("audit-spoof") is None and not gw_flow_ids("onos"), 20)
        check("reset audit counters (leave a clean state)", "onos", "POST", "/admin/audit/reset", 204)
    _, js, _ = call("onos", "GET", V1 + "/flows")
    stray = [f["id"] for f in js["flows"] if not f["appId"].startswith("org.onosproject.core")]
    _, ijs, _ = call("onos", "GET", V1 + "/intents")
    expect("nothing left: non-core flows %s, intents %s" % (stray, [i["key"] for i in ijs["intents"]]),
           not stray and not ijs["intents"])
    return summary("B7")


if __name__ == "__main__":
    sys.exit(main())
