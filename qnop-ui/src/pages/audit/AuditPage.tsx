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

import { useEffect, useState } from 'react';
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
import { useTranslation } from 'react-i18next';
import { useAuditLog } from '../../api/hooks/useAuditLog';
import { ClearableSearchField } from '../../components/ClearableSearchField';
import { AuditTable } from '../../components/audit/AuditTable';
import { AuditEventBadge } from '../../components/audit/AuditEventBadge';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { AUDIT_EVENT_TYPES, auditEventMeta } from '../../utils/auditEvents';

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
  const { t } = useTranslation(['audit', 'common']);
  const [eventType, setEventType] = useState('');
  const [detailSearch, setDetailSearch] = useState('');
  const [debouncedDetail, setDebouncedDetail] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [actor, setActor] = useState<ActorFilter | null>(null);
  const [document, setDocument] = useState<EntityFilter | null>(null);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [legendOpen, setLegendOpen] = useState(false);

  // Debounce the free-text search so paging only resets once typing settles (issue #536).
  useEffect(() => {
    const handle = setTimeout(() => {
      setDebouncedDetail(detailSearch);
      setPage(0);
    }, 300);
    return () => clearTimeout(handle);
  }, [detailSearch]);

  const { data, isLoading, isFetching, isError } = useAuditLog({
    eventType: eventType || undefined,
    actorId: actor?.kind === 'user' ? actor.id : undefined,
    actorSystem: actor?.kind === 'system' ? true : undefined,
    documentId: document?.id,
    detail: debouncedDetail.trim() || undefined,
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
        title={t('page.title')}
        description={t('page.description')}
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
            {t('page.legendToggle')}
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
            {t('page.legendTitle')}
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
                  {t(auditEventMeta(type).descriptionKey, { type })}
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
          getOptionLabel={(type) => t(auditEventMeta(type).labelKey, { type })}
          size="small"
          sx={{ minWidth: 240 }}
          renderInput={(params) => (
            <TextField
              {...params}
              label={t('filters.eventType')}
              placeholder={t('filters.allEvents')}
            />
          )}
        />
        <ClearableSearchField
          label={t('filters.searchDetails')}
          value={detailSearch}
          onValueChange={setDetailSearch}
          onClear={() => {
            // Skip the debounce on reset — the filter drops immediately.
            setDetailSearch('');
            setDebouncedDetail('');
            setPage(0);
          }}
          clearLabel={t('filters.clearSearch')}
          hideSearchIcon
          maxLength={256}
          placeholder={t('filters.searchPlaceholder')}
          sx={{ minWidth: 240, flexGrow: 1, maxWidth: 360 }}
        />
        <TextField
          type="datetime-local"
          label={t('filters.from')}
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
          label={t('filters.to')}
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
              label={t('filters.actorChip', {
                name: actor.kind === 'user' ? actor.label : t('system.name'),
              })}
              onDelete={() => setActor(null)}
              size="small"
            />
          )}
          {document && (
            <Chip
              label={t('filters.documentChip', { title: document.label })}
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
            {t('page.loadError')}
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
              labelRowsPerPage={t('pagination.rowsPerPage')}
              labelDisplayedRows={({ from: first, to: last, count }) =>
                t('pagination.displayedRows', { from: first, to: last, total: count })
              }
            />
          </Box>
        )}
        {isLoading && (
          <Typography color="text.secondary" sx={{ p: 2, fontSize: 14 }}>
            {t('common:loading')}
          </Typography>
        )}
      </Paper>
    </Stack>
  );
}
