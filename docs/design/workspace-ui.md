# Workspace Navigation and Drawing UI

Last verified: 2026-08-22

Read this document before changing notebook/section/page navigation, drawing-tool configuration, or
the dark workspace chrome.

## Product intent

Turn the handwriting lab into a usable tablet workspace whose hierarchy can later be shared with a
Windows client. The interaction language is inspired by the user's installed desktop OneNote, but
the product remains an independent clean-room application with its own recursively nestable section
model.

The current notebook data and tool presets are session-only prototypes. Persistence, multi-notebook
storage, import, and sync remain later product layers; their visible entry points may be shells, but
drawing and hierarchy selection must be real.

## Visual evidence and asset boundary

The user's local OneNote desktop window was observed through Windows window capture on 2026-08-22.
Only chrome and layout were used for comparison; note content was not copied into the project.

The observed dark theme uses:

- near-black title, tab, and notebook chrome rather than a full-width purple title bar;
- a dark-gray drawing ribbon and page list;
- small 2–3 dp corner radii, thin separators, muted selection blocks, and narrow section color bars;
- purple as a restrained accent rather than a dominant surface color;
- tool-specific flyouts opened from a pen/highlighter/eraser item.

The project includes only the regular 24 px vectors it actually uses from Microsoft's open-source
Fluent UI System Icons. They are MIT-licensed and recorded in `THIRD_PARTY_NOTICES.md`. Do not copy
the OneNote logo, brand illustrations, application screenshots, or proprietary Office assets.

## Navigation contract

The two left panes have deliberately non-overlapping responsibilities.

### Black pane: sections only

- Recursively display sections nested inside sections with no fixed depth.
- Never display a page, even when pages and child sections share the same parent section.
- Expanding/collapsing a branch changes only section visibility in this pane.
- Selecting a section establishes the root for the gray page pane.
- `新分区` adds at notebook root; `子分区` adds beneath the selected section.

### Gray pane: selectable page modes

The page-pane header exposes two modes. The default is `本层`.

1. **本层 / Current section**
   - Display only pages directly owned by the selected black-pane section.
   - Never display child sections or pages owned by child sections.
2. **递归 / Recursive**
   - Treat the selected black-pane section as a stable root.
   - Display its direct pages, then each child-section header followed by that child's pages, and
     continue recursively to the deepest descendant.
   - Selecting a deep page does not change the black-pane root. The canvas breadcrumb reflects the
     page's real owning section.

The recursive page pane ignores black-pane collapse state: collapse controls navigation density,
not which descendant pages exist in recursive page mode.

## Drawing-tool contract

Each toolbar item is a `ToolPreset`, not a global mode plus unrelated settings. Adding a tool creates
a new preset; deleting removes only that preset. At least one tool remains.

Required working tools:

- fountain pen with logical black, blue, or red color plus independent width, pressure response,
  ink-flow/speed response, and optional stationary pressure eraser;
- highlighter with the same restricted black/blue/red palette, width, and opacity;
- whole-stroke eraser with its own radius;
- freeform lasso that selects intersecting complete strokes and can delete the selection.

Tap a tool to select it and open its own flyout. The flyout shows only parameters meaningful to that
tool. Pixel erasing and lasso move/resize are labeled shells; they must not masquerade as completed
functions.

The dark canvas displays logical black ink as light ink, mirroring the readability behavior of a
dark note theme. A future persisted document must store the logical palette value and theme-map it
at render time rather than serializing the temporary display color.

## Preserved input behavior

- Stylus input authors the selected pen or highlighter.
- The explicit eraser and hardware eraser erase complete intersecting strokes.
- The lasso consumes stylus motion without creating ink.
- One finger does not write or move the page; two fingers pan/zoom only when no stylus stroke is
  active.
- Prediction, undo, clear-with-confirmation, pressure erasing, viewport transforms, Xiaomi shortcut
  filtering, telemetry, and wet/dry coordinate parity remain available.

## Adaptive behavior

The default workspace shows a 232 dp black section pane and a 196 dp gray page pane. The title-bar
navigation button collapses both to return the full width to the canvas. This keeps the desktop
information architecture available on a tablet without permanently shrinking portrait writing
space.
