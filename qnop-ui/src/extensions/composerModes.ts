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

import { useSyncExternalStore, type ComponentType, type RefObject } from 'react';
import type { MentionCandidate } from '../components/reviews/markdown/mentionToken';
import type { UploadedAttachment } from '../components/reviews/markdown/useCommentAttachmentUpload';

/**
 * The composer-mode extension point (issue #599) of the runtime UI extension
 * model (ADR-0039): a registered contribution adds a tab to the composer's
 * mode strip and renders its own editing surface in place of the Markdown
 * textarea while active.
 *
 * The contract is the one the textarea already honours. `value` is raw
 * Markdown and stays the single source of truth — a surface may present it
 * any way it likes, but what it hands back through `onChange` is what gets
 * stored, so the storage format cannot change from here. The Community bundle
 * ships no mode; it only holds the seam. These types are the future
 * `qnop-ui-spi` shape for this slot and move there with the loader.
 */
export interface ComposerModeSurfaceProps {
  /** The draft, as raw Markdown. */
  value: string;
  /** Replaces the draft — with raw Markdown, nothing else. */
  onChange: (value: string) => void;
  /**
   * The platform submit chord ({@link isSubmitShortcut}) belongs to the
   * surface while it is active; it calls this and the host guards validity.
   */
  onSubmit?: () => void;
  disabled: boolean;
  placeholder: string;
  /** The accessible name the host gave the writing surface. */
  inputAriaLabel: string;
  minRows: number;
  maxRows: number;
  /** True on the full-screen stage. */
  fullscreen: boolean;
  /** The stage's frameless, fill-the-host layout (see MarkdownComposer). */
  bare: boolean;
  /** The document roster for @-mentions; absent where identities are hidden. */
  mentionCandidates?: MentionCandidate[];
  /**
   * Uploads a file and resolves to its Markdown reference. Absent when the
   * host offers no attachments. The composer's own attach button, drop zone
   * and clipboard paste keep working on top of the surface through
   * {@link ComposerModeHandle}; a surface may also call this itself.
   */
  onUploadAttachment?: (file: File) => Promise<UploadedAttachment>;
  /**
   * Where the surface publishes its caret-level operations, so the composer's
   * shared affordances (emoji picker, attachments) land in the surface rather
   * than at the end of the text. A surface that does not set it still works —
   * the composer then appends.
   */
  handleRef: RefObject<ComposerModeHandle | null>;
}

/** What the composer needs from a surface to keep its shared affordances working. */
export interface ComposerModeHandle {
  /** Inserts Markdown at the caret (an emoji, an upload placeholder). */
  insertText: (text: string) => void;
  /** Replaces the first occurrence of `search` (an upload placeholder resolving). */
  replaceText: (search: string, replacement: string) => void;
}

export interface ComposerModeContribution {
  /** Stable, unique; `write` and `preview` are the composer's own. */
  id: string;
  /** The tab label. */
  label: string;
  /** The editing surface rendered while the mode is active. */
  Surface: ComponentType<ComposerModeSurfaceProps>;
  /**
   * A surface that shows the rendered result as you type has no use for the
   * Preview tab; the active mode owns its visibility.
   */
  hidesPreview?: boolean;
}

const RESERVED = new Set(['write', 'preview']);

let modes: readonly ComposerModeContribution[] = [];
const listeners = new Set<() => void>();

function publish(next: readonly ComposerModeContribution[]) {
  modes = next;
  for (const listener of listeners) listener();
}

/** Registers a mode; returns the matching unregister. Re-registering an id replaces it. */
export function registerComposerMode(contribution: ComposerModeContribution): () => void {
  if (RESERVED.has(contribution.id)) {
    throw new Error(`Composer mode id "${contribution.id}" is reserved`);
  }
  publish([...modes.filter((mode) => mode.id !== contribution.id), contribution]);
  return () => unregisterComposerMode(contribution.id);
}

export function unregisterComposerMode(id: string): void {
  if (modes.some((mode) => mode.id === id)) publish(modes.filter((mode) => mode.id !== id));
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

const getSnapshot = () => modes;

/** The registered modes, in registration order; re-renders on registry changes. */
export function useComposerModes(): readonly ComposerModeContribution[] {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}
