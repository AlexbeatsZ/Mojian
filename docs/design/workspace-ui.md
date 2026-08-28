# Workspace Navigation and Drawing UI

Last verified: 2026-08-28

Read this document before changing notebook/section/page navigation, drawing-tool configuration, or
the dark workspace chrome.

## Product intent

Turn the handwriting lab into a usable tablet workspace whose hierarchy can later be shared with a
Windows client. The interaction language is inspired by the user's installed desktop OneNote, but
the product remains an independent clean-room application with its own recursively nestable section
model.

The notebook hierarchy, manual ordering, names, and active navigation persist in product-owned
preferences. Ink/text/tag layers remain prototype state, while inserted page objects and their
embedded assets persist in the product-owned private store. Full notebook import and
sync remain later product layers; their visible entry points may be shells, but drawing, hierarchy
selection, insertion, and export must be real.

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
- Render each expanded notebook's section rows immediately beneath that notebook row; never append
  the active notebook's section tree after later sibling notebooks.
- Selecting a notebook switches the active workspace and therefore the gray page pane and canvas
  header. Do not copy OneNote's repeated per-notebook `新建分区` row.
- Do not copy the desktop notebook picker footer entries `更多笔记本` or `快速笔记`. The prototype
  sample contains one ordinary notebook; the model may still support additional real notebooks.
- Recursively display sections nested inside sections with no fixed depth.
- Never display a page, even when pages and child sections share the same parent section.
- Expanding/collapsing a branch changes only section visibility in this pane.
- Selecting a section establishes the root for the gray page pane.
- The black-pane header has one `+` control, aligned with the gray pane's add-page control. It opens
  a compact menu with exactly `新建笔记本` and `新建分区`. A notebook is always created at library
  root and can never nest. A section is created beneath the focused section; focusing a notebook
  row or clicking black-pane blank space makes the notebook itself the focus, so the next section is
  created at that notebook's root.
- Do not render `笔记本`, `分区`, or `页面` as pane-header titles. The panes' content already conveys
  its type; keep only the black-pane add menu and gray-pane add-page action in compact headers.
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

### Long-press management and manual ordering

Section and page rows use one mutually exclusive long-press gesture:

- reaching Android's long-press threshold shows that row's context menu immediately, without
  waiting for release;
- after a short 180 ms drag-arm delay, moving beyond touch slop enters drag-and-drop ordering;
- after drag has started, releasing performs only the drop and must never also open the menu;
- movement before the drag-arm delay remains ordinary list scrolling and cancels the menu timer.

Dragging a section places it immediately before or after another visible section and adopts that
target's parent. Dragging a page places it before or after another visible page and adopts that
page's owning section. This makes both same-parent ordering and deliberate cross-parent moves
possible without inventing a second sort mode. Dragging and the current move/copy destination list
stay within the source notebook; cross-notebook hierarchy transfer remains later storage work.

The section menu contains `重命名`, `导出`, `删除`, `移动或复制`, `与其他分区合并`,
`复制指向分区的链接`, and `新建分区`. The page menu contains `重命名`, `删除`, `剪切`,
`复制`, `粘贴`, `复制指向页面的链接`, and `移动或复制`. These are real hierarchy/data
operations: deletion removes product-owned page data, copying includes embedded assets, section
export writes a scoped private package, and changes survive restart. Destructive operations require
confirmation; a section deletion explicitly includes its descendant sections and pages.

## Drawing-tool contract

Each toolbar item is a `ToolPreset`, not a global mode plus unrelated settings. Adding a tool creates
a new preset. Tool presets are add-only: neither the ribbon nor a tool flyout may expose a delete-tool
button, and persisted presets are never silently discarded.

Required working tools:

- fountain pen with logical black, blue, or red color plus independent width, pressure response,
  and ink-flow/speed response;
- highlighter with the same restricted black/blue/red palette, width, and opacity;
- whole-stroke eraser with its own 0.25–40 mm radius and the sole pressure-erase switch, threshold,
  and dwell settings. Any pen/highlighter may spring-load this eraser; lasso and panorama hand may not;
