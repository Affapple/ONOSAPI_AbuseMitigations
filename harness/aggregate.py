#!/usr/bin/env python3
"""Summarise harness runs (laptop, after harness/fetch.sh; Python 3, stdlib only).

  python3 harness/aggregate.py [results dir]          (default: results/)

Reads results/<config>/<workload>/<rep>/ and writes results/summary/{summary.md, resources.csv,
latency.csv, ping.csv}, per config x workload, over the VALID runs (latencies pooled over reps).
Windows: pre (t0..t0+30), attack (t0+30..t0+90), post (t0+90..t0+120).
  resources  ONOS CPU mean / mean in the attack window / peak (% of one core), container memory
             peak, JVM heap peak, flow-table peaks (5 s polls): all flows, busiest device, and per
             appId class: core (org.onosproject.core), intent (org.onosproject.net.intent),
             tenant (lu.uni.sdn.gw.*), other (anything else, e.g. a tenant posing as another app)
  latency    every workload CSV with ts,status,latency_ms (per op too if it has an "op" column):
             requests, success rate (2xx), p50/p95/p99 of the 2xx latencies, rejections (status
             >= 400) by status:reason
  ping       every *-ping.csv (ts,seq,ok,rtt_ms): loss and RTT p50/p95
A run that is invalid or misses a collector CSV is skipped with a message.
"""
import collections
import csv
import glob
import json
import os
import sys

COLLECTORS = ["docker.csv", "heap.csv", "flows.csv"]
WINDOWS = ["pre", "attack", "post"]


