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

import type { ReactNode } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { Eye, RefreshCw } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import type { MailTemplatePreviewResponse } from '../../../../api/generated';
import type { PreviewStatus } from '../../../../api/hooks/useMailTemplatePreview';
import { SectionCard } from '../../layout/SectionCard';
import { PreviewStatusBadge } from './PreviewStatusBadge';
import { SampleVariablesPopover } from './SampleVariablesPopover';

interface MailTemplatePreviewPaneProps {
  status: PreviewStatus;
  preview: MailTemplatePreviewResponse | null;
  error: string | null;
  onRefresh: () => void;
  /** Whether the operator maintains an HTML alternative; otherwise the HTML pane shows a fallback. */
  htmlEnabled: boolean;
  placeholders: string[];
  sampleValues: Record<string, string>;
  onSampleChange: (name: string, value: string) => void;
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box>
      <Typography
        sx={{
          fontSize: 11,
          fontWeight: 700,
          letterSpacing: 0.5,
          textTransform: 'uppercase',
          color: 'text.secondary',
          mb: 0.75,
        }}
      >
        {label}
      </Typography>
      {children}
    </Box>
  );
}

/**
 * The live preview beside the editor (issue #145): subject (single line), plain-text body and — when
 * the operator keeps an HTML alternative — the rendered HTML in a fully sandboxed iframe so untrusted
 * markup can neither run scripts nor navigate. A status badge, sample-variable overrides and a manual
 * refresh sit in the header; the body dims while a fresh render is in flight.
 */
export function MailTemplatePreviewPane({
  status,
  preview,
  error,
  onRefresh,
  htmlEnabled,
  placeholders,
  sampleValues,
  onSampleChange,
}: MailTemplatePreviewPaneProps) {
  const { t } = useTranslation('admin');
  const pending = status === 'stale' || status === 'syncing';

  return (
    <SectionCard
      icon={Eye}
      title={t('mailTemplates.preview.title')}
      description={t('mailTemplates.preview.description')}
      action={
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
          <PreviewStatusBadge status={status} />
          <SampleVariablesPopover
            placeholders={placeholders}
            values={sampleValues}
            onChange={onSampleChange}
          />
          <Tooltip title={t('mailTemplates.preview.refreshTooltip')}>
            <IconButton
              size="small"
              onClick={onRefresh}
              aria-label={t('mailTemplates.preview.refresh')}
            >
              <RefreshCw size={16} />
            </IconButton>
          </Tooltip>
        </Stack>
      }
    >
      {status === 'error' && error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {!preview ? (
        <Typography color="text.secondary" sx={{ fontSize: 14, fontStyle: 'italic', py: 2 }}>
          {t('mailTemplates.preview.empty')}
        </Typography>
      ) : (
        <Stack
          spacing={2.25}
          sx={{
            opacity: pending ? 0.55 : 1,
            transition: (theme) => theme.transitions.create('opacity'),
          }}
        >
          <Field label={t('mailTemplates.preview.subject')}>
            <Typography sx={{ fontFamily: 'monospace', fontSize: 14, fontWeight: 600 }}>
              {preview.subject}
            </Typography>
          </Field>

          <Field label={t('mailTemplates.preview.plainText')}>
            <Box
              component="pre"
              sx={{
                m: 0,
                p: 1.5,
                bgcolor: 'action.hover',
                borderRadius: 1,
                fontSize: 13,
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
                fontFamily: 'monospace',
              }}
            >
              {preview.bodyPlain}
            </Box>
          </Field>

          <Field label={t('mailTemplates.preview.html')}>
            {htmlEnabled && preview.bodyHtml ? (
              <Box
                component="iframe"
                title={t('mailTemplates.preview.htmlFrame')}
                srcDoc={preview.bodyHtml}
                sandbox=""
                sx={{
                  width: '100%',
                  height: 380,
                  border: 1,
                  borderColor: 'divider',
                  borderRadius: 1,
                  bgcolor: 'background.paper',
                }}
              />
            ) : (
              <Typography
                color="text.secondary"
                sx={{ fontSize: 13.5, fontStyle: 'italic', py: 1 }}
              >
                {t('mailTemplates.preview.noHtml')}
              </Typography>
            )}
          </Field>
        </Stack>
      )}
    </SectionCard>
  );
}
