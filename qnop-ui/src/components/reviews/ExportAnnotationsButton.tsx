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
import { ToolbarIconButton } from './ToolbarIconButton';
import { ExportWizard, type ExportCounts } from './export/ExportWizard';
import type { ExportSettings } from './export/exportModel';

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

  const run = async (settings: ExportSettings) => {
    setExporting(true);
    try {
      await downloadAnnotationExport(documentId, version, {
        fields: settings.fields,
        scope: settings.scope,
      });
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
          counts={counts}
          exporting={exporting}
        />
      )}
    </>
  );
}
