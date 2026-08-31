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

import { useEffect, useSyncExternalStore } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { annotationKeys } from '../api/hooks/useAnnotations';
import { commentKeys } from '../api/hooks/useComments';

/**
 * The review live-channel slot (issue #602, ADR-0062): a registered contributor
 * gets a lifecycle window while a review surface is mounted — the place an SSE
 * client lives (qnop-ee#5) — plus an invalidation facade over the host's query
 * keys, so a push event becomes a normal authorized refetch without the
 * extension importing internals. Registered per-feature like the mention and
 * composer-mode registries; migrates to the generic slot registry at the
 * qnop-ui-spi cut.
 */
export interface LiveReviewContext {
  /** The mounted review. */
  documentId: string;
  /** Refetches the annotation list of this review (every cached version window). */
  invalidateAnnotations: () => void;
  /** Refetches one annotation's comment thread. */
  invalidateComments: (annotationId: string) => void;
}

export interface LiveChannelContributor {
  /** Unique id of the contributing extension (diagnostics, dedup). */
  id: string;
  /**
   * Called when a review surface mounts (and again when the user navigates to
   * another review). Returns the teardown — close the connection there.
   */
  onReviewMounted(context: LiveReviewContext): () => void;
}

let contributors: readonly LiveChannelContributor[] = [];
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Registers a contributor; returns its deregistration. Mounted surfaces react immediately. */
export function registerLiveChannelContributor(contributor: LiveChannelContributor): () => void {
  contributors = [...contributors, contributor];
  emit();
  return () => {
    contributors = contributors.filter((candidate) => candidate !== contributor);
    emit();
  };
}

/**
 * Host-side mount point: the review surface calls this with its document id.
 * Without registered contributors the effect is a no-op — community behaviour
 * is byte-identical.
 */
export function useLiveChannel(documentId: string | null | undefined): void {
  const queryClient = useQueryClient();
  // Subscribed, not snapshotted at mount: the ADR-0039 loader registers contributors AFTER the
  // first surfaces mounted (issue #602 review) — a late registration must reach an open review.
  const registered = useSyncExternalStore(subscribe, () => contributors);
  useEffect(() => {
    if (!documentId || registered.length === 0) {
      return;
    }
    const context: LiveReviewContext = {
      documentId,
      invalidateAnnotations: () =>
        void queryClient.invalidateQueries({ queryKey: annotationKeys.listPrefix(documentId) }),
      invalidateComments: (annotationId) =>
        void queryClient.invalidateQueries({ queryKey: commentKeys.list(annotationId) }),
    };
    const teardowns = registered.map((contributor) => {
      try {
        return contributor.onReviewMounted(context);
      } catch {
        // A broken contributor costs itself, never the review surface.
        return () => {};
      }
    });
    return () => {
      for (const teardown of teardowns) {
        try {
          teardown();
        } catch {
          // Teardown failures are the contributor's problem, not the page's.
        }
      }
    };
  }, [documentId, queryClient, registered]);
}
