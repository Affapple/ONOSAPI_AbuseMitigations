#!/usr/bin/env python3
"""L: the legitimate workload (a tenant behaving normally). Runs on the VM; Python 3.6, stdlib only.

  l_legit.py --target v1|gw --user t2-op --password t2-op-pw --duration 120 --out L.csv

- A long-lived HostToHostIntent between the tenant's two hosts (key l-base, priority 100)
  carries a continuous ping a->b (mnctl.sh exec, every 0.5 s).
- A second one (key l-churn, priority 200, so its flows never share an ID with l-base's)
  is installed every CYCLE s, kept HOLD s, then removed.
- A reader thread: READ_RATE reads per second, round robin own flows / intents / hosts. A slow
  read delays the next one; missed reads are skipped, never sent in a burst.
Every request above goes through the tenant path with the tenant's account. Install/remove
timing is measured by polling the intent's state as admin on /onos/v1 (not rate limited, not
logged as L requests): every 50 ms for the first second, then every 250 ms, so the resolution
is ~50 ms for installs under 1 s and ~250 ms for slower ones.

<out>.csv          ts,op,status,reason,latency_ms: one row per tenant request. status 0 = no
                   HTTP answer (reason timeout / conn_error), or a 401 (reason infra_401: Karaf
                   answers 401 while the gateway redeploys, an infrastructure error)
<out>-install.csv  same columns, per intent one "install" row (POST until INSTALLED) and one
                   "remove" row (DELETE until gone); status 0, reason "timeout" if it never happened
<out>-ping.csv     ts,seq,ok,rtt_ms: one row per ping reply or "no answer yet"
Payloads pass the C3 rules (priority 100..30000, no constraints, own hosts). Every intent L
created is removed on exit (also on SIGTERM); exit 1 if setup or that cleanup fails.
"""
import argparse
import base64
import csv
import json
import os
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

CYCLE, HOLD, READ_RATE, WAIT = 15.0, 5.0, 2.0, 20.0
HERE = os.path.dirname(os.path.abspath(__file__))

a = argparse.ArgumentParser()
a.add_argument("--target", choices=["v1", "gw"], required=True)
a.add_argument("--user", default="t2-op")
a.add_argument("--password", default="t2-op-pw")
a.add_argument("--admin", default="onos:rocks", help="user:password for the state polls")
a.add_argument("--base-url", default="http://localhost:8181")
a.add_argument("--duration", type=float, default=120)
a.add_argument("--out", required=True)
args = a.parse_args()

tenant = args.user.split("-")[0]
APP = "lu.uni.sdn.gw." + tenant
HA, HB = json.load(open(os.path.join(HERE, "..", "topology", "tenants.json")))["tenants"][tenant]["hosts"][:2]
V1 = args.base_url + "/onos/v1"
if args.target == "gw":
    GW = args.base_url + "/onos/gw"
    URL = {"flows": GW + "/flows", "intents": GW + "/intents", "hosts": GW + "/v1/hosts",
           "post": GW + "/intents", "delete": GW + "/intents/%s"}
else:
    URL = {"flows": V1 + "/flows/application/" + APP, "intents": V1 + "/intents/application/" + APP,
           "hosts": V1 + "/hosts", "post": V1 + "/intents", "delete": V1 + "/intents/" + APP + "/%s"}

lock = threading.Lock()
base = args.out[:-4] if args.out.endswith(".csv") else args.out
files = []


def csv_out(suffix, header):
    f = open(base + suffix, "w", newline="")
    files.append(f)
    w = csv.writer(f)
    w.writerow(header)
    return w


log = csv_out(".csv", ["ts", "op", "status", "reason", "latency_ms"])
ilog = csv_out("-install.csv", ["ts", "op", "status", "reason", "latency_ms"])


def row(w, *vals):
    with lock:
        w.writerow(vals)
        for f in files:
            f.flush()


def request(cred, method, url, body=None):
    """(status, parsed JSON or None, reason); status 0 = no HTTP answer."""
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body else None)
    req.add_header("Authorization", "Basic " + base64.b64encode(cred.encode()).decode())
    if body:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            st, text = r.status, r.read()
    except urllib.error.HTTPError as e:
        st, text = e.code, e.read()
    except Exception as e:  # no HTTP answer
        timeout = isinstance(e, socket.timeout) or isinstance(getattr(e, "reason", None), socket.timeout)
        return 0, None, "timeout" if timeout else "conn_error"
    try:
        js = json.loads(text.decode()) if text else None
    except ValueError:
        js = None
    if st == 401:
        return 0, None, "infra_401"
    return st, js, js.get("reason", "-") if isinstance(js, dict) else "-"


def http(op, method, url, body=None):
    """One tenant request, logged in <out>.csv."""
    t = time.time()
    st, js, reason = request("%s:%s" % (args.user, args.password), method, url, body)
    row(log, "%.3f" % t, op, st, reason, "%.1f" % ((time.time() - t) * 1000))
    return st


