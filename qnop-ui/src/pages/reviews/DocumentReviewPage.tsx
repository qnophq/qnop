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

import { useMemo, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import Stack from '@mui/material/Stack';
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
import type { BadgeTone } from '../../components/admin/ToneBadge';
import { ToneBadge } from '../../components/admin/ToneBadge';
import { AnnotationPanel } from '../../components/reviews/panel/AnnotationPanel';
import type { TextSelectionOffsets } from '../../components/reviews/viewer/anchoring';
import { buildRegionAnchor, buildTextAnchor } from '../../components/reviews/viewer/anchoring';
import { DocumentViewer } from '../../components/reviews/viewer/DocumentViewer';
import { usePdfDocument } from '../../components/reviews/viewer/usePdfDocument';
import type { ViewerTool } from '../../components/reviews/viewer/ViewerToolbar';
import { ViewerToolbar } from '../../components/reviews/viewer/ViewerToolbar';

/** Community workflow states with a badge tone; unknown (enterprise) states render neutral. */
const WORKFLOW_TONES: Record<string, BadgeTone> = {
  DRAFT: 'neutral',
  IN_REVIEW: 'blue',
  CHANGES_REQUESTED: 'amber',
  FINALIZED: 'green',
  CANCELLED: 'red',
};

function workflowBadge(state: string) {
  const label = state
    .toLowerCase()
    .replaceAll('_', ' ')
    .replace(/^./, (c) => c.toUpperCase());
  return <ToneBadge tone={WORKFLOW_TONES[state] ?? 'neutral'} label={label} />;
}

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
  const [pendingAnchor, setPendingAnchor] = useState<Anchor | null>(null);

  const surfaces = renderedQuery.data?.surfaces;
  const annotations = useMemo(
    () => annotationsQuery.data?.annotations ?? [],
    [annotationsQuery.data],
  );
  const canAnnotate = extractionReady && surfaces !== undefined;
  const textToolAvailable = (surfaces ?? []).some((surface) => surface.textSpans.length > 0);

  const handleVersionChange = (version: number) => {
    setSearchParams({ version: String(version) });
    setActiveAnnotationId(null);
    setPendingAnchor(null);
  };

  const handleTextSelected = ({ surfaceIndex, start, end }: TextSelectionOffsets) => {
    const surface = surfaces?.find((s) => s.index === surfaceIndex);
    if (!surface) return;
    const anchor = buildTextAnchor(surfaceIndex, surface.textSpans, start, end);
    if (anchor) {
      setPendingAnchor(anchor);
      setActiveAnnotationId(null);
    }
  };

  const handleRegionSelected = (surfaceIndex: number, box: NormalizedBox) => {
    const anchor = buildRegionAnchor(surfaceIndex, box);
    if (anchor) {
      setPendingAnchor(anchor);
      setActiveAnnotationId(null);
    }
  };

  const handleCreate = (comment: string) => {
    if (!pendingAnchor || versionNumber === undefined) return;
    createAnnotation.mutate(
      { versionNumber, anchor: pendingAnchor, comment: comment.trim() || undefined },
      {
        onSuccess: (created) => {
          setPendingAnchor(null);
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
    <Stack spacing={3}>
      <PageHeader
        title={document.title}
        titleAdornment={workflowBadge(document.workflowState)}
        description={
          versionNumber !== undefined
            ? `Version ${versionNumber} of ${latestVersion}`
            : 'No version uploaded yet.'
        }
      />
      {versionNumber === undefined ? (
        <Alert severity="info">This review has no uploaded document version yet.</Alert>
      ) : (
        <Stack
          direction={{ xs: 'column', md: 'row' }}
          spacing={3}
          sx={{ alignItems: 'flex-start' }}
        >
          <Box sx={{ flex: 1, minWidth: 0, alignSelf: 'stretch' }}>
            <Stack spacing={2}>
              <ViewerToolbar
                versions={versionsQuery.data?.versions ?? []}
                currentVersion={versionNumber}
                onVersionChange={handleVersionChange}
                extractionStatus={extractionStatus}
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
                  Extraction failed for this version, so it cannot be annotated. The original
                  document is still shown below.
                </Alert>
              )}
              {pdfError && (
                <Alert severity="error">The PDF could not be displayed: {pdfError}</Alert>
              )}
              {pdfQuery.isError && (
                <Alert severity="error">The original document could not be loaded.</Alert>
              )}
              {(pdfQuery.isPending || (!pdf && !pdfError && !pdfQuery.isError)) && (
                <Stack sx={{ alignItems: 'center', py: 6 }}>
                  <CircularProgress />
                </Stack>
              )}
              {pdf && (
                <DocumentViewer
                  pdf={pdf}
                  surfaces={surfaces}
                  zoom={zoom}
                  annotations={annotations}
                  activeAnnotationId={activeAnnotationId}
                  onSelectAnnotation={(id) => {
                    setActiveAnnotationId(id);
                    setPendingAnchor(null);
                  }}
                  tool={tool}
                  canAnnotate={canAnnotate}
                  pendingAnchor={pendingAnchor}
                  onTextSelected={handleTextSelected}
                  onRegionSelected={handleRegionSelected}
                />
              )}
            </Stack>
          </Box>
          <Box
            sx={{
              width: { xs: '100%', md: 360 },
              flexShrink: 0,
              position: { md: 'sticky' },
              top: { md: 0 },
              maxHeight: { md: 'calc(100dvh - 140px)' },
              overflowY: { md: 'auto' },
            }}
          >
            <AnnotationPanel
              annotations={annotations}
              activeAnnotationId={activeAnnotationId}
              onSelect={setActiveAnnotationId}
              pendingAnchor={pendingAnchor}
              creating={createAnnotation.isPending}
              onCreate={handleCreate}
              onCancelPending={() => setPendingAnchor(null)}
              canAnnotate={canAnnotate}
              notify={notify}
            />
          </Box>
        </Stack>
      )}
      <AdminToast toast={toast} onClose={clear} />
    </Stack>
  );
}
