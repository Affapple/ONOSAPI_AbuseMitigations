#!/bin/bash
# Laptop: write harness/build-info.json (git commit, dirty flag, md5 of the local gateway .oar)
# and copy harness/ and scenarios/ to the VM (~/project). Run after every harness/scenario change.
set -eu
P=$(cd "$(dirname "$0")/.." && pwd)
cd "$P"
oar=$(ls gateway/target/*.oar 2>/dev/null | head -n 1 || true)
cat > harness/build-info.json <<EOF
{"gitCommit": "$(git rev-parse HEAD)", "gitDirty": $([ -n "$(git status --porcelain)" ] && echo true || echo false),
 "laptopOarMd5": "$([ -n "$oar" ] && md5sum "$oar" | cut -c1-32)", "deployedAt": "$(date -Is)"}
EOF
rm -rf harness/__pycache__ scenarios/__pycache__
tools/vm.sh 'rm -rf ~/project/harness ~/project/scenarios && mkdir -p ~/project/results'
tools/vm-cp.sh harness project/
tools/vm-cp.sh scenarios project/
echo "deployed; build-info: $(cat harness/build-info.json | tr -d '\n')"
