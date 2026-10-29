#!/usr/bin/env python3
"""Generate stable and next-cycle preview version names from one version source."""

import argparse
from pathlib import Path
import re
import tomllib


DEFAULT_VERSION_FILE = Path(__file__).resolve().parent.parent / "gradle" / "libs.versions.toml"


def version_name(channel: str, build_number: int, version_file: Path = DEFAULT_VERSION_FILE) -> str:
    if channel not in ("stable", "preview"):
        raise ValueError(f"Unknown release channel: {channel}")
    if build_number <= 0:
        raise ValueError("GitHub run number must be positive")
    versions = tomllib.loads(version_file.read_text(encoding="utf-8"))["versions"]
    base = versions["appName"]
    match = re.fullmatch(r"([0-9]+)\.([0-9]+)\.([0-9]+)", base)
    if match is None:
        raise ValueError(f"Invalid stable Android version: {base}")
    major, minor, patch = map(int, match.groups())
    if int(versions["appCode"]) != major * 10000 + minor * 100 + patch:
        raise ValueError("Local Android appCode and appName must match")
    if channel == "stable":
        return f"{major}.{minor}.{patch}.{build_number}"
    return f"{major}.{minor}.{patch + 1}-preview.{build_number}"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("channel", choices=("stable", "preview"))
    parser.add_argument("build_number", type=int)
    parser.add_argument("--version-file", type=Path, default=DEFAULT_VERSION_FILE)
    args = parser.parse_args()
    print(version_name(args.channel, args.build_number, args.version_file))


if __name__ == "__main__":
    main()
