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

import { useMemo, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import IconButton from '@mui/material/IconButton';
import InputAdornment from '@mui/material/InputAdornment';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { Clock, Info, Search, ServerCog, X } from 'lucide-react';
import type { ConfigurationEntry, ConfigurationGroup } from '../../api/generated';
import { useAdminConfiguration } from '../../api/hooks/useAdminConfiguration';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { SectionCard } from '../../components/admin/layout/SectionCard';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { useToast } from '../../components/admin/layout/useToast';
import { ToneBadge } from '../../components/admin/ToneBadge';
import { CopyTextButton } from '../../components/reviews/CopyTextButton';
import { tokens } from '../../theme/tokens';

/** A property path or env var, rendered as machine text with a copy affordance. */
function CodeCell({
  text,
  copyLabel,
  notify,
}: {
  text: string;
  copyLabel: string;
  notify: (m: string, s?: 'success' | 'error') => void;
}) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, minWidth: 0 }}>
      <Box
        component="code"
        sx={{
          fontFamily: tokens.font.mono,
          fontSize: 12.5,
          color: 'text.primary',
          wordBreak: 'break-all',
        }}
      >
        {text}
      </Box>
      <CopyTextButton
        text={text}
        notify={notify}
        label={copyLabel}
        copiedMessage={`Copied ${text}`}
      />
    </Box>
  );
}

/**
 * A hover/focus tooltip explaining what a property is, from the description the backend harvested
 * from its Javadoc. Renders nothing when the property is undocumented, so the column stays quiet.
 */
function PropertyInfo({ description }: { description?: string }) {
  if (!description) {
    return null;
  }
  return (
    <Tooltip title={description} arrow placement="top" enterTouchDelay={0}>
      <Box
        component="span"
        tabIndex={0}
        role="note"
        aria-label={description}
        sx={{
          display: 'inline-flex',
          flexShrink: 0,
          color: 'text.disabled',
          cursor: 'help',
          '&:hover, &:focus-visible': { color: 'text.secondary' },
        }}
      >
        <Info size={14} aria-hidden />
      </Box>
    </Tooltip>
  );
}

/** The value cell: state as form (chips) for what's scannable, machine text for the rest. */
function ValueCell({ entry }: { entry: ConfigurationEntry }) {
  switch (entry.valueType) {
    case 'SECRET':
      return entry.configured ? (
        <ToneBadge tone="green" label="Configured" />
      ) : (
        <ToneBadge tone="neutral" label="Not configured" />
      );
    case 'BOOLEAN':
      return entry.value === 'true' ? (
        <ToneBadge tone="green" label="true" />
      ) : (
        <ToneBadge tone="neutral" label="false" />
      );
    case 'UNSET':
      return (
        <Typography
          component="span"
          sx={{ fontSize: 13, color: 'text.disabled', fontStyle: 'italic' }}
        >
          not set
        </Typography>
      );
    case 'LIST': {
      const items = entry.value ? entry.value.split(', ').filter(Boolean) : [];
      if (items.length === 0) {
        return (
          <Typography
            component="span"
            sx={{ fontSize: 13, color: 'text.disabled', fontStyle: 'italic' }}
          >
            none
          </Typography>
        );
      }
      return (
        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
          {items.map((item) => (
            <ToneBadge key={item} tone="neutral" label={item} />
          ))}
        </Box>
      );
    }
    default: {
      const isDuration = entry.valueType === 'DURATION';
      return (
        <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
          {isDuration && (
            <Clock size={13} aria-hidden style={{ color: tokens.light.fg3, flexShrink: 0 }} />
          )}
          <Box
            component="code"
            sx={{ fontFamily: tokens.font.mono, fontSize: 12.5, color: 'text.primary' }}
          >
            {entry.value}
          </Box>
        </Box>
      );
    }
  }
}

/**
 * Fixed column proportions shared by every group's table, so the Path / Env var / Value columns line
 * up across all sections instead of each table sizing itself to its own content.
 */
const COLUMN_WIDTHS = ['38%', '37%', '25%'] as const;

