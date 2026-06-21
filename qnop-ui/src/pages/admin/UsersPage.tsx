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
import InputAdornment from '@mui/material/InputAdornment';
import LinearProgress from '@mui/material/LinearProgress';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Snackbar from '@mui/material/Snackbar';
import Stack from '@mui/material/Stack';
import TablePagination from '@mui/material/TablePagination';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { Search, UserPlus } from 'lucide-react';
import type { AdminUserSummary, UserRole } from '../../api/generated';
import { useAdminUsers, useSendUserPasswordReset } from '../../api/hooks/useAdminUsers';
import { UserFormDialog } from '../../components/admin/users/UserFormDialog';
import { UsersTable } from '../../components/admin/users/UsersTable';
import { apiErrorMessage } from '../../utils/apiError';

const PAGE_SIZE = 20;

type DialogState =
  | { open: false }
  | { open: true; mode: 'create' }
  | { open: true; mode: 'edit'; user: AdminUserSummary };

type Toast = { message: string; severity: 'success' | 'error' } | null;

/** Admin user management: search, filter, paginate, create/invite, edit and reset (#104). */
export function UsersPage() {
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [roleFilter, setRoleFilter] = useState<UserRole | ''>('');
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState<DialogState>({ open: false });
  // Bumped on every open so the dialog remounts with fresh state (see UserFormDialog).
  const [openSeq, setOpenSeq] = useState(0);
  const [toast, setToast] = useState<Toast>(null);

  const sendReset = useSendUserPasswordReset();

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
    page,
    size: PAGE_SIZE,
  });

  const users = data?.items ?? [];
  const total = data?.total ?? 0;

  const onResetPassword = async (user: AdminUserSummary) => {
    try {
      await sendReset.mutateAsync(user.id);
      setToast({ message: `Passwort-Link an ${user.email} gesendet.`, severity: 'success' });
    } catch (err) {
      setToast({
        message: apiErrorMessage(err, 'Der Link konnte nicht gesendet werden.'),
        severity: 'error',
      });
    }
  };

  return (
    <Stack spacing={3}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' } }}
      >
        <Box>
          <Typography variant="h1" sx={{ fontSize: 28 }}>
            Benutzer
          </Typography>
          <Typography color="text.secondary" sx={{ mt: 0.5 }}>
            Konten, Rollen und Zugriff verwalten.
          </Typography>
        </Box>
        <Button variant="contained" startIcon={<UserPlus size={18} />} onClick={openCreate}>
          Benutzer anlegen
        </Button>
      </Stack>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
        <TextField
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Nach Name, E-Mail oder Benutzername suchen"
          size="small"
          sx={{ flex: 1, maxWidth: 420 }}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <Search size={16} />
                </InputAdornment>
              ),
            },
          }}
        />
        <TextField
          select
          label="Rolle"
          value={roleFilter}
          onChange={(e) => {
            setRoleFilter(e.target.value as UserRole | '');
            setPage(0);
          }}
          size="small"
          sx={{ minWidth: 160 }}
        >
          <MenuItem value="">Alle Rollen</MenuItem>
          <MenuItem value="ADMIN">Admin</MenuItem>
          <MenuItem value="MEMBER">Mitglied</MenuItem>
          <MenuItem value="AUDITOR">Auditor</MenuItem>
        </TextField>
      </Stack>

      <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
        <Box sx={{ height: 3 }}>{isFetching && <LinearProgress />}</Box>
        {isError ? (
          <Alert severity="error" sx={{ m: 2 }}>
            Die Benutzerliste konnte nicht geladen werden.
          </Alert>
        ) : (
          <>
            <UsersTable users={users} onEdit={openEdit} onResetPassword={onResetPassword} />
            <TablePagination
              component="div"
              count={total}
              page={page}
              rowsPerPage={PAGE_SIZE}
              rowsPerPageOptions={[PAGE_SIZE]}
              onPageChange={(_, next) => setPage(next)}
              labelDisplayedRows={({ from, to, count }) => `${from}–${to} von ${count}`}
            />
          </>
        )}
        {isLoading && (
          <Typography color="text.secondary" sx={{ p: 2, fontSize: 14 }}>
            Lädt …
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

      <Snackbar
        open={toast !== null}
        autoHideDuration={5000}
        onClose={() => setToast(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        {toast ? (
          <Alert severity={toast.severity} onClose={() => setToast(null)} variant="filled">
            {toast.message}
          </Alert>
        ) : undefined}
      </Snackbar>
    </Stack>
  );
}
