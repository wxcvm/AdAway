"""Ensure Android stable and preview version names cannot drift apart."""

import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("android_release_version.py")


class AndroidReleaseVersionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("android_release_version", SCRIPT)
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def test_current_base_and_ordering(self):
        stable = self.module.version_name("stable", 814)
        preview = self.module.version_name("preview", 40)
        self.assertEqual(stable, "6.7.6.814")
        self.assertEqual(preview, "6.7.7-preview.40")

    def test_channel_and_build_validation(self):
        for channel, number in (("invalid", 10), ("stable", 0), ("preview", -1)):
            with self.subTest(channel=channel, number=number):
                with self.assertRaises(ValueError):
                    self.module.version_name(channel, number)

    def test_invalid_base_and_mismatched_code_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "libs.versions.toml"
            for base, code in (("6.7.6", "60705"), ("6.7.6.8", "60706"), ("latest", "60706")):
                source.write_text(f'[versions]\nappName = "{base}"\nappCode = "{code}"\n')
                with self.subTest(base=base, code=code), self.assertRaises(ValueError):
                    self.module.version_name("stable", 814, source)

    def test_workflows_use_shared_version_source(self):
        root = SCRIPT.parent.parent
        stable = (root / ".github/workflows/android-ci.yml").read_text()
        preview = (root / ".github/workflows/progress-preview.yml").read_text()
        self.assertIn("python3 tools/android_release_version.py stable", stable)
        self.assertIn("python3 tools/android_release_version.py preview", preview)
        self.assertIn("1577836800", stable)
        self.assertIn("1577836800", preview)


if __name__ == "__main__":
    unittest.main()
