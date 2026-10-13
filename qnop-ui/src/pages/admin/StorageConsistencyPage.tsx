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
import Button from '@mui/material/Button';
import LinearProgress from '@mui/material/LinearProgress';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import {
  Boxes,
  CheckCircle2,
  Database,
  FileWarning,
  HardDrive,
  RefreshCw,
  Trash2,
} from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { useDeleteOrphans, useStorageConsistency } from '../../api/hooks/useStorageConsistency';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { SectionCard } from '../../components/admin/layout/SectionCard';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { useToast } from '../../components/admin/layout/useToast';
import { ConfirmDialog } from '../../components/admin/ConfirmDialog';
import { ToneBadge } from '../../components/admin/ToneBadge';
import { StatStrip, type StatTile } from '../../components/dashboard/StatStrip';
import { MissingBinariesTable } from '../../components/admin/storage/MissingBinariesTable';
import { OrphanedObjectsTable } from '../../components/admin/storage/OrphanedObjectsTable';
import { useFormatters } from '../../hooks/useFormatters';

/** Whether a query error is the scan-limit circuit breaker (HTTP 409). */
function isScanLimit(error: unknown): boolean {
  return (error as { response?: { status?: number } })?.response?.status === 409;
}

/**
 * The storage-consistency dashboard (issue #523, ADR-0044): reconciles document
 * binaries in object storage against the database. A summary strip, a report-only
 * "missing binaries" section (data loss), and a remediable "orphaned objects"
 * section (cost/leak) with bulk selection and confirm dialogs. The scan-limit
 * circuit breaker surfaces as a readable warning; a fully consistent store shows
 * a reassuring all-clear.
 */
