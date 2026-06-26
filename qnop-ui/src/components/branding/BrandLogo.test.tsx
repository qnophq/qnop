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
import { fireEvent, render, screen } from '@testing-library/react';
import { BrandLogo } from './BrandLogo';

const fallback = <span>FALLBACK</span>;

describe('BrandLogo', () => {
  it('renders the image when a URL is given', () => {
    render(<BrandLogo url="/logo.svg?v=abc" alt="qnop" fallback={fallback} />);

    expect(screen.getByAltText('qnop').getAttribute('src')).toBe('/logo.svg?v=abc');
    expect(screen.queryByText('FALLBACK')).toBeNull();
  });

  it('renders the fallback when there is no URL', () => {
    render(<BrandLogo url={null} alt="qnop" fallback={fallback} />);

    expect(screen.getByText('FALLBACK')).toBeTruthy();
    expect(screen.queryByAltText('qnop')).toBeNull();
  });

  it('falls back when the image fails to load', () => {
    render(<BrandLogo url="/broken.svg?v=1" alt="qnop" fallback={fallback} />);

    fireEvent.error(screen.getByAltText('qnop'));

    expect(screen.getByText('FALLBACK')).toBeTruthy();
    expect(screen.queryByAltText('qnop')).toBeNull();
  });

  it('retries the image after a failure once the URL changes', () => {
    const { rerender } = render(<BrandLogo url="/broken.svg?v=1" alt="qnop" fallback={fallback} />);
    fireEvent.error(screen.getByAltText('qnop'));
    expect(screen.getByText('FALLBACK')).toBeTruthy();

    rerender(<BrandLogo url="/fixed.svg?v=2" alt="qnop" fallback={fallback} />);

    expect(screen.getByAltText('qnop').getAttribute('src')).toBe('/fixed.svg?v=2');
    expect(screen.queryByText('FALLBACK')).toBeNull();
  });
});
