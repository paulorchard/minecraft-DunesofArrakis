#!/usr/bin/env bash
# Generate the pure-dune comparison windows with the OLD build (commit 8ec4142, a git worktree next to the project).
#   tools/rewrite/run-old.sh      (run from anywhere; takes about 15 minutes)
set -e
OLD="$(cd "$(dirname "$0")/../../.." && pwd)/DunesOld8ec"; cd "$OLD"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
for spec in "far1_s1 1 -3024 -3024 2048" "far2_s1 1 18976 18976 2048" "far1_s2 2 -2512 -2512 1024" "far1_s3 3 -2512 -2512 1024"; do
  set -- $spec; rm -rf run-server/old_$1; tools/worldscan/gen.sh old_$1 $2 $3 $4 $5 | tail -1
done