def rows(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def num(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def pct(v, p):
    if not v:
        return None
    v = sorted(v)
    k = (len(v) - 1) * p / 100.0
    i = int(k)
    return v[i] + (v[min(i + 1, len(v) - 1)] - v[i]) * (k - i)


def mean(v):
    return sum(v) / len(v) if v else None


def peak(v):
    return max(v) if v else None


def fmt(x, n=1):
    return "" if x is None else round(x, n) if isinstance(x, float) else x


def app_class(app):
    if app.startswith("lu.uni.sdn.gw."):
        return "tenant"
    return {"org.onosproject.core": "core", "org.onosproject.net.intent": "intent"}.get(app, "other")


def resources(d, inrun, window):
    """One run's resource numbers (None where a collector has no sample)."""
    dk = [r for r in rows(os.path.join(d, "docker.csv")) if inrun(num(r["ts"]))]
    cpu = [num(r["cpu_pct"]) for r in dk]
    heap = [num(r["heap_used"]) / 2 ** 20 for r in rows(os.path.join(d, "heap.csv"))
            if inrun(num(r["ts"])) and num(r["heap_used"]) is not None]
    polls = collections.defaultdict(collections.Counter)  # ts -> {"all", "dev:<id>", <app class>: flows}
    for r in rows(os.path.join(d, "flows.csv")):
        if inrun(num(r["ts"])):
            n = int(r["count"])
            polls[r["ts"]].update({"all": n, "dev:" + r["device"]: n, app_class(r["app"]): n})
    out = collections.OrderedDict([
        ("cpu_mean", mean(cpu)),
        ("cpu_attack_mean", mean([num(r["cpu_pct"]) for r in dk if window(num(r["ts"])) == "attack"])),
        ("cpu_peak", peak(cpu)),
        ("mem_peak_mib", peak([num(r["mem_bytes"]) / 2 ** 20 for r in dk])),
        ("heap_peak_mib", peak(heap)),
        ("flows_peak", peak([c["all"] for c in polls.values()])),
        ("device_flows_peak", peak([n for c in polls.values() for k, n in c.items() if k.startswith("dev:")]))])
    for cls in ("core", "intent", "tenant", "other"):
        out[cls + "_peak"] = peak([c[cls] for c in polls.values()])
    return out


def add_workload_csv(path, key, inrun, window, lat, png):
    rs = rows(path)
    if not rs:
        return
    name = os.path.basename(path)[:-4]
    if name.endswith("-ping") and "rtt_ms" in rs[0]:
        for r in rs:
            if inrun(num(r["ts"])):
                s = png[key + (name, window(num(r["ts"])))]
                s["n"] += 1
                if r["ok"] == "1":
                    s["ok"] += 1
                    s["ms"].append(num(r["rtt_ms"]))
    elif {"ts", "status", "latency_ms"} <= set(rs[0]):
        for r in rs:
            if not inrun(num(r["ts"])):
                continue
            st = int(num(r["status"]))
            for op in ["all"] + ([r["op"]] if "op" in r else []):
                s = lat[key + (name, op, window(num(r["ts"])))]
                s["n"] += 1
                if 200 <= st < 300:
                    s["ok"] += 1
                    s["ms"].append(num(r["latency_ms"]))
                elif st >= 400:
                    s["rej"]["%s:%s" % (st, r.get("reason", "-"))] += 1


def table(name, data, out, md):
    if not data:
        return
    with open(os.path.join(out, name + ".csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(data[0]))
        w.writeheader()
        w.writerows(data)
    md += ["", "## " + name, "", "| " + " | ".join(data[0]) + " |", "|" + "---|" * len(data[0])]
    md += ["| " + " | ".join(str(v) for v in r.values()) + " |" for r in data]


def main():
    root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "results"))
    res = collections.defaultdict(list)  # (cfg, wl) -> [resources of each valid run]
    lat = collections.defaultdict(lambda: {"n": 0, "ok": 0, "ms": [], "rej": collections.Counter()})
    png = collections.defaultdict(lambda: {"n": 0, "ok": 0, "ms": []})
    runs = collections.Counter()  # (cfg, wl, used?) -> runs
    for mp in sorted(glob.glob(os.path.join(root, "C*", "*", "*", "meta.json"))):
        d, m = os.path.dirname(mp), json.load(open(mp))
        key = (m["config"], m["scenario"])
        missing = [f for f in COLLECTORS if not os.path.exists(os.path.join(d, f))]
        if not m["valid"] or missing:
            print("skipped %s: %s" % (d, m["invalidReasons"] or "missing %s" % missing))
            runs[key + (False,)] += 1
            continue
        runs[key + (True,)] += 1
        t0, (a0, a1), end = m["t0"], m["attackWindow"], m["t0"] + m["duration"]
        inrun = lambda ts: ts is not None and t0 <= ts <= end  # noqa: E731
        window = lambda ts: "pre" if ts < a0 else "attack" if ts < a1 else "post"  # noqa: E731
        res[key].append(resources(d, inrun, window))
        for p in sorted(glob.glob(os.path.join(d, "*.csv"))):
            if os.path.basename(p) not in COLLECTORS:
                add_workload_csv(p, key, inrun, window, lat, png)
    if not runs:
        sys.exit("no runs under " + root)

    resources_t = []
    for (c, w), v in sorted(res.items(), key=lambda kv: (kv[0][1], kv[0][0])):
        r = collections.OrderedDict([("config", c), ("workload", w), ("runs", runs[(c, w, True)]),
                                     ("skipped", runs[(c, w, False)])])
        for k in v[0]:
            vals = [x[k] for x in v if x[k] is not None]
            r[k] = fmt(mean(vals) if k in ("cpu_mean", "cpu_attack_mean") else peak(vals))
        resources_t.append(r)
    order = lambda k: (k[1], k[2], k[3], k[0], WINDOWS.index(k[-1]))  # noqa: E731 workload, csv, op, config, window
    latency_t = [collections.OrderedDict([
        ("config", k[0]), ("workload", k[1]), ("csv", k[2]), ("op", k[3]), ("window", k[4]), ("requests", s["n"]),
        ("success", fmt(s["ok"] / s["n"], 3)), ("p50_ms", fmt(pct(s["ms"], 50))), ("p95_ms", fmt(pct(s["ms"], 95))),
        ("p99_ms", fmt(pct(s["ms"], 99))), ("rejections", " ".join("%s=%d" % kv for kv in sorted(s["rej"].items())))])
        for k, s in sorted(lat.items(), key=lambda kv: order(kv[0]))]
    ping_t = [collections.OrderedDict([
        ("config", k[0]), ("workload", k[1]), ("csv", k[2]), ("window", k[3]), ("pings", s["n"]),
        ("loss_pct", fmt(100.0 * (s["n"] - s["ok"]) / s["n"])), ("rtt_p50_ms", fmt(pct(s["ms"], 50), 3)),
        ("rtt_p95_ms", fmt(pct(s["ms"], 95), 3))])
        for k, s in sorted(png.items(), key=lambda kv: (kv[0][1], kv[0][0], WINDOWS.index(kv[0][3])))]

    out = os.path.join(root, "summary")
    os.makedirs(out, exist_ok=True)
    md = ["# Evaluation summary", "", "From `%s`, valid runs only; CPU in %% of one core, memory in MiB, "
          "latency of 2xx responses in ms." % root]
    table("resources", resources_t, out, md)
    table("latency", latency_t, out, md)
    table("ping", ping_t, out, md)
    open(os.path.join(out, "summary.md"), "w").write("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main()
