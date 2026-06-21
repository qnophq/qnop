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

import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import Divider from '@mui/material/Divider';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';

const BADGE_TONES = ['blue', 'green', 'amber', 'red'] as const;
const BADGE_LABEL: Record<(typeof BADGE_TONES)[number], string> = {
  blue: 'In Prüfung',
  green: 'Freigegeben',
  amber: 'Änderungen',
  red: 'Konflikt',
};

/**
 * A compact gallery of the devtank42 design system (#101): the type scale,
 * button colours/variants, the brand badge tones, the avatar palette and the
 * monospace face. It is throwaway scaffolding for visual verification (light +
 * dark) and is removed once the real shell and surfaces land (#102+).
 */
export function ThemeShowcase() {
  const theme = useTheme();
  const isDark = theme.qnop.mode === 'dark';

  return (
    <Card aria-label="Theme showcase">
      <CardContent>
        <Typography variant="h6" gutterBottom>
          Design-System
        </Typography>

        <Stack spacing={2.5} sx={{ mt: 1 }}>
          {/* Type scale */}
          <Box>
            <Typography variant="h1">Heading 1</Typography>
            <Typography variant="h3">Heading 3</Typography>
            <Typography variant="body1">
              Body — Reviewer markieren Zeilen, kommentieren und geben frei.
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Body 2 / secondary — gestern aktualisiert · 3 offene Aufgaben
            </Typography>
            <Box component="span" sx={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 13 }}>
              Mono: § 3.4 · v2.4 · €4.200.000 · a3f2…9b1c
            </Box>
          </Box>

          <Divider />

          {/* Buttons */}
          <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 1, alignItems: 'center' }}>
            <Button variant="contained">Primär</Button>
            <Button variant="outlined">Sekundär</Button>
            <Button variant="text">Text</Button>
            <Button variant="contained" color="success">
              Freigeben
            </Button>
            <Button variant="outlined" color="error">
              Ablehnen
            </Button>
            <Button variant="contained" size="small">
              Klein
            </Button>
            <Button variant="contained" size="large">
              Groß
            </Button>
          </Stack>

          <Divider />

          {/* Brand badge tones */}
          <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 1 }}>
            {BADGE_TONES.map((tone) => {
              const t = theme.qnop.badge[tone];
              return (
                <Box
                  key={tone}
                  sx={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    height: 22,
                    px: 1,
                    borderRadius: 999,
                    fontSize: 11,
                    fontWeight: 500,
                    bgcolor: t.bg,
                    color: isDark ? t.fgDark : t.fg,
                    border: `1px solid ${t.border}`,
                  }}
                >
                  {BADGE_LABEL[tone]}
                </Box>
              );
            })}
          </Stack>

          {/* Avatar palette */}
          <Stack direction="row" spacing={-1}>
            {theme.qnop.avatarPalette.map((color, i) => (
              <Box
                key={color}
                sx={{
                  width: 28,
                  height: 28,
                  borderRadius: 999,
                  bgcolor: color,
                  color: '#fff',
                  fontSize: 11,
                  fontWeight: 600,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  border: `2px solid ${theme.palette.background.paper}`,
                }}
              >
                {String.fromCharCode(65 + i)}
              </Box>
            ))}
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  );
}
