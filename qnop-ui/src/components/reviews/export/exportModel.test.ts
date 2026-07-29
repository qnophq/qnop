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
  isFormatAvailable,
  effectiveFields,
  loadSettings,
  saveSettings,
} from './exportModel';

beforeEach(() => localStorage.clear());

describe('exportModel', () => {
  it('knows which formats can carry an image', () => {
    // The switch is offered per format, so this flag decides whether a user is
    // shown a control that could do nothing.
    const byId = Object.fromEntries(EXPORT_FORMATS.map((format) => [format.id, format]));
    expect(byId.xlsx.supportsLogo).toBe(true);
    expect(byId.docx.supportsLogo).toBe(true);
    expect(byId.pdf.supportsLogo).toBe(true);
    expect(byId.html.supportsLogo).toBe(true);
  });

  it('offers only the formats that are still on the roadmap', () => {
    // CSV and Markdown were dropped (#636/#638) — a wizard that still listed
    // them as "planned" would be promising work nobody intends to do.
    const ids = EXPORT_FORMATS.map((format) => format.id);
    expect(ids).toEqual(['xlsx', 'docx', 'html', 'pdf']);
  });

  it("treats availability as the server's answer, not the release's", () => {
    const byId = Object.fromEntries(EXPORT_FORMATS.map((format) => [format.id, format]));

    // A planned format is never available, whatever the server lists.
    expect(isFormatAvailable(byId.html, ['xlsx', 'docx', 'pdf', 'html'])).toBe(false);
    // A shipped one follows the server: PDF needs an office converter (#639).
    expect(isFormatAvailable(byId.pdf, ['xlsx', 'docx'])).toBe(false);
    expect(isFormatAvailable(byId.pdf, ['xlsx', 'docx', 'pdf'])).toBe(true);
    // Silence is not a refusal — an older server, or a config request still in
    // flight, must not take a working format away.
    expect(isFormatAvailable(byId.pdf, undefined)).toBe(true);
    expect(isFormatAvailable(byId.pdf, [])).toBe(true);
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

  it("opens on the reader's own timezone", () => {
    // Not UTC: the wizard should show the times the reader actually works in,
    // resolved once for the whole app (ADR-0041) rather than per feature.
    expect(defaultSettings('Europe/Berlin').timezone).toBe('Europe/Berlin');
    expect(loadSettings('Europe/Berlin').timezone).toBe('Europe/Berlin');
  });

  it("yields to the reader's zone when the stored one no longer exists", () => {
    localStorage.setItem(
      'qnop-export-settings',
      JSON.stringify({ ...defaultSettings(), timezone: 'Middle/Earth' }),
    );

    // A zone the runtime cannot resolve would break every timestamp in the file.
    expect(loadSettings('Europe/Berlin').timezone).toBe('Europe/Berlin');
  });

  it('keeps an explicitly chosen timezone across exports', () => {
    saveSettings({ ...defaultSettings('Europe/Berlin'), timezone: 'Asia/Tokyo' });

    // An explicit choice outranks the account's zone: someone reporting to a
    // Tokyo counterpart picked it on purpose.
    expect(loadSettings('Europe/Berlin').timezone).toBe('Asia/Tokyo');
  });

  it('keeps the required field however the selection is cleared', () => {
    expect(effectiveFields([])).toEqual(['taskKey']);
  });
});
