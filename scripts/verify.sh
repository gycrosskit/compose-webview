#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
: "${WEBVIEW_IOS_RENDER_FRAMEWORK_DIR:?Pass the real iphoneos Kuikly Render framework directory}"
bash gradlew testDebugUnitTest :webview-core:testDebugUnitTest :webview-kuikly:compileDebugKotlinAndroid :webview-kuikly:compileKotlinIosArm64 :webview-kuikly:compileKotlinIosSimulatorArm64 :webview-kuikly:compileKotlinOhosArm64 --max-workers=1
bash scripts/package-maven.sh
bash gradlew -p verification-consumer assembleDebug verifyNoCmpUi compileKotlinIosSimulatorArm64 linkDebugFrameworkIosArm64 linkDebugSharedOhosArm64 "-PrenderFrameworkDir=$WEBVIEW_IOS_RENDER_FRAMEWORK_DIR" --refresh-dependencies --max-workers=1
bash gradlew -p verification-consumer -PverifyCmp=true assembleDebug compileKotlinIosSimulatorArm64 --max-workers=1
bash scripts/verify-ios-native.sh
bash ohos/scripts/verify-har.sh
