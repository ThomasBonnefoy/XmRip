#!/bin/sh
# Lance le pont RFCOMM -> TCP (macOS). Compilé au premier lancement, hors du dépôt (dossier synchronisé).
#
# macOS attribue l'accès Bluetooth à l'application « responsable » du processus (le terminal, ou Claude), pas à
# l'exécutable lui-même : lancé en direct, il est tué faute de description d'usage. Le pont est donc empaqueté dans
# une mini-app et lancé via `open`, ce qui en fait sa propre application responsable. Au premier lancement, macOS
# demande d'autoriser le Bluetooth pour « rfcomm_bridge » : accepter.
set -e
dir=$(cd "$(dirname "$0")" && pwd)
out="${XMRIP_BRIDGE_DIR:-$HOME/Library/Caches/xmrip-bridge}"
app="$out/XmRipBridge.app"
bin="$app/Contents/MacOS/rfcomm_bridge"
if [ ! -x "$bin" ] || [ "$dir/Bridge.swift" -nt "$bin" ] || [ "$dir/main.swift" -nt "$bin" ] || [ "$dir/Info.plist" -nt "$bin" ]; then
    mkdir -p "$app/Contents/MacOS"
    cp "$dir/Info.plist" "$app/Contents/Info.plist"
    swiftc -swift-version 5 -O "$dir/Bridge.swift" "$dir/main.swift" -o "$bin"
    codesign --force --sign - "$app" >/dev/null 2>&1
fi

log=$(mktemp -t xmrip-bridge)
stop() {
    pkill -f "$bin" 2>/dev/null || true
    [ -n "$tailpid" ] && kill "$tailpid" 2>/dev/null
    rm -f "$log"
}
tailpid=
trap 'stop; exit 0' INT TERM
open -W -n --stdout "$log" --stderr "$log" "$app" --args "$@" &
openpid=$!
tail -f "$log" &
tailpid=$!
wait "$openpid" || true
sleep 0.3
stop
