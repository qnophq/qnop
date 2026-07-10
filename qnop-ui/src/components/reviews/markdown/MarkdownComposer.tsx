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

import {
  useLayoutEffect,
  useRef,
  useState,
  type ClipboardEvent,
  type DragEvent,
  type KeyboardEvent,
  type ReactNode,
} from 'react';
import Box from '@mui/material/Box';
import ButtonBase from '@mui/material/ButtonBase';
import IconButton from '@mui/material/IconButton';
import InputBase from '@mui/material/InputBase';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { alpha, useTheme, type Theme } from '@mui/material/styles';
import { Paperclip } from 'lucide-react';
import { isSubmitShortcut } from '../../../utils/platform';
import { applyMarkdownAction, type MarkdownAction } from './markdownFormatting';
import { EmojiPickerButton } from './EmojiPickerButton';
import { Markdown } from './Markdown';
import { MarkdownHint } from './MarkdownHint';
import { MarkdownToolbar } from './MarkdownToolbar';
import type { UploadedAttachment } from './useCommentAttachmentUpload';

/** Matches the API's `CommentBody.body` maxLength. */
const BODY_MAX_LENGTH = 20000;

/** One segment of the Write | Preview switch — the active tab reads raised. */
function modeTabSx(theme: Theme, active: boolean) {
  return {
    px: 1,
    py: 0.25,
    borderRadius: '5px',
    fontSize: '0.75rem',
    fontWeight: active ? 600 : 500,
    color: active ? 'text.primary' : 'text.secondary',
    bgcolor: active ? 'background.paper' : 'transparent',
    boxShadow: active ? theme.shadows[1] : 'none',
    '&:focus-visible': { boxShadow: theme.qnop.focusRing },
  } as const;
}

/** ⌘/Ctrl + key → formatting action (the ⇧ variant handles strikethrough). */
const FORMAT_SHORTCUTS: Partial<Record<string, MarkdownAction>> = {
  b: 'bold',
  i: 'italic',
  e: 'code',
  k: 'link',
};

interface MarkdownComposerProps {
  value: string;
  onChange: (value: string) => void;
  /** Fired by the platform submit chord; the caller guards its own validity. */
  onSubmit?: () => void;
  placeholder?: string;
  /** The textarea's accessible name — kept caller-specific for the tests. */
  inputAriaLabel: string;
  minRows?: number;
  maxRows?: number;
  disabled?: boolean;
  /**
   * Uploads a local file and resolves to its Markdown reference (issue #446).
   * Its presence enables the attach button, drag & drop and clipboard paste;
   * rejections must be surfaced by the callback itself — the composer only
   * rolls its placeholder back.
   */
  onUploadAttachment?: (file: File) => Promise<UploadedAttachment>;
  /** Right side of the footer row — the send / create actions. */
  actions?: ReactNode;
}

/**
 * The shared Markdown writing surface of the review discussions (issue #445),
 * modelled on Slack's message box: a framed field with the formatting toolbar
 * on top, a roomy auto-growing textarea, and a footer row holding the emoji
 * picker + Markdown hint left and the caller's actions right. Formatting is
 * selection-aware ({@link applyMarkdownAction}) and spliced through
 * `insertText` where available, so the browser's undo stack survives; jsdom
 * and older engines fall back to a plain controlled update.
 */
