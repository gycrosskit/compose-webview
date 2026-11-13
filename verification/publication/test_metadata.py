"""使用真实 ZIP/metadata 文件验证发布裁剪，不需要 Gradle 或厂商 SDK。"""
import hashlib
import importlib.util
import json
import re
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location("metadata", Path(__file__).resolve().parents[2] / "jitpack-metadata.py")
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


class PublicationContracts(unittest.TestCase):
    def setUp(self):
        scratch = Path(__file__).resolve().parents[2] / "build/publication-tests"
        scratch.mkdir(parents=True, exist_ok=True)
        self.temp = TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.native = self.root / "native.module"
        self.shared = self.root / "shared.module"
        self.resource_name = "iosArm64ResourcesElements-published"
        self.api = {"name": "iosArm64ApiElements-published", "files": [{"url": "main.klib"}]}
        self.resource = {"name": self.resource_name, "files": [{"url": "resources.zip"}]}
        with ZipFile(self.root / "resources.zip", "w") as zipped:
            zipped.writestr("composeResources/", b"")
        self.write(self.native, [self.api, self.resource])
        self.write(self.shared, [{"name": self.resource_name, "available-at": {"url": "native.module"}}, {"name": "metadataApiElements"}, {"name": "metadataSourcesElements"}])

    def write(self, path, variants):
        path.write_text(json.dumps({"variants": variants}))

    def variants(self, path):
        return json.loads(path.read_text())["variants"]

    def test_release_versions_follow_explicit_channel_matrix(self):
        root = Path(__file__).resolve().parents[2]
        def version(path, expression):
            values = re.findall(expression, (root / path).read_text())
            self.assertEqual(len(values), 1, path)
            return values[0]
        har = json.loads((root / 'ohos/webview-native/oh-package.json5').read_text())
        versions = {
            'maven': version('build.gradle.kts', r'environmentVariable\("VERSION"\)\.orElse\("([^"]+)"\)'),
            'maven-package': version('scripts/package-maven.sh', r'\$\{VERSION:-([^}]+)\}'),
            'pod': version('GYWebView.podspec', r"s\.version\s*=\s*'([^']+)'"),
            'maven-consumer': version('verification-consumer/build.gradle.kts', r'gradleProperty\("webViewVersion"\)\.orElse\("([^"]+)"\)'),
            'verify-consumer': version('scripts/verify.sh', r'-PwebViewVersion=([^\s]+)'),
            'har': har['version'],
        }
        self.assertEqual(versions, {
            'maven': '2.3.2',
            'maven-package': '2.3.2',
            'maven-consumer': '2.3.2',
            'verify-consumer': '2.3.2',
            'pod': '2.3.2',
            'har': '2.3.2',
        })
        for path, current in (('CHANGELOG.md', versions['maven']),
                              ('ohos/webview-native/CHANGELOG.md', versions['har'])):
            sections = re.findall(r'^##[ \t]+(.*?)\n(.*?)(?=^#{1,2}[ \t]|\Z)',
                                  (root / path).read_text(), re.MULTILINE | re.DOTALL)
            current_sections = [(title, body) for title, body in sections
                                if re.match(re.escape(current) + r'($|[ \t（(])', title)]
            self.assertEqual(len(current_sections), 1, path)
            title, body = current_sections[0]
            self.assertEqual(title, current, f'{path}: use ## <version>; put release status in the body')
            self.assertRegex(body, r'(?m)^-[ \t]+\S', path)
        self.assertIn(':tag => "native-#{s.version}"', (root / 'GYWebView.podspec').read_text())
        consumer = json.loads((root / 'ohos/verification-consumer/consumer/oh-package.json5').read_text())
        for manifest in (har, consumer):
            self.assertEqual(manifest['dependencies']['@gycrosskit/system-actions-native'], '1.0.2')

    def test_empty_resource_and_forwarding_removed_api_preserved(self):
        self.assertEqual(metadata.normalize([self.native, self.shared]), 2)
        self.assertEqual(self.variants(self.native), [self.api])
        self.assertEqual(self.variants(self.shared), [{"name": "metadataApiElements"}])
        self.assertEqual(metadata.normalize([self.native, self.shared]), 0)

    def test_nonempty_resource_fails_before_any_write(self):
        before = {path: path.read_bytes() for path in (self.shared, self.native)}
        with ZipFile(self.root / "resources.zip", "a") as zipped:
            zipped.writestr("composeResources/image.png", b"image")
        with self.assertRaisesRegex(ValueError, "Nonempty resources"):
            metadata.normalize([self.shared, self.native])
        self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_zero_byte_real_file_also_requires_resource_publication(self):
        with ZipFile(self.root / "resources.zip", "a") as zipped:
            zipped.writestr("composeResources/empty.txt", b"")
        with self.assertRaisesRegex(ValueError, "Nonempty resources"):
            metadata.normalize([self.native, self.shared])

    def test_missing_resource_target_fails_without_write(self):
        before = self.shared.read_bytes()
        with self.assertRaisesRegex(ValueError, "Missing resource metadata"):
            metadata.normalize([self.shared])
        self.assertEqual(self.shared.read_bytes(), before)

    def test_root_sources_exact_and_classified_variants_only(self):
        keep = {"name": "customSourcesElements"}
        self.write(self.native, [self.api, keep, {"name": "iosArm64SourcesElements-published"}, {"name": "iosArm64MetadataElements-published"}])
        metadata.normalize([self.native])
        self.assertEqual(self.variants(self.native), [self.api, keep])

    def test_all_existing_metadata_checksums_refreshed(self):
        for algorithm in ("sha1", "sha256", "sha512", "md5"):
            self.native.with_suffix(".module." + algorithm).write_text("old")
        metadata.normalize([self.native, self.shared])
        for algorithm in ("sha1", "sha256", "sha512", "md5"):
            self.assertEqual(self.native.with_suffix(".module." + algorithm).read_text(), hashlib.new(algorithm, self.native.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
