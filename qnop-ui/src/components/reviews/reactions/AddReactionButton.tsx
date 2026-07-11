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
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import { SmilePlus } from 'lucide-react';
import { EmojiPickerPopover } from '../markdown/EmojiPickerPopover';

interface AddReactionButtonProps {
  /** Receives the chosen emoji as native Unicode. */
  onPick: (emoji: string) => void;
  /** Matches the copy-link affordance's footprint in the hover clusters. */
  iconSize?: number;
}

/**
 * The hover-cluster entry point for the FIRST reaction (issue #410) — Slack's
 * smiley-plus next to a message. Sits beside the copy-link in the header rows
 * of comments and annotation heads; once reactions exist, the bar's trailing
 * "+" chip takes over for the follow-ups.
 */
export function AddReactionButton({ onPick, iconSize = 13 }: AddReactionButtonProps) {
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  return (
    <>
      <Tooltip title="Add reaction">
        <IconButton
          size="small"
          aria-label="Add reaction"
          onClick={(event) => {
            // Lives inside clickable cards — opening the picker stays local.
            event.stopPropagation();
            setAnchorEl(event.currentTarget);
          }}
          sx={{ p: 0.5, color: 'text.secondary', '&:hover': { color: 'text.primary' } }}
        >
          <SmilePlus size={iconSize} aria-hidden />
        </IconButton>
      </Tooltip>
      <EmojiPickerPopover anchorEl={anchorEl} onClose={() => setAnchorEl(null)} onSelect={onPick} />
    </>
  );
}
