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
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { CircleCheck, Inbox, PartyPopper, type LucideIcon } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import type { DocumentSummary } from '../../api/generated';
import { ToneBadge } from '../admin/ToneBadge';
import { SectionCard } from '../admin/layout/SectionCard';
import { CardEmptyState } from './CardEmptyState';
import { CardScroller } from './CardScroller';
import { CountPill } from './CountPill';
import { DueDateLabel } from '../reviews/DueDateLabel';
import { DocumentIcon, ProgressBar, ReviewerStack } from '../reviews/list/ReviewListParts';
import { progressOf } from '../reviews/list/reviewListModel';
import { WORKFLOW_TONES, workflowLabel } from '../reviews/workflowMeta';
import { readyToFinalize, reviewPath } from './dashboardModel';

interface ReviewListCardProps {
  icon: LucideIcon;
  title: string;
  description: string;
  reviews: DocumentSummary[];
  emptyText: string;
  /** Headline over `emptyText` in the designed empty state (issue #588). */
  emptyTitle?: string;
  /** Medallion glyph of the designed empty state (issue #588). */
  emptyIcon?: LucideIcon;
  /** Shows the owner's "Ready to finalize" cue on settled reviews. */
  ownerCues?: boolean;
  /** Turns the empty state into a small celebration — earned quiet, not absence. */
  celebrateEmpty?: boolean;
}

/**
 * One dashboard review list (issue #454) — the "waiting on you" and "my
 * reviews" cards share this anatomy: linked rows with the workflow badge,
 * resolution progress and the due-date label, sorted by the caller.
 */
export function ReviewListCard({
  icon,
  title,
  description,
  reviews,
  emptyText,
  emptyTitle = 'Nothing here yet',
  emptyIcon = Inbox,
  ownerCues = false,
  celebrateEmpty = false,
}: ReviewListCardProps) {
  const theme = useTheme();
  const navigate = useNavigate();
  const visible = reviews;

  return (
    <SectionCard
      icon={icon}
      title={title}
      description={description}
      action={<CountPill value={reviews.length} />}
    >
      {visible.length === 0 ? (
        celebrateEmpty ? (
          <CardEmptyState icon={PartyPopper} tone="green" title="All caught up!" text={emptyText} />
        ) : (
          <CardEmptyState icon={emptyIcon} title={emptyTitle} text={emptyText} />
        )
      ) : (
        <CardScroller>
          <Stack spacing={0.5}>
            {visible.map((review) => {
              const progress = progressOf(review);
              return (
                <ButtonBase
                  key={review.id}
                  onClick={() => navigate(reviewPath(review))}
                  sx={{
                    display: 'block',
                    textAlign: 'left',
                    borderRadius: '8px',
                    px: 1.25,
                    py: 1,
                    '&:hover': { bgcolor: alpha(theme.qnop.brand.blue, 0.05) },
                  }}
                >
                  <Stack direction="row" spacing={1.25} sx={{ alignItems: 'center', minWidth: 0 }}>
                    <DocumentIcon size={26} contentType={review.contentType} />
                    <Box sx={{ flex: 1, minWidth: 0 }}>
                      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', minWidth: 0 }}>
                        <Typography noWrap sx={{ fontWeight: 600, fontSize: '0.875rem', flex: 1 }}>
                          {review.title}
                        </Typography>
                        {ownerCues && readyToFinalize(review) ? (
                          <ToneBadge
                            tone="green"
                            label="Ready to finalize"
                            icon={<CircleCheck size={11} aria-hidden />}
                          />
                        ) : (
                          <ToneBadge
                            tone={WORKFLOW_TONES[review.workflowState] ?? 'neutral'}
                            label={workflowLabel(review.workflowState)}
                          />
                        )}
                      </Stack>
                      <Stack
                        direction="row"
                        spacing={1.25}
                        sx={{ alignItems: 'center', mt: 0.5, minWidth: 0 }}
                      >
                        {progress ? (
                          <>
                            <ProgressBar
                              resolved={progress.resolved}
                              total={progress.total}
                              color={theme.qnop.brand.blue}
                            />
                            <Typography
                              variant="caption"
                              color="text.secondary"
                              sx={{ flexShrink: 0 }}
                            >
                              {progress.resolved}/{progress.total} resolved
                            </Typography>
                          </>
                        ) : (
                          <Typography variant="caption" color="text.secondary">
                            No annotations yet
                          </Typography>
                        )}
                        <Box sx={{ flex: 1 }} />
                        <ReviewerStack
                          participants={review.participants ?? []}
                          anonymous={review.anonymous}
                        />
                        <DueDateLabel dueAt={review.dueAt} workflowState={review.workflowState} />
                      </Stack>
                    </Box>
                  </Stack>
                </ButtonBase>
              );
            })}
          </Stack>
        </CardScroller>
      )}
    </SectionCard>
  );
}
