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

/** The minimum pointer/touch target the responsive audit asks for (#461). */
export const TOUCH_TARGET_PX = 44;

/**
 * Grows a control's hit area to at least 44×44 px without touching its visual
 * size (issue #724).
 *
 * <p>A transparent `::before` overlay is centred on the element and sized to
 * `max(100%, 44px)` on each axis, so a 28 px icon button keeps its 28 px ring
 * and ripple while the box the finger (or the trackpad cursor) has to hit is
 * 44 px. Neighbouring overlays may overlap; the topmost wins, which is fine
 * for controls that sit 12 px apart. Requires a positioned host — MUI's
 * `ButtonBase` already is.
 */
export const touchTargetSx: SxProps<Theme> = {
  '&::before': {
    content: '""',
    position: 'absolute',
    top: '50%',
    left: '50%',
    transform: 'translate(-50%, -50%)',
    width: `max(100%, ${TOUCH_TARGET_PX}px)`,
    height: `max(100%, ${TOUCH_TARGET_PX}px)`,
  },
};
