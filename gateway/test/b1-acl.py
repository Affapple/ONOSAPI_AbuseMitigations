#!/usr/bin/env python3
"""B1 integration test: role x method x path table, default deny, ownership,
appId imposition (O3/O4 live), read filtering, read proxy, JSON errors, and
"no second way in". Runs ON THE VM against http://localhost:8181.

It installs and removes flows/intents and turns the B2 rate limiter off for the
duration (restored at the end), so hold the ONOS lock:
    flock /tmp/sdn-onos.lock python3 ~/gw/b1-acl.py

Exit code 0 = all checks passed. Every check prints one line.
Python 3.6 compatible (the VM's python3).
"""
import base64
import http.cookiejar
import json
import sys
import time
import urllib.parse
import urllib.error
import urllib.request

BASE = "http://localhost:8181"
GW = BASE + "/onos/gw"
PW = {"onos": "rocks", "karaf": "karaf"}
FAILS = []


def call(user, method, url, body=None, raw_path=False):
    """Returns (status, parsed JSON or text, headers)."""
    pw = PW.get(user, user + "-pw")
    data = None
    if body is not None:
        data = (body if isinstance(body, str) else json.dumps(body)).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Basic " + base64.b64encode(("%s:%s" % (user, pw)).encode()).decode())
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            status, text, hdrs = r.status, r.read().decode(errors="replace"), r.headers
    except urllib.error.HTTPError as e:
        status, text, hdrs = e.code, e.read().decode(errors="replace"), e.headers
    try:
        parsed = json.loads(text) if text else None
    except ValueError:
        parsed = text
    return status, parsed, hdrs


def check(label, user, method, path, want_status, want_reason=None, body=None, base=GW):
    status, js, _ = call(user, method, base + path, body)
    reason = js.get("reason") if isinstance(js, dict) else None
    ok = status == want_status and (want_reason is None or reason == want_reason)
    # every gateway error must be a JSON body with a reason code
    if ok and base == GW and status >= 400 and status != 401 and method != "HEAD":
        ok = isinstance(js, dict) and "reason" in js and "code" in js
    line = "%-5s %-58s %-8s %-6s %s %s -> %s %s" % (
        "ok" if ok else "FAIL", label, user, method, path, "", status, reason or "")
    print(line)
    if not ok:
        FAILS.append(line + "   (want %s %s; body=%s)" % (want_status, want_reason, str(js)[:300]))
    return status, js


def flow(dev, in_port, out_port, app_id=None, eth_src=None):
    f = {"priority": 20000, "isPermanent": False, "timeout": 120, "deviceId": dev,
         "treatment": {"instructions": [{"type": "OUTPUT", "port": str(out_port)}]},
         "selector": {"criteria": [{"type": "IN_PORT", "port": str(in_port)},
                                   {"type": "ETH_TYPE", "ethType": "0x0800"}]}}
    if app_id:
        f["appId"] = app_id
    if eth_src:   # B4: a trunk IN_PORT needs an owned source in the match
        f["selector"]["criteria"].append({"type": "ETH_SRC", "mac": eth_src})
    return {"flows": [f]}


S1, S2, S3 = "of:0000000000000001", "of:0000000000000002", "of:0000000000000003"


def wait_until(pred, secs=10.0):
    """ONOS applies flows and intents asynchronously; poll until pred() is true."""
    end = time.time() + secs
    while time.time() < end:
        if pred():
            return True
        time.sleep(0.2)
    return False


def gw_flow_ids(user):
    _, j, _ = call(user, "GET", GW + "/flows")
    return [f["id"] for f in j["flows"]] if isinstance(j, dict) else []


def gw_intent_keys(user):
    _, j, _ = call(user, "GET", GW + "/intents")
    return [i["key"] for i in j["intents"]] if isinstance(j, dict) else []


def gui_login(user):
    """FORM login to the ONOS GUI, then GET index.html with the session cookie."""
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    pw = PW.get(user, user + "-pw")
    try:
        op.open(BASE + "/onos/ui/login.html", timeout=10).read()
    except urllib.error.HTTPError:
        pass
    form = urllib.parse.urlencode({"j_username": user, "j_password": pw}).encode()
    try:
        op.open(urllib.request.Request(BASE + "/onos/ui/j_security_check", data=form), timeout=10).read()
    except urllib.error.HTTPError:
        pass
    try:
        with op.open(BASE + "/onos/ui/index.html", timeout=10) as r:
            return r.status, r.geturl()
    except urllib.error.HTTPError as e:
        return e.code, e.geturl()


