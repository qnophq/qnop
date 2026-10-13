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

import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import LinearProgress from '@mui/material/LinearProgress';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import useMediaQuery from '@mui/material/useMediaQuery';
import { alpha, useTheme, type Theme } from '@mui/material/styles';
import { Star } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Link as RouterLink } from 'react-router';
import type { MailTemplateResponse, MailTemplateVariant } from '../../api/generated';
import { useMailTemplates } from '../../api/hooks/useMailTemplates';
import { LocaleStatusMark } from '../../components/admin/mail/locale/LocaleStatusMark';
import {
  MAIL_LOCALE_STATUS_LABELS,
  mailLocaleStatus,
  type MailLocaleStatus,
} from '../../components/admin/mail/locale/mailLocaleStatus';
import { mailTemplatePath } from '../../components/admin/mail/locale/mailTemplatePath';
import { useFormatters } from '../../hooks/useFormatters';
import { useUiStore } from '../../stores/uiStore';
import { localeDisplayName, localeShortCode } from '../../utils/locale';

/** The default-language column reads as the anchor of the matrix: a faint brand tint. */
const defaultColumnTint = (theme: Theme) => alpha(theme.qnop.brand.blue, 0.05);

/** One status for one language: what the tooltip and the accessible name say. */
function useVariantDescription() {
  const { t } = useTranslation('admin');
  const { formatRelative } = useFormatters();
  const uiLocale = useUiStore((s) => s.locale);
  return (template: MailTemplateResponse, variant: MailTemplateVariant) => {
    const language = localeDisplayName(variant.locale, uiLocale);
    const status = t(MAIL_LOCALE_STATUS_LABELS[mailLocaleStatus(variant)]);
    const edited =
      variant.source === 'DATABASE'
        ? variant.updatedByName
          ? t('mailTemplates.matrix.editedBy', {
              when: formatRelative(variant.updatedAt),
              name: variant.updatedByName,
            })
          : t('mailTemplates.matrix.edited', { when: formatRelative(variant.updatedAt) })
        : null;
    return {
      label: t('mailTemplates.matrix.cellAria', {
        name: template.friendlyName,
        language,
        status,
      }),
      language,
      status,
      edited,
    };
  };
}

/** A matrix cell: the whole cell links to the editor in that language. */
function VariantCell({
  template,
  variant,
}: {
  template: MailTemplateResponse;
  variant: MailTemplateVariant;
}) {
  const { t } = useTranslation('admin');
  const describe = useVariantDescription();
  const { label, language, status, edited } = describe(template, variant);
  const kind = mailLocaleStatus(variant);
  return (
    <TableCell
      align="center"
      sx={{ p: 0, bgcolor: variant.instanceDefault ? defaultColumnTint : undefined }}
    >
      <Tooltip
        title={
          <Box sx={{ py: 0.25 }}>
            <Typography sx={{ fontSize: 12, fontWeight: 600 }}>
              {t('mailTemplates.edit.languageTooltip', { language, status })}
            </Typography>
            <Typography sx={{ fontSize: 12, fontFamily: 'monospace' }}>
              {variant.subject}
            </Typography>
            {edited && <Typography sx={{ fontSize: 12, opacity: 0.8 }}>{edited}</Typography>}
          </Box>
        }
      >
        <Box
          component={RouterLink}
          to={mailTemplatePath(template.key, variant.locale)}
          aria-label={label}
          sx={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 0.5,
            minHeight: 56,
            px: 1,
            color: 'inherit',
            textDecoration: 'none',
            transition: (theme) => theme.transitions.create(['background-color']),
            '&:hover': { bgcolor: 'action.hover' },
            '&:hover [data-status]': { transform: 'scale(1.25)' },
            '& [data-status]': {
              transition: (theme) => theme.transitions.create(['transform']),
            },
            '&:focus-visible': {
              outline: (theme) => `2px solid ${theme.qnop.brand.blue}`,
              outlineOffset: '-2px',
            },
          }}
        >
          <LocaleStatusMark status={kind} size={12} />
          <FallbackHint status={kind} />
        </Box>
      </Tooltip>
    </TableCell>
  );
}

