"""Actual feed producer against minimal APK/ELF/report fixtures, never a signer test."""
import hashlib
import importlib.util
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('preview_feed', Path(__file__).with_name('prepare-preview-feed.py'))
feed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(feed)
TAG = 'progress-android-20261023.1'
SOURCE = 'a' * 40


def elf(abi):
    data = bytearray(256)
    data[:6] = b'\x7fELF\x02\x01'
    machine = {'arm64-v8a': 183, 'x86_64': 62, 'riscv64': 243}[abi]
    struct.pack_into('<HHIQQQIHHHHHH', data, 16, 3, machine, 1, 128, 64, 0, 0, 64, 56, 1, 0, 0, 0)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0, 0, 256, 256, 16384)
    return data


class PreviewFeedTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.assets = Path(self.temp.name) / 'release-assets'
        self.assets.mkdir()

    def package(self, abi, code=123456, version='6.7.6-preview.33'):
        slug, suffix, sdk = feed.TARGETS[abi]
        (self.assets / f'APK-IDENTITY{suffix}.txt').write_text(
            f"package: name='com.wxcvm.Adaway' versionCode='{code}' versionName='{version}'\n"
            f"sdkVersion:'{sdk}'\nnative-code: '{abi}'\n", encoding='utf-8')
        (self.assets / f'APK-SIGNATURE{suffix}.txt').write_text(
            'Signer #1 certificate SHA-256 digest: ' + 'b' * 64 + '\n', encoding='utf-8')
        with zipfile.ZipFile(self.assets / f'AdAway-preview-{slug}.apk', 'w') as archive:
            for name in ('webserver', 'tcpdump'):
                archive.writestr(f'lib/{abi}/lib{name}_exec.so', elf(abi))
            if abi == 'riscv64':
                library = elf(abi)
                struct.pack_into('<Q', library, 24, 0)
                archive.writestr('lib/riscv64/libandroidx.graphics.path.so', library)

    def test_riscv_preview_rejects_missing_or_invalid_graphics_native_library(self):
        self.package('arm64-v8a')
        for failure in ('missing', 'wrong_machine', 'pie_placeholder', 'unaligned'):
            self.package('riscv64')
            apk = self.assets / 'AdAway-preview-riscv64.apk'
            with zipfile.ZipFile(apk) as archive:
                entries = {name: archive.read(name) for name in archive.namelist()}
            name = 'lib/riscv64/libandroidx.graphics.path.so'
            library = bytearray(entries.pop(name))
            if failure == 'wrong_machine': struct.pack_into('<H', library, 18, 183)
            elif failure == 'pie_placeholder': struct.pack_into('<Q', library, 24, 128)
            elif failure == 'unaligned': struct.pack_into('<Q', library, 112, 4096)
            if failure != 'missing': entries[name] = library
            with zipfile.ZipFile(apk, 'w') as archive:
                for entry, data in entries.items(): archive.writestr(entry, data)
            with self.assertRaises((ValueError, KeyError), msg=failure): self.prepare()

    def prepare(self):
        return feed.prepare(self.assets, TAG, SOURCE)

    def test_three_real_abi_records_and_legacy_arm64_fields(self):
        for abi in feed.TARGETS:
            self.package(abi)
        doc = self.prepare()
        self.assertEqual(set(feed.TARGETS), set(doc['artifacts']))
        self.assertTrue(doc['downloadUrl'].endswith('/AdAway-preview-arm64.apk'))
        self.assertEqual(doc['sha256'], doc['artifacts']['arm64-v8a']['sha256'])
        for abi, artifact in doc['artifacts'].items():
            slug, _, sdk = feed.TARGETS[abi]
            self.assertEqual(sdk, artifact['minSdk'])
            self.assertEqual(abi, artifact['nativeAbi'])
            self.assertEqual(SOURCE, artifact['sourceCommit'])
            self.assertEqual(doc['versionCode'], artifact['versionCode'])
            self.assertEqual(doc['version'], artifact['version'])
            self.assertEqual(doc['packageName'], artifact['packageName'])
            self.assertEqual(hashlib.sha256((self.assets / f'AdAway-preview-{slug}.apk').read_bytes()).hexdigest(), artifact['sha256'])

    def test_old_arm64_only_layout_is_still_readable(self):
        self.package('arm64-v8a')
        self.assertEqual(['arm64-v8a'], list(self.prepare()['artifacts']))

    def test_version_code_name_and_signer_cannot_diverge(self):
        self.package('arm64-v8a')
        for code, version in ((123457, '6.7.6-preview.33'), (123456, '6.7.6-preview.34')):
            with self.subTest(code=code, version=version):
                self.package('x86_64', code, version)
                with self.assertRaisesRegex(ValueError, 'same release version'):
                    self.prepare()
        self.package('x86_64')
        (self.assets / 'APK-SIGNATURE-x86_64.txt').write_text('Signer #1 certificate SHA-256 digest: ' + 'c' * 64)
        with self.assertRaisesRegex(ValueError, 'signers differ'):
            self.prepare()

    def test_identity_sdk_package_and_badging_abi_fail_closed(self):
        self.package('arm64-v8a')
        for old, new in (("sdkVersion:'30'", "sdkVersion:'29'"), ('com.wxcvm.Adaway', 'com.wxcvm.Adaway.preview'),
                         ("native-code: 'arm64-v8a'", "native-code: 'x86_64'")):
            with self.subTest(old=old):
                self.package('arm64-v8a')
                identity = self.assets / 'APK-IDENTITY.txt'
                identity.write_text(identity.read_text().replace(old, new))
                with self.assertRaises(ValueError):
                    self.prepare()

    def test_native_machine_placeholder_alignment_missing_or_mixed_libs_rejected(self):
        self.package('arm64-v8a')
        native = elf('arm64-v8a')
        variants = []
        wrong = bytearray(native); struct.pack_into('<H', wrong, 18, 243); variants.append(wrong)
        placeholder = bytearray(native); struct.pack_into('<Q', placeholder, 24, 0); variants.append(placeholder)
        alignment = bytearray(native); struct.pack_into('<Q', alignment, 64 + 48, 4096); variants.append(alignment)
        apk = self.assets / 'AdAway-preview-arm64.apk'
        for data in variants:
            with self.subTest(data=data[18:32]), zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('lib/arm64-v8a/libwebserver_exec.so', data)
                archive.writestr('lib/arm64-v8a/libtcpdump_exec.so', native)
            with self.assertRaises(ValueError):
                self.prepare()
        self.package('arm64-v8a')
        with zipfile.ZipFile(apk, 'a') as archive:
            archive.writestr('lib/x86_64/other.so', elf('x86_64'))
        with self.assertRaisesRegex(ValueError, 'another ABI'):
            self.prepare()
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr('lib/arm64-v8a/libwebserver_exec.so', native)
        with self.assertRaises(KeyError):
            self.prepare()

    def test_partial_unknown_artifacts_and_nonimmutable_identity_rejected(self):
        self.package('arm64-v8a')
        extra = self.assets / 'APK-IDENTITY-riscv64.txt'
        extra.write_text('partial')
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            self.prepare()
        extra.unlink()
        unknown = self.assets / 'AdAway-preview-unknown.apk'
        unknown.write_bytes(b'wrong')
        with self.assertRaisesRegex(ValueError, 'Unknown'):
            self.prepare()
        unknown.unlink()
        for tag, source in (('android-preview', SOURCE), (TAG, 'not-a-commit')):
            with self.assertRaises(ValueError):
                feed.prepare(self.assets, tag, source)

    def test_failed_preparation_keeps_previous_manifest(self):
        self.package('arm64-v8a')
        self.package('riscv64', code=999)
        target = self.assets / 'manifest.json'
        target.write_text('previous valid manifest')
        previous = Path.cwd()
        os.chdir(self.temp.name)
        try:
            with patch.dict(os.environ, PREVIEW_TAG=TAG, GITHUB_SHA=SOURCE), self.assertRaises(ValueError):
                feed.main()
            self.assertEqual('previous valid manifest', target.read_text())
            self.assertFalse((self.assets / 'manifest.json.tmp').exists())
        finally:
            os.chdir(previous)


if __name__ == '__main__':
    unittest.main()
