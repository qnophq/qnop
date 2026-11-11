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
package io.qnop.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.qnop.entity.UserRole;
import io.qnop.repository.RefreshTokenRepository;
import io.qnop.repository.UserRepository;
import io.qnop.service.AdminUserConflictException;
import io.qnop.service.AdminUserService;
import io.qnop.service.RefreshTokenService;
import io.qnop.service.RefreshTokenService.RotationResult;
import io.qnop.testsupport.SeededIntegrationTest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Session and token lifecycle under change and concurrency (issues #901, #902, #920): a role change
 * or deletion ends the user's existing access tokens at once, one refresh token rotates into
 * exactly one successor even when presented twice concurrently, and concurrent removals of the two
 * remaining admins leave one admin. Requires Docker (Testcontainers).
 *
 * <p>The concurrency cases hold the first transaction open on purpose while the second runs: with
 * the row locks in place the second waits and then sees the committed state; without them it would
 * act on the stale state and succeed alongside the first.
 */
class SessionLifecycleIT extends SeededIntegrationTest {

  private static final String USERS = "/api/v1/admin/users";
  private static final String ME = "/api/v1/users/me";
  private static final long LOCK_WAIT_TIMEOUT_MS = 10_000;

  @Autowired private AdminUserService adminUsers;
  @Autowired private RefreshTokenService refreshTokens;
  @Autowired private RefreshTokenRepository refreshTokenRows;
  @Autowired private UserRepository users;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;

  /** Dedicated threads, so neither operation queues behind unrelated work on a shared pool. */
  private ExecutorService pool;

  @BeforeEach
  void startPool() {
    pool = Executors.newFixedThreadPool(2);
  }

  @AfterEach
  void stopPool() {
    pool.shutdownNow();
  }

  @Test
  @DisplayName("a role change rejects the user's existing access token immediately (#901)")
  void roleChangeEndsExistingAccessTokens() throws Exception {
    String auditorToken = token(AUDITOR_ID);
    mockMvc
        .perform(get(ME).header("Authorization", "Bearer " + auditorToken))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            patch(USERS + "/" + AUDITOR_ID)
                .header("Authorization", "Bearer " + token(ADMIN_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"MEMBER\"}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(get(ME).header("Authorization", "Bearer " + auditorToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("deleting a user rejects their existing access token immediately (#901)")
  void deletionEndsExistingAccessTokens() throws Exception {
    String memberToken = token(MEMBER2_ID);
    mockMvc
        .perform(get(ME).header("Authorization", "Bearer " + memberToken))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            delete(USERS + "/" + MEMBER2_ID).header("Authorization", "Bearer " + token(ADMIN_ID)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get(ME).header("Authorization", "Bearer " + memberToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("concurrent rotation of one refresh token yields exactly one successor (#902)")
  void concurrentRotationYieldsOneSuccessor() throws Exception {
    String presented = refreshCookie(login("member", SEED_PASSWORD)).getValue();
    long rowsBefore = refreshTokenRows.count();

    HeldTransaction<RotationResult> first = holdOpen(() -> refreshTokens.rotate(presented));
    CompletableFuture<RotationResult> second =
        CompletableFuture.supplyAsync(() -> refreshTokens.rotate(presented), pool);

    assertWaitsForTheFirst(second);
    RotationResult firstResult = first.commit();
    RotationResult secondResult = second.get(10, TimeUnit.SECONDS);

    assertThat(firstResult).isInstanceOf(RotationResult.Success.class);
    assertThat(secondResult).isInstanceOf(RotationResult.Reused.class);
    assertThat(refreshTokenRows.count()).isEqualTo(rowsBefore + 1);
    // Reuse revoked the family, the one successor included.
    String successor = ((RotationResult.Success) firstResult).token().plaintext();
    assertThat(refreshTokens.rotate(successor)).isInstanceOf(RotationResult.Reused.class);
  }

  @Test
  @DisplayName("concurrent demotions of the last two admins leave one enabled admin (#920)")
  void concurrentDemotionsLeaveOneAdmin() throws Exception {
    HeldTransaction<Object> first =
        holdOpen(() -> adminUsers.update(ADMIN2_ID, null, "MEMBER", null, ADMIN_ID));
    CompletableFuture<Object> second =
        CompletableFuture.supplyAsync(
            () -> adminUsers.update(ADMIN_ID, null, "MEMBER", null, ADMIN2_ID), pool);

    assertWaitsForTheFirst(second);
    first.commit();

    assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isInstanceOf(AdminUserConflictException.class)
        .extracting("code")
        .isEqualTo("LAST_ADMIN");
    assertThat(users.countByRoleAndEnabledTrue(UserRole.ADMIN)).isEqualTo(1);
  }

  @Test
  @DisplayName("a concurrent disable and delete of the last two admins leave one admin (#920)")
  void concurrentDisableAndDeleteLeaveOneAdmin() throws Exception {
    HeldTransaction<Object> first =
        holdOpen(() -> adminUsers.update(ADMIN2_ID, null, null, false, ADMIN_ID));
    CompletableFuture<Object> second =
        CompletableFuture.supplyAsync(
            () -> {
              adminUsers.delete(ADMIN_ID, ADMIN2_ID);
              return null;
            },
            pool);

    assertWaitsForTheFirst(second);
    first.commit();

    assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isInstanceOf(AdminUserConflictException.class);
    assertThat(users.countByRoleAndEnabledTrue(UserRole.ADMIN)).isEqualTo(1);
  }

  @Test
  @DisplayName("a revocation racing an in-flight rotation also ends the new successor (#902)")
  void revocationDuringRotationEndsTheSuccessor() throws Exception {
    String presented = refreshCookie(login("member", SEED_PASSWORD)).getValue();

    HeldTransaction<RotationResult> rotation = holdOpen(() -> refreshTokens.rotate(presented));
    CompletableFuture<Object> roleChange =
        CompletableFuture.supplyAsync(
            () -> adminUsers.update(MEMBER_ID, null, "AUDITOR", null, ADMIN_ID), pool);

    assertWaitsForTheFirst(roleChange);
    RotationResult rotated = rotation.commit();
    roleChange.get(10, TimeUnit.SECONDS);

    assertThat(rotated).isInstanceOf(RotationResult.Success.class);
    String successor = ((RotationResult.Success) rotated).token().plaintext();
    assertThat(refreshTokens.rotate(successor)).isNotInstanceOf(RotationResult.Success.class);
  }

  /**
   * Proves the second operation is blocked on the first one's row locks: polls {@code
   * pg_stat_activity} until another backend waits on a lock, failing if the second operation
   * finishes first (it did not wait) or nothing ever waits.
   */
  private void assertWaitsForTheFirst(CompletableFuture<?> second) throws InterruptedException {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    long deadline = System.currentTimeMillis() + LOCK_WAIT_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      assertThat(second.isDone())
          .as("the second operation must wait for the first transaction, not finish before it")
          .isFalse();
      Integer waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                  + " AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()",
              Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(25);
    }
    throw new AssertionError("the second operation never waited on a row lock");
  }

  /**
   * Runs {@code work} in its own transaction on another thread and keeps that transaction open (row
   * locks held) until {@link HeldTransaction#commit()}.
   */
  private <T> HeldTransaction<T> holdOpen(Supplier<T> work) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    CompletableFuture<T> result =
        CompletableFuture.supplyAsync(
            () ->
                tx.execute(
                    status -> {
                      T value = work.get();
                      done.countDown();
                      awaitQuietly(release);
                      return value;
                    }),
            pool);
    if (!done.await(10, TimeUnit.SECONDS)) {
      result.get(1, TimeUnit.SECONDS); // surfaces the failure of the first operation
      throw new AssertionError("the first operation did not finish");
    }
    return new HeldTransaction<>(release, result);
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private record HeldTransaction<T>(CountDownLatch release, CompletableFuture<T> result) {
    T commit() throws Exception {
      release.countDown();
      return result.get(10, TimeUnit.SECONDS);
    }
  }
}
