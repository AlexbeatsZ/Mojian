# Mojian data migration tools

These tools migrate user-owned Notein and OneNote data into the Windows Mojian
workspace without modifying either source application.

The workflow has three stages:

1. `Export-OneNote.ps1` uses OneNote's supported desktop COM API to export a
   version-2 neutral JSON representation. `OneNoteInkDecoder.cs` preserves each
   ISF stroke's filled outline, pressure, exact color and drawing attributes.
   `-SkipPdf` skips optional flattened visual copies; original embedded PDFs
   are recovered independently in the next step.
2. `Enrich-OneNoteExport.ps1` recovers original embedded files from the
   supported COM metadata's `InsertedFile.pathCache`, including attachments
   without inline `Data` or a direct `Position`. It records printout identity
   and source stacking order.
3. `migrate_to_mojian.py` reads that neutral export and/or a Notein local
   `backup.zip`, writes a *staging* Mojian workspace, and validates every
   referenced page and asset before the staging workspace replaces live data.

For Notein, the official local backup is treated as read-only. Legacy JSON ink
and current protobuf-like ink are converted to Mojian strokes. The converter
preserves original colors and Notein layer IDs, order and visibility. Its
editable filled contours are reconstructed from source inputs; exact Notein
brush appearance remains unverified. The converter also accepts the readable
Android external-files snapshot when `--include-visual-fallbacks` is requested.

Editable content is the default page list. Visual copies require
`--include-visual-fallbacks` and live in separate child sections. Source-PDF backup pages are placed
after editable annotation pages. PDF-backed editable pages retain the original
vector PDF and page number. Mojian may show a PNG immediately, then rerenders
from that original PDF at the current zoom instead of enlarging a fixed 144 DPI
preview.

OneNote printout previews are matched to pages of original embedded PDFs.
Ambiguous matches retain their original image; the converter never guesses
a source-page relationship. Original screenshots/scans remain independent
image objects, not reconstructed handwriting. This import route currently
targets the Windows native scene; Android does not yet render these imports.

The converter intentionally does not expand every unannotated page of a large
source PDF into the page list. It creates native pages for annotated pages and
retains their required vector PDF backgrounds, without standalone original-PDF
backup attachments or migration-summary pages. Unannotated source PDFs are not archived.

## Commands

Run OneNote export from Windows PowerShell 5.1 because the Office COM type
library is not reliable through PowerShell 7:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Export-OneNote.ps1 `
  -OutputDirectory C:\Users\Meta\AppData\Local\Temp\.agents\notein-data-migration\onenote -SkipPdf
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Enrich-OneNoteExport.ps1 `
  -OutputDirectory C:\Users\Meta\AppData\Local\Temp\.agents\notein-data-migration\onenote
```

Build a staging workspace with `uv`:

```powershell
uv run --project . migrate_to_mojian.py `
  --workspace C:\Users\Meta\AppData\Local\Temp\.agents\notein-data-migration\workspace-staging `
  --onenote-export C:\Users\Meta\AppData\Local\Temp\.agents\notein-data-migration\onenote `
  --notein-backup C:\Users\Meta\AppData\Local\Temp\.agents\notein-data-migration\notein-backup.zip `
  --notein-external C:\Users\Meta\AppData\Local\Temp\.agents\notein-data-migration\notein-external\files `
  --report C:\Users\Meta\AppData\Local\Temp\.agents\notein-data-migration\migration-report.json
```

The destination may already contain a Mojian library. Existing notebooks are
kept; only notebooks with the same stable migration IDs are replaced on a
rerun. Always stage first, validate, then switch/copy into the live workspace.
Do not reuse version-1 neutral exports: they lack authoritative vector outlines.

Use `--skip-missing-notein-pdfs` for this user's current workspace. Their
2026-10-02 instruction removes entire imported pages belonging to PDF notes
whose original PDF is unavailable, including annotations and placeholder pages.
The read-only backup is retained for recovery.
