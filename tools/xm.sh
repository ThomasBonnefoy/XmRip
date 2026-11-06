#!/bin/sh
# Raccourci : tools/xm.sh battery nc eq ...   (le pont tools/pc-bridge/run.sh doit tourner)
# Configuration à la demande : le module :app (Android) n'est pas configuré, donc pas besoin du SDK Android.
root=$(cd "$(dirname "$0")/.." && pwd)
. "$root/tools/env.sh"
cd "$root" || exit 1
args=""
for a in "$@"; do args="$args $a"; done
exec sh ./gradlew -Dorg.gradle.configureondemand=true :desktop:run --console=plain -q "--args=${args# }"
