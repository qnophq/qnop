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

### 3. A primary action sits outside the viewport at 768 px — the real finding

On the review workspace at 768 px, **“New version” spans x = 692…798** against a
768 px viewport: 30 px past the edge. It is not lost — its action bar scrolls
horizontally (`scrollWidth` 798 vs `clientWidth` 768) — but nothing indicates
that, so on a portrait tablet the control looks cut off rather than scrollable.

That is the gap worth closing: not overflow, but an affordance that hides a
primary action behind an invisible gesture.

### 4. Header controls are 28×28 px

“Toggle menu”, “Switch to dark mode” and “Notifications” measure 28×28 px on
every route — 64 % of the 44 px touch target the issue asks for, and they are the
three most important controls on a phone. “Dismiss this notice” is 25×25 px.

Inside content, many more controls fall under 44 px (25 on the review workspace
at 768 px, 90+ on admin tables at 320 px), but most of those are table-row
actions where density is the point. The header is not: it is chrome, present on
every screen, and the first thing a thumb reaches for.

## Support tiers — proposed, pending the rest of the audit

Derived from what was measured. The 375 px line in particular is a
**hypothesis** until the review workspace is measured there with a PDF.

| Tier | Width | What is promised |
|---|---|---|
| Full | ≥ 1024 px | Viewer and panel side by side, compare, tasks board |
| Workable | 768–1024 px | Everything except simultaneous viewer + panel |
| Read and reply | 375–768 px | Read a review, comment, decide — **no annotation creation** |
| No breakage | 320 px | Nothing overflows, everything reachable; comfort not promised |

The third row is the deliberate one. Creating an annotation needs text or region
selection on a PDF.js canvas; on a touch phone that is its own project, and
shipping a half-working version of it would produce a feature that frustrates
rather than one that is honestly absent.

## What follows

- Verify the viewer toolbar against a PDF review at 375/768 px
- Complete the matrix at 375/1024/1440/1920
- Fix the two findings above
- A Playwright regression net — the project has no E2E framework today, so that
  is infrastructure work rather than responsive work
