import importlib.util
import hashlib
import pathlib
import tempfile
import unittest
from unittest.mock import patch

HERE = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("spike_fetch", HERE / "fetch.py")
fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch)


class InputIdentityTest(unittest.TestCase):
    def test_windows_checkout_newlines_are_not_a_canonical_blob(self):
        self.assertNotEqual(
            hashlib.sha256(b"x\n").hexdigest(), hashlib.sha256(b"x\r\n").hexdigest()
        )

    def test_bad_cached_fixture_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "lws-source").mkdir()
            cached = root / "fixture/value.txt"
            cached.parent.mkdir()
            cached.write_bytes(b"changed")
            lock = {
                "lws": {"commit": "pin"},
                "fixture": {"files": {"value.txt": hashlib.sha256(b"x\n").hexdigest()}},
            }

            def git(*args, **kwargs):
                return "pin" if "rev-parse" in args else ""

            with patch.object(fetch, "LOCK", lock), patch.object(fetch, "run", git):
                with self.assertRaisesRegex(RuntimeError, "Fixture cache changed"):
                    fetch.fetch(root)
            self.assertEqual(b"changed", cached.read_bytes())

    def test_locked_names_do_not_allow_traversal(self):
        for name in fetch.LOCK["fixture"]["files"]:
            path = pathlib.PurePosixPath(name)
            self.assertFalse(path.is_absolute())
            self.assertNotIn("..", path.parts)


if __name__ == "__main__":
    unittest.main()
