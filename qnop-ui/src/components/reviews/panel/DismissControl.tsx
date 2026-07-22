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

import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import Button from '@mui/material/Button';
import InputBase from '@mui/material/InputBase';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { CircleSlash } from 'lucide-react';

/**
 * The owner/admin's escape hatch on someone else's open annotation (issue
 * #408) — deliberately SUBORDINATE to the author's Resolve: a quiet text
 * button that unfolds into a required-justification prompt. The justification
 * lands in the thread, the author keeps their reopen right, so the control
 * says so.
 */
export function DismissControl({
  disabled,
  onDismiss,
}: {
  disabled: boolean;
  onDismiss: (justification: string) => void;
}) {
  const theme = useTheme();
  const [open, setOpen] = useState(false);
  const [justification, setJustification] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  // Unfolding is the user's explicit click — landing the caret in the freshly
  // revealed field is the expected continuation, not a focus steal.
  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  const canSubmit = justification.trim().length > 0 && !disabled;
  const submit = () => {
    if (canSubmit) onDismiss(justification.trim());
  };
  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      submit();
    }
    if (event.key === 'Escape') setOpen(false);
  };

  if (!open) {
    return (
      <Stack direction="row" data-testid="dismiss-control" sx={{ justifyContent: 'flex-end' }}>
        <Button
          size="small"
          variant="text"
          color="warning"
          startIcon={<CircleSlash size={13} />}
          onClick={() => setOpen(true)}
          sx={{ fontSize: 12, color: 'text.secondary' }}
        >
          Dismiss…
        </Button>
      </Stack>
    );
  }

  return (
    <Stack spacing={0.75} data-testid="dismiss-control" sx={{ pl: 2, pr: 0, py: 1 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
        <InputBase
          inputRef={inputRef}
          value={justification}
          onChange={(event) => setJustification(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder="Why is this concern dismissed? (required)"
          inputProps={{ 'aria-label': 'Dismissal justification (required)' }}
          sx={{
            flex: 1,
            fontSize: 13,
            px: 1.25,
            py: 0.25,
            borderRadius: '12px',
            bgcolor: theme.qnop.surface2,
            border: `1px solid ${theme.palette.divider}`,
            transition: theme.transitions.create(['border-color', 'box-shadow'], {
              duration: theme.transitions.duration.shortest,
            }),
            '&.Mui-focused': {
              borderColor: theme.palette.warning.main,
              boxShadow: `0 0 0 2px ${alpha(theme.palette.warning.main, 0.15)}`,
            },
          }}
        />
        <Button
          size="small"
          variant="outlined"
          color="warning"
          startIcon={<CircleSlash size={14} />}
          disabled={!canSubmit}
          onClick={submit}
          sx={{ flexShrink: 0 }}
        >
          Dismiss
        </Button>
        <Button
          size="small"
          variant="text"
          onClick={() => setOpen(false)}
          sx={{ flexShrink: 0, color: 'text.secondary' }}
        >
          Cancel
        </Button>
      </Stack>
      <Typography sx={{ fontSize: 11.5, color: 'text.secondary', pl: 0.5 }}>
        The justification is posted to the thread; the author may reopen.
      </Typography>
    </Stack>
  );
}
