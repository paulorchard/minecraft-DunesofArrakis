#!/bin/bash
# Run the dev server once with a file of console commands. Generic driver used by gen.sh.
#   srv.sh <world-name> <seed> <commands-file> [mod-jar-file-names...]
# Commands file: one console command per line; a line "sleep N" waits N seconds; "stop" is appended automatically.
# Jars are looked up in $MODS_SRC (default: run-server/study-downloads) and copied to run-server/mods for the run only.
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
W=$1; SEED=$2; CMDS=$3; shift 3
MODS_SRC="${MODS_SRC:-$ROOT/run-server/study-downloads}"
mkdir -p run-server/mods; rm -f run-server/mods/*.jar
for j in "$@"; do cp "$MODS_SRC/$j" run-server/mods/; done
cp run-server/server.properties run-server/server.properties.worldscan-backup
trap 'mv -f run-server/server.properties.worldscan-backup run-server/server.properties; rm -f run-server/mods/*.jar' EXIT
sed -i "s/^level-name=.*/level-name=$W/; s/^level-seed=.*/level-seed=$SEED/; s/^max-tick-time=.*/max-tick-time=-1/; s/^gamemode=.*/gamemode=creative/" run-server/server.properties
rm -f run-server/logs/latest.log
( for i in $(seq 1 240); do grep -q "Done (" run-server/logs/latest.log 2>/dev/null && break; sleep 1; done; sleep 1
  while IFS= read -r line; do if [[ "$line" == sleep* ]]; then sleep "${line#sleep }"; else echo "$line"; fi; done < "$CMDS"
  echo stop ) | ./gradlew runServer --console=plain > "run-server/worldscan_$W.out" 2>&1
cp run-server/logs/latest.log "run-server/worldscan_$W.log"
