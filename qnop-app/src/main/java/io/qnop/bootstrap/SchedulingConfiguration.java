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
package io.qnop.bootstrap;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Distributed locking for the {@code @Scheduled} cleanup sweeps (issue #52, ADR-0029).
 *
 * <p>Plain Spring {@code @Scheduled} fires per instance with no coordination, so in a
 * multi-instance deployment every node would run each sweep concurrently. ShedLock fronts each
 * {@code @SchedulerLock}-annotated sweep with a row in the {@code shedlock} table (migration 0006),
 * so the job runs at most once per tick across all instances — backed by the existing PostgreSQL,
 * with no Redis (ADR-0013). {@code usingDbTime()} makes the database clock authoritative, avoiding
 * skew between application nodes.
 *
 * <p>{@code PROXY_METHOD} intercept mode wraps the annotated bean methods directly (rather than the
 * scheduler), so the lock also applies to any direct invocation — keeping the behaviour uniform.
 * {@code @EnableScheduling} itself lives on {@link QnopApplication}.
 */
@Configuration
@EnableSchedulerLock(
    defaultLockAtMostFor = "PT5M",
    interceptMode = EnableSchedulerLock.InterceptMode.PROXY_METHOD)
public class SchedulingConfiguration {

  @Bean
  LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            .usingDbTime()
            .build());
  }
}
