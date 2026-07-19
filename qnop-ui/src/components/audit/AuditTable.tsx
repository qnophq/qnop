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

import IconButton from '@mui/material/IconButton';
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { Link as RouterLink } from 'react-router-dom';
import { ListFilter } from 'lucide-react';
import type { AuditEvent } from '../../api/generated';
import { useFormatters } from '../../hooks/useFormatters';
import { formatAuditDetail } from '../../utils/auditDetail';
import { PersonLink } from '../dashboard/PersonLink';
import { reviewPath } from '../dashboard/dashboardModel';
import { AuditEventBadge } from './AuditEventBadge';
import { SystemActor } from './SystemActor';

const COLUMNS = ['Time', 'Actor', 'Event', 'Document', 'Details'];
const EM_DASH = '—';

interface AuditTableProps {
  events: AuditEvent[];
  /** Narrow the list to one actor (id + resolved label for the filter chip). */
  onFilterActor: (actorId: string, label: string) => void;
  /** Narrow the list to system-only events (those with no human actor). */
  onFilterSystem: () => void;
  /** Narrow the list to one document (id + resolved title for the filter chip). */
  onFilterDocument: (documentId: string, label: string) => void;
}

/** A compact "filter to this" affordance, sitting next to a linked entity. */
function FilterButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <Tooltip title={label}>
      <IconButton
        size="small"
        aria-label={label}
        onClick={onClick}
        sx={{ color: 'text.disabled', '&:hover': { color: 'text.secondary' } }}
      >
        <ListFilter size={14} />
      </IconButton>
    </Tooltip>
  );
}

/**
 * The audit trail as a table (issue #466, ADR-0042): time, actor, event type,
 * document and a readable rendering of the jsonb detail.
 *
 * <p>Actor and document each render as the product's canonical link — a
 * {@link PersonLink} (avatar + name, profile hover card #482, click → profile)
 * for a user, a review link (click → the document) for a document — with a
 * separate "filter to this" button beside it, so navigating to the entity and
 * narrowing the list are distinct actions. The system actor (no id) carries its
 * own distinct {@link SystemActor} emblem and a "filter to system events"
 * button — no profile, since there is no person. No raw UUIDs are ever shown.
 * Timestamps render in the viewer's timezone through the shared {@link
 * useFormatters} seam (issue #465).
 */
export function AuditTable({
  events,
  onFilterActor,
  onFilterSystem,
  onFilterDocument,
}: AuditTableProps) {
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
          events.map((event) => {
            const actorName = event.actorDisplayName ?? 'Unknown user';
            const documentTitle = event.documentTitle ?? EM_DASH;
            return (
              <TableRow key={event.id} hover>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>
                  {formatDateTime(event.createdAt)}
                </TableCell>
                <TableCell>
                  {event.actorId ? (
                    <Stack
                      direction="row"
                      spacing={0.25}
                      sx={{ alignItems: 'center', minWidth: 0 }}
                    >
                      <PersonLink
                        userId={event.actorId}
                        slug={event.actorSlug}
                        name={actorName}
                        avatarUrl={event.actorAvatarUrl}
                        size={24}
                      />
                      <FilterButton
                        label={`Filter by ${actorName}`}
                        onClick={() => onFilterActor(event.actorId!, actorName)}
                      />
                    </Stack>
                  ) : (
                    <Stack
                      direction="row"
                      spacing={0.25}
                      sx={{ alignItems: 'center', minWidth: 0 }}
                    >
                      <SystemActor />
                      <FilterButton label="Filter by System" onClick={onFilterSystem} />
                    </Stack>
                  )}
                </TableCell>
                <TableCell>
                  <AuditEventBadge eventType={event.eventType} />
                </TableCell>
                <TableCell>
                  {event.documentId ? (
                    <Stack
                      direction="row"
                      spacing={0.25}
                      sx={{ alignItems: 'center', minWidth: 0 }}
                    >
                      <Link
                        component={RouterLink}
                        to={reviewPath({ id: event.documentId, slug: event.documentSlug })}
                        underline="hover"
                        noWrap
                        sx={{ fontWeight: 600, minWidth: 0 }}
                      >
                        {documentTitle}
                      </Link>
                      <FilterButton
                        label={`Filter by ${documentTitle}`}
                        onClick={() => onFilterDocument(event.documentId!, documentTitle)}
                      />
                    </Stack>
                  ) : (
                    // A SYSTEM-scoped event (e.g. a scheduler action) has no document.
                    <Typography color="text.secondary" aria-label="No document">
                      —
                    </Typography>
                  )}
                </TableCell>
                <TableCell>
                  <Typography variant="body2" color="text.secondary">
                    {formatAuditDetail(event.eventType, event.detail, formatDateTime)}
                  </Typography>
                </TableCell>
              </TableRow>
            );
          })
        )}
      </TableBody>
    </Table>
  );
}
