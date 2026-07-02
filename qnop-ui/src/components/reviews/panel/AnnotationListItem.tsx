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

import ButtonBase from '@mui/material/ButtonBase';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { MessageSquare } from 'lucide-react';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import type { BadgeTone } from '../../admin/ToneBadge';
import { ToneBadge } from '../../admin/ToneBadge';
import { PlacementStatusChip } from './PlacementStatusChip';

const STATUS_CUES: Record<AnnotationStatus, { tone: BadgeTone; label: string }> = {
  [AnnotationStatus.Open]: { tone: 'blue', label: 'Open' },
  [AnnotationStatus.Accepted]: { tone: 'green', label: 'Accepted' },
  [AnnotationStatus.Rejected]: { tone: 'neutral', label: 'Rejected' },
};

interface AnnotationListItemProps {
  annotation: AnnotationView;
  active: boolean;
  onClick: () => void;
}

/**
 * One annotation in the panel: its lifecycle status (owner's decision,
 * ADR-0011), the placement cue for the viewed version (ADR-0009), and the mark
 * itself — the quoted text, or the page for a pure region annotation.
 */
export function AnnotationListItem({ annotation, active, onClick }: AnnotationListItemProps) {
  const theme = useTheme();
  const quote = annotation.anchor?.textQuote?.quote;
  const region = annotation.anchor?.region;
  const statusCue = STATUS_CUES[annotation.status];

  return (
    <ButtonBase
      onClick={onClick}
      aria-expanded={active}
      data-testid={`annotation-item-${annotation.id}`}
      sx={{
        display: 'block',
        width: '100%',
        textAlign: 'left',
        borderRadius: 1.5,
        border: '1px solid',
        borderColor: active ? theme.qnop.brand.blue : theme.palette.divider,
        bgcolor: active ? alpha(theme.qnop.brand.blue, 0.06) : 'transparent',
        px: 1.5,
        py: 1.25,
        transition: 'border-color 120ms ease, background-color 120ms ease',
        '&:hover': { borderColor: theme.qnop.brand.blue },
        '&:focus-visible': { boxShadow: theme.qnop.focusRing },
      }}
    >
      <Stack spacing={0.75}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
          <ToneBadge tone={statusCue.tone} label={statusCue.label} />
          <PlacementStatusChip status={annotation.placementStatus} />
          <Stack
            direction="row"
            spacing={0.5}
            sx={{ alignItems: 'center', ml: 'auto', color: 'text.secondary' }}
          >
            <MessageSquare size={13} aria-hidden />
            <Typography variant="caption" aria-label={`${annotation.commentCount} comments`}>
              {annotation.commentCount}
            </Typography>
          </Stack>
        </Stack>
        {quote ? (
          <Typography
            variant="body2"
            sx={{
              fontStyle: 'italic',
              color: 'text.secondary',
              display: '-webkit-box',
              WebkitLineClamp: 2,
              WebkitBoxOrient: 'vertical',
              overflow: 'hidden',
            }}
          >
            “{quote}”
          </Typography>
        ) : (
          <Typography variant="body2" color="text.secondary">
            {region ? `Region on page ${region.surfaceIndex + 1}` : 'No placement on this version'}
          </Typography>
        )}
      </Stack>
    </ButtonBase>
  );
}
