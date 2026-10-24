import struct
import unittest
import tempfile
from pathlib import Path
from verify_webserver_apk import load_segments, find_apk


def executable():
    blob = bytearray(256)
    blob[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<HHIQQ', blob, 16, 3, 183, 1, 0x100080, 64)
    struct.pack_into('<HH', blob, 54, 56, 1)
    struct.pack_into('<IIQQQQQQ', blob, 64, 1, 5, 0, 0x100000, 0, 256, 256, 4096)
    return blob


class ApkExecutableTest(unittest.TestCase):
    def test_cross_architecture_binaries_and_page_alignment_are_checked(self):
        for abi, machine in [('arm64-v8a', 183), ('x86_64', 62), ('riscv64', 243)]:
            blob = executable()
            struct.pack_into('<H', blob, 18, machine)
            struct.pack_into('<Q', blob, 112, 16384)
            load_segments(blob, abi, 16384)
            wrong = 'x86_64' if abi != 'x86_64' else 'riscv64'
            with self.assertRaises(ValueError): load_segments(blob, wrong, 16384)
            struct.pack_into('<Q', blob, 112, 4096)
            with self.assertRaises(ValueError): load_segments(blob, abi, 16384)

    def test_agp9_intermediate_output_is_found_but_duplicates_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / 'intermediates/apk/release/app-release.apk'
            apk.parent.mkdir(parents=True)
            apk.touch()
            debug = root / 'outputs/apk/debug/app-debug.apk'
            debug.parent.mkdir(parents=True)
            debug.touch()
            self.assertEqual(apk, find_apk(root, 'release'))
            duplicate = root / 'outputs/apk/release/app-release.apk'
            duplicate.parent.mkdir(parents=True)
            duplicate.touch()
            with self.assertRaises(ValueError):
                find_apk(root, 'release')

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

    def test_riscv_strip_may_relocate_only_nonloaded_attributes(self):
        built = executable()
        struct.pack_into('<H', built, 18, 243)
        struct.pack_into('<H', built, 56, 2)
        struct.pack_into('<IIQQQQQQ', built, 120, 0x70000003, 4, 256, 0, 0, 8, 8, 1)
        built.extend(b'ATTRtest')
        stripped = bytearray(built)
        stripped.extend(b'ATTRtest')
        struct.pack_into('<Q', stripped, 128, 264)
        self.assertEqual(load_segments(built, 'riscv64'), load_segments(stripped, 'riscv64'))
        stripped[270] ^= 1
        self.assertNotEqual(load_segments(built, 'riscv64'), load_segments(stripped, 'riscv64'))
        stripped[200] ^= 1
        self.assertNotEqual(load_segments(built, 'riscv64'), load_segments(stripped, 'riscv64'))
        struct.pack_into('<Q', stripped, 128, 200)
        with self.assertRaises(ValueError): load_segments(stripped, 'riscv64')


if __name__ == '__main__':
    unittest.main()
