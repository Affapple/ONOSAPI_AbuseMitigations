#!/usr/bin/env python3
"""B3 live test: per-tenant quotas. Runs ON THE VM, under the ONOS lock:
    flock /tmp/sdn-onos.lock python3 ~/gw/b3-quota.py

Uses small temporary quotas, turns the B2 limiter off for the run (to isolate B3),
restores every changed property and removes every flow/intent it created.
Needs the topology up (H2H intents must compile).
"""
import sys
import threading
import time

from gwlib import BASE, GW, S1, S2, S3, call, check, expect, flow, gw_flow_ids, saved_config, set_cfg, \
    summary, wait_until

PROPS = ["quotaEnabled", "maxFlowsPerTenant", "maxFlowsPerTenantPerDevice", "maxIntentsPerTenant",
         "churnLimit", "churnWindowSeconds", "rateLimitEnabled", "adaptiveEnabled"]
H1A_MAC = "00:00:00:00:01:01"
H1A, H1B, H2A, H2B = ("00:00:00:00:01:01/None", "00:00:00:00:01:02/None",
                      "00:00:00:00:02:01/None", "00:00:00:00:02:02/None")


def status(t):
    _, js, _ = call("onos", "GET", GW + "/admin/tenants/" + t)
    return js["usage"]


def h2h(key, one, two):
    return {"type": "HostToHostIntent", "key": key, "priority": 100, "one": one, "two": two}


def intent_state(key):
    _, js, _ = call("onos", "GET", BASE + "/onos/v1/intents")
    for i in js.get("intents", []):
        if i["key"] == key:
            return i.get("state")
    return None


def intent_app_flows():
    _, js, _ = call("onos", "GET", BASE + "/onos/v1/flows")
    return [f for f in js["flows"] if f["appId"] == "org.onosproject.net.intent"]


def post_flows(user, rules, want, reason=None, label=""):
    st, js, _ = check(label or "POST %d flow(s)" % len(rules), user, "POST", "/flows", want, reason,
                      body={"flows": rules})
    return [f["flowId"] for f in js["flows"]] if st == 200 else []


def admin_delete(tenant, dev_ids):
    if dev_ids:
        wait_until(lambda: all(i in gw_flow_ids("onos", tenant) for _, i in dev_ids))
        check("cleanup %d %s flow(s) as admin" % (len(dev_ids), tenant), "onos", "DELETE", "/flows", 204,
              body={"flows": [{"deviceId": d, "flowId": i} for d, i in dev_ids]})


