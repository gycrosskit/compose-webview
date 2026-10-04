#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# staging 为本脚本生成的临时仓库，避免归档夹带旧构建版本。
rm -rf build/maven
bash gradlew publishAllPublicationsToStagingRepository --max-workers=1
python3 jitpack-metadata.py build/maven/com/github/gycrosskit/compose-webview
python3 scripts/check-maven.py build/maven com.github.gycrosskit.compose-webview "${VERSION:-0.2.0-rc.4}" compose-webview,webview-core,webview-kuikly ios_arm64,ios_x64,ios_simulator_arm64,ohos_arm64
COPYFILE_DISABLE=1 tar --no-xattrs -czf build/compose-webview-maven.tar.gz -C build/maven com
shasum -a 256 build/compose-webview-maven.tar.gz
