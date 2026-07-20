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
import Autocomplete from '@mui/material/Autocomplete';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Collapse from '@mui/material/Collapse';
import LinearProgress from '@mui/material/LinearProgress';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TablePagination from '@mui/material/TablePagination';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { ChevronDown, HelpCircle } from 'lucide-react';
import { useAuditLog } from '../../api/hooks/useAuditLog';
import { AuditTable } from '../../components/audit/AuditTable';
import { AuditEventBadge } from '../../components/audit/AuditEventBadge';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { AUDIT_EVENT_META, AUDIT_EVENT_TYPES } from '../../utils/auditEvents';

const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];

interface EntityFilter {
  id: string;
  label: string;
}

/** The actor filter is either a specific user or the system (no human actor). */
type ActorFilter = { kind: 'user'; id: string; label: string } | { kind: 'system' };

/** A datetime-local input value → an ISO instant, or undefined when blank. */
function toIso(value: string): string | undefined {
  if (!value) return undefined;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

/**
 * The organisation-wide audit trail (issue #466, ADR-0042), reachable only by an
 * AUDITOR or ADMIN (the sidebar hides it and the route guards it). Filter by
 * event type, a created-at range, and — by clicking a row — a single actor or
 * document; page through the results. Loading, error and empty states are all
 * handled. Timestamps render in the viewer's timezone via the AuditTable's
 * useFormatters seam (issue #465, ADR-0041).
 */
export function AuditPage() {
  const [eventType, setEventType] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [actor, setActor] = useState<ActorFilter | null>(null);
  const [document, setDocument] = useState<EntityFilter | null>(null);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [legendOpen, setLegendOpen] = useState(false);

  const { data, isLoading, isFetching, isError } = useAuditLog({
    eventType: eventType || undefined,
    actorId: actor?.kind === 'user' ? actor.id : undefined,
    actorSystem: actor?.kind === 'system' ? true : undefined,
    documentId: document?.id,
    from: toIso(from),
    to: toIso(to),
    page,
    size: pageSize,
  });

  const events = data?.items ?? [];
  const total = data?.total ?? 0;

  const onFilterActor = (id: string, label: string) => {
    setActor({ kind: 'user', id, label });
    setPage(0);
  };
  const onFilterSystem = () => {
    setActor({ kind: 'system' });
    setPage(0);
  };
  const onFilterDocument = (id: string, label: string) => {
    setDocument({ id, label });
    setPage(0);
  };

  return (
    <Stack spacing={3}>
      <PageHeader
        title="Audit trail"
        description="Every recorded action across all document reviews — who did what, and when."
        action={
          <Button
            color="inherit"
            startIcon={<HelpCircle size={16} />}
            endIcon={
              <ChevronDown
                size={15}
                style={{
                  transform: legendOpen ? 'rotate(180deg)' : 'none',
                  transition: 'transform 150ms',
                }}
              />
            }
            onClick={() => setLegendOpen((open) => !open)}
            aria-expanded={legendOpen}
          >
            What do these events mean?
          </Button>
        }
      />

      <Collapse in={legendOpen} unmountOnExit>
        <Paper variant="outlined" sx={{ p: { xs: 2, sm: 2.5 } }}>
          <Typography
            component="h2"
            sx={{
              fontSize: 12,
              fontWeight: 600,
              letterSpacing: '0.08em',
              textTransform: 'uppercase',
              color: 'text.disabled',
              mb: 2,
            }}
          >
            Event guide
          </Typography>
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0, 1fr))' },
              columnGap: 4,
              rowGap: 1.75,
            }}
          >
            {AUDIT_EVENT_TYPES.map((type) => (
              <Stack
                key={type}
                direction="row"
                spacing={1.5}
                sx={{ alignItems: 'baseline', minWidth: 0 }}
              >
                <Box sx={{ flexShrink: 0, pt: 0.25 }}>
                  <AuditEventBadge eventType={type} />
                </Box>
                <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.5 }}>
                  {AUDIT_EVENT_META[type].description}
                </Typography>
              </Stack>
            ))}
          </Box>
        </Paper>
      </Collapse>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ flexWrap: 'wrap' }}>
        <Autocomplete
          options={AUDIT_EVENT_TYPES}
          value={eventType || null}
          onChange={(_, next) => {
            setEventType(next ?? '');
            setPage(0);
          }}
          getOptionLabel={(type) => AUDIT_EVENT_META[type]?.label ?? type}
          size="small"
          sx={{ minWidth: 240 }}
          renderInput={(params) => (
            <TextField {...params} label="Event type" placeholder="All events" />
          )}
        />
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
            <Chip
              label={`Actor: ${actor.kind === 'user' ? actor.label : 'System'}`}
              onDelete={() => setActor(null)}
              size="small"
            />
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
              onFilterSystem={onFilterSystem}
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
