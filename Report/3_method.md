# Method

## 1. Experimental Approach

This evaluation uses a **simulation-based testbed approach** implemented on a single Linux VM running **Mininet**, a network emulation framework that creates a lightweight virtual topology of network devices and hosts. The harness orchestrates experiments on the VM with ONOS (OpenDaylight Network Operating System) as the SDN controller and a custom API Gateway application running as an ONOS app.

**Key components:**
- **Mininet topology**: Three OpenFlow switches (S1, S2, S3) with four tenant hosts (H1a, H1b from tenant t1; H2a, H2b from tenant t2)
- **ONOS controller**: Hosts the custom gateway application that implements defense mechanisms
- **Tenant isolation**: Two isolated tenants (t1, t2) with separate credentials and tenant-specific API overlays
- **Measurement VM**: The same machine running the topology, controller, and load generators to minimize network variability

The harness uses a **distributed measurement approach**: legitimate and attack workloads are generated from within the VM (using Python scripts) and communicate with the controller via HTTP REST APIs. Collectors run as separate processes sampling system metrics throughout each run.

---

## 2. Configurations Tested

Three main configurations were evaluated, controlled by the `config.sh` script:

### Configuration 1 (C1): Baseline—No Gateway Controls
- **API target**: `/onos/v1` (direct ONOS v1 API, bypassing the gateway)
- **Gateway state**: The gateway app remains installed but all defense modules are disabled
- **Defense toggles**: `aclEnabled=false`, `rateLimitEnabled=false`, `quotaEnabled=false`, `validationEnabled=false`, `conflictEnabled=false`, `adaptiveEnabled=false`
- **Purpose**: Establishes baseline behavior and attack impact under no defense conditions

### Configuration 2 (C2): ACL-Only Defense
- **API target**: `/onos/gw` (gateway API endpoint)
- **Gateway state**: Gateway intercepts all requests; only ACL enforcement is active
- **Defense toggles**: `aclEnabled=true`, all others `false` (rate limiting, quota, validation, conflict detection, adaptive throttling disabled)
- **Purpose**: Isolates the effectiveness of static access control lists as a single-layer defense

### Configuration 3 (C3): Full System (All Defenses)
- **API target**: `/onos/gw` (gateway API endpoint)
- **Gateway state**: Gateway fully enabled with all default defense modules active
- **Defense toggles**: `aclEnabled=true`, `rateLimitEnabled=true`, `quotaEnabled=true`, `validationEnabled=true`, `conflictEnabled=true`, `adaptiveEnabled=true`
- **Other modules**: Rate limiting (10 rps burst 20), quota (100 flows/tenant max), validation (priority range 100–30000), conflict detection, and adaptive throttling (scoreHalfLife=60s, three escalation levels)
- **Purpose**: Demonstrates the full defense stack under realistic, hardened conditions

### Configuration 3F (C3F): Fixed-Penalty Variant
- Same as C3 except `throttleMode=fixed` instead of `adaptive`
- Uses fixed penalty approach (60s duration, 0.1 rate factor) for comparison with adaptive throttling

---

## 3. Experiment Execution

Each experiment run is orchestrated by the harness `run.sh` script and follows a strict sequence:

### 3.1 Invocation
```bash
harness/run.sh <config> <workload> <rep>
```
- `<config>`: One of `C1`, `C2`, `C3`, `C3F`
- `<workload>`: One of `baseline`, `S1`, `S2`, `S3`, `S4` (or custom scenario)
- `<rep>`: Repetition number (1–5 for the standard matrix)

### 3.2 Execution Sequence

1. **Configuration Phase** (via `config.sh`):
   - ONOS restart (if `RESTART=1`, default)
   - Apply user overlay (tenants get restricted access)
   - Ready check: gateway becomes ACTIVE and serving
   - Purge: Remove all intents and non-core flows
   - Gateway configuration: Set every property according to the config, read back all values to verify
   - Unblock tenants: Grant the attack tenant (t1) and legitimate tenant (t2) access
   - Topology discovery: Confirm 3 devices + 4 links present in ONOS

