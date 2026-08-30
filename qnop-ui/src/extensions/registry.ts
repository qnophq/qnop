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

import { createContext, useContext, useSyncExternalStore, type ReactNode } from 'react';

/**
 * The runtime UI extension registry (ADR-0039, first slots via issue #600).
 *
 * qnop-ui defines typed SLOTS; an extension bundle registers CONTRIBUTIONS
 * into them at load time. The community bundle ships no contributions — every
 * slot renders nothing until a registered module (loaded per ADR-0039's
 * import-map mechanism, or a test) adds one, so community DOM stays
 * byte-identical without extensions.
 *
 * The types here are the seed of the published `qnop-ui-spi` package; they
 * live inside the host bundle until the runtime loader lands and the package
 * is extracted (ADR-0039/0049).
 */

/**
 * What a message-row contribution gets to decide visibility and behaviour
 * (issue #600): enough context for a policy like "own + annotation not
 * resolved + review still open" without the community row hard-coding any.
 */
export interface MessageRowContext {
  /** The row's nature: an annotation head (the opening post) or a thread comment. */
  kind: 'annotation' | 'comment';
  annotationId: string;
  /**
   * The comment behind the row. On an annotation head this is the opening
   * comment — `null` until the thread cache holds it.
   */
  commentId: string | null;
  /** The author (a pseudonym token in anonymous reviews — see realAuthorId). */
  authorId: string;
  /** True when the viewer wrote this message. */
  own: boolean;
  /** The annotation's workflow status (OPEN/RESOLVED/DISMISSED); null when the row cannot know. */
  annotationStatus: string | null;
  /** The review's workflow state (ADR-0011); null while the document is not cached. */
  workflowState: string | null;
  /** False once the review is FINALIZED/CANCELLED — or unknown, which reads as closed. */
  reviewOpen: boolean;
  /** The message's raw Markdown body. */
  body: string;
}

/** One rendered contribution; `id` must be globally unique (it keys the React list). */
export interface MessageRowContribution {
  id: string;
  /**
   * Renders the contribution. Interactive output MUST carry an accessible
   * name (e.g. an `aria-label` on an icon button) — it stands beside the
   * host's labelled copy/permalink/reaction affordances and ships to screen
   * readers exactly like them.
   */
  render: (context: MessageRowContext) => ReactNode;
}

interface SlotContributionMap {
  /** Extra per-message actions (icon buttons) beside copy/permalink/reaction. */
  messageActions: MessageRowContribution;
  /** Markers beside the message timestamp (e.g. an "edited" badge). */
  messageBadges: MessageRowContribution;
}

export type ExtensionSlot = keyof SlotContributionMap;

export interface ExtensionRegistry {
  /** Adds a contribution; throws on a duplicate `id` within the slot. */
  register<S extends ExtensionSlot>(slot: S, contribution: SlotContributionMap[S]): void;
  /** The slot's contributions; a stable reference until the next `register`. */
  get<S extends ExtensionSlot>(slot: S): readonly SlotContributionMap[S][];
  /**
   * Notifies on every `register` — the extension loader (ADR-0039) resolves
   * its dynamic imports AFTER the first render, so already-mounted slots must
   * re-read. `useExtensionSlot` subscribes through this; returns the
   * unsubscribe.
   */
  subscribe(listener: () => void): () => void;
}

/** Shared empty snapshot — `get` must stay reference-stable between registrations. */
const NO_CONTRIBUTIONS: readonly MessageRowContribution[] = Object.freeze([]);

export function createExtensionRegistry(): ExtensionRegistry {
  const slots = new Map<ExtensionSlot, MessageRowContribution[]>();
  const listeners = new Set<() => void>();
  return {
    register(slot, contribution) {
      const existing = slots.get(slot) ?? [];
      if (existing.some((entry) => entry.id === contribution.id)) {
        throw new Error(`duplicate contribution id "${contribution.id}" in slot "${slot}"`);
      }
      // A fresh array per registration: `get` snapshots stay immutable, so
      // useSyncExternalStore sees a changed reference exactly when content changed.
      slots.set(slot, [...existing, contribution]);
      listeners.forEach((listener) => listener());
    },
    get(slot) {
      return slots.get(slot) ?? NO_CONTRIBUTIONS;
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

/**
 * The host application's registry — the one the runtime extension loader
 * (ADR-0039) will feed. Also the context default, so production code needs no
 * explicit provider.
 */
export const hostExtensions = createExtensionRegistry();

/** Internal: the context the provider and hook share; not part of the extension contract. */
export const ExtensionRegistryContext = createContext<ExtensionRegistry>(hostExtensions);

/**
 * The contributions registered into one slot; empty (and reference-stable)
 * without extensions. Subscribed: a contribution registered after mount —
 * the runtime loader's normal timing (ADR-0039) — re-renders the consumers.
 */
export function useExtensionSlot<S extends ExtensionSlot>(
  slot: S,
): readonly SlotContributionMap[S][] {
  const registry = useContext(ExtensionRegistryContext);
  return useSyncExternalStore(registry.subscribe, () => registry.get(slot));
}