def states():
    """{key: state} of the tenant's intents (admin view), or None if the read failed."""
    st, js, _ = request(args.admin, "GET", V1 + "/intents/application/" + APP)
    return {i["key"]: i.get("state") for i in js["intents"]} if st == 200 else None


created = set()


def install(key, prio):
    i = {"type": "HostToHostIntent", "key": key, "priority": prio,
         "one": HA["mac"] + "/None", "two": HB["mac"] + "/None"}
    if args.target == "v1":
        i["appId"] = APP
    ok = http("post_intent", "POST", URL["post"], i) == 201
    if ok:
        created.add(key)
    return ok


def remove(key):
    return http("delete_intent", "DELETE", URL["delete"] % key) == 204


def wait_for(key, gone, t0, until=None):
    """Polls until key is INSTALLED (or gone), at most until t0+WAIT (or `until`), and logs the
    install/remove row. True on success."""
    op = "remove" if gone else "install"
    until = min(t0 + WAIT, until or t0 + WAIT)
    while time.time() < until:
        s = states()
        if s is not None and (key not in s if gone else s.get(key) == "INSTALLED"):
            row(ilog, "%.3f" % t0, op, 200, "-", "%.1f" % ((time.time() - t0) * 1000))
            return True
        if gone and s and s.get(key) in ("WITHDRAWN", "FAILED") and time.time() - t0 > 3:
            remove(key)  # DELETE withdraws and purges; purge a leftover still there 3 s later
        time.sleep(0.05 if time.time() - t0 < 1 else 0.25)
    row(ilog, "%.3f" % t0, op, 0, "timeout", "%.1f" % ((time.time() - t0) * 1000))
    return False


def reader(end, stop):
    t, k = time.time(), 0
    while time.time() < end and not stop.is_set():
        op = ("flows", "intents", "hosts")[k % 3]
        http("get_" + op, "GET", URL[op])
        k += 1
        t = max(t + 1.0 / READ_RATE, time.time())  # skip missed slots: no catch-up burst
        stop.wait(max(0.0, min(t, end) - time.time()))


def ping(end):
    pw = csv_out("-ping.csv", ["ts", "seq", "ok", "rtt_ms"])
    p = subprocess.Popen(["bash", os.path.join(HERE, "..", "topology", "mnctl.sh"), "exec", HA["name"], "ping",
                          "-D", "-O", "-i", "0.5", "-w", str(int(end - time.time()) + 1), HB["ip"]],
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, universal_newlines=True)

    def parse():  # "[ts] 64 bytes from ..: icmp_seq=3 ttl=64 time=0.1 ms" / "[ts] no answer yet for icmp_seq=4"
        for line in p.stdout:
            if line.startswith("[") and "icmp_seq=" in line:
                rtt = line.split("time=")[1].split()[0] if "time=" in line else ""
                row(pw, line[1:line.index("]")], line.split("icmp_seq=")[1].split()[0], 1 if rtt else 0, rtt)
    th = threading.Thread(target=parse, daemon=True)
    th.start()
    return p, th


def sleep_until(t):
    time.sleep(max(0.0, t - time.time()))


def term(*_):
    raise SystemExit(143)


def main():
    signal.signal(signal.SIGTERM, term)
    t_start = time.time()
    end = t_start + args.duration
    stop, threads, pinger = threading.Event(), [], None
    try:
        while not (install("l-base", 100) and wait_for("l-base", False, time.time())):
            if time.time() - t_start > 30:  # retried while the API is briefly unavailable
                print("l_legit: l-base not INSTALLED after 30 s", file=sys.stderr)
                return 1
            time.sleep(1)
        pinger = ping(end)
        threads.append(threading.Thread(target=reader, args=(end, stop), daemon=True))
        threads[0].start()
        t = time.time()
        while time.time() + HOLD + 3 < end:  # a slow cycle shortens the next pause, never the run
            if install("l-churn", 200):
                wait_for("l-churn", False, t, end)
                sleep_until(min(time.time() + HOLD, end))
                t1 = time.time()
                remove("l-churn")
                wait_for("l-churn", True, t1, end)
            t = max(t + CYCLE, time.time())
            sleep_until(min(t, end))
        sleep_until(end)
        return 0
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        stop.set()
        for th in threads:
            th.join(16)
        if pinger:
            subprocess.call(["sudo", "kill", "-INT", str(pinger[0].pid)])
            pinger[0].wait()
            pinger[1].join(5)
        ok = cleanup()
        for f in files:
            f.close()
        if not ok:
            sys.exit(1)


def cleanup():
    """Removes every intent L created (retried through 401s and timeouts). True if none is left."""
    left = sorted(created)
    for _ in range(10 if created else 0):
        s = states()
        if s is None:
            time.sleep(3)
            continue
        left = sorted(created & set(s))
        if not left:
            return True
        for key in left:
            remove(key)
            wait_for(key, True, time.time())
    if left:
        print("l_legit: CLEANUP FAILED, still present: %s" % left, file=sys.stderr)
    return not left


if __name__ == "__main__":
    sys.exit(main())
