#!/usr/bin/env python3
"""S4: cross-tenant interference through flows and intents as t1-op (VM; Python 3.6, stdlib only)."""
import argparse
import collections
import sys
import time

from common import (Client, H1A_MAC, H2A_MAC, H2B_MAC, S1, S2, S3, add_args, eth_dst, eth_src,
                    eth_type, flow, h2h, in_port, ipv4_dst, output, p2p, rewrite_eth_dst, run_main)

HOLD = 2.0
Jobs = collections.namedtuple("Jobs", "name kind build")


def jobs():
    return [
        Jobs("dst_capture", "flow", lambda key: flow(
            S1, 29000, [eth_dst(H2B_MAC)], output(1), timeout=60)),
        Jobs("dst_capture_owned", "flow", lambda key: flow(
            S1, 29010, [in_port(1), eth_type(), eth_src(H1A_MAC), eth_dst(H2B_MAC)], output(2),
            timeout=60)),
        Jobs("spoof_src", "flow", lambda key: flow(
            S2, 29020, [in_port(1), eth_type(), eth_src(H2A_MAC)], output(3), timeout=60)),
        Jobs("rewrite_dst", "flow", lambda key: flow(
            S2, 29030, [in_port(1), eth_type(), eth_src(H1A_MAC)], rewrite_eth_dst(H2B_MAC),
            timeout=60)),
        Jobs("capture_ip", "flow", lambda key: flow(
            S2, 29040, [in_port(1), eth_type(), ipv4_dst("10.0.2.0/24")], output(3), timeout=60)),
        Jobs("intent_cross", "intent", lambda key: h2h(key, H1A_MAC, H2B_MAC)),
        Jobs("p2p_capture", "intent", lambda key: p2p(
            key, (S2, 1), (S3, 1), [eth_src(H1A_MAC), eth_dst(H2B_MAC)])),
        Jobs("p2p_spoof", "intent", lambda key: p2p(key, (S2, 1), (S3, 1), [eth_src(H2A_MAC)])),
    ]


def main():
    parser = add_args(argparse.ArgumentParser(), 0.4)
    args = parser.parse_args()
    c = Client(args, "S4", args.variant)
    selected = [j for j in jobs() if args.variant == "all" or j.name in args.variant.split(",")]
    if not selected:
        print("s4_cross_tenant: unknown --variant %s" % args.variant, file=sys.stderr)
        return 2
    end = time.time() + args.duration
    next_t = time.time()
    i = 0

    def run():
        nonlocal next_t, i
        try:
            while time.time() < end:
                job = selected[i % len(selected)]
                key = "s4-%s-%d" % (job.name, i)
                if job.kind == "flow":
                    st, _ = c.post_flows([job.build(key)], op="post_" + job.name, variant=job.name)
                    if 200 <= st < 300:
                        time.sleep(min(HOLD, max(0.0, end - time.time())))
                        c.drain_flows(variant=job.name)
                else:
                    st, _ = c.post_intent(job.build(key), op="post_" + job.name, variant=job.name)
                    if 200 <= st < 300:
                        time.sleep(min(HOLD, max(0.0, end - time.time())))
                        c.delete_intent(key, variant=job.name)
                        if key in c.created_intents:
                            c.created_intents.remove(key)
                i += 1
                if args.rate > 0:
                    next_t = max(next_t + 1.0 / args.rate, time.time())
                    time.sleep(max(0.0, min(next_t, end) - time.time()))
        finally:
            c.cleanup()
        return 0

    return run()


if __name__ == "__main__":
    sys.exit(run_main(main))
