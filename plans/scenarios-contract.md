# Scenario Script Contract (A4)

The user writes S1–S4. A Track A agent writes L (legitimate workload) and the harness (A5). Every script follows this contract, so the harness can run any of them the same way in all three configs.

## Location and invocation

```
scenarios/s1_flood.py   scenarios/s2_churn.py   scenarios/s3_semantic.py
scenarios/s4_cross_tenant.py   scenarios/l_legit.py   scenarios/common.py  (shared helpers, optional)
```

The scripts run **on the VM** with `python3` (3.6, Ubuntu 18.04: no f-string `=` debugging, no walrus), using the standard library only (`urllib.request`, `json`, `csv`, `time`, `argparse`, `threading`).

Every script accepts:

| Flag | Default | Meaning |
|---|---|---|
| `--target v1\|gw` | required | `v1` = call `/onos/v1/...` directly (config C1); `gw` = call `/onos/gw/...` (C2/C3) |
| `--user`, `--password` | `t1-op` / `t1-op-pw` (S1–S4), `t2-op` / `t2-op-pw` (L) | tenant account |
| `--base-url` | `http://localhost:8181` | ONOS REST |
| `--rate` | script-specific | requests per second (0 = as fast as possible, single thread) |
| `--duration` | `60` | seconds to run |
| `--variant` | `all` | S3/S4 only: which payload variant to run |
| `--out` | required | CSV file to write |

Exit code: 0 = ran and cleaned up; non-zero = setup or cleanup failure (the harness aborts the run).

## CSV format (one row per request)

```
ts,scenario,variant,tenant,target,method,path,status,reason,latency_ms,items
```

- `ts`: epoch seconds (float) when the request was sent
- `scenario`: `S1`…`S4` or `L`
- `variant`: free text, `-` if none
- `tenant`: `t1` or `t2`
- `status`: HTTP code, or `0` for connection error or timeout
- `reason`: the gateway's JSON `reason` field if present, otherwise `-`
- `latency_ms`: time to the full response
- `items`: number of flows or intents in the request body (1 for GETs)

## API cheat sheet

| Operation | C1 (`--target v1`) | C2/C3 (`--target gw`) |
|---|---|---|
| Install flows | `POST /onos/v1/flows` body `{"flows":[...]}` | `POST /onos/gw/flows` same body → 200 `{appId, flows:[{deviceId, flowId}]}` |
| Delete one flow | `DELETE /onos/v1/flows/{deviceId}/{flowId}` | `DELETE /onos/gw/flows/{deviceId}/{flowId}` |
| Submit intent | `POST /onos/v1/intents` (one intent, needs `appId`) → 201 | `POST /on