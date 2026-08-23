# Stylus Shortcuts, Viewport Gestures, and Pressure Erasing

Last verified: 2026-08-23

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

The installed protected APK likewise exposes editor gesture class names only as unresolved
references, including `NoteinZoomHandle`; their implementations and numeric motion thresholds are
not defined in the shell DEX. A fresh scan of all six DEX files marks `NoteinZoomHandle` as `r`
(referenced) rather than `d` (defined), while the defined application-loader classes belong to
`com.sagittarius.v6`. Do not claim a Notein-specific pinch threshold from static inspection.
The clean-room implementation instead follows Android's public gesture convention: scale slop is
twice `ViewConfiguration.scaledTouchSlop`, with explicit pan/scale arbitration described below.

## Clean-room implementation contract

### Android stylus shortcuts and shared command IDs

`XiaomiStylusShortcutDispatcher` is an Android-only device boundary:

- accept PageUp/PageDown only when `InputDevice.name` exactly equals
  `Xiaomi Smart Pen Keyboard`;
- preserve down/up phase, repeat count, and held duration;
- consume recognized events so the pen cannot accidentally scroll or adjust focused controls;
  ordinary keyboard PageUp/PageDown still pass through;
- interpret each physical key independently as delayed single tap, rapid double tap, or hold. The
  user owns the double-tap window (150--600 ms) and hold threshold (200--1200 ms);
- single/double tap can toggle to a chosen tool and back, undo, redo, or move to the previous/next
  tool in actual ribbon order. Hold can temporarily select a chosen tool and restores the prior
  tool on release;
- a single tap waits until the double-tap window expires. A configured double action suppresses
  both singles; without one, the two taps retain ordinary two-single behavior;
- consume application-delivered repeats, but do not claim that Android can override a Xiaomi
  system gesture that the OEM handles before it reaches this process.

The settings UI calls these `触控笔` bindings; Xiaomi device bindings are never exposed on Windows.
Android and Windows instead share stable
action IDs/categories for `文件`, `开始`, `插入`, `绘图`, and `视图`, with platform-local key assignments.
Only concrete leaf actions are bindable: `重要` and `问题` are separate actions, while the parent
tag chooser is not. Duplicate key combinations move to the newly assigned action.

### Touch arbitration

- Stylus/eraser tool types author ink and take priority while a stroke is active.
- Text cursor mode accepts finger-only MotionEvent streams. A stylus bypasses the text layer and
  begins the most recently selected pen immediately; this does not disable finger text mode. The
  text layer observes finger taps at its own dispatch boundary, before an enabled `EditText` can
  consume them, so double tap selects the complete text box both during and outside editing. When
  editing is off the layer still returns the stream to the canvas, preserving two-finger transforms.
- Page tags accept a direct finger tap for selection. While lasso is active, a stylus can also tap a
  tag, draw a freeform selection, drag inside the resulting frame, or drag a corner handle to scale.
- Each lasso preset switches between a freeform path and a normalized drag rectangle. The completed
  polygon selects overlapping text boxes and non-background inserted objects in addition to ink
  and tags; the shared frame transforms every selected content type in document coordinates.
- The whole-stroke eraser hit-tests both ink strokes and page tags in document coordinates.
- With the panorama-hand control off, a one-finger stream first reaches the inserted-object layer.
  A hit image/object may consume double-tap selection, selection drag/resize, activation, or long
  press. A miss continues to the ordinary canvas arbiter and does not write, pan, or zoom.
- Observing an inserted object never withholds the pointer stream from the canvas arbiter. The first
  finger `DOWN` must reach both layers so a later second pointer can establish a complete pan/pinch
  baseline even when the gesture starts over a selected image.
- Object-body move and handle resize remain candidates until movement exceeds the platform touch
  slop. A stationary hold may open the object menu; motion beyond slop permanently suppresses that
  menu for the sequence. When the panorama hand is active, object manipulation stays observational:
  sliding over an image pans the page, while a stationary hold can still open the image menu.
- A second finger cancels any pending object move/resize and hands the unchanged selection to the
  two-finger transform arbiter. Page pan/zoom never clears the selected image. Only Android's
  confirmed single-tap callback on a different object or blank canvas clears it; raw `DOWN`, drag,
  pointer-count changes, and pinch do not.
- With the panorama hand selected, one finger or a contacting stylus/eraser pans at 1x scale and
  cannot author ink. Tapping the selected hand again or choosing a drawing tool restores the
  default two-finger-only mode.
- Two finger pointers begin a transform candidate only when no stylus stroke is active. Centroid
  drift below one platform touch slop and span drift below two touch slops do nothing.
- After a candidate crosses a threshold, it initially commits to pan or scale according to the
  larger normalized motion. A committed pan ignores span noise below scale slop but promotes to
  scale if the same contact sequence later develops an unambiguous pinch. Scale remains committed
  and may translate around its moving centroid. Initial commitment and pan-to-scale promotion both
  rebase the incremental span so the page does not jump.
- The zoom lock prevents a scale candidate from committing but does not disable a two-finger pan;
  ribbon presets and zoom buttons are outside this touch arbiter and continue to work.
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
- lasso selection bounds, text-box and inserted-object bounds, tag hit testing, move deltas, and
  corner scaling all use the same document coordinate space; the overlay converts only touch and
  rendering at its boundary.

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

Automated tests cover transform slop, pan-to-scale promotion, scale commitment, viewport anchoring/clamping,
pressure-erasing arming/dwell/reset, exact Xiaomi
device/key filtering, lasso geometry, translation/scaling geometry, and finger-only text routing.
Real-device acceptance still requires:

- two-finger pan without scale drift;
- hand-selected one-finger pan, followed by hand deselection restoring inert one-finger input;
- pinch zoom without a centroid jump when a finger lifts;
- completed ink remaining sharp at 50–800% instead of scaling its 1x antialias fringe;
- wet and dry ink remaining under the nib after zooming;
- pressure erase not triggering on the first stroke or ordinary heavy writing;
- eraser activation after dwell and pen restoration on lift;
- Xiaomi PageUp/PageDown filtering, delayed single/double arbitration, temporary hold restoration,
  user timing limits, and ordinary-keyboard separation. Physical key and OEM long-press acceptance
  still require the tablet.
- finger taps creating/focusing text while a hovering or contacting stylus immediately writes ink;
- tag tap/lasso selection, whole-stroke erasure, selection drag, and all four scale handles under
  the actual tablet touch/stylus dispatcher.
- inserted-image finger double tap, eight-handle resize, image drag, immediate long-press menu, and
  stylus authoring above an image under the actual tablet dispatcher.
- selected-image two-finger pinch beginning on the image, panorama-hand swipe beginning on the image,
  stationary long press versus moving swipe, and selection retention after page pan/zoom.
