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

import { useState } from 'react';
import { Download } from 'lucide-react';
import { downloadAnnotationExport } from '../../api/annotationExport';
import { useConfig } from '../../api/hooks/useConfig';
import { useDocument } from '../../api/hooks/useDocuments';
import { ToolbarIconButton } from './ToolbarIconButton';
import { ExportWizard, type ExportCounts } from './export/ExportWizard';
import type { ExportSettings } from './export/exportModel';
import { trackEvent } from '../../tracking/trackEvent';

/**
 * Opens the export wizard for a review's annotations (issue #547).
 *
 * <p>A wizard rather than a format menu, because choosing a format is only part
 * of the decision: which annotations and which columns matter just as much, and
 * a dropdown has nowhere to put them.
 *
 * <p>One component for both surfaces (the Tasks toolbar and the review's
 * annotation panel), so the two cannot drift. It owns the in-flight state,
 * because the request is the only thing it does; reporting a failure is the
 * caller's, since each surface already has its own toast.
 */
export function ExportAnnotationsButton({
  documentId,
  version,
  counts,
  onError,
}: {
  documentId: string;
  version?: number;
  counts?: ExportCounts;
  onError?: (message: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [exporting, setExporting] = useState(false);
  // The title is only needed to prefill the filename, and both surfaces have
  // already loaded this document — so it comes from the cache rather than
  // becoming a prop that two call sites have to remember to pass.
  const documentQuery = useDocument(documentId);
  // Which formats this deployment can actually produce (#639): PDF needs an
  // office converter, and offering one the server lacks yields a failed download.
  const configQuery = useConfig();

  const run = async (settings: ExportSettings, fileName: string) => {
    setExporting(true);
    try {
      await downloadAnnotationExport(documentId, version, {
        fields: settings.fields,
        scope: settings.scope,
        comments: settings.includeComments,
        format: settings.format,
        logo: settings.includeLogo,
        dateFormat: settings.dateFormat,
        timezone: settings.timezone,
        fileName,
      });
      trackEvent('export_generated');
      // Closing only after it succeeded keeps the configuration on screen when
      // it did not — the user retries instead of rebuilding their selection.
      setOpen(false);
    } catch {
      onError?.('The export could not be generated. Please try again.');
    } finally {
      setExporting(false);
    }
  };

  return (
    <>
      <ToolbarIconButton
        label="Export"
        icon={Download}
        onClick={() => setOpen(true)}
        expanded={open}
      />
      {/* Mounted only while open, so the wizard opens on step 1 with the last
          saved configuration without needing a reset effect. */}
      {open && (
        <ExportWizard
          open
          onClose={() => setOpen(false)}
          onExport={run}
          documentTitle={documentQuery.data?.title}
          offeredFormats={configQuery.data?.exportFormats}
          counts={counts}
          exporting={exporting}
        />
      )}
    </>
  );
}
