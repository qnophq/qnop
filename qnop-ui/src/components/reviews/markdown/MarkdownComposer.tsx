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
  useMemo,
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
import MenuItem from '@mui/material/MenuItem';
import MenuList from '@mui/material/MenuList';
import Paper from '@mui/material/Paper';
import Popper from '@mui/material/Popper';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { alpha, useTheme, type Theme } from '@mui/material/styles';
import { Maximize2, Minimize2, Paperclip } from 'lucide-react';
import { isSubmitShortcut } from '../../../utils/platform';
import { applyMarkdownAction, type MarkdownAction } from './markdownFormatting';
import { EmojiPickerButton } from './EmojiPickerButton';
import { Markdown } from './Markdown';
import { MarkdownHint } from './MarkdownHint';
import { MarkdownToolbar } from './MarkdownToolbar';
import { activeMentionQuery, mentionToken, type MentionCandidate } from './mentionToken';
import type { UploadedAttachment } from './useCommentAttachmentUpload';

/** How many roster matches the @-picker offers at once. */
const MENTION_LIMIT = 6;

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
  /**
   * The document roster the {@code @}-picker offers (issue #462). Absent or empty disables the
   * picker entirely — which is exactly how anonymous reviews opt out (the host passes no candidates),
   * so no roster ever surfaces where identities are hidden.
   */
  mentionCandidates?: MentionCandidate[];
  /**
   * Offered by hosts that can stage the composer full screen (issue #403
   * follow-up): renders the expand/collapse affordance in the mode row.
   */
  onToggleFullscreen?: () => void;
  /** True on the full-screen instance — flips the affordance to "exit". */
  fullscreen?: boolean;
  /** Larger reading type for the full-screen stage. */
  roomy?: boolean;
  /**
   * Editor mode for the full-screen stage (issue #403 follow-up): no frame,
   * the writing area fills all available height, and the footer becomes the
   * stage's bottom-fixed action bar. Implies the larger reading type.
   */
  bare?: boolean;
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
  onToggleFullscreen,
  fullscreen = false,
  roomy = false,
  bare = false,
  actions,
  mentionCandidates,
}: MarkdownComposerProps) {
  const theme = useTheme();
  // The full-screen stage reads larger; both the input and the preview's
  // row-derived heights follow the same metrics.
  const fontSize = roomy || bare ? 16 : 14;
  const lineHeight = roomy || bare ? 1.65 : 1.55;
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  // Selection for the controlled-update fallback, applied after the re-render.
  const pendingSelection = useRef<{ start: number; end: number } | null>(null);
  // Drag depth — dragenter/dragleave fire per child, so a plain boolean flickers.
  const dragDepth = useRef(0);
  const [dragActive, setDragActive] = useState(false);
  // The active @-mention query (issue #462): its text + the index of the `@`, or null when the caret
  // is not in a mention. `mentionIndex` is the highlighted match for keyboard selection.
  const [mention, setMention] = useState<{ query: string; start: number } | null>(null);
  const [mentionIndex, setMentionIndex] = useState(0);
  // The textarea node the picker anchors to — captured on interaction, never read from the ref
  // during render.
  const [mentionAnchor, setMentionAnchor] = useState<HTMLElement | null>(null);
  const mentionMatches = useMemo(() => {
    if (!mention || !mentionCandidates?.length) return [];
    const needle = mention.query.toLowerCase();
    return mentionCandidates
      .filter((candidate) => candidate.name.toLowerCase().includes(needle))
      .slice(0, MENTION_LIMIT);
  }, [mention, mentionCandidates]);
  const mentionOpen = mentionMatches.length > 0;
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

  /** Recompute the active @-mention query from the caret; skipped when no roster is offered. */
  const refreshMention = () => {
    const el = inputRef.current;
    if (!el || !mentionCandidates?.length) {
      setMention(null);
      return;
    }
    setMentionAnchor(el);
    setMention(activeMentionQuery(el.value, el.selectionStart ?? el.value.length));
  };

  /** Replaces the active `@query` with the canonical mention token and a trailing space. */
  const insertMentionCandidate = (candidate: MentionCandidate) => {
    const el = inputRef.current;
    if (!el || !mention) return;
    const token = `${mentionToken(candidate)} `;
    const end = el.selectionStart ?? el.value.length; // caret sits at the end of the @query
    const caret = mention.start + token.length;
    splice(
      mention.start,
      end,
      token,
      caret,
      caret,
      el.value.slice(0, mention.start) + token + el.value.slice(end),
    );
    setMention(null);
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
    // While the @-picker is open it owns the arrows, Enter/Tab (select) and Escape (issue #462),
    // ahead of submit and formatting shortcuts.
    if (mentionOpen) {
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        setMentionIndex((index) => (index + 1) % mentionMatches.length);
        return;
      }
      if (event.key === 'ArrowUp') {
        event.preventDefault();
        setMentionIndex((index) => (index - 1 + mentionMatches.length) % mentionMatches.length);
        return;
      }
      if (event.key === 'Enter' || event.key === 'Tab') {
        event.preventDefault();
        insertMentionCandidate(mentionMatches[Math.min(mentionIndex, mentionMatches.length - 1)]);
        return;
      }
      if (event.key === 'Escape') {
        event.preventDefault();
        setMention(null);
        return;
      }
    }
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
        bgcolor: 'background.paper',
        // The stage IS the surface (issue #403): no frame, fill the host.
        ...(bare
          ? { height: '100%', display: 'flex', flexDirection: 'column', minHeight: 0 }
          : {
              border: '1px solid',
              borderColor: 'divider',
              borderRadius: '10px',
              transition: 'border-color 120ms ease, box-shadow 120ms ease',
              '&:focus-within': {
                borderColor: theme.qnop.brand.blue,
                boxShadow: `0 0 0 2px ${alpha(theme.qnop.brand.blue, 0.18)}`,
              },
            }),
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
        <Stack direction="row" spacing={0.25} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
          {!previewing && <MarkdownToolbar onAction={handleAction} disabled={disabled} />}
          {onToggleFullscreen && (
            <Tooltip title={fullscreen ? 'Exit full screen' : 'Full screen'}>
              <IconButton
                size="small"
                aria-label={fullscreen ? 'Exit full screen' : 'Full screen'}
                onClick={onToggleFullscreen}
              >
                {fullscreen ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
              </IconButton>
            </Tooltip>
          )}
        </Stack>
      </Stack>
      {previewing ? (
        <Box
          data-testid="composer-preview"
          sx={
            bare
              ? { flex: 1, minHeight: 0, overflowY: 'auto', px: { xs: 2.5, md: 4 }, py: 2.5 }
              : {
                  px: 1.5,
                  py: 1.25,
                  minHeight: Math.round(minRows * fontSize * lineHeight) + 20,
                  maxHeight: Math.round(maxRows * fontSize * lineHeight) + 20,
                  overflowY: 'auto',
                }
          }
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
          onChange={(event) => {
            onChange(event.target.value);
            setMentionIndex(0);
            refreshMention();
          }}
          onKeyUp={refreshMention}
          onClick={refreshMention}
          onBlur={() => setMention(null)}
          onKeyDown={handleKeyDown}
          onPaste={handlePaste}
          inputProps={{ maxLength: BODY_MAX_LENGTH, 'aria-label': inputAriaLabel }}
          sx={
            bare
              ? {
                  flex: 1,
                  minHeight: 0,
                  alignItems: 'stretch',
                  px: { xs: 2.5, md: 4 },
                  py: 2.5,
                  fontSize,
                  lineHeight,
                  // The autosizing textarea would grow the page; on the stage
                  // it IS the scroll container instead.
                  '& textarea': { height: '100% !important', overflowY: 'auto !important' },
                }
              : { px: 1.5, py: 1.25, fontSize, lineHeight }
          }
        />
      )}
      {/* @-mention picker (issue #462): the document roster, filtered by the active @query and
          driven by the keyboard in handleKeyDown. onMouseDown selects without blurring the field. */}
      <Popper
        open={mentionOpen && mentionAnchor !== null}
        anchorEl={mentionAnchor}
        placement="bottom-start"
        style={{ zIndex: theme.zIndex.modal }}
      >
        <Paper elevation={6} sx={{ mt: 0.5, minWidth: 220, maxHeight: 240, overflowY: 'auto' }}>
          <MenuList dense aria-label="Mention a participant">
            {mentionMatches.map((candidate, index) => (
              <MenuItem
                key={candidate.id}
                data-testid="mention-option"
                selected={index === Math.min(mentionIndex, mentionMatches.length - 1)}
                onMouseDown={(event) => {
                  event.preventDefault();
                  insertMentionCandidate(candidate);
                }}
              >
                <Typography noWrap sx={{ fontSize: '0.85rem' }}>
                  {candidate.name}
                </Typography>
              </MenuItem>
            ))}
          </MenuList>
        </Paper>
      </Popper>
      <Stack
        direction="row"
        spacing={1}
        sx={
          bare
            ? {
                px: 2,
                py: 1,
                justifyContent: 'space-between',
                alignItems: 'center',
                borderTop: '1px solid',
                borderColor: 'divider',
                flexWrap: 'wrap',
                gap: 0.5,
              }
            : { px: 0.75, pb: 0.75, justifyContent: 'space-between', alignItems: 'center' }
        }
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
