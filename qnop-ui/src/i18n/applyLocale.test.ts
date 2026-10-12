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
import i18n, { applyLocale } from '.';

describe('applyLocale', () => {
  it('lets the newest request win when an older catalog loads last', async () => {
    // German is not loaded yet, so its request waits on the lazy chunk while the
    // flip back to English (bundled) completes first — the old request must not
    // switch the UI back to German once its chunk arrives.
    const toGerman = applyLocale('de');
    const toEnglish = applyLocale('en');

    await expect(toEnglish).resolves.toBe(true);
    await expect(toGerman).resolves.toBe(false);
    expect(i18n.language).toBe('en');
    expect(document.documentElement.lang).toBe('en');
  });

  it('switches when uncontested', async () => {
    await expect(applyLocale('de')).resolves.toBe(true);
    expect(i18n.language).toBe('de');
    expect(document.documentElement.lang).toBe('de');
    await applyLocale('en');
  });
});
