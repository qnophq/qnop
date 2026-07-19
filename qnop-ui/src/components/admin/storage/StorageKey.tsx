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
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import { Check, Copy } from 'lucide-react';

const HEAD = 16;
const TAIL = 10;

/** Middle-truncates a long key so both the shard and the hash tail stay visible. */
function shorten(value: string): string {
  return value.length > HEAD + TAIL + 1 ? `${value.slice(0, HEAD)}…${value.slice(-TAIL)}` : value;
}

/**
 * A storage object key (issue #523): monospaced and middle-truncated with the
 * full value on hover and a copy button — content-addressed keys are long, and
 * an admin often needs to paste one, so it is never shown as bare cut-off text.
 */
export function StorageKey({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);

  const copy = () => {
    void navigator.clipboard?.writeText(value).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    });
  };

  return (
    <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', minWidth: 0 }}>
      <Tooltip title={value}>
        <Box
          component="code"
          sx={{
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
            fontSize: 12,
            color: 'text.secondary',
            whiteSpace: 'nowrap',
          }}
        >
          {shorten(value)}
        </Box>
      </Tooltip>
      <Tooltip title={copied ? 'Copied' : 'Copy key'}>
        <IconButton size="small" onClick={copy} aria-label="Copy storage key">
          {copied ? <Check size={13} /> : <Copy size={13} />}
        </IconButton>
      </Tooltip>
    </Stack>
  );
}
