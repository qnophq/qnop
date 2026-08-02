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
package io.qnop.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.qnop.entity.User;
import io.qnop.entity.UserRole;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.UserRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The per-team quota against the way the admin dialog actually submits (issue #691).
 *
 * <p>It takes a multi-selection and fires one request per user with {@code Promise.allSettled}, so
 * every one of them read the same membership count and passed the check: a team at its ceiling
 * minus two accepted four people. This is not the "two administrators at the same instant" race
 * ADR-0057 accepts — one click produces the whole fleet, so it happened every time.
 */
@TestPropertySource(properties = {"qnop.limits.max-team-members=4"})
class TeamMemberQuotaConcurrencyIT extends SeededIntegrationTest {

  private static final int CEILING = 4;

  @Autowired private UserRepository users;
  @Autowired private TeamMembershipRepository memberships;

  @Test
  @DisplayName("a fleet of concurrent additions cannot push a team past its ceiling")
  void concurrentAdditionsRespectTheCeiling() throws Exception {
    UUID teamId = createTeam();
    long alreadyIn = memberships.countByTeamId(teamId);
    // More candidates than seats, submitted at once — the shape of the report.
    List<UUID> candidates = new ArrayList<>();
    for (int i = 0; i < CEILING + 3; i++) {
      candidates.add(newMember("bulk-" + i));
    }

    CountDownLatch startLine = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(candidates.size());
    try {
      List<Future<MvcResult>> futures = new ArrayList<>();
      for (UUID candidate : candidates) {
        futures.add(
            pool.submit(
                () -> {
                  startLine.await(5, TimeUnit.SECONDS);
                  return mockMvc
                      .perform(
                          post("/api/v1/admin/teams/" + teamId + "/members")
                              .header("Authorization", "Bearer " + token(ADMIN_ID))
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(
                                  "{\"userId\":\"" + candidate + "\",\"teamRole\":\"MEMBER\"}"))
                      .andReturn();
                }));
      }
      startLine.countDown();

      int created = 0;
      int refused = 0;
      for (Future<MvcResult> future : futures) {
        int status = future.get(30, TimeUnit.SECONDS).getResponse().getStatus();
        if (status == 201) {
          created++;
        } else if (status == 409) {
          refused++;
        }
      }

      // The assertion that would have caught the bug: the team, not the replies.
      assertThat(memberships.countByTeamId(teamId))
          .as("a team must never hold more members than its ceiling")
          .isLessThanOrEqualTo(CEILING);
      assertThat(created).isEqualTo(CEILING - (int) alreadyIn);
      assertThat(refused).isEqualTo(candidates.size() - created);
    } finally {
      pool.shutdownNow();
    }
  }

  /** A team with its lead, which already occupies one seat. */
  private UUID createTeam() throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/admin/teams")
                    .header("Authorization", "Bearer " + token(ADMIN_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Quota race\",\"leadUserId\":\"" + MEMBER_ID + "\"}"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
  }

  private UUID newMember(String name) {
    User user = User.internal(name, name + "@example.com", name, "$2a$10$abcdefghijklmnopqrstuv");
    user.setRole(UserRole.MEMBER);
    user.setSlug(name);
    return users.save(user).getId();
  }
}
