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
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import {
  Activity,
  CalendarClock,
  CircleCheck,
  MessageSquarePlus,
  RotateCcw,
  Workflow,
  type LucideIcon,
} from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';
import Link from '@mui/material/Link';
import type { DashboardActivity } from '../../api/generated';
import { shortRelativeTime } from '../../utils/relativeTime';
import { SectionCard } from '../admin/layout/SectionCard';
import { PersonLink } from './PersonLink';
import { activityPhrase, reviewPath } from './dashboardModel';

const TYPE_ICONS: Record<string, LucideIcon> = {
  'annotation.created': MessageSquarePlus,
  'annotation.resolved': CircleCheck,
  'annotation.reopened': RotateCcw,
  'workflow.transition': Workflow,
  'document.due_date.changed': CalendarClock,
};

interface ActivityCardProps {
  activity: DashboardActivity[];
}

/**
 * What moved while the caller was away (issue #454): a slim feed from the
 * audit trail of their reviews, their own actions excluded.
 */
export function ActivityCard({ activity }: ActivityCardProps) {
  const theme = useTheme();

  return (
    <SectionCard
      icon={Activity}
      title="Recent activity"
      description="What happened across your reviews."
    >
      {activity.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          Nothing new — you are all caught up.
        </Typography>
      ) : (
        <Stack spacing={1.25}>
          {activity.map((item, index) => {
            const Icon = TYPE_ICONS[item.type] ?? Activity;
            return (
              <Stack
                key={`${item.type}-${item.createdAt}-${index}`}
                direction="row"
                spacing={1.25}
                sx={{ alignItems: 'flex-start', minWidth: 0 }}
              >
                <Box
                  aria-hidden
                  sx={{ color: theme.palette.text.secondary, pt: 0.25, flexShrink: 0 }}
                >
                  <Icon size={14} />
                </Box>
                <Box sx={{ pt: '1px', flexShrink: 0 }}>
                  <PersonLink
                    userId={item.actorId}
                    name={item.actorDisplayName ?? 'Someone'}
                    avatarUrl={item.actorAvatarUrl}
                    size={20}
                    avatarOnly
                  />
                </Box>
                <Typography variant="body2" sx={{ minWidth: 0, color: 'text.secondary' }}>
                  <Typography
                    component="span"
                    variant="body2"
                    sx={{ fontWeight: 600, color: 'text.primary' }}
                  >
                    {item.actorDisplayName ?? 'Someone'}
                  </Typography>{' '}
                  {activityPhrase(item.type)}{' '}
                  <Link
                    component={RouterLink}
                    to={reviewPath({ id: item.documentId, slug: item.documentSlug })}
                    underline="hover"
                    sx={{ fontWeight: 600 }}
                  >
                    {item.documentTitle}
                  </Link>{' '}
                  · {shortRelativeTime(item.createdAt)}
                </Typography>
              </Stack>
            );
          })}
        </Stack>
      )}
    </SectionCard>
  );
}
