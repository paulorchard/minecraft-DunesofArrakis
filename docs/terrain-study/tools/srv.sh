#!/bin/bash
# usage: srv.sh <worldname> <seed> <cmdfile> <mod jars...>
cd /c/Apps/IslandCraft-MC/DunesOfArrakis/DunesOfArrakis
export JAVA_HOME="$(cygpath -u "$USERPROFILE")/.jdks/temurin-25.0.5"
W=$1; SEED=$2; CMDS=$3; shift 3
rm -f run-server/mods/*.jar
for j in "$@"; do cp "$TEMP/jars/$j" run-server/mods/; done
cp "$TEMP/bk/server.properties.orig" run-server/server.properties
sed -i "s/^level-name=.*/level-name=$W/; s/^level-seed=.*/level-seed=$SEED/; s/^max-tick-time=.*/max-tick-time=-1/; s/^gamemode=.*/gamemode=creative/" run-server/server.properties
grep -q "^level-seed" run-server/server.properties || echo "level-seed=$SEED" >> run-server/server.properties
rm -f run-server/logs/latest.log
( # feeder: wait for Done then send commands
  for i in $(seq 1 240); do grep -q "Done (" run-server/logs/latest.log 2>/dev/null && break; sleep 2; done
  sleep 2
  while IFS= read -r line; do
    if [[ "$line" == sleep* ]]; then sleep ${line#sleep }; else echo "$line"; fi
  done < "$CMDS"
  echo stop
) | ./gradlew runServer --console=plain > "$TEMP/srv_$W.out" 2>&1
cp run-server/logs/latest.log "$TEMP/log_$W.log" 2>/dev/null
rm -f run-server/mods/*.jar
