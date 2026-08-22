# Stylus Shortcuts, Viewport Gestures, and Pressure Erasing

Last verified: 2026-08-22

Read this document before changing key dispatch, touch arbitration, viewport transforms, or eraser
activation.

## Verified device and Notein behavior

### Xiaomi pen input channels

The tablet exposes two independent input devices:

- `NVTCapacitivePen` at `/dev/input/event11`: stylus position, normalized pressure, distance, and
  tilt. The hardware pressure range is 0–4095 before Android normalization.
- `Xiaomi Smart Pen Keyboard` at `/dev/input/event16`: Bluetooth keyboard-style shortcut events.

The shortcut device advertises arrow, PageUp/PageDown, and Meta keys. A live `getevent` capture in
Notein proved that the two physical buttons emit `KEY_PAGEUP` and `KEY_PAGEDOWN`; a long press holds
the same key and a double press produces two down/up pairs. They are not delivered as
`BTN_STYLUS` or Android's generic stylus-button MotionEvent bits.

Notein resources contain separate configurable Xiaomi primary/secondary shortcut entries and an
independent Xiaomi slide-gesture setting. The capture did not establish which physical label maps
to PageUp versus PageDown, so the lab protocol names the observed keys without guessing primary or
secondary semantics.

### Notein gesture and pressure-eraser evidence

Decoded Notein resources expose these settings and guides:

- `2-Finger Swipe to Move Page`;
- one-finger double-tap zoom as a separate optional gesture;
- `Adaptive Stroke Width`, which makes stroke thickness scale with page zoom when enabled;
- `Hold to Erase`, enabled by default in the decoded eraser layout;
- an eraser sensitivity slider whose decoded companion value defaults to `20%`;
- the guide sequence “write, lift, then press down again,” with temporary erasing ending on lift.

The protected application code does not expose the mapping from that 20% user value to normalized
pressure, or the exact dwell and stationarity constants. The lab must show its normalized threshold
explicitly and treat 78% / 140 ms / 2 dp as tunable research data, not recovered Notein constants.

## Clean-room implementation contract

### Shortcut interface only

`XiaomiStylusShortcutDispatcher` is a protocol boundary, not a feature binding:

- accept PageUp/PageDown only when `InputDevice.name` exactly equals
  `Xiaomi Smart Pen Keyboard`;
- preserve down/up phase, repeat count, and held duration;
- consume recognized events even without a handler so the pen cannot accidentally scroll or
  adjust focused controls; ordinary keyboard PageUp/PageDown still pass through;
- do not bind eraser, lasso, undo, page navigation, or pen width until those product features and
  user-configurable bindings exist.

### Touch arbitration

- Stylus/eraser tool types author ink and take priority while a stroke is active.
- Text cursor mode accepts finger-only MotionEvent streams. A stylus bypasses the text layer and
  begins the most recently selected pen immediately; this does not disable finger text mode.
- Page tags accept a direct finger tap for selection. While lasso is active, a stylus can also tap a
  tag, draw a freeform selection, drag inside the resulting frame, or drag a corner handle to scale.
- The whole-stroke eraser hit-tests both ink strokes and page tags in document coordinates.
- With the panorama-hand control off, one finger is consumed but does not write, pan, or zoom.
- With the panorama hand selected, one finger pans at 1x scale; tapping the selected hand again or
  choosing a drawing tool restores the default two-finger-only mode.
- Two finger pointers begin a combined pan/zoom gesture only when no stylus stroke is active.
- Adding, removing, or reindexing pointers rebases the gesture centroid and span to avoid jumps.
- A viewport transform never changes during an active stylus stroke.

### Coordinate spaces

Finished strokes live in document coordinates. `ViewportState` owns the only document-to-view
scale and translation:

- dry ink and the grid render through the document-to-view matrix;
- apply that matrix inside `ViewStrokeRenderer.drawWithStrokes`, after the renderer captures its
  baseline canvas transform, so its screen-space antialiasing receives the actual zoom;
- stylus MotionEvents enter AndroidX Ink through the inverse view-to-document matrix;
- wet ink uses AndroidX Ink's world/stroke transforms so it matches the dry renderer;
- eraser hit testing converts the pointer through the same inverse matrix.
- lasso selection bounds, tag hit testing, move deltas, and corner scaling all use the same document
  coordinate space; the overlay converts only touch and rendering at its boundary.

Zoom is anchored at the two-finger centroid, not the screen center. The lab range is 50–800%. This
is a safe prototype limit, not a claim about Notein's full runtime range.

### Pressure eraser state machine

1. A completed pen stroke and lift arm pressure erasing.
2. On a later stylus contact, pressure must remain above the displayed normalized threshold while
   the nib stays within a small screen-space stationarity radius for the dwell interval (currently
   140 ms). Continuing to write outside that radius restarts the timer even at high pressure.
3. Activation cancels the provisional wet pen stroke and enters temporary erase mode.
4. Movement erases complete strokes intersecting the visible eraser circle.
5. Stylus lift always exits erase mode, clears the visible eraser circle, and restores the pen.

The first implementation is a whole-stroke eraser. Pixel splitting, eraser-size controls, undo of
erasures, and persistence are separate future features.

## Verification

Automated tests cover viewport anchoring/clamping, pressure-erasing arming/dwell/reset, exact Xiaomi
device/key filtering, lasso geometry, translation/scaling geometry, and finger-only text routing.
Real-device acceptance still requires:

- two-finger pan without scale drift;
- hand-selected one-finger pan, followed by hand deselection restoring inert one-finger input;
- pinch zoom without a centroid jump when a finger lifts;
- completed ink remaining sharp at 50–800% instead of scaling its 1x antialias fringe;
- wet and dry ink remaining under the nib after zooming;
- pressure erase not triggering on the first stroke or ordinary heavy writing;
- eraser activation after dwell and pen restoration on lift;
- Xiaomi pen keys causing no action until a handler is intentionally registered, without changing
  a focused control.
- finger taps creating/focusing text while a hovering or contacting stylus immediately writes ink;
- tag tap/lasso selection, whole-stroke erasure, selection drag, and all four scale handles under
  the actual tablet touch/stylus dispatcher.
