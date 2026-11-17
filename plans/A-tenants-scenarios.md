# Track A: Tenants, Accounts, Threat Model, Scenarios, Harness

Track A builds the environment and the workloads. It runs in parallel with Track B and doesn't depend on the gateway until A4/A5 are pointed at it.

---

## A1. Tenant topology (`topology/tenants.py`)

Each tenant has its own IP subnet, fixed MAC addresses and its own edge ports. Both tenants share the switches.

```
                 s1 (shared core)
                /                \
             s2                    s3
          /      \              /      \
       h1a        h2a        h1b        h2b
```

| Host | Tenant | IP | MAC | Attached at |
|---|---|---|---|---|
| h1a | t1 | 10.0.1.1/16 | 00:00:00:00:01:01 | s2 port 1 |
| h1b | t1 | 10.0.1.2/16 | 00:00:00:00:01:02 | s3 port 1 |
| h2a | t2 | 10.0.2.1/16 | 00:00:00:00:02:01 | s2 port 2 |
| h2b | t2 | 10.0.2.2/16 | 00:00:00:00:02:02 | s3 port 2 |

- Port numbers are fixed in the script (`addLink(..., port1=, port2=)`), so the tenant registry can refer to `of:0000000000000002/1` and so on, and the numbers never shift.
- All hosts share one /16 so ARP works across tenants. Isolation is a policy question, not an addressing trick.
- Controller: `RemoteController` at `127.0.0.1:6653` (the container maps 6653).
- The script also writes `topology/tenants.json`: the tenant registry Track B loads (subnets, MACs, edge connect points, trunk ports).

**O5, connectivity baseline** (decide during A1; options in order of preference):
1. No `fwd`. Each tenant installs its own `HostToHostIntent`s as part of the legitimate workload L. All flows are then tenant-owned, so the counts stay clean. Hosts still need to be discovered, so check whether `hostprovider` learns them from ARP without `fwd`/`proxyarp`. If not, the script pings each host once at startup, or `proxyarp` gets enabled.
2. `fwd` stays on, and the harness excludes `org.onosproject.fwd` flows from the counts. Simpler, but `fwd` flows also take up switch table space and add noise to the measurements.

**Done when** the topology comes up, ONOS sees 3 devices and 4 hosts with the expected connect points, and `tenants.json` matches.

## A2. Karaf accounts (`karaf/`)

Karaf handles **login only**. Roles in the gateway are separate (see B1).

```
# users.properties additions
t1-op   = <pw>,_g_:tenantgroup
t1-view = <pw>,_g_:tenantgroup
t2-op   = <pw>,_g_:tenantgroup
t2-view = <pw>,_g_:tenantgroup
gw-svc  = <pw>,_g_:guestgroup        # viewer-only service account for read forwarding
_g_\:tenantgroup = group,tenant
```

The admin uses the existing `onos` account.

For config 1 ("no controls"), a second overlay puts the tenant accounts in `admingroup`.

- `karaf/apply.sh <overlay>` copies the overlay with `docker cp` and restarts the container. This is the same mechanism as Lab2, but only for users, no command ACLs.
- **Answers O1.** After applying, `t1-op` should get 401/403 on `GET /onos/v1/flows`. Record the actual code.

## A3. Threat model (`plans/threat-model.md`, or a report section)

- **Assets:** controller CPU, JVM heap, and request threads; switch flow tables; other tenants' traffic and policies; topology information.
- **Adversary:** an authenticated tenant operator using only the endpoints its role allows. Out of scope, per the definition: southbound attacks, a compromised controller host, stolen admin credentials, the GUI, the cluster.
- **Trust boundary:** the northbound REST API.
- **For each scenario:** permission used, mechanism, impact on each asset, which layer should catch it.

## A4. Scenarios (`scenarios/`)

Each scenario is a parameterized script (target URL, credentials, rate, duration) that writes timestamped per-request records (status code, latency) to CSV. All of them use only operations the attacking tenant's role allows.

| ID | Scenario (from definition) | Behaviour | Expected outcome per config |
|---|---|---|---|
| **S1** | API flooding / resource exhaustion | High rate of valid requests from t1-op: cheap reads (`GET flows`, `GET topology`), plus a variant with large-but-valid write batches | No controls: latency rises for everyone. ACL only: **unchanged** (every request is allowed). Mitigation: rate limit → 429, then throttling escalates |
| **S2** | Flow-rule churn | t1-op loops: install N flows → delete them → repeat | No controls / ACL only: table and controller churn, slower flow install for t2. Mitigation: churn quota + rate limit |
| **S3** | Semantic flow abuse | Valid-JSON flows that are harmful: catch-all match at top priority, drop-all, permanent rules with no timeout, targeting devices or ports the tenant doesn't own | ACL only: ownership checks catch device/port misuse, **broad matches on owned ports get through**. Mitigation: payload validation rejects them |
| **S4** | Intent-based cross-tenant interference | t1 installs intents or flows that capture or redirect t2 traffic: matching t2's destination at higher priority, spoofing t2's MAC/IP as source, intents ending at t2 hosts | No controls: interference works. ACL only: works for anything not caught by the plain ownership check. Mitigation: conflict/spoofing detection rejects |
| **L** | Legitimate workload (always running) | t2-op does a steady, low-rate mix: install/remove a host intent, read flows and hosts; plus iperf h2a→h2b | Reference for "legitimate tenant throughput" and latency |

Each scenario also has a **pass criterion** for the mitigated config, written before the run (e.g. "S1: L's p95 HTTP latency within 20% of the no-attack baseline").

## A5. Measurement harness (`harness/`)

| Metric (definition objective 4) | Source |
|---|---|
| Controller CPU | `docker stats --no-stream onos`, sampled every 1 s |
| Memory / JVM heap | O6: try `/onos/v1/system` first, then `jcmd <pid> GC.heap_info` inside the container |
| HTTP response latency | Measured client-side by the scenario and L scripts |
| Flow-installation latency | L: time from POST to the flow/intent reaching `ADDED`/`INSTALLED` (polled) |
| Legitimate tenant throughput | L: successful operations per second + iperf h2a→h2b |
| Switch table occupancy | `GET /onos/v1/flows` count per device (admin account, outside tenant paths) |

`harness/run.sh <config> <scenario> <reps>` applies the config, waits for ONOS to be ready, starts the collectors and L, runs the scenario, and writes `results/<config>/<scenario>/<rep>/`. Everything runs on the VM; the scripts are developed here and copied over.
