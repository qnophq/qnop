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

import type { SxProps, Theme } from '@mui/material/styles';

/**
 * Hides content visually while keeping it in the accessibility tree (issue
 * #460) — for text a screen reader needs but the layout does not show, such as
 * the header of an actions column that is deliberately blank on screen.
 *
 * The clip-rect recipe rather than the obvious alternatives, because those
 * remove the element from the accessibility tree too and would defeat the
 * point: `display: none`, `visibility: hidden`, and `width/height: 0` without
 * clipping are all announced as absent. `white-space: nowrap` stops a
 * 1px-wide box from wrapping its text into a tall column that can still be
 * scrolled to.
 *
 * MUI ships an equivalent in newer `@mui/utils`, which this version does not
 * carry; if a future bump brings it, prefer theirs and delete this.
 */
export const visuallyHidden: SxProps<Theme> = {
  border: 0,
  clip: 'rect(0 0 0 0)',
  clipPath: 'inset(50%)',
  height: '1px',
  width: '1px',
  margin: '-1px',
  overflow: 'hidden',
  padding: 0,
  position: 'absolute',
  whiteSpace: 'nowrap',
};
