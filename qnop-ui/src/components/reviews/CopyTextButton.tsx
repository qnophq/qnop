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

import type { MouseEvent } from 'react';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import { Copy } from 'lucide-react';
import { copyToClipboard } from '../../utils/clipboard';
import type { Notify } from '../admin/layout/useToast';

interface CopyTextButtonProps {
  /** The exact string written to the clipboard (e.g. raw quote or comment body). */
  text: string;
  notify: Notify;
  /** Tooltip + accessible label, e.g. "Copy quote" / "Copy comment". */
  label: string;
  /** Success toast; the error message is shared. */
  copiedMessage?: string;
  iconSize?: number;
}

/**
 * The "copy text" sibling of {@link CopyLinkButton} (issue #478): writes a
 * text payload to the clipboard and confirms with a toast. Stops propagation
 * so it can sit inside clickable annotation cards, and degrades to an error
 * toast when the Clipboard API is unavailable (insecure context).
 */
export function CopyTextButton({
  text,
  notify,
  label,
  copiedMessage = 'Copied to clipboard.',
  iconSize = 13,
}: CopyTextButtonProps) {
  const handleCopy = async (event: MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    const ok = await copyToClipboard(text);
    notify(ok ? copiedMessage : 'Could not copy to the clipboard.', ok ? 'success' : 'error');
  };
  return (
    <Tooltip title={label}>
      <IconButton
        size="small"
        aria-label={label}
        onClick={handleCopy}
        sx={{ color: 'text.secondary' }}
      >
        <Copy size={iconSize} aria-hidden />
      </IconButton>
    </Tooltip>
  );
}
