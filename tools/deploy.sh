#!/usr/bin/env bash
# Build the mod and copy the jar into the CurseForge test instance (replacing any older copy of this mod).
# Usage: tools/deploy.sh [instance-dir]      (close the game first: Windows locks a loaded jar)
set -e
cd "$(dirname "$0")/.."
INSTANCE="${1:-$USERPROFILE/curseforge/minecraft/Instances/ArrakisTest01}"
export JAVA_HOME="${JAVA_HOME:-$USERPROFILE/.jdks/temurin-25.0.5}"
./gradlew build --console=plain -q
rm -f "$INSTANCE"/mods/IslandCraft-DunesOfArrakis-*.jar
cp build/libs/IslandCraft-DunesOfArrakis-*.jar "$INSTANCE/mods/"
ls -la "$INSTANCE"/mods/IslandCraft-DunesOfArrakis-*.jar
