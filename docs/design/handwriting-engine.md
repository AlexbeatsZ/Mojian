# Handwriting Engine Research and Tablet Lab

Last verified: 2026-11-07 (Notein 2.1.48 black-box calibration)

## Objective

Reproduce the qualities the user values in Notein handwriting:

- visible fountain-pen character and tapered stroke endings;
- low apparent latency;
- no distracting separation between the physical nib and wet ink;
- stable dry strokes without a visible handoff jump;
- an engine that can later be shared conceptually with a Windows client.

The work is a clean-room behavioral study. Do not copy or redistribute Notein application code or
brush assets. The user's backup may be inspected locally for structural interoperability evidence,
but note titles, text, coordinates, PDFs, and thumbnails must not be committed.

## Verified Notein Evidence

### Rendering engine

- Installed package: `com.orion.notein` `2.0.10.0` (`versionCode=343`).
- The APK contains `lib/arm64-v8a/libink.so`.
- That library is 1,268,384 bytes with ELF Build ID
  `f1d1c3bd763943e1864e2073844e0572`.
- The arm64 library from Google Maven artifact
  `androidx.ink:ink-nativeloader-android:1.0.0` has the same size and Build ID.

Therefore Notein's current native ink core is AndroidX Ink 1.0.0, not an unidentified proprietary
renderer. Notein's product-specific value is its brush family, configuration, input integration,
and surrounding note application.

### Protection boundary and Xiaomi prediction path

The APK manifest uses Baidu Protect's `com.sagittarius.v6.StubApplication`. Defined-only DEX
inspection finds the protection shell and third-party libraries but not Notein's business classes;
the apparent archive JARs are encrypted payloads loaded at runtime. This explains why resource
names and native exports decompile normally while the Kotlin/Java state machines that select and
configure them do not.

The native layer nevertheless proves a separate Xiaomi integration in this exact build:

- `libestimate_interface.so` exports JNI methods named for
  `com.miui.penengine.facade.algorithm.JNIPathEstimateInterface`;
- it loads Xiaomi's `libtflite_interface_wrap.touch.so`, present on this tablet at
  `/system_ext/lib64/`;
- it forwards model initialization, float-array inference, destruction, and availability checks;
- Notein bundles an encrypted `assets/predict/estimate.hdb` TensorFlow Lite model for that route.

This is distinct from AndroidX Ink's own `MotionEventPredictor`. It is strong evidence that Notein
can add a Xiaomi-specific trajectory forecast before or alongside its general Ink authoring path.
The model's preprocessing window, output horizon, and blending rules are not yet recovered. An
isolated no-data probe succeeded after declaring Xiaomi's public touch native library: the facade
reported availability, initialization returned zero, and one raw inference returned a two-float
array. This proves the route runs on the user's tablet; it does not yet prove what the two normalized
values mean. The probe and Notein's proprietary model must not become a distributed product
dependency.

Xiaomi now documents a supported Pencil Engine SDK. Its public integration collects current and
historical `MotionEvent` points on each move, sets the current display refresh rate, and returns a
list of predicted points. Production should use that authenticated SDK for Xiaomi-specific
acceleration and retain AndroidX `MotionEventPredictor` as the portable fallback. This is safer and
more maintainable than reproducing Notein's private JNI/model packaging. Reference:
[Xiaomi Pencil Engine client integration guide](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1884).

### Local backup and stroke records

The user-authorized local backup is a 1,579,602,577-byte unencrypted ZIP with 170 entries:

- 167 per-note `.in` files;
- two `.template` files;
- one `.tree` file.

Each inspected `.in` is itself an unencrypted ZIP. It contains a Room/SQLite database at schema
version 25, JSON metadata, and, for PDF notes, the original PDF. The ink-related tables include
`StrokeEntity`, `ShapeEntity`, `PageEntity`, and `PageLayerEntity`.

Across all 167 note databases:

- total stroke rows: 316,354;
- legacy `record_json` strokes: 305,132;
- new `ink_stroke_blob` strokes: 11,222;
- `ink_stroke_json` rows: zero.

Legacy records store an explicit point list with `x`, `y`, pressure `p`, and action plus brush type,
color, width, bounds, layer, and creation time.

