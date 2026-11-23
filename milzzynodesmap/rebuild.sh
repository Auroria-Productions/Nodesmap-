#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
javac=javac
java=java
if [[ -n ${JAVA_HOME:-} ]]; then javac="$JAVA_HOME/bin/javac"; java="$JAVA_HOME/bin/java"; fi
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/addon" "$tmp/tools" dist
"$javac" --release 21 -cp 'base.jar:deps/*' -d "$tmp/addon" src/phonon/nodesmap/compat/*.java
"$javac" --release 21 -cp 'deps/*' -d "$tmp/tools" tools/Build.java
"$java" -cp "$tmp/tools:deps/*" Build base.jar "$tmp/addon" web dist/milzzynodesmap.jar