2. **Stabilization Phase**:
   - Poll ONOS CPU usage every 1 second
   - Wait for stability: Last 10 seconds of CPU mean < `SETTLE_CPU` % (default 8%) and within 20% of the previous 10 seconds
   - Timeout: Fail if not stable within `SETTLE_MAX` seconds (default 180s)
   - Stability checkpoint recorded in `settle.json`

3. **Collector Startup** (three parallel processes, all using cgroup-based metrics):
   - **Docker collector** (`collect.py docker`): Samples container CPU and memory every 1 second
   - **Heap collector** (`collect.py heap`): Polls ONOS JVM heap status (via `/onos/v1/system`) every 1 second
   - **Flows collector** (`collect.py flows`): Polls flow counts per device/appId/state via `/onos/v1/flows` every 5 seconds

4. **Measurement Window**:
   - **t = t0 − LEAD**: Legitimate workload (L) starts (default `LEAD=30` seconds before t0)
     - L runs for 150 seconds total (30 s before t0, 120 s measured window)
   - **t = t0**: Official measurement window starts (t0 set after L's setup and controller warm-up)
     - `timeline.txt` records t0 timestamp and attack window offsets
   - **t = t0 + 30**: Named workload (attack scenario) starts (runs for 60 seconds: `ATTACK_LEN=60`)
   - **t = t0 + 90**: Named workload ends
   - **t = t0 + 120**: Measurement window ends, L stops

5. **Cleanup Phase**:
   - Collectors stop; final metrics recorded
   - Snapshot tenant configuration: `tenants.json` exported from gateway
   - Capture post-run state: gateway app state, other apps, container restart time
   - Restore VM to default state: Force tenant overlay, re-apply C3 configuration (no restart), purge and re-discover topology
   - Write run metadata: `meta.json` records validity, timing, build info, stability status

### 3.3 Run Validity Criteria
A run is marked valid only if:
- Workload exits with code 0 (or was not run for baseline)
- Workload does not overrun its time window by more than `GRACE` seconds (default 30s)
- Collectors remain alive throughout
- Enough data rows collected from each collector
- Legitimate workload (L) confirms ONOS and gateway remained reachable
- Gateway and ONOS did not change mid-run (confirmed by `onosctl.py meta` state check)
- Stability achieved before measurement began (`settle.json` valid)

Invalid runs are moved to `results/_attic/` and can be re-run using `matrix.sh`.

---

## 4. Data Collection

Three collectors run in parallel, each writing a time-series CSV with microsecond-precision timestamps:

### 4.1 Docker Collector
- **File**: `docker.csv`
- **Interval**: Every 1 second
- **Columns**: `ts` (timestamp), `cpu_pct` (CPU %, normalized to one core), `mem_bytes` (memory usage in bytes)
- **Source**: Linux cgroup v1 files (`/sys/fs/cgroup/cpuacct/` and `/memory/`)
  - CPU: `cpuacct.usage` delta divided by wall-time delta, multiplied by 100
  - Memory: `memory.usage_in_bytes` minus `memory.stat["total_inactive_file"]` (matches `docker stats` metric)
- **Rationale**: Avoids spawning a new process per sample (unlike `docker stats` or `jstat`); cgroup reads are sub-millisecond

### 4.2 Heap Collector
- **File**: `heap.csv`
- **Interval**: Every 1 second
- **Columns**: `ts`, `heap_used` (bytes), `heap_committed` (bytes), `heap_max` (bytes)
- **Source**: ONOS REST API endpoint `/onos/v1/system` → `mem` field (JVM heap statistics)
- **Rationale**: Direct JVM metrics; avoids `jstat`/`jcmd` which start separate JVMs and have hung in prior test runs
- **Fallback**: If poll fails, row is written with empty values

### 4.3 Flows Collector
- **File**: `flows.csv`
- **Interval**: Every 5 seconds
- **Columns**: `ts`, `device` (OpenFlow device ID), `app` (app ID), `state` (INSTALLED/PENDING/WITHDRAWN/FAILED), `count` (number of flows)
- **Source**: ONOS admin API `/onos/v1/flows` (not rate-limited, not logged to workload metrics)
- **Aggregation**: Each row is one (device, appId, state) tuple; multiple rows per sample possible

### 4.4 Measurement Window Alignment
- Collectors start 3 seconds before t0, ensuring measurements begin before the legitimate workload settles
- Rows are timestamped with wall-clock time (via `time.time()`)
- Data aggregation aligns all three collectors by timestamp, with 5-second flows samples interpolated

---

## 5. Scenario Execution

### 5.1 Legitimate Workload (L)

**Script**: `scenarios/l_legit.py`  
**Duration**: 150 seconds total (30s pre-measurement + 120s measured)  
**Rate**: Defined by `READ_RATE=2` (2 reads per second) and workload timing

**Operations**:
1. **Base intent** (l-base, priority 100):
   - Long-lived HostToHostIntent between tenant's two hosts
   - Installed once at startup; persists throughout the run
   - Associated continuous ping (every 0.5 s) validates connectivity

2. **Churn cycle** (l-churn, priority 200):
   - Every 15 seconds (`CYCLE=15`): Install intent, hold 5 seconds (`HOLD=5`), remove
   - Generates install/remove latency data to quantify control-plane performance

3. **Read workload** (rate = 2 ops/sec, cycle through):
   - GET flows (application-scoped)
   - GET intents (application-scoped)
   - GET hosts
   - Rotates every 1/READ_RATE = 0.5 seconds
   - Missed read slots are skipped, never caught up (no burst)

4. **Ping baseline**:
   - ICMP echo requests every 0.5 seconds to the other tenant host
   - Logs RTT; "no answer yet" for timeout cases
   - Provides low-latency heartbeat to detect connectivity loss

**Output files**:
- `L.csv`: All tenant requests (ts, op, status, reason, latency_ms)
- `L-install.csv`: Intent install/remove timing (per-intent rows)
- `L-ping.csv`: ICMP replies (ts, seq, ok, rtt_ms)

---

### 5.2 Abuse Scenarios

Each scenario runs from t0+30 to t0+90 (60 seconds). Attack workload runs as a separate tenant (t1-op) concurrently with the legitimate workload.

#### **S1: API Flooding & Resource Exhaustion**

**Script**: `scenarios/s1_flood.py`  
**Attack strategy**: Maximize request rate to exhaust controller and gateway resources

**Operations** (repeating cycle):
1. Every 10th request (cycle):
   - POST 10 flow rules to device S2 (priority 28000+i, in_port 1, eth_type 0x0800, output port 3)
   - Drain (delete) all created flows
2. Every other request:
   - GET one of: flows, intents, devices, links, topology, hosts
3. **Rate**: 0 rps (unlimited, as fast as the controller responds)

**Output**: One row per HTTP request with timestamp, status code, latency, items count

#### **S2: Flow-Rule Churn**

**Script**: `scenarios/s2_churn.py`  
**Attack strategy**: Maximize flow table activity and rule-install latency by high-rate churn

**Operations** (repeating):
1. POST batch of 20 flow rules (priority 10000+i, same match/action as S1)
2. If successful (2xx), immediately drain all flows
3. Repeat every ~1–2 seconds (rate-dependent, default 0 rps = as fast as possible)

**Output**: Same CSV format; logs individual batch operations and drain attempts

#### **S3: Semantic Abuse**

**Script**: `scenarios/s3_semantic.py`  
**Attack strategy**: Send malformed, overly permissive, or policy-violating flows/intents

**Attack types** (cycling through selected variants, default: all):
1. **catchall**: Catch-all flow on S1, priority 30000, no match criteria, output port 2 (5s timeout)
2. **dropall**: Drop-all flow on S2, priority 20000, no match, empty instructions (5s timeout)
3. **permanent**: Permanent flow (no timeout) on S2 with match/action
4. **foreign_port**: Match on ingress port 2 (not owned by this tenant) with output
5. **controller**: Output action sends packets to CONTROLLER (suspicious, rate-limited target)
6. **p2p_empty**: Point-to-point intent with no selector criteria
7. **constraints**: Host intent with bandwidth constraint (may require prerequisites)
8. **missing_prereq**: Flow rule with IPv4_SRC selector on port 1 (requires ARP to be learned)
9. **catchall_top**: Catch-all with priority 40001 (above max policy limit 30000) — **run once only**

**Rate**: 4.0 rps (default)  
**Output**: Per-request CSV with variant name logged in "variant" column

#### **S4: Cross-Tenant Interference**

**Script**: `scenarios/s4_cross_tenant.py`  
**Attack strategy**: Inject flows/intents targeting other tenants' hosts to disrupt or eavesdrop

**Attack types** (cycling through selected variants, default: all):
1. **dst_capture**: Flow on S1 matching destination MAC H2b (tenant t2 host), output port 1
2. **dst_capture_owned**: Flow matching source H1a AND destination H2b with match constraints
3. **spoof_src**: Flow on S2 spoofing source MAC as H2a (tenant t2) with output port 3
4. **rewrite_dst**: Flow on S2 matching source H1a and rewriting destination to H2b MAC
5. **capture_ip**: Flow matching destination IP subnet 10.0.2.0/24 (tenant t2's subnet)
6. **intent_cross**: HostToHostIntent from H1a to H2b (cross-tenant connection attempt)
7. **p2p_capture**: Point-to-point intent from (S2, port 1) to (S3, port 1) with cross-tenant match
8. **p2p_spoof**: Point-to-point intent spoofing source as H2a

**Timing**: Each flow/intent held for HOLD=2 seconds before deletion (allowing time for switch install and rule evaluation)  
**Rate**: 0.4 rps (default; slower than flooding to allow rules to propagate)  
**Output**: Per-request CSV with attack variant name logged

---

### 5.3 Test Matrix

The full evaluation matrix is defined in `matrix.sh` and runs:
```bash
CONFIGS="C1 C2 C3" WORKLOADS_TO_RUN="baseline S1 S2 S3 S4" REPS=5 harness/matrix.sh
```

- **Configurations**: 3 (C1, C2, C3 or C3F) = 3 scenarios
- **Workloads**: 5 (baseline + 4 attacks) = 5 scenarios per config
- **Repetitions**: 5 runs per combination = 5 reps
- **Total runs**: 3 configs × 5 workloads × 5 reps = 75 runs
- **Estimated time**: ~4.5 minutes per run (config 2–3 min, measurement 2 min, restore 1–2 min) → ~6 hours total

**Execution order**: Reps → Workloads → Configs (so timing drift spreads evenly across configs)

---

## 6. Metrics Aggregation

**Output structure** per run:
```
results/<config>/<workload>/<rep>/
├── config.log           ONOS config phase output
├── collect-docker.log   Docker collector stderr (if any)
├── collect-flows.log    Flows collector stderr
├── collect-heap.log     Heap collector stderr
├── docker.csv           Container metrics (1 Hz)
├── flows.csv            Flow counts per (device, app, state) tuple (5 Hz)
├── heap.csv             JVM heap metrics (1 Hz)
├── L.csv                Legitimate workload requests
├── L-install.csv        L's intent install/remove timing
├── L-ping.csv           ICMP ping data
├── S1.csv (or S2–S4)    Attack workload requests
├── settle.json          Stability check results
├── state.pre.json       Gateway + ONOS state before measurement
├── state.post.json      Gateway + ONOS state after measurement
├── host.pre.json        Host system metrics (load, CPU cgroup time)
├── host.post.json       Post-measurement host metrics
├── tenants.json         Tenant configuration snapshot
├── meta.json            Run metadata (valid flag, build commit, timing, stability)
├── timeline.txt         Event log (config, L start, t0, attack window, end times)
└── run.log              Full harness log (tee of run.sh stdout/stderr)
```

**Aggregation** (via `aggregate.py`):
- Reads all workload CSVs (L, S1–S4) from a run
- Groups rows by operation type (op column if present, else per-row)
- Computes per-operation statistics:
  - Success rate (status 2xx / total)
  - Rejection rate (status ≥ 400 / total, broken down by status:reason)
  - Latency percentiles (p50, p99, p99.9) on successful (2xx) requests only
- Cross-references with system metrics (CPU, heap, flows) via aligned timestamps

---

## Summary

This evaluation combines **controlled emulation** (Mininet), **realistic API patterns** (HTTP REST over ONOS), and **precise measurement** (cgroup metrics, 1–5 Hz collectors, microsecond timestamps) to assess the SDN API gateway's defense mechanisms under four distinct attack scenarios. The harness ensures repeatability through deterministic orchestration, automatic validity checks, and resumable matrix execution, while separating legitimate and attack traffic to isolate performance impact.
