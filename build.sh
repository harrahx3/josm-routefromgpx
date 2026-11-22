#!/bin/sh
# Build routefromgpx.jar with javac against an installed JOSM (no Ant/Gradle needed).
#   ./build.sh            -> dist/routefromgpx.jar
#   ./build.sh test       -> build, then run the headless checks in test/
#   ./build.sh install    -> build, then copy the jar into the JOSM plugin folder
# JOSM_JAR (default /usr/share/josm/josm.jar) and JOSM_PLUGIN_DIR can be overridden.
set -e
cd "$(dirname "$0")"
JOSM_JAR=${JOSM_JAR:-/usr/share/josm/josm.jar}
VERSION=${VERSION:-$(git describe --tags --always --dirty 2>/dev/null || date +%Y%m%d)}
MAIN_VERSION=19000   # oldest JOSM version the plugin is meant for

rm -rf build && mkdir -p build/classes dist
javac --release 17 -encoding UTF-8 -Xlint:all,-serial -cp "$JOSM_JAR" -d build/classes $(find src -name '*.java')
cp -r images build/classes/
cat > build/MANIFEST.MF <<MF
Manifest-Version: 1.0
Plugin-Class: org.openstreetmap.josm.plugins.routefromgpx.RouteFromGpxPlugin
Plugin-Description: Create route relations (hiking, bicycle, mtb, horse...) from GPX tracks, one file, many files or a folder: map matching on the downloaded data, way splitting, gap and duplicate detection, common and per-route tags (with CSV import/export).
Plugin-Version: $VERSION
Plugin-Mainversion: $MAIN_VERSION
Plugin-Minimum-Java-Version: 17
Plugin-Canloadatruntime: true
Plugin-Icon: images/routefromgpx.svg
Plugin-Link: https://github.com/harrahx3/josm-routefromgpx
Author: routefromgpx contributors
MF
jar cfm dist/routefromgpx.jar build/MANIFEST.MF -C build/classes .
echo "built dist/routefromgpx.jar ($VERSION)"

case "$1" in
  test)
    mkdir -p build/test
    javac --release 17 -encoding UTF-8 -cp "$JOSM_JAR:build/classes" -d build/test $(find test -name '*.java')
    for t in $(cd test && find . -name '*Check.java' | sed 's#^\./##; s#\.java$##; s#/#.#g'); do
      java -Djava.awt.headless=true -cp "$JOSM_JAR:build/classes:build/test" "$t"
    done
    ;;
  install)
    DEST=${JOSM_PLUGIN_DIR:-$HOME/.local/share/JOSM/plugins}
    mkdir -p "$DEST"
    cp dist/routefromgpx.jar "$DEST/routefromgpx.jar"
    echo "installed $DEST/routefromgpx.jar (enable it in Preferences > Plugins if it is not yet)"
    ;;
esac
