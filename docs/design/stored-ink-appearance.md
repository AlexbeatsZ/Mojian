# Stored ink appearance compatibility

Last verified: 2026-10-12

Read before changing Android shared-stroke color, opacity, native proxy brushes or legacy pen loading.

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
