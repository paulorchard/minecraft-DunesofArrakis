#!/bin/bash
# Generate a square area on the dev server and time it.
#   gen.sh <world-name> <seed> <x0> <z0> <size> [dimension]
#   x0, z0, size must be multiples of 16; the area is force-loaded in 256x256-block tiles (the /forceload limit).
#   Default dimension: islandcraft_dunesofarrakis:arrakis.  Use minecraft:overworld for the vanilla check.
# Output: the world in run-server/<world-name>/, the server log in run-server/worldscan_<world-name>.log and
#   run-server/worldscan_<world-name>.timing (seconds from the first /forceload until a 3x3 probe grid is loaded).
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/temurin-25.0.5}"
W=$1; SEED=$2; X0=$3; Z0=$4; SIZE=$5; DIM=${6:-islandcraft_dunesofarrakis:arrakis}
LOG=run-server/logs/latest.log; TIMING=run-server/worldscan_$W.timing
cp run-server/server.properties run-server/server.properties.worldscan-backup
trap 'mv -f run-server/server.properties.worldscan-backup run-server/server.properties; rm -f run-server/mods/*.jar' EXIT
rm -f run-server/mods/*.jar
sed -i "s/^level-name=.*/level-name=$W/; s/^level-seed=.*/level-seed=$SEED/; s/^max-tick-time=.*/max-tick-time=-1/; s/^gamemode=.*/gamemode=creative/" run-server/server.properties
rm -f $LOG
probe_points() { for fx in 8 $((SIZE/2)) $((SIZE-8)); do for fz in 8 $((SIZE/2)) $((SIZE-8)); do echo "$((X0+fx)) $((Z0+fz))"; done; done; }
(
  for i in $(seq 1 240); do grep -q "Done (" $LOG 2>/dev/null && break; sleep 1; done; sleep 1
  T0=$(date +%s.%N)
  # ORDER=reverse loads the 256 x 256 tiles in the opposite order (the chunk-order independence check of prompt 08)
  ( for ((x=X0; x<X0+SIZE; x+=256)); do for ((z=Z0; z<Z0+SIZE; z+=256)); do
    ex=$((x+255)); [ $ex -ge $((X0+SIZE)) ] && ex=$((X0+SIZE-1)); ez=$((z+255)); [ $ez -ge $((Z0+SIZE)) ] && ez=$((Z0+SIZE-1))
    echo "execute in $DIM run forceload add $x $z $ex $ez"
  done; done ) | if [ "${ORDER:-}" = reverse ]; then tac; else cat; fi
  n=0; DONE=0
  while [ $n -lt 1200 ]; do
    n=$((n+1)); k=0
    probe_points | while read px pz; do k=$((k+1)); echo "execute in $DIM if loaded $px 64 $pz run say PROBE${n}_${k}"; done
    sleep 0.5
    c=$(grep -c "PROBE${n}_" $LOG || true)
    if [ "$c" -ge 9 ]; then T1=$(date +%s.%N); echo "$T0 $T1" > $TIMING.raw; DONE=1; break; fi
  done
  sleep 1; echo "save-all flush"; sleep 6; echo stop
) | ./gradlew runServer --console=plain > "run-server/worldscan_$W.out" 2>&1
cp $LOG "run-server/worldscan_$W.log"
if [ -f $TIMING.raw ]; then read a b < $TIMING.raw; rm $TIMING.raw; awk -v a=$a -v b=$b -v n=$(( (SIZE/16)*(SIZE/16) )) 'BEGIN{printf "chunks %d  seconds %.1f  ms_per_chunk %.2f\n", n, b-a, (b-a)*1000/n}' | tee $TIMING; else echo "timing: probe grid never completed" | tee $TIMING; fi
