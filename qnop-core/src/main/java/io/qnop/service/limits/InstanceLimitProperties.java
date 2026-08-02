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
package io.qnop.service.limits;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What this instance is allowed to hold (issue #673), overridable via {@code qnop.limits.*} or the
 * matching {@code QNOP_LIMITS_*} environment variables.
 *
 * <p><strong>Properties rather than application settings, deliberately.</strong> A quota is what
 * the operator of a deployment granted; an administrator inside it must not be able to raise their
 * own. Putting these in the settings registry would put them in a web form, and a limit that its
 * subject can edit is a suggestion. The same reasoning governs {@code
 * qnop.tracking.allow-private-host} (issue #666) — some decisions may only be made by someone with
 * deployment access.
 *
 * <p>Every limit defaults to {@code 0}, meaning unlimited: a Community deployment behaves exactly
 * as it did before this existed, and a value only ever appears where somebody put one.
 *
 * <p>Limits bound what is <em>created</em>, never what exists. Lowering one below current usage
 * blocks the next record and leaves every present one alone — the alternative would be a deployment
 * that silently disables accounts an operator is still paying attention to.
 *
 * @param maxUsers accounts on this instance, disabled ones included (issue #687): a seat is a
 *     record, so the ceiling cannot be walked around by deactivating somebody
 * @param maxTeams teams on this instance
 * @param maxTeamMembers members of any single team
 * @param maxActiveReviews reviews still being worked on — not closed and not archived. Said as a
 *     negative on purpose: the workflow state is an extensible string (ADR-0011), so listing the
 *     open states would count an enterprise state, such as a review awaiting signature, as
 *     finished. Finished work occupies no seat, so a tenant with fifty finalized reviews still has
 *     their whole quota.
 */
@ConfigurationProperties(prefix = "qnop.limits")
public record InstanceLimitProperties(
    int maxUsers, int maxTeams, int maxTeamMembers, int maxActiveReviews) {

  /** A limit that is absent, zero or negative means "no limit" rather than "nothing allowed". */
  public InstanceLimitProperties {
    maxUsers = Math.max(0, maxUsers);
    maxTeams = Math.max(0, maxTeams);
    maxTeamMembers = Math.max(0, maxTeamMembers);
    maxActiveReviews = Math.max(0, maxActiveReviews);
  }

  /** The documented defaults — for direct construction in tests and non-Spring callers. */
  public static InstanceLimitProperties unlimited() {
    return new InstanceLimitProperties(0, 0, 0, 0);
  }
}
