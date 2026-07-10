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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ThemeProvider } from '@mui/material/styles';
import useMediaQuery from '@mui/material/useMediaQuery';
import { buildTheme } from '../../theme/theme';
import { AppShell } from './AppShell';

// The sidebar and top bar have their own tests; stub them to expose just the
// props AppShell drives — the collapsed flag and the toggle/navigate callbacks.
vi.mock('./SidebarContent', () => ({
  SidebarContent: ({ collapsed, onNavigate }: { collapsed: boolean; onNavigate?: () => void }) => (
    <div data-testid="sidebar" data-collapsed={String(collapsed)}>
      <button onClick={() => onNavigate?.()}>nav</button>
    </div>
  ),
}));
vi.mock('./TopBar', () => ({
  TopBar: ({ isMobile, onToggleSidebar }: { isMobile: boolean; onToggleSidebar: () => void }) => (
    <button data-mobile={String(isMobile)} onClick={onToggleSidebar}>
      toggle
    </button>
  ),
}));

vi.mock('@mui/material/useMediaQuery', () => ({ default: vi.fn() }));
const mediaQuery = vi.mocked(useMediaQuery);

const COLLAPSE_KEY = 'qnop-nav-collapsed';

/** true → the shell renders in its mobile (temporary drawer) layout. */
function setMobile(mobile: boolean) {
  mediaQuery.mockReturnValue(mobile);
}

function renderShell(path = '/') {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/" element={<AppShell />}>
            <Route index element={<p>home outlet</p>} />
            <Route path="reviews/new" element={<p>new outlet</p>} />
            <Route path="reviews/:documentId" element={<p>review outlet</p>} />
            <Route path="reviews/:documentId/compare" element={<p>compare outlet</p>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

const container = () => document.querySelector('.MuiContainer-root');
// A keepMounted temporary Drawer stays in the DOM when closed; its modal
// backdrop is the reliable open/closed signal — `visibility: hidden` while
// closed, visible (opacity 1) once open.
const drawerHidden = () => {
  const backdrop = document.querySelector('.MuiBackdrop-root') as HTMLElement | null;
  return backdrop === null || backdrop.style.visibility === 'hidden';
};

beforeEach(() => {
  localStorage.clear();
  setMobile(false);
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('AppShell', () => {
  it('renders the routed outlet inside the shell', () => {
    renderShell();

    expect(screen.getByText('home outlet')).toBeInTheDocument();
  });

  describe('desktop rail', () => {
    it('restores the collapsed rail from persisted state', () => {
      localStorage.setItem(COLLAPSE_KEY, '1');
      renderShell();

      expect(screen.getByTestId('sidebar').dataset.collapsed).toBe('true');
    });

    it('defaults to the expanded rail with no persisted state', () => {
      renderShell();

      expect(screen.getByTestId('sidebar').dataset.collapsed).toBe('false');
    });

    it('toggles the rail and persists each collapsed state', () => {
      renderShell();

      fireEvent.click(screen.getByRole('button', { name: 'toggle' }));
      expect(screen.getByTestId('sidebar').dataset.collapsed).toBe('true');
      expect(localStorage.getItem(COLLAPSE_KEY)).toBe('1');

      fireEvent.click(screen.getByRole('button', { name: 'toggle' }));
      expect(screen.getByTestId('sidebar').dataset.collapsed).toBe('false');
      expect(localStorage.getItem(COLLAPSE_KEY)).toBe('0');
    });

    it('shows a permanent rail and no drawer on desktop', () => {
      renderShell();

      expect(document.querySelector('aside')).toBeInTheDocument();
      expect(document.querySelector('.MuiDrawer-root')).toBeNull();
    });
  });

  describe('mobile drawer', () => {
    beforeEach(() => setMobile(true));

    it('replaces the rail with a temporary drawer', () => {
      renderShell();

      expect(document.querySelector('aside')).toBeNull();
      expect(document.querySelector('.MuiDrawer-root')).toBeInTheDocument();
    });

    it('opens the drawer from the top bar and closes it on navigation', async () => {
      renderShell();

      // Closed on first render.
      expect(drawerHidden()).toBe(true);

      fireEvent.click(screen.getByRole('button', { name: 'toggle' }));
      expect(drawerHidden()).toBe(false);

      // Following a link inside the drawer closes it again (after the fade-out).
      fireEvent.click(screen.getByRole('button', { name: 'nav' }));
      await waitFor(() => expect(drawerHidden()).toBe(true));
    });

    it('never persists a collapse toggle on mobile', () => {
      renderShell();

      fireEvent.click(screen.getByRole('button', { name: 'toggle' }));

      expect(localStorage.getItem(COLLAPSE_KEY)).toBeNull();
    });
  });

  describe('full-bleed surfaces', () => {
    it('keeps the centred reading container on ordinary pages', () => {
      renderShell('/');

      expect(container()?.className).toContain('MuiContainer-maxWidthLg');
    });

    it('drops the width cap on the document review workspace', () => {
      renderShell('/reviews/doc-1');

      expect(container()?.className).not.toContain('MuiContainer-maxWidthLg');
    });

    it('keeps the new-review wizard centred despite the dynamic segment', () => {
      renderShell('/reviews/new');

      expect(container()?.className).toContain('MuiContainer-maxWidthLg');
    });

    it('drops the width cap on the version comparison', () => {
      renderShell('/reviews/doc-1/compare');

      expect(container()?.className).not.toContain('MuiContainer-maxWidthLg');
    });
  });
});
