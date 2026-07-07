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
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';

/**
 * The official Markdown mark (CommonMark's rounded "M▾"), inlined as an SVG so
 * it self-hosts and inherits the current text colour — the recognisable badge
 * that a field speaks Markdown.
 */
function MarkdownMark() {
  return (
    <Box
      component="svg"
      viewBox="0 0 208 128"
      aria-hidden
      sx={{ width: 18, height: 'auto', display: 'block', flexShrink: 0 }}
    >
      <rect
        x="5"
        y="5"
        width="198"
        height="118"
        rx="10"
        ry="10"
        fill="none"
        stroke="currentColor"
        strokeWidth="10"
      />
      <path
        fill="currentColor"
        d="M30 98V30h20l20 25 20-25h20v68H90V59L70 84 50 59v39zm125 0l-30-33h20V30h20v35h20z"
      />
    </Box>
  );
}

/**
 * The quiet "Markdown supported" affordance under a composer (issue #427): the
 * Markdown mark plus a small label, with the syntax essentials in its tooltip.
 * Deliberately minimal — no toolbar or live preview in v1; it should never
 * compete with the send/create action beside it.
 */
export function MarkdownHint() {
  return (
    <Tooltip title="Markdown: **bold**, _italic_, `code`, lists, > quote, [link](url)">
      <Stack
        direction="row"
        spacing={0.625}
        data-testid="markdown-hint"
        sx={{ alignItems: 'center', color: 'text.disabled', userSelect: 'none' }}
      >
        <MarkdownMark />
        <Typography component="span" variant="caption" sx={{ fontSize: 11 }}>
          Markdown supported
        </Typography>
      </Stack>
    </Tooltip>
  );
}
