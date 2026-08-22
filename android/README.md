# 墨笺 Android Workspace Prototype

`墨笺` is a clean-room tablet workspace for studying Notein-like handwriting and the navigation
model of a future independent notebook application. It does not read or modify Notein data.

## Current workspace

- desktop-OneNote-inspired dark chrome with a collapsible section/page navigation area;
- a black pane containing recursively nestable sections only;
- a gray page pane switchable between direct pages (`本层`) and recursive descendant sections plus
  their pages (`递归`);
- per-tool flyouts and add/delete for black, blue, and red fountain pens, highlighter, whole-stroke
  eraser, and freeform lasso selection;
- preserved motion prediction, two-finger pan/zoom, stationary pressure erasing, undo, clear, and
  telemetry.

Notebook hierarchy, pages, and strokes are still session-only. Search, non-drawing ribbon tabs,
pixel erasing, and lasso move/resize are explicit later-feature shells.

## Safety boundary

- release application ID: `com.meta.inklab`;
- debug package installed by this project: `com.meta.inklab.debug`;
- no Internet permission;
- no external-storage permission;
- session-only strokes;
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

The amber `input age` value measures how old the delivered input event is when handled. It is not an end-to-end pen latency measurement.

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
