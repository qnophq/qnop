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
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import type { LucideIcon } from 'lucide-react';
import {
  Activity,
  CalendarClock,
  CircleCheck,
  MessageSquarePlus,
  RotateCcw,
  Sparkles,
  Workflow,
} from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';
import type { DashboardActivity } from '../../api/generated';
import { activityPhrase, reviewPath } from '../dashboard/dashboardModel';
import { shortRelativeTime } from '../../utils/relativeTime';

const TYPE_ICONS: Record<string, LucideIcon> = {
  'annotation.created': MessageSquarePlus,
  'annotation.resolved': CircleCheck,
  'annotation.reopened': RotateCcw,
  'workflow.transition': Workflow,
  'document.due_date.changed': CalendarClock,
};

interface ProfileMovesCardProps {
  /** The profiled person's first name, for the section copy. */
  firstName: string;
  moves: DashboardActivity[];
}

/**
 * The person's recent moves (issue #482 polish) — the dashboard feed's
 * language, filtered to this one player: verb phrase plus the review link and
 * a relative timestamp. Sourced from the viewer's own feed, so only shared,
 * anonymity-safe activity ever shows.
 */
export function ProfileMovesCard({ firstName, moves }: ProfileMovesCardProps) {
  const theme = useTheme();
  const blue = theme.qnop.brand.blue;
  const dark = theme.qnop.mode === 'dark';

  return (
    <Paper
      variant="outlined"
      data-testid="profile-moves"
      sx={{ p: { xs: 2, sm: 2.5 }, borderRadius: '16px' }}
    >
      <Stack direction="row" spacing={1.25} sx={{ alignItems: 'center', mb: 0.25 }}>
        <Box
          aria-hidden
          sx={{
            width: 30,
            height: 30,
            borderRadius: '9px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: blue,
            bgcolor: alpha(blue, dark ? 0.16 : 0.1),
            flexShrink: 0,
          }}
        >
          <Activity size={15} />
        </Box>
        <Typography sx={{ fontSize: 14, fontWeight: 700, flex: 1 }}>Recent moves</Typography>
        {moves.length > 0 && (
          <Typography
            variant="caption"
            sx={{ fontWeight: 700, color: blue, fontVariantNumeric: 'tabular-nums' }}
          >
            {moves.length}
          </Typography>
        )}
      </Stack>
      <Typography variant="caption" color="text.secondary" component="p" sx={{ mb: 1.25 }}>
        In reviews you share
      </Typography>

      {moves.length === 0 ? (
        <Stack
          direction="row"
          spacing={1}
          sx={{ alignItems: 'center', color: 'text.disabled', py: 1 }}
        >
          <Sparkles size={15} aria-hidden />
          <Typography variant="body2" color="text.secondary">
            No recent moves from {firstName} in reviews you share.
          </Typography>
        </Stack>
      ) : (
        <Stack spacing={1.25}>
          {moves.map((item, index) => {
            const Icon = TYPE_ICONS[item.type] ?? Activity;
            return (
              <Stack
                key={`${item.type}-${item.createdAt}-${index}`}
                direction="row"
                spacing={1}
                sx={{ alignItems: 'flex-start', minWidth: 0 }}
              >
                <Box aria-hidden sx={{ color: 'text.secondary', pt: '3px', flexShrink: 0 }}>
                  <Icon size={14} />
                </Box>
                <Typography variant="body2" sx={{ minWidth: 0, color: 'text.secondary' }}>
                  {activityPhrase(item.type).replace(/^./, (c) => c.toUpperCase())}{' '}
                  <Typography
                    component={RouterLink}
                    to={reviewPath({ id: item.documentId, slug: item.documentSlug })}
                    variant="body2"
                    sx={{
                      fontWeight: 600,
                      color: blue,
                      textDecoration: 'none',
                      '&:hover': { textDecoration: 'underline' },
                    }}
                  >
                    {item.documentTitle}
                  </Typography>{' '}
                  <Typography component="span" variant="caption" color="text.secondary">
                    · {shortRelativeTime(item.createdAt)}
                  </Typography>
                </Typography>
              </Stack>
            );
          })}
        </Stack>
      )}
    </Paper>
  );
}
