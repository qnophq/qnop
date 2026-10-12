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
import { screen } from '@testing-library/react';
import { applyLocale } from '../../i18n';
import { renderWithProviders } from '../../test/renderWithProviders';
import { AchievementRow } from './AchievementRow';
import { profileAchievements, publicProfileAchievements } from './profileModel';
import { TimezoneSetting } from './TimezoneSetting';

describe('profile surfaces in German (issue #464)', () => {
  beforeEach(() => applyLocale('de'));
  afterEach(() => applyLocale('en'));

  it('names achievements and their captions from the German catalog', () => {
    const achievements = profileAchievements({
      reviews: [],
      userId: 'u1',
      hasAvatar: true,
      notificationsOn: false,
    });
    renderWithProviders(<AchievementRow achievements={achievements} />);

    expect(screen.getByText('Gesicht zeigen')).toBeInTheDocument();
    expect(screen.getByLabelText('Gesicht zeigen: Dein Team erkennt dich')).toBeInTheDocument();
    expect(
      screen.getByLabelText('Startschuss: Starte dein erstes Review (gesperrt)'),
    ).toBeInTheDocument();
  });

  it('interpolates milestones into the public captions', () => {
    const achievements = publicProfileAchievements({
      reviewsOwned: 0,
      reviewsParticipating: 0,
      annotationsResolved: 0,
      commentsWritten: 12,
      annotationsRaised: 0,
    } as Parameters<typeof publicProfileAchievements>[0]);
    renderWithProviders(<AchievementRow achievements={achievements} />);

    expect(screen.getByLabelText('Stimme: 10+ Kommentare in Diskussionen')).toBeInTheDocument();
    expect(
      screen.getByLabelText('Scharfes Auge: Erreicht ab 25 Anmerkungen (gesperrt)'),
    ).toBeInTheDocument();
  });

  it('renders the time-zone card in German', () => {
    renderWithProviders(
      <TimezoneSetting
        value="Europe/Berlin"
        isExplicit={false}
        saving={false}
        onChange={vi.fn()}
      />,
    );

    // The card title; the shared picker's field label (issue #836) reads the same.
    expect(screen.getByText('Zeitzone', { selector: 'p' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Zeitzone' })).toBeInTheDocument();
    expect(screen.getByText('Folgt aktuell dem Workspace-Standard.')).toBeInTheDocument();
  });
});
