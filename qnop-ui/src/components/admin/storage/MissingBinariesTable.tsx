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

import Link from '@mui/material/Link';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import { Link as RouterLink } from 'react-router-dom';
import type { MissingBinary } from '../../../api/generated';
import { reviewPath } from '../../dashboard/dashboardModel';
import { ToneBadge } from '../ToneBadge';
import { StorageKey } from './StorageKey';

const EM_DASH = '—';

/** The human context for a missing binary — which version or which attachment. */
function context(binary: MissingBinary): string {
  if (binary.kind === 'VERSION') {
    return binary.versionNumber != null ? `Version ${binary.versionNumber}` : 'Version';
  }
  return binary.attachmentName ? `Attachment · ${binary.attachmentName}` : 'Attachment';
}

/**
 * Missing binaries (issue #523, ADR-0044): referenced objects that are gone —
 * data loss. Report-only by design; instead of a destructive shortcut each row
 * links to the affected document so an admin decides (restore from backup, or
 * delete the document through the review flow).
 */
export function MissingBinariesTable({ binaries }: { binaries: MissingBinary[] }) {
  return (
    <Table size="small">
      <TableHead>
        <TableRow>
          <TableCell>Document</TableCell>
          <TableCell>Context</TableCell>
          <TableCell>Storage key</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {binaries.length === 0 ? (
          <TableRow>
            <TableCell colSpan={3}>
              <Typography color="text.secondary" sx={{ py: 3, textAlign: 'center' }}>
                No missing binaries.
              </Typography>
            </TableCell>
          </TableRow>
        ) : (
          binaries.map((binary) => (
            <TableRow key={`${binary.storageKey}:${binary.documentId}`} hover>
              <TableCell>
                <Link
                  component={RouterLink}
                  to={reviewPath({ id: binary.documentId, slug: binary.documentSlug })}
                  underline="hover"
                  sx={{ fontWeight: 600 }}
                >
                  {binary.documentTitle ?? EM_DASH}
                </Link>
              </TableCell>
              <TableCell>
                <ToneBadge
                  tone={binary.kind === 'VERSION' ? 'blue' : 'neutral'}
                  label={context(binary)}
                />
              </TableCell>
              <TableCell>
                <StorageKey value={binary.storageKey} />
              </TableCell>
            </TableRow>
          ))
        )}
      </TableBody>
    </Table>
  );
}
