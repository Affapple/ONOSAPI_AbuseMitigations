#!/usr/bin/env python3
"""Measurement collectors (VM; Python 3.6, stdlib only). One collector per process; runs until
SIGTERM and flushes every row.

  collect.py docker <dir>  docker.csv ts,cpu_pct,mem_bytes          every 1 s
      From the container's cgroup (v1) files, no `docker` process per sample: cpu_pct =
      cpuacct.usage delta / wall time * 100 (% of one core); mem = memory.usage_in_bytes -
      total_inactive_file (as `docker stats`). `docker stats` divides CPU by /proc/stat ticks
      instead, which on this VM run ~6 % slower than wall time (NOTES.md).
  collect.py heap <dir>    heap.csv   ts,heap_used,heap_committed,heap_max   every 1 s (empty: poll failed)
      GET /onos/v1/system "mem" = the ONOS JVM heap (checked against jstat -gc, NOTES.md).
      Chosen over jstat/jcmd in the container: those start a JVM per sample and have hung.
  collect.py flows <dir>   flows.csv  ts,device,app,state,count     every 5 s
      GET /onos/v1/flows (admin), counted per device, appId and state.
"""
import base64
import collections
import csv
import json
import os
import signal
import subprocess
import sys
import time
import urllib.request

AUTH = "Basic " + base64.b64encode(b"onos:rocks").decode()


def rest(path):
    req = urllib.request.Request("http://localhost:8181/onos/v1" + path, headers={"Authorization": AUTH})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        print("%.3f %s: %s" % (time.time(), path, e), file=sys.stderr, flush=True)
        return None


def every(interval):
    """Yields the sample time at t0 + k*interval (no drift)."""
    t0 = time.time()
    while True:
        yield time.time()
        time.sleep(max(0.0, t0 + interval * (int((time.time() - t0) / interval) + 1) - time.time()))


def csv_out(d, name, header):
    f = open(os.path.join(d, name), "w", newline="")
    w = csv.writer(f)
    w.writerow(header)

    def row(*v):
        w.writerow(v)
        f.flush()
    return row


def docker(d):
    cid = subprocess.check_output(["sudo", "timeout", "-s", "KILL", "10", "docker", "inspect", "-f", "{{.Id}}", "onos"],
                                  universal_newlines=True).strip()
    cg = lambda c, f: open("/sys/fs/cgroup/%s/docker/%s/%s" % (c, cid, f)).read()  # noqa: E731

    def cpu():  # (container CPU ns, wall ns)
        return int(cg("cpuacct", "cpuacct.usage")), time.time() * 1e9
    row = csv_out(d, "docker.csv", ["ts", "cpu_pct", "mem_bytes"])
    prev = cpu()
    for i, ts in enumerate(every(1.0)):
        cur = cpu()
        if i:
            stat = dict(l.split() for l in cg("memory", "memory.stat").splitlines())
            mem = int(cg("memory", "memory.usage_in_bytes")) - int(stat["total_inactive_file"])
            row("%.3f" % ts, "%.2f" % ((cur[0] - prev[0]) / (cur[1] - prev[1]) * 100), mem)
        prev = cur


def heap(d):
    row = csv_out(d, "heap.csv", ["ts", "heap_used", "heap_committed", "heap_max"])
    for ts in every(1.0):
        m = (rest("/system") or {}).get("mem") or {}  # a failed poll still writes a row (empty values)
        row("%.3f" % ts, m.get("current", ""), m.get("committed", ""), m.get("max", ""))


def flows(d):
    row = csv_out(d, "flows.csv", ["ts", "device", "app", "state", "count"])
    for ts in every(5.0):
        js = rest("/flows")
        if js:
            n = collections.Counter((f["deviceId"], f["appId"], f["state"]) for f in js["flows"])
            for k in sorted(n):
                row("%.3f" % ts, k[0], k[1], k[2], n[k])


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] not in ("docker", "heap", "flows"):
        sys.exit(__doc__)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    globals()[sys.argv[1]](sys.argv[2])
