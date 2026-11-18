#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${VERSION:?Pass an immutable released Maven version}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?$ ]] || { echo "Invalid release version" >&2; exit 1; }
output="$(mktemp -d)"
preserve_evidence() {
  status=$?
  diagnostics="${CI_DIAGNOSTICS_DIR:-ci-diagnostics}/public"
  mkdir -p "$diagnostics"
  printf 'version=%s\nexit=%s\n' "$VERSION" "$status" > "$diagnostics/result.txt"
  rm -rf "$output"
  exit "$status"
}
trap preserve_evidence EXIT
# 从固定公开仓库取准确的 tag 提交和 tree，使用与 JitPack 相同的回执和归档校验。
repo_root="$PWD"
git init -q "$output/tag"
git -C "$output/tag" fetch -q --no-tags --depth=1 https://github.com/gycrosskit/compose-webview.git "refs/tags/$VERSION"
git -C "$output/tag" checkout -q --detach FETCH_HEAD
commit="$(git -C "$output/tag" rev-parse --verify HEAD)"
(cd "$output/tag" && bash "$repo_root/jitpack-install.sh" compose-webview "$output/maven")
python3 scripts/check-maven.py "$output/maven" com.github.gycrosskit.compose-webview "$VERSION" webview-core,webview-kuikly ios_arm64,ios_x64,ios_simulator_arm64,ohos_arm64
# 消费方只使用 JitPack；归档校验不会安装到 MavenLocal 或替代远程解析。

# 预期 inventory 来自通过冻结 SHA 的归档，不从 JitPack 自报 inventory 反推。
publications="$(python3 - "$output/maven" <<'PYTHON'
import sys
from pathlib import Path
print(','.join(sorted(path.parent.parent.name for path in Path(sys.argv[1]).rglob('*.module'))))
PYTHON
)"
python3 scripts/check-public-maven.py --repo compose-webview --version "$VERSION" --commit "$commit" --expected-publications "$publications" --output-dir "${CI_DIAGNOSTICS_DIR:-ci-diagnostics}/public"
