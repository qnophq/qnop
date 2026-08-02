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

/**
 * What this deployment holds against what it may hold (issue #673).
 *
 * <p>Exists so an administrator learns their ceiling before they hit it. Being refused at the
 * moment of creating the twenty-sixth user tells you the limit is twenty-five, but only after the
 * work of getting there — and never how close you were the day before.
 *
 * @param users enabled accounts
 * @param teams teams on this instance
 * @param teamMembers the <em>fullest</em> team, since a per-team ceiling has no instance-wide total
 * @param activeReviews reviews still being worked on
 */
public record InstanceLimitUsage(Quota users, Quota teams, Quota teamMembers, Quota activeReviews) {

  /**
   * One quota.
   *
   * @param used what is in use now
   * @param maximum what is permitted; {@code 0} means unlimited, and {@link #unlimited()} says so
   *     without every caller having to remember the convention
   */
  public record Quota(long used, int maximum) {

    public boolean unlimited() {
      return maximum <= 0;
    }

    /** How many more may be created, or empty when there is no ceiling. */
    public java.util.OptionalLong remaining() {
      return unlimited()
          ? java.util.OptionalLong.empty()
          : java.util.OptionalLong.of(Math.max(0, maximum - used));
    }
  }
}
