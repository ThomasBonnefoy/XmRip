#!/bin/sh
# Compile l'app macOS « XmRip Banc » dans dist/ (ignoré par git). Option --open : la lance ensuite.
# Il faut Xcode ou les Command Line Tools ; le JDK est cherché à l'exécution (tools/env.sh).
set -e
dir=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$dir/../.." && pwd)
app="$root/dist/XmRip Banc.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$dir/Info.plist" "$app/Contents/Info.plist"
printf '%s\n' "$root" > "$app/Contents/Resources/root.txt"
# Photos du casque, les mêmes que l'app Android (une par couleur, choisie d'après la couleur annoncée par le casque).
cp "$root"/app/src/main/res/drawable-nodpi/headphones_*.webp "$app/Contents/Resources/"
swiftc -swift-version 5 -O -parse-as-library "$dir/App.swift" "$dir/../pc-bridge/Bridge.swift" -o "$app/Contents/MacOS/XmRipBanc"
codesign --force --sign - "$app" >/dev/null 2>&1
echo "App : $app"
[ "$1" = "--open" ] && open "$app"
exit 0
