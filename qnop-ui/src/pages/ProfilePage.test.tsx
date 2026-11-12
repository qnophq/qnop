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

import { fireEvent, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '../test/renderWithProviders';
import { ProfilePage } from './ProfilePage';

const state = vi.hoisted(() => ({
  settings: [] as { key: string; value: string }[],
  mutate: vi.fn(),
}));

vi.mock('../api/hooks/useUserSettings', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/hooks/useUserSettings')>()),
  useUserSettings: () => ({ data: { settings: state.settings }, isPending: false }),
  useUpdateUserSettings: () => ({ mutate: state.mutate, isPending: false }),
}));
vi.mock('../api/hooks/useReviews', () => ({
  useReviews: () => ({ data: { items: [] } }),
}));
vi.mock('../api/hooks/useConfig', () => ({ useConfig: () => ({ data: {} }) }));
vi.mock('../api/hooks/useAvatar', () => ({
  useUploadMyAvatar: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useRemoveMyAvatar: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));
vi.mock('../api/hooks/useDisplayTimezone', () => ({ useDisplayTimezone: () => 'UTC' }));
vi.mock('../api/hooks/useDisplayLanguage', () => ({
  useExplicitLanguage: () => '',
  useInheritedLanguage: () => 'en',
}));
vi.mock('../components/profile/LanguageSetting', () => ({ LanguageSetting: () => null }));
vi.mock('../components/profile/TimezoneSetting', () => ({ TimezoneSetting: () => null }));

const LABEL = "Email me when I'm invited to a team";

/** The team-invitation mail opt-out on the profile page (issue #948). */
describe('ProfilePage team-invitation mails', () => {
  beforeEach(() => {
    state.settings = [];
    state.mutate.mockReset();
  });

  it('is on by default and switching it off stores the opt-out', () => {
    renderWithProviders(<ProfilePage />);

    const toggle = screen.getByRole('switch', { name: LABEL });
    expect(toggle).toBeChecked();

    fireEvent.click(toggle);
    expect(state.mutate).toHaveBeenCalledWith(
      { email_team_invitations: 'false' },
      expect.any(Object),
    );
  });

  it('reflects a stored opt-out and switches back on', () => {
    state.settings = [{ key: 'email_team_invitations', value: 'false' }];
    renderWithProviders(<ProfilePage />);

    const toggle = screen.getByRole('switch', { name: LABEL });
    expect(toggle).not.toBeChecked();

    fireEvent.click(toggle);
    expect(state.mutate).toHaveBeenCalledWith(
      { email_team_invitations: 'true' },
      expect.any(Object),
    );
  });
});
