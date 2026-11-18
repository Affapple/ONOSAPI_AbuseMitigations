#!/usr/bin/env python3
"""B6 live test: adaptive throttling. Runs ON THE VM, under the ONOS lock:
    flock /tmp/sdn-onos.lock python3 ~/gw/b6-throttle.py

Small temporary thresholds: half-life 10 s, levels 5 / 10 / 20, cooldown 4 s (cap 60),
fixed penalty 5 s. Violations: invalid flows (bad_priority, +3) and spoofed flows
(spoofed_source, +5). t2 keeps reading and writing normally the whole time.
B2 is off (to isolate B6); everything changed is restored and created flows removed.
"""
import sys
import time

from gwlib import GW, S2, call, check, expect, gw_flow_ids, saved_config, set_cfg, summary, wait_until

PROPS = ["adaptiveEnabled", "throttleMode", "scoreHalfLife", "level1Threshold", "level2Threshold",
         "level3Threshold", "cooldown", "cooldownMax", "fixedPenalty", "fixedEffect", "fixedRateFactor",
         "rateLimitEnabled", "rateLimitRps", "rateLimitBurst"]
H2A = "00:00:00:00:02:01"
created = []
prio = [20000]


def flow(in_port, extra=None, p=None):
    crit = [{"type": "IN_PORT", "port": str(in_port)}] + (extra or [])
    if p is None:
        prio[0] += 1
        p = prio[0]
    return {"flows": [{"priority": p, "isPermanent": False, "timeout": 120, "deviceId": S2,
                       "selector": {"criteria": crit}, "treatment": {"instructions": [{"type": "OUTPUT", "port": "3"}]}}]}


def thr(t):
    _, js, _ = call("onos", "GET", GW + "/admin/tenants/" + t)
    return js["throttle"]


def bad(n=1):          # validation reject, +3
    for _ in range(n):
        st, js, _ = call("t1-op", "POST", GW + "/flows", flow(1, p=99))
        expect("t1 invalid flow -> 400 bad_priority", st == 400 and js.get("reason") == "bad_priority",
               "-> %s %s" % (st, (js or {}).get("reason")))


def spoof(n=1):        # conflict reject, +5
    for _ in range(n):
        st, js, _ = call("t1-op", "POST", GW + "/flows", flow(1, [{"type": "ETH_SRC", "mac": H2A}]))
        expect("t1 spoofed flow -> 409 spoofed_source", st == 409 and js.get("reason") == "spoofed_source",
               "-> %s %s" % (st, (js or {}).get("reason")))


def acl_read(n=1):     # ACL reject on a read, +3 (reads are not blocked by the cooldown)
    for _ in range(n):
        st, js, _ = call("t1-op", "GET", GW + "/v1/flows")
        expect("t1 GET /gw/v1/flows -> 403 acl_denied", st == 403 and js.get("reason") == "acl_denied",
               "-> %s %s" % (st, (js or {}).get("reason")))


def good_write(user, in_port, want, reason=None):
    st, js, h = call(user, "POST", GW + "/flows", flow(in_port))
    ok = st == want and (reason is None or (js or {}).get("reason") == reason)
    expect("%s valid write -> %s %s" % (user, want, reason or ""), ok,
           "-> %s %s Retry-After=%s" % (st, (js or {}).get("reason"), h.get("Retry-After") if h else None))
    if st == 200:
        created.append((S2, js["flows"][0]["flowId"]))
    return st, js, h


def t2_ok(where):
    st1, _, _ = call("t2-op", "GET", GW + "/flows")
    st2, js, _ = call("t2-op", "POST", GW + "/flows", flow(2))
    if st2 == 200:
        created.append((S2, js["flows"][0]["flowId"]))
    expect("t2 unaffected %s (GET %s, POST %s, level %s)" % (where, st1, st2, thr("t2")["level"]),
           st1 == 200 and st2 == 200 and thr("t2")["level"] == 0)


def show(label):
    t = thr("t1")
    print("      %-38s score=%-6s level=%s rateFactor=%s cooldownRemaining=%s entries=%s fixedRemaining=%s"
          % (label, t["score"], t["level"], t["rateFactor"], t["cooldownRemaining"], t["cooldownEntries"],
             t["fixedRemaining"]))
    return t