/** Under a fallback mark: "→ EN", so the gap reads without hovering. */
function FallbackHint({ status }: { status: MailLocaleStatus }) {
  const { t } = useTranslation('admin');
  const theme = useTheme();
  if (status !== 'fallback') return null;
  const amber = theme.qnop.badge.amber;
  return (
    <Typography
      component="span"
      aria-hidden
      sx={{
        fontSize: 10.5,
        fontWeight: 600,
        letterSpacing: '0.04em',
        lineHeight: 1,
        color: theme.palette.mode === 'dark' ? amber.fgDark : amber.fg,
      }}
    >
      {t('mailTemplates.matrix.fallbackShort', { code: localeShortCode('en') })}
    </Typography>
  );
}

/** A column header: the language code, its name below, a star on the default language. */
function LocaleHeader({ locale, isDefault }: { locale: string; isDefault: boolean }) {
  const { t } = useTranslation('admin');
  const uiLocale = useUiStore((s) => s.locale);
  const name = localeDisplayName(locale, uiLocale);
  return (
    <TableCell
      align="center"
      sx={{
        width: 84,
        verticalAlign: 'bottom',
        bgcolor: isDefault ? defaultColumnTint : undefined,
      }}
    >
      <Stack spacing={0.25} sx={{ alignItems: 'center' }}>
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
          {isDefault && (
            <Tooltip title={t('mailTemplates.matrix.defaultLanguage')}>
              <Box
                component="span"
                sx={{ display: 'inline-flex', color: (theme) => theme.qnop.brand.blue }}
              >
                <Star size={12} fill="currentColor" aria-hidden />
              </Box>
            </Tooltip>
          )}
          <Typography
            component="span"
            sx={{ fontSize: 13, fontWeight: 700, fontFamily: 'monospace', letterSpacing: '0.06em' }}
          >
            {localeShortCode(locale)}
          </Typography>
        </Stack>
        <Typography
          component="span"
          noWrap
          sx={{ fontSize: 11, color: 'text.secondary', maxWidth: 80 }}
        >
          {name}
        </Typography>
      </Stack>
    </TableCell>
  );
}

/** The template's name and key; the name links to the editor in the default language. */
function TemplateName({
  template,
  defaultLocale,
}: {
  template: MailTemplateResponse;
  defaultLocale: string;
}) {
  return (
    <>
      <Typography
        component={RouterLink}
        to={mailTemplatePath(template.key, defaultLocale)}
        sx={{
          fontSize: 14,
          fontWeight: 600,
          color: 'text.primary',
          textDecoration: 'none',
          '&:hover': { textDecoration: 'underline' },
        }}
      >
        {template.friendlyName}
      </Typography>
      <Typography sx={{ fontSize: 12, fontFamily: 'monospace', color: 'text.secondary' }} noWrap>
        {template.key}
      </Typography>
    </>
  );
}

/** The marks explained, once, under the matrix. */
function Legend() {
  const { t } = useTranslation('admin');
  const statuses: MailLocaleStatus[] = ['custom', 'shipped', 'fallback'];
  return (
    <Stack
      direction="row"
      useFlexGap
      spacing={2.5}
      sx={{
        flexWrap: 'wrap',
        alignItems: 'center',
        px: 2,
        py: 1.5,
        borderTop: '1px solid',
        borderColor: 'divider',
        bgcolor: (theme) => theme.qnop.surface2,
      }}
    >
      {statuses.map((status) => (
        <Stack key={status} direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
          <LocaleStatusMark status={status} />
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
            {t(MAIL_LOCALE_STATUS_LABELS[status])}
          </Typography>
        </Stack>
      ))}
      <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center' }}>
        <Box
          component="span"
          sx={{ display: 'inline-flex', color: (theme) => theme.qnop.brand.blue }}
        >
          <Star size={12} fill="currentColor" aria-hidden />
        </Box>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          {t('mailTemplates.matrix.defaultLanguageLegend')}
        </Typography>
      </Stack>
    </Stack>
  );
}

