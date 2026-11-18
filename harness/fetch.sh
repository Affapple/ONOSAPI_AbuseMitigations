#!/bin/bash
# Laptop: copy the VM's ~/project/results/ into results/ (incremental, rsync over the VM ssh).
set -eu
H=$(cd "$(dirname "$0")" && pwd)
P=$(cd "$H/.." && pwd)
export SSH_ASKPASS=$P/tools/askpass.sh SSH_ASKPASS_REQUIRE=force DISPLAY=:0
mkdir -p "$P/results"
rsync -a -e "ssh -p 2222 -o StrictHostKeyChecking=accept-new -o LogLevel=ERROR" \
    mininet@localhost:project/results/ "$P/results/" < /dev/null
echo "fetched into $P/results"
