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

import { beforeEach, describe, expect, it } from 'vitest';
import {
  EXPORT_DATE_FORMATS,
  EXPORT_FORMATS,
  defaultSettings,
  effectiveFields,
  loadSettings,
  saveSettings,
} from './exportModel';

beforeEach(() => localStorage.clear());

describe('exportModel', () => {
  it('knows which formats can carry an image', () => {
    // The switch is offered per format, so this flag decides whether a user is
    // shown a control that could do nothing. Text formats have nowhere to put one.
    const byId = Object.fromEntries(EXPORT_FORMATS.map((format) => [format.id, format]));
    expect(byId.xlsx.supportsLogo).toBe(true);
    expect(byId.docx.supportsLogo).toBe(true);
    expect(byId.pdf.supportsLogo).toBe(true);
    expect(byId.html.supportsLogo).toBe(true);
    expect(byId.md.supportsLogo).toBe(false);
    expect(byId.csv.supportsLogo).toBe(false);
  });

  it('starts with everything included', () => {
    const settings = defaultSettings();

    expect(settings.includeLogo).toBe(true);
    expect(settings.includeComments).toBe(true);
    expect(settings.dateFormat).toBe('iso');
  });

  it('offers date formats whose samples are all different', () => {
    // Two entries rendering the same string would be a choice without a
    // difference — and the sample is what the user actually picks by.
    const samples = EXPORT_DATE_FORMATS.map((entry) => entry.sample);
    expect(new Set(samples).size).toBe(samples.length);
  });

  it('remembers the presentation choices', () => {
    saveSettings({ ...defaultSettings(), includeLogo: false, dateFormat: 'european' });

    const loaded = loadSettings();

    expect(loaded.includeLogo).toBe(false);
    expect(loaded.dateFormat).toBe('european');
  });

  it('falls back when a stored date format is no longer offered', () => {
    localStorage.setItem(
      'qnop-export-settings',
      JSON.stringify({ ...defaultSettings(), dateFormat: 'klingon' }),
    );

    // A selection from an older release must not leave the wizard in a state
    // the user cannot see or fix.
    expect(loadSettings().dateFormat).toBe('iso');
  });

  it('keeps the required field however the selection is cleared', () => {
    expect(effectiveFields([])).toEqual(['taskKey']);
  });
});
