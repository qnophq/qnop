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

import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { renderHook } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { useReviewPermalink, type PermalinkView } from './useReviewPermalink';

function wrapperFor(entry: string) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/reviews/:documentId" element={<>{children}</>} />
        </Routes>
      </MemoryRouter>
    );
  };
}

function buildAt(entry: string, view?: PermalinkView) {
  return renderHook(() => useReviewPermalink(view), { wrapper: wrapperFor(entry) }).result.current;
}

const origin = window.location.origin;

describe('useReviewPermalink', () => {
  it('keeps the pretty route segment as-is, so a slug URL yields a slug permalink (#411)', () => {
    const build = buildAt('/reviews/supply-contract');
    expect(build('a1')).toBe(`${origin}/reviews/supply-contract?annotation=a1`);
  });

  it('carries the current document view so a link reopens the same surface', () => {
    const build = buildAt('/reviews/doc-1', 'focus');
    expect(build('a1')).toBe(`${origin}/reviews/doc-1?annotation=a1&view=focus`);
  });

  it('adds the comment param only when a comment id is given', () => {
    const build = buildAt('/reviews/doc-1', 'panel');
    expect(build('a1', 'c9')).toBe(`${origin}/reviews/doc-1?annotation=a1&comment=c9&view=panel`);
  });

  it('omits the view when none is passed (recipient keeps their preference)', () => {
    const build = buildAt('/reviews/doc-1');
    expect(build('a1', 'c9')).toBe(`${origin}/reviews/doc-1?annotation=a1&comment=c9`);
  });
});
