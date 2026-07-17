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
import { alpha, useTheme } from '@mui/material/styles';
import { CalendarClock } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import type { DocumentSummary } from '../../api/generated';
import { SectionCard } from '../admin/layout/SectionCard';
import { CardScroller } from './CardScroller';
import { CountPill } from './CountPill';
import { dueUrgency, reviewPath } from './dashboardModel';

const DATE_FORMAT = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });

interface DeadlinesCardProps {
  reviews: DocumentSummary[];
}

/**
 * Every open review with a completion deadline (issue #295), soonest first,
 * in the dashboard's urgency colours (issue #454): overdue in danger, due
 * today in warning, the rest quiet.
 */
export function DeadlinesCard({ reviews }: DeadlinesCardProps) {
  const theme = useTheme();
  const navigate = useNavigate();

  const urgencyColor = (level: string) =>
    level === 'overdue'
      ? theme.palette.error.main
      : level === 'today' || level === 'soon'
        ? theme.palette.warning.main
        : theme.qnop.brand.blue;

  return (
    <SectionCard
      icon={CalendarClock}
      title="Deadlines"
      description="When your running reviews are due."
      action={<CountPill value={reviews.length} />}
    >
      {reviews.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          No deadlines — nothing is due.
        </Typography>
      ) : (
        <CardScroller>
          <Stack spacing={0.5}>
            {reviews.map((review) => {
              const urgency = dueUrgency(review.dueAt as string);
              const color = urgencyColor(urgency.level);
              return (
                <ButtonBase
                  key={review.id}
                  onClick={() => navigate(reviewPath(review))}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1.25,
                    textAlign: 'left',
                    borderRadius: '8px',
                    px: 1.25,
                    py: 1,
                    '&:hover': { bgcolor: alpha(theme.qnop.brand.blue, 0.05) },
                  }}
                >
                  {/* The urgency, readable before the words. */}
                  <Box
                    aria-hidden
                    sx={{
                      width: 8,
                      height: 8,
                      borderRadius: '50%',
                      bgcolor: color,
                      flexShrink: 0,
                      boxShadow: `0 0 0 3px ${alpha(color, 0.18)}`,
                    }}
                  />
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Typography noWrap sx={{ fontWeight: 600, fontSize: '0.85rem' }}>
                      {review.title}
                    </Typography>
                    <Typography variant="caption" color="text.secondary" noWrap component="p">
                      {DATE_FORMAT.format(new Date(review.dueAt as string))}
                    </Typography>
                  </Box>
                  <Typography
                    variant="caption"
                    sx={{ color, fontWeight: 700, flexShrink: 0 }}
                    data-urgency={urgency.level}
                  >
                    {urgency.label}
                  </Typography>
                </ButtonBase>
              );
            })}
          </Stack>
        </CardScroller>
      )}
    </SectionCard>
  );
}
