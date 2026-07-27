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

import { useEffect } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Paper from '@mui/material/Paper';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha } from '@mui/material/styles';
import { ArrowLeft, ArrowRight } from 'lucide-react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router';
import { useMarkNotificationRead, useNotification } from '../../api/hooks/useNotifications';
import { NotificationTypeIcon } from '../../components/notifications/NotificationTypeIcon';
import { useFormatters } from '../../hooks/useFormatters';
import { ErrorState } from '../errors/ErrorState';
import { NotFoundIllustration } from '../errors/illustrations';

/** One labelled fact in the context rail. */
function RailFact({ label, value }: { label: string; value: string }) {
  return (
    <Box>
      <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>{label}</Typography>
      <Typography sx={{ fontSize: 14, fontWeight: 600, wordBreak: 'break-word' }}>
        {value}
      </Typography>
    </Box>
  );
}

/**
 * One notification in full (issue #538): the formatted message, the excerpt it
 * quotes, and the way back to what it is about.
 *
 * <p>Opening the page marks it read — the server keeps that an explicit call
 * rather than a side effect of the GET, so a prefetch or a re-render can never
 * silently clear the badge.
 */
export function MessageDetailPage() {
  const { notificationId = '' } = useParams();
  const navigate = useNavigate();
  const { formatDateTime } = useFormatters();
  const { data, isPending, isError } = useNotification(notificationId);
  const markRead = useMarkNotificationRead();

  const unread = Boolean(data && !data.readAt);
  useEffect(() => {
    if (unread) {
      markRead.mutate(notificationId);
    }
    // Exactly once per notification that arrives unread — the mutation object
    // is not a dependency, its identity changes on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [unread, notificationId]);

  if (isError) {
    return (
      <ErrorState
        code="404"
        title="This message has already been filed away"
        message="No notification answers to that link. It may have been about a review that has since been removed."
        illustration={<NotFoundIllustration />}
        primaryAction={{ label: 'Back to messages', to: '/messages' }}
        secondaryAction={{ label: 'Go to dashboard', to: '/' }}
      />
    );
  }

  if (isPending || !data) {
    return (
      <Stack spacing={2}>
        <Skeleton height={32} width={180} />
        <Skeleton height={160} />
      </Stack>
    );
  }

  return (
    <Stack spacing={2.5}>
      <Box>
        <Button
          component={RouterLink}
          to="/messages"
          size="small"
          startIcon={<ArrowLeft size={15} />}
          sx={{ ml: -1 }}
        >
          All messages
        </Button>
      </Box>

      {/*
        The card takes the full width the shell gives this route, and then uses
        it: the message reads on the left, its context and the way onward sit in
        a rail on the right — the same "content + rail" composition the
        new-review wizard uses, rather than one paragraph stretched across a
        monitor.
      */}
      <Paper variant="outlined" sx={{ borderRadius: '14px', p: { xs: 2.5, sm: 3.5 } }}>
        <Stack
          direction={{ xs: 'column', md: 'row' }}
          spacing={{ xs: 3, md: 4 }}
          sx={{ alignItems: 'stretch' }}
        >
          {/* the message itself */}
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Stack direction="row" spacing={2} sx={{ alignItems: 'flex-start' }}>
              <Box
                sx={{
                  width: 42,
                  height: 42,
                  flexShrink: 0,
                  borderRadius: '50%',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  color: 'primary.main',
                  bgcolor: (t) =>
                    alpha(t.palette.primary.main, t.palette.mode === 'dark' ? 0.16 : 0.1),
                }}
              >
                <NotificationTypeIcon type={data.type} size={20} />
              </Box>
              <Box sx={{ minWidth: 0, flex: 1 }}>
                <Typography variant="h1" sx={{ fontSize: 22, lineHeight: 1.3 }}>
                  {data.title}
                </Typography>
                {!data.accessible && (
                  <Chip
                    label="No longer available"
                    size="small"
                    variant="outlined"
                    sx={{ mt: 0.75 }}
                  />
                )}
              </Box>
            </Stack>

            {/* prose keeps a measure even when the card is very wide */}
            <Typography sx={{ mt: 2.5, fontSize: 15, lineHeight: 1.65, maxWidth: '68ch' }}>
              {data.body}
            </Typography>

            {data.preview && (
              <Box
                sx={{
                  mt: 2,
                  px: 2,
                  py: 1.5,
                  maxWidth: '68ch',
                  borderLeft: '3px solid',
                  borderColor: 'primary.main',
                  borderRadius: '0 8px 8px 0',
                  bgcolor: (t) =>
                    alpha(t.palette.primary.main, t.palette.mode === 'dark' ? 0.08 : 0.04),
                }}
              >
                <Typography sx={{ fontSize: 14, fontStyle: 'italic', color: 'text.secondary' }}>
                  “{data.preview}”
                </Typography>
              </Box>
            )}
          </Box>

          {/* the context rail: what this is about, and where it leads */}
          <Box
            sx={{
              width: { xs: '100%', md: 300 },
              flexShrink: 0,
              pt: { xs: 2.5, md: 0 },
              pl: { md: 4 },
              borderTop: { xs: '1px solid', md: 'none' },
              borderLeft: { md: '1px solid' },
              borderColor: { xs: 'divider', md: 'divider' },
            }}
          >
            <Typography
              sx={{
                fontSize: 11,
                fontWeight: 700,
                letterSpacing: '0.12em',
                textTransform: 'uppercase',
                color: 'text.disabled',
              }}
            >
              Context
            </Typography>

            <Stack spacing={1.75} sx={{ mt: 1.75 }}>
              {data.documentTitle && <RailFact label="Review" value={data.documentTitle} />}
              {data.actorName && <RailFact label="From" value={data.actorName} />}
              <RailFact label="Received" value={formatDateTime(data.createdAt)} />
            </Stack>

            {data.actionPath && (
              <Button
                fullWidth
                variant="contained"
                endIcon={<ArrowRight size={16} />}
                onClick={() => navigate(data.actionPath as string)}
                sx={{ mt: 3 }}
              >
                {data.actionLabel ?? 'Open review'}
              </Button>
            )}
          </Box>
        </Stack>
      </Paper>
    </Stack>
  );
}
