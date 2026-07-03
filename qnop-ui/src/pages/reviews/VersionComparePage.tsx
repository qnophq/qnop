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

import { useEffect, useRef, useState } from 'react';
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import { ArrowLeft } from 'lucide-react';
import { ExtractionStatus } from '../../api/generated';
import { useDocument, useDocumentVersions } from '../../api/hooks/useDocuments';
import { useOriginalPdf } from '../../api/hooks/useDocuments';
import { useRenderedDocument } from '../../api/hooks/useDocuments';
import { useParticipants } from '../../api/hooks/useReviews';
import { useVersionDiff, versionDiffErrorCode } from '../../api/hooks/useVersionDiff';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { ChangeSummaryPanel } from '../../components/reviews/diff/ChangeSummaryPanel';
import { ComparePane } from '../../components/reviews/diff/ComparePane';
import { CompareToolbar } from '../../components/reviews/diff/CompareToolbar';
import { useSyncScroll } from '../../components/reviews/diff/useSyncScroll';
import { usePdfDocument } from '../../components/reviews/viewer/usePdfDocument';
import { formatDateTime } from '../../utils/formatDate';

/**
 * The version comparison workspace (issue #252, ADR-0034): two panes render
 * the compared originals side by side with the server's located changes
 * painted on them; the right-hand summary lists every change with statistics.
 * The pair lives in the URL (`?from=1&to=3`) so a comparison is shareable;
 * invalid or missing parameters normalise to "previous ↔ latest".
 */
