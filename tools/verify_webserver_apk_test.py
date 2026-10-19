import struct
import unittest
from verify_webserver_apk import load_segments


def executable():
    blob = bytearray(256)
    blob[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<HHIQQ', blob, 16, 3, 183, 1, 0x100080, 64)
    struct.pack_into('<HH', blob, 54, 56, 1)
    struct.pack_into('<IIQQQQQQ', blob, 64, 1, 5, 0, 0x100000, 0, 256, 256, 4096)
    return blob


class ApkExecutableTest(unittest.TestCase):
    def test_rejects_placeholder_and_entry_outside_code(self):
        for entry in (0, 0x200000):
            blob = executable()
            struct.pack_into('<Q', blob, 24, entry)
            with self.assertRaises(ValueError):
                load_segments(blob)

    def test_strip_metadata_may_change_but_loaded_code_must_match(self):
        built = executable()
        stripped = bytearray(built)
        struct.pack_into('<Q', stripped, 40, 2048)
        struct.pack_into('<HHH', stripped, 58, 64, 12, 11)
        self.assertEqual(load_segments(built), load_segments(stripped))
        stripped[200] = 1
        self.assertNotEqual(load_segments(built), load_segments(stripped))

    def test_rejects_truncated_load_segments(self):
        with self.assertRaises(ValueError):
            load_segments(executable()[:200])


if __name__ == '__main__':
    unittest.main()
