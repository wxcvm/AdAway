"""Offline regression checks: transient retries never weaken source pinning."""
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import call, patch
from urllib.error import HTTPError, URLError

_SCRIPT = Path(__file__).with_name("prepare-graphics-path.py")
_spec = importlib.util.spec_from_file_location("prepare_graphics_path", _SCRIPT)
graphics = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(graphics)
_URL = "https://android.googlesource.com/pinned-source?format=TEXT"


class GraphicsPathDownloadTest(unittest.TestCase):
    def test_recovers_from_temporary_503(self):
        server_error = HTTPError(_URL, 503, "Service Unavailable", None, None)
        with patch.object(graphics.urllib.request, "urlopen",
                          side_effect=[server_error, io.BytesIO(b"verified")]) as fetch, \
             patch.object(graphics.time, "sleep") as sleep:
            self.assertEqual(graphics.download_pinned_source(_URL), b"verified")
            self.assertEqual(fetch.call_count, 2)
            sleep.assert_called_once_with(1)

    def test_exhausts_transient_failure_without_fallback(self):
        with patch.object(graphics.urllib.request, "urlopen",
                          side_effect=URLError("source unavailable")) as fetch, \
             patch.object(graphics.time, "sleep") as sleep:
            with self.assertRaises(URLError):
                graphics.download_pinned_source(_URL)
            self.assertEqual(fetch.call_count, 4)
            self.assertEqual(sleep.call_args_list, [call(1), call(2), call(4)])

    def test_permanent_404_never_retries(self):
        not_found = HTTPError(_URL, 404, "Not Found", None, None)
        with patch.object(graphics.urllib.request, "urlopen",
                          side_effect=not_found) as fetch, \
             patch.object(graphics.time, "sleep") as sleep:
            with self.assertRaises(HTTPError):
                graphics.download_pinned_source(_URL)
            fetch.assert_called_once()
            sleep.assert_not_called()

    def test_oversized_source_fails_immediately(self):
        too_large = b"x" * (graphics._MAX_ENCODED_SIZE + 1)
        with patch.object(graphics.urllib.request, "urlopen",
                          return_value=io.BytesIO(too_large)) as fetch, \
             patch.object(graphics.time, "sleep") as sleep:
            with self.assertRaisesRegex(ValueError, "exceeds limit"):
                graphics.download_pinned_source(_URL)
            fetch.assert_called_once()
            sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()
