# Application branding

Since 2026-11-16 the brand mark is a two-tone blue pen nib drawn in the Google Pixel
2026 gradient style (one dominant colour, soft gradient, white background). It replaces
the 2026-10-30 feather artwork. The sources live in `shared/branding`:

- `Mojian_Windows.svg` / `.ico` / `_256.png` / `_1024.png`: the nib alone on a
  transparent background, framed tightly for Windows.
- `Mojian_macOS.svg` / `.icns` / `_256.png` / `_1024.png`: the nib on a white rounded
  tile following Apple's 1024 px template (824 px tile, soft shadow).
- `Mojian_Android_1024.png`: the 72 dp visible viewport of the Pixel launcher design
  (nib on transparent, positioned for a white background).
- `android/app/src/main/res/drawable/ic_launcher_monochrome.xml`: the themed-icon
  (Android 13+) monochrome layer, referenced by both adaptive launcher XMLs.

There is no native macOS target on this branch; the ICNS remains source artwork for it.

`tools/build_icons.py` packages the artwork using Pillow. It does not draw a logo:

- Windows copies the Windows ICO and 256 px PNG byte for byte into the existing
  `mojian/src/Notein-win/Assets` paths. `ApplicationIcon` embeds the ICO in the EXE;
  `MainWindow.Icon` uses the PNG for window/taskbar branding.
- Android keeps `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`, the existing
  package name and signing identity. `Mojian_Android_1024.png` is scaled to the 72 dp
  viewport and centred in the 108 dp adaptive foreground (five densities); the nib stays
  inside the 66 dp safe circle. The adaptive background is white (`#FFFFFF`). Five legacy
  density pairs use the same viewport on white, with rounded-square and circular masks.
  The Android OS still owns the adaptive outer mask.
- iOS/iPadOS keeps the universal 1024 px `AppIcon` catalog: the Android viewport artwork
  on an opaque white square; iOS owns the outer rounded mask.

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
