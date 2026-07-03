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

import { useMemo, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import { NotebookPen } from 'lucide-react';
import type { Anchor, NormalizedBox } from '../../api/generated';
import { ExtractionStatus } from '../../api/generated';
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
import { ReviewHubHead } from '../../components/reviews/hub/ReviewHubHead';
import { AnnotationPanel } from '../../components/reviews/panel/AnnotationPanel';
import { PANEL_MIN_WIDTH, PanelResizer } from '../../components/reviews/PanelResizer';
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
  const versionNumber =
    requestedVersion >= 1 && requestedVersion <= latestVersion
      ? requestedVersion
      : latestVersion >= 1
        ? latestVersion
        : undefined;

  const versionsQuery = useDocumentVersions(documentId, versionNumber);
  const versionSummary = versionsQuery.data?.versions.find(
    (version) => version.versionNumber === versionNumber,
  );
  const extractionStatus = versionSummary?.extractionStatus;
  const extractionReady = extractionStatus === ExtractionStatus.Ready;

  const renderedQuery = useRenderedDocument(documentId, versionNumber ?? 0, extractionReady);
  const pdfQuery = useOriginalPdf(documentId, versionNumber);
  const { pdf, error: pdfError } = usePdfDocument(pdfQuery.data);
  const annotationsQuery = useAnnotations(documentId, versionNumber);
  const createAnnotation = useCreateAnnotation(documentId);

  const [tool, setTool] = useState<ViewerTool>('text');
  const [zoom, setZoom] = useState(1);
  const [activeAnnotationId, setActiveAnnotationId] = useState<string | null>(null);
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
  const canAnnotate = extractionReady && surfaces !== undefined;
  const textToolAvailable = (surfaces ?? []).some((surface) => surface.textSpans.length > 0);
  const pageCount = surfaces?.length ?? pdf?.numPages ?? 0;

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

  const handleCreate = (comment: string) => {
    if (!pending || versionNumber === undefined) return;
    createAnnotation.mutate(
      { versionNumber, anchor: pending.anchor, comment: comment.trim() || undefined },
      {
        onSuccess: (created) => {
          setPending(null);
          setActiveAnnotationId(created.id);
          notify('Annotation created.');
        },
        onError: () => notify('Could not create the annotation.', 'error'),
      },
    );
  };

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
            />
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
                />
              </Box>
            )}
          </Stack>
          <PanelResizer
            width={panelWidth}
            defaultWidth={PANEL_MIN_WIDTH}
            onWidthChange={handlePanelWidthChange}
          />
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
              ownerId={document.ownerId}
              notify={notify}
            />
          </Box>
        </Stack>
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
