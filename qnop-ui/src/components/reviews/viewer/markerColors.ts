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

import type { PaletteMode } from '@mui/material/styles';

/**
 * Text-marker colours shared by the live selection (TextSpanLayer) and the
 * pending-anchor preview (HighlightLayer), so drawing a mark and its preview
 * look identical. Translucent so the page's own glyphs stay readable — the
 * PDF pixels are white in both themes. Light mode is the classic highlighter
 * yellow; dark mode uses the softer brand amber to match the muted dark UI.
 */
const SELECTION_BG_LIGHT = 'rgba(255, 224, 0, 0.45)';
const SELECTION_BG_DARK = 'rgba(245, 184, 61, 0.5)';

export function selectionMarkerColor(mode: PaletteMode): string {
  return mode === 'dark' ? SELECTION_BG_DARK : SELECTION_BG_LIGHT;
}
