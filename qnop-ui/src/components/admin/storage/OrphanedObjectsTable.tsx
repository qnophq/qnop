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
import Checkbox from '@mui/material/Checkbox';
import IconButton from '@mui/material/IconButton';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableFooter from '@mui/material/TableFooter';
import TableHead from '@mui/material/TableHead';
import TablePagination from '@mui/material/TablePagination';
import TableRow from '@mui/material/TableRow';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { Trash2 } from 'lucide-react';
import type { OrphanedObject } from '../../../api/generated';
import { useFormatters } from '../../../hooks/useFormatters';
import { formatBytes } from '../../../utils/formatBytes';
import { StorageKey } from './StorageKey';

/** Page sizes for the orphaned-objects table; the smallest is also the default. */
const ROWS_PER_PAGE_OPTIONS = [25, 50, 100] as const;

interface OrphanedObjectsTableProps {
  objects: OrphanedObject[];
  selected: Set<string>;
  onToggle: (key: string) => void;
  onToggleAll: () => void;
  onDeleteOne: (key: string) => void;
  /** Locks selection + actions while a deletion is in flight. */
  busy: boolean;
}

/**
 * The orphaned objects (issue #523, ADR-0044): unreferenced stored objects with
 * their size and age. Rows are selectable for a bulk delete, and each has a
 * single-delete action; both are locked while a deletion runs. Keys render
 * monospaced and copyable — never as bare truncated text.
 *
 * The list can grow large on a drifted bucket, so it is paginated client-side
 * (the scan returns one atomic snapshot, so there is nothing to fetch per page).
 * Selection lives with the parent and therefore persists across pages: the
 * header checkbox selects/clears <em>every</em> orphan, not just the visible page.
 */
export function OrphanedObjectsTable({
  objects,
  selected,
  onToggle,
  onToggleAll,
  onDeleteOne,
  busy,
}: OrphanedObjectsTableProps) {
  const { formatDateTime, formatRelative } = useFormatters();
  const allSelected = objects.length > 0 && selected.size === objects.length;
  const someSelected = selected.size > 0 && !allSelected;

  const [page, setPage] = useState(0);
  const [rowsPerPage, setRowsPerPage] = useState<number>(ROWS_PER_PAGE_OPTIONS[0]);

  // A delete + rescan can shrink the list past the current page. Clamp during
  // render (rather than writing state back in an effect) so the slice — and the
  // page prop MUI receives — always stay in range without an extra render.
  const pageCount = Math.ceil(objects.length / rowsPerPage);
  const safePage = Math.min(page, Math.max(0, pageCount - 1));

  const start = safePage * rowsPerPage;
  const visible = objects.slice(start, start + rowsPerPage);
  const showPagination = objects.length > ROWS_PER_PAGE_OPTIONS[0];

  return (
    <Table size="small">
      <TableHead>
        <TableRow>
          <TableCell padding="checkbox">
            <Checkbox
              checked={allSelected}
              indeterminate={someSelected}
              onChange={onToggleAll}
              disabled={busy || objects.length === 0}
              slotProps={{ input: { 'aria-label': 'Select all orphaned objects' } }}
            />
          </TableCell>
          <TableCell>Object key</TableCell>
          <TableCell align="right">Size</TableCell>
          <TableCell>Last modified</TableCell>
          <TableCell padding="checkbox" />
        </TableRow>
      </TableHead>
      <TableBody>
        {objects.length === 0 ? (
          <TableRow>
            <TableCell colSpan={5}>
              <Typography color="text.secondary" sx={{ py: 3, textAlign: 'center' }}>
                No orphaned objects.
              </Typography>
            </TableCell>
          </TableRow>
        ) : (
          visible.map((object) => {
            const key = object.storageKey;
            const checked = selected.has(key);
            return (
              <TableRow key={key} hover selected={checked}>
                <TableCell padding="checkbox">
                  <Checkbox
                    checked={checked}
                    onChange={() => onToggle(key)}
                    disabled={busy}
                    slotProps={{ input: { 'aria-label': `Select ${key}` } }}
                  />
                </TableCell>
                <TableCell>
                  <StorageKey value={key} />
                </TableCell>
                <TableCell
                  align="right"
                  sx={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}
                >
                  {formatBytes(object.size)}
                </TableCell>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>
                  <Tooltip title={formatDateTime(object.lastModified)}>
                    <span>{formatRelative(object.lastModified)}</span>
                  </Tooltip>
                </TableCell>
                <TableCell padding="checkbox">
                  <Tooltip title="Delete this object">
                    <span>
                      <IconButton
                        size="small"
                        color="error"
                        onClick={() => onDeleteOne(key)}
                        disabled={busy}
                        aria-label={`Delete ${key}`}
                      >
                        <Trash2 size={16} />
                      </IconButton>
                    </span>
                  </Tooltip>
                </TableCell>
              </TableRow>
            );
          })
        )}
      </TableBody>
      {showPagination && (
        <TableFooter>
          <TableRow>
            <TablePagination
              rowsPerPageOptions={[...ROWS_PER_PAGE_OPTIONS]}
              count={objects.length}
              rowsPerPage={rowsPerPage}
              page={safePage}
              colSpan={5}
              labelRowsPerPage="Objects per page"
              onPageChange={(_, next) => setPage(next)}
              onRowsPerPageChange={(event) => {
                setRowsPerPage(parseInt(event.target.value, 10));
                setPage(0);
              }}
              slotProps={{ select: { 'aria-label': 'Objects per page' } }}
            />
          </TableRow>
        </TableFooter>
      )}
    </Table>
  );
}
