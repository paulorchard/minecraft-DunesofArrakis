#!/usr/bin/env bash
# One generation timing for the cost question (prompt 25, task 7). Usage: tools/model/timing.sh <true|false> [seed]
# Regenerates the data with dune_enabled = <arg>, starts the dev server on a fresh world with a fixed seed, force-loads 21 x 21 chunks around 0,0
# (two forceload commands: the limit is 256 chunks each) and polls with an "if loaded" test on the four corner chunks every 0.3 s.
# Prints the time from the forceload command to the first successful poll. Restores run-server/server.properties and ocean.json afterwards.
set -u
cd "$(dirname "$0")/../.."
ENABLED=$1; SEED=${2:-2}
export JAVA_HOME="$HOME/.jdks/temurin-25.0.5"
cp run-server/server.properties "$TEMP/server.properties.timing.bak"; cp tools/rewrite/ocean.json "$TEMP/ocean.timing.bak"
sed -i "s/\"dune_enabled\": [a-z]*/\"dune_enabled\": $ENABLED/" tools/rewrite/ocean.json
node tools/rewrite/gen.js > /dev/null
sed -i 's/^level-type=.*/level-type=islandcraft_dunesofarrakis\\:arrakis/; s/^level-name=.*/level-name=timing/; s/^max-tick-time=.*/max-tick-time=-1/' run-server/server.properties
sed -i "s/^level-seed=.*/level-seed=$SEED/" run-server/server.properties
rm -rf run-server/timing run-server/logs/latest.log
(
  for i in $(seq 1 100); do grep -q "Done (" run-server/logs/latest.log 2>/dev/null && break; sleep 1; done
  sleep 2
  echo "forceload add -160 -160 175 -1"
  echo "forceload add -160 0 175 175"
  for i in $(seq 1 300); do
    echo "execute in minecraft:overworld if loaded 175 64 175 if loaded -160 64 -160 if loaded 0 64 175 if loaded 175 64 -160 if loaded 0 64 -160 run say TIMING_DONE"
    sleep 0.3
  done
  echo stop
) | timeout 115 ./gradlew runServer --console=plain > "$TEMP/timing.out" 2>&1
cp "$TEMP/server.properties.timing.bak" run-server/server.properties; cp "$TEMP/ocean.timing.bak" tools/rewrite/ocean.json
LOG=run-server/logs/latest.log
t0=$(grep -m1 "Marked .* chunks" "$LOG" | sed -E 's/^\[[0-9A-Za-z]+ ([0-9:.]+)\].*/\1/')
t1=$(grep -m1 "TIMING_DONE" "$LOG" | sed -E 's/^\[[0-9A-Za-z]+ ([0-9:.]+)\].*/\1/')
echo "dune_enabled=$ENABLED seed=$SEED: forceload at $t0, all corners loaded at $t1"
node -e '
const p=s=>{const [h,m,x]=s.split(":");return (+h)*3600+(+m)*60+(+x)};
const a="'"$t0"'",b="'"$t1"'"; if(a&&b) console.log("generation time of 21 x 21 chunks: "+(p(b)-p(a)).toFixed(1)+" s (poll resolution 0.3 s)"); else console.log("NO RESULT");'
grep -E "ERROR|FATAL|Exception" "$LOG" | head -5
rm -rf run-server/timing
node tools/rewrite/gen.js > /dev/null
