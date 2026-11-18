"""Shared helpers for the gateway's VM integration tests (Python 3.6, stdlib only).

Runs ON THE VM against http://localhost:8181. Tests that change config must run
under the ONOS lock and restore the config they changed (use saved_config()).
"""
import base64
import contextlib
import json
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8181"
GW = BASE + "/onos/gw"
CFG = BASE + "/onos/v1/configuration/lu.uni.sdn.apigateway.GatewayManager"
PW = {"onos": "rocks", "karaf": "karaf"}
FAILS = []

S1, S2, S3 = "of:0000000000000001", "of:0000000000000002", "of:0000000000000003"


def call(user, method, url, body=None, timeout=20):
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
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status, text, hdrs = r.status, r.read().decode(errors="replace"), r.headers
    except urllib.error.HTTPError as e:
        status, text, hdrs = e.code, e.read().decode(errors="replace"), e.headers
    try:
        parsed = json.loads(text) if text else None
    except ValueError:
        parsed = text
    return status, parsed, hdrs


def reason_of(js):
    return js.get("reason") if isinstance(js, dict) else None


def expect(label, ok, detail=""):
    line = "%-5s %s %s" % ("ok" if ok else "FAIL", label, detail)
    print(line)
    if not ok:
        FAILS.append(line)
    return ok


def check(label, user, method, path, want_status, want_reason=None, body=None):
    status, js, hdrs = call(user, method, GW + path, body)
    reason = reason_of(js)
    ok = status == want_status and (want_reason is None or reason == want_reason)
    expect("%-50s %-8s %-6s %s" % (label, user, method, path), ok,
           "-> %s %s%s" % (status, reason or "", "" if ok else "  (want %s %s; body=%s)"
                           % (want_status, want_reason, str(js)[:200])))
    return status, js, hdrs


def flow(dev, in_port, out_port, extra_instructions=None, timeout=120, priority=20000, eth_src=None):
    """A flow inside the B4 limits (priority 100..30000, timeout <= 300). Flows whose
    IN_PORT is a trunk must pass eth_src (an owned MAC) to have an owned match."""
    instr = [{"type": "OUTPUT", "port": str(out_port)}] + (extra_instructions or [])
    crit = [{"type": "IN_PORT", "port": str(in_port)}, {"type": "ETH_TYPE", "ethType": "0x0800"}]
    if eth_src:
        crit.append({"type": "ETH_SRC", "mac": eth_src})
    return {"priority": priority, "isPermanent": False, "timeout": timeout, "deviceId": dev,
            "treatment": {"instructions": instr}, "selector": {"criteria": crit}}


def get_cfg():
    _, js, _ = call("onos", "GET", CFG)
    return dict(js["lu.uni.sdn.apigateway.GatewayManager"])


def set_cfg(values):
    """values: {name: value}; ONOS component config takes strings."""
    st, js, _ = call("onos", "POST", CFG, {k: str(v).lower() if isinstance(v, bool) else str(v)
                                           for k, v in values.items()})
    if st != 200:
        raise RuntimeError("cfg set %s -> %s %s" % (values, st, js))
    time.sleep(1.0)  # @Modified runs asynchronously


@contextlib.contextmanager
def saved_config(names):
    """Restores the named properties to their values at entry, even on failure."""
    before = get_cfg()
    saved = {n: before[n] for n in names if n in before}
    try:
        yield saved
    finally:
        set_cfg(saved)
        after = get_cfg()
        ok = all(after.get(k) == v for k, v in saved.items())
        expect("config restored", ok, str({k: after.get(k) for k in saved}))


def wait_until(pred, secs=10.0):
    end = time.time() + secs
    while time.time() < end:
        if pred():
            return True
        time.sleep(0.2)
    return False


def gw_flow_ids(user, tenant=None):
    _, j, _ = call(user, "GET", GW + "/flows" + ("?tenant=" + tenant if tenant else ""))
    return [f["id"] for f in j["flows"]] if isinstance(j, dict) and "flows" in j else []


def summary(name):
    print()
    if FAILS:
        print("%s: FAIL (%d)" % (name, len(FAILS)))
        for f in FAILS:
            print("  " + f)
        return 1
    print("%s: PASS" % name)
    return 0
