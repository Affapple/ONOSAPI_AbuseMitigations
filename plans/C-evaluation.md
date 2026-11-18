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

## Known limitations to report

- Karaf JAAS checks HTTP Basic credentials on **every** request, before the gateway's code runs. The gateway can't rate-limit that cost, so part of S1's controller load stays even in C3. Measure it, e.g. with S1 sent using a wrong password.

- Admins are exempt from rate limits, quotas, validation and conflict checks. Stolen admin credentials are out of scope.
- `dst_capture` rejects every "owned source → foreign destination" rule, so C3 allows no cross-tenant communication at all.
- Cross-tenant race in B5: two tenants' conflicting submissions can both pass the overlap check, because it reads the rule store without a cross-tenant lock. Only rules without an owned source can conflict, and B4 rejects those in C3, so the effect there is negligible.
- Intent quota accounting relies on a pre-compilation estimate (B3). Intents in FAILED state count 0 and could reinstall after a topology recovery without a new admission check.
- Adaptive throttling (B6) only reacts to rejections from the other modules. Abuse that's never rejected never escalates: churn just under `churnLimit`, reads just under the rate limit, DELETEs of nonexistent flows (404, weight 0).
- Throttle scores and conflict counters are kept in memory and reset on app reinstall or ONOS restart. Single-node only.
- Q4 baseline: `throttleMode=fixed` with `fixedEffect=rate` is the Lab2 analogue: one violation → reduced rate on *all* requests for 60 s, no escalation. Lab2 capped the source's data-plane traffic, not API requests; state that difference.
- The harness must apply each config **and then restart/purge** before every run. Some checks only act on new events: for example, the compiled-conflict listener doesn't rescan intents that were installed while it was off.

## Outputs

- One table per scenario: rows C1/C2/C3; columns CPU (mean/peak), heap (peak), L's HTTP p95, L's flow-install p95, L's throughput, flows held by the attacker, rejections by reason.
- Time-series plots per scenario: CPU and L latency over the 120 s, with the attack window shaded.
- Peak number of flows that belong to neither a tenant nor ONOS core, per run (expected 0 in C2/C3; the harness reports it).
