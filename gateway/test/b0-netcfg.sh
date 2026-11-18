#!/bin/sh
# B0 netcfg reload test. Runs ON THE VM and changes shared state, so hold the lock:
#   flock /tmp/sdn-onos.lock sh ~/gw/b0-netcfg.sh
# Needs ~/gw/tenants.json, ~/gw/vm-install.sh and the .oar (scripts/deploy.sh copies them).
# Leaves the valid registry installed at the end.
U=onos:rocks; URL=http://localhost:8181/onos/v1; NC=$URL/network/configuration/apps/lu.uni.sdn.api_gateway/tenants
who() { curl -s -u onos:rocks http://localhost:8181/onos/gw/whoami; echo; }
echo "### 1. invalid registry (unknown root field) pushed"
python3 -c 'import json; d=json.load(open("/home/mininet/gw/tenants.json")); d["bogus"]=1; json.dump(d, open("/tmp/gw-bad.json","w"))'
curl -s -u $U -X POST -H 'Content-Type: application/json' $NC --data-binary @/tmp/gw-bad.json -w '\nHTTP %{http_code}\n'
sleep 1; echo "whoami onos after invalid push:"; who
echo "### 2. valid registry re-pushed"
curl -s -u $U -X POST -H 'Content-Type: application/json' $NC --data-binary @/home/mininet/gw/tenants.json -w '\nHTTP %{http_code}\n'
sleep 1; who
echo "### 3. registry removed"
curl -s -u $U -X DELETE $NC -w 'HTTP %{http_code}\n'
sleep 1; echo "whoami onos after removal:"; who
echo "### 4. valid registry pushed, then app reinstalled WITHOUT re-push"
curl -s -o /dev/null -u $U -X POST -H 'Content-Type: application/json' $NC --data-binary @/home/mininet/gw/tenants.json -w 'push HTTP %{http_code}\n'
out=$(sh /home/mininet/gw/vm-install.sh /home/mininet/gw/api_gateway-1.0-SNAPSHOT.oar 2>&1) || { echo "$out"; echo "FAIL: reinstall failed, gateway may be uninstalled"; exit 1; }
echo "$out" | grep -E 'HTTP|ACTIVE'
for i in 1 2 3 4 5 6 7 8 9 10; do c=$(curl -s -o /dev/null -w '%{http_code}' -u $U http://localhost:8181/onos/gw/whoami); [ "$c" = 200 ] && break; sleep 1; done
echo "whoami onos after reinstall:"; who
echo "netcfg still present after reinstall:"; curl -s -u $U $NC -o /dev/null -w 'GET netcfg HTTP %{http_code}\n'
rm -f /tmp/gw-bad.json
