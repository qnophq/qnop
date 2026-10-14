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

import Box from '@mui/material/Box';
import { useTheme } from '@mui/material/styles';
import type { MailLocaleStatus } from './mailLocaleStatus';

/**
 * The status glyph, told apart by shape as well as colour: a filled brand dot (customised), a thin
 * ring (built-in) and a dashed amber ring (English stands in) — the one state that needs action,
 * and the only one in a warning colour. Decorative: the caller names the status in text or a label.
 */
export function LocaleStatusMark({
  status,
  size = 10,
}: {
  status: MailLocaleStatus;
  size?: number;
}) {
  const theme = useTheme();
  const amber = theme.qnop.badge.amber;
  const shape = {
    custom: { bgcolor: theme.qnop.brand.blue, border: `1.5px solid ${theme.qnop.brand.blue}` },
    shipped: { bgcolor: 'transparent', border: `1.5px solid ${theme.palette.text.secondary}` },
    fallback: {
      bgcolor: amber.bg,
      border: `1.5px dashed ${theme.palette.mode === 'dark' ? amber.fgDark : amber.fg}`,
    },
  }[status];
  return (
    <Box
      component="span"
      aria-hidden
      data-status={status}
      sx={{
        display: 'inline-block',
        flexShrink: 0,
        width: size,
        height: size,
        borderRadius: '50%',
        boxSizing: 'border-box',
        ...shape,
      }}
    />
  );
}
