#!/usr/bin/env python3
"""Harness helper for ONOS and the gateway (VM; Python 3.6, stdlib only; admin account).

  onosctl.py wait-gw [secs]               wait until the gateway app is ACTIVE and serving
  onosctl.py purge                        remove every intent and every non-core flow
  onosctl.py gwconfig <config> <out>      set EVERY gateway property, read all back (exit 1 on mismatch)
  onosctl.py reset                        unblock every tenant
  onosctl.py snapshot <dir>               <dir>/tenants.json from /gw/admin/tenants
  onosctl.py state                        JSON: gateway app + .oar md5, other apps, container start
  onosctl.py host                         JSON: VM load average, CPU time (root cgroup), steal ticks
  onosctl.py settle <max s> <cpu %>       wait until ONOS CPU is stable (JSON; exit 1 at the cap)
  onosctl.py target <config>              v1 or gw
  onosctl.py render <conf> <name> k=v...  the workload command with {k} filled in (exit 3: undefined)
  onosctl.py meta <dir>                   write <dir>/meta.json from the run's files
"""
import base64
import collections
import csv
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8181"
COMP = "lu.uni.sdn.apigateway.GatewayManager"
CFG_URL = BASE + "/onos/v1/configuration/" + COMP
GW_APP = "lu.uni.sdn.api_gateway"
HERE = os.path.dirname(os.path.abspath(__file__))

# Every GatewayManager property and its default, as ONOS reports them (strings). Source: the
# @Component property list of GatewayManager.java and the modules' *Settings/*Limits classes.
DEFAULTS = collections.OrderedDict([
    ("aclEnabled", "true"), ("rateLimitEnabled", "true"), ("quotaEnabled", "true"),
    ("validationEnabled", "true"), ("conflictEnabled", "true"), ("adaptiveEnabled", "true"),
    ("proxyBaseUrl", "http://127.0.0.1:8181/onos/v1/"), ("serviceAccountPassword", "gw-svc-pw"),
    ("rateLimitRps", "10.0"), ("rateLimitBurst", "20"), ("maxBatchSize", "50"),
    ("maxBodyBytes", "65536"), ("maxFlowsPerTenant", "100"), ("maxFlowsPerTenantPerDevice", "40"),
    ("maxIntentsPerTenant", "20"), ("churnLimit", "200"), ("churnWindowSeconds", "60"),
    ("minPriority", "100"), ("maxPriority", "30000"), ("maxTimeout", "300"),
    ("throttleMode", "adaptive"), ("scoreHalfLife", "60.0"), ("level1Threshold", "10.0"),
    ("level2Threshold", "25.0"), ("level3Threshold", "50.0"), ("restrictedRateFactor", "0.5"),
    ("cooldown", "30"), ("cooldownMax", "960"), ("fixedPenalty", "60"), ("fixedEffect", "rate"),
    ("fixedRateFactor", "0.1"), ("weightRateLimit", "1.0"), ("weightQuota", "2.0"),
    ("weightValidation", "3.0"), ("weightConflict", "5.0"), ("weightAcl", "3.0"),
])
TOGGLES = ["aclEnabled", "rateLimitEnabled", "quotaEnabled", "validationEnabled", "conflictEnabled",
           "adaptiveEnabled"]
CONFIGS = {  # config -> (API target, changes to DEFAULTS)
    "C1": ("v1", dict.fromkeys(TOGGLES, "false")),  # gateway unused: every module off
    "C2": ("gw", dict(dict.fromkeys(TOGGLES, "false"), aclEnabled="true")),
    "C3": ("gw", {}),
    "C3F": ("gw", {"throttleMode": "fixed"}),  # Lab2-like fixed penalty (question 4)
}


def log(msg):
    print("onosctl: " + msg, file=sys.stderr, flush=True)


def fail(msg):
    log(msg)
    return 1


