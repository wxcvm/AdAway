"""Validate both packaged native executables and their per-variant provenance."""
import argparse
import hashlib
import struct
import zipfile
from pathlib import Path


def find_apk(root, variant):
    candidates = sorted(root.glob(f'**/apk/{variant}/*.apk'))
    if len(candidates) != 1:
        raise ValueError(f'Expected exactly one {variant} APK under {root}, found {candidates}')
    return candidates[0]


ABI_MACHINES = {'arm64-v8a': 183, 'x86_64': 62, 'riscv64': 243}


def load_segments(blob, abi='arm64-v8a', minimum_page_size=None):
    if abi not in ABI_MACHINES:
        raise ValueError(f'Unsupported ABI: {abi}')
    if len(blob) < 64 or blob[:6] != b'\x7fELF\x02\x01':
        raise ValueError('Server must be little-endian ELF64')
    kind, machine = struct.unpack_from('<HH', blob, 16)
    entry, phoff = struct.unpack_from('<QQ', blob, 24)
    size, count = struct.unpack_from('<HH', blob, 54)
    if kind != 3 or machine != ABI_MACHINES[abi] or not entry or size != 56 or not count or phoff + size * count > len(blob):
        raise ValueError(f'Server is not a {abi} PIE executable; shared-library placeholder rejected')
    segments = []
    entry_executable = False
    for offset in range(phoff, phoff + size * count, size):
        ptype, flags, file_offset, address, _, file_size, mem_size, alignment = struct.unpack_from('<IIQQQQQQ', blob, offset)
        if ptype != 1:
            continue
        if file_offset + file_size > len(blob):
            raise ValueError('Truncated ELF load segment')
        if minimum_page_size and (alignment < minimum_page_size or address % minimum_page_size != file_offset % minimum_page_size):
            raise ValueError(f'ELF load segment is not aligned for {minimum_page_size}-byte pages')
        entry_executable |= bool(flags & 1 and address <= entry < address + file_size)
        payload = bytearray(blob[file_offset:file_offset + file_size])
        if file_offset == 0:
            # llvm-strip updates only ELF section-table metadata in the loaded header.
            payload[40:48] = bytes(8)
            payload[58:64] = bytes(6)
        segments.append((address, flags, mem_size, bytes(payload)))
    if not entry_executable:
        raise ValueError('Server entry point is outside executable load segments')
    return entry, segments


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--apk-root', type=Path, default=Path('app/build'))
    parser.add_argument('--variant', choices=('debug', 'release'), default='release')
    parser.add_argument('--abi', choices=tuple(ABI_MACHINES), default='arm64-v8a')
    parser.add_argument('--write-apk-path-file', type=Path)
    parser.add_argument('--executable', type=Path, required=True)
    parser.add_argument('--tcpdump-executable', type=Path)
    args = parser.parse_args()
    apk_path = args.apk if args.apk is not None else find_apk(args.apk_root, args.variant)
    tcpdump = args.tcpdump_executable or Path(f'tcpdump/build/generated/tcpdumpJniLibs/{args.variant}/{args.abi}/libtcpdump_exec.so')
    with zipfile.ZipFile(apk_path) as apk:
        for name, executable in (('webserver', args.executable), ('tcpdump', tcpdump)):
            packaged = apk.read(f'lib/{args.abi}/lib{name}_exec.so')
            # AGP strips section/symbol tables; PT_LOAD contents and entry must still match.
            if load_segments(packaged, args.abi, 16384) != load_segments(executable.read_bytes(), args.abi, 16384):
                raise ValueError(f'Packaged {name} differs from this variant\'s generated executable')
            print(f'Verified {args.abi} executable {name}, entry={load_segments(packaged, args.abi)[0]:#x}, '
                  f'bytes={len(packaged)}, sha256={hashlib.sha256(packaged).hexdigest()}')
    if args.write_apk_path_file:
        args.write_apk_path_file.write_text(str(apk_path) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
