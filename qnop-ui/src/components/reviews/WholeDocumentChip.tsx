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

import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { FileText } from 'lucide-react';

interface WholeDocumentChipProps {
  /** Shortens the label to "Whole doc" for tight rows. */
  compact?: boolean;
}

/**
 * The scope marker for a document-scoped annotation (issue #395): a quiet pill
 * that reads as "this remark is about the whole document", taking the slot a
 * located annotation uses for its page reference. Deliberately neutral — scope
 * is metadata, not a status — so it never competes with the status/priority
 * cues. One component so the card, list row, drawer and panel stay identical.
 */
export function WholeDocumentChip({ compact = false }: WholeDocumentChipProps) {
  const theme = useTheme();
  return (
    <Tooltip title="Applies to the whole document — not pinned to a passage">
      <Stack
        direction="row"
        spacing={0.5}
        data-testid="whole-document-chip"
        sx={{
          alignItems: 'center',
          flexShrink: 0,
          px: 0.75,
          py: 0.25,
          borderRadius: 99,
          bgcolor: theme.qnop.surface2,
          color: 'text.secondary',
        }}
      >
        <FileText size={11} aria-hidden />
        <Typography
          component="span"
          sx={{ fontSize: 11, fontWeight: 500, lineHeight: 1, whiteSpace: 'nowrap' }}
        >
          {compact ? 'Whole doc' : 'Whole document'}
        </Typography>
      </Stack>
    </Tooltip>
  );
}
