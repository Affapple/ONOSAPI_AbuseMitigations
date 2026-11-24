#!/usr/bin/env python3
"""Shared client helpers for the S1-S4 workload scripts (VM; Python 3.6, stdlib only)."""
import base64
import copy
import csv
import json
import os
import signal
import socket
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(HERE, "..", "topology", "tenants.json")) as _f:
    REGISTRY = json.load(_f)

S1 = "of:0000000000000001"
S2 = "of:0000000000000002"
S3 = "of:0000000000000003"
H1A_MAC = "00:00:00:00:01:01"
H1B_MAC = "00:00:00:00:01:02"
H2A_MAC = "00:00:00:00:02:01"
H2B_MAC = "00:00:00:00:02:02"
H1A_IP = "10.0.1.1"
H1B_IP = "10.0.1.2"
H2A_IP = "10.0.2.1"
H2B_IP = "10.0.2.2"

CSV_HEADER = ["ts", "scenario", "variant", "tenant", "target", "method", "path",
              "status", "reason", "latency_ms", "items", "op"]


def add_args(parser, default_rate):
    parser.add_argument("--target", choices=["v1", "gw"], required=True)
    parser.add_argument("--user", default="t1-op")
    parser.add_argument("--password", default="t1-op-pw")
    parser.add_argument("--base-url", default="http://localhost:8181")
    parser.add_argument("--rate", type=float, default=default_rate)
    parser.add_argument("--duration", type=float, default=60)
    parser.add_argument("--variant", default="all")
    parser.add_argument("--out", required=True)
    return parser


def in_port(port):
    return {"type": "IN_PORT", "port": str(port)}


def eth_type(hex_type="0x0800"):
    return {"type": "ETH_TYPE", "ethType": hex_type}


def eth_src(mac):
    return {"type": "ETH_SRC", "mac": mac}


def eth_dst(mac):
    return {"type": "ETH_DST", "mac": mac}


def ipv4_src(ip):
    return {"type": "IPV4_SRC", "ip": ip}


def ipv4_dst(ip):
    return {"type": "IPV4_DST", "ip": ip}


def output(port):
    return [{"type": "OUTPUT", "port": str(port)}]


def drop():
    return []


def rewrite_eth_dst(mac):
    return [{"type": "L2MODIFICATION", "subtype": "ETH_DST", "mac": mac}, {"type": "OUTPUT", "port": "3"}]


def flow(device, priority, criteria, instructions, timeout=60, permanent=False):
    rule = {"priority": priority, "isPermanent": permanent, "deviceId": device,
            "selector": {"criteria": criteria}, "treatment": {"instructions": instructions}}
    if not permanent:
        rule["timeout"] = timeout
    return rule


def h2h(key, one, two, priority=100, constraints=None):
    intent = {"type": "HostToHostIntent", "key": key, "priority": priority,
              "one": one + "/None", "two": two + "/None"}
    if constraints is not None:
        intent["constraints"] = constraints
    return intent


def p2p(key, ingress, egress, criteria, priority=100):
    return {"type": "PointToPointIntent", "key": key, "priority": priority,
            "ingressPoint": {"device": ingress[0], "port": str(ingress[1])},
            "egressPoint": {"device": egress[0], "port": str(egress[1])},
            "selector": {"criteria": criteria}}


