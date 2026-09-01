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

/** One audited viewport (issue #461). */
export interface Breakpoint {
  readonly name: string;
  readonly width: number;
  readonly height: number;
}

/**
 * The width axis of the responsive audit matrix (issue #461): 320 is the
 * narrowest phone still in the field, 375 the common one, 768 a portrait
 * tablet, 1024 the floor of the full workspace experience, 1440 and 1920 the
 * desktop sizes reviewers actually work at.
 *
 * The heights are the companions of those widths on real devices, not round
 * numbers: a short viewport is its own failure mode (sticky bars eating the
 * content, panes with no room left), so measuring 320x1000 would quietly hide
 * what 320x640 shows.
 */
export const BREAKPOINTS: readonly Breakpoint[] = [
  { name: '320', width: 320, height: 640 },
  { name: '375', width: 375, height: 812 },
  { name: '768', width: 768, height: 1024 },
  { name: '1024', width: 1024, height: 768 },
  { name: '1440', width: 1440, height: 900 },
  { name: '1920', width: 1920, height: 1080 },
];

/**
 * The widths that carry screenshot baselines. Issue #461 asks for these four;
 * the other two are covered by the assertions, which need no images and so cost
 * nothing to run everywhere.
 */
export const VISUAL_WIDTHS: readonly string[] = ['320', '768', '1024', '1440'];
