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
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { ChevronRight } from 'lucide-react';
import type { DocumentSummary } from '../../../api/generated';
import { useFormatters } from '../../../hooks/useFormatters';
import { DueDateLabel } from '../DueDateLabel';
import { WorkflowBadge } from '../WorkflowBadge';
import { AnonymousBadge } from '../AnonymousBadge';
import { ToneBadge } from '../../admin/ToneBadge';
import { readyToFinalize } from '../../dashboard/dashboardModel';
import { DocumentIcon, OwnerChip, ProgressBar, ReviewerStack, RoleBadge } from './ReviewListParts';
import { progressOf, roleOf } from './reviewListModel';

interface ReviewsTableProps {
  reviews: DocumentSummary[];
  userId: string | null;
  onOpen: (documentId: string) => void;
}

/** The overview's table view (prototype `reviews.jsx`): dense, scannable rows. */
export function ReviewsTable({ reviews, userId, onOpen }: ReviewsTableProps) {
  const theme = useTheme();
  const { formatRelative } = useFormatters();
  return (
    <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
      <Box sx={{ overflowX: 'auto' }}>
        <Table size="small" sx={{ minWidth: 720 }}>
          <TableHead>
            <TableRow sx={{ bgcolor: theme.qnop.surface2 }}>
              {[
                'Document',
                'Role',
                'Owner',
                'Status',
                'Progress',
                'Reviewers',
                'Due',
                'Updated',
                '',
              ].map((header) => (
                <TableCell
                  key={header}
                  sx={{
                    fontSize: 10.5,
                    fontWeight: 500,
                    textTransform: 'uppercase',
                    letterSpacing: '0.06em',
                    color: 'text.secondary',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {header}
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {reviews.map((review) => {
              const progress = progressOf(review);
              return (
                <TableRow
                  key={review.id}
                  hover
                  onClick={() => onOpen(review.slug ?? review.id)}
                  data-testid={`review-row-${review.id}`}
                  sx={{ cursor: 'pointer', '&:last-child td': { borderBottom: 0 } }}
                >
                  <TableCell sx={{ py: 1.5 }}>
                    <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
                      <DocumentIcon />
                      <Box sx={{ minWidth: 0 }}>
                        <Typography variant="body2" noWrap sx={{ fontWeight: 500, maxWidth: 320 }}>
                          {review.title}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          v{review.latestVersionNumber}
                        </Typography>
                      </Box>
                    </Stack>
                  </TableCell>
                  <TableCell>
                    <RoleBadge role={roleOf(review, userId)} />
                  </TableCell>
                  <TableCell>
                    <OwnerChip
                      ownerId={review.ownerId}
                      slug={review.ownerSlug}
                      name={review.ownerDisplayName}
                    />
                  </TableCell>
                  <TableCell>
                    <Stack
                      direction="row"
                      spacing={0.75}
                      useFlexGap
                      sx={{ alignItems: 'center', flexWrap: 'wrap' }}
                    >
                      <WorkflowBadge state={review.workflowState} />
                      {review.anonymous && <AnonymousBadge compact />}
                      {roleOf(review, userId) === 'owner' && readyToFinalize(review) && (
                        <ToneBadge tone="green" label="Ready to finalize" />
                      )}
                    </Stack>
                  </TableCell>
                  <TableCell sx={{ minWidth: 120 }}>
                    {progress ? (
                      <ProgressBar
                        resolved={progress.resolved}
                        total={progress.total}
                        // Fully settled reads as a win, not just a full bar.
                        color={
                          progress.resolved === progress.total
                            ? theme.palette.success.main
                            : theme.qnop.brand.blue
                        }
                      />
                    ) : (
                      <Typography variant="caption" color="text.secondary">
                        —
                      </Typography>
                    )}
                  </TableCell>
                  <TableCell>
                    <ReviewerStack
                      participants={review.participants}
                      anonymous={review.anonymous}
                    />
                  </TableCell>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>
                    {review.dueAt ? (
                      <DueDateLabel dueAt={review.dueAt} workflowState={review.workflowState} />
                    ) : (
                      <Typography variant="caption" color="text.secondary">
                        —
                      </Typography>
                    )}
                  </TableCell>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>
                    <Typography variant="caption" color="text.secondary">
                      {formatRelative(review.updatedAt)}
                    </Typography>
                  </TableCell>
                  <TableCell align="right" sx={{ color: 'text.secondary' }}>
                    <ChevronRight size={16} aria-hidden />
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </Box>
    </Paper>
  );
}
