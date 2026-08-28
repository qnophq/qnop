<!--
Copyright (c) 2026-present devtank42 GmbH
SPDX-License-Identifier: AGPL-3.0-only
-->

# Accessibility (WCAG 2.2 AA): findings and the regression net

Issue #460. This is the structural pass: what an automated checker can see in
the DOM, plus the pointer-only interactions walked by hand in the source. It is
honest about its limits — see the last section for what still needs a browser.

## What was checked, and how

**Automated, in CI.** `vitest-axe` (axe-core 4.12) runs over a rendered,
populated state of every page-level surface named in #460 and the review
components underneath them: login, dashboard, reviews list, new review,
document review page, version compare, tasks board and page, annotation panel,
comment thread, sidebar, profile, search, messages, my teams, audit, and the
admin pages (users, teams, settings, OIDC, scheduler). The shared configuration
lives in `qnop-ui/src/test/axe.ts`; one rule (`region`) is off globally because
a component rendered in isolation has no landmark around it, and one surface
(document review page) exempts `nested-interactive` for the finding tracked by
#549. Every new violation fails `pnpm test:run`, which CI runs on every PR.

The `jsx-a11y` recommended preset is active in ESLint with no rule disabled.

**By hand, in the source.** Every pointer handler in the review workspace
(`onPointerDown`/`onMouseDown`, drag, resize) was traced for a keyboard path.

## Findings

Each maps to *fixed*, *tracked* (another issue owns it) or *wontfix* with the
reason.

### 1. Unnamed column header in the reviews table — fixed

The trailing actions column had an empty `<th>`. axe: `empty-table-header`.
It now carries a visually hidden "Actions" (`theme/visuallyHidden.ts`).

### 2. Profile hover link without an accessible name — fixed

`UserHoverCard` renders a link around a decorative avatar (`alt=""`) and only
set `aria-label` when the profile name was known. With an unnamed participant
that left a focusable link with no name. axe: `link-name`. The link now always
has a name ("View profile" as the fallback).

### 3. Sidebar navigation: no landmark, invalid list — fixed

The rail's navigation was a plain `<div>`, and each entry was an `<a>` as a
direct child of `<ul>`. axe: `list`. It is now `<nav aria-label="Main
navigation">` with an `<li>` per entry.

### 4. Rescan spinner not behind `prefers-reduced-motion` — fixed

The one animation in the app without the guard (storage-consistency page). All
other `keyframes`/`animation` uses already carried it; verified by grep.

### 5. Text selection was pointer-only — fixed

`TextSpanLayer` had no keyboard path at all, so a keyboard-only user could not
start the core review loop (WCAG 2.1.1). The layer is now focusable: arrow
keys move a caret through the canonical text (Up/Down keep the column across
lines, Home/End jump to the line ends), Shift extends a range, Enter emits it
exactly like a pointer release, Escape clears. A visually hidden description
names the keys. Caret and range paint with the same marker bands as a drag.

### 6. Region (rubber-band) selection is pointer-only — tracked

`RegionSelectLayer` has no keyboard equivalent. With text selection now
keyboard-reachable (finding 5), a keyboard user can annotate any extracted
text; what they cannot do is mark an arbitrary rectangle (a figure, a scanned
page). A keyboard rectangle (arrows move, Shift+arrows size, Enter commits) is
a self-contained follow-up and is filed as such rather than bolted on here.

### 7. Expanded annotation card nests controls inside `role="button"` — tracked (#549)

axe: `nested-interactive`. #549 restructures the card; the page-level axe test
exempts exactly this rule until it lands.

### 8. Panel splitter and drawer resize handles — already compliant

`PanelResizer` and `ResizeHandle` are `role="separator"` with
`aria-valuemin/max/now`, `tabIndex=0`, arrow keys (plus Home/End on the
splitter) and a `focus-visible` ring. No change.

### 9. Dialogs, drawers, popovers — already compliant

All are MUI `Dialog`/`Drawer`/`Popover`, which trap focus and restore it to the
opener on close. Toasts are MUI `Alert` inside `Snackbar` (`role="alert"`), so
they are announced. Form fields are MUI `TextField` with `error`/`helperText`,
which wires `aria-invalid` and `aria-describedby`. No change.

### 10. PDF canvas content — wontfix here

Screen-reader access to the rendered page image is out of scope per #460; the
extracted text layer is what assistive tech gets today.

## What this cannot see — still a browser step

jsdom builds a DOM but never lays out or paints, and axe reports what it
cannot judge as *incomplete*, not as a violation. So none of the following is
covered by the net above, and none is claimed here:

- **Colour contrast (1.4.3)** in either theme. The dark palette was calibrated
  in #423; the light palette has not had that treatment.
- **Target size (2.5.8)** beyond the header controls fixed in #724.
- **Focus visibility (2.4.7/2.4.11)** — the rings exist in the source; that
  they render unobscured is a visual check.
- **A real screen-reader walkthrough** of the review loop.

These belong to the Playwright net #725 introduces (`@axe-core/playwright`
runs in a browser and does evaluate contrast); until then they are a manual
pass before a release, not something CI guarantees.
