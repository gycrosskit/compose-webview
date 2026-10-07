#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${VERSION:?Pass an immutable released Maven version}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?$ ]] || { echo "Invalid release version" >&2; exit 1; }
checksum="$(awk -v version="$VERSION" '$1 == version {print $2}' release-checksums.txt)"
[[ "$checksum" =~ ^[a-f0-9]{64}$ ]] || { echo "Missing frozen archive checksum for $VERSION" >&2; exit 1; }
output="$(mktemp -d)"
trap 'rm -rf "$output"' EXIT
archive="$output/compose-webview-maven.tar.gz"
curl --fail --location --retry 3 --connect-timeout 30 --max-time 300 -o "$archive" "https://github.com/gycrosskit/compose-webview/releases/download/$VERSION/compose-webview-maven.tar.gz"
printf '%s  %s\n' "$checksum" "$archive" | shasum -a 256 --check
python3 - "$archive" "$output/maven" <<'PYTHON'
import sys, tarfile
from pathlib import Path
root = Path(sys.argv[2]); root.mkdir()
with tarfile.open(sys.argv[1]) as archive:
    for entry in archive.getmembers():
        target = (root / entry.name).resolve()
        if (target != root.resolve() and root.resolve() not in target.parents) or not (entry.isfile() or entry.isdir()):
            raise ValueError(f"Unsafe archive member: {entry.name}")
    archive.extractall(root)
PYTHON
python3 scripts/check-maven.py "$output/maven" com.github.gycrosskit.compose-webview "$VERSION" compose-webview,webview-core,webview-kuikly ios_arm64,ios_x64,ios_simulator_arm64,ohos_arm64
# 消费方只使用 JitPack；归档校验不会安装到 MavenLocal 或替代远程解析。

# 预期 inventory 来自通过冻结 SHA 的归档，不从 JitPack 自报 inventory 反推。
publications="$(python3 - "$output/maven" <<'PYTHON'
import sys
from pathlib import Path
print(','.join(sorted(path.parent.parent.name for path in Path(sys.argv[1]).rglob('*.module'))))
PYTHON
)"
tag_refs="$(git ls-remote --tags https://github.com/gycrosskit/compose-webview.git "refs/tags/$VERSION" "refs/tags/$VERSION^{}")"
commit="$(printf '%s\n' "$tag_refs" | awk '$2 ~ /\^\{\}$/ {peeled=$1} {plain=$1} END {print peeled ? peeled : plain}')"
python3 scripts/check-public-maven.py --repo compose-webview --version "$VERSION" --commit "$commit" --expected-publications "$publications" --output-dir "$output/public"
