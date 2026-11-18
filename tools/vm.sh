#!/bin/bash
# Run a command on the Mininet VM: tools/vm.sh '<command>'
# stdout is untouched; only ssh's post-quantum warning is filtered from stderr; exit code = remote exit code.
D=$(cd "$(dirname "$0")" && pwd)
SSH_ASKPASS=$D/askpass.sh SSH_ASKPASS_REQUIRE=force DISPLAY=:0 \
  ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -o LogLevel=ERROR -p 2222 mininet@localhost "$@" </dev/null \
  2> >(grep -v -E 'post-quantum|store now, decrypt later|openssh.com/pq' >&2)
