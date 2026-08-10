#!/usr/bin/env python3
"""Generate every launcher icon v2rayV needs from one source image.

Usage:
    python tools/make_icons.py [source.png]

With no argument it takes the newest image in design/logo/.

The source should be square and at least 512x512, with transparency where the
background should show through. Everything else is derived:

  ic_launcher_foreground  the adaptive icon's foreground layer. Android crops
                          adaptive icons to whatever shape the launcher wants, so
                          the artwork is inset into the centre 66/108 of the
                          canvas — the only part guaranteed never to be clipped.
  ic_launcher             the legacy square icon, for launchers predating
                          adaptive icons.
  ic_launcher_round       the legacy round icon, masked to a circle.
  ic_banner               the TV banner, artwork centred on the brand background.

The adaptive background is a flat colour in res/values/ic_launcher_background.xml
rather than a layer here, so it can be changed without regenerating anything.
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parent.parent
RES = REPO / "V2rayNG" / "app" / "src" / "main" / "res"
LOGO_DIR = REPO / "design" / "logo"

# Brand black, matching Securo.Background and ic_launcher_background.
BRAND_BG = (5, 7, 5, 255)

# Legacy icon edge length in pixels, per density bucket.
LAUNCHER_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# Adaptive icons are 108dp square regardless of density.
FOREGROUND_SIZES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

# 66dp of the 108dp canvas is the documented safe zone.
SAFE_ZONE = 66 / 108

BANNER_SIZE = (320, 180)


def find_source() -> Path:
    if len(sys.argv) > 1:
        path = Path(sys.argv[1])
        if not path.is_file():
            sys.exit(f"No such file: {path}")
        return path

    candidates = [
        p for p in LOGO_DIR.glob("*")
        if p.suffix.lower() in {".png", ".webp", ".jpg", ".jpeg"}
    ]
    if not candidates:
        sys.exit(
            f"No image found in {LOGO_DIR}.\n"
            "Drop the logo there (square PNG with transparency, 512px or larger)."
        )
    return max(candidates, key=lambda p: p.stat().st_mtime)


def load_square(path: Path) -> Image.Image:
    """Open the source and pad it to a square without distorting it."""
    img = Image.open(path).convert("RGBA")
    side = max(img.size)
    if img.size != (side, side):
        square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        square.paste(img, ((side - img.width) // 2, (side - img.height) // 2))
        img = square
    return img


def resized(img: Image.Image, size: int) -> Image.Image:
    return img.resize((size, size), Image.LANCZOS)


def write(img: Image.Image, folder: str, name: str) -> None:
    target = RES / folder
    target.mkdir(parents=True, exist_ok=True)
    img.save(target / name)
    print(f"  {folder}/{name}  {img.width}x{img.height}")


def circular_mask(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


def main() -> None:
    source = find_source()
    print(f"Source: {source}")
    logo = load_square(source)

    print("Adaptive foreground (artwork inset to the safe zone):")
    for folder, size in FOREGROUND_SIZES.items():
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        inner = max(1, int(size * SAFE_ZONE))
        offset = (size - inner) // 2
        canvas.paste(resized(logo, inner), (offset, offset), resized(logo, inner))
        write(canvas, folder, "ic_launcher_foreground.png")

    print("Legacy square and round:")
    for folder, size in LAUNCHER_SIZES.items():
        square = Image.new("RGBA", (size, size), BRAND_BG)
        inner = max(1, int(size * 0.78))
        offset = (size - inner) // 2
        art = resized(logo, inner)
        square.paste(art, (offset, offset), art)
        write(square, folder, "ic_launcher.png")

        round_icon = square.copy()
        round_icon.putalpha(circular_mask(size))
        write(round_icon, folder, "ic_launcher_round.png")

    # Two banners: the flat one for pre-26 launchers, and a transparent foreground
    # that mipmap-anydpi-v26/ic_banner.xml insets over its own background colour.
    print("TV banner:")
    inner = int(BANNER_SIZE[1] * 0.72)
    art = resized(logo, inner)
    position = ((BANNER_SIZE[0] - inner) // 2, (BANNER_SIZE[1] - inner) // 2)

    banner = Image.new("RGBA", BANNER_SIZE, BRAND_BG)
    banner.paste(art, position, art)
    write(banner, "mipmap-xhdpi", "ic_banner.png")

    banner_fg = Image.new("RGBA", BANNER_SIZE, (0, 0, 0, 0))
    banner_fg.paste(art, position, art)
    write(banner_fg, "mipmap-xhdpi", "ic_banner_foreground.png")

    print("\nDone. Rebuild to see it: gradlew assemblePlaystoreDebug")


if __name__ == "__main__":
    main()