export function VersionComparePage() {
  const { documentId = '' } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();

  const documentQuery = useDocument(documentId);
  const versionsQuery = useDocumentVersions(documentId);
  const participantsQuery = useParticipants(documentId);

  const versions = versionsQuery.data?.versions ?? [];
  const readyNumbers = versions
    .filter((version) => version.extractionStatus === ExtractionStatus.Ready)
    .map((version) => version.versionNumber)
    .sort((a, b) => a - b);

  // Resolve the compared pair: URL params when valid, else previous ↔ latest.
  const paramFrom = Number(searchParams.get('from'));
  const paramTo = Number(searchParams.get('to'));
  const to = readyNumbers.includes(paramTo) ? paramTo : readyNumbers.at(-1);
  const from =
    readyNumbers.includes(paramFrom) && paramFrom !== to
      ? paramFrom
      : readyNumbers.filter((n) => n !== to).at(-1);

  // Keep the URL canonical so a copied link reproduces exactly this view.
  useEffect(() => {
    if (from === undefined || to === undefined) return;
    if (paramFrom !== from || paramTo !== to) {
      setSearchParams({ from: String(from), to: String(to) }, { replace: true });
    }
  }, [from, to, paramFrom, paramTo, setSearchParams]);

  const fromBytes = useOriginalPdf(documentId, from);
  const toBytes = useOriginalPdf(documentId, to);
  const fromPdf = usePdfDocument(fromBytes.data);
  const toPdf = usePdfDocument(toBytes.data);
  const fromRendered = useRenderedDocument(documentId, from ?? 0, from !== undefined);
  const toRendered = useRenderedDocument(documentId, to ?? 0, to !== undefined);
  const diffQuery = useVersionDiff(documentId, from, to);

  const [activeChange, setActiveChange] = useState<number | null>(null);
  const [syncScroll, setSyncScroll] = useState(true);
  const fromScrollRef = useRef<HTMLDivElement>(null);
  const toScrollRef = useRef<HTMLDivElement>(null);
  useSyncScroll(fromScrollRef, toScrollRef, syncScroll);

  // Selecting a change (card or highlight) brings it into view on both sides.
  useEffect(() => {
    if (activeChange === null) return;
    for (const side of ['from', 'to'] as const) {
      document
        .getElementById(`diff-change-${side}-${activeChange}`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }, [activeChange]);

  const handleChangePair = (nextFrom: number, nextTo: number) => {
    setActiveChange(null);
    setSearchParams({ from: String(nextFrom), to: String(nextTo) });
  };

  if (documentQuery.isPending || versionsQuery.isPending) {
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

  const document_ = documentQuery.data;
  const participants = participantsQuery.data?.participants ?? [];
  const uploaderName = (userId: string) =>
    participants.find((participant) => participant.principalId === userId)?.displayName;
  const versionMeta = (versionNumber: number | undefined) => {
    const version = versions.find((v) => v.versionNumber === versionNumber);
    if (!version) return '';
    const name = uploaderName(version.createdBy);
    return `${formatDateTime(version.createdAt)}${name ? ` · ${name}` : ''}`;
  };

  const changes = diffQuery.data?.changes ?? null;
  const backHref = `/reviews/${documentId}${to !== undefined ? `?version=${to}` : ''}`;

  return (
    <Stack spacing={2.5} sx={{ height: { md: '100%' }, minHeight: { md: 480 } }}>
      <PageHeader
        title={document_.title}
        titleAdornment={<Chip size="small" variant="outlined" label="Compare versions" />}
        action={
          <Button
            component={RouterLink}
            to={backHref}
            variant="outlined"
            size="small"
            startIcon={<ArrowLeft size={15} />}
          >
            Back to review
          </Button>
        }
      />

      {from === undefined || to === undefined ? (
        <Alert severity="info">
          Comparing needs at least two versions with an extracted text layer. Upload a new version
          first.
        </Alert>
      ) : (
        <>
          <CompareToolbar
            versions={versions}
            from={from}
            to={to}
            onChangePair={handleChangePair}
            syncScroll={syncScroll}
            onSyncScrollChange={setSyncScroll}
            changeCount={changes ? changes.length : null}
          />
          {diffQuery.isError && (
            <Alert severity="error">
              {versionDiffErrorCode(diffQuery.error) === 'EXTRACTION_PENDING'
                ? 'One of the versions is still being processed — try again in a moment.'
                : 'The comparison could not be computed.'}
            </Alert>
          )}
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', lg: 'minmax(0,1fr) minmax(0,1fr) 300px' },
              gap: 2,
              flex: 1,
              minHeight: 0,
            }}
          >
            {(
              [
                { side: 'from' as const, version: from, pdfState: fromPdf, rendered: fromRendered },
                { side: 'to' as const, version: to, pdfState: toPdf, rendered: toRendered },
              ] as const
            ).map(({ side, version, pdfState, rendered }) => (
              <Paper
                key={side}
                variant="outlined"
                sx={{
                  display: 'flex',
                  flexDirection: 'column',
                  minHeight: 0,
                  minWidth: 0,
                  overflow: 'hidden',
                  height: { xs: 480, lg: 'auto' },
                }}
              >
                <ComparePane
                  side={side}
                  versionNumber={version}
                  metaText={versionMeta(version)}
                  trailingText={
                    side === 'to' && changes
                      ? `${changes.length} ${changes.length === 1 ? 'change' : 'changes'}`
                      : undefined
                  }
                  pdf={pdfState.pdf}
                  pdfError={pdfState.error}
                  surfaces={rendered.data?.surfaces}
                  changes={changes ?? []}
                  activeChangeIndex={activeChange}
                  onSelectChange={(index) => setActiveChange(index)}
                  scrollRef={side === 'from' ? fromScrollRef : toScrollRef}
                />
              </Paper>
            ))}
            <Paper variant="outlined" sx={{ p: 2, overflow: 'auto', minHeight: 0 }}>
              {changes === null && !diffQuery.isError ? (
                <Stack sx={{ alignItems: 'center', py: 4 }}>
                  <CircularProgress size={22} />
                </Stack>
              ) : (
                <ChangeSummaryPanel
                  changes={changes ?? []}
                  activeChangeIndex={activeChange}
                  onSelectChange={setActiveChange}
                />
              )}
            </Paper>
          </Box>
        </>
      )}
    </Stack>
  );
}
