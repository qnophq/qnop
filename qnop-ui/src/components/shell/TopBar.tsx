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

import { useState } from 'react';
import AppBar from '@mui/material/AppBar';
import Badge from '@mui/material/Badge';
import Box from '@mui/material/Box';
import IconButton from '@mui/material/IconButton';
import Toolbar from '@mui/material/Toolbar';
import Tooltip from '@mui/material/Tooltip';
import { Bell, Menu as MenuIcon, Moon, PanelLeft, Sun } from 'lucide-react';
import { useUnreadCount } from '../../api/hooks/useNotifications';
import { useUiStore } from '../../stores/uiStore';
import { NotificationsPopover } from './NotificationsPopover';
import { Breadcrumbs } from './Breadcrumbs';
import { GlobalSearch } from './search/GlobalSearch';

interface TopBarProps {
  isMobile: boolean;
  onToggleSidebar: () => void;
}

/** The application top bar: sidebar toggle, breadcrumbs, search and quick actions. */
export function TopBar({ isMobile, onToggleSidebar }: TopBarProps) {
  const themeMode = useUiStore((s) => s.themeMode);
  const toggleTheme = useUiStore((s) => s.toggleTheme);
  const [notificationsAnchor, setNotificationsAnchor] = useState<HTMLElement | null>(null);
  // The real number, polled (issue #538) — the bell used to carry a decorative dot.
  const { data: unreadCount = 0 } = useUnreadCount();

  return (
    <AppBar
      position="sticky"
      color="default"
      elevation={0}
      sx={{ borderBottom: 1, borderColor: 'divider', bgcolor: 'background.paper' }}
    >
      <Toolbar sx={{ gap: 1.5, minHeight: { xs: 56, sm: 56 } }}>
        <Tooltip title={isMobile ? 'Menu' : 'Toggle menu'}>
          <IconButton onClick={onToggleSidebar} size="small" edge="start" aria-label="Toggle menu">
            {isMobile ? <MenuIcon size={18} /> : <PanelLeft size={18} />}
          </IconButton>
        </Tooltip>

        <Box sx={{ display: { xs: 'none', sm: 'block' } }}>
          <Breadcrumbs />
        </Box>

        <Box sx={{ flex: 1 }} />

        {/* The real global search (issue #540), replacing the #514 trigger. */}
        <GlobalSearch />

        <Tooltip title={themeMode === 'dark' ? 'Light mode' : 'Dark mode'}>
          <IconButton
            onClick={toggleTheme}
            size="small"
            aria-label={themeMode === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
          >
            {themeMode === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
          </IconButton>
        </Tooltip>

        <Tooltip
          title={unreadCount > 0 ? `Notifications (${unreadCount} unread)` : 'Notifications'}
        >
          <IconButton
            size="small"
            aria-label={unreadCount > 0 ? `Notifications, ${unreadCount} unread` : 'Notifications'}
            aria-haspopup="dialog"
            aria-expanded={notificationsAnchor ? true : undefined}
            onClick={(event) => setNotificationsAnchor(event.currentTarget)}
          >
            <Badge
              color="primary"
              badgeContent={unreadCount}
              max={99}
              overlap="circular"
              slotProps={{ badge: { sx: { fontSize: 10, height: 16, minWidth: 16 } } }}
            >
              <Bell size={18} />
            </Badge>
          </IconButton>
        </Tooltip>
        <NotificationsPopover
          anchorEl={notificationsAnchor}
          onClose={() => setNotificationsAnchor(null)}
        />
      </Toolbar>
    </AppBar>
  );
}
