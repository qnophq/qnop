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
import ButtonBase from '@mui/material/ButtonBase';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, keyframes, useTheme } from '@mui/material/styles';
import { MessageSquare } from 'lucide-react';
import type { AnnotationView } from '../../../api/generated';
import { AnnotationStatus } from '../../../api/generated';
import type { BadgeTone } from '../../admin/ToneBadge';
import { ToneBadge } from '../../admin/ToneBadge';
import { highlightColorFor } from '../viewer/markerColors';
import { PlacementStatusChip } from './PlacementStatusChip';

/** Gentle glow on the status rail while the card is linked to its hovered mark. */
const railGlow = keyframes`
  0%, 100% { box-shadow: 0 0 0 0 transparent; }
  50% { box-shadow: 0 0 10px 2px var(--rail-glow); }
`;

const STATUS_CUES: Record<AnnotationStatus, { tone: BadgeTone; label: string }> = {
  [AnnotationStatus.Open]: { tone: 'blue', label: 'Open' },
  [AnnotationStatus.Accepted]: { tone: 'green', label: 'Accepted' },
  [AnnotationStatus.Rejected]: { tone: 'neutral', label: 'Rejected' },
};

interface AnnotationListItemProps {
  annotation: AnnotationView;
  active: boolean;
  /** True while the mark on the page is hovered — mirrors the link visually. */
  linked?: boolean;
  onClick: () => void;
  onHover?: (annotationId: string | null) => void;
}

/**
 * One annotation in the panel: its lifecycle status (owner's decision,
 * ADR-0011), the placement cue for the viewed version (ADR-0009), and the mark
 * itself — the quoted text, or the page for a pure region annotation. The left
 * rail carries the same colour the mark paints with on the page, and hovering
 * either side of the card↔mark pair lights up the other (prototype linking).
 */
export function AnnotationListItem({
  annotation,
  active,
  linked = false,
  onClick,
  onHover,
}: AnnotationListItemProps) {
  const theme = useTheme();
  const quote = annotation.anchor?.textQuote?.quote;
  const region = annotation.anchor?.region;
  const statusCue = STATUS_CUES[annotation.status];
  const railColor = annotation.anchor
    ? highlightColorFor(annotation, theme.palette)
    : theme.palette.divider;

  return (
    <ButtonBase
      onClick={onClick}
      onMouseEnter={() => onHover?.(annotation.id)}
      onMouseLeave={() => onHover?.(null)}
      onFocus={() => onHover?.(annotation.id)}
      onBlur={() => onHover?.(null)}
      aria-expanded={active}
      data-testid={`annotation-item-${annotation.id}`}
      sx={{
        display: 'block',
        width: '100%',
        textAlign: 'left',
        position: 'relative',
        borderRadius: 1.5,
        border: '1px solid',
        borderColor: active ? theme.qnop.brand.blue : linked ? railColor : theme.palette.divider,
        bgcolor: active
          ? alpha(theme.qnop.brand.blue, 0.06)
          : linked
            ? alpha(railColor, 0.08)
            : 'transparent',
        pl: 2,
        pr: 1.5,
        py: 1.25,
        transition:
          'border-color 120ms ease, background-color 120ms ease, transform 160ms ease, box-shadow 160ms ease',
        '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
        ...(linked && {
          transform: 'translateX(-3px)',
          boxShadow: `0 8px 22px -10px ${alpha(railColor, 0.8)}`,
        }),
        '&:hover': { borderColor: railColor },
        '&:focus-visible': { boxShadow: theme.qnop.focusRing },
      }}
    >
      {/* Link arrow pointing at the document while the pair is hot (prototype). */}
      {linked && (
        <Box
          aria-hidden
          sx={{
            position: 'absolute',
            left: -7,
            top: '50%',
            transform: 'translateY(-50%)',
            width: 0,
            height: 0,
            borderTop: '6px solid transparent',
            borderBottom: '6px solid transparent',
            borderRight: `7px solid ${railColor}`,
          }}
        />
      )}
      {/* Status rail — the same colour the mark paints with on the page. */}
      <Box
        aria-hidden
        sx={{
          position: 'absolute',
          left: 0,
          top: 8,
          bottom: 8,
          width: 3,
          borderRadius: '0 3px 3px 0',
          bgcolor: railColor,
          opacity: linked || active ? 1 : 0.55,
          transition: 'opacity 120ms ease',
          '--rail-glow': alpha(railColor, 0.55),
          ...(linked && { animation: `${railGlow} 1.1s ease-in-out infinite` }),
          '@media (prefers-reduced-motion: reduce)': { animation: 'none', transition: 'none' },
        }}
      />
      <Stack spacing={0.75}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
          <ToneBadge tone={statusCue.tone} label={statusCue.label} />
          <PlacementStatusChip status={annotation.placementStatus} />
          <Stack
            direction="row"
            spacing={1}
            sx={{ alignItems: 'center', ml: 'auto', color: 'text.secondary' }}
          >
            {region && <Typography variant="caption">Page {region.surfaceIndex + 1}</Typography>}
            <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
              <MessageSquare size={13} aria-hidden />
              <Typography variant="caption" aria-label={`${annotation.commentCount} comments`}>
                {annotation.commentCount}
              </Typography>
            </Stack>
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
            {region ? 'Region annotation' : 'No placement on this version'}
          </Typography>
        )}
      </Stack>
    </ButtonBase>
  );
}
