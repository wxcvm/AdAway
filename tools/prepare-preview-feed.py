"""Emit a fail-closed multi-ABI preview feed after production signing checks.

Top-level ARM64 fields remain compatible with released clients. These report and
package checks supplement the workflow's real apksigner/stable-signer checks.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import zipfile

from compare_apk_signers import fingerprint
from verify_webserver_apk import load_segments

TARGETS = {
    'arm64-v8a': ('arm64', '', 30),
    'x86_64': ('x86_64', '-x86_64', 30),
    'riscv64': ('riscv64', '-riscv64', 35),
}
PACKAGE = 'com.wxcvm.Adaway'
IDENTITY = re.compile(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", re.MULTILINE)


def artifact(assets, abi, tag, source):
    slug, suffix, minimum = TARGETS[abi]
    apk = assets / f'AdAway-preview-{slug}.apk'
    identity = (assets / f'APK-IDENTITY{suffix}.txt').read_text(encoding='utf-8')
    package = IDENTITY.search(identity)
    sdk = re.search(r"^sdkVersion:'([0-9]+)'$", identity, re.MULTILINE)
    native = re.search(r'^native-code:(.*)$', identity, re.MULTILINE)
    if not package or package[1] != PACKAGE or not sdk or int(sdk[1]) != minimum:
        raise ValueError(f'Invalid production package/minSdk identity for {abi}')
    if not native or re.findall(r"'([^']+)'", native[1]) != [abi]:
        raise ValueError(f'Badging ABI does not match {abi}')
    if not 0 < int(package[2]) <= 2147483647 or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+-preview\.[0-9]+', package[3]):
        raise ValueError(f'Invalid production preview version for {abi}')
    if not 0 < apk.stat().st_size <= 64 * 1024 * 1024:
        raise ValueError(f'Package exceeds updater resource bounds: {abi}')
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names):
            raise ValueError('Duplicate APK zip entries')
        packaged_abis = {name.split('/')[1] for name in names
                         if name.startswith('lib/') and name.endswith('.so') and len(name.split('/')) == 3}
        if packaged_abis != {abi}:
            raise ValueError(f'APK contains another ABI or no native code: {abi}')
        for executable in ('webserver', 'tcpdump'):
            load_segments(archive.read(f'lib/{abi}/lib{executable}_exec.so'), abi, 16384)
        if abi == 'riscv64':
            graphics = archive.read('lib/riscv64/libandroidx.graphics.path.so')
            load_segments(graphics, abi, 16384, False)
            if b'libc++_shared.so\0' in graphics or 'lib/riscv64/libc++_shared.so' in names:
                raise ValueError('RISC-V preview must not use the prebuilt 4KB C++ runtime')
    signer = fingerprint((assets / f'APK-SIGNATURE{suffix}.txt').read_text(encoding='utf-8'))
    return dict(downloadUrl=f'https://github.com/wxcvm/AdAway/releases/download/{tag}/{apk.name}',
                sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), packageName=PACKAGE,
                minSdk=minimum, nativeAbi=abi, sourceCommit=source,
                versionCode=int(package[2]), version=package[3]), signer


def prepare(assets, tag, source):
    assets = Path(assets)
    if not re.fullmatch(r'progress-android-[0-9]{8}\.[0-9]+', tag) or not re.fullmatch(r'[0-9a-f]{40}', source):
        raise ValueError('Invalid immutable preview tag or source commit')
    recognized = {f'AdAway-preview-{slug}.apk' for slug, _, _ in TARGETS.values()}
    if {p.name for p in assets.glob('AdAway-preview-*.apk')} - recognized:
        raise ValueError('Unknown preview ABI attachment')
    entries = {}
    signers = set()
    for abi, (slug, suffix, _) in TARGETS.items():
        paths = [assets / f'AdAway-preview-{slug}.apk', assets / f'APK-IDENTITY{suffix}.txt',
                 assets / f'APK-SIGNATURE{suffix}.txt']
        if any(path.exists() for path in paths):
            if not all(path.is_file() and not path.is_symlink() for path in paths):
                raise ValueError(f'Incomplete preview artifact/report set: {abi}')
            entries[abi], signer = artifact(assets, abi, tag, source)
            signers.add(signer)
    if 'arm64-v8a' not in entries or len(signers) != 1:
        raise ValueError('ARM64 compatibility package missing or package signers differ')
    legacy = entries['arm64-v8a']
    if any((entry['versionCode'], entry['version']) != (legacy['versionCode'], legacy['version'])
           for entry in entries.values()):
        raise ValueError('ABI packages do not share the same release version')
    return dict(channel='preview', tag=tag, **legacy, artifacts=entries,
                changelog='日志改为全部保留历史筛选与固定快照完整导出；补齐请求编号、IPv6实际状态说明，修复可见占位重复计数与应用放行/转发漏计。覆盖原安装的三架构预览；真机与RISC-V运行仍待验收。')


def main():
    assets = Path('release-assets')
    doc = prepare(assets, os.environ['PREVIEW_TAG'], os.environ['GITHUB_SHA'])
    stage = assets / 'manifest.json.tmp'
    stage.write_text(json.dumps(doc, indent=2) + '\n', encoding='utf-8')
    stage.replace(assets / 'manifest.json')


if __name__ == '__main__':
    main()
