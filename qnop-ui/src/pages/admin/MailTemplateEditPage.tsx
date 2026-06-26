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

import { useRef, useState, type FormEvent } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Collapse from '@mui/material/Collapse';
import Divider from '@mui/material/Divider';
import FormControlLabel from '@mui/material/FormControlLabel';
import LinearProgress from '@mui/material/LinearProgress';
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { ArrowLeft, Eye, RotateCcw, Variable } from 'lucide-react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import type { MailTemplateResponse } from '../../api/generated';
import {
  useMailTemplate,
  useResetMailTemplate,
  useUpdateMailTemplate,
} from '../../api/hooks/useMailTemplates';
import { useMailTemplatePreview } from '../../api/hooks/useMailTemplatePreview';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { SectionCard } from '../../components/admin/layout/SectionCard';
import { AdminToast } from '../../components/admin/layout/AdminToast';
import { useToast } from '../../components/admin/layout/useToast';
import { ToneBadge } from '../../components/admin/ToneBadge';
import { ConfirmDialog } from '../../components/admin/ConfirmDialog';
import { CompareWithDefault } from '../../components/admin/mail/CompareWithDefault';
import { MailTemplatePreviewPane } from '../../components/admin/mail/preview/MailTemplatePreviewPane';
import {
  MustacheCodeEditor,
  type MustacheEditorHandle,
} from '../../components/admin/mail/mustache/MustacheCodeEditor';
import { formatRelative } from '../../utils/formatDate';
import { apiErrorMessage } from '../../utils/apiError';

/** Inserts text at the caret of a plain input, then restores focus and caret after re-render. */
function insertIntoInput(
  el: HTMLInputElement | null,
  text: string,
  setValue: (next: string) => void,
) {
  if (!el) return;
  const start = el.selectionStart ?? el.value.length;
  const end = el.selectionEnd ?? el.value.length;
  setValue(el.value.slice(0, start) + text + el.value.slice(end));
  requestAnimationFrame(() => {
    el.focus();
    const caret = start + text.length;
    el.setSelectionRange(caret, caret);
  });
}

/** Loads one template and renders its editor; remounts when the key or its saved state changes. */
export function MailTemplateEditPage() {
  const { key = '' } = useParams();
  // React Router already decodes dynamic segments — don't decode again (avoids a double-decode).
  const { data, isLoading, isError, isFetching } = useMailTemplate(key);

  if (isLoading) {
    return (
      <Typography color="text.secondary" sx={{ p: 1, fontSize: 14 }}>
        Loading…
      </Typography>
    );
  }
  if (isError || !data) {
    return (
      <Alert severity="error">
        This template could not be loaded.{' '}
        <Link component={RouterLink} to="/admin/mail-templates" underline="hover">
          Back to templates
        </Link>
      </Alert>
    );
  }
  return (
    <EditForm
      key={`${data.key}:${data.updatedAt ?? 'default'}`}
      template={data}
      refreshing={isFetching}
    />
  );
}

type FocusTarget = 'subject' | 'bodyPlain' | 'bodyHtml';

