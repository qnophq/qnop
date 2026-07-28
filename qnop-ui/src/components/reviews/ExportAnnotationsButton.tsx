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
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import type { LucideIcon } from 'lucide-react';
import { Download, FileSpreadsheet } from 'lucide-react';
import { downloadAnnotationExport } from '../../api/annotationExport';
import { ToolbarIconButton } from './ToolbarIconButton';

/** One offered format. Adding Word, Markdown or HTML is one more entry here. */
interface ExportFormat {
  id: string;
  label: string;
  icon: LucideIcon;
  download: (documentId: string, version?: number) => Promise<void>;
}

const FORMATS: ExportFormat[] = [
  {
    id: 'xlsx',
    label: 'Export to Excel',
    icon: FileSpreadsheet,
    download: downloadAnnotationExport,
  },
];

/**
 * Exports the review's annotations (issue #547).
 *
 * <p>A menu rather than a single button, even while it offers exactly one
 * format: Word, Markdown and HTML are planned, and a button that has to be
 * rebuilt into a menu later would take its label, its tooltip and every test
 * that names it along. The list above is the extension point — a new format is
 * an entry, not a change to this component.
 *
 * <p>One component for both surfaces (the Tasks toolbar and the review's
 * annotation panel), so the two cannot drift. It owns the in-flight state,
 * because the request is the only thing it does; reporting a failure is the
 * caller's, since each surface already has its own toast.
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
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const [exporting, setExporting] = useState(false);

  const run = async (format: ExportFormat) => {
    setAnchor(null);
    setExporting(true);
    try {
      await format.download(documentId, version);
    } catch {
      // A failed download leaves nothing behind, so the surface's own toast is
      // the whole recovery story — the action stays available for another try.
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
        busy={exporting}
        onClick={(event) => setAnchor(event.currentTarget)}
        expanded={Boolean(anchor)}
      />
      <Menu
        open={Boolean(anchor)}
        anchorEl={anchor}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ paper: { sx: { minWidth: 210 } } }}
      >
        {FORMATS.map((format) => (
          <MenuItem key={format.id} onClick={() => run(format)}>
            <ListItemIcon>
              <format.icon size={16} />
            </ListItemIcon>
            <ListItemText slotProps={{ primary: { sx: { fontSize: 14 } } }}>
              {format.label}
            </ListItemText>
          </MenuItem>
        ))}
      </Menu>
    </>
  );
}