- pen lasso with per-preset `自由圈选` and `矩形` shapes. Either shape uses complete containment for
  stroke geometry, page tags, text boxes, and non-background inserted objects such as images. Mixed
  content shares one selection frame, can be deleted together, moves
  by dragging inside the frame, and scales from any corner handle.

The drawing ribbon is icon-only. A first tap on an unselected tool selects it without interruption;
tapping the already selected tool opens its own flyout. The flyout shows only parameters meaningful
to that tool. It does not contain delete-tool or delete-selection actions; a mixed content selection
is deleted by `Backspace` / `Delete` or its context surface. Pixel erasing remains a labeled shell;
it must not masquerade as a completed function.

Tool flyout dismissal is part of that same two-click contract. If a tool flyout is already open,
clicking its selected tool closes it, while clicking a different tool closes the old flyout and
selects the new tool without opening the new flyout. Only a later second click on that now-selected
tool may open its flyout. Windows must preserve the popup state observed at pointer-down; WPF's
outside-click dismissal must not clear `IsOpen` first and make the following `Click` look like a
second click.

Follow the desktop OneNote grouping rhythm: undo/redo, a hairline divider, lasso,
another hairline divider, eraser and ink tools, add-tool, a divider, stylus panorama hand, a divider,
insert space and background format (rule lines/grid styles), then low-frequency page clearing
after a final divider. Dividers are one physical pixel on the tested tablet rather than one
density-independent pixel. Keep icons and selected frames compact and do not restore permanent
tool labels.

There is no cursor/text tool. Finger and primary-mouse input always use the direct-content contract
in `input-gestures.md`: an ordinary click selects content, a later ordinary click on selected text
enters editing, and a blank click creates a temporary caret without immediately opening Android's
keyboard. The first valid character commits the text box to page content; an empty temporary box is
discarded when focus or operation changes and does not create an undo record. This behavior remains
independent from the selected stylus tool.

The Fluent palm icon after add-tool is a working stylus panorama hand, not a shell. It changes only
stylus contact into panning, so the pen cannot leave ink in this mode. Finger and mouse behavior do
not inherit this state: one direct pointer still selects/edits/marquees content and two fingers always
pan/zoom. Choosing a drawing tool exits the stylus panorama state. Its second-tap flyout persists
25–300% movement sensitivity. Middle-button navigation spring-loads the same visible Hand state and
restores the prior drawing/insert-space state on release instead of running a hidden duplicate mode.

The insert space and background format group directly to the right of panorama hand provides:
- an insert-space exception supporting finger or stylus drag displacement and downward content
  shifting. Stylus and mouse hover show the dashed insertion preview, but mouse-left keeps direct-content routing;
- a background format tool that opens a flyout menu offering ruled lines (narrow, standard with margin line, wide, clean), grid styles (standard, dense, wide), dot matrix styles (standard, dense, wide), and blank page formatting, instantly updating and persisting on the underlying canvas.

The dark canvas displays logical black ink as light ink, mirroring the readability behavior of a
dark note theme. A future persisted document must store the logical palette value and theme-map it
at render time rather than serializing the temporary display color.

The canvas has no permanent breadcrumb card and no permanent current-tool/zoom card. The optional
page title is document-surface content near the paper's top edge, not floating chrome, and the View
ribbon can hide it. The gray page pane already identifies the selected page, while tool details
belong to the second-tap flyout.

## View ribbon and page surface

The `视图` tab is working and follows the same compact, icon-only ribbon language as drawing. It
does not add explanatory captions; only functional values such as the current zoom percentage and
paper-size menu names are visible. Accessibility descriptions and hover tooltips remain present.

- `整页视图` and `普通视图` drive the existing full-page chrome state. Normal view exposes search
  and enter-full-page at the paper's top-right; full-page view exposes the floating-toolbar pen,
  search, and exit-full-page controls.
