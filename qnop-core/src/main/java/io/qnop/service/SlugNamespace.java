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
package io.qnop.service;

import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The flat user⟷team slug namespace (issue #595, ADR-0048): a slug identifies exactly one principal
 * — user OR team — so an {@code @slug} mention token (issues #462/#596) can never be ambiguous.
 * Both slug services probe their candidates through this one helper; derivation stays in {@code
 * UserSlugs}/{@code TeamSlugs}, the namespace rule lives here and the two allocation paths cannot
 * drift apart.
 *
 * <p>The per-table unique indexes ({@code ux_qnop_user_slug_lower}, {@code ux_team_slug_lower})
 * backstop the check-then-save race only <em>within</em> a namespace; no index can guard the
 * cross-table window. {@link #lockAndCheckFree} therefore serializes on a transaction-scoped
 * advisory lock keyed by the lowercased candidate before probing, so two concurrent allocations of
 * the same slug — user vs. team included — cannot both succeed.
 */
@Service
public class SlugNamespace {

  private final UserRepository users;
  private final TeamRepository teams;

  public SlugNamespace(UserRepository users, TeamRepository teams) {
    this.users = users;
    this.teams = teams;
  }

  /**
   * Locks the candidate for the calling transaction and reports whether it is free in BOTH
   * namespaces, case-insensitively. {@code MANDATORY}: an advisory xact lock outside a transaction
   * would be released immediately and guard nothing — a non-transactional caller is a bug and must
   * fail loudly rather than allocate unguarded.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean lockAndCheckFree(String candidate) {
    users.acquireSlugAllocationLock(candidate.toLowerCase(Locale.ROOT));
    return !users.existsBySlugIgnoreCase(candidate) && !teams.existsBySlugIgnoreCase(candidate);
  }
}
