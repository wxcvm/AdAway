#!/usr/bin/env python3
"""Validate the evaluation library's platform identity and ELF protections."""
import argparse
import json
import re
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('candidate', type=Path)
parser.add_argument('readelf', type=Path)
args = parser.parse_args()
results = []
for abi, machine in [('arm64-v8a', 'AArch64'), ('x86_64', 'Advanced Micro Devices X86-64')]:
    path = args.candidate / 'libs' / abi / 'libhev-socks5-tunnel.so'
    def read(*options):
        return subprocess.check_output([str(args.readelf), *options, str(path)], text=True)
    assert machine in read('-h'), abi
    segments = read('-W', '-l').splitlines()
    loads = [line for line in segments if re.match(r'\s*LOAD\s', line)]
    assert loads and all(int(line.split()[-1], 16) >= 16384 for line in loads), abi
    stacks = [line for line in segments if 'GNU_STACK' in line]
    assert len(stacks) == 1 and 'E' not in stacks[0].split()[6:-1], abi
    assert any('GNU_RELRO' in line for line in segments), abi
    assert 'BIND_NOW' in read('-d'), abi
    deps = re.findall(r'Shared library: \[(.*?)\]', read('-d'))
    assert set(deps) <= {'libc.so', 'libm.so', 'libdl.so'}, (abi, deps)
    exports = read('--dyn-syms', '--wide')
    for name in ('main_from_str', 'quit', 'stats'):
        assert 'hev_socks5_tunnel_' + name in exports, (abi, name)
    results.append({'abi': abi, 'library_bytes': path.stat().st_size,
                    'load_alignment_at_least_16KiB': True, 'non_executable_stack': True,
                    'relro_bind_now': True, 'dependencies': deps})
print(json.dumps(results, indent=2))
