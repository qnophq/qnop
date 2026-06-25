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
import List from '@mui/material/List';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemText from '@mui/material/ListItemText';
import Paper from '@mui/material/Paper';
import Snackbar from '@mui/material/Snackbar';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { MailCheck } from 'lucide-react';
import { useMailTemplates } from '../../api/hooks/useMailTemplates';
import { MailTemplateEditor } from '../../components/admin/mail/MailTemplateEditor';
import { SendTestEmailDialog } from '../../components/admin/mail/SendTestEmailDialog';
import { ToneBadge } from '../../components/admin/ToneBadge';

type Toast = { message: string; severity: 'success' | 'error' } | null;

/** Admin mail-template editor: edit, preview, reset, and send a test email (#106). */
export function MailTemplatesPage() {
  const { data, isLoading, isFetching, isError } = useMailTemplates();
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [testOpen, setTestOpen] = useState(false);
  const [toast, setToast] = useState<Toast>(null);

  const templates = data?.templates ?? [];
  const selected = templates.find((t) => t.key === selectedKey) ?? templates[0];

  const notify = (message: string, severity: 'success' | 'error') =>
    setToast({ message, severity });

  return (
    <Stack spacing={3}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' } }}
      >
        <Box>
          <Typography variant="h1" sx={{ fontSize: 28 }}>
            Mail templates
          </Typography>
          <Typography color="text.secondary" sx={{ mt: 0.5 }}>
            Edit, preview and reset transactional emails. Customised templates override the
            defaults.
          </Typography>
        </Box>
        <Button
          variant="outlined"
          startIcon={<MailCheck size={18} />}
          onClick={() => setTestOpen(true)}
        >
          Send test email
        </Button>
      </Stack>

      {isError ? (
        <Alert severity="error">The mail templates could not be loaded.</Alert>
      ) : (
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} sx={{ alignItems: 'stretch' }}>
          <Paper variant="outlined" sx={{ width: { md: 280 }, flexShrink: 0, overflow: 'hidden' }}>
            <Box sx={{ height: 3 }}>{isFetching && <LinearProgress />}</Box>
            <List disablePadding>
              {templates.map((template) => (
                <ListItemButton
                  key={template.key}
                  selected={selected?.key === template.key}
                  onClick={() => setSelectedKey(template.key)}
                >
                  <ListItemText
                    primary={template.key}
                    secondary={template.locale}
                    slotProps={{
                      primary: { sx: { fontSize: 14, fontFamily: 'monospace' } },
                      secondary: { sx: { fontSize: 12 } },
                    }}
                  />
                  {template.source === 'DATABASE' && <ToneBadge tone="blue" label="Custom" />}
                </ListItemButton>
              ))}
              {!isLoading && templates.length === 0 && (
                <Typography color="text.secondary" sx={{ p: 2, fontSize: 14 }}>
                  No templates.
                </Typography>
              )}
            </List>
          </Paper>

          <Paper variant="outlined" sx={{ flex: 1, p: { xs: 2, sm: 3 } }}>
            {selected ? (
              <MailTemplateEditor key={selected.key} template={selected} onNotify={notify} />
            ) : (
              <Typography color="text.secondary" sx={{ fontSize: 14 }}>
                {isLoading ? 'Loading…' : 'Select a template to edit.'}
              </Typography>
            )}
          </Paper>
        </Stack>
      )}

      <SendTestEmailDialog open={testOpen} onClose={() => setTestOpen(false)} />

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
