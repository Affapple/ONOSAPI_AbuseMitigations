#!/usr/bin/env python3
"""B2 live test: per-tenant token bucket. Runs ON THE VM, under the ONOS lock:
    flock /tmp/sdn-onos.lock python3 ~/gw/b2-ratelimit.py

Functional check only, with small numbers (rps=1, burst=5, maxBatchSize=3,
maxBodyBytes=4096); every changed property is restored at the end.
"""
import json
import sys
import threading
import time

from gwlib import FAILS, GW, S2, call, check, expect, flow, gw_flow_ids, saved_config, set_cfg, \
    summary, wait_until

PROPS = ["rateLimitEnabled", "rateLimitRps", "rateLimitBurst", "maxBatchSize", "maxBodyBytes", "adaptiveEnabled"]
RPS, BURST = 1, 5


def refill():
    time.sleep(BURST / RPS + 1)


def main():
    with saved_config(PROPS) as saved:
        print("saved config: %s" % saved)
        set_cfg({"rateLimitEnabled": True, "rateLimitRps": RPS, "rateLimitBurst": BURST,
                 "maxBatchSize": 3, "maxBodyBytes": 4096, "adaptiveEnabled": False})
        refill()

        print("== 1. t1-op burst while t2-op keeps a normal pace")
        t2 = []

        def t2_loop():
            for _ in range(6):
                st, _, _ = call("t2-op", "GET", GW + "/flows")
                t2.append(st)
                time.sleep(1.5)            # 0.67 req/s < 1 token/s
        th = threading.Thread(target=t2_loop)
        th.start()
        t1 = []
        for _ in range(12):
            st, js, h = call("t1-op", "GET", GW + "/flows")
            t1.append((st, js.get("reason") if isinstance(js, dict) else None, h.get("Retry-After")))
        th.join()
        codes = [c for c, _, _ in t1]
        print("      t1-op codes: %s" % codes)
        print("      t2-op codes: %s" % t2)
        n200 = codes.index(429) if 429 in codes else len(codes)
        expect("t1-op: first %d (burst) are 200, then 429" % BURST,
               n200 in (BURST, BURST + 1) and all(c == 429 for c in codes[n200:]), "(200s before first 429: %d)"
               % n200)
        expect("every 429 has reason rate_limited and Retry-After >= 1",
               all(r == "rate_limited" and ra is not None and int(ra) >= 1 for c, r, ra in t1 if c == 429),
               str([(r, ra) for c, r, ra in t1 if c == 429][:3]))
        expect("t2-op unaffected (all 200)", t2 and all(c == 200 for c in t2))

        print("== 2. denied / 404 / bad-path requests also cost tokens (limiter runs before the ACL)")
        # Slow refill (0.5/s) so the bucket cannot regain a token between the calls below.
        set_cfg({"rateLimitRps": 0.5})
        time.sleep(BURST / 0.5 + 1)                     # full: 5 tokens
        spent = [call("t1-op", m, GW + p)[0] for m, p in
                 (("GET", "/nosuchpath"), ("GET", "/application.wadl"), ("OPTIONS", "/flows"),
                  ("GET", "/v1/flows"), ("GET", "/v1//x"))]
        expect("5 rejected requests answered by ACL/path checks", spent == [403, 403, 403, 403, 400], str(spent))
        st, js, h = check("next VALID request -> 429: the 5 rejects used all 5 tokens", "t1-op", "GET",
                          "/flows", 429, "rate_limited")
        check("unknown path while empty -> 429, not 403", "t1-op", "GET", "/nosuchpath", 429, "rate_limited")
        check("bad path while empty -> 429, not 400", "t1-op", "GET", "/v1//x", 429, "rate_limited")
        ra = int(h.get("Retry-After", "2")) if h is not None else 2
        expect("Retry-After at 0.5 tokens/s is 2 s", ra == 2, "Retry-After=%s" % ra)
        time.sleep(ra + 0.2)
        check("after Retry-After seconds -> 200", "t1-op", "GET", "/flows", 200)
        set_cfg({"rateLimitRps": RPS})

        print("== 3. hard caps before parsing (each costs 1 token)")
        refill()
        check("4 flows > maxBatchSize=3", "t1-op", "POST", "/flows", 413, "batch_too_large",
              body={"flows": [flow(S2, 1, 3)] * 4})
        check("body > maxBodyBytes=4096", "t1-op", "POST", "/flows", 413, "body_too_large",
              body={"flows": [flow(S2, 1, 3)], "pad": "x" * 5000})
        check("intent body > maxBodyBytes", "t1-op", "POST", "/intents", 413, "body_too_large",
              body={"type": "HostToHostIntent", "pad": "x" * 5000})

        print("== 3b. duplicate \"flows\" key (auditor's bypass) -> 400, nothing installed")
        one = json.dumps(flow(S2, 1, 3, priority=21000))
        six = ",".join(json.dumps(flow(S2, 1, 3, priority=21001 + i)) for i in range(6))
        check("POST {flows:[1], flows:[6]}", "t1-op", "POST", "/flows", 400, "bad_request",
              body='{"flows":[%s],"flows":[%s]}' % (one, six))
        check("DELETE {flows:[1], flows:[2]}", "t1-op", "DELETE", "/flows", 400, "bad_request",
              body='{"flows":[{"deviceId":"%s","flowId":"1"}],"flows":[{"deviceId":"%s","flowId":"2"},'
                   '{"deviceId":"%s","flowId":"3"}]}' % (S2, S2, S2))
        time.sleep(2)
        expect("no t1 flows were installed by the duplicate-key POST", not gw_flow_ids("onos", "t1"),
               str(gw_flow_ids("onos", "t1")))

        print("== 4. weighted cost: a write costs 1 token per flow")
        refill()
        st, js, _ = check("3-flow batch from a full bucket (5 -> 2)", "t1-op", "POST", "/flows", 200,
                          body={"flows": [flow(S2, 1, 3, priority=20000 + i) for i in range(3)]})
        created = [f["flowId"] for f in js["flows"]] if st == 200 else []
        check("second 3-flow batch (needs 3, has ~2) -> 429", "t1-op", "POST", "/flows", 429, "rate_limited",
              body={"flows": [flow(S2, 1, 3, priority=20010 + i) for i in range(3)]})
        check("but a 1-token GET still passes", "t1-op", "GET", "/flows", 200)
        if created:
            wait_until(lambda: all(f in gw_flow_ids("onos", "t1") for f in created))
            check("cleanup as admin (admins are exempt)", "onos", "DELETE", "/flows", 204,
                  body={"flows": [{"deviceId": S2, "flowId": f} for f in created]})

        print("== 5. admins exempt; rateLimitEnabled=false turns the module off")
        codes = [call("onos", "GET", GW + "/flows")[0] for _ in range(12)]
        expect("onos: 12 rapid GETs all 200", all(c == 200 for c in codes), str(codes))
        refill()
        set_cfg({"rateLimitEnabled": False})
        codes = [call("t1-op", "GET", GW + "/flows")[0] for _ in range(12)]
        expect("rateLimitEnabled=false: t1-op 12 rapid GETs all 200", all(c == 200 for c in codes), str(codes))
        check("rateLimitEnabled=false: no batch cap", "t1-op", "POST", "/flows", 403, "port_not_owned",
              body={"flows": [flow(S2, 1, 2)] * 4})
    wait_until(lambda: not gw_flow_ids("onos", "t1"))   # removal is asynchronous
    left = gw_flow_ids("onos", "t1")
    expect("no t1 flows left", not left, str(left))
    return summary("B2")


if __name__ == "__main__":
    sys.exit(main())
