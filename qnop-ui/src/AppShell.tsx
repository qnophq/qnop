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
import Box from '@mui/material/Box';
import Container from '@mui/material/Container';
import IconButton from '@mui/material/IconButton';
import Toolbar from '@mui/material/Toolbar';
import Typography from '@mui/material/Typography';
import { Moon, Sun } from 'lucide-react';
import { Outlet } from 'react-router-dom';
import { useUiStore } from './stores/uiStore';

/**
 * Minimal application layout (#100): a top bar with the brand, theme toggle, and
 * a content outlet. The full sidebar shell from the design prototype lands in
 * #102; this keeps the foundation navigable and demonstrable.
 */
export function AppShell() {
  const themeMode = useUiStore((s) => s.themeMode);
  const toggleTheme = useUiStore((s) => s.toggleTheme);

  return (
    <Box sx={{ minHeight: '100dvh', display: 'flex', flexDirection: 'column' }}>
      <AppBar position="static" color="default" elevation={0} component="header">
        <Toolbar sx={{ gap: 1.5 }}>
          <Typography
            variant="h6"
            component="span"
            sx={{ fontWeight: 700, letterSpacing: '0.02em' }}
          >
            qnop
          </Typography>
          <Box sx={{ flexGrow: 1 }} />
          <IconButton
            onClick={toggleTheme}
            aria-label={
              themeMode === 'dark' ? 'Hellen Modus aktivieren' : 'Dunklen Modus aktivieren'
            }
            size="small"
          >
            {themeMode === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
          </IconButton>
        </Toolbar>
      </AppBar>
      <Container component="main" maxWidth="lg" sx={{ flexGrow: 1, py: { xs: 3, md: 5 } }}>
        <Outlet />
      </Container>
    </Box>
  );
}
