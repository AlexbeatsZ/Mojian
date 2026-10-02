from __future__ import annotations

import argparse
import hashlib
import html
import io
import json
import math
import mimetypes
import os
import re
import shutil
import sqlite3
import struct
import tempfile
import zipfile
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

import pymupdf as fitz


JSON_KWARGS = {"ensure_ascii": False, "indent": 2}
PAGE_JSON_KWARGS = {"ensure_ascii": False, "separators": (",", ":")}
UNITS_PER_MM = 96.0 / 25.4
PALETTE = {
    "Black": (0x1B, 0x1B, 0x1F),
    "Blue": (0x18, 0x5A, 0xBD),
    "Red": (0xC4, 0x2B, 0x1C),
}


def stable_id(prefix: str, value: str) -> str:
    return f"{prefix}-{hashlib.sha256(value.encode('utf-8')).hexdigest()[:24]}"


def safe_name(value: str, default: str = "asset") -> str:
    value = Path(value).name
    value = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", value).strip(" .")
    return value or default


def epoch_millis(value: Any) -> str:
    try:
        return datetime.fromtimestamp(int(value) / 1000, tz=timezone.utc).isoformat().replace("+00:00", "Z")
    except (TypeError, ValueError, OSError, OverflowError):
        return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def parse_time(value: Any) -> str:
    if isinstance(value, str) and value:
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        except ValueError:
            pass
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def atomic_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    options = PAGE_JSON_KWARGS if isinstance(payload, dict) and "strokes" in payload else JSON_KWARGS
    temporary.write_text(json.dumps(payload, **options), encoding="utf-8")
    os.replace(temporary, path)


def color_components(value: int | str | None) -> tuple[int, int, int, int]:
    if isinstance(value, str):
        text = value.lstrip("#")
        if len(text) == 6:
            return 255, int(text[0:2], 16), int(text[2:4], 16), int(text[4:6], 16)
        if len(text) == 8:
            return int(text[0:2], 16), int(text[2:4], 16), int(text[4:6], 16), int(text[6:8], 16)
    raw = int(value or 0)
    # AndroidX Color stores packed sRGB ARGB in the upper word of a ColorLong.
    if raw > 0xFFFFFFFF:
        raw >>= 32
    raw &= 0xFFFFFFFF
    alpha = (raw >> 24) & 0xFF
    if alpha == 0:
        alpha = 255
    return alpha, (raw >> 16) & 0xFF, (raw >> 8) & 0xFF, raw & 0xFF


def source_color_hex(value: int | str | None) -> str:
    return '#%02X%02X%02X%02X' % color_components(value)


def stroke_outline(points: list[dict[str, Any]], size: float, *, variable: bool = True) -> str:
    """One filled, movable vector per stroke; no preview pixels or per-segment opacity."""
    if not points:
        return ''
    samples = []
    for p in points:
        radius = size * (0.38 + max(0.0, min(1.0, p['pressure'])) * 0.78) / 2 if variable else size / 2
        if samples and math.hypot(p['x'] - samples[-1][0], p['y'] - samples[-1][1]) < 0.001:
            samples[-1] = (p['x'], p['y'], max(radius, samples[-1][2]))
        else:
            samples.append((p['x'], p['y'], radius))
    def number(n): return format(n, '.5f').rstrip('0').rstrip('.') or '0'
    if len(samples) == 1:
        x,y,r=samples[0]
        return f'M {number(x-r)},{number(y)} A {number(r)},{number(r)} 0 1 0 {number(x+r)},{number(y)} A {number(r)},{number(r)} 0 1 0 {number(x-r)},{number(y)} Z'
    left, right = [], []
    for i,(x,y,r) in enumerate(samples):
        before=samples[max(0,i-1)]; after=samples[min(len(samples)-1,i+1)]
        dx,dy=after[0]-before[0],after[1]-before[1]; length=math.hypot(dx,dy) or 1
        nx,ny=-dy/length*r,dx/length*r
        left.append((x+nx,y+ny)); right.append((x-nx,y-ny))
    if not variable:
        vertices=left+list(reversed(right))
        return 'M '+' L '.join(f'{number(x)},{number(y)}' for x,y in vertices)+' Z'
    r=samples[-1][2]; first_r=samples[0][2]
    coords=lambda seq: ' L '.join(f'{number(x)},{number(y)}' for x,y in seq)
    return ('M '+coords(left)+f' A {number(r)},{number(r)} 0 0 0 '+coords(list(reversed(right)))+
            f' A {number(first_r)},{number(first_r)} 0 0 0 {number(left[0][0])},{number(left[0][1])} Z')


def cache_vector_bounds(stroke: dict[str, Any]) -> None:
    points = stroke.get('points') or []
    if points and stroke.get('vectorPath') and not stroke.get('vectorBounds'):
        margin = max(1.0, stroke.get('widthMm', 1) * UNITS_PER_MM * 2)
        stroke['vectorBounds'] = [min(p['x'] for p in points)-margin, min(p['y'] for p in points)-margin,
                                  max(p['x'] for p in points)+margin, max(p['y'] for p in points)+margin]


def page_fingerprint(page: fitz.Page) -> bytes:
    # Normalize only for identifying a printout's source page, never for display.
    rect = page.rect
    pix = page.get_pixmap(matrix=fitz.Matrix(31.999 / rect.width, 47.999 / rect.height), colorspace=fitz.csRGB, alpha=False)
    return pix.samples


def match_printout(image: bytes, candidates: list[tuple[str, int, bytes]]) -> tuple[str, int] | None:
    with fitz.open(stream=image) as document:
        fingerprint = page_fingerprint(document[0])
    scores = sorted((sum(abs(a-b) for a,b in zip(fingerprint, sample)) / (255 * len(sample)), name, page)
                    for name,page,sample in candidates if len(sample) == len(fingerprint))
    if not scores or scores[0][0] > 0.025:
        return None
    # Similar/blank/ambiguous pages retain the original image. A guessed PDF
    # relationship must never silently substitute a different source page.
    if len(scores) > 1 and scores[1][0] - scores[0][0] < 0.005:
        return None
    _,name,page = scores[0]
    return name,page


def nearest_palette(value: int | str | None) -> tuple[int, str, int]:
    alpha, red, green, blue = color_components(value)
    best_name = min(
        PALETTE,
        key=lambda name: sum((component - target) ** 2 for component, target in zip((red, green, blue), PALETTE[name])),
    )
    return {"Black": 0, "Blue": 1, "Red": 2}[best_name], best_name, alpha


def strip_markup(value: str | None) -> str:
    if not value:
        return ""
    text = re.sub(r"(?i)<br\s*/?>", "\n", value)
    text = re.sub(r"(?i)</(?:p|div|li|font)>", "\n", text)
    text = re.sub(r"<[^>]+>", "", text)
    return html.unescape(text).strip()


def page_appearance(
    *, width: float = 793.7, height: float = 1122.5, paper_kind: int = 0, show_title: bool = True
) -> dict[str, Any]:
    return {
        "paperKind": paper_kind,
        "theme": 1,
        "background": 0,
        "pageColor": "#FFFFFF",
        "showTitle": show_title,
        "sheetCount": 1,
        "width": max(1.0, width),
        "height": max(1.0, height),
    }


