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
import { useTheme } from '@mui/material/styles';
import { MessageSquare } from 'lucide-react';
import type { AnnotationView } from '../../../api/generated';
import { ToneBadge } from '../../admin/ToneBadge';
import { isDocumentScoped } from '../annotationScope';
import { WholeDocumentChip } from '../WholeDocumentChip';
import { PRIORITY_CUES, TYPE_CUES } from '../tasks/tasksModel';
import { PlacementStatusChip } from './PlacementStatusChip';
import { STATUS_CUES } from './statusCues';

interface AnnotationBadgeRowProps {
  annotation: AnnotationView;
  /** Adds the "New" badge (issue #307). */
  unseen?: boolean;
}

/**
 * The annotation's badge line — status, optional New, type cue, priority dot,
 * placement cue, and page + thread size on the right. ONE component so the
 * panel's head card and the mark's hover preview stay pixel-identical
 * (issue #403).
 */
export function AnnotationBadgeRow({ annotation, unseen = false }: AnnotationBadgeRowProps) {
  const theme = useTheme();
  const statusCue = STATUS_CUES[annotation.status];
  const typeCue = annotation.type ? TYPE_CUES[annotation.type] : null;
  const TypeIcon = typeCue?.icon;
  const priorityCue = annotation.priority ? PRIORITY_CUES[annotation.priority] : null;
  const region = annotation.anchor?.region;

  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
      <ToneBadge tone={statusCue.tone} label={statusCue.label} />
      {unseen && <ToneBadge tone="blue" label="New" />}
      {typeCue && TypeIcon && (
        <Stack
          direction="row"
          spacing={0.5}
          sx={{ alignItems: 'center', color: typeCue.color(theme) }}
        >
          <TypeIcon size={12} aria-hidden />
          <Typography component="span" sx={{ fontSize: 11, fontWeight: 600 }}>
            {typeCue.label}
          </Typography>
        </Stack>
      )}
      {priorityCue && (
        <Tooltip title={`${priorityCue.label} priority`}>
          <Box
            sx={{
              width: 7,
              height: 7,
              borderRadius: '50%',
              bgcolor: priorityCue.color(theme),
              flexShrink: 0,
            }}
          />
        </Tooltip>
      )}
      <PlacementStatusChip status={annotation.placementStatus} />
      <Stack
        direction="row"
        spacing={1}
        sx={{ alignItems: 'center', ml: 'auto', color: 'text.secondary' }}
      >
        {isDocumentScoped(annotation) ? (
          <WholeDocumentChip compact />
        ) : (
          region && <Typography variant="caption">Page {region.surfaceIndex + 1}</Typography>
        )}
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
          <MessageSquare size={13} aria-hidden />
          <Typography variant="caption" aria-label={`${annotation.commentCount} comments`}>
            {annotation.commentCount}
          </Typography>
        </Stack>
      </Stack>
    </Stack>
  );
}
