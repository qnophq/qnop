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
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Tooltip from '@mui/material/Tooltip';
import { FileDown } from 'lucide-react';
import { downloadAnnotationExport } from '../../api/annotationExport';

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
  /** Icon-only, for toolbars that are already dense. */
  compact = false,
}: {
  documentId: string;
  version?: number;
  onError?: (message: string) => void;
  compact?: boolean;
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

  const spinner = <CircularProgress size={14} color="inherit" />;

  if (compact) {
    return (
      <Tooltip title="Export to Excel">
        <span>
          <Button
            size="small"
            variant="outlined"
            disabled={exporting}
            onClick={run}
            aria-label="Export to Excel"
            sx={{ minWidth: 36, px: 1 }}
          >
            {exporting ? spinner : <FileDown size={15} />}
          </Button>
        </span>
      </Tooltip>
    );
  }

  return (
    <Button
      size="small"
      variant="outlined"
      startIcon={exporting ? spinner : <FileDown size={16} />}
      disabled={exporting}
      onClick={run}
      sx={{ flexShrink: 0 }}
    >
      Export to Excel
    </Button>
  );
}
