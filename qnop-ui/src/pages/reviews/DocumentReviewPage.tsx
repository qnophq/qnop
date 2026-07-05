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
import { NotebookPen } from 'lucide-react';
import type { Anchor, NormalizedBox } from '../../api/generated';
import { ExtractionStatus } from '../../api/generated';
import type { AnnotationPriority, AnnotationType } from '../../api/generated';
import { useAnnotations, useCreateAnnotation } from '../../api/hooks/useAnnotations';
import {
  useDocument,
  useDocumentVersions,
  useOriginalPdf,
  useRenderedDocument,
} from '../../api/hooks/useDocuments';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { useToast } from '../../components/admin/layout/useToast';
import { BoundaryFallback } from '../../components/errors/BoundaryFallback';
import { ErrorBoundary } from '../../components/errors/ErrorBoundary';
import { ReviewHubHead } from '../../components/reviews/hub/ReviewHubHead';
import { ReviewViewTabs } from '../../components/reviews/hub/ReviewViewTabs';
import { AnnotationPanel } from '../../components/reviews/panel/AnnotationPanel';
import { PANEL_MIN_WIDTH, PanelResizer } from '../../components/reviews/PanelResizer';
import { FocusAnnotationCard } from '../../components/reviews/focus/FocusAnnotationCard';
import { FocusDrawer } from '../../components/reviews/focus/FocusDrawer';
import {
  spotlightForAnchor,
  spotlightForAnnotation,
  walkPosition,
} from '../../components/reviews/focus/spotlightModel';
import { useAnchorElement } from '../../components/reviews/focus/useAnchorElement';
import { useRecordVisit } from '../../api/hooks/useReviews';
import { useViewMode } from '../../components/reviews/focus/useViewMode';
import { columnOf } from '../../components/reviews/tasks/tasksModel';
import { Composer } from '../../components/reviews/panel/Composer';
import { WorkflowBadge } from '../../components/reviews/WorkflowBadge';
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
import { useAuthStore } from '../../stores/authStore';
import { apiErrorCode } from '../../utils/apiError';
import { pdfFetchVersion, resolveEffectiveVersion } from './resolveEffectiveVersion';

const PANEL_WIDTH_KEY = 'qnop-review-panel-width';

/**
 * The review surface of one document (#250): pdf.js renders the original for
 * fidelity, every annotation overlay is driven by the stored normalized boxes
 * of the server's rendered representation (ADR-0032), and marks anchor via the
 * multi-layer model (ADR-0009). The viewed version comes from the `version`
 * search param, defaulting to the latest.
 */
export function DocumentReviewPage() {
  const { documentId = '' } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const { toast, notify, clear } = useToast();
  const userId = useAuthStore((s) => s.userId);

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
  const [listOpen, setListOpen] = useState(false);
  // Deep link from the tasks view (issue #393): ?annotation= seeds the active
  // annotation once; the param is consumed so in-page selection owns the state.
  const [activeAnnotationId, setActiveAnnotationId] = useState<string | null>(() =>
    searchParams.get('annotation'),
  );
  useEffect(() => {
    if (!searchParams.has('annotation')) return;
    const next = new URLSearchParams(searchParams);
    next.delete('annotation');
    setSearchParams(next, { replace: true });
  }, [searchParams, setSearchParams]);
  // Card↔mark linking (prototype): hovering either side lights up the other.
  const [hoverAnnotationId, setHoverAnnotationId] = useState<string | null>(null);
  const [currentPage, setCurrentPage] = useState(0);
  const viewerRef = useRef<DocumentViewerHandle>(null);
  const [panelWidth, setPanelWidth] = useState<number>(() => {
    try {
      const stored = Number(localStorage.getItem(PANEL_WIDTH_KEY));
      return stored >= PANEL_MIN_WIDTH ? stored : PANEL_MIN_WIDTH;
    } catch {
      return PANEL_MIN_WIDTH;
    }
  });

  const handlePanelWidthChange = (width: number) => {
    setPanelWidth(width);
    try {
      localStorage.setItem(PANEL_WIDTH_KEY, String(width));
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

  const closeFocusCard = () => {
    setActiveAnnotationId(null);
    activeMarkEl?.focus();
  };

  const handleVersionChange = (version: number) => {
    setSearchParams({ version: String(version) });
    setActiveAnnotationId(null);
    setPending(null);
  };

  const stagePending = (anchor: Anchor, at: ScreenPosition) => {
    setPending({ anchor, menuPosition: at });
    setActiveAnnotationId(null);
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
          setActiveAnnotationId(created.id);
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
      // A placed annotation continues in the spotlight; unplaced ones keep their
      // thread inside the drawer (no mark to spotlight).
      if (id && annotations.find((a) => a.id === id)?.anchor) setListOpen(false);
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
      <PageHeader
        title={document.title}
        titleAdornment={<WorkflowBadge state={document.workflowState} />}
        action={
          <ReviewHubHead
            documentId={documentId}
            isOwner={document.ownerId === userId}
            ownUserId={userId}
            annotations={annotations}
            dueAt={document.dueAt ?? null}
            workflowState={document.workflowState}
            notify={notify}
            onVersionUploaded={handleVersionChange}
          />
        }
      />
      <ReviewViewTabs
        documentId={documentId}
        active={focusMode ? 'focus' : 'document'}
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
              focusMode={focusMode}
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
                  bgcolor: (theme) => theme.qnop.surface2,
                }}
              >
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
            <PanelResizer
              width={panelWidth}
              defaultWidth={PANEL_MIN_WIDTH}
              onWidthChange={handlePanelWidthChange}
            />
          )}
          {!focusMode && (
            <Box
              component="aside"
              aria-label="Annotations"
              sx={{
                width: { xs: '100%', md: panelWidth },
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
                  readOnly={!isLatestVersion}
                  previousSeenAt={previousSeenAt}
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
              previousSeenAt={previousSeenAt}
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
          previousSeenAt={previousSeenAt}
        />
      )}
      {focusMode && composingPending && pending && (
        <Popper
          open={Boolean(pendingMarkEl)}
          anchorEl={pendingMarkEl}
          placement="right-start"
          sx={(theme) => ({
            zIndex: theme.zIndex.modal,
            width: 380,
            maxWidth: 'calc(100vw - 32px)',
          })}
          modifiers={[
            { name: 'offset', options: { offset: [0, 16] } },
            { name: 'flip', options: { fallbackPlacements: ['left-start', 'bottom', 'top'] } },
            { name: 'preventOverflow', options: { padding: 12 } },
          ]}
        >
          {/* Same floating-surface treatment as the focus card: bordered
              Paper under one soft ambient shadow. */}
          <Box
            sx={{
              boxShadow: (theme) =>
                theme.qnop.mode === 'dark' ? 'none' : '0 16px 48px rgba(1,32,66,0.14)',
              borderRadius: '10px',
            }}
          >
            <Composer
              pendingAnchor={pending.anchor}
              creating={createAnnotation.isPending}
              onCreate={handleCreate}
              onCancel={() => setPending(null)}
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
      </Menu>
      <AdminToast toast={toast} onClose={clear} />
    </Stack>
  );
}
