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

import { useState, type FormEvent } from 'react';
import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { Eye, RotateCcw } from 'lucide-react';
import type { MailTemplateResponse } from '../../../api/generated';
import { ConfirmDialog } from '../ConfirmDialog';
import { ToneBadge } from '../ToneBadge';
import {
  usePreviewMailTemplate,
  useResetMailTemplate,
  useUpdateMailTemplate,
} from '../../../api/hooks/useMailTemplates';
import { apiErrorMessage } from '../../../utils/apiError';
import { MailTemplatePreviewDialog } from './MailTemplatePreviewDialog';

interface MailTemplateEditorProps {
  template: MailTemplateResponse;
  onNotify: (message: string, severity: 'success' | 'error') => void;
}

/**
 * Editor for a single mail template. State is seeded from props via useState
 * initializers; the parent passes a changing `key` so switching templates
 * remounts the editor fresh. A DATABASE override can be reset to the built-in
 * default; DEFAULT templates can be overridden by saving.
 */
export function MailTemplateEditor({ template, onNotify }: MailTemplateEditorProps) {
  const updateTemplate = useUpdateMailTemplate();
  const resetTemplate = useResetMailTemplate();
  const preview = usePreviewMailTemplate();

  const [subject, setSubject] = useState(template.subject);
  const [bodyPlain, setBodyPlain] = useState(template.bodyPlain);
  const [bodyHtml, setBodyHtml] = useState(template.bodyHtml ?? '');
  const [confirmReset, setConfirmReset] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);

  const isOverride = template.source === 'DATABASE';
  const dirty =
    subject !== template.subject ||
    bodyPlain !== template.bodyPlain ||
    bodyHtml !== (template.bodyHtml ?? '');

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    try {
      await updateTemplate.mutateAsync({
        key: template.key,
        request: {
          locale: template.locale,
          subject,
          bodyPlain,
          bodyHtml: bodyHtml.trim().length > 0 ? bodyHtml : undefined,
        },
      });
      onNotify('Template saved.', 'success');
    } catch (err) {
      onNotify(apiErrorMessage(err, 'The template could not be saved.'), 'error');
    }
  };

  const onReset = async () => {
    setConfirmReset(false);
    try {
      await resetTemplate.mutateAsync(template.key);
      onNotify('Template reset to the built-in default.', 'success');
    } catch (err) {
      onNotify(apiErrorMessage(err, 'The template could not be reset.'), 'error');
    }
  };

  const openPreview = async () => {
    setPreviewOpen(true);
    try {
      await preview.mutateAsync({ key: template.key, locale: template.locale });
    } catch (err) {
      onNotify(apiErrorMessage(err, 'The preview could not be rendered.'), 'error');
      setPreviewOpen(false);
    }
  };

  return (
    <Stack component="form" onSubmit={onSubmit} spacing={2.5}>
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <Typography sx={{ fontWeight: 600, fontFamily: 'monospace' }}>{template.key}</Typography>
        <ToneBadge
          tone={isOverride ? 'blue' : 'neutral'}
          label={isOverride ? 'Custom' : 'Default'}
        />
        <Typography color="text.secondary" sx={{ fontSize: 13 }}>
          {template.locale}
        </Typography>
      </Stack>

      <TextField
        label="Subject"
        value={subject}
        onChange={(e) => setSubject(e.target.value)}
        fullWidth
        required
      />
      <TextField
        label="Plain-text body"
        value={bodyPlain}
        onChange={(e) => setBodyPlain(e.target.value)}
        fullWidth
        required
        multiline
        minRows={6}
        slotProps={{ htmlInput: { style: { fontFamily: 'monospace', fontSize: 13 } } }}
        helperText="Mustache syntax, e.g. {{name}}."
      />
      <TextField
        label="HTML body (optional)"
        value={bodyHtml}
        onChange={(e) => setBodyHtml(e.target.value)}
        fullWidth
        multiline
        minRows={6}
        slotProps={{ htmlInput: { style: { fontFamily: 'monospace', fontSize: 13 } } }}
        helperText="Leave blank for a plain-text-only email."
      />

      <Stack direction="row" spacing={1.5} sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
        <Button type="submit" variant="contained" disabled={!dirty || updateTemplate.isPending}>
          {updateTemplate.isPending ? 'Saving…' : 'Save'}
        </Button>
        <Button startIcon={<Eye size={16} />} onClick={openPreview} disabled={preview.isPending}>
          Preview
        </Button>
        {isOverride && (
          <Button
            startIcon={<RotateCcw size={16} />}
            color="inherit"
            onClick={() => setConfirmReset(true)}
            disabled={resetTemplate.isPending}
          >
            Reset to default
          </Button>
        )}
      </Stack>

      <ConfirmDialog
        open={confirmReset}
        title="Reset template"
        message={`Discard the custom “${template.key}” template and revert to the built-in default?`}
        confirmLabel="Reset"
        destructive
        onConfirm={onReset}
        onClose={() => setConfirmReset(false)}
      />
      <MailTemplatePreviewDialog
        open={previewOpen}
        loading={preview.isPending}
        preview={preview.data ?? null}
        onClose={() => setPreviewOpen(false)}
      />
    </Stack>
  );
}
