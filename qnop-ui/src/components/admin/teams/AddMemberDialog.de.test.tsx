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
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import type { AdminUserSummary } from '../../../api/generated';
import { applyLocale } from '../../../i18n';
import { buildTheme } from '../../../theme/theme';
import { AddMemberDialog } from './AddMemberDialog';

const CANDIDATES: AdminUserSummary[] = ['Ada', 'Ben'].map((name, i) => ({
  id: `user-${i}`,
  displayName: name,
  username: name.toLowerCase(),
  email: `${name.toLowerCase()}@example.com`,
  role: 'MEMBER' as AdminUserSummary['role'],
  source: 'INTERNAL' as AdminUserSummary['source'],
  enabled: true,
  passwordChangeRequired: false,
  createdAt: '2026-01-01T00:00:00Z',
}));

vi.mock('../../../api/hooks/useAdminUsers', () => ({
  useAdminUsers: () => ({ data: { items: CANDIDATES, total: 2, page: 0, size: 10 } }),
}));
vi.mock('../../../api/hooks/useTeams', () => ({
  useAddTeamMember: () => ({ mutateAsync: vi.fn().mockResolvedValue(undefined) }),
}));

function renderDialog(remainingSlots?: number) {
  render(
    <ThemeProvider theme={buildTheme('light', 'de')}>
      <AddMemberDialog
        open
        teamId="team-1"
        existingMemberIds={[]}
        remainingSlots={remainingSlots}
        onClose={vi.fn()}
      />
    </ThemeProvider>,
  );
}

/** The admin add-members dialog in German (issue #836): plural slot hint and team roles. */
describe('AddMemberDialog in German', () => {
  beforeEach(async () => {
    await applyLocale('de');
  });
  afterEach(async () => {
    await applyLocale('en');
  });

  it('renders the title, the plural slot hint and the counted submit label', async () => {
    const user = userEvent.setup();
    renderDialog(3);

    expect(screen.getByRole('heading', { name: 'Mitglieder hinzufügen' })).toBeInTheDocument();
    expect(screen.getByText('3 weitere Mitglieder passen in dieses Team.')).toBeInTheDocument();
    expect(
      screen.getByText('Gilt für alle, die du in diesem Schritt hinzufügst.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Abbrechen' })).toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: /Benutzer/ }));
    await user.click(await screen.findByRole('option', { name: /Ada/ }));
    await user.click(await screen.findByRole('option', { name: /Ben/ }));
    expect(screen.getByRole('button', { name: '2 hinzufügen' })).toBeInTheDocument();
  });

  it('uses the singular slot hint and the German team role', () => {
    renderDialog(1);

    expect(screen.getByText('Ein weiteres Mitglied passt in dieses Team.')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Teamrolle' })).toHaveTextContent('Mitglied');
  });
});
