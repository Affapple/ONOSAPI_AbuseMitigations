# Threat Model: Authorized API Abuse in a Multi-Tenant ONOS Deployment

Step A3 (Track A). Every factual statement cites its evidence: **V1–V7** (verified
facts in `00-overview.md` §2), **A1**/**A2** (Track A step reports and their audits),
or **B0** (gateway skeleton audit). Anything not yet tested is listed in §10,
"Assumptions to verify", and referenced as **AV-n**.

---

## 1. System under consideration

| Element | Description |
|---|---|
| Controller | ONOS 2.7 (`onosproject/onos:2.7-latest`) in Docker on the Mininet VM, single instance |
| Northbound | REST over HTTP on port 8181: `/onos/v1/*` (stock `onos-rest`), `/onos/gw/*` (our gateway `lu.uni.sdn.api_gateway`), other app contexts (e.g. Lab3's `/onos/macextractor`) |
| Authentication | Karaf JAAS realm backed by `users.properties` (HTTP Basic on every request) |
| Data plane | 3 OVS switches (s1 core, s2/s3 edge), 4 hosts, 2 tenants that share every switch (A1) |
| Tenants | t1: h1a, h1b, 10.0.1.0/24, edge ports s2/1, s3/1. t2: h2a, h2b, 10.0.2.0/24, edge ports s2/2, s3/2. Trunk ports s1/1, s1/2, s2/3, s3/3 (`topology/tenants.json`, A1) |
| Connectivity | No `fwd`, no `proxyarp`. Hosts use static ARP, and traffic flows only through tenant-installed intents/flows (A1, O5) |

Three configurations are evaluated (`C-evaluation.md`):

| Config | Tenant Karaf group | Tenant path | Verified access (A2, GET/POST `/onos/v1/flows`) |
|---|---|---|---|
| C1 no controls | `admingroup` (`noctl` overlay) | `/onos/v1` | `t1-op` 200 / 200 |
| C2 ACL only | `tenantgroup`, role `tenant` (`tenant` overlay) | `/onos/gw` | `t1-op` 403 `!role` / 403; `/onos/gw/whoami` 200, tenant t1 |
| C3 ACL + mitigation | same as C2 | `/onos/gw` | same as C2 |

In the `tenant` overlay, the gateway's read-forwarding account `gw-svc` is a viewer: GET 200, POST 403 on `/onos/v1/flows` (A2). The stock `guest:guest` account is removed from both overlays and gets 401 everywhere (A2 fix).

## 2. Assets

| ID | Asset | Property at risk |
|---|---|---|
| AS1 | Controller CPU | availability |
| AS2 | JVM heap | availability |
| AS3 | Jetty request threads / HTTP latency of the northbound API | availability (for other tenants and the admin) |
| AS4 | Switch flow tables (capacity) and the flow-rule pipeline (FlowRuleManager → OpenFlow FlowMods) | availability; flow-installation latency |
| AS5 | Other tenants' traffic and policies (their intents/flows, their hosts' reachability) | integrity, availability, confidentiality of traffic |
| AS6 | Network and tenant information: topology, hosts (MAC/IP/location), other tenants' rules | confidentiality (reconnaissance) |
| AS7 | Attribution: which tenant owns which rule (appIds), audit trail | integrity (accountability) |
| AS8 | Control-plane services the network depends on: LLDP link discovery and ARP/host learning via the core punt rules (priority 40000, A1) | availability |

## 3. Adversary

- **Who:** an authenticated tenant operator (`t1-op`) with valid credentials. In C2/C3 it also has a tenant viewer account (`t1-view`). It uses only the operations its role allows in the active configuration. It is not trying to break authentication.
- **Capabilities:** it can script arbitrary HTTP request sequences at any rate, with any well-formed payload. It knows the documented ONOS REST API. It controls its own hosts (h1a, h1b), but data-plane attacks from those hosts are out of scope (§7).
- **Knowledge:** in C1 it can read everything (V1: any viewer-level GET is allowed). In C2/C3 the gateway's ACL table (B1) still allows `GET /gw/v1/{devices,links,topology,hosts}`, so the tenant can learn the other tenant's host MACs, IPs and attachment points. Those are exactly the inputs S4 needs. This is a design consequence of B1, not a bug.
- **Goals:** degrade the controller for everyone (S1, S2), monopolise or break shared switch resources (S2, S3), capture, blackhole or connect to another tenant's traffic (S4), and learn other tenants' policies (reconnaissance).

## 4. Trust boundary

The trust boundary is the **northbound REST API on port 8181**. Everything behind it is trusted: Karaf/Jetty, the ONOS core and its apps, the OpenFlow channel and the switches. Inbound requests cross the boundary in this order:

1. **Jetty + Karaf JAAS authentication.** This runs on every request, before any application code (C-evaluation "Known limitations"; its cost is AV1).
2. **The web context's `<auth-constraint>`.** `onos-rest` accepts only roles `admin` and `viewer` (V4), so a `tenant`-role account gets `403 !role` (A2). Lab3's context also requires `admin`/`viewer`: `t1-op` gets 403 and `gw-svc` gets 200 (observed during A3 prep).
3. **Per-application authorization.**
   - `onos-rest`: `AuthorizationFilter` lets writes through only for `admin` (V1).
   - Other apps get that filter only if they opt in (V2). Lab3 does not, so a viewer can write there (V2: `guest` DELETE → 204).
   - The gateway: its own `GatewayFilter` (B-plan Layer 1).
4. **Gateway Layer 2 checks and in-process installation** with the tenant's ApplicationId (`lu.uni.sdn.gw.<tenant>`; `whoami` returns `appId`, A2).

Published container ports (A3 prep, `docker port onos`): 6653 (OpenFlow), 8101 (Karaf SSH) and 8181 (REST), all bound to `0.0.0.0` on the VM.

## 5. Scenarios

Layer names follow `B-gateway-app.md`:
- **ACL** = B1 (role/path table + ownership checks);
- **RL** = B2 (rate limit, `maxBatchSize`, `maxBodyBytes`);
- **Q** = B3 (quotas, churn);
- **VAL** = B4 (payload validation);
- **CF** = B5 (conflict/spoofing);
- **AT** = B6 (adaptive throttling);
- **AUD** = B7 (auditor).

### S1: API flooding / resource exhaustion

| | |
|---|---|
| Permission used | C1: any GET or POST on `/onos/v1` (admin). C2/C3: operator GETs on `/gw/flows`, `/gw/intents`, `/gw/v1/{devices,links,topology,hosts}`, and POST `/gw/flows` with large batches |
| Mechanism | A high rate of valid, cheap reads. Variant: large but valid write batches (many flows per POST) |
| AS1 CPU | authentication per request (AV1), JSON encoding, and flow-store reads that grow with table size (AV3) |
| AS2 heap | response bodies and parsed batches (AV3, AV5) |
| AS3 threads/latency | requests queue, so L's HTTP latency rises (the measured outcome) |
| AS4 | write variant only: many FlowMods |
| AS5 | indirectly, through L's latency and slower flow installs |
| C2 catches | **nothing**: every request is permitted |
| C3 catches | RL (token bucket, weighted per flow in a batch; `maxBatchSize`/`maxBodyBytes` before parsing) → 429; repeated 429s → AT escalation |
| Residual in C3 | the Jetty + JAAS cost of each rejected request is paid before the gateway runs and cannot be rate-limited by it (AV1). If read forwarding uses an HTTP loopback with `gw-svc`, each forwarded read may cost a second authentication (AV8) |

### S2: Flow-rule churn

| | |
|---|---|
| Permission used | flow write + delete. C1: `/onos/v1/flows` (admin). C2/C3: POST/DELETE `/gw/flows` on own ports |
| Mechanism | loop: install N flows → delete them → repeat |
| AS1/AS2 | flow-store updates, FlowMod encoding, garbage |
| AS4 | table and pipeline churn on the shared switches; t2's flow-installation latency is expected to rise (AV6) |
| AS5 | L's intent installs slow down (AV6) |
| C2 catches | **nothing**: the rules are on t1's own ports |
| C3 catches | Q (churn window, flows per tenant and per device), RL (cost per flow), AT |

### S3: Semantic flow abuse

| Variant | Mechanism | Main assets hit |
|---|---|---|
| a. catch-all, top priority | no criteria (or `ETH_TYPE` only) at priority ≥ 40000. The core LLDP/BDDP/ARP punt rules are at priority 40000 (A1 flow dump), so such a rule can shadow them (AV4) | AS8 (link and host discovery), AS5 |
| b. drop-all | catch-all with no output | AS5 (blackholes everyone on that switch) |
| c. permanent rules | `isPermanent=true` / no timeout, to squat on table space | AS4 |
| d. foreign devices/ports | match or output on t2's edge ports or the core switch | AS5 |
| e. output CONTROLLER | packet-in amplification | AS1, AS2 |

| | |
|---|---|
| Permission used | flow write (same as S2) |
| C1 | all variants are accepted (admin) |
| C2 catches | ACL ownership catches **d**. For **a/b/c/e** confined to an owned port they get through. How B1 treats flows with no `IN_PORT` on a shared switch decides whether **a/b** without `IN_PORT` get through (AV7) |
| C3 catches | VAL (priority range, the match must constrain to owned src/port, no permanent rules, no CONTROLLER output, drop only on owned traffic) → 400 with a reason, then AT |

### S4: Intent/flow-based cross-tenant interference

| Variant | Mechanism |
|---|---|
| a. destination capture | a flow matching t2's `ETH_DST`/`IPV4_DST` at a priority above t2's rules on a shared switch. In A1, intent-compiled flows had priority 100 (the submitted priority) and match `IN_PORT`+`ETH_SRC`+`ETH_DST`, so a more general or higher-priority match overrides them (AV12) |
| b. spoofing | match `ETH_SRC`/`IPV4_SRC` of t2, or `SET_ETH_SRC`/`SET_IPV4_SRC` rewrites to t2 addresses |
| c. intents ending at t2 hosts | `HostToHostIntent` h1a ↔ h2b, or a `PointToPointIntent` with an egress on t2's edge port. This breaks isolation, since connectivity exists only through intents (A1: h1a→h2b pings fail without one) |
| d. C1 only: tampering with t2's rules | `GET /onos/v1/intents` lists every intent's appId and key (V1, A1). An admin can withdraw any intent: `DELETE /onos/v1/intents/{appId}/{key}` → 204 (A1). Flows and intents can be labelled with any appId, including t2's (V3, V7), so attribution is forgeable |

| | |
|---|---|
| Permission used | flow/intent write |
| AS5 | t2's traffic captured, blackholed or exposed |
| AS7 | in C1, ownership can be forged (V3/V7) |
| C1 | all variants work |
| C2 catches | ACL ownership: **c** (endpoints must be owned) and any rule on t2's edge ports. Not caught: **a/b** on t1's own ports or on trunk/core ports if B1 allows them (AV7) |
| C3 catches | CF (spoofing, cross-tenant overlap at ≥ priority, destination capture, intent endpoints) → 409, then AT. AUD should report 0 rules outside tenant appIds |

### R: Reconnaissance (from the definition; not a separately timed scenario)

| | |
|---|---|
| C1 | every account can read all flows, intents, hosts and topology (V1) |
| C2/C3 | `/gw/flows` and `/gw/intents` are filtered to the caller's appId (B1). Topology and hosts stay visible to every tenant by design (B1 table), so other tenants' MAC/IP/location is still disclosed (AS6) |

## 6. Verified facts and their consequences

| Fact | Consequence for the threat model |
|---|---|
| V1: two-level REST authorization (writes need admin, everything else needs viewer) | No per-tenant or per-endpoint control in stock ONOS. Any viewer reads everything (R), and any admin changes everything (S4d). ACLs must come from the gateway |
| V2: `AuthorizationFilter` is opt-in (implicitly added by `AbstractWebApplication`) | Other apps' REST contexts may accept writes from viewers (Lab3: `guest` DELETE 204). `gw-svc` is a viewer and gets 200 on Lab3's context, so a leaked `gw-svc` password could change Lab3 state (not tested destructively). Tenants get 403 there (A3 prep) |
| V3, V7: `POST /onos/v1/flows` and `/onos/v1/intents` accept a caller-chosen appId | In C1, attribution (AS7) is forgeable and per-appId quotas are meaningless. The gateway must assign the appId itself, as it does (A2 `whoami.appId`) |
| V7: intent-compiled flows carry appId `org.onosproject.net.intent` | Counting flows by tenant appId misses intent flows. Q and AUD must map flows to intents (`/intents/relatedflows/{appId}/{key}`) |
| V4 + A2: `onos-rest` accepts only roles admin/viewer | A `tenant`-role account gets `403 !role` on `/onos/v1` and 200 on `/onos/gw` (O1), so in C2/C3 the gateway is the only path (for `/onos/v1`, see AV13 for other contexts) |
| V6 + A2: stock users `karaf`, `onos`, `guest` | `guest:guest` (default viewer credentials) would bypass the gateway for reads. It is removed in our overlays, and A2 shows 401 for it |
| A2: Karaf re-reads `users.properties` on every login | Account changes are live without a restart, so whoever can write that file controls authentication (host compromise, out of scope) |
| A2: a container restart kept netcfg, app states and the host store | A restart is not a full reset. Runs must purge flows/intents explicitly and re-check the topology (harness/NOTES.md) |
| A1: core punt rules at priority 40000 (LLDP, BDDP, ARP) | Tenant priorities must stay below the core range (VAL), or S3a can disable discovery (AV4) |
| A1: no `fwd`; connectivity only through tenant intents | All forwarding state belongs to a tenant, so effects on L are attributable to tenant actions |
| C-evaluation limitation: JAAS runs before the gateway | Part of the S1 cost stays in C3 (AV1, to be measured with wrong-password floods) |

## 7. Out of scope (per the definition)

- **Default or stolen admin credentials.** `onos:rocks` and `karaf:karaf` are kept unchanged and treated as admin-only secrets. A real deployment must change both: anyone holding them has full control and bypasses every layer.
- **Southbound attacks** (OpenFlow channel on 6653, rogue switches) and **data-plane attacks from tenant hosts**, e.g. spoofed frames that make hostprovider move another tenant's host (AV10).
- **Controller-host or container compromise**, including writing `users.properties` or using `docker exec`.
- **The web GUI** (`/onos/ui`, which redirects to its login: 307 for `t1-op`, A3 prep).
- **ONOS cluster-level attacks.** The deployment is single-instance.

## 8. C1-specific exposures

Under `noctl`, tenant accounts are in `admingroup`, whose roles include `ssh` and `webconsole` (`users.properties`), and Karaf SSH is published on 8101 (§4). So in C1, tenants presumably also get the **Karaf shell** and the **web console**, which means full control: apps, components, flows (AV2). The C1 scenarios use **REST only**, to keep C1 comparable with C2/C3. The shell is reported as an additional C1 exposure, not measured.

## 9. Lab limitations and confounds

**Lab limitations**
- **Plain-text passwords** in `users.properties`, as configured by the overlays (A2). A real deployment would use hashed passwords or an external identity provider.
- **HTTP Basic over plain HTTP** on a port published on all VM interfaces (§4), so credentials can be sniffed on the network path. This is a lab simplification.

**Confounds**
- **Lab3 app punt rules (user decision pending).** While `lu.uni.sdn.southbound_mac_extractor` is active, it adds priority-5 ip/arp → CONTROLLER rules on every switch (A1 audit, `MacCollector.requestPackets`). Effects:
  - all unmatched IPv4 becomes packet-ins, adding controller load that is unrelated to the API;
  - it changes host learning (MAC and location without IP, A1);
  - it interacts with S3 (drops versus punts) and S4 (traffic that misses a rule reaches the controller instead of being dropped).
  
  Every run must record its state.
- **`fwd` in `ONOS_APPS`.** It has stayed INSTALLED (not active) across all restarts (A2). `apply.sh` warns if that changes.
- **Unshaped links.** iperf h2a→h2b reached 31.5 Gbit/s (A1), so L's throughput is CPU-bound on the shared VM.
- **Shared VM and other agents.** The gateway was briefly not installed (404) during A2 while its owner redeployed it. Runs need exclusive use of the lock.

## 10. Assumptions to verify

| # | Assumption | How to verify (step) |
|---|---|---|
| AV1 | JAAS authentication on every request is a significant CPU cost that the gateway cannot rate-limit | S1 with a wrong password vs a valid one, CPU compared (A5/C) |
| AV2 | Under `noctl`, tenant accounts can log in to the Karaf SSH shell (8101) and the web console | try an SSH login as `t1-op` (only if needed for the report) |
| AV3 | The cost of `GET /onos/v1/flows` grows with the number of flows (CPU/heap) | S1 at different table sizes (A4/A5) |
| AV4 | A catch-all flow at priority ≥ 40000 shadows the LLDP/ARP punt rules, so links and host learning disappear | S3a in C1, watching `/onos/v1/links` (A4) |
| AV5 | `/onos/v1/flows` has no batch or body size limit | S1 write variant with growing batches (A4) |
| AV6 | One tenant's churn raises the other tenant's flow/intent installation latency | S2 vs baseline, L's install latency (A4/A5) |
| AV7 | How B1 treats flows without `IN_PORT`, or on trunk/core ports | gateway ACL tests (Track B) |
| AV8 | Forwarded reads (`/gw/v1/*`) cost an extra `gw-svc` authentication | Track B implementation / profiling |
| AV9 | In C1, one tenant can bulk-delete another's flows by appId (e.g. `DELETE /onos/v1/flows/application/{appId}`) | S4d in C1 (A4) |
| AV10 | A spoofed data-plane frame can move another tenant's host location (out of scope, but it affects S4 results if it happens accidentally) | note only |
| AV11 | Stock ONOS applies no rate limiting on `/onos/v1` (no 429 has been seen so far) | S1 in C1 (A4) |
| AV12 | A tenant flow at priority > 100 matching t2's destination overrides t2's intent flows on a shared switch | S4a in C1 (A4) |
| AV13 | No other REST context reachable by the `tenant` role offers writes (only `/onos/v1`, Lab3, `/onos/ui` and `/onos/v1/docs` were checked: 403/403/307/403) | list web contexts, probe them as `t1-op` |
