<!--
Copyright (c) 2026-present devtank42 GmbH
SPDX-License-Identifier: AGPL-3.0-only
-->

# Responsive behaviour: findings and support tiers

Measured against a running instance (Vite dev server + backend, seeded data), not
estimated from the source. Every number below came from
`getBoundingClientRect()` and `scrollWidth`/`clientWidth` in the live DOM.

## What was measured, and what was not

**Measured.** Twelve routes at 320 px — dashboard, reviews list, messages, my
teams, profile, audit, and the admin surfaces (users, teams, settings,
scheduler, mail templates, storage consistency) — plus the review workspace at
320 and 768 px.

**Not measured, and therefore not claimed.** 375/1024/1440/1920 px across every
surface; focus mode; the tasks board; version compare; the PDF viewer toolbar.
The last one matters: the seeded review used for this pass is a text document,
so no PDF canvas rendered and the toolbar density named in issue #461 as the
prime suspect is still unverified. It needs a PDF review to say anything honest
about it.

This document is therefore a first pass that settles the biggest question —
whether anything is structurally broken — not the complete matrix.

## Findings

### 1. No horizontal page overflow anywhere measured — the headline criterion holds

`document.documentElement.scrollWidth - clientWidth` was **0** on all twelve
routes at 320 px and on the review workspace at 320 and 768 px. Nothing pushes
the page sideways.

### 2. Wide tables scroll in their own containers, as they should

The suspicion in #461 that tables break the page does not hold. At 320 px the
tables are far wider than the viewport — reviews 1014 px, audit 947 px, mail
templates 849 px, users 785 px, teams 563 px — and every one of them sits inside
an ancestor with `overflow-x: auto`. This is the behaviour the issue asks for,
already built.

### 3. A primary action sat outside the viewport at 768 px — fixed (#723)

On the review workspace at 768 px, **“New version” spanned x = 692…798** against
a 768 px viewport: 30 px past the edge, reachable only through an action bar
that scrolled without saying so.

The cause was a chain, not the bar: the bar itself always had `flexWrap`, but
`PageHeader`'s action slot is `flexShrink: 0` (so long titles cannot crush the
buttons), which also means the slot never imposes a width the wrap could fire
at — and the shell's `overflow: auto` below `md` swallowed the resulting
horizontal scroll instead of surfacing it.

Fixed by stacking the header below `md` (title above, actions in full width
underneath), so the wrap that was always there finally has an edge to wrap at.
Re-measured live after the change: the button sits at x = 36…142, and no hidden
horizontal scroller remains on the route.

