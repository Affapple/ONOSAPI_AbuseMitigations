skipped /home/jgaspar/Downloads/SDN/Project/results/C2/S2/2: ['S2 overran, killed', 'S2 exited 124']
# Evaluation summary

From `/home/jgaspar/Downloads/SDN/Project/results`, valid runs only; CPU in % of one core, memory in MiB, latency of 2xx responses in ms.

## resources

| config | workload | runs | skipped | cpu_mean | cpu_attack_mean | cpu_peak | mem_peak_mib | heap_peak_mib | flows_peak | device_flows_peak | core_peak | intent_peak | tenant_peak | other_peak |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| C1 | S1 | 2 | 0 | 44.3 | 75.0 | 86.4 | 641.2 | 215.0 | 31 | 17 | 9 | 12 | 10 | 0 |
| C2 | S1 | 2 | 0 | 58.9 | 85.3 | 90.5 | 611.8 | 196.0 | 31 | 17 | 9 | 12 | 10 | 0 |
| C3 | S1 | 2 | 0 | 32.1 | 54.0 | 84.9 | 559.0 | 151.6 | 21 | 7 | 9 | 12 | 0 | 0 |
| C1 | S2 | 2 | 0 | 42.5 | 73.5 | 86.8 | 642.3 | 165.2 | 41 | 27 | 9 | 12 | 20 | 0 |
| C2 | S2 | 1 | 1 | 63.7 | 92.5 | 96.4 | 641.4 | 207.0 | 41 | 27 | 9 | 12 | 20 | 0 |
| C3 | S2 | 2 | 0 | 34.4 | 57.6 | 80.5 | 547.5 | 138.4 | 40 | 26 | 9 | 12 | 19 | 0 |
| C1 | S3 | 2 | 0 | 21.9 | 30.9 | 82.6 | 520.1 | 139.5 | 31 | 11 | 9 | 21 | 1 | 0 |
| C2 | S3 | 2 | 0 | 24.5 | 32.5 | 90.2 | 534.1 | 137.4 | 31 | 11 | 9 | 21 | 2 | 0 |
| C3 | S3 | 2 | 0 | 18.1 | 22.2 | 92.8 | 518.0 | 134.3 | 21 | 7 | 9 | 12 | 0 | 0 |
| C1 | S4 | 2 | 0 | 12.6 | 14.4 | 50.6 | 528.5 | 146.8 | 24 | 8 | 9 | 15 | 1 | 0 |
| C2 | S4 | 2 | 0 | 12.8 | 13.2 | 38.2 | 528.4 | 139.1 | 22 | 8 | 9 | 12 | 1 | 0 |
| C3 | S4 | 2 | 0 | 13.3 | 14.3 | 52.2 | 532.7 | 147.3 | 21 | 7 | 9 | 12 | 0 | 0 |
| C1 | baseline | 3 | 0 | 10.3 | 10.2 | 50.1 | 530.2 | 144.1 | 21 | 7 | 9 | 12 | 0 | 0 |
| C2 | baseline | 2 | 0 | 11.6 | 11.7 | 39.1 | 547.8 | 148.3 | 21 | 7 | 9 | 12 | 0 | 0 |
| C3 | baseline | 2 | 0 | 12.7 | 12.8 | 71.3 | 535.2 | 148.8 | 21 | 7 | 9 | 12 | 0 | 0 |

## latency

