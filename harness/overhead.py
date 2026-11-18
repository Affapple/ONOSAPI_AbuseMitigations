#!/usr/bin/env python3
"""The collectors' own overhead on an idle controller (VM; run once, holding the lock so no other
load runs; it changes nothing):

  flock /tmp/sdn-onos.lock python3 ~/project/harness/overhead.py [seconds per mode] [out dir]

Modes: none, each collector alone, all three, none again. Per mode, after 3 s of settling:
  onos_cpu_pct  ONOS container CPU (cgroup cpuacct.usage), % of one core
  coll_cpu_pct  CPU of the collector processes themselves, % of one core
Writes <out dir>/overhead.csv (default results/overhead/).
"""
import csv
import os
import signal
import subprocess
import sys
import time

H = os.path.dirname(os.path.abspath(__file__))
MODES = [("none", []), ("docker", ["docker"]), ("heap", ["heap"]), ("flows", ["flows"]),
         ("all", ["docker", "heap", "flows"]), ("none", [])]
CID = subprocess.check_output("sudo timeout -s KILL 10 docker inspect -f '{{.Id}}' onos", shell=True,
                              universal_newlines=True).strip()


def onos_s():
    return int(open("/sys/fs/cgroup/cpuacct/docker/%s/cpuacct.usage" % CID).read()) / 1e9


def procs_s(pids):  # utime + stime of the given processes
    return sum(sum(map(int, open("/proc/%d/stat" % p).read().rsplit(")", 1)[1].split()[11:13]))
               for p in pids) / os.sysconf("SC_CLK_TCK")


def main():
    dur = float(sys.argv[1]) if len(sys.argv) > 1 else 60
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(H, "..", "results", "overhead")
    os.makedirs(out, exist_ok=True)
    w = csv.writer(open(os.path.join(out, "overhead.csv"), "w", newline=""))
    w.writerow(["mode", "seconds", "onos_cpu_pct", "coll_cpu_pct"])
    for name, kinds in MODES:
        ps = [subprocess.Popen(["python3", os.path.join(H, "collect.py"), k, out], stderr=subprocess.DEVNULL)
              for k in kinds]
        time.sleep(3)
        pids = [p.pid for p in ps]
        t0, o0, c0 = time.time(), onos_s(), procs_s(pids)
        time.sleep(dur)
        t1, o1, c1 = time.time(), onos_s(), procs_s(pids)
        for p in ps:
            p.send_signal(signal.SIGTERM)
            p.wait()
        r = [name, round(t1 - t0, 1), round(100 * (o1 - o0) / (t1 - t0), 2), round(100 * (c1 - c0) / (t1 - t0), 2)]
        w.writerow(r)
        print("%-6s %6ss  onos %6s%%  collectors %5s%%" % tuple(r), flush=True)


if __name__ == "__main__":
    main()
