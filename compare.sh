#!/bin/bash
#
# Compare every implementation against the same dataset.
#
# main holds the latest implementation (divided_workers), the generator and
# this harness. The comparable baselines live on the impl/* branches and are
# benchmarked from fresh local clones (this box ships git 1.8, which predates
# git worktree). Every tree is benchmarked by benchmark.py with the same runs
# on the same input file, and results are appended to results/<timestamp>.csv.
#
# Usage:   ./compare.sh [dataset]   (default: weather_stations.csv)
# Env:     RUNS=N   measured runs per implementation (default 5)
#          IMPLS=   space-separated impl/* branches (default all four)
#
set -euo pipefail

REPO=$(cd "$(dirname "$0")" && pwd -P)
RUNS=${RUNS:-5}
IMPLS=${IMPLS:-"impl/naive impl/naive2 impl/parallel impl/python"}

DATASET=${1:-}
ARGS=()
if [ -n "$DATASET" ]; then
  ARGS+=("$DATASET")
fi

mkdir -p "$REPO/results"
STAMP=$(date +%Y%m%d-%H%M%S)
RESULTS="$REPO/results/$STAMP.csv"
echo "timestamp,tree,implementation,median_seconds,checksum" > "$RESULTS"

record() {  # $1 label; runs benchmark.py in the current directory
  local out
  echo "=== $1 ==="
  out=$(python3 "$REPO/benchmark.py" --runs "$RUNS" --tree "$2" ${ARGS+"${ARGS[@]}"}
        2>&1) || { echo "$out"; exit 1; }
  echo "$out"
  echo "$out" | grep '^RESULT' | sed "s/^RESULT,/${STAMP},$1,/" >> "$RESULTS"
}

record main "$REPO"

SCRATCH=$(mktemp -d "${TMPDIR:-/tmp}/1brc-compare-XXXXXX")
trap 'rm -rf "$SCRATCH"' EXIT

for branch in $IMPLS; do
  name=${branch#impl/}
  tree="$SCRATCH/$name"
  git clone -q "$REPO" "$tree"
  (cd "$tree" && git checkout -q "$branch")
  record "$name" "$tree"
done

echo
echo "Results appended to $RESULTS:"
tail -n +2 "$RESULTS" | sort -t, -k4 -g | awk -F, \
  '{printf "%-20s %-10.3f s  checksum %s\n", $2, $3, substr($4, 1, 12)}'
