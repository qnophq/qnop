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
import { alpha, keyframes, useTheme } from '@mui/material/styles';
import { ArrowRight } from 'lucide-react';
import { useNavigate, useParams } from 'react-router';
import { useMarkNotificationRead, useNotification } from '../../api/hooks/useNotifications';
import { NotificationTypeIcon } from '../../components/notifications/NotificationTypeIcon';
import { NotificationActor } from '../../components/notifications/NotificationActor';
import {
  notificationLabel,
  notificationTone,
} from '../../components/notifications/notificationMeta';
import { useFormatters } from '../../hooks/useFormatters';
import { ErrorState } from '../errors/ErrorState';
import { NotFoundIllustration } from '../errors/illustrations';

const fadeUp = keyframes`
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: none; }
`;

/** The staged reveal the profile and empty states already use. */
function stagger(delayMs: number) {
  return {
    animation: `${fadeUp} 480ms cubic-bezier(0.16, 1, 0.3, 1) both`,
    animationDelay: `${delayMs}ms`,
    '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
  } as const;
}

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
  const theme = useTheme();
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

  const tone = notificationTone(data.type, theme);
  const dark = theme.palette.mode === 'dark';

  return (
    <Stack spacing={2.5}>
      {/*
        The type wears its own colour (see notificationMeta): a crest on a
        gradient band, exactly the identity-hero treatment the profile player
        card uses — except the identity here is *what happened*, not *who*. So
        a mention is recognisable as a mention before a word is read.
      */}
      <Paper variant="outlined" sx={{ ...stagger(0), overflow: 'hidden', borderRadius: '16px' }}>
        <Box
          aria-hidden
          sx={{
            height: 88,
            background: `
              radial-gradient(60% 150% at 80% 0%, ${alpha(tone, dark ? 0.3 : 0.18)} 0%, transparent 100%),
              linear-gradient(120deg, ${alpha(tone, dark ? 0.2 : 0.12)}, ${alpha(tone, 0.03)})
            `,
          }}
        />

        <Box sx={{ px: { xs: 2.5, sm: 3.5 }, pb: { xs: 3, sm: 3.5 } }}>
          <Stack
            direction={{ xs: 'column', md: 'row' }}
            spacing={{ xs: 3, md: 4 }}
            sx={{ alignItems: 'stretch' }}
          >
            {/* the message itself */}
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Stack
                direction="row"
                spacing={2}
                sx={{ ...stagger(60), alignItems: 'flex-end', mt: -5 }}
              >
                {/* the crest, ringed like the profile avatar and sitting on the band */}
                <Box
                  sx={{
                    display: 'inline-flex',
                    flexShrink: 0,
                    p: '3px',
                    borderRadius: '50%',
                    bgcolor: 'background.paper',
                    border: `2px solid ${alpha(tone, 0.45)}`,
                  }}
                >
                  <Box
                    sx={{
                      width: 62,
                      height: 62,
                      borderRadius: '50%',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      color: tone,
                      bgcolor: alpha(tone, dark ? 0.22 : 0.12),
                    }}
                  >
                    <NotificationTypeIcon type={data.type} size={26} />
                  </Box>
                </Box>
                <Box sx={{ minWidth: 0, pb: 0.5 }}>
                  <Typography
                    sx={{
                      fontSize: 11,
                      fontWeight: 700,
                      letterSpacing: '0.14em',
                      textTransform: 'uppercase',
                      color: tone,
                    }}
                  >
                    {notificationLabel(data.type)}
                  </Typography>
                  <Typography variant="h1" sx={{ fontSize: 22, lineHeight: 1.25, mt: 0.25 }}>
                    {data.title}
                  </Typography>
                </Box>
              </Stack>

              {!data.accessible && (
                <Chip
                  label="No longer available"
                  size="small"
                  variant="outlined"
                  sx={{ mt: 1.5 }}
                />
              )}

              {/* prose keeps a measure even when the card is very wide */}
              <Typography
                sx={{ ...stagger(140), mt: 2.5, fontSize: 15, lineHeight: 1.65, maxWidth: '68ch' }}
              >
                {data.body}
              </Typography>

              {data.preview && (
                <Box
                  sx={{
                    ...stagger(200),
                    mt: 2,
                    px: 2,
                    py: 1.5,
                    maxWidth: '68ch',
                    borderLeft: '3px solid',
                    borderColor: tone,
                    borderRadius: '0 8px 8px 0',
                    bgcolor: alpha(tone, dark ? 0.1 : 0.05),
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
                ...stagger(260),
                width: { xs: '100%', md: 300 },
                flexShrink: 0,
                pt: { xs: 2.5, md: 3 },
                pl: { md: 4 },
                borderTop: { xs: '1px solid', md: 'none' },
                borderLeft: { md: '1px solid' },
                borderColor: 'divider',
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
                {data.actorName && (
                  <Box>
                    <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>From</Typography>
                    <Box sx={{ mt: 0.5 }}>
                      <NotificationActor
                        name={data.actorName}
                        slug={data.actorSlug}
                        size={28}
                        showName
                      />
                    </Box>
                  </Box>
                )}
                {data.documentTitle && <RailFact label="Review" value={data.documentTitle} />}
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
        </Box>
      </Paper>
    </Stack>
  );
}
