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
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import IconButton from '@mui/material/IconButton';
import Popover from '@mui/material/Popover';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { Smile } from 'lucide-react';

/** The picker's footprint, reserved so the popover doesn't jump when it loads. */
const PICKER_WIDTH = 352;
const PICKER_HEIGHT = 420;

interface EmojiPickerButtonProps {
  /** Receives the chosen emoji as native Unicode; the popover closes itself. */
  onSelect: (emoji: string) => void;
  disabled?: boolean;
}

/**
 * The composer's emoji affordance (issue #445): a quiet smiley that opens the
 * Slack-style emoji-mart picker in a popover. The picker is heavy (the emoji
 * index alone is ~200 kB gzipped), so `emoji-mart` and its data load lazily on
 * first open and never touch the initial bundle. Selection inserts native
 * Unicode — no image sheets, no shortcodes.
 */
export function EmojiPickerButton({ onSelect, disabled = false }: EmojiPickerButtonProps) {
  const theme = useTheme();
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'failed'>('loading');
  const hostRef = useRef<HTMLDivElement | null>(null);
  // The latest callback, readable from the picker without re-creating it.
  const onSelectRef = useRef(onSelect);
  useEffect(() => {
    onSelectRef.current = onSelect;
  }, [onSelect]);

  const open = Boolean(anchorEl);

  // Mount the picker (a framework-agnostic custom element) into the popover
  // once it opens. Rebuilt per open — it must re-read the current theme, and
  // keeping it mounted would pin ~1 MB of emoji index for a rarely-open surface.
  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    (async () => {
      try {
        const [{ Picker }, { default: data }] = await Promise.all([
          import('emoji-mart'),
          import('@emoji-mart/data'),
        ]);
        if (cancelled || !hostRef.current) return;
        const picker = new Picker({
          data,
          set: 'native',
          theme: theme.qnop.mode,
          previewPosition: 'none',
          skinTonePosition: 'search',
          autoFocus: true,
          onEmojiSelect: (emoji: { native?: string }) => {
            const { native } = emoji;
            if (!native) return;
            setAnchorEl(null);
            // Insert AFTER the popover's focus restore has run, so the
            // composer's refocus of the textarea wins.
            requestAnimationFrame(() => onSelectRef.current(native));
          },
        });
        // The host div is manual-DOM only — React renders the spinner/error as
        // siblings, so the reconciler never trips over the injected element.
        hostRef.current.replaceChildren(picker as unknown as HTMLElement);
        setStatus('ready');
      } catch {
        if (!cancelled) setStatus('failed');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [open, theme.qnop.mode]);

  return (
    <>
      <Tooltip title="Emoji">
        <span>
          <IconButton
            size="small"
            aria-label="Insert emoji"
            disabled={disabled}
            onClick={(event) => {
              setStatus('loading');
              setAnchorEl(event.currentTarget);
            }}
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
      <Popover
        open={open}
        anchorEl={anchorEl}
        onClose={() => setAnchorEl(null)}
        anchorOrigin={{ vertical: 'top', horizontal: 'left' }}
        transformOrigin={{ vertical: 'bottom', horizontal: 'left' }}
        slotProps={{ paper: { sx: { borderRadius: '10px', overflow: 'hidden' } } }}
      >
        <Box
          sx={{
            width: PICKER_WIDTH,
            minHeight: status === 'ready' ? 0 : PICKER_HEIGHT,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            // The custom element sizes itself; cap it to the reserved frame.
            '& em-emoji-picker': { width: '100%', height: PICKER_HEIGHT },
          }}
        >
          {status === 'failed' && (
            <Typography variant="body2" color="text.secondary" sx={{ p: 2 }}>
              The emoji picker could not be loaded.
            </Typography>
          )}
          {status === 'loading' && <CircularProgress size={22} aria-label="Loading emoji picker" />}
          <Box ref={hostRef} sx={{ alignSelf: 'stretch' }} />
        </Box>
      </Popover>
    </>
  );
}
