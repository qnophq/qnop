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

import { useState } from 'react';
import Box from '@mui/material/Box';
import ButtonBase from '@mui/material/ButtonBase';
import IconButton from '@mui/material/IconButton';
import InputAdornment from '@mui/material/InputAdornment';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { Plus, Search, X } from 'lucide-react';
import type { PrincipalView } from '../../../api/generated';
import { ParticipantKind } from '../../../api/generated';
import { usePrincipalSearch } from '../../../api/hooks/useReviews';
import { UserAvatar } from '../../shell/UserAvatar';
import { TeamAvatar } from '../../shell/TeamAvatar';

interface ReviewerStepProps {
  selected: PrincipalView[];
  /** The current user — the owner is structural and cannot be a reviewer (ADR-0011). */
  ownUserId: string | null;
  onAdd: (principal: PrincipalView) => void;
  onRemove: (principal: PrincipalView) => void;
}

function principalKey(p: PrincipalView): string {
  return `${p.kind}:${p.id}`;
}

/** Step 2 — pick reviewers (users or teams) from the principal directory. */
export function ReviewerStep({ selected, ownUserId, onAdd, onRemove }: ReviewerStepProps) {
  const theme = useTheme();
  const [query, setQuery] = useState('');
  const { data, isPending } = usePrincipalSearch(query.trim());

  const selectedKeys = new Set(selected.map(principalKey));
  const candidates = (data?.principals ?? []).filter(
    (p) =>
      !selectedKeys.has(principalKey(p)) &&
      !(p.kind === ParticipantKind.User && p.id === ownUserId),
  );

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          Selected reviewers
        </Typography>
        {selected.length === 0 ? (
          <Typography variant="body2" color="text.disabled">
            No reviewers yet — you can also add them later.
          </Typography>
        ) : (
          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
            {selected.map((principal) => (
              <Stack
                key={principalKey(principal)}
                direction="row"
                spacing={1}
                sx={{
                  alignItems: 'center',
                  pl: 0.75,
                  pr: 0.5,
                  py: 0.5,
                  borderRadius: 1.75,
                  bgcolor: theme.palette.primary.light,
                  border: `1px solid ${theme.qnop.brand.blue}2E`,
                }}
              >
                {principal.kind === ParticipantKind.Team ? (
                  <TeamAvatar
                    name={principal.displayName}
                    size={22}
                    imageUrl={principal.avatarUrl}
                  />
                ) : (
                  <UserAvatar
                    name={principal.displayName}
                    size={22}
                    imageUrl={principal.avatarUrl}
                  />
                )}
                <Typography variant="body2" sx={{ fontSize: 12.5, fontWeight: 500 }}>
                  {principal.displayName}
                </Typography>
                <IconButton
                  size="small"
                  onClick={() => onRemove(principal)}
                  aria-label={`Remove ${principal.displayName}`}
                  sx={{ width: 20, height: 20 }}
                >
                  <X size={12} />
                </IconButton>
              </Stack>
            ))}
          </Stack>
        )}
      </Box>

      <Box>
        <TextField
          size="small"
          fullWidth
          placeholder="Search people or teams…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          sx={{ mb: 1.25 }}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <Search size={15} />
                </InputAdornment>
              ),
            },
          }}
        />
        <Box
          data-testid="principal-list"
          sx={{
            border: `1px solid ${theme.palette.divider}`,
            borderRadius: 2,
            // The directory can be long (issue #292) — cap the list to roughly
            // seven rows and let it scroll instead of stretching the wizard.
            maxHeight: 'min(48vh, 384px)',
            overflowY: 'auto',
          }}
        >
          {candidates.length === 0 ? (
            <Typography variant="body2" color="text.disabled" sx={{ px: 2, py: 1.75 }}>
              {isPending ? 'Searching…' : 'No matching people or teams.'}
            </Typography>
          ) : (
            candidates.map((principal, i) => (
              <ButtonBase
                key={principalKey(principal)}
                onClick={() => onAdd(principal)}
                data-testid={`principal-${principal.id}`}
                sx={{
                  display: 'flex',
                  width: '100%',
                  alignItems: 'center',
                  gap: 1.25,
                  px: 1.75,
                  py: 1.25,
                  textAlign: 'left',
                  borderBottom:
                    i < candidates.length - 1 ? `1px solid ${theme.palette.divider}` : 'none',
                  '&:hover': { bgcolor: theme.qnop.surface2 },
                  '&:focus-visible': { boxShadow: `inset ${theme.qnop.focusRing}` },
                }}
              >
                {principal.kind === ParticipantKind.Team ? (
                  <TeamAvatar
                    name={principal.displayName}
                    size={28}
                    imageUrl={principal.avatarUrl}
                  />
                ) : (
                  <UserAvatar
                    name={principal.displayName}
                    size={28}
                    imageUrl={principal.avatarUrl}
                  />
                )}
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Typography variant="body2" noWrap sx={{ fontWeight: 500 }}>
                    {principal.displayName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {principal.kind === ParticipantKind.Team ? 'Team' : 'User'}
                  </Typography>
                </Box>
                <Plus size={14} aria-hidden style={{ color: theme.palette.text.disabled }} />
              </ButtonBase>
            ))
          )}
        </Box>
      </Box>
    </Stack>
  );
}
