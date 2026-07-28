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
import { FileDown } from 'lucide-react';
import { downloadAnnotationExport } from '../../api/annotationExport';
import { ToolbarIconButton } from './ToolbarIconButton';

/**
 * Downloads the review's annotations as a spreadsheet (issue #547).
 *
 * <p>One component for both surfaces — the Tasks toolbar and the review's
 * annotation panel — so the two cannot drift in label, icon or busy behaviour.
 * It owns the in-flight state, because the request is the only thing it does;
 * reporting a failure is the caller's, since each surface already has its own
 * toast.
 */
export function ExportAnnotationsButton({
  documentId,
  version,
  onError,
}: {
  documentId: string;
  version?: number;
  onError?: (message: string) => void;
}) {
  const [exporting, setExporting] = useState(false);

  const run = async () => {
    setExporting(true);
    try {
      await downloadAnnotationExport(documentId, version);
    } catch {
      // A failed download leaves nothing behind, so the surface's own toast is
      // the whole recovery story — the button stays available for another try.
      onError?.('The export could not be generated. Please try again.');
    } finally {
      setExporting(false);
    }
  };

  return (
    <ToolbarIconButton label="Export to Excel" icon={FileDown} onClick={run} busy={exporting} />
  );
}
