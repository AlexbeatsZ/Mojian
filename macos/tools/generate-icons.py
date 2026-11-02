"""Generate macOS Fluent icon geometry from the Android drawable sources.

Android vectors are the single icon source (Windows resources are generated from the same files).
The output keeps every path, normalizes it to absolute M/L/C/Z commands and records literal colors,
so AppKit can rebuild CGPaths without a general SVG parser.

Run from the repository root:
    uv run --with svgelements python macos/tools/generate-icons.py
"""
import json
import pathlib
import xml.etree.ElementTree as ET

from svgelements import Arc, Close, CubicBezier, Line, Matrix, Move, Path, QuadraticBezier

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "android/app/src/main/res/drawable"
TARGET = ROOT / "macos/Sources/MojianDesktop/Resources/fluent-icons.json"
NS = "{http://schemas.android.com/apk/res/android}"


def number(value: float) -> str:
    text = f"{value:.3f}".rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


def normalize(data: str, matrix: Matrix) -> str:
    out = []
    path = Path(data) * matrix
    path.reify()
    for segment in path.segments():
        if isinstance(segment, Move):
            out += ["M", number(segment.end.x), number(segment.end.y)]
        elif isinstance(segment, Close):
            out.append("Z")
        elif isinstance(segment, Line):
            out += ["L", number(segment.end.x), number(segment.end.y)]
        elif isinstance(segment, CubicBezier):
            out += ["C"] + [number(v) for p in (segment.control1, segment.control2, segment.end) for v in (p.x, p.y)]
        elif isinstance(segment, QuadraticBezier):
            s, c, e = segment.start, segment.control, segment.end
            c1 = (s.x + 2 / 3 * (c.x - s.x), s.y + 2 / 3 * (c.y - s.y))
            c2 = (e.x + 2 / 3 * (c.x - e.x), e.y + 2 / 3 * (c.y - e.y))
            out += ["C"] + [number(v) for v in (*c1, *c2, e.x, e.y)]
        elif isinstance(segment, Arc):
            for cubic in segment.as_cubic_curves():
                out += ["C"] + [number(v) for p in (cubic.control1, cubic.control2, cubic.end) for v in (p.x, p.y)]
        else:
            raise ValueError(f"unsupported segment {segment!r}")
    return " ".join(out)


def color(value: str | None) -> str | None:
    if not value or value.startswith("@"):
        return None  # tintable: follows the control foreground
    value = value.lstrip("#").upper()
    rgb = value[-6:]
    # Opaque white is the dark-theme foreground, so it follows the same tint as tintable vectors.
    return None if rgb == "FFFFFF" else "#" + rgb


def collect(node: ET.Element, matrix: Matrix, paths: list) -> None:
    for child in node:
        if child.tag == "group":
            px, py = float(child.attrib.get(NS + "pivotX", 0)), float(child.attrib.get(NS + "pivotY", 0))
            sx, sy = float(child.attrib.get(NS + "scaleX", 1)), float(child.attrib.get(NS + "scaleY", 1))
            tx, ty = float(child.attrib.get(NS + "translateX", 0)), float(child.attrib.get(NS + "translateY", 0))
            local = Matrix(f"translate({-px},{-py})") * Matrix(f"scale({sx},{sy})") * Matrix(f"translate({px + tx},{py + ty})")
            collect(child, local * matrix, paths)
        elif child.tag == "path":
            item = {"d": normalize(child.attrib[NS + "pathData"], matrix)}
            if (fill := color(child.attrib.get(NS + "fillColor"))) is not None:
                item["color"] = fill
            if child.attrib.get(NS + "fillType", "").lower() == "evenodd":
                item["evenOdd"] = True
            paths.append(item)


def main() -> None:
    icons = {}
    for file in sorted(SOURCE.glob("ic_fluent_*_24_regular.xml")):
        name = file.name[len("ic_fluent_"):-len("_24_regular.xml")]
        root = ET.parse(file).getroot()
        paths = []
        collect(root, Matrix(), paths)
        icons[name] = {
            "width": float(root.attrib[NS + "viewportWidth"]),
            "height": float(root.attrib[NS + "viewportHeight"]),
            "paths": paths,
        }
    TARGET.write_text(json.dumps(icons, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n", encoding="utf-8")
    print(f"{len(icons)} icons -> {TARGET.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
