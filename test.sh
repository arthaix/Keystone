#!/usr/bin/env bash
# Runs the unit tests in src/test/java against build/classes (run ./build.sh first). Besides the build jars it needs
# libs/log4j-core-2.17.1.jar: Minecraft's BlockPos logs through log4j when its class loads.
set -euo pipefail
cd "$(dirname "$0")"

JDK="${JAVA8_HOME:?set JAVA8_HOME to a JDK 8}"
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac
[ -d build/classes ] || { echo "run ./build.sh first"; exit 1; }

CP="build/classes"
for jar in forge-1.12.2-srg.jar LittleTiles_v1.5.14_mc1.12.2.jar CreativeCore_v1.10.61_mc1.12.2.jar fastutil-7.1.0.jar \
           guava-21.0.jar log4j-api-2.17.1.jar log4j-core-2.17.1.jar; do
    [ -f "libs/$jar" ] || { echo "missing libs/$jar (see README, Building)"; exit 1; }
    CP="$CP${SEP}libs/$jar"
done

rm -rf build/test
mkdir -p build/test
"$JDK/bin/javac" -proc:none -source 8 -target 8 -encoding UTF-8 -nowarn -cp "$CP" -d build/test src/test/java/*.java
for t in DeferredRemovalListTest NeighborDedupTest NbtGroupsTest GeometryPackerTest NbtSizeTest PackIconSpritesTest VertexLayoutTest SignAngleTest; do
    echo "== $t"
    "$JDK/bin/java" -Xmx3g -cp "build/test${SEP}$CP" "$t"
done
