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
import { Link as LinkIcon } from 'lucide-react';
import { copyToClipboard } from '../../../utils/clipboard';
import type { Notify } from '../../admin/layout/useToast';

interface CopyLinkButtonProps {
  /** The absolute permalink to copy. */
  url: string;
  notify: Notify;
  /** Tooltip + accessible label; the meta variant distinguishes annotation vs comment. */
  label?: string;
  iconSize?: number;
}

/**
 * The quiet "copy link" affordance (issue #412): a small link icon that writes
 * an absolute permalink to the clipboard and confirms with a toast. It stops
 * event propagation so it can sit inside a clickable annotation card without
 * toggling it, and degrades to an error toast when the clipboard is
 * unavailable (an insecure context) rather than throwing.
 */
export function CopyLinkButton({
  url,
  notify,
  label = 'Copy link',
  iconSize = 13,
}: CopyLinkButtonProps) {
  const handleCopy = async (event: MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    const ok = await copyToClipboard(url);
    notify(ok ? 'Link copied.' : 'Could not copy the link.', ok ? 'success' : 'error');
  };
  return (
    <Tooltip title={label}>
      <IconButton
        size="small"
        aria-label={label}
        onClick={handleCopy}
        sx={{ color: 'text.secondary' }}
      >
        <LinkIcon size={iconSize} aria-hidden />
      </IconButton>
    </Tooltip>
  );
}