export function MarkdownComposer({
  value,
  onChange,
  onSubmit,
  placeholder = 'Add a comment',
  inputAriaLabel,
  minRows = 3,
  maxRows = 12,
  disabled = false,
  onUploadAttachment,
  actions,
}: MarkdownComposerProps) {
  const theme = useTheme();
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  // Selection for the controlled-update fallback, applied after the re-render.
  const pendingSelection = useRef<{ start: number; end: number } | null>(null);
  // Drag depth — dragenter/dragleave fire per child, so a plain boolean flickers.
  const dragDepth = useRef(0);
  const [dragActive, setDragActive] = useState(false);
  // Write/Preview (issue #445 follow-up) — GitHub's comment-box anatomy.
  const [previewing, setPreviewing] = useState(false);

  const showWrite = () => {
    setPreviewing(false);
    // Back to writing means back to the caret.
    requestAnimationFrame(() => inputRef.current?.focus());
  };

  useLayoutEffect(() => {
    if (!pendingSelection.current || !inputRef.current) return;
    const { start, end } = pendingSelection.current;
    pendingSelection.current = null;
    inputRef.current.setSelectionRange(start, end);
  }, [value]);

  /** Replaces `[spanStart, spanEnd)` with `replacement`, keeping undo intact where possible. */
  const splice = (
    spanStart: number,
    spanEnd: number,
    replacement: string,
    selectionStart: number,
    selectionEnd: number,
    nextValue: string,
  ) => {
    const el = inputRef.current;
    if (!el) return;
    el.focus();
    el.setSelectionRange(spanStart, spanEnd);
    let inserted: boolean;
    try {
      // Deprecated but universally supported — the only way to keep the native
      // undo stack. Fires an input event, so React state follows.
      inserted = replacement.length > 0 && document.execCommand('insertText', false, replacement);
    } catch {
      inserted = false;
    }
    if (inserted) {
      el.setSelectionRange(selectionStart, selectionEnd);
    } else {
      pendingSelection.current = { start: selectionStart, end: selectionEnd };
      onChange(nextValue);
    }
  };

  const handleAction = (action: MarkdownAction) => {
    const el = inputRef.current;
    if (!el || disabled) return;
    const applied = applyMarkdownAction(
      { value: el.value, start: el.selectionStart ?? 0, end: el.selectionEnd ?? 0 },
      action,
    );
    splice(
      applied.spanStart,
      applied.spanEnd,
      applied.replacement,
      applied.selectionStart,
      applied.selectionEnd,
      applied.value,
    );
  };

  const insertEmoji = (emoji: string) => {
    const el = inputRef.current;
    if (!el || disabled) return;
    const start = el.selectionStart ?? el.value.length;
    const end = el.selectionEnd ?? start;
    const caret = start + emoji.length;
    splice(start, end, emoji, caret, caret, el.value.slice(0, start) + emoji + el.value.slice(end));
  };

  /** Replaces the first occurrence of `search` in the CURRENT field value. */
  const replaceOnce = (search: string, replacement: string) => {
    const el = inputRef.current;
    if (!el) return;
    const index = el.value.indexOf(search);
    if (index === -1) return;
    const caret = index + replacement.length;
    splice(
      index,
      index + search.length,
      replacement,
      caret,
      caret,
      el.value.slice(0, index) + replacement + el.value.slice(index + search.length),
    );
  };

  /**
   * GitHub's upload choreography (issue #446): a visible placeholder lands at
   * the caret immediately, then resolves into the real Markdown reference —
   * image syntax for images, a plain link for any other file — or disappears
   * when the upload fails (the callback surfaced the error).
   */
  const uploadOne = async (upload: (file: File) => Promise<UploadedAttachment>, file: File) => {
    const label = file.name || 'attachment';
    // The optimistic form follows the browser-declared type; the final form
    // follows the server-sniffed one.
    const placeholder = `${file.type.startsWith('image/') ? '!' : ''}[Uploading ${label}…]()`;
    const el = inputRef.current;
    if (!el) return;
    const start = el.selectionStart ?? el.value.length;
    const end = el.selectionEnd ?? start;
    const caret = start + placeholder.length;
    splice(
      start,
      end,
      placeholder,
      caret,
      caret,
      el.value.slice(0, start) + placeholder + el.value.slice(end),
    );
    try {
      const uploaded = await upload(file);
      const bang = uploaded.contentType.startsWith('image/') ? '!' : '';
      replaceOnce(placeholder, `${bang}[${uploaded.fileName}](${uploaded.url})`);
    } catch {
      replaceOnce(placeholder, '');
    }
  };

  const handleFiles = (files: FileList | File[]) => {
    if (!onUploadAttachment || disabled) return;
    for (const file of Array.from(files)) {
      void uploadOne(onUploadAttachment, file);
    }
  };

  const hasFilePayload = (event: DragEvent) =>
    Array.from(event.dataTransfer?.types ?? []).includes('Files');

  const handleDragEnter = (event: DragEvent) => {
    if (!onUploadAttachment || disabled || !hasFilePayload(event)) return;
    event.preventDefault();
    dragDepth.current += 1;
    setDragActive(true);
  };

  const handleDragOver = (event: DragEvent) => {
    if (!onUploadAttachment || disabled || !hasFilePayload(event)) return;
    event.preventDefault();
  };

  const handleDragLeave = (event: DragEvent) => {
    if (!onUploadAttachment || disabled || !hasFilePayload(event)) return;
    dragDepth.current = Math.max(0, dragDepth.current - 1);
    if (dragDepth.current === 0) setDragActive(false);
  };

  const handleDrop = (event: DragEvent) => {
    if (!onUploadAttachment || disabled || !hasFilePayload(event)) return;
    event.preventDefault();
    dragDepth.current = 0;
    setDragActive(false);
    handleFiles(event.dataTransfer.files);
  };

  const handlePaste = (event: ClipboardEvent) => {
    if (!onUploadAttachment || disabled) return;
    const files = event.clipboardData?.files;
    if (files && files.length > 0) {
      event.preventDefault();
      handleFiles(files);
    }
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement | HTMLInputElement>) => {
    if (isSubmitShortcut(event)) {
      event.preventDefault();
      onSubmit?.();
      return;
    }
    if (!event.metaKey && !event.ctrlKey) return;
    const key = event.key.toLowerCase();
    if (event.shiftKey && key === 'x') {
      event.preventDefault();
      handleAction('strikethrough');
      return;
    }
    const action = !event.shiftKey && FORMAT_SHORTCUTS[key];
    if (action) {
      event.preventDefault();
      handleAction(action);
    }
  };

  return (
    <Box
      data-testid="markdown-composer"
      onDragEnter={handleDragEnter}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
      sx={{
        position: 'relative',
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: '10px',
        bgcolor: 'background.paper',
        transition: 'border-color 120ms ease, box-shadow 120ms ease',
        '&:focus-within': {
          borderColor: theme.qnop.brand.blue,
          boxShadow: `0 0 0 2px ${alpha(theme.qnop.brand.blue, 0.18)}`,
        },
        '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
      }}
    >
      <Stack
        direction="row"
        sx={{
          px: 0.75,
          py: 0.5,
          borderBottom: '1px solid',
          borderColor: 'divider',
          alignItems: 'center',
          justifyContent: 'space-between',
          flexWrap: 'wrap',
          gap: 0.5,
        }}
      >
        {/* GitHub's Write | Preview switch, in qnop's segmented language. */}
        <Stack
          direction="row"
          spacing={0.25}
          sx={{
            p: '2px',
            borderRadius: '7px',
            bgcolor: theme.qnop.surface2,
            flexShrink: 0,
          }}
        >
          <ButtonBase
            onClick={showWrite}
            aria-pressed={!previewing}
            sx={modeTabSx(theme, !previewing)}
          >
            Write
          </ButtonBase>
          <ButtonBase
            onClick={() => setPreviewing(true)}
            aria-pressed={previewing}
            sx={modeTabSx(theme, previewing)}
          >
            Preview
          </ButtonBase>
        </Stack>
        {!previewing && <MarkdownToolbar onAction={handleAction} disabled={disabled} />}
      </Stack>
      {previewing ? (
        <Box
          data-testid="composer-preview"
          sx={{
            px: 1.5,
            py: 1.25,
            minHeight: Math.round(minRows * 14 * 1.55) + 20,
            maxHeight: Math.round(maxRows * 14 * 1.55) + 20,
            overflowY: 'auto',
          }}
        >
          {value.trim() ? (
            <Markdown>{value}</Markdown>
          ) : (
            <Typography variant="body2" sx={{ color: 'text.disabled', fontStyle: 'italic' }}>
              Nothing to preview.
            </Typography>
          )}
        </Box>
      ) : (
        <InputBase
          multiline
          fullWidth
          minRows={minRows}
          maxRows={maxRows}
          placeholder={placeholder}
          value={value}
          disabled={disabled}
          inputRef={inputRef}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={handleKeyDown}
          onPaste={handlePaste}
          inputProps={{ maxLength: BODY_MAX_LENGTH, 'aria-label': inputAriaLabel }}
          sx={{ px: 1.5, py: 1.25, fontSize: 14, lineHeight: 1.55 }}
        />
      )}
      <Stack
        direction="row"
        spacing={1}
        sx={{ px: 0.75, pb: 0.75, justifyContent: 'space-between', alignItems: 'center' }}
      >
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', pl: 0.25 }}>
          <EmojiPickerButton onSelect={insertEmoji} disabled={disabled || previewing} />
          {onUploadAttachment && (
            <>
              <input
                ref={fileInputRef}
                type="file"
                multiple
                hidden
                data-testid="composer-file-input"
                onChange={(event) => {
                  if (event.target.files) handleFiles(event.target.files);
                  event.target.value = '';
                }}
              />
              <Tooltip title="Attach file">
                <span>
                  <IconButton
                    size="small"
                    aria-label="Attach file"
                    disabled={disabled || previewing}
                    onClick={() => fileInputRef.current?.click()}
                    sx={{
                      width: 26,
                      height: 26,
                      borderRadius: '6px',
                      color: 'text.secondary',
                      '&:hover': { color: 'text.primary' },
                    }}
                  >
                    <Paperclip size={15} aria-hidden />
                  </IconButton>
                </span>
              </Tooltip>
            </>
          )}
          <MarkdownHint />
        </Stack>
        {actions && (
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            {actions}
          </Stack>
        )}
      </Stack>
      {dragActive && (
        <Stack
          data-testid="composer-drop-overlay"
          sx={{
            position: 'absolute',
            inset: 2,
            zIndex: 1,
            alignItems: 'center',
            justifyContent: 'center',
            borderRadius: '8px',
            border: '2px dashed',
            borderColor: theme.qnop.brand.blue,
            bgcolor: alpha(theme.qnop.brand.blue, 0.08),
            backdropFilter: 'blur(1px)',
            pointerEvents: 'none',
          }}
        >
          <Typography variant="body2" sx={{ fontWeight: 600, color: theme.qnop.brand.blue }}>
            Drop files to attach
          </Typography>
        </Stack>
      )}
    </Box>
  );
}
