# Harness design notes (input for step A5; not yet implemented)

Constraints found during A1/A2 that `harness/run.sh` must respect:

1. **Topology check after every ONOS restart or purge.** After any container
   restart (`karaf/apply.sh`), flow/intent purge, or host purge, run
   `topology/mnctl.sh discover` (it waits for 3 devices + 4 links, ARP-discovers
   the hosts and runs `check_onos.py`) and **abort the run if it does not exit 0
   (MATCH)**. `karaf/apply.sh` already does this after its restart; the purge path
   must call it explicitly. (Observed in A2: a `docker restart onos` kept the
   host store, netcfg and app states, but the check stays mandatory.)
2. **Account switches.** Karaf re-reads `users.properties` on every login, so
   `karaf/apply.sh --no-restart <overlay>` switches C1 <-> C2/C3 accounts live
   (its probe saw the new codes after 0 s). Use the default (restart) mode
   when a run needs a clean ONOS; `--no-restart` for quick switches. On any
   failure after the copy, `apply.sh` restores the previous file and exits 1;
   the harness must then abort the run.
3. **Locking.** `karaf/apply.sh` takes `/tmp/sdn-onos.lock` itself unless
   `SDN_LOCK_HELD=1`; `mnctl.sh` never does. If the harness holds the lock for a
   whole run, export `SDN_LOCK_HELD=1` and call both directly.
4. **Lab3 app.** While `lu.uni.sdn.southbound_mac_extractor` is active it punts
   all unmatched IPv4/ARP to ONOS (priority-5 rules), adding controller load.
   The user decides whether it runs during evaluation; the harness records its
   state in every run's metadata (apply.sh prints it).
5. **`fwd`.** The container env has `ONOS_APPS=drivers,openflow,fwd,gui2`, but
   `fwd` stayed INSTALLED (not ACTIVE) across four restarts. Record its state per
   run anyway; `apply.sh` warns if it is ACTIVE.
6. **Flow attribution.** Intent-compiled flows carry appId
   `org.onosproject.net.intent`; attribute them to a tenant through
   `/onos/v1/intents/relatedflows/{appId}/{key}`.
7. **Throughput.** Links are unshaped (iperf h2a->h2b measured 31.5 Gbit/s), so
   L's iperf throughput is CPU bound. Decide in A4/A5 whether to add TCLink limits.
