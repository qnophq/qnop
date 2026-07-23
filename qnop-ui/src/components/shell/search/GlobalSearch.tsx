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
import ClickAwayListener from '@mui/material/ClickAwayListener';
import Grow from '@mui/material/Grow';
import InputBase from '@mui/material/InputBase';
import Paper from '@mui/material/Paper';
import Popper from '@mui/material/Popper';
import Skeleton from '@mui/material/Skeleton';
import Typography from '@mui/material/Typography';
import { alpha, useTheme } from '@mui/material/styles';
import { Search } from 'lucide-react';
import { useLocation, useNavigate } from 'react-router-dom';
import { SEARCH_MIN_LENGTH, useSearchQuick } from '../../../api/hooks/useSearch';
import { SearchDropdownResults } from './SearchDropdownResults';

/** Mirrors the admin lists' debounce (UsersPage) — one query per typing pause. */
const DEBOUNCE_MS = 300;

/**
 * The top-bar global search (issue #540, ADR-0047), replacing the #514
 * coming-soon trigger: a quiet pill that opens a grouped quickview of the top
 * hits — reviews (with their milestone track, the #568 gamified state
 * language), people and teams — each row a deep link, each group counted with
 * a "see all" continuation onto /search. Results are authorization-scoped
 * server-side; this component only renders what the caller may see.
 */
export function GlobalSearch() {
  const theme = useTheme();
  const navigate = useNavigate();
  const location = useLocation();
  const [value, setValue] = useState('');
  const [debounced, setDebounced] = useState('');
  const [open, setOpen] = useState(false);
  const [anchorEl, setAnchorEl] = useState<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const handle = setTimeout(() => setDebounced(value), DEBOUNCE_MS);
    return () => clearTimeout(handle);
  }, [value]);

  // Navigating away (a hit was clicked, or the user moved on) closes the
  // panel — the render-time derived-state pattern, as in FocusAnnotationCard.
  const locationKey = location.pathname + location.search;
  const [lastLocationKey, setLastLocationKey] = useState(locationKey);
  if (locationKey !== lastLocationKey) {
    setLastLocationKey(locationKey);
    setOpen(false);
  }

  const query = useSearchQuick(debounced);
  const trimmed = value.trim();
  const showPanel = open && trimmed.length > 0;
  const belowMinimum = trimmed.length < SEARCH_MIN_LENGTH;

  const close = () => setOpen(false);
  const submitToResultsPage = () => {
    if (trimmed.length >= SEARCH_MIN_LENGTH) {
      navigate(`/search?q=${encodeURIComponent(trimmed)}`);
    }
  };

  return (
    <ClickAwayListener onClickAway={close}>
      <Box sx={{ display: { xs: 'none', md: 'block' } }}>
        <Box
          ref={setAnchorEl}
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            width: 280,
            height: 34,
            px: 1.25,
            borderRadius: 1.5,
            border: 1,
            borderColor: showPanel ? 'primary.main' : 'divider',
            bgcolor: theme.qnop.surface2,
            color: 'text.secondary',
            transition: 'border-color .15s',
            '&:hover': { borderColor: 'text.disabled' },
            '&:focus-within': {
              borderColor: 'primary.main',
              boxShadow: `0 0 0 2px ${alpha(theme.palette.primary.main, 0.15)}`,
            },
          }}
        >
          <Search size={15} aria-hidden style={{ flexShrink: 0 }} />
          <InputBase
            inputRef={inputRef}
            value={value}
            onChange={(event) => {
              setValue(event.target.value);
              setOpen(true);
            }}
            onFocus={() => {
              if (value.trim().length > 0) setOpen(true);
            }}
            onKeyDown={(event) => {
              if (event.key === 'Escape') {
                close();
                inputRef.current?.blur();
              }
              if (event.key === 'Enter') {
                submitToResultsPage();
              }
            }}
            placeholder="Search…"
            inputProps={{ 'aria-label': 'Search reviews, people and teams' }}
            sx={{ flex: 1, fontSize: 13, color: 'text.primary' }}
          />
        </Box>

        <Popper
          open={showPanel}
          anchorEl={anchorEl}
          placement="bottom-end"
          transition
          sx={{ zIndex: theme.zIndex.appBar + 1 }}
        >
          {({ TransitionProps }) => (
            <Grow {...TransitionProps} timeout={140} style={{ transformOrigin: 'top right' }}>
              <Paper
                variant="outlined"
                role="dialog"
                aria-label="Search results"
                data-testid="global-search-dropdown"
                sx={{ mt: 1, width: 360, maxHeight: '70vh', overflowY: 'auto', borderRadius: 2.5 }}
              >
                {belowMinimum ? (
                  <Hint text="Keep typing — at least 2 characters." />
                ) : query.isPending ? (
                  <Box sx={{ p: 2 }} data-testid="global-search-loading">
                    {[0, 1, 2].map((row) => (
                      <Skeleton key={row} height={30} sx={{ my: 0.5 }} />
                    ))}
                  </Box>
                ) : query.isError ? (
                  <Hint text="Search is unavailable right now." />
                ) : query.data ? (
                  <SearchDropdownResults query={trimmed} data={query.data} />
                ) : null}
              </Paper>
            </Grow>
          )}
        </Popper>
      </Box>
    </ClickAwayListener>
  );
}

function Hint({ text }: { text: string }) {
  return (
    <Typography sx={{ p: 2, fontSize: 13, color: 'text.secondary', textAlign: 'center' }}>
      {text}
    </Typography>
  );
}
