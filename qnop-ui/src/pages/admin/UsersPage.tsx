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
import IconButton from '@mui/material/IconButton';
import InputAdornment from '@mui/material/InputAdornment';
import LinearProgress from '@mui/material/LinearProgress';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TablePagination from '@mui/material/TablePagination';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { Search, UserPlus, X } from 'lucide-react';
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

const CONFLICT_MESSAGES: Record<string, string> = {
  LAST_ADMIN: 'At least one enabled admin must remain.',
  SELF_LOCKOUT: "You can't disable or change the role of your own account.",
  SELF_DELETE: "You can't delete your own account.",
};

/** Admin user management: search/filter/sort, paginate, create, edit, delete and reset (#104/#124). */
export function UsersPage() {
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
      notify(conflictOr(err, `Could not update ${user.displayName}.`), 'error');
    }
  };

  const onResetPassword = async (user: AdminUserSummary) => {
    try {
      const outcome = await sendReset.mutateAsync(user.id);
      if (outcome.emailSent) {
        notify(`Password reset emailed to ${user.email}. Sessions revoked.`);
      } else if (outcome.resetUrl) {
        setResetLink({ displayName: user.displayName, url: outcome.resetUrl });
      } else {
        notify(`Reset triggered for ${user.displayName}, but no link was returned.`, 'error');
      }
    } catch (err) {
      notify(conflictOr(err, 'The reset could not be triggered.'), 'error');
    }
  };

  const onGeneratePassword = async (user: AdminUserSummary) => {
    try {
      const outcome = await generatePassword.mutateAsync(user.id);
      setGeneratedPassword({ displayName: user.displayName, password: outcome.password });
    } catch (err) {
      notify(conflictOr(err, 'A password could not be generated.'), 'error');
    }
  };

  const confirmDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteUser.mutateAsync(deleteTarget.id);
      notify(`User “${deleteTarget.displayName}” deleted.`);
    } catch (err) {
      notify(conflictOr(err, 'Could not delete the user.'), 'error');
    } finally {
      setDeleteTarget(null);
    }
  };

  return (
    <Stack spacing={3}>
      <PageHeader
        title="Users"
        description="Manage accounts, roles and access."
        action={
          <Button variant="contained" startIcon={<UserPlus size={18} />} onClick={openCreate}>
            Add user
          </Button>
        }
      />

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ flexWrap: 'wrap' }}>
        <TextField
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search by name, email or username"
          size="small"
          sx={{ flex: 1, minWidth: 240, maxWidth: 420 }}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <Search size={16} />
                </InputAdornment>
              ),
              endAdornment: search ? (
                <InputAdornment position="end">
                  <IconButton
                    aria-label="Clear search"
                    size="small"
                    edge="end"
                    onClick={() => setSearch('')}
                  >
                    <X size={16} />
                  </IconButton>
                </InputAdornment>
              ) : undefined,
            },
          }}
        />
        <TextField
          select
          label="Role"
          value={roleFilter}
          onChange={(e) => {
            setRoleFilter(e.target.value as UserRole | '');
            setPage(0);
          }}
          size="small"
          sx={{ minWidth: 150 }}
        >
          <MenuItem value="">All roles</MenuItem>
          <MenuItem value="ADMIN">Admin</MenuItem>
          <MenuItem value="MEMBER">Member</MenuItem>
          <MenuItem value="AUDITOR">Auditor</MenuItem>
        </TextField>
        <TextField
          select
          label="Status"
          value={statusFilter}
          onChange={(e) => {
            setStatusFilter(e.target.value as StatusFilter);
            setPage(0);
          }}
          size="small"
          sx={{ minWidth: 150 }}
        >
          <MenuItem value="">All statuses</MenuItem>
          <MenuItem value="active">Active</MenuItem>
          <MenuItem value="disabled">Disabled</MenuItem>
        </TextField>
      </Stack>

      <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
        <Box sx={{ height: 3 }}>{isFetching && <LinearProgress />}</Box>
        {isError ? (
          <Alert severity="error" sx={{ m: 2 }}>
            The user list could not be loaded.
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
              labelRowsPerPage="Users per page"
              labelDisplayedRows={({ from, to, count }) => `${from}–${to} of ${count}`}
            />
          </>
        )}
        {isLoading && (
          <Typography color="text.secondary" sx={{ p: 2, fontSize: 14 }}>
            Loading…
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
        title="Delete user"
        message={`Permanently delete “${deleteTarget?.displayName}”? Their sessions, settings and team memberships are removed. This cannot be undone.`}
        confirmLabel="Delete"
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

/** Maps a known 409 conflict code to a friendly message, else the generic fallback. */
function conflictOr(err: unknown, fallback: string): string {
  const code = apiErrorCode(err);
  return (code && CONFLICT_MESSAGES[code]) ?? apiErrorMessage(err, fallback);
}
