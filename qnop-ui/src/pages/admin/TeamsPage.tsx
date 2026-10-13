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

import { useEffect, useState, type MouseEvent } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import IconButton from '@mui/material/IconButton';
import Link from '@mui/material/Link';
import LinearProgress from '@mui/material/LinearProgress';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TablePagination from '@mui/material/TablePagination';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import { MoreVertical, SquarePen, Trash2, UsersRound } from 'lucide-react';
import { ClearableSearchField } from '../../components/ClearableSearchField';
import { TeamAvatar } from '../../components/shell/TeamAvatar';
import { Link as RouterLink, useNavigate } from 'react-router';
import type { AdminTeamSummary } from '../../api/generated';
import { useDeleteTeam, useTeams } from '../../api/hooks/useTeams';
import { TeamFormDialog } from '../../components/admin/teams/TeamFormDialog';
import { ConfirmDialog } from '../../components/admin/ConfirmDialog';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { QuotaAlert } from '../../components/limits/QuotaAlert';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { useToast } from '../../components/admin/layout/useToast';
import { UserStatusBadge } from '../../components/admin/users/UserBadges';
import { apiErrorMessage } from '../../utils/apiError';

const PAGE_SIZE = 20;

type DialogState =
  | { open: false }
  | { open: true; mode: 'create' }
  | { open: true; mode: 'edit'; team: AdminTeamSummary };

