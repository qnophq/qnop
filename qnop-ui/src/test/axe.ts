/*
 * Copyright (c) 2026-present devtank42 GmbH
 *
 * This file is part of qnop (Qualified Notes on Papers).
 *
 * qnop is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * qnop is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with qnop. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

import { axe } from 'vitest-axe';

type AxeOptions = NonNullable<Parameters<typeof axe>[1]>;

/**
 * Shared axe configuration for the component-level accessibility net (issue
 * #460). Previously each auth test carried its own copy of these options
 * (issue #352); one definition keeps the surfaces comparable and gives a
 * single place to record *why* a rule is off.
 *
 * <h2>What this can and cannot catch</h2>
 *
 * These checks run in jsdom, which parses and builds a DOM but never lays out
 * or paints. Everything that needs geometry or computed colour is therefore
 * out of reach here, and axe says so itself: it returns `color-contrast` as
 * **incomplete**, not as a violation. Verified — text at #eee on #fff passes
 * this harness untouched. The same goes for target size (2.5.8) and anything
 * depending on visible focus rings.
 *
 * So this net catches the structural half of WCAG: names, roles, labels, ARIA
 * validity, heading order, duplicated ids, form-control association. Contrast
 * and the pointer/vision criteria need a real browser, which the repository
 * does not have today (no Playwright); until it does, they stay a manual step.
 */
export const AXE_OPTIONS = {
  rules: {
    // `region` requires every node to sit inside a landmark. A component
    // rendered in isolation has no page around it, so this fires on the
    // harness rather than on the component. Landmark structure is a page-level
    // property and is asserted where a page is rendered whole.
    region: { enabled: false },
  },
} as const;

/**
 * Runs axe over a rendered container with the shared configuration. Per-call
 * `options` merge on top of it, so a surface can switch off a rule for a
 * known, tracked finding without touching the shared baseline — always say
 * which issue tracks it.
 *
 * Usage mirrors the existing auth tests:
 * `expect(await checkA11y(container)).toHaveNoViolations();`
 */
export function checkA11y(container: Element, options: AxeOptions = {}) {
  return axe(container, {
    ...AXE_OPTIONS,
    ...options,
    rules: { ...AXE_OPTIONS.rules, ...options.rules },
  });
}