def main():
    with saved_config(PROPS):
        set_cfg({"adaptiveEnabled": True, "throttleMode": "adaptive", "scoreHalfLife": 10, "level1Threshold": 5,
                 "level2Threshold": 10, "level3Threshold": 20, "cooldown": 4, "cooldownMax": 60, "fixedPenalty": 5,
                 "rateLimitEnabled": False})
        for t in ("t1", "t2"):
            check("start clean: unblock " + t, "onos", "POST", "/admin/tenants/%s/unblock" % t, 200)

        print("== 1. level 0 -> 1 (Restricted)")
        bad(2)
        t = show("after 2 validation rejects")
        expect("level 1, refill rate x0.5", t["level"] == 1 and t["rateFactor"] == 0.5)
        good_write("t1-op", 1, 200)
        t2_ok("at t1 level 1")

        print("== 2. level 1 -> 2 (Cooldown): writes 429, reads pass")
        bad(2)
        t = show("after 2 more")
        expect("level 2 with a ~4 s cooldown", t["level"] == 2 and 1 <= t["cooldownRemaining"] <= 4)
        st, js, h = good_write("t1-op", 1, 429, "throttled_cooldown")
        check("t1 read during cooldown", "t1-op", "GET", "/flows", 200)
        t2_ok("at t1 level 2")
        before = thr("t1")["score"]
        for _ in range(5):
            call("t1-op", "POST", GW + "/flows", flow(1))     # all throttled_cooldown
        expect("B6's own 429s add no score", thr("t1")["score"] <= before, "%s -> %s" % (before, thr("t1")["score"]))
        time.sleep(int(h.get("Retry-After", "4")) + 0.3)
        t = show("after the cooldown")
        good_write("t1-op", 1, 200)
        expect("cooldown over but still level 2 (hysteresis: needs < 5)", thr("t1")["level"] == 2)

        print("== 3. level 2 -> 3 (Blocked): everything 403")
        spoof(3)
        t = show("after 3 spoofing rejects")
        expect("level 3", t["level"] == 3)
        st, js, h = check("t1 read while blocked", "t1-op", "GET", "/flows", 403, "throttled_blocked")
        print("      Retry-After=%s" % (h.get("Retry-After") if h else None))
        check("t1 whoami while blocked", "t1-op", "GET", "/whoami", 403, "throttled_blocked")
        before = thr("t1")["score"]
        for _ in range(5):
            call("t1-op", "GET", GW + "/flows")
        expect("B6's own 403s add no score (it can decay out)", thr("t1")["score"] <= before,
               "%s -> %s" % (before, thr("t1")["score"]))
        t2_ok("while t1 is blocked")

        print("== 4. admin unblock")
        check("POST /gw/admin/tenants/t1/unblock", "onos", "POST", "/admin/tenants/t1/unblock", 200)
        t = show("after unblock")
        expect("level 0, score 0", t["level"] == 0 and t["score"] == 0)
        check("t1 read after unblock", "t1-op", "GET", "/flows", 200)
        check("tenant cannot unblock itself", "t1-op", "POST", "/admin/tenants/t1/unblock", 403, "acl_denied")

        print("== 4b. format errors on flow/intent writes score as validation; 404s stay free")
        check("unblock t1", "onos", "POST", "/admin/tenants/t1/unblock", 200)
        check("t1 POST /gw/flows with invalid JSON", "t1-op", "POST", "/flows", 400, "bad_request", body="{not json")
        s1 = thr("t1")["score"]
        expect("bad_request on a flow write adds the validation weight (3)", 2.9 <= s1 <= 3.0, "score %s" % s1)
        check("t1 DELETE a flow that does not exist", "t1-op", "DELETE", "/flows/%s/123456" % S2, 404, "not_found")
        check("t1 POST /gw/intents with invalid JSON", "t1-op", "POST", "/intents", 400, "bad_request", body="[1,")
        s2 = thr("t1")["score"]
        expect("not_found adds nothing; intent bad_request adds 3", 5.8 <= s2 <= 6.0, "score %s" % s2)
        check("unblock t1", "onos", "POST", "/admin/tenants/t1/unblock", 200)

        print("== 5. decay back to level 0 through every level (hysteresis)")
        spoof(2)                                               # -> level 2 (cooldown blocks further writes)
        while thr("t1")["level"] < 3:
            acl_read()                                         # reads still pass the cooldown
        show("blocked again")
        seen = [thr("t1")["level"]]
        t0 = time.time()
        while time.time() - t0 < 45 and seen[-1] != 0:
            time.sleep(1)
            lv = thr("t1")
            if lv["level"] != seen[-1]:
                seen.append(lv["level"])
                print("      t+%4.1fs level -> %s (score %s)" % (time.time() - t0, lv["level"], lv["score"]))
        expect("decayed 3 -> 2 -> 1 -> 0", seen == [3, 2, 1, 0], str(seen))
        check("t1 read at level 0", "t1-op", "GET", "/flows", 200)

        print("== 6. cooldown doubles on re-entry")
        while thr("t1")["level"] < 2:                         # residual score varies: stop at level 2
            bad()
        t = show("first entry into level 2")
        first = t["cooldownRemaining"]
        wait_until(lambda: thr("t1")["level"] == 1, 30)
        show("decayed to level 1")
        while thr("t1")["level"] < 2:
            bad()
        t = show("second entry into level 2")
        expect("cooldown doubled (%s -> %s s), entries 2" % (first, t["cooldownRemaining"]),
               t["cooldownEntries"] == 2 and 5 <= t["cooldownRemaining"] <= 8)
        check("unblock t1", "onos", "POST", "/admin/tenants/t1/unblock", 200)

        print("== 7. fixed mode, effect block-writes: any violation -> writes blocked 5 s, no escalation")
        set_cfg({"throttleMode": "fixed", "fixedEffect": "block-writes"})
        bad(1)
        st, js, h = good_write("t1-op", 1, 429, "throttled_fixed")
        check("t1 read during the fixed block", "t1-op", "GET", "/flows", 200)
        for _ in range(10):                                    # 10 more violations through the read path (+3 each)
            call("t1-op", "GET", GW + "/v1/flows")             # 403 acl_denied
        t = show("after 10 more violations")
        check("still no block, reads pass (no escalation)", "t1-op", "GET", "/flows", 200)
        expect("no levels in fixed mode", t["level"] == 0)
        t2_ok("in fixed mode")
        time.sleep(t["fixedRemaining"] + 0.5)
        good_write("t1-op", 1, 200)

        print("== 7b. fixed mode, effect rate (default, Lab2-like): refill rate x0.1 on all requests for 6 s")
        check("unblock t1", "onos", "POST", "/admin/tenants/t1/unblock", 200)
        set_cfg({"throttleMode": "fixed", "fixedEffect": "rate", "fixedRateFactor": 0.1, "fixedPenalty": 6,
                 "rateLimitEnabled": True, "rateLimitRps": 10, "rateLimitBurst": 5})
        time.sleep(1.0)                                         # bucket full (5)

        def paced(seconds, per_second=8):
            codes = []
            end = time.time() + seconds
            while time.time() < end:
                codes.append(call("t1-op", "GET", GW + "/flows")[0])
                time.sleep(1.0 / per_second)
            return codes
        control = paced(1.5)
        expect("control: 8 req/s under a 10/s bucket -> all 200", control and all(c == 200 for c in control),
               "%d requests, %d x 200" % (len(control), control.count(200)))
        bad(1)
        t = show("after one violation")
        expect("rateFactor 0.1 during the penalty, no level", t["rateFactor"] == 0.1 and t["level"] == 0)
        slow = paced(2.0)
        print("      penalized: %d requests at 8/s -> %d x 200, %d x 429" % (len(slow), slow.count(200), slow.count(429)))
        expect("penalized: the 1/s refill only covers the bucket + ~2 more (<= 9 x 200), the rest 429",
               slow.count(200) <= 9 and slow.count(429) >= len(slow) - 9 and all(c in (200, 429) for c in slow))
        wait_until(lambda: thr("t1")["fixedRemaining"] == 0, 20)
        time.sleep(1.0)                                         # refill at the full rate again
        after = paced(1.5)
        expect("after the penalty: all 200 again", after and all(c == 200 for c in after),
               "%d requests, %d x 200" % (len(after), after.count(200)))

        print("== 8. cleanup")
        for t in ("t1", "t2"):
            check("unblock " + t, "onos", "POST", "/admin/tenants/%s/unblock" % t, 200)
        if created:
            wait_until(lambda: all(i in gw_flow_ids("onos") for _, i in created), 10)
            check("delete %d flows" % len(created), "onos", "DELETE", "/flows", 204,
                  body={"flows": [{"deviceId": d, "flowId": i} for d, i in created]})
    wait_until(lambda: not gw_flow_ids("onos"), 10)
    expect("nothing left: tenant flows %s" % gw_flow_ids("onos"), not gw_flow_ids("onos"))
    return summary("B6")


if __name__ == "__main__":
    sys.exit(main())
