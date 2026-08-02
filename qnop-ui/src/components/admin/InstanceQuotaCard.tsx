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
import { Gauge } from 'lucide-react';
import type { InstanceLimitsResponse, InstanceQuota } from '../../api/generated';
import { useInstanceLimits } from '../../api/hooks/useAdminConfiguration';
import { SectionCard } from './layout/SectionCard';
import { tokens } from '../../theme/tokens';

/** At this share of a quota the bar stops being informational and starts being a warning. */
const NEARLY_FULL = 0.8;

interface Row {
  label: string;
  hint: string;
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
  const { data } = useInstanceLimits();
  if (!data || !hasAnyLimit(data)) {
    return null;
  }

  const rows: Row[] = [
    { label: 'User accounts', hint: 'enabled accounts', quota: data.users },
    { label: 'Teams', hint: 'teams on this instance', quota: data.teams },
    { label: 'Members per team', hint: 'the largest team', quota: data.teamMembers },
    { label: 'Active reviews', hint: 'not finished, not archived', quota: data.activeReviews },
  ];

  return (
    <SectionCard
      icon={Gauge}
      title="Instance quotas"
      description="What this deployment may hold. Set where it is deployed, not in the settings — an administrator can read these but not raise them."
    >
      <Stack spacing={2.25}>
        {rows.map((row) => (
          <QuotaRow key={row.label} {...row} />
        ))}
      </Stack>
    </SectionCard>
  );
}

function QuotaRow({ label, hint, quota }: Row) {
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
          {hint}
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
          {unlimited ? `${quota.used} — no limit` : `${quota.used} of ${quota.maximum}`}
        </Typography>
      </Box>
      {!unlimited && (
        <LinearProgress
          variant="determinate"
          // Capped at 100: an instance can sit above its quota after somebody
          // lowered it, and a bar past the end would read as a rendering fault
          // rather than as the fact it is.
          value={share * 100}
          aria-label={`${label}: ${quota.used} of ${quota.maximum}`}
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
