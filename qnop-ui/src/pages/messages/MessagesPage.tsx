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
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation(['notifications', 'common']);
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
        title={t('inbox.loadError.title')}
        message={t('inbox.loadError.message')}
        illustration={<ServerErrorIllustration />}
        tone="alert"
        primaryAction={{ label: t('common:actions.tryAgain'), onClick: () => refetch() }}
        secondaryAction={{ label: t('common:actions.backToDashboard'), to: '/' }}
      />
    );
  }

  return (
    <Stack spacing={3}>
      <PageHeader
        title={t('inbox.title')}
        description={t('inbox.description')}
        action={
          unreadTotal > 0 ? (
            <Button
              size="small"
              variant="outlined"
              startIcon={<CheckCheck size={15} />}
              onClick={() => markAllRead.mutate()}
              disabled={markAllRead.isPending}
            >
              {t('inbox.markAllRead')}
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
              label={
                total > 0 && filter === 'all'
                  ? t('inbox.filters.allCount', { total })
                  : t('inbox.filters.all')
              }
              size="small"
              color={filter === 'all' ? 'primary' : 'default'}
              variant={filter === 'all' ? 'filled' : 'outlined'}
              onClick={() => setParam('filter', 'all', 'all')}
              sx={{ fontWeight: filter === 'all' ? 600 : 400 }}
            />
            <Chip
              label={
                unreadTotal > 0
                  ? t('inbox.filters.unreadCount', { total: unreadTotal })
                  : t('inbox.filters.unread')
              }
              size="small"
              color={filter === 'unread' ? 'primary' : 'default'}
              variant={filter === 'unread' ? 'filled' : 'outlined'}
              onClick={() => setParam('filter', 'unread', 'all')}
              sx={{ fontWeight: filter === 'unread' ? 600 : 400 }}
            />
            <Chip
              label={t('inbox.filters.read')}
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
                  bgcolor: (theme) => theme.qnop.surface2,
                  fontSize: 11,
                  fontWeight: 700,
                  letterSpacing: '0.08em',
                  textTransform: 'uppercase',
                  color: 'text.disabled',
                }}
              >
                <Box sx={{ width: 30, flexShrink: 0 }} />
                <Box sx={{ flex: '1.1 1 0', minWidth: 0, pl: 1.5 }}>{t('inbox.columns.what')}</Box>
                <Box sx={{ flex: '1.4 1 0', minWidth: 0, display: { xs: 'none', md: 'block' } }}>
                  {t('inbox.columns.excerpt')}
                </Box>
                <Box sx={{ width: 190, flexShrink: 0 }}>{t('inbox.columns.review')}</Box>
                <Box sx={{ width: 92, flexShrink: 0, textAlign: 'right' }}>
                  {t('inbox.columns.when')}
                </Box>
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
                  <Typography sx={{ fontWeight: 600 }}>{t('inbox.nothingRead.title')}</Typography>
                  <Typography sx={{ fontSize: 13.5, color: 'text.secondary', maxWidth: 420 }}>
                    {t('inbox.nothingRead.body')}
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
