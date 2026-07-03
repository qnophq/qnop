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
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ThemeProvider } from '@mui/material/styles';
import type { AnnotationView, RenderedSurface } from '../../api/generated';
import { AnnotationStatus, ExtractionStatus, PlacementStatus } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { DocumentReviewPage } from './DocumentReviewPage';
import { resolveEffectiveVersion } from './resolveEffectiveVersion';
import {
  useDocument,
  useDocumentVersions,
  useOriginalPdf,
  useRenderedDocument,
} from '../../api/hooks/useDocuments';
import { useAnnotations, useCreateAnnotation } from '../../api/hooks/useAnnotations';
import { usePdfDocument } from '../../components/reviews/viewer/usePdfDocument';

vi.mock('../../api/hooks/useDocuments', () => ({
  useDocument: vi.fn(),
  useDocumentVersions: vi.fn(),
  useRenderedDocument: vi.fn(),
  useOriginalPdf: vi.fn(),
}));
vi.mock('../../api/hooks/useAnnotations', () => ({
  useAnnotations: vi.fn(),
  useCreateAnnotation: vi.fn(),
  useDecideAnnotation: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
}));
vi.mock('../../api/hooks/useComments', () => ({
  useComments: vi.fn().mockReturnValue({ isPending: true, isError: false, data: undefined }),
  useAddComment: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
}));
vi.mock('../../components/reviews/viewer/usePdfDocument', () => ({
  usePdfDocument: vi.fn(),
}));
// The hub head talks to its own hooks (participants, workflow, config) and has
// dedicated tests; the page test only checks it receives the right identity.
vi.mock('../../components/reviews/hub/ReviewHubHead', () => ({
  ReviewHubHead: ({ isOwner }: { isOwner: boolean }) => (
    <div data-testid="review-hub-head" data-is-owner={String(isOwner)} />
  ),
}));
// The viewer needs a real pdf.js document and layout; the page test only
// verifies the orchestration around it. The stub exposes a button that
// simulates a completed text selection.
vi.mock('../../components/reviews/viewer/DocumentViewer', () => ({
  DocumentViewer: ({
    annotations,
    pendingAnchor,
    onTextSelected,
  }: {
    annotations: AnnotationView[];
    pendingAnchor: unknown;
    onTextSelected: (sel: unknown, at: unknown) => void;
  }) => (
    <div
      data-testid="document-viewer"
      data-annotation-count={annotations.length}
      data-has-pending={String(Boolean(pendingAnchor))}
    >
      <button
        data-testid="fake-text-select"
        onClick={() =>
          onTextSelected({ surfaceIndex: 0, start: 0, end: 5 }, { left: 120, top: 240 })
        }
      >
        select
      </button>
    </div>
  ),
}));

const SURFACES: RenderedSurface[] = [
  {
    index: 0,
    width: 612,
    height: 792,
    textSpans: [
      {
        text: 'Hello',
        startOffset: 0,
        endOffset: 5,
        box: { x: 0.1, y: 0.1, width: 0.3, height: 0.02 },
      },
    ],
  },
];

const ANNOTATIONS: AnnotationView[] = [
  {
    id: 'a1',
    documentId: 'doc-1',
    authorId: 'u1',
    status: AnnotationStatus.Open,
    placementStatus: PlacementStatus.Moved,
    anchor: {
      region: { surfaceIndex: 0, box: { x: 0.1, y: 0.1, width: 0.2, height: 0.05 } },
      textQuote: { quote: 'Hello' },
    },
    commentCount: 0,
    createdAt: '2026-07-01T10:00:00Z',
    updatedAt: '2026-07-01T10:00:00Z',
  },
];

type Queryish = { data?: unknown; isPending?: boolean; isError?: boolean };
const asQuery = <T,>(value: Queryish) =>
  ({ isPending: false, isError: false, ...value }) as unknown as T;

function seedHappyPath(extractionStatus: ExtractionStatus = ExtractionStatus.Ready) {
  vi.mocked(useDocument).mockReturnValue(
    asQuery({
      data: {
        id: 'doc-1',
        title: 'Supply Contract',
        ownerId: 'u1',
        workflowState: 'IN_REVIEW',
        latestVersionNumber: 2,
        createdAt: '2026-07-01T10:00:00Z',
        updatedAt: '2026-07-01T10:00:00Z',
      },
    }),
  );
  vi.mocked(useDocumentVersions).mockReturnValue(
    asQuery({
      data: {
        versions: [
          { versionNumber: 1, extractionStatus: ExtractionStatus.Ready },
          { versionNumber: 2, extractionStatus },
        ],
      },
    }),
  );
  vi.mocked(useRenderedDocument).mockReturnValue(
    asQuery({
      data: extractionStatus === ExtractionStatus.Ready ? { surfaces: SURFACES } : undefined,
    }),
  );
  vi.mocked(useOriginalPdf).mockReturnValue(asQuery({ data: new ArrayBuffer(4) }));
  vi.mocked(usePdfDocument).mockReturnValue({
    pdf: { numPages: 1 } as unknown as NonNullable<ReturnType<typeof usePdfDocument>['pdf']>,
    error: null,
  });
  vi.mocked(useAnnotations).mockReturnValue(asQuery({ data: { annotations: ANNOTATIONS } }));
  vi.mocked(useCreateAnnotation).mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
  } as unknown as ReturnType<typeof useCreateAnnotation>);
}