| config | workload | csv | op | window | requests | success | p50_ms | p95_ms | p99_ms | rejections |
|---|---|---|---|---|---|---|---|---|---|---|
| C1 | S1 | L | all | pre | 128 | 1.0 | 21.6 | 44.7 | 53.6 |  |
| C1 | S1 | L | all | attack | 256 | 1.0 | 12.6 | 35.6 | 55.7 |  |
| C1 | S1 | L | all | post | 128 | 1.0 | 11.4 | 30.1 | 47.4 |  |
| C2 | S1 | L | all | pre | 128 | 1.0 | 16.9 | 54.8 | 73.0 |  |
| C2 | S1 | L | all | attack | 256 | 1.0 | 17.5 | 40.1 | 76.4 |  |
| C2 | S1 | L | all | post | 128 | 1.0 | 8.6 | 33.4 | 40.5 |  |
| C3 | S1 | L | all | pre | 128 | 1.0 | 16.5 | 43.5 | 58.5 |  |
| C3 | S1 | L | all | attack | 256 | 1.0 | 12.1 | 41.4 | 63.9 |  |
| C3 | S1 | L | all | post | 128 | 1.0 | 9.6 | 33.8 | 43.6 |  |
| C1 | S1 | L | delete_intent | pre | 4 | 1.0 | 44.4 | 51.2 | 51.9 |  |
| C1 | S1 | L | delete_intent | attack | 8 | 1.0 | 42.7 | 59.3 | 60.7 |  |
| C1 | S1 | L | delete_intent | post | 4 | 1.0 | 46.5 | 55.1 | 56.1 |  |
| C2 | S1 | L | delete_intent | pre | 4 | 1.0 | 45.9 | 78.7 | 81.5 |  |
| C2 | S1 | L | delete_intent | attack | 8 | 1.0 | 43.8 | 79.3 | 80.6 |  |
| C2 | S1 | L | delete_intent | post | 4 | 1.0 | 34.6 | 41.2 | 41.9 |  |
| C3 | S1 | L | delete_intent | pre | 4 | 1.0 | 30.8 | 56.8 | 59.0 |  |
| C3 | S1 | L | delete_intent | attack | 8 | 1.0 | 38.2 | 48.0 | 48.2 |  |
| C3 | S1 | L | delete_intent | post | 4 | 1.0 | 37.4 | 47.6 | 48.0 |  |
| C1 | S1 | L | get_flows | pre | 40 | 1.0 | 18.2 | 38.6 | 55.2 |  |
| C1 | S1 | L | get_flows | attack | 80 | 1.0 | 11.6 | 24.1 | 37.9 |  |
| C1 | S1 | L | get_flows | post | 40 | 1.0 | 9.9 | 17.5 | 20.9 |  |
| C2 | S1 | L | get_flows | pre | 40 | 1.0 | 12.9 | 27.9 | 35.8 |  |
| C2 | S1 | L | get_flows | attack | 80 | 1.0 | 10.0 | 27.0 | 57.4 |  |
| C2 | S1 | L | get_flows | post | 40 | 1.0 | 6.6 | 21.8 | 24.2 |  |
| C3 | S1 | L | get_flows | pre | 40 | 1.0 | 15.2 | 36.6 | 52.6 |  |
| C3 | S1 | L | get_flows | attack | 80 | 1.0 | 7.8 | 23.1 | 39.0 |  |
| C3 | S1 | L | get_flows | post | 40 | 1.0 | 9.3 | 11.3 | 13.5 |  |
| C1 | S1 | L | get_hosts | pre | 40 | 1.0 | 24.0 | 40.1 | 48.4 |  |
| C1 | S1 | L | get_hosts | attack | 80 | 1.0 | 13.6 | 30.2 | 48.0 |  |
| C1 | S1 | L | get_hosts | post | 40 | 1.0 | 12.9 | 25.6 | 38.4 |  |
| C2 | S1 | L | get_hosts | pre | 40 | 1.0 | 25.7 | 56.8 | 72.3 |  |
| C2 | S1 | L | get_hosts | attack | 80 | 1.0 | 20.9 | 40.5 | 50.2 |  |
| C2 | S1 | L | get_hosts | post | 40 | 1.0 | 12.2 | 25.8 | 35.6 |  |
| C3 | S1 | L | get_hosts | pre | 40 | 1.0 | 24.6 | 49.0 | 74.9 |  |
| C3 | S1 | L | get_hosts | attack | 80 | 1.0 | 21.2 | 46.7 | 93.8 |  |
| C3 | S1 | L | get_hosts | post | 40 | 1.0 | 19.0 | 36.5 | 40.4 |  |
| C1 | S1 | L | get_intents | pre | 40 | 1.0 | 19.1 | 26.1 | 34.6 |  |
| C1 | S1 | L | get_intents | attack | 80 | 1.0 | 10.6 | 22.0 | 45.0 |  |
| C1 | S1 | L | get_intents | post | 40 | 1.0 | 9.4 | 13.9 | 30.0 |  |
| C2 | S1 | L | get_intents | pre | 40 | 1.0 | 14.9 | 30.4 | 36.2 |  |
| C2 | S1 | L | get_intents | attack | 80 | 1.0 | 16.6 | 26.7 | 37.4 |  |
| C2 | S1 | L | get_intents | post | 40 | 1.0 | 7.0 | 26.6 | 56.9 |  |
| C3 | S1 | L | get_intents | pre | 40 | 1.0 | 15.1 | 24.7 | 27.7 |  |
| C3 | S1 | L | get_intents | attack | 80 | 1.0 | 7.9 | 21.1 | 27.5 |  |
| C3 | S1 | L | get_intents | post | 40 | 1.0 | 9.2 | 11.5 | 12.4 |  |
| C1 | S1 | L | post_intent | pre | 4 | 1.0 | 29.6 | 49.6 | 51.3 |  |
| C1 | S1 | L | post_intent | attack | 8 | 1.0 | 26.1 | 47.7 | 52.2 |  |
| C1 | S1 | L | post_intent | post | 4 | 1.0 | 14.8 | 18.4 | 18.8 |  |
| C2 | S1 | L | post_intent | pre | 4 | 1.0 | 20.2 | 37.9 | 39.1 |  |
| C2 | S1 | L | post_intent | attack | 8 | 1.0 | 22.6 | 63.4 | 74.8 |  |
| C2 | S1 | L | post_intent | post | 4 | 1.0 | 11.4 | 32.3 | 34.9 |  |
| C3 | S1 | L | post_intent | pre | 4 | 1.0 | 13.6 | 27.1 | 28.1 |  |
| C3 | S1 | L | post_intent | attack | 8 | 1.0 | 19.9 | 41.5 | 46.1 |  |
| C3 | S1 | L | post_intent | post | 4 | 1.0 | 10.0 | 12.1 | 12.2 |  |
| C1 | S1 | L-install | all | pre | 8 | 1.0 | 138.8 | 156.8 | 158.3 |  |
| C1 | S1 | L-install | all | attack | 16 | 1.0 | 119.3 | 157.1 | 164.0 |  |
| C1 | S1 | L-install | all | post | 8 | 1.0 | 95.7 | 143.2 | 147.3 |  |
| C2 | S1 | L-install | all | pre | 8 | 1.0 | 124.1 | 197.2 | 215.3 |  |
| C2 | S1 | L-install | all | attack | 16 | 1.0 | 122.7 | 141.6 | 151.0 |  |
| C2 | S1 | L-install | all | post | 8 | 1.0 | 107.8 | 135.9 | 139.5 |  |
| C3 | S1 | L-install | all | pre | 8 | 1.0 | 90.1 | 142.6 | 149.6 |  |
| C3 | S1 | L-install | all | attack | 16 | 1.0 | 100.2 | 131.4 | 134.4 |  |
| C3 | S1 | L-install | all | post | 8 | 1.0 | 82.0 | 115.0 | 126.3 |  |
| C1 | S1 | L-install | install | pre | 4 | 1.0 | 122.7 | 146.2 | 149.5 |  |
| C1 | S1 | L-install | install | attack | 8 | 1.0 | 126.2 | 157.8 | 164.1 |  |
| C1 | S1 | L-install | install | post | 4 | 1.0 | 92.1 | 95.8 | 95.8 |  |
| C2 | S1 | L-install | install | pre | 4 | 1.0 | 109.7 | 134.8 | 135.1 |  |
| C2 | S1 | L-install | install | attack | 8 | 1.0 | 117.5 | 136.8 | 137.4 |  |
| C2 | S1 | L-install | install | post | 4 | 1.0 | 102.3 | 138.5 | 140.0 |  |
| C3 | S1 | L-install | install | pre | 4 | 1.0 | 102.2 | 124.8 | 126.0 |  |
| C3 | S1 | L-install | install | attack | 8 | 1.0 | 104.8 | 133.4 | 134.8 |  |
| C3 | S1 | L-install | install | post | 4 | 1.0 | 73.2 | 86.2 | 87.4 |  |
| C1 | S1 | L-install | remove | pre | 4 | 1.0 | 147.5 | 157.9 | 158.5 |  |
| C1 | S1 | L-install | remove | attack | 8 | 1.0 | 112.8 | 154.1 | 154.2 |  |
| C1 | S1 | L-install | remove | post | 4 | 1.0 | 123.2 | 146.1 | 147.9 |  |
| C2 | S1 | L-install | remove | pre | 4 | 1.0 | 135.4 | 210.1 | 217.9 |  |
| C2 | S1 | L-install | remove | attack | 8 | 1.0 | 123.3 | 144.9 | 151.7 |  |
| C2 | S1 | L-install | remove | post | 4 | 1.0 | 107.8 | 124.1 | 126.0 |  |
| C3 | S1 | L-install | remove | pre | 4 | 1.0 | 88.1 | 142.5 | 149.6 |  |
| C3 | S1 | L-install | remove | attack | 8 | 1.0 | 90.7 | 122.6 | 123.6 |  |
| C3 | S1 | L-install | remove | post | 4 | 1.0 | 87.7 | 123.1 | 127.9 |  |
| C1 | S1 | S1 | all | attack | 11177 | 1.0 | 7.6 | 26.2 | 38.5 |  |
| C1 | S1 | S1 | all | post | 34 | 1.0 | 8.6 | 19.9 | 27.3 |  |
| C2 | S1 | S1 | all | attack | 7770 | 1.0 | 6.1 | 78.2 | 147.9 |  |
| C2 | S1 | S1 | all | post | 12093 | 0.007 | 8.6 | 19.1 | 45.1 | 404:not_found=12004 |
| C3 | S1 | S1 | all | attack | 23232 | 0.007 | 14.1 | 36.1 | 70.6 | 403:throttled_blocked=22912 404:not_found=6 429:rate_limited=110 429:throttled_cooldown=51 |
| C3 | S1 | S1 | all | post | 56 | 0.0 |  |  |  | 403:throttled_blocked=56 |
| C2 | S1 | S1 | delete_flow | post | 12000 | 0.0 |  |  |  | 404:not_found=12000 |
| C3 | S1 | S1 | delete_flow | attack | 76 | 0.132 | 12.8 | 27.1 | 34.1 | 403:throttled_blocked=3 404:not_found=6 429:rate_limited=15 429:throttled_cooldown=42 |
| C1 | S1 | S1 | delete_flows | attack | 932 | 1.0 | 11.7 | 31.4 | 46.4 |  |
| C1 | S1 | S1 | delete_flows | post | 5 | 1.0 | 11.9 | 28.2 | 29.4 |  |
| C2 | S1 | S1 | delete_flows | attack | 599 | 1.0 | 96.5 | 180.6 | 241.5 |  |
| C2 | S1 | S1 | delete_flows | post | 5 | 0.2 | 165.0 | 165.0 | 165.0 | 404:not_found=4 |
| C3 | S1 | S1 | delete_flows | attack | 14 | 0.071 | 41.8 | 41.8 | 41.8 | 403:throttled_blocked=3 429:rate_limited=4 429:throttled_cooldown=6 |
| C1 | S1 | S1 | get_devices | attack | 1552 | 1.0 | 7.6 | 27.2 | 34.6 |  |
| C1 | S1 | S1 | get_devices | post | 5 | 1.0 | 11.3 | 16.3 | 16.3 |  |
| C2 | S1 | S1 | get_devices | attack | 996 | 1.0 | 6.7 | 22.8 | 33.9 |  |
| C2 | S1 | S1 | get_devices | post | 1 | 1.0 | 4.4 | 4.4 | 4.4 |  |
| C3 | S1 | S1 | get_devices | attack | 3473 | 0.0 | 30.8 | 30.8 | 30.8 | 403:throttled_blocked=3465 429:rate_limited=7 |
| C3 | S1 | S1 | get_devices | post | 9 | 0.0 |  |  |  | 403:throttled_blocked=9 |
| C1 | S1 | S1 | get_flows | attack | 1553 | 1.0 | 8.7 | 29.0 | 39.7 |  |
| C1 | S1 | S1 | get_flows | post | 4 | 1.0 | 8.1 | 15.2 | 16.2 |  |
| C2 | S1 | S1 | get_flows | attack | 1595 | 1.0 | 4.6 | 19.7 | 29.0 |  |
| C2 | S1 | S1 | get_flows | post | 79 | 1.0 | 8.7 | 17.9 | 21.4 |  |
| C3 | S1 | S1 | get_flows | attack | 3690 | 0.035 | 13.6 | 31.3 | 63.6 | 403:throttled_blocked=3502 429:rate_limited=60 |
| C3 | S1 | S1 | get_flows | post | 8 | 0.0 |  |  |  | 403:throttled_blocked=8 |
| C1 | S1 | S1 | get_hosts | attack | 1551 | 1.0 | 8.1 | 25.8 | 36.8 |  |
| C1 | S1 | S1 | get_hosts | post | 4 | 1.0 | 8.8 | 9.8 | 9.9 |  |
| C2 | S1 | S1 | get_hosts | attack | 994 | 1.0 | 7.3 | 21.8 | 31.6 |  |
| C2 | S1 | S1 | get_hosts | post | 3 | 1.0 | 14.2 | 27.3 | 28.5 |  |
| C3 | S1 | S1 | get_hosts | attack | 3473 | 0.001 | 11.3 | 25.8 | 27.1 | 403:throttled_blocked=3465 429:rate_limited=5 |
| C3 | S1 | S1 | get_hosts | post | 8 | 0.0 |  |  |  | 403:throttled_blocked=8 |
| C1 | S1 | S1 | get_intents | attack | 1553 | 1.0 | 6.3 | 22.1 | 38.2 |  |
| C1 | S1 | S1 | get_intents | post | 4 | 1.0 | 6.5 | 13.9 | 14.9 |  |
| C2 | S1 | S1 | get_intents | attack | 996 | 1.0 | 3.6 | 17.0 | 20.9 |  |
| C2 | S1 | S1 | get_intents | post | 1 | 1.0 | 3.4 | 3.4 | 3.4 |  |
| C3 | S1 | S1 | get_intents | attack | 3475 | 0.001 | 15.8 | 18.6 | 18.8 | 403:throttled_blocked=3466 429:rate_limited=6 |
| C3 | S1 | S1 | get_intents | post | 8 | 0.0 |  |  |  | 403:throttled_blocked=8 |
| C1 | S1 | S1 | get_links | attack | 1552 | 1.0 | 6.3 | 20.6 | 31.1 |  |
| C1 | S1 | S1 | get_links | post | 5 | 1.0 | 5.2 | 17.9 | 18.4 |  |
| C2 | S1 | S1 | get_links | attack | 996 | 1.0 | 5.7 | 20.5 | 28.9 |  |
| C2 | S1 | S1 | get_links | post | 1 | 1.0 | 4.4 | 4.4 | 4.4 |  |
| C3 | S1 | S1 | get_links | attack | 3473 | 0.0 | 22.4 | 22.4 | 22.4 | 403:throttled_blocked=3465 429:rate_limited=7 |
| C3 | S1 | S1 | get_links | post | 9 | 0.0 |  |  |  | 403:throttled_blocked=9 |
| C1 | S1 | S1 | get_topology | attack | 1551 | 1.0 | 6.0 | 19.7 | 27.6 |  |
| C1 | S1 | S1 | get_topology | post | 5 | 1.0 | 6.5 | 11.1 | 11.6 |  |
| C2 | S1 | S1 | get_topology | attack | 995 | 1.0 | 5.4 | 20.8 | 30.2 |  |
| C2 | S1 | S1 | get_topology | post | 2 | 1.0 | 10.5 | 16.4 | 17.0 |  |
| C3 | S1 | S1 | get_topology | attack | 3473 | 0.001 | 22.1 | 43.6 | 46.1 | 403:throttled_blocked=3465 429:rate_limited=4 |
| C3 | S1 | S1 | get_topology | post | 9 | 0.0 |  |  |  | 403:throttled_blocked=9 |
| C1 | S1 | S1 | post_batch | attack | 933 | 1.0 | 10.3 | 30.6 | 46.4 |  |
| C1 | S1 | S1 | post_batch | post | 2 | 1.0 | 14.5 | 17.7 | 18.0 |  |
| C2 | S1 | S1 | post_batch | attack | 599 | 1.0 | 6.3 | 24.4 | 46.2 |  |
| C2 | S1 | S1 | post_batch | post | 1 | 1.0 | 4.9 | 4.9 | 4.9 |  |
| C3 | S1 | S1 | post_batch | attack | 2085 | 0.001 | 51.0 | 76.1 | 78.3 | 403:throttled_blocked=2078 429:rate_limited=2 429:throttled_cooldown=3 |
| C3 | S1 | S1 | post_batch | post | 5 | 0.0 |  |  |  | 403:throttled_blocked=5 |
| C1 | S2 | L | all | pre | 128 | 1.0 | 16.6 | 41.7 | 67.7 |  |
| C1 | S2 | L | all | attack | 256 | 1.0 | 13.3 | 40.6 | 47.7 |  |
| C1 | S2 | L | all | post | 128 | 1.0 | 10.0 | 26.7 | 40.9 |  |
| C2 | S2 | L | all | pre | 64 | 1.0 | 14.4 | 30.7 | 39.3 |  |
| C2 | S2 | L | all | attack | 128 | 1.0 | 19.5 | 50.6 | 67.3 |  |
| C2 | S2 | L | all | post | 64 | 1.0 | 7.2 | 24.3 | 44.1 |  |
| C3 | S2 | L | all | pre | 128 | 1.0 | 15.8 | 41.5 | 57.4 |  |
| C3 | S2 | L | all | attack | 256 | 1.0 | 10.6 | 40.1 | 57.7 |  |
| C3 | S2 | L | all | post | 128 | 1.0 | 9.6 | 33.2 | 53.0 |  |
| C1 | S2 | L | delete_intent | pre | 4 | 1.0 | 65.3 | 75.1 | 75.7 |  |
| C1 | S2 | L | delete_intent | attack | 8 | 1.0 | 42.0 | 55.7 | 58.8 |  |
| C1 | S2 | L | delete_intent | post | 4 | 1.0 | 39.5 | 43.9 | 44.2 |  |
| C2 | S2 | L | delete_intent | pre | 2 | 1.0 | 39.4 | 39.7 | 39.7 |  |
| C2 | S2 | L | delete_intent | attack | 4 | 1.0 | 39.3 | 50.8 | 52.0 |  |
| C2 | S2 | L | delete_intent | post | 2 | 1.0 | 31.6 | 34.8 | 35.1 |  |
| C3 | S2 | L | delete_intent | pre | 4 | 1.0 | 49.7 | 68.3 | 69.7 |  |
| C3 | S2 | L | delete_intent | attack | 8 | 1.0 | 50.0 | 77.2 | 84.3 |  |
| C3 | S2 | L | delete_intent | post | 4 | 1.0 | 50.8 | 66.7 | 68.3 |  |
| C1 | S2 | L | get_flows | pre | 40 | 1.0 | 14.3 | 28.1 | 41.0 |  |
| C1 | S2 | L | get_flows | attack | 80 | 1.0 | 10.3 | 25.1 | 32.5 |  |
| C1 | S2 | L | get_flows | post | 40 | 1.0 | 9.6 | 17.8 | 19.2 |  |
| C2 | S2 | L | get_flows | pre | 20 | 1.0 | 11.6 | 20.4 | 20.7 |  |
| C2 | S2 | L | get_flows | attack | 40 | 1.0 | 19.1 | 51.0 | 63.0 |  |
| C2 | S2 | L | get_flows | post | 20 | 1.0 | 5.6 | 7.6 | 7.7 |  |
| C3 | S2 | L | get_flows | pre | 40 | 1.0 | 13.8 | 20.3 | 21.9 |  |
| C3 | S2 | L | get_flows | attack | 80 | 1.0 | 8.5 | 19.5 | 25.1 |  |
| C3 | S2 | L | get_flows | post | 40 | 1.0 | 9.1 | 11.8 | 17.1 |  |
| C1 | S2 | L | get_hosts | pre | 40 | 1.0 | 21.5 | 41.2 | 42.0 |  |
| C1 | S2 | L | get_hosts | attack | 80 | 1.0 | 17.6 | 41.3 | 44.8 |  |
| C1 | S2 | L | get_hosts | post | 40 | 1.0 | 13.9 | 28.8 | 34.9 |  |
| C2 | S2 | L | get_hosts | pre | 20 | 1.0 | 24.1 | 30.9 | 31.0 |  |
| C2 | S2 | L | get_hosts | attack | 40 | 1.0 | 24.2 | 39.0 | 42.5 |  |
| C2 | S2 | L | get_hosts | post | 20 | 1.0 | 12.6 | 26.1 | 52.6 |  |
| C3 | S2 | L | get_hosts | pre | 40 | 1.0 | 25.6 | 48.1 | 52.4 |  |
| C3 | S2 | L | get_hosts | attack | 80 | 1.0 | 22.2 | 42.0 | 53.3 |  |
| C3 | S2 | L | get_hosts | post | 40 | 1.0 | 19.0 | 31.3 | 41.0 |  |
| C1 | S2 | L | get_intents | pre | 40 | 1.0 | 14.9 | 22.8 | 32.2 |  |
| C1 | S2 | L | get_intents | attack | 80 | 1.0 | 12.3 | 24.8 | 38.9 |  |
| C1 | S2 | L | get_intents | post | 40 | 1.0 | 9.6 | 19.0 | 22.8 |  |
| C2 | S2 | L | get_intents | pre | 20 | 1.0 | 13.5 | 20.9 | 23.1 |  |
| C2 | S2 | L | get_intents | attack | 40 | 1.0 | 18.9 | 59.3 | 87.4 |  |
| C2 | S2 | L | get_intents | post | 20 | 1.0 | 5.8 | 12.2 | 18.5 |  |
| C3 | S2 | L | get_intents | pre | 40 | 1.0 | 13.2 | 26.8 | 37.9 |  |
| C3 | S2 | L | get_intents | attack | 80 | 1.0 | 7.2 | 17.0 | 20.2 |  |
| C3 | S2 | L | get_intents | post | 40 | 1.0 | 8.9 | 11.1 | 11.9 |  |
| C1 | S2 | L | post_intent | pre | 4 | 1.0 | 23.1 | 30.9 | 31.8 |  |
| C1 | S2 | L | post_intent | attack | 8 | 1.0 | 31.8 | 46.4 | 46.9 |  |
| C1 | S2 | L | post_intent | post | 4 | 1.0 | 9.0 | 13.2 | 13.4 |  |
| C2 | S2 | L | post_intent | pre | 2 | 1.0 | 11.4 | 12.8 | 12.9 |  |
| C2 | S2 | L | post_intent | attack | 4 | 1.0 | 10.4 | 36.0 | 39.2 |  |
| C2 | S2 | L | post_intent | post | 2 | 1.0 | 5.0 | 5.2 | 5.2 |  |
| C3 | S2 | L | post_intent | pre | 4 | 1.0 | 13.8 | 26.4 | 27.6 |  |
| C3 | S2 | L | post_intent | attack | 8 | 1.0 | 17.4 | 40.3 | 42.1 |  |
| C3 | S2 | L | post_intent | post | 4 | 1.0 | 11.6 | 38.3 | 41.3 |  |
| C1 | S2 | L-install | all | pre | 8 | 1.0 | 126.2 | 214.1 | 235.0 |  |
| C1 | S2 | L-install | all | attack | 16 | 1.0 | 116.9 | 159.1 | 163.7 |  |
| C1 | S2 | L-install | all | post | 8 | 1.0 | 93.3 | 123.6 | 127.9 |  |
| C2 | S2 | L-install | all | pre | 4 | 1.0 | 104.9 | 120.5 | 121.9 |  |
| C2 | S2 | L-install | all | attack | 8 | 1.0 | 100.8 | 131.0 | 133.7 |  |
| C2 | S2 | L-install | all | post | 4 | 1.0 | 95.3 | 104.3 | 105.0 |  |
| C3 | S2 | L-install | all | pre | 8 | 1.0 | 108.3 | 157.2 | 165.2 |  |
| C3 | S2 | L-install | all | attack | 16 | 1.0 | 104.0 | 130.7 | 137.2 |  |
| C3 | S2 | L-install | all | post | 8 | 1.0 | 108.7 | 133.1 | 134.4 |  |
| C1 | S2 | L-install | install | pre | 4 | 1.0 | 106.4 | 125.2 | 126.6 |  |
| C1 | S2 | L-install | install | attack | 8 | 1.0 | 107.0 | 148.9 | 161.7 |  |
| C1 | S2 | L-install | install | post | 4 | 1.0 | 87.9 | 110.5 | 113.1 |  |
| C2 | S2 | L-install | install | pre | 2 | 1.0 | 99.2 | 99.5 | 99.5 |  |
| C2 | S2 | L-install | install | attack | 4 | 1.0 | 100.8 | 130.3 | 133.6 |  |
| C2 | S2 | L-install | install | post | 2 | 1.0 | 63.8 | 88.9 | 91.1 |  |
| C3 | S2 | L-install | install | pre | 4 | 1.0 | 89.2 | 115.2 | 117.8 |  |
| C3 | S2 | L-install | install | attack | 8 | 1.0 | 105.0 | 127.5 | 127.8 |  |
| C3 | S2 | L-install | install | post | 4 | 1.0 | 92.5 | 126.9 | 129.5 |  |
| C1 | S2 | L-install | remove | pre | 4 | 1.0 | 155.1 | 229.0 | 238.0 |  |
| C1 | S2 | L-install | remove | attack | 8 | 1.0 | 127.7 | 152.5 | 156.3 |  |
| C1 | S2 | L-install | remove | post | 4 | 1.0 | 98.2 | 124.9 | 128.2 |  |
| C2 | S2 | L-install | remove | pre | 2 | 1.0 | 116.3 | 121.7 | 122.2 |  |
| C2 | S2 | L-install | remove | attack | 4 | 1.0 | 97.3 | 123.7 | 124.5 |  |
| C2 | S2 | L-install | remove | post | 2 | 1.0 | 102.1 | 104.9 | 105.1 |  |
| C3 | S2 | L-install | remove | pre | 4 | 1.0 | 124.3 | 162.9 | 166.3 |  |
| C3 | S2 | L-install | remove | attack | 8 | 1.0 | 101.5 | 132.3 | 137.6 |  |
| C3 | S2 | L-install | remove | post | 4 | 1.0 | 111.8 | 131.7 | 134.1 |  |
| C1 | S2 | S2 | all | attack | 10086 | 1.0 | 8.6 | 27.1 | 36.9 |  |
| C1 | S2 | S2 | all | post | 30 | 1.0 | 12.3 | 22.9 | 25.7 |  |
| C2 | S2 | S2 | all | attack | 861 | 1.0 | 14.5 | 338.6 | 396.3 |  |
| C2 | S2 | S2 | all | post | 5760 | 0.003 | 9.8 | 13.6 | 16.9 | 404:not_found=5741 |
| C3 | S2 | S2 | all | attack | 23927 | 0.001 | 14.5 | 40.9 | 53.9 | 403:throttled_blocked=13120 429:rate_limited=118 429:throttled_cooldown=10662 |
| C3 | S2 | S2 | all | post | 118 | 0.0 |  |  |  | 403:throttled_blocked=118 |
| C2 | S2 | S2 | delete_flow | post | 5740 | 0.0 |  |  |  | 404:not_found=5740 |
| C3 | S2 | S2 | delete_flow | attack | 247 | 0.053 | 12.0 | 28.4 | 32.1 | 403:throttled_blocked=80 429:rate_limited=104 429:throttled_cooldown=50 |
| C3 | S2 | S2 | delete_flow | post | 3 | 0.0 |  |  |  | 403:throttled_blocked=3 |
| C1 | S2 | S2 | delete_flows | attack | 5042 | 1.0 | 9.1 | 26.3 | 37.2 |  |
| C1 | S2 | S2 | delete_flows | post | 17 | 1.0 | 9.3 | 22.8 | 23.0 |  |
| C2 | S2 | S2 | delete_flows | attack | 287 | 1.0 | 156.2 | 384.2 | 442.4 |  |
| C2 | S2 | S2 | delete_flows | post | 1 | 0.0 |  |  |  | 404:not_found=1 |
| C3 | S2 | S2 | delete_flows | attack | 12 | 0.0 |  |  |  | 403:throttled_blocked=2 429:rate_limited=8 429:throttled_cooldown=2 |
| C3 | S2 | S2 | delete_flows | post | 3 | 0.0 |  |  |  | 403:throttled_blocked=3 |
| C2 | S2 | S2 | get_flows | attack | 287 | 1.0 | 7.9 | 28.3 | 35.0 |  |
| C2 | S2 | S2 | get_flows | post | 19 | 1.0 | 9.8 | 13.6 | 16.9 |  |
| C3 | S2 | S2 | get_flows | attack | 54 | 0.185 | 16.2 | 35.5 | 41.1 | 403:throttled_blocked=38 429:rate_limited=6 |
| C3 | S2 | S2 | get_flows | post | 55 | 0.0 |  |  |  | 403:throttled_blocked=55 |
| C1 | S2 | S2 | post_batch | attack | 5044 | 1.0 | 8.1 | 27.9 | 36.6 |  |
| C1 | S2 | S2 | post_batch | post | 13 | 1.0 | 12.7 | 23.8 | 26.2 |  |
| C2 | S2 | S2 | post_batch | attack | 287 | 1.0 | 9.2 | 29.8 | 34.6 |  |
| C3 | S2 | S2 | post_batch | attack | 23614 | 0.0 | 29.9 | 54.8 | 57.3 | 403:throttled_blocked=13000 429:throttled_cooldown=10610 |
| C3 | S2 | S2 | post_batch | post | 57 | 0.0 |  |  |  | 403:throttled_blocked=57 |
| C1 | S3 | L | all | pre | 128 | 1.0 | 20.0 | 39.0 | 59.9 |  |
| C1 | S3 | L | all | attack | 256 | 1.0 | 15.2 | 45.0 | 84.3 |  |
| C1 | S3 | L | all | post | 128 | 1.0 | 13.4 | 28.9 | 51.4 |  |
| C2 | S3 | L | all | pre | 128 | 1.0 | 19.9 | 49.1 | 53.0 |  |
| C2 | S3 | L | all | attack | 256 | 1.0 | 16.8 | 58.7 | 88.3 |  |
| C2 | S3 | L | all | post | 128 | 1.0 | 12.4 | 41.6 | 59.0 |  |
| C3 | S3 | L | all | pre | 128 | 1.0 | 22.9 | 55.8 | 65.9 |  |
| C3 | S3 | L | all | attack | 256 | 1.0 | 20.2 | 51.5 | 69.6 |  |
| C3 | S3 | L | all | post | 128 | 1.0 | 14.8 | 43.6 | 56.3 |  |
| C1 | S3 | L | delete_intent | pre | 4 | 1.0 | 57.8 | 68.3 | 69.2 |  |
| C1 | S3 | L | delete_intent | attack | 8 | 1.0 | 44.8 | 63.3 | 65.0 |  |
| C1 | S3 | L | delete_intent | post | 4 | 1.0 | 50.7 | 57.9 | 58.7 |  |
| C2 | S3 | L | delete_intent | pre | 4 | 1.0 | 50.0 | 55.1 | 55.7 |  |
| C2 | S3 | L | delete_intent | attack | 8 | 1.0 | 42.3 | 115.6 | 135.3 |  |
| C2 | S3 | L | delete_intent | post | 4 | 1.0 | 48.8 | 60.5 | 62.0 |  |
| C3 | S3 | L | delete_intent | pre | 4 | 1.0 | 53.2 | 63.4 | 64.5 |  |
| C3 | S3 | L | delete_intent | attack | 8 | 1.0 | 58.0 | 72.6 | 75.7 |  |
| C3 | S3 | L | delete_intent | post | 4 | 1.0 | 41.4 | 53.1 | 53.8 |  |
| C1 | S3 | L | get_flows | pre | 40 | 1.0 | 18.4 | 31.0 | 34.7 |  |
| C1 | S3 | L | get_flows | attack | 80 | 1.0 | 12.7 | 39.5 | 71.9 |  |
| C1 | S3 | L | get_flows | post | 40 | 1.0 | 11.3 | 27.2 | 28.9 |  |
| C2 | S3 | L | get_flows | pre | 40 | 1.0 | 15.6 | 29.3 | 31.7 |  |
| C2 | S3 | L | get_flows | attack | 80 | 1.0 | 11.6 | 32.6 | 79.9 |  |
| C2 | S3 | L | get_flows | post | 40 | 1.0 | 10.4 | 20.0 | 39.7 |  |
| C3 | S3 | L | get_flows | pre | 40 | 1.0 | 18.6 | 33.2 | 41.9 |  |
| C3 | S3 | L | get_flows | attack | 80 | 1.0 | 13.9 | 38.1 | 60.3 |  |
| C3 | S3 | L | get_flows | post | 40 | 1.0 | 11.6 | 33.4 | 49.5 |  |
| C1 | S3 | L | get_hosts | pre | 40 | 1.0 | 24.9 | 37.1 | 38.2 |  |
| C1 | S3 | L | get_hosts | attack | 80 | 1.0 | 19.0 | 46.2 | 95.1 |  |
| C1 | S3 | L | get_hosts | post | 40 | 1.0 | 15.1 | 24.8 | 30.7 |  |
| C2 | S3 | L | get_hosts | pre | 40 | 1.0 | 29.0 | 51.4 | 53.0 |  |
| C2 | S3 | L | get_hosts | attack | 80 | 1.0 | 27.1 | 60.7 | 84.1 |  |
| C2 | S3 | L | get_hosts | post | 40 | 1.0 | 20.5 | 37.1 | 46.6 |  |
| C3 | S3 | L | get_hosts | pre | 40 | 1.0 | 41.6 | 60.7 | 68.7 |  |
| C3 | S3 | L | get_hosts | attack | 80 | 1.0 | 30.6 | 52.0 | 74.0 |  |
| C3 | S3 | L | get_hosts | post | 40 | 1.0 | 23.8 | 42.9 | 99.9 |  |
| C1 | S3 | L | get_intents | pre | 40 | 1.0 | 16.0 | 39.7 | 47.8 |  |
| C1 | S3 | L | get_intents | attack | 80 | 1.0 | 12.1 | 31.0 | 43.4 |  |
| C1 | S3 | L | get_intents | post | 40 | 1.0 | 11.2 | 25.1 | 37.2 |  |
| C2 | S3 | L | get_intents | pre | 40 | 1.0 | 15.2 | 25.8 | 31.3 |  |
| C2 | S3 | L | get_intents | attack | 80 | 1.0 | 12.4 | 40.8 | 49.2 |  |
| C2 | S3 | L | get_intents | post | 40 | 1.0 | 10.2 | 22.0 | 26.8 |  |
| C3 | S3 | L | get_intents | pre | 40 | 1.0 | 18.1 | 26.3 | 34.7 |  |
| C3 | S3 | L | get_intents | attack | 80 | 1.0 | 14.9 | 37.2 | 40.9 |  |
| C3 | S3 | L | get_intents | post | 40 | 1.0 | 12.6 | 21.3 | 33.2 |  |
| C1 | S3 | L | post_intent | pre | 4 | 1.0 | 23.5 | 34.1 | 35.0 |  |
| C1 | S3 | L | post_intent | attack | 8 | 1.0 | 13.3 | 35.8 | 37.6 |  |
| C1 | S3 | L | post_intent | post | 4 | 1.0 | 15.1 | 18.4 | 18.4 |  |
| C2 | S3 | L | post_intent | pre | 4 | 1.0 | 20.8 | 32.0 | 32.8 |  |
| C2 | S3 | L | post_intent | attack | 8 | 1.0 | 26.9 | 77.5 | 84.6 |  |
| C2 | S3 | L | post_intent | post | 4 | 1.0 | 27.8 | 82.0 | 89.0 |  |
| C3 | S3 | L | post_intent | pre | 4 | 1.0 | 38.2 | 41.7 | 41.8 |  |
| C3 | S3 | L | post_intent | attack | 8 | 1.0 | 18.6 | 55.0 | 63.0 |  |
| C3 | S3 | L | post_intent | post | 4 | 1.0 | 25.9 | 52.6 | 56.3 |  |
| C1 | S3 | L-install | all | pre | 8 | 1.0 | 124.1 | 147.2 | 147.8 |  |
| C1 | S3 | L-install | all | attack | 16 | 1.0 | 89.6 | 129.0 | 132.8 |  |
| C1 | S3 | L-install | all | post | 8 | 1.0 | 84.8 | 100.6 | 102.8 |  |
| C2 | S3 | L-install | all | pre | 8 | 1.0 | 108.0 | 130.7 | 131.4 |  |
| C2 | S3 | L-install | all | attack | 16 | 1.0 | 98.6 | 164.4 | 169.3 |  |
| C2 | S3 | L-install | all | post | 8 | 1.0 | 110.8 | 132.7 | 134.2 |  |
| C3 | S3 | L-install | all | pre | 8 | 1.0 | 141.4 | 174.0 | 178.6 |  |
| C3 | S3 | L-install | all | attack | 16 | 1.0 | 106.6 | 138.8 | 149.2 |  |
| C3 | S3 | L-install | all | post | 8 | 1.0 | 98.8 | 122.6 | 124.0 |  |
| C1 | S3 | L-install | install | pre | 4 | 1.0 | 124.1 | 141.6 | 142.1 |  |
| C1 | S3 | L-install | install | attack | 8 | 1.0 | 78.6 | 117.8 | 125.5 |  |
| C1 | S3 | L-install | install | post | 4 | 1.0 | 94.5 | 95.6 | 95.6 |  |
| C2 | S3 | L-install | install | pre | 4 | 1.0 | 96.6 | 124.9 | 128.1 |  |
| C2 | S3 | L-install | install | attack | 8 | 1.0 | 102.7 | 145.2 | 158.9 |  |
| C2 | S3 | L-install | install | post | 4 | 1.0 | 99.8 | 132.4 | 134.2 |  |
| C3 | S3 | L-install | install | pre | 4 | 1.0 | 152.3 | 177.3 | 179.3 |  |
| C3 | S3 | L-install | install | attack | 8 | 1.0 | 110.0 | 131.5 | 133.9 |  |
| C3 | S3 | L-install | install | post | 4 | 1.0 | 112.8 | 123.6 | 124.2 |  |
| C1 | S3 | L-install | remove | pre | 4 | 1.0 | 119.3 | 147.7 | 147.9 |  |
| C1 | S3 | L-install | remove | attack | 8 | 1.0 | 107.8 | 130.3 | 133.1 |  |
| C1 | S3 | L-install | remove | post | 4 | 1.0 | 74.9 | 99.2 | 102.5 |  |
| C2 | S3 | L-install | remove | pre | 4 | 1.0 | 114.1 | 129.5 | 131.2 |  |
| C2 | S3 | L-install | remove | attack | 8 | 1.0 | 96.6 | 149.5 | 166.3 |  |
| C2 | S3 | L-install | remove | post | 4 | 1.0 | 110.8 | 127.4 | 128.8 |  |
| C3 | S3 | L-install | remove | pre | 4 | 1.0 | 116.5 | 145.1 | 145.7 |  |
| C3 | S3 | L-install | remove | attack | 8 | 1.0 | 93.3 | 136.9 | 148.8 |  |
| C3 | S3 | L-install | remove | post | 4 | 1.0 | 89.2 | 101.9 | 102.9 |  |
| C1 | S3 | S3 | all | attack | 960 | 0.903 | 19.8 | 47.0 | 72.6 | 400:-=93 |
| C1 | S3 | S3 | all | post | 2 | 1.0 | 31.9 | 32.9 | 33.0 |  |
| C2 | S3 | S3 | all | attack | 1662 | 0.596 | 11.9 | 37.7 | 67.7 | 403:port_not_owned=10 404:not_found=662 |
| C2 | S3 | S3 | all | post | 671 | 0.398 | 10.2 | 23.3 | 35.5 | 404:not_found=404 |
| C3 | S3 | S3 | all | attack | 479 | 0.0 |  |  |  | 400:bad_priority=2 400:constraints_not_allowed=4 400:controller_output=4 400:drop_not_owned=6 400:match_not_owned=6 400:missing_prerequisite=4 400:permanent_not_allowed=6 400:selector_not_owned=4 403:port_not_owned=4 403:throttled_blocked=199 429:throttled_cooldown=240 |
| C3 | S3 | S3 | all | post | 1 | 0.0 |  |  |  | 403:throttled_blocked=1 |
| C2 | S3 | S3 | delete_flow | attack | 649 | 0.074 | 17.8 | 34.5 | 44.4 | 404:not_found=601 |
| C2 | S3 | S3 | delete_flow | post | 391 | 0.005 | 28.0 | 48.1 | 49.9 | 404:not_found=389 |
| C1 | S3 | S3 | delete_flows | attack | 360 | 1.0 | 22.1 | 39.1 | 46.7 |  |
| C1 | S3 | S3 | delete_flows | post | 2 | 1.0 | 31.9 | 32.9 | 33.0 |  |
| C2 | S3 | S3 | delete_flows | attack | 50 | 0.04 | 42.1 | 65.5 | 67.6 | 404:not_found=48 |
| C2 | S3 | S3 | delete_flows | post | 15 | 0.0 |  |  |  | 404:not_found=15 |
| C1 | S3 | S3 | delete_intent | attack | 120 | 0.225 | 57.1 | 78.5 | 91.1 | 400:-=93 |
| C2 | S3 | S3 | delete_intent | attack | 18 | 0.278 | 75.1 | 93.2 | 95.3 | 404:not_found=13 |
| C2 | S3 | S3 | get_flows | attack | 865 | 1.0 | 11.7 | 35.2 | 53.6 |  |
| C2 | S3 | S3 | get_flows | post | 265 | 1.0 | 10.2 | 23.1 | 34.7 |  |
| C1 | S3 | S3 | post_catchall | attack | 60 | 1.0 | 17.8 | 37.3 | 41.5 |  |
| C2 | S3 | S3 | post_catchall | attack | 11 | 1.0 | 21.2 | 49.8 | 50.7 |  |
| C3 | S3 | S3 | post_catchall | attack | 60 | 0.0 |  |  |  | 400:match_not_owned=6 403:throttled_blocked=24 429:throttled_cooldown=30 |
| C1 | S3 | S3 | post_catchall_top | attack | 2 | 1.0 | 15.8 | 16.9 | 17.0 |  |
| C2 | S3 | S3 | post_catchall_top | attack | 2 | 1.0 | 21.2 | 23.4 | 23.6 |  |
| C3 | S3 | S3 | post_catchall_top | attack | 2 | 0.0 |  |  |  | 400:bad_priority=2 |
| C1 | S3 | S3 | post_constraints | attack | 60 | 1.0 | 14.6 | 36.8 | 51.2 |  |
| C2 | S3 | S3 | post_constraints | attack | 9 | 1.0 | 11.7 | 44.1 | 47.7 |  |
| C3 | S3 | S3 | post_constraints | attack | 59 | 0.0 |  |  |  | 400:constraints_not_allowed=4 403:throttled_blocked=25 429:throttled_cooldown=30 |
| C3 | S3 | S3 | post_constraints | post | 1 | 0.0 |  |  |  | 403:throttled_blocked=1 |
| C1 | S3 | S3 | post_controller | attack | 60 | 1.0 | 17.1 | 36.6 | 42.6 |  |
| C2 | S3 | S3 | post_controller | attack | 10 | 1.0 | 19.2 | 56.6 | 68.7 |  |
| C3 | S3 | S3 | post_controller | attack | 60 | 0.0 |  |  |  | 400:controller_output=4 403:throttled_blocked=26 429:throttled_cooldown=30 |
| C1 | S3 | S3 | post_dropall | attack | 60 | 1.0 | 18.2 | 39.0 | 68.5 |  |
| C2 | S3 | S3 | post_dropall | attack | 10 | 1.0 | 12.8 | 43.8 | 45.0 |  |
| C3 | S3 | S3 | post_dropall | attack | 60 | 0.0 |  |  |  | 400:drop_not_owned=6 403:throttled_blocked=24 429:throttled_cooldown=30 |
| C1 | S3 | S3 | post_foreign_port | attack | 60 | 1.0 | 17.6 | 44.3 | 60.0 |  |
| C2 | S3 | S3 | post_foreign_port | attack | 10 | 0.0 |  |  |  | 403:port_not_owned=10 |
| C3 | S3 | S3 | post_foreign_port | attack | 60 | 0.0 |  |  |  | 403:port_not_owned=4 403:throttled_blocked=26 429:throttled_cooldown=30 |
| C1 | S3 | S3 | post_missing_prereq | attack | 58 | 1.0 | 28.9 | 68.8 | 97.7 |  |
| C2 | S3 | S3 | post_missing_prereq | attack | 9 | 1.0 | 22.7 | 67.5 | 67.7 |  |
| C3 | S3 | S3 | post_missing_prereq | attack | 58 | 0.0 |  |  |  | 400:missing_prerequisite=4 403:throttled_blocked=24 429:throttled_cooldown=30 |
| C1 | S3 | S3 | post_p2p_empty | attack | 60 | 1.0 | 17.4 | 38.3 | 57.2 |  |
| C2 | S3 | S3 | post_p2p_empty | attack | 9 | 1.0 | 17.2 | 31.8 | 32.0 |  |
| C3 | S3 | S3 | post_p2p_empty | attack | 60 | 0.0 |  |  |  | 400:selector_not_owned=4 403:throttled_blocked=26 429:throttled_cooldown=30 |
| C1 | S3 | S3 | post_permanent | attack | 60 | 1.0 | 17.1 | 33.3 | 46.2 |  |
| C2 | S3 | S3 | post_permanent | attack | 10 | 1.0 | 14.9 | 71.9 | 97.4 |  |
| C3 | S3 | S3 | post_permanent | attack | 60 | 0.0 |  |  |  | 400:permanent_not_allowed=6 403:throttled_blocked=24 429:throttled_cooldown=30 |
| C1 | S4 | L | all | pre | 128 | 1.0 | 13.6 | 40.6 | 85.9 |  |
| C1 | S4 | L | all | attack | 256 | 1.0 | 14.8 | 41.6 | 114.8 |  |
| C1 | S4 | L | all | post | 128 | 1.0 | 13.0 | 32.8 | 54.6 |  |
| C2 | S4 | L | all | pre | 128 | 1.0 | 17.2 | 37.3 | 43.4 |  |
| C2 | S4 | L | all | attack | 256 | 1.0 | 11.9 | 34.1 | 45.5 |  |
| C2 | S4 | L | all | post | 128 | 1.0 | 11.4 | 29.9 | 39.4 |  |
| C3 | S4 | L | all | pre | 128 | 1.0 | 19.6 | 52.8 | 70.0 |  |
| C3 | S4 | L | all | attack | 256 | 1.0 | 15.9 | 50.9 | 75.9 |  |
| C3 | S4 | L | all | post | 128 | 1.0 | 12.5 | 37.2 | 48.9 |  |
| C1 | S4 | L | delete_intent | pre | 4 | 1.0 | 44.5 | 52.1 | 52.3 |  |
| C1 | S4 | L | delete_intent | attack | 8 | 1.0 | 48.5 | 66.2 | 66.7 |  |
| C1 | S4 | L | delete_intent | post | 4 | 1.0 | 43.5 | 51.1 | 51.8 |  |
| C2 | S4 | L | delete_intent | pre | 4 | 1.0 | 31.7 | 40.0 | 40.2 |  |
| C2 | S4 | L | delete_intent | attack | 8 | 1.0 | 39.1 | 71.8 | 81.1 |  |
| C2 | S4 | L | delete_intent | post | 4 | 1.0 | 39.2 | 48.3 | 49.5 |  |
| C3 | S4 | L | delete_intent | pre | 4 | 1.0 | 62.6 | 80.5 | 82.3 |  |
| C3 | S4 | L | delete_intent | attack | 8 | 1.0 | 51.3 | 65.7 | 65.9 |  |
| C3 | S4 | L | delete_intent | post | 4 | 1.0 | 46.8 | 53.5 | 53.9 |  |
| C1 | S4 | L | get_flows | pre | 40 | 1.0 | 12.7 | 34.6 | 73.9 |  |
| C1 | S4 | L | get_flows | attack | 80 | 1.0 | 13.1 | 38.6 | 79.6 |  |
| C1 | S4 | L | get_flows | post | 40 | 1.0 | 11.9 | 24.6 | 81.3 |  |
| C2 | S4 | L | get_flows | pre | 40 | 1.0 | 15.1 | 27.5 | 32.9 |  |
| C2 | S4 | L | get_flows | attack | 80 | 1.0 | 9.6 | 20.4 | 22.8 |  |
| C2 | S4 | L | get_flows | post | 40 | 1.0 | 9.9 | 19.9 | 23.6 |  |
| C3 | S4 | L | get_flows | pre | 40 | 1.0 | 16.8 | 39.6 | 60.1 |  |
| C3 | S4 | L | get_flows | attack | 80 | 1.0 | 12.6 | 33.4 | 62.8 |  |
| C3 | S4 | L | get_flows | post | 40 | 1.0 | 11.4 | 20.7 | 32.1 |  |
| C1 | S4 | L | get_hosts | pre | 40 | 1.0 | 16.9 | 36.5 | 56.2 |  |
| C1 | S4 | L | get_hosts | attack | 80 | 1.0 | 17.9 | 37.0 | 60.5 |  |
| C1 | S4 | L | get_hosts | post | 40 | 1.0 | 16.6 | 24.5 | 29.9 |  |
| C2 | S4 | L | get_hosts | pre | 40 | 1.0 | 25.5 | 41.3 | 44.9 |  |
| C2 | S4 | L | get_hosts | attack | 80 | 1.0 | 21.5 | 36.1 | 47.7 |  |
| C2 | S4 | L | get_hosts | post | 40 | 1.0 | 18.5 | 30.6 | 34.7 |  |
| C3 | S4 | L | get_hosts | pre | 40 | 1.0 | 30.5 | 53.7 | 67.5 |  |
| C3 | S4 | L | get_hosts | attack | 80 | 1.0 | 29.1 | 56.1 | 163.7 |  |
| C3 | S4 | L | get_hosts | post | 40 | 1.0 | 23.0 | 34.4 | 40.0 |  |
| C1 | S4 | L | get_intents | pre | 40 | 1.0 | 11.8 | 23.2 | 27.5 |  |
| C1 | S4 | L | get_intents | attack | 80 | 1.0 | 12.8 | 25.6 | 130.3 |  |
| C1 | S4 | L | get_intents | post | 40 | 1.0 | 11.9 | 24.2 | 45.0 |  |
| C2 | S4 | L | get_intents | pre | 40 | 1.0 | 12.2 | 17.3 | 22.1 |  |
| C2 | S4 | L | get_intents | attack | 80 | 1.0 | 10.1 | 18.5 | 21.2 |  |
| C2 | S4 | L | get_intents | post | 40 | 1.0 | 10.0 | 23.8 | 26.3 |  |
| C3 | S4 | L | get_intents | pre | 40 | 1.0 | 15.8 | 33.2 | 37.0 |  |
| C3 | S4 | L | get_intents | attack | 80 | 1.0 | 12.7 | 34.1 | 45.0 |  |
| C3 | S4 | L | get_intents | post | 40 | 1.0 | 11.4 | 23.3 | 40.0 |  |
| C1 | S4 | L | post_intent | pre | 4 | 1.0 | 25.1 | 97.0 | 105.6 |  |
| C1 | S4 | L | post_intent | attack | 8 | 1.0 | 15.6 | 20.0 | 20.9 |  |
| C1 | S4 | L | post_intent | post | 4 | 1.0 | 15.5 | 30.8 | 31.6 |  |
| C2 | S4 | L | post_intent | pre | 4 | 1.0 | 29.8 | 33.5 | 33.8 |  |
| C2 | S4 | L | post_intent | attack | 8 | 1.0 | 13.9 | 23.7 | 23.9 |  |
| C2 | S4 | L | post_intent | post | 4 | 1.0 | 15.9 | 22.6 | 22.8 |  |
| C3 | S4 | L | post_intent | pre | 4 | 1.0 | 18.2 | 36.0 | 37.4 |  |
| C3 | S4 | L | post_intent | attack | 8 | 1.0 | 13.0 | 33.5 | 33.7 |  |
| C3 | S4 | L | post_intent | post | 4 | 1.0 | 12.4 | 38.2 | 41.3 |  |
| C1 | S4 | L-install | all | pre | 8 | 1.0 | 106.8 | 197.7 | 213.4 |  |
| C1 | S4 | L-install | all | attack | 16 | 1.0 | 101.2 | 144.0 | 146.7 |  |
| C1 | S4 | L-install | all | post | 8 | 1.0 | 81.5 | 116.7 | 118.1 |  |
| C2 | S4 | L-install | all | pre | 8 | 1.0 | 107.2 | 120.2 | 123.8 |  |
| C2 | S4 | L-install | all | attack | 16 | 1.0 | 88.0 | 129.7 | 144.3 |  |
| C2 | S4 | L-install | all | post | 8 | 1.0 | 100.8 | 110.6 | 110.7 |  |
| C3 | S4 | L-install | all | pre | 8 | 1.0 | 151.6 | 176.1 | 178.0 |  |
| C3 | S4 | L-install | all | attack | 16 | 1.0 | 84.3 | 115.7 | 123.9 |  |
| C3 | S4 | L-install | all | post | 8 | 1.0 | 95.2 | 127.1 | 129.7 |  |
| C1 | S4 | L-install | install | pre | 4 | 1.0 | 105.2 | 202.6 | 214.4 |  |
| C1 | S4 | L-install | install | attack | 8 | 1.0 | 97.7 | 135.2 | 141.3 |  |
| C1 | S4 | L-install | install | post | 4 | 1.0 | 80.1 | 102.8 | 106.0 |  |
| C2 | S4 | L-install | install | pre | 4 | 1.0 | 108.8 | 111.8 | 111.9 |  |
| C2 | S4 | L-install | install | attack | 8 | 1.0 | 85.8 | 104.3 | 110.9 |  |
| C2 | S4 | L-install | install | post | 4 | 1.0 | 85.4 | 100.1 | 100.9 |  |
| C3 | S4 | L-install | install | pre | 4 | 1.0 | 118.5 | 148.0 | 148.9 |  |
| C3 | S4 | L-install | install | attack | 8 | 1.0 | 81.0 | 108.5 | 111.5 |  |
| C3 | S4 | L-install | install | post | 4 | 1.0 | 84.4 | 117.1 | 120.2 |  |
| C1 | S4 | L-install | remove | pre | 4 | 1.0 | 106.8 | 153.7 | 159.7 |  |
| C1 | S4 | L-install | remove | attack | 8 | 1.0 | 103.6 | 143.2 | 146.6 |  |
| C1 | S4 | L-install | remove | post | 4 | 1.0 | 98.2 | 117.7 | 118.3 |  |
| C2 | S4 | L-install | remove | pre | 4 | 1.0 | 107.2 | 122.2 | 124.2 |  |
| C2 | S4 | L-install | remove | attack | 8 | 1.0 | 104.5 | 139.4 | 146.2 |  |
| C2 | S4 | L-install | remove | post | 4 | 1.0 | 109.7 | 110.7 | 110.7 |  |
| C3 | S4 | L-install | remove | pre | 4 | 1.0 | 165.3 | 177.4 | 178.3 |  |
| C3 | S4 | L-install | remove | attack | 8 | 1.0 | 91.3 | 117.5 | 124.3 |  |
| C3 | S4 | L-install | remove | post | 4 | 1.0 | 101.8 | 127.1 | 129.7 |  |
| C1 | S4 | S4 | all | attack | 96 | 1.0 | 21.2 | 72.8 | 86.3 |  |
| C1 | S4 | S4 | all | post | 2 | 1.0 | 13.5 | 15.3 | 15.5 |  |
| C2 | S4 | S4 | all | attack | 567 | 0.764 | 10.8 | 23.3 | 40.5 | 403:endpoint_not_owned=4 404:not_found=130 |
| C2 | S4 | S4 | all | post | 504 | 0.591 | 10.0 | 20.1 | 25.5 | 404:not_found=206 |
| C3 | S4 | S4 | all | attack | 48 | 0.0 |  |  |  | 400:match_not_owned=2 400:selector_not_owned=2 403:endpoint_not_owned=4 409:dst_capture=10 409:foreign_rewrite=4 409:spoofed_source=3 429:throttled_cooldown=23 |
| C2 | S4 | S4 | delete_flow | attack | 130 | 0.154 | 10.5 | 26.4 | 30.6 | 404:not_found=110 |
| C2 | S4 | S4 | delete_flow | post | 192 | 0.01 | 9.1 | 11.9 | 12.1 | 404:not_found=190 |
| C1 | S4 | S4 | delete_flows | attack | 30 | 1.0 | 18.1 | 58.5 | 79.0 |  |
| C1 | S4 | S4 | delete_flows | post | 2 | 1.0 | 13.5 | 15.3 | 15.5 |  |
| C2 | S4 | S4 | delete_flows | attack | 22 | 0.091 | 8.8 | 10.0 | 10.1 | 404:not_found=20 |
| C2 | S4 | S4 | delete_flows | post | 16 | 0.0 |  |  |  | 404:not_found=16 |
| C1 | S4 | S4 | delete_intent | attack | 18 | 1.0 | 45.9 | 78.2 | 86.8 |  |
| C2 | S4 | S4 | delete_intent | attack | 8 | 1.0 | 41.8 | 54.7 | 55.9 |  |
| C2 | S4 | S4 | get_flows | attack | 371 | 1.0 | 10.5 | 20.2 | 25.8 |  |
| C2 | S4 | S4 | get_flows | post | 296 | 1.0 | 10.0 | 20.2 | 25.5 |  |
| C1 | S4 | S4 | post_capture_ip | attack | 6 | 1.0 | 19.9 | 31.4 | 33.2 |  |
| C2 | S4 | S4 | post_capture_ip | attack | 4 | 1.0 | 10.8 | 36.1 | 39.1 |  |
| C3 | S4 | S4 | post_capture_ip | attack | 6 | 0.0 |  |  |  | 409:dst_capture=4 429:throttled_cooldown=2 |
| C1 | S4 | S4 | post_dst_capture | attack | 6 | 1.0 | 20.6 | 42.5 | 43.7 |  |
| C2 | S4 | S4 | post_dst_capture | attack | 6 | 1.0 | 16.4 | 39.7 | 40.3 |  |
| C3 | S4 | S4 | post_dst_capture | attack | 6 | 0.0 |  |  |  | 400:match_not_owned=2 429:throttled_cooldown=4 |
| C1 | S4 | S4 | post_dst_capture_owned | attack | 6 | 1.0 | 14.2 | 23.1 | 23.7 |  |
| C2 | S4 | S4 | post_dst_capture_owned | attack | 6 | 1.0 | 11.9 | 16.1 | 16.3 |  |
| C3 | S4 | S4 | post_dst_capture_owned | attack | 6 | 0.0 |  |  |  | 409:dst_capture=2 429:throttled_cooldown=4 |
| C1 | S4 | S4 | post_intent_cross | attack | 6 | 1.0 | 17.8 | 22.2 | 22.5 |  |
| C2 | S4 | S4 | post_intent_cross | attack | 4 | 0.0 |  |  |  | 403:endpoint_not_owned=4 |
| C3 | S4 | S4 | post_intent_cross | attack | 6 | 0.0 |  |  |  | 403:endpoint_not_owned=4 429:throttled_cooldown=2 |
| C1 | S4 | S4 | post_p2p_capture | attack | 6 | 1.0 | 12.1 | 60.1 | 65.5 |  |
| C2 | S4 | S4 | post_p2p_capture | attack | 4 | 1.0 | 14.2 | 17.7 | 17.9 |  |
| C3 | S4 | S4 | post_p2p_capture | attack | 6 | 0.0 |  |  |  | 409:dst_capture=4 429:throttled_cooldown=2 |
| C1 | S4 | S4 | post_p2p_spoof | attack | 6 | 1.0 | 27.4 | 37.0 | 39.3 |  |
| C2 | S4 | S4 | post_p2p_spoof | attack | 4 | 1.0 | 15.8 | 32.5 | 34.7 |  |
| C3 | S4 | S4 | post_p2p_spoof | attack | 6 | 0.0 |  |  |  | 400:selector_not_owned=2 429:throttled_cooldown=4 |
| C1 | S4 | S4 | post_rewrite_dst | attack | 6 | 1.0 | 24.8 | 64.4 | 72.8 |  |
| C2 | S4 | S4 | post_rewrite_dst | attack | 4 | 1.0 | 10.6 | 16.4 | 16.6 |  |
| C3 | S4 | S4 | post_rewrite_dst | attack | 6 | 0.0 |  |  |  | 409:foreign_rewrite=4 429:throttled_cooldown=2 |
| C1 | S4 | S4 | post_spoof_src | attack | 6 | 1.0 | 21.7 | 49.4 | 51.5 |  |
| C2 | S4 | S4 | post_spoof_src | attack | 4 | 1.0 | 7.3 | 18.1 | 19.5 |  |
| C3 | S4 | S4 | post_spoof_src | attack | 6 | 0.0 |  |  |  | 409:spoofed_source=3 429:throttled_cooldown=3 |
| C1 | baseline | L | all | pre | 192 | 1.0 | 17.2 | 33.3 | 42.4 |  |
| C1 | baseline | L | all | attack | 384 | 1.0 | 15.4 | 31.5 | 48.4 |  |
| C1 | baseline | L | all | post | 192 | 1.0 | 12.8 | 29.5 | 57.5 |  |
| C2 | baseline | L | all | pre | 128 | 1.0 | 19.8 | 44.9 | 56.9 |  |
| C2 | baseline | L | all | attack | 256 | 1.0 | 14.7 | 42.5 | 54.6 |  |
| C2 | baseline | L | all | post | 128 | 1.0 | 12.8 | 36.1 | 48.1 |  |
| C3 | baseline | L | all | pre | 128 | 1.0 | 20.0 | 44.0 | 62.7 |  |
| C3 | baseline | L | all | attack | 256 | 1.0 | 14.9 | 46.5 | 67.5 |  |
| C3 | baseline | L | all | post | 128 | 1.0 | 13.8 | 38.9 | 53.9 |  |
| C1 | baseline | L | delete_intent | pre | 6 | 1.0 | 38.0 | 55.1 | 57.7 |  |
| C1 | baseline | L | delete_intent | attack | 12 | 1.0 | 46.8 | 64.5 | 70.0 |  |
| C1 | baseline | L | delete_intent | post | 6 | 1.0 | 47.7 | 61.0 | 62.1 |  |
| C2 | baseline | L | delete_intent | pre | 4 | 1.0 | 32.0 | 46.1 | 46.8 |  |
| C2 | baseline | L | delete_intent | attack | 8 | 1.0 | 38.3 | 62.4 | 68.1 |  |
| C2 | baseline | L | delete_intent | post | 4 | 1.0 | 37.4 | 61.4 | 63.1 |  |
| C3 | baseline | L | delete_intent | pre | 4 | 1.0 | 42.1 | 48.8 | 49.7 |  |
| C3 | baseline | L | delete_intent | attack | 8 | 1.0 | 49.2 | 57.3 | 58.1 |  |
| C3 | baseline | L | delete_intent | post | 4 | 1.0 | 50.1 | 76.9 | 80.2 |  |
| C1 | baseline | L | get_flows | pre | 60 | 1.0 | 15.4 | 23.8 | 24.9 |  |
| C1 | baseline | L | get_flows | attack | 120 | 1.0 | 13.3 | 20.8 | 28.3 |  |
| C1 | baseline | L | get_flows | post | 60 | 1.0 | 12.6 | 20.7 | 26.3 |  |
| C2 | baseline | L | get_flows | pre | 40 | 1.0 | 17.4 | 31.0 | 49.0 |  |
| C2 | baseline | L | get_flows | attack | 80 | 1.0 | 13.2 | 23.9 | 30.8 |  |
| C2 | baseline | L | get_flows | post | 40 | 1.0 | 10.5 | 20.3 | 24.0 |  |
| C3 | baseline | L | get_flows | pre | 40 | 1.0 | 16.9 | 24.7 | 26.9 |  |
| C3 | baseline | L | get_flows | attack | 80 | 1.0 | 12.6 | 23.7 | 47.9 |  |
| C3 | baseline | L | get_flows | post | 40 | 1.0 | 11.8 | 18.4 | 21.6 |  |
| C1 | baseline | L | get_hosts | pre | 60 | 1.0 | 20.1 | 35.6 | 41.2 |  |
| C1 | baseline | L | get_hosts | attack | 120 | 1.0 | 18.4 | 27.3 | 35.4 |  |
| C1 | baseline | L | get_hosts | post | 60 | 1.0 | 16.4 | 27.8 | 30.8 |  |
| C2 | baseline | L | get_hosts | pre | 40 | 1.0 | 28.6 | 51.2 | 57.6 |  |
| C2 | baseline | L | get_hosts | attack | 80 | 1.0 | 26.9 | 45.5 | 49.5 |  |
| C2 | baseline | L | get_hosts | post | 40 | 1.0 | 22.9 | 38.9 | 42.8 |  |
| C3 | baseline | L | get_hosts | pre | 40 | 1.0 | 30.4 | 61.3 | 64.2 |  |
| C3 | baseline | L | get_hosts | attack | 80 | 1.0 | 25.2 | 54.7 | 75.9 |  |
| C3 | baseline | L | get_hosts | post | 40 | 1.0 | 24.7 | 39.5 | 48.9 |  |
| C1 | baseline | L | get_intents | pre | 60 | 1.0 | 15.5 | 21.2 | 23.0 |  |
| C1 | baseline | L | get_intents | attack | 120 | 1.0 | 13.1 | 22.0 | 32.9 |  |
| C1 | baseline | L | get_intents | post | 60 | 1.0 | 11.9 | 20.4 | 47.0 |  |
| C2 | baseline | L | get_intents | pre | 40 | 1.0 | 16.4 | 33.0 | 36.4 |  |
| C2 | baseline | L | get_intents | attack | 80 | 1.0 | 13.2 | 30.1 | 59.1 |  |
| C2 | baseline | L | get_intents | post | 40 | 1.0 | 12.1 | 22.4 | 39.6 |  |
| C3 | baseline | L | get_intents | pre | 40 | 1.0 | 15.0 | 21.5 | 33.1 |  |
| C3 | baseline | L | get_intents | attack | 80 | 1.0 | 12.8 | 21.6 | 28.7 |  |
| C3 | baseline | L | get_intents | post | 40 | 1.0 | 12.5 | 28.6 | 40.7 |  |
| C1 | baseline | L | post_intent | pre | 6 | 1.0 | 12.6 | 30.1 | 33.9 |  |
| C1 | baseline | L | post_intent | attack | 12 | 1.0 | 12.8 | 24.6 | 27.4 |  |
| C1 | baseline | L | post_intent | post | 6 | 1.0 | 8.7 | 20.6 | 22.9 |  |
| C2 | baseline | L | post_intent | pre | 4 | 1.0 | 16.6 | 36.2 | 38.0 |  |
| C2 | baseline | L | post_intent | attack | 8 | 1.0 | 12.7 | 21.5 | 21.7 |  |
| C2 | baseline | L | post_intent | post | 4 | 1.0 | 13.1 | 17.8 | 18.3 |  |
| C3 | baseline | L | post_intent | pre | 4 | 1.0 | 20.9 | 28.9 | 30.0 |  |
| C3 | baseline | L | post_intent | attack | 8 | 1.0 | 19.7 | 31.6 | 34.7 |  |
| C3 | baseline | L | post_intent | post | 4 | 1.0 | 15.9 | 19.0 | 19.4 |  |
| C1 | baseline | L-install | all | pre | 12 | 1.0 | 96.2 | 137.0 | 158.5 |  |
| C1 | baseline | L-install | all | attack | 24 | 1.0 | 97.1 | 120.2 | 120.9 |  |
| C1 | baseline | L-install | all | post | 12 | 1.0 | 97.6 | 126.2 | 134.0 |  |
| C2 | baseline | L-install | all | pre | 8 | 1.0 | 106.5 | 149.6 | 152.1 |  |
| C2 | baseline | L-install | all | attack | 16 | 1.0 | 84.0 | 98.9 | 102.6 |  |
| C2 | baseline | L-install | all | post | 8 | 1.0 | 80.5 | 98.3 | 98.9 |  |
| C3 | baseline | L-install | all | pre | 8 | 1.0 | 110.9 | 170.4 | 178.6 |  |
| C3 | baseline | L-install | all | attack | 16 | 1.0 | 97.0 | 129.1 | 133.0 |  |
| C3 | baseline | L-install | all | post | 8 | 1.0 | 96.2 | 111.0 | 111.8 |  |
| C1 | baseline | L-install | install | pre | 6 | 1.0 | 95.2 | 106.5 | 109.1 |  |
| C1 | baseline | L-install | install | attack | 12 | 1.0 | 97.1 | 118.9 | 120.3 |  |
| C1 | baseline | L-install | install | post | 6 | 1.0 | 93.2 | 98.2 | 98.6 |  |
| C2 | baseline | L-install | install | pre | 4 | 1.0 | 107.1 | 140.3 | 143.1 |  |
| C2 | baseline | L-install | install | attack | 8 | 1.0 | 72.7 | 96.3 | 97.2 |  |
| C2 | baseline | L-install | install | post | 4 | 1.0 | 90.3 | 98.8 | 99.0 |  |
| C3 | baseline | L-install | install | pre | 4 | 1.0 | 101.7 | 121.8 | 124.0 |  |
| C3 | baseline | L-install | install | attack | 8 | 1.0 | 98.7 | 131.7 | 133.5 |  |
| C3 | baseline | L-install | install | post | 4 | 1.0 | 86.8 | 104.3 | 106.3 |  |
| C1 | baseline | L-install | remove | pre | 6 | 1.0 | 111.9 | 151.7 | 161.5 |  |
| C1 | baseline | L-install | remove | attack | 12 | 1.0 | 98.0 | 118.8 | 120.5 |  |
| C1 | baseline | L-install | remove | post | 6 | 1.0 | 115.7 | 131.6 | 135.1 |  |
| C2 | baseline | L-install | remove | pre | 4 | 1.0 | 106.5 | 146.4 | 151.4 |  |
| C2 | baseline | L-install | remove | attack | 8 | 1.0 | 87.2 | 100.6 | 102.9 |  |
| C2 | baseline | L-install | remove | post | 4 | 1.0 | 80.3 | 80.5 | 80.5 |  |
| C3 | baseline | L-install | remove | pre | 4 | 1.0 | 132.2 | 176.2 | 179.7 |  |
| C3 | baseline | L-install | remove | attack | 8 | 1.0 | 89.0 | 120.2 | 122.4 |  |
| C3 | baseline | L-install | remove | post | 4 | 1.0 | 105.6 | 111.5 | 111.9 |  |