def note_page(
    page_id: str,
    title: str,
    *,
    created_at: str,
    updated_at: str,
    appearance: dict[str, Any],
    pdf: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "id": page_id,
        "title": title or "无标题页",
        "createdAt": created_at,
        "updatedAt": updated_at,
        "appearance": appearance,
        "isPdfPage": pdf is not None,
        "pdfPageNumber": pdf.get("pageNumber") if pdf else None,
        "pdfPageCount": pdf.get("pageCount") if pdf else None,
        "pdfWidthPoints": pdf.get("widthPoints") if pdf else None,
        "pdfHeightPoints": pdf.get("heightPoints") if pdf else None,
        "pdfSourceAssetName": pdf.get("sourceAssetName") if pdf else None,
        "pdfSourceDocumentName": pdf.get("sourceDocumentName") if pdf else None,
    }


def page_document(notebook_id: str, page_id: str) -> dict[str, Any]:
    return {
        "version": 2,
        "notebookId": notebook_id,
        "pageId": page_id,
        "revision": 1,
        "updatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "viewport": {"scale": 1.0, "translationX": 0.0, "translationY": 0.0},
        "strokes": [],
        "tags": [],
        "textItems": [],
        "objects": [],
        "layers": [],
    }


class WorkspaceWriter:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.notebooks_root = (self.root / "notebooks").resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.notebooks_root.mkdir(parents=True, exist_ok=True)
        library_path = self.root / "library.json"
        if library_path.is_file():
            self.library = json.loads(library_path.read_text(encoding="utf-8"))
        else:
            self.library = {"version": 2, "notebooks": [], "activeNotebookId": ""}
        self.library.setdefault("version", 2)
        self.library.setdefault("notebooks", [])

    def replace_notebook(self, notebook: dict[str, Any]) -> None:
        notebook_id = notebook["id"]
        target = (self.notebooks_root / safe_name(notebook_id, "invalid")).resolve()
        if target.parent != self.notebooks_root:
            raise ValueError(f"Unsafe notebook destination: {target}")
        if target.exists():
            shutil.rmtree(target)
        target.mkdir(parents=True)
        self.library["notebooks"] = [item for item in self.library["notebooks"] if item.get("id") != notebook_id]
        self.library["notebooks"].append(notebook)
        if not self.library.get("activeNotebookId"):
            self.library["activeNotebookId"] = notebook_id

    def notebook_directory(self, notebook_id: str) -> Path:
        path = (self.notebooks_root / safe_name(notebook_id, "invalid")).resolve()
        if path.parent != self.notebooks_root:
            raise ValueError(f"Unsafe notebook path: {path}")
        path.mkdir(parents=True, exist_ok=True)
        return path

    def asset_directory(self, notebook_id: str, page_id: str) -> Path:
        path = self.notebook_directory(notebook_id) / "assets" / safe_name(page_id, "invalid")
        path.mkdir(parents=True, exist_ok=True)
        return path

    def write_asset(self, notebook_id: str, page_id: str, preferred_name: str, data: bytes) -> str:
        digest = hashlib.sha256(data).hexdigest()[:16]
        extension = Path(preferred_name).suffix.lower()
        if len(extension) > 12 or not re.fullmatch(r"\.[a-z0-9]+", extension):
            extension = ".bin"
        name = f"asset-{digest}{extension}"
        path = self.asset_directory(notebook_id, page_id) / name
        if not path.exists():
            path.write_bytes(data)
        return name

    def link_asset(self, notebook_id: str, page_id: str, source: Path) -> str:
        source = source.resolve()
        name = safe_name(source.name)
        destination = self.asset_directory(notebook_id, page_id) / name
        if destination.exists():
            return name
        try:
            os.link(source, destination)
        except OSError:
            shutil.copy2(source, destination)
        return name

    def write_page(self, notebook_id: str, page_id: str, document: dict[str, Any]) -> None:
        path = self.notebook_directory(notebook_id) / "pages" / f"{safe_name(page_id)}.json"
        atomic_json(path, document)

    def finish(self) -> None:
        atomic_json(self.root / "library.json", self.library)
        settings = self.root / "settings.json"
        if not settings.exists():
            atomic_json(settings, {})