/** Narrow screens: one card per template, its languages as a row of linked chips. */
function TemplateCards({
  templates,
  defaultLocale,
}: {
  templates: MailTemplateResponse[];
  defaultLocale: string;
}) {
  const describe = useVariantDescription();
  return (
    <Stack component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
      {templates.map((template) => (
        <Box
          component="li"
          key={template.key}
          sx={{ px: 2, py: 1.5, borderBottom: '1px solid', borderColor: 'divider' }}
        >
          <TemplateName template={template} defaultLocale={defaultLocale} />
          <Stack direction="row" useFlexGap spacing={0.75} sx={{ flexWrap: 'wrap', mt: 1 }}>
            {(template.variants ?? []).map((variant) => (
              <Box
                key={variant.locale}
                component={RouterLink}
                to={mailTemplatePath(template.key, variant.locale)}
                aria-label={describe(template, variant).label}
                sx={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 0.75,
                  px: 1,
                  py: 0.5,
                  borderRadius: 1,
                  border: '1px solid',
                  borderColor: variant.instanceDefault ? 'primary.main' : 'divider',
                  color: 'text.primary',
                  textDecoration: 'none',
                  fontSize: 12,
                  fontWeight: 700,
                  fontFamily: 'monospace',
                }}
              >
                <LocaleStatusMark status={mailLocaleStatus(variant)} />
                {localeShortCode(variant.locale)}
              </Box>
            ))}
          </Stack>
        </Box>
      ))}
    </Stack>
  );
}

/**
 * Mail-templates list (issue #144), the Templates tab of the email admin area (#525): every
 * template of the closed registry against every language the editor offers (issue #853). Each
 * cell shows by shape whether the language is customised, built in, or missing so that English
 * stands in, and links to the editor in that language; the default language's column is tinted
 * and starred. Narrow screens get one card per template instead of the matrix.
 */
export function MailTemplatesListPage() {
  const { t } = useTranslation(['admin', 'common']);
  const { data, isLoading, isFetching, isError } = useMailTemplates();
  const theme = useTheme();
  const narrow = useMediaQuery(theme.breakpoints.down('sm'));

  const templates = data?.templates ?? [];
  const locales = data?.locales ?? [];
  const defaultLocale = data?.defaultLocale ?? 'en';

  return (
    <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
      <Box sx={{ height: 3 }}>{isFetching && <LinearProgress />}</Box>
      {isError ? (
        <Alert severity="error" sx={{ m: 2 }}>
          {t('mailTemplates.list.loadError')}
        </Alert>
      ) : narrow ? (
        <TemplateCards templates={templates} defaultLocale={defaultLocale} />
      ) : (
        <Table size="medium" sx={{ '& th, & td': { borderColor: 'divider' } }}>
          <TableHead>
            <TableRow>
              <TableCell
                sx={{
                  fontSize: 12,
                  color: 'text.secondary',
                  fontWeight: 600,
                  verticalAlign: 'bottom',
                }}
              >
                {t('mailTemplates.list.columns.template')}
              </TableCell>
              {locales.map((locale) => (
                <LocaleHeader key={locale} locale={locale} isDefault={locale === defaultLocale} />
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {!isLoading && templates.length === 0 && (
              <TableRow>
                <TableCell colSpan={locales.length + 1}>
                  <Typography color="text.secondary" sx={{ py: 4, textAlign: 'center' }}>
                    {t('mailTemplates.list.empty')}
                  </Typography>
                </TableCell>
              </TableRow>
            )}
            {templates.map((template) => (
              <TableRow key={template.key} hover>
                <TableCell sx={{ minWidth: 200 }}>
                  <TemplateName template={template} defaultLocale={defaultLocale} />
                </TableCell>
                {(template.variants ?? []).map((variant) => (
                  <VariantCell key={variant.locale} template={template} variant={variant} />
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {isLoading && (
        <Typography color="text.secondary" sx={{ p: 2, fontSize: 14 }}>
          {t('common:loading')}
        </Typography>
      )}
      {!isLoading && !isError && templates.length > 0 && <Legend />}
    </Paper>
  );
}
