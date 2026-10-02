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
    decode_notein_legacy_stroke,
    source_color_hex,
    stroke_outline,
    OneNoteImporter,
    page_document,
    page_fingerprint,
    match_printout,
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
    def test_android_colorlong_uses_high_argb_word(self) -> None:
        self.assertEqual('#FFE02020', source_color_hex(0xFFE0202000000000))
        self.assertEqual('#FFFFFFFF', source_color_hex(0xFFFFFFFF00000000))
        self.assertEqual('#FF000000', source_color_hex(-16777216))

    def test_legacy_ink_preserves_custom_color_and_vector_outline(self) -> None:
        result = decode_notein_legacy_stroke(json.dumps({'width': 4, 'color': '#FF44AA66', 'points': [{'x':10,'y':20,'p':0.25},{'x':30,'y':40,'p':0.75}]}), 'sample', Counter())
        self.assertEqual('#FF44AA66', result['colorHex'])
        self.assertTrue(result['vectorPath'].startswith('M '))
        self.assertTrue(result['vectorPath'].endswith(' Z'))
        self.assertNotIn('assetName', result)

    def test_stationary_dot_keeps_the_largest_pressure_in_vector_outline(self) -> None:
        path=stroke_outline([{'x':10,'y':20,'pressure':0.1},{'x':10,'y':20,'pressure':1}],4)
        self.assertIn('A 2.32,2.32', path)
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
    def test_metadata_sidecar_recovers_pdf_attachment_and_printout_without_flattening_ink(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); export=root/'source'; (export/'pages').mkdir(parents=True)
            assets=export/'assets/p'; assets.mkdir(parents=True); (export/'enrichment').mkdir()
            with fitz.open() as pdf:
                page=pdf.new_page(width=300,height=400); page.insert_text((20,60),'original',fontsize=24)
                (assets/'original.pdf').write_bytes(pdf.tobytes())
                (assets/'printout.png').write_bytes(page.get_pixmap().tobytes('png'))
            image={'id':'image','kind':'Image','assetName':'printout.png','x':10,'y':20,'width':300,'height':400}
            ink={'id':'ink','colorHex':'#FF55BB77','vectorPath':'M 10,20 L 30,20 30,30 Z','points':[{'x':10,'y':20},{'x':30,'y':20}]}
            (export/'pages/p.json').write_text(json.dumps({'strokes':[ink],'objects':[image]}))
            (export/'enrichment/p.json').write_text(json.dumps({'images':[{**image,'isPrintout':True,'zOrder':-1,'sourceObjectId':'source-image'}],
                'objects':[{'id':'file','kind':'Attachment','assetName':'original.pdf','mimeType':'application/pdf'}]}))
            writer=WorkspaceWriter(root/'staging'); writer.replace_notebook({'id':'n','sections':[]})
            OneNoteImporter(export,writer)._import_page('n',{'id':'p','json':'pages/p.json','title':'Native'})
            doc=json.loads((root/'staging/notebooks/n/pages/p.json').read_text(encoding='utf-8'))
            self.assertEqual(2,len(doc['objects']))
            self.assertEqual(3,doc['objects'][0]['kind'])
            self.assertEqual(1,doc['objects'][0]['sourcePageNumber'])
            self.assertEqual(-1,doc['objects'][0]['zOrder'])
            self.assertTrue(doc['objects'][0]['sourceAssetName'])
            self.assertEqual(ink['vectorPath'],doc['strokes'][0]['vectorPath'])

    def test_pdf_printout_matches_original_page_and_rejects_ambiguous_sources(self) -> None:
        with fitz.open() as pdf:
            page = pdf.new_page(width=300, height=400)
            page.insert_text((20, 60), "original vector source", fontsize=20)
            preview = page.get_pixmap(matrix=fitz.Matrix(2, 2)).tobytes('png')
            candidates = [('original.pdf', 1, page_fingerprint(page))]
            self.assertEqual(('original.pdf', 1), match_printout(preview, candidates))
            self.assertIsNone(match_printout(preview, candidates + [('duplicate.pdf', 1, candidates[0][2])]))
            other = pdf.new_page(width=300, height=400)
            other.draw_rect(other.rect, color=(0, 0, 0), fill=(0, 0, 0))
            self.assertIsNone(match_printout(preview, [('different.pdf', 1, page_fingerprint(other))]))

    def test_onenote_primary_import_contains_native_vectors_without_visual_child(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); export=root/'source'; export.mkdir(); (export/'pages').mkdir()
            descriptor={'id':'p','json':'pages/p.json','pdf':'pdf/p.pdf','title':'Sample'}
            (export/'pages/p.json').write_text(json.dumps({'strokes':[{'id':'s','colorHex':'#FF55BB77','vectorPath':'M 10,20 L 30,20 30,30 Z','points':[{'x':10,'y':20},{'x':30,'y':20}]}]}))
            writer=WorkspaceWriter(root/'staging'); writer.replace_notebook({'id':'n','sections':[]})
            importer=OneNoteImporter(export,writer)
            section=importer._import_section('n',{'id':'section','pages':[descriptor]})
            doc=json.loads((root/'staging/notebooks/n/pages/p.json').read_text(encoding='utf-8'))
            self.assertEqual([],section['children'])
            self.assertEqual([],doc['objects'])
            self.assertEqual('#FF55BB77',doc['strokes'][0]['colorHex'])
            self.assertTrue(doc['strokes'][0]['vectorPath'])
            self.assertEqual('onenote-content',doc['strokes'][0]['layerId'])
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
                create table NoteContentEntity(page_list text, page_layer_list text);
                create table PageLayerEntity(id text, name text, visible integer);
                create table PageEntity(
                    id text, paper_spec text, unbounded integer,
                    creation_time integer, last_modification_time integer
                );
                create table PdfInfoEntity(id text);
                create table StrokeEntity(
                    id text, page_id text, creation_time integer,
                    record_json text, ink_stroke_blob blob, layer_id text
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
            connection.execute("insert into NoteContentEntity values (?,?)", (json.dumps([page_id]), json.dumps(['lower', 'upper'])))
            connection.execute("insert into PageLayerEntity values (?,?,?)", ('upper', 'Cover', 0))
            connection.execute("insert into PageLayerEntity values (?,?,?)", ('lower', 'Ink', 1))
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
                "insert into StrokeEntity values (?,?,?,?,?,?)",
                ("stroke-row", page_id, 1, json.dumps(legacy), None, 'lower'),
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
            self.assertEqual('lower', editable_document['strokes'][0]['layerId'])
            layers = {layer['id']: layer for layer in editable_document['layers']}
            self.assertEqual(0, layers['lower']['order'])
            self.assertEqual(1, layers['upper']['order'])
            self.assertFalse(layers['upper']['visible'])
            without_pdf=io.BytesIO()
            with zipfile.ZipFile(inner_buffer) as original, zipfile.ZipFile(without_pdf,'w') as filtered:
                for name in original.namelist():
                    if not name.endswith('.pdf'):filtered.writestr(name,original.read(name))
            missing_backup=root/'missing-pdf-backup.zip'
            with zipfile.ZipFile(missing_backup,'w') as outer:
                outer.writestr('n_note-source/Synthetic.in',without_pdf.getvalue())
            filtered_writer=WorkspaceWriter(root/'filtered-workspace')
            filtered_report=NoteinImporter(missing_backup,None,filtered_writer,root/'filtered-work',skip_missing_pdfs=True).import_all()
            filtered_writer.finish()
            self.assertFalse(filtered_report['errors'])
            self.assertEqual(1,filtered_report['stats']['skipped-missing-pdf-notes'])
            self.assertEqual(1,filtered_report['stats']['skipped-missing-pdf-content-pages'])
            filtered_library=json.loads((root/'filtered-workspace/library.json').read_text('utf-8'))
            self.assertEqual([],filtered_library['notebooks'][0]['sections'])


if __name__ == "__main__":
    unittest.main()
