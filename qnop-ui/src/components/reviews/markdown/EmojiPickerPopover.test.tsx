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
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { applyLocale } from '../../../i18n';
import { buildTheme } from '../../../theme/theme';
import { EmojiPickerPopover } from './EmojiPickerPopover';

const { pickerProps } = vi.hoisted(() => ({ pickerProps: [] as Record<string, unknown>[] }));

vi.mock('emoji-mart', () => ({
  Picker: class {
    constructor(props: Record<string, unknown>) {
      pickerProps.push(props);
      const element = document.createElement('div');
      element.setAttribute('data-testid', 'emoji-picker');
      return element;
    }
  },
}));
vi.mock('@emoji-mart/data', () => ({ default: { emojis: {} } }));
vi.mock('@emoji-mart/data/i18n/de.json', () => ({ default: { search: 'Suche' } }));

function renderPicker() {
  const anchor = document.createElement('button');
  document.body.appendChild(anchor);
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <EmojiPickerPopover anchorEl={anchor} onClose={vi.fn()} onSelect={vi.fn()} />
    </ThemeProvider>,
  );
}

/** The picker's own strings follow the UI language (issue #836) — bundled, never fetched. */
describe('EmojiPickerPopover language', () => {
  let fetchSpy: ReturnType<typeof vi.spyOn>;
  beforeEach(() => {
    pickerProps.length = 0;
    fetchSpy = vi.spyOn(globalThis, 'fetch');
  });
  afterEach(async () => {
    cleanup();
    fetchSpy.mockRestore();
    await applyLocale('en');
  });

  it('passes neither locale nor strings in English', async () => {
    renderPicker();

    await screen.findByTestId('emoji-picker');
    expect(pickerProps[0]).not.toHaveProperty('locale');
    expect(pickerProps[0]).not.toHaveProperty('i18n');
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('hands the bundled German strings to the picker in German', async () => {
    await applyLocale('de');
    renderPicker();

    await screen.findByTestId('emoji-picker');
    expect(pickerProps[0]).toMatchObject({ locale: 'de', i18n: { search: 'Suche' } });
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('falls back to the English picker when the German strings fail to load', async () => {
    // The chunk import rejects, as a failed network fetch of it would.
    vi.doMock('@emoji-mart/data/i18n/de.json', () => {
      throw new Error('chunk failed');
    });
    vi.resetModules();
    await applyLocale('de');
    renderPicker();

    await screen.findByTestId('emoji-picker');
    expect(pickerProps[0]).not.toHaveProperty('locale');
    expect(pickerProps[0]).not.toHaveProperty('i18n');
    await waitFor(() =>
      expect(
        screen.queryByText('Die Emoji-Auswahl konnte nicht geladen werden.'),
      ).not.toBeInTheDocument(),
    );
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
