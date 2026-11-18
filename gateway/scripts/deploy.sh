#!/bin/sh
# Runs ON THE LAPTOP. Copies the built .oar, the VM scripts and the tenant registry
# to ~/gw on the VM, then reinstalls the app and pushes the registry under the
# ONOS lock.
#
#   cd gateway && JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 mvn clean install
#   scripts/deploy.sh [tenants.json]
#
# Tenant registry: argument, else ../topology/tenants.json (Track A), else the
# test fixture src/test/resources/tenants.json.
set -eu
GW=$(cd "$(dirname "$0")/.." && pwd)
TOOLS=$GW/../tools
OAR=$GW/target/api_gateway-1.0-SNAPSHOT.oar

if [ $# -ge 1 ]; then
    TENANTS=$1
elif [ -f "$GW/../topology/tenants.json" ]; then
    TENANTS=$GW/../topology/tenants.json
else
    TENANTS=$GW/src/test/resources/tenants.json
fi
echo "tenant registry: $TENANTS"
[ -f "$OAR" ] || { echo "missing $OAR; build first" >&2; exit 1; }

"$TOOLS/vm.sh" 'mkdir -p ~/gw'
"$TOOLS/vm-cp.sh" "$OAR" "$GW/scripts/vm-install.sh" "$GW/scripts/vm-push-tenants.sh" gw/
"$TOOLS/vm-cp.sh" "$TENANTS" gw/tenants.json
"$TOOLS/vm.sh" 'flock /tmp/sdn-onos.lock sh ~/gw/vm-install.sh ~/gw/api_gateway-1.0-SNAPSHOT.oar ~/gw/tenants.json'