function EditForm({
  template,
  refreshing,
}: {
  template: MailTemplateResponse;
  refreshing: boolean;
}) {
  const updateTemplate = useUpdateMailTemplate();
  const resetTemplate = useResetMailTemplate();
  const { toast, notify, clear } = useToast();

  const [subject, setSubject] = useState(template.subject);
  const [bodyPlain, setBodyPlain] = useState(template.bodyPlain);
  const [bodyHtml, setBodyHtml] = useState(template.bodyHtml ?? '');
  const [showHtml, setShowHtml] = useState(template.bodyHtml != null);
  const [submitAttempted, setSubmitAttempted] = useState(false);
  const [confirmReset, setConfirmReset] = useState(false);
  const [sampleOverrides, setSampleOverrides] = useState<Record<string, string>>({});

  const lastFocused = useRef<FocusTarget>('subject');
  const subjectRef = useRef<HTMLInputElement>(null);
  const plainRef = useRef<MustacheEditorHandle>(null);
  const htmlRef = useRef<MustacheEditorHandle>(null);

  const isCustomised = template.source === 'DATABASE';
  const subjectError = submitAttempted && subject.trim() === '' ? 'A subject is required.' : '';
  const plainError =
    submitAttempted && bodyPlain.trim() === '' ? 'A plain-text body is required.' : '';
  const dirty =
    subject !== template.subject ||
    bodyPlain !== template.bodyPlain ||
    (showHtml ? bodyHtml : null) !== (template.bodyHtml ?? null);

  // Live preview of the unsaved draft (issue #145), debounced and rendered beside the editor.
  const htmlEnabled = showHtml && bodyHtml.trim() !== '';
  const preview = useMailTemplatePreview({
    key: template.key,
    locale: template.locale,
    subject,
    bodyPlain,
    bodyHtml: htmlEnabled ? bodyHtml : undefined,
    variables: sampleOverrides,
  });
  const sampleValues = { ...(preview.data?.sampleVars ?? {}), ...sampleOverrides };

  const insertPlaceholder = (name: string) => {
    if (lastFocused.current === 'bodyHtml' && showHtml) {
      htmlRef.current?.insertAtCursor(`{{{${name}}}}`);
    } else if (lastFocused.current === 'bodyPlain') {
      plainRef.current?.insertAtCursor(`{{${name}}}`);
    } else {
      insertIntoInput(subjectRef.current, `{{${name}}}`, setSubject);
    }
  };

  const toggleHtml = (on: boolean) => {
    setShowHtml(on);
    setBodyHtml(on ? bodyHtml || template.defaultBodyHtml || '' : '');
  };

  const onSave = async (event: FormEvent) => {
    event.preventDefault();
    if (subject.trim() === '' || bodyPlain.trim() === '') {
      setSubmitAttempted(true);
      return;
    }
    try {
      await updateTemplate.mutateAsync({
        key: template.key,
        request: {
          locale: template.locale,
          subject,
          bodyPlain,
          bodyHtml: showHtml && bodyHtml.trim() !== '' ? bodyHtml : undefined,
        },
      });
      notify('Template saved.');
    } catch (err) {
      notify(apiErrorMessage(err, 'The template could not be saved.'), 'error');
    }
  };

  const onReset = async () => {
    setConfirmReset(false);
    try {
      await resetTemplate.mutateAsync(template.key);
      notify('Reverted to the default.');
    } catch (err) {
      notify(apiErrorMessage(err, 'The template could not be reset.'), 'error');
    }
  };

  return (
    <Box component="form" onSubmit={onSave} noValidate>
      <Stack spacing={3}>
        <Box>
          <Link
            component={RouterLink}
            to="/admin/mail-templates"
            underline="hover"
            sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, fontSize: 14, mb: 1.5 }}
          >
            <ArrowLeft size={16} /> All templates
          </Link>
          <PageHeader
            title={template.friendlyName}
            titleAdornment={
              <ToneBadge
                tone={isCustomised ? 'blue' : 'neutral'}
                label={isCustomised ? 'Custom' : 'Default'}
              />
            }
            description={
              <Box component="span" sx={{ fontFamily: 'monospace', fontSize: 13 }}>
                {template.key} · {template.locale}
                {isCustomised && (
                  <Box component="span" sx={{ fontFamily: 'body1.fontFamily', ml: 1 }}>
                    — edited {formatRelative(template.updatedAt)}
                    {template.updatedByName ? ` by ${template.updatedByName}` : ''}
                  </Box>
                )}
              </Box>
            }
          />
        </Box>

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 1fr) minmax(0, 1fr)' },
            gap: 3,
            alignItems: 'start',
          }}
        >
          <Stack spacing={3}>
            <SectionCard
              icon={Variable}
              title="Available variables"
              description="Click a chip to insert it at the caret of the last field you edited."
            >
              {template.placeholders.length === 0 ? (
                <Typography color="text.secondary" sx={{ fontSize: 14, fontStyle: 'italic' }}>
                  This template takes no variables.
                </Typography>
              ) : (
                <Stack direction="row" spacing={0.75} useFlexGap sx={{ flexWrap: 'wrap' }}>
                  {template.placeholders.map((name) => (
                    <Chip
                      key={name}
                      label={`{{${name}}}`}
                      size="small"
                      variant="outlined"
                      onClick={() => insertPlaceholder(name)}
                      sx={{ fontFamily: 'monospace', fontSize: 12.5 }}
                    />
                  ))}
                </Stack>
              )}
            </SectionCard>

            <SectionCard icon={Eye} title="Content" description="Subject and body for this email.">
              <Stack spacing={2.5}>
                <Box>
                  <TextField
                    label="Subject"
                    value={subject}
                    onChange={(e) => setSubject(e.target.value)}
                    onFocus={() => (lastFocused.current = 'subject')}
                    inputRef={subjectRef}
                    fullWidth
                    required
                    error={Boolean(subjectError)}
                    helperText={subjectError || 'Mustache syntax, e.g. {{siteName}}.'}
                    slotProps={{ input: { sx: { fontFamily: 'monospace' } } }}
                  />
                  <CompareWithDefault label="default subject" value={template.defaultSubject} />
                </Box>

                <Box>
                  <Typography sx={{ fontSize: 13.5, fontWeight: 500, mb: 0.5 }}>
                    Plain-text body{' '}
                    <Box component="span" sx={{ color: 'error.main' }}>
                      *
                    </Box>
                  </Typography>
                  <MustacheCodeEditor
                    ref={plainRef}
                    value={bodyPlain}
                    onChange={setBodyPlain}
                    onFocus={() => (lastFocused.current = 'bodyPlain')}
                    placeholders={template.placeholders}
                    minHeight="220px"
                  />
                  {plainError && (
                    <Typography color="error" sx={{ fontSize: 12.5, mt: 0.5, ml: 1.5 }}>
                      {plainError}
                    </Typography>
                  )}
                  <CompareWithDefault
                    label="default plain-text body"
                    value={template.defaultBodyPlain}
                  />
                </Box>

                <Box>
                  <FormControlLabel
                    control={
                      <Switch checked={showHtml} onChange={(e) => toggleHtml(e.target.checked)} />
                    }
                    label="Add HTML alternative"
                    sx={{ m: 0, gap: 1 }}
                  />
                  <Collapse in={showHtml} unmountOnExit>
                    <Box sx={{ mt: 1.5 }}>
                      <MustacheCodeEditor
                        ref={htmlRef}
                        value={bodyHtml}
                        onChange={setBodyHtml}
                        onFocus={() => (lastFocused.current = 'bodyHtml')}
                        placeholders={template.placeholders}
                        language="html"
                        minHeight="300px"
                      />
                      {template.defaultBodyHtml && (
                        <CompareWithDefault
                          label="default HTML body"
                          value={template.defaultBodyHtml}
                        />
                      )}
                    </Box>
                  </Collapse>
                </Box>
              </Stack>
            </SectionCard>

            <Box>
              <Divider sx={{ mb: 2 }} />
              <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={1.5}
                sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between' }}
              >
                <Button
                  type="button"
                  color="error"
                  startIcon={<RotateCcw size={18} />}
                  disabled={!isCustomised || resetTemplate.isPending}
                  onClick={() => setConfirmReset(true)}
                >
                  Reset to default
                </Button>
                <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
                  {updateTemplate.isPending && <LinearProgress sx={{ width: 100 }} />}
                  <Button
                    type="submit"
                    variant="contained"
                    disabled={!dirty || updateTemplate.isPending || refreshing}
                  >
                    {updateTemplate.isPending ? 'Saving…' : 'Save changes'}
                  </Button>
                </Stack>
              </Stack>
            </Box>
          </Stack>

          <Box sx={{ position: { lg: 'sticky' }, top: { lg: 24 } }}>
            <MailTemplatePreviewPane
              status={preview.status}
              preview={preview.data}
              error={preview.error}
              onRefresh={preview.refresh}
              htmlEnabled={htmlEnabled}
              placeholders={template.placeholders}
              sampleValues={sampleValues}
              onSampleChange={(name, value) =>
                setSampleOverrides((prev) => ({ ...prev, [name]: value }))
              }
            />
          </Box>
        </Box>
      </Stack>

      <ConfirmDialog
        open={confirmReset}
        title="Reset template"
        message={`Discard the customised "${template.friendlyName}" and revert to the built-in default? This cannot be undone.`}
        confirmLabel="Reset"
        destructive
        onConfirm={onReset}
        onClose={() => setConfirmReset(false)}
      />

      <AdminToast toast={toast} onClose={clear} />
    </Box>
  );
}
