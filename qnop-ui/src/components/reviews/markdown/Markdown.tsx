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

import { memo } from 'react';
import Box from '@mui/material/Box';
import { useTheme } from '@mui/material/styles';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize';
import { tokens } from '../../../theme/tokens';

/**
 * Only behaviour CSS cannot express: safe link/image attributes. Everything
 * visual is styled by the scoped `sx` below, so the sanitised elements keep the
 * design tokens. `node` is react-markdown's AST handle — dropped before it
 * reaches the DOM. Links open in a new tab with no referrer/opener leakage;
 * images never send a referrer and load lazily.
 */
const components: Components = {
  a: ({ node: _node, children, ...props }) => (
    <a {...props} target="_blank" rel="noopener noreferrer">
      {children}
    </a>
  ),
  img: ({ node: _node, ...props }) => (
    <img {...props} alt={props.alt ?? ''} loading="lazy" referrerPolicy="no-referrer" />
  ),
};

// GitHub-grade sanitisation (rehype-sanitize's default schema): full GFM incl.
// images, tables and task lists, but no <script>/<style>/event handlers and
// only http(s)/mailto links + http(s) image sources. react-markdown never
// parses raw HTML (no rehype-raw), so this is defence-in-depth on top of that.
const remarkPlugins = [remarkGfm];
const rehypePlugins = [[rehypeSanitize, defaultSchema]] as const;

interface MarkdownProps {
  children: string;
  /**
   * Height-limits the rendered block to roughly N lines for the mark's hover
   * preview (issue #427). A line clamp cannot span the multiple blocks Markdown
   * emits, so this caps the height and hides the overflow.
   */
  clampLines?: number;
}

/**
 * Renders a comment / opening-annotation body as sanitised Markdown (issue
 * #427). Bodies are stored as raw Markdown and rendered only here — react-
 * markdown builds a React element tree (never an HTML string, so no
 * `dangerouslySetInnerHTML`), and rehype-sanitize enforces a safe allowlist.
 * The scoped `sx` maps every element onto the design tokens with tight
 * in-bubble spacing, so a formatted note reads as one system in both themes.
 * Shared by the panel thread, the focus card and the mark's hover preview.
 */
function MarkdownBase({ children, clampLines }: MarkdownProps) {
  const theme = useTheme();
  return (
    <Box
      data-testid="markdown-body"
      sx={{
        fontSize: '0.875rem',
        lineHeight: 1.5,
        color: 'text.primary',
        overflowWrap: 'anywhere',
        wordBreak: 'break-word',
        ...(clampLines !== undefined && {
          maxHeight: `calc(${clampLines} * 1.5em)`,
          overflow: 'hidden',
        }),
        // Collapse the outer margins the block document leaves, so the bubble
        // keeps its own padding rhythm.
        '& > :first-of-type': { mt: 0 },
        '& > :last-child': { mb: 0 },
        '& p': { m: '0 0 0.5em' },
        '& ul, & ol': { m: '0 0 0.5em', pl: '1.5em' },
        '& li': { mb: '0.15em' },
        '& li > ul, & li > ol': { mt: '0.15em', mb: 0 },
        '& a': {
          color: theme.qnop.brand.blue,
          textDecoration: 'none',
          '&:hover': { textDecoration: 'underline' },
          '&:focus-visible': {
            outline: 'none',
            boxShadow: theme.qnop.focusRing,
            borderRadius: '2px',
          },
        },
        '& code': {
          fontFamily: tokens.font.mono,
          fontSize: '0.85em',
          bgcolor: theme.palette.action.hover,
          px: '0.35em',
          py: '0.1em',
          borderRadius: '4px',
        },
        '& pre': {
          m: '0 0 0.5em',
          p: 1,
          bgcolor: theme.palette.action.hover,
          borderRadius: '6px',
          overflowX: 'auto',
          fontSize: '0.85em',
          '& code': { bgcolor: 'transparent', p: 0, fontSize: 'inherit' },
        },
        // Reuse the annotation quotation recipe (AnnotationHead) so a Markdown
        // blockquote reads identically to an anchored quote.
        '& blockquote': {
          m: '0 0 0.5em',
          borderLeft: '3px solid',
          borderColor: 'divider',
          bgcolor: theme.qnop.surface2,
          borderRadius: '0 6px 6px 0',
          px: 1.25,
          py: 0.75,
          color: 'text.secondary',
          fontStyle: 'italic',
          '& > :last-child': { mb: 0 },
        },
        // Headings are capped small — a heading inside a comment must not
        // dominate the bubble.
        '& h1, & h2': {
          fontSize: '1.05rem',
          fontWeight: 600,
          m: '0.5em 0 0.25em',
          lineHeight: 1.3,
        },
        '& h3, & h4, & h5, & h6': { fontSize: '0.95rem', fontWeight: 600, m: '0.5em 0 0.25em' },
        '& hr': { border: 'none', borderTop: '1px solid', borderColor: 'divider', my: 1 },
        '& img': {
          maxWidth: '100%',
          height: 'auto',
          borderRadius: '6px',
          display: 'block',
          my: 0.5,
        },
        // Wide tables scroll inside the bubble rather than stretching it.
        '& table': {
          display: 'block',
          width: 'max-content',
          maxWidth: '100%',
          overflowX: 'auto',
          borderCollapse: 'collapse',
          m: '0 0 0.5em',
          fontSize: '0.85em',
        },
        '& th, & td': {
          border: '1px solid',
          borderColor: 'divider',
          px: 1,
          py: 0.5,
          textAlign: 'left',
        },
        '& th': { bgcolor: theme.qnop.surface2, fontWeight: 600 },
        '& del': { color: 'text.disabled' },
        '& input[type="checkbox"]': { mr: '0.4em' },
      }}
    >
      <ReactMarkdown
        remarkPlugins={remarkPlugins}
        rehypePlugins={rehypePlugins as never}
        components={components}
      >
        {children}
      </ReactMarkdown>
    </Box>
  );
}

/** Memoised: threads re-render on hover/selection, but a body only re-parses when its text changes. */
export const Markdown = memo(MarkdownBase);
