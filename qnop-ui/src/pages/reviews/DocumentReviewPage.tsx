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

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Popper from '@mui/material/Popper';
import Stack from '@mui/material/Stack';
import { Copy, NotebookPen } from 'lucide-react';
import type { Anchor, AnnotationView, NormalizedBox } from '../../api/generated';
import { ExtractionStatus } from '../../api/generated';
import type { AnnotationPriority, AnnotationType } from '../../api/generated';
import {
  useAnnotations,
  useCreateAnnotation,
  useReattachPlacement,
} from '../../api/hooks/useAnnotations';
import {
  useDocument,
  useDocumentVersions,
  useOriginalPdf,
  useRenderedDocument,
} from '../../api/hooks/useDocuments';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { copyToClipboard } from '../../utils/clipboard';
import { useToast } from '../../components/admin/layout/useToast';
import { BoundaryFallback } from '../../components/errors/BoundaryFallback';
import { ErrorBoundary } from '../../components/errors/ErrorBoundary';
import { ReviewPageHeader } from '../../components/reviews/hub/ReviewPageHeader';
import { ReviewViewTabs } from '../../components/reviews/hub/ReviewViewTabs';
import { useReviewDocumentId } from '../../components/reviews/reviewDocumentId';
import { useReviewPermalink } from '../../components/reviews/useReviewPermalink';
import { isDocumentScoped } from '../../components/reviews/annotationScope';
import { AnnotationPanel } from '../../components/reviews/panel/AnnotationPanel';
import {
  DEFAULT_PANEL_FRACTION,
  PANEL_MAX_FRACTION,
  PANEL_MIN_FRACTION,
  PanelResizer,
  RESIZER_WIDTH,
} from '../../components/reviews/PanelResizer';
import { FocusAnnotationCard } from '../../components/reviews/focus/FocusAnnotationCard';
import { FocusDrawer } from '../../components/reviews/focus/FocusDrawer';
import {
  spotlightForAnchor,
  spotlightForAnnotation,
  walkPosition,
} from '../../components/reviews/focus/spotlightModel';
import { useAnchorElement } from '../../components/reviews/focus/useAnchorElement';
import { useRecordVisit } from '../../api/hooks/useReviews';
import { useConfig } from '../../api/hooks/useConfig';
import { useViewMode, type ReviewViewMode } from '../../components/reviews/focus/useViewMode';
import { columnOf } from '../../components/reviews/tasks/tasksModel';
import { NewTaskDialog } from '../../components/reviews/tasks/NewTaskDialog';
import { Composer } from '../../components/reviews/panel/Composer';
import { useCommentAttachmentUpload } from '../../components/reviews/markdown/useCommentAttachmentUpload';
import type {
  ScreenPosition,
  TextSelectionOffsets,
} from '../../components/reviews/viewer/anchoring';
import { buildRegionAnchor, buildTextAnchor } from '../../components/reviews/viewer/anchoring';
import type { DocumentViewerHandle } from '../../components/reviews/viewer/DocumentViewer';
import { DocumentViewer } from '../../components/reviews/viewer/DocumentViewer';
import { usePdfDocument } from '../../components/reviews/viewer/usePdfDocument';
import type { ViewerTool } from '../../components/reviews/viewer/ViewerToolbar';
import { ViewerToolbar } from '../../components/reviews/viewer/ViewerToolbar';
import { ReattachHintBar } from '../../components/reviews/viewer/ReattachHintBar';
import { isOpenWorkflowState } from '../../components/reviews/workflowMeta';
import { recordRecentReview } from '../../components/dashboard/recentReviews';
import { selectIsAdmin, useAuthStore } from '../../stores/authStore';
import { apiErrorCode } from '../../utils/apiError';
import { revealInScroller } from '../../utils/revealInScroller';
import { pdfFetchVersion, resolveEffectiveVersion } from './resolveEffectiveVersion';

const PANEL_SPLIT_KEY = 'qnop-review-split';
/** The pre-#403 pixel-width key — superseded by the fraction split, cleaned on load. */
const LEGACY_PANEL_WIDTH_KEY = 'qnop-review-panel-width';