## ping

| config | workload | csv | window | pings | loss_pct | rtt_p50_ms | rtt_p95_ms |
|---|---|---|---|---|---|---|---|
| C1 | S1 | L-ping | pre | 117 | 0.0 | 0.108 | 0.145 |
| C1 | S1 | L-ping | attack | 233 | 0.0 | 0.066 | 0.341 |
| C1 | S1 | L-ping | post | 116 | 0.0 | 0.111 | 0.157 |
| C2 | S1 | L-ping | pre | 117 | 0.0 | 0.091 | 0.133 |
| C2 | S1 | L-ping | attack | 232 | 0.0 | 0.048 | 0.115 |
| C2 | S1 | L-ping | post | 117 | 0.0 | 0.049 | 0.122 |
| C3 | S1 | L-ping | pre | 117 | 0.0 | 0.102 | 0.121 |
| C3 | S1 | L-ping | attack | 234 | 0.0 | 0.048 | 0.166 |
| C3 | S1 | L-ping | post | 117 | 0.0 | 0.099 | 0.137 |
| C1 | S2 | L-ping | pre | 116 | 0.0 | 0.099 | 0.145 |
| C1 | S2 | L-ping | attack | 233 | 0.0 | 0.053 | 0.36 |
| C1 | S2 | L-ping | post | 118 | 0.0 | 0.103 | 0.145 |
| C2 | S2 | L-ping | pre | 58 | 0.0 | 0.095 | 0.122 |
| C2 | S2 | L-ping | attack | 117 | 0.0 | 0.048 | 0.086 |
| C2 | S2 | L-ping | post | 58 | 0.0 | 0.041 | 0.195 |
| C3 | S2 | L-ping | pre | 117 | 0.0 | 0.101 | 0.143 |
| C3 | S2 | L-ping | attack | 233 | 0.0 | 0.044 | 0.423 |
| C3 | S2 | L-ping | post | 118 | 0.0 | 0.097 | 0.153 |
| C1 | S3 | L-ping | pre | 116 | 0.0 | 0.107 | 0.141 |
| C1 | S3 | L-ping | attack | 234 | 2.6 | 0.09 | 0.137 |
| C1 | S3 | L-ping | post | 118 | 0.0 | 0.107 | 0.142 |
| C2 | S3 | L-ping | pre | 118 | 0.0 | 0.1 | 0.14 |
| C2 | S3 | L-ping | attack | 232 | 36.6 | 0.086 | 0.151 |
| C2 | S3 | L-ping | post | 119 | 2.5 | 0.092 | 0.125 |
| C3 | S3 | L-ping | pre | 118 | 0.0 | 0.108 | 0.148 |
| C3 | S3 | L-ping | attack | 234 | 0.0 | 0.106 | 0.15 |
| C3 | S3 | L-ping | post | 116 | 0.0 | 0.106 | 0.136 |
| C1 | S4 | L-ping | pre | 117 | 0.0 | 0.104 | 0.141 |
| C1 | S4 | L-ping | attack | 234 | 10.3 | 0.103 | 0.142 |
| C1 | S4 | L-ping | post | 118 | 0.0 | 0.105 | 0.142 |
| C2 | S4 | L-ping | pre | 117 | 0.0 | 0.098 | 0.147 |
| C2 | S4 | L-ping | attack | 234 | 17.1 | 0.075 | 0.112 |
| C2 | S4 | L-ping | post | 118 | 0.0 | 0.086 | 0.114 |
| C3 | S4 | L-ping | pre | 116 | 0.0 | 0.105 | 0.157 |
| C3 | S4 | L-ping | attack | 234 | 0.0 | 0.103 | 0.14 |
| C3 | S4 | L-ping | post | 117 | 0.0 | 0.101 | 0.124 |
| C1 | baseline | L-ping | pre | 174 | 0.0 | 0.105 | 0.13 |
| C1 | baseline | L-ping | attack | 352 | 0.0 | 0.105 | 0.137 |
| C1 | baseline | L-ping | post | 176 | 0.0 | 0.103 | 0.13 |
| C2 | baseline | L-ping | pre | 117 | 0.0 | 0.104 | 0.14 |
| C2 | baseline | L-ping | attack | 234 | 0.0 | 0.103 | 0.157 |
| C2 | baseline | L-ping | post | 118 | 0.0 | 0.092 | 0.145 |
| C3 | baseline | L-ping | pre | 117 | 0.0 | 0.105 | 0.142 |
| C3 | baseline | L-ping | attack | 234 | 0.0 | 0.104 | 0.146 |
| C3 | baseline | L-ping | post | 117 | 0.0 | 0.104 | 0.148 |
