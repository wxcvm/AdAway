"""Check emitted C JSON field names, including short keys, against the core contract."""
from pathlib import Path
import re


def fields(source):
    # JSON quotes inside C literals are escaped. Ordinary C strings before a
    # ternary colon are not JSON fields (for example "listening" : "failed").
    return set(re.findall(r'\\"([a-z][a-zA-Z0-9_]{0,40})\\"\s*:', source))


def main():
    root = Path(__file__).resolve().parents[1]
    source = '\n'.join((root / name).read_text(encoding='utf-8') for name in (
        'webserver/jni/webserver.c', 'webserver/jni/query_log_store.inc'))
    document = (root / 'docs/windows-core-contract.md').read_text(encoding='utf-8')
    section = document.split('## 2.', 1)[1].split('## 3.', 1)[0]
    code_keys = fields(source)
    doc_keys = set(re.findall(r'`([a-z][a-zA-Z0-9_]{0,40})`', section))
    if not code_keys or code_keys != doc_keys:
        raise SystemExit(f'JSON field contract mismatch: undocumented={sorted(code_keys-doc_keys)}, '
                         f'not emitted={sorted(doc_keys-code_keys)}')
    print(f'PASS: all {len(code_keys)} emitted JSON fields match the contract')


if __name__ == '__main__':
    main()