/** One namespace's table: Path | Env var | Value. */
function GroupTable({
  entries,
  notify,
}: {
  entries: ConfigurationEntry[];
  notify: (m: string, s?: 'success' | 'error') => void;
}) {
  const theme = useTheme();
  return (
    <Box sx={{ overflowX: 'auto' }}>
      <Table
        size="small"
        sx={{ tableLayout: 'fixed', minWidth: 640, '& td, & th': { borderColor: 'divider' } }}
      >
        <colgroup>
          {COLUMN_WIDTHS.map((width, index) => (
            <col key={index} style={{ width }} />
          ))}
        </colgroup>
        <TableHead>
          <TableRow>
            {['Path', 'Environment variable', 'Value'].map((head) => (
              <TableCell
                key={head}
                sx={{
                  fontSize: 11.5,
                  fontWeight: 700,
                  letterSpacing: 0.4,
                  textTransform: 'uppercase',
                  color: 'text.secondary',
                }}
              >
                {head}
              </TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {entries.map((entry) => (
            <TableRow key={entry.path} hover sx={{ '&:hover': { bgcolor: theme.qnop.surface2 } }}>
              <TableCell sx={{ verticalAlign: 'top' }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, minWidth: 0 }}>
                  <CodeCell
                    text={entry.path}
                    copyLabel={`Copy path ${entry.path}`}
                    notify={notify}
                  />
                  <PropertyInfo description={entry.description} />
                </Box>
              </TableCell>
              <TableCell sx={{ verticalAlign: 'top' }}>
                <CodeCell
                  text={entry.envVar}
                  copyLabel={`Copy environment variable ${entry.envVar}`}
                  notify={notify}
                />
              </TableCell>
              <TableCell sx={{ verticalAlign: 'top' }}>
                <ValueCell entry={entry} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Box>
  );
}

/** Case-insensitive substring match on the path or env var. */
function filterGroups(groups: ConfigurationGroup[], query: string): ConfigurationGroup[] {
  const needle = query.trim().toLowerCase();
  if (!needle) {
    return groups;
  }
  return groups
    .map((group) => ({
      ...group,
      entries: group.entries.filter(
        (entry) =>
          entry.path.toLowerCase().includes(needle) || entry.envVar.toLowerCase().includes(needle),
      ),
    }))
    .filter((group) => group.entries.length > 0);
}

/**
 * The read-only effective-configuration surface (issue #522): the `qnop.*` settings the running
 * server bound at startup, grouped by namespace, with each property's env-var name and effective
 * value. Secrets are redacted server-side to a configured / not-configured chip — no secret value
 * is ever fetched. Operators verify a deployment here instead of shelling into the container.
 */
export function ConfigurationPage() {
  const { data, isLoading, isError } = useAdminConfiguration();
  const { toast, notify, clear } = useToast();
  const [query, setQuery] = useState('');

  const groups = useMemo(() => filterGroups(data?.groups ?? [], query), [data, query]);

  return (
    <Stack spacing={3}>
      <PageHeader
        title="Configuration"
        description="The effective qnop.* settings this server bound at startup, from application.yml and QNOP_* environment variables."
      />

      <Alert severity="info" variant="outlined">
        Read-only. These values are set in your deployment — change one by updating the environment
        (or <code>application.yml</code>) and restarting the server. Secrets are never shown here,
        only whether they are configured.
      </Alert>

      <TextField
        value={query}
        onChange={(event) => setQuery(event.target.value)}
        placeholder="Filter by property path or environment variable…"
        size="small"
        fullWidth
        slotProps={{
          input: {
            startAdornment: (
              <InputAdornment position="start">
                <Search size={16} aria-hidden />
              </InputAdornment>
            ),
            endAdornment: query ? (
              <InputAdornment position="end">
                <IconButton
                  aria-label="Clear filter"
                  size="small"
                  edge="end"
                  onClick={() => setQuery('')}
                >
                  <X size={16} />
                </IconButton>
              </InputAdornment>
            ) : undefined,
          },
        }}
        sx={{ maxWidth: 460 }}
      />

      {isLoading && (
        <Stack spacing={1.5}>
          <Skeleton variant="rounded" width="100%" height={52} />
          <Skeleton variant="rounded" width="100%" height={160} />
        </Stack>
      )}

      {isError && (
        <Alert severity="error">Could not load the server configuration. Please try again.</Alert>
      )}

      {!isLoading && !isError && groups.length === 0 && (
        <Typography color="text.secondary">No settings match “{query}”.</Typography>
      )}

      {groups.map((group) => (
        <SectionCard key={group.key} icon={ServerCog} title={`qnop.${group.key}`}>
          <GroupTable entries={group.entries} notify={notify} />
        </SectionCard>
      ))}

      <AdminToast toast={toast} onClose={clear} />
    </Stack>
  );
}
