# Handwriting Engine Research and Tablet Lab

Last verified: 2026-08-22

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

Do not require AndroidX Ink binaries on Windows. Preserve a platform-neutral input model containing
positions, elapsed time, pressure, tilt, orientation, tool type, physical-unit calibration, brush
profile/version, and deterministic behavior parameters. Android can use AndroidX Ink for authoring
and rendering; Windows can implement the same behavior graph with Windows pointer prediction and a
native vector/mesh renderer. Visual parity is an acceptance test, not an assumption.
