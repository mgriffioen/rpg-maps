#!/usr/bin/env python3
"""
Regenerates the launcher icon foreground from app/map.png.

Run this after changing the source artwork:

    pip install Pillow
    python3 tools/generate-launcher-icon.py

An adaptive icon's layers are a 108dp canvas, but the launcher never shows all
of it. Three numbers matter:

    108dp   the layer you supply
     72dp   the centre square actually displayed -- the outer 18dp on each
            side exists only so the launcher can slide the layers for its
            parallax effect, and is always cropped away
     66dp   the centre circle guaranteed to survive whatever mask the
            launcher applies: circle, squircle, rounded square, teardrop

So a full-bleed 512px image dropped straight in is not merely tight, it is
cropped: at 108dp wide it would lose 18dp off each side before any mask is
even considered. The artwork is therefore scaled down and centred with
transparent padding. ART_DP is that scaled width, on the 108dp canvas.

58dp leaves a comfortable margin inside the 72dp viewport and keeps the scroll
rollers clear of a circular mask, while still filling the icon about as much
as other apps do. Verified by emulating both masks -- see the sweep in the
commit that introduced this.
"""

import pathlib

from PIL import Image

REPO = pathlib.Path(__file__).resolve().parent.parent
SOURCE = REPO / "app" / "map.png"
RES = REPO / "app" / "src" / "main" / "res"

CANVAS_DP = 108
ART_DP = 58

# The launcher icon densities Android expects, as multipliers of dp.
DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}


def main() -> None:
    source = Image.open(SOURCE).convert("RGBA")

    for name, factor in DENSITIES.items():
        canvas_px = round(CANVAS_DP * factor)
        art_px = round(ART_DP * factor)

        art = source.resize((art_px, art_px), Image.LANCZOS)
        out = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
        offset = (canvas_px - art_px) // 2
        out.paste(art, (offset, offset))

        directory = RES / f"mipmap-{name}"
        directory.mkdir(parents=True, exist_ok=True)
        target = directory / "ic_launcher_foreground.png"
        out.save(target, optimize=True)
        print(f"{target.relative_to(REPO)}  {canvas_px}px canvas, {art_px}px art")


if __name__ == "__main__":
    main()
