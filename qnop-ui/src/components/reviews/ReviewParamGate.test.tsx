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

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { useDocumentBySlug } from '../../api/hooks/useDocuments';
import { ReviewParamGate } from './ReviewParamGate';
import { useReviewDocumentId } from './reviewDocumentId';

vi.mock('../../api/hooks/useDocuments', () => ({
  useDocumentBySlug: vi.fn(),
}));

const DOC_ID = '123e4567-e89b-12d3-a456-426614174000';

function Probe() {
  return <div data-testid="probe">{useReviewDocumentId()}</div>;
}

function renderGate(segment: string) {
  render(
    <MemoryRouter initialEntries={[`/reviews/${segment}`]}>
      <Routes>
        <Route
          path="/reviews/:documentId"
          element={
            <ReviewParamGate>
              <Probe />
            </ReviewParamGate>
          }
        />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(useDocumentBySlug).mockReturnValue({
    isPending: true,
    isError: false,
    data: undefined,
  } as never);
});

describe('ReviewParamGate', () => {
  it('passes a UUID segment straight through without resolving', () => {
    renderGate(DOC_ID);
    expect(screen.getByTestId('probe')).toHaveTextContent(DOC_ID);
    expect(vi.mocked(useDocumentBySlug)).toHaveBeenCalledWith(DOC_ID, false);
  });

  it('shows a spinner while a slug segment resolves', () => {
    renderGate('q3-contract-review');
    expect(screen.getByLabelText('Resolving review')).toBeInTheDocument();
    expect(screen.queryByTestId('probe')).not.toBeInTheDocument();
    expect(vi.mocked(useDocumentBySlug)).toHaveBeenCalledWith('q3-contract-review', true);
  });

  it('renders children with the canonical id once the slug resolves', () => {
    vi.mocked(useDocumentBySlug).mockReturnValue({
      isPending: false,
      isError: false,
      data: { id: DOC_ID, slug: 'q3-contract-review' },
    } as never);
    renderGate('q3-contract-review');
    expect(screen.getByTestId('probe')).toHaveTextContent(DOC_ID);
  });

  it('shows the not-found state for an unknown slug', () => {
    vi.mocked(useDocumentBySlug).mockReturnValue({
      isPending: false,
      isError: true,
      data: undefined,
    } as never);
    renderGate('no-such-review');
    expect(screen.getByText('Review not found')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to reviews' })).toHaveAttribute(
      'href',
      '/reviews',
    );
  });
});

describe('useReviewDocumentId', () => {
  it('falls back to the raw route segment without a gate (unit-test rendering)', () => {
    render(
      <MemoryRouter initialEntries={['/reviews/d1']}>
        <Routes>
          <Route path="/reviews/:documentId" element={<Probe />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByTestId('probe')).toHaveTextContent('d1');
  });
});
