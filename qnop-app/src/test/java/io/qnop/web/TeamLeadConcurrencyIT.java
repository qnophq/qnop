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
package io.qnop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.jayway.jsonpath.JsonPath;
import io.qnop.entity.Team;
import io.qnop.entity.TeamMembership;
import io.qnop.entity.TeamRole;
import io.qnop.repository.TeamMembershipRepository;
import io.qnop.repository.TeamRepository;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The last-lead invariant under concurrency (issue #470). A team has exactly two co-leads; one lead
 * removes the other while that other lead concurrently demotes the first — two mutations on two
 * *different* membership rows that would each read a lead count of 2 and both commit under READ
 * COMMITTED, leaving the team with zero leads. {@code TeamService} takes a pessimistic write lock
 * on the team ({@code TeamRepository.findByIdForUpdate}) before the last-lead count, so the two
 * requests serialize: exactly one commits (2xx) and the other re-reads the fresh count and is
 * denied with {@code 409 LAST_LEAD}. The observable guarantee — one winner, one conflict, and a
 * team that still has a lead — is what the guard promises.
 *
 * <p>The base class is deliberately not {@code @Transactional}, so each request commits on its own
 * connection and the winner's commit is visible to the loser's {@code SELECT … FOR UPDATE}.
 * Requires Docker (Testcontainers Postgres).
 */
class TeamLeadConcurrencyIT extends SeededIntegrationTest {

  private static final int WORKERS = 2;

  @Autowired private TeamRepository teams;
  @Autowired private TeamMembershipRepository memberships;

  @Test
  void competingLastLeadMutationsKeepAtLeastOneLead() throws Exception {
    UUID teamId = teams.save(Team.create("Lead race", null)).getId();
    memberships.save(TeamMembership.of(teamId, MEMBER_ID, TeamRole.LEAD));
    memberships.save(TeamMembership.of(teamId, MEMBER2_ID, TeamRole.LEAD));

    // Worker 0 (lead MEMBER_ID) removes the co-lead MEMBER2_ID; worker 1 (lead
    // MEMBER2_ID) concurrently demotes MEMBER_ID — the classic two-row race.
    ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
    CyclicBarrier startLine = new CyclicBarrier(WORKERS);
    try {
      List<Future<MvcResult>> futures = new ArrayList<>();
      futures.add(
          pool.submit(
              () -> {
                startLine.await(5, TimeUnit.SECONDS);
                return mockMvc
                    .perform(
                        delete("/api/v1/teams/" + teamId + "/members/" + MEMBER2_ID)
                            .header("Authorization", "Bearer " + token(MEMBER_ID)))
                    .andReturn();
              }));
      futures.add(
          pool.submit(
              () -> {
                startLine.await(5, TimeUnit.SECONDS);
                return mockMvc
                    .perform(
                        patch("/api/v1/teams/" + teamId + "/members/" + MEMBER_ID)
                            .header("Authorization", "Bearer " + token(MEMBER2_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamRole\":\"MEMBER\"}"))
                    .andReturn();
              }));

      int successCount = 0;
      int conflictCount = 0;
      for (Future<MvcResult> future : futures) {
        MvcResult result = future.get(15, TimeUnit.SECONDS);
        int status = result.getResponse().getStatus();
        if (status == 200 || status == 204) {
          successCount++;
        } else if (status == 409) {
          conflictCount++;
          assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.code"))
              .isEqualTo("LAST_LEAD");
        } else {
          throw new AssertionError("Unexpected status " + status);
        }
      }

      assertThat(successCount).isEqualTo(1);
      assertThat(conflictCount).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }

    // The invariant held: the team still has exactly one lead, never zero.
    assertThat(memberships.countByTeamIdAndTeamRole(teamId, TeamRole.LEAD)).isEqualTo(1);
  }
}
