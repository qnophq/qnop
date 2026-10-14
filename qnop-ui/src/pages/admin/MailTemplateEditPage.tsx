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
import { Send, Eye, RotateCcw, Variable } from 'lucide-react';
import { isAxiosError } from 'axios';
import { Trans, useTranslation } from 'react-i18next';
import { Link as RouterLink, useNavigate, useParams } from 'react-router';
import type { MailTemplateResponse } from '../../api/generated';
import {
  useMailTemplate,
  useResetMailTemplate,
  useUpdateMailTemplate,
} from '../../api/hooks/useMailTemplates';
import { useMailTemplatePreview } from '../../api/hooks/useMailTemplatePreview';
import { SectionCard } from '../../components/admin/layout/SectionCard';
import { adminEmailApi } from '../../api/config';
import { useAuthStore } from '../../stores/authStore';
import { SendTestEmailDialog } from '../../components/admin/mail/SendTestEmailDialog';
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
import { MailLocaleSwitcher } from '../../components/admin/mail/locale/MailLocaleSwitcher';
import { mailLocaleStatus } from '../../components/admin/mail/locale/mailLocaleStatus';
import { mailTemplatePath } from '../../components/admin/mail/locale/mailTemplatePath';
import { useMailTemplateName } from '../../components/admin/mail/mailTemplateName';
import { useFormatters } from '../../hooks/useFormatters';
import { useUiStore } from '../../stores/uiStore';
import { localeDisplayName } from '../../utils/locale';
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

/**
 * Loads one template in one language and renders its editor; remounts when the key, the language
 * or the saved state changes. Without a language in the URL the server answers with the default
 * language.
 */
