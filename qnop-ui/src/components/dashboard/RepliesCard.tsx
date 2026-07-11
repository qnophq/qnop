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
import { MessagesSquare } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import type { DashboardReply } from '../../api/generated';
import { shortRelativeTime } from '../../utils/relativeTime';
import { SectionCard } from '../admin/layout/SectionCard';
import { UserAvatar } from '../shell/UserAvatar';
import { reviewPath } from './dashboardModel';

interface RepliesCardProps {
  replies: DashboardReply[];
}

/**
 * Replies directed at the caller (issue #454): the latest comments by others
 * in threads they started or joined, each row deep-linking straight into the
 * thread via the permalink params (issue #412).
 */
export function RepliesCard({ replies }: RepliesCardProps) {
  const theme = useTheme();
  const navigate = useNavigate();

  return (
    <SectionCard
      icon={MessagesSquare}
      title="Replies to you"
      description="The latest answers in discussions you started or joined."
    >
      {replies.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          No new replies — your threads are quiet.
        </Typography>
      ) : (
        <Stack spacing={0.5} sx={{ mx: -1 }}>
          {replies.map((reply) => (
            <ButtonBase
              key={reply.commentId}
              onClick={() =>
                navigate(
                  `${reviewPath({ id: reply.documentId, slug: reply.documentSlug })}` +
                    `?annotation=${reply.annotationId}&comment=${reply.commentId}`,
                )
              }
              sx={{
                display: 'block',
                textAlign: 'left',
                borderRadius: '8px',
                px: 1,
                py: 0.75,
                '&:hover': { bgcolor: alpha(theme.qnop.brand.blue, 0.05) },
              }}
            >
              <Stack direction="row" spacing={1.25} sx={{ alignItems: 'flex-start', minWidth: 0 }}>
                <Box sx={{ pt: 0.25 }}>
                  <UserAvatar name={reply.authorDisplayName ?? 'Participant'} size={26} />
                </Box>
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Stack direction="row" spacing={0.75} sx={{ alignItems: 'center', minWidth: 0 }}>
                    <Typography
                      component="span"
                      noWrap
                      sx={{ fontWeight: 700, fontSize: '0.85rem' }}
                    >
                      {reply.authorDisplayName ?? 'Participant'}
                    </Typography>
                    <Typography component="span" variant="caption" color="text.secondary" noWrap>
                      in {reply.documentTitle} · {shortRelativeTime(reply.createdAt)}
                    </Typography>
                  </Stack>
                  <Typography
                    variant="body2"
                    sx={{
                      mt: 0.25,
                      display: '-webkit-box',
                      WebkitLineClamp: 2,
                      WebkitBoxOrient: 'vertical',
                      overflow: 'hidden',
                    }}
                  >
                    {reply.body}
                  </Typography>
                  {reply.annotationExcerpt && (
                    <Typography
                      variant="caption"
                      noWrap
                      component="p"
                      sx={{
                        mt: 0.25,
                        color: 'text.secondary',
                        fontStyle: 'italic',
                        borderLeft: '2px solid',
                        borderColor: 'divider',
                        pl: 0.75,
                      }}
                    >
                      {reply.annotationExcerpt}
                    </Typography>
                  )}
                </Box>
              </Stack>
            </ButtonBase>
          ))}
        </Stack>
      )}
    </SectionCard>
  );
}
