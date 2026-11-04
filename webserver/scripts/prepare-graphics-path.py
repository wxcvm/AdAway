"""Fetch unmodified AndroidX Graphics Path 1.0.1 sources for the missing RISC-V JNI library.

All bytes are pinned independently of HTTPS and published atomically. No binary
downloads or alternate architecture fallbacks are accepted.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.request


_MAX_ENCODED_SIZE = 2 * 1024 * 1024
_RETRYABLE_HTTP = {429, 500, 502, 503, 504}


def download_pinned_source(url):
    """Bounded retries for transient source-host failures; never change the pin."""
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=45) as response:
                encoded = response.read(_MAX_ENCODED_SIZE + 1)
            if len(encoded) > _MAX_ENCODED_SIZE:
                raise ValueError('AndroidX source file exceeds limit')
            return encoded
        except urllib.error.HTTPError as error:
            if error.code not in _RETRYABLE_HTTP or attempt == 3:
                raise
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt == 3:
                raise
        time.sleep(1 << attempt)
    raise AssertionError('unreachable retry state')


def prepare(destination):
    lock = Path(__file__).with_name('graphics-path-source.json')
    pin = json.loads(lock.read_text(encoding='utf-8'))
    commit = pin['commit']
    if pin['version'] != '1.0.1' or not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Review the Java/native version pin together')
    base = ('https://android.googlesource.com/platform/frameworks/support/+/'
            + commit + '/graphics/graphics-path/')
    if pin['sourceBase'] != base:
        raise ValueError('Unexpected AndroidX source origin')
    destination = Path(destination)
    for name, digest in pin['files'].items():
        relative = Path(name)
        if relative.is_absolute() or '..' in relative.parts or not re.fullmatch('[0-9a-f]{64}', digest):
            raise ValueError('Unsafe source pin')
        target = destination / relative
        if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == digest:
            continue
        encoded = download_pinned_source(base + name + '?format=TEXT')
        data = base64.b64decode(encoded, validate=True)
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError('AndroidX source hash mismatch: ' + name)
        target.parent.mkdir(parents=True, exist_ok=True)
        stage = target.with_name(target.name + '.download')
        stage.write_bytes(data)
        stage.replace(target)
    (destination / 'BUILD-COMMIT').write_text(commit + '\n', encoding='utf-8')
    print('Verified official graphics-path ' + pin['version'] + ' sources at ' + commit)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    prepare(parser.parse_args().output)
