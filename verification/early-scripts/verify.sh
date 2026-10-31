#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# 既有 Android unit test 编译并调用生产 generator，Gradle 将导出目录登记为测试输出。
scripts=build/early-scripts/testDebugUnitTest
for name in production page channels; do
    if [[ ! -s "$scripts/$name.js" ]]; then
        echo "Missing $scripts/$name.js; run :webview-core:testDebugUnitTest first" >&2
        exit 1
    fi
done
node verification/early-scripts/verify.cjs "$scripts/production.js"
node verification/early-scripts/verify.cjs "$scripts/page.js" page
node verification/early-scripts/page-messages.cjs "$scripts/channels.js"
