#!/usr/bin/env python3
"""S2: flow-rule churn as t1-op (VM; Python 3.6, stdlib only)."""
import argparse
import sys
import time

from common import Client, S2, add_args, eth_type, flow, in_port, output, run_main

BATCH = 20
PRIORITY_BASE = 10000


def main():
    parser = add_args(argparse.ArgumentParser(), 0.0)
    args = parser.parse_args()
    c = Client(args, "S2", args.variant)
    end = time.time() + args.duration
    next_t = time.time()

    def churn():
        nonlocal next_t
        try:
            while time.time() < end:
                rules = [flow(S2, PRIORITY_BASE + i, [in_port(1), eth_type()], output(3))
                         for i in range(BATCH)]
                st, _ = c.post_flows(rules, op="post_batch", variant=args.variant)
                if 200 <= st < 300:
                    c.drain_flows(variant=args.variant)
                if args.rate > 0:
                    next_t = max(next_t + 1.0 / args.rate, time.time())
                    time.sleep(max(0.0, next_t - time.time()))
        finally:
            c.cleanup()
        return 0

    return churn()


if __name__ == "__main__":
    sys.exit(run_main(main))
