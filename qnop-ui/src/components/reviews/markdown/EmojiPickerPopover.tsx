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
import Popover from '@mui/material/Popover';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';

/** The picker's footprint, reserved so the popover doesn't jump when it loads. */
const PICKER_WIDTH = 352;
const PICKER_HEIGHT = 420;

interface EmojiPickerPopoverProps {
  anchorEl: HTMLElement | null;
  onClose: () => void;
  /**
   * Receives the chosen emoji as native Unicode after the popover has closed
   * and its focus restore has run — so a composer's refocus wins.
   */
  onSelect: (emoji: string) => void;
}

/**
 * The Slack-style emoji-mart picker in a popover (issue #445), shared by the
 * composer's emoji button and the reaction affordances (issue #410). The
 * picker is heavy (the emoji index alone is ~200 kB gzipped), so `emoji-mart`
 * and its data load lazily on open and never touch the initial bundle; closing
 * unmounts it, so the index never stays pinned for a rarely-open surface.
 */
export function EmojiPickerPopover({ anchorEl, onClose, onSelect }: EmojiPickerPopoverProps) {
  const open = Boolean(anchorEl);
  return (
    <Popover
      open={open}
      anchorEl={anchorEl}
      onClose={onClose}
      anchorOrigin={{ vertical: 'top', horizontal: 'left' }}
      transformOrigin={{ vertical: 'bottom', horizontal: 'left' }}
      slotProps={{ paper: { sx: { borderRadius: '10px', overflow: 'hidden' } } }}
      // The popover portals out of the DOM but NOT out of the React tree: a
      // click inside would bubble synthetically into a hosting annotation
      // card's ButtonBase and collapse it (issue #410).
      onClick={(event) => event.stopPropagation()}
    >
      {/* Mount fresh per open: the picker re-reads the theme, and the loading
          state resets by construction (no set-state-in-effect). */}
      {open && <PickerHost onClose={onClose} onSelect={onSelect} />}
    </Popover>
  );
}

function PickerHost({ onClose, onSelect }: Omit<EmojiPickerPopoverProps, 'anchorEl'>) {
  const theme = useTheme();
  const [status, setStatus] = useState<'loading' | 'ready' | 'failed'>('loading');
  const hostRef = useRef<HTMLDivElement | null>(null);
  // The latest callbacks, readable from the picker without re-creating it.
  const onSelectRef = useRef(onSelect);
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onSelectRef.current = onSelect;
    onCloseRef.current = onClose;
  }, [onSelect, onClose]);

  useEffect(() => {
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
            onCloseRef.current();
            // Deliver AFTER the popover's focus restore has run, so a
            // composer's refocus of its textarea wins.
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
  }, [theme.qnop.mode]);

  return (
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
  );
}
