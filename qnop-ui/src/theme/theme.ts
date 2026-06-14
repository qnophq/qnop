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

import { createTheme } from '@mui/material/styles';

/**
 * Deliberate, restrained palette for an enterprise review tool: a deep indigo
 * primary against neutral paper surfaces, with a burnt accent reserved for
 * semantic emphasis (open annotations, required action). Not the MUI default.
 */
export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: { main: '#34386b' },
    secondary: { main: '#c2410c' },
    background: { default: '#f3f4f8', paper: '#ffffff' },
    text: { primary: '#1c1d29', secondary: '#5b5d72' },
  },
  typography: {
    fontFamily: '"Inter", system-ui, -apple-system, "Segoe UI", sans-serif',
    h1: {
      fontSize: 'clamp(2rem, 1.4rem + 2.5vw, 3.25rem)',
      fontWeight: 700,
      letterSpacing: '-0.025em',
    },
    h6: { fontWeight: 600, letterSpacing: '-0.01em' },
    button: { textTransform: 'none', fontWeight: 600 },
  },
  shape: { borderRadius: 10 },
});
