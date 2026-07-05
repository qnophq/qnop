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
import type { KeyboardEvent } from 'react';
import Button from '@mui/material/Button';
import InputBase from '@mui/material/InputBase';
import Stack from '@mui/material/Stack';
import { alpha, useTheme } from '@mui/material/styles';
import { CircleCheck } from 'lucide-react';

/**
 * The author's closing move on their own open annotation (issue #405): one
 * Resolve action plus an optional note that lands in the thread as the last
 * word. Quiet by design — a pill input and a success button, no verdict
 * vocabulary.
 */
export function ResolveBar({
  disabled,
  onResolve,
}: {
  disabled: boolean;
  onResolve: (note?: string) => void;
}) {
  const theme = useTheme();
  const [note, setNote] = useState('');

  const resolve = () => onResolve(note.trim() || undefined);
  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Enter' && !event.shiftKey && !disabled) {
      event.preventDefault();
      resolve();
    }
  };

  return (
    <Stack
      direction="row"
      spacing={1}
      data-testid="resolve-bar"
      sx={{ alignItems: 'center', pl: 2, pr: 1.5, py: 1 }}
    >
      <InputBase
        value={note}
        onChange={(event) => setNote(event.target.value)}
        onKeyDown={onKeyDown}
        placeholder="Optional closing note…"
        inputProps={{ 'aria-label': 'Optional closing note' }}
        sx={{
          flex: 1,
          fontSize: 13,
          px: 1.25,
          py: 0.25,
          // Same rounding as the thread's comment composer block.
          borderRadius: '12px',
          bgcolor: theme.qnop.surface2,
          border: `1px solid ${theme.palette.divider}`,
          transition: theme.transitions.create(['border-color', 'box-shadow'], {
            duration: theme.transitions.duration.shortest,
          }),
          '&.Mui-focused': {
            borderColor: theme.palette.success.main,
            boxShadow: `0 0 0 2px ${alpha(theme.palette.success.main, 0.15)}`,
          },
        }}
      />
      <Button
        size="small"
        variant="contained"
        color="success"
        startIcon={<CircleCheck size={14} />}
        disabled={disabled}
        onClick={resolve}
        sx={{ flexShrink: 0 }}
      >
        Resolve
      </Button>
    </Stack>
  );
}
