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
import { Plus } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import type { OidcProviderDto } from '../../api/generated';
import {
  useDeleteOidcProvider,
  useOidcProviders,
  useUpdateOidcProvider,
} from '../../api/hooks/useOidcProviders';
import { ConfirmDialog } from '../../components/admin/ConfirmDialog';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { useToast } from '../../components/admin/layout/useToast';
import { OidcProviderFormDialog } from '../../components/admin/oidc/OidcProviderFormDialog';
import { OidcProvidersTable } from '../../components/admin/oidc/OidcProvidersTable';
import { apiErrorMessage } from '../../utils/apiError';

type DialogState =
  | { open: false }
  | { open: true; mode: 'create' }
  | { open: true; mode: 'edit'; provider: OidcProviderDto };

/** Admin OIDC/OAuth2 provider management: list, add, edit, enable and delete (#106). */
export function OidcProvidersPage() {
  const { t } = useTranslation(['admin', 'common']);
  const { data, isLoading, isFetching, isError } = useOidcProviders();
  const updateProvider = useUpdateOidcProvider();
  const deleteProvider = useDeleteOidcProvider();

  const [dialog, setDialog] = useState<DialogState>({ open: false });
  const [openSeq, setOpenSeq] = useState(0);
  const [toDelete, setToDelete] = useState<OidcProviderDto | null>(null);
  const { toast, notify, clear } = useToast();

  const providers = data?.providers ?? [];

  const openCreate = () => {
    setDialog({ open: true, mode: 'create' });
    setOpenSeq((n) => n + 1);
  };
  const openEdit = (provider: OidcProviderDto) => {
    setDialog({ open: true, mode: 'edit', provider });
    setOpenSeq((n) => n + 1);
  };

  const onToggleEnabled = async (provider: OidcProviderDto) => {
    try {
      await updateProvider.mutateAsync({
        id: provider.id,
        request: { enabled: !provider.enabled },
      });
      notify(
        provider.enabled
          ? t('oidc.toast.disabled', { name: provider.name })
          : t('oidc.toast.enabled', { name: provider.name }),
      );
    } catch (err) {
      notify(apiErrorMessage(err, t('oidc.errors.toggle')), 'error');
    }
  };

  const confirmDelete = async () => {
    if (!toDelete) return;
    try {
      await deleteProvider.mutateAsync(toDelete.id);
      notify(t('oidc.toast.deleted', { name: toDelete.name }));
    } catch (err) {
      notify(apiErrorMessage(err, t('oidc.errors.delete')), 'error');
    } finally {
      setToDelete(null);
    }
  };

  return (
    <Stack spacing={3}>
      <PageHeader
        title={t('oidc.page.title')}
        description={t('oidc.page.description')}
        action={
          <Button variant="contained" startIcon={<Plus size={18} />} onClick={openCreate}>
            {t('oidc.page.add')}
          </Button>
        }
      />

      <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
        <Box sx={{ height: 3 }}>{isFetching && <LinearProgress />}</Box>
        {isError ? (
          <Alert severity="error" sx={{ m: 2 }}>
            {t('oidc.errors.load')}
          </Alert>
        ) : (
          <OidcProvidersTable
            providers={providers}
            onEdit={openEdit}
            onToggleEnabled={onToggleEnabled}
            onDelete={setToDelete}
          />
        )}
        {isLoading && (
          <Typography color="text.secondary" sx={{ p: 2, fontSize: 14 }}>
            {t('common:loading')}
          </Typography>
        )}
      </Paper>

      <OidcProviderFormDialog
        key={openSeq}
        open={dialog.open}
        mode={dialog.open ? dialog.mode : 'create'}
        provider={dialog.open && dialog.mode === 'edit' ? dialog.provider : undefined}
        onClose={() => setDialog({ open: false })}
      />

      <ConfirmDialog
        open={toDelete !== null}
        title={t('oidc.delete.title')}
        message={t('oidc.delete.message', { name: toDelete?.name })}
        confirmLabel={t('common:actions.delete')}
        destructive
        onConfirm={confirmDelete}
        onClose={() => setToDelete(null)}
      />

      <AdminToast toast={toast} onClose={clear} />
    </Stack>
  );
}
