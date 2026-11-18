#!/bin/bash
# Run matrix on the VM, resumable: run.sh skips runs that already have a valid meta.json, so
# re-running this script continues where it stopped (and redoes invalid runs).
#
#   CONFIGS="C1 C2 C3" WORKLOADS_TO_RUN="baseline S1 S2 S3 S4" REPS=5 harness/matrix.sh
#
# Order rep -> workload -> config, so drift spreads evenly over the configs. ~4.5 min per run.
# Detach it from the ssh session:
#   setsid nohup ~/project/harness/matrix.sh > ~/project/results/matrix.log 2>&1 < /dev/null &
# Stops at a setup/restore failure (run.sh exit 1 or 2); invalid runs (exit 3) are counted.
# Other env (WORKLOADS, RESULTS, RESTART, ...) is passed on to run.sh.
set -u
H=$(cd "$(dirname "$0")" && pwd)
inv=0
for rep in $(seq 1 "${REPS:-5}"); do
    for w in ${WORKLOADS_TO_RUN:-baseline S1 S2 S3 S4}; do
        for c in ${CONFIGS:-C1 C2 C3}; do
            echo "=== $(date -Is) $c $w $rep"
            "$H/run.sh" "$c" "$w" "$rep"
            case $? in
                0) ;;
                3) inv=$((inv + 1)) ;;
                *) echo "=== $c $w $rep failed: matrix stopped" >&2; exit 1 ;;
            esac
        done
    done
done
echo "=== matrix done, $inv invalid run(s) (re-run the matrix to redo them)"
[ $inv = 0 ]