Every new binary stroke uses the same technical brush family and behavior parameters:

```text
client brush family: notein-fountain-v1
pressure_correct: 2.0
speed_correct: 1.0
epsilon: 0.01
tool type: stylus
```

The binary record is protobuf-like and stores ID/layer/time, brush size/color/epsilon, physical
stroke-unit length, packed positions, per-input elapsed time/pressure/tilt/orientation, and behavior
parameters. One dot sample contained eight real inputs. Its packed per-input fields matched elapsed
milliseconds, normalized pressure, tilt, orientation, and physical unit length.

The backup evidence is sufficient to build a future read-only importer. It does not reveal the
complete `notein-fountain-v1` behavior graph, which Notein constructs at runtime.

### AndroidX Ink behavior relevant to perceived quality

AndroidX Ink 1.0.0 provides the mechanisms needed to explain Notein's observed behavior:

- `InProgressStrokesView` renders a wet stroke through the low-latency/front-buffer path on
  supported Android versions.
- `MotionEventPredictor` supplies predicted inputs up to the next display frame.
- `InProgressStroke` separates real and predicted inputs and incrementally updates native geometry.
- a wet-to-dry handoff keeps the stroke visible while the application commits the final `Stroke`.
- `BrushFamily.SlidingWindowModel` models and upsamples input rather than connecting raw samples
  directly.
- `BrushBehavior` can drive tip size, shape, opacity, rotation, and offsets from pressure, speed,
  direction, acceleration, distance traveled, distance remaining, and predicted-input age.

AndroidX's stock `pressurePen` already combines prediction fade-out, end taper, direction-dependent
width, lateral-acceleration response, high-pressure response, and a sliding-window input model.
Notein adds or tunes a family identified as `notein-fountain-v1` and persists pressure/speed
correction controls.

### Notein 2.1.48 calibrated fountain behavior (2026-11-07)

Installed `com.orion.notein` `v2.1.48.0` (code 358) on Xiaomi Pad 6 Pro. Business DEX is still
Baidu-protected, as in 2.0.10, so the fountain graph was measured, not decompiled. Static evidence:

- the bundled `libink.so` (1,415,664 bytes) is not the 1.0.0 build, and the retained classes include
  `IntegralNode` and `MultipleBrushFamilies`, which 1.0.0 lacks: most likely Ink 1.1 alpha. Most node
  types (Damping/Noise/PolarTarget/ToolTypeFilter) and `InProgressShapesView` already exist in 1.0.0,
  which Mojian pins;
- no `androidx.input.motionprediction` class is retained. Prediction is vendor-specific: Xiaomi
  `JNIPathEstimateInterface` + `estimate.hdb`, Huawei `HwStrokeEstimate`, vivo `TrackPredCore`
  with per-model `optparam*.cfg`;
- `assets/ink/*.noteinbrush` are gzip `BrushFamily` protos XOR `0x5d`; pencil/rainbow families use the
  default `SlidingWindowModel` (20 ms window, 1/180 s upsampling) and the stock prediction fade.
  The fountain family is not an asset; it is built at runtime.

Method: a uinput virtual pen with the real `NVTCapacitivePen` axes (shell has `uhid`; HyperOS
SELinux denies writing `/dev/input/event*`) replayed timed 480 Hz strokes with exact pressure and
on-glass speed into a Notein page; widths were measured from screenshots. Settings were
`笔刷粗细 0.5 / 压感 200% / 出墨速度 100% / 稳定性 50%` unless stated, and restored afterwards.

Measured model (steady state, multiplicative):

```text
width = W1 · p^γ · (1 − s·g(v)) · dir
γ = 压感 / 100            (200% → p², 107% → ≈linear; p=1 width is the same for every γ)
s = 出墨速度 / 100
g(v), v = on-glass cm/s (independent of page zoom):
  v   ≤1   2    4    8    16   32   ≥64
  g   0   .08  .15  .23  .46  .69  .85 (cap)
dir = 1 for horizontal/upward motion, ≈1.09–1.12 for downward vertical/diagonal
      (matches the stock NORMALIZED_DIRECTION_Y behavior already in Mojian)
```

