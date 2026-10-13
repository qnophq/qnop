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
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import type { LucideIcon } from 'lucide-react';
import {
  Bold,
  Code,
  Heading,
  Image,
  Italic,
  Link,
  List,
  ListOrdered,
  SquareCode,
  Strikethrough,
  Table,
  TextQuote,
} from 'lucide-react';
import type { ParseKeys } from 'i18next';
import { useTranslation } from 'react-i18next';
import { isApplePlatform } from '../../../utils/platform';
import type { MarkdownAction } from './markdownFormatting';

interface ToolbarItem {
  action: MarkdownAction;
  /** The `workspace` catalog key of the button's name (ADR-0063). */
  labelKey: ParseKeys<'workspace'>;
  icon: LucideIcon;
  /** The shortcut key, spelled platform-aware in the tooltip. */
  shortcut?: string;
}

/**
 * Slack's formatting set (issue #445) extended by the document-review
 * extras — heading, image and table — grouped by intent: inline styles,
 * block structure, inserts, lists, code.
 */
const GROUPS: ToolbarItem[][] = [
  [
    { action: 'bold', labelKey: 'markdown.toolbar.bold', icon: Bold, shortcut: 'B' },
    { action: 'italic', labelKey: 'markdown.toolbar.italic', icon: Italic, shortcut: 'I' },
    {
      action: 'strikethrough',
      labelKey: 'markdown.toolbar.strikethrough',
      icon: Strikethrough,
      shortcut: '⇧X',
    },
  ],
  [
    { action: 'heading', labelKey: 'markdown.toolbar.heading', icon: Heading },
    { action: 'quote', labelKey: 'markdown.toolbar.quote', icon: TextQuote },
  ],
  [
    { action: 'link', labelKey: 'markdown.toolbar.link', icon: Link, shortcut: 'K' },
    { action: 'image', labelKey: 'markdown.toolbar.image', icon: Image },
    { action: 'table', labelKey: 'markdown.toolbar.table', icon: Table },
  ],
  [
    { action: 'orderedList', labelKey: 'markdown.toolbar.orderedList', icon: ListOrdered },
    { action: 'bulletList', labelKey: 'markdown.toolbar.bulletList', icon: List },
  ],
  [
    { action: 'code', labelKey: 'markdown.toolbar.code', icon: Code, shortcut: 'E' },
    { action: 'codeBlock', labelKey: 'markdown.toolbar.codeBlock', icon: SquareCode },
  ],
];

function shortcutLabel(shortcut: string): string {
  return isApplePlatform() ? `⌘${shortcut}` : `Ctrl+${shortcut.replace('⇧', 'Shift+')}`;
}

interface MarkdownToolbarProps {
  onAction: (action: MarkdownAction) => void;
  disabled?: boolean;
}

/**
 * The composer's formatting row (issue #445) — Slack's Markdown set as quiet
 * icon buttons. `onMouseDown` is swallowed so a click never steals the
 * textarea's focus or collapses its selection; the action itself fires on
 * click. Keyboard users reach every button by tab — the composer refocuses the
 * field after the action lands.
 */
export function MarkdownToolbar({ onAction, disabled = false }: MarkdownToolbarProps) {
  const { t } = useTranslation('workspace');
  return (
    <Stack
      direction="row"
      spacing={0.25}
      role="toolbar"
      aria-label={t('markdown.toolbar.label')}
      sx={{ alignItems: 'center', flexWrap: 'wrap' }}
    >
      {GROUPS.map((group, groupIndex) => (
        <Stack key={group[0].action} direction="row" spacing={0.25} sx={{ alignItems: 'center' }}>
          {groupIndex > 0 && (
            <Box
              aria-hidden
              sx={{ width: '1px', height: 16, bgcolor: 'divider', mx: 0.5, flexShrink: 0 }}
            />
          )}
          {group.map(({ action, labelKey, icon: Icon, shortcut }) => {
            const label = t(labelKey);
            return (
              <Tooltip
                key={action}
                title={
                  shortcut
                    ? t('markdown.toolbar.withShortcut', {
                        label,
                        shortcut: shortcutLabel(shortcut),
                      })
                    : label
                }
              >
                <span>
                  <IconButton
                    size="small"
                    aria-label={label}
                    disabled={disabled}
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => onAction(action)}
                    sx={{
                      width: 26,
                      height: 26,
                      borderRadius: '6px',
                      color: 'text.secondary',
                      '&:hover': { color: 'text.primary' },
                    }}
                  >
                    <Icon size={15} aria-hidden />
                  </IconButton>
                </span>
              </Tooltip>
            );
          })}
        </Stack>
      ))}
    </Stack>
  );
}