function renderPage() {
  render(
    <ThemeProvider theme={buildTheme('light')}>
      <MemoryRouter initialEntries={['/reviews/doc-1']}>
        <Routes>
          <Route path="/reviews/:documentId" element={<DocumentReviewPage />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

// Issue #300: the effective version must honor the URL as soon as ANY loaded
// source (document detail or the version list) knows it — a stale
// latestVersionNumber right after an upload must not push the page back onto
// the old version (which would switch extraction polling off).
describe('resolveEffectiveVersion', () => {
  it('uses the requested version when the detail already knows it', () => {
    expect(resolveEffectiveVersion(2, 2, [1, 2])).toBe(2);
  });

  it('uses the requested version when only the version list knows it (stale detail)', () => {
    expect(resolveEffectiveVersion(2, 1, [1, 2])).toBe(2);
  });

  it('uses the requested version when only the detail knows it (stale list)', () => {
    expect(resolveEffectiveVersion(2, 2, [1])).toBe(2);
  });

  it('falls back to the highest known version when the requested one is unknown', () => {
    expect(resolveEffectiveVersion(9, 2, [1, 2])).toBe(2);
  });

  it('falls back to the latest without a requested version', () => {
    expect(resolveEffectiveVersion(NaN, 3, [1, 2, 3])).toBe(3);
  });

  it('is undefined while nothing is known yet', () => {
    expect(resolveEffectiveVersion(2, 0, [])).toBeUndefined();
  });
});

describe('DocumentReviewPage', () => {
  it('renders title, workflow badge, version info, viewer and annotations', () => {
    seedHappyPath();
    renderPage();

    expect(screen.getByRole('heading', { level: 1, name: 'Supply Contract' })).toBeInTheDocument();
    expect(screen.getByText('In review')).toBeInTheDocument();
    expect(screen.getByTestId('document-viewer')).toHaveAttribute('data-annotation-count', '1');
    expect(screen.getByText('Annotations (1)')).toBeInTheDocument();
    expect(screen.getByText('“Hello”')).toBeInTheDocument();
    // Placement cues are expanded-state details.
    fireEvent.click(screen.getByTestId('annotation-item-a1'));
    expect(screen.getByText('Moved')).toBeInTheDocument();
  });

  it('announces a still-processing version and keeps annotating disabled', () => {
    seedHappyPath(ExtractionStatus.Pending);
    renderPage();

    expect(screen.getByText(/The document is being processed/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Draw region' })).toBeDisabled();
  });

  it('opens the composer only after choosing "Create annotation" from the popup', () => {
    seedHappyPath();
    renderPage();

    // Selection complete → popup at the pointer, mark previewed, no composer yet.
    fireEvent.click(screen.getByTestId('fake-text-select'));
    expect(screen.getByRole('menuitem', { name: /Create annotation/ })).toBeInTheDocument();
    expect(screen.getByTestId('document-viewer')).toHaveAttribute('data-has-pending', 'true');
    expect(screen.queryByTestId('annotation-composer')).not.toBeInTheDocument();

    // Explicitly choosing the item opens the composer; the mark stays.
    fireEvent.click(screen.getByRole('menuitem', { name: /Create annotation/ }));
    expect(screen.getByTestId('annotation-composer')).toBeInTheDocument();
    expect(screen.getByTestId('document-viewer')).toHaveAttribute('data-has-pending', 'true');
  });

  it('discards popup and mark when the popup is dismissed', () => {
    seedHappyPath();
    renderPage();

    fireEvent.click(screen.getByTestId('fake-text-select'));
    // Escape = click-away semantics: the menu closes and the mark is gone.
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' });

    expect(screen.queryByRole('menuitem', { name: /Create annotation/ })).not.toBeInTheDocument();
    expect(screen.getByTestId('document-viewer')).toHaveAttribute('data-has-pending', 'false');
    expect(screen.queryByTestId('annotation-composer')).not.toBeInTheDocument();
  });

  it('shows the anti-enumeration error state', () => {
    vi.mocked(useDocument).mockReturnValue(asQuery({ isError: true }));
    vi.mocked(useDocumentVersions).mockReturnValue(asQuery({}));
    vi.mocked(useRenderedDocument).mockReturnValue(asQuery({}));
    vi.mocked(useOriginalPdf).mockReturnValue(asQuery({}));
    vi.mocked(usePdfDocument).mockReturnValue({ pdf: null, error: null });
    vi.mocked(useAnnotations).mockReturnValue(asQuery({}));
    vi.mocked(useCreateAnnotation).mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as unknown as ReturnType<typeof useCreateAnnotation>);

    renderPage();

    expect(
      screen.getByText('This document does not exist, or you are not a participant of its review.'),
    ).toBeInTheDocument();
  });
});

// Issue #291: the focus view replaces the fixed panel with the drawer + the
// spotlight overlay; the mode toggle persists as a personal preference.
describe('DocumentReviewPage focus mode', () => {
  afterEach(() => {
    localStorage.removeItem('qnop-review-view-mode');
  });

  it('hides the side panel, offers the list drawer and persists the choice', () => {
    localStorage.setItem('qnop-review-view-mode', 'focus');
    seedHappyPath();
    renderPage();

    // No fixed aside — the document takes the full width.
    expect(screen.queryByRole('complementary', { name: 'Annotations' })).not.toBeInTheDocument();

    // The toolbar's counter button opens the full panel in a drawer.
    fireEvent.click(screen.getByRole('button', { name: /Show annotations/ }));
    expect(screen.getByText(/Annotations \(/)).toBeInTheDocument();
  });

  it('switches back to panel mode via the toolbar toggle and stores it', () => {
    localStorage.setItem('qnop-review-view-mode', 'focus');
    seedHappyPath();
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Panel view' }));

    expect(screen.getByRole('complementary', { name: 'Annotations' })).toBeInTheDocument();
    expect(localStorage.getItem('qnop-review-view-mode')).toBe('panel');
  });
});
