from __future__ import annotations

import io
import json
import sqlite3
import struct
import sys
import tempfile
import unittest
import zipfile
from collections import Counter
from pathlib import Path

import pymupdf as fitz

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from migrate_to_mojian import (  # noqa: E402
    NoteinImporter,
    WorkspaceWriter,
    decode_notein_new_stroke,
    page_document,
    validate_workspace,
)


def varint(value: int) -> bytes:
    result = bytearray()
    while value >= 0x80:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


def field(number: int, wire: int, value: bytes | int) -> bytes:
    result = bytearray(varint((number << 3) | wire))
    if wire == 0:
        result.extend(varint(int(value)))
    elif wire == 2:
        data = bytes(value)
        result.extend(varint(len(data)))
        result.extend(data)
    elif wire == 5:
        result.extend(bytes(value))
    else:
        raise ValueError(wire)
    return bytes(result)


class ProtobufStrokeTests(unittest.TestCase):
    def test_new_notein_stroke_applies_both_matrices_and_pressure(self) -> None:
        identity = struct.pack("<9f", 1, 0, 0, 0, 1, 0, 0, 0, 1)
        translate = struct.pack("<9f", 1, 0, -5, 0, 1, 7, 0, 0, 1)
        blob = b"".join(
            [
                field(1, 2, b"stroke-source-id"),
                field(4, 5, struct.pack("<f", 3.78)),
                field(5, 0, 0xFF185ABD),
                field(10, 2, struct.pack("<4f", 10, 20, 14, 24)),
                field(11, 2, struct.pack("<10f", 0, 0.25, 0, 0, 1, 8, 0.75, 0, 0, 1)),
                field(12, 2, identity),
                field(13, 2, translate),
            ]
        )
        result = decode_notein_new_stroke(blob, "fallback", Counter())
        self.assertEqual(1, result["color"])
        self.assertAlmostEqual(1.0, result["widthMm"], places=2)
        self.assertEqual((5.0, 27.0), (result["points"][0]["x"], result["points"][0]["y"]))
        self.assertEqual((9.0, 31.0), (result["points"][1]["x"], result["points"][1]["y"]))
        self.assertEqual(0.25, result["points"][0]["pressure"])
        self.assertEqual(8, result["points"][1]["elapsedMilliseconds"])


