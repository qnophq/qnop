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

import { useSyncExternalStore } from 'react';

/**
 * The runtime mention-extension contract (issue #598, ADR-0039): how a loaded
 * extension teaches the Community UI about principals a mention token may
 * stand for beyond user profiles — a team, for example. This module is the
 * seed of the published `qnop-ui-spi` package; the in-process registry below
 * is what the ADR-0039 extension loader will feed once it exists. Without a
 * registered contributor every consumer behaves exactly as before.
 */
export interface MentionPrincipal {
  /** Stable principal id (not necessarily a user id). */
  id: string;
  /** Display name for pills, pickers and excerpts. */
  name: string;
  /** The slug the mention token carries — unique across namespaces (#595). */
  slug: string;
  /** Namespace discriminator, e.g. `team`. Never `user` — users stay core. */
  kind: string;
  /** Avatar image for the picker/pill; initials of `name` when absent. */
  avatarUrl?: string | null;
  /** Link target of the rendered pill; the pill is plain text when absent. */
  href?: string;
  /** Small picker annotation, e.g. "notifies 5 people". */
  hint?: string;
}

export interface MentionContributor {
  /** Unique id of the contributing extension (diagnostics, dedup). */
  id: string;
  /**
   * Roster candidates to offer in the composer picker for this review.
   * Synchronous by design: the contributor reads its own cache and calls
   * {@link notifyMentionContributionsChanged} when that cache fills.
   */
  candidatesFor(documentId: string): MentionPrincipal[];
  /**
   * The principal a slug stands for, or undefined when not this namespace.
   * Callers pass the slug as it appears in the text; match case-insensitively,
   * as slugs are (the server's parser lower-cases tokens before resolution).
   */
  resolve(slug: string): MentionPrincipal | undefined;
}

/** Snapshot semantics for useSyncExternalStore: the array is replaced, never mutated. */
let contributors: readonly MentionContributor[] = [];
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

/** Registers a contributor; returns its deregistration. */
export function registerMentionContributor(contributor: MentionContributor): () => void {
  contributors = [...contributors, contributor];
  emit();
  return () => {
    contributors = contributors.filter((candidate) => candidate !== contributor);
    emit();
  };
}

/**
 * A contributor whose asynchronous data arrived calls this so subscribed
 * components re-read {@link MentionContributor.candidatesFor}/`resolve`.
 */
export function notifyMentionContributionsChanged(): void {
  contributors = [...contributors];
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** The registered contributors; re-renders on (de)registration and data changes. */
export function useMentionContributors(): readonly MentionContributor[] {
  return useSyncExternalStore(subscribe, () => contributors);
}

/** First contributor answer for a slug — at most one exists (#595). */
export function resolveContributedMention(
  registered: readonly MentionContributor[],
  slug: string,
): MentionPrincipal | undefined {
  for (const contributor of registered) {
    const principal = contributor.resolve(slug);
    if (principal) return principal;
  }
  return undefined;
}
