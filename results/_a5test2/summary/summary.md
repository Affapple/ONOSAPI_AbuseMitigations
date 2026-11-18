# Evaluation summary

From `/home/jgaspar/Downloads/SDN/Project/results/_a5test2`, valid runs only; CPU in % of one core, memory in MiB, latency of 2xx responses in ms.

## resources

| config | workload | runs | skipped | cpu_mean | cpu_attack_mean | cpu_peak | mem_peak_mib | heap_peak_mib | flows_peak | device_flows_peak | core_peak | intent_peak | tenant_peak | other_peak |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| C1 | S0 | 1 | 0 | 17.5 | 16.1 | 65.4 | 596.0 | 194.7 | 21 | 7 | 9 | 12 | 0 | 0 |
| C3 | S0 | 1 | 0 | 18.5 | 15.7 | 84.4 | 573.5 | 182.9 | 21 | 7 | 9 | 12 | 0 | 0 |

## latency

| config | workload | csv | op | window | requests | success | p50_ms | p95_ms | p99_ms | rejections |
|---|---|---|---|---|---|---|---|---|---|---|
| C1 | S0 | L | all | pre | 65 | 1.0 | 25.8 | 49.6 | 91.3 |  |
| C1 | S0 | L | all | attack | 128 | 1.0 | 17.9 | 50.6 | 75.4 |  |
| C1 | S0 | L | all | post | 64 | 1.0 | 13.9 | 42.2 | 53.6 |  |
| C3 | S0 | L | all | pre | 65 | 1.0 | 23.9 | 79.2 | 150.0 |  |
| C3 | S0 | L | all | attack | 128 | 1.0 | 18.9 | 48.2 | 62.3 |  |
| C3 | S0 | L | all | post | 64 | 1.0 | 18.4 | 42.5 | 60.9 |  |
| C1 | S0 | L | delete_intent | pre | 2 | 1.0 | 84.0 | 114.8 | 117.5 |  |
| C1 | S0 | L | delete_intent | attack | 4 | 1.0 | 54.8 | 66.4 | 67.7 |  |
| C1 | S0 | L | delete_intent | post | 2 | 1.0 | 47.6 | 57.7 | 58.6 |  |
| C3 | S0 | L | delete_intent | pre | 2 | 1.0 | 96.1 | 141.6 | 145.7 |  |
| C3 | S0 | L | delete_intent | attack | 4 | 1.0 | 52.0 | 61.8 | 62.2 |  |
| C3 | S0 | L | delete_intent | post | 2 | 1.0 | 56.2 | 61.9 | 62.4 |  |
| C1 | S0 | L | get_flows | pre | 20 | 1.0 | 23.3 | 43.1 | 50.1 |  |
| C1 | S0 | L | get_flows | attack | 40 | 1.0 | 15.4 | 38.2 | 51.1 |  |
| C1 | S0 | L | get_flows | post | 20 | 1.0 | 12.1 | 38.2 | 48.0 |  |
| C3 | S0 | L | get_flows | pre | 20 | 1.0 | 17.3 | 38.5 | 39.3 |  |
| C3 | S0 | L | get_flows | attack | 40 | 1.0 | 15.8 | 25.3 | 33.1 |  |
| C3 | S0 | L | get_flows | post | 20 | 1.0 | 15.2 | 24.2 | 31.0 |  |
| C1 | S0 | L | get_hosts | pre | 20 | 1.0 | 33.6 | 46.2 | 46.4 |  |
| C1 | S0 | L | get_hosts | attack | 40 | 1.0 | 25.1 | 46.5 | 91.6 |  |
| C1 | S0 | L | get_hosts | post | 20 | 1.0 | 18.3 | 32.6 | 34.1 |  |
| C3 | S0 | L | get_hosts | pre | 20 | 1.0 | 43.6 | 84.3 | 141.5 |  |
| C3 | S0 | L | get_hosts | attack | 40 | 1.0 | 31.4 | 54.9 | 66.5 |  |
| C3 | S0 | L | get_hosts | post | 20 | 1.0 | 30.1 | 43.4 | 56.7 |  |
| C1 | S0 | L | get_intents | pre | 20 | 1.0 | 20.2 | 34.8 | 35.2 |  |
| C1 | S0 | L | get_intents | attack | 40 | 1.0 | 16.9 | 38.6 | 48.3 |  |
| C1 | S0 | L | get_intents | post | 20 | 1.0 | 11.6 | 30.0 | 42.9 |  |
| C3 | S0 | L | get_intents | pre | 20 | 1.0 | 20.4 | 34.9 | 35.2 |  |
| C3 | S0 | L | get_intents | attack | 40 | 1.0 | 14.0 | 23.6 | 25.7 |  |
| C3 | S0 | L | get_intents | post | 20 | 1.0 | 14.9 | 21.0 | 21.7 |  |
| C1 | S0 | L | post_intent | pre | 3 | 1.0 | 49.0 | 73.4 | 75.6 |  |
| C1 | S0 | L | post_intent | attack | 4 | 1.0 | 11.2 | 37.5 | 40.9 |  |
| C1 | S0 | L | post_intent | post | 2 | 1.0 | 29.0 | 41.6 | 42.7 |  |
| C3 | S0 | L | post_intent | pre | 3 | 1.0 | 72.0 | 100.2 | 102.7 |  |
| C3 | S0 | L | post_intent | attack | 4 | 1.0 | 20.0 | 32.1 | 32.5 |  |
| C3 | S0 | L | post_intent | post | 2 | 1.0 | 18.6 | 20.5 | 20.7 |  |
| C1 | S0 | L-install | all | pre | 5 | 1.0 | 169.1 | 217.6 | 226.9 |  |
| C1 | S0 | L-install | all | attack | 8 | 1.0 | 105.4 | 160.0 | 168.6 |  |
| C1 | S0 | L-install | all | post | 4 | 1.0 | 118.9 | 139.3 | 141.1 |  |
| C3 | S0 | L-install | all | pre | 5 | 1.0 | 181.7 | 193.2 | 195.4 |  |
| C3 | S0 | L-install | all | attack | 8 | 1.0 | 107.8 | 154.0 | 159.4 |  |
| C3 | S0 | L-install | all | post | 4 | 1.0 | 77.9 | 110.6 | 115.2 |  |
| C1 | S0 | L-install | install | pre | 3 | 1.0 | 149.3 | 168.9 | 170.7 |  |
| C1 | S0 | L-install | install | attack | 4 | 1.0 | 99.5 | 134.4 | 138.8 |  |
| C1 | S0 | L-install | install | post | 2 | 1.0 | 118.9 | 126.3 | 126.9 |  |
| C3 | S0 | L-install | install | pre | 3 | 1.0 | 181.7 | 194.5 | 195.6 |  |
| C3 | S0 | L-install | install | attack | 4 | 1.0 | 118.2 | 140.1 | 141.3 |  |
| C3 | S0 | L-install | install | post | 2 | 1.0 | 68.6 | 76.5 | 77.2 |  |
| C1 | S0 | L-install | remove | pre | 2 | 1.0 | 199.1 | 226.2 | 228.6 |  |
| C1 | S0 | L-install | remove | attack | 4 | 1.0 | 120.5 | 165.2 | 169.7 |  |
| C1 | S0 | L-install | remove | post | 2 | 1.0 | 117.2 | 139.1 | 141.0 |  |
| C3 | S0 | L-install | remove | pre | 2 | 1.0 | 168.5 | 181.1 | 182.2 |  |
| C3 | S0 | L-install | remove | attack | 4 | 1.0 | 98.2 | 153.2 | 159.2 |  |
| C3 | S0 | L-install | remove | post | 2 | 1.0 | 97.3 | 114.4 | 115.9 |  |

## ping

| config | workload | csv | window | pings | loss_pct | rtt_p50_ms | rtt_p95_ms |
|---|---|---|---|---|---|---|---|
| C1 | S0 | L-ping | pre | 58 | 0.0 | 0.105 | 0.186 |
| C1 | S0 | L-ping | attack | 117 | 0.0 | 0.104 | 0.143 |
| C1 | S0 | L-ping | post | 59 | 0.0 | 0.086 | 0.139 |
| C3 | S0 | L-ping | pre | 58 | 0.0 | 0.101 | 0.141 |
| C3 | S0 | L-ping | attack | 117 | 0.0 | 0.099 | 0.124 |
| C3 | S0 | L-ping | post | 59 | 0.0 | 0.101 | 0.133 |
