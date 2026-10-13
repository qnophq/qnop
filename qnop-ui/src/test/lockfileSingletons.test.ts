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

/**
 * Packages that must resolve to exactly one version (issue #857). CodeMirror's extension system
 * relies on instanceof checks across @codemirror/state and @codemirror/view; a second copy — which
 * a dependency update can introduce through lagging transitive packages — makes the mail template
 * editor crash on load. The pnpm overrides in pnpm-workspace.yaml keep those two single; the others
 * here (CodeMirror's language layer, Lezer, React) are just as instance-sensitive and are not
 * forced, so this is their only guard.
 */
const SINGLETONS = [
  '@codemirror/state',
  '@codemirror/view',
  '@codemirror/language',
  '@lezer/common',
  'react',
  'react-dom',
];

const lockfile = readFileSync(resolve(import.meta.dirname, '../../pnpm-lock.yaml'), 'utf8');

/** Every version of `name` the lockfile's package section resolves. */
function resolvedVersions(name: string): string[] {
  const escaped = name.replace(/[/@]/g, (c) => `\\${c}`);
  // Scoped names are quoted in the lockfile, unscoped ones are not; a peer-resolved snapshot key
  // carries a "(peer@x)" suffix, which the version capture stops at.
  const pattern = new RegExp(`^  '?${escaped}@([^':(]+)`, 'gm');
  return [...new Set([...lockfile.matchAll(pattern)].map((match) => match[1]))];
}

describe('lockfile singletons', () => {
  it.each(SINGLETONS)('resolves exactly one version of %s', (name) => {
    // Not zero either: a regex that matched nothing would pass silently.
    expect(resolvedVersions(name)).toHaveLength(1);
  });
});
