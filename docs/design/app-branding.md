# Application branding

The 2026-10-30 supplied `Mojian_*` feather artwork is the source of truth in
`shared/branding`. Keep the original SVG, PNG, ICO and ICNS files intact. The other
application's assets from the supplied archive are outside this repository's scope.
There is no native macOS target; the ICNS remains source artwork for a future target.

`tools/build_icons.py` packages the artwork using Pillow. It does not draw a logo:

- Windows copies the supplied Windows ICO and 256 px PNG byte for byte into the
  existing `mojian/src/Notein-win/Assets` paths. `ApplicationIcon` embeds the ICO in
  the EXE; `MainWindow.Icon` uses the PNG for window/taskbar branding.
- Android keeps `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`, the existing
  package name and signing identity. The supplied transparent Windows feather fits
  inside the central 66 dp safe circle of a 108 dp adaptive foreground. Both adaptive
  resources use the supplied Apple artwork's `#1C2453` background. Five foreground
  densities encode 108 dp correctly. Five legacy density pairs use the same foreground's
  central 72 dp viewport, with rounded-square and circular masks respectively. The Android
  OS still owns the adaptive outer mask.
- iOS/iPadOS keeps the universal 1024 px `AppIcon` catalog. The supplied Apple PNG is
  composited onto its own `#1C2453` background to make a fully opaque RGB square;
  iOS owns the outer rounded mask. No feather path, proportion, or color is redrawn.

Regenerate and verify from the repository root:

```powershell
uv run --no-project --with pillow python tools/build_icons.py
uv run --no-project --with pillow python tools/build_icons.py --check
```

After a change, publish the Windows EXE and build the Android APK; inspect their
embedded icon resources. On the Mac, build the existing iOS simulator and unsigned
device targets to verify asset catalog compilation. Record source/build checks
separately from Windows Explorer/taskbar caches or installed-device launcher checks.
Preserve running applications, shortcuts, user preferences, and note libraries.
