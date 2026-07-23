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
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ThemeProvider } from '@mui/material/styles';
import type { AnnotationView, RenderedSurface } from '../../api/generated';
import { AnnotationStatus, ExtractionStatus, PlacementStatus } from '../../api/generated';
import { buildTheme } from '../../theme/theme';
import { DocumentReviewPage } from './DocumentReviewPage';
import { pdfFetchVersion, resolveEffectiveVersion } from './resolveEffectiveVersion';
import {
  useDocument,
  useDocumentVersions,
  useOriginalPdf,
  useRenderedDocument,
} from '../../api/hooks/useDocuments';
import { useAnnotations, useCreateAnnotation } from '../../api/hooks/useAnnotations';
import { useComments } from '../../api/hooks/useComments';
import { usePdfDocument } from '../../components/reviews/viewer/usePdfDocument';
import { useAuthStore } from '../../stores/authStore';

// The reaction toggles (issue #410) reach for the query client; the data
// hooks above stay mocked, so a bare client per file is all the tests need.
const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

vi.mock('../../api/hooks/useDocuments', () => ({
  useDocument: vi.fn(),
  useDocumentVersions: vi.fn(),
  useRenderedDocument: vi.fn(),
  useOriginalPdf: vi.fn(),
}));
vi.mock('../../api/hooks/useAnnotations', () => ({
  useConfirmPlacement: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
  useReattachPlacement: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
  useAnnotations: vi.fn(),
  useCreateAnnotation: vi.fn(),
  useResolveAnnotation: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
  useReopenAnnotation: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
  useDismissAnnotation: vi.fn().mockReturnValue({ mutate: vi.fn(), isPending: false }),
}));
vi.mock('../../api/hooks/useReviews', () => ({
  useRecordVisit: vi.fn().mockReturnValue(null),
  useParticipants: vi.fn().mockReturnValue({ data: { participants: [] } }),
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
    reactions: [],
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
        contentType: 'application/pdf',
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

function renderPage(initialEntry = '/reviews/doc-1') {
  render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={buildTheme('light')}>
        <MemoryRouter initialEntries={[initialEntry]}>
          <Routes>
            <Route path="/reviews/:documentId" element={<DocumentReviewPage />} />
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
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

// Issue #332: the PDF fetch starts from an explicit ?version= before the
// metadata queries resolve it, so a shared deep link downloads its bytes in
// parallel with the document + version-list queries.
describe('pdfFetchVersion', () => {
  it('uses the resolved version once it is known', () => {
    expect(pdfFetchVersion(2, 5)).toBe(2);
  });

  it('fetches the requested version eagerly before resolution (deep-link first paint)', () => {
    expect(pdfFetchVersion(undefined, 3)).toBe(3);
  });

  it('is undefined before resolution when no version was requested', () => {
    expect(pdfFetchVersion(undefined, NaN)).toBeUndefined();
    expect(pdfFetchVersion(undefined, 0)).toBeUndefined();
  });
});

describe('DocumentReviewPage', () => {
  it('renders title, workflow badge, version info, viewer and annotations', () => {
    seedHappyPath();
    renderPage();

    expect(screen.getByRole('heading', { level: 1, name: 'Supply Contract' })).toBeInTheDocument();
    // The typed document icon leads the header (issue #509 follow-up).
    expect(screen.getByRole('img', { name: 'PDF document' })).toBeInTheDocument();
    expect(screen.getByText('In review')).toBeInTheDocument();
    expect(screen.getByTestId('document-viewer')).toHaveAttribute('data-annotation-count', '1');
    expect(screen.getByText('Annotations (1)')).toBeInTheDocument();
    expect(screen.getByText('“Hello”')).toBeInTheDocument();
    // Placement cues are expanded-state details.
    fireEvent.click(screen.getByTestId('annotation-item-a1'));
    expect(screen.getByText('Moved')).toBeInTheDocument();
  });

  // Issue #395: a whole-document ("global") annotation can be raised from the panel too, not only
  // the tasks view.
  it('opens the global annotation dialog from the annotations panel', () => {
    seedHappyPath();
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Global annotation' }));
    expect(screen.getByText(/applies to the whole document/i)).toBeInTheDocument();
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

  it('switches back to panel mode via the toolbar switch and stores it', () => {
    localStorage.setItem('qnop-review-view-mode', 'focus');
    seedHappyPath();
    renderPage();

    // Panel vs. focus lives in the viewer toolbar now (issue #403); the switch
    // routes through ?view=, which syncs into the stored preference.
    fireEvent.click(screen.getByRole('button', { name: 'Panel view' }));

    expect(screen.getByRole('complementary', { name: 'Annotations' })).toBeInTheDocument();
    expect(localStorage.getItem('qnop-review-view-mode')).toBe('panel');
  });

  it('switches into focus mode via the toolbar switch', () => {
    localStorage.setItem('qnop-review-view-mode', 'panel');
    seedHappyPath();
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Focus view' }));

    expect(screen.queryByRole('complementary', { name: 'Annotations' })).not.toBeInTheDocument();
    expect(localStorage.getItem('qnop-review-view-mode')).toBe('focus');
  });

  it('activates focus mode through the ?view= deep link', () => {
    seedHappyPath();
    renderPage('/reviews/doc-1?view=focus');
    expect(screen.queryByRole('complementary', { name: 'Annotations' })).not.toBeInTheDocument();
    expect(localStorage.getItem('qnop-review-view-mode')).toBe('focus');
  });
});

// Issue #306: mutating review activity is latest-only — older versions read as
// an archive: banner + jump, annotation tools off, threads read-only.
describe('DocumentReviewPage deep link', () => {
  // The tasks view's "Show in document" (issue #393): ?annotation= seeds the
  // active annotation once and is then consumed from the URL.
  it('activates the annotation named by ?annotation=', () => {
    seedHappyPath();
    renderPage('/reviews/doc-1?annotation=a1');
    expect(screen.getByTestId('annotation-item-a1')).toHaveAttribute('aria-expanded', 'true');
  });

  // Issue #412: a comment permalink opens the annotation AND scrolls its thread
  // to the referenced comment once the thread has loaded.
  it('scrolls to the comment named by ?comment= on the deep-linked annotation', () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    seedHappyPath();
    vi.mocked(useComments).mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        comments: [
          {
            id: 'op',
            annotationId: 'a1',
            authorId: 'u1',
            body: 'opener',
            createdAt: '2026-07-01T10:00:00Z',
          },
          {
            id: 'c9',
            annotationId: 'a1',
            authorId: 'u1',
            body: 'the reply',
            createdAt: '2026-07-02T10:00:00Z',
          },
        ],
      },
    } as unknown as ReturnType<typeof useComments>);

    renderPage('/reviews/doc-1?annotation=a1&comment=c9');

    expect(screen.getByTestId('annotation-item-a1')).toHaveAttribute('aria-expanded', 'true');
    expect(scrollIntoView).toHaveBeenCalled();
  });

  // Issue #412: an annotation that no longer exists degrades to a toast rather
  // than a silently empty page; the review itself still opens.
  it('degrades an unknown ?annotation= target to a toast', () => {
    seedHappyPath();
    renderPage('/reviews/doc-1?annotation=does-not-exist');
    expect(screen.getByText('This annotation no longer exists.')).toBeInTheDocument();
  });
});

// Issue #480: placement actions must not collapse the annotation they act on.
// Arming re-attach clears the selection only in focus mode, where the floating
// card must make way so the document is selectable.
describe('DocumentReviewPage placement actions (#480)', () => {
  afterEach(() => {
    localStorage.removeItem('qnop-review-view-mode');
  });

  function seedOrphaned() {
    seedHappyPath();
    vi.mocked(useAnnotations).mockReturnValue(
      asQuery({
        data: { annotations: [{ ...ANNOTATIONS[0], placementStatus: PlacementStatus.Orphaned }] },
      }),
    );
  }

  it('keeps the annotation expanded when arming re-attach in panel mode', () => {
    useAuthStore.setState({ userId: 'u1' });
    seedOrphaned();
    renderPage('/reviews/doc-1?annotation=a1');
    expect(screen.getByTestId('annotation-item-a1')).toHaveAttribute('aria-expanded', 'true');

    // Exact name: the expanded row is itself role="button" and its accessible
    // name contains the action's text, so a substring match would be ambiguous.
    fireEvent.click(screen.getByRole('button', { name: 'Re-attach' }));

    expect(screen.getByTestId('reattach-hint')).toBeInTheDocument();
    expect(screen.getByTestId('annotation-item-a1')).toHaveAttribute('aria-expanded', 'true');
  });

  it('still clears the selection and closes the drawer when arming re-attach in focus mode', () => {
    useAuthStore.setState({ userId: 'u1' });
    seedOrphaned();
    renderPage('/reviews/doc-1?annotation=a1&view=focus');

    fireEvent.click(screen.getByRole('button', { name: /Show annotations/ }));
    expect(screen.getByTestId('annotation-item-a1')).toHaveAttribute('aria-expanded', 'true');

    fireEvent.click(screen.getByRole('button', { name: 'Re-attach' }));
    expect(screen.getByTestId('reattach-hint')).toBeInTheDocument();

    // The selection is cleared so the focus overlay cannot spring back onto
    // the thread (issue #403); the drawer closes, its content stays mounted.
    expect(screen.getByTestId('annotation-item-a1')).toHaveAttribute('aria-expanded', 'false');
  });
});

describe('DocumentReviewPage on an older version', () => {
  it('shows the read-only banner with a jump to the latest version', () => {
    seedHappyPath();
    renderPage('/reviews/doc-1?version=1');

    expect(screen.getByTestId('read-only-banner')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Go to v2' }));
    expect(screen.queryByTestId('read-only-banner')).not.toBeInTheDocument();
  });

  it('shows no banner on the latest version', () => {
    seedHappyPath();
    renderPage();
    expect(screen.queryByTestId('read-only-banner')).not.toBeInTheDocument();
  });

  // The page must pass readOnly down to the panel — the resolve bar and the
  // reply composer of an expanded thread are gated there, not in the page.
  describe('expanded thread', () => {
    afterEach(() => {
      useAuthStore.setState({ userId: null });
    });

    it('is read-only on an older version', () => {
      seedHappyPath();
      useAuthStore.setState({ userId: 'u1' });
      renderPage('/reviews/doc-1?version=1');

      fireEvent.click(screen.getByTestId('annotation-item-a1'));
      expect(screen.queryByTestId('resolve-bar')).not.toBeInTheDocument();
      expect(screen.queryByLabelText('Add a comment')).not.toBeInTheDocument();
    });

    it('stays writable on the latest version', () => {
      seedHappyPath();
      useAuthStore.setState({ userId: 'u1' });
      renderPage('/reviews/doc-1?version=2');

      fireEvent.click(screen.getByTestId('annotation-item-a1'));
      expect(screen.getByTestId('resolve-bar')).toBeInTheDocument();
      expect(screen.getByLabelText('Add a comment')).toBeInTheDocument();
    });
  });
});