**A consequence worth keeping in mind:** the shell's `overflow: auto` masks
body-level overflow on sub-`md` viewports. The audit's “no body overflow”
numbers are partly _because_ that container swallows; the regression assertion
(#725) must therefore check the shell content element as well as
`document.body`, or it will pass while a surface silently scrolls.

### 4. Header controls are 28×28 px

“Toggle menu”, “Switch to dark mode” and “Notifications” measure 28×28 px on
every route — 64 % of the 44 px touch target the issue asks for, and they are the
three most important controls on a phone. “Dismiss this notice” is 25×25 px.

Inside content, many more controls fall under 44 px (25 on the review workspace
at 768 px, 90+ on admin tables at 320 px), but most of those are table-row
actions where density is the point. The header is not: it is chrome, present on
every screen, and the first thing a thumb reaches for.

Fixed in #724 by growing the hit area, not the icon: `touchTargetSx`
(`src/theme/touchTarget.ts`) lays a transparent `::before` overlay of
`max(100%, 44px)` on each axis over the control, so the header keeps its 28 px
visual weight while the box a finger or cursor has to hit is 44 px. Applied to
the three header controls and the banner dismiss; reusable for any other
chrome-level control that falls under the target.

### 5. The viewer toolbar's trailing group does not wrap — the prime suspect, measured (#772)

The first thing the regression net (#725) found once it checked `main` and not
only the document: on the review workspace the toolbar's trailing control group
— tool toggle, zoom, Panel/Focus switch, page navigation — is one 412 px row
with no wrap of its own. At **320 and 375 px** the workspace scrolls `main` to
441 px, in split view and in focus mode. The page body stays at 0 overflow
throughout, which is finding 3's masking effect at work: the audit's first pass
could not have seen this.

Tracked in #772; the two cases are marked expected failures in the net until
the fix lands.

### 6. KPI card rows do not wrap on phones (#773)

Two surfaces lay their KPI cards out as one row that never breaks: the dashboard
scrolls `main` to 415 px at 320 **and** 375 (cards of 169 px, the second at
x = 174…343), and `/admin/storage-consistency` to 348 px at 320 (cards of
166 px). The audit's first pass measured the dashboard before its cards had
loaded — the numbers arrive after the query resolves and no spinner marks the
gap — which is why the net waits for the layout to stop moving before it reads
a width. Tracked in #773; expected failures in the net until fixed.

### 7. The review head's action row is wider than the content at 1024 px (#774)

On every review route at 1024 × 768 — the `md`-and-up side-by-side header, the
sidebar open, 764 px left for content — the head's action row measures 784 px
and `main` scrolls to 824. This is finding 3 one breakpoint up: the stacking
fix ends at `md`, but with the sidebar open the content is narrower than the
row well above it. Tracked in #774; expected failure in the net until fixed.

### 8. The review title collapses to "S…" at 1440 px (#775)

Seen in the net's screenshot baselines rather than by an assertion: on the
workspace at 1440 × 900 the `h1` gets 45 px of a 286 px slot and shows one
letter and an ellipsis, while the head's middle segment and the 784 px action
row keep their width. 1920 px leaves 173 px — still an ellipsis for most titles.
Not an overflow, so the net cannot flag it; tracked in #775.

## Support tiers — proposed, pending the rest of the audit

Derived from what was measured. The 375 px line in particular is a
**hypothesis** until the review workspace is measured there with a PDF.

| Tier           | Width       | What is promised                                              |
| -------------- | ----------- | ------------------------------------------------------------- |
| Full           | ≥ 1024 px   | Viewer and panel side by side, compare, tasks board           |
| Workable       | 768–1024 px | Everything except simultaneous viewer + panel                 |
| Read and reply | 375–768 px  | Read a review, comment, decide — **no annotation creation**   |
| No breakage    | 320 px      | Nothing overflows, everything reachable; comfort not promised |

The third row is the deliberate one. Creating an annotation needs text or region
selection on a PDF.js canvas; on a touch phone that is its own project, and
shipping a half-working version of it would produce a feature that frustrates
rather than one that is honestly absent.

## The regression net (#725)

`qnop-ui/e2e/` is a Playwright suite that runs in CI inside the smoke job,
against the stack the smoke script has just built, seeded and uploaded a PDF
review into. It drives a production build of the working tree served by
`vite preview`, with the API proxied to the smoke stack (`QNOP_API_URL`) — the
same bundle a deployment ships, and none of the dev server's per-route cold
transforms, which cost more than a test's timeout inside the Playwright image.

**What it asserts.**

- `responsive.spec.ts`: on sixteen surfaces — dashboard, reviews list, the
  review workspace (split and focus mode), tasks board, version compare,
  messages, my teams, profile, audit and six admin pages — plus the login page,
  at all six audit widths, _nothing scrolls sideways_. The check reads
  `scrollWidth > clientWidth` on `documentElement`, `body` **and** the shell's
  `main` container, because below `md` that container has `overflow: auto`
  (finding 3) and would otherwise swallow the very overflow the check exists
  for. The three header controls are also measured against the 44 px target at
  every width (finding 4), including the `::before` overlay that grows them.
- `visual.spec.ts`: screenshot baselines of the dashboard, reviews list, review
  workspace, tasks board and login at 320/768/1024/1440, light theme. The theme
  changes colours, not boxes, so one theme carries the layout signal. The
  browser clock is frozen one hour after the smoke review was created — not at
  a calendar date, because the review is re-created by every smoke run — so the
  hour-of-day greeting and every relative time render the same on every run.

**Running it locally.** Bring the smoke stack up and run the smoke script once
— the real one, `scripts/smoke-test.sh`, because the screenshots are of its
data: the users it seeds, the review it uploads, the due date it clears, the
fixtures it adds. A hand-seeded backend renders a different page. Then from
`qnop-ui/`: `pnpm test:e2e` for a run on the host Chromium (it builds the
bundle first), or `scripts/e2e-docker.sh` for the run CI does. Point `QNOP_API_URL` at the
backend if it is not on `localhost:8080`.

**Where the screenshots come from.** Inside the official Playwright image
(`scripts/e2e-docker.sh`), in CI and on a developer machine alike. A host
browser renders the same page a few percent differently in antialiasing alone
— the first CI run proved it on all twenty baselines — so the image is the one
environment the pixels are compared in. The host run is still worth having for
the overflow and touch-target assertions, which need no images.

**Updating a baseline.** A screenshot that changed on purpose is updated with
`pnpm test:e2e:update` (the docker run with `--update-snapshots=all`) and
committed with the change that moved it; the PR diff then shows the
before/after image, which is the review. A failing screenshot in CI leaves its
actual/expected/diff triple in the `playwright-report` artefact. The
comparison allows a 0.5 % pixel difference; a layout regression moves whole
boxes and sits far above that.

**Two limits the run has to live with.** The login endpoint allows ten
attempts a minute per IP and the refresh endpoint thirty (ADR-0027). The suite
signs in once per worker and resolves the review once per run; but every page
load refreshes the access token, and a hundred pages from one address in three
minutes is more than thirty. The smoke stack therefore raises the refresh limit
(`QNOP_AUTH_RATE_LIMIT_REFRESH_MAX_ATTEMPTS`, `docker-compose.smoke.yml`) —
a test deployment; production keeps its default. A run against a backend with
the default limit fails at random surfaces with a page that never renders.

**Known gap.** The seeded review is the smoke PDF, a one-page text fixture, so
the viewer toolbar is exercised but the toolbar-density finding for a dense,
multi-page document is still unverified — the same gap the audit named.

## What follows

- Verify the viewer toolbar against a richer PDF review at 375/768 px
- Complete the matrix at 375/1024/1440/1920 by hand where the net cannot see
  (menus, popovers, drag on touch)
- Fix finding 4 (#724); finding 3 is fixed (#723)
