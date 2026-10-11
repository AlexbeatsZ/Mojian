#!/bin/bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
derived="${MOJIAN_IOS_BUILD_DIR:-/tmp/.agents/mojian-ios-xcode}"
mode="${1:-simulator}"
case "$mode" in
  test)
    cd "$project_dir"
    swift test --scratch-path "${MOJIAN_IOS_TEST_DIR:-/tmp/.agents/mojian-ios-swift}"
    ;;
  simulator)
    xcodebuild -project "$project_dir/Mojian.xcodeproj" -scheme Mojian -configuration Release \
      -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
      -derivedDataPath "$derived" CODE_SIGNING_ALLOWED=NO build
    mkdir -p "$project_dir/artifacts/current"
    ditto "$derived/Build/Products/Release-iphonesimulator/Mojian.app" "$project_dir/artifacts/current/Mojian-simulator.app"
    ;;
  device)
    xcodebuild -project "$project_dir/Mojian.xcodeproj" -scheme Mojian -configuration Release \
      -sdk iphoneos -destination 'generic/platform=iOS' \
      -derivedDataPath "$derived" CODE_SIGNING_ALLOWED=NO build
    ;;
  archive)
    : "${MOJIAN_APPLE_TEAM:?Set MOJIAN_APPLE_TEAM to your development team ID}"
    xcodebuild -project "$project_dir/Mojian.xcodeproj" -scheme Mojian -configuration Release \
      -destination 'generic/platform=iOS' -derivedDataPath "$derived" \
      -archivePath "$project_dir/artifacts/current/Mojian.xcarchive" \
      DEVELOPMENT_TEAM="$MOJIAN_APPLE_TEAM" archive
    ;;
  *) printf 'Usage: %s test|simulator|device|archive\n' "$0" >&2; exit 2 ;;
esac
