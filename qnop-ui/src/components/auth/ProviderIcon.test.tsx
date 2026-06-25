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
import { render } from '@testing-library/react';
import type { OidcIconKind } from '../../api/generated';
import { ProviderIcon } from './ProviderIcon';

describe('ProviderIcon', () => {
  it('renders an svg glyph for every icon kind', () => {
    const kinds: OidcIconKind[] = ['github', 'google', 'facebook', 'oidc', 'oauth2'];
    for (const kind of kinds) {
      const { container } = render(<ProviderIcon kind={kind} />);
      expect(container.querySelector('svg')).not.toBeNull();
    }
  });

  it('uses the generic key glyph for oidc/oauth2 and a brand mark otherwise', () => {
    const oidc = render(<ProviderIcon kind="oidc" />).container.querySelector('svg');
    const github = render(<ProviderIcon kind="github" />).container.querySelector('svg');
    // lucide tags its icons with a `lucide-*` class; the inline brand marks do not.
    expect(oidc?.getAttribute('class') ?? '').toMatch(/lucide/);
    expect(github?.getAttribute('class') ?? '').not.toMatch(/lucide/);
  });
});