- No distance-based start or end taper: a constant-pressure, constant-speed line has the same width
  from the first to the last pixel. Teardrop heads and hairline tails arise only from the user's
  pressure falling and speed rising within the stroke (reproduced with v 0.25→40 cm/s, p 0.7→0.2).
- Very light input disappears: p ≤ 0.05 draws nothing visible at 200%.
- Pressure has no damping beyond the input window (a 0.3→0.8 step settles in ≈20 ms at 50%
  stability). Speed is smoothed more (≈50 ms to settle after a 2→32 cm/s step).
- Tilt 0–45° has no width effect.
- `稳定性` is the input smoothing window: 0% gives sharp V corners and ≈10 ms steps, 50% ≈20 ms
  (the AndroidX default), 100% rounds a V into a U and stretches steps to ≈110 ms.
- Width is document-space (scales with zoom); `W1` at 100% zoom for size 0.5 is ≈8.6 px ≈0.71 mm.

The pre-0.7.18 Mojian fountain (now `fountain-v1` / `BrushProfiles.createLegacy`) was the opposite
in the parts that matter for Chinese stroke edges: it started thin (distance taper 0.55→1), tapered
the end by distance, mapped pressure linearly into 0.55×–1.45× (Notein spans ≈0→1 with p²), its
speed range 0–85 cm/s only thinned by 18% (real handwriting peaks at 4–9 cm/s, so it was effectively
inactive), and every behavior added 12–25 ms damping.

### Mojian `fountain-v2` (Android 0.7.18)

`BrushProfiles.create` now authors the calibrated model; `fountainV2Spec` holds the pure numbers:

- pressure: `NORMALIZED_PRESSURE` 0..1 → size 0..1.4 through a 16-segment `EasingFunction.Linear`
  sampling `p^γ`, no damping, stylus only; γ = 0 uses a step at x = 0 (constant width);
- speed: `INPUT_SPEED_IN_CENTIMETERS_PER_SECOND` 0..64 → 1..(1 − 0.85·s) through the measured g(v)
  table, 25 ms damping, omitted at 0% ink flow (degenerate range, the 0.7.17 crash guard);
- direction: stock `NORMALIZED_DIRECTION_Y` 0.45..0.65 → 1..1.17; stock prediction fade;
  default `SlidingWindowModel` (Notein stability 50%); no distance tapers, no lateral-acceleration term;
- `InProgressStrokesView` derives `strokeUnitLengthCm` from `displayMetrics.xdpi`, so the speed
  source is on-glass cm/s like Notein. Strokes rebuilt from JSON have no unit length, but every
  Android `fountain-v2` stroke stores its outline (`vectorPath`), which is what gets drawn;
- the nominal tool width keeps Notein's meaning (size 0.5 ≈ 0.71 mm at p = 1), so existing presets
  draw lighter strokes thinner than v1 did.

Compatibility: the JSON `brushProfile` is `fountain-v2` only for strokes whose family id starts with
`inklab-fountain-v4-`. Windows-authored ink and older Android ink stay `fountain-v1` and are rebuilt
with the legacy family when they have no outline (28,755 Windows and 806 older Android strokes on
the tablet library had none), so existing notes do not change appearance. Windows and iOS draw the
stored outline for Android strokes and pass the profile string through. Windows authoring still uses
its own engine; matching it to `fountain-v2` is separate work.

### Android tablet latency diagnosis (2026-11-07)

`dumpsys gfxinfo` on the tablet: Mojian 90/95/99th frame percentiles 53/65/113 ms with 90 of 500
frames "slow issue draw commands"; Notein 24/27/44 ms. `DryInkView.addStrokes()` invalidates and,
outside pan/zoom gestures, `onDraw` re-records every visible stroke (`drawScene`, plus a per-frame
`strokes.filter`). A median Chinese stroke lasts ≈160 ms, so nearly every pen lift blocks the UI
thread before the next pen-down is handled. Windows WPF keeps one retained visual per stroke and does
not show this. 0.7.18 keeps composited scene tiles at rest and re-records only tiles intersecting a new or erased
stroke (see `android-page-navigation.md`). 0.7.20 fixes the host scene revision still discarding all
tiles and makes the product non-debuggable: `simpleperf` showed the UI thread almost entirely in
ART's switch interpreter and generic JNI trampolines (`status=run-from-apk`, no dexopt).

