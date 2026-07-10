#!/usr/bin/env python3
"""
Replace AdAway's 7 default block-placeholder images
(app/src/main/assets/img_00.webp .. img_06.webp) with your own, for
maintainers/forkers who want different defaults baked into the build -
as opposed to the in-app "custom block image" picker (Settings, hidden
behind tapping the version number 7 times), which lets any *user*
override images at runtime without touching the repo at all.

These 7 files don't need to share one fixed size/aspect ratio - the
originals are a mix of 512x768, 512x512 and 512x410, matching common ad
slot shapes. The native web server just serves whichever one it picks
at random per blocked request; there's no cropping/scaling requirement
here, this script only re-encodes whatever you give it as WebP (the
only format/extension the web server's MIME-type mapping recognizes).

Usage:
    python3 scripts/set_default_block_images.py IMAGE [IMAGE ...]

    One image  -> used for all 7 slots (img_00.webp .. img_06.webp).
    2-7 images -> assigned in order to img_00.webp, img_01.webp, ...;
                  any remaining slots repeat the last image given.
    More than 7 images is an error - there are only 7 slots.

Requires Pillow (`pip install Pillow --break-system-packages` if you
don't already have it).

Examples:
    python3 scripts/set_default_block_images.py my_logo.png
    python3 scripts/set_default_block_images.py ad1.jpg ad2.jpg ad3.png
"""
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    sys.exit(
        "Pillow is required. Install it with:\n"
        "    pip install Pillow --break-system-packages"
    )

ASSETS_DIR = Path(__file__).resolve().parent.parent / "app/src/main/assets"
SLOT_COUNT = 7


def main(argv):
    if not argv:
        sys.exit(__doc__)
    if len(argv) > SLOT_COUNT:
        sys.exit(f"Got {len(argv)} images, but there are only {SLOT_COUNT} slots (img_00..img_{SLOT_COUNT - 1:02d}).")

    if not ASSETS_DIR.is_dir():
        sys.exit(f"Couldn't find {ASSETS_DIR} - run this from within the repo.")

    sources = [Path(p) for p in argv]
    for p in sources:
        if not p.is_file():
            sys.exit(f"Not a file: {p}")

    # Repeat the last image to fill any remaining slots.
    while len(sources) < SLOT_COUNT:
        sources.append(sources[-1])

    for i, src in enumerate(sources):
        dest = ASSETS_DIR / f"img_{i:02d}.webp"
        with Image.open(src) as im:
            if im.mode not in ("RGB", "RGBA"):
                im = im.convert("RGBA" if "A" in im.mode else "RGB")
            im.save(dest, "WEBP", quality=85, method=6)
        print(f"{src} -> {dest.relative_to(ASSETS_DIR.parent.parent.parent)}")

    print(f"\nDone. Rebuild the app for these to take effect (assets are packaged at build time, "
          f"not read at runtime like the in-app custom-image picker's files are).")


if __name__ == "__main__":
    main(sys.argv[1:])