- Zoom uses the shared `ViewportState`: the preset menu contains 50/75/100/125/150/200/400/800%,
  with discrete zoom in/out, 100% reset, and page-width fit. A lock icon directly to the left of
  the percentage disables only finger-pinch scale changes while continuing to allow two-finger
  translation; presets and ribbon buttons still change the locked scale, and the lock state
  persists. The accepted range remains 50–800%, and every scale change preserves wet/dry
  document-coordinate parity.
- The View-ribbon baseline icon directly reuses the drawing ribbon's background-format menu instead
  of creating a second grid/rule implementation. The selected baseline is stored per page.
- Page color, paper size, baseline, page-title visibility, and light-versus-dark page background are
  stored per page. Paper options are only unbounded and A4. A4 is a paginated document policy, not a
  fixed single sheet: it renders consecutive A4 sheets with visible gaps and appends sheets before
  edited content or the downward-moving viewport reaches the final sheet's bottom. It must therefore
  remain continuously browsable like Word even before new content is placed. Automatic editing and
  browsing never shrink the sheet count. Rule, grid, and dot backgrounds are clipped separately to
  every sheet so the workspace-colored inter-sheet gap remains visually empty.
- Switching the page between light and dark backgrounds remaps logical black ink and default text
  editor colors for readability without changing blue/red ink.
- Finite paper is a surface, not a clipping mask. Changing paper size never hides text, ink, tags,
  or inserted objects outside the new edge; those objects stay visible and movable over the
  `#0A0A0A` workspace until the user moves them back onto paper.

The page pane's `+` opens a compact creation dialog with unbounded/A4 template cards, the shared
baseline selector, and page-color swatches. It contains no explanatory paragraph. Only a successful
creation becomes the next dialog's default configuration; cancelling leaves the remembered template
unchanged.

The same dialog has a quiet two-item tab strip at its top: `页` keeps the normal-page template
controls, while `PDF` exposes the system PDF picker. PDF import creates one real notebook page for
every source PDF page, in source order and at that page's original aspect and physical point size.
PDF pages show a small muted `PDF` suffix at the far right of their gray-pane row; the canvas itself
does not add a PDF badge over the document.

## Split view

Split view is an independent View-ribbon state, not a variant of full-page mode. It divides the
canvas into left and right page slots with a one-physical-pixel center rule inside a narrow gray
gutter. A restrained lavender focus border pulses for 500 ms when focus changes, then disappears;
there is no permanent accent frame, pane title, or instruction.

- Each slot retains its own notebook, focused section root, owning section, page, viewport, and
  session ink/tag/text state.
- The black and gray sidebars always reflect the focused slot. Selecting a notebook, section, or
  page changes only that slot, so the other slot keeps its prior location.
- Tapping the other page focuses it and immediately switches both sidebars to its saved context.
- Full-page mode may be entered while split view is active. It hides normal chrome but preserves
  both slots and their focus; leaving full-page mode restores the same split state.
- Leaving split view always keeps the right slot as the single visible page, regardless of which
  slot was focused immediately before exit.

## Start ribbon and page tags

The desktop-style `主页` tab is named `开始`. It contains working clipboard, character-format,
paragraph-format, tag-search, and style controls. Paste is a single direct action with no split or
drop-down affordance. Font families use Android's available sans, serif, and monospace families;
do not claim that desktop Calibri is installed on the tablet.

The tag menu contains exactly `重要` and `问题`. Choosing one arms a one-shot placement mode; the
next tap on the paper places a minimal pure-red tag in document coordinates. The toolbar `标记` and
`查找标记` icons use the same red with no decorative frame. Tag search lists placed tags, recenters
the page on the chosen result, and briefly identifies it. A tag can also be selected by tapping it,
included in a lasso selection, moved or scaled with that selection, and removed by either the
whole-stroke eraser or selection deletion. Tags are session-only until the document storage model
is implemented.

## Insert ribbon

The working Insert ribbon contains table, embedded file attachment, PDF printout, spreadsheet,
multi-image, sticker, and note-link commands. Image and sticker remain adjacent. Screen clipping,
page template, and the Draw ribbon's insert-space command do not appear here. Detailed object,
asset, filter, and export behavior is owned by `docs/design/insert-and-export.md`.

