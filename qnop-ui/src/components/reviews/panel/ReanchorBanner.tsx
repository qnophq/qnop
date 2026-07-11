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

import { useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { Anchor, X } from 'lucide-react';
import type { AnnotationView } from '../../../api/generated';
import { reanchorSummary } from './panelFilters';

function dismissKey(documentId: string, versionNumber: number) {
  return `qnop-reanchor-dismissed-${documentId}-v${versionNumber}`;
}

interface ReanchorBannerProps {
  annotations: AnnotationView[];
  versionNumber: number;
  /** Sets the panel's placement facet to "needs attention". */
  onReview: () => void;
}

/**
 * The "review re-anchoring changes" cue (ADR-0009, issue #326): when the
 * viewed version carries MOVED/ORPHANED placements, an amber strip above the
 * panel says so in one sentence and filters the list on click. Dismissable per
 * document version (device-local) — and it retires by itself once every
 * outcome is handled.
 */
export function ReanchorBanner({ annotations, versionNumber, onReview }: ReanchorBannerProps) {
  const theme = useTheme();
  const documentId = annotations[0]?.documentId;
  const [dismissed, setDismissed] = useState<boolean>(() => {
    try {
      return Boolean(
        documentId && localStorage.getItem(dismissKey(documentId, versionNumber)) === '1',
      );
    } catch {
      return false;
    }
  });

  const summary = reanchorSummary(annotations);
  if (summary.total === 0 || dismissed || !documentId) return null;

  const parts = [
    summary.moved > 0 ? `${summary.moved} moved` : null,
    summary.orphaned > 0 ? `${summary.orphaned} orphaned` : null,
  ].filter(Boolean);

  const dismiss = () => {
    setDismissed(true);
    try {
      localStorage.setItem(dismissKey(documentId, versionNumber), '1');
    } catch {
      // best-effort persistence
    }
  };

  return (
    <Stack
      direction="row"
      spacing={1}
      data-testid="reanchor-banner"
      sx={{
        alignItems: 'center',
        px: 1.25,
        py: 0.75,
        borderRadius: '8px',
        border: '1px solid',
        borderColor: alpha(theme.palette.warning.main, 0.4),
        bgcolor: alpha(theme.palette.warning.main, 0.08),
      }}
    >
      <Box aria-hidden sx={{ color: theme.palette.warning.main, display: 'flex', flexShrink: 0 }}>
        <Anchor size={14} />
      </Box>
      <Typography variant="body2" sx={{ flex: 1, minWidth: 0 }}>
        Re-anchoring on v{versionNumber}: {parts.join(', ')}.
      </Typography>
      <Button size="small" variant="text" onClick={onReview} sx={{ flexShrink: 0 }}>
        Review
      </Button>
      <IconButton size="small" aria-label="Dismiss re-anchoring notice" onClick={dismiss}>
        <X size={13} />
      </IconButton>
    </Stack>
  );
}
