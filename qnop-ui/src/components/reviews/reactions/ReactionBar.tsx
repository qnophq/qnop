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
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import { alpha, useTheme } from '@mui/material/styles';
import { SmilePlus } from 'lucide-react';
import type { ReactionGroup } from '../../../api/generated';
import { EmojiPickerPopover } from '../markdown/EmojiPickerPopover';

interface ReactionBarProps {
  reactions: ReactionGroup[];
  /** Toggles the viewer's reaction; `reacted` is the chip's CURRENT own-state. */
  onToggle: (emoji: string, reacted: boolean) => void;
}

/**
 * Slack's reaction row (issue #410): one pill chip per emoji with a tabular
 * count, the viewer's own reactions accented in brand blue, hovering reveals
 * who reacted, clicking toggles. A trailing "+" chip opens the shared emoji
 * picker for another emoji. Renders nothing while there are no reactions —
 * the FIRST reaction comes from the hover affordance next to the message
 * ({@link AddReactionButton}), as in Slack.
 */
export function ReactionBar({ reactions, onToggle }: ReactionBarProps) {
  const theme = useTheme();
  const [pickerAnchor, setPickerAnchor] = useState<HTMLElement | null>(null);
  if (reactions.length === 0) return null;

  const chipSx = (own: boolean) => ({
    height: 24,
    px: 1,
    borderRadius: '12px',
    display: 'inline-flex',
    alignItems: 'center',
    gap: 0.5,
    fontSize: '0.8125rem',
    lineHeight: 1,
    border: '1px solid',
    transition: 'border-color 120ms ease, background-color 120ms ease',
    '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
    ...(own
      ? {
          borderColor: alpha(theme.qnop.brand.blue, 0.55),
          bgcolor: alpha(theme.qnop.brand.blue, 0.1),
          color: theme.qnop.brand.blue,
        }
      : {
          borderColor: 'divider',
          bgcolor: theme.qnop.surface2,
          color: 'text.secondary',
        }),
    '&:hover': {
      borderColor: alpha(theme.qnop.brand.blue, 0.55),
      bgcolor: alpha(theme.qnop.brand.blue, own ? 0.16 : 0.06),
    },
  });

  return (
    <Stack
      direction="row"
      spacing={0.5}
      sx={{ flexWrap: 'wrap', alignItems: 'center', rowGap: 0.5 }}
      data-testid="reaction-bar"
    >
      {reactions.map((group) => (
        <Tooltip key={group.emoji} title={group.reactors.join(', ')}>
          <ButtonBase
            onClick={(event) => {
              // Chips live inside clickable cards — the toggle stays local.
              event.stopPropagation();
              onToggle(group.emoji, group.reactedByMe);
            }}
            aria-pressed={group.reactedByMe}
            aria-label={`${group.emoji} ${group.count}`}
            sx={chipSx(group.reactedByMe)}
          >
            <Box component="span" sx={{ fontSize: '0.875rem' }}>
              {group.emoji}
            </Box>
            <Box
              component="span"
              sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: 600, fontSize: '0.75rem' }}
            >
              {group.count}
            </Box>
          </ButtonBase>
        </Tooltip>
      ))}
      <Tooltip title="Add reaction">
        <ButtonBase
          aria-label="Add reaction"
          onClick={(event) => {
            event.stopPropagation();
            setPickerAnchor(event.currentTarget);
          }}
          sx={{
            height: 24,
            width: 28,
            borderRadius: '12px',
            border: '1px dashed',
            borderColor: 'divider',
            color: 'text.secondary',
            '&:hover': {
              borderColor: alpha(theme.qnop.brand.blue, 0.55),
              color: theme.qnop.brand.blue,
            },
          }}
        >
          <SmilePlus size={13} aria-hidden />
        </ButtonBase>
      </Tooltip>
      <EmojiPickerPopover
        anchorEl={pickerAnchor}
        onClose={() => setPickerAnchor(null)}
        onSelect={(emoji) => {
          const existing = reactions.find((group) => group.emoji === emoji);
          onToggle(emoji, existing?.reactedByMe ?? false);
        }}
      />
    </Stack>
  );
}