/**
 * The review surface of one document (#250): pdf.js renders the original for
 * fidelity, every annotation overlay is driven by the stored normalized boxes
 * of the server's rendered representation (ADR-0032), and marks anchor via the
 * multi-layer model (ADR-0009). The viewed version comes from the `version`
 * search param, defaulting to the latest.
 */
export function DocumentReviewPage() {
  // The raw segment may be a slug (issue #411) — sibling links keep it, while
  // all data access below uses the canonical id resolved by the route gate.
  const { documentId: routeSegment = '' } = useParams();
  const documentId = useReviewDocumentId();
  const [searchParams, setSearchParams] = useSearchParams();
  const { toast, notify, clear } = useToast();
  const uploadAttachment = useCommentAttachmentUpload(documentId, notify);
  const userId = useAuthStore((s) => s.userId);
  const viewerIsAdmin = useAuthStore(selectIsAdmin);
  // The operator's free-re-attach switch (#562); the server enforces it
  // independently, this only gates the affordance.
  const freeReattachEnabled = useConfig().data?.review?.freeReattachEnabled ?? false;

  const documentQuery = useDocument(documentId);
  const latestVersion = documentQuery.data?.latestVersionNumber ?? 0;
  const requestedVersion = Number(searchParams.get('version'));

  // Watch the URL's version even when the cached list does not know it yet —
  // right after an upload the list is briefly stale, and useDocumentVersions
  // polls until the watched version appears and its extraction settles (#300).
  const versionsQuery = useDocumentVersions(
    documentId,
    requestedVersion >= 1 ? requestedVersion : latestVersion >= 1 ? latestVersion : undefined,
  );
  const versionNumber = resolveEffectiveVersion(
    requestedVersion,
    latestVersion,
    versionsQuery.data?.versions.map((version) => version.versionNumber) ?? [],
  );
  const versionSummary = versionsQuery.data?.versions.find(
    (version) => version.versionNumber === versionNumber,
  );
  const extractionStatus = versionSummary?.extractionStatus;
  const extractionReady = extractionStatus === ExtractionStatus.Ready;

  const renderedQuery = useRenderedDocument(documentId, versionNumber ?? 0, extractionReady);
  // The heavy PDF download starts from an explicit ?version= without waiting for
  // the metadata queries to validate it, so a shared deep link fetches its bytes
  // in parallel with useDocument/useDocumentVersions (issue #332).
  const pdfQuery = useOriginalPdf(documentId, pdfFetchVersion(versionNumber, requestedVersion));
  const { pdf, error: pdfError } = usePdfDocument(pdfQuery.data);
  const annotationsQuery = useAnnotations(documentId, versionNumber);
  const createAnnotation = useCreateAnnotation(documentId);

  const [tool, setTool] = useState<ViewerTool>('text');
  const [zoom, setZoom] = useState(1);
  const [viewMode, setViewMode] = useViewMode();
  // The unseen-marker baseline (issue #307): the PREVIOUS visit, stamped once.
  const previousSeenAt = useRecordVisit(documentId);
  // Feed the dashboard's "continue where you left off" (issue #454) —
  // device-local by design, so a plain localStorage stamp per resolved doc.
  const resolvedDocument = documentQuery.data;
  useEffect(() => {
    if (resolvedDocument) {
      recordRecentReview({
        id: resolvedDocument.id,
        slug: resolvedDocument.slug ?? null,
        title: resolvedDocument.title,
      });
    }
  }, [resolvedDocument]);
  // The view tabs (issue #398) address the mode through the URL — ?view= wins
  // over (and updates) the stored preference; the param stays shareable.
  const urlView = searchParams.get('view');
  useEffect(() => {
    if (urlView === 'focus' || urlView === 'panel') {
      setViewMode(urlView === 'panel' ? 'panel' : 'focus');
    }
    // setViewMode is a stable setter-like callback; syncing on param change only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlView]);
  // The toolbar's panel/focus switch (issue #403) goes through the URL, so the
  // address stays shareable and the ?view= sync above remains the single path
  // into the stored preference.
  const switchViewMode = (mode: ReviewViewMode) => {
    setSearchParams(
      (params) => {
        const next = new URLSearchParams(params);
        next.set('view', mode);
        return next;
      },
      { replace: true },
    );
  };
  const [listOpen, setListOpen] = useState(false);
  // The "new whole-document task" dialog (issue #395), reachable from the panel in both the
  // document and focus views — the same anchor-free create the tasks view offers.
  const [newNoteOpen, setNewNoteOpen] = useState(false);
  // Deep link (issues #393/#412): ?annotation= seeds the active annotation and
  // ?comment= seeds a comment scroll target once; both params are consumed so
  // in-page selection owns the state afterwards. The original ?annotation= is
  // kept in a ref to validate the target once the annotations settle.
  const [activeAnnotationId, setActiveAnnotationId] = useState<string | null>(() =>
    searchParams.get('annotation'),
  );
  const [scrollToCommentId, setScrollToCommentId] = useState<string | null>(() =>
    searchParams.get('comment'),
  );
  const clearScrollToComment = useCallback(() => setScrollToCommentId(null), []);
  const deepLinkAnnotationRef = useRef<string | null>(searchParams.get('annotation'));
  useEffect(() => {
    if (!searchParams.has('annotation') && !searchParams.has('comment')) return;
    const next = new URLSearchParams(searchParams);
    next.delete('annotation');
    next.delete('comment');
    setSearchParams(next, { replace: true });
  }, [searchParams, setSearchParams]);
  // Card↔mark linking (prototype): hovering either side lights up the other.
  const [hoverAnnotationId, setHoverAnnotationId] = useState<string | null>(null);
  const [currentPage, setCurrentPage] = useState(0);
  const viewerRef = useRef<DocumentViewerHandle>(null);
  // Document : panel defaults to 2 : 1; a user's adjustment sticks (issue #403).
  const [panelFraction, setPanelFraction] = useState<number>(() => {
    try {
      localStorage.removeItem(LEGACY_PANEL_WIDTH_KEY);
      const stored = Number(localStorage.getItem(PANEL_SPLIT_KEY));
      return stored >= PANEL_MIN_FRACTION && stored <= PANEL_MAX_FRACTION
        ? stored
        : DEFAULT_PANEL_FRACTION;
    } catch {
      return DEFAULT_PANEL_FRACTION;
    }
  });

  const handlePanelFractionChange = (fraction: number) => {
    setPanelFraction(fraction);
    try {
      localStorage.setItem(PANEL_SPLIT_KEY, String(fraction));
    } catch {
      // best-effort persistence
    }
  };
  // A drawn-but-not-created mark. While `menuPosition` is set, the "Create
  // annotation" popup is open at the pointer; choosing the item clears the
  // position and opens the composer in the panel. Dismissing the popup
  // discards the mark entirely.
  const [pending, setPending] = useState<{
    anchor: Anchor;
    menuPosition: { left: number; top: number } | null;
  } | null>(null);
  // Re-attach mode (issue #457): while armed, the next text/region selection
  // becomes the lost annotation's new anchor instead of a fresh mark.
  const [reattaching, setReattaching] = useState<{
    annotationId: string;
    excerpt: string | null;
  } | null>(null);
  const reattachPlacement = useReattachPlacement(notify);

  const surfaces = renderedQuery.data?.surfaces;
  const annotations = useMemo(
    () => annotationsQuery.data?.annotations ?? [],
    [annotationsQuery.data],
  );
  // The latest version across BOTH sources (the detail can be stale right
  // after an upload, #300); mutating review activity is latest-only (#306).
  const knownVersionNumbers = versionsQuery.data?.versions.map((v) => v.versionNumber) ?? [];
  const knownLatest = Math.max(
    latestVersion,
    ...(knownVersionNumbers.length ? knownVersionNumbers : [0]),
  );
  const isLatestVersion = versionNumber !== undefined && versionNumber === knownLatest;
  const canAnnotate = extractionReady && surfaces !== undefined && isLatestVersion;
  const textToolAvailable = (surfaces ?? []).some((surface) => surface.textSpans.length > 0);
  const pageCount = surfaces?.length ?? pdf?.numPages ?? 0;

  // Focus mode (issue #291): the spotlight follows the active annotation, or
  // the pending anchor while its composer is open. The card/composer anchor
  // to the mark elements the highlight layer renders.
  const focusMode = viewMode === 'focus';
  const activeAnnotation = annotations.find((a) => a.id === activeAnnotationId);
  // Permalinks (issue #412): the builder carries the current view so a copied
  // link reopens the same surface; it only builds URLs, the server still gates
  // access. A deep-linked annotation that no longer exists degrades to a toast
  // once the annotations settle (validated once via the ref above).
  const buildPermalink = useReviewPermalink(focusMode ? 'focus' : 'panel');
  useEffect(() => {
    const target = deepLinkAnnotationRef.current;
    if (!target || annotationsQuery.isPending || annotationsQuery.isError) return;
    deepLinkAnnotationRef.current = null;
    if (!annotations.some((a) => a.id === target)) {
      notify('This annotation no longer exists.', 'error');
      setActiveAnnotationId(null);
      setScrollToCommentId(null);
    }
  }, [annotations, annotationsQuery.isPending, annotationsQuery.isError, notify]);
  const composingPending = Boolean(pending && !pending.menuPosition);
  const focusSpotlight = !focusMode
    ? null
    : composingPending && pending
      ? spotlightForAnchor(pending.anchor, surfaces)
      : spotlightForAnnotation(activeAnnotation, surfaces);
  const focusOverlayOpen = focusMode && (composingPending || Boolean(activeAnnotation));
  const activeMarkEl = useAnchorElement(
    focusMode && activeAnnotation?.anchor ? `annotation-highlight-${activeAnnotation.id}` : null,
  );
  const pendingMarkEl = useAnchorElement(
    focusMode && composingPending ? 'pending-highlight' : null,
  );

  // Escape leaves the re-attach mode without touching anything (issue #457).
  useEffect(() => {
    if (!reattaching) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setReattaching(null);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [reattaching]);

  // Copy the drawn selection with the native gesture (issue #478): while a
  // TEXT selection is pending, Cmd/Ctrl+C copies its quote — unless the user
  // is in an input or made a real DOM selection somewhere (those keep native
  // copy). Region selections carry no text and stay untouched.
  const pendingQuote = pending?.anchor?.textQuote?.quote;
  useEffect(() => {
    if (!pendingQuote) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== 'c') return;
      const target = event.target as HTMLElement | null;
      if (target?.closest('input, textarea, [contenteditable="true"]')) return;
      const domSelection = window.getSelection();
      if (domSelection && !domSelection.isCollapsed) return;
      event.preventDefault();
      void copyToClipboard(pendingQuote).then((ok) =>
        notify(ok ? 'Text copied.' : 'Could not copy to the clipboard.', ok ? 'success' : 'error'),
      );
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [pendingQuote, notify]);

  const closeFocusCard = () => {
    setActiveAnnotationId(null);
    activeMarkEl?.focus();
  };

  const handleVersionChange = (version: number) => {
    setSearchParams({ version: String(version) });
    setActiveAnnotationId(null);
    setPending(null);
    setReattaching(null);
  };

  const stagePending = (anchor: Anchor, at: ScreenPosition) => {
    if (reattaching && versionNumber !== undefined) {
      const { annotationId } = reattaching;
      setReattaching(null);
      reattachPlacement.mutate(
        { annotationId, versionNumber, anchor },
        // Land on the re-homed thread — except in focus mode, where activating
        // would re-open the overlay the user just aimed past (issue #403).
        { onSuccess: () => (focusMode ? undefined : setActiveAnnotationId(annotationId)) },
      );
      return;
    }
    setPending({ anchor, menuPosition: at });
    setActiveAnnotationId(null);
  };

  // Arming (issue #457): mutually exclusive with drafting a new mark. Only the
  // focus overlay/drawer make way so the document is selectable — in panel
  // mode the thread stays selected while the user picks the new passage
  // (issue #480), mirroring the success-path guard in stagePending.
  const armReattach = (annotation: AnnotationView) => {
    setPending(null);
    if (focusMode) {
      setActiveAnnotationId(null);
      setListOpen(false);
    }
    setReattaching({
      annotationId: annotation.id,
      excerpt: annotation.anchor?.textQuote?.quote ?? null,
    });
  };

  const handleTextSelected = (
    { surfaceIndex, start, end }: TextSelectionOffsets,
    at: ScreenPosition,
  ) => {
    const surface = surfaces?.find((s) => s.index === surfaceIndex);
    if (!surface) return;
    const anchor = buildTextAnchor(surfaceIndex, surface.textSpans, start, end);
    if (anchor) stagePending(anchor, at);
  };

  const handleRegionSelected = (surfaceIndex: number, box: NormalizedBox, at: ScreenPosition) => {
    const anchor = buildRegionAnchor(surfaceIndex, box);
    if (anchor) stagePending(anchor, at);
  };

  const handleCreate = (comment: string, type?: AnnotationType, priority?: AnnotationPriority) => {
    // The first comment is mandatory (issue #301) — the composer only submits
    // non-blank text; this guard keeps the invariant at the API boundary too.
    const trimmed = comment.trim();
    if (!pending || versionNumber === undefined || trimmed.length === 0) return;
    createAnnotation.mutate(
      { versionNumber, anchor: pending.anchor, comment: trimmed, type, priority },
      {
        onSuccess: (created) => {
          setPending(null);
          // In the panel the fresh annotation expands in place — helpful. In
          // focus mode activating it would re-open the overlay on the spot the
          // writer just left: the scrim (blur) stays up and the card's focus
          // trap yanks the viewport to a mark that hasn't rendered yet (issue
          // #403). Creating is a closing gesture there: page stays put, sharp.
          if (!focusMode) setActiveAnnotationId(created.id);
          notify('Annotation created.');
        },
        onError: (error) =>
          notify(
            apiErrorCode(error) === 'VERSION_READ_ONLY'
              ? 'A newer version was uploaded in the meantime — annotations go on the latest version.'
              : 'Could not create the annotation.',
            'error',
          ),
      },
    );
  };

  // Stable so the focus-mode panel's memoized rows don't all re-render on every hover (issue #333).
  const handleFocusSelect = useCallback(
    (id: string | null) => {
      setActiveAnnotationId(id);
      if (!id) return;
      const selected = annotations.find((a) => a.id === id);
      // A located annotation closes the drawer to reveal its floating card; a document-scoped one
      // (issue #395) has no mark to float by, so it stays in the drawer where its thread expands.
      if (selected) setListOpen(isDocumentScoped(selected));
    },
    [annotations],
  );

  if (documentQuery.isPending) {
    return (
      <Stack sx={{ alignItems: 'center', py: 8 }}>
        <CircularProgress />
      </Stack>
    );
  }

  if (documentQuery.isError || !documentQuery.data) {
    return (
      <Alert severity="error">
        This document does not exist, or you are not a participant of its review.
      </Alert>
    );
  }

  const document = documentQuery.data;

  return (
    <Stack
      spacing={2.5}
      sx={{
        // The review surface is a fixed workspace (prototype layout): the page
        // header stays put while document and panel scroll independently. The
        // shell hands this route the full container height (AppShell fullBleed).
        height: { md: '100%' },
        minHeight: { md: 480 },
      }}
    >
      {/* No description line: the version lives in the toolbar dropdown, and
          every saved pixel goes to the document and its annotations. */}
      <ReviewPageHeader
        document={document}
        annotations={annotations}
        notify={notify}
        onVersionUploaded={handleVersionChange}
      />
      <ReviewViewTabs
        documentId={routeSegment}
        active="document"
        openTaskCount={annotations.filter((a) => columnOf(a) !== 'done').length}
        compareEnabled={
          (versionsQuery.data?.versions.filter(
            (version) => version.extractionStatus === ExtractionStatus.Ready,
          ).length ?? 0) >= 2
        }
      />
      {versionNumber === undefined ? (
        <Alert severity="info">This review has no uploaded document version yet.</Alert>
      ) : (
        <Stack
          direction={{ xs: 'column', md: 'row' }}
          // On md+ the resizer provides the gap between the panes.
          spacing={{ xs: 2.5, md: 0 }}
          sx={{ alignItems: 'stretch', flex: 1, minHeight: 0 }}
        >
          <Stack spacing={1.5} sx={{ flex: 1, minWidth: 0, minHeight: 0 }}>
            <ViewerToolbar
              versions={versionsQuery.data?.versions ?? []}
              currentVersion={versionNumber}
              onVersionChange={handleVersionChange}
              extractionStatus={extractionStatus}
              currentPage={currentPage}
              pageCount={pageCount}
              onNavigateToPage={(pageIndex) => viewerRef.current?.scrollToPage(pageIndex)}
              tool={tool}
              onToolChange={setTool}
              textToolAvailable={textToolAvailable}
              canAnnotate={canAnnotate}
              zoom={zoom}
              onZoomChange={setZoom}
              viewMode={viewMode}
              onViewModeChange={switchViewMode}
              annotationCount={annotations.length}
              onOpenAnnotationList={() => setListOpen(true)}
            />
            {!isLatestVersion && versionNumber !== undefined && (
              <Alert
                severity="info"
                data-testid="read-only-banner"
                action={
                  <Button
                    color="inherit"
                    size="small"
                    onClick={() => handleVersionChange(knownLatest)}
                  >
                    Go to v{knownLatest}
                  </Button>
                }
              >
                You are viewing an older version — reading only. Annotate on v{knownLatest}.
              </Alert>
            )}
            {extractionStatus === ExtractionStatus.Pending && (
              <Alert severity="info">
                The document is being processed — annotating becomes available once the text layer
                is extracted.
              </Alert>
            )}
            {extractionStatus === ExtractionStatus.Failed && (
              <Alert severity="error">
                Extraction failed for this version, so it cannot be annotated. The original document
                is still shown below.
              </Alert>
            )}
            {pdfError && <Alert severity="error">The PDF could not be displayed: {pdfError}</Alert>}
            {pdfQuery.isError && (
              <Alert severity="error">The original document could not be loaded.</Alert>
            )}
            {(pdfQuery.isPending || (!pdf && !pdfError && !pdfQuery.isError)) && (
              <Stack sx={{ alignItems: 'center', py: 6 }}>
                <CircularProgress aria-label="Loading document" />
              </Stack>
            )}
            {pdf && (
              <Box
                sx={{
                  flex: 1,
                  minHeight: { xs: 480, md: 0 },
                  borderRadius: 1,
                  // The re-attach hint pill anchors to this stage (issue #457).
                  position: 'relative',
                  bgcolor: (theme) => theme.qnop.surface2,
                }}
              >
                {reattaching && (
                  <ReattachHintBar
                    excerpt={reattaching.excerpt}
                    onCancel={() => setReattaching(null)}
                  />
                )}
                {/* A crash in the pdf.js viewer must not take down the panel or the
                    page — scope it and let the reviewer retry (issue #331). */}
                <ErrorBoundary
                  resetKeys={[versionNumber, pdf]}
                  fallback={(_error, reset) => (
                    <BoundaryFallback title="The document viewer failed" onRetry={reset} />
                  )}
                >
                  <DocumentViewer
                    ref={viewerRef}
                    pdf={pdf}
                    surfaces={surfaces}
                    zoom={zoom}
                    annotations={annotations}
                    activeAnnotationId={activeAnnotationId}
                    hoverAnnotationId={hoverAnnotationId}
                    onSelectAnnotation={(id) => {
                      setActiveAnnotationId(id);
                      setPending(null);
                      setReattaching(null);
                      // Reveal the row's HEAD in the panel on EVERY mark
                      // click (#491) — also when the annotation is already the
                      // active one, where the panel's state-change effect
                      // cannot fire. 'start' on purpose: the expanded card is
                      // often taller than the panel, and 'nearest' treats a
                      // partially visible card as in view. setTimeout(0) runs
                      // after React committed the expansion.
                      if (id) {
                        setTimeout(() => {
                          // window.document explicitly — the component scope
                          // shadows `document` with the DocumentResponse.
                          const el = window.document.getElementById(`annotation-item-${id}`);
                          if (el) revealInScroller(el, 'start');
                        }, 0);
                      }
                    }}
                    onHoverAnnotation={setHoverAnnotationId}
                    onVisiblePageChange={setCurrentPage}
                    tool={tool}
                    canAnnotate={canAnnotate}
                    pendingAnchor={pending?.anchor ?? null}
                    onTextSelected={handleTextSelected}
                    onRegionSelected={handleRegionSelected}
                    focusScrim={
                      focusOverlayOpen
                        ? {
                            spotlight: focusSpotlight,
                            // The veil never discards a composer mid-typing — the
                            // card's Cancel is the explicit way out.
                            onDismiss: () => {
                              if (!composingPending) closeFocusCard();
                            },
                          }
                        : null
                    }
                  />
                </ErrorBoundary>
              </Box>
            )}
          </Stack>
          {!focusMode && (
            <PanelResizer fraction={panelFraction} onFractionChange={handlePanelFractionChange} />
          )}
          {!focusMode && (
            <Box
              component="aside"
              aria-label="Annotations"
              sx={{
                // The divider's width is off the top, so the split reads
                // exactly document : panel = (1 - f) : f.
                width: { xs: '100%', md: `calc((100% - ${RESIZER_WIDTH}px) * ${panelFraction})` },
                minWidth: { md: 320 },
                flexShrink: 0,
                minHeight: 0,
                overflowY: { md: 'auto' },
              }}
            >
              <ErrorBoundary
                resetKeys={[versionNumber]}
                fallback={(_error, reset) => (
                  <BoundaryFallback title="The annotations panel failed" onRetry={reset} dense />
                )}
              >
                <AnnotationPanel
                  anonymous={document.anonymous ?? false}
                  threadParticipation={document.threadParticipation ?? 'OPEN'}
                  ownerId={document.ownerId}
                  annotations={annotations}
                  activeAnnotationId={activeAnnotationId}
                  hoverAnnotationId={hoverAnnotationId}
                  onSelect={setActiveAnnotationId}
                  onHover={setHoverAnnotationId}
                  pendingAnchor={pending && !pending.menuPosition ? pending.anchor : null}
                  creating={createAnnotation.isPending}
                  onCreate={handleCreate}
                  onCancelPending={() => setPending(null)}
                  canAnnotate={canAnnotate}
                  notify={notify}
                  onUploadAttachment={uploadAttachment}
                  readOnly={!isLatestVersion}
                  versionNumber={versionNumber}
                  onArmReattach={armReattach}
                  freeReattachEnabled={freeReattachEnabled}
                  viewerIsAdmin={viewerIsAdmin}
                  reviewClosed={!isOpenWorkflowState(document.workflowState)}
                  previousSeenAt={previousSeenAt}
                  buildPermalink={buildPermalink}
                  scrollToCommentId={scrollToCommentId}
                  onScrolledToComment={clearScrollToComment}
                  onNewDocumentNote={() => setNewNoteOpen(true)}
                />
              </ErrorBoundary>
            </Box>
          )}
        </Stack>
      )}
      {focusMode && (
        <FocusDrawer open={listOpen} onClose={() => setListOpen(false)}>
          <ErrorBoundary
            resetKeys={[versionNumber]}
            fallback={(_error, reset) => (
              <BoundaryFallback title="The annotations panel failed" onRetry={reset} dense />
            )}
          >
            <AnnotationPanel
              anonymous={document.anonymous ?? false}
              threadParticipation={document.threadParticipation ?? 'OPEN'}
              ownerId={document.ownerId}
              frameless
              annotations={annotations}
              activeAnnotationId={activeAnnotationId}
              hoverAnnotationId={hoverAnnotationId}
              onSelect={handleFocusSelect}
              onHover={setHoverAnnotationId}
              pendingAnchor={null}
              creating={createAnnotation.isPending}
              onCreate={handleCreate}
              onCancelPending={() => setPending(null)}
              canAnnotate={canAnnotate}
              notify={notify}
              readOnly={!isLatestVersion}
              versionNumber={versionNumber}
              onArmReattach={armReattach}
              freeReattachEnabled={freeReattachEnabled}
              viewerIsAdmin={viewerIsAdmin}
              reviewClosed={!isOpenWorkflowState(document.workflowState)}
              previousSeenAt={previousSeenAt}
              buildPermalink={buildPermalink}
              scrollToCommentId={scrollToCommentId}
              onScrolledToComment={clearScrollToComment}
              onNewDocumentNote={() => setNewNoteOpen(true)}
            />
          </ErrorBoundary>
        </FocusDrawer>
      )}
      {focusMode && activeAnnotation && !listOpen && (
        <FocusAnnotationCard
          annotation={activeAnnotation}
          anchorEl={activeMarkEl}
          position={walkPosition(annotations, activeAnnotation.id)}
          onNavigate={setActiveAnnotationId}
          onClose={closeFocusCard}
          userId={userId}
          notify={notify}
          readOnly={!isLatestVersion}
          versionNumber={versionNumber}
          onArmReattach={armReattach}
          freeReattachEnabled={freeReattachEnabled}
          viewerIsAdmin={viewerIsAdmin}
          reviewClosed={!isOpenWorkflowState(document.workflowState)}
          threadParticipation={document.threadParticipation ?? 'OPEN'}
          ownerId={document.ownerId}
          previousSeenAt={previousSeenAt}
          buildPermalink={buildPermalink}
          scrollToCommentId={scrollToCommentId}
          onScrolledToComment={clearScrollToComment}
        />
      )}
      {focusMode && composingPending && pending && (
        <Popper
          open={Boolean(pendingMarkEl)}
          anchorEl={pendingMarkEl}
          placement="right-start"
          sx={(theme) => ({ zIndex: theme.zIndex.modal })}
          modifiers={[
            { name: 'offset', options: { offset: [0, 16] } },
            { name: 'flip', options: { fallbackPlacements: ['left-start', 'bottom', 'top'] } },
            { name: 'preventOverflow', options: { padding: 12 } },
          ]}
        >
          {/* Same floating-surface treatment as the focus card: bordered
              Paper under one soft ambient shadow. The Composer's own Paper is
              deliberately transparent (it normally sits on the panel), so this
              wrapper supplies the opaque surface — floating over the document
              it must never shine through (issue #403). */}
          <Box
            sx={(theme) => ({
              position: 'relative',
              boxShadow: theme.qnop.mode === 'dark' ? 'none' : '0 16px 48px rgba(1,32,66,0.14)',
              borderRadius: '10px',
              bgcolor: 'background.paper',
              overflow: 'hidden',
              // Writer-resizable (issue #403), like the focus card — but only
              // horizontally: height stays content-driven (the textarea grows
              // as you type), so width is the axis worth grabbing.
              resize: 'horizontal',
              width: 380,
              minWidth: 320,
              maxWidth: 'min(720px, calc(100vw - 48px))',
              // The inner outlined Paper draws the border; align its corners
              // with this surface so the edge reads as ONE rounded frame.
              '& > .MuiPaper-root': { borderRadius: '10px' },
            })}
          >
            <Composer
              pendingAnchor={pending.anchor}
              creating={createAnnotation.isPending}
              onCreate={handleCreate}
              onCancel={() => setPending(null)}
              onUploadAttachment={uploadAttachment}
            />
            {/* Discoverability for the native resize grip underneath. */}
            <Box
              aria-hidden
              sx={(theme) => ({
                position: 'absolute',
                right: 2,
                bottom: 2,
                width: 11,
                height: 11,
                pointerEvents: 'none',
                clipPath: 'polygon(100% 0, 100% 100%, 0 100%)',
                background: `repeating-linear-gradient(135deg, transparent 0 2.5px, ${theme.palette.divider} 2.5px 4px)`,
              })}
            />
          </Box>
        </Popper>
      )}
      {/* The post-selection popup: only an explicit "Create annotation" opens
          the composer; dismissing it (click-away, Escape) discards the mark. */}
      <Menu
        open={Boolean(pending?.menuPosition)}
        onClose={() => setPending(null)}
        anchorReference="anchorPosition"
        anchorPosition={pending?.menuPosition ?? undefined}
      >
        <MenuItem onClick={() => setPending((state) => state && { ...state, menuPosition: null })}>
          <ListItemIcon>
            <NotebookPen size={16} />
          </ListItemIcon>
          <ListItemText>Create annotation</ListItemText>
        </MenuItem>
        {pendingQuote && (
          <MenuItem
            onClick={() => {
              void copyToClipboard(pendingQuote).then((ok) =>
                notify(
                  ok ? 'Text copied.' : 'Could not copy to the clipboard.',
                  ok ? 'success' : 'error',
                ),
              );
              setPending(null);
            }}
          >
            <ListItemIcon>
              <Copy size={16} />
            </ListItemIcon>
            <ListItemText>Copy text</ListItemText>
          </MenuItem>
        )}
      </Menu>
      <NewTaskDialog
        open={newNoteOpen}
        documentId={documentId}
        versionNumber={knownLatest}
        notify={notify}
        onClose={() => setNewNoteOpen(false)}
      />
      <AdminToast toast={toast} onClose={clear} />
    </Stack>
  );
}
