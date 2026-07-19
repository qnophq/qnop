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
import Box from '@mui/material/Box';
import IconButton from '@mui/material/IconButton';
import InputBase from '@mui/material/InputBase';
import Popover from '@mui/material/Popover';
import Toolbar from '@mui/material/Toolbar';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { alpha } from '@mui/material/styles';
import { Bell, Menu as MenuIcon, Moon, PanelLeft, Search, Sun } from 'lucide-react';
import { useUiStore } from '../../stores/uiStore';
import { Breadcrumbs } from './Breadcrumbs';

/**
 * Placeholder notification center (#514): the bell opens a compact panel that
 * announces the surface instead of pretending to be one — no unread dot, no
 * fake items. Replaced by the real notification feed when it ships.
 */
function NotificationsPopover({
  anchorEl,
  onClose,
}: {
  anchorEl: HTMLElement | null;
  onClose: () => void;
}) {
  return (
    <Popover
      open={!!anchorEl}
      anchorEl={anchorEl}
      onClose={onClose}
      anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
      transformOrigin={{ vertical: 'top', horizontal: 'right' }}
      slotProps={{ paper: { sx: { mt: 1, width: 300, borderRadius: 2.5 } } }}
    >
      <Box sx={{ p: 3, textAlign: 'center' }}>
        <Box
          sx={{
            width: 44,
            height: 44,
            mx: 'auto',
            mb: 1.5,
            borderRadius: '50%',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: 'primary.main',
            bgcolor: (t) => alpha(t.palette.primary.main, t.palette.mode === 'dark' ? 0.16 : 0.1),
          }}
        >
          <Bell size={20} />
        </Box>
        <Typography sx={{ fontWeight: 700, fontSize: 15, mb: 0.5 }}>Notifications</Typography>
        <Typography sx={{ fontSize: 13, color: 'text.secondary', lineHeight: 1.55 }}>
          Coming soon — mentions, replies and review updates will land here.
        </Typography>
      </Box>
    </Popover>
  );
}

interface TopBarProps {
  isMobile: boolean;
  onToggleSidebar: () => void;
}

/** The application top bar: sidebar toggle, breadcrumbs, search and quick actions. */
export function TopBar({ isMobile, onToggleSidebar }: TopBarProps) {
  const themeMode = useUiStore((s) => s.themeMode);
  const toggleTheme = useUiStore((s) => s.toggleTheme);
  const [notificationsAnchor, setNotificationsAnchor] = useState<HTMLElement | null>(null);

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

        {/* Search (visual for now; wired to global search later) */}
        <Box
          sx={{
            display: { xs: 'none', md: 'flex' },
            alignItems: 'center',
            gap: 1,
            width: 280,
            height: 34,
            px: 1.25,
            borderRadius: 1.5,
            border: 1,
            borderColor: 'divider',
            bgcolor: (t) => t.qnop.surface2,
            color: 'text.disabled',
          }}
        >
          <Search size={15} />
          <InputBase
            placeholder="Search…"
            sx={{ fontSize: 13, flex: 1 }}
            inputProps={{ 'aria-label': 'Search' }}
          />
        </Box>

        <Tooltip title={themeMode === 'dark' ? 'Light mode' : 'Dark mode'}>
          <IconButton
            onClick={toggleTheme}
            size="small"
            aria-label={themeMode === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
          >
            {themeMode === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
          </IconButton>
        </Tooltip>

        <Tooltip title="Notifications">
          <IconButton
            size="small"
            aria-label="Notifications"
            aria-haspopup="dialog"
            aria-expanded={notificationsAnchor ? true : undefined}
            onClick={(event) => setNotificationsAnchor(event.currentTarget)}
          >
            <Bell size={18} />
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
