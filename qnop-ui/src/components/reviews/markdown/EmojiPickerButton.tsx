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
import { Smile } from 'lucide-react';
import { EmojiPickerPopover } from './EmojiPickerPopover';

interface EmojiPickerButtonProps {
  /** Receives the chosen emoji as native Unicode; the popover closes itself. */
  onSelect: (emoji: string) => void;
  disabled?: boolean;
}

/**
 * The composer's emoji affordance (issue #445): a quiet smiley opening the
 * shared {@link EmojiPickerPopover}. Selection inserts native Unicode — no
 * image sheets, no shortcodes.
 */
export function EmojiPickerButton({ onSelect, disabled = false }: EmojiPickerButtonProps) {
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);

  return (
    <>
      <Tooltip title="Emoji">
        <span>
          <IconButton
            size="small"
            aria-label="Insert emoji"
            disabled={disabled}
            onClick={(event) => setAnchorEl(event.currentTarget)}
            sx={{
              width: 26,
              height: 26,
              borderRadius: '6px',
              color: 'text.secondary',
              '&:hover': { color: 'text.primary' },
            }}
          >
            <Smile size={15} aria-hidden />
          </IconButton>
        </span>
      </Tooltip>
      <EmojiPickerPopover
        anchorEl={anchorEl}
        onClose={() => setAnchorEl(null)}
        onSelect={onSelect}
      />
    </>
  );
}
