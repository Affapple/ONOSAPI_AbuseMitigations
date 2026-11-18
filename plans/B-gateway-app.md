# Track B: ONOS Gateway Application

The app is a single ONOS 2.7 bundle, built from the Lab3 template (`lu.uni.sdn`, Lombok, `AbstractWebResource`, web bundle via `_wab`). Each milestone adds one module, and each module can be turned on or off at runtime with ONOS component config (`cfg set`). That is how the evaluation moves between configs 2 and 3 without rebuilding.

- Maven: `lu.uni.sdn:api_gateway`, app name `lu.uni.sdn.api_gateway`, web context `/onos/gw`
- Build: `JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 mvn clean install`

---

## Components

```
GatewayManager        @Component: lifecycle, component config properties, per-tenant ApplicationIds
TenantRegistry        tenants.json → Tenant {id, subnets, macs, edgePorts, accounts→role, quotas}
                      (loaded through ONOS app network config (ConfigFactory) so the admin can update it at runtime;
                       if that turns out awkward, fall back to a file path set in component config)
GatewayFilter         JAX-RS ContainerRequestFilter = Layer 1, registered in web.xml
  ├─ AccessControl    role × method × path table
  ├─ RateLimiter      per-tenant token buckets
  └─ ThrottleManager  violation score → penalty level (adaptive throttling)
FlowsResource         /gw/flows        native handler, Layer 2
IntentsResource       /gw/intents      native handler, Layer 2
ProxyResource         /gw/v1/{path}    GET → /onos/v1/{path} with gw-svc; other methods → 403 unless admin
AdminResource         /gw/admin/...    tenant status, penalty levels, unblock, counters
Layer 2:
  QuotaManager        flow/intent counts per tenant ApplicationId + churn window
  PayloadValidator    format + meaning checks
  ConflictDetector    ownership, spoofing, cross-tenant overlap
```

The request flow:
1. `GatewayFilter` handles every request: identify the tenant, ACL, throttle state, rate limit, size limit.
2. The request goes to the resource.
3. For flows and intents, layer 2 runs: validate → ownership/conflict → quota.
4. Install through `FlowRuleService` / `IntentService` with the tenant's `ApplicationId`.

Every rejection returns a JSON error with a reason code, and every rejection feeds `ThrottleManager`.

Component config properties: `aclEnabled`, `rateLimitEnabled`, `quotaEnabled`, `validationEnabled`, `conflictEnabled`, `adaptiveEnabled`, plus the numeric limits listed below.

---

## B0. Skeleton (milestone M0)

- `pom.xml` copied from Lab3. Change the names. Drop the openflow requirement unless something needs it.
- `web.xml` security constraint accepts roles `tenant`, `viewer`, `admin`. **Don't** register `org.onlab.rest.AuthorizationFilter` (V2). Register `GatewayFilter`.
- `GET /gw/whoami` returns `{user, roles, tenant, gatewayRole}`. **Answers O2.**
- `GatewayManager` registers `lu.uni.sdn.gw.<tenantId>` application IDs at activation.

**Test:** `t1-op` → `whoami` shows tenant `t1`; `gw-svc` → no tenant; wrong password → 401.

## B1. Tenant identification + ACL (milestone M1)

Gateway roles are assigned per account in `tenants.json`. Karaf only knows the role `tenant`.

| Path | viewer | operator | admin |
|---|---|---|---|
| GET `/gw/flows`, `/gw/intents` (own tenant only) | ✓ | ✓ | ✓ all tenants |
| POST/DELETE `/gw/flows`, `/gw/intents` | ✗ | ✓ own resources | ✓ |
| GET `/gw/v1/{devices,links,topology}` | ✓ | ✓ | ✓ |
| GET `/gw/v1/hosts[/{id}]` | ✓ own tenant's hosts only | ✓ own only | ✓ all |
| GET `/gw/v1/{anything else}` | ✗ | ✗ | ✓ |
| Writes to `/gw/v1/*` | ✗ | ✗ | ✓ |
| `/gw/admin/*` | ✗ | ✗ | ✓ |

