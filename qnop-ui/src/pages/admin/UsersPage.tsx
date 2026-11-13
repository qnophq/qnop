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
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import LinearProgress from '@mui/material/LinearProgress';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TablePagination from '@mui/material/TablePagination';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import type { ParseKeys } from 'i18next';
import { UserPlus } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { ClearableSearchField } from '../../components/ClearableSearchField';
import type { AdminUserSummary, UserRole } from '../../api/generated';
import {
  useAdminUsers,
  useDeleteUser,
  useGenerateUserPassword,
  useSendUserPasswordReset,
  useUpdateUser,
} from '../../api/hooks/useAdminUsers';
import { UserFormDialog } from '../../components/admin/users/UserFormDialog';
import { UsersTable } from '../../components/admin/users/UsersTable';
import { ResetLinkDialog } from '../../components/admin/users/ResetLinkDialog';
import { GeneratedPasswordDialog } from '../../components/admin/users/GeneratedPasswordDialog';
import { ConfirmDialog } from '../../components/admin/ConfirmDialog';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { QuotaAlert } from '../../components/limits/QuotaAlert';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { useToast } from '../../components/admin/layout/useToast';
import { useAuthStore } from '../../stores/authStore';
import { apiErrorCode, apiErrorMessage } from '../../utils/apiError';

const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];
const DEFAULT_PAGE_SIZE = 20;
const DEFAULT_SORT = 'displayName,asc';

type DialogState =
  | { open: false }
  | { open: true; mode: 'create' }
  | { open: true; mode: 'edit'; user: AdminUserSummary };

type StatusFilter = '' | 'active' | 'disabled';

const CONFLICT_MESSAGES: Record<string, ParseKeys<'admin'>> = {
  LAST_ADMIN: 'users.conflicts.LAST_ADMIN',
  SELF_LOCKOUT: 'users.conflicts.SELF_LOCKOUT',
  SELF_DELETE: 'users.conflicts.SELF_DELETE',
};

