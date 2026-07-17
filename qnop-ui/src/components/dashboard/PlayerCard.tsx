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
import Button from '@mui/material/Button';
import Skeleton from '@mui/material/Skeleton';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { Medal } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useUserProfile } from '../../api/hooks/useUsers';
import { AchievementRow } from '../profile/AchievementRow';
import { publicProfileAchievements } from '../profile/profileModel';
import { SectionCard } from '../admin/layout/SectionCard';

const STAT_LABELS = [
  ['reviewsOwned', 'Owned'],
  ['reviewsParticipating', 'Reviewing'],
  ['annotationsRaised', 'Raised'],
  ['annotationsResolved', 'Resolved'],
] as const;

/**
 * The caller's real player card (issues #469/#473): the same gamified reviewer
 * identity the auth stage showcases, fed from the public-profile aggregates —
 * the four contribution numbers over the achievement stickers, with a jump to
 * the full profile. Anonymity-safe by construction: it renders exactly what
 * the public profile shows (ADR-0038).
 */
export function PlayerCard({ userId }: { userId: string }) {
  const navigate = useNavigate();
  const profileQuery = useUserProfile(userId);
  const profile = profileQuery.data;
  const stats = profile?.stats;

  return (
    <SectionCard
      icon={Medal}
      title="Your reviewer card"
      description="What your profile shows the workspace."
      action={
        <Button size="small" onClick={() => navigate(`/users/${profile?.slug ?? userId}`)}>
          View profile
        </Button>
      }
    >
      {profileQuery.isPending ? (
        <Stack spacing={2}>
          <Skeleton variant="rounded" height={56} />
          <Skeleton variant="rounded" height={74} />
        </Stack>
      ) : stats ? (
        <Stack spacing={3}>
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: 'repeat(4, minmax(0, 1fr))',
              gap: 1,
              textAlign: 'center',
            }}
          >
            {STAT_LABELS.map(([key, label]) => (
              <Box key={key}>
                <Typography sx={{ fontWeight: 700, fontSize: 26, lineHeight: 1.2 }}>
                  {stats[key]}
                </Typography>
                <Typography
                  sx={{
                    fontSize: 10.5,
                    letterSpacing: '0.08em',
                    textTransform: 'uppercase',
                    color: 'text.secondary',
                    fontWeight: 600,
                  }}
                >
                  {label}
                </Typography>
              </Box>
            ))}
          </Box>
          <AchievementRow achievements={publicProfileAchievements(stats)} size="large" spread />
        </Stack>
      ) : (
        <Typography color="text.secondary" sx={{ fontSize: 13.5 }}>
          Your profile stats are unavailable right now.
        </Typography>
      )}
    </SectionCard>
  );
}