export function StorageConsistencyPage() {
  const { t } = useTranslation(['admin', 'common']);
  const { data, isLoading, isFetching, isError, error, refetch } = useStorageConsistency();
  const deleteOrphans = useDeleteOrphans();
  const { formatRelative } = useFormatters();
  const { toast, notify, clear } = useToast();

  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [pendingDelete, setPendingDelete] = useState<string[] | null>(null);

  const summary = data?.summary;
  const missing = data?.missing ?? [];
  const orphaned = data?.orphaned ?? [];
  const busy = deleteOrphans.isPending;

  const toggle = (key: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const toggleAll = () =>
    setSelected((prev) =>
      prev.size === orphaned.length ? new Set() : new Set(orphaned.map((o) => o.storageKey)),
    );

  const confirmDelete = () => {
    const keys = pendingDelete ?? [];
    deleteOrphans.mutate(keys, {
      onSuccess: (result) => {
        const skipped = result.skipped.length;
        const count = result.deleted.length;
        notify(
          skipped > 0
            ? t('storage.delete.deletedSkipped', { count, skipped })
            : t('storage.delete.deleted', { count }),
          skipped > 0 ? 'error' : 'success',
        );
        setSelected(new Set());
      },
      onError: () => notify(t('storage.delete.failed'), 'error'),
      onSettled: () => setPendingDelete(null),
    });
  };

  const handleRescan = () => {
    void refetch().then((result) => {
      if (result.isError) {
        notify(
          isScanLimit(result.error)
            ? t('storage.rescanResult.stoppedAtLimit')
            : t('storage.scanFailed'),
          'error',
        );
        return;
      }
      const s = result.data?.summary;
      notify(
        s
          ? t('storage.rescanResult.completeSummary', {
              missing: s.missingCount,
              orphaned: s.orphanedCount,
            })
          : t('storage.rescanResult.complete'),
      );
    });
  };

  const tiles: StatTile[] = [
    {
      label: t('storage.tiles.dbReferences'),
      value: summary?.dbReferencedCount ?? 0,
      icon: Database,
    },
    {
      label: t('storage.tiles.storageObjects'),
      value: summary?.storageObjectCount ?? 0,
      icon: HardDrive,
    },
    {
      label: t('storage.tiles.missing'),
      value: summary?.missingCount ?? 0,
      icon: FileWarning,
      tone: 'danger',
    },
    {
      label: t('storage.tiles.orphaned'),
      value: summary?.orphanedCount ?? 0,
      icon: Boxes,
      tone: 'warning',
    },
  ];

  const consistent = summary != null && summary.missingCount === 0 && summary.orphanedCount === 0;

  return (
    <Stack spacing={3}>
      <PageHeader
        title={t('storage.title')}
        description={
          summary
            ? t('storage.lastScanned', { when: formatRelative(summary.scannedAt) })
            : t('storage.description')
        }
        action={
          <Button
            variant="outlined"
            startIcon={
              <Box
                component="span"
                sx={{
                  display: 'inline-flex',
                  // The one animation on this page; stilled for reduced-motion
                  // users like every other spinner in the app (issue #460).
                  animation: isFetching ? 'qnop-spin 1s linear infinite' : 'none',
                  '@keyframes qnop-spin': { to: { transform: 'rotate(360deg)' } },
                  '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
                }}
              >
                <RefreshCw size={16} />
              </Box>
            }
            onClick={handleRescan}
            disabled={isFetching}
          >
            {isFetching ? t('storage.scanning') : t('storage.rescan')}
          </Button>
        }
      />

      {isScanLimit(error) ? (
        <Alert severity="warning">
          <Trans t={t} i18nKey="storage.scanLimit" components={{ code: <code /> }} />
        </Alert>
      ) : isError ? (
        <Alert severity="error">{t('storage.scanFailed')}</Alert>
      ) : isLoading ? (
        <Typography color="text.secondary" sx={{ p: 1, fontSize: 14 }}>
          {t('storage.scanning')}
        </Typography>
      ) : (
        <>
          <StatStrip tiles={tiles} />

          {consistent && (
            <SectionCard icon={CheckCircle2} title={t('storage.consistent.title')}>
              <Typography color="text.secondary">{t('storage.consistent.message')}</Typography>
            </SectionCard>
          )}

          {missing.length > 0 && (
            <SectionCard
              icon={FileWarning}
              title={t('storage.missing.title')}
              description={t('storage.missing.description')}
              action={<ToneBadge tone="red" label={t('storage.missing.badge')} />}
            >
              <Box sx={{ overflowX: 'auto' }}>
                <MissingBinariesTable binaries={missing} />
              </Box>
            </SectionCard>
          )}

          {orphaned.length > 0 && (
            <SectionCard
              icon={Boxes}
              title={t('storage.orphaned.title')}
              description={t('storage.orphaned.description')}
              action={<ToneBadge tone="amber" label={t('storage.orphaned.badge')} />}
            >
              <Stack spacing={1.5}>
                <Box>
                  <Button
                    size="small"
                    color="error"
                    variant="outlined"
                    startIcon={<Trash2 size={15} />}
                    disabled={selected.size === 0 || busy}
                    onClick={() => setPendingDelete([...selected])}
                  >
                    {t('storage.orphaned.deleteSelected', { count: selected.size })}
                  </Button>
                </Box>
                <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
                  <Box sx={{ height: 3 }}>{busy && <LinearProgress />}</Box>
                  <Box sx={{ overflowX: 'auto' }}>
                    <OrphanedObjectsTable
                      objects={orphaned}
                      selected={selected}
                      onToggle={toggle}
                      onToggleAll={toggleAll}
                      onDeleteOne={(key) => setPendingDelete([key])}
                      busy={busy}
                    />
                  </Box>
                </Paper>
              </Stack>
            </SectionCard>
          )}
        </>
      )}

      <ConfirmDialog
        open={pendingDelete !== null}
        title={t('storage.delete.title')}
        message={t('storage.delete.message', { count: pendingDelete?.length ?? 0 })}
        confirmLabel={t('common:actions.delete')}
        destructive
        onConfirm={confirmDelete}
        onClose={() => setPendingDelete(null)}
      />
      <AdminToast toast={toast} onClose={clear} />
    </Stack>
  );
}
