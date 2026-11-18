# Harness (step A5)

Laptop: `harness/deploy.sh` (writes build-info.json, copies harness/ + scenarios/ to the VM),
`harness/fetch.sh` (rsync the VM's results/ back), `python3 harness/aggregate.py` (results/summary/).
VM: `harness/run.sh <config> <workload|baseline> <rep>`, `harness/matrix.sh`, `harness/overhead.py`.

| File | Does |
|---|---|
| `config.sh` | applies C1/C2/C3/C3F: Karaf overlay + ONOS restart + MATCH, purge, all 36 gateway properties set and read back, unblock, MATCH |
| `run.sh` | one 120 s run under the lock: L for -30..120 s, named workload 30..90 s, collectors, meta.json, restore (tenant overlay forced, then C3) |
| `matrix.sh` | loops run.sh over CONFIGS x WORKLOADS_TO_RUN x REPS; resumable (run.sh skips valid runs) |
| `collect.py` | docker.csv (1 s), heap.csv (1 s), flows.csv (5 s) |
| `onosctl.py` | REST helpers used by the scripts above |
| `aggregate.py` | per config x workload tables (laptop) |
| `overhead.py` | collectors' own CPU cost, measured once |
| `workloads.conf` | the evaluation's workload commands (L + S1..S4, commented template) |
| `workloads.example.conf` | L + a `sleep` placeholder S0, used to test the harness |

A run is invalid (`meta.json` `invalidReasons`) if: config.sh fails; a workload exits non-zero
or overruns its window by 30 s; ONOS CPU did not settle before t0; a collector dies; docker.csv
or heap.csv < 110 rows, flows.csv < 12 polls; a workload whose command contains its `{out}` wrote
no CSV or an empty one (a command without `{out}`, like `S0 = sleep`, is a placeholder); L.csv has rows with
reason `infra_401` or `conn_error` (gateway/ONOS down); the gateway (.oar md5, version, state) or
the ONOS container start time changed. A named workload ending > 5 s after t0+90 is a warning.
config.sh and the restore run in their own session, so a Ctrl-C reaches only run.sh, which
lets them finish; its cleanup ignores further INT/TERM and forces the tenant overlay.

**Settle gate** (instead of a fixed wait): after config.sh, ONOS container CPU is sampled every
1 s; t0 comes once the last 10 s mean is below SETTLE_CPU (8 %) and within 20 % (at least 1
point) of the 10 s before, at most SETTLE_MAX (180 s). meta.json records it (`settle`) and the
VM's load over the run (`hostLoad`: load average at t0 and at the end, VM busy % and steal %),
so external load on the VM/host shows up per run. L then starts LEAD (30) s before t0: its
setup (base intent install, ping start) and ONOS's warm-up of L's request paths would otherwise
dominate the first 10-30 s of the measured window (seen in the A5 test runs).

**L's install/remove latency** comes from admin polls of the intent state: every 50 ms for the
first second, then every 250 ms (resolution ~50 ms below 1 s, ~250 ms above).

Run directory `results/<config>/<workload>/<rep>/`: `meta.json` (valid flag + reasons, timestamps,
exit codes, commands, read-back gateway config, settle, hostLoad, gateway version + .oar md5 and app states before
and after, ONOS container start, build-info: git commit, dirty flag, laptop .oar md5),
`timeline.txt`, `docker.csv`, `heap.csv`, `flows.csv`, `<workload>.csv`, `L.csv`, `L-install.csv`, `L-ping.csv`,
`tenants.json` (gateway per-tenant state at the end), `*.log`.

Sources, as checked on the VM (2026-11-18):
- **CPU/memory**: the container's cgroup v1 files. CPU = cpuacct.usage delta / wall time, in
  % of one core (the VM has 1 vCPU). Memory = usage - total_inactive_file, as `docker stats`
  (side by side: 650620928 B = 620.5 MiB in both). `docker stats` divides CPU by /proc/stat ticks;
  those run at ~94/s instead of 100/s here, and they credit most busy time to idle (10 s sample:
  container 0.40 s CPU by cpuacct, VM user+system 0.04 s by /proc/stat). So the VM's own busy %
  (meta.json `hostLoad.vmBusyPct`) comes from the root cgroup's cpuacct.usage; steal % from
  /proc/stat is tick-based, treat it as a rough indicator only. `docker stats --no-stream` itself
  takes ~2 s per call, so it cannot sample at 1 s.
- **JVM heap**: `GET /onos/v1/system` `mem` = heap used/committed/max. Checked against
  `jcmd GC.heap_info` in the container: committed 238026752 B = 232448K, used within 1 MiB.
  `jstat`/`jcmd` are not used during runs: they start a JVM per call and a `jstat` once hung
  for 22 min while holding the lock. Every `docker` call in the harness runs under `timeout 10`.
- **Flows**: `GET /onos/v1/flows` (admin). Intent-compiled flows carry appId
  `org.onosproject.net.intent`; aggregate.py classes appIds as core / intent / tenant
  (`lu.uni.sdn.gw.*`) / other.

Constraints carried over from A1/A2: run `mnctl.sh discover` (MATCH) after every ONOS restart or
purge (config.sh does); `karaf/apply.sh` takes the lock itself unless `SDN_LOCK_HELD=1`; keep
the Lab3 app deactivated (its state is recorded in meta.json `state.*.apps`).
