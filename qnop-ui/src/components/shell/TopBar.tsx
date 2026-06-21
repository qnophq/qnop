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

import AppBar from '@mui/material/AppBar';
import Badge from '@mui/material/Badge';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import InputBase from '@mui/material/InputBase';
import Toolbar from '@mui/material/Toolbar';
import Tooltip from '@mui/material/Tooltip';
import { Bell, Menu as MenuIcon, Moon, PanelLeft, Plus, Search, Sun } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useUiStore } from '../../stores/uiStore';
import { Breadcrumbs } from './Breadcrumbs';

interface TopBarProps {
  isMobile: boolean;
  onToggleSidebar: () => void;
}

/** The application top bar: sidebar toggle, breadcrumbs, search and quick actions. */
export function TopBar({ isMobile, onToggleSidebar }: TopBarProps) {
  const themeMode = useUiStore((s) => s.themeMode);
  const toggleTheme = useUiStore((s) => s.toggleTheme);
  const navigate = useNavigate();

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
          <IconButton size="small" aria-label="Notifications">
            <Badge color="primary" variant="dot">
              <Bell size={18} />
            </Badge>
          </IconButton>
        </Tooltip>

        <Button
          variant="contained"
          size="small"
          startIcon={<Plus size={16} />}
          onClick={() => navigate('/reviews')}
          sx={{ display: { xs: 'none', sm: 'inline-flex' } }}
        >
          New review
        </Button>
      </Toolbar>
    </AppBar>
  );
}
