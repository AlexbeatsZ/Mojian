# Ink storage format: size and memory (2026-11-18)

Read this before changing how strokes, points or outlines are stored.

## Incident

Tablet "页面暂时无法读取" on 作业 4 (8,966 strokes / 289,609 points): 358 MB page, 90% Windows
`vectorPath`. 0.7.34 compacted outlines (page 70 MB), but Android still OOMed while *parsing*: the
org.json tree (397k objects, 290k point objects x 6 keys, each key a new String) filled the 512 MB
heap. 0.7.35 shares repeated names/short values in `StreamingJson`; the page then opened with a
177 MB Java heap, and an uncompacted 85 MB page with 263 MB. Points as JSON objects remain the
dominant memory cost on Android.

## How Notein and OneNote store ink (sources, not decompiled code)

Notein's business DEX is Baidu-protected; do not unpack it. Its APK ships AndroidX Ink
(`libink.so`) and its backup stores new strokes as `ink_stroke_blob`. OneNote stores ISF. Both
formats are public, so study them directly:

- **AndroidX / Google Ink** (`github.com/google/ink`, `ink/storage`): persists
  `CodedStrokeInputBatch`, never the mesh. Each channel (x, y, elapsed time, pressure, tilt,
  orientation) is a `CodedNumericRun { repeated sint32 deltas [packed]; float scale; float offset }`;
  value[n] = value[n-1] + scale * delta[n]. x/y quantized to ~4096 steps of the batch envelope,
  time in microseconds, pressure 1/4096, tilt 4096/(pi/2). Absent channels are omitted. AndroidX
  gzips the message. Decoding is lazy (iterators). Shape = inputs + brush, regenerated on load.
- **ISF** (WPF `MS.Internal.Ink.InkSerializedFormat`, MIT; Microsoft Open Specification): per stroke,
  each property is an int array compressed independently: delta-delta (x[n]-2x[n-1]+x[n-2]), then
  Huffman with built-in tables chosen by variance, or fixed-width "Gorilla" bit packing; no outline.
- Measured on 作业 4 (same strokes): Mojian points JSON 26.8 MB = 92.6 B/point; WPF ISF of
  x/y/pressure 0.7 MB = 2.4 B/point; gzip of the whole compacted page 11.5 MB.

## Implemented (0.7.36 / macOS 0.2.3)

`pointsCoded` (`shared/ink-points.md`, golden vectors `shared/fixtures/points-coded.json` from an
independent Python implementation): per-channel zigzag LEB128 deltas, x/y 0.001 DIP, pressure 1e-4,
exact ms. Windows decodes via `StrokeRecord.PointsCoded` on every read path and writes it only in
the page store (unchanged strokes stay verbatim; clipboard/history/.mjn keep arrays). Android keeps
the packed string in the tree and decodes per stroke on use. Swift expands on load, packs on write.
Strokes with per-point extension fields or mixed channels keep `points`. The library was migrated
with `tools/outline-compaction --pack-points` after every device ran 0.7.36 and all 444 pages
>= 0.5 MB were proven unchanged on every device: 3150 -> 1774 MB, 587,276 strokes, worst error
0.5 quantum on 1.26M checked points. Imported OneNote outlines (`vectorPath` from ISF geometry)
are now the largest remaining field on imported pages.

## Implications for Mojian (original analysis)

1. Points are the next bottleneck, not outlines. A packed per-stroke channel encoding (delta
   varints/zigzag at fixed quanta: 0.01 DIP, pressure 1/4096, ms) would cut ~27 MB to ~1-2 MB and
   replace 290k JSON objects with 9k strings on Android.
2. Both references regenerate shape from inputs. Mojian stores `vectorPath` because the three
   clients do not share one deterministic generator (Android authors with AndroidX meshes).
   Dropping outlines requires one generator per profile on all clients plus pixel tests; until
   then keep the compact outline (0.7.34).
3. Any packed form is a schema change: old clients cannot read it. Gate it behind a page `version`
   bump, update `shared/page.schema.json`, all three readers/writers, sync fixtures and migration,
   and keep imported/unknown fields byte-preserved.
