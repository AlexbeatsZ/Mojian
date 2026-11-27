# Material 3 Expressive experiment

Status: interactive proposal awaiting user review, 2026-11-26.
Branch: `codex/material-3-expressive`. Product mainline remains 0.7.44.

The user asked to explore a new native frontend following Google's design system,
then clarified that the preview must come before further builds. Do not package,
release or deploy this experiment until the user has reviewed the direction.

## Proposal

`docs/previews/mojian-material-expressive.html` is an interactive conversation
preview, not a running native product or a feature-completeness claim. It contains
invented notes, local page selection, temporary pen strokes/undo, theme and device
switches, the pen parameter popover and an official loading-indicator GIF. Some
commands only show an illustrative response; no real libraries or services are used.

- Desktop: navigation rail, notebook tree, page list and writing surface.
- Tablet: rail, page list and writing surface; notebook navigation opens as a drawer.
- Phone: writing surface with bottom navigation and notebook/page drawers.
- Use baseline Material dark/light color roles, tonal containers, full selection
  shapes, Roboto with Chinese system fallback and Material Symbols Rounded.
- Keep page content quiet and separate from chrome. Document ink colors and
  source images are not theme tokens. The shown science note is demonstration content.
- Tapping the active pen opens its parameters. Preserve the native save/loading,
  undo, manual conflict review and direct-input behavior during implementation.

The proposal changes the earlier OneNote-inspired visual specification in
workspace-ui.md only for this branch; all document/storage/input contracts remain.

## Official references

- https://m3.material.io/
- https://m3.material.io/styles/color/roles
- https://m3.material.io/styles/typography/type-scale-tokens
- https://m3.material.io/components/loading-indicator/overview
- https://github.com/material-components/material-components-android/blob/1.14.0/docs/getting-started.md
- https://github.com/material-components/material-components-android/blob/1.14.0/docs/components/LoadingIndicator.md
- https://github.com/material-components/material-components-android/blob/1.14.0/lib/java/com/google/android/material/color/res/values/tokens.xml
- https://github.com/google/material-design-icons

Android can use Google's Views library and LoadingIndicator directly. Windows has
no Google-provided WPF widget implementation; native templates must be reviewed
against the same tokens and interaction guidance. Do not call a WPF recreation an
official Google component. The preview does not certify full specification compliance.

## Continuation boundary

Native implementation was started before the preview-first clarification and
remains uncommitted in this checkout and OMEN's matching experimental branch.
Android initially compiled, but lint failed at 13 suspicious-indentation sites
and PassiveStatusView's AppCompat inheritance after adding Material 1.14.0.
Do not baseline or suppress these errors without reviewing their cause. Later
changes and new native integration tests have not yet been rebuilt. Windows
task-specific tests were stopped, so there is no final Windows validation claim.

After design review, reconcile the native implementation with the accepted
proposal, complete Android/Windows checks and review rendered native screens.
Only then create an experimental release. Do not auto-upgrade the user's stable
notes application as part of this design preview.
