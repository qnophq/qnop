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
import Pagination from '@mui/material/Pagination';
import Paper from '@mui/material/Paper';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { CheckCheck, MailOpen } from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router';
import { useMarkAllNotificationsRead, useNotifications } from '../../api/hooks/useNotifications';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { NotificationRow } from '../../components/notifications/NotificationRow';
import { AllCaughtUpState, MessagesEmptyState } from './MessagesEmptyState';
import { ErrorState } from '../errors/ErrorState';
import { ServerErrorIllustration } from '../errors/illustrations';

const PAGE_SIZE = 20;

type ReadFilter = 'all' | 'unread' | 'read';

const READ_FILTERS: readonly ReadFilter[] = ['all', 'unread', 'read'];

function parseFilter(raw: string | null): ReadFilter {
  return READ_FILTERS.includes(raw as ReadFilter) ? (raw as ReadFilter) : 'all';
}

/**
 * The full inbox (issue #538): every notification the caller ever received,
 * newest first, with the read/unread facet and the page both persisted in the
 * URL so a filtered view is shareable and survives a reload.
 */
export function MessagesPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const filter = parseFilter(searchParams.get('filter'));
  const page = Math.max(0, Number(searchParams.get('page') ?? '0') || 0);

  const setParam = (key: string, next: string, fallback: string) =>
    setSearchParams(
      (prev) => {
        const params = new URLSearchParams(prev);
        if (next === fallback) params.delete(key);
        else params.set(key, next);
        // Changing the facet restarts paging — page 3 of "unread" rarely exists.
        if (key === 'filter') params.delete('page');
        return params;
      },
      { replace: true },
    );

  const { data, isPending, isError, refetch } = useNotifications({
    unread: filter === 'all' ? undefined : filter === 'unread',
    page,
    size: PAGE_SIZE,
  });
  const markAllRead = useMarkAllNotificationsRead();

  const items = data?.items ?? [];
  const total = data?.total ?? 0;
  const unreadTotal = data?.unreadTotal ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  // Nothing has EVER landed here — a cold start, not a filter that came up
  // empty. It replaces the facets and the list rather than sitting inside them:
  // filtering an inbox that has never received anything is busywork.
  const neverReceivedAnything = !isPending && filter === 'all' && total === 0;

  if (isError) {
    return (
      <ErrorState
        code="500"
        title="Your messages didn't make it to the desk"
        message="The inbox could not be loaded. It is almost certainly the connection, not your notifications — they are safe."
        illustration={<ServerErrorIllustration />}
        tone="alert"
        primaryAction={{ label: 'Try again', onClick: () => refetch() }}
        secondaryAction={{ label: 'Back to dashboard', to: '/' }}
      />
    );
  }

  return (
    <Stack spacing={3}>
      <PageHeader
        title="Messages"
        description="Everything that happened on your reviews — mentions, replies, decisions and new versions."
        action={
          unreadTotal > 0 ? (
            <Button
              size="small"
              variant="outlined"
              startIcon={<CheckCheck size={15} />}
              onClick={() => markAllRead.mutate()}
              disabled={markAllRead.isPending}
            >
              Mark all read
            </Button>
          ) : undefined
        }
      />

      {neverReceivedAnything ? (
        <MessagesEmptyState />
      ) : (
        <>
          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
            <Chip
              label={`All${total > 0 && filter === 'all' ? ` (${total})` : ''}`}
              size="small"
              color={filter === 'all' ? 'primary' : 'default'}
              variant={filter === 'all' ? 'filled' : 'outlined'}
              onClick={() => setParam('filter', 'all', 'all')}
              sx={{ fontWeight: filter === 'all' ? 600 : 400 }}
            />
            <Chip
              label={`Unread${unreadTotal > 0 ? ` (${unreadTotal})` : ''}`}
              size="small"
              color={filter === 'unread' ? 'primary' : 'default'}
              variant={filter === 'unread' ? 'filled' : 'outlined'}
              onClick={() => setParam('filter', 'unread', 'all')}
              sx={{ fontWeight: filter === 'unread' ? 600 : 400 }}
            />
            <Chip
              label="Read"
              size="small"
              color={filter === 'read' ? 'primary' : 'default'}
              variant={filter === 'read' ? 'filled' : 'outlined'}
              onClick={() => setParam('filter', 'read', 'all')}
              sx={{ fontWeight: filter === 'read' ? 600 : 400 }}
            />
          </Stack>

          <Paper variant="outlined" sx={{ borderRadius: '14px', overflow: 'hidden' }}>
            {/*
              The rows are columnar, so they need a legend — without it four
              aligned strings read as arbitrary. Hidden on narrow screens, where
              the row drops those columns anyway. Widths mirror the row's.
            */}
            {!isPending && items.length > 0 && (
              <Box
                aria-hidden
                sx={{
                  display: { xs: 'none', sm: 'flex' },
                  alignItems: 'center',
                  gap: 2,
                  px: 2.5,
                  py: 0.75,
                  borderBottom: '1px solid',
                  borderColor: 'divider',
                  bgcolor: (t) => t.qnop.surface2,
                  fontSize: 11,
                  fontWeight: 700,
                  letterSpacing: '0.08em',
                  textTransform: 'uppercase',
                  color: 'text.disabled',
                }}
              >
                <Box sx={{ width: 30, flexShrink: 0 }} />
                <Box sx={{ flex: '1.1 1 0', minWidth: 0, pl: 1.5 }}>What happened</Box>
                <Box sx={{ flex: '1.4 1 0', minWidth: 0, display: { xs: 'none', md: 'block' } }}>
                  Excerpt
                </Box>
                <Box sx={{ width: 190, flexShrink: 0 }}>Review</Box>
                <Box sx={{ width: 92, flexShrink: 0, textAlign: 'right' }}>When</Box>
              </Box>
            )}

            {isPending ? (
              <Box sx={{ p: 2.5 }}>
                {[0, 1, 2, 3].map((row) => (
                  <Skeleton key={row} height={54} sx={{ mb: 1 }} />
                ))}
              </Box>
            ) : items.length === 0 ? (
              // A facet that came up empty. "Nothing unread" is an achievement and
              // says so; "nothing read" is just a fact and stays quiet.
              filter === 'unread' ? (
                <AllCaughtUpState />
              ) : (
                <Stack
                  sx={{ alignItems: 'center', textAlign: 'center', px: 3, py: 8 }}
                  spacing={1.25}
                >
                  <MailOpen size={26} aria-hidden />
                  <Typography sx={{ fontWeight: 600 }}>Nothing read yet</Typography>
                  <Typography sx={{ fontSize: 13.5, color: 'text.secondary', maxWidth: 420 }}>
                    Everything in your inbox is still waiting for you.
                  </Typography>
                </Stack>
              )
            ) : (
              items.map((notification) => (
                <NotificationRow
                  key={notification.id}
                  notification={notification}
                  onOpen={() => navigate(`/messages/${notification.id}`)}
                />
              ))
            )}
          </Paper>

          {pageCount > 1 && (
            <Stack sx={{ alignItems: 'center' }}>
              <Pagination
                count={pageCount}
                page={page + 1}
                onChange={(_event, next) => setParam('page', String(next - 1), '0')}
                shape="rounded"
              />
            </Stack>
          )}
        </>
      )}
    </Stack>
  );
}
