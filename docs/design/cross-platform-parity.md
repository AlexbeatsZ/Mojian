# Windows / Android Product Parity

Last verified: 2026-08-28

Read this document before changing a product feature, command, persisted page concept, or user-visible
interaction that exists in both the Android and Windows editions of 墨笺.

## Default rule

墨笺 is one product with Android and Windows clients. A reported defect or requested behavior in a
shared product concept is a two-platform task by default. The same work item must inspect both
implementations and, when both expose the concept, update both implementations, tests, documentation,
and delivered builds together. The user must not need to repeat that the other client also needs the
fix.

A one-platform change is acceptable only when the behavior is genuinely platform-specific, such as
Xiaomi pen keys, Android content-URI grants, Windows native caption hit testing, or a driver-specific
pointer stream. Record that exception and its reason in the relevant design document or project log;
do not silently leave the other client behind.

## Shared behavior, native platform boundary

Parity means the same product outcome, not identical operating-system APIs. For example:

- `开始 -> 粘贴` and its keyboard shortcut paste an image from the system clipboard as a normal
  page-owned image object. Windows accepts a clipboard bitmap and copied image-file drop list;
  Android accepts foreground-readable `ClipData` image URIs. Each client immediately copies the
  image into the active page's private asset directory, preserves its aspect ratio, applies the
  configured image display policy, and persists the object. Text editing and the product's internal
  object clipboard remain available when the system clipboard does not contain an image.
- A4 is a continuous Word-style stack on both clients. Existing content near the final sheet and a
  viewport browsing near the final sheet both append more paper before the user reaches the end.
  Sheets retain an explicit workspace-colored gap, the stored count never shrinks automatically,
  and the enlarged count persists with the page.
- Shared `.mjn`, page appearance, object, and asset semantics remain compatible even when the native
  picker, clipboard, pointer, or window integration differs.

## Completion gate

For a shared product change:

1. Trace the corresponding Windows and Android routes before editing.
2. Add focused regression coverage at the lowest durable shared seam on each client.
3. Run the affected Windows tests/build and Android unit tests/build/lint.
4. Refresh both deliverables: the self-contained Windows Release EXE and the current Android APK.
5. Perform native runtime or device acceptance when the required device/session is available; record
   any remaining physical acceptance honestly rather than treating compilation as runtime proof.

Do not close a shared change merely because one client built successfully.
