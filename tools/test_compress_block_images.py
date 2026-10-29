"""Regression checks for the shared stable/preview default-image step."""

import importlib.util
from pathlib import Path
import tempfile
import unittest

from PIL import Image


SCRIPT = Path(__file__).with_name("compress_block_images.py")


class CompressBlockImagesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("compress_block_images", SCRIPT)
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def test_converts_resizes_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            Image.new("RGB", (1600, 1200), "blue").save(folder / "img_00.png")
            Image.new("RGB", (240, 320), "green").save(folder / "img_01.jpg")
            Image.new("RGB", (80, 80), "red").save(folder / "img_02.webp")
            original_webp = (folder / "img_02.webp").read_bytes()
            (folder / "notes.txt").write_text("unchanged")

            self.assertEqual(self.module.compress_assets(folder), 2)
            self.assertFalse((folder / "img_00.png").exists())
            self.assertFalse((folder / "img_01.jpg").exists())
            with Image.open(folder / "img_00.webp") as image:
                self.assertEqual(image.size, (800, 600))
            with Image.open(folder / "img_01.webp") as image:
                self.assertEqual(image.size, (240, 320))
            self.assertEqual((folder / "img_02.webp").read_bytes(), original_webp)
            self.assertEqual((folder / "notes.txt").read_text(), "unchanged")
            self.assertEqual(self.module.compress_assets(folder), 0)

    def test_missing_assets_directory_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(NotADirectoryError):
                self.module.compress_assets(Path(directory) / "missing")

    def test_stable_and_preview_workflows_share_implementation(self):
        root = Path(__file__).resolve().parent.parent
        for workflow in ("android-ci.yml", "progress-preview.yml"):
            content = (root / ".github" / "workflows" / workflow).read_text()
            self.assertIn("python3 tools/compress_block_images.py", content)


if __name__ == "__main__":
    unittest.main()
