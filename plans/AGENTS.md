# Team Rules (read before doing anything)

You are one agent on a team building this project. Read `../definition.md` and every file in `plans/` first. They are the specification. If the spec is wrong or impossible, **stop and report**; don't silently deviate.

## Core rule: no larping

Do not claim something works, exists, or behaves a certain way unless you ran it and saw the output. Every claim in your report must have evidence: the command you ran and the relevant output, pasted verbatim (trimmed is fine). If you couldn't verify something, write **UNVERIFIED** and explain why. Inventing ONOS APIs, Karaf behaviour, or test results is the worst possible failure here.

## Environment

| Thing | Value |
|---|---|
| Project root (laptop) | `/home/jgaspar/Downloads/SDN/Project` |
| Build (laptop) | `JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 mvn clean install` (default JDK is 25 and won't build ONOS 2.7) |
| Reference code (read only, never modify) | `/home/jgaspar/Downloads/SDN/Lab3/southbound_mac_extractor` (working ONOS 2.7 app with REST), `/home/jgaspar/Downloads/SDN/Lab2` |
| Run a command on the VM | `tools/vm.sh '<command>'` (Ubuntu 18.04 Mininet VM, user `mininet`, passwordless sudo) |
| Copy files to the VM | `tools/vm-cp.sh <local...> <remote-path>` |
| ONOS | Docker container `onos` on the VM (`onosproject/onos:2.7-latest`, Karaf at `/root/onos/apache-karaf-4.2.14`), REST at `http://localhost:8181` *from inside the VM*, OpenFlow on 6653. Admin login `onos:rocks`. Container restart policy is `no`. |
| VM tools | no tmux or screen; `flock`, `curl`, `python3` available. Long-running processes need `setsid nohup ... &` with a PID file. |
| ONOS ready check | loop until `curl -s -o /dev/null -w '%{http_code}' -u onos:rocks http://localhost:8181/onos/v1/devices` returns 200 |

Code is written on the laptop and copied to the VM to run. There is no git repo; don't create one.

## VM locking (several agents share one ONOS)

Any command that **changes shared state** must hold the lock:

```
tools/vm.sh 'flock /tmp/sdn-onos.lock -c "<command>"'
```

Shared state: restarting or reconfiguring the container, installing/activating/uninstalling ONOS apps, starting/stopping Mininet, purging flows or intents, editing `users.properties`. Keep lock holds short. Read-only commands (GET requests, `docker stats`, reading files) don't need the lock. After anything that restarts ONOS, run the ready check before testing.

**Mininet hazard:** `mn -c` (and `topology/mnctl.sh clean`) kills every process whose command line contains `mininet:`, including your own ssh shell. Never put that string in a command that also runs a cleanup.

**Topology:** Track A's `topology/mnctl.sh` (on the VM: `~/project/topology/mnctl.sh`) handles `start --static-arp | stop | status | exec <host> <cmd> | discover`. There's no `fwd`/`proxyarp`: connectivity only comes from tenant intents and flows, with static ARP on the hosts. Intent-compiled flows carry appId `org.onosproject.net.intent`, not the tenant's. They can be traced back to the submitter with `GET /onos/v1/intents/relatedflows/{appId}/{key}` and `/intents/installables/{appId}/{key}`. After **any** ONOS restart or host purge, run `mnctl.sh discover` and then `check_onos.py`, and stop if it doesn't print MATCH.

**Karaf overlays:** `~/project/karaf/apply.sh <tenant|noctl|stock>` switches `users.properties` and restarts ONOS (about 45–65 s). It takes the lock **itself**, so never wrap it in `flock`; that deadlocks. If you already hold the lock, set `SDN_LOCK_HELD=1`. The default state of the VM is the `tenant` overlay. Always leave it that way. Karaf re-reads `users.properties` on every login, so a copied overlay is live at once, even before the restart. The tenant/noctl overlays have no `guest` account. Default admin credentials (`onos:rocks`, `karaf:karaf`) are out of scope as "stolen admin credentials".

**Lab3 app dependency (pending user decision):** while `lu.uni.sdn.southbound_mac_extractor` is active, it requests priority-5 `ip` and `arp` → CONTROLLER punt rules on every switch. So all unmatched IPv4 traffic reaches ONOS as packet-ins, which feeds host learning and adds controller load. Don't rely on it, and don't deactivate it yourself. The user will decide whether it's deactivated for evaluation runs.

Don't uninstall or disable apps you didn't install (e.g. `lu.uni.sdn.southbound_mac_extractor` from Lab3).

## Directory ownership

| Owner | Directories / files |
|---|---|
| Track A worker | `topology/`, `karaf/`, `scenarios/`, `harness/`, `results/`, `plans/threat-model.md` |
| Track B worker | `gateway/` |
| Auditors | read only on project files; scratch work goes in `/tmp/claude-1000/-home-jgaspar-Downloads-SDN-Project/e06714fc-8a48-4812-8312-2954380ccb18/scratchpad/audit/` |

Never edit another owner's files. If you need a change there, say so in your report.

## Shared contracts

### `topology/tenants.json`

Track A produces it. Track B reads it and must not change its shape on its own. Schema:

```json
{
  "tenants": {
    "t1": {
      "subnets":   ["10.0.1.0/24"],
      "hosts": [
        {"name": "h1a", "mac": "00:00:00:00:01:01", "ip": "10.0.1.1", "location": "of:0000000000000002/1"},
        {"name": "h1b", "mac": "00:00:00:00:01:02", "ip": "10.0.1.2", "location": "of:0000000000000003/1"}
      ],
      "edgePorts": ["of:0000000000000002/1", "of:0000000000000003/1"],
      "accounts":  {"t1-op": "operator", "t1-view": "viewer"},
      "quotas":    {}
    },
    "t2": { "...": "same shape; subnet 10.0.2.0/24, hosts h2a/h2b on port 2 of s2/s3, accounts t2-op/t2-view" }
  },
  "trunkPorts": ["<every switch port that connects two switches, as of:<dpid>/<port>>"],
  "admins": ["onos"],
  "serviceAccount": "gw-svc"
}
```

`quotas` is empty by default; Track B fills in defaults in code. The exact trunk port numbers come from the real topology script. Track A writes them out.

### Karaf accounts (password = `<user>-pw`)

| User | Karaf group | Roles |
|---|---|---|
| `t1-op`, `t1-view`, `t2-op`, `t2-view` | `tenantgroup` | `group,tenant` |
| `gw-svc` | `guestgroup` | `group,viewer` |
| `onos` (existing, password `rocks`) | `admingroup` | admin |

Config C1 ("no controls") uses an alternative overlay that puts the tenant accounts in `admingroup`.

### Gateway

App name `lu.uni.sdn.api_gateway`, web context `/onos/gw`, per-tenant ApplicationIds `lu.uni.sdn.gw.<tenantId>`.

## Step protocol (workers)

1. Do **one step** of your track. Never start the next step until you're told to continue.
2. Verify it yourself against that step's "Done when" / "Test" lines in the plan.
3. End your turn with a report in this format:

```
## Step <id>: <name>, READY FOR AUDIT | BLOCKED
### Files changed
### What I did (short)
### Evidence (commands + verbatim output)
### Deviations from plan (and why)
### UNVERIFIED / open issues
### How an auditor can reproduce the checks
```

4. An auditor will check your work. You may get fix requests. Do them, then report again.

## Audit protocol (auditors)

You know the full project scope (definition + all plans). For the step under audit:

- **Re-run the checks yourself.** Don't trust the worker's evidence; reproduce it. Build it, deploy it if needed (holding the lock), and run the tests.
- Check against the plan's "Done when" / "Test" lines, the shared contracts above, and the definition's objectives.
- Look for: claims without evidence, invented APIs, gaps the tests don't cover, contract violations, things that will break later steps, and security holes (e.g. a path that skips the gateway).
- Don't modify project files. Scratch tests go in your scratchpad audit dir.
- Report a verdict: **PASS**, **PASS WITH NOTES**, or **FAIL**, with numbered findings. For each finding give severity (blocker/major/minor), evidence, and the required fix.
