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

import type { KeyboardEvent } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import LinearProgress from '@mui/material/LinearProgress';
import Paper from '@mui/material/Paper';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { ChevronRight } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import type { MailTemplateResponse } from '../../api/generated';
import { useMailTemplates } from '../../api/hooks/useMailTemplates';
import { ToneBadge } from '../../components/admin/ToneBadge';
import { useFormatters } from '../../hooks/useFormatters';
import { localeDisplayName, localeShortCode } from '../../utils/locale';

const COLUMNS = ['Template', 'Subject', 'Language', 'Source', 'Last edited', ''];

/** A compact, navigable template row: name + key, subject, language, source and edit attribution. */
function TemplateRow({ template, onOpen }: { template: MailTemplateResponse; onOpen: () => void }) {
  const { formatRelative } = useFormatters();
  const customised = template.source === 'DATABASE';
  const onKeyDown = (event: KeyboardEvent<HTMLTableRowElement>) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onOpen();
    }
  };

  return (
    <TableRow
      hover
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={onKeyDown}
      aria-label={`Edit the ${template.friendlyName} template`}
      sx={{
        cursor: 'pointer',
        '& td': { borderColor: 'divider' },
        '&:hover .row-chevron': { opacity: 1, transform: 'translateX(2px)' },
        '&:focus-visible': {
          outline: (t) => `2px solid ${t.qnop.brand.blue}`,
          outlineOffset: '-2px',
        },
      }}
    >
      <TableCell sx={{ minWidth: 200 }}>
        <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{template.friendlyName}</Typography>
        <Typography sx={{ fontSize: 12, fontFamily: 'monospace', color: 'text.secondary' }} noWrap>
          {template.key}
        </Typography>
      </TableCell>

      <TableCell sx={{ maxWidth: 280 }}>
        <Typography sx={{ fontSize: 13, color: 'text.secondary' }} noWrap title={template.subject}>
          {template.subject}
        </Typography>
      </TableCell>

      <TableCell>
        <Tooltip title={localeDisplayName(template.locale)}>
          <Box component="span" sx={{ display: 'inline-flex' }}>
            <ToneBadge tone="neutral" label={localeShortCode(template.locale)} />
          </Box>
        </Tooltip>
      </TableCell>

      <TableCell>
        <ToneBadge
          tone={customised ? 'blue' : 'neutral'}
          label={customised ? 'Custom' : 'Default'}
        />
      </TableCell>

      <TableCell sx={{ whiteSpace: 'nowrap' }}>
        {customised ? (
          <>
            <Typography sx={{ fontSize: 13 }}>{formatRelative(template.updatedAt)}</Typography>
            {template.updatedByName && (
              <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
                by {template.updatedByName}
              </Typography>
            )}
          </>
        ) : (
          <Typography sx={{ fontSize: 13, color: 'text.disabled' }}>—</Typography>
        )}
      </TableCell>

      <TableCell align="right" sx={{ width: 44 }}>
        <Box
          className="row-chevron"
          sx={{
            display: 'inline-flex',
            color: 'text.secondary',
            opacity: 0.5,
            transition: (t) => t.transitions.create(['transform', 'opacity']),
          }}
          aria-hidden
        >
          <ChevronRight size={18} />
        </Box>
      </TableCell>
    </TableRow>
  );
}

/**
 * Mail-templates list (issue #144), the Templates tab of the email admin area (#525): a compact,
 * navigable table over the closed registry — no "add" CTA. Templates are managed per language
 * (today only the configured default locale exists); the Language column anticipates the
 * per-locale variants i18n will add. Customised templates carry an edit attribution, built-ins
 * read as factory defaults. Opening a row routes to its editor.
 */
export function MailTemplatesListPage() {
  const { data, isLoading, isFetching, isError } = useMailTemplates();
  const navigate = useNavigate();

  const templates = data?.templates ?? [];

  return (
    <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
      <Box sx={{ height: 3 }}>{isFetching && <LinearProgress />}</Box>
      {isError ? (
        <Alert severity="error" sx={{ m: 2 }}>
          The mail templates could not be loaded.
        </Alert>
      ) : (
        <Table size="medium" sx={{ '& th': { borderColor: 'divider' } }}>
          <TableHead>
            <TableRow>
              {COLUMNS.map((col, index) => (
                <TableCell
                  key={col || 'chevron'}
                  align={index === COLUMNS.length - 1 ? 'right' : 'left'}
                  sx={{ fontSize: 12, color: 'text.secondary', fontWeight: 600 }}
                >
                  {col}
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {!isLoading && templates.length === 0 && (
              <TableRow>
                <TableCell colSpan={COLUMNS.length}>
                  <Typography color="text.secondary" sx={{ py: 4, textAlign: 'center' }}>
                    No mail templates.
                  </Typography>
                </TableCell>
              </TableRow>
            )}
            {templates.map((template) => (
              <TemplateRow
                key={template.key}
                template={template}
                onOpen={() =>
                  navigate(`/admin/email/templates/${encodeURIComponent(template.key)}`)
                }
              />
            ))}
          </TableBody>
        </Table>
      )}
      {isLoading && (
        <Typography color="text.secondary" sx={{ p: 2, fontSize: 14 }}>
          Loading…
        </Typography>
      )}
    </Paper>
  );
}
