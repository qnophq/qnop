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
import CircularProgress from '@mui/material/CircularProgress';
import Divider from '@mui/material/Divider';
import Popover from '@mui/material/Popover';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha } from '@mui/material/styles';
import { BellOff } from 'lucide-react';
import { useNavigate } from 'react-router';
import { useMarkAllNotificationsRead, useNotifications } from '../../api/hooks/useNotifications';
import { NotificationRow } from '../notifications/NotificationRow';

const QUICKVIEW_SIZE = 6;

/**
 * The bell's quickview (issue #538): the handful of most recent notifications,
 * unread ones carrying the emphasis, with a way to clear the badge and a way
 * into the full inbox. Deliberately not a second inbox — it answers "anything
 * new?" in one glance and hands off for everything else.
 */
export function NotificationsPopover({
  anchorEl,
  onClose,
}: {
  anchorEl: HTMLElement | null;
  onClose: () => void;
}) {
  const navigate = useNavigate();
  const open = Boolean(anchorEl);
  // Only fetched while open — a closed popover has no business polling; the
  // badge does that on its own.
  const { data, isPending } = useNotifications(open ? { size: QUICKVIEW_SIZE } : {});
  const markAllRead = useMarkAllNotificationsRead();
  const items = open ? (data?.items ?? []) : [];
  const unread = data?.unreadTotal ?? 0;

  const go = (path: string) => {
    onClose();
    navigate(path);
  };

  return (
    <Popover
      open={open}
      anchorEl={anchorEl}
      onClose={onClose}
      anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
      transformOrigin={{ vertical: 'top', horizontal: 'right' }}
      slotProps={{ paper: { sx: { mt: 1, width: 380, maxWidth: '95vw', borderRadius: 2.5 } } }}
    >
      <Stack
        direction="row"
        sx={{ alignItems: 'center', justifyContent: 'space-between', px: 2, py: 1.5 }}
      >
        <Typography sx={{ fontWeight: 700, fontSize: 15 }}>
          Notifications
          {unread > 0 && (
            <Box
              component="span"
              sx={{
                ml: 1,
                px: 0.75,
                py: 0.125,
                borderRadius: 1,
                fontSize: 12,
                fontWeight: 700,
                color: 'primary.main',
                bgcolor: (t) =>
                  alpha(t.palette.primary.main, t.palette.mode === 'dark' ? 0.18 : 0.1),
              }}
            >
              {unread} new
            </Box>
          )}
        </Typography>
        {unread > 0 && (
          <Button
            size="small"
            onClick={() => markAllRead.mutate()}
            disabled={markAllRead.isPending}
            sx={{ fontSize: 12.5 }}
          >
            Mark all read
          </Button>
        )}
      </Stack>
      <Divider />

      {isPending && open ? (
        <Stack sx={{ alignItems: 'center', py: 5 }}>
          <CircularProgress size={22} aria-label="Loading notifications" />
        </Stack>
      ) : items.length === 0 ? (
        <Stack sx={{ alignItems: 'center', textAlign: 'center', px: 3, py: 5 }} spacing={1}>
          <BellOff size={22} aria-hidden />
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Nothing new. Activity on your reviews will land here.
          </Typography>
        </Stack>
      ) : (
        <Box sx={{ maxHeight: 420, overflowY: 'auto' }}>
          {items.map((notification) => (
            <NotificationRow
              key={notification.id}
              notification={notification}
              onOpen={() => go(`/messages/${notification.id}`)}
              dense
            />
          ))}
        </Box>
      )}

      <Divider />
      <Box sx={{ p: 1, textAlign: 'center' }}>
        <Button size="small" fullWidth onClick={() => go('/messages')} sx={{ fontSize: 13 }}>
          See all messages
        </Button>
      </Box>
    </Popover>
  );
}
