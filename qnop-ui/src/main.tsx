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

import { StrictMode, useMemo } from 'react';
import { createRoot } from 'react-dom/client';
import CssBaseline from '@mui/material/CssBaseline';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router-dom';
import '@fontsource/outfit/300.css';
import '@fontsource/outfit/400.css';
import '@fontsource/outfit/500.css';
import '@fontsource/outfit/600.css';
import '@fontsource/outfit/700.css';
import '@fontsource/jetbrains-mono/400.css';
import '@fontsource/jetbrains-mono/500.css';
import './index.css';
import { queryClient } from './api/queryClient';
import { AuthHydrationBoundary } from './components/auth/AuthHydrationBoundary';
import { FaviconManager } from './components/branding/FaviconManager';
import { TimezoneSync } from './components/TimezoneSync';
import { router } from './router';
import { buildTheme } from './theme/theme';
import { useUiStore } from './stores/uiStore';

function Root() {
  const themeMode = useUiStore((s) => s.themeMode);
  // Rebuild the MUI theme only when the mode changes, not on every Root re-render — a fresh theme
  // identity would otherwise cascade a re-render through every themed component (issue #170).
  const theme = useMemo(() => buildTheme(themeMode), [themeMode]);

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <FaviconManager />
        <TimezoneSync />
        <AuthHydrationBoundary>
          <RouterProvider router={router} />
        </AuthHydrationBoundary>
      </ThemeProvider>
    </QueryClientProvider>
  );
}

const rootElement = document.getElementById('root');
if (!rootElement) {
  throw new Error('Root element #root not found');
}

createRoot(rootElement).render(
  <StrictMode>
    <Root />
  </StrictMode>,
);
