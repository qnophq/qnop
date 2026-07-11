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
import Box from '@mui/material/Box';
import Container from '@mui/material/Container';
import Drawer from '@mui/material/Drawer';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import { Outlet, useMatch } from 'react-router-dom';
import { SidebarContent } from './SidebarContent';
import { TopBar } from './TopBar';

const EXPANDED_WIDTH = 260;
const COLLAPSED_WIDTH = 72;
const COLLAPSE_KEY = 'qnop-nav-collapsed';

/**
 * The application shell (#102): a collapsible, role-aware sidebar, a top bar
 * with breadcrumbs and quick actions, and the routed content outlet. On desktop
 * the sidebar is a permanent rail that collapses to icons (state persisted); on
 * mobile it is a temporary drawer toggled from the top bar.
 */
export function AppShell() {
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('md'));
  // The document review workspace (#250), the version comparison (#252) and
  // the tasks board (#393) manage their own scrolling and tight padding.
  // /reviews/new also matches the dynamic segment but is a regular page (the
  // wizard, #251).
  const reviewMatch = useMatch('/reviews/:documentId');
  const compareMatch = useMatch('/reviews/:documentId/compare');
  const tasksMatch = useMatch('/reviews/:documentId/tasks');
  const fullBleed = Boolean(
    (reviewMatch && reviewMatch.params.documentId !== 'new') || compareMatch || tasksMatch,
  );
  // The work surfaces share one width language (issue #454 follow-up): the
  // dashboard and the reviews overview span the full width like the review
  // workspace; admin and profile keep the centred reading container.
  const dashboardMatch = useMatch('/');
  const reviewsListMatch = useMatch('/reviews');
  const wide = fullBleed || Boolean(dashboardMatch) || Boolean(reviewsListMatch);
  const [collapsed, setCollapsed] = useState<boolean>(() => {
    try {
      return localStorage.getItem(COLLAPSE_KEY) === '1';
    } catch {
      return false;
    }
  });
  const [mobileOpen, setMobileOpen] = useState(false);

  const toggleSidebar = () => {
    if (isMobile) {
      setMobileOpen((o) => !o);
      return;
    }
    setCollapsed((c) => {
      const next = !c;
      try {
        localStorage.setItem(COLLAPSE_KEY, next ? '1' : '0');
      } catch {
        // best-effort persistence
      }
      return next;
    });
  };

  const railWidth = collapsed ? COLLAPSED_WIDTH : EXPANDED_WIDTH;

  return (
    <Box sx={{ display: 'flex', height: '100dvh', overflow: 'hidden' }}>
      {/* Desktop rail */}
      {!isMobile && (
        <Box
          component="aside"
          sx={{
            width: railWidth,
            flexShrink: 0,
            borderRight: 1,
            borderColor: 'divider',
            bgcolor: 'background.paper',
            transition: theme.transitions.create('width', {
              duration: theme.transitions.duration.shorter,
            }),
          }}
        >
          <SidebarContent collapsed={collapsed} />
        </Box>
      )}

      {/* Mobile drawer */}
      {isMobile && (
        <Drawer
          variant="temporary"
          open={mobileOpen}
          onClose={() => setMobileOpen(false)}
          ModalProps={{ keepMounted: true }}
          slotProps={{ paper: { sx: { width: EXPANDED_WIDTH } } }}
        >
          <SidebarContent collapsed={false} onNavigate={() => setMobileOpen(false)} />
        </Drawer>
      )}

      {/* Main column */}
      <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <TopBar isMobile={isMobile} onToggleSidebar={toggleSidebar} />
        <Box
          component="main"
          sx={{
            flex: 1,
            // The review workspace manages its own scrolling (document pane and
            // annotation panel scroll independently) — the shell must not add a
            // second scrollbar around it. On xs the workspace stacks and the
            // page scroll returns.
            overflow: fullBleed ? { xs: 'auto', md: 'hidden' } : 'auto',
            bgcolor: 'background.default',
          }}
        >
          <Container
            maxWidth={wide ? false : 'lg'}
            sx={{
              py: fullBleed ? 2 : { xs: 3, md: 4 },
              height: fullBleed ? { md: '100%' } : undefined,
            }}
          >
            <Outlet />
          </Container>
        </Box>
      </Box>
    </Box>
  );
}
