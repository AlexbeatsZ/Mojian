"""Package the supplied Mojian artwork without redrawing the brand.

Run from any directory:
    uv run --no-project --with pillow python tools/build_icons.py
    uv run --no-project --with pillow python tools/build_icons.py --check
"""

import argparse
import math
import shutil
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "shared" / "branding"
BACKGROUND = "#1C2453"
ADAPTIVE_SIZE = 432  # 108 dp at xxxhdpi.
SAFE_RADIUS = 132  # Android's central 66 dp safe circle.
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def adaptive_foreground():
    artwork = Image.open(SOURCES / "Mojian_Windows_1024.png").convert("RGBA")
    # Fit the supplied alpha silhouette inside the safe circle, without clipping
    # its diagonal feather tip or flourish under circular/squircle launcher masks.
    center_x, center_y = artwork.width / 2, artwork.height / 2
    radius = max(
        math.hypot(x + 0.5 - center_x, y + 0.5 - center_y)
        for y in range(artwork.height)
        for x in range(artwork.width)
        if artwork.getpixel((x, y))[3]
    )
    # Leave two pixels for Lanczos's antialiasing fringe.
    scale = (SAFE_RADIUS - 2) / radius
    artwork = artwork.resize(
        (round(artwork.width * scale), round(artwork.height * scale)),
        Image.Resampling.LANCZOS,
    )
    foreground = Image.new("RGBA", (ADAPTIVE_SIZE, ADAPTIVE_SIZE))
    foreground.alpha_composite(
        artwork,
        ((ADAPTIVE_SIZE - artwork.width) // 2, (ADAPTIVE_SIZE - artwork.height) // 2),
    )
    return foreground


def generated_images():
    foreground = adaptive_foreground()
    res = ROOT / "android" / "app" / "src" / "main" / "res"
    # The 72 dp legacy viewport matches the default adaptive launcher viewport.
    inset = (ADAPTIVE_SIZE - 72 * 4) // 2
    viewport = foreground.crop((inset, inset, ADAPTIVE_SIZE - inset, ADAPTIVE_SIZE - inset))
    background = Image.new("RGBA", viewport.size, BACKGROUND)
    background.alpha_composite(viewport)
    for density, size in DENSITIES.items():
        foreground_size = size * 108 // 48
        yield (
            res / f"drawable-{density}" / "ic_launcher_foreground.png",
            foreground.resize((foreground_size, foreground_size), Image.Resampling.LANCZOS),
        )
        icon = background.resize((size, size), Image.Resampling.LANCZOS)
        folder = res / f"mipmap-{density}"
        legacy_mask = Image.new("L", (size * 4, size * 4))
        ImageDraw.Draw(legacy_mask).rounded_rectangle(
            (0, 0, size * 4 - 1, size * 4 - 1), radius=size * 0.22 * 4, fill=255
        )
        legacy_icon = icon.copy()
        legacy_icon.putalpha(legacy_mask.resize(icon.size, Image.Resampling.LANCZOS))
        yield folder / "ic_launcher.png", legacy_icon
        mask = Image.new("L", (size * 4, size * 4))
        ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
        round_icon = icon.copy()
        round_icon.putalpha(mask.resize(icon.size, Image.Resampling.LANCZOS))
        yield folder / "ic_launcher_round.png", round_icon

    # iOS applies its own rounded mask. Fill only the supplied macOS PNG's
    # transparent outer corners with the exact supplied background color.
    apple = Image.open(SOURCES / "Mojian_macOS_1024.png").convert("RGBA")
    ios_icon = Image.new("RGBA", apple.size, BACKGROUND)
    ios_icon.alpha_composite(apple)
    yield ROOT / "ios/App/Assets.xcassets/AppIcon.appiconset/Icon.png", ios_icon.convert("RGB")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check committed resources without writing.")
    args = parser.parse_args()
    windows = ROOT / "mojian/src/Notein-win/Assets"
    copies = [
        (SOURCES / "Mojian_Windows.ico", windows / "mojian.ico"),
        (SOURCES / "Mojian_Windows_256.png", windows / "mojian.png"),
    ]
    checked = 0
    for source, target in copies:
        if args.check:
            assert target.read_bytes() == source.read_bytes(), f"Outdated resource: {target}"
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        checked += 1
    for target, image in generated_images():
        if args.check:
            actual = Image.open(target)
            assert actual.size == image.size and actual.mode == image.mode, f"Wrong image format: {target}"
            difference = ImageChops.difference(actual, image)
            assert all(high == 0 for low, high in difference.getextrema()), f"Outdated resource: {target}"
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            image.save(target, "PNG")
        checked += 1
    color_xml = ROOT / "android/app/src/main/res/values/colors.xml"
    assert f'<color name="ic_launcher_background">{BACKGROUND}</color>' in color_xml.read_text("utf-8")
    print(f"{'Checked' if args.check else 'Packaged'} {checked} brand resources.")


if __name__ == "__main__":
    main()
