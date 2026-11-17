# Plan: Overview and Architecture

The problem statement is in `../definition.md`. This folder holds the plans:

| File | Contents |
|---|---|
| `00-overview.md` | Architecture, verified facts, open checks, milestones (this file) |
| `A-tenants-scenarios.md` | Track A: tenant topology, accounts, threat model, abuse scenarios, measurement harness |
| `B-gateway-app.md` | Track B: the ONOS gateway application (ACLs + mitigation layer) |
| `C-evaluation.md` | The three-configuration evaluation |

Tracks A and B run in parallel. They meet at milestone M2, the first end-to-end run.

---

## 1. Architecture

Karaf only handles **login**. All **authorization** and mitigation happen in our ONOS app (the "gateway"). Tenants have no access to `/onos/v1`, so the gateway is the only way into the controller for them.

```
 tenant client ──HTTP Basic──▶ /onos/gw/*   (our app, accepts role "tenant")
                                   │
                    Layer 1 (every request, JAX-RS request filter)
                    identify tenant → role/path ACL → rate limit +
                    adaptive throttling → size limit → audit log
                                   │
             ┌─────────────────────┼──────────────────────────┐
             ▼                     ▼                          ▼
   flows / intents          other GET requests          other writes
   native handlers          forwarded to /onos/v1       denied unless
   + Layer 2:               using a viewer-only         role = admin
   quotas, validation,      service account
   conflict/spoofing
             │
   FlowRuleService / IntentService (in-process,
   ApplicationId per tenant, e.g. lu.uni.sdn.gw.t1)

 tenant client ──▶ /onos/v1/*   → rejected (tenant role is neither admin nor viewer)
```

Why this design:
- **Tenant accounts get only a custom `tenant` role.** `onos-rest`'s `web.xml` only accepts the `admin` and `viewer` roles, so these accounts can't use `/onos/v1` for reads or writes. Our app's `web.xml` accepts `tenant`.
- **Flows and intents are installed in-process.** Each tenant has its own `ApplicationId`, which records who owns each rule. Quotas and ownership checks then only need to count or look up rules by app ID. No admin credentials are stored anywhere.
- **Reads are forwarded with a viewer service account.** That account cannot write, so a bug in the forwarder cannot become a write path.
- **Writes the gateway doesn't handle are denied** (meters, groups, netcfg, applications, ...) unless the role is admin. Writes that skip layer 2 are blocked, not let through unchecked.

## 2. Verified facts (tested 2026-11-17, ONOS `onosproject/onos:2.7-latest`, Karaf 4.2.14)

| # | Fact | Evidence |
|---|---|---|
| V1 | ONOS REST authorization only knows two levels: writes (POST/PUT/DELETE/PATCH) need `admin`, everything else needs `viewer`. Nothing per endpoint or per tenant. | `guest` (viewer): GET `/onos/v1/flows` 200, POST 403. `onos` (admin): both 200. Source: `org.onlab.rest.AuthorizationFilter` |
| V2 | `AuthorizationFilter` is opt-in: an app gets it only if its `web.xml` registers it | `guest` DELETE on Lab3 `/onos/macextractor/macs` returned 204 (Lab3's `web.xml` doesn't register the filter) |
| V3 | `POST /onos/v1/flows` accepts `?appId=`, chosen by the caller | `FlowsWebResource.createFlows(@QueryParam("appId") String, InputStream)` in `onos-rest-2.7.0.jar` |
| V4 | `onos-rest`'s `web.xml` restricts access to roles `admin` and `viewer` | `<auth-constraint>` in `onos-rest-2.7.0.jar!/WEB-INF/web.xml` |
| V5 | The local build toolchain works | `JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 mvn -v` → Maven 3.9.12, Java 11.0.33 |
| V6 | The container starts with stock users only (`karaf`, `onos`, `guest`); Lab2's users are not there | `users.properties` read from the container |

V3 means that in the "no controls" config, a tenant can label its flows with **any** app ID, including another tenant's. Record this as a finding.

## 3. Open checks (step 0). Each must pass before we build on it.

| # | Question | How to test | Blocks |
|---|---|---|---|
| O1 | Does an account with only the `tenant` role get rejected by `/onos/v1` and accepted by our app? | Add the account to `users.properties`, restart, curl both | Whole architecture |
| O2 | Can our JAX-RS resource or filter read the logged-in username (`SecurityContext.getUserPrincipal()`)? | `GET /onos/gw/whoami` | Tenant identification |
| O3 | Does `FlowRuleCodec` decode a flow from JSON when we supply the `ApplicationId` ourselves? | Unit test, then a POST through the gateway | B2–B4 |
| O4 | Same question for `HostToHostIntent` and `PointToPointIntent` | Same | B2–B4 for intents |
| O5 | How does the tenant topology get basic connectivity without `fwd` mixing its own flows into the counts? | See A1 | A1, legitimate workload |
| O6 | Where do JVM heap numbers come from (`/onos/v1/system`? `jcmd` in the container? JMX)? | Try each | A5 |

If O1 fails (e.g. Karaf requires `viewer` for any web login), the fallback is: tenants get `viewer`, and our app documents that direct reads bypass the gateway. Then read flooding of `/onos/v1` is out of scope for config 3, and we report that as a limitation.

## 4. Repository layout

```
Project/
  definition.md
  plans/                 these files
  gateway/               ONOS app (Maven, built locally with JDK 11)
  topology/              Mininet tenant topology script
  karaf/                 users.properties overlay + install script
  scenarios/             abuse scenarios S1–S4 + legitimate workload L
  harness/               metrics collection + run orchestration
  results/               raw CSVs + plots per run
```

## 5. Milestones

| Milestone | Contents | Done when |
|---|---|---|
| **M0** Step 0 | O1, O2 answered; gateway skeleton built and deployed; tenant topology up | `t1-op` gets 200 from `/onos/gw/whoami` with the right tenant, and 401/403 from `/onos/v1/flows` |
| **M1** ACL layer | B1 (identification + role/path ACL + read forwarding) and A1–A3 | ACL-only config enforces the role table; tenants can't touch another tenant's resources |
| **M2** First end-to-end | A4 scenarios and A5 harness run against configs 1 and 2 | CSVs for S1–S4 under "no controls" and "ACL only" |
| **M3** Mitigation | B2–B6 one at a time, each tested against its target scenario | Each module has a passing test showing it blocks or limits its scenario |
| **M4** Evaluation | Full matrix from `C-evaluation.md` | Results tables and plots |

## 6. Dev loop

```sh
# laptop
cd gateway && JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 mvn clean install
scp -P 2222 target/*.oar mininet@localhost:~/
# VM
onos-app localhost reinstall! ~/gateway-*.oar     # or the REST applications endpoint
```

The exact deploy command should match whatever was used for Lab3.
