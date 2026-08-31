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
package io.qnop.spi.mention;

import java.util.Set;
import java.util.UUID;

/**
 * Resolves one {@code @slug} mention token to the user ids it addresses (issue #598).
 *
 * <p>Every registered resolver sees every token; the core unions their answers, so a resolver
 * returns an empty set for a slug it does not own rather than guessing. Cross-namespace slug
 * uniqueness (issue #595) guarantees at most one principal per token, so two resolvers never
 * disagree about the same slug — they only ever cover different namespaces.
 *
 * <p>The core applies its own document-access rule to every id a resolver returns before a mention
 * is persisted or notified: a resolver cannot widen who may be mentioned, only name whom a token
 * stands for. It may apply a narrower rule of its own. What is persisted is always per-user {@code
 * comment_mention} rows, so the notification path (mail, opt-out, dedup) is unaware of how a token
 * was resolved.
 *
 * <p>Implementations must be safe to call from any thread and must not throw for an unknown slug.
 * Mentions are never resolved in anonymous reviews (ADR-0038); resolvers are not called there.
 */
@FunctionalInterface
public interface MentionResolver {

  /**
   * The user ids {@code slug} addresses in the given context, or an empty set when this resolver
   * does not know the slug. {@code slug} is passed as written (case not normalized); resolvers
   * match it case-insensitively, as slugs are.
   */
  Set<UUID> resolve(MentionContext context, String slug);
}
