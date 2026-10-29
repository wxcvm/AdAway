#!/usr/bin/env python3
"""Prepare identical default image assets for stable and preview APKs."""

import argparse
from pathlib import Path

from PIL import Image


def compress_assets(assets_dir: Path) -> int:
    if not assets_dir.is_dir():
        raise NotADirectoryError(f"Asset directory not found: {assets_dir}")

    converted = 0
    for source_path in sorted(assets_dir.glob("img_*")):
        if not source_path.is_file() or source_path.suffix.lower() == ".webp":
            continue

        with Image.open(source_path) as source:
            image = source.convert("RGB")
        width, height = image.size
        scale = min(1.0, 800 / max(width, height))
        if scale < 1.0:
            image = image.resize(
                (max(1, int(width * scale)), max(1, int(height * scale))),
                Image.LANCZOS,
            )

        output_path = source_path.with_suffix(".webp")
        image.save(output_path, "WEBP", quality=95, method=6)
        source_path.unlink()
        print(f"{source_path.name} ({width}x{height}) -> {output_path.name} "
              f"({output_path.stat().st_size // 1024}KB)")
        converted += 1

    print(f"block images compressed: {converted}")
    return converted


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--assets-dir", type=Path, default=Path("app/src/main/assets"),
        help="Input directory of img_*.jpg/png/webp assets",
    )
    args = parser.parse_args()
    compress_assets(args.assets_dir)


if __name__ == "__main__":
    main()