## Preserved input behavior

- Stylus input exclusively follows the selected stylus tool: ink/highlighter/eraser, containment
  lasso, or stylus panorama hand. It does not change finger or mouse routing.
- The explicit eraser and hardware eraser erase complete intersecting strokes and page tags.
- Finger and primary-mouse drags move/resize only when they begin on selected content or a selected
  handle. Every other drag starts a rectangular containment marquee, including a drag that begins
  over unselected content. All content types share one dotted eight-handle frame.
- One finger never writes or pans the page. Two fingers always preempt the pending one-finger action
  and pan/zoom, even when the first finger began over a text editor or inserted object.
- Prediction, undo, clear-with-confirmation, eraser-owned pressure erasing, viewport transforms, Xiaomi shortcut
  filtering, and wet/dry coordinate parity remain available.

## File and options surface

`文件` opens a full-screen Backstage-style surface rather than a small popup. Its default information
view lists every notebook and can switch the active notebook. New/open, backup, print, share, and
account entries remain explicit shells until their underlying product layers exist. Export is
working and offers layered PDF for the current page plus the private notebook package.

`选项` stays at the bottom of the Backstage navigation and opens as a full-screen settings surface,
not a floating dialog. The working general-options panel owns the
global motion-prediction toggle and the global page-pane mode (`本层` or `递归`); neither preference is
shown as a permanent workspace control.

The shortcut panel is a peer of `常规` inside that same Backstage/options shell: it keeps the same
160 dp navigation column, heading rhythm, dividers, row density, and value buttons. The device
section is named `触控笔`; product-facing text does not expose Xiaomi implementation names or
platform qualifiers, and the single/double/hold rows do not carry redundant descriptive captions.
The exact input-device name remains an internal filtering boundary only.

Ribbon drop-downs and sidebar/object context menus are app-owned command surfaces rather than OEM
`PopupMenu` widgets. They use the workspace's near-black panel, thin gray border, compact 40 dp
rows, Fluent icon rail, separators, disabled state, and lavender selection. Nested commands open a
second app-owned surface to the right (or left when space is constrained); context commands always
show an icon. Sidebar context menus stay near 244 dp wide and image menus near 236 dp rather than
stretching to dialog-like widths.

The full-page quick toolbar has its own full-screen settings subpage. Every implemented Start
command and every current drawing command/preset appears in one catalog and can be added or
removed. Added commands are appended in selection order. Its floating surface measures to the
actual configured buttons instead of reserving a long fixed strip; it becomes horizontally
scrollable only when that content reaches the available page width. The ordered command IDs are stored in local
workspace preferences; unsupported stale IDs are ignored when rendering.

## Adaptive behavior

The workspace does not reserve a separate application-brand/title row or a bottom debug telemetry
bar. The drawing tabs and ribbon should hand the remaining vertical area directly to navigation and
the page canvas.

A persistent 32 dp near-black vertical rail sits beside a 196 dp black section pane. Its two
controls are independently centered and show the same compact selected frame as the drawing ribbon
while their pane is expanded:

- the navigation icon hides or reveals only the black section pane, matching desktop OneNote;
- the Fluent `panel_left` icon hides or reveals only the 196 dp gray page pane.

The rail never disappears in normal view, so either pane can be restored after both are hidden. Independent pane
visibility allows a user to keep pages visible while reclaiming the section width, or return almost
the full width to handwriting without sacrificing a recovery control.

The paper's top-right controls are context-sensitive. Normal view shows search and enter-full-page;
the regular drawing ribbon cannot be hidden there. Full-page view hides tabs, the regular ribbon,
rail, and both panes, then leaves the two pane controls as independent floating buttons at the
original left edge. They reopen the same black/gray panes without restoring the black rail. Full
page also shows the configurable quick toolbar plus pen, search, and exit-full-page controls. Only
the full-page pen control toggles that toolbar. Search remains directly to the right of that pen
control.
