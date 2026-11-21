#!/usr/bin/env bash
# Run on the Inspiron: download the latest CI build, sign it with the local key, install it over adb.
#   newport-planner/tools/install.sh [adb-device]     e.g. 192.168.1.48:37201
# The key lives only in ~/.config/planner-signing/ (planner.p12 + password.txt). Losing it means
# the next build can't update the installed app: uninstall once (the app restores from vault/planner/state.json).
set -euo pipefail
KEYDIR="$HOME/.config/planner-signing"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
gh release download planner-latest -R AlonnGreenn/days-since-widget -p planner-unsigned.apk -D "$WORK"
apksigner sign --ks "$KEYDIR/planner.p12" --ks-type PKCS12 --ks-pass "file:$KEYDIR/password.txt" \
  --out "$WORK/planner.apk" "$WORK/planner-unsigned.apk"
apksigner verify "$WORK/planner.apk"
cp "$WORK/planner.apk" "$HOME/planner-latest.apk"
adb ${1:+-s "$1"} install -r "$WORK/planner.apk"
