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

import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import type { MailTemplateResponse, MailTemplateVariant } from '../../api/generated';
import { applyLocale } from '../../i18n';
import { renderWithProviders } from '../../test/renderWithProviders';
import { MailTemplatesListPage } from './MailTemplatesListPage';

const variant = (
  locale: string,
  overrides: Partial<MailTemplateVariant> = {},
): MailTemplateVariant => ({
  locale,
  source: 'DEFAULT',
  shipped: true,
  instanceDefault: false,
  subject: `Subject ${locale}`,
  ...overrides,
});

const template = (
  key: string,
  friendlyName: string,
  variants: MailTemplateVariant[],
): MailTemplateResponse => ({
  key,
  friendlyName,
  locale: 'de',
  subject: 'Subject de',
  bodyPlain: 'Body',
  source: 'DEFAULT',
  placeholders: ['siteName'],
  defaultSubject: 'Subject de',
  defaultBodyPlain: 'Body',
  variants,
});

const TEMPLATES: MailTemplateResponse[] = [
  template('auth.password_reset', 'Password reset', [
    variant('en'),
    variant('de', {
      instanceDefault: true,
      source: 'DATABASE',
      updatedAt: new Date().toISOString(),
      updatedByName: 'Ada Admin',
    }),
    variant('it', { shipped: false }),
  ]),
  template('auth.registration_verification', 'Account verification', [
    variant('en'),
    variant('de', { instanceDefault: true }),
    variant('it', { shipped: false }),
  ]),
  // A template the catalog does not know, e.g. one an extension adds.
  template('ext.custom_notice', 'Custom notice', [
    variant('en'),
    variant('de', { instanceDefault: true }),
    variant('it', { shipped: false }),
  ]),
];

vi.mock('../../api/hooks/useMailTemplates', () => ({
  useMailTemplates: () => ({
    data: { templates: TEMPLATES, locales: ['en', 'de', 'it'], defaultLocale: 'de' },
    isLoading: false,
    isFetching: false,
    isError: false,
  }),
}));

describe('MailTemplatesListPage', () => {
  afterEach(async () => {
    await applyLocale('en');
  });

  it('lays the templates out against every offered language', () => {
    renderWithProviders(<MailTemplatesListPage />);

    const headers = screen.getAllByRole('columnheader').map((cell) => cell.textContent);
    expect(headers).toEqual(['Template', 'ENEnglish', 'DEGerman', 'ITItalian']);
    expect(screen.getAllByRole('row')).toHaveLength(4);
    expect(screen.getByText('auth.password_reset')).toBeTruthy();
  });

  it('stars the default language', () => {
    renderWithProviders(<MailTemplatesListPage />);

    const german = screen.getAllByRole('columnheader')[2];
    expect(within(german).getByLabelText('Default language')).toBeTruthy();
  });

  it('names each cell by its status and links it to the editor in that language', () => {
    renderWithProviders(<MailTemplatesListPage />);

    const custom = screen.getByRole('link', {
      name: 'Password reset in German (default language): Customised',
    });
    expect(custom.getAttribute('href')).toBe('/admin/email/templates/auth.password_reset/de');
    expect(screen.getByRole('link', { name: 'Password reset in English: Built-in' })).toBeTruthy();
    const missing = screen.getByRole('link', {
      name: 'Password reset in Italian: Missing — English is sent',
    });
    expect(missing.getAttribute('href')).toBe('/admin/email/templates/auth.password_reset/it');
    expect(within(missing).getByText('→ EN')).toBeTruthy();
  });

  it('links the template name to its default language', () => {
    renderWithProviders(<MailTemplatesListPage />);

    expect(screen.getByRole('link', { name: 'Account verification' }).getAttribute('href')).toBe(
      '/admin/email/templates/auth.registration_verification/de',
    );
  });

  it('names the templates in the UI language, the server name for unknown ones', async () => {
    await applyLocale('de');
    renderWithProviders(<MailTemplatesListPage />);

    expect(screen.getByRole('link', { name: 'Passwort zurücksetzen' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Kontobestätigung' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Custom notice' })).toBeTruthy();
    // The cells' accessible names carry the translated name too.
    expect(
      screen.getAllByRole('link', { name: /^Passwort zurücksetzen auf .+: Mitgeliefert$/ }),
    ).toHaveLength(1);
  });

  it('explains the marks in a legend', () => {
    renderWithProviders(<MailTemplatesListPage />);

    expect(screen.getByText('Customised')).toBeTruthy();
    expect(screen.getByText('Built-in')).toBeTruthy();
    expect(screen.getByText('Missing — English is sent')).toBeTruthy();
  });
});
