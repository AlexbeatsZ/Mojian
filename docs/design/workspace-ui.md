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

- Display notebook nodes in the black tree; expanding a notebook reveals only its section tree.
- Selecting a notebook switches the active workspace and therefore the gray page pane and canvas
  header. Do not copy OneNote's repeated per-notebook `新建分区` row.
- Recursively display sections nested inside sections with no fixed depth.
- Never display a page, even when pages and child sections share the same parent section.
- Expanding/collapsing a branch changes only section visibility in this pane.
- Selecting a section establishes the root for the gray page pane.
- The black-pane header has one `+` control, aligned with the gray pane's add-page control. It adds
  beneath the focused section; clicking blank space in the black pane clears that focus, after which
  `+` adds a notebook-root section. Do not expose separate root/child add buttons.
- The black pane has no local search box. Full-notebook search belongs to the single top-right search
  entry point.

### Gray pane: selectable page modes

The page pane supports two modes. The default is `本层`; the mode selector belongs in
`文件 > 选项`, not in the page-pane header.

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

The drawing ribbon is icon-only. A first tap on an unselected tool selects it without interruption;
tapping the already selected tool opens its own flyout. The flyout shows only parameters meaningful
to that tool. Pixel erasing and lasso move/resize are labeled shells; they must not masquerade as
completed functions.

Follow the desktop OneNote grouping rhythm: undo, a hairline divider, selection/lasso, another
hairline divider, eraser and ink tools, add-tool, a divider, panorama hand, then low-frequency page
clearing after a final divider. Dividers are one physical pixel on the tested tablet rather than
one density-independent pixel. Keep icons and selected frames compact and do not restore permanent
tool labels.

The Fluent palm icon after add-tool is a working panorama hand, not a shell. Selected on Android it
allows one-finger panning while preserving two-finger pinch; deselecting it restores two-finger-only
navigation. Choosing any drawing tool also exits panorama-hand mode. A ruler is intentionally not
shown until its behavior is implemented.

The dark canvas displays logical black ink as light ink, mirroring the readability behavior of a
dark note theme. A future persisted document must store the logical palette value and theme-map it
at render time rather than serializing the temporary display color.

The canvas has no permanent page-title/breadcrumb card and no permanent current-tool/zoom card.
The gray page pane already identifies the selected page, and tool details belong to the second-tap
flyout; duplicating either on the paper obstructs handwriting.

## Preserved input behavior

- Stylus input authors the selected pen or highlighter.
- The explicit eraser and hardware eraser erase complete intersecting strokes.
- The lasso consumes stylus motion without creating ink.
- One finger does not write or move the page; two fingers pan/zoom only when no stylus stroke is
  active.
- Prediction, undo, clear-with-confirmation, pressure erasing, viewport transforms, Xiaomi shortcut
  filtering, and wet/dry coordinate parity remain available.

## File and options surface

`文件` opens a full-screen Backstage-style surface rather than a small popup. Its default information
view lists every notebook and can switch the active notebook. New/open, backup, print, share, export,
and account entries are explicit shells until their underlying product layers exist.

`选项` stays at the bottom of the Backstage navigation. The working general-options panel owns the
global motion-prediction toggle and the global page-pane mode (`本层` or `递归`); neither preference is
shown as a permanent workspace control.

## Adaptive behavior

The workspace does not reserve a separate application-brand/title row or a bottom debug telemetry
bar. The drawing tabs and ribbon should hand the remaining vertical area directly to navigation and
the page canvas.

A persistent 36 dp near-black vertical rail sits beside a 196 dp black section pane, keeping the
combined section-navigation width at the earlier 232 dp. Its two controls are independent:

- the navigation icon hides or reveals only the black section pane, matching desktop OneNote;
- the Fluent `panel_left` icon hides or reveals only the 196 dp gray page pane.

The rail never disappears, so either pane can be restored after both are hidden. Independent pane
visibility allows a user to keep pages visible while reclaiming the section width, or return almost
the full width to handwriting without sacrificing a recovery control.
