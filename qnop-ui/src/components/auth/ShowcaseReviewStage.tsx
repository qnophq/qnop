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
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { alpha, keyframes } from '@mui/material/styles';
import { CheckCheck, FileText } from 'lucide-react';
import { tokens } from '../../theme/tokens';

const float = keyframes`
  from { transform: translateY(0); }
  to   { transform: translateY(-9px); }
`;

/** A floating layer of the collage: rotation on the wrapper, bob on the child. */
function Floating({
  tilt,
  delay,
  sx,
  children,
}: {
  tilt: number;
  delay: number;
  sx?: object;
  children: React.ReactNode;
}) {
  return (
    <Box sx={{ transform: `rotate(${tilt}deg)`, ...sx }}>
      <Box
        sx={{
          animation: `${float} 5.5s ease-in-out ${delay}s infinite alternate`,
          '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
        }}
      >
        {children}
      </Box>
    </Box>
  );
}

/** An abstract text line of the miniature page. */
function TextLine({ width }: { width: number }) {
  return (
    <Box
      sx={{
        height: 5,
        width: `${width}%`,
        borderRadius: 2,
        bgcolor: tokens.light.borderStrong,
      }}
    />
  );
}

/** A line-precise highlight with its numbered annotation marker in the margin. */
function HighlightLine({ color, marker, width }: { color: string; marker: string; width: number }) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mr: -2.75 }}>
      <Box
        sx={{
          flex: 1,
          height: 12,
          borderRadius: '3px',
          bgcolor: alpha(color, 0.16),
          border: `1px solid ${alpha(color, 0.4)}`,
          display: 'flex',
          alignItems: 'center',
          px: 0.625,
        }}
      >
        <Box sx={{ height: 5, width: `${width}%`, borderRadius: 2, bgcolor: alpha(color, 0.55) }} />
      </Box>
      <Box
        sx={{
          width: 17,
          height: 17,
          borderRadius: '50%',
          flexShrink: 0,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          bgcolor: color,
          color: '#fff',
          fontSize: 10,
          fontWeight: 700,
          boxShadow: `0 0 0 3px ${alpha(color, 0.22)}`,
        }}
      >
        {marker}
      </Box>
    </Box>
  );
}

/**
 * The reviewed document — the hero of the collage: a recognisable page with a
 * title row, abstract text and two line-precise annotations whose numbered
 * markers sit in the margin, exactly like the real viewer's annotation layer.
 */
function DocumentReviewCard() {
  const blue = tokens.brand.blue;
  return (
    <Box
      sx={{
        width: 252,
        borderRadius: '14px',
        p: 2.25,
        pr: 4,
        bgcolor: tokens.light.surface,
        border: `1px solid ${tokens.light.border}`,
        boxShadow: '0 24px 64px -12px rgba(0,10,25,0.55)',
      }}
    >
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1.75 }}>
        <Box
          sx={{
            width: 28,
            height: 28,
            borderRadius: '8px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            bgcolor: alpha(blue, 0.12),
            color: tokens.brand.blueDeep,
            flexShrink: 0,
          }}
        >
          <FileText size={15} />
        </Box>
        <Typography sx={{ fontWeight: 800, fontSize: 13.5, color: tokens.light.fg, flex: 1 }}>
          Release notes
        </Typography>
        <Box
          sx={{
            px: 0.875,
            py: 0.25,
            borderRadius: 999,
            fontSize: 10,
            fontWeight: 700,
            bgcolor: alpha(blue, 0.12),
            color: tokens.brand.blueDeep,
          }}
        >
          v4
        </Box>
      </Stack>
      <Stack spacing={1}>
        <TextLine width={92} />
        <TextLine width={78} />
        <HighlightLine color={blue} marker="1" width={84} />
        <TextLine width={88} />
        <TextLine width={70} />
        <HighlightLine color={tokens.semantic.warning} marker="2" width={58} />
        <TextLine width={84} />
        <TextLine width={62} />
      </Stack>
    </Box>
  );
}

/**
 * A background document peeking out from behind the hero — smaller and
 * quieter (at most one unnumbered highlight) so it deepens the stack without
 * competing with the hero's annotation story.
 */
function BackgroundDocumentCard({
  title,
  version,
  width,
  highlight = false,
}: {
  title: string;
  version: string;
  width: number;
  highlight?: boolean;
}) {
  const blue = tokens.brand.blue;
  return (
    <Box
      sx={{
        width,
        borderRadius: '12px',
        p: 1.75,
        bgcolor: tokens.light.surface,
        border: `1px solid ${tokens.light.border}`,
        boxShadow: '0 18px 48px -12px rgba(0,10,25,0.5)',
      }}
    >
      <Stack direction="row" spacing={0.875} sx={{ alignItems: 'center', mb: 1.25 }}>
        <Box
          sx={{
            width: 22,
            height: 22,
            borderRadius: '7px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            bgcolor: alpha(blue, 0.12),
            color: tokens.brand.blueDeep,
            flexShrink: 0,
          }}
        >
          <FileText size={12} />
        </Box>
        <Typography sx={{ fontWeight: 800, fontSize: 12, color: tokens.light.fg, flex: 1 }}>
          {title}
        </Typography>
        <Box
          sx={{
            px: 0.75,
            py: 0.125,
            borderRadius: 999,
            fontSize: 9.5,
            fontWeight: 700,
            bgcolor: alpha(blue, 0.12),
            color: tokens.brand.blueDeep,
          }}
        >
          {version}
        </Box>
      </Stack>
      <Stack spacing={0.875}>
        <TextLine width={90} />
        <TextLine width={72} />
        {highlight && (
          <Box
            sx={{
              height: 10,
              width: '80%',
              borderRadius: '3px',
              bgcolor: alpha(blue, 0.14),
              border: `1px solid ${alpha(blue, 0.32)}`,
            }}
          />
        )}
        <TextLine width={84} />
        <TextLine width={58} />
      </Stack>
    </Box>
  );
}

