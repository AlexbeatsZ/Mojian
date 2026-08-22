# 墨笺 Android Workspace Prototype

`墨笺` is a clean-room tablet workspace for studying Notein-like handwriting and the navigation
model of a future independent notebook application. It does not read or modify Notein data.

## Current workspace

- desktop-OneNote-inspired dark chrome with a compact header and a persistent navigation rail;
- a black pane containing multiple collapsible notebooks and their recursively nestable sections;
- a gray page pane switchable between direct pages (`本层`) and recursive descendant sections plus
  their pages (`递归`);
- compact, centered rail buttons for hiding the black section pane or the gray page pane, with a
  selected frame while the corresponding pane is expanded;
- one section `+` in the black-pane header: it adds beneath the focused section, or at notebook root
  after blank-space focus clearing;
- per-tool flyouts and add/delete for black, blue, and red fountain pens, highlighter, whole-stroke
  eraser, and freeform lasso selection;
- an icon-only drawing ribbon where the first tap selects a tool and a second tap opens its options;
- a cursor tool that places a session-only text caret on the paper and opens the Android keyboard;
- a clean writing canvas without duplicate page-title or current-tool status overlays;
- canvas-right search and full-page controls; full-page view uses a centered floating toolbox whose
  visibility is controlled by its pen button;
- a panorama-hand toggle for one-finger panning; off restores the default two-finger-only gestures;
- a File/Backstage notebook information page with a bottom Options entry; prediction and page-pane
  range live in that settings panel instead of the workspace;
- preserved motion prediction, two-finger pan/zoom, stationary pressure erasing, undo, and clear.
- a working Insert ribbon with editable tables, embedded attachments, PDF-as-pages, first-sheet
  spreadsheet previews, batch images, reusable stickers, and note-only links;
- independent image/PDF-printout view-filter preferences calibrated so source white maps to the
  `#1b1b1b` canvas, while original assets and exports remain unfiltered;
- light-theme export (white background and logical black ink restored to black) as a three-layer
  PDF, or a `.mjn` ZIP container carrying page JSON, embedded assets, and layer snapshots.

Notebook hierarchy, text boxes, and strokes are still session-only. Inserted page objects and their
assets persist in app-private storage. Search, View, pixel erasing, and several Backstage operations
remain explicit later-feature shells.

## Safety boundary

- release application ID: `com.meta.inklab`;
- debug package installed by this project: `com.meta.inklab.debug`;
- no Internet permission;
- no broad external-storage permission; import/export uses Android's system document picker;
- session-only strokes, with persistent inserted page objects and embedded private assets;
- stylus/eraser input draws, while finger/palm input on the paper is consumed without creating ink.

## Build and lint

Use the repository-local Gradle wrapper:

```powershell
cd C:\Users\Meta\Project\Workspaces\Notein\android
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

## Install and launch

Normal Android devices can use `adb install -r`. The tested Xiaomi HyperOS build rejects the ADB install channel with `INSTALL_FAILED_USER_RESTRICTED`; copy the APK to Downloads, open it in Xiaomi File Manager, and choose the installer's `单次安装授权`. Do not enable a persistent global unknown-source setting.

Launch component:

```text
com.meta.inklab.debug/com.meta.inklab.MainActivity
```

## Stylus test sequence

The current build intentionally exposes one clean-room fountain-pen stroke. Its three continuous
controls are independent:

- width: 0.1–2.0 mm, default 0.4 mm;
- pressure correction: 0–400%, default 200%;
- ink-flow/speed correction: 0–200%, default 100%.

The percentage boundary follows the values persisted by Notein: the displayed percentage is
divided by 100 before it enters the brush configuration (`200% -> 2.0`, `100% -> 1.0`). The wider
lab ranges are experimental; they are not evidence that Notein's own UI exposes those maxima.

Write the same short Chinese phrase while changing one parameter at a time. Repeat once with
`预测 开` and once with `预测 关`. Judge these separately:

- nib-to-wet-ink alignment during fast curves;
- visible tail snap or double image when lifting;
- start/end taper and horizontal/vertical contrast;
- pressure controllability at slow speed;
- palm/finger rejection.

Test unplugged. This tablet has shown charging-related touch/stylus noise, which is raw input noise
rather than evidence about the brush model.

## Gesture and pressure-eraser test

- Move two fingers together to pan. Pinch around a recognizable stroke to zoom; the point between
  the fingers should stay anchored.
- One finger must neither write nor move the page.
- Write and lift one normal stroke. On the next contact, press above the displayed `压擦阈值` for
  about 140 ms. The provisional ink should disappear and a circular whole-stroke eraser should
  remain active until lift.
- After zooming, write directly beside an existing stroke and confirm wet ink, dry ink, and the nib
  stay in the same coordinate space.

The Xiaomi shortcut adapter recognizes PageUp/PageDown only from the observed
`Xiaomi Smart Pen Keyboard` device. No actions are registered yet, so those exact pen events are
consumed and intentionally do nothing; PageUp/PageDown from ordinary keyboards are not captured.

## Open-source UI assets

The app ships a small source-selected subset of Microsoft's MIT-licensed Fluent UI System Icons.
See `..\THIRD_PARTY_NOTICES.md`. It does not ship the OneNote logo, proprietary Office art, or
copied OneNote UI screenshots.
