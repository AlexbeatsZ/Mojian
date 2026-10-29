# Stored ink appearance compatibility

Last verified: 2026-10-12

Read before changing Android shared-stroke color, opacity, native proxy brushes or legacy pen loading.

## Imported page contrast (2026-10-29)

Read `imported-page-presentation.md` before changing source-color display. Old migrated pages now
adapt paper, neutral source ink, explicit rich-text colors and page-like screenshots together,
without changing stored colors/alpha/geometry or the original image bytes. Colorful backgrounds
must not be handled by blindly inverting every RGB channel. Non-imported authoring retains its
existing palette behavior. Display cover ink follows paper conversion during printing.

## Filled outline intersections (2026-10-29)

Unprefixed shared `vectorPath` uses nonzero winding, matching Android/SVG. WPF's path parser
defaults to EvenOdd, which made overlapping parts of one imported/native Android outline turn
transparent. Windows now supplies `F1` only when a path has no explicit fill prefix; intentional
`F0` holes and explicit `F1` remain authoritative. Android newly authored outlines include `F1`;
loading existing records does not rewrite their path, color, opacity, inputs or transform.
Live rendering, hit testing, PNG/SVG and PDF consume the same effective filled geometry. A
translucent crossing is filled once at the source alpha, rather than drawing each contour again.
Desktop regressions sample canvas/PNG pixels and independently rendered PDF pixels for all
three prefix cases; the isolated Android color probe also samples crossings and source retention.

Windows ordinary fountain pens without a `vectorPath` render with opaque palette/source RGB.
Older authoring records can still contain `opacityPercent=32`, inherited from the tool preset's
highlighter-only field. This value is not a transparency instruction for ordinary freehand pens.
The inspected current desktop page contained 1,083 such pens and 22 opaque generated shapes.

Android `StoredInkAppearance` reproduces that display rule at decoding time. It does not rewrite
the source stroke JSON, IDs, points, colors, or opacity fields. New Android steel/fountain pens
already author with opaque color and need no parameter change.

`shape-v1` and imported filled outlines retain their stored opacity, including zero. Highlighters
remain translucent. Outlines own their geometry and use a neutral native proxy rather than the
interactive highlighter brush, whose 10-70% settings clamp must not alter source opacity.
Windows already treats outline/shape opacity separately, so this is an Android reader compatibility
repair, not a change to the shared document format or Windows pen behavior.

Unit regressions cover legacy/missing-profile pens, unchanged JSON, highlighter/shape opacity and
the outline alpha range. Debug-only `InkColorProbeActivity` uses isolated native views and synthetic
strokes, never preferences or user pages. It checks legacy pen RGB pixels, source JSON round trip
and ten outline opacity/type combinations. Physical touch/pen appearance remains user acceptance.

The 2026-10-12 Xiaomi Pad 6 Pro probe passed: black/blue/red/dark-page white at 50%, 100% and
320% produced twelve exact RGB matches (zero error). The legacy red pen pixel was `#FFC42B1C`,
source JSON remained unchanged, and ten outline opacity/type cases passed. Android 161/161 unit
tests, APK/test-APK assembly and lint passed (zero errors, 130 warnings). This is native isolated
pixel evidence; it does not measure stylus latency or replace physical gesture acceptance.
