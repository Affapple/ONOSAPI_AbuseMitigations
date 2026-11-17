# Evaluation Plan

## Configurations

| Config | Tenant Karaf group | Tenant access path | Gateway modules |
|---|---|---|---|
| **C1** No controls | `admingroup` | `/onos/v1` directly | not used |
| **C2** ACL only | `tenantgroup` | `/onos/gw` | `aclEnabled` only (role table + ownership) |
| **C3** ACL + mitigation | `tenantgroup` | `/onos/gw` | all modules |

In C2 and C3 the scenarios use the gateway equivalent of each request. In C1 the same request goes to `/onos/v1` directly.

## Run matrix

The scenarios are {baseline (L only), S1, S2, S3, S4}, each paired with L, run under C1, C2 and C3. Each cell gets 5 repetitions of 120 s, with the attack running from t=30 s to t=90 s. Every repetition starts from a fresh topology and ONOS restart, or at least a flow and intent purge.

## Questions the evaluation answers

1. **What ACLs alone prevent** (objective 2): compare C1 with C2 for each scenario. Expected: C2 stops device/port misuse and reconnaissance of other tenants, but does **not** stop S1, S2, broad matches on owned ports in S3, or the subtler S4 variants.
2. **What the mitigation layer adds** (objective 5): compare C2 with C3 on the same metrics, plus the pass criteria from A4.
3. **Overhead:** baseline L under C2 and C3 vs C1. Per-request latency added by the gateway (p50/p95), and the controller's CPU and heap with no attack running.
4. **Adaptive vs fixed throttling:** in C3, compare B6 with `adaptiveEnabled=false` and a fixed 60 s penalty (Lab2 behaviour). Measure time until the abuse is contained and the number of L requests wrongly rejected.

## Outputs

- One table per scenario: rows C1/C2/C3; columns CPU (mean/peak), heap (peak), L's HTTP p95, L's flow-install p95, L's throughput, flows held by the attacker, rejections by reason.
- Time-series plots per scenario: CPU and L latency over the 120 s, with the attack window shaded.
- Auditor count for each C3 run (expected 0).
