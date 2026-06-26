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
import ButtonBase from '@mui/material/ButtonBase';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { ChevronRight, MailCheck } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import type { MailTemplateResponse } from '../../api/generated';
import { useMailTemplates } from '../../api/hooks/useMailTemplates';
import { PageHeader } from '../../components/admin/layout/PageHeader';
import { ToneBadge } from '../../components/admin/ToneBadge';
import { SendTestEmailDialog } from '../../components/admin/mail/SendTestEmailDialog';
import { formatRelative } from '../../utils/formatDate';

/** The attribution line for a template row: who customised it and when, or the factory marker. */
function attribution(template: MailTemplateResponse): { text: string; customised: boolean } {
  if (template.source !== 'DATABASE') {
    return { text: 'Factory default', customised: false };
  }
  const when = formatRelative(template.updatedAt);
  return {
    text: template.updatedByName ? `Edited ${when} by ${template.updatedByName}` : `Edited ${when}`,
    customised: true,
  };
}

/** A navigable template row: friendly name, key, subject preview, attribution and source badge. */
function TemplateRow({ template, onOpen }: { template: MailTemplateResponse; onOpen: () => void }) {
  const attr = attribution(template);
  return (
    <ButtonBase
      onClick={onOpen}
      focusRipple
      sx={{
        display: 'block',
        textAlign: 'left',
        width: '100%',
        borderRadius: 2,
        border: '1px solid',
        borderColor: 'divider',
        bgcolor: 'background.paper',
        p: { xs: 2, sm: 2.5 },
        transition: (t) => t.transitions.create(['border-color', 'box-shadow']),
        '&:hover': {
          borderColor: (t) => t.qnop.brand.blue,
          boxShadow: (t) => t.qnop.badge.blue.border,
        },
        '&:hover .row-chevron': { transform: 'translateX(2px)', opacity: 1 },
        '&.Mui-focusVisible': { boxShadow: (t) => t.qnop.focusRing },
      }}
    >
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: 'minmax(0, 1fr) auto',
          gap: 2,
          alignItems: 'center',
        }}
      >
        <Box sx={{ minWidth: 0 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.25 }}>
            <Typography sx={{ fontSize: 16, fontWeight: 600 }} noWrap>
              {template.friendlyName}
            </Typography>
            <ToneBadge
              tone={attr.customised ? 'blue' : 'neutral'}
              label={attr.customised ? 'Custom' : 'Default'}
            />
          </Stack>
          <Typography
            sx={{ fontSize: 12.5, fontFamily: 'monospace', color: 'text.secondary' }}
            noWrap
          >
            {template.key}
          </Typography>
          <Typography color="text.secondary" sx={{ fontSize: 14, mt: 0.5 }} noWrap>
            {template.subject}
          </Typography>
          <Typography
            sx={{
              fontSize: 12.5,
              mt: 0.5,
              color: attr.customised ? 'text.primary' : 'text.disabled',
              fontStyle: attr.customised ? 'normal' : 'italic',
              fontWeight: attr.customised ? 500 : 400,
            }}
          >
            {attr.text}
          </Typography>
        </Box>
        <Box
          className="row-chevron"
          sx={{
            display: 'grid',
            placeItems: 'center',
            color: 'text.secondary',
            opacity: 0.6,
            transition: (t) => t.transitions.create(['transform', 'opacity']),
          }}
          aria-hidden
        >
          <ChevronRight size={20} />
        </Box>
      </Box>
    </ButtonBase>
  );
}

/**
 * Mail-templates list (issue #144): one navigable row per template in the closed registry — no
 * "add" CTA. Customised templates carry an "Edited … by …" attribution; built-ins read "Factory
 * default". Opening a row routes to its editor.
 */
export function MailTemplatesListPage() {
  const { data, isLoading, isError } = useMailTemplates();
  const navigate = useNavigate();
  const [testOpen, setTestOpen] = useState(false);

  const templates = data?.templates ?? [];

  return (
    <Stack spacing={3} sx={{ maxWidth: 860 }}>
      <PageHeader
        title="Mail templates"
        description="Edit the transactional emails qnop sends. Customised templates override the built-in defaults."
        action={
          <Button
            variant="outlined"
            startIcon={<MailCheck size={18} />}
            onClick={() => setTestOpen(true)}
          >
            Send test email
          </Button>
        }
      />

      {isError ? (
        <Alert severity="error">The mail templates could not be loaded.</Alert>
      ) : isLoading ? (
        <Typography color="text.secondary" sx={{ p: 1, fontSize: 14 }}>
          Loading…
        </Typography>
      ) : templates.length === 0 ? (
        <Typography color="text.secondary" sx={{ p: 1, fontSize: 14 }}>
          No mail templates.
        </Typography>
      ) : (
        <Stack spacing={1.5}>
          {templates.map((template) => (
            <TemplateRow
              key={template.key}
              template={template}
              onOpen={() => navigate(`/admin/mail-templates/${encodeURIComponent(template.key)}`)}
            />
          ))}
        </Stack>
      )}

      <SendTestEmailDialog open={testOpen} onClose={() => setTestOpen(false)} />
    </Stack>
  );
}