export function MailTemplateEditPage() {
  const { t } = useTranslation(['admin', 'common']);
  const { key = '', locale } = useParams();
  // React Router already decodes dynamic segments — don't decode again (avoids a double-decode).
  const { data, error, isLoading, isError, isFetching } = useMailTemplate(key, locale);

  if (isLoading) {
    return (
      <Typography color="text.secondary" sx={{ p: 1, fontSize: 14 }}>
        {t('common:loading')}
      </Typography>
    );
  }
  // A stale or mistyped language in the URL: the server refuses it with a 400 (issue #853).
  if (locale && isAxiosError(error) && error.response?.status === 400) {
    return (
      <Alert severity="warning">
        <Trans
          t={t}
          i18nKey="mailTemplates.edit.unsupportedLanguage"
          components={{
            back: <Link component={RouterLink} to={mailTemplatePath(key)} underline="hover" />,
          }}
        />
      </Alert>
    );
  }
  if (isError || !data) {
    return (
      <Alert severity="error">
        <Trans
          t={t}
          i18nKey="mailTemplates.edit.loadError"
          components={{
            back: <Link component={RouterLink} to="/admin/email/templates" underline="hover" />,
          }}
        />
      </Alert>
    );
  }
  return (
    <EditForm
      key={`${data.key}:${data.locale}:${data.updatedAt ?? 'default'}`}
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
  const { t } = useTranslation(['admin', 'common']);
  const updateTemplate = useUpdateMailTemplate();
  const resetTemplate = useResetMailTemplate();
  const { toast, notify, clear } = useToast();
  const { formatRelative } = useFormatters();
  const navigate = useNavigate();
  const uiLocale = useUiStore((s) => s.locale);
  const language = localeDisplayName(template.locale, uiLocale);
  const name = useMailTemplateName()(template);
  const variants = template.variants ?? [];
  const currentVariant = variants.find((variant) => variant.locale === template.locale);
  const isFallback = currentVariant != null && mailLocaleStatus(currentVariant) === 'fallback';
  // The language a switch with unsaved changes is waiting on, until the admin confirms.
  const [pendingLocale, setPendingLocale] = useState<string | null>(null);

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
  const subjectError =
    submitAttempted && subject.trim() === '' ? t('mailTemplates.edit.subjectRequired') : '';
  const plainError =
    submitAttempted && bodyPlain.trim() === '' ? t('mailTemplates.edit.bodyPlainRequired') : '';
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

  // Sends exactly what the live preview shows — current draft + sample values —
  // to a recipient of the admin's choosing, prefilled with their own address
  // (issue #316).
  const ownEmail = useAuthStore((s) => s.email);
  const [testOpen, setTestOpen] = useState(false);
  const sendTemplateTest = async (recipient: string) => {
    const response = await adminEmailApi.sendTemplateTestEmail({
      key: template.key,
      templateTestEmailRequest: {
        recipient,
        locale: template.locale,
        subject,
        bodyPlain,
        bodyHtml: htmlEnabled ? bodyHtml : undefined,
        variables: sampleValues,
      },
    });
    return response.data;
  };

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
      notify(t('mailTemplates.edit.saved'));
    } catch (err) {
      notify(apiErrorMessage(err, t('mailTemplates.edit.saveFailed')), 'error');
    }
  };

  const onReset = async () => {
    setConfirmReset(false);
    try {
      await resetTemplate.mutateAsync({ key: template.key, locale: template.locale });
      notify(t('mailTemplates.edit.reverted'));
    } catch (err) {
      notify(apiErrorMessage(err, t('mailTemplates.edit.resetFailed')), 'error');
    }
  };

  return (
    <Box component="form" onSubmit={onSave} noValidate>
      <Stack spacing={3}>
        {/* An h2 below the email area's page-level h1 (the EmailLayout header stays visible). */}
        <Box sx={{ minWidth: 0 }}>
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <Typography variant="h2" sx={{ fontSize: 22 }} noWrap>
              {name}
            </Typography>
            <ToneBadge
              tone={isCustomised ? 'blue' : 'neutral'}
              label={
                isCustomised ? t('mailTemplates.source.custom') : t('mailTemplates.source.default')
              }
            />
          </Stack>
          <Typography color="text.secondary" sx={{ mt: 0.5 }}>
            <Box component="span" sx={{ fontFamily: 'monospace', fontSize: 13 }}>
              {template.key}
              {isCustomised && (
                <Box component="span" sx={{ fontFamily: 'body1.fontFamily', ml: 1 }}>
                  {template.updatedByName
                    ? t('mailTemplates.edit.editedBy', {
                        when: formatRelative(template.updatedAt),
                        name: template.updatedByName,
                      })
                    : t('mailTemplates.edit.edited', { when: formatRelative(template.updatedAt) })}
                </Box>
              )}
            </Box>
          </Typography>
        </Box>

        {variants.length > 0 && (
          <Stack spacing={1.5}>
            <MailLocaleSwitcher
              templateKey={template.key}
              variants={variants}
              current={template.locale}
              onLeave={(target, event) => {
                if (dirty) {
                  event.preventDefault();
                  setPendingLocale(target);
                }
              }}
            />
            {isFallback && (
              <Alert severity="warning">
                {t('mailTemplates.edit.fallbackNotice', { language })}
              </Alert>
            )}
          </Stack>
        )}

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
              title={t('mailTemplates.edit.variables.title')}
              description={t('mailTemplates.edit.variables.description')}
            >
              {template.placeholders.length === 0 ? (
                <Typography color="text.secondary" sx={{ fontSize: 14, fontStyle: 'italic' }}>
                  {t('mailTemplates.edit.variables.none')}
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
                      // Draggable into the subject and body fields: the payload is the
                      // literal token, so the browser's native text drop (input) and
                      // CodeMirror's drop handling insert it at the pointer.
                      draggable
                      onDragStart={(event) => {
                        event.dataTransfer.setData('text/plain', `{{${name}}}`);
                        event.dataTransfer.effectAllowed = 'copy';
                      }}
                      sx={{
                        fontFamily: 'monospace',
                        fontSize: 12.5,
                        cursor: 'grab',
                        '&:active': { cursor: 'grabbing' },
                      }}
                    />
                  ))}
                </Stack>
              )}
            </SectionCard>

            <SectionCard
              icon={Eye}
              title={t('mailTemplates.edit.content.title')}
              description={t('mailTemplates.edit.content.description')}
            >
              <Stack spacing={2.5}>
                <Box>
                  <TextField
                    label={t('mailTemplates.edit.subject')}
                    value={subject}
                    onChange={(e) => setSubject(e.target.value)}
                    onFocus={() => (lastFocused.current = 'subject')}
                    inputRef={subjectRef}
                    fullWidth
                    required
                    error={Boolean(subjectError)}
                    helperText={
                      subjectError ||
                      t('mailTemplates.edit.subjectHelper', { example: '{{siteName}}' })
                    }
                    slotProps={{ input: { sx: { fontFamily: 'monospace' } } }}
                  />
                  <CompareWithDefault
                    label={t('mailTemplates.edit.compareTargets.subject')}
                    value={template.defaultSubject}
                  />
                </Box>

                <Box>
                  <Typography sx={{ fontSize: 13.5, fontWeight: 500, mb: 0.5 }}>
                    {t('mailTemplates.edit.bodyPlain')}{' '}
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
                    label={t('mailTemplates.edit.compareTargets.bodyPlain')}
                    value={template.defaultBodyPlain}
                  />
                </Box>

                <Box>
                  <FormControlLabel
                    control={
                      <Switch checked={showHtml} onChange={(e) => toggleHtml(e.target.checked)} />
                    }
                    label={t('mailTemplates.edit.addHtml')}
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
                          label={t('mailTemplates.edit.compareTargets.bodyHtml')}
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
                <Stack direction="row" spacing={1.5}>
                  <Button
                    type="button"
                    color="error"
                    startIcon={<RotateCcw size={18} />}
                    disabled={!isCustomised || resetTemplate.isPending}
                    onClick={() => setConfirmReset(true)}
                  >
                    {t('mailTemplates.edit.resetToDefault')}
                  </Button>
                  <Button
                    type="button"
                    startIcon={<Send size={16} />}
                    onClick={() => setTestOpen(true)}
                  >
                    {t('mailTemplates.edit.sendTest')}
                  </Button>
                </Stack>
                <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
                  {updateTemplate.isPending && <LinearProgress sx={{ width: 100 }} />}
                  <Button
                    type="submit"
                    variant="contained"
                    disabled={!dirty || updateTemplate.isPending || refreshing}
                  >
                    {updateTemplate.isPending
                      ? t('mailTemplates.edit.saving')
                      : t('mailTemplates.edit.saveChanges')}
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
        title={t('mailTemplates.edit.reset.title')}
        message={t('mailTemplates.edit.reset.message', { name, language })}
        confirmLabel={t('common:actions.reset')}
        destructive
        onConfirm={onReset}
        onClose={() => setConfirmReset(false)}
      />

      <ConfirmDialog
        open={pendingLocale != null}
        title={t('mailTemplates.edit.switchLanguage.title')}
        message={t('mailTemplates.edit.switchLanguage.message', {
          language,
          target: pendingLocale ? localeDisplayName(pendingLocale, uiLocale) : '',
        })}
        confirmLabel={t('mailTemplates.edit.switchLanguage.confirm')}
        destructive
        onConfirm={() => {
          if (pendingLocale) navigate(mailTemplatePath(template.key, pendingLocale));
          setPendingLocale(null);
        }}
        onClose={() => setPendingLocale(null)}
      />

      <SendTestEmailDialog
        open={testOpen}
        onClose={() => setTestOpen(false)}
        initialRecipient={ownEmail ?? ''}
        send={sendTemplateTest}
        helperText={t('mailTemplates.edit.testHelper')}
      />
      <AdminToast toast={toast} onClose={clear} />
    </Box>
  );
}