/** Admin team management: search, paginate, create / edit / delete, open a team (#105). */
export function TeamsPage() {
  const { t } = useTranslation(['admin', 'common']);
  const navigate = useNavigate();
  const deleteTeam = useDeleteTeam();
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState<DialogState>({ open: false });
  const [openSeq, setOpenSeq] = useState(0);
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const [active, setActive] = useState<AdminTeamSummary | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<AdminTeamSummary | null>(null);
  const { toast, notify, clear } = useToast();

  useEffect(() => {
    const handle = setTimeout(() => {
      setDebouncedSearch(search);
      setPage(0);
    }, 300);
    return () => clearTimeout(handle);
  }, [search]);

  const { data, isLoading, isFetching, isError } = useTeams({
    q: debouncedSearch || undefined,
    page,
    size: PAGE_SIZE,
  });
  // What this deployment allows (issue #688); 0 means no ceiling, which is the
  // ordinary self-hosted case and shows nothing.
  const teamLimit = data?.teamLimit ?? 0;
  const teamsUsed = data?.teamsUsed ?? 0;
  const teamsFull = teamLimit > 0 && teamsUsed >= teamLimit;
  const teams = data?.items ?? [];
  const total = data?.total ?? 0;

  const openCreate = () => {
    setDialog({ open: true, mode: 'create' });
    setOpenSeq((n) => n + 1);
  };
  const openEdit = (team: AdminTeamSummary) => {
    setDialog({ open: true, mode: 'edit', team });
    setOpenSeq((n) => n + 1);
  };
  const openMenu = (event: MouseEvent<HTMLElement>, team: AdminTeamSummary) => {
    event.stopPropagation();
    setAnchorEl(event.currentTarget);
    setActive(team);
  };

  const confirmDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteTeam.mutateAsync(deleteTarget.id);
      notify(t('teams.list.deleted', { name: deleteTarget.name }));
    } catch (err) {
      notify(apiErrorMessage(err, t('teams.list.deleteFailed')), 'error');
    } finally {
      setDeleteTarget(null);
    }
  };

  return (
    <Stack spacing={3}>
      <PageHeader
        title={t('teams.list.title')}
        description={
          teamLimit > 0
            ? t('teams.list.descriptionWithLimit', { count: teamLimit, used: teamsUsed })
            : t('teams.list.description')
        }
        action={
          // Disabled rather than left to fail (issue #688): the refusal is
          // correct either way, but learning about a ceiling by filling in a
          // form and being turned away reads as a fault rather than a setting.
          <Button
            variant="contained"
            startIcon={<UsersRound size={18} />}
            disabled={teamsFull}
            onClick={openCreate}
          >
            {t('teams.list.create')}
          </Button>
        }
      />

      {teamsFull && (
        <QuotaAlert>{t('teams.list.quotaFull', { count: teamLimit, used: teamsUsed })}</QuotaAlert>
      )}

      <ClearableSearchField
        value={search}
        onValueChange={setSearch}
        placeholder={t('teams.list.searchPlaceholder')}
        sx={{ maxWidth: 420 }}
      />

      <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
        <Box sx={{ height: 3 }}>{isFetching && <LinearProgress />}</Box>
        {isError ? (
          <Alert severity="error" sx={{ m: 2 }}>
            {t('teams.list.loadError')}
          </Alert>
        ) : (
          <>
            <Table size="medium" sx={{ '& td, & th': { borderColor: 'divider' } }}>
              <TableHead>
                <TableRow>
                  <TableCell>{t('teams.list.columns.name')}</TableCell>
                  <TableCell>{t('teams.list.columns.description')}</TableCell>
                  <TableCell>{t('teams.list.columns.members')}</TableCell>
                  <TableCell>{t('teams.list.columns.status')}</TableCell>
                  <TableCell align="right">{t('teams.list.columns.actions')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {teams.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={5}>
                      <Typography color="text.secondary" sx={{ py: 4, textAlign: 'center' }}>
                        {t('teams.list.empty')}
                      </Typography>
                    </TableCell>
                  </TableRow>
                )}
                {teams.map((team) => (
                  <TableRow
                    key={team.id}
                    hover
                    sx={{ cursor: 'pointer' }}
                    onClick={() => navigate(`/admin/teams/${team.id}`)}
                  >
                    <TableCell sx={{ fontWeight: 600 }}>
                      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
                        <TeamAvatar name={team.name} imageUrl={team.avatarUrl} size={28} />
                        {/* Links to the public team profile (issue #586); an id
                            visit canonicalises to the slug URL. */}
                        <Link
                          component={RouterLink}
                          to={`/teams/${team.id}`}
                          underline="hover"
                          color="inherit"
                        >
                          {team.name}
                        </Link>
                      </Stack>
                    </TableCell>
                    <TableCell sx={{ color: 'text.secondary', maxWidth: 320 }}>
                      <Typography noWrap sx={{ fontSize: 14 }}>
                        {team.description || '—'}
                      </Typography>
                    </TableCell>
                    <TableCell>{team.memberCount}</TableCell>
                    <TableCell>
                      <UserStatusBadge enabled={team.enabled} />
                    </TableCell>
                    <TableCell align="right">
                      <IconButton
                        size="small"
                        aria-label={t('teams.list.actionsFor', { name: team.name })}
                        onClick={(e) => openMenu(e, team)}
                      >
                        <MoreVertical size={18} />
                      </IconButton>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <TablePagination
              component="div"
              count={total}
              page={page}
              rowsPerPage={PAGE_SIZE}
              rowsPerPageOptions={[PAGE_SIZE]}
              onPageChange={(_, next) => setPage(next)}
              labelDisplayedRows={({ from, to, count }) =>
                t('teams.list.displayedRows', { from, to, total: count })
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

      <Menu anchorEl={anchorEl} open={Boolean(anchorEl)} onClose={() => setAnchorEl(null)}>
        <MenuItem
          onClick={() => {
            setAnchorEl(null);
            if (active) openEdit(active);
          }}
        >
          <ListItemIcon>
            <SquarePen size={16} />
          </ListItemIcon>
          <ListItemText>{t('common:actions.edit')}</ListItemText>
        </MenuItem>
        <MenuItem
          onClick={() => {
            setAnchorEl(null);
            setDeleteTarget(active);
          }}
        >
          <ListItemIcon>
            <Trash2 size={16} />
          </ListItemIcon>
          <ListItemText>{t('common:actions.delete')}</ListItemText>
        </MenuItem>
      </Menu>

      <TeamFormDialog
        key={openSeq}
        open={dialog.open}
        mode={dialog.open ? dialog.mode : 'create'}
        team={dialog.open && dialog.mode === 'edit' ? dialog.team : undefined}
        onClose={() => setDialog({ open: false })}
      />

      <ConfirmDialog
        open={deleteTarget !== null}
        title={t('teams.list.delete.title')}
        message={t('teams.list.delete.message', { name: deleteTarget?.name })}
        confirmLabel={t('common:actions.delete')}
        destructive
        onConfirm={confirmDelete}
        onClose={() => setDeleteTarget(null)}
      />

      <AdminToast toast={toast} onClose={clear} />
    </Stack>
  );
}