/** Admin user management: search/filter/sort, paginate, create, edit, delete and reset (#104/#124). */
export function UsersPage() {
  const { t } = useTranslation(['admin', 'common']);
  const currentUserId = useAuthStore((s) => s.userId);
  const updateUser = useUpdateUser();
  const deleteUser = useDeleteUser();
  const sendReset = useSendUserPasswordReset();
  const generatePassword = useGenerateUserPassword();

  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [roleFilter, setRoleFilter] = useState<UserRole | ''>('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('');
  const [sort, setSort] = useState(DEFAULT_SORT);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [dialog, setDialog] = useState<DialogState>({ open: false });
  const [openSeq, setOpenSeq] = useState(0);
  const [deleteTarget, setDeleteTarget] = useState<AdminUserSummary | null>(null);
  const [resetLink, setResetLink] = useState<{ displayName: string; url: string } | null>(null);
  const [generatedPassword, setGeneratedPassword] = useState<{
    displayName: string;
    password: string;
  } | null>(null);
  const { toast, notify, clear } = useToast();

  /** Maps a known 409 conflict code to a friendly message, else the generic fallback. */
  const conflictOr = (err: unknown, fallback: string): string => {
    const code = apiErrorCode(err);
    const conflictKey = code ? CONFLICT_MESSAGES[code] : undefined;
    return conflictKey ? t(conflictKey) : apiErrorMessage(err, fallback);
  };

  const openCreate = () => {
    setDialog({ open: true, mode: 'create' });
    setOpenSeq((n) => n + 1);
  };
  const openEdit = (user: AdminUserSummary) => {
    setDialog({ open: true, mode: 'edit', user });
    setOpenSeq((n) => n + 1);
  };

  // Debounce the search box and reset to the first page when the query changes.
  useEffect(() => {
    const handle = setTimeout(() => {
      setDebouncedSearch(search);
      setPage(0);
    }, 300);
    return () => clearTimeout(handle);
  }, [search]);

  const { data, isLoading, isFetching, isError } = useAdminUsers({
    q: debouncedSearch || undefined,
    role: roleFilter || undefined,
    enabled: statusFilter === '' ? undefined : statusFilter === 'active',
    sort,
    page,
    size: pageSize,
  });

  const users = data?.items ?? [];
  const total = data?.total ?? 0;

  const onSort = (field: string) => {
    const [currentField, currentDir] = sort.split(',');
    const nextDir = currentField === field && currentDir === 'asc' ? 'desc' : 'asc';
    setSort(`${field},${nextDir}`);
    setPage(0);
  };

  const onToggleEnabled = async (user: AdminUserSummary) => {
    try {
      await updateUser.mutateAsync({ id: user.id, request: { enabled: !user.enabled } });
    } catch (err) {
      notify(conflictOr(err, t('users.toasts.updateFailed', { name: user.displayName })), 'error');
    }
  };

  const onResetPassword = async (user: AdminUserSummary) => {
    try {
      const outcome = await sendReset.mutateAsync(user.id);
      if (outcome.emailSent) {
        notify(t('users.toasts.resetEmailed', { email: user.email }));
      } else if (outcome.resetUrl) {
        setResetLink({ displayName: user.displayName, url: outcome.resetUrl });
      } else {
        notify(t('users.toasts.resetNoLink', { name: user.displayName }), 'error');
      }
    } catch (err) {
      notify(conflictOr(err, t('users.toasts.resetFailed')), 'error');
    }
  };

  const onGeneratePassword = async (user: AdminUserSummary) => {
    try {
      const outcome = await generatePassword.mutateAsync(user.id);
      setGeneratedPassword({ displayName: user.displayName, password: outcome.password });
    } catch (err) {
      notify(conflictOr(err, t('users.toasts.generateFailed')), 'error');
    }
  };

  const confirmDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteUser.mutateAsync(deleteTarget.id);
      notify(t('users.toasts.deleted', { name: deleteTarget.displayName }));
    } catch (err) {
      notify(conflictOr(err, t('users.toasts.deleteFailed')), 'error');
    } finally {
      setDeleteTarget(null);
    }
  };

  // What this deployment allows (issue #687). `seatLimit` is 0 where there is no
  // ceiling, which is the ordinary self-hosted case and shows nothing at all.
  const seatLimit = data?.seatLimit ?? 0;
  const seatsUsed = data?.seatsUsed ?? 0;
  const seatsFull = seatLimit > 0 && seatsUsed >= seatLimit;

  return (
    <Stack spacing={3}>
      <PageHeader
        title={t('users.page.title')}
        description={
          seatLimit > 0
            ? t('users.page.descriptionWithSeats', { used: seatsUsed, count: seatLimit })
            : t('users.page.description')
        }
        action={
          // Disabled rather than left to fail: filling in a name, an email and a
          // password only to be refused at submit is the worst way to learn that
          // an instance is full. Disabled accounts occupy a seat too, so freeing
          // one means deleting, not deactivating.
          <Button
            variant="contained"
            startIcon={<UserPlus size={18} />}
            disabled={seatsFull}
            onClick={openCreate}
          >
            {t('users.page.addUser')}
          </Button>
        }
      />

      {seatsFull && (
        <QuotaAlert>{t('users.page.seatsFull', { used: seatsUsed, count: seatLimit })}</QuotaAlert>
      )}

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ flexWrap: 'wrap' }}>
        <ClearableSearchField
          value={search}
          onValueChange={setSearch}
          placeholder={t('users.page.searchPlaceholder')}
          inputAriaLabel={t('users.page.searchLabel')}
          sx={{ flex: 1, minWidth: 240, maxWidth: 420 }}
        />
        <TextField
          select
          label={t('users.page.roleFilter')}
          value={roleFilter}
          onChange={(e) => {
            setRoleFilter(e.target.value as UserRole | '');
            setPage(0);
          }}
          size="small"
          sx={{ minWidth: 150 }}
        >
          <MenuItem value="">{t('users.page.allRoles')}</MenuItem>
          <MenuItem value="ADMIN">{t('roles.global.ADMIN')}</MenuItem>
          <MenuItem value="MEMBER">{t('roles.global.MEMBER')}</MenuItem>
          <MenuItem value="AUDITOR">{t('roles.global.AUDITOR')}</MenuItem>
        </TextField>
        <TextField
          select
          label={t('users.page.statusFilter')}
          value={statusFilter}
          onChange={(e) => {
            setStatusFilter(e.target.value as StatusFilter);
            setPage(0);
          }}
          size="small"
          sx={{ minWidth: 150 }}
        >
          <MenuItem value="">{t('users.page.allStatuses')}</MenuItem>
          <MenuItem value="active">{t('users.status.active')}</MenuItem>
          <MenuItem value="disabled">{t('users.status.disabled')}</MenuItem>
        </TextField>
      </Stack>

      <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
        <Box sx={{ height: 3 }}>{isFetching && <LinearProgress />}</Box>
        {isError ? (
          <Alert severity="error" sx={{ m: 2 }}>
            {t('users.page.loadError')}
          </Alert>
        ) : (
          <>
            <UsersTable
              users={users}
              currentUserId={currentUserId}
              sort={sort}
              onSort={onSort}
              onEdit={openEdit}
              onResetPassword={onResetPassword}
              onGeneratePassword={onGeneratePassword}
              onToggleEnabled={onToggleEnabled}
              onDelete={setDeleteTarget}
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
              labelRowsPerPage={t('users.page.rowsPerPage')}
              labelDisplayedRows={({ from, to, count }) =>
                t('users.page.displayedRows', { from, to, count })
              }
            />
          </>
        )}
        {isLoading && (
          <Typography color="text.secondary" sx={{ p: 2, fontSize: 14 }}>
            {t('common:loading')}
          </Typography>
        )}
      </Paper>

      <UserFormDialog
        key={openSeq}
        open={dialog.open}
        mode={dialog.open ? dialog.mode : 'create'}
        user={dialog.open && dialog.mode === 'edit' ? dialog.user : undefined}
        onClose={() => setDialog({ open: false })}
      />

      <ConfirmDialog
        open={deleteTarget !== null}
        title={t('users.delete.title')}
        message={t('users.delete.message', { name: deleteTarget?.displayName ?? '' })}
        confirmLabel={t('common:actions.delete')}
        destructive
        onConfirm={confirmDelete}
        onClose={() => setDeleteTarget(null)}
      />

      <ResetLinkDialog
        open={resetLink !== null}
        displayName={resetLink?.displayName ?? ''}
        url={resetLink?.url ?? ''}
        onClose={() => setResetLink(null)}
      />

      <GeneratedPasswordDialog
        open={generatedPassword !== null}
        displayName={generatedPassword?.displayName ?? ''}
        password={generatedPassword?.password ?? ''}
        onClose={() => setGeneratedPassword(null)}
      />

      <AdminToast toast={toast} onClose={clear} />
    </Stack>
  );
}
