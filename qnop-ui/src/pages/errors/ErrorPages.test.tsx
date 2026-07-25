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

import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../../theme/theme';
import { ErrorState } from './ErrorState';
import { NotFoundIllustration } from './illustrations';
import { NotFoundPage } from './NotFoundPage';
import { ForbiddenPage } from './ForbiddenPage';
import { ConflictPage } from './ConflictPage';
import { ServerErrorPage } from './ServerErrorPage';
import { MaintenancePage } from './MaintenancePage';
import { RateLimitPage } from './RateLimitPage';
import { OfflinePage } from './OfflinePage';

function renderAt(element: React.ReactElement) {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter initialEntries={['/errors-under-test']}>
        <Routes>
          <Route path="/errors-under-test" element={element} />
          <Route path="/" element={<div data-testid="home" />} />
          <Route path="/reviews" element={<div data-testid="reviews" />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

describe('ErrorState shell (#611)', () => {
  it('renders code chip, headline, decorative illustration and a working way onward', () => {
    renderAt(
      <ErrorState
        code="404"
        title="Headline"
        message="Body copy."
        illustration={<NotFoundIllustration />}
      />,
    );
    expect(screen.getByText('404')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Headline');
    // The illustration is decorative - hidden from assistive tech.
    const illustration = screen.getByTestId('error-state').querySelector('[aria-hidden="true"]');
    expect(illustration?.querySelector('svg')).toBeInTheDocument();
    // Never a dead end: the default action really navigates.
    fireEvent.click(screen.getByRole('link', { name: 'Back to dashboard' }));
    expect(screen.getByTestId('home')).toBeInTheDocument();
  });

  it('announces interrupting states via role=alert, but not route states', () => {
    renderAt(
      <ErrorState
        title="Interrupted"
        message="m"
        tone="alert"
        illustration={<NotFoundIllustration />}
      />,
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });
});

describe('error pages (#611)', () => {
  it('404 is playful about the page, never the user, and links onward twice', () => {
    renderAt(<NotFoundPage />);
    expect(screen.getByText('404')).toBeInTheDocument();
    expect(screen.getByText(/paper plane/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('link', { name: 'My reviews' }));
    expect(screen.getByTestId('reviews')).toBeInTheDocument();
  });

  it('403 frames a boundary without accusing', () => {
    renderAt(<ForbiddenPage />);
    expect(screen.getByText('403')).toBeInTheDocument();
    expect(screen.getByText(/binder is locked/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to dashboard' })).toBeInTheDocument();
  });

  it('409 offers a reload and reassures nothing was lost', () => {
    renderAt(<ConflictPage />);
    expect(screen.getByText('409')).toBeInTheDocument();
    expect(screen.getByText(/nothing of yours was lost/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload this page' })).toBeInTheDocument();
  });

  it('500 takes the blame itself and retries', () => {
    renderAt(<ServerErrorPage />);
    expect(screen.getByText('500')).toBeInTheDocument();
    expect(screen.getByText(/not your doing/i)).toBeInTheDocument();
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('503 reassures and retries', () => {
    renderAt(<MaintenancePage />);
    expect(screen.getByText('503')).toBeInTheDocument();
    expect(screen.getByText(/back shortly/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('429 stays light and offers the way back', () => {
    renderAt(<RateLimitPage />);
    expect(screen.getByText('429')).toBeInTheDocument();
    expect(screen.getByText(/stamp champion/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to dashboard' })).toBeInTheDocument();
  });

  it('offline is calm, has no code chip, and offers a retry', () => {
    renderAt(<OfflinePage />);
    expect(screen.getByText(/paper cups/i)).toBeInTheDocument();
    expect(screen.queryByText(/^\d{3}$/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry connection' })).toBeInTheDocument();
  });
});
