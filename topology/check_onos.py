#!/usr/bin/env python3
"""Compare what ONOS sees (REST, admin account) with topology/tenants.json.

  python3 check_onos.py [--url http://localhost:8181] [--user onos:rocks]
                        [--registry tenants.json] [--prune-hosts]

Checks:
  * available devices == the switches implied by tenants.json
  * every registry host is known to ONOS at exactly its registry location
    (MAC, IP, connect point), and no other host is known
  * every trunk port is the endpoint of an ACTIVE link, and every link
    endpoint is a trunk port
--infra-only  check only devices and links (used by mnctl.sh discover before
              it generates host traffic)
--wait N      repeat the check every second until it matches or N seconds pass;
              only the last attempt is printed
--prune-hosts deletes ONOS hosts that are not in the registry (stale hosts from
older topologies). That changes shared state: run it under the team lock.
Exit status 0 when everything matches, 1 otherwise.
"""
import argparse
import base64
import json
import os
import sys
import time
import urllib.parse
import urllib.request


def req(base, auth, path, method="GET"):
    r = urllib.request.Request(base + path, method=method)
    r.add_header("Authorization", "Basic " + base64.b64encode(auth.encode()).decode())
    r.add_header("Accept", "application/json")
    with urllib.request.urlopen(r, timeout=10) as resp:
        body = resp.read()
        return resp.status, (json.loads(body) if body else None)


def check(a, out):
    """One pass. Appends report lines to 'out', returns True on a match."""
    def print(s):  # noqa: A001 - collect instead of printing
        out.append(s)
    base = a.url.rstrip("/") + "/onos/v1"
    reg = json.load(open(a.registry))
    ok = True

    exp_hosts = {}
    for tid, t in reg["tenants"].items():
        for h in t["hosts"]:
            exp_hosts[h["mac"].upper()] = dict(h, tenant=tid)
    trunk = set(reg["trunkPorts"])
    exp_devs = {cp.split("/")[0] for cp in trunk} | \
               {h["location"].split("/")[0] for h in exp_hosts.values()}

    _, devs = req(base, a.user, "/devices")
    avail = sorted(d["id"] for d in devs["devices"] if d["available"])
    unavail = sorted(d["id"] for d in devs["devices"] if not d["available"])
    print("devices available: %s" % avail)
    if unavail:
        print("devices unavailable (stale): %s" % unavail)
    if set(avail) != exp_devs:
        ok = False
        print("  MISMATCH: expected available %s" % sorted(exp_devs))

    if a.infra_only:
        hosts = {"hosts": []}
    else:
        _, hosts = req(base, a.user, "/hosts")
    seen = {}
    for h in hosts["hosts"]:
        locs = ["%s/%s" % (l["elementId"], l["port"]) for l in h["locations"]]
        seen[h["mac"].upper()] = (h, locs)
    if not a.infra_only:
        print("hosts known to ONOS: %d" % len(seen))
    for mac, e in ([] if a.infra_only else
                   sorted(exp_hosts.items(), key=lambda kv: kv[1]["name"])):
        if mac not in seen:
            ok = False
            print("  %-4s %s MISSING (expected at %s)" % (e["name"], mac, e["location"]))
            continue
        h, locs = seen[mac]
        good = locs == [e["location"]] and e["ip"] in h["ipAddresses"]
        ok &= good
        print("  %-4s %s %-14s %-24s ips=%s %s" % (
            e["name"], mac, e["tenant"], ",".join(locs), h["ipAddresses"],
            "OK" if good else "MISMATCH (expected %s %s)" % (e["location"], e["ip"])))
    extra = [m for m in seen if m not in exp_hosts]
    for mac in extra:
        h, locs = seen[mac]
        print("  unexpected host %s ips=%s at %s" % (h["id"], h["ipAddresses"], ",".join(locs)))
        if a.prune_hosts:
            st, _ = req(base, a.user, "/hosts/" + urllib.parse.quote(h["id"], safe=""),
                        method="DELETE")
            print("    deleted (HTTP %d)" % st)
        else:
            ok = False

    _, links = req(base, a.user, "/links")
    ends = set()
    for l in links["links"]:
        s = "%s/%s" % (l["src"]["device"], l["src"]["port"])
        d = "%s/%s" % (l["dst"]["device"], l["dst"]["port"])
        print("link %s -> %s %s" % (s, d, l["state"]))
        if l["state"] == "ACTIVE":
            ends |= {s, d}
    if ends != trunk:
        ok = False
        print("  MISMATCH: link endpoints %s != trunkPorts %s" % (sorted(ends), sorted(trunk)))
    else:
        print("link endpoints == trunkPorts %s" % sorted(trunk))

    return ok


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8181")
    ap.add_argument("--user", default="onos:rocks")
    ap.add_argument("--registry", default=os.path.join(here, "tenants.json"))
    ap.add_argument("--prune-hosts", action="store_true")
    ap.add_argument("--infra-only", action="store_true")
    ap.add_argument("--wait", type=float, default=0)
    a = ap.parse_args()
    deadline = time.time() + a.wait
    tries = 0
    while True:
        tries += 1
        out = []
        try:
            ok = check(a, out)
        except Exception as e:  # ONOS not reachable / restarting
            ok = False
            out.append("ERROR: %r" % (e,))
        if ok or time.time() >= deadline:
            break
        time.sleep(1)
    print("\n".join(out))
    print("RESULT: %s%s%s" % ("MATCH" if ok else "MISMATCH",
                              " (infra only)" if a.infra_only else "",
                              " after %d tries" % tries if a.wait else ""))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
