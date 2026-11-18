#!/bin/sh
# Copy local files/dirs to the VM: tools/vm-cp.sh <local...> <remote-dir-or-path>
#   remote path is relative to /home/mininet unless absolute.
D=$(cd "$(dirname "$0")" && pwd)
dest=$(eval echo "\${$#}")
n=$(($# - 1)); srcs=""
i=0; for a in "$@"; do i=$((i+1)); [ $i -le $n ] && srcs="$srcs '$a'"; done
eval SSH_ASKPASS=$D/askpass.sh SSH_ASKPASS_REQUIRE=force DISPLAY=:0 \
  scp -o StrictHostKeyChecking=accept-new -o LogLevel=ERROR -P 2222 -r $srcs "mininet@localhost:$dest"