def read_varint(blob: bytes, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while offset < len(blob):
        byte = blob[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, offset
        shift += 7
        if shift > 70:
            break
    raise ValueError("Invalid protobuf varint")


def protobuf_fields(blob: bytes) -> dict[int, list[tuple[int, Any]]]:
    result: dict[int, list[tuple[int, Any]]] = {}
    offset = 0
    while offset < len(blob):
        key, offset = read_varint(blob, offset)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, offset = read_varint(blob, offset)
        elif wire == 1:
            value = blob[offset : offset + 8]
            offset += 8
        elif wire == 2:
            length, offset = read_varint(blob, offset)
            value = blob[offset : offset + length]
            offset += length
        elif wire == 5:
            value = blob[offset : offset + 4]
            offset += 4
        else:
            raise ValueError(f"Unsupported protobuf wire type {wire}")
        result.setdefault(number, []).append((wire, value))
    return result


def packed_floats(value: bytes) -> list[float]:
    if len(value) % 4:
        raise ValueError("Packed float field is not aligned")
    return list(struct.unpack("<" + "f" * (len(value) // 4), value))


def apply_matrix(values: list[float], x: float, y: float) -> tuple[float, float]:
    if len(values) < 9:
        return x, y
    return values[0] * x + values[1] * y + values[2], values[3] * x + values[4] * y + values[5]


def decode_notein_new_stroke(blob: bytes, fallback_id: str, stats: Counter[str]) -> dict[str, Any]:
    fields = protobuf_fields(blob)
    stroke_id = fallback_id
    if fields.get(1):
        stroke_id = fields[1][0][1].decode("utf-8", "replace")
    size = struct.unpack("<f", fields[4][0][1])[0] if fields.get(4) else 4.8649
    color = fields[5][0][1] if fields.get(5) else 0xFF000000
    positions = packed_floats(fields[10][0][1]) if fields.get(10) else []
    inputs = packed_floats(fields[11][0][1]) if fields.get(11) else []
    first_matrix = packed_floats(fields[12][0][1]) if fields.get(12) else []
    second_matrix = packed_floats(fields[13][0][1]) if fields.get(13) else []
    palette, palette_name, alpha = nearest_palette(color)
    stats[f"palette-{palette_name}"] += 1
    points: list[dict[str, Any]] = []
    count = len(positions) // 2
    input_width = len(inputs) // count if count and len(inputs) % count == 0 else 0
    for index in range(count):
        x, y = positions[index * 2 : index * 2 + 2]
        x, y = apply_matrix(first_matrix, x, y)
        x, y = apply_matrix(second_matrix, x, y)
        group = inputs[index * input_width : (index + 1) * input_width] if input_width else []
        elapsed = round(group[0]) if len(group) >= 1 else index * 8
        pressure = group[1] if len(group) >= 2 else 0.5
        points.append(
            {
                "x": round(x, 4),
                "y": round(y, 4),
                "pressure": round(max(0.01, min(1.0, pressure)), 5),
                "tiltX": 0.0,
                "tiltY": float(group[2]) if len(group) > 2 else 0.0,
                "elapsedMilliseconds": max(0, int(elapsed)),
            }
        )
    params = {}
    for _, parameter in fields.get(14, []):
        entry = protobuf_fields(parameter)
        if entry.get(1) and entry.get(2):
            params[entry[1][0][1].decode('utf-8')] = struct.unpack('<f', entry[2][0][1])[0]
    profile = fields[7][0][1].decode('utf-8') if fields.get(7) else 'notein-fountain-v1'
    kind = 1 if 'highlight' in profile.lower() or 'marker' in profile.lower() else 0
    scale = 1.0
    for matrix in (first_matrix, second_matrix):
        if len(matrix) >= 9: scale *= math.sqrt(abs(matrix[0]*matrix[4]-matrix[1]*matrix[3]))
    size *= scale
    return {
        "id": stable_id("stroke-notein", stroke_id),
        "kind": kind,
        "color": palette,
        "colorHex": source_color_hex(color),
        "widthMm": max(0.05, size / UNITS_PER_MM),
        "pressurePercent": round(params.get('pressure_correct', 2) * 100),
        "inkFlowPercent": round(params.get('speed_correct', 1) * 100),
        "opacityPercent": round(alpha * 100 / 255),
        "brushProfile": "notein-fountain-v1-import",
        "points": points,
        "vectorPath": stroke_outline(points, size, variable=kind == 0),
        "sourceBrush": {"profile": profile, "parameters": params},
    }


def decode_notein_legacy_stroke(raw: str, fallback_id: str, stats: Counter[str]) -> dict[str, Any]:
    source = json.loads(raw)
    kind = 1 if int(source.get("type", 0)) == 5 else 0
    palette, palette_name, alpha = nearest_palette(source.get("color"))
    stats[f"palette-{palette_name}"] += 1
    if color_components(source.get("color"))[1:] not in PALETTE.values():
        stats["mapped-custom-colors"] += 1
    points = []
    for index, point in enumerate(source.get("points") or []):
        points.append(
            {
                "x": round(float(point.get("x", 0)), 4),
                "y": round(float(point.get("y", 0)), 4),
                "pressure": round(max(0.01, min(1.0, float(point.get("p", 0.5) or 0.5))), 5),
                "tiltX": 0.0,
                "tiltY": 0.0,
                "elapsedMilliseconds": index * 8,
            }
        )
    return {
        "id": stable_id("stroke-notein", str(source.get("id") or fallback_id)),
        "kind": kind,
        "color": palette,
        "colorHex": source_color_hex(source.get('color')),
        "widthMm": max(0.05, float(source.get("width", 4.8649)) / UNITS_PER_MM),
        "pressurePercent": 200,
        "inkFlowPercent": 100,
        "opacityPercent": 32 if kind == 1 else round(alpha * 100 / 255),
        "brushProfile": "notein-legacy-import",
        "points": points,
        "vectorPath": stroke_outline(points, float(source.get('width', 4.8649)), variable=kind == 0),
    }


@dataclass
class NoteinSource:
    entry: zipfile.ZipInfo
    note_id: str
    title: str
    created_at: str
    updated_at: str


class NoteinImporter:
    def __init__(
        self,
        backup: Path,
        external: Path | None,
        writer: WorkspaceWriter,
        work: Path,
        skip_missing_pdfs: bool = False,
    ):
        self.backup = backup
        self.external = external.resolve() if external else None
        self.writer = writer
        self.work = work
        self.skip_missing_pdfs = skip_missing_pdfs
        self.stats: Counter[str] = Counter()
        self.errors: list[dict[str, str]] = []

    def import_all(self) -> dict[str, Any]:
        notebook_id = "notebook-notein-import"
        notebook = {
            "id": notebook_id,
            "title": "Notein 导入",
            "sections": [],
            "expanded": True,
            "focusedSectionId": None,
            "selectedPageSectionId": "",
            "selectedPageId": "",
        }
        self.writer.replace_notebook(notebook)
        with zipfile.ZipFile(self.backup) as outer:
            sources = []
            for entry in outer.infolist():
                if not entry.filename.lower().endswith(".in"):
                    continue
                try:
                    with zipfile.ZipFile(io.BytesIO(outer.read(entry))) as inner:
                        meta = json.loads(inner.read("note_meta.json").decode("utf-8"))
                    sources.append(
                        NoteinSource(
                            entry=entry,
                            note_id=str(meta.get("id") or entry.filename.split("/")[0].removeprefix("n_")),
                            title=str(meta.get("title") or "无标题笔记"),
                            created_at=epoch_millis(meta.get("creationTime")),
                            updated_at=epoch_millis(meta.get("lastModificationTime")),
                        )
                    )
                except Exception as exc:  # continue with other independently backed-up notes
                    self.errors.append({"note": entry.filename.split("/")[0], "error": str(exc)})
            sources.sort(key=lambda item: (item.created_at, item.note_id))
            for index, source in enumerate(sources, 1):
                try:
                    section = self._import_note(outer, source, notebook_id)
                    if section is not None:
                        notebook["sections"].append(section)
                    print(f"NOTEIN_NOTE {index}/{len(sources)} ok", flush=True)
                except Exception as exc:
                    self.errors.append({"note": source.note_id, "error": str(exc)})
                    print(f"NOTEIN_NOTE {index}/{len(sources)} failed", flush=True)
        first_section = next(
            (s for s in notebook["sections"] if s.get("pages") and not s.get("id", "").startswith("section-notein-preview")),
            notebook["sections"][0] if notebook["sections"] else None,
        )
        if first_section:
            notebook["focusedSectionId"] = first_section["id"]
            if first_section["pages"]:
                notebook["selectedPageSectionId"] = first_section["id"]
                notebook["selectedPageId"] = first_section["pages"][0]["id"]
        return {"stats": dict(self.stats), "errors": self.errors}

    def _import_note(
        self, outer: zipfile.ZipFile, source: NoteinSource, notebook_id: str
    ) -> dict[str, Any] | None:
        section_id = stable_id("section-notein", source.note_id)
        section = {"id": section_id, "title": source.title, "pages": [], "children": [], "expanded": True}
        preview_section = {
            "id": stable_id("section-notein-preview", source.note_id),
            "title": "原貌图片",
            "pages": [],
            "children": [],
            "expanded": False,
        }
        raw_container = outer.read(source.entry)
        with zipfile.ZipFile(io.BytesIO(raw_container)) as inner:
            names = inner.namelist()
            database_name = next(name for name in names if name.startswith("note_database_") and not name.endswith(("-shm", "-wal")))
            database = inner.read(database_name)
            pdf_names = [name for name in names if name.lower().endswith(".pdf")]
            pdf_name = pdf_names[0] if pdf_names else None
            pdf_bytes = inner.read(pdf_name) if pdf_name else None
            image_names = {Path(name).name: name for name in names if name.startswith("note_image_")}
            db_path = self._write_temp_database(database, source.note_id)
            try:
                connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
                connection.row_factory = sqlite3.Row
                content = connection.execute("select * from NoteContentEntity limit 1").fetchone()
                active_ids = json.loads(content["page_list"] or "[]") if content else []
                page_rows = {
                    row["id"]: row
                    for row in connection.execute(
                        f"select * from PageEntity where id in ({','.join('?' for _ in active_ids)})",
                        active_ids,
                    )
                } if active_ids else {}
                has_pdf_model = connection.execute("select count(*) from PdfInfoEntity").fetchone()[0] > 0
                content_pages = self._content_page_ids(connection)
                if has_pdf_model and not pdf_bytes and self.skip_missing_pdfs:
                    self.stats['skipped-missing-pdf-notes'] += 1
                    self.stats['skipped-missing-pdf-pages'] += len(active_ids)
                    self.stats['skipped-missing-pdf-content-pages'] += sum(page in content_pages for page in active_ids)
                    self.stats['source-notes'] += 1
                    connection.close()
                    return None
                summary: dict[str, Any] | None = None
                source_pdf_path: Path | None = None
                self.stats["source-active-pages"] += len(active_ids)
                if has_pdf_model:
                    self.stats["source-pdf-notes"] += 1
                    selected_ids = [page_id for page_id in active_ids if page_id in content_pages]
                    self.stats["skipped-unannotated-pdf-pages"] += len(active_ids) - len(selected_ids)
                    summary, source_pdf_path = self._pdf_summary_page(
                        notebook_id, source, len(active_ids), pdf_name, pdf_bytes
                    )
                else:
                    self.stats["source-plain-notes"] += 1
                    selected_ids = list(active_ids)
                pdf_document = fitz.open(stream=pdf_bytes, filetype="pdf") if pdf_bytes else None
                try:
                    for page_index, page_id in enumerate(active_ids):
                        if page_id not in selected_ids:
                            continue
                        title = f"第 {page_index + 1} 页" + (" · 可编辑批注" if has_pdf_model else "")
                        row = page_rows.get(page_id)
                        if row is None:
                            self.stats["missing-page-records"] += 1
                            placeholder = self._missing_page_placeholder(
                                notebook_id,
                                source,
                                page_id,
                                page_index,
                                title,
                            )
                            section["pages"].append(placeholder)
                            continue
                        page, document = self._editable_page(
                            connection,
                            inner,
                            image_names,
                            notebook_id,
                            source,
                            row,
                            page_index,
                            title,
                            pdf_document,
                            has_pdf_model,
                            source_pdf_path,
                        )
                        section["pages"].append(page)
                        self.writer.write_page(notebook_id, page["id"], document)
                        preview = self._preview_page(notebook_id, source, row, page_index, title)
                        if preview:
                            preview_section["pages"].append(preview)
                finally:
                    if pdf_document:
                        pdf_document.close()
                    connection.close()
                if summary is not None:
                    section["pages"].append(summary)
            finally:
                db_path.unlink(missing_ok=True)
        if preview_section["pages"]:
            section["children"].append(preview_section)
        self.stats["source-notes"] += 1
        return section

    def _missing_page_placeholder(
        self,
        notebook_id: str,
        source: NoteinSource,
        source_page_id: str,
        page_index: int,
        title: str,
    ) -> dict[str, Any]:
        page_id = stable_id("page-notein-missing", f"{source.note_id}:{source_page_id}")
        page = note_page(
            page_id,
            title,
            created_at=source.created_at,
            updated_at=source.updated_at,
            appearance=page_appearance(width=1240, height=1754, paper_kind=1, show_title=True),
        )
        document = page_document(notebook_id, page_id)

        document["textItems"].append(
            {
                "id": stable_id("text-notein-missing", f"{source.note_id}:{source_page_id}"),
                "x": 72.0,
                "y": 100.0,
                "width": 720.0,
                "height": 100.0,
                "text": "Notein 官方备份的活动页列表包含此页，但备份数据库缺少对应页面记录。已保留页面位置，未伪造内容。",
                "fontFamily": "Segoe UI Variable, Microsoft YaHei UI",
                "fontSize": 16.0,
                "bold": False,
                "italic": False,
                "underline": False,
                "strikethrough": False,
                "subscript": False,
                "color": 0,
                "foregroundHex": "",
                "backgroundHex": "Transparent",
                "indentLevel": 0,
                "alignment": "Left",
                "lineSpacing": 1.0,
                "styleName": "正文",
            }
        )
        self.writer.write_page(notebook_id, page_id, document)
        self.stats["placeholder-pages"] += 1
        return page

    def _write_temp_database(self, data: bytes, note_id: str) -> Path:
        self.work.mkdir(parents=True, exist_ok=True)
        fd, name = tempfile.mkstemp(prefix=f"notein-{safe_name(note_id)}-", suffix=".db", dir=self.work)
        os.close(fd)
        path = Path(name)
        path.write_bytes(data)
        return path

    @staticmethod
    def _content_page_ids(connection: sqlite3.Connection) -> set[str]:
        result: set[str] = set()
        for table in ("StrokeEntity", "TextBoxEntity", "ImageEntity", "ShapeEntity", "QuoteEntity"):
            result.update(row[0] for row in connection.execute(f"select distinct page_id from {table}"))
        return result

    def _pdf_summary_page(
        self,
        notebook_id: str,
        source: NoteinSource,
        page_count: int,
        pdf_name: str | None,
        pdf_bytes: bytes | None,
    ) -> tuple[dict[str, Any], Path | None]:
        page_id = stable_id("page-notein-summary", source.note_id)
        page = note_page(
            page_id,
            "原始 PDF 备份与迁移说明",
            created_at=source.created_at,
            updated_at=source.updated_at,
            appearance=page_appearance(show_title=True),
        )
        document = page_document(notebook_id, page_id)
        source_pdf_path: Path | None = None
        if pdf_bytes:
            asset = self.writer.write_asset(notebook_id, page_id, pdf_name or "source.pdf", pdf_bytes)
            source_pdf_path = self.writer.asset_directory(notebook_id, page_id) / asset
            document["objects"].append(
                {
                    "id": stable_id("object-notein-pdf", source.note_id),
                    "kind": 1,
                    "x": 72.0,
                    "y": 130.0,
                    "width": 360.0,
                    "height": 64.0,
                    "displayName": safe_name(pdf_name or f"{source.title}.pdf"),
                    "assetName": asset,
                    "mimeType": "application/pdf",
                    "sizeBytes": len(pdf_bytes),
                    "viewInverted": False,
                    "rows": 0,
                    "columns": 0,
                    "cells": [],
                    "previewRows": [],
                    "targetNotebookId": "",
                    "targetSectionId": "",
                    "targetPageId": "",
                    "sourceAssetName": "",
                    "sourcePageNumber": None,
                    "isPageBackground": False,
                    "rotationQuarterTurns": 0,
                    "flipHorizontal": False,
                    "flipVertical": False,
                }
            )
            message = f"原始 PDF 已完整嵌入（{page_count} 页），仅作源文件备份；有批注的页面位于本分区前部，笔迹可编辑。"
            self.stats["embedded-source-pdfs"] += 1
        else:
            message = (
                f"Notein 本地备份未包含这份原始 PDF（记录页数 {page_count}）。"
                "已导入可编辑批注，并在可用时保留原貌预览。"
            )
            self.stats["missing-source-pdfs"] += 1
        document["textItems"].append(
            {
                "id": stable_id("text-notein-summary", source.note_id),
                "x": 72.0,
                "y": 72.0,
                "width": 620.0,
                "height": 80.0,
                "text": message,
                "fontFamily": "Segoe UI Variable, Microsoft YaHei UI",
                "fontSize": 16.0,
                "bold": False,
                "italic": False,
                "underline": False,
                "strikethrough": False,
                "subscript": False,
                "color": 0,
                "foregroundHex": "",
                "backgroundHex": "Transparent",
                "indentLevel": 0,
                "alignment": "Left",
                "lineSpacing": 1.0,
                "styleName": "正文",
            }
        )
        self.writer.write_page(notebook_id, page_id, document)
        return page, source_pdf_path

    def _editable_page(
        self,
        connection: sqlite3.Connection,
        inner: zipfile.ZipFile,
        image_names: dict[str, str],
        notebook_id: str,
        source: NoteinSource,
        page_row: sqlite3.Row,
        page_index: int,
        title: str,
        pdf_document: fitz.Document | None,
        has_pdf_model: bool,
        source_pdf_path: Path | None,
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        page_id = stable_id("page-notein", f"{source.note_id}:{page_row['id']}")
        try:
            paper = json.loads(page_row["paper_spec"] or "{}")
        except json.JSONDecodeError:
            paper = {}
        width = float(paper.get("preciseWidth") or paper.get("width") or 1240)
        height = float(paper.get("preciseHeight") or paper.get("height") or 1754)
        source_page = pdf_document.load_page(page_index) if pdf_document is not None and page_index < pdf_document.page_count else None
        source_asset = self.writer.link_asset(notebook_id, page_id, source_pdf_path) if source_page is not None and source_pdf_path is not None else None
        appearance = page_appearance(width=width, height=height, paper_kind=2 if has_pdf_model else (0 if page_row["unbounded"] else 1), show_title=False)
        if 'paper_theme' in page_row.keys():
            try:
                theme = json.loads(page_row['paper_theme'] or '{}')
                base = theme.get('baseTheme', theme)
                if 'color' in base: appearance['pageColor'] = source_color_hex(base['color'])
            except (ValueError, TypeError):
                pass
        pdf_metadata = None
        if source_page is not None and source_asset is not None:
            pdf_metadata = {
                "pageNumber": page_index + 1,
                "pageCount": pdf_document.page_count,
                "widthPoints": source_page.rect.width,
                "heightPoints": source_page.rect.height,
                "sourceAssetName": source_asset,
                "sourceDocumentName": source_pdf_path.name,
            }
        page = note_page(
            page_id,
            title,
            created_at=epoch_millis(page_row["creation_time"]),
            updated_at=epoch_millis(page_row["last_modification_time"]),
            appearance=appearance,
            pdf=pdf_metadata,
        )
        document = page_document(notebook_id, page_id)
        if source_page is not None:
            scale = max(2048.0 / max(1.0, source_page.rect.width), width / max(1.0, source_page.rect.width))
            pixmap = source_page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
            background = pixmap.tobytes("png")
            asset = self.writer.write_asset(notebook_id, page_id, "source-page.png", background)
            pdf_background = self._image_object(
                stable_id("object-notein-pdf-background", f"{source.note_id}:{page_row['id']}"),
                asset,
                "PDF 页面背景（原始矢量文件已保留）",
                0,
                0,
                width,
                height,
                is_background=True,
            )
            if source_asset is not None:
                pdf_background["kind"] = 3
                pdf_background["sourceAssetName"] = source_asset
                pdf_background["sourcePageNumber"] = page_index + 1
            document["objects"].append(pdf_background)
            self.stats["rendered-pdf-backgrounds"] += 1
            self.stats["vector-source-pdf-pages"] += 1

        if connection.execute("select 1 from sqlite_master where name='PageLayerEntity'").fetchone():
            layer_record = connection.execute('select * from NoteContentEntity limit 1').fetchone()
            layer_ids = json.loads(layer_record['page_layer_list'] or '[]') if layer_record and 'page_layer_list' in layer_record.keys() else []
            document['layers'] = [
                {'id': row['id'], 'name': row['name'] or '图层', 'visible': bool(row['visible']), 'order': layer_ids.index(row['id']) if row['id'] in layer_ids else len(layer_ids) + index}
                for index,row in enumerate(connection.execute('select * from PageLayerEntity order by rowid'))
            ]
        for row in connection.execute("select * from StrokeEntity where page_id=? order by creation_time,id", (page_row["id"],)):
            try:
                if row["record_json"]:
                    stroke = decode_notein_legacy_stroke(row["record_json"], row["id"], self.stats)
                elif row["ink_stroke_blob"]:
                    stroke = decode_notein_new_stroke(row["ink_stroke_blob"], row["id"], self.stats)
                    self.stats["new-format-strokes"] += 1
                else:
                    self.stats["empty-strokes"] += 1
                    continue
                if stroke["points"]:
                    stroke['layerId'] = row['layer_id'] if 'layer_id' in row.keys() else 'default'
                    stroke['zOrder'] = int(row['creation_time'] or 0)
                    document["strokes"].append(stroke)
            except Exception:
                self.stats["stroke-decode-errors"] += 1

        for row in connection.execute("select * from ShapeEntity where page_id=? order by creation_time,id", (page_row["id"],)):
            try:
                points = json.loads(row["points"] or "[]")
                palette, _, alpha = nearest_palette(row["color"])
                document["strokes"].append(
                    {
                        "id": stable_id("shape-notein", row["id"]),
                        "kind": 0,
                        "color": palette,
                        "colorHex": source_color_hex(row['color']),
                        "layerId": row['layer_id'] if 'layer_id' in row.keys() else 'default',
                        "zOrder": int(row['creation_time'] or 0),
                        "widthMm": max(0.05, float(row["width"] or 1) / UNITS_PER_MM),
                        "pressurePercent": 100,
                        "inkFlowPercent": 100,
                        "opacityPercent": round(alpha * 100 / 255),
                        "brushProfile": "notein-shape-import",
                        "points": [
                            {
                                "x": round(float(item.get("x", 0)), 4),
                                "y": round(float(item.get("y", 0)), 4),
                                "pressure": 1.0,
                                "tiltX": 0.0,
                                "tiltY": 0.0,
                                "elapsedMilliseconds": index * 8,
                            }
                            for index, item in enumerate(points)
                        ],
                    }
                )
                self.stats["shape-strokes"] += 1
            except Exception:
                self.stats["shape-decode-errors"] += 1

        for row in connection.execute("select * from QuoteEntity where page_id=? order by creation_time,id", (page_row["id"],)):
            try:
                rectangles = json.loads(row["rect_list"] or "[]")
                palette, _, alpha = nearest_palette(row["bg_color"] or row["color"])
                for rect_index, rectangle in enumerate(rectangles):
                    left = float(rectangle.get("left", 0))
                    right = float(rectangle.get("right", left))
                    top = float(rectangle.get("top", 0))
                    bottom = float(rectangle.get("bottom", top))
                    if right <= left or bottom <= top:
                        continue
                    document["strokes"].append(
                        {
                            "id": stable_id("quote-notein", f"{row['id']}:{rect_index}"),
                            "kind": 1,
                            "color": palette,
                            "colorHex": source_color_hex(row['bg_color'] or row['color']),
                            "layerId": row['layer_id'] if 'layer_id' in row.keys() else 'default',
                            "zOrder": int(row['creation_time'] or 0),
                            "widthMm": max(0.05, (bottom - top) / UNITS_PER_MM),
                            "pressurePercent": 100,
                            "inkFlowPercent": 100,
                            "opacityPercent": max(20, min(65, round(alpha * 100 / 255))),
                            "brushProfile": "notein-quote-import",
                            "points": [
                                {"x": left, "y": (top + bottom) / 2, "pressure": 1.0, "tiltX": 0.0, "tiltY": 0.0, "elapsedMilliseconds": 0},
                                {"x": right, "y": (top + bottom) / 2, "pressure": 1.0, "tiltX": 0.0, "tiltY": 0.0, "elapsedMilliseconds": 8},
                            ],
                        }
                    )
                    self.stats["quote-highlights"] += 1
            except Exception:
                self.stats["quote-decode-errors"] += 1

        for row in connection.execute("select * from TextBoxEntity where page_id=? order by creation_time,id", (page_row["id"],)):
            plain = strip_markup(row["text"])
            if not plain:
                continue
            palette, _, _ = nearest_palette(row["default_text_color"])
            document["textItems"].append(
                {
                    "id": stable_id("text-notein", row["id"]),
                    "layerId": row['layer_id'] if 'layer_id' in row.keys() else 'default',
                    "zOrder": int(row['creation_time'] or 0),
                    "x": float(row["left"] or 0),
                    "y": float(row["top"] or 0),
                    "width": max(80.0, float(row["box_width"] or (row["right"] or 0) - (row["left"] or 0))),
                    "height": max(36.0, float(row["box_height"] or (row["bottom"] or 0) - (row["top"] or 0))),
                    "text": plain,
                    "fontFamily": "Segoe UI Variable, Microsoft YaHei UI",
                    "fontSize": max(8.0, float(row["text_size"] or 16)),
                    "bold": False,
                    "italic": False,
                    "underline": False,
                    "strikethrough": False,
                    "subscript": False,
                    "color": palette,
                    "foregroundHex": source_color_hex(row['default_text_color']),
                    "backgroundHex": "Transparent",
                    "indentLevel": 0,
                    "alignment": "Left",
                    "lineSpacing": 1.0,
                    "styleName": "正文",
                }
            )
            self.stats["text-boxes"] += 1

        for row in connection.execute("select * from ImageEntity where page_id=? order by creation_time,id", (page_row["id"],)):
            basename = Path(row["uri"] or "").name
            candidates = [basename, f"note_image_{basename}"]
            archive_name = next((image_names[name] for name in candidates if name in image_names), None)
            if archive_name is None:
                archive_name = next((name for key, name in image_names.items() if key.endswith(basename)), None)
            if archive_name is None:
                self.stats["missing-image-assets"] += 1
                continue
            data = inner.read(archive_name)
            asset = self.writer.write_asset(notebook_id, page_id, basename or archive_name, data)
            document["objects"].append(
                self._image_object(
                    stable_id("object-notein-image", row["id"]),
                    asset,
                    basename or "Notein 图片",
                    float(row["left"] or 0),
                    float(row["top"] or 0),
                    max(1.0, float(row["right"] or 0) - float(row["left"] or 0)),
                    max(1.0, float(row["bottom"] or 0) - float(row["top"] or 0)),
                    rotation=round(float(row["rotation"] or 0) / 90) % 4,
                )
            )
            self.stats["images"] += 1
            document['objects'][-1]['layerId'] = row['layer_id'] if 'layer_id' in row.keys() else 'default'
            document['objects'][-1]['zOrder'] = int(row['creation_time'] or 0)
        if not document['layers']:
            document['layers'] = [{'id':'default','name':'内容','visible':True,'order':0}]
        for stroke in document['strokes']:
            if not stroke.get('vectorPath'):
                stroke['vectorPath'] = stroke_outline(stroke['points'], stroke['widthMm'] * UNITS_PER_MM, variable=False)
            cache_vector_bounds(stroke)
            self.stats['vector-strokes'] += 1
        self.stats["editable-pages"] += 1
        self.stats["editable-strokes"] += len(document["strokes"])
        return page, document

    @staticmethod
    def _image_object(
        object_id: str,
        asset: str,
        display_name: str,
        x: float,
        y: float,
        width: float,
        height: float,
        *,
        is_background: bool = False,
        rotation: int = 0,
    ) -> dict[str, Any]:
        return {
            "id": object_id,
            "kind": 2,
            "x": x,
            "y": y,
            "width": width,
            "height": height,
            "displayName": display_name,
            "assetName": asset,
            "mimeType": mimetypes.guess_type(display_name)[0] or "image/png",
            "sizeBytes": 0,
            "viewInverted": False,
            "rows": 0,
            "columns": 0,
            "cells": [],
            "previewRows": [],
            "targetNotebookId": "",
            "targetSectionId": "",
            "targetPageId": "",
            "sourceAssetName": "",
            "sourcePageNumber": None,
            "isPageBackground": is_background,
            "rotationQuarterTurns": rotation,
            "flipHorizontal": False,
            "flipVertical": False,
        }

    def _preview_page(
        self,
        notebook_id: str,
        source: NoteinSource,
        page_row: sqlite3.Row,
        page_index: int,
        title: str,
    ) -> dict[str, Any] | None:
        if self.external is None:
            return None
        key = f"{source.note_id}_{page_row['id']}"
        candidates = [self.external / key, self.external / f"{key}.png"]
        path = next((candidate for candidate in candidates if candidate.is_file()), None)
        if path is None:
            self.stats["missing-page-previews"] += 1
            return None
        data = path.read_bytes()
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            self.stats["invalid-page-previews"] += 1
            return None
        try:
            paper = json.loads(page_row["paper_spec"] or "{}")
        except json.JSONDecodeError:
            paper = {}
        width = float(paper.get("preciseWidth") or paper.get("width") or 1240)
        height = float(paper.get("preciseHeight") or paper.get("height") or 1754)
        page_id = stable_id("page-notein-preview", f"{source.note_id}:{page_row['id']}")
        page = note_page(
            page_id,
            f"{title} · 原貌",
            created_at=epoch_millis(page_row["creation_time"]),
            updated_at=epoch_millis(page_row["last_modification_time"]),
            appearance=page_appearance(width=width, height=height, paper_kind=1, show_title=False),
        )
        document = page_document(notebook_id, page_id)
        asset = self.writer.write_asset(notebook_id, page_id, "notein-preview.png", data)
        document["objects"].append(
            self._image_object(
                stable_id("object-notein-preview", f"{source.note_id}:{page_row['id']}"),
                asset,
                "Notein 原貌预览",
                0,
                0,
                width,
                height,
                is_background=True,
            )
        )
        self.writer.write_page(notebook_id, page_id, document)
        self.stats["preview-pages"] += 1
        return page


class OneNoteImporter:
    def __init__(self, export_root: Path, writer: WorkspaceWriter, include_visual_fallbacks: bool = False):
        self.root = export_root
        self.include_visual_fallbacks = include_visual_fallbacks
        self.pdf_fingerprints: dict[Path, list[tuple[str, int, bytes]]] = {}
        self.writer = writer
        self.stats: Counter[str] = Counter()
        self.errors: list[dict[str, str]] = []

    def import_all(self) -> dict[str, Any]:
        manifest = json.loads((self.root / "manifest.json").read_text(encoding="utf-8"))
        for notebook_source in manifest.get("notebooks", []):
            notebook_id = notebook_source["id"]
            notebook = {
                "id": notebook_id,
                "title": notebook_source.get("title") or "OneNote 导入",
                "sections": [],
                "expanded": True,
                "focusedSectionId": None,
                "selectedPageSectionId": "",
                "selectedPageId": "",
            }
            self.writer.replace_notebook(notebook)
            for section_source in notebook_source.get("sections", []):
                notebook["sections"].append(self._import_section(notebook_id, section_source))
            if notebook["sections"]:
                first = next(
                    (s for s in notebook["sections"] if s.get("pages") and not s.get("id", "").startswith("section-onenote-pdf")),
                    next((s for s in notebook["sections"] if s.get("pages")), notebook["sections"][0]),
                )
                notebook["focusedSectionId"] = first["id"]
                if first["pages"]:
                    notebook["selectedPageSectionId"] = first["id"]
                    notebook["selectedPageId"] = first["pages"][0]["id"]
            self.stats["notebooks"] += 1
        return {"stats": dict(self.stats), "errors": self.errors}

    def _import_section(self, notebook_id: str, source: dict[str, Any]) -> dict[str, Any]:
        section = {
            "id": source["id"],
            "title": source.get("title") or "分区",
            "pages": [],
            "children": [],
            "expanded": True,
        }
        fallback = {
            "id": stable_id("section-onenote-pdf", source["id"]),
            "title": "原貌图片（不可编辑回退）",
            "pages": [],
            "children": [],
            "expanded": False,
        }
        for descriptor in source.get("pages", []):
            try:
                editable, exact = self._import_page(notebook_id, descriptor)
                section["pages"].append(editable)
                if exact:
                    fallback["pages"].append(exact)
                print(f"ONENOTE_IMPORT_PAGE {self.stats['editable-pages']} ok", flush=True)
            except Exception as exc:
                self.errors.append({"page": descriptor.get("id", "unknown"), "error": str(exc)})
        for child in source.get("children", []):
            section["children"].append(self._import_section(notebook_id, child))
        if fallback["pages"]:
            section["children"].append(fallback)
        return section

    def _import_page(
        self, notebook_id: str, descriptor: dict[str, Any]
    ) -> tuple[dict[str, Any], dict[str, Any] | None]:
        page_json = self.root / descriptor["json"]
        source = json.loads(page_json.read_text(encoding="utf-8"))
        page_id = descriptor["id"]
        metadata_path = self.root / 'enrichment' / f'{page_id}.json'
        if metadata_path.is_file():
            metadata = json.loads(metadata_path.read_text(encoding='utf-8'))
            # Position/size matching avoids ordinal drift when COM omits an
            # image's binary data or the exporter rejects a malformed image.
            for item in source.get('objects', []):
                if item.get('kind') != 'Image':
                    continue
                matches = [image for image in metadata.get('images', []) if all(abs(float(item.get(key, 0))-float(image.get(key, 0))) < 0.01 for key in ('x','y','width','height'))]
                if len(matches) == 1:
                    item.update({key:matches[0][key] for key in ('isPrintout','zOrder','sourceObjectId')})
            assets = {item.get('assetName') for item in source.get('objects', [])}
            source.setdefault('objects', []).extend(item for item in metadata.get('objects', []) if item.get('assetName') not in assets)
        created = parse_time(source.get("createdAt") or descriptor.get("createdAt"))
        updated = parse_time(source.get("updatedAt") or descriptor.get("updatedAt"))
        page = note_page(
            page_id,
            descriptor.get("title") or "无标题页",
            created_at=created,
            updated_at=updated,
            appearance=page_appearance(show_title=False),
        )
        document = page_document(notebook_id, page_id)
        document['layers'] = [{'id':'onenote-content','name':'内容','visible':True,'order':0}]
        for stroke_source in source.get("strokes", []):
            if not stroke_source.get('vectorPath'):
                raise ValueError('OneNote export lacks native vector outlines; rerun Export-OneNote.ps1 before importing')
            palette, palette_name, alpha = nearest_palette(stroke_source.get("colorHex"))
            self.stats[f"palette-{palette_name}"] += 1
            document["strokes"].append(
                {
                    "id": stroke_source.get("id") or stable_id("stroke-onenote", f"{page_id}:{len(document['strokes'])}"),
                    "kind": 1 if stroke_source.get("kind") == "Highlighter" else 0,
                    "color": palette,
                    "widthMm": max(0.05, float(stroke_source.get("widthMm") or 0.4)),
                    "pressurePercent": 100,
                    "inkFlowPercent": 100,
                    "opacityPercent": int(stroke_source.get("opacityPercent") or round(alpha * 100 / 255)),
                    "brushProfile": "onenote-isf-import",
                    "colorHex": source_color_hex(stroke_source.get('colorHex')),
                    "vectorPath": stroke_source.get('vectorPath', ''),
                    "vectorBounds": stroke_source.get('vectorBounds'),
                    "layerId": stroke_source.get('layerId', 'onenote-content'),
                    "zOrder": int(stroke_source.get('zOrder', 0)),
                    "sourceBrush": stroke_source.get('sourceDrawingAttributes'),
                    "points": [
                        {
                            "x": float(point.get("x", 0)),
                            "y": float(point.get("y", 0)),
                            "pressure": max(0.01, min(1.0, float(point.get("pressure", 0.5)))),
                            "tiltX": 0.0,
                            "tiltY": 0.0,
                            "elapsedMilliseconds": int(point.get("elapsedMilliseconds", index * 8)),
                        }
                        for index, point in enumerate(stroke_source.get("points", []))
                    ],
                }
            )
        for text_source in source.get("textItems", []):
            document["textItems"].append(
                {
                    "id": text_source.get("id") or stable_id("text-onenote", f"{page_id}:{len(document['textItems'])}"),
                    "layerId": 'onenote-content',
                    "zOrder": int(text_source.get('zOrder', 0)),
                    "x": float(text_source.get("x", 0)),
                    "y": float(text_source.get("y", 0)),
                    "width": max(80.0, float(text_source.get("width", 320))),
                    "height": max(36.0, float(text_source.get("height", 60))),
                    "text": str(text_source.get("text") or ""),
                    "fontFamily": "Segoe UI Variable, Microsoft YaHei UI",
                    "fontSize": max(8.0, float(text_source.get("fontSize", 16))),
                    "bold": False,
                    "italic": False,
                    "underline": False,
                    "strikethrough": False,
                    "subscript": False,
                    "color": 0,
                    "foregroundHex": "",
                    "backgroundHex": "Transparent",
                    "indentLevel": 0,
                    "alignment": "Left",
                    "lineSpacing": 1.0,
                    "styleName": "正文",
                }
            )
        page_assets = self.root / "assets" / page_id
        candidates = []
        for attachment in source.get('objects', []):
            if attachment.get('kind') != 'Attachment' or attachment.get('mimeType') != 'application/pdf':
                continue
            path = page_assets / attachment['assetName']
            if path not in self.pdf_fingerprints:
                try:
                    with fitz.open(path) as pdf:
                        self.pdf_fingerprints[path] = [(attachment['assetName'],index+1,page_fingerprint(pdf[index])) for index in range(pdf.page_count)]
                except Exception:
                    self.stats['unreadable-original-pdfs'] += 1
                    self.pdf_fingerprints[path] = []
            candidates.extend(self.pdf_fingerprints[path])
        for object_source in source.get("objects", []):
            source_asset = page_assets / object_source["assetName"]
            data = source_asset.read_bytes()
            asset = self.writer.write_asset(notebook_id, page_id, object_source["assetName"], data)
            kind = 1 if object_source.get("kind") == "Attachment" else 2
            pdf_match = None
            if kind == 2 and object_source.get('isPrintout') and candidates:
                pdf_match = match_printout(data, candidates)
            original_pdf_asset = ''
            if pdf_match:
                original_pdf_asset = self.writer.write_asset(notebook_id,page_id,pdf_match[0],(page_assets/pdf_match[0]).read_bytes())
                kind = 3
                self.stats['vector-pdf-printouts'] += 1
            document["objects"].append(
                {
                    "id": object_source.get("id") or stable_id("object-onenote", f"{page_id}:{len(document['objects'])}"),
                    "layerId": 'onenote-content',
                    "zOrder": int(object_source.get('zOrder', -1)),
                    "kind": kind,
                    "x": float(object_source.get("x", 0)),
                    "y": float(object_source.get("y", 0)),
                    "width": max(1.0, float(object_source.get("width", 240))),
                    "height": max(1.0, float(object_source.get("height", 120))),
                    "displayName": object_source.get("displayName") or object_source["assetName"],
                    "assetName": asset,
                    "mimeType": object_source.get("mimeType") or "application/octet-stream",
                    "sizeBytes": len(data),
                    "viewInverted": False,
                    "rows": 0,
                    "columns": 0,
                    "cells": [],
                    "previewRows": [],
                    "targetNotebookId": "",
                    "targetSectionId": "",
                    "targetPageId": "",
                    "sourceAssetName": original_pdf_asset,
                    "sourcePageNumber": pdf_match[1] if pdf_match else None,
                    "isPageBackground": False,
                    "rotationQuarterTurns": 0,
                    "flipHorizontal": False,
                    "flipVertical": False,
                }
            )
        for stroke in document['strokes']: cache_vector_bounds(stroke)
        self.writer.write_page(notebook_id, page_id, document)
        self.stats["editable-pages"] += 1
        self.stats["editable-strokes"] += len(document["strokes"])
        self.stats["text-items"] += len(document["textItems"])
        self.stats['vector-strokes'] += len(document['strokes'])
        self.stats["objects"] += len(document["objects"])

        if not self.include_visual_fallbacks:
            return page, None
        pdf_path = self.root / descriptor["pdf"]
        if not pdf_path.is_file():
            self.stats["missing-pdf-fallbacks"] += 1
            return page, None
        pdf_bytes = pdf_path.read_bytes()
        pdf_document = fitz.open(stream=pdf_bytes, filetype="pdf")
        try:
            if pdf_document.page_count < 1:
                return page, None
            source_page = pdf_document.load_page(0)
            width_points, height_points = source_page.rect.width, source_page.rect.height
            width, height = width_points * 96 / 72, height_points * 96 / 72
            scale = max(1.0, 2048.0 / max(1.0, source_page.rect.width))
            pixmap = source_page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
            rendered = pixmap.tobytes("png")
        finally:
            pdf_document.close()
        fallback_id = stable_id("page-onenote-pdf", page_id)
        pdf_asset = self.writer.write_asset(notebook_id, fallback_id, "source.pdf", pdf_bytes)
        image_asset = self.writer.write_asset(notebook_id, fallback_id, "source-page.png", rendered)
        fallback_page = note_page(
            fallback_id,
            f"{descriptor.get('title') or '无标题页'} · 原貌图片回退",
            created_at=created,
            updated_at=updated,
            appearance=page_appearance(width=width, height=height, paper_kind=2, show_title=False),
            pdf={
                "pageNumber": 1,
                "pageCount": 1,
                "widthPoints": width_points,
                "heightPoints": height_points,
                "sourceAssetName": pdf_asset,
                "sourceDocumentName": f"{descriptor.get('title') or 'OneNote 页面'}.pdf",
            },
        )
        fallback_document = page_document(notebook_id, fallback_id)
        background = NoteinImporter._image_object(
            stable_id("object-onenote-pdf", page_id),
            image_asset,
            "OneNote 原貌图片（原始矢量 PDF 已保留）",
            0,
            0,
            width,
            height,
            is_background=True,
        )
        background["kind"] = 3
        background["sourceAssetName"] = pdf_asset
        background["sourcePageNumber"] = 1
        fallback_document["objects"].append(background)
        self.writer.write_page(notebook_id, fallback_id, fallback_document)
        self.stats["pdf-fallback-pages"] += 1
        return page, fallback_page


def iter_sections(sections: Iterable[dict[str, Any]]) -> Iterable[dict[str, Any]]:
    for section in sections:
        yield section
        yield from iter_sections(section.get("children", []))


def validate_workspace(root: Path) -> dict[str, Any]:
    library = json.loads((root / "library.json").read_text(encoding="utf-8"))
    errors: list[str] = []
    notebook_ids: set[str] = set()
    page_ids: set[str] = set()
    pages = 0
    assets = 0
    for notebook in library.get("notebooks", []):
        notebook_id = notebook.get("id", "")
        if not notebook_id or notebook_id in notebook_ids:
            errors.append(f"duplicate-or-empty-notebook:{notebook_id}")
        notebook_ids.add(notebook_id)
        for section in iter_sections(notebook.get("sections", [])):
            for page in section.get("pages", []):
                page_id = page.get("id", "")
                if not page_id or page_id in page_ids:
                    errors.append(f"duplicate-or-empty-page:{page_id}")
                page_ids.add(page_id)
                pages += 1
                document_path = root / "notebooks" / safe_name(notebook_id) / "pages" / f"{safe_name(page_id)}.json"
                if not document_path.is_file():
                    errors.append(f"missing-page-document:{notebook_id}:{page_id}")
                    continue
                try:
                    document = json.loads(document_path.read_text(encoding="utf-8"))
                except Exception:
                    errors.append(f"invalid-page-document:{notebook_id}:{page_id}")
                    continue
                for item in document.get("objects", []):
                    for key in ("assetName", "sourceAssetName"):
                        name = item.get(key)
                        if not name:
                            continue
                        path = root / "notebooks" / safe_name(notebook_id) / "assets" / safe_name(page_id) / safe_name(name)
                        if not path.is_file():
                            errors.append(f"missing-asset:{notebook_id}:{page_id}:{name}")
                        else:
                            assets += 1
    return {
        "notebooks": len(notebook_ids),
        "pages": pages,
        "assetsReferenced": assets,
        "errors": errors,
        "ok": not errors,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Migrate Notein and OneNote exports into a Mojian workspace")
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--onenote-export", type=Path)
    parser.add_argument("--notein-backup", type=Path)
    parser.add_argument("--notein-external", type=Path)
    parser.add_argument("--work-directory", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument('--include-visual-fallbacks', action='store_true', help='Add explicitly non-editable image copies as child sections')
    parser.add_argument('--skip-missing-notein-pdfs', action='store_true', help='Omit Notein PDF notes lacking their original PDF, including annotation and placeholder pages')
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.onenote_export and not args.notein_backup:
        raise SystemExit("At least one source is required")
    work = args.work_directory or args.workspace.parent / "migration-work"
    work.mkdir(parents=True, exist_ok=True)
    writer = WorkspaceWriter(args.workspace)
    report: dict[str, Any] = {
        "version": 1,
        "createdAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "workspace": str(args.workspace.resolve()),
    }
    if args.onenote_export:
        report["onenote"] = OneNoteImporter(args.onenote_export.resolve(), writer, args.include_visual_fallbacks).import_all()
    if args.notein_backup:
        report["notein"] = NoteinImporter(
            args.notein_backup.resolve(),
            args.notein_external.resolve() if args.notein_external and args.include_visual_fallbacks else None,
            writer,
            work.resolve(),
            skip_missing_pdfs=args.skip_missing_notein_pdfs,
        ).import_all()
    writer.finish()
    report["validation"] = validate_workspace(args.workspace.resolve())
    atomic_json(args.report.resolve(), report)
    print(
        f"MIGRATION_DONE notebooks={report['validation']['notebooks']} "
        f"pages={report['validation']['pages']} errors={len(report['validation']['errors'])}",
        flush=True,
    )
    source_errors = any(source.get('errors') or any(value for key,value in source.get('stats', {}).items() if key.endswith('decode-errors')) for name,source in report.items() if name in ('onenote','notein'))
    return 0 if report["validation"]["ok"] and not source_errors else 2


if __name__ == "__main__":
    raise SystemExit(main())