Reads of flows and intents are filtered to the caller's own `ApplicationId`, so a tenant can't see other tenants' rules. (In the "no controls" config, any account can see every tenant's rules. That's the reconnaissance angle.)

**Ownership checks** also belong to the ACL layer, because without them "ACL only" would be meaningless:
- a flow's device/port and an intent's endpoints must belong to the caller's tenant;
- DELETE requests can only target rules with the caller's `ApplicationId`.

**Tests:** run through the table above with each account, and check the status codes.

## B2. Per-tenant rate limit (milestone M3)

- A token bucket per tenant. The defaults below are placeholders, to be tuned from L's real rate:

| Parameter | Default |
|---|---|
| `rateLimitRps` | 10 req/s |
| `rateLimitBurst` | 20 |

- **Weighted cost:** a GET costs 1 token, a write costs 1 token per flow or intent in the batch. A single huge batch therefore can't get under a request-count limit.
- **Hard caps:** `maxBatchSize` (e.g. 50) and `maxBodyBytes` (e.g. 64 KiB) are enforced before parsing.
- A request that runs out of tokens gets 429 with `Retry-After`.

**Test:** S1 → 429s start after the burst; L, from the other tenant, is unaffected.

## B3. Resource quotas

| Quota | Default | How it's counted |
|---|---|---|
| `maxFlowsPerTenant` | 100 | `FlowRuleService.getFlowEntriesById(tenantAppId)` for direct flows **plus** the flows compiled from the tenant's intents. Track A observed (A1) that intent-compiled flows carry appId `org.onosproject.net.intent`, not the submitter's, so they must be attributed through the tenant's intents (e.g. `IntentService.getInstallableIntents(key)` → FlowRuleIntent rules), never by flow appId |
| `maxFlowsPerTenantPerDevice` | 40 | Same count, grouped by device |
| `maxIntentsPerTenant` | 20 | `IntentService.getIntentsByAppId(tenantAppId)` |
| `churnLimit` | 200 add+delete per 60 s window | Sliding-window counter of write operations |

A request that would exceed a quota is rejected whole (409/429 with the reason). Nothing is partially installed.

**Test:** S2 → the churn quota trips; the table-size quota caps how many rules t1 holds per device.

## B4. Payload validation