Tablet acceptance 2026-11-07 on one ~1900-stroke temporary page, 60 injected handwriting strokes
(120 ms gaps, 8 cm/s), `gfxinfo` frame p90/95/99 and UI draw-record p90:

| Build | p90/95/99 ms | UI record p90 |
|---|---|---|
| 0.7.17 (user writing, earlier) | 53 / 65 / 113 | n/a |
| 0.7.18 debuggable | 61 / 65 / 73 | 31.9 ms |
| 0.7.20 debuggable | 53 / 61 / 77 | 24.9 ms |
| 0.7.20 non-debuggable, `speed` dexopt | 24 / 28 / 38 | 7.7 ms |
| Notein 2.1.48 (user writing) | 24 / 27 / 44 | n/a |

The same session verified `fountain-v2` on device by the same uinput method: width relative to p=1 at
p 0.3/0.4/0.5/0.6/0.8 = 0.091/0.164/0.255/0.364/0.655 (p²), speed factor at 2/4/8/16/32/64 cm/s =
0.915/0.855/0.76/0.54/0.316/0.159 (Notein 0.92/0.85/0.77/0.54/0.31/0.15), and an accelerating,
lightening flick tapers from 4 px to 0.1 px. Measure thin strokes per column with the real background
level; a wide coverage window with a wrong background value adds false width. HyperOS's global `MiuiStylusEventBlocker` (40 ms / 8 px)
applies to both apps and is not the differentiator.

## Research Model

Treat perceived handwriting quality as two independently testable systems:

1. **Following quality** — event history, motion prediction, front-buffer rendering, modeled-input
   upsampling, wet geometry update, and flicker-free wet-to-dry handoff.
2. **Mark quality** — tip geometry, pressure curve, speed curve, direction/acceleration response,
   start/end taper, brush size, and physical-unit calibration.

Do not tune brush width to hide a latency problem. Do not disable prediction merely because an
overly opaque predicted tail looks wrong; prediction opacity and handoff should be corrected first.

## Tablet Lab v1

Package name: `com.meta.inklab`.

Safety boundary:

- distinct package and signing identity from Notein;
- no network permission;
- no storage permission;
- no access to Notein package data or the user's backup;
- all v1 strokes are session-only and disappear when the app process is cleared.

### Current experiment

After the first comparison build, the user rejected the circular and stock profiles and requested
one adjustable fountain-pen stroke. The current build therefore has no brush-profile selector. It
exposes only these independent controls:

- physical width: 0.1–2.0 mm, default 0.4 mm;
- pressure correction: 0–400%, default 200%;
- ink-flow/speed correction: 0–200%, default 100%.

Notein's persisted values prove the percentage boundary mapping: `percentage / 100`, so 200% is
stored as 2.0 and 100% as 1.0. They do not prove the private behavior graph that consumes those
numbers. The lab keeps the direct boundary mapping while clearly treating its current curve as a
clean-room research implementation. The extended maxima are user-requested experimental ranges,
not a claim about Notein's own UI limits.

Motion prediction is independently switchable. Front-buffer authoring stays enabled. The telemetry
strip reports event dispatch age, historical samples, prediction availability, refresh rate, and
stroke count; it must not call this number end-to-end pen latency.

The tablet exhibits touch/stylus instability while charging. Evaluate brush smoothing and nib
alignment while unplugged; charger-induced digitizer noise is a separate raw-input condition and
must not be attributed to the brush algorithm without a controlled capture.

Viewport, touch arbitration, Xiaomi shortcut input, and pressure-erasing behavior are specified in
`docs/design/input-gestures.md` and must preserve the same wet/dry document transform.
Pressure erasing is product-level tool switching, not part of the fountain brush: its configuration
belongs to one real eraser preset, can be triggered by pen or highlighter authoring, and must expose
the temporary eraser selection/cursor before restoring the source tool on lift.

