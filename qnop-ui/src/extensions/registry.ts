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

import { createContext, useContext, type ReactNode } from 'react';

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
  register<S extends ExtensionSlot>(slot: S, contribution: SlotContributionMap[S]): void;
  get<S extends ExtensionSlot>(slot: S): readonly SlotContributionMap[S][];
}

export function createExtensionRegistry(): ExtensionRegistry {
  const slots = new Map<ExtensionSlot, MessageRowContribution[]>();
  return {
    register(slot, contribution) {
      slots.set(slot, [...(slots.get(slot) ?? []), contribution]);
    },
    get(slot) {
      return slots.get(slot) ?? [];
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

/** The contributions registered into one slot; empty (and render-stable) without extensions. */
export function useExtensionSlot<S extends ExtensionSlot>(
  slot: S,
): readonly SlotContributionMap[S][] {
  return useContext(ExtensionRegistryContext).get(slot);
}