class Client(object):
    def __init__(self, args, scenario, variant="all"):
        self.args = args
        self.scenario = scenario
        self.variant = variant
        self.target = args.target
        self.user = args.user
        self.password = args.password
        self.tenant = args.user.split("-")[0]
        self.app = "lu.uni.sdn.gw." + self.tenant
        self.base = args.base_url.rstrip("/")
        self.V1 = self.base + "/onos/v1"
        self.GW = self.base + "/onos/gw"
        if self.target == "gw":
            self.flows = self.GW + "/flows"
            self.intents = self.GW + "/intents"
            self.url_post_flows = self.GW + "/flows"
            self.url_post_intents = self.GW + "/intents"
            self.flow_delete_one = self.GW + "/flows/%s/%s"
            self.flow_delete_batch = self.GW + "/flows"
            self.intent_delete = self.GW + "/intents/%s"
            self.paths = {"flows": self.GW + "/flows", "intents": self.GW + "/intents",
                          "devices": self.GW + "/v1/devices", "links": self.GW + "/v1/links",
                          "topology": self.GW + "/v1/topology", "hosts": self.GW + "/v1/hosts"}
        else:
            self.flows = self.V1 + "/flows"
            self.intents = self.V1 + "/intents"
            self.url_post_flows = self.V1 + "/flows"
            self.url_post_intents = self.V1 + "/intents"
            self.flow_delete_one = self.V1 + "/flows/%s/%s"
            self.flow_delete_batch = None
            self.intent_delete = self.V1 + "/intents/" + self.app + "/%s"
            self.paths = {"flows": self.V1 + "/flows", "intents": self.V1 + "/intents",
                          "devices": self.V1 + "/devices", "links": self.V1 + "/links",
                          "topology": self.V1 + "/topology", "hosts": self.V1 + "/hosts"}
        self.created_flows = []
        self.created_intents = []
        self.f = open(args.out, "w", newline="")
        self.w = csv.writer(self.f)
        self.w.writerow(CSV_HEADER)
        self.f.flush()

    def row(self, *values):
        self.w.writerow(values)
        self.f.flush()

    def raw(self, method, url, body=None):
        data = None
        if body is not None:
            data = body.encode() if isinstance(body, str) else json.dumps(body).encode()
        req = urllib.request.Request(url, method=method, data=data)
        req.add_header("Authorization", "Basic " +
                       base64.b64encode(("%s:%s" % (self.user, self.password)).encode()).decode())
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                st, text = r.status, r.read()
        except urllib.error.HTTPError as e:
            st, text = e.code, e.read()
        except Exception as e:
            timeout = isinstance(e, socket.timeout) or isinstance(getattr(e, "reason", None), socket.timeout)
            return 0, None, "timeout" if timeout else "conn_error"
        try:
            js = json.loads(text.decode()) if text else None
        except ValueError:
            js = None
        if st == 401:
            return 0, None, "infra_401"
        reason = js.get("reason", "-") if isinstance(js, dict) else "-"
        return st, js, reason

    def request(self, method, url, body=None, op=None, items=1, variant=None):
        t = time.time()
        st, js, reason = self.raw(method, url, body)
        self.row("%.3f" % t, self.scenario, variant or self.variant, self.tenant, self.target,
                 method, url, st, reason, "%.1f" % ((time.time() - t) * 1000), items, op or method)
        return st, js

    def get(self, name, op=None, variant=None):
        return self.request("GET", self.paths[name], op=op or "get_" + name, variant=variant)

    def post_flows(self, rules, op="post_flows", variant=None):
        body = {"flows": [copy.copy(r) for r in rules]}
        if self.target == "v1":
            for r in body["flows"]:
                r["appId"] = self.app
        st, js = self.request("POST", self.url_post_flows, body, op=op, items=len(rules), variant=variant)
        if 200 <= st < 300 and self.target == "gw" and isinstance(js, dict):
            for f in js.get("flows", []):
                self.created_flows.append((f["deviceId"], str(f["flowId"])))
        return st, js

    def post_intent(self, intent, op="post_intent", variant=None):
        body = copy.copy(intent)
        if self.target == "v1":
            body["appId"] = self.app
        st, js = self.request("POST", self.url_post_intents, body, op=op, variant=variant)
        if 200 <= st < 300:
            key = js.get("key") if isinstance(js, dict) else None
            self.created_intents.append(str(key if key is not None else intent["key"]))
        return st, js

    def _flow_ids(self):
        st, js = self.get("flows", op="get_flows")
        if not (200 <= st < 300) or not isinstance(js, dict):
            return None
        return set(str(f.get("id", f.get("flowId"))) for f in js.get("flows", []))

    def wait_visible(self, ids, timeout=2.0):
        end = time.time() + timeout
        ids = set(ids)
        while ids and time.time() < end:
            seen = self._flow_ids()
            if seen is not None:
                ids -= seen
                if not ids:
                    return True
            time.sleep(0.1)
        return not ids

    def drain_flows(self, ids=None, variant=None):
        if self.target == "v1":
            st, _ = self.request("DELETE", self.V1 + "/flows/application/" + self.app,
                                 op="delete_flows", variant=variant)
            if not (200 <= st < 300):
                st2, js = self.get("flows", op="get_flows", variant=variant)
                if 200 <= st2 < 300 and isinstance(js, dict):
                    for f in js.get("flows", []):
                        if f.get("appId") == self.app:
                            self.request("DELETE", self.flow_delete_one % (f["deviceId"], f["id"]),
                                         op="delete_flow", variant=variant)
            self.created_flows = []
            return True
        pending = list(ids if ids is not None else self.created_flows)
        if not pending:
            return True
        for _ in range(3):
            if not pending:
                break
            self.wait_visible([i for _, i in pending])
            body = {"flows": [{"deviceId": d, "flowId": i} for d, i in pending]}
            st, _ = self.request("DELETE", self.flow_delete_batch, body, op="delete_flows",
                                 items=len(pending), variant=variant)
            if 200 <= st < 300:
                pending = []
                break
            left = []
            for d, i in pending:
                st, _ = self.request("DELETE", self.flow_delete_one % (d, i), op="delete_flow",
                                     variant=variant)
                if not (200 <= st < 300 or st == 404):
                    left.append((d, i))
            pending = left
            if pending:
                time.sleep(0.5)
        done = set(pending)
        self.created_flows = [x for x in self.created_flows if x not in done]
        return not pending

    def delete_intent(self, key, op="delete_intent", variant=None):
        return self.request("DELETE", self.intent_delete % key, op=op, variant=variant)

    def cleanup(self, seconds=20.0):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        end = time.time() + seconds
        for key in list(self.created_intents):
            while time.time() < end:
                st, _ = self.delete_intent(key, op="cleanup_intent")
                if 200 <= st < 300 or st == 404:
                    self.created_intents.remove(key)
                    break
                time.sleep(min(1.0, max(0.2, end - time.time())))
        if self.created_flows and self.target != "v1":
            while time.time() < end and self.created_flows:
                self.drain_flows()
                if self.created_flows:
                    time.sleep(min(1.0, max(0.2, end - time.time())))
        elif self.target == "v1":
            self.drain_flows()
        self.f.close()
        return not self.created_intents and not self.created_flows


def run_main(main):
    def term(*_):
        raise SystemExit(143)

    signal.signal(signal.SIGTERM, term)
    try:
        return main()
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