def main():
    print("== 1. whoami / identity")
    check("t1-op whoami", "t1-op", "GET", "/whoami", 200)
    check("karaf (Karaf admin, not in admins) whoami", "karaf", "GET", "/whoami", 200)
    _, js, _ = call("karaf", "GET", GW + "/whoami")
    if not (isinstance(js, dict) and js.get("gatewayRole") == "none"):
        FAILS.append("karaf should be gatewayRole none: %s" % js)
    print("      karaf whoami body: %s" % js)

    print("== 2. default deny / unknown accounts / WADL / OPTIONS / HEAD")
    for u in ("karaf", "gw-svc"):
        check("unknown/service account: flows", u, "GET", "/flows", 403, "unknown_account")
        check("unknown/service account: v1/devices", u, "GET", "/v1/devices", 403, "unknown_account")
        check("unknown/service account: wadl", u, "GET", "/application.wadl", 403, "unknown_account")
        check("unknown/service account: OPTIONS whoami", u, "OPTIONS", "/whoami", 403, "unknown_account")
    for u in ("t1-view", "t1-op", "onos"):
        check("wadl denied", u, "GET", "/application.wadl", 403, "acl_denied")
        check("OPTIONS denied", u, "OPTIONS", "/flows", 403, "acl_denied")
        check("OPTIONS whoami denied", u, "OPTIONS", "/whoami", 403, "acl_denied")
        check("HEAD denied", u, "HEAD", "/flows", 403)
        check("unknown path denied", u, "GET", "/nosuchpath", 403, "acl_denied")
        check("context root denied", u, "GET", "/", 403, "acl_denied")
        check("PUT flows denied", u, "PUT", "/flows", 403, "acl_denied")
    print("== 3. path tricks -> 400 bad_path")
    check("encoded slash", "t1-op", "GET", "/v1/devices%2F..%2Fflows", 400, "bad_path")
    check("dot-dot (raw)", "t1-op", "GET", "/v1/devices/../flows", 400, "bad_path")
    check("matrix param", "t1-op", "GET", "/v1;x=1/flows", 400, "bad_path")
    check("double slash", "t1-op", "GET", "/v1//flows", 400, "bad_path")

    print("== 4. table: reads")
    for u in ("t1-view", "t1-op", "onos"):
        check("GET flows", u, "GET", "/flows", 200)
        check("GET intents", u, "GET", "/intents", 200)
        for p in ("devices", "links", "topology", "hosts"):
            check("GET v1/" + p, u, "GET", "/v1/" + p, 200)
        check("GET v1/devices/{id}/ports", u, "GET", "/v1/devices/%s/ports" % S2, 200)
    for u in ("t1-view", "t1-op"):
        for p in ("flows", "intents", "applications", "network/configuration", "configuration"):
            check("GET v1/%s (tenant)" % p, u, "GET", "/v1/" + p, 403, "acl_denied")
    check("GET v1/flows (admin)", "onos", "GET", "/v1/flows", 200)
    check("GET v1/applications (admin)", "onos", "GET", "/v1/applications", 200)
    check("tenant mismatch", "t1-op", "GET", "/flows?tenant=t2", 403, "tenant_mismatch")
    check("admin unknown tenant", "onos", "GET", "/flows?tenant=t9", 400, "tenant_required")

    print("== 4b. /gw/v1/hosts filtered to the caller's tenant (admin sees all)")
    T1 = {"00:00:00:00:01:01", "00:00:00:00:01:02"}
    T2 = {"00:00:00:00:02:01", "00:00:00:00:02:02"}
    _, allh, _ = call("onos", "GET", BASE + "/onos/v1/hosts")
    present = set(h["mac"] for h in allh["hosts"])
    print("      hosts known to ONOS: %s" % sorted(present))
    for u, own in (("t1-op", T1), ("t1-view", T1), ("t2-op", T2), ("t2-view", T2), ("onos", None)):
        st, j, _ = call(u, "GET", GW + "/v1/hosts")
        got = set(h["mac"] for h in j["hosts"]) if st == 200 else None
        want = present if own is None else (present & own)
        ok = st == 200 and got == want
        print("%-5s %-8s GET /gw/v1/hosts -> %s %s" % ("ok" if ok else "FAIL", u, st, sorted(got or [])))
        if not ok:
            FAILS.append("host filtering for %s: %s want %s" % (u, got, want))
    st1, own_host = check("own host by mac/vlan", "t1-op", "GET", "/v1/hosts/00:00:00:00:01:01/None", 200)
    st2, other = check("other tenant's host", "t1-op", "GET", "/v1/hosts/00:00:00:00:02:01/None", 404, "not_found")
    st3, missing = check("nonexistent host", "t1-op", "GET", "/v1/hosts/00:00:00:00:09:09/None", 404, "not_found")
    same = other == missing
    print("%-5s other-tenant 404 body == nonexistent 404 body: %s %s" % ("ok" if same else "FAIL", same, other))
    if not same:
        FAILS.append("host 404 bodies differ: %s vs %s" % (other, missing))
    check("other tenant's host, viewer", "t2-view", "GET", "/v1/hosts/00:00:00:00:01:01/None", 404, "not_found")
    check("host id with encoded slash", "t1-op", "GET", "/v1/hosts/00:00:00:00:02:01%2FNone", 400, "bad_path")
    st, j, _ = call("t1-op", "GET", GW + "/v1/hosts/00:00:00:00:02:01")
    leak = st == 200 and isinstance(j, dict) and j.get("mac") == "00:00:00:00:02:01"
    print("%-5s t1-op GET /gw/v1/hosts/00:00:00:00:02:01 (id form, no vlan) -> %s %s" % (
        "FAIL" if leak else "ok", st, (j or {}).get("reason") if isinstance(j, dict) else ""))
    if leak:
        FAILS.append("id-form host leak")
    check("admin sees other tenant's host", "onos", "GET", "/v1/hosts/00:00:00:00:02:01/None", 200)

    print("== 5. table: writes to /gw/v1 and /gw/admin")
    for u in ("t1-view", "t1-op"):
        check("POST v1/flows (tenant)", u, "POST", "/v1/flows", 403, "acl_denied", body={"flows": []})
        check("DELETE v1/flows/x/y (tenant)", u, "DELETE", "/v1/flows/%s/1" % S2, 403, "acl_denied")
        check("GET admin/x (tenant)", u, "GET", "/admin/tenants", 403, "acl_denied")
    # admin write is forwarded with the admin's own credentials: an invalid body
    # reaches ONOS and comes back as ONOS's own 400 (gw-svc would have been 403 -> 502).
    st, js = check("POST v1/flows (admin, invalid body -> ONOS 400)", "onos", "POST", "/v1/flows", 400,
                   "upstream_rejected", body={"notflows": 1})
    print("      upstream body: %s" % str(js)[:160])
    check("GET admin/<no such resource> (admin)", "onos", "GET", "/admin/nosuch", 404, "not_found")
    check("GET admin/tenants (admin, B3 quota status)", "onos", "GET", "/admin/tenants", 200)

    print("== 6. flows: ownership + appId imposition (O3 live)")
    check("viewer cannot POST flows", "t1-view", "POST", "/flows", 403, "acl_denied", body=flow(S2, 1, 3))
    check("output to t2 edge port", "t1-op", "POST", "/flows", 403, "port_not_owned", body=flow(S2, 1, 2))
    check("in_port = t2 edge port", "t1-op", "POST", "/flows", 403, "port_not_owned", body=flow(S2, 2, 3))
    check("unknown device", "t1-op", "POST", "/flows", 403, "device_not_owned",
          body=flow("of:0000000000000099", 1, 3))
    check("FLOOD output", "t1-op", "POST", "/flows", 403, "port_not_owned", body=flow(S2, 1, "FLOOD"))
    check("admin POST without ?tenant", "onos", "POST", "/flows", 400, "tenant_required", body=flow(S2, 1, 3))
    check("invalid JSON", "t1-op", "POST", "/flows", 400, "bad_request", body="{not json")
    check("missing priority", "t1-op", "POST", "/flows", 400, "bad_request",
          body={"flows": [{"deviceId": S2, "isPermanent": True}]})
    st, js = check("t1-op POST own flow claiming t2's appId", "t1-op", "POST", "/flows", 200,
                   body=flow(S2, 1, 3, app_id="lu.uni.sdn.gw.t2"))
    fid = js["flows"][0]["flowId"] if st == 200 else None
    print("      response: %s" % js)
    if fid:
        print("      visible within 10 s: %s" % wait_until(lambda: fid in gw_flow_ids("t1-op")))
        _, allf, _ = call("onos", "GET", BASE + "/onos/v1/flows/%s/%s" % (S2, fid))
        app = allf["flows"][0]["appId"] if isinstance(allf, dict) and allf.get("flows") else None
        print("      /onos/v1/flows/%s/%s appId = %s" % (S2, fid, app))
        if app != "lu.uni.sdn.gw.t1":
            FAILS.append("O3: flow appId is %s, want lu.uni.sdn.gw.t1" % app)

        def ids(user):
            _, j, _ = call(user, "GET", GW + "/flows")
            return [f["id"] for f in j["flows"]], set(f["appId"] for f in j["flows"])
        for u, visible in (("t1-op", True), ("t1-view", True), ("t2-op", False), ("t2-view", False),
                           ("onos", True)):
            got, apps = ids(u)
            ok = (fid in got) == visible
            print("%-5s %s GET /gw/flows sees t1 flow: %s (appIds %s)" % ("ok" if ok else "FAIL", u, fid in got,
                                                                         sorted(apps)))
            if not ok:
                FAILS.append("read filtering for %s" % u)
        check("t2-op DELETE t1's flow", "t2-op", "DELETE", "/flows/%s/%s" % (S2, fid), 403, "not_owner")
        check("t2-op batch DELETE t1's flow", "t2-op", "DELETE", "/flows", 403, "not_owner",
              body={"flows": [{"deviceId": S2, "flowId": fid}]})
        check("t1-view DELETE", "t1-view", "DELETE", "/flows/%s/%s" % (S2, fid), 403, "acl_denied")
        check("t1-op DELETE own flow", "t1-op", "DELETE", "/flows/%s/%s" % (S2, fid), 204)
        check("t1-op DELETE again", "t1-op", "DELETE", "/flows/%s/%s" % (S2, fid), 404, "not_found")
    st, js = check("t1-op POST own flow on core s1 (trunk->trunk)", "t1-op", "POST", "/flows", 200,
                   body=flow(S1, 1, 2, eth_src="00:00:00:00:01:01"))
    if st == 200:
        wait_until(lambda: js["flows"][0]["flowId"] in gw_flow_ids("t1-op"))
        check("t1-op batch DELETE own", "t1-op", "DELETE", "/flows", 204,
              body={"flows": [{"deviceId": S1, "flowId": js["flows"][0]["flowId"]}]})
    st, js = check("admin POST for t2", "onos", "POST", "/flows?tenant=t2", 200, body=flow(S3, 2, 3))
    if st == 200:
        print("      response: %s" % js)
        wait_until(lambda: js["flows"][0]["flowId"] in gw_flow_ids("t2-op"))
        check("admin DELETE t2 flow", "onos", "DELETE", "/flows/%s/%s" % (S3, js["flows"][0]["flowId"]), 204)

    print("== 7. intents: ownership + appId imposition (O4 live)")
    h2h = {"type": "HostToHostIntent", "appId": "lu.uni.sdn.gw.t2", "key": "b1test", "priority": 100,
           "one": "00:00:00:00:01:01/None", "two": "00:00:00:00:01:02/None"}
    check("viewer cannot POST intents", "t1-view", "POST", "/intents", 403, "acl_denied", body=h2h)
    bad = dict(h2h, two="00:00:00:00:02:02/None", key="b1bad")
    check("H2H to t2 host", "t1-op", "POST", "/intents", 403, "endpoint_not_owned", body=bad)
    p2p_bad = {"type": "PointToPointIntent", "appId": "x", "key": "b1p2pbad",
               "ingressPoint": {"device": S2, "port": "1"}, "egressPoint": {"device": S3, "port": "2"}}
    check("P2P to t2 edge port", "t1-op", "POST", "/intents", 403, "endpoint_not_owned", body=p2p_bad)
    sp2mp = {"type": "SinglePointToMultiPointIntent", "appId": "x",
             "ingressPoint": {"device": S2, "port": "1"},
             "egressPoint": [{"device": S3, "port": "1"}]}
    check("SinglePointToMultiPoint", "t1-op", "POST", "/intents", 400, None, body=sp2mp)
    check("t1-op POST own H2H claiming t2's appId", "t1-op", "POST", "/intents", 201, body=h2h)
    print("      visible within 10 s: %s" % wait_until(lambda: "b1test" in gw_intent_keys("t1-op")))
    _, allint, _ = call("onos", "GET", BASE + "/onos/v1/intents")
    mine = [i for i in allint.get("intents", []) if i.get("key") == "b1test"]
    print("      /onos/v1/intents key=b1test -> %s" % [(i["appId"], i.get("state")) for i in mine])
    if not mine or any(i["appId"] != "lu.uni.sdn.gw.t1" for i in mine):
        FAILS.append("O4: intent appId %s" % [i["appId"] for i in mine])
    _, j, _ = call("t2-op", "GET", GW + "/intents")
    seen = [i["key"] for i in j["intents"]]
    print("%-5s t2-op GET /gw/intents does not see b1test: %s" % ("ok" if "b1test" not in seen else "FAIL", seen))
    if "b1test" in seen:
        FAILS.append("t2-op sees t1 intent")
    _, j, _ = call("t1-view", "GET", GW + "/intents")
    seen = [i["key"] for i in j["intents"]]
    print("%-5s t1-view GET /gw/intents sees b1test: %s" % ("ok" if "b1test" in seen else "FAIL", seen))
    if "b1test" not in seen:
        FAILS.append("t1-view does not see own intent")
    check("t2-op DELETE t1 intent (looked up under t2's app)", "t2-op", "DELETE", "/intents/b1test", 404,
          "not_found")
    check("t1-view DELETE intent", "t1-view", "DELETE", "/intents/b1test", 403, "acl_denied")
    check("t1-op DELETE own intent", "t1-op", "DELETE", "/intents/b1test", 204)
    def b1test_left():
        _, allint, _ = call("onos", "GET", BASE + "/onos/v1/intents")
        return [i for i in allint.get("intents", []) if i.get("key") == "b1test"]
    wait_until(lambda: not b1test_left())   # purge is asynchronous too
    left = b1test_left()
    print("%-5s b1test purged (within 10 s): %s" % ("ok" if not left else "FAIL", left))
    if left:
        FAILS.append("intent not purged")
    # B4: a P2P selector must carry an owned source (transit capture), so ETH_SRC=h1a
    p2p = {"type": "PointToPointIntent", "appId": "x", "key": "b1p2p",
           "selector": {"criteria": [{"type": "ETH_SRC", "mac": "00:00:00:00:01:01"}]},
           "ingressPoint": {"device": S2, "port": "1"}, "egressPoint": {"device": S3, "port": "1"}}
    check("t1-op POST own P2P", "t1-op", "POST", "/intents", 201, body=p2p)
    wait_until(lambda: "b1p2p" in gw_intent_keys("t1-op"))
    check("t1-op DELETE own P2P", "t1-op", "DELETE", "/intents/b1p2p", 204)

    print("== 8. no second way in for tenant accounts")
    for path in ("/onos/v1/flows", "/onos/v1/devices", "/onos/v1/docs/", "/onos/macextractor/macs"):
        st, js, _ = call("t1-op", "GET", BASE + path)
        ok = st in (401, 403)
        print("%-5s t1-op GET %s -> %s" % ("ok" if ok else "FAIL", path, st))
        if not ok:
            FAILS.append("second way in: %s -> %s" % (path, st))
    st, js, _ = call("t1-op", "POST", BASE + "/onos/v1/flows", body=flow(S2, 1, 3))
    print("%-5s t1-op POST /onos/v1/flows -> %s" % ("ok" if st in (401, 403) else "FAIL", st))
    if st not in (401, 403):
        FAILS.append("second way in: POST /onos/v1/flows -> %s" % st)

    for u, want_ok in (("onos", True), ("t1-op", False)):
        st, url = gui_login(u)
        ok = (st == 200 and url.endswith("/index.html")) == want_ok
        print("%-5s GUI form login as %s, then GET /onos/ui/index.html -> %s (%s)" % (
            "ok" if ok else "FAIL", u, st, url))
        if not ok:
            FAILS.append("GUI login %s -> %s %s" % (u, st, url))

    print()
    if FAILS:
        print("B1: FAIL (%d)" % len(FAILS))
        for f in FAILS:
            print("  " + f)
        return 1
    print("B1: PASS")
    return 0


if __name__ == "__main__":
    # This suite sends far more than one burst per tenant; it tests B1, so the B2
    # limiter is switched off for the run and restored afterwards.
    import gwlib
    with gwlib.saved_config(["rateLimitEnabled", "adaptiveEnabled"]):
        gwlib.set_cfg({"rateLimitEnabled": False, "adaptiveEnabled": False})
        rc = main()
    sys.exit(rc or (1 if gwlib.FAILS else 0))
