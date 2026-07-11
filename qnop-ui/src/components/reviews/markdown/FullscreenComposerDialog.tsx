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

import { useRef, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import Dialog from '@mui/material/Dialog';
import Fade from '@mui/material/Fade';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { Minimize2, PenLine } from 'lucide-react';

/** Reading-comfortable line length for the writing column. */
const STAGE_MAX_WIDTH = 860;
const RAIL_WIDTH = 400;

interface FullscreenComposerDialogProps {
  open: boolean;
  onClose: () => void;
  /** The stage's heading — "Write a reply", "New annotation", … */
  title: string;
  /** Right-hand context rail: the discussion so far, the anchored passage, … */
  context?: ReactNode;
  contextTitle?: string;
  /** The writing surface — a composer the HOST controls, so drafts survive the mode switch. */
  children: ReactNode;
}

/**
 * The full-screen writing stage (issue #403 follow-up): long thoughts deserve
 * more room than an inline reply box. The editor takes a centred, reading-wide
 * column; the conversation stays visible in a quiet rail on the right — where
 * discussions live everywhere else in the app — so writers keep the thread's
 * context without scrolling the page behind. The host renders the SAME
 * controlled composer inside, so the draft carries over in both directions;
 * Escape or the minimize affordance returns to the inline field.
 */
export function FullscreenComposerDialog({
  open,
  onClose,
  title,
  context,
  contextTitle = 'Discussion',
  children,
}: FullscreenComposerDialogProps) {
  const theme = useTheme();
  const stageRef = useRef<HTMLDivElement>(null);

  // Writers expect the caret in the field, at the end of their draft — the
  // dialog's default focus lands on the first button instead.
  const focusEditor = () => {
    const input = stageRef.current?.querySelector('textarea');
    if (!input) return;
    input.focus();
    const end = input.value.length;
    input.setSelectionRange(end, end);
  };

  return (
    <Dialog
      fullScreen
      open={open}
      onClose={onClose}
      slots={{ transition: Fade }}
      transitionDuration={200}
      slotProps={{ transition: { onEntered: focusEditor } }}
      data-testid="fullscreen-composer"
    >
      <Stack sx={{ height: '100%' }}>
        {/* Stage header: what you are writing, and the way back. */}
        <Stack
          direction="row"
          spacing={1.25}
          sx={{
            alignItems: 'center',
            px: 2,
            py: 1,
            borderBottom: '1px solid',
            borderColor: 'divider',
          }}
        >
          <Box
            aria-hidden
            sx={{
              width: 30,
              height: 30,
              borderRadius: '8px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: theme.qnop.brand.blue,
              bgcolor: alpha(theme.qnop.brand.blue, 0.1),
            }}
          >
            <PenLine size={15} />
          </Box>
          <Typography sx={{ fontWeight: 700, fontSize: '0.9375rem' }}>{title}</Typography>
          <Box sx={{ flex: 1 }} />
          <Typography
            variant="caption"
            sx={{
              color: 'text.secondary',
              display: { xs: 'none', sm: 'inline-flex' },
              alignItems: 'center',
              gap: 0.5,
            }}
          >
            <Box
              component="kbd"
              sx={{
                font: 'inherit',
                px: 0.75,
                py: 0.1,
                borderRadius: '5px',
                border: '1px solid',
                borderColor: 'divider',
                bgcolor: theme.qnop.surface2,
              }}
            >
              esc
            </Box>
            to exit
          </Typography>
          <Tooltip title="Exit full screen">
            <IconButton size="small" aria-label="Exit full screen" onClick={onClose}>
              <Minimize2 size={16} />
            </IconButton>
          </Tooltip>
        </Stack>

        <Stack direction="row" sx={{ flex: 1, minHeight: 0 }}>
          {/* The writing stage: a centred, reading-wide column. */}
          <Box ref={stageRef} sx={{ flex: 1, minWidth: 0, overflowY: 'auto' }}>
            <Box sx={{ maxWidth: STAGE_MAX_WIDTH, mx: 'auto', px: { xs: 2, sm: 3 }, py: 3 }}>
              {children}
            </Box>
          </Box>

          {/* The conversation, kept in sight — quiet, read-only, on the right
              where discussions live everywhere else in the app. */}
          {context && (
            <Box
              data-testid="fullscreen-composer-context"
              sx={{
                width: RAIL_WIDTH,
                flexShrink: 0,
                display: { xs: 'none', md: 'flex' },
                flexDirection: 'column',
                minHeight: 0,
                borderLeft: '1px solid',
                borderColor: 'divider',
                bgcolor: theme.qnop.surface2,
              }}
            >
              <Typography
                variant="overline"
                sx={{
                  px: 2.5,
                  pt: 1.75,
                  pb: 0.75,
                  color: 'text.secondary',
                  letterSpacing: '0.08em',
                }}
              >
                {contextTitle}
              </Typography>
              <Box sx={{ flex: 1, overflowY: 'auto', px: 2.5, pb: 2.5 }}>{context}</Box>
            </Box>
          )}
        </Stack>
      </Stack>
    </Dialog>
  );
}
