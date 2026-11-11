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

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { spaCspPolicy } from './spaCspPolicy';

// Read from the backend source: the UI is tested inside the monorepo checkout,
// so a missing file fails loudly rather than letting the two policies drift.
const writerSource = readFileSync(
  resolve(
    import.meta.dirname,
    '../../../qnop-app/src/main/java/io/qnop/web/security/PathAwareCspHeaderWriter.java',
  ),
  'utf8',
);

/** The `SPA_CSP` constant: its string literals, concatenated, `%s` for the nonce. */
function javaSpaPolicy(): string {
  // Up to the first `";` — the closing quote of the last literal and the statement's end.
  const declaration = /SPA_CSP\s*=([\s\S]*?")\s*;/.exec(writerSource);
  if (!declaration) throw new Error('SPA_CSP not found in PathAwareCspHeaderWriter.java');
  return [...declaration[1].matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1]).join('');
}

describe('spaCspPolicy', () => {
  it('matches the policy the backend sends', () => {
    expect(spaCspPolicy('NONCE')).toBe(javaSpaPolicy().replace('%s', 'NONCE'));
  });

  it('allows styles by nonce only', () => {
    expect(spaCspPolicy('abc')).toContain("style-src 'self' 'nonce-abc';");
    expect(spaCspPolicy('abc')).not.toContain('unsafe-inline');
  });
});