The Android dry-ink layer caches document-space bounds and content-bottom metadata for completed
strokes. Normal on-screen drawing submits only strokes intersecting the inverse-mapped viewport to
AndroidX Ink; export submits the complete list. Any operation that replaces, moves, scales, erases,
undoes, or redoes strokes must rebuild that cache so culling never hides changed ink.

### Interface direction

Subject: a stylus-engine test bench for a technically engaged tablet user.

Palette:

- drafting rail `#1B2027`;
- rail text `#F1F4F8`;
- paper `#F7F9FC`;
- grid `#D8E2F0`;
- active cobalt `#2D5BFF`;
- telemetry amber `#E9A23B`.

Typography uses the Android system sans for Chinese labels and monospace for measurements. The
layout is a narrow dark instrument rail, a dominant full-size paper surface, and a compact telemetry
strip. The signature element is the explicit wet/predicted-ink state rather than decorative chrome.

## Acceptance for First Installation

- Builds as a debug APK without changing global development tools.
- Installs alongside Notein on device `cd94ae18`.
- Manifest contains no Internet or external-storage permission.
- Stylus draws; finger/palm contact does not create ink.
- The single brush can be rebuilt from every control without clearing existing strokes.
- Prediction can be toggled independently.
- Clear and undo work.
- Completed strokes hand off without a visible gap or double-opacity flash.
- App starts while Notein remains force-stopped and Wi-Fi remains disabled during the initial test.

### First installation result

- `assembleDebug` and `lintDebug` pass.
- Debug package `com.meta.inklab.debug` was installed beside Notein on Xiaomi `23046RP50C` through File Manager's one-time install authorization; HyperOS rejected the ADB install channel.
- Installed package inspection shows zero application permissions other than AndroidX's generated signature-only dynamic-receiver permission. There is no Internet, storage, or Notein-data permission.
- Cold launch completed in 451 ms without an application exception. Notein remained force-stopped and Wi-Fi remained disabled.
- The initial four-profile build switched all profiles, sizes, and prediction states without recreating the activity.
- A real stylus session completed 31 strokes with prediction `ACTIVE`; the telemetry snapshot reported 2.2 ms input-event age and 120 Hz at that instant. This is useful pipeline evidence, not a claim of measured nib-to-photon latency.
- The initial one-row rail clipped controls in portrait. The installed revision uses two scrollable rows and actual Android system-bar insets; real-device portrait inspection shows prediction, undo, clear, telemetry, and navigation unobscured.
- The 2026-08-22 revision removes all profile choices, keeps one fountain-pen stroke, changes width
  to a continuous 0.1–2.0 mm control, and extends pressure/ink-flow ranges to 0–400% and 0–200%.
  `assembleDebug` and `lintDebug` pass; the APK was updated directly through wireless ADB and cold
  launched in 393 ms. Live device interaction reached the newly extended high-value region without
  an application exception.
- The later 2026-08-22 revision adds shared-coordinate two-finger pan/pinch zoom, a temporary
  pressure whole-stroke eraser, and an unbound Xiaomi shortcut adapter. Its first device build was
  installed and cold-launched in 720 ms. After the user's heavy-writing observation, the eraser
  candidate was changed to require stationarity as well as pressure; ten unit tests plus
  `assembleDebug` and `lintDebug` pass. After the user supplied the changed wireless-debugging
  port, the corrected APK was installed and cold-launched in 408 ms.

The remaining acceptance items—palm rejection quality, visible wet-to-dry handoff, nib alignment, and subjective brush character—require the user's physical stylus judgment.

## Later Windows Boundary

图形笔画和停笔转直线遵循 `shapes-and-straightening.md`。`shape-v1` 使用固定线宽与原始几何点列，不能经过自由笔的压力/速度平滑；停笔计时须在没有新输入事件时继续工作，并服从压力橡皮和取消路径。

Do not require AndroidX Ink binaries on Windows. Preserve a platform-neutral input model containing
positions, elapsed time, pressure, tilt, orientation, tool type, physical-unit calibration, brush
profile/version, and deterministic behavior parameters. Android can use AndroidX Ink for authoring
and rendering; Windows can implement the same behavior graph with Windows pointer prediction and a
native vector/mesh renderer. Visual parity is an acceptance test, not an assumption.
