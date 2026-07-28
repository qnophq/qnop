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

import type { ReactNode } from 'react';
import Box from '@mui/material/Box';
import ButtonBase from '@mui/material/ButtonBase';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import type { NotificationSummary } from '../../api/generated';
import { useFormatters } from '../../hooks/useFormatters';
import { NotificationActor } from './NotificationActor';
import { NotificationTypeIcon } from './NotificationTypeIcon';
import { notificationTone } from './notificationMeta';

/** One line of text that never wraps — the whole point of the columnar row. */
const CLAMP = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
} as const;

/** Column widths of the inbox row; the two flexible ones share what is left. */
const REVIEW_COL = 190;
const TIME_COL = 92;

/**
 * One notification, in the inbox or in the bell quickview (issue #538).
 *
 * <p>The person leads: their avatar carries the row, with the type as a small
 * crest on its corner in the type's own colour — who did it and what it was,
 * in one glance and before any text.
 *
 * <p>Two layouts, because the two surfaces have opposite constraints. The inbox
 * is full-width and long, so its row is <strong>columnar and single-line</strong>
 * — headline, excerpt, review and age side by side, roughly half the height of
 * a stacked row, which is what makes a hundred of them scannable. The quickview
 * popover is 380px wide and shows six, so it stacks instead; columns there would
 * be four ellipses in a row.
 *
 * <p>The avatar is its own link (to the profile, with the player card on
 * hover), so it sits <em>beside</em> the button that opens the notification
 * rather than inside it — an anchor nested in a button is invalid markup and a
 * keyboard trap.
 */
export function NotificationRow({
  notification,
  onOpen,
  dense = false,
}: {
  notification: NotificationSummary;
  onOpen: () => void;
  dense?: boolean;
}) {
  const theme = useTheme();
  const { formatRelative } = useFormatters();
  const unread = !notification.readAt;
  const tone = notificationTone(notification.type, theme);
  const avatarSize = dense ? 32 : 30;

  const title = (
    <Stack direction="row" spacing={1} sx={{ alignItems: 'center', minWidth: 0 }}>
      <Typography
        sx={{
          ...CLAMP,
          fontSize: dense ? 13.5 : 14,
          fontWeight: unread ? 700 : 500,
          color: 'text.primary',
        }}
      >
        {notification.title}
      </Typography>
      {unread && (
        <Box
          aria-label="Unread"
          sx={{
            width: 7,
            height: 7,
            borderRadius: '50%',
            bgcolor: 'primary.main',
            flexShrink: 0,
          }}
        />
      )}
    </Stack>
  );

  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: dense ? 'flex-start' : 'center',
        gap: 1.5,
        px: dense ? 2 : 2.5,
        py: dense ? 1.25 : 1,
        borderBottom: '1px solid',
        borderColor: 'divider',
        transition: 'background-color 150ms',
        '&:last-of-type': { borderBottom: 'none' },
        '&:hover, &:focus-within': {
          bgcolor: (t) => alpha(t.palette.primary.main, t.palette.mode === 'dark' ? 0.1 : 0.05),
        },
      }}
    >
      <Box sx={{ position: 'relative', flexShrink: 0, mt: dense ? 0.25 : 0 }}>
        <NotificationActor
          name={notification.actorName}
          slug={notification.actorSlug}
          size={avatarSize}
        />
        <Box
          aria-hidden
          sx={{
            position: 'absolute',
            right: -3,
            bottom: -3,
            width: 16,
            height: 16,
            borderRadius: '50%',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#fff',
            bgcolor: tone,
            border: '2px solid',
            borderColor: 'background.paper',
          }}
        >
          <NotificationTypeIcon type={notification.type} size={9} />
        </Box>
      </Box>

      <ButtonBase
        onClick={onOpen}
        sx={{
          display: dense ? 'block' : 'flex',
          alignItems: 'center',
          gap: 2,
          flex: 1,
          minWidth: 0,
          textAlign: 'left',
          borderRadius: 1,
          py: 0.25,
        }}
      >
        {dense ? (
          <StackedBody
            notification={notification}
            title={title}
            time={formatRelative(notification.createdAt)}
          />
        ) : (
          <>
            <Box sx={{ flex: '1.1 1 0', minWidth: 0 }}>{title}</Box>
            <Typography
              sx={{
                ...CLAMP,
                flex: '1.4 1 0',
                minWidth: 0,
                fontSize: 12.5,
                fontStyle: 'italic',
                color: 'text.secondary',
                display: { xs: 'none', md: 'block' },
              }}
            >
              {notification.preview ? `“${notification.preview}”` : ''}
            </Typography>
            <Typography
              sx={{
                ...CLAMP,
                width: REVIEW_COL,
                flexShrink: 0,
                fontSize: 12.5,
                color: 'text.secondary',
                display: { xs: 'none', sm: 'block' },
              }}
            >
              {notification.documentTitle}
            </Typography>
            <Typography
              sx={{
                ...CLAMP,
                width: TIME_COL,
                flexShrink: 0,
                fontSize: 11.5,
                color: 'text.disabled',
                textAlign: 'right',
              }}
            >
              {formatRelative(notification.createdAt)}
            </Typography>
          </>
        )}
      </ButtonBase>
    </Box>
  );
}

/** The quickview's stacked body — narrow surface, so everything goes under itself. */
function StackedBody({
  notification,
  title,
  time,
}: {
  notification: NotificationSummary;
  title: ReactNode;
  time: string;
}) {
  return (
    <>
      {title}
      {notification.documentTitle && (
        <Typography sx={{ ...CLAMP, fontSize: 12.5, color: 'text.secondary' }}>
          {notification.documentTitle}
        </Typography>
      )}
      {notification.preview && (
        <Typography
          sx={{ ...CLAMP, mt: 0.25, fontSize: 12.5, color: 'text.secondary', fontStyle: 'italic' }}
        >
          “{notification.preview}”
        </Typography>
      )}
      <Typography sx={{ mt: 0.25, fontSize: 11.5, color: 'text.disabled' }}>{time}</Typography>
    </>
  );
}
