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
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ThemeProvider } from '@mui/material/styles';
import type { DiffChange, DocumentVersionSummary } from '../../api/generated';
import { DiffChangeType, ExtractionStatus } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { VersionComparePage } from './VersionComparePage';
import {
  useDocument,
  useDocumentVersions,
  useOriginalPdf,
  useRenderedDocument,
} from '../../api/hooks/useDocuments';
import { useParticipants } from '../../api/hooks/useReviews';
import { useVersionDiff } from '../../api/hooks/useVersionDiff';
import { usePdfDocument } from '../../components/reviews/viewer/usePdfDocument';

vi.mock('../../api/hooks/useDocuments', () => ({
  useDocument: vi.fn(),
  useDocumentVersions: vi.fn(),
  useOriginalPdf: vi.fn(),
  useRenderedDocument: vi.fn(),
}));
vi.mock('../../api/hooks/useReviews', () => ({
  useParticipants: vi.fn(),
}));
vi.mock('../../api/hooks/useVersionDiff', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  useVersionDiff: vi.fn(),
}));
vi.mock('../../components/reviews/viewer/usePdfDocument', () => ({
  usePdfDocument: vi.fn(),
}));
// The pane needs a real pdf.js document and layout; the page test verifies the
// orchestration around it and drives selection through the summary cards.
vi.mock('../../components/reviews/diff/ComparePane', () => ({
  ComparePane: ({
    side,
    versionNumber,
    changes,
    activeChangeIndex,
  }: {
    side: string;
    versionNumber: number;
    changes: unknown[];
    activeChangeIndex: number | null;
  }) => (
    <div
      data-testid={`pane-${side}`}
      data-version={versionNumber}
      data-changes={changes.length}
      data-active={String(activeChangeIndex)}
    />
  ),
}));

const version = (
  versionNumber: number,
  extractionStatus: ExtractionStatus = ExtractionStatus.Ready,
): DocumentVersionSummary => ({
  versionNumber,
  contentType: 'application/pdf',
  sizeBytes: 1000,
  contentHash: `hash-${versionNumber}`,
  extractionStatus,
  createdBy: 'u1',
  createdAt: '2026-07-01T10:00:00Z',
});

const CHANGES: DiffChange[] = [
  {
    type: DiffChangeType.Inserted,
    fromText: '',
    toText: 'brand new sentence',
    fromLocations: [],
    toLocations: [{ surfaceIndex: 0, box: { x: 0.1, y: 0.1, width: 0.4, height: 0.02 } }],
  },
];

function mockData({
  versions = [version(1), version(2), version(3)],
  diff = {
    data: { fromVersion: 2, toVersion: 3, changes: CHANGES },
    isError: false,
    error: null,
  },
}: {
  versions?: DocumentVersionSummary[];
  diff?: unknown;
} = {}) {
  vi.mocked(useDocument).mockReturnValue({
    isPending: false,
    isError: false,
    data: { id: 'd1', title: 'Framework agreement', ownerId: 'u1', workflowState: 'IN_REVIEW' },
  } as never);
  vi.mocked(useDocumentVersions).mockReturnValue({
    isPending: false,
    data: { versions },
  } as never);
  vi.mocked(useParticipants).mockReturnValue({
    data: { participants: [{ principalId: 'u1', displayName: 'Maxim' }] },
  } as never);
  vi.mocked(useOriginalPdf).mockReturnValue({ data: undefined } as never);
  vi.mocked(useRenderedDocument).mockReturnValue({ data: undefined } as never);
  vi.mocked(usePdfDocument).mockReturnValue({ pdf: null, error: null });
  vi.mocked(useVersionDiff).mockReturnValue(diff as never);
}

function renderPage(initialEntry = '/reviews/d1/compare') {
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/reviews/:documentId/compare" element={<VersionComparePage />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('VersionComparePage', () => {
  it('defaults the pair to previous ↔ latest extracted version', () => {
    mockData();
    renderPage();
    expect(screen.getByTestId('pane-from')).toHaveAttribute('data-version', '2');
    expect(screen.getByTestId('pane-to')).toHaveAttribute('data-version', '3');
  });

  it('takes a valid pair from the URL and skips unextracted versions as defaults', () => {
    mockData({ versions: [version(1), version(2), version(3, ExtractionStatus.Pending)] });
    renderPage('/reviews/d1/compare?from=1&to=2');
    expect(screen.getByTestId('pane-from')).toHaveAttribute('data-version', '1');
    expect(screen.getByTestId('pane-to')).toHaveAttribute('data-version', '2');
  });

  it('guards when fewer than two versions are extracted', () => {
    mockData({ versions: [version(1), version(2, ExtractionStatus.Pending)] });
    renderPage();
    expect(screen.getByText(/needs at least two versions/)).toBeInTheDocument();
    expect(screen.queryByTestId('pane-from')).not.toBeInTheDocument();
  });

  it('feeds the changes into panes, toolbar statistics and summary', () => {
    mockData();
    renderPage();
    expect(screen.getByTestId('pane-to')).toHaveAttribute('data-changes', '1');
    const stats = within(screen.getByTestId('toolbar-diff-stats'));
    expect(stats.getByText('+3')).toBeInTheDocument(); // "brand new sentence"
    expect(stats.getByText('−0')).toBeInTheDocument();
    expect(screen.getByText('brand new sentence')).toBeInTheDocument();
  });

  it('marks the selected change as active on both panes', () => {
    mockData();
    renderPage();
    fireEvent.click(screen.getByTestId('change-card-0'));
    expect(screen.getByTestId('pane-from')).toHaveAttribute('data-active', '0');
    expect(screen.getByTestId('pane-to')).toHaveAttribute('data-active', '0');
  });

  it('explains a still-pending extraction on the diff error path', () => {
    mockData({
      diff: {
        data: undefined,
        isError: true,
        error: {
          isAxiosError: true,
          response: { status: 409, data: { code: 'EXTRACTION_PENDING' } },
        },
      },
    });
    renderPage();
    expect(screen.getByText(/still being processed/)).toBeInTheDocument();
  });

  // The changes rail collapses to a narrow strip and the choice persists like
  // the other viewer preferences (issue #369).
  describe('changes rail', () => {
    afterEach(() => {
      localStorage.removeItem('qnop-compare-rail-collapsed');
    });

    it('collapses to a strip with the change count and persists the choice', () => {
      mockData();
      renderPage();

      fireEvent.click(screen.getByTestId('rail-collapse'));
      expect(screen.queryByTestId('change-summary')).not.toBeInTheDocument();
      expect(within(screen.getByTestId('rail-collapsed')).getByText('1')).toBeInTheDocument();
      expect(localStorage.getItem('qnop-compare-rail-collapsed')).toBe('1');

      fireEvent.click(screen.getByTestId('rail-expand'));
      expect(screen.getByTestId('change-summary')).toBeInTheDocument();
      expect(localStorage.getItem('qnop-compare-rail-collapsed')).toBe('0');
    });

    it('starts collapsed when the preference is stored', () => {
      localStorage.setItem('qnop-compare-rail-collapsed', '1');
      mockData();
      renderPage();

      expect(screen.getByTestId('rail-collapsed')).toBeInTheDocument();
      expect(screen.queryByTestId('change-summary')).not.toBeInTheDocument();
    });
  });
});
