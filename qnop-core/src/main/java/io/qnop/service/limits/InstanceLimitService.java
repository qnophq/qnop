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

import io.qnop.repository.DocumentRepository;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces this deployment's quotas (issue #673).
 *
 * <p>Every {@code require…} method is called immediately before the record it guards is written,
 * and refuses by throwing rather than returning a flag — a caller cannot forget to check a return
 * value that does not exist.
 *
 * <p>The counting is deliberately live rather than cached. These are rare operations (creating a
 * user, a team, a review), the counts are indexed, and a stale number here would either refuse
 * somebody who has room or admit somebody who does not.
 *
 * <p><strong>Not a race-free guarantee.</strong> Two administrators creating the last permitted
 * user at the same moment can both pass the check. Closing that would mean serialising every
 * creation against a lock, which is a real cost on every request to prevent an overshoot of one on
 * an operation nobody performs twice a second. Quotas here are a commercial boundary, not a safety
 * interlock; ADR-0057 records that choice rather than leaving it to be discovered.
 */
@Service
public class InstanceLimitService {

  private final InstanceLimitProperties limits;
  private final UserRepository users;
  private final TeamRepository teams;
  private final TeamMembershipRepository memberships;
  private final DocumentRepository documents;

  public InstanceLimitService(
      InstanceLimitProperties limits,
      UserRepository users,
      TeamRepository teams,
      TeamMembershipRepository memberships,
      DocumentRepository documents) {
    this.limits = limits;
    this.users = users;
    this.teams = teams;
    this.memberships = memberships;
    this.documents = documents;
  }

  /**
   * @throws InstanceLimitExceededException if this instance may hold no further user account
   */
  @Transactional(readOnly = true)
  public void requireUserCapacity() {
    require(InstanceLimit.USERS, limits.maxUsers(), this::userCount);
  }

  /**
   * @throws InstanceLimitExceededException if this instance may hold no further team
   */
  @Transactional(readOnly = true)
  public void requireTeamCapacity() {
    require(InstanceLimit.TEAMS, limits.maxTeams(), teams::count);
  }

  /**
   * @throws InstanceLimitExceededException if {@code teamId} may take no further member
   */
  @Transactional(readOnly = true)
  public void requireTeamMemberCapacity(UUID teamId) {
    require(
        InstanceLimit.TEAM_MEMBERS,
        limits.maxTeamMembers(),
        () -> memberships.countByTeamId(teamId));
  }

  /**
   * @throws InstanceLimitExceededException if this instance may hold no further active review
   */
  @Transactional(readOnly = true)
  public void requireActiveReviewCapacity() {
    require(InstanceLimit.ACTIVE_REVIEWS, limits.maxActiveReviews(), this::activeReviewCount);
  }

  /** Every quota with what it currently holds — the administration view (issue #673). */
  @Transactional(readOnly = true)
  public InstanceLimitUsage usage() {
    return new InstanceLimitUsage(
        new InstanceLimitUsage.Quota(userCount(), limits.maxUsers()),
        new InstanceLimitUsage.Quota(teams.count(), limits.maxTeams()),
        new InstanceLimitUsage.Quota(largestTeamSize(), limits.maxTeamMembers()),
        new InstanceLimitUsage.Quota(activeReviewCount(), limits.maxActiveReviews()));
  }

  private void require(InstanceLimit limit, int maximum, java.util.function.LongSupplier count) {
    if (maximum <= 0) {
      // Unlimited — and the count is not even asked for, so an unconfigured
      // deployment pays nothing for a feature it does not use.
      return;
    }
    if (count.getAsLong() >= maximum) {
      throw new InstanceLimitExceededException(limit, maximum);
    }
  }

  private long userCount() {
    return users.countByEnabledTrue();
  }

  /**
   * Reviews still being worked on: not closed, not archived.
   *
   * <p>Asked as a negative on purpose. {@code workflow_state} is an extensible string (ADR-0011),
   * so naming the open states would have made every enterprise state count as finished — a review
   * awaiting signature would have freed a seat it is still using.
   */
  private long activeReviewCount() {
    return documents.countByArchivedAtIsNullAndClosedAtIsNull();
  }

  /**
   * The fullest single team, since the per-team limit has no instance-wide number.
   *
   * <p>Reported rather than summed: "the largest team has 9 of 10 members" is the sentence that
   * tells an administrator whether the next invitation will land.
   */
  private long largestTeamSize() {
    List<Long> largest = memberships.memberCountsLargestFirst(PageRequest.of(0, 1));
    return largest.isEmpty() ? 0L : largest.get(0);
  }
}
