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
import { alpha } from '@mui/material/styles';
import type { NotificationSummary } from '../../api/generated';
import { useFormatters } from '../../hooks/useFormatters';
import { NotificationTypeIcon } from './NotificationTypeIcon';

/**
 * One notification, in the inbox or in the bell quickview (issue #538).
 *
 * <p>Unread is carried by weight and a leading dot rather than a background
 * wash, so a long list of unread rows still reads as a list instead of a block
 * of colour — and a read row keeps its full legibility.
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
  const { formatRelative } = useFormatters();
  const unread = !notification.readAt;

  return (
    <ButtonBase
      onClick={onOpen}
      sx={{
        display: 'block',
        width: '100%',
        textAlign: 'left',
        px: dense ? 2 : 2.5,
        py: dense ? 1.25 : 1.75,
        borderBottom: '1px solid',
        borderColor: 'divider',
        transition: 'background-color 150ms',
        '&:last-of-type': { borderBottom: 'none' },
        '&:hover, &:focus-visible': {
          bgcolor: (t) => alpha(t.palette.primary.main, t.palette.mode === 'dark' ? 0.1 : 0.05),
        },
      }}
    >
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'flex-start' }}>
        <Box sx={{ pt: 0.25, color: unread ? 'primary.main' : 'text.disabled', flexShrink: 0 }}>
          <NotificationTypeIcon type={notification.type} size={dense ? 16 : 18} />
        </Box>
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'baseline' }}>
            <Typography
              sx={{
                fontSize: dense ? 13.5 : 14.5,
                fontWeight: unread ? 700 : 500,
                color: 'text.primary',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
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
          {notification.documentTitle && (
            <Typography
              sx={{
                fontSize: 12.5,
                color: 'text.secondary',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {notification.documentTitle}
            </Typography>
          )}
          {notification.preview && (
            <Typography
              sx={{
                mt: 0.5,
                fontSize: 12.5,
                color: 'text.secondary',
                fontStyle: 'italic',
                display: '-webkit-box',
                WebkitLineClamp: 2,
                WebkitBoxOrient: 'vertical',
                overflow: 'hidden',
              }}
            >
              “{notification.preview}”
            </Typography>
          )}
          <Typography sx={{ mt: 0.5, fontSize: 11.5, color: 'text.disabled' }}>
            {formatRelative(notification.createdAt)}
          </Typography>
        </Box>
      </Stack>
    </ButtonBase>
  );
}
