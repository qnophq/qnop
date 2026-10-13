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

import type { MouseEvent } from 'react';
import Box from '@mui/material/Box';
import Tooltip from '@mui/material/Tooltip';
import { alpha } from '@mui/material/styles';
import { Star } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Link as RouterLink } from 'react-router';
import type { MailTemplateVariant } from '../../../../api/generated';
import { useUiStore } from '../../../../stores/uiStore';
import { localeDisplayName, localeShortCode } from '../../../../utils/locale';
import { LocaleStatusMark } from './LocaleStatusMark';
import { MAIL_LOCALE_STATUS_LABELS, mailLocaleStatus } from './mailLocaleStatus';
import { mailTemplatePath } from './mailTemplatePath';

/**
 * The editor's language switcher (issue #853): one segment per offered language, each carrying the
 * same status mark as the list matrix, so the admin sees where they are going before they go.
 * Segments are links (the language lives in the URL); `onLeave` may cancel a switch, e.g. to ask
 * about unsaved changes first.
 */
export function MailLocaleSwitcher({
  templateKey,
  variants,
  current,
  onLeave,
}: {
  templateKey: string;
  variants: MailTemplateVariant[];
  current: string;
  /** Called before leaving `current`; call `event.preventDefault()` to stay. */
  onLeave?: (locale: string, event: MouseEvent<HTMLAnchorElement>) => void;
}) {
  const { t } = useTranslation('admin');
  const uiLocale = useUiStore((s) => s.locale);
  return (
    <Box
      component="nav"
      aria-label={t('mailTemplates.edit.languages')}
      sx={{
        display: 'inline-flex',
        // Sized to its segments, also as an item of a column stack.
        alignSelf: 'flex-start',
        flexWrap: 'wrap',
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1.5,
        p: 0.375,
        gap: 0.375,
        bgcolor: (theme) => theme.qnop.surface2,
      }}
    >
      {variants.map((variant) => {
        const selected = variant.locale === current;
        const status = mailLocaleStatus(variant);
        const language = localeDisplayName(variant.locale, uiLocale);
        const statusLabel = t(MAIL_LOCALE_STATUS_LABELS[status]);
        return (
          <Tooltip
            key={variant.locale}
            title={
              variant.instanceDefault
                ? t('mailTemplates.edit.languageTooltipDefault', { language, status: statusLabel })
                : t('mailTemplates.edit.languageTooltip', { language, status: statusLabel })
            }
          >
            <Box
              component={RouterLink}
              to={mailTemplatePath(templateKey, variant.locale)}
              aria-current={selected ? 'page' : undefined}
              aria-label={t(
                variant.instanceDefault
                  ? 'mailTemplates.edit.languageAriaDefault'
                  : 'mailTemplates.edit.languageAria',
                { language, status: statusLabel },
              )}
              onClick={(event: MouseEvent<HTMLAnchorElement>) => {
                if (!selected) onLeave?.(variant.locale, event);
              }}
              sx={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 0.75,
                px: 1.25,
                py: 0.625,
                borderRadius: 1,
                fontFamily: 'monospace',
                fontSize: 13,
                fontWeight: 700,
                letterSpacing: '0.06em',
                textDecoration: 'none',
                color: selected ? 'text.primary' : 'text.secondary',
                bgcolor: selected ? 'background.paper' : 'transparent',
                boxShadow: selected
                  ? (theme) =>
                      `0 0 0 1px ${theme.palette.divider}, 0 1px 2px ${alpha(theme.qnop.brand.navy, 0.08)}`
                  : 'none',
                transition: (theme) =>
                  theme.transitions.create(['background-color', 'color', 'box-shadow']),
                '&:hover': {
                  color: 'text.primary',
                  bgcolor: selected ? undefined : 'action.hover',
                },
                '&:focus-visible': {
                  outline: (theme) => `2px solid ${theme.qnop.brand.blue}`,
                  outlineOffset: 1,
                },
              }}
            >
              <LocaleStatusMark status={status} />
              {localeShortCode(variant.locale)}
              {variant.instanceDefault && (
                <Box
                  component="span"
                  aria-hidden
                  sx={{ display: 'inline-flex', color: (theme) => theme.qnop.brand.blue }}
                >
                  <Star size={11} fill="currentColor" />
                </Box>
              )}
            </Box>
          </Tooltip>
        );
      })}
    </Box>
  );
}
