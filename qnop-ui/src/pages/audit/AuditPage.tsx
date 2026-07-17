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
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import LinearProgress from '@mui/material/LinearProgress';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TablePagination from '@mui/material/TablePagination';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useAuditLog } from '../../api/hooks/useAuditLog';
import { AuditTable } from '../../components/audit/AuditTable';
import { PageHeader } from '../../components/admin/layout/PageHeader';

const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];

/** The document-review audit event vocabulary (ADR-0041), offered as a filter. */
const EVENT_TYPES = [
  'annotation.created',
  'annotation.resolved',
  'annotation.reopened',
  'placement.confirmed',
  'placement.reattached',
  'workflow.transition',
  'document.due_date.changed',
];

interface EntityFilter {
  id: string;
  label: string;
}

/** A datetime-local input value → an ISO instant, or undefined when blank. */
function toIso(value: string): string | undefined {
  if (!value) return undefined;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

/**
 * The organisation-wide audit trail (issue #466, ADR-0041), reachable only by an
 * AUDITOR or ADMIN (the sidebar hides it and the route guards it). Filter by
 * event type, a created-at range, and — by clicking a row — a single actor or
 * document; page through the results. Loading, error and empty states are all
 * handled. Timestamps use the shared formatter until user-timezone display
 * lands centrally (#465).
 */
export function AuditPage() {
  const [eventType, setEventType] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [actor, setActor] = useState<EntityFilter | null>(null);
  const [document, setDocument] = useState<EntityFilter | null>(null);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  const { data, isLoading, isFetching, isError } = useAuditLog({
    eventType: eventType || undefined,
    actorId: actor?.id,
    documentId: document?.id,
    from: toIso(from),
    to: toIso(to),
    page,
    size: pageSize,
  });

  const events = data?.items ?? [];
  const total = data?.total ?? 0;

  const onFilterActor = (id: string, label: string) => {
    setActor({ id, label });
    setPage(0);
  };
  const onFilterDocument = (id: string, label: string) => {
    setDocument({ id, label });
    setPage(0);
  };

  return (
    <Stack spacing={3}>
      <PageHeader
        title="Audit"
        description="Browse the organisation-wide document review audit trail."
      />

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ flexWrap: 'wrap' }}>
        <TextField
          select
          label="Event type"
          value={eventType}
          onChange={(e) => {
            setEventType(e.target.value);
            setPage(0);
          }}
          size="small"
          sx={{ minWidth: 220 }}
        >
          <MenuItem value="">All events</MenuItem>
          {EVENT_TYPES.map((type) => (
            <MenuItem key={type} value={type}>
              {type}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          type="datetime-local"
          label="From"
          value={from}
          onChange={(e) => {
            setFrom(e.target.value);
            setPage(0);
          }}
          size="small"
          slotProps={{ inputLabel: { shrink: true } }}
        />
        <TextField
          type="datetime-local"
          label="To"
          value={to}
          onChange={(e) => {
            setTo(e.target.value);
            setPage(0);
          }}
          size="small"
          slotProps={{ inputLabel: { shrink: true } }}
        />
      </Stack>

      {(actor || document) && (
        <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
          {actor && (
            <Chip label={`Actor: ${actor.label}`} onDelete={() => setActor(null)} size="small" />
          )}
          {document && (
            <Chip
              label={`Document: ${document.label}`}
              onDelete={() => setDocument(null)}
              size="small"
            />
          )}
        </Stack>
      )}

      <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
        <Box sx={{ height: 3 }}>{isFetching && <LinearProgress />}</Box>
        {isError ? (
          <Alert severity="error" sx={{ m: 2 }}>
            The audit trail could not be loaded.
          </Alert>
        ) : (
          <Box sx={{ overflowX: 'auto' }}>
            <AuditTable
              events={events}
              onFilterActor={onFilterActor}
              onFilterDocument={onFilterDocument}
            />
            <TablePagination
              component="div"
              count={total}
              page={page}
              rowsPerPage={pageSize}
              rowsPerPageOptions={PAGE_SIZE_OPTIONS}
              onPageChange={(_, next) => setPage(next)}
              onRowsPerPageChange={(e) => {
                setPageSize(parseInt(e.target.value, 10));
                setPage(0);
              }}
              labelRowsPerPage="Events per page"
              labelDisplayedRows={({ from: f, to: t, count }) => `${f}–${t} of ${count}`}
            />
          </Box>
        )}
        {isLoading && (
          <Typography color="text.secondary" sx={{ p: 2, fontSize: 14 }}>
            Loading…
          </Typography>
        )}
      </Paper>
    </Stack>
  );
}