class WorkspaceTests(unittest.TestCase):
    def test_writer_preserves_existing_notebook_and_validation_checks_assets(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            existing = {
                "version": 2,
                "notebooks": [{"id": "existing", "title": "Existing", "sections": []}],
                "activeNotebookId": "existing",
            }
            (root / "library.json").write_text(json.dumps(existing), encoding="utf-8")
            writer = WorkspaceWriter(root)
            page_id = "page-imported"
            notebook = {
                "id": "notebook-imported",
                "title": "Imported",
                "sections": [
                    {
                        "id": "section-imported",
                        "title": "Section",
                        "pages": [{"id": page_id, "title": "Page"}],
                        "children": [],
                    }
                ],
            }
            writer.replace_notebook(notebook)
            document = page_document(notebook["id"], page_id)
            asset = writer.write_asset(notebook["id"], page_id, "image.png", b"png-data")
            document["objects"].append({"assetName": asset, "sourceAssetName": ""})
            writer.write_page(notebook["id"], page_id, document)
            writer.finish()
            validation = validate_workspace(root)
            self.assertTrue(validation["ok"], validation["errors"])
            library = json.loads((root / "library.json").read_text(encoding="utf-8"))
            self.assertEqual({"existing", "notebook-imported"}, {item["id"] for item in library["notebooks"]})

    def test_synthetic_notein_backup_imports_editable_stroke_and_preview(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            database_path = root / "source.db"
            connection = sqlite3.connect(database_path)
            connection.executescript(
                """
                create table NoteContentEntity(page_list text);
                create table PageEntity(
                    id text, paper_spec text, unbounded integer,
                    creation_time integer, last_modification_time integer
                );
                create table PdfInfoEntity(id text);
                create table StrokeEntity(
                    id text, page_id text, creation_time integer,
                    record_json text, ink_stroke_blob blob
                );
                create table ShapeEntity(
                    id text, page_id text, creation_time integer, points text,
                    color integer, width real
                );
                create table QuoteEntity(
                    id text, page_id text, creation_time integer, rect_list text,
                    bg_color integer, color integer
                );
                create table TextBoxEntity(
                    id text, page_id text, creation_time integer, text text,
                    default_text_color integer, left real, top real, right real,
                    bottom real, box_width real, box_height real, text_size real
                );
                create table ImageEntity(
                    id text, page_id text, creation_time integer, uri text,
                    left real, top real, right real, bottom real, rotation real
                );
                """
            )
            page_id = "page-source"
            connection.execute("insert into NoteContentEntity values (?)", (json.dumps([page_id]),))
            connection.execute(
                "insert into PageEntity values (?,?,?,?,?)",
                (
                    page_id,
                    json.dumps({"preciseWidth": 1240, "preciseHeight": 1754}),
                    0,
                    1_700_000_000_000,
                    1_700_000_001_000,
                ),
            )
            legacy = {
                "id": "legacy-source",
                "type": 0,
                "color": -16777216,
                "width": 3.78,
                "points": [{"x": 10, "y": 20, "p": 0.5}, {"x": 30, "y": 40, "p": 0.8}],
            }
            connection.execute(
                "insert into StrokeEntity values (?,?,?,?,?)",
                ("stroke-row", page_id, 1, json.dumps(legacy), None),
            )
            connection.execute("insert into PdfInfoEntity values (?)", ("pdf-source",))
            connection.commit()
            connection.close()

            note_id = "note-source"
            source_pdf = fitz.open()
            source_pdf_page = source_pdf.new_page(width=595, height=842)
            source_pdf_page.insert_text((72, 72), "vector source")
            source_pdf_bytes = source_pdf.tobytes()
            source_pdf.close()
            inner_buffer = io.BytesIO()
            with zipfile.ZipFile(inner_buffer, "w") as inner:
                inner.writestr("note_database_source_db", database_path.read_bytes())
                inner.writestr("source.pdf", source_pdf_bytes)
                inner.writestr(
                    "note_meta.json",
                    json.dumps(
                        {
                            "id": note_id,
                            "title": "Synthetic",
                            "creationTime": 1_700_000_000_000,
                            "lastModificationTime": 1_700_000_001_000,
                        }
                    ),
                )
            backup = root / "backup.zip"
            with zipfile.ZipFile(backup, "w") as outer:
                outer.writestr("n_note-source/Synthetic.in", inner_buffer.getvalue())
            external = root / "external"
            external.mkdir()
            (external / f"{note_id}_{page_id}").write_bytes(b"\x89PNG\r\n\x1a\nsynthetic")

            workspace = root / "workspace"
            writer = WorkspaceWriter(workspace)
            report = NoteinImporter(backup, external, writer, root / "work").import_all()
            writer.finish()
            validation = validate_workspace(workspace)
            self.assertFalse(report["errors"])
            self.assertTrue(validation["ok"], validation["errors"])
            self.assertEqual(1, report["stats"]["editable-strokes"])
            self.assertEqual(1, report["stats"]["preview-pages"])
            library = json.loads((workspace / "library.json").read_text(encoding="utf-8"))
            section = library["notebooks"][0]["sections"][0]
            self.assertIn("可编辑批注", section["pages"][0]["title"])
            self.assertEqual("原始 PDF 备份与迁移说明", section["pages"][-1]["title"])
            self.assertEqual(section["pages"][0]["id"], library["notebooks"][0]["selectedPageId"])
            self.assertTrue(section["pages"][0]["isPdfPage"])
            editable_document = json.loads(
                (workspace / "notebooks" / "notebook-notein-import" / "pages" / f"{section['pages'][0]['id']}.json").read_text(encoding="utf-8")
            )
            self.assertEqual(3, editable_document["objects"][0]["kind"])
            self.assertTrue(editable_document["objects"][0]["sourceAssetName"])


if __name__ == "__main__":
    unittest.main()