1. **Format:** the body parses; the flow decodes with ONOS's own codec (`codec(FlowRule.class)`, O3); only known criterion and instruction types are accepted.
2. **Meaning**, checked against `tenants.json`:
   - priority within `[minPriority, maxPriority]` (e.g. 1000–30000; the reserved range stays with the core apps);
   - the match must constrain the source or destination to something the tenant owns (`IN_PORT` on an owned edge port, or an owned `ETH_SRC`/`IPV4_SRC`). Catch-all and "only `ETH_TYPE`" matches are rejected;
   - permanent rules are not allowed: `isPermanent=false` and `timeout` ≤ `maxTimeout` (e.g. 300 s);
   - drop-all is only allowed when the match is limited to tenant-owned traffic;
   - output ports must be owned edge ports or trunk links, never another tenant's edge port; `CONTROLLER` output is rejected (it would generate packet-ins);
   - intents: only `HostToHostIntent` and `PointToPointIntent`, with endpoints owned by the tenant.
   - **intent selectors and treatments get the same rules as flows** (A3 audit, "transit capture"). A `PointToPointIntent` with an empty or broad selector between two owned edge ports compiles to `IN_PORT`-only rules on the shared transit switches (s1, and the far edge switch's trunk port). Those rules capture **every** tenant's traffic that comes in on that trunk. So a P2P selector must contain an owned `ETH_SRC` or an `IPV4_SRC` inside an owned subnet, and its treatment must not rewrite addresses to foreign ones. `HostToHostIntent` is safe as it is: it compiles to `IN_PORT+ETH_SRC+ETH_DST` with owned MACs.

**Test:** each S3 variant gets 400 with a specific reason code; L's normal flows pass.

## B5. Conflict detection + spoofing prevention

- **Spoofing:** any `ETH_SRC`/`IPV4_SRC` in the match, and any rewrite instruction (`SET_ETH_SRC`, `SET_IPV4_SRC`, ...), must belong to the tenant. Rewriting a destination to another tenant's address is also rejected.
- **Cross-tenant overlap:** for each existing rule of another tenant on the same device, test whether the two matches can both match the same packet (compare criteria field by field; IP prefixes overlap when one contains the other). An overlap where the new rule has priority ≥ the existing one is rejected, because it would take over the other tenant's traffic. Overlap at a lower priority is allowed.
- **Destination capture:** a match on `ETH_DST`/`IPV4_DST` belonging to another tenant is rejected unless the actions only forward toward that destination. Simplest rule to start with: reject it.
- **Intents:** reject any intent whose endpoint is attached to another tenant's edge port.
- **Compiled-flow check (detective, defence in depth):** after an intent reaches `INSTALLED`, look at the compiled flows (`IntentService.getInstallableIntents(key)` → FlowRuleIntent rules). If any of them, on any device, overlaps another tenant's rules or traffic as defined above, withdraw the intent, count a conflict violation (B6), and log it. This catches compilations the selector check didn't anticipate.

**Test:** each S4 variant gets a 409 conflict; t2's traffic (iperf h2a→h2b) is unaffected.

## B6. Adaptive throttling (the escalation proposed at the end of Lab2)

- Every rejection adds to the tenant's score: 429 +1, quota hit +2, validation reject +3, conflict/spoof +5.
- The score decays exponentially (`scoreHalfLife`, e.g. 60 s).

| Level | Enter at score | Effect |
|---|---|---|
| 0 Normal | — | base bucket |
| 1 Restricted | ≥ 10 | bucket rate × 0.5 |
| 2 Cooldown | ≥ 25 | all writes 429 for `cooldown` (30 s), doubling each time it's re-entered |
| 3 Blocked | ≥ 50 | everything 403 until it decays below 25 **or** an admin calls `POST /gw/admin/tenants/{id}/unblock` |

- There's **hysteresis**: the level drops by one only when the score falls below half of that level's threshold, so a tenant can't flap between levels.
- Compare against Lab2's fixed 60 s throttle in C: time until the abuse stops, and how much L is hurt by false positives.

**Test:** run S1 and then S3 in a row → the level rises; after the attack stops, it decays back to 0.

## B7. Auditor: REMOVED (user decision, 2026-11-18)

A bypass auditor (listeners flagging flows/intents that didn't come through the gateway) was built and then removed as unnecessary complexity. Tenants can't reach `/onos/v1` at all (A2: 403 on every other context), and the harness reports flows that belong to neither a tenant nor ONOS core.

---

## B8. Settings refactor ("Option B", user decision 2026-11-18)

`GatewayManager` stays the **single** ONOS component, so that one `cfg set` target switches C1/C2/C3 atomically. Each module owns how its settings are read:
- `XSettings.from(Dictionary props)` (or `XLimits.from`) per module: parsing, defaults, sanity checks and warnings live there;
- property names and defaults live as constants in each module's package;
- the background listeners move into their module (compiled-flow monitor → `conflict/`); `GatewayManager` only starts and stops them.

`GatewayManager` keeps only the `@Component` property list (ONOS requires it there), the toggles, tenant loading and lifecycle.

Success criterion: the `cfg set` names don't change, and every unit test and VM suite passes unchanged.

**Code style (user preference):** use Lombok (`@Getter`, `@Value`, etc.) instead of hand-written getters or boilerplate. Keep code short. Comments must satisfy ONOS checkstyle: the first sentence ends with a period, no trailing spaces.

## Testing strategy

- **Unit tests** (run locally with `mvn install`): token bucket, score/decay/hysteresis, match-overlap logic, validator rules against JSON fixtures. These need no ONOS.
- **Integration tests on the VM:** a curl script per module (`gateway/test/<module>.sh`) that checks the status codes and reasons from that module's "Test" line above.
