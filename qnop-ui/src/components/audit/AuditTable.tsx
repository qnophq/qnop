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
import type { AuditEvent } from '../../api/generated';
import { useFormatters } from '../../hooks/useFormatters';
import { formatAuditDetail } from '../../utils/auditDetail';
import { AuditEventBadge } from './AuditEventBadge';

const COLUMNS = ['Time', 'Actor', 'Event', 'Document', 'Details'];
const EM_DASH = '—';

interface AuditTableProps {
  events: AuditEvent[];
  /** Narrow the list to one actor (id + resolved label for the filter chip). */
  onFilterActor: (actorId: string, label: string) => void;
  /** Narrow the list to one document (id + resolved title for the filter chip). */
  onFilterDocument: (documentId: string, label: string) => void;
}

/**
 * The audit trail as a table (issue #466): time, actor, event type, document and
 * a readable rendering of the jsonb detail. Actor and document cells are
 * click-to-filter; the system actor (no id) is inert plain text. No raw UUIDs
 * are ever shown — an unresolved actor/document falls back to an em dash
 * (ADR-0042). Timestamps render in the viewer's timezone through the shared
 * {@link useFormatters} seam (issue #465, ADR-0041).
 */
export function AuditTable({ events, onFilterActor, onFilterDocument }: AuditTableProps) {
  const { formatDateTime } = useFormatters();
  return (
    <Table size="small">
      <TableHead>
        <TableRow>
          {COLUMNS.map((column) => (
            <TableCell key={column}>{column}</TableCell>
          ))}
        </TableRow>
      </TableHead>
      <TableBody>
        {events.length === 0 ? (
          <TableRow>
            <TableCell colSpan={COLUMNS.length}>
              <Typography color="text.secondary" sx={{ py: 3, textAlign: 'center' }}>
                No audit events found.
              </Typography>
            </TableCell>
          </TableRow>
        ) : (
          events.map((event) => (
            <TableRow key={event.id} hover>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(event.createdAt)}</TableCell>
              <TableCell>
                {event.actorId ? (
                  <Link
                    component="button"
                    type="button"
                    underline="hover"
                    onClick={() => onFilterActor(event.actorId!, event.actorDisplayName ?? EM_DASH)}
                  >
                    {event.actorDisplayName ?? EM_DASH}
                  </Link>
                ) : (
                  <Typography component="span" color="text.secondary">
                    {event.actorDisplayName ?? 'System'}
                  </Typography>
                )}
              </TableCell>
              <TableCell>
                <AuditEventBadge eventType={event.eventType} />
              </TableCell>
              <TableCell>
                <Link
                  component="button"
                  type="button"
                  underline="hover"
                  onClick={() => onFilterDocument(event.documentId, event.documentTitle ?? EM_DASH)}
                >
                  {event.documentTitle ?? EM_DASH}
                </Link>
              </TableCell>
              <TableCell>
                <Typography variant="body2" color="text.secondary">
                  {formatAuditDetail(event.eventType, event.detail, formatDateTime)}
                </Typography>
              </TableCell>
            </TableRow>
          ))
        )}
      </TableBody>
    </Table>
  );
}
