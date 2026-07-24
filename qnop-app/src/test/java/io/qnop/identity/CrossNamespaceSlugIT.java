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
package io.qnop.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.qnop.bootstrap.AbstractIntegrationTest;
import io.qnop.entity.Team;
import io.qnop.entity.User;
import io.qnop.repository.TeamRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.TeamSlugService;
import io.qnop.service.UserSlugService;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileCopyUtils;

/**
 * Cross-namespace slug uniqueness (issue #595, ADR-0048) against a real PostgreSQL: allocation
 * yields to the OTHER namespace's slugs, the advisory lock serializes concurrent cross-namespace
 * allocations, and the 0024 migration re-slugs pre-existing collisions (teams yield). Tests manage
 * their transactions explicitly ({@link TransactionTemplate}) because the lock protocol is
 * transaction-scoped; committed leftovers are wiped in {@link #cleanUp()}.
 */
class CrossNamespaceSlugIT extends AbstractIntegrationTest {

  private static final String MIGRATION_SQL =
      "db/changelog/migrations/0024-cross-namespace-slug-collisions.sql";

  @Autowired UserSlugService userSlugs;
  @Autowired TeamSlugService teamSlugs;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;

  @AfterEach
  void cleanUp() {
    jdbc.update("DELETE FROM team WHERE slug LIKE 'clash-co%' OR slug LIKE 'collide%'");
    jdbc.update("DELETE FROM qnop_user WHERE slug LIKE 'clash-co%' OR slug LIKE 'collide%'");
  }

  @Test
  @DisplayName("a user allocation yields to an existing team slug")
  void userAllocationYieldsToTeamSlug() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    String allocated =
        tx.execute(
            status -> {
              Team team = Team.create("Design Crew", null);
              team.setSlug(teamSlugs.allocate("Design Crew"));
              teams.saveAndFlush(team);
              assertThat(team.getSlug()).isEqualTo("design-crew");

              String slug = userSlugs.allocate("Design Crew");
              status.setRollbackOnly();
              return slug;
            });

    assertThat(allocated).isEqualTo("design-crew-2");
  }

  @Test
  @DisplayName("a team allocation yields to an existing user slug")
  void teamAllocationYieldsToUserSlug() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    String allocated =
        tx.execute(
            status -> {
              User user = User.external("Design Crew", "design-crew@example.com");
              user.setSlug(userSlugs.allocate("Design Crew"));
              users.saveAndFlush(user);
              assertThat(user.getSlug()).isEqualTo("design-crew");

              String slug = teamSlugs.allocate("Design Crew");
              status.setRollbackOnly();
              return slug;
            });

    assertThat(allocated).isEqualTo("design-crew-2");
  }

  @Test
  @DisplayName("concurrent cross-namespace allocations of the same name never duplicate")
  void concurrentCrossNamespaceAllocationsNeverDuplicate() throws Exception {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch teamAllocated = new CountDownLatch(1);
      CountDownLatch userStarted = new CountDownLatch(1);

      // A allocates the team slug and then HOLDS its transaction open — the advisory lock on
      // "clash-co" stays held, so B must queue behind it instead of racing the uncommitted row.
      Future<String> teamSlug =
          pool.submit(
              () ->
                  tx.execute(
                      status -> {
                        Team team = Team.create("Clash Co", null);
                        team.setSlug(teamSlugs.allocate("Clash Co"));
                        teams.saveAndFlush(team);
                        teamAllocated.countDown();
                        try {
                          userStarted.await(5, TimeUnit.SECONDS);
                          // Give B time to reach the advisory lock. Correctness does NOT depend
                          // on this window: if B arrives later it simply sees the committed row.
                          Thread.sleep(300);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return team.getSlug();
                      }));

      assertThat(teamAllocated.await(10, TimeUnit.SECONDS)).isTrue();

      Future<String> userSlug =
          pool.submit(
              () ->
                  tx.execute(
                      status -> {
                        userStarted.countDown();
                        String slug = userSlugs.allocate("Clash Co");
                        status.setRollbackOnly();
                        return slug;
                      }));

      assertThat(teamSlug.get(10, TimeUnit.SECONDS)).isEqualTo("clash-co");
      assertThat(userSlug.get(10, TimeUnit.SECONDS)).isEqualTo("clash-co-2");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("the 0024 migration re-slugs a colliding team and leaves the user untouched")
  void migrationReslugsCollidingTeam() throws IOException {
    // Manufacture the pre-rule state the migration exists for: identical slugs in both tables.
    jdbc.update(
        "INSERT INTO qnop_user (id, display_name, slug, email, source, enabled,"
            + " password_change_required, role, created_at, updated_at, version)"
            + " VALUES (?, 'Collide', 'collide', 'collide@example.com', 'EXTERNAL', true, false,"
            + " 'MEMBER', now(), now(), 0)",
        UUID.randomUUID());
    jdbc.update(
        "INSERT INTO team (id, name, slug, enabled, created_at, updated_at, version)"
            + " VALUES (?, 'Collide', 'collide', true, now(), now(), 0)",
        UUID.randomUUID());

    jdbc.execute(migrationSql());

    assertThat(jdbc.queryForObject("SELECT slug FROM team WHERE name = 'Collide'", String.class))
        .isEqualTo("collide-2");
    assertThat(
            jdbc.queryForObject(
                "SELECT slug FROM qnop_user WHERE display_name = 'Collide'", String.class))
        .isEqualTo("collide");
  }

  /** The exact statement the 0024 changeset runs — loaded from the shared sqlFile resource. */
  private static String migrationSql() throws IOException {
    return FileCopyUtils.copyToString(
        new InputStreamReader(
            new ClassPathResource(MIGRATION_SQL).getInputStream(), StandardCharsets.UTF_8));
  }
}
