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
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { CalendarClock, FileText, History, Inbox, Plus, Trophy, UserCheck } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useDashboard } from '../api/hooks/useDashboard';
import { useReviews } from '../api/hooks/useReviews';
import { PageHeader } from '../components/admin/layout/PageHeader';
import { ActivityCard } from '../components/dashboard/ActivityCard';
import {
  deadlines,
  dueUrgency,
  greeting,
  greetingEmoji,
  myReviews,
  reviewPath,
  waitingOnYou,
} from '../components/dashboard/dashboardModel';
import { DeadlinesCard } from '../components/dashboard/DeadlinesCard';
import { EmptyDashboard } from '../components/dashboard/EmptyDashboard';
import { readRecentReviews } from '../components/dashboard/recentReviews';
import { RepliesCard } from '../components/dashboard/RepliesCard';
import { ReviewListCard } from '../components/dashboard/ReviewListCard';
import { StatStrip } from '../components/dashboard/StatStrip';
import { isOpenWorkflowState } from '../components/reviews/workflowMeta';
import { useAuthStore } from '../stores/authStore';

const DATE_FORMAT = new Intl.DateTimeFormat('en-US', {
  weekday: 'long',
  day: 'numeric',
  month: 'long',
});

/**
 * The command-centre dashboard (issue #454, prototype `dashboard.jsx`): a
 * greeting masthead with the glance numbers, then the two hats side by side —
 * what the caller owes as a reviewer, what their own reviews are up to —
 * flanked by replies directed at them, the deadline rail and the recent
 * activity of their reviews. Renders from exactly two requests: the reviews
 * overview and the dashboard aggregates.
 */
export function HomePage() {
  const navigate = useNavigate();
  const displayName = useAuthStore((s) => s.displayName);
  const userId = useAuthStore((s) => s.userId);
  const reviewsQuery = useReviews({ page: 0, size: 100, sort: 'updatedAt,desc' });
  const dashboardQuery = useDashboard();

  const reviews = reviewsQuery.data?.items ?? [];
  const waiting = waitingOnYou(reviews, userId);
  const owned = myReviews(reviews, userId);
  const due = deadlines(reviews);
  const recents = readRecentReviews();
  const firstName = displayName?.split(' ')[0];

  const overdueCount = due.filter(
    (review) => dueUrgency(review.dueAt as string).level === 'overdue',
  ).length;
  const dueSoonCount = due.filter((review) =>
    ['overdue', 'today', 'soon'].includes(dueUrgency(review.dueAt as string).level),
  ).length;

  const loading = reviewsQuery.isPending || dashboardQuery.isPending;
  const empty = !reviewsQuery.isPending && reviews.length === 0;

  return (
    <Stack spacing={3}>
      {/* Masthead: who, when, and the page's one command — start a review. Uses
          the canonical PageHeader so the action sits exactly where it does on
          the Reviews page. */}
      <PageHeader
        title={`${greeting(new Date().getHours())}${firstName ? `, ${firstName}` : ''}.`}
        titleAdornment={
          <Box component="span" aria-hidden sx={{ fontSize: 22 }}>
            {greetingEmoji(new Date().getHours())}
          </Box>
        }
        description={DATE_FORMAT.format(new Date())}
        action={
          <Button
            variant="contained"
            startIcon={<Plus size={16} />}
            onClick={() => navigate('/reviews/new')}
          >
            New review
          </Button>
        }
      />

      {loading ? (
        <Stack spacing={2}>
          <Skeleton variant="rounded" height={76} />
          <Skeleton variant="rounded" height={220} />
        </Stack>
      ) : empty ? (
        // A brand-new workspace: the gamified launch pad (issue #469).
        <EmptyDashboard />
      ) : (
        <>
          <StatStrip
            tiles={[
              {
                label: 'Open reviews',
                value: reviews.filter((r) => isOpenWorkflowState(r.workflowState)).length,
                icon: Inbox,
              },
              { label: 'Waiting on you', value: waiting.length, tone: 'accent', icon: UserCheck },
              {
                label: 'Due soon',
                value: dueSoonCount,
                tone: overdueCount > 0 ? 'danger' : 'warning',
                icon: CalendarClock,
              },
              {
                label: 'Resolved this week',
                value: dashboardQuery.data?.stats.resolvedThisWeek ?? 0,
                tone: 'success',
                icon: Trophy,
              },
            ]}
          />

          {/* Continue where you left off — device-local, one click back in. */}
          {recents.length > 0 && (
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
              <History size={14} aria-hidden style={{ opacity: 0.6 }} />
              <Typography variant="caption" color="text.secondary">
                Continue where you left off:
              </Typography>
              {recents.map((recent) => (
                <Chip
                  key={recent.id}
                  label={recent.title}
                  size="small"
                  onClick={() => navigate(reviewPath(recent))}
                />
              ))}
            </Stack>
          )}

          {/* The two hats plus context — a row-paired grid so side-by-side cards
              (Waiting on you | Deadlines, My reviews | Recent activity) always
              share the same height; grid items stretch to their row. */}
          <Box
            sx={{
              display: 'grid',
              gap: 2.5,
              gridTemplateColumns: { xs: '1fr', lg: 'repeat(2, minmax(0, 1fr))' },
            }}
          >
            <ReviewListCard
              icon={UserCheck}
              title="Waiting on you"
              description="Reviews you are asked to work through."
              reviews={waiting}
              emptyText="Nothing waits on you — enjoy the quiet."
              celebrateEmpty
            />
            <DeadlinesCard reviews={due} />
            <ReviewListCard
              icon={FileText}
              title="My reviews"
              description="Reviews you own, running ones first."
              reviews={owned}
              emptyText="You own no reviews yet."
              ownerCues
            />
            <ActivityCard activity={dashboardQuery.data?.activity ?? []} />
            <RepliesCard replies={dashboardQuery.data?.replies ?? []} />
          </Box>
        </>
      )}
    </Stack>
  );
}
