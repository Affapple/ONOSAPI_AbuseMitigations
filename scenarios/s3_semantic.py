#!/usr/bin/env python3
"""S3: semantic flow/intent abuse as t1-op (VM; Python 3.6, stdlib only)."""
import argparse
import collections
import sys
import time

from common import (Client, H1A_MAC, H1B_MAC, S1, S2, S3, add_args, drop, eth_type, flow,
                    h2h, in_port, ipv4_src, output, p2p, run_main)

Jobs = collections.namedtuple("Jobs", "name kind build once")


def jobs():
    return [
        Jobs("catchall", "flow", lambda key: flow(S1, 30000, [], output(2), timeout=5), False),
        Jobs("dropall", "flow", lambda key: flow(S2, 20000, [], drop(), timeout=5), False),
        Jobs("permanent", "flow", lambda key: flow(S2, 20000, [in_port(1), eth_type()], output(3),
                                                   permanent=True), False),
        Jobs("foreign_port", "flow", lambda key: flow(S2, 20000, [in_port(2), eth_type()], output(3),
                                                      timeout=60), False),
        Jobs("controller", "flow", lambda key: flow(S2, 20000, [in_port(1), eth_type()],
                                                    [{"type": "OUTPUT", "port": "CONTROLLER"}],
                                                    timeout=60), False),
        Jobs("p2p_empty", "intent", lambda key: p2p(key, (S2, 1), (S3, 1), []), False),
        Jobs("constraints", "intent", lambda key: h2h(
            key, H1A_MAC, H1B_MAC, constraints=[{"type": "BandwidthConstraint", "bandwidth": 1000}]),
            False),
        Jobs("missing_prereq", "flow", lambda key: flow(
            S2, 20000, [in_port(1), ipv4_src("10.0.1.1/32")], output(3), timeout=60), False),
        Jobs("catchall_top", "flow", lambda key: flow(S1, 40001, [], output(2), timeout=1), True),
    ]


def main():
    parser = add_args(argparse.ArgumentParser(), 4.0)
    args = parser.parse_args()
    c = Client(args, "S3", args.variant)
    selected = [j for j in jobs() if args.variant == "all" or j.name in args.variant.split(",")]
    if not selected:
        print("s3_semantic: unknown --variant %s" % args.variant, file=sys.stderr)
        return 2
    end = time.time() + args.duration
    next_t = time.time()
    i = 0
    done_once = set()

    def run():
        nonlocal next_t, i
        try:
            while time.time() < end:
                job = selected[i % len(selected)]
                if job.once and job.name in done_once:
                    i += 1
                    continue
                if job.once:
                    done_once.add(job.name)
                key = "s3-%s-%d" % (job.name, i)
                if job.kind == "flow":
                    st, _ = c.post_flows([job.build(key)], op="post_" + job.name, variant=job.name)
                    if 200 <= st < 300:
                        c.drain_flows(variant=job.name)
                else:
                    st, _ = c.post_intent(job.build(key), op="post_" + job.name, variant=job.name)
                    if 200 <= st < 300:
                        c.delete_intent(key, variant=job.name)
                        if key in c.created_intents:
                            c.created_intents.remove(key)
                i += 1
                if args.rate > 0:
                    next_t = max(next_t + 1.0 / args.rate, time.time())
                    time.sleep(max(0.0, next_t - time.time()))
        finally:
            c.cleanup()
        return 0

    return run()


if __name__ == "__main__":
    sys.exit(run_main(main))
