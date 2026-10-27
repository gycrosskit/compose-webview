#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export VERSION="${VERSION:-1.0.2}"
export GROUP=com.github.gycrosskit.compose-webview
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# staging 为本脚本生成的临时仓库，避免归档夹带旧构建版本。
rm -rf build/maven
bash gradlew "$@" --no-daemon --max-workers=1 -Dorg.gradle.parallel=false publishAllPublicationsToStagingRepository
python3 jitpack-metadata.py build/maven/com/github/gycrosskit/compose-webview
python3 scripts/check-maven.py build/maven com.github.gycrosskit.compose-webview "$VERSION" webview-core,webview-kuikly ios_arm64,ios_x64,ios_simulator_arm64,ohos_arm64 webview-core,webview-core-android,webview-core-iosarm64,webview-core-iosx64,webview-core-iossimulatorarm64,webview-core-ohosarm64,webview-kuikly,webview-kuikly-android,webview-kuikly-iosarm64,webview-kuikly-iosx64,webview-kuikly-iossimulatorarm64,webview-kuikly-ohosarm64
COPYFILE_DISABLE=1 tar --no-xattrs -czf build/compose-webview-maven.tar.gz -C build/maven com
(cd build && shasum -a 256 compose-webview-maven.tar.gz > compose-webview-maven.tar.gz.sha256 && cat compose-webview-maven.tar.gz.sha256)
