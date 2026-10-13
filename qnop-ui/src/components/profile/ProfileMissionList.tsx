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
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { Crown, Telescope, UserCheck } from 'lucide-react';
import { Link as RouterLink } from 'react-router';
import type { DocumentSummary } from '../../api/generated';
import { reviewPath } from '../dashboard/dashboardModel';
import { ToneBadge } from '../admin/ToneBadge';
import { ProgressBar } from '../reviews/list/ReviewListParts';
import { WORKFLOW_TONES } from '../reviews/workflowMeta';
import { useWorkflowLabel } from '../reviews/useWorkflowLabel';
import { useTranslation } from 'react-i18next';

interface ProfileMissionListProps {
  /** Owned missions wear the commander's crown; joined ones the crew mark. */
  variant: 'owned' | 'reviewing';
  /** The profiled person's first name, for the section copy. */
  firstName: string;
  reviews: DocumentSummary[];
}

/**
 * The profiled person's reviews in the campaign's mission language (issue
 * #482 polish): commanded (owner) or joined (reviewer), each row linking into
 * the review with its state badge and resolved-progress strip. Fed from the
 * VIEWER's own overview, so only shared, anonymity-safe reviews ever appear.
 */
export function ProfileMissionList({ variant, firstName, reviews }: ProfileMissionListProps) {
  const { t } = useTranslation('profile');
  const workflowLabel = useWorkflowLabel();
  const theme = useTheme();
  const blue = theme.qnop.brand.blue;
  const dark = theme.qnop.mode === 'dark';
  const owned = variant === 'owned';
  const accent = owned ? theme.palette.warning.main : blue;
  const Icon = owned ? Crown : UserCheck;

  return (
    <Paper
      variant="outlined"
      data-testid={`profile-missions-${variant}`}
      sx={{ p: { xs: 2, sm: 2.5 }, borderRadius: '16px' }}
    >
      <Stack direction="row" spacing={1.25} sx={{ alignItems: 'center', mb: 0.25 }}>
        <Box
          aria-hidden
          sx={{
            width: 30,
            height: 30,
            borderRadius: '9px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: accent,
            bgcolor: alpha(accent, dark ? 0.16 : 0.1),
            flexShrink: 0,
          }}
        >
          <Icon size={15} />
        </Box>
        <Typography sx={{ fontSize: 14, fontWeight: 700, flex: 1 }}>
          {t(owned ? 'missions.owned.title' : 'missions.reviewing.title')}
        </Typography>
        {reviews.length > 0 && (
          <Typography
            variant="caption"
            sx={{ fontWeight: 700, color: accent, fontVariantNumeric: 'tabular-nums' }}
          >
            {reviews.length}
          </Typography>
        )}
      </Stack>
      <Typography variant="caption" color="text.secondary" component="p" sx={{ mb: 1.25 }}>
        {t('missions.sharedCaption')}
      </Typography>

      {reviews.length === 0 ? (
        <Stack
          direction="row"
          spacing={1}
          sx={{ alignItems: 'center', color: 'text.disabled', py: 1 }}
        >
          <Telescope size={15} aria-hidden />
          <Typography variant="body2" color="text.secondary">
            {t(owned ? 'missions.owned.empty' : 'missions.reviewing.empty', {
              name: firstName,
            })}
          </Typography>
        </Stack>
      ) : (
        <Stack spacing={0.25}>
          {reviews.map((review) => {
            const total = review.annotationCount;
            const resolved = total - review.openAnnotationCount;
            return (
              <Stack
                key={review.id}
                component={RouterLink}
                to={reviewPath(review)}
                direction="row"
                spacing={1.5}
                sx={{
                  alignItems: 'center',
                  px: 1.25,
                  py: 1,
                  mx: -1.25,
                  borderRadius: '10px',
                  textDecoration: 'none',
                  color: 'inherit',
                  '&:hover': { bgcolor: theme.qnop.surface2 },
                  '&:focus-visible': { boxShadow: `inset ${theme.qnop.focusRing}` },
                }}
              >
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Typography noWrap sx={{ fontWeight: 600, fontSize: '0.875rem' }}>
                    {review.title}
                  </Typography>
                  {total > 0 && (
                    <Box sx={{ mt: 0.5 }}>
                      <ProgressBar resolved={resolved} total={total} color={blue} />
                    </Box>
                  )}
                </Box>
                <ToneBadge
                  tone={WORKFLOW_TONES[review.workflowState] ?? 'neutral'}
                  label={workflowLabel(review.workflowState)}
                />
              </Stack>
            );
          })}
        </Stack>
      )}
    </Paper>
  );
}
