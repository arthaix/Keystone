#!/usr/bin/env bash
# Builds build/z-keystone-<version>.jar. See README ("Building").
set -euo pipefail
cd "$(dirname "$0")"

JDK="${JAVA8_HOME:?set JAVA8_HOME to a JDK 8}"
VERSION=$(sed -n 's/.*VERSION = "\([^"]*\)".*/\1/p' src/main/java/ru/arthaix/keystone/Keystone.java | head -1)
AFTERIMAGE_VERSION=$(sed -n 's/.*VERSION = "\([^"]*\)".*/\1/p' src/mod/java/ru/arthaix/afterimage/Afterimage.java | head -1)
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac

# SRG-named Minecraft first: the sources call Minecraft by SRG names and run against the obfuscated runtime. The dev jar
# after it only supplies Forge classes with readable Minecraft class names. The other mods are needed to compile only.
JARS="mixinbooter-10.7.jar forge-1.12.2-srg.jar forge-1.12.2-dev.jar forge-1.12.2-universal.jar
      LittleTiles_v1.5.14_mc1.12.2.jar CreativeCore_v1.10.61_mc1.12.2.jar chiselsandbits-14.33.jar
      UniversalModCore-1.12.2-forge-1.1.4-580823d.jar OnlinePicFrame_v1.5.0-pre1_mc1.12.2.jar log4j-api-2.17.1.jar
      journeymap_1.12.2_5.7.1.jar
      lwjgl-2.9.4.jar netty-all-4.1.9.Final.jar fastutil-7.1.0.jar guava-21.0.jar"
CP=""
for jar in $JARS; do
    [ -f "libs/$jar" ] || { echo "missing libs/$jar (see README, Building)"; exit 1; }
    CP="${CP:+$CP$SEP}libs/$jar"
done

CLASSES=build/classes
rm -rf build
mkdir -p "$CLASSES"

# pass 1: everything that touches Minecraft
"$JDK/bin/javac" -proc:none -source 8 -target 8 -encoding UTF-8 -nowarn -cp "$CP" -d "$CLASSES" $(find src/main/java -name '*.java')

# pass 2: Afterimage's mod classes, which only use Forge's own API, compiled against the dev jar
"$JDK/bin/javac" -proc:none -source 8 -target 8 -encoding UTF-8 -nowarn \
    -cp "${CLASSES}${SEP}libs/forge-1.12.2-dev.jar${SEP}libs/netty-all-4.1.9.Final.jar${SEP}libs/fastutil-7.1.0.jar${SEP}libs/guava-21.0.jar" \
    -d "$CLASSES" $(find src/mod/java -name '*.java')

cp src/main/resources/mixins.*.json "$CLASSES/"
sed -e "s/\${version}/$VERSION/" -e "s/\${afterimage_version}/$AFTERIMAGE_VERSION/" src/main/resources/mcmod.info > "$CLASSES/mcmod.info"
# FML loads coremod jars in file-name order and this one needs MixinBooter already loaded,
# so the file name has to sort after "mixinbooter"
JAR="build/z-keystone-$VERSION.jar"
"$JDK/bin/jar" cfm "$JAR" src/main/resources/META-INF/MANIFEST.MF -C "$CLASSES" .
echo "built $JAR (Afterimage $AFTERIMAGE_VERSION)"