/**
 * The reviewer at work: the annotation thread on marker 1 — avatar, name, the
 * Reviewer role and an abstract comment, with the resolve affordance.
 */
function ReviewerThreadCard() {
  const blue = tokens.brand.blue;
  return (
    <Box
      sx={{
        width: 212,
        borderRadius: '12px',
        p: 1.75,
        bgcolor: tokens.light.surface,
        border: `1px solid ${alpha(blue, 0.35)}`,
        boxShadow: '0 20px 56px -12px rgba(0,10,25,0.55)',
      }}
    >
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
        <Box
          sx={{
            width: 30,
            height: 30,
            borderRadius: '50%',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            bgcolor: tokens.avatarPalette[5],
            color: '#fff',
            fontWeight: 700,
            fontSize: 12,
            flexShrink: 0,
          }}
        >
          MN
        </Box>
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography sx={{ fontWeight: 700, fontSize: 12.5, color: tokens.light.fg }}>
            Mira Novak
          </Typography>
        </Box>
        <Box
          sx={{
            px: 0.875,
            py: 0.25,
            borderRadius: 999,
            fontSize: 10,
            fontWeight: 700,
            bgcolor: alpha(blue, 0.12),
            color: tokens.brand.blueDeep,
          }}
        >
          Reviewer
        </Box>
      </Stack>
      <Stack spacing={0.75} sx={{ mt: 1.25, mb: 1.25 }}>
        <Box
          sx={{ height: 5, width: '92%', borderRadius: 2, bgcolor: tokens.light.borderStrong }}
        />
        <Box
          sx={{ height: 5, width: '64%', borderRadius: 2, bgcolor: tokens.light.borderStrong }}
        />
      </Stack>
      <Stack
        direction="row"
        spacing={0.5}
        sx={{
          alignItems: 'center',
          width: 'fit-content',
          px: 1,
          py: 0.375,
          borderRadius: 999,
          bgcolor: tokens.badge.green.bg,
          border: `1px solid ${tokens.badge.green.border}`,
        }}
      >
        <CheckCheck size={12} style={{ color: tokens.semantic.success }} />
        <Typography sx={{ fontSize: 11, fontWeight: 700, color: tokens.badge.green.fg }}>
          Resolve
        </Typography>
      </Stack>
    </Box>
  );
}

/**
 * Decorative product showcase for the auth brand panel (#503, review-focused
 * rework): the reviewed document is the hero — a page with two line-precise
 * annotations and their margin markers — while the user appears as the
 * reviewer working annotation 1's thread. A resolved-annotation toast keeps
 * the gamified tone. Purely presentational:
 * static markup, no data, no hooks; hidden from assistive tech. The panel
 * behind it is always dark, so the cards use fixed light-surface tokens.
 */
export function ShowcaseReviewStage() {
  return (
    <Box aria-hidden sx={{ position: 'relative', width: 330, pointerEvents: 'none' }}>
      {/* Two more documents deepen the stack behind the hero */}
      <Floating tilt={7} delay={3.4} sx={{ position: 'absolute', top: -34, right: -76, zIndex: 0 }}>
        <BackgroundDocumentCard title="Audit report" version="v1" width={182} />
      </Floating>
      <Floating tilt={-9} delay={2.6} sx={{ position: 'absolute', top: -42, left: -66, zIndex: 0 }}>
        <BackgroundDocumentCard title="Master agreement" version="v2" width={206} highlight />
      </Floating>

      {/* The reviewed document — the hero */}
      <Floating tilt={-2.5} delay={0} sx={{ position: 'relative', zIndex: 1 }}>
        <DocumentReviewCard />
      </Floating>

      {/* The reviewer's annotation thread, overlapping the page */}
      <Floating
        tilt={2.5}
        delay={1.4}
        sx={{ position: 'absolute', right: -72, bottom: -52, zIndex: 2 }}
      >
        <ReviewerThreadCard />
      </Floating>

      {/* Resolved toast */}
      <Floating
        tilt={-1.5}
        delay={1.8}
        sx={{ position: 'absolute', bottom: -46, left: -42, zIndex: 2 }}
      >
        <Stack
          direction="row"
          spacing={1}
          sx={{
            alignItems: 'center',
            px: 1.5,
            py: 1,
            borderRadius: '10px',
            bgcolor: tokens.light.surface,
            border: `1px solid ${tokens.badge.green.border}`,
            boxShadow: '0 16px 40px -10px rgba(0,10,25,0.5)',
          }}
        >
          <Box
            sx={{
              width: 26,
              height: 26,
              borderRadius: '8px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              bgcolor: tokens.badge.green.bg,
            }}
          >
            <CheckCheck size={15} style={{ color: tokens.semantic.success }} />
          </Box>
          <Box>
            <Typography sx={{ fontSize: 12.5, fontWeight: 700, color: tokens.light.fg }}>
              Annotation resolved
            </Typography>
            <Typography sx={{ fontSize: 10.5, color: tokens.light.fg3 }}>
              Release notes v4 · just now
            </Typography>
          </Box>
        </Stack>
      </Floating>
    </Box>
  );
}
