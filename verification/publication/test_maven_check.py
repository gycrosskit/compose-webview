"""验证发布检查能拒绝缺平台、缺许可和无目标的转发。"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest


class MavenCheckContracts(unittest.TestCase):
    def setUp(self):
        scratch = Path(__file__).resolve().parents[2] / "build/publication-tests"
        scratch.mkdir(parents=True, exist_ok=True)
        self.temp = TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.group = "example.group"
        self.modules = {}
        for name, suffix, variant in [("example", ".jar", "metadataApiElements"), ("example-android", ".aar", "releaseApiElements-published"), ("example-iosarm64", ".klib", "iosArm64ApiElements-published")]:
            folder = self.root / name / "1.0"
            folder.mkdir(parents=True)
            artifact = folder / (name + "-1.0" + suffix)
            artifact.write_bytes(b"test publication API")
            entry = {"url": artifact.name, "size": artifact.stat().st_size}
            entry.update({algorithm: hashlib.new(algorithm, artifact.read_bytes()).hexdigest() for algorithm in ("sha256", "sha1", "sha512", "md5")})
            item = {"name": variant, "files": [entry]}
            if suffix == ".klib": item["attributes"] = {"org.jetbrains.kotlin.native.target": "ios_arm64"}
            component = {"group": self.group, "version": "1.0", "module": "example"}
            if name != "example": component["url"] = "../../example/1.0/example-1.0.module"
            module = folder / (name + "-1.0.module")
            module.write_text(json.dumps({"component": component, "variants": [item]}))
            self.modules[name] = module
            module.with_suffix(".pom").write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>{self.group}</groupId><artifactId>{name}</artifactId><version>1.0</version><licenses><license><name>Apache License, Version 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url><distribution>repo</distribution></license></licenses></project>''')

    def check(self):
        return subprocess.run([sys.executable, str(Path(__file__).resolve().parents[2] / "scripts/check-maven.py"), str(self.root), self.group, "1.0", "example", "ios_arm64"], capture_output=True, text=True)

    def test_full_publications_with_platform_owner_component(self):
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_native_publication_rejected(self):
        self.modules["example-iosarm64"].unlink()
        self.assertNotEqual(self.check().returncode, 0)

    def test_missing_apache_license_rejected(self):
        pom = self.modules["example-android"].with_suffix(".pom")
        pom.write_text(pom.read_text().replace("Apache License, Version 2.0", "unknown"))
        self.assertNotEqual(self.check().returncode, 0)

    def test_existing_module_without_target_variant_rejected(self):
        module = self.modules["example"]
        data = json.loads(module.read_text())
        data["variants"].append({"name": "missingApiElements-published", "available-at": {"url": "../../example-android/1.0/example-android-1.0.module", "group": self.group, "version": "1.0", "module": "example-android"}})
        module.write_text(json.dumps(data))
        self.assertNotEqual(self.check().returncode, 0)


if __name__ == "__main__":
    unittest.main()