def call(method, path, body=None, timeout=15):
    """(status, parsed JSON or text); status 0 = connection error/timeout."""
    req = urllib.request.Request(BASE + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Authorization", "Basic " + base64.b64encode(b"onos:rocks").decode())
    if body is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            st, text = r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        st, text = e.code, e.read().decode(errors="replace")
    except Exception as e:
        return 0, str(e)
    try:
        return st, json.loads(text) if text else None
    except ValueError:
        return st, text


def gw(method, path, secs=60):
    """/onos/gw call, retried while the gateway is (re)deploying (401/404/5xx/0)."""
    end = time.time() + secs
    while True:
        st, js = call(method, "/onos/gw" + path)
        if 200 <= st < 300 or time.time() > end:
            return st, js
        time.sleep(2)


def q(s):
    return urllib.parse.quote(str(s), safe="")


def docker(args):  # hard timeout (a `docker exec` has hung on this VM); `timeout` must run under sudo to kill it
    return subprocess.run("sudo timeout -s KILL 10 docker " + args, shell=True, stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL, universal_newlines=True).stdout.strip()


def wait_gw(secs):
    end = time.time() + secs
    while True:
        _, app = call("GET", "/onos/v1/applications/" + GW_APP)
        st, _ = call("GET", "/onos/gw/admin/tenants")
        if isinstance(app, dict) and app.get("state") == "ACTIVE" and st == 200:
            return 0
        if time.time() > end:
            log("gateway not ready after %ss (app %s, /gw/admin/tenants %s)" % (secs, app, st))
            return 1
        time.sleep(2)


def purge():
    t0 = time.time()
    while True:  # 1st DELETE withdraws, a DELETE on a WITHDRAWN/FAILED intent purges
        st, js = call("GET", "/onos/v1/intents")
        if st != 200:
            return fail("GET intents -> %s" % st)
        if not js["intents"]:
            break
        if time.time() - t0 > 60:
            return fail("intents left after 60 s: %s" % [i["key"] for i in js["intents"]])
        for i in js["intents"]:
            call("DELETE", "/onos/v1/intents/%s/%s" % (q(i["appId"]), q(i["key"])))
        time.sleep(1)
    while True:
        st, js = call("GET", "/onos/v1/flows")
        if st != 200:
            return fail("GET flows -> %s" % st)
        apps = {f["appId"] for f in js["flows"]} - {"org.onosproject.core"}
        if not apps:
            break
        if time.time() - t0 > 90:
            return fail("flows of %s left after 90 s" % sorted(apps))
        for a in apps:
            call("DELETE", "/onos/v1/flows/application/" + q(a))
        time.sleep(2)
    log("purge done in %.1fs; %d core flows left" % (time.time() - t0, len(js["flows"])))
    return 0


def get_cfg():
    st, js = call("GET", "/onos/v1/configuration/" + COMP)
    if st != 200 or not isinstance(js, dict) or COMP not in js:
        raise SystemExit("onosctl: GET config -> %s %s" % (st, str(js)[:200]))
    return js[COMP]


def effective():
    """What the running component reports (proves @Modified ran), from one tenant's status."""
    st, js = gw("GET", "/admin/tenants")
    t = sorted(js["tenants"].values(), key=lambda x: x["appId"])[0] if st == 200 and js["tenants"] else {}
    return {"quotaEnabled": t.get("quotaEnabled"), "adaptiveEnabled": t.get("throttle", {}).get("adaptiveEnabled"),
            "mode": t.get("throttle", {}).get("mode")}


def gwconfig(cfg, out):
    want = collections.OrderedDict(DEFAULTS)
    want.update(CONFIGS[cfg][1])
    have = get_cfg()
    if set(have) != set(want):
        return fail("deployed properties differ from the harness table: unknown %s, missing %s"
                    % (sorted(set(have) - set(want)), sorted(set(want) - set(have))))
    st, js = call("POST", "/onos/v1/configuration/" + COMP, want)
    if st != 200:
        return fail("POST config -> %s %s" % (st, js))
    eff_want = {"quotaEnabled": want["quotaEnabled"] == "true",
                "adaptiveEnabled": want["adaptiveEnabled"] == "true", "mode": want["throttleMode"]}
    for _ in range(30):  # @Modified runs asynchronously
        got, eff = get_cfg(), effective()
        bad = {k: [got.get(k), v] for k, v in want.items() if got.get(k) != v}
        if not bad and eff == eff_want:
            break
        time.sleep(1)
    if bad or eff != eff_want:
        return fail("read-back mismatch {name: [got, want]}: %s; effective %s, want %s" % (bad, eff, eff_want))
    json.dump({"config": cfg, "readBack": got, "effective": eff}, open(out, "w"), indent=1, sort_keys=True)
    log("%s: all %d properties read back OK" % (cfg, len(got)))
    return 0


def reset():
    st, js = gw("GET", "/admin/tenants")
    if st != 200:
        return fail("GET /gw/admin/tenants -> %s" % st)
    for t in sorted(js["tenants"]):
        if gw("POST", "/admin/tenants/%s/unblock" % q(t))[0] != 200:
            return fail("unblock %s failed" % t)
    log("unblocked %s" % sorted(js["tenants"]))
    return 0


def snapshot(d):
    st, js = gw("GET", "/admin/tenants", secs=20)
    json.dump(js if st == 200 else {"error": st}, open(os.path.join(d, "tenants.json"), "w"), indent=1, sort_keys=True)
    return int(st != 200)


def state():
    apps = {}
    for a in (GW_APP, "lu.uni.sdn.southbound_mac_extractor", "org.onosproject.fwd", "org.onosproject.proxyarp"):
        st, js = call("GET", "/onos/v1/applications/" + a)
        apps[a] = js if st == 200 else {"state": "not installed"}
    return {"gateway": {"version": apps[GW_APP].get("version"), "state": apps[GW_APP]["state"],
                        "oarMd5": docker("exec onos md5sum /root/onos/apps/%s/%s.oar" % (GW_APP, GW_APP))[:32]},
            "apps": {a: v["state"] for a, v in apps.items()},
            "containerStartedAt": docker("inspect -f '{{.State.StartedAt}}' onos")}


def host():
    """VM load. CPU time from the root cgroup (exact); /proc/stat's tick counts undercount busy
    time on this VM (about 10x), so only its steal share is used."""
    ticks = [int(x) for x in open("/proc/stat").readline().split()[1:9]]  # user..steal
    return {"ts": time.time(), "loadavg": [float(x) for x in open("/proc/loadavg").read().split()[:3]],
            "cpuNs": int(open("/sys/fs/cgroup/cpuacct/cpuacct.usage").read()), "ncpu": os.cpu_count(),
            "stealTicks": ticks[7], "totalTicks": sum(ticks)}


def settle(cap, threshold):
    """Samples the ONOS container's CPU (% of one core) every 1 s until the last 10 s mean is
    below `threshold` and within 20 % (at least 1 point) of the 10 s before; gives up after `cap` s."""
    usage = "/sys/fs/cgroup/cpuacct/docker/%s/cpuacct.usage" % docker("inspect -f '{{.Id}}' onos")
    t0, cpu = time.time(), []
    prev = (time.time(), int(open(usage).read()))
    while True:
        time.sleep(1)
        cur = (time.time(), int(open(usage).read()))
        cpu.append((cur[1] - prev[1]) / 1e9 / (cur[0] - prev[0]) * 100)
        prev = cur
        last, before = sum(cpu[-10:]) / 10, sum(cpu[-20:-10]) / 10
        stable = len(cpu) >= 20 and last < threshold and abs(last - before) <= max(0.2 * before, 1.0)
        if stable or time.time() - t0 > cap:
            print(json.dumps({"stable": stable, "seconds": round(time.time() - t0, 1), "threshold": threshold,
                              "cpuLast10": round(last, 2), "cpuPrev10": round(before, 2)}))
            return 0 if stable else 1


def render(conf, name, kv):
    cmds = dict(l.split("=", 1) for l in open(conf) if "=" in l and not l.lstrip().startswith("#"))
    cmds = {k.strip(): v.strip() for k, v in cmds.items()}
    if name not in cmds:
        return 3
    cmd = cmds[name]
    for k, v in kv.items():
        cmd = cmd.replace("{%s}" % k, v)
    print(cmd)
    return 0


# Liveness checks: minimum data rows per run (the collectors sample from t0-3 s to about t0+121 s;
# heap.csv gets a row even for a failed poll; flows polls can take seconds, hence the lower limit).
MIN_ROWS = {"docker.csv": 110, "heap.csv": 110, "flows.csv": 12}  # flows.csv: distinct polls


def data_rows(path, distinct_ts=False):
    """Data rows (or distinct ts values) of a CSV; None if it is missing."""
    if not os.path.exists(path):
        return None
    with open(path) as f:
        rs = list(csv.DictReader(f))
    return len({r["ts"] for r in rs}) if distinct_ts else len(rs)


def meta(d):
    """meta.json from timeline.txt (k=v lines written by run.sh) and the run's files."""
    path = lambda f: os.path.join(d, f)  # noqa: E731
    rd = lambda f: json.load(open(path(f))) if os.path.exists(path(f)) else None  # noqa: E731
    tl = dict(l.rstrip("\n").split("=", 1) for l in open(path("timeline.txt")) if "=" in l)
    num = lambda k: float(tl[k]) if tl.get(k) else None  # noqa: E731
    exits = {k[5:]: int(v) for k, v in tl.items() if k.startswith("exit_")}
    cmds = {k[4:]: v for k, v in tl.items() if k.startswith("cmd_")}
    invalid = [r for r in tl.get("invalid", "").split(";") if r]
    invalid += ["%s exited %s" % kv for kv in sorted(exits.items()) if kv[1] != 0]
    for f, n in sorted(MIN_ROWS.items()):
        got = data_rows(path(f), f == "flows.csv")
        if got is None or got < n:
            invalid.append("%s: %s rows (< %d)" % (f, got, n))
    for w, cmd in cmds.items():  # a workload given its {out} must write it, with data
        got = data_rows(path(w + ".csv"))
        if "/%s.csv" % w in cmd and not got:
            invalid.append("%s.csv: %s data rows" % (w, got))
    if os.path.exists(path("L.csv")):  # no HTTP answer at all, or a 401: ONOS or the gateway was down
        infra = [r["reason"] for r in csv.DictReader(open(path("L.csv"))) if r["reason"] in ("infra_401", "conn_error")]
        if infra:
            invalid.append("L.csv: %d rows %s" % (len(infra), sorted(set(infra))))
    h0, h1 = rd("host.pre.json"), rd("host.post.json")
    load = None
    if h0 and h1:
        load = {"loadavgStart": h0["loadavg"], "loadavgEnd": h1["loadavg"],
                "vmBusyPct": round((h1["cpuNs"] - h0["cpuNs"]) / 1e7 / (h1["ts"] - h0["ts"]) / h0["ncpu"], 1),
                "vmStealPct": round(100.0 * (h1["stealTicks"] - h0["stealTicks"]) / (h1["totalTicks"] - h0["totalTicks"]), 1)}
    settled = rd("settle.json")
    if not settled or not settled["stable"]:
        invalid.append("ONOS CPU did not settle before t0: %s" % settled)
    pre, post = rd("state.pre.json"), rd("state.post.json")
    if pre and post and (pre["gateway"] != post["gateway"] or pre["containerStartedAt"] != post["containerStartedAt"]):
        invalid.append("gateway or ONOS changed during the run")
    warnings = []
    for w in cmds:
        late = (num("ts_%s_end" % w) or 0) - (num("attack_end") or 0)
        if w != "L" and num("attack_end") and late > 5:
            warnings.append("%s ended %.0f s after the attack window" % (w, late))
    build = os.path.join(HERE, "build-info.json")
    m = {"valid": not invalid, "invalidReasons": invalid, "warnings": warnings,
         "config": tl["config"], "scenario": tl["scenario"], "rep": tl["rep"], "target": tl["target"],
         "t0": num("t0"), "duration": num("duration"), "attackWindow": [num("attack_start"), num("attack_end")],
         "timestamps": {k: num(k) for k in tl if k.startswith("ts_")}, "commands": cmds, "exitCodes": exits,
         "gatewayConfig": rd("gwconfig.json"), "state": {"pre": pre, "post": post},
         "settle": settled, "hostLoad": load,
         "build": json.load(open(build)) if os.path.exists(build) else "missing: run harness/deploy.sh"}
    json.dump(m, open(path("meta.json"), "w"), indent=1, sort_keys=True)
    print("valid" if m["valid"] else "INVALID: " + "; ".join(invalid))
    return 0


def main(a):
    cmd = {"wait-gw": lambda: wait_gw(int(a[1]) if len(a) > 1 else 180), "purge": purge,
           "gwconfig": lambda: gwconfig(a[1], a[2]), "reset": reset, "snapshot": lambda: snapshot(a[1]),
           "state": lambda: print(json.dumps(state(), sort_keys=True)) or 0,
           "target": lambda: print(CONFIGS[a[1]][0]) or 0,
           "render": lambda: render(a[1], a[2], dict(x.split("=", 1) for x in a[3:])),
           "meta": lambda: meta(a[1]), "host": lambda: print(json.dumps(host())) or 0,
           "settle": lambda: settle(float(a[1]), float(a[2]))}
    if not a or a[0] not in cmd:
        print(__doc__)
        return 2
    return cmd[a[0]]()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