def main():
    with saved_config(PROPS) as saved:
        print("saved config: %s" % saved)
        set_cfg({"rateLimitEnabled": False, "adaptiveEnabled": False, "quotaEnabled": True, "churnLimit": 200, "churnWindowSeconds": 60,
                 "maxFlowsPerTenant": 100, "maxFlowsPerTenantPerDevice": 40, "maxIntentsPerTenant": 20})

        print("== 0. baseline")
        # a previous suite's deletes/purges may still be settling: wait for zero usage
        wait_until(lambda: status("t1")["flows"] == 0 and status("t1")["intents"] == 0
                   and status("t2")["flows"] == 0 and status("t2")["intents"] == 0, 20)
        u1, u2 = status("t1"), status("t2")
        print("      t1 %s\n      t2 %s" % (u1, u2))
        expect("t1 and t2 start empty", u1["flows"] == 0 and u1["intents"] == 0 and u2["flows"] == 0
               and u2["intents"] == 0)

        print("== 0b. intents are counted with their estimate BEFORE they compile (audit findings 1 and 2)")
        set_cfg({"maxFlowsPerTenant": 10})
        codes = [call("t1-op", "POST", GW + "/intents", h2h("bb%d" % i, H1A, H1B))[0:2] for i in range(5)]
        print("      5 back-to-back H2H (est. 6 flows each), maxFlowsPerTenant=10: %s"
              % [(c, (j or {}).get("reason") if isinstance(j, dict) else None) for c, j in codes])
        expect("only the first is admitted, the rest 409 quota_flows",
               [c for c, _ in codes] == [201, 409, 409, 409, 409]
               and all(j.get("reason") == "quota_flows" for c, j in codes[1:]))
        u = status("t1")
        print("      t1 right after: %s" % u)
        expect("counted before compiling (flows <= 10)", u["flows"] <= 10 and u["intents"] == 1, str(u))
        wait_until(lambda: intent_state("bb0") == "INSTALLED" and status("t1")["estimatedIntentFlows"] == 0, 20)
        u = status("t1")
        expect("after compiling: estimate replaced by the 6 real flows", u["intentFlows"] == 6
               and u["estimatedIntentFlows"] == 0, str(u))
        set_cfg({"maxFlowsPerTenant": 100, "maxFlowsPerTenantPerDevice": 4})
        codes = [call("t1-op", "POST", GW + "/intents", h2h("pd%d" % i, H1A, H1B))[0:2] for i in range(2)]
        print("      perDevice=4, t1 already has 2 per switch; 2 more H2H back-to-back: %s"
              % [(c, (j or {}).get("reason") if isinstance(j, dict) else None) for c, j in codes])
        expect("first fits (4 per switch), second 409 quota_flows_device",
               [c for c, _ in codes] == [201, 409] and codes[1][1].get("reason") == "quota_flows_device")
        for k in ("bb0", "pd0"):
            check("cleanup " + k, "onos", "DELETE", "/intents/%s?tenant=t1" % k, 204)
        wait_until(lambda: not intent_app_flows() and status("t1")["flows"] == 0, 20)
        expect("t1 back to 0 flows", status("t1")["flows"] == 0, str(status("t1")))
        set_cfg({"maxFlowsPerTenant": 100, "maxFlowsPerTenantPerDevice": 40})

        print("== 1. an H2H intent's compiled flows count against the submitting tenant only")
        check("t1-op POST H2H h1a<->h1b", "t1-op", "POST", "/intents", 201, body=h2h("q-h2h", H1A, H1B))
        wait_until(lambda: intent_state("q-h2h") == "INSTALLED" and status("t1")["intentFlows"] > 0, 20)
        u1, u2 = status("t1"), status("t2")
        compiled = intent_app_flows()
        print("      state=%s\n      t1 %s\n      t2 %s" % (intent_state("q-h2h"), u1, u2))
        print("      /onos/v1/flows with appId org.onosproject.net.intent: %d (devices %s)"
              % (len(compiled), sorted(set(f["deviceId"][-1] for f in compiled))))
        expect("t1 intentFlows == compiled flows in ONOS (appId org.onosproject.net.intent)",
               u1["intentFlows"] == len(compiled) > 0, "%d vs %d" % (u1["intentFlows"], len(compiled)))
        expect("t1 directFlows 0, intents 1", u1["directFlows"] == 0 and u1["intents"] == 1)
        expect("t2 charged nothing", u2["flows"] == 0 and u2["intentFlows"] == 0 and u2["intents"] == 0)

        print("== 2. intent quota (maxIntentsPerTenant=1)")
        set_cfg({"maxIntentsPerTenant": 1})
        check("t1 second intent", "t1-op", "POST", "/intents", 409, "quota_intents", body=h2h("q-2", H1B, H1A))
        check("t1 resubmits its own key (replacement, not +1)", "t1-op", "POST", "/intents", 201,
              body=h2h("q-h2h", H1A, H1B))
        check("t2 has its own quota", "t2-op", "POST", "/intents", 201, body=h2h("q-t2", H2A, H2B))
        wait_until(lambda: intent_state("q-t2") == "INSTALLED" and status("t2")["intentFlows"] > 0, 20)
        u1b, u2b = status("t1"), status("t2")
        print("      t1 %s\n      t2 %s" % (u1b, u2b))
        expect("t2's intent flows charged to t2, t1 unchanged",
               u2b["intentFlows"] > 0 and u1b["intentFlows"] == u1["intentFlows"],
               "t1 %d -> %d, t2 %d" % (u1["intentFlows"], u1b["intentFlows"], u2b["intentFlows"]))
        check("cleanup t2 intent (admin)", "onos", "DELETE", "/intents/q-t2?tenant=t2", 204)
        wait_until(lambda: status("t2")["flows"] == 0, 15)

        print("== 3. flow quotas count direct + intent flows, including writes not yet in the store")
        u1 = status("t1")
        c, d2, d3 = u1["flows"], u1["perDevice"].get(S2, 0), u1["perDevice"].get(S3, 0)
        expect("s3 has room for 1 under the s2-derived per-device limit", d3 + 1 <= d2 + 2, "s2=%d s3=%d" % (d2, d3))
        set_cfg({"maxFlowsPerTenant": c + 3, "maxFlowsPerTenantPerDevice": d2 + 2})
        print("      t1 holds %d (s2: %d) -> maxFlowsPerTenant=%d, maxFlowsPerTenantPerDevice=%d"
              % (c, d2, c + 3, d2 + 2))
        made = []
        made += [(S2, i) for i in post_flows("t1-op", [flow(S2, 1, 3, priority=22000 + i) for i in range(2)],
                                             200, label="2 flows on s2 (s2 now full)")]
        post_flows("t1-op", [flow(S2, 1, 3, priority=22010)], 409, "quota_flows_device",
                   label="1 more on s2, sent immediately (counts in-flight)")
        made += [(S3, i) for i in post_flows("t1-op", [flow(S3, 1, 3, priority=22020)], 200,
                                             label="1 on s3 (total now full)")]
        post_flows("t1-op", [flow(S1, 1, 2, priority=22030, eth_src=H1A_MAC)], 409, "quota_flows", label="1 on s1")
        post_flows("t1-op", [flow(S1, 1, 2, priority=22040, eth_src=H1A_MAC), flow(S1, 2, 1, priority=22041, eth_src=H1A_MAC)], 409, "quota_flows",
                   label="batch of 2 on s1, rejected whole")
        time.sleep(2)
        u1 = status("t1")
        expect("t1 holds exactly 3 direct flows (nothing partially installed)", u1["directFlows"] == 3,
               str(u1))
        t2made = [(S2, i) for i in post_flows("t2-op", [flow(S2, 2, 3, priority=22050)], 200,
                                              label="t2 unaffected by t1 being full")]
        admin_delete("t2", t2made)

        print("== 4. race: two concurrent requests from one tenant, room for one")
        check("t1-op frees its 3 direct flows", "t1-op", "DELETE", "/flows", 204,
              body={"flows": [{"deviceId": d, "flowId": i} for d, i in made]})
        set_cfg({"maxFlowsPerTenantPerDevice": 40})      # only the total quota decides the race
        made = []
        results = {}

        def worker(name, prio):
            st, js, _ = call("t1-op", "POST", GW + "/flows",
                             {"flows": [flow(S3, 1, 3, priority=prio), flow(S3, 1, 3, priority=prio + 1)]})
            results[name] = (st, js)
        ths = [threading.Thread(target=worker, args=("a", 23000)), threading.Thread(target=worker, args=("b", 23010))]
        for t in ths:
            t.start()
        for t in ths:
            t.join()
        codes = sorted(st for st, _ in results.values())
        print("      concurrent codes: %s" % codes)
        expect("exactly one 200 and one 409 quota_flows (no overshoot)", codes == [200, 409]
               and any(js.get("reason") == "quota_flows" for st, js in results.values() if st == 409))
        for st, js in results.values():
            if st == 200:
                made += [(S3, f["flowId"]) for f in js["flows"]]
        time.sleep(2)
        expect("t1 holds 2 direct flows after the race", status("t1")["directFlows"] == 2)
        admin_delete("t1", made)

        print("== 5. churn (add+delete per window)")
        set_cfg({"maxFlowsPerTenant": 100, "maxFlowsPerTenantPerDevice": 40, "churnWindowSeconds": 10,
                 "churnLimit": 4})
        time.sleep(11)                                   # previous operations leave the window
        ids = post_flows("t1-op", [flow(S3, 1, 3, priority=24000 + i) for i in range(2)], 200,
                         label="2 adds (churn 2)")
        wait_until(lambda: all(i in gw_flow_ids("t1-op") for i in ids))
        check("2 deletes (churn 4)", "t1-op", "DELETE", "/flows", 204,
              body={"flows": [{"deviceId": S3, "flowId": i} for i in ids]})
        st, js, h = check("1 more add -> 429 quota_churn", "t1-op", "POST", "/flows", 429, "quota_churn",
                          body={"flows": [flow(S3, 1, 3, priority=24010)]})
        ra = int(h.get("Retry-After", "0")) if h is not None else 0
        expect("Retry-After set (1..10 s)", 1 <= ra <= 10, "Retry-After=%s" % ra)
        print("      t1 churn now: %s" % status("t1")["churn"])
        time.sleep(ra + 0.5)
        ids = post_flows("t1-op", [flow(S3, 1, 3, priority=24010)], 200, label="after Retry-After")
        admin_delete("t1", [(S3, i) for i in ids])

        print("== 6. quotaEnabled=false turns the module off")
        set_cfg({"quotaEnabled": False, "maxFlowsPerTenant": 1, "churnLimit": 200})
        ids = post_flows("t1-op", [flow(S3, 1, 3, priority=25000 + i) for i in range(3)], 200,
                         label="3 flows over maxFlowsPerTenant=1")
        admin_delete("t1", [(S3, i) for i in ids])

        print("== 7. cleanup")
        check("delete t1 intent (admin)", "onos", "DELETE", "/intents/q-h2h?tenant=t1", 204)
        wait_until(lambda: not intent_app_flows() and status("t1")["flows"] == 0, 20)
    _, js, _ = call("onos", "GET", BASE + "/onos/v1/intents")
    left_int = [i["key"] for i in js["intents"] if i["appId"].startswith("lu.uni.sdn.gw")]
    left_fl = gw_flow_ids("onos")
    expect("nothing left: gateway intents %s, tenant flows %s, intent-compiled flows %d"
           % (left_int, left_fl, len(intent_app_flows())), not left_int and not left_fl and not intent_app_flows())
    return summary("B3")


if __name__ == "__main__":
    sys.exit(main())
