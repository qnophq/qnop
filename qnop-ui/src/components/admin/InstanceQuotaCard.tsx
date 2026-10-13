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

import Box from '@mui/material/Box';
import LinearProgress from '@mui/material/LinearProgress';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import type { ParseKeys } from 'i18next';
import { Gauge } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import type { InstanceLimitsResponse, InstanceQuota } from '../../api/generated';
import { useInstanceLimits } from '../../api/hooks/useAdminConfiguration';
import { SectionCard } from './layout/SectionCard';
import { tokens } from '../../theme/tokens';

/** At this share of a quota the bar stops being informational and starts being a warning. */
const NEARLY_FULL = 0.8;

interface Row {
  /** `admin` catalog keys (issue #836), translated at render. */
  labelKey: ParseKeys<'admin'>;
  hintKey: ParseKeys<'admin'>;
  quota: InstanceQuota;
}

/**
 * What this deployment may hold, and what it holds (issue #673).
 *
 * <p>It sits on the Configuration page rather than in Settings because that is what these are:
 * values the deployment sets and an administrator reads. The whole point is that the ceiling is
 * visible <em>before</em> somebody runs into it — being refused at the twenty-sixth user tells you
 * the limit, but only after the work of getting there.
 *
 * <p>Renders nothing at all when no quota is configured, which is every Community deployment. An
 * empty card explaining that there are no limits would be a row of zeroes to interpret.
 */
export function InstanceQuotaCard() {
  const { t } = useTranslation('admin');
  const { data } = useInstanceLimits();
  if (!data || !hasAnyLimit(data)) {
    return null;
  }

  const rows: Row[] = [
    { labelKey: 'quota.rows.users.label', hintKey: 'quota.rows.users.hint', quota: data.users },
    { labelKey: 'quota.rows.teams.label', hintKey: 'quota.rows.teams.hint', quota: data.teams },
    {
      labelKey: 'quota.rows.teamMembers.label',
      hintKey: 'quota.rows.teamMembers.hint',
      quota: data.teamMembers,
    },
    {
      labelKey: 'quota.rows.activeReviews.label',
      hintKey: 'quota.rows.activeReviews.hint',
      quota: data.activeReviews,
    },
  ];

  return (
    <SectionCard icon={Gauge} title={t('quota.title')} description={t('quota.description')}>
      <Stack spacing={2.25}>
        {rows.map((row) => (
          <QuotaRow key={row.labelKey} {...row} />
        ))}
      </Stack>
    </SectionCard>
  );
}

function QuotaRow({ labelKey, hintKey, quota }: Row) {
  const { t } = useTranslation('admin');
  const label = t(labelKey);
  const unlimited = quota.maximum <= 0;
  const share = unlimited ? 0 : Math.min(1, quota.used / quota.maximum);
  const full = !unlimited && quota.used >= quota.maximum;
  const tone = full
    ? tokens.semantic.danger
    : share >= NEARLY_FULL
      ? tokens.semantic.warning
      : tokens.brand.blue;

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1, mb: 0.75 }}>
        <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{label}</Typography>
        <Typography color="text.secondary" sx={{ fontSize: 12.5 }}>
          {t(hintKey)}
        </Typography>
        <Typography
          sx={{
            ml: 'auto',
            fontSize: 13.5,
            fontWeight: 600,
            fontVariantNumeric: 'tabular-nums',
            color: full ? tokens.semantic.danger : 'text.primary',
          }}
        >
          {unlimited
            ? t('quota.noLimit', { used: quota.used })
            : t('quota.usedOf', { used: quota.used, maximum: quota.maximum })}
        </Typography>
      </Box>
      {!unlimited && (
        <LinearProgress
          variant="determinate"
          // Capped at 100: an instance can sit above its quota after somebody
          // lowered it, and a bar past the end would read as a rendering fault
          // rather than as the fact it is.
          value={share * 100}
          aria-label={t('quota.progressLabel', {
            label,
            used: quota.used,
            maximum: quota.maximum,
          })}
          sx={{
            height: 6,
            borderRadius: 999,
            bgcolor: 'action.hover',
            '& .MuiLinearProgress-bar': { bgcolor: tone, borderRadius: 999 },
          }}
        />
      )}
    </Box>
  );
}

function hasAnyLimit(limits: InstanceLimitsResponse): boolean {
  return [limits.users, limits.teams, limits.teamMembers, limits.activeReviews].some(
    (quota) => quota.maximum > 0,
  );
}
