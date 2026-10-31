#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd -P)"
SCRATCH="${MOJIAN_MAC_BUILD_ROOT:-/tmp/.agents/mojian-macos-client/build}"
MODE="${1:-build}"
cp "$ROOT/shared/page-backgrounds.json" "$ROOT/macos/Sources/MojianDesktop/Resources/page-backgrounds.json"
if [[ "$MODE" == "test" ]]; then
    swift test --package-path "$ROOT/ios" --scratch-path "${SCRATCH}-core"
    swift test --package-path "$ROOT/macos" --scratch-path "$SCRATCH"
    exit
fi
swift build --package-path "$ROOT/macos" --scratch-path "$SCRATCH" --configuration release
BIN="$(swift build --package-path "$ROOT/macos" --scratch-path "$SCRATCH" --configuration release --show-bin-path)"
OUT="$ROOT/macos/artifacts/current"
STAGE="$(mktemp -d /tmp/.agents/mojian-macos-package.XXXXXX)"
trap 'rm -rf "$STAGE"' EXIT
APP="$STAGE/Mojian.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN/Mojian" "$APP/Contents/MacOS/Mojian"
cp "$ROOT/macos/Info.plist" "$APP/Contents/Info.plist"
cp "$ROOT/shared/branding/Mojian_macOS.icns" "$APP/Contents/Resources/Mojian.icns"
for RESOURCE in "$BIN"/*.bundle; do cp -R "$RESOURCE" "$APP/Contents/Resources/"; done
cp "$ROOT/ios/THIRD_PARTY_NOTICES.md" "$APP/Contents/Resources/THIRD_PARTY_NOTICES.md"
codesign --force --sign - "$APP"
codesign --verify --deep --strict "$APP"
plutil -lint "$APP/Contents/Info.plist"
if [[ -d "$OUT/Mojian.app" ]] && pgrep -f "$OUT/Mojian.app/Contents/MacOS/Mojian" >/dev/null; then
    echo "The current Mojian.app is running; close it normally before replacing the deliverable." >&2
    exit 1
fi
mkdir -p "$OUT"
if [[ -d "$OUT/Mojian.app" ]]; then rm -rf "$OUT/Mojian.app"; fi
mv "$APP" "$OUT/Mojian.app"
VERSION="$(cat "$ROOT/macos/VERSION")"
ARCHIVE="$OUT/Mojian-macOS-arm64-$VERSION.zip"
if [[ -f "$ARCHIVE" ]]; then rm "$ARCHIVE"; fi
ditto -c -k --keepParent "$OUT/Mojian.app" "$ARCHIVE"
echo "$OUT/Mojian.app"
echo "$ARCHIVE"
