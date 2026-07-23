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
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { ParticipantView } from '../../../api/generated';
import { ParticipantKind } from '../../../api/generated';
import { buildTheme } from '../../../theme/theme';
import { DocumentIcon, ReviewerStack } from './ReviewListParts';

const TEAM: ParticipantView = {
  id: 'p1',
  kind: ParticipantKind.Team,
  principalId: 'b0000000-0000-0000-0000-000000000001',
  displayName: 'Alpha',
};

function renderStack(participants: ParticipantView[], anonymous = false) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <ThemeProvider theme={buildTheme('light')}>
        <MemoryRouter>
          <ReviewerStack participants={participants} anonymous={anonymous} />
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('ReviewerStack team avatars (#509)', () => {
  it('loads the team picture from the team-avatar endpoint', () => {
    const { container } = renderStack([TEAM]);

    const img = container.querySelector('img');
    expect(img).not.toBeNull();
    expect(img?.getAttribute('src')).toBe(
      '/api/v1/teams/b0000000-0000-0000-0000-000000000001/avatar',
    );
  });

  it('builds no URL on an anonymised roster — the synthetic token stays dark', () => {
    const { container } = renderStack(
      [{ ...TEAM, principalId: 'a-synthetic-token', displayName: 'Reviewer team' }],
      true,
    );

    expect(container.querySelector('img')).toBeNull();
  });
});

function renderIcon(contentType?: string | null) {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <DocumentIcon contentType={contentType} />
    </ThemeProvider>,
  );
}

describe('DocumentIcon typed sheet (#509)', () => {
  it('stamps a known MIME type with its format label', () => {
    renderIcon('application/pdf');

    const sheet = screen.getByTestId('document-icon');
    expect(sheet).toHaveTextContent('PDF');
    expect(sheet).toHaveAccessibleName('PDF document');
  });

  it('ignores MIME parameters when resolving the format', () => {
    renderIcon('text/markdown; charset=utf-8');

    expect(screen.getByTestId('document-icon')).toHaveTextContent('MD');
  });

  it('falls back to the neutral glyph for an unknown or missing type', () => {
    const { container } = renderIcon('application/octet-stream');

    const sheet = screen.getByTestId('document-icon');
    expect(sheet).toHaveTextContent('');
    expect(sheet).toHaveAttribute('aria-hidden', 'true');
    expect(container.querySelector('svg')).not.toBeNull();

    const { container: absent } = renderIcon(undefined);
    expect(absent.querySelector('svg')).not.toBeNull();
  });
});
