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
import ButtonBase from '@mui/material/ButtonBase';
import Divider from '@mui/material/Divider';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { History, MessageSquare } from 'lucide-react';
import type { DocumentSummary } from '../../../api/generated';
import { formatRelative } from '../../../utils/formatDate';
import { WorkflowBadge } from '../WorkflowBadge';
import { DocumentIcon, ProgressBar, ReviewerStack, RoleBadge } from './ReviewListParts';
import { progressOf, roleOf } from './reviewListModel';

interface ReviewCardsProps {
  reviews: DocumentSummary[];
  userId: string | null;
  onOpen: (documentId: string) => void;
}

/** The overview's card view: hover-lift tiles (prototype `rv-card`). */
export function ReviewCards({ reviews, userId, onOpen }: ReviewCardsProps) {
  const theme = useTheme();
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))',
        gap: 2,
      }}
    >
      {reviews.map((review) => {
        const progress = progressOf(review);
        return (
          <ButtonBase
            key={review.id}
            onClick={() => onOpen(review.id)}
            data-testid={`review-card-${review.id}`}
            sx={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'stretch',
              textAlign: 'left',
              gap: 1.5,
              p: 2.25,
              borderRadius: 1.5,
              border: `1px solid ${theme.palette.divider}`,
              bgcolor: 'background.paper',
              transition: 'transform 160ms ease, box-shadow 160ms ease, border-color 160ms ease',
              '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
              '&:hover': {
                transform: 'translateY(-3px)',
                boxShadow: '0 14px 36px -14px rgba(1, 32, 66, 0.22)',
                borderColor: theme.palette.text.disabled,
              },
              '&:focus-visible': { boxShadow: theme.qnop.focusRing },
            }}
          >
            <Stack
              direction="row"
              sx={{ justifyContent: 'space-between', alignItems: 'flex-start' }}
            >
              <DocumentIcon size={38} />
              <RoleBadge role={roleOf(review, userId)} />
            </Stack>
            <Box>
              <Typography variant="body1" sx={{ fontWeight: 500, lineHeight: 1.3 }}>
                {review.title}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                v{review.latestVersionNumber}
              </Typography>
            </Box>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <WorkflowBadge state={review.workflowState} />
              {progress && (
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ fontVariantNumeric: 'tabular-nums' }}
                >
                  {progress.decided}/{progress.total} decided
                </Typography>
              )}
            </Stack>
            {progress && (
              <ProgressBar
                decided={progress.decided}
                total={progress.total}
                color={theme.qnop.brand.blue}
              />
            )}
            <Divider />
            <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
              <ReviewerStack participants={review.participants} />
              <Box sx={{ flex: 1 }} />
              <Stack
                direction="row"
                spacing={0.5}
                sx={{ alignItems: 'center', color: 'text.secondary' }}
              >
                <History size={12} aria-hidden />
                <Typography variant="caption">{review.latestVersionNumber}</Typography>
              </Stack>
              <Stack
                direction="row"
                spacing={0.5}
                sx={{ alignItems: 'center', color: 'text.secondary' }}
              >
                <MessageSquare size={12} aria-hidden />
                <Typography variant="caption">{review.annotationCount}</Typography>
              </Stack>
            </Stack>
            <Typography variant="caption" color="text.secondary">
              {formatRelative(review.updatedAt)}
            </Typography>
          </ButtonBase>
        );
      })}
    </Box>
  );
}
