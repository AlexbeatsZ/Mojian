# Mojian data migration tools

These tools migrate user-owned Notein and OneNote data into the Windows Mojian
workspace without modifying either source application.

The workflow deliberately has two stages:

1. `Export-OneNote.ps1` uses OneNote's supported desktop COM API to export a
   neutral JSON representation plus one exact PDF per page. Standard OneNote
   ISF ink is decoded with WPF's `StrokeCollection`, so ink points remain
   editable.
2. `migrate_to_mojian.py` reads that neutral export and/or a Notein local
   `backup.zip`, writes a *staging* Mojian workspace, and validates every
   referenced page and asset before the staging workspace replaces live data.

For Notein, the official local backup is treated as read-only. Legacy JSON ink
and current protobuf-like ink are converted to Mojian strokes. The converter
also accepts the readable Android external-files snapshot; matching PNG page
previews are imported into a separate `原貌图片` child section so visual
fallbacks do not duplicate editable strokes on the same canvas.

Editable content is always the primary page list. OneNote visual copies live
only under `原貌图片（不可编辑回退）`; Notein source-PDF backup pages are placed
after editable annotation pages. PDF-backed editable pages retain the original
vector PDF and page number. Mojian may show a PNG immediately, then rerenders
from that original PDF at the current zoom instead of enlarging a fixed 144 DPI
preview.

The converter intentionally does not expand every unannotated page of a large
source PDF into the page list. It embeds the complete original PDF once and
creates native pages for annotated pages. This preserves the source while
keeping a notebook with thousands of PDF pages usable.

## Commands

Run OneNote export from Windows PowerShell 5.1 because the Office COM type
library is not reliable through PowerShell 7:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Export-OneNote.ps1 `
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
